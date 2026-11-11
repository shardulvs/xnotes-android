package com.xnotes.ui

import android.test.InstrumentationTestCase
import android.view.MotionEvent
import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.TextItem
import com.xnotes.core.tools.Tool

/** Exercises actual XCanvas taps and text sessions on Android. */
class CanvasTextEditFlowTest : InstrumentationTestCase() {
    private fun tap(editor: InfiniteEditor, x: Float, y: Float, time: Long) {
        val pointer = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_STYLUS
        }
        val coords = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }
        fun event(action: Int, whenMs: Long) = MotionEvent.obtain(
            time, whenMs, action, 1, arrayOf(pointer), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_STYLUS, 0,
        )
        val down = event(MotionEvent.ACTION_DOWN, time)
        val up = event(MotionEvent.ACTION_UP, time + 30)
        try {
            editor.interaction.onTouch(down)
            editor.interaction.onTouch(up)
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    fun testReopenEditAndCreateNewBoxKeepsTextAndPosition() {
        instrumentation.runOnMainSync {
            val editor = InfiniteEditor(instrumentation.targetContext)
            editor.newCanvas()
            editor.viewport.widthPx = 1600
            editor.viewport.heightPx = 1000
            editor.viewport.zoom = 1.0
            editor.viewport.scrollX = 0.0
            editor.viewport.scrollY = 0.0
            editor.armTool(Tool.TEXT_BOX)

            tap(editor, 100f, 110f, 1000L)
            assertEquals("", editor.editingField?.text)
            editor.updateEditingText("One text box with words to remove")
            editor.commitText()
            val first = editor.document.items.filterIsInstance<TextItem>().single()
            val before = first.pos
            assertEquals("One text box with words to remove", first.text)

            tap(editor, 110f, 118f, 2000L)
            assertEquals("One text box with words to remove", editor.editingField?.text)
            val firstSession = editor.textEditSession
            editor.updateEditingText("One text box with words")
            editor.commitText()
            assertEquals("One text box with words", first.text)
            assertEquals(before, first.pos)

            tap(editor, 600f, 150f, 3000L)
            assertEquals("", editor.editingField?.text)
            assertTrue(editor.textEditSession > firstSession)
            editor.updateEditingText("Independent text")
            editor.commitText()
            val boxes = editor.document.items.filterIsInstance<TextItem>()
            assertEquals(2, boxes.size)
            assertEquals("One text box with words", first.text)
            assertEquals(before, first.pos)
            assertEquals("Independent text", boxes.last().text)
            assertEquals(Pt(600.0, 150.0), boxes.last().pos)
        }
    }

    fun testDoubleTapLongTextKeepsGeometryStable() {
        instrumentation.runOnMainSync {
            val editor = InfiniteEditor(instrumentation.targetContext)
            editor.newCanvas()
            editor.viewport.widthPx = 1600
            editor.viewport.heightPx = 1000
            editor.viewport.zoom = 1.0
            editor.viewport.scrollX = 0.0
            editor.viewport.scrollY = 0.0
            editor.armTool(Tool.TEXT_BOX)
            tap(editor, 90f, 100f, 1000L)
            val longText = "Long paragraph with many words and wrapping. ".repeat(70)
            editor.updateEditingText(longText)
            editor.commitText()
            val item = editor.document.items.filterIsInstance<TextItem>().single()
            val originalPos = item.pos
            val originalWidth = item.width
            val originalHeight = item.bounds().h

            editor.armTool(Tool.SELECT)
            // Stay away from selection resize handles: this is a double tap on the text body.
            tap(editor, 200f, 230f, 2000L)
            tap(editor, 200f, 230f, 2150L)
            assertEquals(longText, editor.editingField?.text)
            assertEquals(originalPos, item.pos)
            editor.updateEditingText(longText + " edited")
            editor.commitText()
            assertEquals(originalPos, item.pos)
            assertEquals(originalWidth, item.width, 0.001)
            assertTrue(item.bounds().h >= originalHeight)
        }
    }
}
