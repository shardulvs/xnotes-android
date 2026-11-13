package com.xnotes.canvas

import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.AudioData
import com.xnotes.core.model.AudioItem
import com.xnotes.core.model.Document
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.SyncMark
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemClipboardTest {

    private val measurer = FakeTextMeasurer()

    private fun ink(rid: Int) = Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), mutableListOf(Sample(10.0, 20.0, 1.0), Sample(30.0, 40.0, 1.0)))
        .also { it.sync = SyncMark(rid, 5) }

    @Test fun copiesKeepTheirRealSizeAcrossDpi() {
        val clip = ItemClipboard.Clip(listOf(ink(1)), 150, Document(), cut = false)
        val copy = ItemClipboard.copiesFor(clip, 300, home = false, measurer = measurer).single() as Stroke
        assertEquals(20.0, copy.sampleAt(0).x, 1e-3)
        assertEquals(60.0, copy.sampleAt(1).x, 1e-3)
        // The clip's own items stay as they were, for the next paste.
        assertEquals(10.0, (clip.items.single() as Stroke).sampleAt(0).x, 1e-3)
    }

    @Test fun awayFromHomeOnlyTiesToRecordingsThatCameAlongStay() {
        val pill = AudioItem(AudioData(java.io.File("a"), "m4a", 1000, ByteArray(0)), Rect(0.0, 0.0, 40.0, 10.0), "rec", 2, measurer)
        val clip = ItemClipboard.Clip(listOf(ink(2), ink(9), pill), 150, Document(), cut = false)
        val away = ItemClipboard.copiesFor(clip, 150, home = false, measurer = measurer)
        assertEquals(2, away[0].sync?.rid)
        assertNull(away[1].sync)
        val home = ItemClipboard.copiesFor(clip, 150, home = true, measurer = measurer)
        assertEquals(9, home[1].sync?.rid)
    }

    @Test fun aClipKnowsWhereItCameFrom() {
        val doc = Document()
        val clip = ItemClipboard.Clip(emptyList(), 150, doc, cut = true)
        assertTrue(clip.isFrom(doc))
        assertFalse(clip.isFrom(Document()))
    }
}
