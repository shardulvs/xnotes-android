package com.xnotes.platform

import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.xmlpull.v1.XmlPullParser
import java.util.Base64

/**
 * Turns the reader's file kinds into one HTML body each, for a WebView with scripts off. Office
 * files are read entry by entry through [ZipRead]; nothing is ever written back.
 */
object ReaderHtml {
    /** Reads a ZIP entry by path, or null when it is absent. */
    fun interface ZipRead { fun read(path: String): ByteArray? }

    /** Link scheme for Obsidian [[wikilinks]]; the reader resolves the target by file name. */
    const val WIKI_SCHEME = "nnotes-wiki"

    /** The few words the converters print, supplied from string resources. */
    class Labels(
        val slide: (Int, Int) -> String = { n, total -> "Slide $n of $total" },
        val notes: String = "Notes",
        val truncatedRows: (Int) -> String = { "Showing the first $it rows." },
        val truncatedCells: (Int, Int) -> String = { r, c -> "Showing the first $r rows and $c columns of this sheet." },
        val image: String = "image",
        val empty: String = "This file is empty.",
    )

    /** The app theme's colours as CSS hex: page, body text, secondary text, links/accents, raised surfaces and rules. */
    class Colors(val background: String, val text: String, val muted: String, val accent: String, val surface: String, val border: String)

    private const val MAX_CSV_ROWS = 10_000
    private const val MAX_IMAGE_BYTES = 6 * 1024 * 1024
    private const val MAX_IMAGE_TOTAL = 40 * 1024 * 1024

    // --- page shell ---

    /** Wraps [body] in a full page styled with [c]; [wide] drops the reading-width cap (tables, slides). */
    fun page(body: String, c: Colors, wide: Boolean = false): String = shell(body, c, wide, "")

    /** [page] laid out for print: backgrounds kept, nothing clipped by scroll boxes, blocks not split across pages. */
    fun printPage(body: String, c: Colors, wide: Boolean = false): String = shell(body, c, wide, """
        @page{margin:14mm 13mm}
        *{-webkit-print-color-adjust:exact;print-color-adjust:exact}
        body{max-width:none;padding:0}
        pre{white-space:pre-wrap;overflow:visible}
        table{display:table;overflow:visible}
        table.csv th{position:static}
        thead{display:table-header-group}
        tr,img,pre,blockquote,.slide,.callout,.props{break-inside:avoid}
        h1,h2,h3,h4,h5,h6{break-after:avoid}
    """.trimIndent())

    private fun shell(body: String, c: Colors, wide: Boolean, extraCss: String): String {
        val css = """
            html{-webkit-text-size-adjust:100%}
            body{background:${c.background};color:${c.text};font-family:sans-serif;font-size:17px;line-height:1.6;margin:0 auto;padding:18px 18px 72px;${if (wide) "" else "max-width:820px;"}overflow-wrap:break-word;word-wrap:break-word}
            a{color:${c.accent}}
            h1,h2,h3,h4,h5,h6{line-height:1.25;margin:1.2em 0 .5em}
            h1{font-size:1.8em}h2{font-size:1.45em}h3{font-size:1.2em}
            p{margin:.6em 0}
            p.gap{margin:0;height:.6em}
            p.subtitle{font-size:1.15em;color:${c.muted}}
            img{max-width:100%;height:auto}
            hr{border:0;border-top:1px solid ${c.border}}
            code{background:${c.surface};border-radius:4px;padding:.1em .3em;font-size:.9em}
            pre{background:${c.surface};border-radius:6px;padding:10px 12px;overflow-x:auto;line-height:1.4}
            pre code{background:none;padding:0}
            blockquote{margin:.8em 0;padding:.2em 0 .2em 14px;border-left:3px solid ${c.border};color:${c.muted}}
            .callout{border-left-color:${c.accent};background:${c.surface};border-radius:0 6px 6px 0;padding:8px 12px;color:inherit}
            .callout-title{font-weight:600;margin:.2em 0;color:${c.accent}}
            mark{background:rgba(255,208,0,.4);color:inherit;border-radius:2px}
            table{border-collapse:collapse;display:block;overflow-x:auto;margin:.8em 0;max-width:100%}
            th,td{border:1px solid ${c.border};padding:5px 9px;vertical-align:top}
            th:not([align]){text-align:left}
            th{background:${c.surface}}
            td.num{text-align:right;font-variant-numeric:tabular-nums}
            table.csv{font-size:14px;line-height:1.35}
            table.csv th{position:sticky;top:0}
            li:has(> input[type=checkbox]){list-style:none}
            li > input[type=checkbox]{margin:0 .4em 0 -1.3em;accent-color:${c.accent}}
            .props{border:1px solid ${c.border};border-radius:6px;padding:6px 10px;margin:0 0 1em;font-size:.88em;color:${c.muted}}
            .props b{color:${c.text};font-weight:600}
            .missing{color:${c.muted};font-style:italic}
            .slide{border:1px solid ${c.border};border-radius:10px;padding:10px 18px 14px;margin:0 0 18px}
            .slide-no{font-size:12px;color:${c.muted};margin-bottom:4px}
            .slide h2:first-of-type{margin-top:.3em}
            .notes{border-top:1px dashed ${c.border};margin-top:12px;padding-top:6px;font-size:.88em;color:${c.muted}}
            .note{font-size:13px;color:${c.muted};margin:.6em 0}
        """.trimIndent()
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<style>$css\n$extraCss</style></head><body>$body</body></html>"
    }

