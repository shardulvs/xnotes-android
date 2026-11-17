package com.xnotes.core.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoteTabsTest {
    @Test fun switchingKeepsBackgroundTabsWithoutDuplicatingThem() {
        val workspace = NoteTabs().open("a", "Math").open("b", "Physics").open("a", "Math")
        assertEquals(listOf("a", "b"), workspace.tabs.map { it.uri })
        assertEquals("a", workspace.primaryUri)
        assertEquals("b", workspace.hide("a").tabs.last().uri)
        assertNull(workspace.hide("a").primaryUri)
    }

    @Test fun splitMovesTabInsteadOfShowingSameDocumentTwice() {
        val workspace = NoteTabs().open("a", "Math").open("b", "Physics", secondary = true)
            .open("a", "Math", secondary = true)
        assertNull(workspace.primaryUri)
        assertEquals("a", workspace.secondaryUri)
    }

    @Test fun closeKeepsOtherPaneAndSelectsReplacementWhenPossible() {
        val workspace = NoteTabs().open("a", "A").open("b", "B")
            .open("c", "C", secondary = true)
        val closed = workspace.close("b")
        assertEquals("a", closed.primaryUri)
        assertEquals("c", closed.secondaryUri)
        assertEquals(listOf("a", "c"), closed.tabs.map { it.uri })
    }

    @Test fun reorderDoesNotChangeSelectedDocuments() {
        val workspace = NoteTabs().open("a", "A").open("b", "B").move(0, 1)
        assertEquals(listOf("b", "a"), workspace.tabs.map { it.uri })
        assertEquals("b", workspace.primaryUri)
    }

    @Test fun closingActiveTabSelectsPreviousTabWithoutClosingOtherTabs() {
        val workspace = NoteTabs().open("a", "A").open("b", "B").open("c", "C")
        val closed = workspace.close("c")
        assertEquals("b", closed.primaryUri)
        assertEquals(listOf("a", "b"), closed.tabs.map { it.uri })
    }

    @Test fun closingFirstTabSelectsNextAndClosingLastReturnsToExplorer() {
        val workspace = NoteTabs().open("a", "A").open("b", "B").open("a", "A")
        val afterFirst = workspace.close("a")
        assertEquals("b", afterFirst.primaryUri)
        val afterLast = afterFirst.close("b")
        assertNull(afterLast.primaryUri)
        assertEquals(emptyList<NoteTab>(), afterLast.tabs)
    }

    @Test fun activeHighlightFollowsReplacementImmediatelyAfterClosingSelectedTab() {
        val workspace = NoteTabs().open("a", "A").open("b", "B")
            .open("c", "C").open("d", "D").open("c", "C")
        val closed = workspace.close("c")
        assertEquals("b", closed.selectedUri(secondaryFocused = false))
        assertEquals("b", closed.selectedUri(secondaryFocused = true))
        val closeSelected = closed.close("b")
        assertEquals("a", closeSelected.selectedUri(secondaryFocused = false))
    }

    @Test fun canvasAndPagedNotesCoexistAsTabs() {
        val workspace = NoteTabs().open("content://notes/math.xnote", "Math")
            .open("content://notes/diagram.xcanvas", "Diagram")
            .open("content://notes/math.xnote", "Math")
        assertEquals("content://notes/math.xnote", workspace.primaryUri)
        assertEquals(2, workspace.tabs.size)
        val backToCanvas = workspace.open("content://notes/diagram.xcanvas", "Diagram")
        assertEquals("content://notes/diagram.xcanvas", backToCanvas.primaryUri)
        assertEquals("content://notes/math.xnote", backToCanvas.close("content://notes/diagram.xcanvas").primaryUri)
    }
}
