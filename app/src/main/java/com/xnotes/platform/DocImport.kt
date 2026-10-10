package com.xnotes.platform

import android.text.Html
import java.io.File
import java.util.zip.ZipFile

/**
 * Lightweight text extraction for imported documents. No third-party libraries: office/epub files
 * are ZIP+XML read with the platform pull parser, HTML goes through [android.text.Html], and the
 * plain formats are read as UTF-8. The output is a list of paragraphs (plain strings) to seed a
 * note's flow text; formatting beyond paragraph breaks is intentionally dropped.
 */
object DocImport {
    /** Caps so a pathological file can't build an unbounded note. */
    private const val MAX_PARAGRAPHS = 20000
    private const val MAX_CHARS = 2_000_000

    /** Extract paragraphs from [file], choosing a parser by [name]'s extension (then [mime]). */
    fun extract(file: File, name: String, mime: String): List<String> {
        val ext = name.substringAfterLast('.', "").lowercase()
        val paras = runCatching {
            when {
                ext == "txt" || mime == "text/plain" -> plain(file)
                ext == "csv" || mime == "text/csv" -> plain(file)
                ext == "rtf" || mime == "application/rtf" || mime == "text/rtf" -> fromRtf(file.readText())
                ext == "html" || ext == "htm" || mime == "text/html" -> listOf(htmlToText(file.readText()))
                ext == "docx" -> fromDocx(file)
                ext == "xlsx" -> fromXlsx(file)
                ext == "epub" -> fromEpub(file)
                else -> plain(file) // best effort: treat unknown as text
            }
        }.getOrElse { runCatching { plain(file) }.getOrDefault(emptyList()) }
        return clamp(paras.flatMap { it.split("\n") }.map { it.trimEnd() })
    }

    private fun clamp(paras: List<String>): List<String> {
        val out = ArrayList<String>()
        var chars = 0
        for (p in paras) {
            if (out.size >= MAX_PARAGRAPHS || chars >= MAX_CHARS) break
            out.add(p)
            chars += p.length + 1
        }
        // Trim leading/trailing blank paragraphs, but keep interior blanks (they space the text).
        while (out.isNotEmpty() && out.first().isBlank()) out.removeAt(0)
        while (out.isNotEmpty() && out.last().isBlank()) out.removeAt(out.size - 1)
        return out
    }

    private fun plain(file: File): List<String> = file.readText().split("\n")

    private fun htmlToText(html: String): String =
        Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().trim()

