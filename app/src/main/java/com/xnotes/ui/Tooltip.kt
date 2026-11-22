package com.xnotes.ui

import android.content.res.Configuration
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Which side of its anchor a tooltip opens on; it flips to the opposite side when there is no room. */
internal enum class TipSide { BELOW, ABOVE, LEFT, RIGHT }

/** Where tips open by default; a bar sets it so its tips open into the page, away from the screen edge. */
internal val LocalTipSide = compositionLocalOf { TipSide.BELOW }

/**
 * Names [content] while a pen or mouse hovers over it: an inverse chip that fades in after a short
 * wait, or at once while the pointer moves on from a neighbour whose tip was showing. [hint] is a
 * dimmer second line for a gesture the control hides; [shortcut] shows beside the name only while a
 * hardware keyboard is attached. A press hides the tip until the pointer leaves, so a tapped button
 * doesn't name itself again under the pen.
 */
@Composable
internal fun Tooltip(
    text: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    shortcut: String? = null,
    side: TipSide = LocalTipSide.current,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val tip = remember { TipState(scope) }
    DisposableEffect(tip) { onDispose { tip.dispose() } }
    Box(modifier.pointerInput(tip) { trackHover(tip) }, propagateMinConstraints = true) {
        content()
        if (tip.mounted) TipPopup(tip, text, hint, shortcut?.takeIf { keyboardAttached() }, side)
    }
}

/** Whether a hardware keyboard is there to press the shortcuts chrome names. */
@Composable
internal fun keyboardAttached(): Boolean {
    val c = LocalConfiguration.current
    return c.keyboard != Configuration.KEYBOARD_NOKEYS && c.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
}

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.trackHover(tip: TipState) = awaitPointerEventScope {
    while (true) {
        val e = awaitPointerEvent(PointerEventPass.Initial)
        val change = e.changes.firstOrNull() ?: continue
        when (e.type) {
            PointerEventType.Enter ->
                if (!change.pressed && (change.type == PointerType.Stylus || change.type == PointerType.Eraser || change.type == PointerType.Mouse)) tip.enter()
            PointerEventType.Exit -> tip.exit()
            PointerEventType.Press, PointerEventType.Scroll -> tip.press()
            PointerEventType.Release -> tip.release()
        }
    }
}

/** What every tip shares: the one on screen, and when one was last seen, so a sweep along a bar keeps them coming at once. */
private object Tips {
    var current: TipState? = null
    var lastSeen = Long.MIN_VALUE / 2

    fun warm(): Boolean = current != null || SystemClock.uptimeMillis() - lastSeen < TIP_WARM_MS

    /** A press means the pen is acting, not browsing: the next tip waits its turn again. */
    fun cool() {
        lastSeen = Long.MIN_VALUE / 2
    }
}

private class TipState(private val scope: CoroutineScope) {
    /** The popup is in the composition, fading in, shown or fading out. */
    var mounted by mutableStateOf(false)
        private set

    /** Shown at once from a neighbour's tip, so it only fades, without the rise. */
    var warm by mutableStateOf(false)
        private set

    val fade = Animatable(0f)
    private var job: Job? = null
    private var suppressed = false
    private var releasedAt = Long.MIN_VALUE / 2

    /** A pen re-enters just after a tap (its hover resumes on lift), which is no new visit. */
    private fun justReleased() = SystemClock.uptimeMillis() - releasedAt < TIP_SETTLE_MS

    fun enter() {
        if (suppressed && justReleased()) return
        suppressed = false
        job?.cancel()
        val warm = Tips.warm()
        job = scope.launch {
            if (!warm) delay(TIP_DELAY_MS)
            appear(warm)
        }
    }

    fun exit() {
        if (!justReleased()) suppressed = false
        job?.cancel()
        if (!mounted) return
        job = scope.launch {
            fade.animateTo(0f, tween(TIP_OUT_MS, easing = LinearEasing))
            unmount()
        }
    }

    fun press() {
        suppressed = true
        vanish()
        Tips.cool()
    }

    fun release() {
        releasedAt = SystemClock.uptimeMillis()
    }

    fun dispose() {
        job?.cancel()
        if (Tips.current === this) Tips.current = null
    }

    private suspend fun appear(warm: Boolean) {
        Tips.current?.takeIf { it !== this }?.vanish()
        Tips.current = this
        this.warm = warm
        mounted = true
        if (warm) fade.animateTo(1f, tween(TIP_WARM_IN_MS, easing = LinearEasing))
        else fade.animateTo(1f, tween(TIP_IN_MS, easing = LinearOutSlowInEasing))
    }

    /** Gone at once: the pen pressed, or a neighbour's tip took over. */
    private fun vanish() {
        job?.cancel()
        job = scope.launch { fade.snapTo(0f) }
        unmount()
    }

    private fun unmount() {
        if (!mounted) return
        mounted = false
        if (Tips.current === this) {
            Tips.current = null
            Tips.lastSeen = SystemClock.uptimeMillis()
        }
    }
}

/** Never focusable or touchable, so the pen hovers and taps straight through it. */
private val TIP_WINDOW = PopupProperties(
    flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
    dismissOnBackPress = false,
    dismissOnClickOutside = false,
    excludeFromSystemGesture = false,
)

