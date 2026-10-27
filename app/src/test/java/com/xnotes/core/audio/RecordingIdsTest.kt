package com.xnotes.core.audio

import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.CanvasSelection
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.infinite.InkPass
import com.xnotes.core.infinite.ItemMesher
import com.xnotes.core.model.AudioData
import com.xnotes.core.model.AudioItem
import com.xnotes.core.model.AudioLook
import com.xnotes.core.model.EraseRule
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.SyncMark
import com.xnotes.core.model.forExport
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RecordingIdsTest {

    private fun pill(rid: Int) =
        AudioItem(AudioData(File("unused"), "m4a", 60_000, ByteArray(8) { 40 }), Rect(0.0, 0.0, 378.0, 83.0), "n", rid, FakeTextMeasurer())

    private fun ink(rid: Int?, ms: Int = 1000) =
        Stroke(Tool.PEN, ToolConfig(), mutableListOf(Sample(1.0, 1.0, 1.0), Sample(5.0, 5.0, 1.0))).apply { sync = rid?.let { SyncMark(it, ms) } }

    @Test fun aCopyDropsItsMomentsAndAPillWhoseIdIsTakenGetsAFreshOne() {
        val existing = listOf(pill(1), pill(4), ink(1))
        val copiedPill = pill(4)
        val copiedInk = ink(4)
        RecordingIds.adopt(listOf(copiedPill, copiedInk), existing, moves = false)
        assertEquals(5, copiedPill.rid)
        assertNull(copiedInk.sync)
    }

    @Test fun aPillWhoseIdIsFreeKeepsIt() {
        val copied = pill(9)
        RecordingIds.adopt(listOf(copied), listOf(pill(1)), moves = false)
        assertEquals(9, copied.rid)
    }

    @Test fun movedItemsKeepTheirMomentsAndFollowARenumberedPill() {
        val existing = listOf(pill(2), ink(2))
        val page = listOf(pill(2), ink(2, 7_000), ink(3, 500), ink(null))
        RecordingIds.adopt(page, existing, moves = true)
        val newRid = (page[0] as AudioItem).rid
        assertEquals(3, newRid)
        assertEquals(SyncMark(3, 7_000), page[1].sync)
        assertEquals(SyncMark(3, 500), page[2].sync)
        assertNull(page[3].sync)
    }

    @Test fun aCutPillPutBackKeepsItsNumberSoItsInkStaysTied() {
        val leftInk = ink(6)
        val moved = pill(6)
        RecordingIds.adopt(listOf(moved), listOf(leftInk), moves = true)
        assertEquals(6, moved.rid)
    }

    @Test fun aCopiedPillNeverTakesOverInkTiedToItsNumber() {
        val copied = pill(6)
        RecordingIds.adopt(listOf(copied), listOf(ink(6)), moves = false)
        assertEquals(7, copied.rid)
    }

    @Test fun anIdInUseOnlyByInkIsNotHandedOutAgain() {
        assertEquals(10, RecordingIds.next(listOf(pill(2), ink(9))))
    }

    @Test fun twoPillsPastedTogetherNeverShareAnId() {
        val a = pill(1)
        val b = pill(1)
        RecordingIds.adopt(listOf(a, b), listOf(pill(1)), moves = false)
        assertTrue(a.rid != b.rid && a.rid != 1 && b.rid != 1)
    }

    @Test fun theNextIdIsAboveEveryRecording() {
        assertEquals(1, RecordingIds.next(emptyList()))
        assertEquals(8, RecordingIds.next(listOf(pill(3), ink(7), pill(7))))
    }

    @Test fun theCanvasMeshesThePillInOpaquePartsStartingWithItsBody() {
        val meshed = ItemMesher.mesh(pill(1))
        assertNotNull(meshed)
        val parts = meshed!!.parts
        assertEquals(AudioLook.current.body, parts.first().color)
        assertTrue(parts.all { it.pass == InkPass.OPAQUE })
        assertTrue(parts.any { it.color == AudioLook.current.disc })
        assertTrue(parts.any { it.color == AudioLook.current.name })
        assertEquals(Rect(0.0, 0.0, 378.0, 83.0), meshed.bounds)
    }

    @Test fun erasersPassOverAPillAndExportsLeaveItOut() {
        val p = pill(1)
        assertFalse(EraseRule.erasable(p))
        val items = listOf(ink(null), p)
        assertEquals(1, items.forExport().size)
        val noPills = listOf(ink(null))
        assertTrue(noPills.forExport() === noPills)
    }

    @Test fun aPillScalesByCornersAndLengthensBySidesButNeverTurns() {
        val doc = InfiniteDocument()
        val p = pill(1)
        val stroke = ink(null)
        doc.add(p)
        doc.add(stroke)
        val sel = CanvasSelection(doc)
        sel.select(listOf(p))
        val ids = sel.handles().map { it.id }.toSet()
        assertEquals(6, ids.size)
        assertFalse(com.xnotes.canvas.HandleId.T in ids || com.xnotes.canvas.HandleId.B in ids)
        assertTrue(com.xnotes.canvas.HandleId.L in ids && com.xnotes.canvas.HandleId.R in ids)
        assertNull(sel.rotateGrip(10.0))
        sel.select(listOf(p, stroke))
        assertTrue(sel.handles().isNotEmpty())
        assertNull(sel.rotateGrip(10.0))
        sel.select(listOf(stroke))
        assertNotNull(sel.rotateGrip(10.0))
    }

    @Test fun renamingAndUnlinkingUndo() {
        val p = pill(3)
        val rename = com.xnotes.core.history.RenameAudio(p, "n", "Lecture 4")
        rename.redo()
        assertEquals("Lecture 4", p.name)
        rename.undo()
        assertEquals("n", p.name)

        val a = ink(3, 100)
        val b = ink(3, 200)
        val unlink = com.xnotes.core.history.UnlinkSync(listOf(a to a.sync!!, b to b.sync!!))
        unlink.redo()
        assertNull(a.sync)
        assertNull(b.sync)
        unlink.undo()
        assertEquals(SyncMark(3, 100), a.sync)
        assertEquals(SyncMark(3, 200), b.sync)
    }
}
