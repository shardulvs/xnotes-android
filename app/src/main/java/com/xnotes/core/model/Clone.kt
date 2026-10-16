package com.xnotes.core.model

import com.xnotes.core.pal.TextMeasurer

/**
 * A deep, independent copy of a canvas item. Mutable geometry (stroke samples) is duplicated so the
 * copy and original can be edited apart; image rasters are *shared* because their pixels are
 * immutable. [measurer] is needed to lay out a copied text box. Used for copy/paste/duplicate of
 * both items and whole pages (see [Page.deepCopy]).
 */
fun CanvasItem.deepCopy(measurer: TextMeasurer): CanvasItem = when (this) {
    is Stroke -> Stroke(this)
    is ImageItem -> ImageItem(image, rect, orientation, angle, flip)
    is TextItem -> TextItem(pos, width, height, text, rgba, pointSize, face, measurer, bold, italic, underline, strike, align, math)
    is ShapeItem ->
        ShapeItem(
            shape, start, end, strokeRgba, strokeWidth, fillRgba, neon, neonStrength, path,
            dashed, dashLength, dashGap, rotation, adj.copyOf(), n, headStart, headEnd,
        ).also {
            it.flip = flip
            it.label = label
            it.labelAt = labelAt
            it.function = function
        }
    is ConnectorItem ->
        ConnectorItem(routing, start, end, strokeRgba, strokeWidth, headStart, headEnd, dashed, dashLength, dashGap, neon, neonStrength).also {
            it.waypoints = waypoints
            it.elbow = elbow
            it.label = label
            it.labelAt = labelAt
        }
    is TableItem ->
        TableItem(rect, colFractions.toList(), rowFractions.toList(), strokeRgba, strokeWidth)
    else -> this
    // Carried, not reset: autosave snapshots the document through this, so dropping the lock here
    // would quietly unlock everything on the next save.
}.also { it.locked = locked; it.link = link }

/**
 * Deep copies of these items that keep their joins among themselves: a connector copied with what it
 * joins joins the copies, and an end glued to anything left behind goes loose where it was. A lone
 * [deepCopy] of a connector keeps its glue to the originals.
 */
fun List<CanvasItem>.deepCopyAll(measurer: TextMeasurer): List<CanvasItem> {
    val copies = java.util.IdentityHashMap<CanvasItem, CanvasItem>(size * 2)
    val out = map { item -> item.deepCopy(measurer).also { copies[item] = it } }
    fun regluedTo(e: ConnectorEnd): ConnectorEnd = e.target?.let { t -> copies[t]?.let { ConnectorEnd(e.at, it, e.point) } } ?: e.loose()
    for (c in out) {
        if (c !is ConnectorItem) continue
        c.start = regluedTo(c.start)
        c.end = regluedTo(c.end)
    }
    return out
}

/** A deep copy of a page — its items cloned ([deepCopyAll]) — keeping the size, PDF link, style, margins and markups. */
fun Page.deepCopy(measurer: TextMeasurer): Page =
    Page(width, height, items.deepCopyAll(measurer).toMutableList(), pdfPage, style, margins, markups)

/**
 * A deep copy of a document: pages cloned, bookmarks copied, the source PDF file and styles shared
 * (immutable for the copy's lifetime). Cheap now that image bytes are shared, so it can snapshot the
 * live document on the main thread before an off-thread save, keeping the writer off the mutating
 * model (no [ConcurrentModificationException]).
 */
fun Document.deepCopy(measurer: TextMeasurer): Document = Document(
    pages = pages.mapTo(mutableListOf()) { it.deepCopy(measurer) },
    dpi = dpi,
    path = path,
    displayName = displayName,
    dirty = dirty,
    pdfFile = pdfFile,
    bookmarks = bookmarks.mapTo(mutableListOf()) { Bookmark(it.page, it.label) },
    style = style,
    margins = margins,
    // The flow must be cloned too or the autosave snapshot would race live edits.
    flow = flow.deepCopy(),
    created = created,
    templates = templates,
)

/**
 * The document as the writer should see it: fresh [Page] objects over fresh item *lists*, sharing
 * the items themselves. O(items) pointers rather than O(samples) floats, so an autosave no longer
 * needs a second copy of the note on the heap (which is what used to run a big note out of it).
 *
 * What makes sharing safe is that the main thread only ever *replaces* an item's state, never
 * edits it under a reader: adding, deleting and reordering touch the live lists this snapshot
 * copied, and a [Stroke]'s samples are published whole through one volatile field. So the writer
 * always serializes a coherent item, though an edit landing mid-write may or may not be in the
 * file; that edit marks the document dirty again and the next autosave carries it.
 *
 * The flow is the exception: it is a tree of mutable lists with no such discipline, so it is still
 * deep-copied. It is small. Use [deepCopy] wherever the copy has to be independently editable.
 */
fun Document.snapshot(): Document = Document(
    pages = pages.mapTo(mutableListOf()) {
        Page(it.width, it.height, ArrayList(it.items), it.pdfPage, it.style, it.margins, it.markups)
    },
    dpi = dpi,
    path = path,
    displayName = displayName,
    dirty = dirty,
    pdfFile = pdfFile,
    bookmarks = bookmarks.mapTo(mutableListOf()) { Bookmark(it.page, it.label) },
    style = style,
    margins = margins,
    flow = flow.deepCopy(),
    created = created,
    templates = templates,
)