    // --- Markdown ---

    private val mdExtensions = listOf(
        TablesExtension.create(), StrikethroughExtension.create(), TaskListItemsExtension.create(), AutolinkExtension.create(),
    )
    private val mdParser: Parser by lazy { Parser.builder().extensions(mdExtensions).build() }
    private val mdRenderer: HtmlRenderer by lazy { HtmlRenderer.builder().extensions(mdExtensions).build() }

    fun markdown(source: String, labels: Labels = Labels()): String {
        val text = source.removePrefix("\uFEFF").replace("\r\n", "\n")
        if (text.isBlank()) return "<p class=\"missing\">${esc(labels.empty)}</p>"
        val (front, body) = splitFrontmatter(text)
        var html = mdRenderer.render(mdParser.parse(obsidianSyntax(body)))
        html = callouts(html)
        html = stripExternalImages(html, labels)
        return frontmatterHtml(front) + html
    }

    /** Splits a leading YAML block (between --- lines) from the Markdown body. */
    internal fun splitFrontmatter(text: String): Pair<String?, String> {
        if (!text.startsWith("---\n")) return null to text
        val end = Regex("^(---|\\.\\.\\.)\\s*$", RegexOption.MULTILINE).find(text, 4) ?: return null to text
        return text.substring(4, end.range.first).trimEnd('\n') to text.substring(end.range.last + 1).trimStart('\n')
    }

    private fun frontmatterHtml(front: String?): String {
        if (front.isNullOrBlank()) return ""
        val rows = ArrayList<Pair<String, MutableList<String>>>()
        for (line in front.lines()) {
            val item = Regex("^\\s+-\\s*(.*)$").find(line)
            val pair = Regex("^([^\\s:#][^:]*):\\s*(.*)$").find(line)
            when {
                item != null && rows.isNotEmpty() -> rows.last().second.add(item.groupValues[1].trim().trim('"', '\''))
                pair != null -> rows.add(pair.groupValues[1].trim() to mutableListOf<String>().apply {
                    pair.groupValues[2].trim().trim('"', '\'').takeIf { it.isNotEmpty() }?.let { add(it) }
                })
            }
        }
        if (rows.isEmpty()) return ""
        return rows.joinToString("", "<div class=\"props\">", "</div>") { (k, v) ->
            "<div><b>${esc(k)}</b>: ${esc(v.joinToString(", "))}</div>"
        }
    }

    private val wikiEmbed = Regex("!\\[\\[([^\\]\\n]+)]]")
    private val wikiLink = Regex("\\[\\[([^\\]\\n]+)]]")
    private val highlight = Regex("==([^=\\n](?:[^\\n]*?[^=\\n])?)==")

    /** Rewrites Obsidian-only syntax ([[links]], ![[embeds]], ==highlights==) into CommonMark, leaving code alone. */
    internal fun obsidianSyntax(body: String): String {
        val out = StringBuilder(body.length + 64)
        var fence: String? = null
        for (line in body.split('\n')) {
            val trimmed = line.trimStart()
            val marker = Regex("^(`{3,}|~{3,})").find(trimmed)?.value
            when {
                fence != null -> { if (marker != null && marker[0] == fence[0] && marker.length >= fence.length) fence = null; out.append(line) }
                marker != null -> { fence = marker; out.append(line) }
                line.startsWith("    ") || line.startsWith("\t") -> out.append(line)
                else -> out.append(rewriteInline(line))
            }
            out.append('\n')
        }
        return out.toString().removeSuffix("\n")
    }

    /** Applies the wikilink/highlight rewrite outside `code spans` only. */
    private fun rewriteInline(line: String): String {
        if (!line.contains("[[") && !line.contains("==")) return line
        val parts = line.split('`')
        // An odd part is code only when a closing backtick follows it.
        return parts.mapIndexed { i, part ->
            if (i % 2 == 1 && i < parts.lastIndex) part else {
                var p = wikiEmbed.replace(part) { wikiMarkdown(it.groupValues[1]) }
                p = wikiLink.replace(p) { wikiMarkdown(it.groupValues[1]) }
                highlight.replace(p) { "<mark>${it.groupValues[1]}</mark>" }
            }
        }.joinToString("`")
    }

