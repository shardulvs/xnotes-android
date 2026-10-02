// The JNI surface over the vendored PDFium (see pdfium.cmake and THIRD_PARTY).
// PDFium is not thread-safe, so all but nativeRevision and nativeLoadedPages run on PdfiumThread.
#include <android/bitmap.h>
#include <fcntl.h>
#include <jni.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <climits>
#include <cmath>
#include <cstring>
#include <set>
#include <string>
#include <utility>
#include <vector>

#include "public/fpdf_doc.h"
#include "public/fpdf_edit.h"
#include "public/fpdf_formfill.h"
#include "public/fpdf_progressive.h"
#include "public/fpdf_text.h"
#include "public/fpdfview.h"

namespace {

// Pages kept loaded per document, so render, links and text parse a page once.
constexpr size_t kPageCache = 4;

// Loaded pages across all documents, for the debug HUD (read off the PDFium thread).
std::atomic<int> g_loaded_pages{0};

// Where a walk of the outline stands between the slices it is read in.
struct OutlineWalk {
    // The next bookmark at each depth still open, deepest last.
    std::vector<std::pair<FPDF_BOOKMARK, int>> stack;
    std::set<FPDF_BOOKMARK> seen;
    size_t read = 0;
};

// An open document, the file it reads and its most recently used pages, newest last.
struct Doc {
    int fd = -1;
    unsigned long size = 0;
    FPDF_DOCUMENT pdf = nullptr;
    // Only for documents with a form; PDFium keeps a pointer to form_info.
    FPDF_FORMFILLINFO form_info = {};
    FPDF_FORMHANDLE form = nullptr;
    std::vector<std::pair<int, FPDF_PAGE>> pages;
    OutlineWalk outline;
};

Doc* FromHandle(jlong handle) {
    return reinterpret_cast<Doc*>(handle);
}

// FPDF_FILEACCESS reader. Positioned reads need no shared file offset.
int ReadBlock(void* param, unsigned long pos, unsigned char* buf, unsigned long size) {
    const int fd = static_cast<Doc*>(param)->fd;
    while (size > 0) {
        const ssize_t n = pread64(fd, buf, size, static_cast<off64_t>(pos));
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return 0;
        buf += n;
        pos += n;
        size -= n;
    }
    return 1;
}

// Loads the document from doc's file; null when it does not parse.
FPDF_DOCUMENT Load(Doc* doc) {
    FPDF_FILEACCESS access = {};
    access.m_FileLen = doc->size;
    access.m_GetBlock = ReadBlock;
    access.m_Param = doc;
    return FPDF_LoadCustomDocument(&access, nullptr);
}

// Form fields are widget annotations, which only FPDF_FFLDraw paints.
void InitForm(Doc* doc) {
    if (FPDF_GetFormType(doc->pdf) == FORMTYPE_NONE) return;
    doc->form_info.version = 1;
    doc->form = FPDFDOC_InitFormFillEnvironment(doc->pdf, &doc->form_info);
}

// Opens path into doc, returning an FPDF_ERR_* code.
jint Open(Doc* doc, const char* path) {
    doc->fd = open(path, O_RDONLY | O_CLOEXEC);
    struct stat64 st;
    if (doc->fd < 0 || fstat64(doc->fd, &st) != 0) return FPDF_ERR_FILE;
    // FPDF_FILEACCESS addresses the file with an unsigned long, 32 bits on armv7.
    if (static_cast<unsigned long long>(st.st_size) > ULONG_MAX) return FPDF_ERR_FILE;
    doc->size = static_cast<unsigned long>(st.st_size);
    doc->pdf = Load(doc);
    if (!doc->pdf) return static_cast<jint>(FPDF_GetLastError());
    InitForm(doc);
    return FPDF_ERR_SUCCESS;
}

void ClosePage(Doc* doc, FPDF_PAGE page) {
    if (doc->form) FORM_OnBeforeClosePage(page, doc->form);
    FPDF_ClosePage(page);
    --g_loaded_pages;
}

// Page index, loaded (its content parsed) at most once while it stays in the cache.
FPDF_PAGE GetPage(Doc* doc, int index) {
    auto& pages = doc->pages;
    for (size_t i = 0; i < pages.size(); ++i) {
        if (pages[i].first != index) continue;
        const auto hit = pages[i];
        pages.erase(pages.begin() + i);
        pages.push_back(hit);
        return hit.second;
    }
    FPDF_PAGE page = FPDF_LoadPage(doc->pdf, index);
    if (!page) return nullptr;
    ++g_loaded_pages;
    if (doc->form) FORM_OnAfterLoadPage(page, doc->form);
    if (pages.size() == kPageCache) {
        ClosePage(doc, pages.front().second);
        pages.erase(pages.begin());
    }
    pages.emplace_back(index, page);
    return page;
}

// Page index if the cache holds it, without making it the most recent; else null.
FPDF_PAGE CachedPage(Doc* doc, int index) {
    for (const auto& p : doc->pages) {
        if (p.first == index) return p.second;
    }
    return nullptr;
}

// Maps page user space to display points: top-left origin, /Rotate applied.
struct DisplayMap {
    double a = 1, b = 0, c = 0, d = 1, e = 0, f = 0;

