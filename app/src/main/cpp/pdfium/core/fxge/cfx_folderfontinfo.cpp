// Copyright 2016 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#include "core/fxge/cfx_folderfontinfo.h"

#include <array>
#include <iterator>
#include <limits>
#include <utility>

#include "build/build_config.h"
#include "core/fxcrt/byteorder.h"
#include "core/fxcrt/check_op.h"
#include "core/fxcrt/compiler_specific.h"
#include "core/fxcrt/containers/contains.h"
#include "core/fxcrt/fixed_size_data_vector.h"
#include "core/fxcrt/fx_codepage.h"
#include "core/fxcrt/fx_extension.h"
#include "core/fxcrt/fx_folder.h"
#include "core/fxcrt/fx_safe_types.h"
#include "core/fxcrt/fx_system.h"
#include "core/fxcrt/span_io.h"
#include "core/fxge/cfx_fontmapper.h"
#include "core/fxge/fx_font.h"

namespace {

struct FontSubst {
  const char* name_;
  const char* subst_name_;
};

constexpr auto kBase14Substs = std::to_array<const FontSubst>({
    {"Courier", "Courier New"},
    {"Courier-Bold", "Courier New Bold"},
    {"Courier-BoldOblique", "Courier New Bold Italic"},
    {"Courier-Oblique", "Courier New Italic"},
    {"Helvetica", "Arial"},
    {"Helvetica-Bold", "Arial Bold"},
    {"Helvetica-BoldOblique", "Arial Bold Italic"},
    {"Helvetica-Oblique", "Arial Italic"},
    {"Times-Roman", "Times New Roman"},
    {"Times-Bold", "Times New Roman Bold"},
    {"Times-BoldItalic", "Times New Roman Bold Italic"},
    {"Times-Italic", "Times New Roman Italic"},
});

// Used with std::unique_ptr to automatically call fclose().
struct FxFileCloser {
  inline void operator()(FILE* h) const {
    if (h) {
      fclose(h);
    }
  }
};

std::optional<FixedSizeDataVector<uint8_t>> DataVectorAtLocation(
    FILE* file,
    FX_FILESIZE filesize,
    const FontTableLocation& loc) {
  FX_SAFE_FILESIZE safe_end = loc.offset;
  safe_end += loc.size;
  if (!safe_end.IsValid() || safe_end.ValueOrDie() > filesize) {
    return std::nullopt;
  }
  auto result_data = FixedSizeDataVector<uint8_t>::Uninit(loc.size);
  if (fseek(file, loc.offset, SEEK_SET) < 0 ||
      fxcrt::spanread(result_data.span(), file).size() != loc.size) {
    return std::nullopt;
  }
  return std::move(result_data);
}

}  // namespace

// static
bool CFX_FolderFontInfo::FindFamilyNameMatch(
    ByteStringView family_name,
    const ByteString& installed_font_name) {
  std::optional<size_t> result = installed_font_name.Find(family_name, 0);
  if (!result.has_value()) {
    return false;
  }

  size_t next_index = result.value() + family_name.GetLength();
  // Rule out the case that |family_name| is a substring of
  // |installed_font_name| but their family names are actually different words.
  // For example: "Univers" and "Universal" are not a match because they have
  // different family names, but "Univers" and "Univers Bold" are a match.
  if (installed_font_name.IsValidIndex(next_index) &&
      FXSYS_IsLowerASCII(installed_font_name[next_index])) {
    return false;
  }

  return true;
}

// static
ByteString CFX_FolderFontInfo::ReadStringFromFile(FILE* pFile, uint32_t size) {
  ByteString result;
  {
    // Span's lifetime must end before ReleaseBuffer() below.
    pdfium::span<char> buffer = result.GetBuffer(size);

    if (fxcrt::spanread(buffer.first(size), pFile).size() != size) {
      return ByteString();
    }
  }
  result.ReleaseBuffer(size);
  return result;
}

CFX_FolderFontInfo::CFX_FolderFontInfo() = default;

