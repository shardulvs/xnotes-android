package com.xnotes.core.infinite

import com.xnotes.core.model.ShapeItem
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** A detached source and its rendered geometry. Rigid changes need no text layout or outlining. */
internal class PreparedShapeText(private val source: ShapeItem, val mesh: MeshedItem) {
    fun rigidCopyFor(next: ShapeItem): MeshedItem? {
        val label = source.label ?: return null
        if (label !== next.label || source.labelHidden != next.labelHidden || source.shape.isEndpointShape ||
            source.shape != next.shape || source.path !== next.path || source.flip != next.flip ||
            source.n != next.n || !source.adj.contentEquals(next.adj) || source.labelAt != next.labelAt ||
            source.strokeRgba != next.strokeRgba || source.fillRgba != next.fillRgba ||
            source.strokeWidth != next.strokeWidth || source.neon != next.neon || source.neonStrength != next.neonStrength ||
            source.dashed != next.dashed || source.dashLength != next.dashLength || source.dashGap != next.dashGap ||
            source.headStart != next.headStart || source.headEnd != next.headEnd) return null
        val before = source.box
        val after = next.box
        if (abs(before.w - after.w) > 1e-7 || abs(before.h - after.h) > 1e-7 ||
            (source.start.x <= source.end.x) != (next.start.x <= next.end.x) ||
            (source.start.y <= source.end.y) != (next.start.y <= next.end.y)) return null
        // Tiny rounding changes in translated boxes must not cross a native wrap-width boundary.
        val minimum = label.lineHeight * com.xnotes.core.model.ShapeLabel.MIN_WIDTH_LINES
        if (maxOf(source.localGeometry().textRect.w, minimum).toInt() !=
            maxOf(next.localGeometry().textRect.w, minimum).toInt()) return null
        val cs = cos(next.rotation - source.rotation)
        val sn = sin(next.rotation - source.rotation)
        val from = before.center
        val to = after.center
        val parts = mesh.parts.map { part ->
            val old = part.mesh
            val positions = DoubleArray(old.positions.size)
            val offsets = if (sn == 0.0 && cs == 1.0) old.offsets else DoubleArray(old.offsets.size)
            for (i in old.positions.indices step 2) {
                val x = old.positions[i] - from.x
                val y = old.positions[i + 1] - from.y
                positions[i] = to.x + cs * x - sn * y
                positions[i + 1] = to.y + sn * x + cs * y
                if (offsets !== old.offsets) {
                    offsets[i] = cs * old.offsets[i] - sn * old.offsets[i + 1]
                    offsets[i + 1] = sn * old.offsets[i] + cs * old.offsets[i + 1]
                }
            }
            MeshPart(MeshData(positions, offsets, old.indices, old.colors), part.color, part.pass, part.glow)
        }
        return MeshedItem(parts, next.paintBounds(), mesh.minHalfWidth)
    }
}
