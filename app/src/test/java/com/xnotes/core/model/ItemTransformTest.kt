package com.xnotes.core.model

import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.stroke.Sample
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class ItemTransformTest {

    private fun horizontalStroke(width: Double = 4.0): Stroke {
        val s = Stroke(Tool.PEN, ToolConfig(baseWidth = width))
        s.addSample(Sample(0.0, 0.0, 1.0))
        s.addSample(Sample(10.0, 0.0, 1.0))
        s.addSample(Sample(20.0, 0.0, 1.0))
        return s
    }

    // --- strokes ---

    @Test fun scaleStrokeMovesSamplesAndScalesWidth() {
        val s = horizontalStroke(4.0)
        s.applyTransform(Affine.scaleAbout(Pt.ZERO, 2.0, 2.0))
        assertEquals(Pt(20.0, 0.0), s.centroid()) // samples doubled about the origin
        assertEquals(8.0, s.config.baseWidth, 1e-9) // width * sqrt(det)=*2
    }

    @Test fun rotateStrokeKeepsWidth() {
        val s = horizontalStroke(4.0)
        s.applyTransform(Affine.rotateAbout(Pt.ZERO, PI / 2))
        assertEquals(0.0, s.centroid().x, 1e-9)
        assertEquals(10.0, s.centroid().y, 1e-9) // horizontal run becomes vertical
        assertEquals(4.0, s.config.baseWidth, 1e-9) // rotation leaves width alone
    }

    @Test fun strokeSnapshotRestoreRoundTrips() {
        val s = horizontalStroke(4.0)
        val snap = s.snapshotGeometry()
        s.applyTransform(Affine.scaleAbout(Pt.ZERO, 3.0, 3.0))
        s.restoreGeometry(snap)
        assertEquals(Pt(10.0, 0.0), s.centroid())
        assertEquals(4.0, s.config.baseWidth, 1e-9)
    }

    // --- shapes ---

    @Test fun scaleRectangleStaysParametric() {
        val s = ShapeItem(ShapeKind.RECTANGLE, Pt(0.0, 0.0), Pt(100.0, 50.0), Rgba(0, 0, 0), 3.0)
        s.applyTransform(Affine.scaleAbout(Pt.ZERO, 2.0, 2.0))
        assertEquals(ShapeKind.RECTANGLE, s.shape)
        assertEquals(Pt(0.0, 0.0), s.start)
        assertEquals(Pt(200.0, 100.0), s.end)
        assertEquals(3.0, s.strokeWidth, 1e-9) // a shape keeps its outline width
        assertNull(s.path)
    }

    @Test fun scaleArrowKeepsItsHeadSize() {
        val s = ShapeItem(ShapeKind.ARROW, Pt(0.0, 0.0), Pt(100.0, 0.0), Rgba(0, 0, 0), 4.0)
        fun head() = s.paths().last().contours.single().let { c -> listOf(c.start) + c.segments.map { it.end } }
        val before = head()
        s.applyTransform(Affine.scaleAbout(Pt.ZERO, 3.0, 3.0))
        val after = head()
        assertEquals(Pt(300.0, 0.0), s.end)
        assertEquals(4.0, s.strokeWidth, 1e-9)
        assertEquals(before[0].distanceTo(before[2]), after[0].distanceTo(after[2]), 1e-9)
    }

    @Test fun aQuarterTurnLeavesAnUprightRectangle() {
        val s = ShapeItem(ShapeKind.RECTANGLE, Pt(0.0, 0.0), Pt(100.0, 50.0), Rgba(0, 0, 0), 3.0)
        s.applyTransform(Affine.rotateAbout(Pt(50.0, 25.0), PI / 2)) // about the box centre
        assertEquals(ShapeKind.RECTANGLE, s.shape)
        assertEquals(0.0, s.rotation, 0.0)
        assertNull(s.path)
        assertEquals(3.0, s.strokeWidth, 1e-9) // rotation keeps the width
        // A 100x50 rect turned 90 degrees about its centre is the upright 50x100 one at (25,-25).
        assertEquals(25.0, s.box.left, 1e-9)
        assertEquals(-25.0, s.box.top, 1e-9)
        assertEquals(75.0, s.box.right, 1e-9)
        assertEquals(75.0, s.box.bottom, 1e-9)
    }

    @Test fun rotateLineKeepsKind() {
        val s = ShapeItem(ShapeKind.LINE, Pt(0.0, 0.0), Pt(10.0, 0.0), Rgba(0, 0, 0), 3.0)
        s.applyTransform(Affine.rotateAbout(Pt.ZERO, PI / 2))
        assertEquals(ShapeKind.LINE, s.shape)
        assertEquals(0.0, s.end.x, 1e-9)
        assertEquals(10.0, s.end.y, 1e-9)
    }

    @Test fun shapeSnapshotRestoresKindAfterAShear() {
        val s = ShapeItem(ShapeKind.RECTANGLE, Pt(0.0, 0.0), Pt(100.0, 50.0), Rgba(0, 0, 0), 3.0)
        val snap = s.snapshotGeometry()
        s.applyTransform(Affine.rotateAbout(Pt(50.0, 25.0), PI / 4))
        s.applyTransform(Affine.scaleAbout(Pt.ZERO, 2.0, 1.0))
        assertEquals(ShapeKind.POLYGON, s.shape)
        s.restoreGeometry(snap)
        assertEquals(ShapeKind.RECTANGLE, s.shape)
        assertEquals(0.0, s.rotation, 0.0)
        assertNull(s.path)
        assertEquals(Pt(100.0, 50.0), s.end)
    }

    // --- images ---

    @Test fun scaleImageRect() {
        val img = ImageItem(ImageData(java.io.File("test-image"),10, 10), Rect(10.0, 10.0, 100.0, 50.0))
        img.applyTransform(Affine.scaleAbout(Pt(10.0, 10.0), 2.0, 2.0))
        assertEquals(Rect(10.0, 10.0, 200.0, 100.0), img.rect)
        assertEquals(0.0, img.angle, 1e-12) // a scale carries no turn
    }

    @Test fun rotateImageKeepsRectAndTurnsBounds() {
        val img = ImageItem(ImageData(java.io.File("test-image"), 10, 10), Rect(0.0, 0.0, 100.0, 50.0))
        img.applyTransform(Affine.rotateAbout(Pt(50.0, 25.0), PI / 2))
        assertEquals(PI / 2, img.angle, 1e-9)
        // The source box is never resampled: only where it sits and how far it is turned change.
        assertEquals(100.0, img.rect.w, 1e-9)
        assertEquals(50.0, img.rect.h, 1e-9)
        assertEquals(50.0, img.rect.centerX, 1e-9)
        assertEquals(25.0, img.rect.centerY, 1e-9)
        val b = img.bounds()
        assertEquals(25.0, b.left, 1e-9)
        assertEquals(-25.0, b.top, 1e-9)
        assertEquals(50.0, b.w, 1e-9)
        assertEquals(100.0, b.h, 1e-9)
        assertTrue(img.contains(Pt(50.0, 70.0))) // inside the turned box, outside the upright one
        assertFalse(img.contains(Pt(90.0, 25.0)))
    }

    @Test fun edgeScaleRotatedImageStretchesAlongItsOwnAxis() {
        // A widen (single-axis scale) of a 45deg-turned image is a world-space shear; the image must
        // stretch along its OWN width axis (x2, height unchanged), not grow near-uniformly in world axes.
        val img = ImageItem(ImageData(java.io.File("test-image"), 10, 10), Rect(0.0, 0.0, 100.0, 50.0), angle = PI / 4)
        val center = img.rect.center
        img.applyTransform(Affine.scaleAlongAxes(center, PI / 4, 2.0, 1.0))
        assertEquals(200.0, img.rect.w, 1e-9)
        assertEquals(50.0, img.rect.h, 1e-9)
        assertEquals(PI / 4, img.angle, 1e-9) // a pure axis scale adds no turn
        assertEquals(center.x, img.rect.centerX, 1e-9)
        assertEquals(center.y, img.rect.centerY, 1e-9)
    }

    @Test fun imageSnapshotRestoresAngle() {
        val img = ImageItem(ImageData(java.io.File("test-image"), 10, 10), Rect(0.0, 0.0, 100.0, 50.0))
        val snap = img.snapshotGeometry()
        img.applyTransform(Affine.rotateAbout(Pt(50.0, 25.0), PI / 3))
        img.restoreGeometry(snap)
        assertEquals(0.0, img.angle, 1e-12)
        assertEquals(Rect(0.0, 0.0, 100.0, 50.0), img.rect)
    }

    // --- text ---

    @Test fun cornerScaleGrowsFontAndBox() {
        val t = TextItem(Pt(0.0, 0.0), width = 100.0, height = 40.0, text = "x", pointSize = 10.0, measurer = FakeTextMeasurer())
        t.applyTransform(Affine.scaleAbout(Pt.ZERO, 2.0, 2.0))
        assertEquals(200.0, t.width, 1e-9)
        assertEquals(20.0, t.pointSize, 1e-9)
        assertEquals(80.0, t.height, 1e-9) // max(40, content) * 2
    }

    @Test fun edgeScaleLeavesFontAlone() {
        val t = TextItem(Pt(0.0, 0.0), width = 100.0, height = 40.0, text = "x", pointSize = 10.0, measurer = FakeTextMeasurer())
        t.applyTransform(Affine.scaleAbout(Pt.ZERO, 2.0, 1.0)) // a horizontal edge drag
        assertEquals(200.0, t.width, 1e-9)
        assertEquals(10.0, t.pointSize, 1e-9) // font unchanged
        assertEquals(40.0, t.height, 1e-9) // vertical axis untouched
    }
}