CFX_FolderFontInfo::~CFX_FolderFontInfo() = default;

void CFX_FolderFontInfo::AddPath(const ByteString& path) {
  path_list_.push_back(path);
}

void CFX_FolderFontInfo::EnumFontList(CFX_FontMapper* pMapper) {
  for (const auto& path : path_list_) {
    ScanPath(pMapper, path);
  }
}

void CFX_FolderFontInfo::ScanPath(CFX_FontMapper* mapper,
                                  const ByteString& path) {
  std::unique_ptr<FX_Folder> handle = FX_Folder::OpenFolder(path);
  if (!handle) {
    return;
  }

  ByteString filename;
  bool bFolder;
  while (handle->GetNextFile(&filename, &bFolder)) {
    if (bFolder) {
      if (filename == "." || filename == "..") {
        continue;
      }
    } else {
      ByteString ext = filename.Last(4);
      ext.MakeLower();
      if (ext != ".ttf" && ext != ".ttc" && ext != ".otf") {
        continue;
      }
    }

    ByteString fullpath = path;
#if BUILDFLAG(IS_WIN)
    fullpath += "\\";
#else
    fullpath += "/";
#endif

    fullpath += filename;
    bFolder ? ScanPath(mapper, fullpath) : ScanFile(mapper, fullpath);
  }
}

void CFX_FolderFontInfo::ScanFile(CFX_FontMapper* mapper,
                                  const ByteString& path) {
  std::unique_ptr<FILE, FxFileCloser> pFile(fopen(path.c_str(), "rb"));
  if (!pFile) {
    return;
  }

  fseek(pFile.get(), 0, SEEK_END);
  FX_FILESIZE filesize = ftell(pFile.get());
  fseek(pFile.get(), 0, SEEK_SET);

  uint8_t buffer[12];
  if (fxcrt::spanread(buffer, pFile.get()).size() != sizeof(buffer)) {
    return;
  }

  uint32_t magic = fxcrt::GetUInt32MSBFirst(pdfium::span(buffer).first<4u>());
  if (magic != SystemFontInfoIface::kTableTTCF) {
    ReportFace(mapper, path, pFile.get(), filesize, 0);
    return;
  }

  uint32_t nFaces =
      fxcrt::GetUInt32MSBFirst(pdfium::span(buffer).subspan<8u, 4u>());
  FX_SAFE_SIZE_T safe_face_bytes = nFaces;
  safe_face_bytes *= 4;
  if (!safe_face_bytes.IsValid()) {
    return;
  }

  auto offsets =
      FixedSizeDataVector<uint8_t>::Uninit(safe_face_bytes.ValueOrDie());
  if (fxcrt::spanread(offsets.span(), pFile.get()).size() != offsets.size()) {
    return;
  }

  for (uint32_t i = 0; i < nFaces; i++) {
    ReportFace(mapper, path, pFile.get(), filesize,
               fxcrt::GetUInt32MSBFirst(offsets.subspan(i * 4).first<4u>()));
  }
}

