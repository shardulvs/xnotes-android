package com.xnotes.core.pdf

/**
 * The ToUnicode CMap of a one-byte font: what each glyph code reads as when a viewer copies,
 * searches or speaks the text. A code may stand for several characters, which is how a ligature
 * or a whole shaped word keeps its real spelling.
 */
object ToUnicode {

    /** A bfchar block may hold at most this many entries (PDF 32000-1, 9.10.3). */
    private const val BLOCK = 100

    /**
     * The CMap for [entries] (code, text). A code outside 0..255 or with no usable text is left
     * out. PDF/UA forbids mapping to U+0000, U+FEFF or U+FFFE, so those characters are dropped,
     * and text left empty by that reads as a zero-width space instead.
     */
    fun cmap(entries: List<Pair<Int, String>>): String {
        val usable = entries.filter { it.first in 0..255 }.map { it.first to clean(it.second) }
        val sb = StringBuilder(128 + usable.size * 16)
        sb.append("/CIDInit /ProcSet findresource begin\n")
        sb.append("12 dict begin\n")
        sb.append("begincmap\n")
        sb.append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n")
        sb.append("/CMapName /Adobe-Identity-UCS def\n")
        sb.append("/CMapType 2 def\n")
        sb.append("1 begincodespacerange\n<00> <FF>\nendcodespacerange\n")
        for (block in usable.chunked(BLOCK)) {
            sb.append(block.size).append(" beginbfchar\n")
            for ((code, text) in block) {
                sb.append('<').append(hex2(code)).append("> <")
                for (c in text) sb.append(hex4(c.code))
                sb.append(">\n")
            }
            sb.append("endbfchar\n")
        }
        sb.append("endcmap\n")
        sb.append("CMapName currentdict /CMap defineresource pop\n")
        sb.append("end\nend\n")
        return sb.toString()
    }

    private fun clean(text: String): String {
        val kept = text.filter { it != '\u0000' && it != '﻿' && it != '￾' }
        return kept.ifEmpty { "​" }
    }

    private const val DIGITS = "0123456789ABCDEF"

    private fun hex2(v: Int): String = "${DIGITS[(v shr 4) and 15]}${DIGITS[v and 15]}"

    private fun hex4(v: Int): String =
        "${DIGITS[(v shr 12) and 15]}${DIGITS[(v shr 8) and 15]}${DIGITS[(v shr 4) and 15]}${DIGITS[v and 15]}"
}