    /** Minimal RTF to text: drop groups/control words, honour \par and \tab, unescape \'xx. */
    private fun fromRtf(rtf: String): List<String> {
        val sb = StringBuilder()
        var i = 0
        while (i < rtf.length) {
            val c = rtf[i]
            when (c) {
                '\\' -> {
                    // control word or symbol
                    if (i + 1 < rtf.length && (rtf[i + 1] == '\'')) {
                        // hex escape \'xx
                        val hex = rtf.substring(i + 2, minOf(i + 4, rtf.length))
                        hex.toIntOrNull(16)?.let { sb.append(it.toChar()) }
                        i += 4
                    } else {
                        var j = i + 1
                        while (j < rtf.length && rtf[j].isLetter()) j++
                        val word = rtf.substring(i + 1, j)
                        // optional numeric parameter
                        var k = j
                        if (k < rtf.length && (rtf[k] == '-' || rtf[k].isDigit())) {
                            if (rtf[k] == '-') k++
                            while (k < rtf.length && rtf[k].isDigit()) k++
                        }
                        if (k < rtf.length && rtf[k] == ' ') k++ // a space delimiter is consumed
                        when (word) {
                            "par", "line" -> sb.append('\n')
                            "tab" -> sb.append('\t')
                        }
                        i = k
                    }
                }
                '{', '}' -> i++
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString().split("\n")
    }

    /** DOCX: word/document.xml, each <w:p> a paragraph, text from its <w:t> runs. */
    private fun fromDocx(file: File): List<String> {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("word/document.xml") ?: return emptyList()
            val xml = zip.getInputStream(entry).reader(Charsets.UTF_8).readText()
            val paras = ArrayList<String>()
            val parser = android.util.Xml.newPullParser()
            parser.setInput(java.io.StringReader(xml))
            var cur = StringBuilder()
            var inText = false
            var e = parser.eventType
            while (e != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                when (e) {
                    org.xmlpull.v1.XmlPullParser.START_TAG -> when (localName(parser.name)) {
                        "t" -> inText = true
                        "tab" -> cur.append('\t')
                        "br", "cr" -> cur.append('\n')
                    }
                    org.xmlpull.v1.XmlPullParser.TEXT -> if (inText) cur.append(parser.text)
                    org.xmlpull.v1.XmlPullParser.END_TAG -> when (localName(parser.name)) {
                        "t" -> inText = false
                        "p" -> { paras.add(cur.toString()); cur = StringBuilder() }
                    }
                }
                e = parser.next()
            }
            if (cur.isNotEmpty()) paras.add(cur.toString())
            return paras
        }
    }

    /** XLSX: shared strings + sheet1, each row a paragraph of tab-joined cells. */
    private fun fromXlsx(file: File): List<String> {
        ZipFile(file).use { zip ->
            val shared = zip.getEntry("xl/sharedStrings.xml")?.let { readSharedStrings(zip.getInputStream(it).reader(Charsets.UTF_8).readText()) }
                ?: emptyList()
            val sheet = zip.getEntry("xl/worksheets/sheet1.xml") ?: return emptyList()
            val xml = zip.getInputStream(sheet).reader(Charsets.UTF_8).readText()
            val rows = ArrayList<String>()
            val parser = android.util.Xml.newPullParser()
            parser.setInput(java.io.StringReader(xml))
            var cells = ArrayList<String>()
            var cellType = ""
            var value = StringBuilder()
            var inValue = false
            var e = parser.eventType
            while (e != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                when (e) {
                    org.xmlpull.v1.XmlPullParser.START_TAG -> when (localName(parser.name)) {
                        "row" -> cells = ArrayList()
                        "c" -> { cellType = parser.getAttributeValue(null, "t") ?: ""; value = StringBuilder() }
                        "v", "t" -> inValue = true
                    }
                    org.xmlpull.v1.XmlPullParser.TEXT -> if (inValue) value.append(parser.text)
                    org.xmlpull.v1.XmlPullParser.END_TAG -> when (localName(parser.name)) {
                        "v", "t" -> inValue = false
                        "c" -> {
                            val raw = value.toString()
                            val text = if (cellType == "s") raw.toIntOrNull()?.let { shared.getOrNull(it) } ?: "" else raw
                            cells.add(text)
                        }
                        "row" -> rows.add(cells.joinToString("\t"))
                    }
                }
                e = parser.next()
            }
            return rows
        }
    }

    private fun readSharedStrings(xml: String): List<String> {
        val out = ArrayList<String>()
        val parser = android.util.Xml.newPullParser()
        parser.setInput(java.io.StringReader(xml))
        var cur = StringBuilder()
        var inSi = false
        var inT = false
        var e = parser.eventType
        while (e != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            when (e) {
                org.xmlpull.v1.XmlPullParser.START_TAG -> when (localName(parser.name)) {
                    "si" -> { inSi = true; cur = StringBuilder() }
                    "t" -> inT = true
                }
                org.xmlpull.v1.XmlPullParser.TEXT -> if (inSi && inT) cur.append(parser.text)
                org.xmlpull.v1.XmlPullParser.END_TAG -> when (localName(parser.name)) {
                    "t" -> inT = false
                    "si" -> { out.add(cur.toString()); inSi = false }
                }
            }
            e = parser.next()
        }
        return out
    }

    /** EPUB: the (x)html documents, in filename order, each run through the HTML parser. */
    private fun fromEpub(file: File): List<String> {
        ZipFile(file).use { zip ->
            val docs = zip.entries().toList()
                .filter { !it.isDirectory && it.name.substringAfterLast('.', "").lowercase() in setOf("xhtml", "html", "htm") }
                .sortedBy { it.name }
            val paras = ArrayList<String>()
            for (entry in docs) {
                val html = zip.getInputStream(entry).reader(Charsets.UTF_8).readText()
                val text = htmlToText(html)
                if (text.isNotBlank()) { paras.addAll(text.split("\n")); paras.add("") }
            }
            return paras
        }
    }

    /** Strip a namespace prefix ("w:t" -> "t"). */
    private fun localName(name: String?): String = name?.substringAfterLast(':') ?: ""
}