void CFX_FolderFontInfo::ReportFace(CFX_FontMapper* mapper,
                                    const ByteString& path,
                                    FILE* pFile,
                                    FX_FILESIZE filesize,
                                    uint32_t offset) {
  if (fseek(pFile, offset, SEEK_SET) < 0) {
    return;
  }

  uint8_t buffer[12];
  if (fxcrt::spanread(buffer, pFile).size() != sizeof(buffer)) {
    return;
  }

  uint16_t nTables =
      fxcrt::GetUInt16MSBFirst(pdfium::span(buffer).subspan<4, 2>());
  ByteString tables = ReadStringFromFile(pFile, nTables * 16);
  if (tables.IsEmpty()) {
    return;
  }

  constexpr uint32_t kNameTag = CFX_FontMapper::MakeTag('n', 'a', 'm', 'e');
  std::optional<FontTableLocation> loc =
      FindFontTableLocation(tables.unsigned_span(), kNameTag);
  if (!loc) {
    return;
  }

  std::optional<FixedSizeDataVector<uint8_t>> names_data =
      DataVectorAtLocation(pFile, filesize, *loc);
  if (!names_data) {
    return;
  }

  ByteString facename = GetNameFromTT(names_data->span(), 1);
  if (facename.IsEmpty()) {
    return;
  }

  ByteString style = GetNameFromTT(names_data->span(), 2);
  if (style != "Regular") {
    facename += " " + style;
  }

  if (pdfium::Contains(font_list_, facename)) {
    return;
  }

  auto pInfo =
      std::make_unique<FontFaceInfo>(path, facename, tables, offset, filesize);
  static constexpr uint32_t kOs2Tag =
      CFX_FontMapper::MakeTag('O', 'S', '/', '2');
  std::optional<FontTableLocation> os2_loc =
      FindFontTableLocation(tables.unsigned_span(), kOs2Tag);
  // `codepages` corresponds to OS/2 table ulCodePageRange1.
  // See https://learn.microsoft.com/en-us/typography/opentype/spec/os2
  uint32_t codepages = 0;
  if (os2_loc) {
    std::optional<FixedSizeDataVector<uint8_t>> os2_data =
        DataVectorAtLocation(pFile, filesize, *os2_loc);
    if (os2_data) {
      codepages = GetCodePageRangeFromOS2(os2_data->span());
    }
  }
  if (codepages) {
    if (codepages & (1U << 1)) {
      mapper->AddInstalledFont(facename, FX_Charset::kMSWin_EasternEuropean);
      pInfo->charsets_ |= FX_CharsetFlag::kMSWin_EasternEuropean;
    }
    if (codepages & (1U << 2)) {
      mapper->AddInstalledFont(facename, FX_Charset::kMSWin_Cyrillic);
      pInfo->charsets_ |= FX_CharsetFlag::kMSWin_Cyrillic;
    }
    if (codepages & (1U << 3)) {
      mapper->AddInstalledFont(facename, FX_Charset::kMSWin_Greek);
      pInfo->charsets_ |= FX_CharsetFlag::kMSWin_Greek;
    }
    if (codepages & (1U << 4)) {
      mapper->AddInstalledFont(facename, FX_Charset::kMSWin_Turkish);
      pInfo->charsets_ |= FX_CharsetFlag::kMSWin_Turkish;
    }
    if (codepages & (1U << 5)) {
      mapper->AddInstalledFont(facename, FX_Charset::kMSWin_Hebrew);
      pInfo->charsets_ |= FX_CharsetFlag::kMSWin_Hebrew;
    }
    if (codepages & (1U << 6)) {
      mapper->AddInstalledFont(facename, FX_Charset::kMSWin_Arabic);
      pInfo->charsets_ |= FX_CharsetFlag::kMSWin_Arabic;
    }
    if (codepages & (1U << 7)) {
      mapper->AddInstalledFont(facename, FX_Charset::kMSWin_Baltic);
      pInfo->charsets_ |= FX_CharsetFlag::kMSWin_Baltic;
    }
    if (codepages & (1U << 8)) {
      mapper->AddInstalledFont(facename, FX_Charset::kMSWin_Vietnamese);
      pInfo->charsets_ |= FX_CharsetFlag::kMSWin_Vietnamese;
    }
    if (codepages & (1U << 16)) {
      mapper->AddInstalledFont(facename, FX_Charset::kThai);
      pInfo->charsets_ |= FX_CharsetFlag::kThai;
    }
    if (codepages & (1U << 17)) {
      mapper->AddInstalledFont(facename, FX_Charset::kShiftJIS);
      pInfo->charsets_ |= FX_CharsetFlag::kShiftJIS;
    }
    if (codepages & (1U << 18)) {
      mapper->AddInstalledFont(facename, FX_Charset::kChineseSimplified);
      pInfo->charsets_ |= FX_CharsetFlag::kChineseSimplified;
    }
    if (codepages & (1U << 19)) {
      mapper->AddInstalledFont(facename, FX_Charset::kHangul);
      pInfo->charsets_ |= FX_CharsetFlag::kHangul;
    }
    if (codepages & (1U << 20)) {
      mapper->AddInstalledFont(facename, FX_Charset::kChineseTraditional);
      pInfo->charsets_ |= FX_CharsetFlag::kChineseTraditional;
    }
    if (codepages & (1U << 21)) {
      mapper->AddInstalledFont(facename, FX_Charset::kJohab);
      pInfo->charsets_ |= FX_CharsetFlag::kJohab;
    }
    if (codepages & (1U << 30)) {
      mapper->AddInstalledFont(facename, FX_Charset::kOEM);
      pInfo->charsets_ |= FX_CharsetFlag::kOEM;
    }
    if (codepages & (1U << 31)) {
      mapper->AddInstalledFont(facename, FX_Charset::kSymbol);
      pInfo->charsets_ |= FX_CharsetFlag::kSymbol;
    }
  }
  static constexpr uint32_t kMaxpTag =
      CFX_FontMapper::MakeTag('m', 'a', 'x', 'p');
  std::optional<FontTableLocation> maxp_loc =
      FindFontTableLocation(tables.unsigned_span(), kMaxpTag);
  if (maxp_loc) {
    std::optional<FixedSizeDataVector<uint8_t>> maxp_data =
        DataVectorAtLocation(pFile, filesize, *maxp_loc);
    if (maxp_data) {
      pInfo->glyph_count_ = GetGlyphCountFromMaxp(maxp_data->span());
    }
  }
  mapper->AddInstalledFont(facename, FX_Charset::kANSI);
  pInfo->charsets_ |= FX_CharsetFlag::kANSI;
  pInfo->styles_ = 0;
  if (style.Contains("Bold")) {
    pInfo->styles_ |= pdfium::kFontStyleForceBold;
  }
  if (style.Contains("Italic") || style.Contains("Oblique")) {
    pInfo->styles_ |= pdfium::kFontStyleItalic;
  }
  if (facename.Contains("Serif")) {
    pInfo->styles_ |= pdfium::kFontStyleSerif;
  }

  font_list_[facename] = std::move(pInfo);
}

