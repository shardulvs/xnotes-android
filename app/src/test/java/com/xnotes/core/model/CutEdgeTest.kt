package com.xnotes.core.model

import com.xnotes.core.FakeRenderer
import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.MeshClip
import com.xnotes.core.infinite.MeshData
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CutEdgeTest {

    private val eps = 1e-9

    private fun stroke(vararg pts: Pt) =
        Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), pts.map { Sample(it.x, it.y, 1.0) })

    /** A vertical line from y 0 to 100 at x 50, cut to keep what lies above y 40. */
    private fun upperHalf(): Stroke =
        stroke(*Array(21) { Pt(50.0, it * 5.0) }).also { it.cuts = listOf(CutEdge.along(40.0, false, keepAfter = false, near = 50.0)) }

    @Test fun anEdgeKeepsItsNearSide() {
        val keepAbove = CutEdge.along(40.0, false, keepAfter = false, near = 0.0)
        assertTrue(keepAbove.past(0.0, 10.0) < 0.0)
        assertTrue(keepAbove.past(0.0, 60.0) > 0.0)
        val keepRight = CutEdge.along(40.0, true, keepAfter = true, near = 0.0)
        assertTrue(keepRight.past(60.0, 0.0) < 0.0)
    }

    @Test fun anEdgeTurnsAndMirrorsWithItsItem() {
        val e = CutEdge(0.0, 40.0, 0.0, 1.0)
        val turned = e.mapped(Affine.rotateAbout(Pt.ZERO, Math.PI / 2))
        val p = Affine.rotateAbout(Pt.ZERO, Math.PI / 2).apply(Pt(10.0, 30.0))
        assertTrue(turned.past(p.x, p.y) < 0.0)
        val mirror = Affine(-1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        val m = e.mapped(mirror)
        assertTrue(m.past(-10.0, 30.0) < 0.0)
        assertTrue(m.past(-10.0, 50.0) > 0.0)
        val squash = Affine.scaleAbout(Pt.ZERO, 3.0, 0.5)
        val sq = e.mapped(squash)
        assertEquals(20.0, sq.y, eps)
        assertTrue(sq.past(0.0, 19.0) < 0.0 && sq.past(0.0, 21.0) > 0.0)
    }

    @Test fun aTighterEdgeReplacesALooserOne() {
        val loose = CutEdge(0.0, 100.0, 0.0, 1.0)
        val tight = CutEdge(0.0, 40.0, 0.0, 1.0)
        assertEquals(listOf(tight), CutEdges.with(listOf(loose), tight))
        assertEquals(listOf(tight), CutEdges.with(listOf(tight), loose))
        val other = CutEdge(0.0, 10.0, 0.0, -1.0)
        assertEquals(2, CutEdges.with(listOf(tight), other).size)
    }

    @Test fun aLineClipsIntoTheRunsThatShow() {
        val cuts = listOf(CutEdge(0.0, 40.0, 0.0, 1.0))
        val runs = CutEdges.clipLine(listOf(Pt(0.0, 0.0), Pt(0.0, 80.0), Pt(10.0, 0.0)), cuts)
        assertEquals(2, runs.size)
        assertEquals(40.0, runs[0].last().y, eps)
        assertEquals(40.0, runs[1].first().y, eps)
        assertEquals(0.0, runs[1].last().y, eps)
    }

    @Test fun aCutStrokeIsBoxedAndHitOnlyWhereItShows() {
        val s = upperHalf()
        val b = s.bounds()
        assertTrue(b.bottom <= 40.0 + 1e-6)
        assertTrue(b.top < 1.0)
        assertTrue(s.contains(Pt(50.0, 20.0)))
        assertFalse(s.contains(Pt(50.0, 60.0)))
        assertTrue(s.intersectsCircle(50.0, 30.0, 2.0))
        assertFalse(s.intersectsCircle(50.0, 70.0, 2.0))
    }

    @Test fun cutInkKeepsEverySampleAndPaintsThroughAClip() {
        val s = upperHalf()
        assertEquals(21, s.sampleCount)
        val r = FakeRenderer()
        s.paint(r)
        assertEquals(1, r.polygonClips.size)
        assertTrue(r.polygonClips[0].all { it.y <= 40.0 + 1e-9 })
    }

    @Test fun edgesMoveTurnAndComeBackWithTheStroke() {
        val s = upperHalf()
        val snap = s.snapshotGeometry()
        s.translate(0.0, 100.0)
        assertEquals(140.0, s.cuts[0].y, eps)
        assertTrue(s.contains(Pt(50.0, 120.0)))
        assertFalse(s.contains(Pt(50.0, 160.0)))
        s.applyTransform(Affine.rotateAbout(Pt(50.0, 100.0), Math.PI))
        assertTrue(s.contains(Pt(50.0, 80.0)))
        s.restoreGeometry(snap)
        assertEquals(40.0, s.cuts[0].y, eps)
        assertEquals(1, Stroke(s).cuts.size)
    }

    @Test fun erasingACutStrokeDropsPiecesThatWouldShowNothing() {
        val s = upperHalf()
        val pieces = s.erasedBy(50.0, 40.0, 3.0)
        assertNotNull(pieces)
        assertEquals(1, pieces!!.size)
        assertTrue(pieces[0].cuts.isNotEmpty())
        assertNull("nothing shows where the eraser went", s.erasedBy(50.0, 80.0, 3.0))
    }

    @Test fun aPartComposesWithACrop() {
        val item = ImageItem(ImageData(File("x"), 100, 100), Rect(0.0, 0.0, 100.0, 100.0), part = Rect.ltrb(0.0, 0.5, 1.0, 1.0))
        val half = ImageCrop.partOf(item, Rect.ltrb(0.0, 0.0, 1.0, 0.5))
        assertEquals(Rect.ltrb(0.0, 0.5, 1.0, 0.75), half)
        val px = ImageCrop.sourceRect(item, 100, 100, Rect.ltrb(0.5, 0.0, 1.0, 1.0))
        assertEquals(Rect.ltrb(50.0, 50.0, 100.0, 100.0), px)
    }

    @Test fun aPictureSplitsAlongACutWhateverQuarterItStandsAt() {
        val upright = ImageItem(ImageData(File("x"), 100, 50), Rect(0.0, 0.0, 100.0, 50.0))
        val (near, far) = ImageCrop.split(upright, Pt(0.0, 20.0), Pt(0.0, 1.0))!!
        assertEquals(Rect.ltrb(0.0, 0.0, 1.0, 0.4), near)
        assertEquals(Rect.ltrb(0.0, 0.4, 1.0, 1.0), far)
        val turned = ImageItem(ImageData(File("x"), 100, 50), Rect(0.0, 0.0, 100.0, 50.0), angle = Math.PI / 2)
        val (tn, tf) = ImageCrop.split(turned, Pt(0.0, 25.0 - 30.0), Pt(0.0, 1.0))!!
        assertEquals(0.2, tn.right, 1e-9)
        assertEquals(0.2, tf.left, 1e-9)
        val upside = ImageItem(ImageData(File("x"), 100, 50), Rect(0.0, 0.0, 100.0, 50.0), angle = Math.PI)
        val (un, uf) = ImageCrop.split(upside, Pt(0.0, 20.0), Pt(0.0, 1.0))!!
        assertEquals(0.6, un.top, 1e-9)
        assertEquals(0.6, uf.bottom, 1e-9)
        val free = ImageItem(ImageData(File("x"), 100, 50), Rect(0.0, 0.0, 100.0, 50.0), angle = 0.3)
        assertNull(ImageCrop.split(free, Pt(0.0, 20.0), Pt(0.0, 1.0)))
        assertNull("a line past the box cuts nothing", ImageCrop.split(upright, Pt(0.0, 60.0), Pt(0.0, 1.0)))
    }

    @Test fun aPictureShowsItsPartAndPutsItBack() {
        val item = ImageItem(ImageData(File("x"), 100, 100), Rect(0.0, 0.0, 100.0, 100.0))
        val snap = item.snapshotGeometry()
        item.part = Rect.ltrb(0.0, 0.0, 1.0, 0.5)
        val r = FakeRenderer()
        item.paint(r)
        assertEquals(Rect.ltrb(0.0, 0.0, 1.0, 0.5), r.parts.single())
        item.restoreGeometry(snap)
        assertNull(item.part)
        assertEquals(Rect.ltrb(0.0, 0.0, 1.0, 0.5), (item.also { it.part = Rect.ltrb(0.0, 0.0, 1.0, 0.5) }.deepCopy(com.xnotes.core.FakeTextMeasurer()) as ImageItem).part)
    }

    @Test fun aMeshClipsToWhatShows() {
        val quad = MeshData(doubleArrayOf(0.0, 0.0, 10.0, 0.0, 10.0, 10.0, 0.0, 10.0), DoubleArray(8), intArrayOf(0, 1, 2, 0, 2, 3))
        val clipped = MeshClip.clip(quad, listOf(CutEdge(0.0, 4.0, 0.0, 1.0)))
        var area = 0.0
        for (t in 0 until clipped.triangleCount) {
            val i = clipped.indices[3 * t]
            val j = clipped.indices[3 * t + 1]
            val k = clipped.indices[3 * t + 2]
            val ax = clipped.positions[2 * i]
            val ay = clipped.positions[2 * i + 1]
            val bx = clipped.positions[2 * j]
            val by = clipped.positions[2 * j + 1]
            val cx = clipped.positions[2 * k]
            val cy = clipped.positions[2 * k + 1]
            area += kotlin.math.abs((bx - ax) * (cy - ay) - (cx - ax) * (by - ay)) / 2.0
            for (v in listOf(ay, by, cy)) assertTrue(v <= 4.0 + 1e-9)
        }
        assertEquals(40.0, area, 1e-9)
    }
}
