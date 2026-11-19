package com.xnotes.core.importing

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.ShapeLabel
import com.xnotes.core.model.TextStyle
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.model.TextItem
import com.xnotes.core.pal.FontFace
import com.xnotes.core.pal.TextMeasurer
import com.xnotes.core.pal.FontSpec
import kotlin.math.sqrt

/** Content copied from another app; image files are owned by the note after insertion. */
sealed interface DroppedContent {
    data class Text(val value: String) : DroppedContent
    data class Image(val data: ImageData) : DroppedContent
}

/** Shared placement for paged notes and infinite canvases, in their content coordinates. */
object DropPlacement {
    fun items(
        content: List<DroppedContent>, at: Pt, area: Rect, constrainToArea: Boolean,
        color: Rgba, pointSize: Double, face: FontFace, measurer: TextMeasurer,
        textAsLabel: Boolean = false,
    ): List<CanvasItem> = content.mapIndexedNotNull { index, entry ->
        val target = at + Pt(index * 24.0, index * 24.0)
        val item = when (entry) {
            is DroppedContent.Text -> {
                if (entry.value.isBlank()) return@mapIndexedNotNull null
                val font = FontSpec(pointSize, face)
                val lineHeight = measurer.lineHeight(font)
                // Aim for a readable block, widening longer passages to the available view.
                // Advances measure all words without repeatedly laying out a long paragraph.
                val advance = measurer.advances(entry.value, font).sum()
                val preferred = maxOf(lineHeight * 12, sqrt(advance * lineHeight * 1.6))
                val width = minOf(preferred, area.w * 0.7).coerceAtLeast(1.0)
                if (textAsLabel) {
                    // Infinite canvases already render, edit and persist shape labels; they have
                    // no standalone TextItem support. An invisible host keeps the words editable.
                    val label = ShapeLabel(entry.value, TextStyle(color, pointSize, face), measurer)
                    // Use the existing shape-label layout without changing editor behavior.
                    // Rectangle labels inset by at most 6% of the width on each side.
                    val wrap = maxOf(width * 0.88, lineHeight * ShapeLabel.MIN_WIDTH_LINES)
                    val height = label.height(wrap) + width * 0.12
                    ShapeItem(ShapeKind.RECTANGLE, target, target + Pt(width, height),
                        strokeRgba = color.withAlpha(0), strokeWidth = 0.0).also { it.label = label }
                } else {
                    TextItem(target, width, text = entry.value,
                        rgba = color, pointSize = pointSize, face = face, measurer = measurer)
                }
            }
            is DroppedContent.Image -> {
                val image = entry.data
                if (image.width <= 0 || image.height <= 0) return@mapIndexedNotNull null
                val scale = minOf(1.0, area.w * 0.6 / image.width, area.h * 0.6 / image.height)
                val w = image.width * scale
                val h = image.height * scale
                ImageItem(image, Rect(target.x - w / 2, target.y - h / 2, w, h))
            }
        }
        if (constrainToArea) {
            val bounds = item.bounds()
            val x = bounds.x.coerceIn(area.left, (area.right - bounds.w).coerceAtLeast(area.left))
            val y = bounds.y.coerceIn(area.top, (area.bottom - bounds.h).coerceAtLeast(area.top))
            item.translate(x - bounds.x, y - bounds.y)
        }
        item
    }
}
