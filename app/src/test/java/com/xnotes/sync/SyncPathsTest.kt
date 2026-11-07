package com.xnotes.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPathsTest {
    @Test
    fun syncsNotesAndSidecarsButNotTemplatesOrConfig() {
        assertTrue(LocalNoteStore.syncsFile("Uni/Maths.xnote"))
        assertTrue(LocalNoteStore.syncsFile(".xnote/colors.json"))
        assertTrue(LocalNoteStore.syncsFile("Uni/.xnote/colors.json"))
        assertTrue(LocalNoteStore.syncsFile(".xnote/pins.json"))
        assertTrue(LocalNoteStore.syncsFile(".xnote/trash/1700-abc/trash.json"))
        assertTrue(LocalNoteStore.syncsFile(".xnote/trash/1700-abc/Maths.xnote"))
        assertTrue(LocalNoteStore.syncsFile(".xnote/trash/1700-abc/Uni/.xnote/colors.json"))
        assertTrue(LocalNoteStore.syncsFile(".trash/Old.xnote"))

        assertFalse(LocalNoteStore.syncsFile(".xnote-config/settings.json"))
        assertFalse(LocalNoteStore.syncsFile(".xnote/templates/grid.xnote"))
        assertFalse(LocalNoteStore.syncsFile("Uni/.xnote/trash/x/trash.json"))
        assertFalse(LocalNoteStore.syncsFile("Uni/trash.json"))
        assertFalse(LocalNoteStore.syncsFile(".obsidian/Note.md"))
        assertFalse(LocalNoteStore.syncsDir(".xnote/templates"))
        assertTrue(LocalNoteStore.isHiddenPath("Uni/.xnote/colors.json"))
        assertFalse(LocalNoteStore.isHiddenPath("Uni/Maths.xnote"))
    }
}
