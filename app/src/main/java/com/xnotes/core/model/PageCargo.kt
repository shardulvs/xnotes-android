package com.xnotes.core.model

import com.xnotes.core.audio.RecordingIds
import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.pal.TextMeasurer

/**
 * Pages lifted out of a note to go into another, carrying what they need there: each its own look,
 * the templates it is ruled with, its bookmarks and recordings. Typed text stays behind. The PDF
 * pages they show are [pdfIndices] in their note's PDF, which the caller copies into a PDF of
 * their own ([pdf]) when they land in another note, so the note they came from may close.
 */
class PageCargo private constructor(
    /** Copies of the pages as they were in their note. */
    private val pages: List<Page>,
    /** Each page's look fixed for another note: its style and margins with the note-wide ones written in. */
    private val looks: List<Pair<PageStyle, PageMargins>>,
    val dpi: Int,
    /** The source PDF's pages the cargo shows, in the order [pdf] holds them. */
    val pdfIndices: List<Int>,
    private val templates: Map<String, String>,
    private val templatePackages: Map<String, java.io.File>,
    /** Bookmarks on the cargo, their page an index into it. */
    val bookmarks: List<Bookmark>,
    /** The first page was its note's cover. */
    val cover: Boolean,
) {
    /** A PDF of just the [pdfIndices] pages, in that order; null until the caller makes one, or without PDF pages. */
    var pdf: java.io.File? = null

    val size: Int get() = pages.size

    /**
     * Fresh copies of the pages for [target], another note: each in its own look, sized to its
     * dpi, their PDF pages counted from [pdfBase] in its PDF (null when the cargo's PDF did not
     * join, which leaves them blank). Ink tied to a recording that did not come along loses the
     * tie, and the recordings are numbered so they take over no ink already in [target]. The
     * templates they are ruled with join [target]'s, where it has none of that name.
     */
    fun unpack(target: Document, pdfBase: Int?, measurer: TextMeasurer): List<Page> {
        val k = target.dpi.toDouble() / dpi
        val at = pdfIndices.withIndex().associate { it.value to it.index }
        val copies = pages.mapIndexed { i, src ->
            src.deepCopy(measurer).also { page ->
                page.style = looks[i].first
                page.margins = looks[i].second
                page.pdfPage = src.pdfPage?.let { at[it] }?.let { n -> pdfBase?.let { it + n } }
                if (page.pdfPage == null) page.pdfTurn = 0
                if (k != 1.0) rescale(page, k)
            }
        }
        val items = copies.flatMap { it.items }
        val rids = items.filterIsInstance<AudioItem>().mapTo(HashSet()) { it.rid }
        for (item in items) if (item.sync?.rid?.let { it !in rids } == true) item.sync = null
        RecordingIds.adopt(items, target.pages.flatMap { it.items }, moves = true)
        target.templates = templates.filterKeys { it !in target.templates } + target.templates
        target.templatePackages = templatePackages.filterKeys { it !in target.templatePackages } + target.templatePackages
        return copies
    }

    /** Fresh copies of the pages as they were, for [home], the note they came from. */
    fun unpackHome(home: Document, measurer: TextMeasurer): List<Page> {
        val copies = pages.map { it.deepCopy(measurer) }
        RecordingIds.adopt(copies.flatMap { it.items }, home.pages.flatMap { it.items }, moves = true)
        return copies
    }

    companion object {

        /** The pages of [doc] at [indices], packed in note order. */
        fun pack(doc: Document, indices: List<Int>, measurer: TextMeasurer): PageCargo {
            val picked = indices.filter { it in doc.pages.indices }.distinct().sorted()
            val pages = picked.map { doc.pages[it].deepCopy(measurer) }
            val looks = picked.map { doc.pages[it].bakedStyle(doc) to doc.pages[it].resolvedMargins(doc) }
            val keys = looks.mapNotNullTo(HashSet()) { it.first.template }
            val bookmarks = doc.bookmarks.mapNotNull { b -> picked.indexOf(b.page).takeIf { it >= 0 }?.let { Bookmark(it, b.label) } }
            val cover = picked.firstOrNull() == 0 && doc.pages.firstOrNull()?.cover == true
            return PageCargo(
                pages, looks, doc.dpi, pages.mapNotNull { it.pdfPage }.distinct(),
                doc.templates.filterKeys { it in keys }, doc.templatePackages.filterKeys { it in keys },
                bookmarks, cover,
            )
        }

        /** Scale [page] and everything on it by [k], from a note of one dpi to another. */
        private fun rescale(page: Page, k: Double) {
            page.width *= k
            page.height *= k
            val t = Affine.scaleAbout(Pt.ZERO, k, k)
            for (item in page.items) item.applyTransform(t)
            page.style.spacing?.let { page.style = page.style.copy(spacing = it * k) }
        }
    }
}

/** [this] page's look, fixed into its own style, so it shows the same under another note's note-wide style. */
fun Page.bakedStyle(doc: Document): PageStyle {
    val template = resolvedTemplate(doc)
    return PageStyle(
        pageColor = style.pageColor ?: doc.style.pageColor,
        template = template,
        patternColor = style.patternColor ?: doc.style.patternColor?.takeIf { sharesNoteTemplate(doc, template) },
        spacing = style.spacing ?: doc.style.spacing?.takeIf { sharesNoteTemplate(doc, template) },
        accentColor = style.accentColor ?: doc.style.accentColor?.takeIf { sharesNoteTemplate(doc, template) },
        params = resolvedParams(doc).ifEmpty { null },
        colors = resolvedColors(doc).ifEmpty { null },
    )
}

/** True when the page shows the note-wide template, so the note-wide template settings reach it. */
private fun sharesNoteTemplate(doc: Document, template: String): Boolean =
    (doc.style.template ?: PageTemplates.NONE) == template
