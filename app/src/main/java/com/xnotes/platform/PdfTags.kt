package com.xnotes.platform

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNull
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata
import com.tom_roush.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkInfo
import com.tom_roush.pdfbox.pdmodel.interactive.viewerpreferences.PDViewerPreferences
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.TextItem
import com.xnotes.core.pal.Mark
import com.xnotes.core.pdf.FlowStructure
import com.xnotes.core.pdf.StructNode
import com.xnotes.core.pdf.TextLink
import java.util.IdentityHashMap

/**
 * The logical structure of a tagged export: which element each piece of the drawing belongs to,
 * and the order a screen reader meets them in. Pages record their content through a [PageTagger]
 * as they paint; [finish] writes the structure tree, its parent tree and the catalog entries a
 * tagged PDF carries. Each page reads as the flow blocks that start on it, then its text boxes top
 * to bottom, its images, and its handwriting as one figure.
 */
internal class PdfTags(private val doc: PDDocument, private val setup: Setup) {

    /** What the document is called and in, and what its figures say to a reader. */
    class Setup(val title: String, val lang: String, val altDrawing: String, val altImage: String)

    private var flow: FlowStructure.Tree? = null
    private var links: Map<Int, List<TextLink>> = emptyMap()
    private val flowLinks = HashMap<Long, StructNode>()
    private val formulas = HashMap<Long, StructNode>()

    /** What each element holds, in drawing order: [Mcid]s, [Annot]s and elements set inline. */
    private val content = IdentityHashMap<StructNode, MutableList<Any>>()
    private val pages = mutableListOf<PageTags>()
    private val annots = mutableListOf<Annot>()
    private var nextKey = 0

    private class Mcid(val page: PageTags, val id: Int)

    private class Annot(val page: PageTags, val dict: COSDictionary, val key: Int, val owner: StructNode)

    /** The flow this export paints, and the links in each of its paragraphs. */
    fun useFlow(tree: FlowStructure.Tree, links: Map<Int, List<TextLink>>) {
        flow = tree
        this.links = links
    }

    /** Start tagging [pd], whose content goes to [cs]. */
    fun page(pd: PDPage, cs: PDPageContentStream): PageTagger = PageTagger(this, PageTags(pd).also { pages += it }, cs)

    private fun contentOf(node: StructNode): MutableList<Any> = content.getOrPut(node) { mutableListOf() }

    /** A new [type] element set inside [parent] where its drawing has got to. */
    private fun inline(parent: StructNode, type: String): StructNode = StructNode(type).also { contentOf(parent) += it }

    private fun key(para: Int, at: Int): Long = (para.toLong() shl 32) or (at.toLong() and 0xFFFFFFFFL)

    /** The element flow characters [start, end) of [para] belong to: a link inside it, or the paragraph's own. */
    fun flowText(para: Int, start: Int, end: Int): StructNode? {
        val node = flow?.textOf(para) ?: return null
        val link = links[para]?.firstOrNull { end > start && start >= it.start && end <= it.end } ?: return node
        return flowLink(para, link, node)
    }

    private fun flowLink(para: Int, link: TextLink, parent: StructNode): StructNode =
        flowLinks.getOrPut(key(para, link.start)) { inline(parent, "Link") }

    fun flowLabel(para: Int): StructNode? = flow?.labelOf(para)

    fun formula(para: Int, start: Int, latex: String): StructNode? {
        val parent = flow?.textOf(para) ?: return null
        return formulas.getOrPut(key(para, start)) { inline(parent, "Formula").apply { alt = latex } }
    }

    /** Tie link annotation [annot] on [page] to [link] in flow paragraph [para]. */
    fun annotateFlow(page: PageTags, annot: COSDictionary, para: Int, link: TextLink) {
        val parent = flow?.textOf(para) ?: return
        annotate(page, annot, flowLink(para, link, parent))
    }

    /** Give text markup annotation [annot] on [pd] an Annot element of its own saying [alt], read after the page's drawing. */
    fun markup(pd: PDPage, annot: COSDictionary, alt: String) {
        val page = pages.lastOrNull { it.pd.cosObject === pd.cosObject } ?: return
        val node = StructNode("Annot").also { it.alt = alt }
        page.markups += node
        annotate(page, annot, node)
    }

    fun annotate(page: PageTags, annot: COSDictionary, owner: StructNode) {
        val a = Annot(page, annot, nextKey++, owner)
        annot.setInt(COSName.STRUCT_PARENT, a.key)
        annots += a
        contentOf(owner) += a
        // A page with annotations takes them in structure order when tabbing.
        page.pd.cosObject.setName(COSName.getPDFName("Tabs"), "S")
    }

