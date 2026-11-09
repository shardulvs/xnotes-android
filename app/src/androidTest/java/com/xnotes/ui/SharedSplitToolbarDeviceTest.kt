package com.xnotes.ui

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.xnotes.MainActivity
import com.xnotes.R
import com.xnotes.core.tools.*
import com.xnotes.settings.*

/** Real split host, real surfaces and real toolbar actions. Use an isolated test application ID. */
@Suppress("DEPRECATION")
class SharedSplitToolbarDeviceTest : InstrumentationTestCase() {
    private lateinit var activity: MainActivity
    private lateinit var editor: Editor
    private lateinit var previousPreferences: Preferences
    private lateinit var previousNoteLayout: ToolbarLayout
    private lateinit var previousCanvasLayout: ToolbarLayout

    override fun tearDown() {
        if (::editor.isInitialized) instrumentation.runOnMainSync {
            editor.goHomeAll()
            editor.applyToolbarLayout(previousNoteLayout)
            editor.applyCanvasToolbarLayout(previousCanvasLayout)
            editor.applyHomePreferences(previousPreferences)
        }
        if (::activity.isInitialized) instrumentation.runOnMainSync { activity.finish() }
        super.tearDown()
    }

    private fun show(firstCanvas: Boolean, secondCanvas: Boolean) {
        assertTrue("Use the isolated validation build", instrumentation.targetContext.packageName.endsWith(".sharedsplit"))
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        instrumentation.runOnMainSync {
            editor = MainActivity::class.java.getDeclaredField("editor").apply { isAccessible = true }.get(activity) as Editor
            previousPreferences = editor.preferences
            previousNoteLayout = editor.toolbarLayout
            previousCanvasLayout = editor.canvasToolbarLayout
            fun layout(vararg items: ToolbarItem) = ToolbarLayout(listOf(ToolbarSection(items.map { ToolbarEntry(it) })))
            editor.applyToolbarLayout(layout(ToolbarItem.PEN, ToolbarItem.SELECT, ToolbarItem.UNDO, ToolbarItem.REDO, ToolbarItem.SIDEBAR))
            editor.applyCanvasToolbarLayout(layout(ToolbarItem.PEN, ToolbarItem.SELECT, ToolbarItem.UNDO, ToolbarItem.REDO, ToolbarItem.MINIMAP))
            editor.applyHomePreferences(previousPreferences.copy(sharedSplitToolbars = true, toolbarLook = ToolbarLook(), mixedSplitToolbar = SplitToolbarInterface.NOTE))
            if (firstCanvas) editor.newCanvas() else editor.newNote()
            val second = editor.secondaryPane()
            if (secondCanvas) second.newCanvas() else second.newNote()
            editor.focusPane(Pane.PRIMARY)
        }
        settle()
    }

    private fun settle() {
        instrumentation.waitForIdleSync()
        Thread.sleep(200)
    }