    void Apply(double x, double y, float* ox, float* oy) const {
        *ox = static_cast<float>(a * x + c * y + e);
        *oy = static_cast<float>(b * x + d * y + f);
    }
};

// The map PDFium renders with, sampled from FPDF_PageToDevice at 1/1024 pt.
DisplayMap MapFor(FPDF_PAGE page) {
    constexpr double kScale = 1024;
    DisplayMap m;
    FS_RECTF box;
    const double bw = FPDF_GetPageBoundingBox(page, &box) ? box.right - box.left : 0;
    const double bh = box.top - box.bottom;
    const double w = FPDF_GetPageWidthF(page) * kScale;
    const double h = FPDF_GetPageHeightF(page) * kScale;
    if (bw <= 0 || bh <= 0 || w < 1 || h < 1) return m;
    const int sx = static_cast<int>(std::lround(w));
    const int sy = static_cast<int>(std::lround(h));
    int x0, y0, x1, y1, x2, y2;
    FPDF_PageToDevice(page, 0, 0, sx, sy, 0, box.left, box.bottom, &x0, &y0);
    FPDF_PageToDevice(page, 0, 0, sx, sy, 0, box.right, box.bottom, &x1, &y1);
    FPDF_PageToDevice(page, 0, 0, sx, sy, 0, box.left, box.top, &x2, &y2);
    m.a = (x1 - x0) / (bw * kScale);
    m.b = (y1 - y0) / (bw * kScale);
    m.c = (x2 - x0) / (bh * kScale);
    m.d = (y2 - y0) / (bh * kScale);
    m.e = x0 / kScale - m.a * box.left - m.c * box.bottom;
    m.f = y0 / kScale - m.b * box.left - m.d * box.bottom;
    return m;
}

// Appends the display box (l, t, r, b) around the user-space points xs, ys.
void AddBox(const DisplayMap& map, const float* xs, const float* ys, int n, std::vector<float>* out) {
    float l = 0, t = 0, r = 0, b = 0;
    for (int i = 0; i < n; ++i) {
        float x, y;
        map.Apply(xs[i], ys[i], &x, &y);
        l = i ? std::min(l, x) : x;
        t = i ? std::min(t, y) : y;
        r = i ? std::max(r, x) : x;
        b = i ? std::max(b, y) : y;
    }
    out->insert(out->end(), {l, t, r, b});
}

// m, then n, in PDF's row-vector order.
FS_MATRIX Concat(const FS_MATRIX& m, const FS_MATRIX& n) {
    return {m.a * n.a + m.b * n.c,       m.a * n.b + m.b * n.d,
            m.c * n.a + m.d * n.c,       m.c * n.b + m.d * n.d,
            m.e * n.a + m.f * n.c + n.e, m.e * n.b + m.f * n.d + n.f};
}

// Appends obj's display box (l, t, r, b) when it is an image, or those of the images inside it
// when it is a form XObject; to_page maps the space obj sits in to page space. PDFium parses
// forms at most 40 deep, which bounds the recursion.
void AddImages(FPDF_PAGEOBJECT obj, const FS_MATRIX& to_page, const DisplayMap& map, float w,
               float h, std::vector<float>* out) {
    FS_MATRIX m;
    if (!FPDFPageObj_GetMatrix(obj, &m)) return;
    const FS_MATRIX u = Concat(m, to_page);
    switch (FPDFPageObj_GetType(obj)) {
        case FPDF_PAGEOBJ_IMAGE: {
            // u maps the unit square to page space; the box is clipped to the page.
            const float xs[] = {u.e, u.a + u.e, u.c + u.e, u.a + u.c + u.e};
            const float ys[] = {u.f, u.b + u.f, u.d + u.f, u.b + u.d + u.f};
            AddBox(map, xs, ys, 4, out);
            float* box = out->data() + out->size() - 4;
            box[0] = std::max(box[0], 0.f);
            box[1] = std::max(box[1], 0.f);
            box[2] = std::min(box[2], w);
            box[3] = std::min(box[3], h);
            if (box[2] <= box[0] || box[3] <= box[1]) out->resize(out->size() - 4);
            break;
        }
        case FPDF_PAGEOBJ_FORM: {
            const int count = FPDFFormObj_CountObjects(obj);
            for (int i = 0; i < count; ++i) AddImages(FPDFFormObj_GetObject(obj, i), u, map, w, h, out);
            break;
        }
    }
}

// PageText's flag bits.
constexpr jbyte kTextGenerated = 1;
constexpr jbyte kTextHyphen = 2;
constexpr jbyte kTextUnmapped = 4;

// The codepoint PageText keeps for what FPDFText_GetUnicode gave: 0 for the markers PDFium leaves
// out of its own text, U+FFFD for a value no character has.
jint Codepoint(unsigned int u, bool hyphen) {
    // PDFium swaps a hyphen that ends a line, a hyphen-minus or soft hyphen, for U+0002.
    if (hyphen) return '-';
    switch (u) {
        case 0x2: case 0x3: case 0x93: case 0x94: case 0x96: case 0x97: case 0x98: case 0xFFFE:
            return 0;
        default:
            break;
    }
    if (u > 0x10FFFF || (u >= 0xD800 && u <= 0xDFFF)) return 0xFFFD;
    return static_cast<jint>(u);
}

// The text page of page. PDFium groups and orders a line's words in display space, reversing them
// on a page turned upside down, so it reads the page unturned; the boxes it gives are in page space.
FPDF_TEXTPAGE LoadTextPage(FPDF_PAGE page) {
    const int rotation = FPDFPage_GetRotation(page);
    if (rotation > 0) FPDFPage_SetRotation(page, 0);
    FPDF_TEXTPAGE text = FPDFText_LoadPage(page);
    if (rotation > 0) FPDFPage_SetRotation(page, rotation);
    return text;
}

// Reads a text page's characters as PageText keeps them, in order from the first.
class CharReader {
  public:
    explicit CharReader(FPDF_TEXTPAGE text) : text_(text), count_(std::max(FPDFText_CountChars(text), 0)) {}

