package com.xnotes.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class NewPageSizeTest {

    @Test fun aCustomChoiceIsTakenAsTypedAndANamedOneTurns() {
        val (w, h) = PageSizeChoice(PageSize.CUSTOM, Orientation.LANDSCAPE, 254.0, 127.0).pixels(150)
        assertEquals(1500.0, w, 1e-6)
        assertEquals(750.0, h, 1e-6)
        assertEquals(
            PageSize.A5.pixels(Orientation.LANDSCAPE, 150),
            PageSizeChoice(PageSize.A5, Orientation.LANDSCAPE, 254.0, 127.0).pixels(150),
        )
    }

    @Test fun aPageIsReadBackAsTheNamedSizeItIsElseAsCustom() {
        val (w, h) = PageSize.LETTER.pixels(Orientation.LANDSCAPE, 150)
        assertEquals(PageSizeChoice(PageSize.LETTER, Orientation.LANDSCAPE), PageSizeChoice.of(w, h, 150))
        val (aw, ah) = PageSize.A4.pixels(Orientation.PORTRAIT, 150)
        assertEquals(PageSizeChoice(PageSize.A4, Orientation.PORTRAIT), PageSizeChoice.of(aw, ah, 150))
        val odd = PageSizeChoice.of(1500.0, 750.0, 150)
        assertEquals(PageSize.CUSTOM, odd.size)
        assertEquals(254.0, odd.customWidthMm, 1e-6)
        assertEquals(127.0, odd.customHeightMm, 1e-6)
        assertEquals(1500.0 to 750.0, odd.pixels(150).let { (x, y) -> Math.round(x).toDouble() to Math.round(y).toDouble() })
    }

    @Test fun aBlankPageTakesTheNotesNewPageSizeElseThePageBefore() {
        val doc = Document(dpi = 150)
        val before = Page(800.0, 600.0)
        assertEquals(800.0 to 600.0, doc.blankPageSize(before))
        assertEquals(PageSize.A4.pixels(Orientation.PORTRAIT, 150), doc.blankPageSize(null))

        doc.newPageSize = PageSizeChoice(PageSize.LETTER, Orientation.LANDSCAPE)
        assertEquals(PageSize.LETTER.pixels(Orientation.LANDSCAPE, 150), doc.blankPageSize(before))
        assertEquals(PageSize.LETTER.pixels(Orientation.LANDSCAPE, 150), doc.blankPageSize(null))
    }

    @Test fun copiesKeepTheNewPageSize() {
        val doc = Document(pages = mutableListOf(Page(100.0, 200.0)))
        doc.newPageSize = PageSizeChoice(PageSize.A5, Orientation.LANDSCAPE)
        assertEquals(doc.newPageSize, doc.snapshot().newPageSize)
        assertEquals(doc.newPageSize, doc.deepCopy(com.xnotes.core.FakeTextMeasurer()).newPageSize)
    }
}
