package com.xnotes.platform

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PageMode
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.xnotes.core.pdf.Heading
import com.xnotes.core.pdf.Outline
import com.xnotes.core.pdf.OutlineNode
import kotlin.math.roundToInt

/**
 * An export's bookmarks: the source PDF's own, and one per heading of the flow, nested by level
 * and opening at the heading itself. When the file has any, it opens with the bookmarks showing.
 */
internal object PdfBookmarks {

    /**
     * Where [heading]'s bookmark opens: its [page] in the export, [left] (null keeps the view's)
     * and [top] there in points, and its tagged [element].
     */
    class Anchor(val heading: Heading, val page: PDPage, val left: Float?, val top: Float, val element: COSDictionary?)

    /** A source bookmark copied for the export, and whether it showed its children. */
    class Entry(val item: PDOutlineItem, val open: Boolean)

    /** Walking a damaged outline stops after this many bookmarks, or this deep. */
    private const val MAX_ENTRIES = 2000
    private const val MAX_DEPTH = 32

    /** Add [sources] and then [anchors] to the end of [doc]'s outline. */
    fun write(doc: PDDocument, sources: List<OutlineNode<Entry>>, anchors: List<Anchor>) {
        val catalog = doc.documentCatalog
        val outline = runCatching { catalog.documentOutline }.getOrNull() ?: PDDocumentOutline()
        for (n in sources) outline.addLast(copied(n))
        for (n in Outline.nest(anchors) { it.heading.level }) outline.addLast(heading(n))
        if (!outline.hasChildren()) return
        catalog.documentOutline = outline
        catalog.pageMode = PageMode.USE_OUTLINES
    }

    private fun copied(n: OutlineNode<Entry>): PDOutlineItem {
        val item = n.value.item
        for (k in n.kids) item.addLast(copied(k))
        if (n.value.open && n.kids.isNotEmpty()) item.openNode()
        return item
    }

    private fun heading(n: OutlineNode<Anchor>): PDOutlineItem {
        val a = n.value
        val item = PDOutlineItem()
        item.title = a.heading.title
        item.destination = PDPageXYZDestination().apply {
            page = a.page
            left = a.left?.roundToInt() ?: -1
            top = a.top.roundToInt()
            zoom = -1f
        }
        a.element?.let { item.cosObject.setItem(COSName.SE, it) }
        for (k in n.kids) item.addLast(heading(k))
        if (n.kids.isNotEmpty()) item.openNode()
        return item
    }

    /**
     * [src]'s own bookmarks for an export rebuilt from its pages, each pointed at the export page
     * showing its page ([pageAt] of a source page index; null when the export left that page out).
     * A bookmark whose page was left out goes, and its children move up into its place.
     */
    fun remapped(src: PDDocument, pageAt: (Int) -> PDPage?): List<OutlineNode<Entry>> {
        val root = runCatching { src.documentCatalog.documentOutline }.getOrNull() ?: return emptyList()
        var seen = 0
        fun tree(node: PDOutlineNode, depth: Int): List<OutlineNode<PDOutlineItem>> {
            if (depth > MAX_DEPTH) return emptyList()
            val out = mutableListOf<OutlineNode<PDOutlineItem>>()
            for (item in node.children()) {
                if (++seen > MAX_ENTRIES) break
                out += OutlineNode(item, tree(item, depth + 1))
            }
            return out
        }
        val items = runCatching { tree(root, 0) }.getOrDefault(emptyList())
        return Outline.remap(items) { copy(src, it, pageAt) }
    }

    /** [item] for the export, or null when its page is not in it. */
    private fun copy(src: PDDocument, item: PDOutlineItem, pageAt: (Int) -> PDPage?): Entry? {
        val out = PDOutlineItem()
        out.title = item.title.orEmpty()
        // Colour and style are copied as written: reading them through PdfBox would add defaults to the source.
        item.cosObject.getDictionaryObject(COSName.C)?.let { out.cosObject.setItem(COSName.C, it) }
        item.cosObject.getDictionaryObject(COSName.F)?.let { out.cosObject.setItem(COSName.F, it) }
        val dest = runCatching { pageDestination(src, item) }.getOrNull()
        val index = dest?.retrievePageNumber() ?: -1
        if (dest != null && index >= 0) {
            val page = pageAt(index) ?: return null
            val old = dest.cosObject
            val arr = COSArray()
            arr.add(page.cosObject)
            for (i in 1 until old.size()) arr.add(old.get(i))
            out.destination = PDDestination.create(arr)
        } else {
            // A bookmark to no page of this file (a web address, say) keeps what it does.
            item.action?.takeIf { it !is PDActionGoTo }?.let { out.action = it }
        }
        return Entry(out, item.isNodeOpen)
    }

    private fun pageDestination(src: PDDocument, item: PDOutlineItem): PDPageDestination? {
        var dest = item.destination ?: (item.action as? PDActionGoTo)?.destination
        if (dest is PDNamedDestination) dest = src.documentCatalog.findNamedDestinationPage(dest)
        return dest as? PDPageDestination
    }
}