    int count() const { return count_; }

    // Character i's codepoint and kText* bits, for i = 0, 1, ... in turn.
    void Read(int i, jint* codepoint, jbyte* flags) {
        const bool generated = FPDFText_IsGenerated(text_, i) == 1;
        const bool hyphen = FPDFText_IsHyphen(text_, i) == 1;
        // A /ToUnicode entry beyond the BMP comes as two characters, its UTF-16 halves; the
        // first gets the whole codepoint, the second none.
        unsigned int u = low_taken_ ? 0 : FPDFText_GetUnicode(text_, i);
        low_taken_ = false;
        if (u >= 0xD800 && u <= 0xDBFF && i + 1 < count_) {
            const unsigned int low = FPDFText_GetUnicode(text_, i + 1);
            if (low >= 0xDC00 && low <= 0xDFFF) {
                u = 0x10000 + ((u - 0xD800) << 10) + (low - 0xDC00);
                low_taken_ = true;
            }
        }
        *codepoint = Codepoint(u, hyphen);
        *flags = static_cast<jbyte>((generated ? kTextGenerated : 0) | (hyphen ? kTextHyphen : 0) |
                                    (FPDFText_HasUnicodeMapError(text_, i) == 1 ? kTextUnmapped : 0));
    }

  private:
    FPDF_TEXTPAGE text_;
    int count_;
    bool low_taken_ = false;
};

// How far a glyph set with matrix m is turned, in degrees clockwise as displayed, in [0, 360);
// float noise is snapped to the nearest quarter turn.
float DisplayAngle(const DisplayMap& map, const FS_MATRIX& m) {
    const double dx = map.a * m.a + map.c * m.b;
    const double dy = map.b * m.a + map.d * m.b;
    double deg = std::atan2(dy, dx) * (180 / 3.14159265358979323846);
    if (deg < 0) deg += 360;
    const double quarter = std::round(deg / 90) * 90;
    if (std::fabs(deg - quarter) < 0.01) deg = quarter == 360 ? 0 : quarter;
    return deg == 0 ? 0.f : static_cast<float>(deg);
}

// The page bookmark b opens, from its /Dest or its GoTo action; -1 for none.
int BookmarkPage(FPDF_DOCUMENT pdf, FPDF_BOOKMARK b) {
    FPDF_DEST dest = FPDFBookmark_GetDest(pdf, b);
    if (!dest) {
        FPDF_ACTION action = FPDFBookmark_GetAction(b);
        if (action && FPDFAction_GetType(action) == PDFACTION_GOTO) dest = FPDFAction_GetDest(pdf, action);
    }
    return dest ? FPDFDest_GetDestPageIndex(pdf, dest) : -1;
}

// Pauses a progressive render, for good, once either CancelToken is cancelled.
struct Cancel {
    IFSDK_PAUSE pause = {};
    JNIEnv* env;
    jobject tokens[2];
    jfieldID cancelled;