void* CFX_FolderFontInfo::GetSubstFont(const ByteString& face) {
  for (size_t iBaseFont = 0; iBaseFont < std::size(kBase14Substs);
       iBaseFont++) {
    if (face == kBase14Substs[iBaseFont].name_) {
      return GetFont(kBase14Substs[iBaseFont].subst_name_);
    }
  }
  return nullptr;
}

void* CFX_FolderFontInfo::FindFont(int weight,
                                   bool italic,
                                   FX_Charset charset,
                                   int pitch_family,
                                   const ByteString& family,
                                   bool must_match_name) {
  FontFaceInfo* pFind = nullptr;
  FX_CharsetFlag charset_flag = FX_CharsetFlagForCharset(charset);
  int32_t iBestSimilar = 0;
  if (must_match_name) {
    // Try a direct lookup for either a perfect score or to determine a
    // baseline similarity score.
    auto direct_it = font_list_.find(family);
    if (direct_it != font_list_.end()) {
      FontFaceInfo* font = direct_it->second.get();
      if (font->IsEligibleForFindFont(charset_flag, charset)) {
        iBestSimilar = font->SimilarityScore(weight, italic, pitch_family,
                                             must_match_name);
        pFind = font;
        if (iBestSimilar == FontFaceInfo::kSimilarityScoreMax) {
          return font;
        }
      }
    }
  }

  ByteStringView bsFamily = family.AsStringView();
  for (const auto& it : font_list_) {
    FontFaceInfo* font = it.second.get();
    if (!font->IsEligibleForFindFont(charset_flag, charset)) {
      continue;
    }
    int32_t iSimilarValue = font->SimilarityScore(
        weight, italic, pitch_family,
        must_match_name &&
            bsFamily.GetLength() == font->face_name_.GetLength());
    if (IsBetterMatch(font, iSimilarValue, pFind, iBestSimilar, charset, family,
                      must_match_name)) {
      iBestSimilar = iSimilarValue;
      pFind = font;
    }
  }

  if (pFind) {
    return pFind;
  }

  if (charset == FX_Charset::kANSI && FontFamilyIsFixedPitch(pitch_family)) {
    auto* courier_new = GetFont("Courier New");
    if (courier_new) {
      return courier_new;
    }
  }

  return nullptr;
}

