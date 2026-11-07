package com.xnotes.sync

import com.xnotes.sync.LocalNoteStore.SwapFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SwapFixesTest {
    private fun fixes(vararg names: String) = LocalNoteStore.swapFixes(names.toList()).toSet()

    @Test
    fun leavesCleanFolderAlone() {
        assertEquals(emptySet<SwapFix>(), fixes("A.xnote", "B.pdf"))
    }

    @Test
    fun crashMidSwapRestoresOriginalAndDropsNewCopy() {
        assertEquals(
            setOf(SwapFix("A.xnote.syncold", "A.xnote"), SwapFix("A.xnote.synctmp", null)),
            fixes("A.xnote.syncold", "A.xnote.synctmp"),
        )
    }

    @Test
    fun crashAfterSwapDropsBackup() {
        assertEquals(setOf(SwapFix("A.xnote.syncold", null)), fixes("A.xnote", "A.xnote.syncold"))
    }

    @Test
    fun loneTempBecomesTheMissingFile() {
        assertEquals(setOf(SwapFix("A.xnote.synctmp", "A.xnote")), fixes("A.xnote.synctmp"))
    }

    @Test
    fun tempNextToFileIsDroppedIgnoringCase() {
        assertEquals(setOf(SwapFix("A.xnote.synctmp", null)), fixes("a.xnote", "A.xnote.synctmp"))
    }

    @Test
    fun leftoversAreNeverSynced() {
        assertFalse(LocalNoteStore.syncsFile("Uni/A.xnote.synctmp"))
        assertFalse(LocalNoteStore.syncsFile("Uni/A.xnote.syncold"))
    }
}
