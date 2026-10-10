package com.xnotes.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.hypot

/**
 * The app's angular lowercase "n" wordmark, the source-of-truth logo drawn straight from the
 * launcher foreground path ([R.drawable.ic_launcher_foreground]) so every in-app logo matches the
 * icon exactly. 432x432 design box; the path is scaled to fill [Modifier].
 *
 * [progress] 1f draws the whole mark; a smaller value draws that fraction of the stroke length, for
 * a draw-on reveal.
 */
@Composable
fun NLogo(
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    progress: Float = 1f,
) {
    Canvas(modifier) {
        val s = minOf(size.width, size.height)
        val ox = (size.width - s) / 2f
        val oy = (size.height - s) / 2f
        fun px(v: Float) = ox + v / 432f * s
        fun py(v: Float) = oy + v / 432f * s
        // The "n" path M162 292 L162 150 L246 150 L278 182 L278 292, in design units.
        val pts = listOf(
            px(162f) to py(292f), px(162f) to py(150f), px(246f) to py(150f),
            px(278f) to py(182f), px(278f) to py(292f),
        )
        val path = Path().apply {
            moveTo(pts[0].first, pts[0].second)
            for (i in 1 until pts.size) lineTo(pts[i].first, pts[i].second)
        }
        val total = (1 until pts.size).sumOf { i ->
            hypot((pts[i].first - pts[i - 1].first).toDouble(), (pts[i].second - pts[i - 1].second).toDouble())
        }.toFloat()
        val p = progress.coerceIn(0f, 1f)
        val effect = if (p < 1f && total > 0f) {
            val shown = total * p
            PathEffect.dashPathEffect(floatArrayOf(shown, total - shown + 1f), 0f)
        } else null
        drawPath(
            path,
            color = color,
            style = Stroke(width = 46f / 432f * s, cap = StrokeCap.Butt, join = StrokeJoin.Miter, miter = 10f, pathEffect = effect),
        )
    }
}
