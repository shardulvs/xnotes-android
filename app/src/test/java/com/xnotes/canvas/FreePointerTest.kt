package com.xnotes.canvas

import com.xnotes.core.tools.Tool
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The gesture table agreed for selecting PDF text (who may long-press it under which tool). */
class FreePointerTest {

    /** What a finger drives under [armed]: it pans when the tool leaves it to, unless it draws. */
    private fun finger(armed: Tool, fingerDraws: Boolean): Tool =
        if (!fingerDraws && armed.fingerPansWhenOff) Tool.PAN else armed

    @Test
    fun panFreesBothFingerAndStylus() {
        assertTrue(FreePointer.isFree(Tool.PAN, Tool.PAN, isFinger = true))
        assertTrue(FreePointer.isFree(Tool.PAN, Tool.PAN, isFinger = false))
    }

    @Test
    fun drawingToolsFreeTheFingerOnlyWhileItPans() {
        for (armed in listOf(Tool.PEN, Tool.HIGHLIGHTER, Tool.SELECT, Tool.LASSO, Tool.SHAPE, Tool.ERASER, Tool.SCREENSHOT)) {
            assertTrue("$armed, finger draw off", FreePointer.isFree(armed, finger(armed, fingerDraws = false), isFinger = true))
            assertFalse("$armed, finger draw on", FreePointer.isFree(armed, finger(armed, fingerDraws = true), isFinger = true))
            assertFalse("$armed, stylus", FreePointer.isFree(armed, armed, isFinger = false))
        }
    }

    @Test
    fun textToolsFreeNeither() {
        for (armed in listOf(Tool.TEXT, Tool.TEXT_BOX)) {
            assertFalse(FreePointer.isFree(armed, finger(armed, fingerDraws = false), isFinger = true))
            assertFalse(FreePointer.isFree(armed, armed, isFinger = false))
        }
    }

    @Test
    fun aSideButtonPanDoesNotFreeTheStylus() {
        assertFalse(FreePointer.isFree(Tool.PEN, Tool.PAN, isFinger = false))
        assertFalse(FreePointer.isFree(Tool.PAN, Tool.ERASER, isFinger = false))
    }
}
