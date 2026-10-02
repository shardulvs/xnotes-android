package com.xnotes.core.pdf

/**
 * Builds a [PageText] laid out the way PDFium reads a simple page: each [line] in fixed cells, a
 * generated CR LF between lines unless the line before ends in a hyphen, and `␣` in a line's text
 * standing for a space PDFium generated, which has no box.
 */
class FakePageText(private val cell: Float = 6f, private val height: Float = 12f) {
    private val codepoints = ArrayList<Int>()
    private val boxes = ArrayList<Float>()
    private val flags = ArrayList<Int>()
    private val angles = ArrayList<Float>()
    private var pen = floatArrayOf(0f, 0f)
    private var angle = 0
    private var layout = 0
    private var hyphenEnded = false

    /**
     * A line of [text] whose first cell's top-left corner is at ([x], [y]), the whole line turned
     * clockwise about that corner by [angle], a quarter turn. [hyphen] makes its last character a
     * line-end hyphen. A [vertical] line runs down like one turned by 90 but keeps its glyphs
     * upright, as CJK set in columns does.
     */
    fun line(
        text: String, x: Float, y: Float, angle: Int = 0, hyphen: Boolean = false, vertical: Boolean = false,
    ): FakePageText {
        if (codepoints.isNotEmpty() && !hyphenEnded) {
            add('\r'.code, null, PageText.GENERATED)
            add('\n'.code, null, PageText.GENERATED)
        }
        this.angle = angle
        layout = if (vertical) 90 else angle
        var k = 0
        for (c in text) {
            val u = k * cell
            pen = turn(x, y, u, BASELINE * height)
            if (c == '␣') {
                add(' '.code, null, PageText.GENERATED)
            } else {
                val a = turn(x, y, u, 0f)
                val b = turn(x, y, u + cell, height)
                val box = floatArrayOf(minOf(a[0], b[0]), minOf(a[1], b[1]), maxOf(a[0], b[0]), maxOf(a[1], b[1]))
                add(c.code, box, if (hyphen && k == text.length - 1) PageText.HYPHEN else 0)
                pen = turn(x, y, u + cell, BASELINE * height)
            }
            k++
        }
        hyphenEnded = hyphen
        return this
    }

    fun build(): PageText = PageText(
        codepoints.toIntArray(), boxes.toFloatArray(), ByteArray(flags.size) { flags[it].toByte() },
        angles.toFloatArray().takeIf { a -> a.any { it != 0f } },
    )

    /** Characters PDFium infers sit at a point: the pen, where the character before ends. */
    private fun add(codepoint: Int, box: FloatArray?, flag: Int) {
        codepoints += codepoint
        boxes.addAll((box ?: floatArrayOf(pen[0], pen[1], pen[0], pen[1])).toList())
        flags += flag
        angles += angle.toFloat()
    }

    /** The point (u, v) of the unturned line, from its corner ([x], [y]), turned as the line runs. */
    private fun turn(x: Float, y: Float, u: Float, v: Float): FloatArray = when (layout) {
        90 -> floatArrayOf(x - v, y + u)
        180 -> floatArrayOf(x - u, y - v)
        270 -> floatArrayOf(x + v, y - u)
        else -> floatArrayOf(x + u, y + v)
    }

    private companion object {
        /** Where the baseline sits down a cell. */
        const val BASELINE = 0.8f
    }
}
