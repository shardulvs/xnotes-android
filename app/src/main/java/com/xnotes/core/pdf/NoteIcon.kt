package com.xnotes.core.pdf

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Rgba
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The icon above a text markup that has a note: a speech bubble holding two lines, its tail pointing
 * down at where the markup starts. It is chrome, the same size on screen at any zoom, so it is laid
 * out in px from its tail's tip, at `dp` px a dp, y down.
 */
object NoteIcon {

    /** The colours of an icon: its body, its outline, and the lines inside. */
    class Colors(val fill: Rgba, val edge: Rgba, val lines: Rgba)

    /** Where the tip goes for a markup whose first line starts at [corner], its top-left on screen. */
    fun tipFor(corner: Pt, dp: Double): Pt = Pt(corner.x + TIP_DX * dp, corner.y - TIP_GAP * dp)

    /** The bubble's outline, tail included, clockwise from its top edge. */
    fun outline(tip: Pt, dp: Double): List<Pt> {
        val pts = ArrayList<Pt>()
        fun at(x: Double, y: Double) {
            pts += Pt(tip.x + x * dp, tip.y + y * dp)
        }
        // A quarter turn about (cx, cy) from angle [from]; y runs down, so the angle grows clockwise.
        fun corner(cx: Double, cy: Double, from: Double) {
            for (i in 0..ARC_STEPS) {
                val a = from + PI / 2 * i / ARC_STEPS
                at(cx + RADIUS * cos(a), cy + RADIUS * sin(a))
            }
        }
        corner(RIGHT - RADIUS, TOP + RADIUS, -PI / 2)
        corner(RIGHT - RADIUS, BOTTOM - RADIUS, 0.0)
        at(TAIL_TO, BOTTOM)
        at(0.0, 0.0)
        at(TAIL_FROM, BOTTOM)
        corner(LEFT + RADIUS, BOTTOM - RADIUS, PI / 2)
        corner(LEFT + RADIUS, TOP + RADIUS, PI)
        return pts
    }

    /** The two lines inside the bubble, each its start and end. */
    fun lines(tip: Pt, dp: Double): List<Pair<Pt, Pt>> = LINE_YS.map { y ->
        Pt(tip.x + LINE_FROM * dp, tip.y + y * dp) to Pt(tip.x + LINE_TO * dp, tip.y + y * dp)
    }

    /** What the icon covers. */
    fun bounds(tip: Pt, dp: Double): Rect =
        Rect.ltrb(tip.x + LEFT * dp, tip.y + TOP * dp, tip.x + RIGHT * dp, tip.y)

    /** Where a tap still lands on the icon: its bounds grown about their middle to [TARGET_DP] a side. */
    fun target(tip: Pt, dp: Double): Rect {
        val b = bounds(tip, dp)
        val w = max(b.w, TARGET_DP * dp)
        val h = max(b.h, TARGET_DP * dp)
        return Rect(b.centerX - w / 2, b.centerY - h / 2, w, h)
    }

    /**
     * The icon of a markup coloured [c]: the colour itself, opaque, outlined in a darker shade. The
     * lines take that shade too, or a pale tint where the colour is too dark for a shade to show.
     */
    fun colorsOf(c: Rgba): Colors {
        val fill = c.withAlpha(255)
        val edge = mix(fill, 0, EDGE_SHADE)
        // Rec. 601 luma: how light the colour reads.
        val light = (0.299 * c.r + 0.587 * c.g + 0.114 * c.b) / 255 >= LIGHT
        return Colors(fill, edge, if (light) edge else mix(fill, 255, LINE_TINT))
    }

    private fun mix(c: Rgba, to: Int, t: Double): Rgba {
        fun ch(v: Int) = (v + (to - v) * t).roundToInt()
        return Rgba(ch(c.r), ch(c.g), ch(c.b))
    }

    /** How wide the outline is drawn. */
    const val EDGE_DP = 1.2

    /** How wide the lines inside are drawn. */
    const val LINE_DP = 1.5

    /** How far a tap may land from the icon and still take it, as the side of a square. */
    const val TARGET_DP = 32.0

    // The bubble, in dp from the tail's tip.
    private const val LEFT = -3.0
    private const val RIGHT = 18.0
    private const val TOP = -18.0
    private const val BOTTOM = -5.0
    private const val RADIUS = 3.5
    private const val ARC_STEPS = 4
    private const val TAIL_FROM = 1.0
    private const val TAIL_TO = 6.5
    private const val LINE_FROM = 2.0
    private const val LINE_TO = 13.0
    private val LINE_YS = doubleArrayOf(-13.6, -9.4)

    /** The tip sits this far right of the markup's start and above its glyphs' tops. */
    private const val TIP_DX = 1.5
    private const val TIP_GAP = 1.0

    private const val EDGE_SHADE = 0.55
    private const val LINE_TINT = 0.8
    private const val LIGHT = 0.5

    /** How far above its markup's top the icon reaches. */
    const val REACH_DP = -TOP + TIP_GAP
}
