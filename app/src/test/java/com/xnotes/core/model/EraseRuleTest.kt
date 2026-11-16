package com.xnotes.core.model

import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.SelectKind
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EraseRuleTest {

    private val sky = Rgba(90, 160, 240, 255)

    private fun fill() = ShapeItem.fill(listOf(listOf(Pt(0.0, 0.0), Pt(200.0, 0.0), Pt(200.0, 200.0), Pt(0.0, 200.0))), sky)

    private fun ink() = Stroke(Tool.PEN, ToolConfig(), (0..20).map { Sample(it * 5.0, 100.0, 1.0) })

    private fun box(fill: Rgba? = sky) = ShapeItem(ShapeKind.RECTANGLE, Pt(0.0, 0.0), Pt(200.0, 200.0), Rgba(1, 1, 1, 255), 3.0, fill)

    @Test fun aFillTheEraserTouchesAtAllGoesWhole() {
        assertTrue(EraseRule.takes(fill(), 50.0, 30.0, 6.0, SelectKind.ALL))
        assertTrue("even over ink", EraseRule.takes(fill(), 50.0, 100.0, 6.0, SelectKind.ALL))
        assertTrue("even just at its edge", EraseRule.takes(fill(), -4.0, 100.0, 6.0, SelectKind.ALL))
        assertFalse(EraseRule.takes(fill(), -10.0, 100.0, 6.0, SelectKind.ALL))
    }

    @Test fun linesGoAsEver() {
        assertTrue(EraseRule.takes(ink(), 50.0, 100.0, 6.0, SelectKind.ALL))
        assertFalse(EraseRule.takes(ink(), 50.0, 30.0, 6.0, SelectKind.ALL))
        assertTrue("an outline takes its shape", EraseRule.takes(box(), 100.0, 0.0, 6.0, SelectKind.ALL))
    }

    @Test fun aFilledShapeTouchedInsideLosesOnlyItsFill() {
        assertTrue(EraseRule.takesFill(box(), 100.0, 100.0, 6.0, SelectKind.ALL))
        assertFalse(EraseRule.takes(box(), 100.0, 100.0, 6.0, SelectKind.ALL))
        assertFalse("on its outline it goes whole instead", EraseRule.takesFill(box(), 100.0, 0.0, 6.0, SelectKind.ALL))
        assertFalse("a hollow one has nothing to lose inside", EraseRule.takesFill(box(null), 100.0, 100.0, 6.0, SelectKind.ALL))
    }

    @Test fun lockedItemsAreNeverTaken() {
        val locked = fill().also { it.locked = true }
        assertFalse(EraseRule.takes(locked, 50.0, 30.0, 6.0, SelectKind.ALL))
    }

    /** A switched-off kind survives the circle, a switched-on one goes. A shape's fill follows its kind. */
    @Test fun theFilterNarrowsWhatTheEraserTakes() {
        assertFalse(EraseRule.takes(ink(), 50.0, 100.0, 6.0, setOf(SelectKind.SHAPES, SelectKind.TAPE)))
        assertTrue(EraseRule.takes(ink(), 50.0, 100.0, 6.0, SelectKind.ALL))
        assertFalse(EraseRule.takes(ink(), 50.0, 100.0, 6.0, setOf(SelectKind.SHAPES)))
        assertTrue(EraseRule.takes(fill(), 50.0, 30.0, 6.0, setOf(SelectKind.SHAPES)))
        assertFalse(EraseRule.takesFill(box(), 100.0, 100.0, 6.0, setOf(SelectKind.INK)))
        assertTrue(EraseRule.takesFill(box(), 100.0, 100.0, 6.0, setOf(SelectKind.SHAPES)))
    }

    @Test fun highlighterIsItsOwnKind() {
        val hl = Stroke(Tool.HIGHLIGHTER, ToolConfig(), (0..20).map { Sample(it * 5.0, 100.0, 1.0) })
        assertEquals(SelectKind.HIGHLIGHTER, SelectKind.of(hl))
        assertFalse(EraseRule.takes(hl, 50.0, 100.0, 6.0, setOf(SelectKind.INK)))
        assertTrue(EraseRule.takes(hl, 50.0, 100.0, 6.0, setOf(SelectKind.HIGHLIGHTER)))
    }

    /** Text and audio go whole once their kind is on; audio is off by default, text is on. */
    @Test fun textAndAudioGoWholeWhenTheirKindIsOn() {
        val text = TextItem(Pt(10.0, 10.0), width = 60.0, text = "x", measurer = FakeTextMeasurer())
        val audio = AudioItem(AudioData(java.io.File("unused"), "m4a", 60_000, ByteArray(8) { 40 }), Rect(10.0, 10.0, 60.0, 16.0), "n", 1, FakeTextMeasurer())
        for (item in listOf(text, audio)) {
            assertTrue(EraseRule.whole(item))
            assertTrue("$item under the circle", EraseRule.takes(item, 20.0, 15.0, 4.0, SelectKind.ALL))
            assertFalse("$item away from the circle", EraseRule.takes(item, 200.0, 200.0, 4.0, SelectKind.ALL))
        }
        assertTrue(EraseRule.erasable(text, SelectKind.ERASER_DEFAULT))
        assertFalse(EraseRule.erasable(audio, SelectKind.ERASER_DEFAULT))
    }
}
