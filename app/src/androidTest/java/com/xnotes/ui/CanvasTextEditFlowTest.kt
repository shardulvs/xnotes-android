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

    fun testNativeTextAndSelectionChromeMoveTogether() {
        instrumentation.runOnMainSync {
            val editor = InfiniteEditor(instrumentation.targetContext)
            editor.newCanvas()
            editor.viewport.widthPx = 1300
            editor.viewport.heightPx = 800
            editor.viewport.zoom = 1.0
            editor.viewport.scrollX = 0.0
            editor.viewport.scrollY = 0.0
            editor.armTool(Tool.TEXT_BOX)
            tap(editor, 100f, 110f, 1000L)
            editor.updateEditingText("Move this text with its selection frame")
            editor.commitText()
            val item = editor.document.items.filterIsInstance<TextItem>().single()
            editor.armTool(Tool.SELECT)
            editor.selection.select(listOf(item))
            val controller = InfiniteEditor::class.java.getDeclaredField("textMeshes").apply {
                isAccessible = true
            }.get(editor)
            controller.javaClass.getMethod("startGesture", TextItem::class.java).invoke(controller, item)
            val fallback = editor.surfaces.getChildAt(2)
            fallback.layout(0, 0, 1300, 800)
            val original = android.graphics.Bitmap.createBitmap(1300, 800, android.graphics.Bitmap.Config.ARGB_8888)
            fallback.draw(android.graphics.Canvas(original))
            assertTrue(android.graphics.Color.alpha(original.getPixel(100, 110)) > 0)

            editor.selection.beginTransform()
            editor.selection.previewMove(250.0, 150.0)
            fallback.javaClass.getMethod(
                "setLift", Double::class.javaPrimitiveType, Double::class.javaPrimitiveType,
            ).invoke(fallback, 250.0, 150.0)
            val moved = android.graphics.Bitmap.createBitmap(1300, 800, android.graphics.Bitmap.Config.ARGB_8888)
            fallback.draw(android.graphics.Canvas(moved))
            assertEquals(0, android.graphics.Color.alpha(moved.getPixel(100, 110)))
            assertTrue(android.graphics.Color.alpha(moved.getPixel(350, 260)) > 0)
            original.recycle()
            moved.recycle()
        }
    }

    fun testNewCanvasTextBoxUsesScreenReadableWidthAtLowZoom() {
        instrumentation.runOnMainSync {
            val editor = InfiniteEditor(instrumentation.targetContext)
            editor.newCanvas()
            editor.viewport.widthPx = 1600
            editor.viewport.heightPx = 1000
            editor.viewport.zoom = 0.5
            editor.armTool(Tool.TEXT_BOX)
            tap(editor, 100f, 110f, 1000L)
            val field = editor.editingField!!
            val density = instrumentation.targetContext.resources.displayMetrics.density
            assertEquals(300.0 * density, field.width * field.zoom, 0.01)
            editor.commitText()
        }
    }

    fun testNativeTextPreviewDropsOldDragTranslationBeforeResize() {
        instrumentation.runOnMainSync {
            val editor = InfiniteEditor(instrumentation.targetContext)
            editor.newCanvas()
            editor.viewport.widthPx = 1600
            editor.viewport.heightPx = 1000
            editor.armTool(Tool.TEXT_BOX)
            tap(editor, 100f, 110f, 1000L)
            editor.updateEditingText("Text that will be resized after moving")
            editor.commitText()
            val item = editor.document.items.filterIsInstance<TextItem>().single()
            val fallback = editor.surfaces.getChildAt(2)
            fallback.translationX = 240f
            fallback.translationY = -170f
            // Exercise the native/GL transition itself, independent of pointer sampling.
            val controller = InfiniteEditor::class.java.getDeclaredField("textMeshes").apply {
                isAccessible = true
            }.get(editor)
            controller.javaClass.getMethod("startGesture", TextItem::class.java).invoke(controller, item)
            assertEquals(0f, fallback.translationX)
            assertEquals(0f, fallback.translationY)
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

    fun testTextToolTapAwayStartsBlankSessionAndDropsLateImeUpdates() {
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
            editor.updateEditingText("Previous box")
            editor.commitText()
            tap(editor, 110f, 118f, 2000L)
            val previousSession = editor.textEditSession
            assertEquals("Previous box", editor.editingField?.text)
            // Unlike the older test, tap the new location while the old editor is still active.
            tap(editor, 650f, 350f, 3000L)
            assertEquals("", editor.editingField?.text)
            editor.updateEditingTextIfSession(previousSession, "Previous box")
            assertEquals("", editor.document.items.filterIsInstance<TextItem>().last().text)
            editor.commitText()
            val boxes = editor.document.items.filterIsInstance<TextItem>()
            assertEquals(1, boxes.size) // The empty draft is removed; the original stays intact.
            assertEquals("Previous box", boxes.single().text)
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
