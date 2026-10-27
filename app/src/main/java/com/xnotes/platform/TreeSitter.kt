package com.xnotes.platform

import android.content.Context
import com.xnotes.core.text.CodeHighlighter
import com.xnotes.core.text.HighlightSpan
import java.io.File

/** The thin JNI surface over the vendored tree-sitter (see cpp/ts_jni.c). Stateless calls only. */
object TreeSitterNative {
    val loaded: Boolean = runCatching { System.loadLibrary("xnotests") }.isSuccess

    @JvmStatic external fun nativeHighlight(lang: String, textUtf8: ByteArray, scm: ByteArray): IntArray?

    @JvmStatic external fun nativeCaptureNames(lang: String, scm: ByteArray): Array<String>?
}

/**
 * Highlights code blocks with the bundled tree-sitter grammars and their bundled
 * .scm queries (colours come from the theme layer, not from here). Query bytes
 * and capture names cache per language. [highlight] parses the whole block per
 * call (blocks are small) and is meant for background threads.
 */
class TreeSitterHighlighter(private val context: Context) : CodeHighlighter {

    private class LangData(val scm: ByteArray, val names: Array<String>)

    private val cache = HashMap<String, LangData?>()

    override fun supports(language: String): Boolean =
        TreeSitterNative.loaded && language in SUPPORTED

    override fun highlight(text: String, language: String): List<HighlightSpan>? {
        if (!supports(language)) return null
        val data = synchronized(cache) { langData(language) } ?: return null
        val utf8 = text.toByteArray(Charsets.UTF_8)
        val raw = TreeSitterNative.nativeHighlight(language, utf8, data.scm) ?: return null
        val byteToChar = byteToCharIndex(text, utf8.size)
        val out = ArrayList<HighlightSpan>(raw.size / 3)
        var k = 0
        while (k + 2 < raw.size) {
            val s = byteToChar[raw[k].coerceIn(0, utf8.size)]
            val e = byteToChar[raw[k + 1].coerceIn(0, utf8.size)]
            val name = data.names.getOrNull(raw[k + 2]) ?: ""
            if (e > s && name.isNotEmpty()) out.add(HighlightSpan(s, e, name))
            k += 3
        }
        return out
    }

    private fun langData(language: String): LangData? = cache.getOrPut(language) {
        val scm = runCatching { context.assets.open("scm/$language.scm").use { it.readBytes() } }.getOrNull()
            ?: return@getOrPut null
        val names = TreeSitterNative.nativeCaptureNames(language, scm) ?: return@getOrPut null
        LangData(scm, names)
    }

    /** utf8 byte offset -> char offset (tree-sitter speaks bytes, the flow speaks chars). */
    private fun byteToCharIndex(text: String, utf8Len: Int): IntArray {
        val map = IntArray(utf8Len + 1)
        var byte = 0
        for (ci in text.indices) {
            map[byte] = ci
            val c = text[ci]
            byte += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isSurrogate(c) -> 2 // each half of a 4-utf8-byte astral char
                else -> 3
            }
            if (byte > utf8Len) break
        }
        // Fill gaps (multi-byte interiors) with the following char's index; end maps to length.
        var last = text.length
        for (b in utf8Len downTo 0) {
            if (map[b] == 0 && b != 0) map[b] = last else last = map[b]
        }
        map[utf8Len] = text.length
        return map
    }

    companion object {
        /** Languages whose grammars ship in libxnotests. */
        val SUPPORTED = setOf(
            "asm", "bash", "c", "cpp", "css", "diff", "dockerfile", "go", "html", "java",
            "javascript", "json", "kotlin", "lua", "php", "python", "rust", "toml", "typescript",
            "xml", "yaml",
        )
    }
}
