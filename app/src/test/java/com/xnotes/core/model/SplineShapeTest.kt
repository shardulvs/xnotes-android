package com.xnotes.core.model

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.tools.ShapeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplineShapeTest {

    private val ink = Rgba(0, 0, 0, 255)

    private fun spline() = ShapeItem(ShapeKind.SPLINE, Pt(0.0, 0.0), Pt(100.0, 0.0), ink, 3.0)

    @Test fun freshSplineHasOneMiddlePoint() {
        val c = spline().controlPoints()
        assertEquals(3, c.size)
        assertEquals(Pt(50.0, 0.0), c[1])
    }

    @Test fun curvePassesThroughEveryControlPoint() {
        val s = spline()
        s.moveControlPoint(1, Pt(50.0, 40.0))
        val path = s.splinePath()
        for (p in s.controlPoints()) assertTrue(path.any { it.distanceTo(p) < 1e-6 })
        assertTrue(s.contains(Pt(50.0, 40.0)))
        assertFalse(s.contains(Pt(50.0, 0.0)))
    }

    @Test fun addAndRemoveKeepAtLeastOneMiddlePoint() {
        val s = spline()
        s.addControlPointAt(Pt(25.0, 0.0))
        assertEquals(4, s.controlPoints().size)
        assertTrue(s.removeControlPointAt(1))
        assertEquals(3, s.controlPoints().size)
        assertFalse(s.removeControlPointAt(1))
        assertEquals(3, s.controlPoints().size)
    }

    @Test fun addedPointGoesWhereTappedInTheNearestSpan() {
        val s = spline()
        s.addControlPointAt(Pt(20.0, 30.0))
        val c = s.controlPoints()
        assertEquals(4, c.size)
        assertTrue(c[1].distanceTo(Pt(20.0, 30.0)) < 1e-6)
        assertTrue(s.splinePath().any { it.distanceTo(Pt(20.0, 30.0)) < 1e-6 })
    }

    @Test fun pointPastAnEndExtendsTheCurve() {
        val s = spline()
        s.addControlPointAt(Pt(130.0, 10.0))
        assertTrue(s.controlPoints().last().distanceTo(Pt(130.0, 10.0)) < 1e-6)
        s.addControlPointAt(Pt(-30.0, 0.0))
        assertTrue(s.controlPoints().first().distanceTo(Pt(-30.0, 0.0)) < 1e-6)
    }

    @Test fun rotationKeepsItASpline() {
        val s = spline()
        s.applyTransform(Affine.rotateAbout(Pt(50.0, 0.0), Math.PI / 2))
        assertEquals(ShapeKind.SPLINE, s.shape)
        val c = s.controlPoints()
        assertEquals(50.0, c.first().x, 1e-6)
        assertEquals(-50.0, c.first().y, 1e-6)
    }

    @Test fun numberLineHasAxisHeadAndTicks() {
        val n = ShapeItem(ShapeKind.NUMBER_LINE, Pt(0.0, 0.0), Pt(200.0, 0.0), ink, 3.0)
        val segs = n.numberLineSegments()
        assertEquals(1 + 1 + 9, segs.size)
        assertTrue(n.contains(Pt(100.0, 0.0)))
        assertTrue(n.bounds().h < 20.0)
    }
}
