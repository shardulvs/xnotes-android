package com.xnotes.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.xnotes.R
import com.xnotes.core.model.Rgba
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlin.math.roundToInt

/** Where a colour picked by the eyedropper goes; null while no eyedropper is armed. */
object Eyedropper {
    var target by mutableStateOf<((Rgba) -> Unit)?>(null)
}

/**
 * Full-screen touch layer shown while [Eyedropper.target] is set: a finger on the screen shows the
 * colour under it in a loupe above the finger, and lifting it hands that colour to the target.
 * Back cancels.
 */
@Composable
fun EyedropperOverlay() {
    val target = Eyedropper.target ?: return
    val view = LocalView.current
    val density = LocalDensity.current
    val palette = LocalPalette.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    var finger by remember { mutableStateOf<Offset?>(null) }
    var sampled by remember { mutableStateOf<Rgba?>(null) }
    val sampler = remember(view) { PixelSampler(view) }
    BackHandler { Eyedropper.target = null }
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInWindow() }
            .pointerInput(target) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    finger = down.position
                    sampler.sample(origin + down.position) { sampled = it }
                    var last = down.position
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        if (!change.pressed) break
                        last = change.position
                        finger = last
                        sampler.sample(origin + last) { sampled = it }
                    }
                    sampler.sample(origin + last, force = true) { c ->
                        finger = null
                        if (c != null) {
                            Eyedropper.target = null
                            target(c)
                        }
                    }
                }
            },
    ) {
        val at = finger
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
                    .offset { IntOffset((at.x - half).roundToInt(), (at.y - lift - half).roundToInt()) }
                    .size(loupe)
                    .border(3.dp, androidx.compose.ui.graphics.Color.White, CircleShape)
                    .padding(3.dp)
                    .border(1.dp, androidx.compose.ui.graphics.Color.Black, CircleShape)
                    .background((sampled ?: Rgba(0, 0, 0, 0)).toComposeColor(), CircleShape),
            )
        }
    }
}

/** Reads single screen pixels: from the GL canvas surface when one is under the point, else the window. */
private class PixelSampler(private val view: View) {
    private val handler = Handler(Looper.getMainLooper())
    private val pixel = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    private var busy = false

    fun sample(window: Offset, force: Boolean = false, done: (Rgba?) -> Unit) {
        if (busy && !force) return
        val x = window.x.roundToInt()
        val y = window.y.roundToInt()
        busy = true
        val finish = { ok: Boolean ->
            busy = false
            done(if (ok) pixel.getPixel(0, 0).let { Rgba((it shr 16) and 0xFF, (it shr 8) and 0xFF, it and 0xFF, 255) } else null)
        }
        try {
            val surface = glSurfaceAt(view.rootView, x, y)
            if (surface != null) {
                val loc = IntArray(2)
                surface.getLocationInWindow(loc)
                val sx = x - loc[0]
                val sy = y - loc[1]
                PixelCopy.request(surface, android.graphics.Rect(sx, sy, sx + 1, sy + 1), pixel, { finish(it == PixelCopy.SUCCESS) }, handler)
            } else {
                val w = view.context.findActivity()?.window ?: return finish(false)
                PixelCopy.request(w, android.graphics.Rect(x, y, x + 1, y + 1), pixel, { finish(it == PixelCopy.SUCCESS) }, handler)
            }
        } catch (e: IllegalArgumentException) {
            finish(false)
        }
    }

    /** The shown GL surface (the infinite canvas) covering window point ([x], [y]), if any. The
     *  window copy leaves a surface's pixels out, so those are read from the surface itself. */
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

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
