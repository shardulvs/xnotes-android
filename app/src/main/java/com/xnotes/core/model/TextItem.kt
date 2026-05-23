package com.xnotes.core.model

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.Renderer
import com.xnotes.core.pal.TextFlags
import com.xnotes.core.pal.TextMeasurer

/**
 * A wrapped plain-text box (spec 02 §5.3). Width is fixed (the wrap width);
 * height always follows the text. The [measurer] lays out text identically for
 * bounds and painting.
 */
class TextItem(
    var pos: Pt,
    var width: Double = DEFAULT_WIDTH,
    var text: String = "",
    var rgba: Rgba = DEFAULT_COLOR,
    var pointSize: Double = DEFAULT_POINT_SIZE,
    private val measurer: TextMeasurer,
) : CanvasItem, Resizable {

    override val kind = KIND
    override val resizable = true

    val font get() = FontSpec(pointSize)

    override fun bounds(): Rect {
        // Empty boxes still have one line of height (one-space probe).
        val probe = text.ifEmpty { " " }
        val measured = measurer.measure(probe, font, width, FLAGS)
        return Rect(pos.x, pos.y, width, measured.h)
    }

    override fun paint(r: Renderer) {
        if (text.isEmpty()) return
        r.drawText(text, bounds(), font, rgba, FLAGS)
    }

    override fun translate(dx: Double, dy: Double) {
        pos = Pt(pos.x + dx, pos.y + dy)
    }

    override fun contains(p: Pt): Boolean = bounds().contains(p)

    override fun centroid(): Pt = bounds().center

    override fun intersectsCircle(cx: Double, cy: Double, radius: Double): Boolean =
        bounds().distanceTo(Pt(cx, cy)) <= radius

    override fun geometry(): GeoHandle = TextHandle(pos, width)

    override fun setGeometry(handle: GeoHandle) {
        if (handle is TextHandle) {
            pos = handle.pos
            width = handle.width
        }
    }

    companion object {
        const val KIND = "text"
        const val DEFAULT_WIDTH = 300.0
        const val DEFAULT_POINT_SIZE = 13.0
        val DEFAULT_COLOR = Rgba(236, 236, 236, 255)
        val FLAGS = TextFlags(wordWrap = true, alignLeft = true, alignTop = true)
    }
}
