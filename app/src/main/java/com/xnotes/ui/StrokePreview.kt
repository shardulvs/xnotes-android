package com.xnotes.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.xnotes.R
import com.xnotes.canvas.InteractionController
import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.Renderer
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import com.xnotes.platform.AndroidRasterSurface
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The stroke [tool] draws with [config] as it stands: one canned pen gesture through the real
 * engine and painter, on the paper in view and at the size the nib leaves on screen now.
 */
@Composable
internal fun StrokePreview(tool: Tool, config: ToolConfig, host: ToolPopupHost, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    val paper = remember { host.hostPaperColor }
    val zoom = remember { host.hostZoom }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val shape = MaterialTheme.shapes.small
    Box(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(shape)
            .background(paper.toComposeColor())
            .border(1.dp, LocalPalette.current.border.toComposeColor(), shape)
            .onSizeChanged { size = it },
    ) {
        if (size.width > 0 && size.height > 0) {
            val image = remember(tool, config, paper, zoom, size, density) {
                StrokeSample.render(tool, config, paper, zoom, size.width, size.height, density.toDouble())
            }
            Image(image, stringResource(R.string.stroke_preview), Modifier.fillMaxSize())
        }
    }
}

/** The gesture the preview draws: a wave pressed lightly at its ends and slowed into each of its bends. */
private object StrokeSample {
    private const val POINTS = 120

    /** The widest the stroke may come out, as a share of the preview's height. */
    private const val FIT = 0.55

    /** The narrowest the stroke may come out, in px. */
    private const val FAINTEST = 1.5

    /** The gesture's speeds in dp/ms: all but still at a bend, just past the default top speed between two. */
    private const val SLOW = 0.05
    private const val FAST = 0.7

    fun render(tool: Tool, config: ToolConfig, paper: Rgba, zoom: Double, w: Int, h: Int, density: Double): ImageBitmap {
        val surface = AndroidRasterSurface.create(w, h)
        surface.fill(paper)
        val r = surface.renderer()
        // Scale off holds the width on screen whatever the zoom. Either way a wide nib is shrunk to
        // fit, and one zoomed out to nothing is kept wide enough to see.
        val natural = if (config.scale && zoom.isFinite() && zoom > 0.0) zoom else 1.0
        val width = config.baseWidth.coerceAtLeast(0.1)
        val s = min(max(natural, FAINTEST / width), FIT * h / width)
        r.scale(s, s)
        val cw = w / s
        val ch = h / s
        val dp = density / s
        val straight = tool == Tool.HIGHLIGHTER && config.straightLine
        if (tool == Tool.HIGHLIGHTER) paintWords(r, cw, ch, dp, paper)
        val stroke = Stroke(
            tool, config.copy(scale = true),
            samples(tool, config.baseWidth, cw, ch, dp, straight),
            speedScale = 1.0 / dp,
            straight = straight,
            smoothScale = InteractionController.smoothScaleFor(s),
        )
        stroke.paint(r)
        return surface.bitmap.asImageBitmap()
    }

    private fun samples(tool: Tool, width: Double, cw: Double, ch: Double, dp: Double, straight: Boolean): List<Sample> {
        val pad = (16 * dp + width / 2).coerceAtMost(cw * 0.25)
        val left = pad
        val right = cw - pad
        val cy = ch / 2
        if (straight) return listOf(Sample(left, cy, 1.0, 0.0), Sample(right, cy, 1.0, 300.0))
        val room = (ch / 2 - width / 2 - 5 * dp).coerceAtLeast(0.0)
        // A highlighter runs along a line of text; a pen gets a wave that turns it through every angle.
        val amp = if (tool == Tool.HIGHLIGHTER) min(room, 1.5 * dp) else min(room, ch * 0.27)
        // The speed pen swells where the hand slows, so it gets more bends and a steadier press.
        val turns = if (tool == Tool.SPEED) 2.2 else 1.2
        val lightest = if (tool == Tool.SPEED) 0.55 else 0.2
        val out = ArrayList<Sample>(POINTS)
        var t = 0.0
        var px = 0.0
        var py = 0.0
        for (i in 0 until POINTS) {
            val u = i / (POINTS - 1.0)
            val x = left + u * (right - left)
            val phase = 2 * PI * turns * u
            val y = cy + amp * cos(phase) * (1 - 0.2 * u)
            if (i > 0) {
                val speed = SLOW + (FAST - SLOW) * abs(sin(phase)).pow(3)
                t += hypot(x - px, y - py) / dp / speed
            }
            val pressure = lightest + (1 - lightest) * (0.5 - 0.5 * cos(2 * PI * u)).pow(0.7)
            out += Sample(x, y, pressure, t)
            px = x
            py = y
        }
        return out
    }

    /** A line of stand-in words for the highlighter to run over, so its blend shows. */
    private fun paintWords(r: Renderer, cw: Double, ch: Double, dp: Double, paper: Rgba) {
        val ink = (if (paper.luminance() > 0.5) Rgba(0, 0, 0) else Rgba(255, 255, 255)).withAlpha(190)
        val shares = doubleArrayOf(0.16, 0.26, 0.10, 0.22, 0.14)
        val pad = 22 * dp
        val gap = 7 * dp
        val usable = cw - 2 * pad - gap * (shares.size - 1)
        var x = pad
        for (share in shares) {
            val len = usable * share / shares.sum()
            r.strokePolyline(listOf(Pt(x, ch / 2), Pt(x + len, ch / 2)), Pen(ink, 2.5 * dp, cosmetic = false))
            x += len + gap
        }
    }
}
