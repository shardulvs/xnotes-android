package com.xnotes.core.room

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.history.Command
import com.xnotes.core.history.CompositeCommand
import com.xnotes.core.history.MoveItems
import com.xnotes.core.history.ReplacePageItems
import com.xnotes.core.history.ReshapePages
import com.xnotes.core.history.TransformItems
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.CutEdge
import com.xnotes.core.model.CutEdges
import com.xnotes.core.model.Document
import com.xnotes.core.model.GeometrySnapshot
import com.xnotes.core.model.GlueFollow
import com.xnotes.core.model.ImageCrop
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Page
import com.xnotes.core.model.PageShape
import com.xnotes.core.model.Room
import com.xnotes.core.model.Rooms
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.insets
import java.util.IdentityHashMap

/**
 * What opening and closing room does to a page (Insert space). Room opens down the page at a cut:
 * what lies wholly past it moves on by the room, ink and upright pictures it crosses are cut in two
 * along it and each piece goes with its side, and anything else it crosses (shapes, tape, text,
 * recordings, a picture at a free angle, a group whatever it holds) stays where it is, the far band
 * sliding away from under it. Closing takes a strip of a room out: what lies wholly in the strip
 * goes, ink and pictures across its edges are cut there and lose what lay inside, what cannot be cut
 * stays, and the far band comes back. Typed text is the flow's, and the flow never moves.
 */
object RoomCut {

    /** Past an edge by less than this, in content px, is not past it. */
    private const val EPS = 0.05

    /** The page height a PDF can take: 200 in. */
    private const val MAX_POINTS = 14_400.0

    /** How much room [page] of [doc] can still take before it outgrows a PDF page. */
    fun maxOpen(page: Page, doc: Document): Double {
        val ins = page.insets(doc)
        val most = MAX_POINTS * doc.dpi / 72.0 - ins.top - ins.bottom
        return (most - page.height).coerceAtLeast(0.0)
    }

    /** Open [size] of room down [page] at [at] (page space, from its top), as one done edit; null when nothing opens. */
    fun open(page: Page, at: Double, size: Double): Command? {
        if (size <= Rooms.EPS) return null
        return apply(page, false, at, at, size, Rooms.opened(page.rooms, at, size))
    }

    /** Close the strip [from] to [to] of [room] on [page], clamped to it, as one done edit; null when nothing closes. */
    fun close(page: Page, room: Room, from: Double, to: Double): Command? {
        val a = maxOf(minOf(from, to), room.at)
        val b = minOf(maxOf(from, to), room.end)
        if (b - a <= Rooms.EPS) return null
        return apply(page, room.sideways, a, b, a - b, Rooms.closed(page.rooms, room, a, b))
    }

    /** What happens to one item, or one group whose members go together. */
    enum class Fate { STAYS, MOVES, CUT, GOES }

    /**
     * Each of [page]'s items' fate when room opens at [a] (with [b] the same) or the strip [a] to
     * [b] closes, along its axis ([sideways] for across the page). The preview asks this every
     * frame, so it reads the items and changes nothing.
     */
    fun fates(page: Page, sideways: Boolean, a: Double, b: Double): Map<CanvasItem, Fate> {
        val out = IdentityHashMap<CanvasItem, Fate>(page.items.size * 2)
        for (unit in units(page.items)) {
            val (lo, hi) = extent(unit, sideways)
            val fate = when {
                hi <= a + EPS -> Fate.STAYS
                lo >= b - EPS -> Fate.MOVES
                b > a && lo >= a - EPS && hi <= b + EPS -> Fate.GOES
                unit.size == 1 && cuttable(unit[0], sideways) -> Fate.CUT
                else -> Fate.STAYS
            }
            for (item in unit) out[item] = fate
        }
        return out
    }

    /** The items that stay put though the cut runs across them: what [fates] keeps that lies past [a]. */
    fun anchored(page: Page, sideways: Boolean, a: Double, b: Double): List<CanvasItem> {
        val fates = fates(page, sideways, a, b)
        return page.items.filter { fates[it] == Fate.STAYS && extent(listOf(it), sideways).second > a + EPS }
    }

