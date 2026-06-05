package com.xnotes.core.model

import com.xnotes.core.pal.TextMeasurer

/**
 * A deep, independent copy of a canvas item. Mutable geometry (stroke samples) is duplicated so the
 * copy and original can be edited apart; image rasters are *shared* because their pixels are
 * immutable. [measurer] is needed to lay out a copied text box. Used for copy/paste/duplicate of
 * both items and whole pages (see [Page.deepCopy]).
 */
fun CanvasItem.deepCopy(measurer: TextMeasurer): CanvasItem = when (this) {
    is Stroke -> Stroke(tool, config, samples.toMutableList(), speedScale, straight)
    is ImageItem -> ImageItem(raster, rect)
    is TextItem -> TextItem(pos, width, height, text, rgba, pointSize, face, measurer)
    is ShapeItem -> ShapeItem(shape, start, end, strokeRgba, strokeWidth, fillRgba, neon, neonStrength)
    else -> this
}

/** A deep copy of a page — its items cloned ([deepCopy]) — keeping the size and PDF-background link. */
fun Page.deepCopy(measurer: TextMeasurer): Page =
    Page(width, height, items.mapTo(mutableListOf()) { it.deepCopy(measurer) }, pdfPage)
