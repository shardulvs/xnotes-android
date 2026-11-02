package com.xnotes.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.GregorianCalendar

class SyncConflictTest {
    private val at = GregorianCalendar(2026, 10, 2, 14, 30).time

    @Test fun copyPathGoesBeforeTheExtension() {
        assertEquals("notes/a (Sync Conflict 2026-11-02 1430).xnote", SyncConflict.copyPath("notes/a.xnote", at))
        assertEquals("notes.d/a (Sync Conflict 2026-11-02 1430)", SyncConflict.copyPath("notes.d/a", at))
    }

    @Test fun newAndOldCopiesAreRecognised() {
        assertTrue(SyncConflict.isCopy("a (Sync Conflict 2026-11-02 1430).xnote"))
        assertTrue(SyncConflict.isCopy("a (Filen conflict 2026-01-01 0900).xnote"))
        assertTrue(SyncConflict.isCopy("a (filen conflict 2026-01-01 0900).xcanvas"))
    }

    @Test fun ordinaryNamesAreNot() {
        assertFalse(SyncConflict.isCopy("a.xnote"))
        assertFalse(SyncConflict.isCopy("Merge conflict notes.xnote"))
        assertFalse(SyncConflict.isCopy("a (2026-11-02).xnote"))
        assertFalse(SyncConflict.isCopy("Meeting (team conflict 2026-03-01 0900).xnote"))
    }
}
