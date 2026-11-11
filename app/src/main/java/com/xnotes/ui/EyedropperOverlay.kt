package com.xnotes.ui

import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.xnotes.R
import com.xnotes.core.model.Rgba
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlin.math.roundToInt

/** The armed eyedropper; [finger] (window px) and [sampled] feed the overlay's loupe. */
object Eyedropper {
    /** Where a picked colour goes; null while the eyedropper is not armed. */
    var target by mutableStateOf<((Rgba) -> Unit)?>(null)
        private set
    internal var finger by mutableStateOf<Offset?>(null)
    internal var sampled by mutableStateOf<Rgba?>(null)

    fun arm(onPick: (Rgba) -> Unit) {
        finger = null
        sampled = null
        target = onPick
    }

    /** Disarms, handing [picked] to the target when there is one. */
    fun end(picked: Rgba? = null) {
        val t = target ?: return
        target = null
        finger = null
        if (picked != null) t(picked)
    }
}

/** Draws the armed eyedropper: a hint until a finger lands, then the colour under it; Back cancels. */
@Composable
fun EyedropperOverlay() {
    if (Eyedropper.target == null) return
    val density = LocalDensity.current
    val palette = LocalPalette.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    BackHandler { Eyedropper.end() }
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }) {
        val at = Eyedropper.finger?.minus(origin)
        if (at == null) {
            Text(
                stringResource(R.string.eyedropper_hint),
                color = palette.text.toComposeColor(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 72.dp)
                    .background(palette.menuBg.toComposeColor(), RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        } else {
            val loupe = 56.dp
            val lift = with(density) { 88.dp.toPx() }
            val half = with(density) { loupe.toPx() / 2 }
            Box(
                Modifier
                    .offset {
                        // Near the top edge the loupe drops below the finger instead.
                        val y = if (at.y - lift - half >= 0f) at.y - lift - half else at.y + lift - half
                        IntOffset((at.x - half).roundToInt(), y.roundToInt())
                    }
                    .size(loupe)
                    .border(3.dp, Color.White, CircleShape)
                    .padding(3.dp)
                    .border(1.dp, Color.Black, CircleShape)
                    .background((Eyedropper.sampled ?: Rgba(0, 0, 0, 0)).toComposeColor(), CircleShape),
            )
        }
    }
}

/** The activity's touches: while armed one pointer picks, and two fingers go through to pan and zoom. */
class EyedropperTouch(window: Window) {
    private enum class Mode { PASS, PICK, SPENT }

    private val sampler = PixelSampler(window)
    private var mode = Mode.PASS
    private var pointer = -1

    fun dispatch(ev: MotionEvent, pass: (MotionEvent) -> Boolean): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            mode = if (Eyedropper.target != null) Mode.PICK else Mode.PASS
            pointer = ev.getPointerId(0)
        }
        if (mode == Mode.PASS) return pass(ev)
        if (mode == Mode.PICK) pick(ev, pass)
        return true
    }

    private fun pick(ev: MotionEvent, pass: (MotionEvent) -> Boolean) {
        val i = ev.findPointerIndex(pointer)
        if (i < 0) return
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> aim(ev.getX(i), ev.getY(i))
            MotionEvent.ACTION_POINTER_DOWN -> if (twoFingers(ev)) handOver(ev, pass)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> if (ev.actionIndex == i) lift(ev.getX(i), ev.getY(i))
            MotionEvent.ACTION_CANCEL -> stop()
        }
    }

    private fun aim(x: Float, y: Float) {
        Eyedropper.finger = Offset(x, y)
        sampler.sample(x, y) { if (it != null && mode == Mode.PICK) Eyedropper.sampled = it }
    }

    private fun lift(x: Float, y: Float) {
        mode = Mode.SPENT
        sampler.sample(x, y, force = true) { if (it != null) Eyedropper.end(it) else Eyedropper.finger = null }
    }

    private fun stop() {
        mode = Mode.SPENT
        Eyedropper.finger = null
    }

    private fun twoFingers(ev: MotionEvent) = ev.pointerCount == 2 &&
        ev.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER && ev.getToolType(1) == MotionEvent.TOOL_TYPE_FINGER

    /** The canvas never saw the first finger, so it gets a down for it before the second. */
    private fun handOver(ev: MotionEvent, pass: (MotionEvent) -> Boolean) {
        mode = Mode.PASS
        Eyedropper.finger = null
        val k = 1 - ev.actionIndex
        val props = MotionEvent.PointerProperties().also { ev.getPointerProperties(k, it) }
        val coords = MotionEvent.PointerCoords().also { ev.getPointerCoords(k, it) }
        val down = MotionEvent.obtain(
            ev.downTime, ev.eventTime, MotionEvent.ACTION_DOWN, 1, arrayOf(props), arrayOf(coords),
            ev.metaState, ev.buttonState, ev.xPrecision, ev.yPrecision, ev.deviceId, ev.edgeFlags, ev.source, ev.flags,
        )
        try {
            pass(down)
        } finally {
            down.recycle()
        }
        pass(ev)
    }
}

/** Reads single screen pixels: the window's, and the GL canvas surface's wherever the window shows through to it. */
private class PixelSampler(private val window: Window) {
    private val handler = Handler(Looper.getMainLooper())
    private var busy = false

    fun sample(wx: Float, wy: Float, force: Boolean = false, done: (Rgba?) -> Unit) {
        if (busy && !force) return
        val x = wx.roundToInt()
        val y = wy.roundToInt()
        busy = true
        val finish = { c: Rgba? -> busy = false; done(c) }
        read(x, y, { r, b, l -> PixelCopy.request(window, r, b, l, handler) }) { top ->
            val surface = if (top != null && top ushr 24 < 255) glSurfaceAt(window.decorView, x, y) else null
            if (top == null || surface == null) return@read finish(top?.let { blend(it, null) })
            val loc = IntArray(2).also { surface.getLocationInWindow(it) }
            read(x - loc[0], y - loc[1], { r, b, l -> PixelCopy.request(surface, r, b, l, handler) }) { finish(blend(top, it)) }
        }
    }

    private fun read(x: Int, y: Int, request: (android.graphics.Rect, Bitmap, PixelCopy.OnPixelCopyFinishedListener) -> Unit, then: (Int?) -> Unit) {
        val pixel = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        try {
            request(android.graphics.Rect(x, y, x + 1, y + 1), pixel) { then(if (it == PixelCopy.SUCCESS) pixel.getPixel(0, 0) else null) }
        } catch (e: IllegalArgumentException) {
            then(null)
        }
    }

    /** The window pixel [top] over the surface pixel [under], so translucent chrome over the canvas reads as seen. */
    private fun blend(top: Int, under: Int?): Rgba {
        val a = (top ushr 24) / 255f
        fun channel(shift: Int) = (((top shr shift) and 0xFF) * a + (((under ?: top) shr shift) and 0xFF) * (1 - a)).roundToInt()
        return Rgba(channel(16), channel(8), channel(0), 255)
    }

    /** The shown GL surface (the infinite canvas) covering window point ([x], [y]), if any. */
    private fun glSurfaceAt(v: View, x: Int, y: Int): GLSurfaceView? {
        if (!v.isShown) return null
        if (v is GLSurfaceView) {
            val loc = IntArray(2)
            v.getLocationInWindow(loc)
            return v.takeIf { x >= loc[0] && y >= loc[1] && x < loc[0] + v.width && y < loc[1] + v.height }
        }
        if (v is ViewGroup) for (i in v.childCount - 1 downTo 0) glSurfaceAt(v.getChildAt(i), x, y)?.let { return it }
        return null
    }
}