@Composable
private fun TipPopup(tip: TipState, text: String, hint: String?, shortcut: String?, side: TipSide) {
    val density = LocalDensity.current
    val placement = remember(side, density) {
        with(density) { TipPlacement(side, (TIP_GAP - TIP_SHADOW_ROOM).roundToPx()) }
    }
    val rise = with(density) { TIP_RISE.toPx() }
    Popup(popupPositionProvider = placement, properties = TIP_WINDOW) {
        // The chip sits inset in its window so its shadow has room to fall.
        Box(
            Modifier
                .padding(TIP_SHADOW_ROOM)
                .graphicsLayer {
                    val f = tip.fade.value
                    alpha = f
                    if (!tip.warm) {
                        val s = TIP_START_SCALE + (1 - TIP_START_SCALE) * f
                        scaleX = s
                        scaleY = s
                        val back = (1 - f) * rise
                        when (placement.placed) {
                            TipSide.BELOW -> translationY = -back
                            TipSide.ABOVE -> translationY = back
                            TipSide.RIGHT -> translationX = -back
                            TipSide.LEFT -> translationX = back
                        }
                    }
                    transformOrigin = when (placement.placed) {
                        TipSide.BELOW -> TransformOrigin(0.5f, 0f)
                        TipSide.ABOVE -> TransformOrigin(0.5f, 1f)
                        TipSide.RIGHT -> TransformOrigin(0f, 0.5f)
                        TipSide.LEFT -> TransformOrigin(1f, 0.5f)
                    }
                },
        ) { TipChip(text, hint, shortcut) }
    }
}

@Composable
private fun TipChip(text: String, hint: String?, shortcut: String?) {
    val m = LocalPalette.current.materialColors
    val bg = m.inverseSurface.toComposeColor()
    val fg = m.inverseOnSurface.toComposeColor()
    val shape = MaterialTheme.shapes.small
    Column(
        Modifier
            .shadow(TIP_ELEVATION, shape)
            .background(bg, shape)
            .widthIn(max = TIP_MAX_WIDTH)
            .padding(start = 10.dp, end = if (shortcut != null) 6.dp else 10.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 17.sp, modifier = Modifier.weight(1f, fill = false))
            if (shortcut != null) {
                Spacer(Modifier.width(10.dp))
                Text(
                    shortcut,
                    color = fg.copy(alpha = 0.72f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .border(1.dp, fg.copy(alpha = 0.22f), TIP_KEY_SHAPE)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
        if (hint != null) Text(hint, color = fg.copy(alpha = 0.72f), fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 1.dp))
    }
}

/**
 * Puts a tip on its [side] of the anchor, centred along it, or on the opposite side when that one
 * runs off the window; then slides it along to stay on screen. [placed] is the side it chose.
 */
private class TipPlacement(private val side: TipSide, private val gap: Int) : PopupPositionProvider {
    var placed by mutableStateOf(side)
        private set

    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val w = popupContentSize.width
        val h = popupContentSize.height
        val a = anchorBounds
        val fitsBelow = a.bottom + gap + h <= windowSize.height
        val fitsAbove = a.top - gap - h >= 0
        val fitsRight = a.right + gap + w <= windowSize.width
        val fitsLeft = a.left - gap - w >= 0
        val s = when (side) {
            TipSide.BELOW -> if (!fitsBelow && fitsAbove) TipSide.ABOVE else TipSide.BELOW
            TipSide.ABOVE -> if (!fitsAbove && fitsBelow) TipSide.BELOW else TipSide.ABOVE
            TipSide.RIGHT -> if (!fitsRight && fitsLeft) TipSide.LEFT else TipSide.RIGHT
            TipSide.LEFT -> if (!fitsLeft && fitsRight) TipSide.RIGHT else TipSide.LEFT
        }
        if (placed != s) placed = s
        val x = when (s) {
            TipSide.BELOW, TipSide.ABOVE -> a.center.x - w / 2
            TipSide.RIGHT -> a.right + gap
            TipSide.LEFT -> a.left - gap - w
        }
        val y = when (s) {
            TipSide.BELOW -> a.bottom + gap
            TipSide.ABOVE -> a.top - gap - h
            TipSide.RIGHT, TipSide.LEFT -> a.center.y - h / 2
        }
        return IntOffset(x.coerceIn(0, maxOf(0, windowSize.width - w)), y.coerceIn(0, maxOf(0, windowSize.height - h)))
    }
}

private const val TIP_DELAY_MS = 500L
private const val TIP_WARM_MS = 600L
private const val TIP_SETTLE_MS = 400L
private const val TIP_IN_MS = 160
private const val TIP_WARM_IN_MS = 90
private const val TIP_OUT_MS = 110
private const val TIP_START_SCALE = 0.92f
private val TIP_GAP = 6.dp
private val TIP_RISE = 4.dp
private val TIP_SHADOW_ROOM = 8.dp
private val TIP_ELEVATION = 4.dp
private val TIP_MAX_WIDTH = 280.dp
private val TIP_KEY_SHAPE = RoundedCornerShape(4.dp)

/** The keyboard shortcuts as tooltips spell them; the editors' key handlers are what they name. */
internal object Keys {
    const val UNDO = "Ctrl+Z"
    const val REDO = "Ctrl+Shift+Z"
    const val SIDEBAR = "Ctrl+B"
    const val SEARCH = "Ctrl+F"
    const val NEXT_MATCH = "Enter"
    const val PREV_MATCH = "Shift+Enter"
    const val ZOOM_IN = "Ctrl+="
    const val ZOOM_OUT = "Ctrl+-"
    const val FIT_ALL = "Ctrl+0"
    const val PREV_PAGE = "PgUp"
    const val NEXT_PAGE = "PgDn"
    const val FULLSCREEN = "F11"
    const val BOLD = "Ctrl+B"
    const val ITALIC = "Ctrl+I"
    const val UNDERLINE = "Ctrl+U"
    const val STRIKE = "Ctrl+Shift+X"
    const val INDENT = "Tab"
    const val OUTDENT = "Shift+Tab"
    const val CUT = "Ctrl+X"
    const val COPY = "Ctrl+C"
    const val DELETE = "Del"
}
