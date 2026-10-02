package com.xnotes.platform

import android.graphics.Bitmap

/** The JNI surface over the vendored PDFium (see cpp/pdf_jni.cpp). All but [revision] and
 *  [nativeLoadedPages] run on [PdfiumThread]. */
object PdfiumNative {
    val loaded: Boolean = runCatching { System.loadLibrary("xnotespdf") }.isSuccess

    /** The pinned PDFium revision; it reads no PDFium state, so any thread may ask. */
    val revision: String by lazy { if (loaded) nativeRevision() else "not loaded" }

    @JvmStatic external fun nativeRevision(): String

    @JvmStatic external fun nativeInit()

    /** Opens the PDF at [path]: a handle, or 0. [out] receives the FPDF_ERR_* code, then the page count. */
    @JvmStatic external fun nativeOpen(path: String, out: IntArray): Long

    @JvmStatic external fun nativeClose(doc: Long)

    /** Frees [doc]'s loaded pages, fonts and cached glyphs; they load again when next used. */
    @JvmStatic external fun nativeTrim(doc: Long)

    /** Pages loaded across all documents; it reads no PDFium state, so any thread may ask. */
    @JvmStatic external fun nativeLoadedPages(): Int

    /** Width and height in points, as displayed (/Rotate applied), of [count] pages from [from]; 0 when broken. */
    @JvmStatic external fun nativePageSizes(doc: Long, from: Int, count: Int): FloatArray?

    /** The boxes (l, t, r, b), in points as displayed, of the images on page [index], those in forms included. */
    @JvmStatic external fun nativeImageRects(doc: Long, index: Int): FloatArray?

    /** Page [index]'s link boxes (l, t, r, b in points as displayed), their target pages (-1 for none) and URIs. */
    @JvmStatic external fun nativeLinks(doc: Long, index: Int): Array<Any>?

    /**
     * The next slice of the outline, read for about [budgetMs] (empty when done, or after [max]
     * entries): titles, target pages (-1 for none), depths. [restart] begins a new walk.
     */
    @JvmStatic external fun nativeOutline(doc: Long, max: Int, budgetMs: Int, restart: Boolean): Array<Any>?

    /** Page [index]'s text as the codepoints, boxes, flags and angles of a [com.xnotes.core.pdf.PageText]. */
    @JvmStatic external fun nativePageText(doc: Long, index: Int): Array<Any?>?

    /** Page [index]'s map from user space to points as displayed, {a, b, c, d, e, f}, leaving the page cache as it was. */
    @JvmStatic external fun nativePageGeometry(doc: Long, index: Int): DoubleArray?

    /** Page [index]'s codepoints and flags as [nativePageText] reads them, leaving the page cache as it was. */
    @JvmStatic external fun nativePageChars(doc: Long, index: Int): Array<Any>?

    /** See [PdfiumDocument.render]; it stops early once [lifetime] or [token] is cancelled. */
    @JvmStatic external fun nativeRender(
        doc: Long, index: Int, bitmap: Bitmap, fullW: Int, fullH: Int, left: Int, top: Int,
        lifetime: CancelToken, token: CancelToken?,
    ): Boolean
}