void* CFX_FolderFontInfo::MapFont(CFX_FontMapper* mapper,
                                  int weight,
                                  bool italic,
                                  FX_Charset charset,
                                  int pitch_family,
                                  const ByteString& face) {
  return nullptr;
}

void* CFX_FolderFontInfo::GetFont(const ByteString& face) {
  auto it = font_list_.find(face);
  return it != font_list_.end() ? it->second.get() : nullptr;
}

size_t CFX_FolderFontInfo::GetFontData(void* hFont,
                                       uint32_t table,
                                       pdfium::span<uint8_t> buffer) {
  if (!hFont) {
    return 0;
  }
  const FontFaceInfo* font = static_cast<FontFaceInfo*>(hFont);
  uint32_t datasize = 0;
  uint32_t offset = 0;
  if (table == SystemFontInfoIface::kTableNone) {
    datasize = font->font_offset_ ? 0 : font->file_size_;
  } else if (table == SystemFontInfoIface::kTableTTCF) {
    datasize = font->font_offset_ ? font->file_size_ : 0;
  } else {
    std::optional<FontTableLocation> loc =
        FindFontTableLocation(font->font_tables_.unsigned_span(), table);
    if (loc) {
      datasize = loc->size;
      offset = loc->offset;
    }
  }

  if (!datasize || buffer.size() < datasize) {
    return datasize;
  }

  std::unique_ptr<FILE, FxFileCloser> pFile(
      fopen(font->file_path_.c_str(), "rb"));
  if (!pFile) {
    return 0;
  }

  if (fseek(pFile.get(), offset, SEEK_SET) < 0) {
    return 0;
  }
  if (fxcrt::spanread(buffer.first(datasize), pFile.get()).size() != datasize) {
    return 0;
  }
  return datasize;
}

void CFX_FolderFontInfo::DeleteFont(void* hFont) {}

bool CFX_FolderFontInfo::GetFaceName(void* hFont, ByteString* name) {
  if (!hFont) {
    return false;
  }
  *name = static_cast<FontFaceInfo*>(hFont)->face_name_;
  return true;
}

