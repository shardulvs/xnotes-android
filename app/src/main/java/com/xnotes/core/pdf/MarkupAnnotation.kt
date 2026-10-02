package com.xnotes.core.pdf

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.MarkupType
import com.xnotes.core.model.TextMarkup

/**
 * A text markup as the annotation an exported PDF carries (ISO 32000-1, 12.5.6.10): its quads and
 * box in the page's user space, and an appearance stream drawing it as [MarkupPainter] does on
 * screen. The stream is in user space too, so its /BBox is [rect] and its /Matrix the identity. A
 * highlight fills all its quads as one path, so where its lines overlap it darkens once, laid on
 * through the graphics state [GS] (Multiply at its intensity). A note draws nothing: viewers show
 * the annotation's /Contents their own way.
 */
class MarkupAnnotation(
    /** Eight numbers a quad: its line's start then end along the glyphs' tops, then along their feet. */
    val quadPoints: FloatArray,
    /** The annotation's /Rect, which holds all it draws; left and top are the smaller x and y. */
    val rect: Rect,
    /** The appearance stream's operators. */
    val content: String,
) {
    companion object {
        /** The name a highlight's appearance gives its Multiply graphics state. */
        const val GS = "GS0"

        /** [m] as an annotation; [toUser] takes a point of the page as displayed, in points, to user space. Null with no quads. */
        fun of(m: TextMarkup, toUser: (Double, Double) -> Pt): MarkupAnnotation? {
            if (m.quads.isEmpty()) return null
            val user = { p: Pt -> toUser(p.x, p.y) }
            val quadPoints = FloatArray(8 * m.quads.size)
            var k = 0
            for (q in m.quads) {
                for (p in MarkupPainter.corners(q).map(user)) {
                    quadPoints[k++] = p.x.toFloat()
                    quadPoints[k++] = p.y.toFloat()
                }
            }
            val sb = StringBuilder()
            fun point(p: Pt, op: String) {
                PdfNumbers.append(sb, p.x)
                sb.append(' ')
                PdfNumbers.append(sb, p.y)
                sb.append(' ').append(op).append('\n')
            }
            fun color(op: String) {
                for (v in intArrayOf(m.color.r, m.color.g, m.color.b)) {
                    PdfNumbers.append(sb, v / 255.0)
                    sb.append(' ')
                }
                sb.append(op).append('\n')
            }
            var shown: Rect? = null
            fun shows(r: Rect) {
                shown = shown?.union(r) ?: r
            }
            if (m.type == MarkupType.HIGHLIGHT) {
                sb.append("q\n/").append(GS).append(" gs\n")
                color("rg")
                for (q in m.quads) {
                    val (ul, ur, ll, lr) = MarkupPainter.corners(q).map(user)
                    point(ul, "m")
                    point(ur, "l")
                    point(lr, "l")
                    point(ll, "l")
                    sb.append("h\n")
                    shows(quadRect(q))
                }
                sb.append("f\nQ\n")
            } else {
                color("RG")
                sb.append("1 J\n1 j\n")
                for (q in m.quads) {
                    val line = MarkupPainter.lineOf(m.type, q)
                    if (line.size < 2) continue
                    val w = MarkupPainter.thickness(q)
                    PdfNumbers.append(sb, w)
                    sb.append(" w\n")
                    point(user(line[0]), "m")
                    for (i in 1 until line.size) point(user(line[i]), "l")
                    sb.append("S\n")
                    // Round caps reach half the width past the line's ends.
                    shows(quadRect(q).outset(w / 2))
                }
            }
            val d = shown ?: return null
            val rect = Rect.bounding(listOf(Pt(d.left, d.top), Pt(d.right, d.top), Pt(d.left, d.bottom), Pt(d.right, d.bottom)).map(user))
            return MarkupAnnotation(quadPoints, rect, sb.toString())
        }

        private fun quadRect(q: TextQuad): Rect =
            Rect.ltrb(q.left.toDouble(), q.top.toDouble(), q.right.toDouble(), q.bottom.toDouble())
    }
}
