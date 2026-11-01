package com.xnotes.format

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.CutEdge
import com.xnotes.core.model.Document
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Page
import com.xnotes.core.model.Room
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class RoomCodecTest {

    private val notes = DocumentCodec(FakeImageCodec(), FakeTextMeasurer())
    private val canvases = CanvasCodec(FakeImageCodec(), FakeTextMeasurer())

    private fun imageFile(): File = File.createTempFile("img", null).apply { writeBytes(byteArrayOf(1, 2, 3, 4)); deleteOnExit() }

    private fun stroke() = Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), listOf(Sample(10.0, 10.0, 1.0), Sample(10.0, 90.0, 1.0)))

    private fun write(doc: Document): ByteArray = ByteArrayOutputStream().also { notes.write(doc, it) }.toByteArray()

    private fun read(bytes: ByteArray): Document =
        notes.read(ByteArrayInputStream(bytes), imageDir = Files.createTempDirectory("rooms-img").toFile())

    private fun manifestOf(bundle: ByteArray): String {
        ZipInputStream(ByteArrayInputStream(bundle)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (e.name == "manifest.json") return zis.readBytes().decodeToString()
                e = zis.nextEntry
            }
        }
        return ""
    }

    @Test fun roomsCutsAndPartsComeBack() {
        val doc = Document()
        val page = Page(200.0, 400.0, rooms = listOf(Room(100.0, 50.0), Room(20.0, 10.0, sideways = true)))
        val ink = stroke().also { it.cuts = listOf(CutEdge(10.0, 40.0, 0.0, 1.0)) }
        val pic = ImageItem(ImageData(imageFile(), 8, 8), Rect(0.0, 0.0, 8.0, 4.0), part = Rect.ltrb(0.0, 0.5, 1.0, 1.0))
        page.items += ink
        page.items += pic
        doc.pages += page
        val back = read(write(doc)).pages.single()
        assertEquals(page.rooms, back.rooms)
        assertEquals(listOf(CutEdge(10.0, 40.0, 0.0, 1.0)), (back.items[0] as Stroke).cuts)
        assertEquals(Rect.ltrb(0.0, 0.5, 1.0, 1.0), (back.items[1] as ImageItem).part)
    }

    @Test fun nothingNewIsWrittenWithoutRoom() {
        val doc = Document()
        doc.pages += Page(200.0, 400.0).also { it.items += stroke() }
        val manifest = manifestOf(write(doc))
        assertFalse(manifest.contains("rooms"))
        assertFalse(manifest.contains("cuts"))
        assertFalse(manifest.contains("part"))
    }

    @Test fun brokenRoomIsLeftOut() {
        val manifest = """
            {"format":"xnote","version":1,"dpi":300,"has_pdf":false,"bookmarks":[],
             "pages":[{"width":200,"height":400,"pdf_page":null,"items":[],
               "rooms":[{"at":10,"size":-5},{"at":50,"size":20},{"at":55,"size":5},{"size":3},"x",{"at":300,"size":900}]}]}
        """.trimIndent()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(manifest.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        val page = read(out.toByteArray()).pages.single()
        assertEquals(listOf(Room(50.0, 20.0)), page.rooms)
    }

    @Test fun cutInkAndSlicedPicturesKeepTheirEdgesOnACanvas() {
        val doc = InfiniteDocument()
        doc.add(stroke().also { it.cuts = listOf(CutEdge(10.0, 40.0, 0.0, -1.0)) })
        doc.add(ImageItem(ImageData(imageFile(), 8, 8), Rect(0.0, 0.0, 8.0, 4.0), part = Rect.ltrb(0.25, 0.0, 1.0, 1.0)))
        doc.add(ImageItem(ImageData(imageFile(), 8, 8), Rect(0.0, 0.0, 8.0, 8.0)))
        val bytes = ByteArrayOutputStream().also { canvases.write(doc, it) }.toByteArray()
        val back = canvases.read(ByteArrayInputStream(bytes), imageDir = Files.createTempDirectory("rooms-canvas").toFile())
        assertEquals(listOf(CutEdge(10.0, 40.0, 0.0, -1.0)), (back.items[0] as Stroke).cuts)
        assertEquals(Rect.ltrb(0.25, 0.0, 1.0, 1.0), (back.items[1] as ImageItem).part)
        assertNull((back.items[2] as ImageItem).part)
        assertTrue((back.items[0] as Stroke).contains(Pt(10.0, 60.0)))
    }
}