    Cancel(JNIEnv* e, jobject lifetime, jobject token) : env(e), tokens{lifetime, token} {
        pause.version = 1;
        pause.NeedToPauseNow = [](IFSDK_PAUSE* p) -> FPDF_BOOL {
            return static_cast<Cancel*>(p->user)->IsCancelled();
        };
        pause.user = this;
        cancelled = env->GetFieldID(env->GetObjectClass(lifetime), "isCancelled", "Z");
    }

    bool IsCancelled() const {
        for (jobject t : tokens) {
            if (t && env->GetBooleanField(t, cancelled)) return true;
        }
        return false;
    }
};

}  // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM*, void*) {
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_xnotes_platform_PdfiumNative_nativeRevision(JNIEnv* env, jclass) {
    return env->NewStringUTF(XNOTES_PDFIUM_REVISION);
}

extern "C" JNIEXPORT void JNICALL
Java_com_xnotes_platform_PdfiumNative_nativeInit(JNIEnv*, jclass) {
    FPDF_LIBRARY_CONFIG config = {};
    config.version = 2;
    FPDF_InitLibraryWithConfig(&config);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_xnotes_platform_PdfiumNative_nativeOpen(JNIEnv* env, jclass, jstring jpath,
                                                 jintArray jout) {
    jint out[2] = {FPDF_ERR_FILE, 0};
    auto* doc = new Doc;
    if (const char* path = env->GetStringUTFChars(jpath, nullptr)) {
        out[0] = Open(doc, path);
        env->ReleaseStringUTFChars(jpath, path);
    }
    if (doc->pdf) {
        out[1] = FPDF_GetPageCount(doc->pdf);
    } else {
        if (doc->fd >= 0) close(doc->fd);
        delete doc;
        doc = nullptr;
    }
    env->SetIntArrayRegion(jout, 0, 2, out);
    return reinterpret_cast<jlong>(doc);
}

extern "C" JNIEXPORT void JNICALL
Java_com_xnotes_platform_PdfiumNative_nativeClose(JNIEnv*, jclass, jlong handle) {
    Doc* doc = FromHandle(handle);
    for (const auto& cached : doc->pages) ClosePage(doc, cached.second);
    if (doc->form) FPDFDOC_ExitFormFillEnvironment(doc->form);
    FPDF_CloseDocument(doc->pdf);
    close(doc->fd);
    delete doc;
}

// Frees what the document holds: its loaded pages, and, by loading it afresh, the fonts and the
// glyphs they cached at every size drawn, which grow with each zoom level. Everything loads again
// when next used. Skipped while an outline walk holds bookmarks of the old document.
extern "C" JNIEXPORT void JNICALL
Java_com_xnotes_platform_PdfiumNative_nativeTrim(JNIEnv*, jclass, jlong handle) {
    Doc* doc = FromHandle(handle);
    for (const auto& cached : doc->pages) ClosePage(doc, cached.second);
    doc->pages.clear();
    if (!doc->outline.stack.empty()) return;
    FPDF_DOCUMENT fresh = Load(doc);
    if (!fresh) return;
    if (doc->form) FPDFDOC_ExitFormFillEnvironment(doc->form);
    doc->form = nullptr;
    FPDF_CloseDocument(doc->pdf);
    doc->pdf = fresh;
    InitForm(doc);
}

// Pages loaded across all documents; it reads no PDFium state, so any thread may ask.
extern "C" JNIEXPORT jint JNICALL
Java_com_xnotes_platform_PdfiumNative_nativeLoadedPages(JNIEnv*, jclass) {
    return g_loaded_pages.load();
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_xnotes_platform_PdfiumNative_nativePageSizes(JNIEnv* env, jclass, jlong handle, jint from,
                                                      jint count) {
    FPDF_DOCUMENT pdf = FromHandle(handle)->pdf;
    const int pages = FPDF_GetPageCount(pdf);
    from = from < 0 ? 0 : (from > pages ? pages : from);
    count = count < 0 ? 0 : (count > pages - from ? pages - from : count);
    // FPDF_GetPageSizeByIndexF reads the page dictionary only, never the content stream.
    std::vector<float> sizes(2 * static_cast<size_t>(count));
    for (int i = 0; i < count; ++i) {
        FS_SIZEF size = {};
        if (FPDF_GetPageSizeByIndexF(pdf, from + i, &size)) {
            sizes[2 * i] = size.width;
            sizes[2 * i + 1] = size.height;
        }
    }
    jfloatArray out = env->NewFloatArray(2 * count);
    if (out) env->SetFloatArrayRegion(out, 0, 2 * count, sizes.data());
    return out;
}

// Renders the part of page index at (left, top) in a full_w x full_h raster of the whole page
// into bitmap, on white. False when cancelled or failed.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_xnotes_platform_PdfiumNative_nativeRender(JNIEnv* env, jclass, jlong handle, jint index,
                                                   jobject jbitmap, jint full_w, jint full_h,
                                                   jint left, jint top, jobject lifetime,
                                                   jobject token) {
    Doc* doc = FromHandle(handle);
    Cancel cancel(env, lifetime, token);
    FPDF_PAGE page = cancel.IsCancelled() ? nullptr : GetPage(doc, index);
    AndroidBitmapInfo info;
    void* pixels = nullptr;
    if (!page || AndroidBitmap_getInfo(env, jbitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        AndroidBitmap_lockPixels(env, jbitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return false;
    }
    memset(pixels, 0xFF, static_cast<size_t>(info.stride) * info.height);
    FPDF_BITMAP bitmap = FPDFBitmap_CreateEx(static_cast<int>(info.width),
                                             static_cast<int>(info.height), FPDFBitmap_BGRA,
                                             pixels, static_cast<int>(info.stride));
    int status = FPDF_RenderPageBitmap_Start(bitmap, page, -left, -top, full_w, full_h, 0,
                                             FPDF_ANNOT | FPDF_REVERSE_BYTE_ORDER,
                                             &cancel.pause);
    // The pause only ever asks to stop, so a render left to be continued was cancelled.
    while (status == FPDF_RENDER_TOBECONTINUED && !cancel.IsCancelled()) {
        status = FPDF_RenderPage_Continue(page, &cancel.pause);
    }
    FPDF_RenderPage_Close(page);
    if (status == FPDF_RENDER_DONE && doc->form) {
        // Without FPDF_ANNOT, FFLDraw paints the form fields alone, not the annotations again.
        FPDF_FFLDraw(doc->form, bitmap, page, -left, -top, full_w, full_h, 0,
                     FPDF_REVERSE_BYTE_ORDER);
    }
    FPDFBitmap_Destroy(bitmap);
    AndroidBitmap_unlockPixels(env, jbitmap);
    return status == FPDF_RENDER_DONE;
}

// The display boxes, in points, of the images on page index, those inside form XObjects included.
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_xnotes_platform_PdfiumNative_nativeImageRects(JNIEnv* env, jclass, jlong handle,
                                                       jint index) {
    FPDF_PAGE page = GetPage(FromHandle(handle), index);
    if (!page) return nullptr;
    const DisplayMap map = MapFor(page);
    const float w = FPDF_GetPageWidthF(page);
    const float h = FPDF_GetPageHeightF(page);
    std::vector<float> rects;
    const int count = FPDFPage_CountObjects(page);
    for (int i = 0; i < count; ++i) {
        AddImages(FPDFPage_GetObject(page, i), {1, 0, 0, 1, 0, 0}, map, w, h, &rects);
    }
    jfloatArray out = env->NewFloatArray(static_cast<jsize>(rects.size()));
    if (out) env->SetFloatArrayRegion(out, 0, static_cast<jsize>(rects.size()), rects.data());
    return out;
}

// Page index's links: one display box (l, t, r, b) per quad, or per link without quads, with the
// page it goes to (-1 for none) and its URI bytes (null for none), as {float[], int[], byte[][]}.
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_xnotes_platform_PdfiumNative_nativeLinks(JNIEnv* env, jclass, jlong handle, jint index) {
    Doc* doc = FromHandle(handle);
    FPDF_PAGE page = GetPage(doc, index);
    if (!page) return nullptr;
    const DisplayMap map = MapFor(page);
    std::vector<float> boxes;
    std::vector<jint> dests;
    std::vector<std::string> uris;
    int pos = 0;
    FPDF_LINK link;
    while (FPDFLink_Enumerate(page, &pos, &link)) {
        int dest = -1;
        std::string uri;
        if (FPDF_DEST d = FPDFLink_GetDest(doc->pdf, link)) {
            dest = FPDFDest_GetDestPageIndex(doc->pdf, d);
        } else if (FPDF_ACTION action = FPDFLink_GetAction(link)) {
            const unsigned long type = FPDFAction_GetType(action);
            if (type == PDFACTION_GOTO) {
                if (FPDF_DEST ad = FPDFAction_GetDest(doc->pdf, action)) {
                    dest = FPDFDest_GetDestPageIndex(doc->pdf, ad);
                }
            } else if (type == PDFACTION_URI) {
                // The length includes the terminating NUL.
                const unsigned long n = FPDFAction_GetURIPath(doc->pdf, action, nullptr, 0);
                if (n > 1) {
                    uri.resize(n);
                    FPDFAction_GetURIPath(doc->pdf, action, uri.data(), n);
                    uri.resize(n - 1);
                }
            }
        }
        if (dest < 0 && uri.empty()) continue;
        const size_t before = boxes.size();
        const int quads = FPDFLink_CountQuadPoints(link);
        for (int q = 0; q < quads; ++q) {
            FS_QUADPOINTSF p;
            if (!FPDFLink_GetQuadPoints(link, q, &p)) continue;
            const float xs[] = {p.x1, p.x2, p.x3, p.x4};
            const float ys[] = {p.y1, p.y2, p.y3, p.y4};
            AddBox(map, xs, ys, 4, &boxes);
        }
        FS_RECTF rect;
        if (boxes.size() == before && FPDFLink_GetAnnotRect(link, &rect)) {
            const float xs[] = {rect.left, rect.right};
            const float ys[] = {rect.bottom, rect.top};
            AddBox(map, xs, ys, 2, &boxes);
        }
        for (size_t added = (boxes.size() - before) / 4; added > 0; --added) {
            dests.push_back(dest);
            uris.push_back(uri);
        }
    }
    const auto count = static_cast<jsize>(dests.size());
    jfloatArray jboxes = env->NewFloatArray(4 * count);
    jintArray jdests = env->NewIntArray(count);
    jobjectArray juris = env->NewObjectArray(count, env->FindClass("[B"), nullptr);
    jobjectArray out = env->NewObjectArray(3, env->FindClass("java/lang/Object"), nullptr);
    if (!jboxes || !jdests || !juris || !out) return nullptr;
    env->SetFloatArrayRegion(jboxes, 0, 4 * count, boxes.data());
    env->SetIntArrayRegion(jdests, 0, count, dests.data());
    for (jsize i = 0; i < count; ++i) {
        if (uris[i].empty()) continue;
        const auto len = static_cast<jsize>(uris[i].size());
        jbyteArray bytes = env->NewByteArray(len);
        if (!bytes) return nullptr;
        env->SetByteArrayRegion(bytes, 0, len, reinterpret_cast<const jbyte*>(uris[i].data()));
        env->SetObjectArrayElement(juris, i, bytes);
        env->DeleteLocalRef(bytes);
    }
    env->SetObjectArrayElement(out, 0, jboxes);
    env->SetObjectArrayElement(out, 1, jdests);
    env->SetObjectArrayElement(out, 2, juris);
    return out;
}

// The next slice of the outline in reading order, as {String[] titles, int[] pages, int[] levels},
// read for about budget_ms; empty once the walk has read it all, or max entries. A restart begins
// a new walk. A bookmark met twice ends its chain, as malformed outlines can loop.
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_xnotes_platform_PdfiumNative_nativeOutline(JNIEnv* env, jclass, jlong handle, jint max,
                                                    jint budget_ms, jboolean restart) {
    Doc* doc = FromHandle(handle);
    OutlineWalk& walk = doc->outline;
    if (restart) {
        walk = OutlineWalk();
        walk.stack.emplace_back(FPDFBookmark_GetFirstChild(doc->pdf, nullptr), 0);
    }
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(budget_ms);
    std::vector<std::u16string> titles;
    std::vector<jint> pages;
    std::vector<jint> levels;
    while (!walk.stack.empty() && walk.read < static_cast<size_t>(max) &&
           std::chrono::steady_clock::now() < deadline) {
        auto& [b, level] = walk.stack.back();
        if (!b || !walk.seen.insert(b).second) {
            walk.stack.pop_back();
            continue;
        }
        const FPDF_BOOKMARK at = b;
        const int depth = level;
        b = FPDFBookmark_GetNextSibling(doc->pdf, at);
        // The length is in bytes, the terminating NUL included.
        const unsigned long bytes = FPDFBookmark_GetTitle(at, nullptr, 0);
        std::u16string title(bytes / 2, u'\0');
        if (!title.empty()) {
            FPDFBookmark_GetTitle(at, title.data(), bytes);
            title.pop_back();
        }
        titles.push_back(std::move(title));
        pages.push_back(BookmarkPage(doc->pdf, at));
        levels.push_back(depth);
        ++walk.read;
        if (depth < 64) walk.stack.emplace_back(FPDFBookmark_GetFirstChild(doc->pdf, at), depth + 1);
    }
    const auto count = static_cast<jsize>(titles.size());
    jobjectArray jtitles = env->NewObjectArray(count, env->FindClass("java/lang/String"), nullptr);
    jintArray jpages = env->NewIntArray(count);
    jintArray jlevels = env->NewIntArray(count);
    jobjectArray out = env->NewObjectArray(3, env->FindClass("java/lang/Object"), nullptr);
    if (!jtitles || !jpages || !jlevels || !out) return nullptr;
    for (jsize i = 0; i < count; ++i) {
        const std::u16string& t = titles[i];
        jstring title = env->NewString(reinterpret_cast<const jchar*>(t.data()), static_cast<jsize>(t.size()));
        if (!title) return nullptr;
        env->SetObjectArrayElement(jtitles, i, title);
        env->DeleteLocalRef(title);
    }
    env->SetIntArrayRegion(jpages, 0, count, pages.data());
    env->SetIntArrayRegion(jlevels, 0, count, levels.data());
    env->SetObjectArrayElement(out, 0, jtitles);
    env->SetObjectArrayElement(out, 1, jpages);
    env->SetObjectArrayElement(out, 2, jlevels);
    return out;
}

// Page index's text in PDFium's order as {int[] codepoints, float[] boxes, byte[] flags,
// float[] angles}: per character its loose box (l, t, r, b) in display points, its kText* bits
// and its DisplayAngle; angles is null when no character is turned.
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_xnotes_platform_PdfiumNative_nativePageText(JNIEnv* env, jclass, jlong handle, jint index) {
    FPDF_PAGE page = GetPage(FromHandle(handle), index);
    if (!page) return nullptr;
    FPDF_TEXTPAGE text = LoadTextPage(page);
    if (!text) return nullptr;
    const DisplayMap map = MapFor(page);
    CharReader reader(text);
    const int count = reader.count();
    std::vector<jint> codepoints(count);
    std::vector<float> boxes;
    boxes.reserve(4 * static_cast<size_t>(count));
    std::vector<jbyte> flags(count);
    std::vector<float> angles(count);
    bool turned = false;
    float angle = 0;
    for (int i = 0; i < count; ++i) {
        reader.Read(i, &codepoints[i], &flags[i]);
        FS_RECTF r = {};
        FPDFText_GetLooseCharBox(text, i, &r);
        const float xs[] = {r.left, r.right};
        const float ys[] = {r.bottom, r.top};
        AddBox(map, xs, ys, 2, &boxes);
        // An inferred character has no matrix of its own, so it takes the angle of the one before.
        FS_MATRIX m;
        if (!(flags[i] & kTextGenerated) && FPDFText_GetMatrix(text, i, &m)) angle = DisplayAngle(map, m);
        angles[i] = angle;
        turned |= angle != 0;
    }
    FPDFText_ClosePage(text);
    const auto n = static_cast<jsize>(count);
    jintArray jcodepoints = env->NewIntArray(n);
    jfloatArray jboxes = env->NewFloatArray(4 * n);
    jbyteArray jflags = env->NewByteArray(n);
    jfloatArray jangles = turned ? env->NewFloatArray(n) : nullptr;
    jobjectArray out = env->NewObjectArray(4, env->FindClass("java/lang/Object"), nullptr);
    if (!jcodepoints || !jboxes || !jflags || (turned && !jangles) || !out) return nullptr;
    env->SetIntArrayRegion(jcodepoints, 0, n, codepoints.data());
    env->SetFloatArrayRegion(jboxes, 0, 4 * n, boxes.data());
    env->SetByteArrayRegion(jflags, 0, n, flags.data());
    if (turned) env->SetFloatArrayRegion(jangles, 0, n, angles.data());
    env->SetObjectArrayElement(out, 0, jcodepoints);
    env->SetObjectArrayElement(out, 1, jboxes);
    env->SetObjectArrayElement(out, 2, jflags);
    env->SetObjectArrayElement(out, 3, jangles);
    return out;
}

// Page index's characters as nativePageText reads them, without boxes: {int[] codepoints,
// byte[] flags}. A page the cache lacks is loaded for this read alone, so a search through a book
// never pushes the pages on screen out of it.
// Page index's display map, user space to points as displayed, as {a, b, c, d, e, f}, leaving the
// page cache as it was; null when the page does not load or has no area.
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_xnotes_platform_PdfiumNative_nativePageGeometry(JNIEnv* env, jclass, jlong handle, jint index) {
    Doc* doc = FromHandle(handle);
    FPDF_PAGE page = CachedPage(doc, index);
    const bool own = page == nullptr;
    if (own) page = FPDF_LoadPage(doc->pdf, index);
    if (!page) return nullptr;
    FS_RECTF box;
    const bool ok = FPDF_GetPageBoundingBox(page, &box) && box.right > box.left && box.top > box.bottom;
    const DisplayMap m = MapFor(page);
    if (own) FPDF_ClosePage(page);
    if (!ok) return nullptr;
    const jdouble values[6] = {m.a, m.b, m.c, m.d, m.e, m.f};
    jdoubleArray out = env->NewDoubleArray(6);
    if (!out) return nullptr;
    env->SetDoubleArrayRegion(out, 0, 6, values);
    return out;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_xnotes_platform_PdfiumNative_nativePageChars(JNIEnv* env, jclass, jlong handle, jint index) {
    Doc* doc = FromHandle(handle);
    FPDF_PAGE page = CachedPage(doc, index);
    const bool own = page == nullptr;
    if (own) page = FPDF_LoadPage(doc->pdf, index);
    if (!page) return nullptr;
    FPDF_TEXTPAGE text = LoadTextPage(page);
    std::vector<jint> codepoints;
    std::vector<jbyte> flags;
    if (text) {
        CharReader reader(text);
        codepoints.resize(reader.count());
        flags.resize(reader.count());
        for (int i = 0; i < reader.count(); ++i) reader.Read(i, &codepoints[i], &flags[i]);
        FPDFText_ClosePage(text);
    }
    if (own) FPDF_ClosePage(page);
    if (!text) return nullptr;
    const auto n = static_cast<jsize>(codepoints.size());
    jintArray jcodepoints = env->NewIntArray(n);
    jbyteArray jflags = env->NewByteArray(n);
    jobjectArray out = env->NewObjectArray(2, env->FindClass("java/lang/Object"), nullptr);
    if (!jcodepoints || !jflags || !out) return nullptr;
    env->SetIntArrayRegion(jcodepoints, 0, n, codepoints.data());
    env->SetByteArrayRegion(jflags, 0, n, flags.data());
    env->SetObjectArrayElement(out, 0, jcodepoints);
    env->SetObjectArrayElement(out, 1, jflags);
    return out;
}
