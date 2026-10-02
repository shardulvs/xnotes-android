package com.xnotes.platform

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import com.xnotes.core.geometry.Rect
import java.io.File
import java.io.InputStream

/** A PDF page's text, with the box each character sits in (normalized to the page, y down; null for
 *  the spaces and breaks the reader puts between words and lines). */
class PdfTextPage(val text: String, val boxes: List<Rect?>)

/** A search hit on page [page]: the boxes (normalized to the page) it covers, one per line it spans. */
data class PdfMatch(val page: Int, val rects: List<Rect>)

/** Reads the text layer of a PDF for search. IO and CPU heavy: call it off the main thread. */
object ReaderPdfText {

    private const val SCRATCH_MEM_BYTES = 8L * 1024 * 1024

    private var cachedKey: String? = null
    private var cached: List<PdfTextPage> = emptyList()

    /** The text of every page of [file] (the last file asked for is kept). */
    fun pages(context: Context, file: File): List<PdfTextPage> =
        pages(context, file.absolutePath + ":" + file.lastModified() + ":" + file.length()) { file.inputStream() }

    /** The text of every page of the PDF [open] reads, cached under [key]. */
    @Synchronized
    fun pages(context: Context, key: String, open: () -> InputStream): List<PdfTextPage> {
        if (key == cachedKey) return cached
        val pages = runCatching { open().use { extract(context, it) } }.getOrDefault(emptyList())
        cachedKey = key
        cached = pages
        return pages
    }

    private fun extract(context: Context, input: InputStream): List<PdfTextPage> {
        PDFBoxResourceLoader.init(context.applicationContext)
        val mem = MemoryUsageSetting.setupMixed(SCRATCH_MEM_BYTES).setTempDir(context.cacheDir)
        return PDDocument.load(input, mem).use { doc ->
            val stripper = Collector()
            (1..doc.numberOfPages).map { n ->
                stripper.reset()
                stripper.startPage = n
                stripper.endPage = n
                stripper.getText(doc)
                PdfTextPage(stripper.text.toString(), stripper.boxes.toList())
            }
        }
    }

    /** Every case-insensitive occurrence of [query] across [pages], in reading order. */
    fun find(pages: List<PdfTextPage>, query: String): List<PdfMatch> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<PdfMatch>()
        pages.forEachIndexed { i, page ->
            val text = page.text.lowercase()
            var at = text.indexOf(q)
            while (at >= 0) {
                val rects = lineRects(page.boxes.subList(at, (at + q.length).coerceAtMost(page.boxes.size)).filterNotNull())
                if (rects.isNotEmpty()) out.add(PdfMatch(i, rects))
                at = text.indexOf(q, at + q.length)
            }
        }
        return out
    }

    /** Glyph boxes merged into one box per line they sit on. */
    private fun lineRects(boxes: List<Rect>): List<Rect> {
        val out = ArrayList<Rect>()
        for (b in boxes) {
            val last = out.lastOrNull()
            if (last != null && kotlin.math.abs(last.centerY - b.centerY) < last.h * 0.5) out[out.size - 1] = last.union(b)
            else out.add(b)
        }
        return out
    }

    private class Collector : PDFTextStripper() {
        val text = StringBuilder()
        val boxes = ArrayList<Rect?>()

        init {
            sortByPosition = true
        }

        fun reset() {
            text.setLength(0)
            boxes.clear()
        }

        override fun writeString(string: String?, textPositions: MutableList<TextPosition>?) {
            val positions = textPositions ?: return
            for (p in positions) {
                val pw = p.pageWidth.toDouble().takeIf { it > 0 } ?: continue
                val ph = p.pageHeight.toDouble().takeIf { it > 0 } ?: continue
                val h = p.heightDir.toDouble().coerceAtLeast(1.0)
                val box = Rect(p.xDirAdj / pw, (p.yDirAdj - h) / ph, p.widthDirAdj / pw, h / ph)
                // A ligature is one position standing for several characters; each gets its box.
                for (c in p.unicode ?: "") {
                    text.append(c)
                    boxes.add(box)
                }
            }
        }

        override fun writeWordSeparator() {
            text.append(' ')
            boxes.add(null)
        }

        override fun writeLineSeparator() {
            text.append(' ')
            boxes.add(null)
        }
    }
}