// Check supported charsets in order of specificity (Symbol and CJK first,
// then regional, then ANSI last) to ensure resolving kDefault to the most
// specific charset supported by the font (e.g. preventing CJK fonts that
// also support ANSI from being incorrectly resolved as ANSI).
bool CFX_FolderFontInfo::GetFontCharset(void* hFont, FX_Charset* charset) {
  if (!hFont) {
    return false;
  }
  FontFaceInfo* font = static_cast<FontFaceInfo*>(hFont);
  if (font->charsets_ & FX_CharsetFlag::kSymbol) {
    *charset = FX_Charset::kSymbol;
    return true;
  }
  if (font->charsets_ & FX_CharsetFlag::kShiftJIS) {
    *charset = FX_Charset::kShiftJIS;
    return true;
  }
  if (font->charsets_ & FX_CharsetFlag::kChineseSimplified) {
    *charset = FX_Charset::kChineseSimplified;
    return true;
  }
  if (font->charsets_ & FX_CharsetFlag::kChineseTraditional) {
    *charset = FX_Charset::kChineseTraditional;
    return true;
  }
  if (font->charsets_ & FX_CharsetFlag::kHangul) {
    *charset = FX_Charset::kHangul;
    return true;
  }
  if (font->charsets_ & FX_CharsetFlag::kJohab) {
    *charset = FX_Charset::kJohab;
    return true;
  }
  if (font->charsets_ & FX_CharsetFlag::kMSWin_Cyrillic) {
    *charset = FX_Charset::kMSWin_Cyrillic;
    return true;
  }
  if (font->charsets_ & FX_CharsetFlag::kMSWin_Greek) {
    *charset = FX_Charset::kMSWin_Greek;
    return true;
  }
  if (font->charsets_ & FX_CharsetFlag::kMSWin_Turkish) {
    *charset = FX_Charset::kMSWin_Turkish;
    return true;
  }
  if (font->charsets_ & FX_CharsetFlag::kMSWin_Hebrew) {
    *charset = FX_Charset::kMSWin_Hebrew;
    return true;
  }
  if (font->charsets_ & FX_CharsetFlag::kMSWin_Arabic) {
    *charset = FX_Charset::kMSWin_Arabic;
    return true;
  }
  if (font->charsets_ & FX_CharsetFlag::kMSWin_Baltic) {
    *charset = FX_Charset::kMSWin_Baltic;
    return true;
  }
  if (font->charsets_ & FX_CharsetFlag::kMSWin_Vietnamese) {
    *charset = FX_Charset::kMSWin_Vietnamese;
    return true;
  }
  if (font->charsets_ & FX_CharsetFlag::kThai) {
    *charset = FX_Charset::kThai;
    return true;
  }
  if (font->charsets_ & FX_CharsetFlag::kMSWin_EasternEuropean) {
    *charset = FX_Charset::kMSWin_EasternEuropean;
    return true;
  }
  if (font->charsets_ & FX_CharsetFlag::kOEM) {
    *charset = FX_Charset::kOEM;
    return true;
  }
  if (font->charsets_ & FX_CharsetFlag::kANSI) {
    *charset = FX_Charset::kANSI;
    return true;
  }
  return false;
}

CFX_FolderFontInfo::FontFaceInfo::FontFaceInfo(ByteString filePath,
                                               ByteString faceName,
                                               ByteString fontTables,
                                               uint32_t fontOffset,
                                               uint32_t fileSize)
    : file_path_(filePath),
      face_name_(faceName),
      font_tables_(fontTables),
      font_offset_(fontOffset),
      file_size_(fileSize) {}

bool CFX_FolderFontInfo::FontFaceInfo::IsEligibleForFindFont(
    FX_CharsetFlag flag,
    FX_Charset charset) const {
  return (charsets_ & flag) || charset == FX_Charset::kDefault;
}

int32_t CFX_FolderFontInfo::FontFaceInfo::SimilarityScore(
    int weight,
    bool italic,
    int pitch_family,
    bool exact_match_bonus) const {
  int32_t score = 0;
  if (FontStyleIsForceBold(styles_) == (weight > 400)) {
    score += 16;
  }
  if (FontStyleIsItalic(styles_) == italic) {
    score += 16;
  }
  if (FontStyleIsSerif(styles_) == FontFamilyIsRoman(pitch_family)) {
    score += 16;
  }
  if (FontStyleIsScript(styles_) == FontFamilyIsScript(pitch_family)) {
    score += 8;
  }
  if (FontStyleIsFixedPitch(styles_) == FontFamilyIsFixedPitch(pitch_family)) {
    score += 8;
  }
  if (exact_match_bonus) {
    score += 4;
  }
  DCHECK_LE(score, kSimilarityScoreMax);
  return score;
}

bool CFX_FolderFontInfo::IsBetterMatch(const FontFaceInfo* candidate,
                                       int32_t candidate_score,
                                       const FontFaceInfo* current_best,
                                       int32_t current_best_score,
                                       FX_Charset charset,
                                       const ByteString& family,
                                       bool must_match_name) const {
  // Avoid the relatively expensive FindFamilyNameMatch() unless there might
  // be a better match.
  if (candidate_score <= current_best_score) {
    return false;
  }
  if (!must_match_name) {
    return true;
  }
  return FindFamilyNameMatch(family.AsStringView(), candidate->face_name_);
}
