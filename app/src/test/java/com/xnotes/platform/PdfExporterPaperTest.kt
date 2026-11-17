package com.xnotes.platform

import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Page
import com.xnotes.core.model.PageInsets
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Room
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The paper an export gives a PDF page where its PDF does not show. */
class PdfExporterPaperTest {

    private val paper = Rgba(22, 33, 44, 255)

    // A Letter page at two px a point, so a page px is half a point.
    private val w = 1224.0
    private val h = 1584.0
    private val s = 0.5

    /** [page] exported over a Letter source page: its box, and what it fills in [paper], in user space. */
    private fun exported(page: Page, ins: PageInsets): Pair<PDRectangle, List<Rect>> = PDDocument().use { doc ->
        val pd = PDPage(PDRectangle(612f, 792f))
        doc.addPage(pd)
        PDPageContentStream(doc, pd).use { cs ->
            cs.setNonStrokingColor(1f, 0f, 0f)
            cs.addRect(100f, 100f, 50f, 50f)
            cs.fill()
        }
        PdfExporter.annotatePage(PdfExportContext(doc), pd, page, ins, s, { paper }, { _, _ -> }, PdfExporter.FlowExport.NONE)
        pd.mediaBox to fills(pd)
    }

    /** The box of every subpath [pd]'s content fills in [paper]. */
    private fun fills(pd: PDPage): List<Rect> {
        val parser = PDFStreamParser(pd)
        parser.parse()
        val out = ArrayList<Rect>()
        val args = ArrayList<Double>()
        val path = ArrayList<ArrayList<Pair<Double, Double>>>()
        val saved = ArrayList<Boolean>()
        var ours = false
        for (token in parser.tokens) {
            if (token is COSNumber) {
                args += token.doubleValue()
                continue
            }
            if (token !is Operator) {
                args.clear()
                continue
            }
            when (token.name) {
                "q" -> saved += ours
                "Q" -> ours = saved.removeAt(saved.size - 1)
                "rg" -> ours = args.size == 3 && near(args[0], paper.r / 255.0) && near(args[1], paper.g / 255.0) && near(args[2], paper.b / 255.0)
                "m" -> path += arrayListOf(args[0] to args[1])
                "l" -> path.last() += args[0] to args[1]
                "re" -> path += arrayListOf(args[0] to args[1], args[0] + args[2] to args[1] + args[3])
                "f", "f*" -> {
                    if (ours) for (sub in path) {
                        val l = sub.minOf { it.first }
                        val b = sub.minOf { it.second }
                        out += Rect(l, b, sub.maxOf { it.first } - l, sub.maxOf { it.second } - b)
                    }
                    path.clear()
                }
                "n", "S", "s", "B", "b" -> path.clear()
            }
            args.clear()
        }
        return out
    }

    private fun near(a: Double, b: Double) = kotlin.math.abs(a - b) < 0.01

    private fun assertRect(want: Rect, got: Rect) {
        assertTrue("$got is not $want", near(want.left, got.left) && near(want.top, got.top) && near(want.w, got.w) && near(want.h, got.h))
    }

    @Test fun roomInAPdfPageIsFilledWithPaper() {
        val page = Page(w, h + 200.0, pdfPage = 0, rooms = listOf(Room(400.0, 200.0)))

        val (box, filled) = exported(page, PageInsets(0.0, 0.0, 0.0, 0.0))

        // The room runs from 200 pt to 300 pt below the top of a page grown 100 pt downward.
        assertEquals(1, filled.size)
        assertRect(Rect(0.0, 492.0, 612.0, 100.0), filled[0])
        assertEquals(-100f, box.lowerLeftY, 0.01f)
        assertEquals(892f, box.height, 0.01f)
    }

    @Test fun theMarginsOfAPdfPageAreFilledWithPaper() {
        val (box, filled) = exported(Page(w, h, pdfPage = 0), PageInsets(20.0, 30.0, 40.0, 50.0))

        // Everything the page's box grew by, and none of the PDF.
        assertEquals(4, filled.size)
        val grown = Rect(box.lowerLeftX.toDouble(), box.lowerLeftY.toDouble(), box.width.toDouble(), box.height.toDouble())
        assertRect(Rect(-10.0, -25.0, 642.0, 832.0), grown)
        assertRect(grown, filled.reduce { a, b -> a.union(b) })
        assertEquals(grown.w * grown.h - 612.0 * 792.0, filled.sumOf { it.w * it.h }, 0.5)
        val pdf = Rect(0.0, 0.0, 612.0, 792.0)
        for (f in filled) assertTrue("$f is over the PDF", !f.outset(-0.01).intersects(pdf))
    }

    @Test fun aPdfPageWithNeitherIsLeftAsItIs() {
        val (box, filled) = exported(Page(w, h, pdfPage = 0), PageInsets(0.0, 0.0, 0.0, 0.0))

        assertTrue(filled.isEmpty())
        assertEquals(792f, box.height, 0.01f)
    }
}
