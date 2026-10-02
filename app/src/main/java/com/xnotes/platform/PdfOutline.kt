package com.xnotes.platform

/**
 * One entry of a PDF's built-in outline (its "table of contents"). [destPage] is the 0-based source
 * PDF page index the entry points at (matching [com.xnotes.core.model.Page.pdfPage]), or -1 when the
 * entry has no resolvable page. [level] is the nesting depth (0 = top level).
 */
data class PdfOutlineEntry(val title: String, val destPage: Int, val level: Int)