    private fun wikiMarkdown(inner: String): String {
        val target = inner.substringBefore('|').trim()
        val alias = inner.substringAfter('|', "").trim()
        val file = target.substringBefore('#').trim()
        val shown = alias.ifEmpty { if (file.isEmpty()) target.removePrefix("#") else target.replace("#", " > ") }
        val label = shown.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]")
        if (file.isEmpty()) return label
        return "[$label](<$WIKI_SCHEME:${percent(file)}>)"
    }

    private fun percent(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private val calloutStart = Regex("<blockquote>\\s*<p>\\[!([A-Za-z0-9_-]+)][+-]?[ \\t]*([^\\n<]*)(\\n|</p>)")

    /** Obsidian callouts (> [!note] Title) as styled boxes instead of a literal "[!note]". */
    internal fun callouts(html: String): String = calloutStart.replace(html) { m ->
        val type = m.groupValues[1].lowercase()
        val title = m.groupValues[2].trim().ifEmpty { type.replaceFirstChar { it.uppercase() } }
        val rest = if (m.groupValues[3] == "</p>") "" else "<p>"
        "<blockquote class=\"callout callout-$type\"><p class=\"callout-title\">$title</p>$rest"
    }

    private val imgTag = Regex("<img\\s[^>]*>")
    private val attr = { name: String -> Regex("\\s$name=\"([^\"]*)\"") }

    /** Images the reader cannot load (not synced, or remote with the network off) become their alt text. */
    private fun stripExternalImages(html: String, labels: Labels): String = imgTag.replace(html) { m ->
        val src = attr("src").find(m.value)?.groupValues?.get(1).orEmpty()
        if (src.startsWith("data:")) m.value else {
            val alt = attr("alt").find(m.value)?.groupValues?.get(1).orEmpty()
            "<span class=\"missing\">[${alt.ifEmpty { labels.image }}]</span>"
        }
    }

    // --- CSV ---

    fun csv(source: String, labels: Labels = Labels()): String {
        val text = source.removePrefix("\uFEFF")
        if (text.isBlank()) return "<p class=\"missing\">${esc(labels.empty)}</p>"
        val rows = parseCsv(text, detectDelimiter(text), MAX_CSV_ROWS + 1)
        val truncated = rows.size > MAX_CSV_ROWS
        val shown = if (truncated) rows.subList(0, MAX_CSV_ROWS) else rows
        val width = shown.maxOf { it.size }
        val sb = StringBuilder("<table class=\"csv\"><thead><tr>")
        for (c in 0 until width) sb.append("<th>").append(esc(shown[0].getOrElse(c) { "" })).append("</th>")
        sb.append("</tr></thead><tbody>")
        for (r in 1 until shown.size) {
            sb.append("<tr>")
            for (c in 0 until width) {
                val v = shown[r].getOrElse(c) { "" }
                sb.append(if (numeric.matches(v.trim())) "<td class=\"num\">" else "<td>").append(esc(v)).append("</td>")
            }
            sb.append("</tr>")
        }
        sb.append("</tbody></table>")
        if (truncated) sb.append("<p class=\"note\">").append(esc(labels.truncatedRows(MAX_CSV_ROWS))).append("</p>")
        return sb.toString()
    }

    private val numeric = Regex("^[-+]?[\\d\\s.,]*\\d[%]?$")

    /** The delimiter that splits the first line most often, outside quotes. */
    internal fun detectDelimiter(text: String): Char {
        val first = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        return listOf(',', ';', '\t', '|').maxBy { d ->
            var n = 0
            var quoted = false
            for (ch in first) { if (ch == '"') quoted = !quoted else if (ch == d && !quoted) n++ }
            n
        }
    }

    /** RFC 4180 rows (quoted fields, doubled quotes, newlines in quotes), at most [limit] of them. */
    internal fun parseCsv(text: String, delimiter: Char, limit: Int = Int.MAX_VALUE): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        fun endRow() {
            row.add(field.toString()); field.setLength(0)
            if (!(row.size == 1 && row[0].isEmpty())) rows.add(row)
            row = ArrayList()
        }
        while (i < text.length && rows.size < limit) {
            val ch = text[i]
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ } else quoted = false
                } else field.append(ch)
            } else when (ch) {
                '"' -> if (field.isEmpty()) quoted = true else field.append(ch)
                delimiter -> { row.add(field.toString()); field.setLength(0) }
                '\r' -> Unit
                '\n' -> endRow()
                else -> field.append(ch)
            }
            i++
        }
        if (rows.size < limit && (field.isNotEmpty() || row.isNotEmpty())) endRow()
        return rows
    }

    // --- shared XML helpers ---

    private fun local(name: String?): String = name?.substringAfterLast(':') ?: ""

    private fun XmlPullParser.attr(localName: String): String? {
        for (i in 0 until attributeCount) if (local(getAttributeName(i)) == localName) return getAttributeValue(i)
        return null
    }

    /** The relationship id (r:id), which can sit beside an unprefixed id attribute. */
    private fun XmlPullParser.relId(): String? {
        for (i in 0 until attributeCount) getAttributeName(i).let { if (it.contains(':') && local(it) == "id") return getAttributeValue(i) }
        return null
    }

    /** Streams [bytes] through a fresh parser, calling [onStart]/[onText]/[onEnd] with local tag names. */
    private inline fun walk(
        newParser: () -> XmlPullParser, bytes: ByteArray,
        onStart: (XmlPullParser, String) -> Unit, onText: (String) -> Unit = {}, onEnd: (String) -> Unit = {},
    ) {
        val p = newParser()
        p.setInput(bytes.inputStream(), "UTF-8")
        var e = p.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            when (e) {
                XmlPullParser.START_TAG -> onStart(p, local(p.name))
                XmlPullParser.TEXT -> onText(p.text)
                XmlPullParser.END_TAG -> onEnd(local(p.name))
            }
            e = p.next()
        }
    }

    private class Rel(val target: String, val type: String, val external: Boolean)

    /** A part's relationships (id -> resolved target), from its _rels file. [baseDir] is the part's folder. */
    private fun rels(newParser: () -> XmlPullParser, bytes: ByteArray?, baseDir: String): Map<String, Rel> {
        if (bytes == null) return emptyMap()
        val out = HashMap<String, Rel>()
        walk(newParser, bytes, onStart = { p, name ->
            if (name == "Relationship") {
                val id = p.attr("Id") ?: return@walk
                val target = p.attr("Target") ?: return@walk
                val external = p.attr("TargetMode") == "External"
                out[id] = Rel(if (external) target else resolvePath(baseDir, target), p.attr("Type").orEmpty(), external)
            }
        })
        return out
    }

    internal fun resolvePath(baseDir: String, target: String): String {
        if (target.startsWith("/")) return target.removePrefix("/")
        val parts = ArrayList(baseDir.split('/').filter { it.isNotEmpty() })
        for (seg in target.split('/')) when (seg) {
            "", "." -> Unit
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
            else -> parts.add(seg)
        }
        return parts.joinToString("/")
    }

    /** Embeds images as data URIs within an overall byte budget. */
    private class Images(private val zip: ZipRead, private val labels: Labels) {
        private var spent = 0L
        fun tag(path: String): String {
            val mime = when (path.substringAfterLast('.').lowercase()) {
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "bmp" -> "image/bmp"
                "webp" -> "image/webp"
                "svg" -> "image/svg+xml"
                else -> null
            }
            val bytes = if (mime == null) null else zip.read(path)
            if (bytes == null || bytes.size > MAX_IMAGE_BYTES || spent + bytes.size > MAX_IMAGE_TOTAL) {
                return "<span class=\"missing\">[${esc(labels.image)}]</span>"
            }
            spent += bytes.size
            return "<img src=\"data:$mime;base64,${Base64.getEncoder().encodeToString(bytes)}\" alt=\"\">"
        }
    }

    private fun isOff(v: String?) = v == "0" || v == "false" || v == "off" || v == "none"

    private fun styled(text: String, bold: Boolean, italic: Boolean, underline: Boolean, strike: Boolean, vert: String?): String {
        if (text.isEmpty()) return ""
        var s = esc(text)
        if (bold) s = "<b>$s</b>"
        if (italic) s = "<i>$s</i>"
        if (underline) s = "<u>$s</u>"
        if (strike) s = "<s>$s</s>"
        if (vert == "superscript") s = "<sup>$s</sup>" else if (vert == "subscript") s = "<sub>$s</sub>"
        return s
    }

    // --- DOCX ---

    fun docx(zip: ZipRead, newParser: () -> XmlPullParser, labels: Labels = Labels()): String {
        val document = zip.read("word/document.xml") ?: throw IllegalArgumentException("not a Word document")
        val rels = rels(newParser, zip.read("word/_rels/document.xml.rels"), "word")
        val headings = docxHeadings(newParser, zip.read("word/styles.xml"))
        val ordered = docxNumbering(newParser, zip.read("word/numbering.xml"))
        val images = Images(zip, labels)

        val out = StringBuilder()
        val lists = ArrayList<Boolean>() // open list levels, true = ordered
        fun closeLists() { while (lists.isNotEmpty()) out.append(if (lists.removeAt(lists.lastIndex)) "</ol>" else "</ul>") }

        class Para { val html = StringBuilder(); var style: String? = null; var numId: String? = null; var ilvl = 0; var align: String? = null }
        // A stack, because a text box inside a paragraph holds paragraphs of its own.
        val paras = ArrayList<Para>()
        var inPPr = false
        var inRPr = false
        var inText = false
        var skipDepth = 0 // inside mc:Fallback, which repeats mc:Choice
        var bold = false; var italic = false; var underline = false; var strike = false; var vert: String? = null
        val linkOpen = ArrayList<Boolean>()

        walk(newParser, document, onStart = { p, name ->
            if (skipDepth > 0) { skipDepth++; return@walk }
            when (name) {
                "Fallback" -> skipDepth = 1
                "tbl" -> { closeLists(); out.append("<table>") }
                "tr" -> out.append("<tr>")
                "tc" -> out.append("<td>")
                "p" -> paras.add(Para())
                "pPr" -> inPPr = true
                "pStyle" -> if (inPPr) paras.lastOrNull()?.style = p.attr("val")
                "numId" -> if (inPPr) paras.lastOrNull()?.numId = p.attr("val")
                "ilvl" -> if (inPPr) paras.lastOrNull()?.ilvl = p.attr("val")?.toIntOrNull() ?: 0
                "jc" -> if (inPPr) paras.lastOrNull()?.align = p.attr("val")
                "r" -> { bold = false; italic = false; underline = false; strike = false; vert = null }
                "rPr" -> inRPr = !inPPr
                "b" -> if (inRPr) bold = !isOff(p.attr("val"))
                "i" -> if (inRPr) italic = !isOff(p.attr("val"))
                "u" -> if (inRPr) underline = !isOff(p.attr("val"))
                "strike", "dstrike" -> if (inRPr) strike = !isOff(p.attr("val"))
                "vertAlign" -> if (inRPr) vert = p.attr("val")
                "t" -> inText = true
                "tab" -> if (!inPPr) paras.lastOrNull()?.html?.append("&emsp;")
                "br", "cr" -> paras.lastOrNull()?.html?.append("<br>")
                "hyperlink" -> {
                    val href = p.relId()?.let { rels[it] }?.takeIf { it.external }?.target
                        ?: p.attr("anchor")?.let { "#$it" }
                    if (href != null) paras.lastOrNull()?.html?.append("<a href=\"${esc(href)}\">")
                    linkOpen.add(href != null)
                }
                "blip" -> p.attr("embed")?.let { rels[it] }?.let { paras.lastOrNull()?.html?.append(images.tag(it.target)) }
                "imagedata" -> p.relId()?.let { rels[it] }?.let { paras.lastOrNull()?.html?.append(images.tag(it.target)) }
            }
        }, onText = { if (inText && skipDepth == 0) paras.lastOrNull()?.html?.append(styled(it, bold, italic, underline, strike, vert)) }, onEnd = { name ->
            if (skipDepth > 0) { skipDepth--; return@walk }
            when (name) {
                "t" -> inText = false
                "pPr" -> inPPr = false
                "rPr" -> inRPr = false
                "hyperlink" -> if (linkOpen.removeLastOrNull() == true) paras.lastOrNull()?.html?.append("</a>")
                "tc" -> { closeLists(); out.append("</td>") }
                "tr" -> out.append("</tr>")
                "tbl" -> out.append("</table>")
                "p" -> {
                    val para = paras.removeLastOrNull() ?: return@walk
                    val content = para.html.toString()
                    val align = para.align
                    val ilvl = para.ilvl
                    val level = para.style?.let { headings[it] ?: headingFromId(it) }
                    val listId = para.numId?.takeIf { it != "0" }
                    when {
                        level != null && level > 0 -> { closeLists(); out.append("<h$level>$content</h$level>") }
                        level == 0 -> { closeLists(); out.append("<p class=\"subtitle\">$content</p>") }
                        listId != null -> {
                            val isOrdered = ordered[listId]?.get(ilvl) ?: false
                            val depth = ilvl.coerceIn(0, 8) + 1
                            while (lists.size > depth) out.append(if (lists.removeAt(lists.lastIndex)) "</ol>" else "</ul>")
                            if (lists.size == depth && lists.last() != isOrdered) out.append(if (lists.removeAt(lists.lastIndex)) "</ol>" else "</ul>")
                            while (lists.size < depth) { lists.add(isOrdered); out.append(if (isOrdered) "<ol>" else "<ul>") }
                            out.append("<li>$content</li>")
                        }
                        content.isBlank() -> { closeLists(); out.append("<p class=\"gap\"></p>") }
                        else -> {
                            closeLists()
                            val css = when (align) { "center" -> "center"; "right", "end" -> "right"; "both", "distribute" -> "justify"; else -> null }
                            out.append(if (css != null) "<p style=\"text-align:$css\">" else "<p>").append(content).append("</p>")
                        }
                    }
                }
            }
        })
        closeLists()
        return out.toString().ifBlank { "<p class=\"missing\">${esc(labels.empty)}</p>" }
    }

    private fun headingFromId(id: String): Int? {
        val lower = id.lowercase()
        if (lower == "title") return 1
        if (lower == "subtitle") return 0
        return Regex("^heading\\s*(\\d)$").find(lower)?.groupValues?.get(1)?.toInt()?.coerceIn(1, 6)
    }

    /** Style id -> heading level (0 = subtitle), from each style's name or outline level. */
    private fun docxHeadings(newParser: () -> XmlPullParser, bytes: ByteArray?): Map<String, Int> {
        if (bytes == null) return emptyMap()
        val out = HashMap<String, Int>()
        var id: String? = null
        walk(newParser, bytes, onStart = { p, name ->
            when (name) {
                "style" -> id = p.attr("styleId")
                "name" -> id?.let { sid -> headingFromId(p.attr("val").orEmpty())?.let { out[sid] = it } }
                "outlineLvl" -> id?.let { sid -> p.attr("val")?.toIntOrNull()?.takeIf { it in 0..5 }?.let { if (sid !in out) out[sid] = it + 1 } }
            }
        }, onEnd = { if (it == "style") id = null })
        return out
    }

    /** numId -> (level -> ordered), resolving each num through its abstract definition. */
    private fun docxNumbering(newParser: () -> XmlPullParser, bytes: ByteArray?): Map<String, Map<Int, Boolean>> {
        if (bytes == null) return emptyMap()
        val abstract = HashMap<String, HashMap<Int, Boolean>>()
        val numToAbstract = HashMap<String, String>()
        var absId: String? = null
        var lvl = -1
        var numId: String? = null
        walk(newParser, bytes, onStart = { p, name ->
            when (name) {
                "abstractNum" -> absId = p.attr("abstractNumId")
                "lvl" -> lvl = p.attr("ilvl")?.toIntOrNull() ?: -1
                "numFmt" -> {
                    val a = absId
                    if (a != null && lvl >= 0) abstract.getOrPut(a) { HashMap() }[lvl] = p.attr("val").let { it != "bullet" && it != "none" }
                }
                "num" -> numId = p.attr("numId")
                "abstractNumId" -> numId?.let { n -> p.attr("val")?.let { numToAbstract[n] = it } }
            }
        }, onEnd = { name ->
            when (name) {
                "abstractNum" -> absId = null
                "lvl" -> lvl = -1
                "num" -> numId = null
            }
        })
        return numToAbstract.mapValues { (_, a) -> abstract[a].orEmpty() }
    }

    // --- PPTX ---

    private class SlideBlock(val title: Boolean) {
        var x = Long.MAX_VALUE
        var y = Long.MAX_VALUE
        val html = StringBuilder()
    }

    fun pptx(zip: ZipRead, newParser: () -> XmlPullParser, labels: Labels = Labels()): String {
        val presentation = zip.read("ppt/presentation.xml") ?: throw IllegalArgumentException("not a PowerPoint file")
        val presRels = rels(newParser, zip.read("ppt/_rels/presentation.xml.rels"), "ppt")
        val order = ArrayList<String>()
        walk(newParser, presentation, onStart = { p, name ->
            if (name == "sldId") p.relId()?.let { presRels[it] }?.let { order.add(it.target) }
        })
        if (order.isEmpty()) return "<p class=\"missing\">${esc(labels.empty)}</p>"
        val images = Images(zip, labels)
        val out = StringBuilder()
        order.forEachIndexed { i, path ->
            val bytes = zip.read(path) ?: return@forEachIndexed
            val dir = path.substringBeforeLast('/', "")
            val slideRels = rels(newParser, zip.read("$dir/_rels/${path.substringAfterLast('/')}.rels"), dir)
            out.append("<section class=\"slide\"><div class=\"slide-no\">").append(esc(labels.slide(i + 1, order.size))).append("</div>")
            out.append(slideBody(bytes, slideRels, images, newParser, notes = false))
            slideRels.values.firstOrNull { it.type.endsWith("/notesSlide") }?.let { zip.read(it.target) }?.let { notes ->
                val text = slideBody(notes, emptyMap(), images, newParser, notes = true)
                if (text.isNotBlank()) out.append("<div class=\"notes\"><b>").append(esc(labels.notes)).append("</b>").append(text).append("</div>")
            }
            out.append("</section>")
        }
        return out.toString()
    }

    /** One slide's shapes as HTML, titles first then top-to-bottom; with [notes], only the speaker-notes body text. */
    private fun slideBody(bytes: ByteArray, rels: Map<String, Rel>, images: Images, newParser: () -> XmlPullParser, notes: Boolean): String {
        val blocks = ArrayList<SlideBlock>()
        var block: SlideBlock? = null
        var depth = 0 // nesting of the current top shape element
        var phType: String? = null
        var isPlaceholder = false
        var skip = false
        var para: StringBuilder? = null
        var paraBullet: Boolean? = null
        var paraLevel = 0
        val paras = ArrayList<Triple<String, Boolean?, Int>>()
        var inText = false
        var bold = false; var italic = false; var underline = false; var strike = false
        var table: StringBuilder? = null
        var cell: StringBuilder? = null

        fun flushText(b: SlideBlock) {
            if (paras.all { it.first.isBlank() }) { paras.clear(); return }
            val title = phType == "title" || phType == "ctrTitle"
            val bodyDefault = isPlaceholder && phType !in setOf("title", "ctrTitle", "subTitle")
            if (title) {
                b.html.append("<h2>").append(paras.filter { it.first.isNotBlank() }.joinToString("<br>") { it.first }).append("</h2>")
            } else {
                var open = false
                for ((text, bullet, level) in paras) {
                    val asBullet = bullet ?: bodyDefault
                    if (asBullet && text.isNotBlank()) {
                        if (!open) { b.html.append("<ul>"); open = true }
                        b.html.append("<li style=\"margin-left:${level * 1.2}em\">").append(text).append("</li>")
                    } else {
                        if (open) { b.html.append("</ul>"); open = false }
                        b.html.append(if (text.isBlank()) "<p class=\"gap\"></p>" else "<p>$text</p>")
                    }
                }
                if (open) b.html.append("</ul>")
            }
            paras.clear()
        }

        walk(newParser, bytes, onStart = { p, name ->
            if (block != null) depth++
            when (name) {
                "sp", "pic", "graphicFrame" -> if (block == null) {
                    block = SlideBlock(false); depth = 1; phType = null; isPlaceholder = false; skip = false
                }
                "ph" -> if (block != null) {
                    isPlaceholder = true
                    phType = p.attr("type")
                    if (phType in setOf("dt", "ftr", "sldNum", "hdr", "sldImg")) skip = true
                    if (notes && phType != "body") skip = true
                }
                "off" -> block?.let { b -> if (b.y == Long.MAX_VALUE) { b.x = p.attr("x")?.toLongOrNull() ?: 0; b.y = p.attr("y")?.toLongOrNull() ?: 0 } }
                "p" -> if (block != null) { para = StringBuilder(); paraBullet = null; paraLevel = 0 }
                "pPr" -> if (para != null) paraLevel = p.attr("lvl")?.toIntOrNull() ?: 0
                "buNone" -> if (para != null) paraBullet = false
                "buChar", "buAutoNum" -> if (para != null) paraBullet = true
                "r" -> { bold = false; italic = false; underline = false; strike = false }
                "rPr" -> {
                    bold = p.attr("b") == "1" || p.attr("b") == "true"
                    italic = p.attr("i") == "1" || p.attr("i") == "true"
                    underline = p.attr("u").let { it != null && it != "none" }
                    strike = p.attr("strike").let { it != null && it != "noStrike" }
                }
                "t" -> inText = true
                "br" -> para?.append("<br>")
                "tbl" -> if (block != null) table = StringBuilder("<table>")
                "tr" -> table?.append("<tr>")
                "tc" -> if (table != null) cell = StringBuilder()
                "blip" -> if (!notes) block?.let { b -> p.attr("embed")?.let { rels[it] }?.let { b.html.append(images.tag(it.target)) } }
            }
        }, onText = { if (inText) para?.append(styled(it, bold, italic, underline, strike, null)) }, onEnd = { name ->
            when (name) {
                "t" -> inText = false
                "p" -> para?.let { text ->
                    val c = cell
                    if (c != null) { if (c.isNotEmpty()) c.append("<br>"); c.append(text) } else paras.add(Triple(text.toString(), paraBullet, paraLevel))
                    para = null
                }
                "tc" -> cell?.let { c -> table?.append("<td>")?.append(c)?.append("</td>"); cell = null }
                "tr" -> table?.append("</tr>")
                "tbl" -> table?.let { t -> block?.html?.append(t)?.append("</table>"); table = null }
            }
            val b = block
            if (b != null) {
                depth--
                if (depth == 0) {
                    flushText(b)
                    val title = phType == "title" || phType == "ctrTitle"
                    if (!skip && b.html.isNotEmpty()) blocks.add(SlideBlock(title).also { it.x = b.x; it.y = b.y; it.html.append(b.html) })
                    block = null
                }
            }
        })
        return blocks.sortedWith(compareBy<SlideBlock>({ !it.title }, { it.y }, { it.x })).joinToString("") { it.html }
    }

    // --- XLSX ---

    private const val MAX_SHEET_ROWS = 2000
    private const val MAX_SHEET_COLS = 100

    fun xlsx(zip: ZipRead, newParser: () -> XmlPullParser, labels: Labels = Labels()): String {
        val workbook = zip.read("xl/workbook.xml") ?: throw IllegalArgumentException("not an Excel workbook")
        val rels = rels(newParser, zip.read("xl/_rels/workbook.xml.rels"), "xl")
        val sheets = ArrayList<Pair<String, String>>()
        walk(newParser, workbook, onStart = { p, name ->
            if (name == "sheet") p.relId()?.let { rels[it] }?.let { sheets.add(p.attr("name").orEmpty() to it.target) }
        })
        if (sheets.isEmpty()) return "<p class=\"missing\">${esc(labels.empty)}</p>"
        val shared = sharedStrings(newParser, zip.read("xl/sharedStrings.xml"))
        val dateStyles = dateStyles(newParser, zip.read("xl/styles.xml"))
        val out = StringBuilder()
        for ((name, path) in sheets) {
            val bytes = zip.read(path) ?: continue
            if (sheets.size > 1) out.append("<h2>").append(esc(name)).append("</h2>")
            out.append(sheetTable(bytes, shared, dateStyles, newParser, labels))
        }
        return out.toString()
    }

    /** One worksheet as a table headed by column letters, each row led by its number. */
    private fun sheetTable(bytes: ByteArray, shared: List<String>, dateStyles: Set<Int>, newParser: () -> XmlPullParser, labels: Labels): String {
        val rows = java.util.TreeMap<Int, HashMap<Int, String>>()
        var maxCol = -1
        var truncated = false
        var rowNo = 0
        var col = -1
        var type: String? = null
        var style = 0
        val value = StringBuilder()
        var inValue = false
        var inInline = false
        walk(newParser, bytes, onStart = { p, name ->
            when (name) {
                "row" -> { rowNo = p.attr("r")?.toIntOrNull() ?: (rowNo + 1); col = -1 }
                "c" -> {
                    col = p.attr("r")?.let { columnIndex(it) } ?: (col + 1)
                    type = p.attr("t")
                    style = p.attr("s")?.toIntOrNull() ?: 0
                    value.setLength(0)
                }
                "v" -> inValue = true
                "is" -> inInline = true
                "t" -> if (inInline) inValue = true
            }
        }, onText = { if (inValue) value.append(it) }, onEnd = { name ->
            when (name) {
                "v", "t" -> inValue = false
                "is" -> inInline = false
                "c" -> {
                    val shown = cellText(value.toString(), type, style, shared, dateStyles)
                    if (shown.isNotEmpty()) {
                        if (rowNo > MAX_SHEET_ROWS || col >= MAX_SHEET_COLS) truncated = true
                        else { rows.getOrPut(rowNo) { HashMap() }[col] = shown; maxCol = maxOf(maxCol, col) }
                    }
                }
            }
        })
        if (rows.isEmpty()) return "<p class=\"missing\">${esc(labels.empty)}</p>"
        val sb = StringBuilder("<table class=\"csv\"><thead><tr><th></th>")
        for (c in 0..maxCol) sb.append("<th>").append(columnName(c)).append("</th>")
        sb.append("</tr></thead><tbody>")
        for ((r, cells) in rows) {
            sb.append("<tr><th>").append(r).append("</th>")
            for (c in 0..maxCol) {
                val v = cells[c].orEmpty()
                sb.append(if (numeric.matches(v)) "<td class=\"num\">" else "<td>").append(esc(v)).append("</td>")
            }
            sb.append("</tr>")
        }
        sb.append("</tbody></table>")
        if (truncated) sb.append("<p class=\"note\">").append(esc(labels.truncatedCells(MAX_SHEET_ROWS, MAX_SHEET_COLS))).append("</p>")
        return sb.toString()
    }

    private fun cellText(raw: String, type: String?, style: Int, shared: List<String>, dateStyles: Set<Int>): String = when (type) {
        "s" -> raw.trim().toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
        "b" -> if (raw.trim() == "1") "TRUE" else "FALSE"
        "str", "inlineStr", "e" -> raw
        else -> {
            val d = raw.trim().toDoubleOrNull()
            when {
                d == null -> raw
                style in dateStyles -> excelDate(d)
                else -> java.math.BigDecimal(d).round(java.math.MathContext(12)).stripTrailingZeros().toPlainString()
            }
        }
    }

    /** An Excel date serial (days since 1899-12-30) as ISO date, with the time when it has one. */
    internal fun excelDate(serial: Double): String {
        val days = kotlin.math.floor(serial).toLong()
        val date = java.time.LocalDate.of(1899, 12, 30).plusDays(days)
        val seconds = Math.round((serial - days) * 86400).toInt()
        if (seconds == 0 || seconds == 86400) return date.toString()
        val time = java.time.LocalTime.ofSecondOfDay(seconds.toLong())
        return if (days == 0L) time.toString() else "$date $time"
    }

    /** "C12" -> 2. */
    internal fun columnIndex(ref: String): Int {
        var n = 0
        for (ch in ref) { if (ch !in 'A'..'Z' && ch !in 'a'..'z') break; n = n * 26 + (ch.uppercaseChar() - 'A' + 1) }
        return n - 1
    }

    internal fun columnName(index: Int): String {
        var n = index + 1
        val sb = StringBuilder()
        while (n > 0) { val r = (n - 1) % 26; sb.append('A' + r); n = (n - 1) / 26 }
        return sb.reverse().toString()
    }

    /** Each shared string's plain text, rich runs joined, phonetic guides left out. */
    private fun sharedStrings(newParser: () -> XmlPullParser, bytes: ByteArray?): List<String> {
        if (bytes == null) return emptyList()
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var inT = false
        var inPhonetic = false
        walk(newParser, bytes, onStart = { _, name ->
            when (name) {
                "si" -> cur.setLength(0)
                "rPh" -> inPhonetic = true
                "t" -> inT = !inPhonetic
            }
        }, onText = { if (inT) cur.append(it) }, onEnd = { name ->
            when (name) {
                "t" -> inT = false
                "rPh" -> inPhonetic = false
                "si" -> out.add(cur.toString())
            }
        })
        return out
    }

    private val builtInDateFormats = (14..22).toSet() + setOf(27, 30, 36, 45, 46, 47, 50, 57)

    /** Indexes of the cell styles (cellXfs) that show a date or time. */
    private fun dateStyles(newParser: () -> XmlPullParser, bytes: ByteArray?): Set<Int> {
        if (bytes == null) return emptySet()
        val customDates = HashSet<Int>()
        val out = HashSet<Int>()
        var inXfs = false
        var index = 0
        walk(newParser, bytes, onStart = { p, name ->
            when (name) {
                "numFmt" -> {
                    val code = p.attr("formatCode").orEmpty().replace(Regex("\"[^\"]*\"|\\[[^\\]]*]"), "").lowercase()
                    if (code.any { it == 'y' || it == 'd' || it == 'h' } || (code.contains('m') && !code.contains('0'))) {
                        p.attr("numFmtId")?.toIntOrNull()?.let { customDates.add(it) }
                    }
                }
                "cellXfs" -> { inXfs = true; index = 0 }
                "xf" -> if (inXfs) {
                    val id = p.attr("numFmtId")?.toIntOrNull() ?: 0
                    if (id in builtInDateFormats || id in customDates) out.add(index)
                    index++
                }
            }
        }, onEnd = { if (it == "cellXfs") inXfs = false })
        return out
    }

    // --- text utils ---

    fun esc(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (ch in s) when (ch) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;")
            else -> sb.append(ch)
        }
        return sb.toString()
    }
}
