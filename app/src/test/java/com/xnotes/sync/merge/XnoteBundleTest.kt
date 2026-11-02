package com.xnotes.sync.merge

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Bookmark
import com.xnotes.core.model.Document
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import com.xnotes.format.DocumentCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class XnoteBundleTest {
    private val codec = DocumentCodec(FakeImageCodec(), FakeTextMeasurer())

    private fun encode(doc: Document): ByteArray = ByteArrayOutputStream().also { codec.write(doc, it) }.toByteArray()

    private fun decode(bytes: ByteArray): Document =
        codec.read(ByteArrayInputStream(bytes), imageDir = Files.createTempDirectory("merge-img").toFile())

    private fun stroke(x: Double) = Stroke(
        Tool.PEN, ToolConfig(2.0, false, 0.4, 0.6, Rgba(0, 0, 0, 255)),
        mutableListOf(Sample(x, 10.0, 0.5), Sample(x + 5, 20.0, 0.5)),
    )

    private fun image(vararg bytes: Byte): ImageItem {
        val f = File.createTempFile("img", null).apply { writeBytes(bytes); deleteOnExit() }
        return ImageItem(ImageData(f, 8, 8), Rect(0.0, 0.0, 8.0, 8.0))
    }

    private fun page(vararg xs: Double) = Page(100.0, 100.0).apply { xs.forEach { items.add(stroke(it)) } }

    /** A note of [n] pages, each with one stroke at its own x so the pages differ. */
    private fun note(n: Int) = Document(dpi = 150).apply { repeat(n) { pages.add(page(it * 10.0)) } }

    private fun fingerprints(bytes: ByteArray) = XnoteBundle.parse(bytes)!!.fingerprints()

    @Test fun unchangedPagesKeepTheirFingerprintThroughALoadAndSave() {
        val doc = note(3)
        doc.pages[1].items.add(image(1, 2, 3))
        val first = encode(doc)
        val second = encode(decode(first))
        assertEquals(fingerprints(first).pages, fingerprints(second).pages)
        assertEquals(fingerprints(first).doc, fingerprints(second).doc)
    }

    @Test fun anImageAddedEarlierDoesNotChangeLaterPages() {
        val doc = note(3)
        doc.pages[2].items.add(image(7, 7))
        val before = fingerprints(encode(doc))
        doc.pages[0].items.add(image(9, 9, 9))
        val after = fingerprints(encode(doc))
        assertNotEquals(before.pages[0], after.pages[0])
        assertEquals(before.pages[1], after.pages[1])
        assertEquals(before.pages[2], after.pages[2])
    }

    @Test fun editsOnDifferentPagesAreMerged() {
        val base = encode(note(3))
        val baseF = fingerprints(base)
        val local = decode(base).apply { pages[0].items.add(stroke(50.0)) }
        val remote = decode(base).apply { pages[2].items.add(image(4, 5, 6)) }
        val merged = XnoteBundle.merge(baseF.pages, baseF.doc, encode(local), encode(remote))
        assertNotNull(merged)
        assertEquals(emptyList<Int>(), merged!!.keptBothPages)
        val doc = decode(merged.bytes)
        assertEquals(3, doc.pages.size)
        assertEquals(2, doc.pages[0].items.size)
        assertEquals(1, doc.pages[1].items.size)
        val img = doc.pages[2].items.filterIsInstance<ImageItem>().single()
        assertArrayEquals(byteArrayOf(4, 5, 6), img.image.file.readBytes())
    }

    @Test fun imagesFromBothSidesSurviveTheMerge() {
        val base = encode(note(2))
        val baseF = fingerprints(base)
        val local = decode(base).apply { pages[0].items.add(image(1)) }
        val remote = decode(base).apply { pages[1].items.add(image(2)) }
        val doc = decode(XnoteBundle.merge(baseF.pages, baseF.doc, encode(local), encode(remote))!!.bytes)
        assertArrayEquals(byteArrayOf(1), doc.pages[0].items.filterIsInstance<ImageItem>().single().image.file.readBytes())
        assertArrayEquals(byteArrayOf(2), doc.pages[1].items.filterIsInstance<ImageItem>().single().image.file.readBytes())
    }

    @Test fun aPageChangedOnBothSidesIsKeptTwice() {
        val base = encode(note(2))
        val baseF = fingerprints(base)
        val local = decode(base).apply { pages[0].items.add(stroke(60.0)) }
        val remote = decode(base).apply { pages[0].items.add(stroke(70.0)) }
        val merged = XnoteBundle.merge(baseF.pages, baseF.doc, encode(local), encode(remote))!!
        assertEquals(listOf(1, 2), merged.keptBothPages)
        val doc = decode(merged.bytes)
        assertEquals(3, doc.pages.size)
        assertEquals(60.0, (doc.pages[0].items[1] as Stroke).samples[0].x, 0.01)
        assertEquals(70.0, (doc.pages[1].items[1] as Stroke).samples[0].x, 0.01)
    }

    @Test fun pagesAddedOnBothSidesAreAllKept() {
        val base = encode(note(2))
        val baseF = fingerprints(base)
        val local = decode(base).apply { pages.add(0, page(500.0)) }
        val remote = decode(base).apply { pages.add(page(600.0)) }
        val doc = decode(XnoteBundle.merge(baseF.pages, baseF.doc, encode(local), encode(remote))!!.bytes)
        assertEquals(listOf(500.0, 0.0, 10.0, 600.0), doc.pages.map { (it.items[0] as Stroke).samples[0].x })
    }

    @Test fun aPageDeletedOnOneSideAndUntouchedOnTheOtherIsDeleted() {
        val base = encode(note(3))
        val baseF = fingerprints(base)
        val local = decode(base).apply { pages.removeAt(1) }
        val remote = decode(base).apply { pages[2].items.add(stroke(80.0)) }
        val doc = decode(XnoteBundle.merge(baseF.pages, baseF.doc, encode(local), encode(remote))!!.bytes)
        assertEquals(2, doc.pages.size)
        assertEquals(2, doc.pages[1].items.size)
    }

    @Test fun bookmarksMakeItFallBack() {
        val base = encode(note(2))
        val baseF = fingerprints(base)
        val local = decode(base).apply { pages[0].items.add(stroke(50.0)); bookmarks.add(Bookmark(1, "B")) }
        val remote = decode(base).apply { pages[1].items.add(stroke(60.0)) }
        assertNull(XnoteBundle.merge(baseF.pages, baseF.doc, encode(local), encode(remote)))
    }

    @Test fun somethingThatIsNotANoteDoesNotParse() {
        assertNull(XnoteBundle.parse(byteArrayOf(1, 2, 3)))
    }
}