    private fun apply(page: Page, sideways: Boolean, a: Double, b: Double, shift: Double, rooms: List<Room>): Command {
        val fates = fates(page, sideways, a, b)
        val before = page.items.toList()
        val dx = if (sideways) shift else 0.0
        val dy = if (sideways) 0.0 else shift
        val moving = before.filter { fates[it] == Fate.MOVES }
        // What a cut changes in place: a picture cut down, or ink that keeps only its far side and so moves.
        val reshaped = before.filter { fates[it] == Fate.CUT }
        // Built before anything changes, as it notes where every connector glued to what changes started.
        // Not rigid: a connector glued to a picture cut in place and to something that moves follows both.
        val glue = GlueFollow.of(moving + reshaped, before, { it in page.items }, rigid = false)
        val changed = ArrayList<CanvasItem>()
        val changedBefore = ArrayList<GeometrySnapshot>()
        val after = ArrayList<CanvasItem>(before.size + 4)
        for (item in before) {
            when (fates[item]) {
                Fate.GOES -> Unit
                Fate.CUT -> {
                    val snap = item.snapshotGeometry()
                    val kept = when (item) {
                        is Stroke -> cutStroke(item, sideways, a, b, dx, dy)
                        is ImageItem -> cutPicture(item, sideways, a, b, dx, dy)
                        else -> Kept(item, null)
                    }
                    kept.self?.let {
                        changed += it
                        changedBefore += snap
                        after += it
                    }
                    kept.piece?.let { after += it }
                }
                else -> after += item
            }
        }
        page.items.clear()
        page.items.addAll(after)
        for (item in moving) item.translate(dx, dy)
        glue.follow(Affine.translateBy(dx, dy))
        val placed = glue.items.toSet()
        changed += glue.items
        changedBefore += glue.startSnapshots
        val shapeBefore = PageShape.bare(page)
        page.rooms = rooms
        if (sideways) page.width += shift else page.height += shift
        val commands = ArrayList<Command>()
        commands += ReplacePageItems(page, before, after)
        if (changed.isNotEmpty()) commands += TransformItems(changed, changedBefore, changed.map { it.snapshotGeometry() })
        val moved = moving.filter { it !in placed }
        if (moved.isNotEmpty()) commands += MoveItems(moved, dx, dy)
        commands += ReshapePages(listOf(page), listOf(shapeBefore), listOf(PageShape.bare(page)))
        return CompositeCommand(commands)
    }

    /** What a cut left of an item: the item itself, changed, if any of it still shows, and a new piece on the far side, moved on. */
    private class Kept(val self: CanvasItem?, val piece: CanvasItem?)

    private fun cutStroke(s: Stroke, sideways: Boolean, a: Double, b: Double, dx: Double, dy: Double): Kept {
        val (lo, hi) = extent(listOf(s), sideways)
        val keepNear = lo < a - EPS
        val keepFar = hi > b + EPS
        val mid = s.bounds().let { if (sideways) it.centerY else it.centerX }
        val farCuts = CutEdges.with(s.cuts, CutEdge.along(b, sideways, keepAfter = true, near = mid))
        if (!keepNear) {
            // Only the far side shows: the stroke itself becomes that piece, rather than going and coming back.
            if (!keepFar) return Kept(null, null)
            s.cuts = farCuts
            s.translate(dx, dy)
            return Kept(s, null)
        }
        val far = if (keepFar) {
            Stroke(s).also {
                it.cuts = farCuts
                it.locked = s.locked
                it.sync = s.sync
                it.translate(dx, dy)
            }
        } else {
            null
        }
        s.cuts = CutEdges.with(s.cuts, CutEdge.along(a, sideways, keepAfter = false, near = mid))
        return Kept(s, far)
    }

