package com.xnotes.core.audio

import com.xnotes.core.FakeRenderer
import com.xnotes.core.history.AddItem
import com.xnotes.core.history.AddItems
import com.xnotes.core.history.CompositeCommand
import com.xnotes.core.history.History
import com.xnotes.core.model.Page
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.SyncMark
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InkFadeTest {

    private fun ink(rid: Int?, ms: Int) =
        Stroke(Tool.PEN, ToolConfig(), mutableListOf(Sample(1.0, 1.0, 1.0), Sample(5.0, 5.0, 1.0))).apply { sync = rid?.let { SyncMark(it, ms) } }

    @Test fun onlyItsRecordingsInkAfterThePlayheadFades() {
        val fade = InkFade(rid = 2, playheadMs = 10_000)
        assertTrue(fade.fades(ink(2, 10_001)))
        assertFalse(fade.fades(ink(2, 10_000)))
        assertFalse(fade.fades(ink(3, 50_000)))
        assertFalse(fade.fades(ink(null, 50_000)))
    }

    @Test fun eachRunOfFadedInkSharesOneLayer() {
        val items = listOf(ink(1, 0), ink(1, 20), ink(1, 30), ink(null, 0), ink(1, 40), ink(2, 99))
        val r = FakeRenderer()
        val painted = ArrayList<Int>()
        InkFade.paint(items, r, InkFade(1, 10)) { painted.add(items.indexOf(it)) }
        assertEquals(listOf(0, 1, 2, 3, 4, 5), painted)
        assertEquals(2, r.ops.count { it == "saveLayerAlpha" })
        assertEquals(2, r.ops.count { it == "restore" })
    }

    @Test fun anItemLeftOutDoesNotBreakARun() {
        val items = listOf(ink(1, 20), ink(1, 0), ink(1, 30))
        val r = FakeRenderer()
        val painted = ArrayList<Int>()
        InkFade.paint(items, r, InkFade(1, 10), include = { it !== items[1] }) { painted.add(items.indexOf(it)) }
        assertEquals(listOf(0, 2), painted)
        assertEquals(1, r.ops.count { it == "saveLayerAlpha" })
    }

    @Test fun withoutAFadeNothingIsLayered() {
        val r = FakeRenderer()
        var n = 0
        InkFade.paint(listOf(ink(1, 20), ink(1, 30)), r, null) { n++ }
        assertEquals(2, n)
        assertFalse(r.ops.contains("saveLayerAlpha"))
    }

    @Test fun historyHearsOfNewEditsButNotOfUndoOrRedo() {
        val history = History()
        val heard = ArrayList<Int>()
        history.onPush = { heard.add(it.added().size) }
        val page = Page(100.0, 100.0)
        val a = ink(null, 0)
        page.items.add(a)
        history.push(AddItem(page, a))
        val b = ink(null, 0)
        val c = ink(null, 0)
        page.items.addAll(listOf(b, c))
        history.push(CompositeCommand(listOf(AddItems(page, listOf(b, c)))))
        history.undo()
        history.redo()
        assertEquals(listOf(1, 2), heard)
    }

    @Test fun theIndexFindsWhatThePlayheadCrossedEitherWay() {
        val a = ink(1, 1_000)
        val b = ink(1, 5_000)
        val c = ink(1, 9_000)
        val other = ink(2, 6_000)
        val index = SyncIndex(1, listOf("p1" to c, "p0" to a, "p1" to other, "p0" to b))
        assertEquals(3, index.size)
        assertEquals(listOf(b), index.between(1_000, 5_000).map { it.item })
        assertEquals(listOf(a, b), index.between(0, 8_999).map { it.item })
        assertEquals(listOf("p0", "p0", "p1"), index.between(-1, 10_000).map { it.place })
        assertTrue(index.between(9_000, 20_000).isEmpty())
        assertTrue(index.between(5_000, 5_000).isEmpty())
    }
}
