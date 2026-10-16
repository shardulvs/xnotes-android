package com.xnotes.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser

class ReaderHtmlTest {
    private val parser = { KXmlParser() }

    private fun zip(vararg entries: Pair<String, String>) = ReaderHtml.ZipRead { path ->
        entries.firstOrNull { it.first == path }?.second?.toByteArray()
    }

    @Test
    fun wikilinksBecomeReaderLinksWithAliasAndHeading() {
        val md = ReaderHtml.obsidianSyntax("See [[My Note|this one]] and [[Other#Part]].")
        assertEquals("See [this one](<nnotes-wiki:My%20Note>) and [Other > Part](<nnotes-wiki:Other>).", md)
    }

    @Test
    fun obsidianSyntaxLeavesCodeAlone() {
        val src = "```\n[[not a link]] ==no==\n```\ninline `[[code]]` and ==mark=="
        val out = ReaderHtml.obsidianSyntax(src)
        assertTrue(out.startsWith("```\n[[not a link]] ==no==\n```\n"))
        assertTrue(out.endsWith("inline `[[code]]` and <mark>mark</mark>"))
    }

    @Test
    fun markdownRendersTablesTasksAndCallouts() {
        val html = ReaderHtml.markdown("| a | b |\n|---|---|\n| 1 | 2 |\n\n- [x] done\n\n> [!warning] Careful\n> body")
        assertTrue(html.contains("<table>"))
        assertTrue(html.contains("type=\"checkbox\""))
        assertTrue(html.contains("<blockquote class=\"callout callout-warning\"><p class=\"callout-title\">Careful</p><p>body"))
    }

    @Test
    fun frontmatterIsShownAsPropertiesNotAsText() {
        val html = ReaderHtml.markdown("---\ntitle: Hello\ntags:\n  - a\n  - b\n---\n# Body")
        assertTrue(html.contains("<b>title</b>: Hello"))
        assertTrue(html.contains("<b>tags</b>: a, b"))
        assertTrue(html.contains("<h1>Body</h1>"))
        assertFalse(html.contains("---"))
    }

    @Test
    fun unsyncedImagesFallBackToAltText() {
        val html = ReaderHtml.markdown("![a cat](cat.png)")
        assertTrue(html.contains("[a cat]"))
        assertFalse(html.contains("<img"))
    }

    @Test
    fun csvHandlesQuotesAndDetectsSemicolons() {
        val text = "name;note\n\"Doe; J\";\"said \"\"hi\"\"\nthen left\"\nx;1"
        assertEquals(';', ReaderHtml.detectDelimiter(text))
        val rows = ReaderHtml.parseCsv(text, ';')
        assertEquals(listOf("name", "note"), rows[0])
        assertEquals(listOf("Doe; J", "said \"hi\"\nthen left"), rows[1])
        assertEquals(listOf("x", "1"), rows[2])
        val html = ReaderHtml.csv(text)
        assertTrue(html.contains("<th>name</th>"))
        assertTrue(html.contains("<td class=\"num\">1</td>"))
    }

    @Test
    fun docxKeepsHeadingsListsFormattingAndTables() {
        val w = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
        val doc = """<w:document $w><w:body>
            <w:p><w:pPr><w:pStyle w:val="Nadpis1"/></w:pPr><w:r><w:t>Intro</w:t></w:r></w:p>
            <w:p><w:r><w:rPr><w:b/></w:rPr><w:t>bold</w:t></w:r><w:r><w:t xml:space="preserve"> plain</w:t></w:r></w:p>
            <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="3"/></w:numPr></w:pPr><w:r><w:t>one</w:t></w:r></w:p>
            <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="3"/></w:numPr></w:pPr><w:r><w:t>two</w:t></w:r></w:p>
            <w:tbl><w:tr><w:tc><w:p><w:r><w:t>cell</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
            </w:body></w:document>"""
        val styles = """<w:styles $w><w:style w:styleId="Nadpis1"><w:name w:val="heading 1"/></w:style></w:styles>"""
        val numbering = """<w:numbering $w><w:abstractNum w:abstractNumId="7"><w:lvl w:ilvl="0"><w:numFmt w:val="decimal"/></w:lvl></w:abstractNum>
            <w:num w:numId="3"><w:abstractNumId w:val="7"/></w:num></w:numbering>"""
        val html = ReaderHtml.docx(zip("word/document.xml" to doc, "word/styles.xml" to styles, "word/numbering.xml" to numbering), parser)
        assertTrue(html, html.contains("<h1>Intro</h1>"))
        assertTrue(html, html.contains("<p><b>bold</b> plain</p>"))
        assertTrue(html, html.contains("<ol><li>one</li><li>two</li></ol>"))
        assertTrue(html, html.contains("<table><tr><td><p>cell</p></td></tr></table>"))
    }