    private fun cutPicture(p: ImageItem, sideways: Boolean, a: Double, b: Double, dx: Double, dy: Double): Kept {
        val n = if (sideways) Pt(1.0, 0.0) else Pt(0.0, 1.0)
        val c = p.rect.center
        fun on(at: Double) = if (sideways) Pt(at, c.y) else Pt(c.x, at)
        val (lo, hi) = extent(listOf(p), sideways)
        val nearPart = if (lo < a - EPS) ImageCrop.split(p, on(a), n)?.first else null
        val farPart = if (hi > b + EPS) ImageCrop.split(p, on(b), n)?.second else null
        if (nearPart == null && farPart == null) return Kept(if (lo < a - EPS || hi > b + EPS) p else null, null)
        val far = farPart?.let { f ->
            ImageItem(p.image, ImageCrop.croppedRect(p, f), p.orientation, p.angle, p.flip, ImageCrop.partOf(p, f)).also {
                it.locked = p.locked
                it.sync = p.sync
                it.translate(dx, dy)
            }
        }
        if (nearPart == null) {
            p.rect = far!!.rect
            p.part = far.part
            return Kept(p, null)
        }
        val part = ImageCrop.partOf(p, nearPart)
        p.rect = ImageCrop.croppedRect(p, nearPart)
        p.part = part
        return Kept(p, far)
    }

    /** Items that go together: each group whose members share a page, and every other item alone. */
    private fun units(items: List<CanvasItem>): List<List<CanvasItem>> {
        val counts = HashMap<Int, Int>()
        for (item in items) item.group?.let { counts[it] = (counts[it] ?: 0) + 1 }
        val groups = HashMap<Int, MutableList<CanvasItem>>()
        val out = ArrayList<List<CanvasItem>>()
        for (item in items) {
            val g = item.group
            if (g != null && (counts[g] ?: 0) >= 2) {
                val members = groups.getOrPut(g) { ArrayList<CanvasItem>().also { out += it } }
                members += item
            } else {
                out += listOf(item)
            }
        }
        return out
    }

    /** Whether a cut across an item can split it: ink, and a picture standing at a quarter turn, which a straight cut follows. */
    private fun cuttable(item: CanvasItem, sideways: Boolean): Boolean = when (item) {
        is Stroke -> true
        is ImageItem -> ImageCrop.split(item, item.rect.center, if (sideways) Pt(1.0, 0.0) else Pt(0.0, 1.0)) != null
        else -> false
    }

    /** How far along its axis what [unit] shows reaches, from where to where. */
    private fun extent(unit: List<CanvasItem>, sideways: Boolean): Pair<Double, Double> {
        var lo = Double.POSITIVE_INFINITY
        var hi = Double.NEGATIVE_INFINITY
        for (item in unit) {
            // Uncut ink's box is its discs' own reach, exact; only cut ink needs its discs walked.
            val (l, h) = if (item is Stroke && item.cuts.isNotEmpty()) inkExtent(item, sideways) else item.bounds().let { if (sideways) it.left to it.right else it.top to it.bottom }
            lo = minOf(lo, l)
            hi = maxOf(hi, h)
        }
        return lo to hi
    }

    /** How far the ink [s] shows reaches along the axis: its discs that show through its cuts, each to its own edge. */
    private fun inkExtent(s: Stroke, sideways: Boolean): Pair<Double, Double> {
        val g = s.geometry()
        var lo = Double.POSITIVE_INFINITY
        var hi = Double.NEGATIVE_INFINITY
        for (i in 0 until g.pointCount) {
            val x = g.cx(i)
            val y = g.cy(i)
            val h = g.hw(i)
            if (s.cuts.isNotEmpty() && !CutEdges.keeps(s.cuts, x, y, h)) continue
            val along = if (sideways) x else y
            lo = minOf(lo, along - h)
            hi = maxOf(hi, along + h)
        }
        if (lo > hi) return s.bounds().let { if (sideways) it.left to it.right else it.top to it.bottom }
        // A disc cut by an edge reaches no further than the edge.
        for (c in s.cuts) {
            if (sideways && kotlin.math.abs(c.nx) > 0.999) { if (c.nx > 0) hi = minOf(hi, c.x) else lo = maxOf(lo, c.x) }
            if (!sideways && kotlin.math.abs(c.ny) > 0.999) { if (c.ny > 0) hi = minOf(hi, c.y) else lo = maxOf(lo, c.y) }
        }
        return lo to hi
    }
}
