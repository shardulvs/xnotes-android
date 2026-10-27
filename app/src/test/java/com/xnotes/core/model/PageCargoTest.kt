package com.xnotes.core.model

import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageCargoTest {

    private val measurer = FakeTextMeasurer()

    private fun stroke(vararg pts: Pt) =
        Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), pts.map { Sample(it.x, it.y, 1.0) }.toMutableList())

    private fun pill(rid: Int) =
        AudioItem(AudioData(java.io.File("a"), "m4a", 1000, ByteArray(0)), Rect(0.0, 0.0, 40.0, 10.0), "rec", rid, measurer)

    private fun note(vararg pages: Page, dpi: Int = 150) = Document(pages = pages.toMutableList(), dpi = dpi)

    @Test fun pagesKeepTheirLookUnderAnotherNotesStyle() {
        val src = note(Page(100.0, 100.0), Page(100.0, 100.0, style = PageStyle(template = PageTemplates.NONE)))
        src.style = PageStyle(pageColor = Rgba(1, 2, 3, 255), template = "lines", spacing = 40.0)
        src.margins = PageMargins(left = 0.1)
        val cargo = PageCargo.pack(src, listOf(0, 1), measurer)
        val target = note(Page(100.0, 100.0)).also { it.style = PageStyle(template = "grid") }
        val (ruled, blank) = cargo.unpack(target, null, measurer)
        assertEquals("lines", ruled.style.template)
        assertEquals(40.0, ruled.style.spacing)
        assertEquals(Rgba(1, 2, 3, 255), ruled.style.pageColor)
        assertEquals(PageMargins(left = 0.1), ruled.margins)
        // A page that showed no ruling keeps showing none, and the note's spacing is not its own.
        assertEquals(PageTemplates.NONE, blank.style.template)
        assertNull(blank.style.spacing)
    }

    @Test fun pdfPagesAreCountedInTheCargosOwnPdfThenFromWhereItJoins() {
        val src = note(Page(100.0, 100.0, pdfPage = 7), Page(100.0, 100.0), Page(100.0, 100.0, pdfPage = 3), Page(100.0, 100.0, pdfPage = 7))
        val cargo = PageCargo.pack(src, listOf(3, 0, 1, 2), measurer)
        assertEquals(listOf(7, 3), cargo.pdfIndices)
        assertEquals(listOf(7, null, 3, 7), cargo.unpackHome(src, measurer).map { it.pdfPage })
        val landed = cargo.unpack(note(Page(10.0, 10.0)), pdfBase = 5, measurer = measurer)
        assertEquals(listOf(5, null, 6, 5), landed.map { it.pdfPage })
        val blank = cargo.unpack(note(Page(10.0, 10.0)), pdfBase = null, measurer = measurer)
        assertTrue(blank.all { it.pdfPage == null })
    }

    @Test fun bookmarksAndTheCoverAreCarried() {
        val cover = Page(100.0, 100.0).also { it.cover = true }
        val src = note(cover, Page(100.0, 100.0), Page(100.0, 100.0))
        src.bookmarks.add(Bookmark(2, "two"))
        src.bookmarks.add(Bookmark(1, "one"))
        val cargo = PageCargo.pack(src, listOf(0, 2), measurer)
        assertTrue(cargo.cover)
        assertEquals(listOf("two" to 1), cargo.bookmarks.map { it.label to it.page })
        assertFalse(PageCargo.pack(src, listOf(1, 2), measurer).cover)
        // The copies themselves are ordinary pages; whoever lands them decides about a cover.
        assertFalse(cargo.unpack(note(), null, measurer).first().cover)
    }

    @Test fun inkTiedToARecordingLeftBehindLosesTheTie() {
        val kept = stroke(Pt(0.0, 0.0), Pt(1.0, 1.0)).also { it.sync = SyncMark(2, 10) }
        val orphan = stroke(Pt(0.0, 0.0), Pt(1.0, 1.0)).also { it.sync = SyncMark(9, 10) }
        val src = note(Page(100.0, 100.0, items = mutableListOf(pill(2), kept, orphan)), Page(100.0, 100.0, items = mutableListOf(pill(9))))
        val landed = PageCargo.pack(src, listOf(0), measurer).unpack(note(Page(10.0, 10.0, items = mutableListOf(pill(2)))), null, measurer)
        val items = landed.single().items
        val newPill = items.filterIsInstance<AudioItem>().single()
        assertTrue(newPill.rid != 2)
        assertEquals(newPill.rid, items[1].sync?.rid)
        assertNull(items[2].sync)
    }

    @Test fun goingBackHomeThePagesAreAsTheyWere() {
        val ink = stroke(Pt(0.0, 0.0), Pt(1.0, 1.0)).also { it.sync = SyncMark(9, 10) }
        val src = note(Page(100.0, 100.0, items = mutableListOf(ink)), Page(100.0, 100.0, items = mutableListOf(pill(9))))
        src.style = PageStyle(template = "lines")
        val home = PageCargo.pack(src, listOf(0), measurer).unpackHome(src, measurer).single()
        // Still following the note-wide style, and still tied to the recording on the other page.
        assertNull(home.style.template)
        assertEquals(9, home.items.single().sync?.rid)
    }

    @Test fun pagesAreRescaledToTheTargetsDpi() {
        val ink = stroke(Pt(10.0, 20.0), Pt(30.0, 40.0))
        val src = note(Page(150.0, 300.0, items = mutableListOf(ink), style = PageStyle(spacing = 30.0)), dpi = 150)
        val landed = PageCargo.pack(src, listOf(0), measurer).unpack(note(dpi = 300), null, measurer).single()
        assertEquals(300.0, landed.width, 1e-9)
        assertEquals(600.0, landed.height, 1e-9)
        assertEquals(60.0, landed.style.spacing!!, 1e-9)
        val s = (landed.items.single() as Stroke).sampleAt(1)
        assertEquals(60.0, s.x, 1e-3)
        assertEquals(80.0, s.y, 1e-3)
        // The source is untouched.
        assertEquals(30.0, ink.sampleAt(1).x, 1e-3)
    }

    @Test fun templatesGoAlongWithoutReplacingTheTargetsOwn() {
        val src = note(Page(100.0, 100.0, style = PageStyle(template = "mine")), Page(100.0, 100.0, style = PageStyle(template = "theirs")))
        src.templates = mapOf("mine" to "<a/>", "theirs" to "<b/>", "unused" to "<c/>")
        val target = note().also { it.templates = mapOf("theirs" to "<own/>") }
        PageCargo.pack(src, listOf(0, 1), measurer).unpack(target, null, measurer)
        assertEquals(mapOf("mine" to "<a/>", "theirs" to "<own/>"), target.templates)
    }
}
