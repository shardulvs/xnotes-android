package com.xnotes.core.text

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.Renderer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The callout icons, after GitHub's: an "i" in a circle for a note, a bulb for a
 * tip, a speech bubble for important, a warning triangle and a stop octagon, the
 * last three holding a "!". Drawn from outlines and fills in content px, so they
 * stay vector in an exported PDF and scale with the text.
 */
object CalloutIcons {

    /** [kind]'s icon in the [s]-sided square at ([x], [y]), in [color]. */
    fun paint(r: Renderer, kind: CalloutKind, x: Double, y: Double, s: Double, color: Rgba) {
        val w = (s / 9.0).coerceAtLeast(1.0)
        val pen = Pen(color, w, cosmetic = false)
        val cx = x + s / 2.0
        when (kind) {
            CalloutKind.NOTE -> {
                r.strokeEllipse(Pt(cx, y + s / 2.0), s / 2.0 - w / 2.0, s / 2.0 - w / 2.0, pen)
                r.fillCircle(Pt(cx, y + s * 0.3), w * 0.75, color)
                r.fillRect(Rect(cx - w / 2.0, y + s * 0.44, w, s * 0.32), color)
            }
            CalloutKind.TIP -> {
                r.strokeEllipse(Pt(cx, y + s * 0.38), s * 0.3, s * 0.3, pen)
                r.fillRect(Rect(cx - s * 0.17, y + s * 0.74, s * 0.34, w), color)
                r.fillRect(Rect(cx - s * 0.11, y + s * 0.88, s * 0.22, w), color)
            }
            CalloutKind.IMPORTANT -> {
                val l = x + w / 2.0
                val rt = x + s - w / 2.0
                val t = y + w / 2.0
                val b = y + s * 0.72
                r.strokePolygon(
                    listOf(Pt(l, t), Pt(rt, t), Pt(rt, b), Pt(x + s * 0.48, b), Pt(x + s * 0.24, y + s - w / 2.0), Pt(x + s * 0.27, b), Pt(l, b)),
                    pen,
                )
                bang(r, cx, y + s * 0.16, s * 0.3, y + s * 0.58, w, color)
            }
            CalloutKind.WARNING -> {
                r.strokePolygon(listOf(Pt(cx, y + w / 2.0), Pt(x + s - w / 2.0, y + s - w / 2.0), Pt(x + w / 2.0, y + s - w / 2.0)), pen)
                bang(r, cx, y + s * 0.36, s * 0.3, y + s * 0.8, w, color)
            }
            CalloutKind.CAUTION -> {
                val rad = s / 2.0 - w / 2.0
                val pts = List(8) { k ->
                    val a = PI / 8.0 + k * PI / 4.0
                    Pt(cx + rad * cos(a), y + s / 2.0 + rad * sin(a))
                }
                r.strokePolygon(pts, pen)
                bang(r, cx, y + s * 0.24, s * 0.32, y + s * 0.72, w, color)
            }
        }
    }

    /** An exclamation mark: its stroke from [top] down [len], its dot centred at [dot]. */
    private fun bang(r: Renderer, cx: Double, top: Double, len: Double, dot: Double, w: Double, color: Rgba) {
        r.fillRect(Rect(cx - w / 2.0, top, w, len), color)
        r.fillCircle(Pt(cx, dot), w * 0.75, color)
    }
}
