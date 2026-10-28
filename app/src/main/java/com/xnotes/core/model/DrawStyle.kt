package com.xnotes.core.model

/**
 * The ink colour and stroke width of a drawn item, lifted out so a finished stroke or shape can be
 * restyled after the fact and put back exactly as it was on undo.
 *
 * Only items that actually carry both have a style: images have neither, and a text box owns its
 * own typography (see [TextStyle]) rather than a stroke width.
 */
data class DrawStyle(val color: Rgba, val width: Double) {

    fun applyTo(item: CanvasItem) {
        when (item) {
            is Stroke -> {
                item.config = item.config.copy(rgba = color, baseWidth = width)
                item.invalidate() // the ribbon's half-widths are baked from the config
            }
            is ShapeItem -> {
                if (item.isFill) return // a dropped colour's area has no outline to restyle
                // A fill in the outline's own colour is a tint of it and follows it, keeping its alpha;
                // a fill given a colour of its own keeps that.
                item.fillRgba = item.fillRgba?.let { f ->
                    if (f.r == item.strokeRgba.r && f.g == item.strokeRgba.g && f.b == item.strokeRgba.b) color.copy(a = f.a) else f
                }
                // So does a label in the outline's colour.
                item.label = item.label?.let { l ->
                    val c = l.style.rgba
                    if (c.r == item.strokeRgba.r && c.g == item.strokeRgba.g && c.b == item.strokeRgba.b) l.withStyle(l.style.copy(rgba = color.copy(a = c.a))) else l
                }
                item.strokeRgba = color
                item.strokeWidth = width
            }
            is TapeItem -> {
                item.rgba = color
                // A rectangle's width is its height, which the box's handles set, not a pen width.
                if (item.mode != TapeMode.RECTANGLE) item.shape = item.shape.copy(width = width.coerceIn(TapeItem.MIN_WIDTH, TapeItem.MAX_WIDTH))
            }
            is ConnectorItem -> {
                item.label = item.label?.let { l ->
                    val c = l.style.rgba
                    if (c.r == item.strokeRgba.r && c.g == item.strokeRgba.g && c.b == item.strokeRgba.b) l.withStyle(l.style.copy(rgba = color.copy(a = c.a))) else l
                }
                item.strokeRgba = color
                item.strokeWidth = width
            }
            is TableItem -> {
                item.strokeRgba = color
                item.strokeWidth = width
            }
            else -> Unit
        }
    }

    companion object {
        /** [item]'s current style, or null when it has no colour and width to change. */
        fun of(item: CanvasItem): DrawStyle? = when (item) {
            is Stroke -> DrawStyle(item.config.rgba, item.config.baseWidth)
            is ShapeItem -> if (item.isFill) null else DrawStyle(item.strokeRgba, item.strokeWidth)
            is ConnectorItem -> DrawStyle(item.strokeRgba, item.strokeWidth)
            is TableItem -> DrawStyle(item.strokeRgba, item.strokeWidth)
            is TapeItem -> DrawStyle(item.rgba, item.shape.width)
            else -> null
        }

        /** Settable width range for a restyle, wide enough to span every drawing tool's own range. */
        const val MIN_WIDTH = 1.0
        const val MAX_WIDTH = 80.0
    }
}
