package com.xnotes.core.pdf

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect

/**
 * Where a note page lands on a page of an exported PDF. Drawing goes into an upright frame, in
 * points with y up, at ([ox], [oy]) plus [s] points a page px; [turn] takes that frame into the
 * page's user space. It is the identity unless the page is a source page turned by /Rotate, whose
 * frame is turned back so the drawing sits upright on the page as displayed.
 */
class PageFrame(val turn: Affine, val ox: Double, val oy: Double, val s: Double) {

    /** Whether drawing needs [turn] set as the stream's transform. */
    val turned: Boolean get() = turn != Affine.IDENTITY

    /** Page point ([x], [y]) in user space. */
    fun toUser(x: Double, y: Double): Pt = pointToUser(x * s, y * s)

    /** Point ([x], [y]) of the page as displayed, in points, in user space: where a markup's quads go. */
    fun pointToUser(x: Double, y: Double): Pt = turn.apply(Pt(ox + x, oy - y))

    /** The user-space box around page rect [r]; its left and top hold the smaller x and y. */
    fun toUser(r: Rect): Rect = Rect.bounding(
        listOf(toUser(r.left, r.top), toUser(r.right, r.top), toUser(r.left, r.bottom), toUser(r.right, r.bottom)),
    )

    companion object {
        /**
         * A source page drawn at [s] points a px: its box is [left], [bottom], [right], [top] in user
         * space before margins grow it, shown turned [rotation] degrees clockwise.
         */
        fun of(left: Double, bottom: Double, right: Double, top: Double, rotation: Int, s: Double): PageFrame =
            when (((rotation % 360) + 360) % 360) {
                90 -> PageFrame(Affine(0.0, 1.0, -1.0, 0.0, right, bottom), 0.0, right - left, s)
                180 -> PageFrame(Affine(-1.0, 0.0, 0.0, -1.0, right, top), 0.0, top - bottom, s)
                270 -> PageFrame(Affine(0.0, -1.0, 1.0, 0.0, left, top), 0.0, right - left, s)
                else -> PageFrame(Affine.IDENTITY, left, top, s)
            }
    }
}
