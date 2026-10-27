package com.xnotes.core.model

import com.xnotes.core.geometry.Pt
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EraseRuleTest {

    private val sky = Rgba(90, 160, 240, 255)

    private fun fill() = ShapeItem.fill(listOf(listOf(Pt(0.0, 0.0), Pt(200.0, 0.0), Pt(200.0, 200.0), Pt(0.0, 200.0))), sky)

    private fun ink() = Stroke(Tool.PEN, ToolConfig(), (0..20).map { Sample(it * 5.0, 100.0, 1.0) })

    private fun box(fill: Rgba? = sky) = ShapeItem(ShapeKind.RECTANGLE, Pt(0.0, 0.0), Pt(200.0, 200.0), Rgba(1, 1, 1, 255), 3.0, fill)

    @Test fun aFillTheEraserTouchesAtAllGoesWhole() {
        assertTrue(EraseRule.takes(fill(), 50.0, 30.0, 6.0))
        assertTrue("even over ink", EraseRule.takes(fill(), 50.0, 100.0, 6.0))
        assertTrue("even just at its edge", EraseRule.takes(fill(), -4.0, 100.0, 6.0))
        assertFalse(EraseRule.takes(fill(), -10.0, 100.0, 6.0))
    }

    @Test fun linesGoAsEver() {
        assertTrue(EraseRule.takes(ink(), 50.0, 100.0, 6.0))
        assertFalse(EraseRule.takes(ink(), 50.0, 30.0, 6.0))
        assertTrue("an outline takes its shape", EraseRule.takes(box(), 100.0, 0.0, 6.0))
    }

    @Test fun aFilledShapeTouchedInsideLosesOnlyItsFill() {
        assertTrue(EraseRule.takesFill(box(), 100.0, 100.0, 6.0))
        assertFalse(EraseRule.takes(box(), 100.0, 100.0, 6.0))
        assertFalse("on its outline it goes whole instead", EraseRule.takesFill(box(), 100.0, 0.0, 6.0))
        assertFalse("a hollow one has nothing to lose inside", EraseRule.takesFill(box(null), 100.0, 100.0, 6.0))
    }

    @Test fun lockedItemsAreNeverTaken() {
        val locked = fill().also { it.locked = true }
        assertFalse(EraseRule.takes(locked, 50.0, 30.0, 6.0))
    }
}