    /** One page's share of the structure: its marked content and the parts only it has. */
    inner class PageTags(val pd: PDPage) {
        val index = pages.size
        private var key = -1
        val owners = ArrayList<StructNode>()
        val boxes = ArrayList<Pair<Rect, StructNode>>()
        val images = ArrayList<StructNode>()
        val markups = ArrayList<StructNode>()
        var ink: StructNode? = null
            private set
        private val boxOf = IdentityHashMap<Mark.TextBox, StructNode>()
        private val imageOf = IdentityHashMap<Mark.Image, StructNode>()
        private val boxLinks = HashMap<Pair<Mark.TextBox, Int>, StructNode>()

        /** The page's key in the parent tree, or -1 while it has no marked content. */
        val parentKey: Int get() = key

        /** A new marked-content id on this page, belonging to [owner]. */
        fun mcid(owner: StructNode): Int {
            if (key < 0) {
                key = nextKey++
                pd.cosObject.setInt(COSName.STRUCT_PARENTS, key)
            }
            owners += owner
            contentOf(owner) += Mcid(this, owners.size - 1)
            return owners.size - 1
        }

        fun ink(): StructNode = ink ?: StructNode("Figure").also {
            it.alt = setup.altDrawing
            ink = it
        }

        fun image(mark: Mark.Image): StructNode = imageOf.getOrPut(mark) {
            StructNode("Figure").also {
                it.alt = setup.altImage
                images += it
            }
        }

        /** Text box [mark]'s paragraph, or the element of its link number [link] when that is not -1. */
        fun textBox(mark: Mark.TextBox, link: Int): StructNode {
            val p = boxOf.getOrPut(mark) { StructNode("P").also { boxes += mark.bounds to it } }
            if (link < 0) return p
            return boxLinks.getOrPut(mark to link) { inline(p, "Link") }
        }
    }

    // --- writing ---

    private val elems = IdentityHashMap<StructNode, COSDictionary>()

    /** Write the structure and mark the document as tagged. Call once, after the last page. */
    fun finish() {
        val root = StructNode("Document")
        val shows = { n: StructNode -> content[n]?.isNotEmpty() == true }
        val blocks = flow?.blocks.orEmpty().filter { it.prune(shows) }.groupBy { firstPage(it) }
        for (page in pages) {
            blocks[page.index]?.let { root.kids += it }
            page.boxes.sortedWith(compareBy({ Math.round(it.first.top) }, { it.first.left })).forEach { root.kids += it.second }
            root.kids += page.images
            page.ink?.let { root.kids += it }
            root.kids += page.markups
        }
        val treeRoot = COSDictionary()
        treeRoot.setItem(COSName.TYPE, COSName.getPDFName("StructTreeRoot"))
        treeRoot.setItem(COSName.K, write(root, treeRoot))
        treeRoot.setItem(COSName.PARENT_TREE, COSDictionary().apply { setItem(COSName.NUMS, parentTree()) })
        treeRoot.setInt(COSName.PARENT_TREE_NEXT_KEY, nextKey)

        val catalog = doc.documentCatalog
        catalog.cosObject.setItem(COSName.STRUCT_TREE_ROOT, treeRoot)
        catalog.markInfo = PDMarkInfo().apply { isMarked = true }
        if (setup.lang.isNotEmpty()) catalog.language = setup.lang
        catalog.viewerPreferences = PDViewerPreferences(COSDictionary()).apply { setDisplayDocTitle(true) }
        doc.documentInformation.title = setup.title
        catalog.metadata = PDMetadata(doc).apply { importXMPMetadata(xmp(setup.title).toByteArray(Charsets.UTF_8)) }
        doc.version = 1.7f
    }

    /** The element written for [node], once [finish] has run; null for a part that showed nothing. */
    fun elementOf(node: StructNode): COSDictionary? = elems[node]

    /** The first page anything in [node]'s subtree was drawn on. */
    private fun firstPage(node: StructNode): Int {
        var best = Int.MAX_VALUE
        for (item in content[node].orEmpty()) {
            best = minOf(
                best,
                when (item) {
                    is Mcid -> item.page.index
                    is Annot -> item.page.index
                    is StructNode -> firstPage(item)
                    else -> Int.MAX_VALUE
                },
            )
        }
        for (kid in node.kids) best = minOf(best, firstPage(kid))
        return best
    }

    private fun write(node: StructNode, parent: COSDictionary): COSDictionary {
        val d = COSDictionary()
        elems[node] = d
        d.setItem(COSName.TYPE, COSName.getPDFName("StructElem"))
        d.setName(COSName.S, node.type)
        d.setItem(COSName.P, parent)
        node.alt?.let { d.setString(COSName.ALT, it) }
        node.numbering?.let { d.setItem(COSName.A, attributes("List", "ListNumbering", it)) }
        node.scope?.let { d.setItem(COSName.A, attributes("Table", "Scope", it)) }
        val items = content[node].orEmpty()
        val pg = items.firstNotNullOfOrNull { (it as? Mcid)?.page ?: (it as? Annot)?.page }
        if (pg != null) d.setItem(COSName.PG, pg.pd.cosObject)
        val kids = COSArray()
        for (item in items) {
            when (item) {
                is Mcid -> kids.add(if (item.page === pg) COSInteger.get(item.id.toLong()) else markedContentRef(item))
                is Annot -> kids.add(objectRef(item))
                is StructNode -> kids.add(write(item, d))
            }
        }
        for (kid in node.kids) kids.add(write(kid, d))
        if (kids.size() == 1) d.setItem(COSName.K, kids.get(0)) else if (kids.size() > 1) d.setItem(COSName.K, kids)
        return d
    }

