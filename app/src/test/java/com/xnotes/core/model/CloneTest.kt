package com.xnotes.core.model

import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Pt
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class CloneTest {

    private fun denseDoc(): Document {
        val doc = Document(dpi = 150)
        repeat(3) { p ->
            val page = Page(100.0, 200.0)
            repeat(4) { i ->
                page.items.add(
                    Stroke(
                        Tool.PEN,
                        ToolConfig(),
                        mutableListOf(Sample(p + i + 0.5, 2.0, 1.0), Sample(p + i + 1.5, 3.0, 0.5)),
                    ),
                )
            }
            page.items.add(TextItem(Pt(1.0, 2.0), text = "t$p", measurer = FakeTextMeasurer()))
            doc.pages.add(page)
        }
        doc.bookmarks.add(Bookmark(1, "b"))
        return doc
    }

    @Test fun deepCopyGivesEveryStrokeStorageOfItsOwn() {
        val doc = denseDoc()
        val copy = doc.deepCopy(FakeTextMeasurer())

        assertEquals(doc.pages.size, copy.pages.size)
        for (pi in doc.pages.indices) {
            val a = doc.pages[pi]
            val b = copy.pages[pi]
            assertEquals(a.items.size, b.items.size)
            for (ii in a.items.indices) {
                val sa = a.items[ii] as? Stroke ?: continue
                val sb = b.items[ii] as Stroke
                assertNotSame(sa, sb) // items are cloned...
                assertEquals(sa.samples, sb.samples) // ...carrying the same sample values...
                // ...over storage of their own: clearing the original leaves the copy intact.
                val copied = sb.samples.toList()
                sa.setSamples(emptyList())
                assertEquals(copied, sb.samples)
            }
        }
        assertEquals(1, copy.bookmarks.size)
        assertEquals("b", copy.bookmarks[0].label)
    }

    @Test fun snapshotSharesTheItemsButNotTheLists() {
        val doc = denseDoc()
        val snap = doc.snapshot()

        assertEquals(doc.pages.size, snap.pages.size)
        for (pi in doc.pages.indices) {
            assertNotSame(doc.pages[pi], snap.pages[pi]) // its own page...
            assertNotSame(doc.pages[pi].items, snap.pages[pi].items) // ...and its own list...
            for (ii in doc.pages[pi].items.indices) {
                assertSame(doc.pages[pi].items[ii], snap.pages[pi].items[ii]) // ...over the live items
            }
        }
    }

    @Test fun snapshotSurvivesPagesAndItemsGoingAway() {
        val doc = denseDoc()
        val snap = doc.snapshot()

        doc.pages[1].items.clear()
        doc.pages.removeAt(2)

        assertEquals(3, snap.pages.size)
        assertEquals(5, snap.pages[1].items.size)
    }

    @Test fun copiesKeepThePagesMarkups() {
        val doc = denseDoc()
        val m = TextMarkup(
            TextMarkup.newId(), MarkupType.UNDERLINE, Rgba(255, 92, 92), 1.0,
            listOf(com.xnotes.core.pdf.TextQuad(1f, 2f, 3f, 4f, 0)), "x", null, 1L, 1L,
        )
        doc.pages[1].markups = listOf(m)

        assertSame(m, doc.pages[1].deepCopy(FakeTextMeasurer()).markups.single())
        assertSame(m, doc.deepCopy(FakeTextMeasurer()).pages[1].markups.single())
        val snap = doc.snapshot()
        doc.pages[1].markups = emptyList()
        assertSame(m, snap.pages[1].markups.single())
    }

    /** The flow has no volatile-publish discipline, so it is the one thing still copied. */
    @Test fun snapshotCopiesTheFlow() {
        val doc = denseDoc()
        doc.flow.paragraphs.clear()
        doc.flow.paragraphs.add(com.xnotes.core.text.Paragraph(mutableListOf(com.xnotes.core.text.Run("hi"))))
        val snap = doc.snapshot()

        doc.flow.paragraphs.clear()

        assertEquals(1, snap.flow.paragraphs.size)
        assertEquals("hi", snap.flow.paragraphs[0].runs[0].text)
    }
}
