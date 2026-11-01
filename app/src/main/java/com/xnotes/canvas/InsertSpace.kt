package com.xnotes.canvas

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.history.History
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.GlueFollow
import com.xnotes.core.model.Page
import com.xnotes.core.model.Room
import com.xnotes.core.model.Rooms
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.Renderer
import com.xnotes.core.room.RoomCut
import java.util.Collections
import java.util.IdentityHashMap

/**
 * The Insert space tool's gesture. A pen held over a page shows the line it would cut along. A drag
 * down opens room there, everything past the cut following the pen, and a drag up from inside room
 * closes the strip the pen sweeps (or, on a page turned since, a drag back across a room standing
 * sideways). The page shows the room as the pen goes ([RoomView]) and the edit is made, as one undo
 * step, when it lifts. Room shows as a tinted band while the tool is armed, with a button at its
 * top right that closes all of it.
 */
internal class InsertSpaceGesture(
    private val state: CanvasState,
    private val history: History,
    private val requestRender: () -> Unit,
    /** Repaint [Page]'s cache over a rect: an item went in or out of [lifted]. */
    private val repair: (Page, Rect) -> Unit,
) {
    /** What stays put across the cut, or is placed afresh while it moves: out of the page's cache, drawn live. */
    val lifted: MutableSet<CanvasItem> = Collections.newSetFromMap(IdentityHashMap())

    private enum class Kind { WAITING, OPEN, CLOSE, NOTHING }

    private var kind: Kind? = null
    private var pageIndex = -1
    private var press = Pt.ZERO
    private var pen = Pt.ZERO
    private var room: Room? = null
    private var follow = GlueFollow.NONE
    private var moving: Set<CanvasItem> = emptySet()
    private var hover: Pair<Int, Pt>? = null
    private val autoscroll = TextHandles(state)
    private var penViewport: Pt? = null

    val active: Boolean get() = kind != null

    /** A hovering pen at [content], [viewport] on screen, or null when it left: shows where a press would cut, unless it is over a room's button. */
    fun hoverAt(content: Pt?, viewport: Pt? = null) {
        val was = hover
        val onButton = viewport != null && buttonAt(viewport) != null
        hover = if (onButton) null else content?.let { c -> state.pageIndexAtContent(c)?.let { it to state.toPageSpace(it, c) } }
        if (was != hover) requestRender()
    }

    /** The button that closes [room], on note page [page], centred at [center] in viewport px. */
    class RoomButton(val page: Int, val room: Room, val center: Pt)

    /**
     * Every room's button on the pages in view: at the top right of the part of its band the screen
     * shows, so a band the view cuts still offers one. None while a drag is under way.
     */
    fun buttons(): List<RoomButton> {
        if (kind != null) return emptyList()
        val d = state.devicePxPerDp
        val radius = BUTTON_RADIUS_DP * d
        val inset = BUTTON_INSET_DP * d
        val clear = Rect(state.insetLeft, state.insetTop, state.clearW, state.clearH)
        val visible = state.visibleContentRect()
        val out = ArrayList<RoomButton>()
        for (i in state.drawablePageRange()) {
            val pr = state.pageRects.getOrNull(i) ?: continue
            if (!pr.intersects(visible)) continue
            val page = state.document.pages[i]
            if (page.rooms.isEmpty()) continue
            val cover = state.footprint(page)
            for (room in page.rooms) {
                val band = if (room.sideways) Rect(room.at, cover.top, room.size, cover.h) else Rect(cover.left, room.at, cover.w, room.size)
                val c = intersect(state.fromPageSpaceRect(i, band), pr) ?: continue
                val a = state.contentToViewport(c.topLeft)
                val b = state.contentToViewport(Pt(c.right, c.bottom))
                val shown = intersect(Rect.fromPoints(a, b), clear) ?: continue
                val x = if (shown.w >= 2 * (radius + inset)) shown.right - inset - radius else shown.centerX
                val y = if (shown.h >= 2 * (radius + inset)) shown.top + inset + radius else shown.centerY
                out += RoomButton(i, room, Pt(x, y))
            }
        }
        return out
    }

    /** The button a press at viewport [p] lands on, or null. */
    fun buttonAt(p: Pt): RoomButton? {
        val reach = BUTTON_HIT_DP * state.devicePxPerDp
        return buttons().lastOrNull { it.center.distanceTo(p) <= reach }
    }

    /**
     * Close all of [b]'s room, as one done edit: what lay in it goes, ink and pictures across its
     * edges are cut there, what cannot be cut stays, and the far band comes back. True when it closed.
     */
    fun closeRoom(b: RoomButton): Boolean {
        val page = state.document.pages.getOrNull(b.page) ?: return false
        val room = page.rooms.firstOrNull { it == b.room } ?: return false
        val a = room.at
        val end = room.end
        val fates = RoomCut.fates(page, room.sideways, a, end)
        val movers = page.items.filter { fates[it] == RoomCut.Fate.MOVES }
        val glue = GlueFollow.of(movers, page.items, { it in page.items }, rigid = false)
        // What stays put, and the connectors the edit places afresh, leave the old layers before the
        // edit, so the moment until rebuilt ones land shows them where they end up.
        val keep = RoomCut.anchored(page, room.sideways, a, end) + glue.items
        for (item in keep) if (lifted.add(item)) repair(page, item.paintBounds())
        val view = RoomView(page, room.sideways, a, end, a - end, keep, state.footprint(page))
        val command = RoomCut.close(page, room, a, end)
        if (command == null) {
            drop(page)
            return false
        }
        history.push(command)
        state.document.dirty = true
        lifted.clear()
        state.settleRoom(view)
        state.clampScroll()
        requestRender()
        return true
    }

    /** A press at [content]; false when it is on no page. */
    fun press(content: Pt): Boolean {
        val pi = state.pageIndexAtContent(content) ?: return false
        hover = null
        pageIndex = pi
        press = state.toPageSpace(pi, content)
        pen = press
        kind = Kind.WAITING
        return true
    }

    /** The pen at [content], at [viewport] on screen. */
    fun drag(content: Pt, viewport: Pt) {
        val k = kind ?: return
        val page = state.document.pages.getOrNull(pageIndex) ?: return
        pen = state.toPageSpace(pageIndex, content)
        penViewport = viewport
        if (k == Kind.WAITING) decide(page)
        when (kind) {
            Kind.OPEN, Kind.CLOSE -> {
                show(page)
                // Held near the top or bottom edge the page scrolls on, so room can open further than the screen shows.
                if (state.verticalScroll) autoscroll.autoscroll(viewport) { v -> drag(state.viewportToContent(v), v) }
            }
            else -> Unit
        }
    }

    /** Lift the pen: make the edit the drag showed. True when room opened or closed. */
    fun release(): Boolean {
        val k = kind
        val page = state.document.pages.getOrNull(pageIndex)
        val view = state.roomView
        autoscroll.stopAutoscroll()
        follow.restore()
        val keep = lifted.toList()
        kind = null
        if (page == null || (k != Kind.OPEN && k != Kind.CLOSE) || view == null) {
            drop(page)
            return false
        }
        val command = when (k) {
            Kind.OPEN -> if (openSize() >= minRoom()) RoomCut.open(page, press.y, openSize()) else null
            else -> room?.let { r -> closeStrip(r)?.let { (a, b) -> if (b - a >= MIN_CLOSE_MM * mm()) RoomCut.close(page, r, a, b) else null } }
        }
        if (command == null) {
            drop(page)
            return false
        }
        history.push(command)
        state.document.dirty = true
        lifted.clear()
        follow = GlueFollow.NONE
        state.settleRoom(RoomView(page, view.sideways, view.split, view.resume, view.shift, keep, view.cover))
        state.clampScroll()
        requestRender()
        return true
    }

    /** Give up the drag: nothing changes. */
    fun abort() {
        if (kind == null) return
        autoscroll.stopAutoscroll()
        follow.restore()
        kind = null
        drop(state.document.pages.getOrNull(pageIndex))
    }

    private fun drop(page: Page?) {
        if (state.roomView?.live == true) {
            state.roomView = null
            state.relayout()
        }
        val back = lifted.toList()
        lifted.clear()
        follow = GlueFollow.NONE
        if (page != null) for (item in back) repair(page, item.paintBounds())
        requestRender()
    }

    /** Which way the drag goes, once it has gone far enough to tell: down opens, back across room closes it. */
    private fun decide(page: Page) {
        val slop = InteractionController.TAP_SLOP / state.zoom
        val dx = pen.x - press.x
        val dy = pen.y - press.y
        if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) < slop) return
        val reach = ROOM_SLOP_DP * state.devicePxPerDp / state.zoom
        val down = Rooms.at(page.rooms, press.y, false, reach)
        val across = Rooms.at(page.rooms, press.x, true, reach)
        kind = when {
            kotlin.math.abs(dy) >= kotlin.math.abs(dx) && dy > 0 -> Kind.OPEN
            kotlin.math.abs(dy) >= kotlin.math.abs(dx) && down != null -> { room = down; Kind.CLOSE }
            kotlin.math.abs(dx) > kotlin.math.abs(dy) && dx < 0 && across != null -> { room = across; Kind.CLOSE }
            else -> Kind.NOTHING
        }
        val (a, b) = when (kind) {
            Kind.OPEN -> press.y to press.y
            Kind.CLOSE -> closeStrip(room!!)?.let { it.second to it.second } ?: return
            else -> return
        }
        val sideways = kind == Kind.CLOSE && room!!.sideways
        val fates = RoomCut.fates(page, sideways, a, b)
        moving = Collections.newSetFromMap<CanvasItem>(IdentityHashMap()).also { set -> page.items.filterTo(set) { fates[it] == RoomCut.Fate.MOVES } }
        follow = GlueFollow.of(moving, page.items, { it in page.items }, rigid = false)
        // The connectors it places move from the first frame, so they leave the cache from where they are now.
        for (c in follow.items) if (lifted.add(c)) repair(page, c.paintBounds())
    }

    /** The room the pen has opened so far, down the page. */
    private fun openSize(): Double {
        val page = state.document.pages.getOrNull(pageIndex) ?: return 0.0
        return (pen.y - press.y).coerceIn(0.0, RoomCut.maxOpen(page, state.document))
    }

    /** The strip of [r] the pen has swept back over so far, from where to where along its axis. */
    private fun closeStrip(r: Room): Pair<Double, Double>? {
        val from = if (r.sideways) press.x else press.y
        val to = if (r.sideways) pen.x else pen.y
        val b = from.coerceIn(r.at, r.end)
        val a = to.coerceIn(r.at, b)
        return a to b
    }

    private fun show(page: Page) {
        val r = room
        val (sideways, split, resume, shift) = when (kind) {
            Kind.OPEN -> Quad(false, press.y, press.y, openSize())
            else -> {
                val (a, b) = closeStrip(r ?: return) ?: return
                Quad(r.sideways, a, b, a - b)
            }
        }
        val dx = if (sideways) shift else 0.0
        val dy = if (sideways) 0.0 else shift
        follow.follow(Affine.translateBy(dx, dy)) { if (it in moving) Affine.translateBy(dx, dy) else null }
        val anchored = RoomCut.anchored(page, sideways, split, resume)
        val now = Collections.newSetFromMap<CanvasItem>(IdentityHashMap()).also {
            it.addAll(anchored)
            it.addAll(follow.items)
        }
        val gone = lifted.filter { it !in now }
        val came = now.filter { it !in lifted }
        lifted.removeAll(gone.toSet())
        lifted.addAll(came)
        for (item in gone + came) repair(page, item.paintBounds())
        state.roomView = RoomView(page, sideways, split, resume, shift, now.toList(), state.footprint(page))
        state.relayout()
        requestRender()
    }

    private data class Quad(val sideways: Boolean, val split: Double, val resume: Double, val shift: Double)

    private fun mm(): Double = state.document.dpi / 25.4

    private fun minRoom(): Double = MIN_ROOM_MM * mm()

    /**
     * In content space: the room on the pages in view as tinted bands with dashed edges, the line a
     * hovering pen would cut along, and the cut under the pen while it drags.
     */
    fun drawOverlay(r: Renderer, visible: Rect) {
        val accent = state.palette.accent
        val tint = accent.withAlpha(BAND_ALPHA)
        val edge = Pen(
            accent, 1.2, cosmetic = true, dashed = true,
            dashOn = com.xnotes.core.infinite.OverlayTessellator.DASH_ON_DP * state.devicePxPerDp,
            dashGap = com.xnotes.core.infinite.OverlayTessellator.DASH_GAP_DP * state.devicePxPerDp,
        )
        val view = state.roomView
        for (i in state.drawablePageRange()) {
            val pr = state.pageRects.getOrNull(i) ?: continue
            if (!pr.intersects(visible)) continue
            val page = state.document.pages[i]
            val rooms = if (view != null && view.live && view.page === page) liveRooms(page) else page.rooms
            if (rooms.isEmpty()) continue
            val cover = state.footprint(page).let { f ->
                if (view != null && view.live && view.page === page) {
                    if (view.sideways) Rect(f.x, f.y, f.w + view.shift, f.h) else Rect(f.x, f.y, f.w, f.h + view.shift)
                } else {
                    f
                }
            }
            for (room in rooms) {
                val band = if (room.sideways) Rect(room.at, cover.top, room.size, cover.h) else Rect(cover.left, room.at, cover.w, room.size)
                val box = state.fromPageSpaceRect(i, band).let { b -> intersect(b, pr) } ?: continue
                r.fillRect(box, tint)
                val a = state.fromPageSpaceRect(i, if (room.sideways) Rect(room.at, cover.top, 0.0, cover.h) else Rect(cover.left, room.at, cover.w, 0.0))
                val b = state.fromPageSpaceRect(i, if (room.sideways) Rect(room.end, cover.top, 0.0, cover.h) else Rect(cover.left, room.end, cover.w, 0.0))
                for (line in listOf(a, b)) r.strokePolyline(listOf(line.topLeft, Pt(line.right, line.bottom)), edge)
            }
        }
        val solid = Pen(accent, 1.6, cosmetic = true)
        val at = when {
            kind == Kind.WAITING || kind == Kind.NOTHING -> pageIndex to press
            kind == null -> hover
            else -> null
        } ?: return
        val (pi, p) = at
        if (kind == Kind.NOTHING) return
        val page = state.document.pages.getOrNull(pi) ?: return
        val f = state.footprint(page)
        val line = state.fromPageSpaceRect(pi, Rect(f.left, p.y, f.w, 0.0))
        r.strokePolyline(listOf(line.topLeft, Pt(line.right, line.bottom)), solid)
    }

    /** The rooms the page shows while the drag is live: what the edit will leave on it. */
    private fun liveRooms(page: Page): List<Room> = when (kind) {
        Kind.OPEN -> Rooms.opened(page.rooms, press.y, openSize())
        Kind.CLOSE -> room?.let { r -> closeStrip(r)?.let { (a, b) -> Rooms.closed(page.rooms, r, a, b) } } ?: page.rooms
        else -> page.rooms
    }

    /** In viewport space: each room's close button, and the room's height beside the pen while it drags. */
    fun drawViewport(r: Renderer) {
        drawButtons(r)
        drawLabel(r)
    }

    /** Each room's close button: an accent disc with a cross, ringed in paper so it reads over ink. */
    private fun drawButtons(r: Renderer) {
        val d = state.devicePxPerDp
        val pal = state.palette
        val radius = BUTTON_RADIUS_DP * d
        val arm = BUTTON_CROSS_DP * d
        val cross = Pen(pal.onAccent, BUTTON_LINE_DP * d)
        for (b in buttons()) {
            val c = b.center
            r.fillCircle(c, radius + BUTTON_RING_DP * d, pal.paper)
            r.fillCircle(c, radius, pal.accent)
            r.strokePolyline(listOf(Pt(c.x - arm, c.y - arm), Pt(c.x + arm, c.y + arm)), cross)
            r.strokePolyline(listOf(Pt(c.x - arm, c.y + arm), Pt(c.x + arm, c.y - arm)), cross)
        }
    }

    /** The room's height beside the pen while it drags. */
    private fun drawLabel(r: Renderer) {
        val k = kind
        if (k != Kind.OPEN && k != Kind.CLOSE) return
        val v = penViewport ?: return
        val page = state.document.pages.getOrNull(pageIndex) ?: return
        val height = liveRooms(page).firstOrNull { room ->
            when (k) {
                Kind.OPEN -> !room.sideways && press.y >= room.at - 1e-6 && press.y <= room.end + 1e-6
                else -> this.room?.let { it.sideways == room.sideways && kotlin.math.abs(it.at - room.at) < 1e-6 } == true
            }
        }?.size ?: 0.0
        val text = "%.1f mm".format(height / mm())
        val d = state.devicePxPerDp
        val font = FontSpec(7.0 * d, bold = true)
        val padX = 7.0 * d
        val padY = 4.0 * d
        val textW = text.length * font.pointSize * 1.25
        val textH = font.pointSize * 2.1
        val w = textW + padX * 2
        val h = textH + padY * 2
        val left = (v.x + LABEL_OFFSET_DP * d).coerceIn(2.0, (state.viewportW - w - 2.0).coerceAtLeast(2.0))
        val top = (v.y - LABEL_OFFSET_DP * d - h).coerceIn(2.0, (state.viewportH - h - 2.0).coerceAtLeast(2.0))
        val pal = state.palette
        r.fillRect(Rect(left, top, w, h), pal.menuBg.scaleAlpha(0.92))
        r.strokeRect(Rect(left, top, w, h), Pen(pal.textDim, 1.2, cosmetic = true))
        r.drawText(text, Rect(left + padX, top + padY, textW + 2.0, textH), font, pal.text)
    }

    private fun intersect(a: Rect, b: Rect): Rect? {
        val l = maxOf(a.left, b.left)
        val t = maxOf(a.top, b.top)
        val r = minOf(a.right, b.right)
        val bo = minOf(a.bottom, b.bottom)
        return if (r > l && bo > t) Rect(l, t, r - l, bo - t) else null
    }

    companion object {
        /** A press this close to a room's edge, in dp, counts as inside it. */
        const val ROOM_SLOP_DP = 6.0

        /** A drag that opens less than this makes no room. */
        const val MIN_ROOM_MM = 1.0

        /** Closing less than this closes nothing. */
        const val MIN_CLOSE_MM = 0.1

        /** The tint room shows in while the tool is armed. */
        const val BAND_ALPHA = 36

        /** How far from the pen its label sits, in dp. */
        const val LABEL_OFFSET_DP = 18.0

        /** A room's close button: its disc, its distance in from the band's corner, its cross and the paper ring round it. */
        const val BUTTON_RADIUS_DP = 12.0
        const val BUTTON_INSET_DP = 6.0
        const val BUTTON_CROSS_DP = 4.0
        const val BUTTON_LINE_DP = 2.0
        const val BUTTON_RING_DP = 1.5

        /** How far from a button's centre a press still takes it, in dp. */
        const val BUTTON_HIT_DP = 22.0
    }
}
