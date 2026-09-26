package com.xnotes.format

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.model.ImageData
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Bookmark
import com.xnotes.core.model.Document
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Orientation
import com.xnotes.core.model.Page
import com.xnotes.core.model.PageMargins
import com.xnotes.core.model.PagePattern
import com.xnotes.core.model.PageSize
import com.xnotes.core.model.PageStyle
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.TextItem
import com.xnotes.core.stroke.Sample
import com.xnotes.core.stroke.StrokeSimplify
import com.xnotes.core.tools.ShapeKind
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream

class DocumentCodecTest {

    private val codec = DocumentCodec(FakeImageCodec(), FakeTextMeasurer())

    private fun imageFile(bytes: ByteArray = byteArrayOf(1, 2, 3, 4)): File =
        File.createTempFile("img", null).apply { writeBytes(bytes); deleteOnExit() }

    /** The manifest's JSON text out of a written bundle. */
    private fun manifestOf(bundle: ByteArray): String {
        ZipInputStream(ByteArrayInputStream(bundle)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (e.name == "manifest.json") return zis.readBytes().decodeToString()
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        return ""
    }

    private fun roundTrip(doc: Document): Document {
        val out = ByteArrayOutputStream()
        codec.write(doc, out)
        val imageDir = Files.createTempDirectory("xnotes-img").toFile()
        return codec.read(ByteArrayInputStream(out.toByteArray()), imageDir = imageDir)
    }

    @Test fun lockedItemsSurviveARoundTripAndOnlyThoseLockedComeBackLocked() {
        val doc = Document(dpi = 150)
        val page = Page(1240.0, 1754.0)
        val pinnedStroke = Stroke(
            Tool.PEN,
            ToolDefaults.configFor(Tool.PEN),
            mutableListOf(Sample(1.0, 2.0, 1.0), Sample(3.0, 4.0, 1.0)),
        ).apply { locked = true }
        val freeStroke = Stroke(
            Tool.PEN,
            ToolDefaults.configFor(Tool.PEN),
            mutableListOf(Sample(5.0, 6.0, 1.0), Sample(7.0, 8.0, 1.0)),
        )
        page.items.add(pinnedStroke)
        page.items.add(freeStroke)
        page.items.add(ImageItem(ImageData(imageFile(), 20, 10), Rect(0.0, 0.0, 20.0, 10.0)).apply { locked = true })
        page.items.add(TextItem(Pt(1.0, 1.0), 100.0, 0.0, "hi", Rgba(0, 0, 0), 12.0, measurer = FakeTextMeasurer()).apply { locked = true })
        page.items.add(
            ShapeItem(ShapeKind.RECTANGLE, Pt(0.0, 0.0), Pt(9.0, 9.0), Rgba(1, 2, 3, 255)).apply { locked = true },
        )
        doc.pages.add(page)

        val back = roundTrip(doc).pages[0].items
        assertEquals(5, back.size)
        assertTrue(back[0].locked)
        assertFalse(back[1].locked)
        assertTrue(back[2].locked)
        assertTrue(back[3].locked)
        assertTrue(back[4].locked)
    }

    @Test fun createdTimeRoundTripsAsIsoUtc() {
        val doc = Document(dpi = 150, created = 1_789_720_071_123L)
        doc.pages.add(Page(100.0, 100.0))
        val out = ByteArrayOutputStream()
        codec.write(doc, out)
        assertTrue(manifestOf(out.toByteArray()).contains("2026-09-18T08:27:51.123Z"))
        assertEquals(1_789_720_071_123L, codec.read(ByteArrayInputStream(out.toByteArray())).created)
    }

    @Test fun aNoteWithoutCreatedWritesNoCreatedField() {
        val doc = Document(dpi = 150)
        doc.pages.add(Page(100.0, 100.0))
        val out = ByteArrayOutputStream()
        codec.write(doc, out)
        assertFalse(manifestOf(out.toByteArray()).contains("\"created\""))
        assertNull(roundTrip(doc).created)
    }

    @Test fun anUnlockedNoteWritesNoLockField() {
        val doc = Document(dpi = 150)
        val page = Page(100.0, 100.0)
        page.items.add(Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), mutableListOf(Sample(1.0, 1.0, 1.0))))
        doc.pages.add(page)
        val out = ByteArrayOutputStream()
        codec.write(doc, out)
        assertFalse(String(out.toByteArray(), Charsets.ISO_8859_1).contains("\"locked\""))
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
        page.items.add(ImageItem(ImageData(imageFile(),64, 48), Rect(5.0, 6.0, 64.0, 48.0), angle = 0.5))
        page.items.add(TextItem(Pt(100.0, 110.0), width = 250.0, text = "hello\nworld", rgba = Rgba(236, 236, 236, 255), pointSize = 13.0, measurer = FakeTextMeasurer()))
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
        assertEquals(0.5, image.angle, 1e-9)

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
        doc.pages[0].items.add(ImageItem(ImageData(imageFile(),10, 10), Rect(0.0, 0.0, 10.0, 10.0)))
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
        // The manifest goes last, always: an in-place save replaces the tail of the file, which
        // only works while nothing sits behind it. See ZipTail.
        assertEquals("manifest.json", names.last())
    }

    @Test fun svgImageIsStoredWithSvgExtensionAndRoundTrips() {
        val markup = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\"/>"
        val doc = Document.blank(count = 1)
        doc.pages[0].items.add(ImageItem(ImageData(imageFile(markup.toByteArray()), 10, 10), Rect(0.0, 0.0, 10.0, 10.0)))
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
        assertTrue(names.contains("assets/image-000.svg"))

        val imageDir = Files.createTempDirectory("xnotes-img").toFile()
        val back = codec.read(ByteArrayInputStream(out.toByteArray()), imageDir = imageDir)
        val item = back.pages[0].items[0] as ImageItem
        assertEquals(markup, item.image.file.readText())
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

    @Test fun pdfFileRoundTrip() {
        val srcDir = java.nio.file.Files.createTempDirectory("xnote-src").toFile()
        val outDir = java.nio.file.Files.createTempDirectory("xnote-out").toFile()
        try {
            val pdf = java.io.File(srcDir, "in.pdf").apply { writeBytes(byteArrayOf(37, 80, 68, 70)) } // "%PDF"
            val doc = Document.blank(count = 1)
            doc.pages[0].pdfPage = 0
            doc.pdfFile = pdf
            val out = ByteArrayOutputStream()
            codec.write(doc, out)
            // Reading with a pdfDir streams the embedded PDF back out to a fresh file (never into RAM).
            val back = codec.read(ByteArrayInputStream(out.toByteArray()), outDir)
            assertNotNull(back.pdfFile)
            assertEquals(0, back.pages[0].pdfPage)
            assertArrayEquals(byteArrayOf(37, 80, 68, 70), back.pdfFile!!.readBytes())
            // Reading without a pdfDir skips the PDF entirely (validation-only reads).
            val noPdf = codec.read(ByteArrayInputStream(out.toByteArray()))
            assertNull(noPdf.pdfFile)
        } finally {
            srcDir.deleteRecursively(); outDir.deleteRecursively()
        }
    }

    @Test fun aPeekCountsPagesAndSeesThePdfWithoutReadingIt() {
        val dir = java.nio.file.Files.createTempDirectory("xnote-peek").toFile()
        try {
            val pdf = File(dir, "in.pdf").apply { writeBytes(ByteArray(200_000) { (it % 251).toByte() }) }
            val doc = Document.blank(count = 3)
            doc.pdfFile = pdf
            doc.created = 1_758_000_000_000L
            val file = File(dir, "n.xnote")
            file.outputStream().use { codec.write(doc, it) }
            val viaChannel = java.io.RandomAccessFile(file, "r").use { codec.peek(it.channel) }!!
            assertEquals(3, viaChannel.pages)
            assertTrue(viaChannel.hasPdf)
            assertEquals(1_758_000_000_000L, viaChannel.created)
            val viaStream = file.inputStream().use { codec.peek(it) }!!
            assertEquals(3, viaStream.pages)
            assertTrue(viaStream.hasPdf)
            val plain = File(dir, "p.xnote")
            plain.outputStream().use { codec.write(Document.blank(count = 1), it) }
            val one = java.io.RandomAccessFile(plain, "r").use { codec.peek(it.channel) }!!
            assertEquals(1, one.pages)
            assertFalse(one.hasPdf)
            assertNull(one.created)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun aPeekAtSomethingElseComesBackEmpty() {
        val junk = File.createTempFile("junk", ".xnote").apply { writeBytes(byteArrayOf(1, 2, 3)); deleteOnExit() }
        assertNull(java.io.RandomAccessFile(junk, "r").use { codec.peek(it.channel) })
        assertNull(codec.peek(ByteArrayInputStream(byteArrayOf(1, 2, 3))))
        assertNull(codec.peekManifest(ByteArrayInputStream("""{"format":"xcanvas","items":[]}""".toByteArray())))
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
        page.items.add(Stroke(Tool.TAPER, ToolConfig(taperEnabled = true, taperMinFactor = 0.30), mutableListOf(Sample(3.0, 4.0, 1.0), Sample(8.0, 9.0, 1.0))))
        doc.pages.add(page)

        val items = roundTrip(doc).pages[0].items
        assertTrue((items[0] as Stroke).config.neon)
        assertEquals(0.85, (items[0] as Stroke).config.neonStrength, 1e-9)   // intensity survives
        val taper = items[1] as Stroke
        assertTrue(taper.config.taperEnabled)
        assertEquals(0.30, taper.config.taperMinFactor, 1e-9)   // tip-width floor survives
        assertEquals(0.0, taper.samples[0].t, 1e-9)   // non-speed stroke writes no time
    }

    @Test fun legacyTaperLengthLoadsAsEnabledTaper() {
        // Files written before the whole-stroke taper carry taper_length but no taper_enabled; a
        // positive legacy length must still load as an enabled taper so old notes keep tapering.
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use {
            it.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
            it.write(
                ("{\"format\":\"xnote\",\"pages\":[{\"width\":100,\"height\":100,\"items\":[" +
                    "{\"kind\":\"stroke\",\"tool\":\"taper\",\"config\":{\"taper_length\":40.0}," +
                    "\"samples\":[[1,2,1.0]]}]}]}").toByteArray(),
            )
            it.closeEntry()
        }
        val stroke = codec.read(ByteArrayInputStream(out.toByteArray())).pages[0].items[0] as Stroke
        assertTrue(stroke.config.taperEnabled)
        assertEquals(0.30, stroke.config.taperMinFactor, 1e-9)   // legacy taper assumes the legacy tip
    }

    @Test fun taperZeroTipWidthRoundTripsAsZero() {
        // A deliberately sharp taper (0% tip) must survive as 0, not pick up the legacy default.
        val doc = Document(dpi = 150)
        val page = Page(100.0, 100.0)
        page.items.add(Stroke(Tool.TAPER, ToolConfig(taperEnabled = true, taperMinFactor = 0.0), mutableListOf(Sample(1.0, 2.0, 1.0), Sample(8.0, 9.0, 1.0))))
        doc.pages.add(page)
        val stroke = roundTrip(doc).pages[0].items[0] as Stroke
        assertTrue(stroke.config.taperEnabled)
        assertEquals(0.0, stroke.config.taperMinFactor, 1e-9)
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
        assertNull(doc.pdfFile)
    }

    @Test fun itemKeyOrderDoesNotMatter() {
        // The streaming parser must not depend on "kind" (or any key) coming first.
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use {
            it.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
            it.write(
                ("{\"pages\":[{\"items\":[" +
                    "{\"samples\":[[1,2,0.5]],\"tool\":\"pen\",\"kind\":\"stroke\"}]," +
                    "\"width\":100,\"height\":100}],\"format\":\"xnote\"}").toByteArray(),
            )
            it.closeEntry()
        }
        val doc = codec.read(ByteArrayInputStream(out.toByteArray()))
        val stroke = doc.pages[0].items[0] as Stroke
        assertEquals(Tool.PEN, stroke.tool)
        assertEquals(Sample(1.0, 2.0, 0.5), stroke.samples[0])
    }

    @Test fun legacyFileInkCompactsOnLoad() {
        // No "writer" field = written before pen-up sample reduction shipped: dense ink is
        // compacted once at load. 100 collinear samples 0.2 px apart carry nothing the ribbon
        // needs beyond the ends and the EMA gap cap. Reads the master switch like the two pen-up
        // paths do, so the test turns it on for itself rather than depending on how it is left.
        val samples = (0 until 100).joinToString(",") { "[${it * 0.2},5,1]" }
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use {
            it.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
            it.write(
                ("{\"format\":\"xnote\",\"version\":1,\"pages\":[{\"width\":100,\"height\":100,\"items\":[" +
                    "{\"kind\":\"stroke\",\"tool\":\"pen\",\"samples\":[$samples]}]}]}").toByteArray(),
            )
            it.closeEntry()
        }
        val was = StrokeSimplify.enabled
        StrokeSimplify.enabled = true
        val doc = try {
            codec.read(ByteArrayInputStream(out.toByteArray()))
        } finally {
            StrokeSimplify.enabled = was
        }
        val stroke = doc.pages[0].items[0] as Stroke
        assertTrue(doc.compactedOnLoad)
        assertTrue("dense legacy ink should compact", stroke.samples.size < 30)
        assertEquals(Sample(0.0, 5.0, 1.0), stroke.samples.first())
        assertEquals(19.8, stroke.samples.last().x, 1e-4) // samples are stored as float offsets
    }

    @Test fun currentWriterInkIsNotRecompacted() {
        // A file this codec wrote carries writer=43, so its (already pen-up-reduced) ink must
        // load back sample-for-sample.
        val doc = Document(dpi = 150)
        val page = Page(100.0, 100.0)
        page.items.add(
            Stroke(
                Tool.PEN,
                ToolConfig(),
                (0 until 100).mapTo(mutableListOf()) { Sample(it * 0.2, 5.0, 1.0) },
            ),
        )
        doc.pages.add(page)
        val loaded = roundTrip(doc)
        assertFalse(loaded.compactedOnLoad)
        assertEquals(100, (loaded.pages[0].items[0] as Stroke).samples.size)
    }

    @Test fun imageKeepsItsZOrderSlot() {
        // Image files stream out of the zip after the manifest, so image items are
        // inserted late; they must still land between the items they were drawn between.
        val doc = Document(dpi = 150)
        val page = Page(200.0, 200.0)
        page.items.add(Stroke(Tool.PEN, ToolConfig(), mutableListOf(Sample(1.0, 1.0, 1.0))))
        page.items.add(ImageItem(ImageData(imageFile(), 8, 8), Rect(0.0, 0.0, 8.0, 8.0)))
        page.items.add(TextItem(Pt(5.0, 5.0), text = "top", measurer = FakeTextMeasurer()))
        doc.pages.add(page)

        val items = roundTrip(doc).pages[0].items
        assertTrue(items[0] is Stroke)
        assertTrue(items[1] is ImageItem)
        assertTrue(items[2] is TextItem)
    }

    @Test fun stringEscapesAndExponentNumbersParse() {
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use {
            it.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
            it.write(
                ("{\"format\":\"xnote\",\"pages\":[{\"width\":100,\"height\":100,\"items\":[" +
                    "{\"kind\":\"text\",\"pos\":[1,2],\"text\":\"a\\\"b\\\\c\\n\\u00e9\\ud83d\\ude00\"}," +
                    "{\"kind\":\"stroke\",\"samples\":[[1.5e2,-2E-1,1]]}]}]}").toByteArray(),
            )
            it.closeEntry()
        }
        val doc = codec.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals("a\"b\\c\né😀", (doc.pages[0].items[0] as TextItem).text)
        val s = (doc.pages[0].items[1] as Stroke).samples[0]
        assertEquals(150.0, s.x, 1e-9)
        assertEquals(-0.2, s.y, 1e-9)
    }

    @Test fun malformedManifestJsonRejectsAsNonXnote() {
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use {
            it.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
            it.write("{\"format\":\"xnote\",\"pages\":[{".toByteArray())
            it.closeEntry()
        }
        assertThrows(XNoteFormatException::class.java) {
            codec.read(ByteArrayInputStream(out.toByteArray()))
        }
    }

    @Test fun nullFillAndShapePointsRoundTrip() {
        val doc = Document(dpi = 150)
        val page = Page(200.0, 200.0)
        page.items.add(ShapeItem(ShapeKind.LINE, Pt(0.0, 0.0), Pt(50.0, 30.0), Rgba(1, 2, 3, 255), 2.0, null))
        page.items.add(ShapeItem.poly(ShapeKind.POLYGON, listOf(Pt(0.0, 0.0), Pt(10.0, 0.0), Pt(5.0, 8.0)), Rgba(9, 8, 7, 255), 1.0, Rgba(4, 5, 6, 32), false, 0.6))
        doc.pages.add(page)

        val items = roundTrip(doc).pages[0].items
        val line = items[0] as ShapeItem
        assertNull(line.fillRgba)
        val poly = items[1] as ShapeItem
        assertEquals(ShapeKind.POLYGON, poly.shape)
        assertEquals(3, poly.vertices()!!.size)
        assertEquals(Rgba(4, 5, 6, 32), poly.fillRgba)
    }

    @Test fun dashedShapeRoundTrip() {
        val doc = Document(dpi = 150)
        val page = Page(200.0, 200.0)
        page.items.add(
            ShapeItem(
                ShapeKind.RECTANGLE, Pt(0.0, 0.0), Pt(50.0, 30.0), Rgba(1, 2, 3, 255), 2.0, null,
                dashed = true, dashLength = 14.0, dashGap = 6.0,
            ),
        )
        page.items.add(
            ShapeItem.poly(
                ShapeKind.POLYLINE, listOf(Pt(0.0, 0.0), Pt(10.0, 0.0), Pt(5.0, 8.0)), Rgba(9, 8, 7, 255),
                1.0, null, false, 0.6, dashed = true, dashLength = 3.0, dashGap = 2.0,
            ),
        )
        page.items.add(ShapeItem(ShapeKind.LINE, Pt(0.0, 0.0), Pt(50.0, 30.0), Rgba(1, 2, 3, 255), 2.0, null))
        doc.pages.add(page)

        val items = roundTrip(doc).pages[0].items
        val rect = items[0] as ShapeItem
        assertTrue(rect.dashed)
        assertEquals(14.0, rect.dashLength, 1e-9)
        assertEquals(6.0, rect.dashGap, 1e-9)
        val poly = items[1] as ShapeItem
        assertTrue(poly.dashed)
        assertEquals(3.0, poly.dashLength, 1e-9)
        assertEquals(2.0, poly.dashGap, 1e-9)
        val line = items[2] as ShapeItem
        assertFalse(line.dashed)
    }

    @Test fun manifestBytesMatchTheHistoricalForm() {
        // The streaming writer must emit the form the org.json DOM produced on Android
        // (key order, integral doubles as longs, escaped slashes); the only departure is
        // that sample values serialize rounded (x/y 2dp, pressure 3dp). The writer stamp is
        // the versionCode, so it is read from the codec rather than typed in: spelled out it
        // turns every release bump into a failing test.
        val doc = Document(dpi = 150)
        doc.style = PageStyle(template = PagePattern.LINES.id, spacing = 48.0)
        doc.bookmarks.add(Bookmark(0, "Intro"))
        val page = Page(100.0, 200.0)
        page.items.add(
            Stroke(
                Tool.SPEED,
                ToolConfig(baseWidth = 3.5, pressureEnabled = false, pressureMinFactor = 0.4, directionStrength = 0.0, rgba = Rgba(1, 2, 3, 255), speedStrength = 0.8),
                mutableListOf(Sample(1.5, 2.0, 1.0, 0.0), Sample(3.0, 4.25, 0.5, 16.0), Sample(1.23456789, 2.3459999, 0.87654321, 33.0)),
                2.5,
            ),
        )
        page.items.add(TextItem(Pt(10.0, 20.0), width = 250.0, text = "a/b\n\"c\"", rgba = Rgba(9, 8, 7, 255), pointSize = 13.0, measurer = FakeTextMeasurer()))
        page.items.add(ShapeItem(ShapeKind.LINE, Pt(0.0, 0.0), Pt(50.0, 30.0), Rgba(5, 6, 7, 255), 2.0, null))
        doc.pages.add(page)

        val out = ByteArrayOutputStream()
        codec.write(doc, out)
        var manifest: String? = null
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (e.name == "manifest.json") manifest = String(zis.readBytes(), Charsets.UTF_8)
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        assertEquals(
            "{\"format\":\"xnote\",\"version\":1,\"writer\":${DocumentCodec.WRITER},\"dpi\":150,\"has_pdf\":false," +
                "\"bookmarks\":[{\"page\":0,\"label\":\"Intro\"}]," +
                "\"pages\":[{\"width\":100,\"height\":200,\"pdf_page\":null,\"items\":[" +
                "{\"kind\":\"stroke\",\"tool\":\"speed\",\"config\":{\"base_width\":3.5," +
                "\"pressure_enabled\":false,\"pressure_min_factor\":0.4,\"direction_strength\":0," +
                "\"rgba\":[1,2,3,255],\"speed_strength\":0.8}," +
                "\"samples\":[[1.5,2,1,0],[3,4.25,0.5,16],[1.23,2.35,0.877,33]],\"speed_scale\":2.5}," +
                "{\"kind\":\"text\",\"pos\":[10,20],\"width\":250,\"text\":\"a\\/b\\n\\\"c\\\"\"," +
                "\"rgba\":[9,8,7,255],\"point_size\":13}," +
                "{\"kind\":\"shape\",\"shape\":\"line\",\"start\":[0,0],\"end\":[50,30]," +
                "\"stroke_rgba\":[5,6,7,255],\"stroke_width\":2,\"fill_rgba\":null}]}]," +
                "\"style\":{\"pattern\":\"lines\",\"spacing\":48}}",
            manifest,
        )

        // And the escaped slash must come back out as a plain one.
        val back = codec.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals("a/b\n\"c\"", (back.pages[0].items[1] as TextItem).text)
    }

    @Test fun samplesRoundTripAtWritePrecision() {
        // Samples serialize rounded to 0.01 px / 0.001 pressure; the read-back must land
        // exactly on the rounded values (and a re-save of those is then byte-stable).
        val doc = Document(dpi = 150)
        val page = Page(100.0, 100.0)
        page.items.add(
            Stroke(
                Tool.PEN,
                ToolConfig(),
                mutableListOf(Sample(369.6723697692391, 33.3059117626799, 0.031501833349466324)),
            ),
        )
        doc.pages.add(page)

        // Samples are held as float offsets, so a read-back lands on the float nearest the written
        // decimal rather than on the decimal itself. That is ~1e-5 here, three orders below the
        // 0.01 the format stores, so it cannot change what the next write emits.
        val once = roundTrip(doc)
        val back = (once.pages[0].items[0] as Stroke).samples[0]
        assertEquals(369.67, back.x, 1e-4)
        assertEquals(33.31, back.y, 1e-4)
        assertEquals(0.032, back.pressure, 1e-6)

        // The property that actually matters: writing is a fixed point, so a note re-saved
        // untouched does not drift sample by sample.
        val twice = (roundTrip(once).pages[0].items[0] as Stroke).samples[0]
        assertEquals(back.x, twice.x, 0.0)
        assertEquals(back.y, twice.y, 0.0)
        assertEquals(back.pressure, twice.pressure, 0.0)
    }

    @Test fun pageStyleRoundTrips() {
        val doc = Document(dpi = 150)
        doc.style = PageStyle(template = PagePattern.LINES.id, spacing = 48.0) // document-wide ("all pages")
        val page = Page(1240.0, 1754.0)
        page.style = PageStyle(
            pageColor = Rgba(20, 20, 20),
            template = PagePattern.GRID.id,
            patternColor = Rgba(100, 100, 100, 120),
            spacing = 32.0,
        )
        doc.pages.add(page)

        val back = roundTrip(doc)

        assertEquals(PagePattern.LINES.id, back.style.template)
        assertEquals(48.0, back.style.spacing!!, 1e-9)
        assertNull(back.style.pageColor) // unset fields stay null (inherit)
        val s = back.pages[0].style
        assertEquals(Rgba(20, 20, 20), s.pageColor)
        assertEquals(PagePattern.GRID.id, s.template)
        assertEquals(Rgba(100, 100, 100, 120), s.patternColor)
        assertEquals(32.0, s.spacing!!, 1e-9)
    }

    @Test fun embeddedTemplatesRoundTripWithTheirParameters() {
        val text = """{ "xtemplate": 1, "name": "Staff", "items": [] }"""
        val doc = Document(dpi = 150)
        doc.style = PageStyle(template = "0123456789abcdef", accentColor = Rgba(1, 2, 3, 4), params = mapOf("gap" to 2.5))
        doc.templates = mapOf("0123456789abcdef" to text, "fedcba9876543210" to "unused")
        val page = Page(100.0, 100.0)
        page.style = PageStyle(colors = mapOf("frame" to Rgba(9, 8, 7)))
        doc.pages.add(page)

        val back = roundTrip(doc)

        assertEquals("0123456789abcdef", back.style.template)
        assertEquals(Rgba(1, 2, 3, 4), back.style.accentColor)
        assertEquals(mapOf("gap" to 2.5), back.style.params)
        assertEquals(mapOf("frame" to Rgba(9, 8, 7)), back.pages[0].style.colors)
        // Only templates a style names are carried.
        assertEquals(mapOf("0123456789abcdef" to text), back.templates)
    }

    @Test fun builtInTemplatesKeepTheOldPatternName() {
        val doc = Document(dpi = 150)
        doc.style = PageStyle(template = "dots")
        doc.pages.add(Page(100.0, 100.0))
        val out = ByteArrayOutputStream()
        codec.write(doc, out)
        val manifest = java.util.zip.ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zis ->
            generateSequence { zis.nextEntry }.first { it.name == "manifest.json" }
            zis.readBytes().toString(Charsets.UTF_8)
        }
        assertTrue(manifest.contains("\"pattern\":\"dots\""))
        assertFalse(manifest.contains("\"template\""))
    }

    @Test fun emptyStyleReadsBackEmpty() {
        val doc = Document(dpi = 150)
        doc.pages.add(Page(100.0, 100.0)) // default (empty) styles on page + document
        val back = roundTrip(doc)
        assertTrue(back.style.isEmpty)
        assertTrue(back.pages[0].style.isEmpty)
    }

    @Test fun partialStyleFieldsTakeDefaults() {
        // A page whose style sets only the pattern: every other field inherits (stays null), and a
        // manifest with no document-level "style" reads back an empty document style.
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use {
            it.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
            it.write(
                ("{\"format\":\"xnote\",\"pages\":[{\"width\":100,\"height\":100," +
                    "\"style\":{\"pattern\":\"dots\"},\"items\":[]}]}").toByteArray(),
            )
            it.closeEntry()
        }
        val doc = codec.read(ByteArrayInputStream(out.toByteArray()))
        val s = doc.pages[0].style
        assertEquals(PagePattern.DOTS.id, s.template)
        assertNull(s.pageColor)
        assertNull(s.patternColor)
        assertNull(s.spacing)
        assertTrue(doc.style.isEmpty)
    }

    @Test fun marginsRoundTripPerPageAndPerDocument() {
        val doc = Document(pages = mutableListOf(Page(400.0, 800.0), Page(400.0, 800.0)))
        doc.margins = PageMargins(left = 0.25, bottom = 0.4)
        doc.pages[1].margins = PageMargins(top = 0.1)

        val read = roundTrip(doc)

        assertEquals(PageMargins(left = 0.25, bottom = 0.4), read.margins)
        assertTrue(read.pages[0].margins.isEmpty)
        assertEquals(PageMargins(top = 0.1), read.pages[1].margins)
    }

    @Test fun noMarginsWritesNoMarginsObject() {
        val out = ByteArrayOutputStream()
        codec.write(Document(pages = mutableListOf(Page(400.0, 800.0))), out)
        assertFalse(manifestOf(out.toByteArray()).contains("margins"))
    }

    @Test fun outOfRangeMarginsAreClampedOnRead() {
        val doc = Document(pages = mutableListOf(Page(400.0, 800.0)))
        doc.pages[0].margins = PageMargins(left = 9.0, right = -3.0)
        val read = roundTrip(doc)
        assertEquals(1.0, read.pages[0].margins.left!!, 1e-9)
        assertEquals(0.0, read.pages[0].margins.right!!, 1e-9)
    }

    @Test fun aPageWithNoAreaLoadsAtItsNeighboursSize() {
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use {
            it.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
            it.write(
                ("{\"format\":\"xnote\",\"dpi\":150,\"pages\":[" +
                    "{\"width\":0,\"height\":0,\"pdf_page\":0}," +
                    "{\"width\":1275,\"height\":1650,\"pdf_page\":1}," +
                    "{\"width\":0,\"height\":0,\"pdf_page\":2}," +
                    "{\"width\":\"NaN\",\"height\":-1}]}").toByteArray(),
            )
            it.closeEntry()
        }
        val pages = codec.read(ByteArrayInputStream(out.toByteArray())).pages
        val (a4w, a4h) = PageSize.A4.pixels(Orientation.PORTRAIT, 150)
        assertEquals(a4w, pages[0].width, 1e-9)
        assertEquals(a4h, pages[0].height, 1e-9)
        assertNull(pages[0].pdfPage)
        assertEquals(1, pages[1].pdfPage)
        for (page in pages.drop(2)) {
            assertEquals(1275.0, page.width, 1e-9)
            assertEquals(1650.0, page.height, 1e-9)
            assertNull(page.pdfPage)
        }
    }
}
