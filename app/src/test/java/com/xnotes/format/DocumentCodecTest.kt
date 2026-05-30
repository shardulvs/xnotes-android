package com.xnotes.format

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeRasterSurface
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Bookmark
import com.xnotes.core.model.Document
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.TextItem
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.ShapeKind
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class DocumentCodecTest {

    private val codec = DocumentCodec(FakeImageCodec(), FakeTextMeasurer())

    private fun roundTrip(doc: Document): Document {
        val out = ByteArrayOutputStream()
        codec.write(doc, out)
        return codec.read(ByteArrayInputStream(out.toByteArray()))
    }

    @Test fun fullRoundTrip() {
        val doc = Document(dpi = 150)
        val page = Page(1240.0, 1754.0)
        page.items.add(
            Stroke(
                Tool.CALLIGRAPHY,
                ToolConfig(6.0, true, 0.40, 0.60, Rgba(0, 230, 118, 255)),
                mutableListOf(Sample(10.0, 20.0, 0.5), Sample(30.0, 40.0, 0.9)),
            ),
        )
        page.items.add(ImageItem(FakeRasterSurface(64, 48), Rect(5.0, 6.0, 64.0, 48.0)))
        page.items.add(TextItem(Pt(100.0, 110.0), 250.0, "hello\nworld", Rgba(236, 236, 236, 255), 13.0, FakeTextMeasurer()))
        page.items.add(ShapeItem(ShapeKind.RECTANGLE, Pt(0.0, 0.0), Pt(50.0, 30.0), Rgba(255, 92, 92, 255), 3.0, Rgba(255, 92, 92, 64)))
        doc.pages.add(page)
        doc.bookmarks.add(Bookmark(0, "Intro"))

        val back = roundTrip(doc)

        assertEquals(1, back.pages.size)
        assertEquals(150, back.dpi)
        assertEquals(1240.0, back.pages[0].width, 1e-9)
        val items = back.pages[0].items
        assertEquals(4, items.size)

        val stroke = items[0] as Stroke
        assertEquals(Tool.CALLIGRAPHY, stroke.tool)
        assertEquals(0.60, stroke.config.directionStrength, 1e-9)
        assertEquals(2, stroke.samples.size)
        assertEquals(Sample(10.0, 20.0, 0.5), stroke.samples[0])

        val image = items[1] as ImageItem
        assertEquals(Rect(5.0, 6.0, 64.0, 48.0), image.rect)

        val text = items[2] as TextItem
        assertEquals("hello\nworld", text.text)
        assertEquals(250.0, text.width, 1e-9)

        val shape = items[3] as ShapeItem
        assertEquals(ShapeKind.RECTANGLE, shape.shape)
        assertNotNull(shape.fillRgba)
        assertEquals(Rgba(255, 92, 92, 64), shape.fillRgba)

        assertEquals(1, back.bookmarks.size)
        assertEquals("Intro", back.bookmarks[0].label)
    }

    @Test fun manifestIsStoredAndDeflatedCorrectly() {
        val doc = Document.blank(count = 1)
        doc.pages[0].items.add(ImageItem(FakeRasterSurface(10, 10), Rect(0.0, 0.0, 10.0, 10.0)))
        val out = ByteArrayOutputStream()
        codec.write(doc, out)

        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                names += e.name
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        assertTrue(names.contains("manifest.json"))
        assertTrue(names.contains("assets/image-000.png"))
    }

    @Test fun rejectsNonXnote() {
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use {
            it.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
            it.write("{\"format\":\"other\"}".toByteArray())
            it.closeEntry()
        }
        assertThrows(XNoteFormatException::class.java) {
            codec.read(ByteArrayInputStream(out.toByteArray()))
        }
    }

    @Test fun emptyPagesFallBackToOneBlank() {
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use {
            it.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
            it.write("{\"format\":\"xnote\",\"version\":1,\"pages\":[]}".toByteArray())
            it.closeEntry()
        }
        val doc = codec.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(1, doc.pages.size)
    }

    @Test fun unknownItemKindIsSkipped() {
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use {
            it.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
            it.write(
                ("{\"format\":\"xnote\",\"version\":1,\"pages\":[" +
                    "{\"width\":100,\"height\":100,\"items\":[" +
                    "{\"kind\":\"future-thing\"},{\"kind\":\"text\",\"pos\":[1,2],\"text\":\"ok\"}]}]}").toByteArray(),
            )
            it.closeEntry()
        }
        val doc = codec.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(1, doc.pages[0].items.size)
        assertTrue(doc.pages[0].items[0] is TextItem)
    }

    @Test fun pdfBytesRoundTrip() {
        val doc = Document.blank(count = 1)
        doc.pages[0].pdfPage = 0
        doc.pdfBytes = byteArrayOf(37, 80, 68, 70) // "%PDF"
        val back = roundTrip(doc)
        assertNotNull(back.pdfBytes)
        assertEquals(0, back.pages[0].pdfPage)
        assertEquals(4, back.pdfBytes!!.size)
    }

    @Test fun speedStrokeTimestampsRoundTrip() {
        val doc = Document(dpi = 150)
        val page = Page(1240.0, 1754.0)
        page.items.add(
            Stroke(
                Tool.SPEED,
                ToolDefaults.configFor(Tool.SPEED),
                mutableListOf(Sample(0.0, 0.0, 1.0, 0.0), Sample(10.0, 0.0, 0.8, 16.0), Sample(20.0, 0.0, 0.6, 33.0)),
                2.5,
            ),
        )
        doc.pages.add(page)

        val s = roundTrip(doc).pages[0].items[0] as Stroke
        assertEquals(Tool.SPEED, s.tool)
        assertEquals(0.8, s.config.speedStrength, 1e-9)
        assertEquals(2.5, s.speedScale, 1e-9)       // gesture-speed scale survives
        assertEquals(3, s.samples.size)
        assertEquals(16.0, s.samples[1].t, 1e-9)   // the 4th sample element survives
        assertEquals(33.0, s.samples[2].t, 1e-9)
    }

    @Test fun neonAndTaperFlagsRoundTrip() {
        val doc = Document(dpi = 150)
        val page = Page(1240.0, 1754.0)
        page.items.add(Stroke(Tool.PEN, ToolConfig(neon = true, neonStrength = 0.85), mutableListOf(Sample(1.0, 2.0, 1.0))))
        page.items.add(Stroke(Tool.TAPER, ToolConfig(taperLength = 40.0), mutableListOf(Sample(3.0, 4.0, 1.0), Sample(8.0, 9.0, 1.0))))
        doc.pages.add(page)

        val items = roundTrip(doc).pages[0].items
        assertTrue((items[0] as Stroke).config.neon)
        assertEquals(0.85, (items[0] as Stroke).config.neonStrength, 1e-9)   // intensity survives
        val taper = items[1] as Stroke
        assertEquals(40.0, taper.config.taperLength, 1e-9)
        assertEquals(0.0, taper.samples[0].t, 1e-9)   // non-speed stroke writes no time
    }

    @Test fun strokeMissingFieldsTakeDefaults() {
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use {
            it.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
            it.write(
                ("{\"format\":\"xnote\",\"pages\":[{\"width\":100,\"height\":100,\"items\":[" +
                    "{\"kind\":\"stroke\",\"samples\":[[1,2,1.0]]}]}]}").toByteArray(),
            )
            it.closeEntry()
        }
        val doc = codec.read(ByteArrayInputStream(out.toByteArray()))
        val stroke = doc.pages[0].items[0] as Stroke
        assertEquals(ToolConfig().baseWidth, stroke.config.baseWidth, 1e-9)
        assertNull(doc.pdfBytes)
    }
}
