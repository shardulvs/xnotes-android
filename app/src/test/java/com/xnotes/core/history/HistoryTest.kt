package com.xnotes.core.history

import com.xnotes.core.model.ImageData
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Bookmark
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.Document
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Page
import com.xnotes.core.model.RectHandle
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTest {

    private fun strokeAt(x: Double): Stroke =
        Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), mutableListOf(Sample(x, 0.0, 1.0)))

    @Test fun addItemUndoRedo() {
        val page = Page(100.0, 100.0)
        val s = strokeAt(1.0)
        page.items.add(s)
        val cmd = AddItem(page, s)
        cmd.undo()
        assertTrue(page.items.isEmpty())
        cmd.redo()
        assertEquals(1, page.items.size)
        cmd.redo() // idempotent
        assertEquals(1, page.items.size)
    }

    @Test fun eraseItemsUndoRestores() {
        val page = Page(100.0, 100.0)
        val a = strokeAt(1.0)
        val b = strokeAt(2.0)
        page.items.addAll(listOf(a, b))
        val cmd = EraseItems(listOf(page to a, page to b))
        cmd.redo()
        assertTrue(page.items.isEmpty())
        cmd.undo()
        assertEquals(2, page.items.size)
    }

    @Test fun moveItemsRoundTrip() {
        val img = ImageItem(ImageData(java.io.File("test-image"),10, 10), Rect(0.0, 0.0, 10.0, 10.0))
        val cmd = MoveItems(listOf(img), 5.0, 7.0)
        // simulate: the gesture already moved the item, then we push the command
        img.translate(5.0, 7.0)
        cmd.undo()
        assertEquals(Rect(0.0, 0.0, 10.0, 10.0), img.rect)
        cmd.redo()
        assertEquals(Rect(5.0, 7.0, 10.0, 10.0), img.rect)
    }

    @Test fun transferItemsMovesBetweenPagesAndBack() {
        val from = Page(100.0, 100.0)
        val to = Page(100.0, 100.0)
        val img = ImageItem(ImageData(java.io.File("test-image"), 10, 10), Rect(0.0, 90.0, 10.0, 10.0))
        from.items.add(img)
        val cmd = TransferItems(listOf(TransferItems.Transfer(from, to, img, 0.0, -120.0)))

        cmd.redo()
        assertTrue(from.items.isEmpty())
        assertSame(img, to.items.single())
        assertEquals(Rect(0.0, -30.0, 10.0, 10.0), img.rect)
        cmd.redo() // idempotent
        assertEquals(1, to.items.size)
        assertEquals(Rect(0.0, -30.0, 10.0, 10.0), img.rect)

        cmd.undo()
        assertTrue(to.items.isEmpty())
        assertSame(img, from.items.single())
        assertEquals(Rect(0.0, 90.0, 10.0, 10.0), img.rect)
        cmd.undo() // idempotent
        assertEquals(1, from.items.size)
        assertEquals(Rect(0.0, 90.0, 10.0, 10.0), img.rect)
    }

    @Test fun resizeItemRoundTrip() {
        val img = ImageItem(ImageData(java.io.File("test-image"),10, 10), Rect(20.0, 20.0, 40.0, 40.0))
        val cmd = ResizeItem(img, RectHandle(Rect(0.0, 0.0, 10.0, 10.0)), RectHandle(Rect(20.0, 20.0, 40.0, 40.0)))
        cmd.undo()
        assertEquals(Rect(0.0, 0.0, 10.0, 10.0), img.rect)
        cmd.redo()
        assertEquals(Rect(20.0, 20.0, 40.0, 40.0), img.rect)
    }

    @Test fun reorderItemsSnapshots() {
        val page = Page(100.0, 100.0)
        val a = strokeAt(1.0)
        val b = strokeAt(2.0)
        val c = strokeAt(3.0)
        page.items.addAll(listOf(a, b, c))
        val old = page.items.toList()
        val new = listOf(b, c, a) // 'a' brought to front
        val cmd = ReorderItems(page, old, new)
        cmd.redo()
        assertSame(a, page.items.last())
        cmd.undo()
        assertSame(a, page.items.first())
    }

    @Test fun addAndDeletePageCommands() {
        val doc = Document.blank(count = 2)
        val newPage = Page(100.0, 100.0)
        doc.pages.add(1, newPage)
        val add = AddPage(doc, newPage, 1)
        add.undo()
        assertEquals(2, doc.pages.size)
        add.redo()
        assertEquals(3, doc.pages.size)
        assertSame(newPage, doc.pages[1])

        val del = DeletePage(doc, newPage, 1)
        del.redo()
        assertEquals(2, doc.pages.size)
        del.undo()
        assertSame(newPage, doc.pages[1])
    }

    @Test fun addedPageMovesLaterBookmarksAlong() {
        val doc = Document.blank(count = 3)
        doc.bookmarks.add(Bookmark(0, "zero"))
        doc.bookmarks.add(Bookmark(2, "two"))
        val page = Page(100.0, 100.0)
        val add = AddPage(doc, page, 1).also { it.redo() }
        assertSame(page, doc.pages[1])
        assertEquals(listOf(0, 3), doc.bookmarks.map { it.page })
        add.undo()
        assertEquals(listOf(0, 2), doc.bookmarks.map { it.page })
        add.redo()
        assertEquals(listOf(0, 3), doc.bookmarks.map { it.page })
    }

    @Test fun deletedPageTakesItsBookmarksAndUndoBringsThemBack() {
        val doc = Document.blank(count = 4)
        val gone = doc.pages[1]
        doc.bookmarks.add(Bookmark(3, "three"))
        doc.bookmarks.add(Bookmark(1, "one"))
        doc.bookmarks.add(Bookmark(0, "zero"))
        val del = DeletePage(doc, gone, 1).also { it.redo() }
        assertEquals(3, doc.pages.size)
        assertEquals(listOf("three" to 2, "zero" to 0), doc.bookmarks.map { it.label to it.page })
        del.undo()
        assertSame(gone, doc.pages[1])
        assertEquals(listOf("three" to 3, "one" to 1, "zero" to 0), doc.bookmarks.map { it.label to it.page })
    }

    @Test fun deletingSeveralPagesInOneStepKeepsBookmarksStraight() {
        val doc = Document.blank(count = 5)
        val pages = doc.pages.toList()
        doc.bookmarks.add(Bookmark(1, "one"))
        doc.bookmarks.add(Bookmark(2, "two"))
        doc.bookmarks.add(Bookmark(4, "four"))
        // Highest index first, as the side panel deletes.
        val cmd = CompositeCommand(listOf(3, 1).map { DeletePage(doc, pages[it], it).also { c -> c.redo() } })
        assertEquals(listOf("two" to 1, "four" to 2), doc.bookmarks.map { it.label to it.page })
        cmd.undo()
        assertEquals(pages, doc.pages)
        assertEquals(listOf("one" to 1, "two" to 2, "four" to 4), doc.bookmarks.map { it.label to it.page })
        cmd.redo()
        assertEquals(listOf("two" to 1, "four" to 2), doc.bookmarks.map { it.label to it.page })
    }

    @Test fun undoingAnAddedPageTakesABookmarkMadeOnIt() {
        val doc = Document.blank(count = 2)
        val page = Page(100.0, 100.0)
        val add = AddPage(doc, page, 2).also { it.redo() }
        doc.bookmarks.add(Bookmark(2, "new"))
        add.undo()
        assertTrue(doc.bookmarks.isEmpty())
        add.redo()
        assertEquals(listOf("new" to 2), doc.bookmarks.map { it.label to it.page })
    }

    @Test fun movePagesFollowsABookmarkAddedAfterTheMove() {
        val doc = Document.blank(count = 3)
        val (p0, p1, p2) = doc.pages.toList()
        val before = doc.pages.toList()
        val cmd = MovePages(doc, before, listOf(p2, p0, p1)).also { it.redo() }
        doc.bookmarks.add(Bookmark(0, "was last"))
        cmd.undo()
        assertEquals(listOf(2), doc.bookmarks.map { it.page })
    }

    @Test fun aBreakAimedAtAPageStaysWithItAsPagesComeAndGo() {
        val doc = Document.blank(count = 4)
        val pages = doc.pages.toList()
        val para = com.xnotes.core.text.Paragraph(breakBefore = true, breakToPage = 2)
        doc.flow.paragraphs.add(para)
        val add = AddPage(doc, Page(100.0, 100.0), 0).also { it.redo() }
        assertEquals(3, para.breakToPage)
        add.undo()
        assertEquals(2, para.breakToPage)
        val del = DeletePage(doc, pages[2], 2).also { it.redo() }
        // The page that takes the deleted one's place takes its break too, until undo.
        assertEquals(2, para.breakToPage)
        del.undo()
        assertEquals(2, para.breakToPage)
        val earlier = DeletePage(doc, pages[0], 0).also { it.redo() }
        assertEquals(1, para.breakToPage)
        earlier.undo()
        assertEquals(2, para.breakToPage)
        val move = MovePages(doc, pages, listOf(pages[2], pages[0], pages[1], pages[3])).also { it.redo() }
        assertEquals(0, para.breakToPage)
        move.undo()
        assertEquals(2, para.breakToPage)
    }

    @Test fun movedPagesLandTogetherInTheirOrder() {
        val pages = listOf("a", "b", "c", "d", "e")
        assertEquals(listOf("a", "c", "e", "b", "d"), MovePages.order(pages, setOf("b", "d"), 5))
        assertEquals(listOf("b", "d", "a", "c", "e"), MovePages.order(pages, setOf("d", "b"), 0))
        assertEquals(listOf("a", "b", "d", "c", "e"), MovePages.order(pages, setOf("b", "d"), 2))
        assertEquals(pages, MovePages.order(pages, setOf("c"), 3)) // dropped right after itself
        assertEquals(listOf("c", "a", "b", "d", "e"), MovePages.order(pages, setOf("c"), -4))
    }

    @Test fun movePagesUndoesAndCarriesBookmarks() {
        val doc = Document.blank(count = 4)
        val (p0, p1, p2, p3) = doc.pages.toList()
        doc.bookmarks.add(Bookmark(1, "one"))
        doc.bookmarks.add(Bookmark(3, "three"))
        val before = doc.pages.toList()
        val cmd = MovePages(doc, before, MovePages.order(before, listOf(p1), 4))
        cmd.redo()
        assertEquals(listOf(p0, p2, p3, p1), doc.pages)
        assertEquals(listOf(3, 2), doc.bookmarks.map { it.page })
        cmd.redo() // idempotent
        assertEquals(listOf(p0, p2, p3, p1), doc.pages)
        cmd.undo()
        assertEquals(before, doc.pages)
        assertEquals(listOf(1, 3), doc.bookmarks.map { it.page })
    }

    @Test fun movePagesKeepsAPageItNeverKnew() {
        val doc = Document.blank(count = 3)
        val before = doc.pages.toList()
        val cmd = MovePages(doc, before, before.reversed())
        cmd.redo()
        val stray = Page(100.0, 100.0)
        doc.pages.add(1, stray)
        cmd.undo()
        assertEquals(before + stray, doc.pages)
    }

    @Test fun historyStackSemantics() {
        val page = Page(100.0, 100.0)
        val h = History()
        assertFalse(h.canUndo)

        val s = strokeAt(1.0)
        page.items.add(s)
        h.push(AddItem(page, s))
        assertTrue(h.canUndo)
        assertFalse(h.canRedo)

        h.undo()
        assertTrue(page.items.isEmpty())
        assertTrue(h.canRedo)

        h.redo()
        assertEquals(1, page.items.size)

        // a new edit clears the redo branch
        h.undo()
        assertTrue(h.canRedo)
        val s2 = strokeAt(2.0)
        page.items.add(s2)
        h.push(AddItem(page, s2))
        assertFalse(h.canRedo)

        h.clear()
        assertFalse(h.canUndo)
        assertFalse(h.canRedo)
    }

    // --- touched(): the regions an undo has to repaint (see CanvasState.repairInkRegions) ---

    /** A locator for commands that hold items but not pages; [HistoryTest] only ever has one page. */
    private fun onPage(page: Page): (CanvasItem) -> Page? = { page }

    @Test fun addItemTouchesItsOwnPage() {
        val page = Page(100.0, 100.0)
        val s = strokeAt(1.0)
        page.items.add(s)
        val cmd = AddItem(page, s)

        assertEquals(listOf(page to s), cmd.touched(onPage(page)))
        cmd.undo() // still nameable once the item is off the page: that is where it has to be rubbed out
        assertEquals(listOf(page to s), cmd.touched(onPage(page)))
    }

    @Test fun replacePageItemsTouchesOnlyTheDifference() {
        val page = Page(100.0, 100.0)
        val kept = strokeAt(1.0)
        val gone = strokeAt(2.0)
        val added = strokeAt(3.0)
        val cmd = ReplacePageItems(page, listOf(kept, gone), listOf(kept, added))

        val touched = cmd.touched(onPage(page))!!.map { it.second }
        assertEquals(listOf(gone, added), touched) // the untouched majority of the page is not repainted
    }

    @Test fun moveItemsTouchesThePageItSitsOn() {
        val page = Page(100.0, 100.0)
        val img = ImageItem(ImageData(java.io.File("test-image"), 10, 10), Rect(0.0, 0.0, 10.0, 10.0))
        page.items.add(img)
        assertEquals(listOf(page to img), MoveItems(listOf(img), 5.0, 7.0).touched(onPage(page)))
    }

    @Test fun transferItemsTouchesBothPages() {
        val from = Page(100.0, 100.0)
        val to = Page(100.0, 100.0)
        val img = ImageItem(ImageData(java.io.File("test-image"), 10, 10), Rect(0.0, 0.0, 10.0, 10.0))
        val cmd = TransferItems(listOf(TransferItems.Transfer(from, to, img, 0.0, -120.0)))
        assertEquals(listOf(from to img, to to img), cmd.touched(onPage(from)))
    }

    @Test fun pageCommandsCannotSay() {
        // A page insert reflows the text flow across pages, so there is no small region to repair.
        val doc = Document.blank(count = 2)
        assertNull(AddPage(doc, Page(100.0, 100.0), 1).touched { null })
        assertNull(DeletePage(doc, doc.pages[0], 0).touched { null })
    }

    @Test fun compositeGivesUpWhenOneStepDoes() {
        val doc = Document.blank(count = 1)
        val page = doc.pages[0]
        val s = strokeAt(1.0)
        page.items.add(s)
        val known = AddItem(page, s)

        assertEquals(listOf(page to s), CompositeCommand(listOf(known)).touched(onPage(page)))
        assertNull(CompositeCommand(listOf(known, AddPage(doc, Page(100.0, 100.0), 1))).touched(onPage(page)))
    }

    @Test fun theStackDropsTheOldestEditPastItsLimit() {
        val page = Page(100.0, 100.0)
        val h = History(limit = 3)
        val added = (0 until 5).map { strokeAt(it.toDouble()) }
        for (s in added) {
            page.items.add(s)
            h.push(AddItem(page, s))
        }

        // Only the last three are reachable; undoing them all leaves the first two in place.
        repeat(3) { h.undo() }
        assertFalse(h.canUndo)
        assertEquals(listOf(added[0], added[1]), page.items)
    }

    @Test fun peeksReportTheNextCommandWithoutApplyingIt() {
        val page = Page(100.0, 100.0)
        val h = History()
        val s = strokeAt(1.0)
        page.items.add(s)
        val cmd = AddItem(page, s)
        h.push(cmd)

        assertSame(cmd, h.nextUndo)
        assertEquals(1, page.items.size) // peeking must not apply anything
        assertNull(h.nextRedo)

        h.undo()
        assertNull(h.nextUndo)
        assertSame(cmd, h.nextRedo)
    }
}
