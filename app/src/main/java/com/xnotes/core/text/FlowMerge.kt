package com.xnotes.core.text

/** Typed text from one note put after another's, as merging notes does. */
object FlowMerge {

    /**
     * Put [src]'s paragraphs after [dst]'s, the first starting on page [page] of the merged note,
     * or later when the text before it runs long. Where [src]'s base text differs from [dst]'s,
     * its own base is written onto its runs so it keeps its look. An empty [src] adds nothing.
     */
    fun append(dst: TextFlow, src: TextFlow, page: Int) {
        if (src.isEmpty) return
        if (dst.isEmpty) dst.paragraphs.clear()
        val paras = src.paragraphs.toList()
        val size = src.defaultSizePt.takeIf { it != dst.defaultSizePt }
        val face = src.defaultFace.takeIf { it != dst.defaultFace }
        val color = src.defaultColor.takeIf { it != dst.defaultColor }
        if (size != null || face != null || color != null) {
            for (para in paras) {
                for (run in para.runs) {
                    var s = run.style
                    if (size != null && s.sizePt == null && para.headingLevel == 0) s = s.copy(sizePt = size)
                    if (face != null && s.face == null && !s.code && !s.math && para.codeLang == null) s = s.copy(face = face)
                    if (color != null && s.color == null) s = s.copy(color = color)
                    run.style = s
                }
                para.touch()
            }
        }
        // A table lays out on its own terms, so a break before one goes on a line of its own.
        val head = paras.first().takeIf { it.table == null } ?: Paragraph()
        head.breakBefore = true
        head.breakToPage = page
        if (head !== paras.first()) dst.paragraphs.add(head)
        dst.paragraphs.addAll(paras)
        dst.touch()
    }
}
