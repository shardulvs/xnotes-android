package com.xnotes.core.audio

import com.xnotes.core.FakeRenderer
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.AudioData
import com.xnotes.core.model.AudioItem
import com.xnotes.core.model.AudioLook
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AudioPillTest {

    private fun pill(name: String = "22 Oct, 14:05", durationMs: Int = 45 * 60_000 + 10_000, peaks: ByteArray = ByteArray(256) { (it * 7 % 256).toByte() }) =
        AudioItem(AudioData(File("unused"), "m4a", durationMs, peaks), Rect(10.0, 20.0, 378.0, 83.0), name, 1, FakeTextMeasurer())

    @Test fun theTimeShowsTheLengthUntilAPlayerOpensThenWhereItStands() {
        val item = pill()
        assertEquals("45:10", AudioPill.layout(item).time)
        assertFalse(AudioPill.layout(item).active)
        item.playhead = 3 * 60_000 + 12_000
        assertEquals("03:12", AudioPill.layout(item).time)
        assertTrue(AudioPill.layout(item).active)
    }

    @Test fun playedBarsFollowThePlayhead() {
        val item = pill(durationMs = 100_000)
        val n = AudioPill.layout(item).bars.size
        assertEquals(0, AudioPill.layout(item).played)
        item.playhead = 50_000
        assertEquals(n / 2, AudioPill.layout(item).played)
        item.playhead = 100_000
        assertEquals(n, AudioPill.layout(item).played)
    }

    @Test fun barsStayInsideThePillAndFitBetweenTheDiscAndTheRightEdge() {
        val item = pill()
        val l = AudioPill.layout(item)
        assertTrue(l.bars.size > 20)
        for (bar in l.bars) for (p in bar) assertTrue(item.rect.outset(1e-6).contains(p))
        val firstLeft = l.bars.first().minOf { it.x }
        assertTrue(firstLeft > l.discCenter.x + l.discRadius)
    }

    @Test fun aLongNameIsCutWithAnEllipsisBeforeTheTime() {
        val l = AudioPill.layout(pill(name = "A very long recording name that cannot possibly fit on the pill"))
        assertTrue(l.name.endsWith("…"))
        val nameRight = l.nameAt.x + l.name.length * l.nameFont.pointSize * FakeTextMeasurer.ADVANCE_PER_POINT
        assertTrue(nameRight <= l.timeAt.x)
    }

    @Test fun aWaveformNotReadYetDrawsFlatPendingBars() {
        val item = pill(peaks = ByteArray(0))
        val l = AudioPill.layout(item)
        assertTrue(l.pending)
        val heights = l.bars.map { bar -> bar.maxOf { it.y } - bar.minOf { it.y } }
        assertEquals(heights.first(), heights.last(), 1e-9)
        assertEquals(AudioLook.DEFAULT.barPending, AudioPill.barColor(AudioLook.DEFAULT, l, 3))
    }

    @Test fun seekAtMapsTheWaveformAcrossTheLength() {
        val item = pill(durationMs = 60_000)
        val l = AudioPill.layout(item)
        val y = item.rect.centerY
        val left = l.bars.first().minOf { it.x }
        assertEquals(0, AudioPill.seekAt(item, Pt(left, y)))
        assertNull(AudioPill.seekAt(item, Pt(l.discCenter.x, y)))
        assertEquals(60_000, AudioPill.seekAt(item, Pt(item.rect.right - 0.34 * item.rect.h, y)))
        assertTrue(AudioPill.onDisc(item, l.discCenter))
        assertFalse(AudioPill.onDisc(item, Pt(item.rect.centerX, y)))
    }

    @Test fun playingSwapsThePlayTriangleForTwoPauseBars() {
        val item = pill()
        assertEquals(1, AudioPill.layout(item).glyph.size)
        item.playhead = 0
        item.playing = true
        assertEquals(2, AudioPill.layout(item).glyph.size)
    }

    @Test fun thePillPaintsBodyDiscGlyphBarsNameAndTime() {
        val item = pill()
        val r = FakeRenderer()
        item.paint(r)
        val l = AudioPill.layout(item)
        assertEquals("fillPolygon", r.ops.first())
        assertEquals(1, r.ops.count { it.startsWith("fillCircle") })
        assertEquals(1 + l.glyph.size + l.bars.size, r.ops.count { it == "fillPolygon" })
        assertEquals(2, r.ops.count { it.startsWith("drawTextRun:") })
        assertTrue(r.ops.any { it.startsWith("drawTextRun:45:10") })
    }

    @Test fun capsulesCloseAroundTheirBox() {
        val wide = AudioPill.capsule(Rect(0.0, 0.0, 100.0, 20.0), 8)
        assertEquals(100.0, wide.maxOf { it.x }, 1e-9)
        assertEquals(0.0, wide.minOf { it.x }, 1e-9)
        assertEquals(20.0, wide.maxOf { it.y }, 1e-9)
        val tall = AudioPill.capsule(Rect(0.0, 0.0, 4.0, 30.0), 4)
        assertEquals(30.0, tall.maxOf { it.y }, 1e-9)
        assertEquals(4.0, tall.maxOf { it.x }, 1e-9)
    }

    @Test fun timesReadAsMinutesAndSecondsWithHoursWhenLong() {
        assertEquals("00:00", AudioTime.format(0))
        assertEquals("03:12", AudioTime.format(192_000))
        assertEquals("1:03:12", AudioTime.format(3_792_000))
        assertEquals("0:03:12", AudioTime.format(192_000, long = true))
        assertEquals("00:00", AudioTime.format(-5))
    }

    @Test fun levelsFoldIntoTheLoudestOfEachSlice() {
        val levels = floatArrayOf(0.1f, 0.9f, 0.2f, 0.4f)
        assertArrayEquals(byteArrayOf(230.toByte(), 102), AudioData.peaksOf(levels, bins = 2))
        assertEquals(0, AudioData.peaksOf(FloatArray(0)).size)
        assertEquals(AudioData.PEAK_BINS, AudioData.peaksOf(levels).size)
    }

    @Test fun aCornerScalesThePillWholeAndASideOnlyLengthensIt() {
        val item = pill()
        val before = AudioPill.layout(item).bars.size
        item.applyTransform(com.xnotes.core.geometry.Affine.scaleAlongAxes(Pt(10.0, 20.0), 0.0, 2.0, 2.0))
        assertEquals(Rect(10.0, 20.0, 756.0, 166.0), item.rect)
        assertEquals(before, AudioPill.layout(item).bars.size)

        val long = pill()
        long.applyTransform(com.xnotes.core.geometry.Affine.scaleAlongAxes(Pt(10.0, 20.0), 0.0, 2.0, 1.0))
        assertEquals(Rect(10.0, 20.0, 756.0, 83.0), long.rect)
        assertTrue(AudioPill.layout(long).bars.size > 2 * before)
    }

    @Test fun shrinkingStopsWhereTheDiscAndClockStillFitAndTheHeldSideStays() {
        val item = pill()
        // Dragging the left side nearly onto the right one: the right side holds still.
        item.applyTransform(com.xnotes.core.geometry.Affine.scaleAlongAxes(Pt(388.0, 20.0), 0.0, 0.05, 1.0))
        assertEquals(388.0, item.rect.right, 1e-9)
        assertEquals(83.0 * AudioItem.MIN_ASPECT, item.rect.w, 1e-9)
        assertEquals(83.0, item.rect.h, 1e-9)
        val tiny = pill()
        tiny.applyTransform(com.xnotes.core.geometry.Affine.scaleAlongAxes(Pt(10.0, 20.0), 0.0, 0.01, 0.01))
        assertEquals(AudioItem.MIN_HEIGHT, tiny.rect.h, 1e-9)
        assertEquals(AudioItem.MIN_HEIGHT * AudioItem.MIN_ASPECT, tiny.rect.w, 1e-9)
        assertEquals(10.0, tiny.rect.left, 1e-9)
    }

    @Test fun aMoveCarriesThePillAsItIs() {
        val item = pill()
        item.applyTransform(com.xnotes.core.geometry.Affine.translateBy(5.0, -7.0))
        assertEquals(Rect(15.0, 13.0, 378.0, 83.0), item.rect)
    }
}
