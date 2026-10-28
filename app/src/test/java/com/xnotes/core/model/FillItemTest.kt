package com.xnotes.core.model

import com.xnotes.core.FakeRenderer
import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.shapes.ShapeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FillItemTest {

    private val red = Rgba(220, 40, 40, 255)

    private fun square(x: Double, y: Double, s: Double) = listOf(Pt(x, y), Pt(x + s, y), Pt(x + s, y + s), Pt(x, y + s))

    /** A 100 px square with a 40 px hole in its middle. */
    private fun ring() = ShapeItem.fill(listOf(square(0.0, 0.0, 100.0), square(30.0, 30.0, 40.0)), red)

    @Test fun aFillIsSolidAndNeverStroked() {
        val f = ring()
        assertSame(ShapeKind.FILL, f.shape)
        assertTrue(f.isFill)
        assertEquals(red, f.fillRgba)
        assertEquals(0.0, f.strokeWidth, 0.0)
        val r = FakeRenderer()
        f.paint(r)
        assertTrue("no outline", r.pathPens.isEmpty())
        assertEquals(listOf(red), r.pathFills)
    }

    @Test fun theHoleIsNotTheFill() {
        val f = ring()
        assertTrue(f.contains(Pt(10.0, 10.0)))
        assertFalse(f.contains(Pt(50.0, 50.0)))
        assertFalse(f.contains(Pt(150.0, 50.0)))
    }

    @Test fun bothErasersTakeAFillWhole() {
        val f = ring()
        assertEquals(emptyList<CanvasItem>(), f.erasedBy(10.0, 10.0, 4.0))
        assertNull("the eraser in the hole misses", f.erasedBy(50.0, 50.0, 4.0))
        assertNull(f.erasedBy(300.0, 300.0, 4.0))
    }

    @Test fun aFillHasNoOutlineToRestyleAndNothingGluesToIt() {
        val f = ring()
        assertNull(DrawStyle.of(f))
        DrawStyle(Rgba(1, 2, 3, 255), 9.0).applyTo(f)
        assertEquals(red, f.fillRgba)
        assertTrue(Glue.points(f).isEmpty())
        assertFalse(f.takesLabel)
        assertTrue(f.vertexDots().isEmpty())
    }

    @Test fun aFillMovesAndTurnsExactly() {
        val f = ring()
        f.applyTransform(Affine.rotateAbout(Pt(50.0, 50.0), Math.PI / 2))
        f.translate(10.0, 0.0)
        assertTrue(f.contains(Pt(20.0, 10.0)))
        assertFalse(f.contains(Pt(60.0, 50.0)))
    }
}