    private fun nodes(description: String): List<Pair<Rect, Boolean>> {
        val found = mutableListOf<Pair<Rect, Boolean>>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.contentDescription?.toString() == description || node.text?.toString() == description) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (!bounds.isEmpty) found.add(bounds to node.isEnabled)
            }
            for (i in 0 until node.childCount) visit(node.getChild(i))
        }
        visit(instrumentation.uiAutomation.rootInActiveWindow)
        return found
    }

    private fun waitNodes(description: String): List<Pair<Rect, Boolean>> {
        val until = SystemClock.uptimeMillis() + 4000
        var result = nodes(description)
        while (result.isEmpty() && SystemClock.uptimeMillis() < until) {
            Thread.sleep(80)
            result = nodes(description)
        }
        assertFalse("Missing $description", result.isEmpty())
        return result
    }

    private fun click(description: String) {
        val target = waitNodes(description).first().first
        tap(target.exactCenterX(), target.exactCenterY())
        settle()
    }

    private fun tap(screenX: Float, screenY: Float) {
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, screenX, screenY, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.sendPointerSync(event)
            event.recycle()
        }
    }

    private fun checkEdgesAndRatios() {
        val pen = activity.getString(R.string.tool_pen)
        val firstSurface = if (editor.canvasOpen) editor.infinite.surfaces else editor.surfaces
        val second = editor.secondary!!
        val secondSurface = if (second.canvasOpen) second.infinite.surfaces else second.surfaces
        for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)) {
        instrumentation.runOnMainSync { activity.requestedOrientation = orientation }
        Thread.sleep(600)
        for (floating in listOf(false, true)) for (edge in ToolbarPosition.entries) {
            instrumentation.runOnMainSync {
                editor.applyHomePreferences(editor.preferences.copy(toolbarLook = ToolbarLook(edge, floating = floating)))
            }
            settle()
            for (ratio in listOf(.25f, .5f, .75f)) {
                instrumentation.runOnMainSync { editor.splitRatio = ratio }
                settle()
                assertEquals("Duplicate tools at $edge/$floating/$ratio", 1, waitNodes(pen).size)
                instrumentation.runOnMainSync {
                    assertTrue(firstSurface.width > 0 && secondSurface.width > 0)
                    assertSame(firstSurface, if (editor.canvasOpen) editor.infinite.surfaces else editor.surfaces)
                    assertSame(secondSurface, if (second.canvasOpen) second.infinite.surfaces else second.surfaces)
                }
            }
        }
        }
    }

    fun testNotesShareOneToolbarOnEveryEdge() { show(false, false); checkEdgesAndRatios() }
    fun testCanvasesShareOneToolbarOnEveryEdge() { show(true, true); checkEdgesAndRatios() }

    fun testMixedInterfaceChoiceAndUnavailableControls() {
        show(false, true)
        val second = editor.secondary!!
        waitNodes(activity.getString(R.string.side_panel))
        instrumentation.runOnMainSync { editor.focusPane(Pane.SECONDARY) }
        settle()
        val sidebar = activity.getString(R.string.split_tool_unavailable, activity.getString(ToolbarItem.SIDEBAR.labelRes))
        assertTrue(waitNodes(sidebar).all { !it.second })
        assertSame(second, editor.active)
        click(activity.getString(R.string.split_settings))
        click(activity.getString(R.string.split_interface_canvas))
        assertEquals(SplitToolbarInterface.CANVAS, editor.preferences.mixedSplitToolbar)
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        settle()
        waitNodes(activity.getString(R.string.toolbar_minimap))
        instrumentation.runOnMainSync { editor.focusPane(Pane.PRIMARY) }
        settle()
        val minimap = activity.getString(R.string.split_tool_unavailable, activity.getString(ToolbarItem.MINIMAP.labelRes))
        assertTrue(waitNodes(minimap).all { !it.second })
        assertEquals(1, waitNodes(activity.getString(R.string.tool_pen)).size)
    }

    fun testPreferenceSwitchesSharedAndPerPaneInTheNote() {
        show(false, true)
        click(activity.getString(R.string.split_settings))
        click(activity.getString(R.string.split_per_pane))
        assertFalse(editor.preferences.sharedSplitToolbars)
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        settle()
        assertEquals(2, waitNodes(activity.getString(R.string.tool_pen)).size)
        instrumentation.runOnMainSync {
            editor.selectTool(Tool.PAN)
            editor.secondary!!.infinite.armTool(Tool.PEN)
            editor.focusPane(Pane.SECONDARY)
            assertEquals(Tool.PEN, editor.secondary!!.infinite.tool)
        }
        click(activity.getString(R.string.split_settings))
        click(activity.getString(R.string.split_shared))
        assertTrue(editor.preferences.sharedSplitToolbars)
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        java.io.File(activity.getExternalFilesDir(null), "shared-split-settings.png").outputStream().use {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        settle()
        assertEquals(1, waitNodes(activity.getString(R.string.tool_pen)).size)
    }

    private fun count(pane: Editor): Int = if (pane.canvasOpen) pane.infinite.document.items.size
        else pane.state.document.pages.sumOf { it.items.size }

    private fun stroke(pane: Editor) {
        val origin = IntArray(2)
        val location = IntArray(2)
        var x = 0f
        var y = 0f
        instrumentation.runOnMainSync {
            val surface = if (pane.canvasOpen) pane.infinite.surfaces else pane.surfaces
            surface.getLocationOnScreen(location)
            activity.window.decorView.getLocationOnScreen(origin)
            x = location[0] - origin[0] + surface.width * .45f
            y = location[1] - origin[1] + surface.height * .4f
        }
        val time = SystemClock.uptimeMillis()
        for (i in 0..5) {
            val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }
            val coords = MotionEvent.PointerCoords().apply { this.x = x + i * 8; this.y = y + i * 6; pressure = .7f; size = .1f }
            val event = MotionEvent.obtain(time, time + i * 16, when (i) { 0 -> MotionEvent.ACTION_DOWN; 5 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE },
                1, arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
            instrumentation.runOnMainSync { activity.dispatchTouchEvent(event) }
            event.recycle()
        }
        settle()
    }

    private fun checkDrawingAndUndo(firstCanvas: Boolean, secondCanvas: Boolean, canvasInterface: Boolean = false) {
        show(firstCanvas, secondCanvas)
        if (canvasInterface) {
            instrumentation.runOnMainSync {
                editor.applyHomePreferences(editor.preferences.copy(mixedSplitToolbar = SplitToolbarInterface.CANVAS))
            }
            settle()
        }
        click(activity.getString(R.string.tool_pen))
        stroke(editor)
        assertEquals(1, count(editor))
        val second = editor.secondary!!
        stroke(second) // first DOWN must focus and carry the pen before the native view draws.
        assertSame(second, editor.active)
        assertEquals(1, count(second))
        click(activity.getString(R.string.undo))
        assertEquals(0, count(second))
        assertEquals(1, count(editor))
        click(activity.getString(R.string.redo))
        assertEquals(1, count(second))
    }

    fun testNoteUndoTargetsOnlyActiveDocument() = checkDrawingAndUndo(false, false)
    fun testCanvasUndoTargetsOnlyActiveDocument() = checkDrawingAndUndo(true, true)
    fun testMixedUndoTargetsOnlyActiveDocument() = checkDrawingAndUndo(false, true)
    fun testReversedMixedCanvasInterfaceUndo() = checkDrawingAndUndo(true, false, true)

    private fun checkSelectionSurvivesFocus(canvas: Boolean) {
        show(false, canvas)
        click(activity.getString(R.string.tool_pen))
        val second = editor.secondary!!
        stroke(second)
        instrumentation.runOnMainSync {
            if (canvas) second.infinite.selectAll() else {
                second.selectTool(Tool.SELECT)
                second.selectAll()
            }
            assertTrue(if (canvas) second.infinite.hasSelection else second.hasSelection)
            editor.focusPane(Pane.PRIMARY)
            editor.selectTool(Tool.PEN)
            editor.focusPane(Pane.SECONDARY)
            assertTrue("Changing focus cleared the action's selection",
                if (canvas) second.infinite.hasSelection else second.hasSelection)
            assertEquals(1, count(second))
            if (canvas) second.infinite.deleteSelection() else second.deleteSelection()
            assertEquals(0, count(second))
        }
    }

    fun testNoteSelectionSurvivesReturningFocus() = checkSelectionSurvivesFocus(false)
    fun testCanvasSelectionSurvivesReturningFocus() = checkSelectionSurvivesFocus(true)

    fun testFocusCanBeRequestedBeforeThePrimaryDocumentOpens() {
        show(false, true)
        instrumentation.runOnMainSync {
            editor.focusPane(Pane.SECONDARY)
            editor.goHome()
            assertFalse(editor.noteOpen)
            editor.focusPane(Pane.PRIMARY)
            assertEquals(Pane.PRIMARY, editor.focusedPane)
            editor.newNote()
            assertSame(editor, editor.active)
        }
    }

    fun testRotationAndClosingKeepTheRemainingSurface() {
        show(false, true)
        val second = editor.secondary!!
        val surface = second.infinite.surfaces
        for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
            instrumentation.runOnMainSync { activity.requestedOrientation = orientation }
            Thread.sleep(800)
            settle()
            assertEquals(1, waitNodes(activity.getString(R.string.tool_pen)).size)
            assertSame(surface, second.infinite.surfaces)
        }
        instrumentation.runOnMainSync { editor.goHome() }
        settle()
        assertSame(second, editor.active)
        assertSame(surface, second.infinite.surfaces)
        assertEquals(1, waitNodes(activity.getString(R.string.tool_pen)).size)
        assertTrue(nodes(activity.getString(R.string.split_settings)).isEmpty())
    }
}
