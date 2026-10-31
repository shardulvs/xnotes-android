package com.xnotes.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.xnotes.core.geometry.Pt
import com.xnotes.core.tools.Tool

/**
 * Where the pen menu is open, if it is: the armed tool's settings, opened at the pen by the stylus
 * side button's tap gesture ("Open tool settings") and closed by the next one.
 *
 * Unlike the toolbar's popups it does not take focus. A focused popup takes every pen event on the
 * screen, the hovering side button's included, so the click that should close it would never reach
 * the page. Without focus the page still hears the button, and a touch on the page while the menu
 * is open only closes it ([takesTouch]), so the tap that dismisses it leaves no ink.
 */
class PenMenuState {
    /** The pen's place when the menu opened, in viewport px, or null when it is closed. */
    var at by mutableStateOf<Pt?>(null)
        private set

    private var closedAt = 0L

    fun toggle(where: Pt) {
        if (at != null) close() else at = where
    }

    fun close() {
        if (at == null) return
        at = null
        closedAt = SystemClock.uptimeMillis()
    }

    /**
     * A touch landed on the page. True when it belongs to the menu: the menu was open (it closes
     * now), or a touch outside it closed it a moment ago, since the popup hears that touch and
     * closes before the page does.
     */
    fun takesTouch(): Boolean {
        val open = at != null
        close()
        return open || SystemClock.uptimeMillis() - closedAt < CLOSED_BY_THIS_TOUCH_MS
    }

    private companion object {
        const val CLOSED_BY_THIS_TOUCH_MS = 250L
    }
}

/** What the pen menu needs from whichever editor is open. */
interface PenMenuHost : ToolPopupHost {
    val penMenu: PenMenuState

    /** The armed tool when it has settings to show, else null. */
    val penMenuTool: Tool?
}

/** Whether [this] tool has a settings popup on the toolbar: the ones the pen menu can open. */
internal val Tool.hasSettings: Boolean
    get() = isStroke || this == Tool.SHAPE || this == Tool.ERASER || this == Tool.SELECT ||
        this == Tool.TEXT || this == Tool.MARKUP

/** The armed tool's settings at the pen, the same popups as the toolbar's. */
@Composable
fun PenMenu(host: PenMenuHost) {
    val at = host.penMenu.at ?: return
    val tool = host.penMenuTool
    if (tool == null) {
        SideEffect { host.penMenu.close() }
        return
    }
    val density = LocalDensity.current
    val close = { host.penMenu.close() }
    Box(Modifier.offset(with(density) { at.x.toFloat().toDp() }, with(density) { at.y.toFloat().toDp() }).size(1.dp)) {
        CompositionLocalProvider(LocalMenuFocusable provides false) {
            when {
                tool == Tool.SHAPE -> ShapeConfigPopup(host, close)
                tool == Tool.ERASER -> EraserConfigPopup(host, close)
                tool == Tool.SELECT -> SelectConfigPopup(host, close)
                tool == Tool.MARKUP -> MarkupToolPopup(host, close)
                tool == Tool.TEXT -> (host as? Editor)?.let { TextToolConfigPopup(it, close) }
                else -> ToolConfigPopup(host, tool, close)
            }
        }
    }
}