    private fun attributes(owner: String, key: String, value: String) = COSDictionary().apply {
        setName(COSName.O, owner)
        setName(key, value)
        isDirect = true
    }

    private fun markedContentRef(m: Mcid) = COSDictionary().apply {
        setItem(COSName.TYPE, COSName.getPDFName("MCR"))
        setItem(COSName.PG, m.page.pd.cosObject)
        setInt(COSName.MCID, m.id)
        isDirect = true
    }

    private fun objectRef(a: Annot) = COSDictionary().apply {
        setItem(COSName.TYPE, COSName.getPDFName("OBJR"))
        setItem(COSName.OBJ, a.dict)
        setItem(COSName.PG, a.page.pd.cosObject)
        isDirect = true
    }

    /** Parent tree entries in key order: each page's elements by marked-content id, each annotation's element. */
    private fun parentTree(): COSArray {
        val entries = ArrayList<Pair<Int, com.tom_roush.pdfbox.cos.COSBase>>()
        for (page in pages) {
            if (page.parentKey < 0) continue
            val arr = COSArray()
            for (owner in page.owners) arr.add(elems[owner] ?: COSNull.NULL)
            arr.isDirect = true
            entries += page.parentKey to arr
        }
        for (a in annots) entries += a.key to (elems[a.owner] ?: COSNull.NULL)
        entries.sortBy { it.first }
        return COSArray().apply {
            for ((k, v) in entries) {
                add(COSInteger.get(k.toLong()))
                add(v)
            }
        }
    }

    private fun xmp(title: String): String {
        val t = title.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return "<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n" +
            "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n" +
            "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n" +
            "<rdf:Description rdf:about=\"\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n" +
            "<dc:format>application/pdf</dc:format>\n" +
            "<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">$t</rdf:li></rdf:Alt></dc:title>\n" +
            "</rdf:Description>\n" +
            "</rdf:RDF>\n" +
            "</x:xmpmeta>\n" +
            "<?xpacket end=\"w\"?>"
    }

    companion object {
        /** What [item] is to a reader: an image, a text box, or part of the page's drawing. */
        fun markOf(item: CanvasItem): Mark = when (item) {
            is ImageItem -> Mark.Image()
            is TextItem -> Mark.TextBox(item.bounds())
            else -> Mark.Drawing
        }
    }
}

/**
 * Marks one page's content stream as it is drawn. Each drawing primitive asks for the sequence it
 * belongs in before writing anything; a run of primitives with one owner shares one sequence, and
 * anything a reader should skip goes in an artifact. A sequence opened inside a saved graphics
 * state ends before that state is restored, so the two always nest.
 */
internal class PageTagger(private val tags: PdfTags, val page: PdfTags.PageTags, private val cs: PDPageContentStream) {

    enum class Kind { GRAPHIC, TEXT }

    private val marks = ArrayList<Mark>()
    private var open = false
    private var owner: StructNode? = null
    private var depth = 0

    /** Which link of the text box being drawn the next glyphs are, or -1 for none. */
    var boxLink = -1

    fun begin(mark: Mark) {
        marks += mark
    }

    fun end() {
        if (marks.isNotEmpty()) marks.removeAt(marks.size - 1)
    }

    /** About to draw [kind] content (a formula when [latex] is set) at graphics-state depth [at]. */
    fun draw(kind: Kind, at: Int, latex: String? = null) {
        val want = ownerFor(kind, latex)
        if (open && want === owner) return
        close()
        if (want == null) {
            cs.appendRawCommands("/Artifact BMC\n")
        } else {
            cs.appendRawCommands("/${want.type} <</MCID ${page.mcid(want)}>> BDC\n")
        }
        open = true
        owner = want
        depth = at
    }

    /** The graphics state is about to go back to depth [to]. */
    fun restoring(to: Int) {
        if (open && depth > to) close()
    }

    fun close() {
        if (!open) return
        cs.appendRawCommands("EMC\n")
        open = false
        owner = null
    }

    fun annotate(annot: COSDictionary) {
        when (val m = marks.lastOrNull()) {
            is Mark.TextBox -> tags.annotate(page, annot, page.textBox(m, boxLink))
            else -> {}
        }
    }

    fun annotateFlow(annot: COSDictionary, para: Int, link: TextLink) = tags.annotateFlow(page, annot, para, link)

    private fun ownerFor(kind: Kind, latex: String?): StructNode? = when (val m = marks.lastOrNull()) {
        is Mark.FlowText -> when {
            latex != null -> tags.formula(m.para, m.start, latex)
            kind == Kind.TEXT -> tags.flowText(m.para, m.start, m.end)
            else -> null
        }
        is Mark.FlowMarker -> tags.flowLabel(m.para)
        is Mark.Drawing -> page.ink()
        is Mark.Image -> page.image(m)
        is Mark.TextBox -> if (kind == Kind.TEXT) page.textBox(m, boxLink) else null
        else -> null
    }
}