    @Test
    fun pptxListsSlidesInOrderWithTitlesFirstAndNotes() {
        val ns = "xmlns:p=\"p\" xmlns:a=\"a\" xmlns:r=\"r\""
        val pres = """<p:presentation $ns><p:sldIdLst><p:sldId id="257" r:id="rId3"/><p:sldId id="256" r:id="rId2"/></p:sldIdLst></p:presentation>"""
        val presRels = """<Relationships><Relationship Id="rId2" Type="t/slide" Target="slides/slide1.xml"/><Relationship Id="rId3" Type="t/slide" Target="slides/slide2.xml"/></Relationships>"""
        fun slide(title: String, body: String) = """<p:sld $ns><p:cSld><p:spTree>
            <p:sp><p:nvSpPr><p:nvPr><p:ph idx="1"/></p:nvPr></p:nvSpPr><p:spPr><a:xfrm><a:off x="0" y="500"/></a:xfrm></p:spPr>
              <p:txBody><a:p><a:r><a:t>$body</a:t></a:r></a:p></p:txBody></p:sp>
            <p:sp><p:nvSpPr><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr><p:spPr><a:xfrm><a:off x="0" y="900"/></a:xfrm></p:spPr>
              <p:txBody><a:p><a:r><a:t>$title</a:t></a:r></a:p></p:txBody></p:sp>
            <p:sp><p:nvSpPr><p:nvPr><p:ph type="sldNum"/></p:nvPr></p:nvSpPr><p:txBody><a:p><a:r><a:t>99</a:t></a:r></a:p></p:txBody></p:sp>
            </p:spTree></p:cSld></p:sld>"""
        val notesRels = """<Relationships><Relationship Id="rId1" Type="x/notesSlide" Target="../notesSlides/notesSlide1.xml"/></Relationships>"""
        val notes = """<p:notes $ns><p:cSld><p:spTree><p:sp><p:nvSpPr><p:nvPr><p:ph type="body"/></p:nvPr></p:nvSpPr>
            <p:txBody><a:p><a:r><a:t>say this</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:notes>"""
        val html = ReaderHtml.pptx(
            zip(
                "ppt/presentation.xml" to pres, "ppt/_rels/presentation.xml.rels" to presRels,
                "ppt/slides/slide1.xml" to slide("First", "alpha"), "ppt/slides/slide2.xml" to slide("Second", "beta"),
                "ppt/slides/_rels/slide2.xml.rels" to notesRels, "ppt/notesSlides/notesSlide1.xml" to notes,
            ),
            parser,
        )
        assertTrue(html, html.indexOf("Second") < html.indexOf("First"))
        assertTrue(html, html.contains("<h2>Second</h2><ul><li style=\"margin-left:0.0em\">beta</li></ul>"))
        assertTrue(html, html.contains("say this"))
        assertFalse(html, html.contains("99"))
    }

    @Test
    fun xlsxShowsSheetsWithSharedStringsNumbersAndDates() {
        val wb = """<workbook xmlns:r="r"><sheets><sheet name="Data" sheetId="1" r:id="rId1"/><sheet name="Empty" sheetId="2" r:id="rId2"/></sheets></workbook>"""
        val wbRels = """<Relationships><Relationship Id="rId1" Type="x/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="x/worksheet" Target="/xl/worksheets/sheet2.xml"/></Relationships>"""
        val shared = """<sst><si><t>Name</t></si><si><r><t>Bo</t></r><r><t>b</t></r><rPh><t>x</t></rPh></si></sst>"""
        val styles = """<styleSheet><numFmts><numFmt numFmtId="164" formatCode="d/m/yyyy"/></numFmts><cellXfs><xf numFmtId="0"/><xf numFmtId="164"/></cellXfs></styleSheet>"""
        val sheet1 = """<worksheet><sheetData>
            <row r="1"><c r="A1" t="s"><v>0</v></c><c r="C1" t="inlineStr"><is><t>When</t></is></c></row>
            <row r="3"><c r="A3" t="s"><v>1</v></c><c r="B3"><v>0.30000000000000004</v></c><c r="C3" s="1"><v>45292</v></c><c r="D3" t="b"><v>1</v></c></row>
            </sheetData></worksheet>"""
        val sheet2 = """<worksheet><sheetData/></worksheet>"""
        val html = ReaderHtml.xlsx(
            zip(
                "xl/workbook.xml" to wb, "xl/_rels/workbook.xml.rels" to wbRels, "xl/sharedStrings.xml" to shared,
                "xl/styles.xml" to styles, "xl/worksheets/sheet1.xml" to sheet1, "xl/worksheets/sheet2.xml" to sheet2,
            ),
            parser,
        )
        assertTrue(html, html.contains("<h2>Data</h2>"))
        assertTrue(html, html.contains("<th></th><th>A</th><th>B</th><th>C</th><th>D</th>"))
        assertTrue(html, html.contains("<tr><th>1</th><td>Name</td><td></td><td>When</td><td></td></tr>"))
        assertTrue(html, html.contains("<tr><th>3</th><td>Bob</td><td class=\"num\">0.3</td><td>2024-01-01</td><td>TRUE</td></tr>"))
        assertTrue(html, html.contains("<h2>Empty</h2><p class=\"missing\">"))
    }

    @Test
    fun spreadsheetColumnsAndDates() {
        assertEquals(0, ReaderHtml.columnIndex("A1"))
        assertEquals(27, ReaderHtml.columnIndex("AB12"))
        assertEquals("AB", ReaderHtml.columnName(27))
        assertEquals("1900-01-01", ReaderHtml.excelDate(2.0))
        assertEquals("2024-01-01 12:00", ReaderHtml.excelDate(45292.5))
    }

    @Test
    fun resolvesRelativePartPaths() {
        assertEquals("ppt/media/image1.png", ReaderHtml.resolvePath("ppt/slides", "../media/image1.png"))
        assertEquals("word/media/a.png", ReaderHtml.resolvePath("word", "media/a.png"))
    }
}
