package com.xnotes.core.model

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.stroke.Sample
import com.xnotes.core.stroke.StrokeSimplify
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/** A speed stroke works its widths out once, at pen-up, and nothing done to it afterwards reshapes them. */
class SpeedWidthsTest {

    /** A wide speed stroke drawn live, sample by sample, with its speed swinging and a pause in it. */
    private fun drawn(): Stroke {
        val cfg = ToolDefaults.configFor(Tool.SPEED).copy(baseWidth = 20.0, pressureEnabled = false)
        val s = Stroke(Tool.SPEED, cfg, speedScale = 160.0 / 280.0)
        s.finished = false
        var t = 0.0
        for (i in 0 until 120) {
            val u = i * 0.09
            s.addSample(Sample(40.0 + u * 26.0 + sin(u * 3.1) * 7.0, 60.0 + sin(u * 1.7) * 34.0, 0.6, t))
            t += if (i == 70) 200.0 else 3.0 + 9.0 * (0.5 + 0.5 * sin(u * 0.7))
        }
        s.finished = true
        return s
    }

    /** The original's sample index holding the same moment as [piece]'s sample [k]. */
    private fun sameMoment(s: Stroke, piece: Stroke, k: Int): Int =
        (0 until s.sampleCount).first { s.tAt(it) == piece.tAt(k) }

    @Test fun keepingTheWidthsLeavesTheInkWhereItWas() {
        val s = drawn()
        assertFalse(s.keepsSpeedWidths)
        val before = s.geometry().halfWidths.copyOf()
        s.freezeSpeedWidths()
        assertTrue(s.keepsSpeedWidths)
        assertArrayEquals(before, s.geometry().halfWidths, 1e-5f)
    }

    @Test fun onlyTheSpeedPenKeepsWidths() {
        val s = Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), listOf(Sample(0.0, 0.0, 1.0, 0.0), Sample(9.0, 0.0, 1.0, 8.0)))
        s.freezeSpeedWidths()
        assertFalse(s.keepsSpeedWidths)
    }

    @Test fun anErasedHoleLeavesTheRestAsItWas() {
        val s = drawn().apply { freezeSpeedWidths() }
        val g = s.geometry()
        val mid = s.sampleAt(40)
        val pieces = s.erasedBy(mid.x, mid.y, 6.0)!!
        assertTrue(pieces.size >= 2)
        for (piece in pieces) {
            assertTrue(piece.keepsSpeedWidths)
            val pg = piece.geometry()
            for (k in 0 until piece.sampleCount) assertEquals(g.hw(sameMoment(s, piece, k)), pg.hw(k), 1e-6)
        }
    }

    @Test fun aResizeScalesTheWidthsAndNothingElse() {
        val s = drawn().apply { freezeSpeedWidths() }
        val before = s.geometry().halfWidths.copyOf()
        s.applyTransform(Affine.scaleAbout(Pt(0.0, 0.0), 2.0, 2.0))
        val after = s.geometry().halfWidths
        for (i in before.indices) assertEquals(2.0 * before[i], after[i].toDouble(), 1e-4)
    }

    @Test fun penUpReductionKeepsEachKeptSamplesWidth() {
        val s = drawn().apply { freezeSpeedWidths() }
        val g = s.geometry()
        val kept = Stroke(Tool.SPEED, s.config, StrokeSimplify.simplify(s.samples, g.halfWidths, 0.2), s.speedScale)
        assertTrue(kept.keepsSpeedWidths)
        val kg = kept.geometry()
        for (k in 0 until kept.sampleCount) assertEquals(g.hw(sameMoment(s, kept, k)), kg.hw(k), 1e-6)
    }

    @Test fun aStrokeCutAtAPageEdgeKeepsItsWidthsAcrossIt() {
        val whole = drawn().apply { freezeSpeedWidths() }
        val cut = 52
        fun piece(from: Int, to: Int) =
            Stroke(Tool.SPEED, whole.config, (from until to).map { whole.sampleAt(it).copy(widthFactor = 0.0) }, whole.speedScale)
        val a = piece(0, cut + 1)
        val b = piece(cut, whole.sampleCount)
        val alone = listOf(piece(0, cut + 1), piece(cut, whole.sampleCount)).onEach { it.freezeSpeedWidths() }
        Stroke.stitchSpeedWidths(listOf(a, b))
        assertTrue(a.keepsSpeedWidths && b.keepsSpeedWidths)
        fun worst(x: Stroke, y: Stroke): Double {
            var w = 0.0
            for (i in cut - 8..cut) w = maxOf(w, kotlin.math.abs(whole.sampleAt(i).widthFactor - x.sampleAt(i).widthFactor))
            for (k in 0..8) w = maxOf(w, kotlin.math.abs(whole.sampleAt(cut + k).widthFactor - y.sampleAt(k).widthFactor))
            return w
        }
        // Each piece smooths its own centreline from the bridge, so a trace of the cut stays in the blur.
        val stitched = worst(a, b)
        assertTrue("stitched $stitched", stitched < 0.02)
        assertTrue("each piece alone ${worst(alone[0], alone[1])}", worst(alone[0], alone[1]) > 5 * stitched)
        assertEquals("the bridge carries the width across", a.sampleAt(cut).widthFactor, b.sampleAt(0).widthFactor, 0.0)
    }

    @Test fun undoingAnEditBringsTheWidthsBack() {
        val s = drawn().apply { freezeSpeedWidths() }
        val before = s.geometry().halfWidths.copyOf()
        val snap = s.snapshotGeometry()
        s.applyTransform(Affine.scaleAbout(Pt(0.0, 0.0), 3.0, 3.0))
        s.restoreGeometry(snap)
        assertTrue(s.keepsSpeedWidths)
        assertArrayEquals(before, s.geometry().halfWidths, 0f)
    }
}
