package com.xnotes.core.infinite

import com.xnotes.core.model.CutEdge

/**
 * Triangles cut down by the edges room cut an item along, so ink cut on a note keeps its straight
 * edges on a canvas. Each triangle is clipped edge by edge and fanned back into triangles, every
 * vertex channel carried along the cut: position, line offset and colour.
 */
object MeshClip {

    fun clip(mesh: MeshData, cuts: List<CutEdge>): MeshData {
        if (cuts.isEmpty() || mesh.isEmpty) return mesh
        val pos = ArrayList<Double>(mesh.positions.size)
        val off = ArrayList<Double>(mesh.offsets.size)
        val col = if (mesh.colors != null) ArrayList<Int>() else null
        val idx = ArrayList<Int>(mesh.indices.size)
        val tri = IntArray(3)
        for (t in 0 until mesh.triangleCount) {
            for (k in 0 until 3) tri[k] = mesh.indices[3 * t + k]
            var poly = tri.map { Vertex.of(mesh, it) }
            for (c in cuts) {
                if (poly.isEmpty()) break
                poly = cut(poly, c)
            }
            if (poly.size < 3) continue
            val base = pos.size / 2
            for (v in poly) {
                pos += v.x
                pos += v.y
                off += v.ox
                off += v.oy
                col?.add(v.color)
            }
            for (k in 1 until poly.size - 1) {
                idx += base
                idx += base + k
                idx += base + k + 1
            }
        }
        if (idx.isEmpty()) return MeshData.EMPTY
        return MeshData(pos.toDoubleArray(), off.toDoubleArray(), idx.toIntArray(), col?.toIntArray())
    }

    private class Vertex(val x: Double, val y: Double, val ox: Double, val oy: Double, val color: Int) {
        fun toward(o: Vertex, t: Double) = Vertex(
            x + (o.x - x) * t, y + (o.y - y) * t, ox + (o.ox - ox) * t, oy + (o.oy - oy) * t, lerpColor(color, o.color, t),
        )

        companion object {
            fun of(mesh: MeshData, i: Int) = Vertex(
                mesh.positions[2 * i], mesh.positions[2 * i + 1],
                mesh.offsets.getOrElse(2 * i) { 0.0 }, mesh.offsets.getOrElse(2 * i + 1) { 0.0 },
                mesh.colors?.get(i) ?: 0,
            )
        }
    }

    private fun cut(poly: List<Vertex>, c: CutEdge): List<Vertex> {
        val out = ArrayList<Vertex>(poly.size + 1)
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            val da = c.past(a.x, a.y)
            val db = c.past(b.x, b.y)
            if (da <= 0.0) out += a
            if ((da < 0.0 && db > 0.0) || (da > 0.0 && db < 0.0)) out += a.toward(b, da / (da - db))
        }
        return out
    }

    private fun lerpColor(a: Int, b: Int, t: Double): Int {
        if (a == b) return a
        fun ch(shift: Int): Int {
            val x = (a ushr shift) and 0xff
            val y = (b ushr shift) and 0xff
            return ((x + (y - x) * t).toInt().coerceIn(0, 255)) shl shift
        }
        return ch(24) or ch(16) or ch(8) or ch(0)
    }
}
