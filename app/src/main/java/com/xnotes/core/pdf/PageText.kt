package com.xnotes.core.pdf

import kotlin.math.roundToInt

/**
 * The text of one PDF page as PDFium reads it, one entry per character in PDFium's order: the
 * content stream's, right-to-left runs turned to reading order, with the spaces and line breaks
 * (CR LF) it infers between words and lines. Boxes are loose (the font's full height, not the
 * glyph's ink) and in points as displayed: top-left origin, the page's /Rotate applied.
 * It keeps the arrays it is given and never writes them, so threads may share one.
 */
class PageText(
    /** Each character's codepoint, 0 when it has no text. */
    private val codepoints: IntArray,
    /** Left, top, right and bottom of each character's box. */
    private val boxes: FloatArray,
    /** Each character's [GENERATED], [HYPHEN] and [UNMAPPED] bits. */
    private val flags: ByteArray,
    /** How far each character's glyph is turned, degrees clockwise as displayed in [0, 360); null when none is. */
    private val angles: FloatArray?,
) {
    init {
        require(boxes.size == 4 * codepoints.size && flags.size == codepoints.size) { "misaligned page text" }
        require(angles == null || angles.size == codepoints.size) { "misaligned page text" }
    }

    /** The number of characters. */
    val length: Int get() = codepoints.size

    fun codepoint(i: Int): Int = codepoints[i]

    fun left(i: Int): Float = boxes[4 * i]

    fun top(i: Int): Float = boxes[4 * i + 1]

    fun right(i: Int): Float = boxes[4 * i + 2]

    fun bottom(i: Int): Float = boxes[4 * i + 3]

    /** Whether character [i] covers an area; PDFium puts the characters it infers at a point. */
    fun hasBox(i: Int): Boolean = right(i) > left(i) && bottom(i) > top(i)

    fun angle(i: Int): Float = angles?.get(i) ?: 0f

    /** [angle] to the nearest quarter turn: 0 reads rightward, 1 down, 2 leftward, 3 up. */
    fun quarter(i: Int): Int = Math.floorMod((angle(i) / 90f).roundToInt(), 4)

    /** The extent of character [i]'s box across its line. */
    fun lineHeight(i: Int): Float = if (quarter(i) % 2 == 1) right(i) - left(i) else bottom(i) - top(i)

    /** A space or line break PDFium inferred from the layout, where the PDF draws nothing. */
    fun isGenerated(i: Int): Boolean = has(i, GENERATED)

    /** A hyphen that ends a line, its word going on in the next; PDFium drops the line break after it. */
    fun isHyphen(i: Int): Boolean = has(i, HYPHEN)

    /** A glyph its font gives no Unicode for; the codepoint is then its raw character code. */
    fun isUnmapped(i: Int): Boolean = has(i, UNMAPPED)

    fun isLineBreak(i: Int): Boolean = codepoints[i] == CR || codepoints[i] == LF

    private fun has(i: Int, bit: Int): Boolean = flags[i].toInt() and bit != 0

    /**
     * Characters [from, to) as copied: PDFium's order, its spaces, line breaks and hyphens kept,
     * nothing joined. A CR LF pair, or a CR alone, becomes one LF, and a line ending in a hyphen
     * gets back the LF PDFium dropped.
     */
    fun text(from: Int = 0, to: Int = length): String {
        val sb = StringBuilder(to - from)
        for (i in from until to) {
            when (val cp = codepoints[i]) {
                0 -> Unit
                CR -> if (i + 1 >= to || codepoints[i + 1] != LF) sb.append('\n')
                else -> sb.appendCodePoint(cp)
            }
            if (isHyphen(i) && i + 1 < to && !isLineBreak(i + 1)) sb.append('\n')
        }
        return sb.toString()
    }

    companion object {
        /** Flag bits, as pdf_jni.cpp writes them. */
        const val GENERATED = 1
        const val HYPHEN = 2
        const val UNMAPPED = 4

        private const val CR = '\r'.code
        private const val LF = '\n'.code
    }
}
