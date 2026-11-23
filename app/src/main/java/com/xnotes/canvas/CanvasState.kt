package com.xnotes.canvas

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.PageInsets
import com.xnotes.core.audio.InkFade
import com.xnotes.core.model.PageLayers
import com.xnotes.core.model.PageTemplates
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Rooms
import com.xnotes.core.model.paintPaperStrips
import com.xnotes.core.model.paperStrips
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.TextMarkup
import com.xnotes.core.model.insets
import com.xnotes.core.model.resolvedPageColor
import com.xnotes.core.model.resolvedTemplate
import com.xnotes.core.pal.RasterSurface
import com.xnotes.core.pal.Renderer
import com.xnotes.core.pal.SurfaceFactory
import com.xnotes.core.stroke.StrokeGeometry
import com.xnotes.core.tools.Tool
import com.xnotes.ui.theme.Palette
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Highlighters are composited live over the finished page each frame (so their MULTIPLY
 * blend darkens against paper, PDF background and ink alike), never baked into the
 * transparent ink cache that sits above the background — see [CanvasView]. All other
 * ink is cached.
 */
internal fun CanvasItem.isHighlighterInk(): Boolean = this is Stroke && this.tool == Tool.HIGHLIGHTER

/** One band of a page layer: its pixels, and how current they are. Main thread only once published. */
class Band(val surface: RasterSurface) {
    /** Out of date: drawn until the rebuild asked for lands, never taken as built. */
    var stale = false

    /** A background band's last markup edit held (see [CanvasState.rebakeBackground]). */
    var seq: Int = 0
}

/**
 * A page layer as cached: the [grid] its raster is cut on, for the footprint and resolution the page
 * had when it was begun, and the bands of it rasterized so far. A margin edit or a zoom gives the
 * page another grid, so a new layer is begun, and this one is drawn where that has nothing yet.
 */
class PageLayer(val grid: BandGrid) {
    val bands = arrayOfNulls<Band>(grid.count)

    /** For each band, the frame that last wanted it built (see [CanvasState.prefetchAndPrune]). */
    val wantedAt = IntArray(grid.count)
    val cover: Rect get() = grid.cover
    val res: Double get() = grid.res

    fun built(): Int = bands.count { it != null }

    /** Give band [k]'s pixels back, if it is built: it has left the caches. */
    fun free(k: Int) {
        bands[k]?.surface?.recycle()
        bands[k] = null
    }

    /** [free] every band: the layer has left the caches. */
    fun free() {
        for (k in bands.indices) free(k)
    }

    fun bytes(): Long {
        var n = 0L
        for (k in bands.indices) if (bands[k] != null) n += grid.bytes(k)
        return n
    }
}

/**
 * A cached highlighter ribbon: its opaque bitmap, the resolution + geometry + opaque colour it
 * was built from (any of which changing rebuilds it), and the page-local content rect it covers
 * (where the frame blits it). See [CanvasState.highlighterCacheFor].
 */
class HighlighterCacheEntry(
    val surface: RasterSurface,
    val res: Double,
    val geom: StrokeGeometry,
    val opaque: Rgba,
    val cover: Rect,
    val cuts: List<com.xnotes.core.model.CutEdge> = emptyList(),
)

/**
 * Room being opened or closed on [page], drawn before the page's caches hold it. While the pen drags
 * ([live]) the page is untouched, and its layout and drawing take the room on; once the edit is
 * made, the caches built before it are drawn the same way until their rebuilds land. Page space
 * throughout, down the page or across it when [sideways]: what lies before [split] stays, what lay
 * from [resume] on shows [shift] further along, and room opened between them is paper. [anchored]
 * stays put across the cut and is drawn live, [fills] under the ink and the rest over it. [cover]
 * is the footprint the old caches were built for.
 */
class RoomView(
    val page: Page,
    val sideways: Boolean,
    val split: Double,
    val resume: Double,
    val shift: Double,
    val anchored: List<CanvasItem>,
    val cover: Rect,
    val live: Boolean = true,
    /** The colour room opening here takes, or null for the page's own. */
    val color: Rgba? = null,
) {
    /** Anchored items drawn under the ink: dropped colour, which lies under the lines it fills. */
    val fills: List<CanvasItem> get() = anchored.filter { it is com.xnotes.core.model.ShapeItem && it.isFill }

    /** Anchored items drawn over the ink. */
    val over: List<CanvasItem> get() = anchored.filter { !(it is com.xnotes.core.model.ShapeItem && it.isFill) }
}

/** How a freshly opened document's initial view is chosen (see [CanvasState.establishInitialView]). */
sealed class InitialView {
    /** Fit the page width and start at the first page. */
    object FitWidth : InitialView()

    /** Reapply a remembered zoom + scroll. */
    class Restore(val zoom: Double, val scrollX: Double, val scrollY: Double) : InitialView()
}

/**
 * Owns the view-side state of the canvas (spec 05): document layout in content
 * space, the viewport (scroll + zoom), the content<->viewport transforms, page
 * navigation and the per-page raster cache.
 *
 * Pure of any Android View dependency so it can be unit-/instrument-tested; the
 * [CanvasView] drives it.
 */
class CanvasState(
    var document: Document,
    private val surfaceFactory: SurfaceFactory,
    var palette: Palette,
) {
    var zoom: Double = 1.0
    var scrollX: Double = 0.0
    var scrollY: Double = 0.0
    var viewportW: Int = 0
    var viewportH: Int = 0
    var renderScale: Double = 1.0

    /**
     * Viewport px a floating toolbar covers along each edge. The pages still run under it, but the
     * scroll range and the fits only count the clear area inside, so nothing is stuck beneath it.
     */
    var insetLeft: Double = 0.0
    var insetTop: Double = 0.0
    var insetRight: Double = 0.0
    var insetBottom: Double = 0.0
    val clearW: Double get() = viewportW - insetLeft - insetRight
    val clearH: Double get() = viewportH - insetTop - insetBottom

    /** While true the note is only read, so the view leaves out the affordances that edit it. */
    var readOnly: Boolean = false

    /** The view's top or left edge about to move by a docked bar's thickness, so the pages can stay put on screen. */
    private var edgeMove: EdgeMove? = null

    private class EdgeMove(val top: Boolean, val px: Double, val at: Long)

    /** The next resize moves the view's top (else its left) edge by about [px]: scroll with it so the pages keep their place. */
    fun expectEdgeMove(top: Boolean, px: Double) {
        edgeMove = EdgeMove(top, px, System.nanoTime() / 1_000_000L)
    }

    /**
     * A resize from [oldW] x [oldH] to the viewport now: when it is the edge move expected just
     * before, scroll by the size change so what shows stays where it was. Any other resize drops it.
     */
    fun followEdgeMove(oldW: Int, oldH: Int) {
        val m = edgeMove ?: return
        edgeMove = null
        if (System.nanoTime() / 1_000_000L - m.at > EDGE_MOVE_MS) return
        val dw = viewportW - oldW
        val dh = viewportH - oldH
        if (m.top && dw == 0 && abs(abs(dh) - m.px) <= EDGE_MOVE_SLOP) scrollY -= dh
        if (!m.top && dh == 0 && abs(abs(dw) - m.px) <= EDGE_MOVE_SLOP) scrollX -= dw
    }

    /** The middle of the clear area, where zoom steps anchor. */
    fun clearCenter(): Pt = Pt(insetLeft + clearW / 2.0, insetTop + clearH / 2.0)

    /** User-set zoom limits (preferences); every zoom path clamps into [minZoom]..[maxZoom]. */
    var minZoom: Double = MIN_ZOOM
    var maxZoom: Double = MAX_ZOOM

    /** Bytes the page caches may hold, as the host has it; unset or 0, they are sized from the viewport ([CacheBudget.automatic]). */
    var cacheLimit: (() -> Long)? = null

    fun cacheBudget(): Long = cacheLimit?.invoke()?.takeIf { it > 0 } ?: CacheBudget.automatic(viewportW, viewportH)

    /**
     * Elastic overscroll past the document's **bottom** end (viewport px, already damped). Positive
     * lifts the whole content up, opening a gap below the last page where the "pull to add page"
     * affordance is drawn (see [CanvasView]). Purely visual — it never changes [scrollX]/[scrollY] —
     * and is sprung back to 0 on release by the interaction layer. The interaction layer owns the
     * gesture; this field just lets [origin] and the draw loop see the live stretch.
     */
    var overscrollY: Double = 0.0

    /** Device pixels per dp (display density), set by the view. Lets the speed pen
     *  measure gesture speed in zoom- and device-independent dp (see [InteractionController]). */
    var devicePxPerDp: Double = 1.0
    var pageColorOverride: Rgba? = null
    var didInitialFit: Boolean = false

    /** The view to install on the next layout for a just-opened document (null ⇒ fit width);
     *  consumed by [establishInitialView] once the viewport is sized. */
    var pendingInitialView: InitialView? = null

    /** Horizontal margin on each side of the page column (0 ⇒ fit-width fills the viewport). */
    var sideMargin: Double = MARGIN

    /** Whether the hairline outline around each page is drawn (a user preference). */
    var pageBorders: Boolean = true

    /** Gap (content px) between pages — inside a spread, between rows, and between paginated
     *  rows alike — equal to [sideMargin] so spacing always matches the margin preference. */
    val pageGap: Double get() = sideMargin

    /** Margin (content px) above and below the content: [sideMargin] too, so a page is framed evenly. */
    val vertMargin: Double get() = sideMargin

    /** How pages group into layout rows (Single / Double / Cover); see [rowRanges]. */
    var viewingMode: ViewingMode = ViewingMode.SINGLE

    /**
     * True = continuous vertical scrolling (rows stack top-down). False = paginated: rows
     * lay out as a horizontal strip, the scroll window is clamped to [currentRow]'s span,
     * and the interaction layer flips between rows after a deliberate edge swipe.
     */
    var verticalScroll: Boolean = true

    /** The row the paginated view is on (its scroll window); moved by flips and [goToPage]. */
    var currentRow: Int = 0

    /**
     * Paginated horizontal drag (viewport px, positive toward the next row).
     * [origin] moves the current row with the finger; the renderer draws the
     * adjacent row using [linkedNeighbourOffset] until release settles the transition.
     */
    var flipOffsetX: Double = 0.0
    /**
     * During a linked-page drag, the adjacent row is drawn beside the current row.
     * This is visual only: hit testing remains limited to [currentRow] until release.
     */
    fun linkedNeighbourRow(): Int? {
        if (verticalScroll || flipOffsetX == 0.0) return null
        val target = currentRow + if (flipOffsetX > 0.0) 1 else -1
        return target.takeIf { it in rowRanges().indices }
    }

    /** Extra distance required for the adjacent row to enter from beyond the viewport. */
    fun linkedPageTravel(rowIndex: Int): Double {
        val rows = rowRanges()
        if (rows.isEmpty() || rowIndex !in rows.indices) return viewportW.toDouble()
        val neighbour = rowBounds(rows[rowIndex])
        val offset = linkedNeighbourOffset(rowIndex)
        // At the end of this drag the neighbour must occupy its regular resting position.
        // Derive travel from the very same geometry used to draw it to avoid snapping.
        val travel = offset.x + rowTargetScroll(rowIndex).x - scrollX
        return kotlin.math.abs(travel)
    }

    /**
     * Keep at least 100 dp between the two page edges, while ensuring the entering
     * page starts outside the viewport. The page-to-page gap is measured on screen,
     * so zoom and panning cannot cause the pages to overlap.
     */
    fun linkedNeighbourOffset(rowIndex: Int): Pt {
        val rows = rowRanges()
        val current = rowBounds(rows[currentRow.coerceIn(0, rows.lastIndex)])
        val neighbour = rowBounds(rows[rowIndex])
        val gapPx = 100.0 * devicePxPerDp
        val currentLeft = current.left * zoom - scrollX
        val currentRight = current.right * zoom - scrollX
        val targetEdge = if (rowIndex > currentRow) {
            max(viewportW.toDouble(), currentRight + gapPx)
        } else {
            min(0.0, currentLeft - gapPx)
        }
        val neighbourEdge = if (rowIndex > currentRow) neighbour.left else neighbour.right
        return Pt(
            targetEdge - (neighbourEdge * zoom - scrollX),
            scrollY - flipLandingScrollY(rowIndex), // drawn where the turn will land it
        )
    }

    /** The scroll Y a turn to [row] lands at: where the row rests, or the same reading height between two tall pages. */
    private fun flipLandingScrollY(row: Int): Double {
        val rows = rowRanges()
        val before = rowBounds(rows[currentRow.coerceIn(0, rows.lastIndex)])
        val after = rowBounds(rows[row])
        val sy = if (before.h * zoom >= clearH && after.h * zoom >= clearH) {
            scrollY + (after.top - before.top) * zoom
        } else {
            rowTargetScroll(row).y
        }
        return rowClampedScroll(row, scrollX, sy).y
    }

    /** True only while an incoming paginated row or cancelled edge-pull is animating. */
    var pageFlipAnimating: Boolean = false

    /**
     * Whole-file clockwise page rotation (0/90/180/270), applied by the view: pages lay
     * out with their rotated footprints ([displayW]/[displayH]) and their page-space
     * content is painted through [applyPageTransform]. Model/page space never rotates —
     * caches, items and hit geometry stay in page space and map through
     * [toPageSpace]/[fromPageSpace] at the display boundary.
     */
    var rotationDeg: Int = 0

    /**
     * [page]'s resolved margins in content px (page override -> document -> none). A margin grows
     * the paper *outside* the page's content box without moving anything on it, so page space keeps
     * its coordinates and simply starts negative on a margined edge — see [footprint].
     */
    fun insets(page: Page): PageInsets = page.insets(document)

    /** Page width including its margins (unrotated), and room still being dragged open on it. */
    fun outerW(page: Page): Double = insets(page).let { it.left + page.width + it.right } + roomGrowth(page, true)

    /** Page height including its margins (unrotated), and room still being dragged open on it. */
    fun outerH(page: Page): Double = insets(page).let { it.top + page.height + it.bottom } + roomGrowth(page, false)

    /** Room opening or closing under the pen, or waiting for the caches to catch up with one (see [RoomView]). */
    var roomView: RoomView? = null

    /**
     * Paints, in page space, the paper the old layers of [RoomView.page] leave bare: on a PDF page
     * the room, ruled like its margins, and on a plain one the page it grew by. Installed by the host,
     * which holds the templates.
     */
    var paintRoomPaper: ((view: RoomView, r: Renderer) -> Unit)? = null

    /** How much the room still under the pen adds to [page] along one axis: none once the page itself has it. */
    private fun roomGrowth(page: Page, sideways: Boolean): Double {
        val v = roomView ?: return 0.0
        return if (v.live && v.page === page && v.sideways == sideways) v.shift else 0.0
    }

    /**
     * The ink layer to draw room [v] through: the one built for the page as it was. Null with none
     * to draw, and once the page's own layer has every band [seen] (page space) shows.
     */
    fun roomInk(page: Page, v: RoomView, seen: Rect): PageLayer? = roomLayer(ink, page, v, seen)

    /** [roomInk] for the background layer. */
    fun roomBackground(page: Page, v: RoomView, seen: Rect): PageLayer? =
        if (hasPageBackground(page)) roomLayer(bg, page, v, seen) else null

    private fun roomLayer(store: Layers, page: Page, v: RoomView, seen: Rect): PageLayer? {
        val now = store.now[page]
        if (now != null && now.cover == v.cover) return now.takeIf { it.built() > 0 }
        if (now != null && covers(now, seen)) return null
        return store.before[page]?.takeIf { it.cover == v.cover && it.built() > 0 }
    }

    private fun covers(layer: PageLayer, seen: Rect): Boolean {
        for (k in layer.grid.meeting(seen)) if (layer.bands[k] == null) return false
        return true
    }

    /** True when [page]'s own ink layer has a band built. */
    fun hasInkBuilt(page: Page): Boolean = (ink.now[page]?.built() ?: 0) > 0

    /** True when [page]'s own background layer has a band built. */
    fun hasBackgroundBuilt(page: Page): Boolean = hasPageBackground(page) && (bg.now[page]?.built() ?: 0) > 0

    /**
     * The room [view] drew has been made: keep its old caches showing it until rebuilds that hold it
     * land, which the changed footprint schedules, and lay the note out round the page as it now is.
     */
    fun settleRoom(view: RoomView) {
        roomView = RoomView(view.page, view.sideways, view.split, view.resume, view.shift, view.anchored, view.cover, live = false, color = view.color)
        ink.gen++
        bg.gen++
        sharpGen++
        relayout()
    }

    /** [page] as the room under the pen shows it: grown or shrunk by it, its rooms as the edit will leave them. */
    fun shownPage(page: Page): Page {
        val v = roomView ?: return page
        if (!v.live || v.page !== page || v.shift == 0.0) return page
        val rooms = if (v.shift > 0.0) {
            Rooms.opened(page.rooms, v.split, v.shift, v.sideways, v.color)
        } else {
            Rooms.at(page.rooms, (v.split + v.resume) / 2, v.sideways)?.let { Rooms.closed(page.rooms, it, v.split, v.resume) } ?: page.rooms
        }
        return Page(
            page.width + if (v.sideways) v.shift else 0.0, page.height + if (v.sideways) 0.0 else v.shift,
            pdfPage = page.pdfPage, style = page.style, margins = page.margins, pdfTurn = page.pdfTurn, rooms = rooms,
        ).also { it.cover = page.cover }
    }

    /** Fill [page]'s paper over [pr]: its own colour, then its margins and room in theirs, laid out as [shown]. */
    fun fillPaper(r: Renderer, page: Page, pr: Rect, shown: Page = shownPage(page)) {
        val paper = paperColor(page)
        r.fillRect(pr, paper)
        val strips = shown.paperStrips(document, paper)
        if (strips.isEmpty()) return
        r.save()
        r.clipRect(pr)
        r.translate(pr.left, pr.top)
        applyPageTransform(r, page)
        paintPaperStrips(r, strips)
        r.restore()
    }

    /** True once [page]'s caches hold what a settled [roomView] drew wherever it shows, so it can go. */
    fun roomSettled(page: Page): Boolean {
        val v = roomView ?: return true
        val seen = seenOf(page) ?: return true
        return roomInk(page, v, seen) == null && roomBackground(page, v, seen) == null
    }

    /** The part of [page] on screen, in its page space, or null when none of it is. */
    fun seenOf(page: Page): Rect? {
        val pr = pageRects.getOrNull(indexOfPage[page] ?: return null) ?: return null
        val visible = visibleContentRect()
        if (!pr.intersects(visible)) return null
        return displayRectToPage(page, visible.translate(-pr.left, -pr.top))
    }

    /** The whole paper in page space, margins included: the rect every page-space painter fills. */
    fun footprint(page: Page): Rect {
        val i = insets(page)
        return Rect(-i.left, -i.top, i.left + page.width + i.right, i.top + page.height + i.bottom)
    }

    /** On-screen (display) width of [page]'s footprint under the current rotation. */
    fun displayW(page: Page): Double = if (rotationDeg == 90 || rotationDeg == 270) outerH(page) else outerW(page)

    /** On-screen (display) height of [page]'s footprint under the current rotation. */
    fun displayH(page: Page): Double = if (rotationDeg == 90 || rotationDeg == 270) outerW(page) else outerH(page)

    /** Map a page-local display point (relative to the page rect's top-left) into page space. */
    fun displayToPage(page: Page, p: Pt): Pt {
        val i = insets(page)
        val q = when (rotationDeg) {
            90 -> Pt(p.y, outerH(page) - p.x)
            180 -> Pt(outerW(page) - p.x, outerH(page) - p.y)
            270 -> Pt(outerW(page) - p.y, p.x)
            else -> p
        }
        return if (i.isZero) q else Pt(q.x - i.left, q.y - i.top)
    }

    /** Map a page-space point into page-local display coordinates. */
    fun pageToDisplay(page: Page, p: Pt): Pt {
        val i = insets(page)
        val q = if (i.isZero) p else Pt(p.x + i.left, p.y + i.top)
        return when (rotationDeg) {
            90 -> Pt(outerH(page) - q.y, q.x)
            180 -> Pt(outerW(page) - q.x, outerH(page) - q.y)
            270 -> Pt(q.y, outerW(page) - q.x)
            else -> q
        }
    }

    /** Map a page-local display rect into page space (axis-aligned; corners re-normalize). */
    fun displayRectToPage(page: Page, r: Rect): Rect =
        Rect.fromPoints(displayToPage(page, Pt(r.left, r.top)), displayToPage(page, Pt(r.right, r.bottom)))

    /** A content-space point mapped into page [i]'s page space. */
    fun toPageSpace(i: Int, content: Pt): Pt {
        val pr = pageRects[i]
        return displayToPage(document.pages[i], Pt(content.x - pr.left, content.y - pr.top))
    }

    /** A page-space point of page [i] mapped into content space. */
    fun fromPageSpace(i: Int, p: Pt): Pt {
        val pr = pageRects[i]
        val d = pageToDisplay(document.pages[i], p)
        return Pt(pr.left + d.x, pr.top + d.y)
    }

    /** An axis-aligned content rect mapped into page [i]'s page space (re-normalized). */
    fun toPageSpaceRect(i: Int, r: Rect): Rect =
        Rect.fromPoints(toPageSpace(i, Pt(r.left, r.top)), toPageSpace(i, Pt(r.right, r.bottom)))

    /** An axis-aligned page-space rect of page [i] mapped into content space (re-normalized). */
    fun fromPageSpaceRect(i: Int, r: Rect): Rect =
        Rect.fromPoints(fromPageSpace(i, Pt(r.left, r.top)), fromPageSpace(i, Pt(r.right, r.bottom)))

    /** Rotate a content-space vector (an offset/delta) into page space. */
    fun vectorToPageSpace(v: Pt): Pt = when (rotationDeg) {
        90 -> Pt(v.y, -v.x)
        180 -> Pt(-v.x, -v.y)
        270 -> Pt(-v.y, v.x)
        else -> v
    }

    /**
     * Take [r] — already translated to a page rect's top-left — into page space: the view rotation,
     * then the page's margins, so page-space painting lands inside the paper's display footprint.
     * The inverse of [displayToPage].
     */
    fun applyPageTransform(r: Renderer, page: Page) {
        when (rotationDeg) {
            90 -> { r.translate(outerH(page), 0.0); r.rotate(90.0) }
            180 -> { r.translate(outerW(page), outerH(page)); r.rotate(180.0) }
            270 -> { r.translate(0.0, outerW(page)); r.rotate(270.0) }
        }
        val i = insets(page)
        if (!i.isZero) r.translate(i.left, i.top)
    }

    /** [pageToDisplay] as an affine (page space → page-local display coords). */
    private fun displayAffine(page: Page): Affine {
        val rot = when (rotationDeg) {
            90 -> Affine(0.0, 1.0, -1.0, 0.0, outerH(page), 0.0)
            180 -> Affine(-1.0, 0.0, 0.0, -1.0, outerW(page), outerH(page))
            270 -> Affine(0.0, -1.0, 1.0, 0.0, 0.0, outerW(page))
            else -> Affine.IDENTITY
        }
        val i = insets(page)
        return if (i.isZero) rot else rot.compose(Affine(1.0, 0.0, 0.0, 1.0, i.left, i.top))
    }

    /** [displayToPage] as an affine (page-local display coords → page space). */
    private fun pageAffine(page: Page): Affine {
        val rot = when (rotationDeg) {
            90 -> Affine(0.0, -1.0, 1.0, 0.0, 0.0, outerH(page))
            180 -> Affine(-1.0, 0.0, 0.0, -1.0, outerW(page), outerH(page))
            270 -> Affine(0.0, 1.0, -1.0, 0.0, outerW(page), 0.0)
            else -> Affine.IDENTITY
        }
        val i = insets(page)
        return if (i.isZero) rot else Affine(1.0, 0.0, 0.0, 1.0, -i.left, -i.top).compose(rot)
    }

    /**
     * Express a content-space affine [world] in page [i]'s page space, so it can be baked
     * into item geometry: conjugates by the page's display map (translation + rotation + margins).
     */
    fun affineToPageSpace(i: Int, world: Affine): Affine {
        val local = world.translatedFrame(pageRects[i].topLeft)
        val page = document.pages[i]
        if (rotationDeg == 0 && insets(page).isZero) return local
        return pageAffine(page).compose(local.compose(displayAffine(page)))
    }

    /** While true (a pinch that has moved the zoom) caches are blitted stale-scaled
     *  instead of rebuilt every frame; they rebuild at the final resolution when
     *  the gesture ends. */
    var zoomingInProgress: Boolean = false

    /** When true, zoom is fixed (pinch pans only, zoom buttons/fit are no-ops). */
    var zoomLocked: Boolean = false
    /** The View menu toggle: pinches never snap to a fit; vertical scroll lets any page edge reach the clear centre, paginated frees up and down. */
    var freeMovement: Boolean = false

    /**
     * True while the view is sitting at fit-to-width. Set when a pinch snaps to fit-width (or
     * [fitWidth]/[resetViewToFitWidth] land there), cleared whenever the zoom moves off it. Drives
     * [reflowFitWidthForResize] so the page re-fits when the usable width changes (e.g. the sidebar
     * opens) — independent of [zoomLocked], which only freezes user zoom gestures.
     */
    var fitWidthActive: Boolean = false

    /** Like [fitWidthActive] but for the paginated fit-to-height magnet ([fitHeightZoom]). */
    var fitHeightActive: Boolean = false

    /** Items excluded from the cache (lifted for selection/editing); set by the interaction layer. */
    var isLiftedItem: (CanvasItem) -> Boolean = { false }

    /**
     * Optional page-background painter (PDF / template). [region] is the page-local content rect
     * to render — the whole page for the page cache/thumbnails, or just the visible sub-rect for
     * the sharp viewport — so the painter can rasterize only that slice at full resolution.
     */
    var paintPageBackground: ((page: Page, renderer: Renderer, res: Double, region: Rect) -> Unit)? = null

    /**
     * Optional flow-text painter (the document-wide typed text). It paints onto the
     * transparent ink layer *before* the page items, so ink annotates over text while
     * text sits over the page background. [region] is the page-local rect being
     * painted, like [paintPageBackground]. Runs on cache threads: the installed hook
     * must read only an immutable published layout snapshot, never the live model.
     */
    var paintFlow: ((page: Page, renderer: Renderer, region: Rect) -> Unit)? = null

    /**
     * Optional painter for the flow's formulas, which [paintFlow] leaves out of every cache:
     * [CanvasView] paints them over the background each frame, so one that finishes setting
     * appears without its page being rebuilt. Like [paintFlow] it reads only the published layout.
     */
    var paintMath: ((page: Page, renderer: Renderer, region: Rect) -> Unit)? = null

    /** Optional: queue the formulas of a page about to scroll in, to be drawn at [scale] screen px per page px. */
    var prefetchMath: ((page: Page, scale: Double) -> Unit)? = null

    /**
     * True while a flow-text caret session is live: screen ink caches build WITHOUT
     * the flow and [CanvasView] paints it immediate-mode each frame instead, so a
     * keystroke never waits for a cache rebuild. Session start/end must invalidate
     * the flow-bearing pages so the layers swap cleanly.
     */
    var flowLifted: Boolean = false

    /**
     * Per-page caches, split into two layers so an ink edit never re-rasterizes the
     * (costly) page background: [ink] holds the transparent **ink** layer (the
     * strokes/shapes/text), [bg] holds the rendered **background** layer (PDF/template),
     * which a page without one never gets. Both are blitted — background then ink — over
     * the paper fill. A layer is cut into bands ([BandGrid]): a long page is never one huge
     * bitmap, and an edit touches only the bands it lands on. A band that leaves the caches
     * gives its pixels back at once ([PageLayer.free]) rather than when the collector comes
     * round, so what is held never runs far past the budget. Main thread only.
     */
    private class Layers(val bandPx: Int, val gutter: Int) {
        /** The layer each page draws from, at the footprint and resolution it shows at. */
        val now = HashMap<Page, PageLayer>()

        /** The layer a page had before its footprint or resolution moved, drawn where [now] has no band yet. */
        val before = HashMap<Page, PageLayer>()

        /** Bands with a build in flight, so none is queued twice. */
        val pending = HashSet<BandKey>()

        /** Where a band was painted on while its build was in flight, to paint again on what the build brings. */
        val meanwhile = HashMap<BandKey, Rect>()

        /**
         * Bumped by every invalidation. An in-flight async build captures the value at schedule
         * time and its result is discarded if the generation has since moved on, so an edit (or
         * zoom) that lands mid-build never gets overwritten by the stale surface.
         */
        var gen = 0

        /** Let go of [page]'s layers. */
        fun drop(page: Page) {
            now.remove(page)?.free()
            before.remove(page)?.free()
        }

        fun clear() {
            for (layer in now.values) layer.free()
            for (layer in before.values) layer.free()
            now.clear()
            before.clear()
        }
    }

    private data class BandKey(val page: Page, val index: Int)

    private val ink = Layers(INK_BAND_PX, INK_GUTTER_PX)
    private val bg = Layers(BG_BAND_PX, BG_GUTTER_PX)
    private val stores = arrayOf(ink, bg)

    /** A band the view wants built: how far it is from the screen (viewport px) and what it costs. */
    private class Want(val store: Layers, val page: Page, val layer: PageLayer, val index: Int, val distance: Double, val bytes: Long)

    /** The bands wanted as of the last frame, nearest first, and the number that marks them in [PageLayer.wantedAt]. */
    private var wanted: List<Want> = emptyList()
    private var stamp = 0

    /** The pages those bands are on: the ones whose ribbons and highlighter bitmaps are kept. */
    private var near: Set<Page> = emptySet()

    /** True while a build ahead of the screen is in flight; they run one at a time, so a band the screen needs never queues behind many. */
    private var prefetching = false
    private var pumping = false

    /** Where each page is in the document, as of the last [relayout]. */
    private val indexOfPage = HashMap<Page, Int>()

    /**
     * Per-highlighter opaque-ribbon bitmaps. Highlighters can't be baked into the ink layer (they
     * MULTIPLY against the live page, not the transparent ink layer above it), so the draw loop
     * composites them every frame — but re-tessellating a self-overlapping ribbon each frame stalls
     * scrolling. Each entry holds the stroke's ribbon rendered once at full opacity; the frame just
     * blits it with the stroke's alpha + MULTIPLY (see [highlighterCacheFor] and [CanvasView]). Keyed
     * by stroke identity (model items compare by identity); rebuilt when the stroke's geometry
     * instance changes (any edit nulls it), its colour changes, or the resolution moves. Touched on
     * the main thread only and pruned to the visible pages' highlighters each frame.
     */
    private val hlCaches = HashMap<Stroke, HighlighterCacheEntry>()

    /**
     * Pages whose strokes have built ribbon geometry ([Stroke.geometry]), so eviction can walk only
     * the pages that leave the band instead of the whole document every frame.
     *
     * Geometry is only ever built as a side effect of rasterizing a page, so its lifetime tracks the
     * page cache's: a stroke holds its ribbon exactly while its page holds a raster. Left unbounded
     * it is the second biggest thing in a dense note (54 MB on a 27-page file, ~30 bytes per sample)
     * and it never came back, because nothing dropped it on scroll.
     */
    private val geomPages = HashSet<Page>()

    /**
     * Off-UI-thread plumbing for the *non-blocking* cache path ([cacheForOrSchedule] /
     * [backgroundForOrSchedule]). The canvas calls those from `onDraw`; when a freshly
     * scrolled-in page has no current-resolution cache yet, the heavy rasterization runs
     * on [runAsync] (a background thread) and the finished surface is published back on
     * [postToMain], which then asks for a repaint via [onCacheReady]. This keeps the
     * scroll frame from stalling while a new page is rasterized — the page just appears a
     * frame or two later. The defaults run inline so unit tests stay synchronous.
     */
    var runAsync: (work: () -> Unit) -> Unit = { it() }
    var postToMain: (work: () -> Unit) -> Unit = { it() }
    var onCacheReady: (() -> Unit)? = null

    // --- sharp viewport (past the resolution cap) ---

    // Two layers, like the page cache, so an erase can clear ink in a region without
    // disturbing the (PDF/paper) background underneath: [base] holds window bg + paper +
    // background + labels, [ink] holds just the strokes on a transparent surface.
    private class SharpFrame(
        val base: RasterSurface,
        val ink: RasterSurface,
        val sx: Double,
        val sy: Double,
        val z: Double,
        val gen: Int,
        val row: Int,
        /** The last markup edit its base layer holds, like [Band.seq]. */
        val seq: Int,
    )

    private var sharpFrame: SharpFrame? = null
    private var pendingSharp = false

    /**
     * Edits landed while a sharp render was in flight (its item snapshot predates them):
     * page + page-local dirty rect, replayed onto the fresh frame at publish so ink drawn
     * (or erased) during the build doesn't vanish (or reappear) when the sharp layer settles.
     */
    private val pendingSharpEdits = ArrayList<Pair<Page, Rect>>()

    /** Bumped on any content/layout change so a stale sharp viewport is discarded. */
    private var sharpGen = 0

    private class SharpPageSnap(
        val page: Page,
        val pr: Rect,
        val items: InkItems,
        val region: Rect,
        val index: Int,
    )

    var pageRects: List<Rect> = emptyList()
        private set
    var contentW: Double = 2 * MARGIN
        private set
    var contentH: Double = 2 * MARGIN
        private set

    // --- layout ---

    /**
     * Pages grouped into layout rows by [viewingMode], as consecutive page-index ranges:
     * Single = one page per row, Double = pairs (1-2, 3-4, …), Cover = the first page
     * alone, then pairs (2-3, 4-5, …) so facing pages line up like a book.
     */
    fun rowRanges(): List<IntRange> {
        val n = document.pages.size
        if (n == 0) return emptyList()
        return when (viewingMode) {
            ViewingMode.SINGLE -> (0 until n).map { it..it }
            ViewingMode.DOUBLE -> (0 until n step 2).map { it..min(it + 1, n - 1) }
            ViewingMode.COVER -> buildList {
                add(0..0)
                var i = 1
                while (i < n) {
                    add(i..min(i + 1, n - 1))
                    i += 2
                }
            }
        }
    }

    /** The layout row containing [pageIndex]. */
    fun rowOf(pageIndex: Int): IntRange {
        val last = document.pages.lastIndex
        val i = pageIndex.coerceIn(0, last)
        return when (viewingMode) {
            ViewingMode.SINGLE -> i..i
            ViewingMode.DOUBLE -> (i - i % 2).let { it..min(it + 1, last) }
            ViewingMode.COVER ->
                if (i == 0) 0..0 else (if (i % 2 == 1) i else i - 1).let { it..min(it + 1, last) }
        }
    }

    fun relayout() {
        sharpGen++ // page layout / viewport size changed: the sharp viewport must re-render
        val pages = document.pages
        if (pages.isEmpty()) {
            pageRects = emptyList()
            indexOfPage.clear()
            contentW = 2 * sideMargin
            contentH = 2 * vertMargin
            currentRow = 0
            return
        }
        // Rects hold the pages' display footprints — rotation swaps their width/height. A row's
        // pages sit side by side, vertically centred within their row so facing pages align.
        val rows = rowRanges()
        val rowWidths = rows.map { r -> r.sumOf { displayW(pages[it]) } + (r.last - r.first) * pageGap }
        val rowHeights = rows.map { r -> r.maxOf { displayH(pages[it]) } }
        val rects = arrayOfNulls<Rect>(pages.size)
        if (verticalScroll) {
            // Rows stack top-down, centred against the widest row.
            val maxW = rowWidths.max()
            var y = vertMargin
            for ((ri, row) in rows.withIndex()) {
                var x = sideMargin + (maxW - rowWidths[ri]) / 2.0
                for (i in row) {
                    rects[i] = Rect(x, y + (rowHeights[ri] - displayH(pages[i])) / 2.0, displayW(pages[i]), displayH(pages[i]))
                    x += displayW(pages[i]) + pageGap
                }
                y += rowHeights[ri] + pageGap
            }
            contentW = maxW + 2 * sideMargin
            contentH = (y - pageGap) + vertMargin
        } else {
            // Paginated: rows run left-to-right in one strip, each top-aligned below the margin.
            val maxH = rowHeights.max()
            var x = sideMargin
            for ((ri, row) in rows.withIndex()) {
                var rx = x
                for (i in row) {
                    rects[i] = Rect(rx, vertMargin + (rowHeights[ri] - displayH(pages[i])) / 2.0, displayW(pages[i]), displayH(pages[i]))
                    rx += displayW(pages[i]) + pageGap
                }
                x += rowWidths[ri] + pageGap
            }
            contentW = (x - pageGap) + sideMargin
            contentH = maxH + 2 * vertMargin
        }
        pageRects = rects.map { it!! }
        indexOfPage.clear()
        for (i in pages.indices) indexOfPage[pages[i]] = i
        currentRow = currentRow.coerceIn(0, rows.lastIndex)
        clampScroll()
    }

    /** Index (into [rowRanges]) of the row containing [pageIndex]. */
    fun rowIndexOf(pageIndex: Int): Int {
        val first = rowOf(pageIndex).first
        return rowRanges().indexOfFirst { it.first == first }.coerceAtLeast(0)
    }

    /** The union of a row's page rects (content space). */
    fun rowBounds(row: IntRange): Rect {
        var acc: Rect? = null
        for (i in row) pageRects.getOrNull(i)?.let { acc = acc?.union(it) ?: it }
        return acc ?: Rect(0.0, 0.0, 1.0, 1.0)
    }

    /** The paginated scroll target for [rowIndex]: keep the zoom, land centred if the row fits, else at its top. */
    fun rowTargetScroll(rowIndex: Int): Pt {
        val rows = rowRanges()
        if (rows.isEmpty()) return Pt(0.0, 0.0)
        val rb = rowBounds(rows[rowIndex.coerceIn(0, rows.lastIndex)])
        val minX = (rb.left - sideMargin) * zoom - insetLeft
        val maxX = (rb.right + sideMargin) * zoom - viewportW + insetRight
        val sx = if (maxX < minX) (minX + maxX) / 2.0 else minX
        val sy = if (rb.h * zoom < clearH) {
            rb.centerY * zoom - clearCenter().y
        } else {
            (rb.top * zoom - TOP_GAP - insetTop).coerceAtLeast(-insetTop)
        }
        return Pt(sx, sy)
    }

    /** True when the paginated view rests against its row's [next]-side edge (a flip is armed). */
    fun atRowEdge(next: Boolean): Boolean {
        val rows = rowRanges()
        if (rows.isEmpty()) return false
        val rb = rowBounds(rows[currentRow.coerceIn(0, rows.lastIndex)])
        val minX = (rb.left - sideMargin) * zoom - insetLeft
        val maxX = (rb.right + sideMargin) * zoom - viewportW + insetRight
        if (maxX < minX) return true // the row fits: both edges at once
        return if (next) scrollX >= maxX - 1.0 else scrollX <= minX + 1.0
    }

    /** Re-derive [currentRow] from the scroll position (a restored view / fresh layout). */
    fun syncCurrentRowToScroll() {
        if (verticalScroll) return
        val rows = rowRanges()
        if (rows.isEmpty()) {
            currentRow = 0
            return
        }
        val cx = (scrollX + clearCenter().x) / zoom
        var best = 0
        var bestD = Double.MAX_VALUE
        rows.forEachIndexed { i, row ->
            val d = abs(rowBounds(row).centerX - cx)
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        currentRow = best
    }

    // --- transforms ---

    fun origin(): Pt {
        val cw = contentW * zoom
        val ch = contentH * zoom
        // Paginated (or free movement): the scroll always wins; the whole strip must never be
        // force-centred (a spread or a taller page would push a page off centre, and free movement
        // pans even when the content fits inside the viewport).
        val ox = (if (!verticalScroll || freeMovement || cw >= clearW) -scrollX else insetLeft + (clearW - cw) / 2.0) - flipOffsetX
        val oy = (if (!verticalScroll || freeMovement || ch >= clearH) -scrollY else insetTop + (clearH - ch) / 2.0) - overscrollY
        return Pt(ox, oy)
    }

    fun contentToViewport(p: Pt): Pt {
        val o = origin()
        return Pt(p.x * zoom + o.x, p.y * zoom + o.y)
    }

    fun viewportToContent(p: Pt): Pt {
        val o = origin()
        return Pt((p.x - o.x) / zoom, (p.y - o.y) / zoom)
    }

    fun visibleContentRect(): Rect =
        Rect.fromPoints(viewportToContent(Pt(0.0, 0.0)), viewportToContent(Pt(viewportW.toDouble(), viewportH.toDouble())))

    private fun freeVertical(): Boolean = freeMovement && verticalScroll && pageRects.isNotEmpty()

    // A floating bar lets the scroll run past the content's edges by as much as it covers;
    // free vertical movement lets any page edge reach the middle of the clear area.
    // At deep zoom a margin outgrows half the screen, so free movement keeps whichever reach is longer.
    fun minScrollX(): Double =
        if (freeVertical()) min(-insetLeft, sideMargin * zoom - clearCenter().x) else -insetLeft
    fun minScrollY(): Double =
        if (freeVertical()) min(-insetTop, vertMargin * zoom - clearCenter().y) else -insetTop
    fun maxScrollX(): Double {
        val edge = ceil(contentW * zoom - viewportW + insetRight)
        return if (freeVertical()) max(edge, (contentW - sideMargin) * zoom - clearCenter().x) else max(-insetLeft, edge)
    }
    fun maxScrollY(): Double {
        val edge = ceil(contentH * zoom - viewportH + insetBottom)
        return if (freeVertical()) max(edge, (contentH - vertMargin) * zoom - clearCenter().y) else max(-insetTop, edge)
    }

    fun clampScroll() {
        if (!verticalScroll && pageRects.isNotEmpty()) {
            // Paginated: the scroll window is the current row's span (plus the side margins);
            // a row narrower/shorter than the viewport pins centred/top instead of drifting.
            val c = rowClampedScroll(currentRow, scrollX, scrollY)
            scrollX = c.x
            scrollY = c.y
            return
        }
        scrollX = scrollX.coerceIn(minScrollX(), maxScrollX())
        scrollY = scrollY.coerceIn(minScrollY(), maxScrollY())
    }

    /** ([sx], [sy]) clamped into [rowIndex]'s paginated scroll window, without mutating state. */
    fun rowClampedScroll(rowIndex: Int, sx: Double, sy: Double): Pt {
        val rows = rowRanges()
        if (rows.isEmpty()) return Pt(sx, sy)
        val rb = rowBounds(rows[rowIndex.coerceIn(0, rows.lastIndex)])
        val minX = (rb.left - sideMargin) * zoom - insetLeft
        val maxX = (rb.right + sideMargin) * zoom - viewportW + insetRight
        val cx = if (maxX < minX) (minX + maxX) / 2.0 else sx.coerceIn(minX, maxX)
        if (!freeMovement) {
            // A page that fits sits centred and can't move, and a tall one stops at its margins.
            val minY = (rb.top - vertMargin) * zoom - insetTop
            val maxY = (rb.bottom + vertMargin) * zoom - viewportH + insetBottom
            return Pt(cx, if (maxY < minY) (minY + maxY) / 2.0 else sy.coerceIn(minY, maxY))
        }
        // Free movement: a page moves until its top or bottom edge reaches the middle of the clear area.
        val mid = clearCenter().y
        return Pt(cx, sy.coerceIn(rb.top * zoom - mid, rb.bottom * zoom - mid))
    }

    fun scrollBy(dx: Double, dy: Double) {
        scrollX += dx
        scrollY += dy
        clampScroll()
    }

    /** Scroll so content rect [c] is on screen, [margin] viewport px clear of the edges and any toolbar; not at all if it is. */
    fun scrollIntoView(c: Rect, margin: Double) {
        val tl = contentToViewport(Pt(c.left, c.top))
        val br = contentToViewport(Pt(c.right, c.bottom))
        val left = insetLeft + margin
        val right = viewportW - insetRight - margin
        val top = insetTop + margin
        val bottom = viewportH - insetBottom - margin
        if (right <= left || bottom <= top) return
        val dx = if (tl.x < left || br.x > right) (tl.x + br.x) / 2 - (left + right) / 2 else 0.0
        val dy = if (tl.y < top || br.y > bottom) (tl.y + br.y) / 2 - (top + bottom) / 2 else 0.0
        if (dx != 0.0 || dy != 0.0) scrollBy(dx, dy)
    }

    // --- pages & navigation ---

    // --- page style resolution (current page -> document "all pages" -> global default) ---

    /** Resolved paper colour for [page], or null to fall back to the theme paper (see [paperColor]). */
    fun effectivePageColor(page: Page): Rgba? = page.resolvedPageColor(document, pageColorOverride)

    /** Resolved page template key for [page] ([PageTemplates.NONE] when nothing in the chain sets one). */
    fun effectiveTemplate(page: Page): String = page.resolvedTemplate(document)

    fun paperColor(page: Page): Rgba = effectivePageColor(page) ?: palette.paper

    /**
     * The pages the view may draw (and touch). Vertical mode shows everything the viewport
     * reaches. The paginated view shows exactly one row at any moment: [currentRow]
     * (zooming out must not creep the neighbouring rows into view). An edge pull just
     * reveals empty background, never the neighbour.
     */
    fun drawablePageRange(): IntRange {
        val last = document.pages.lastIndex
        if (verticalScroll) return 0..last
        val rows = rowRanges()
        if (rows.isEmpty()) return 0..last
        return rows[currentRow.coerceIn(0, rows.lastIndex)]
    }

    /** Index of the page whose rect contains a content-space point, or null. Hidden paginated
     *  neighbours never hit, so ink/erases/taps can't land on a page that isn't shown. */
    fun pageIndexAtContent(p: Pt): Int? {
        val drawable = drawablePageRange()
        for (i in pageRects.indices) if (i in drawable && pageRects[i].contains(p)) return i
        return null
    }

    /**
     * The page under [content], or the nearest shown one (drags cross the gaps between pages),
     * with the point in its page space clamped to the page.
     */
    fun pagePointAt(content: Pt): Pair<Int, Pt>? {
        var pi = pageIndexAtContent(content)
        if (pi == null) {
            val drawable = drawablePageRange()
            var bestD = Double.MAX_VALUE
            for (i in pageRects.indices) {
                if (i !in drawable) continue // never target a hidden paginated neighbour
                val d = pageRects[i].distanceTo(content)
                if (d < bestD) {
                    bestD = d
                    pi = i
                }
            }
        }
        val index = pi ?: return null
        if (pageRects.getOrNull(index) == null) return null
        val page = document.pages.getOrNull(index) ?: return null
        val p = toPageSpace(index, content)
        return index to Pt(p.x.coerceIn(0.0, page.width), p.y.coerceIn(0.0, page.height))
    }

    /** The current page (spec 05 §4): contains the viewport vertical centre, biased by half a gap.
     *  Paginated mode reads it from [currentRow] instead (the page under the viewport centre). */
    fun currentPageIndex(): Int {
        if (pageRects.isEmpty()) return 0
        if (!verticalScroll) {
            val rows = rowRanges()
            val row = rows[currentRow.coerceIn(0, rows.lastIndex)]
            val cx = viewportToContent(Pt(viewportW / 2.0, viewportH / 2.0)).x
            for (i in row) if (pageRects[i].right + pageGap / 2.0 > cx) return i
            return row.last
        }
        val centerY = viewportToContent(Pt(viewportW / 2.0, viewportH / 2.0)).y
        for (i in pageRects.indices) {
            if (pageRects[i].bottom + pageGap / 2.0 > centerY) return i
        }
        return pageRects.size - 1
    }

    /**
     * True when the bottom edge of the last page is at or above the viewport's bottom — i.e. the
     * document's end is genuinely on screen. The pull-past-the-end (add-page) elastic keys off this
     * rather than inferring "at the end" from a rejected downward scroll, so it can never arm while
     * there is still document below the fold — even when a transient bad scroll/layout state right
     * after a document opens would make the bottom scroll clamp fire. Computed through the same
     * [origin]/transform that draws the frame, so it can never disagree with what the user sees.
     */
    fun isDocumentEndVisible(): Boolean {
        if (pageRects.isEmpty()) return false
        // The last row's lowest edge is contentH - vertMargin whatever the viewing mode.
        return contentToViewport(Pt(0.0, contentH - vertMargin)).y <= viewportH - insetBottom + 1.0
    }

    /** The first page of the row after the one containing [from] (page-nav stepping). */
    fun nextPageIndex(from: Int): Int = min(rowOf(from).last + 1, document.pages.lastIndex.coerceAtLeast(0))

    /** The first page of the row before the one containing [from] (page-nav stepping). */
    fun prevPageIndex(from: Int): Int = rowOf(max(rowOf(from).first - 1, 0)).first

    fun goToPage(index: Int) {
        if (pageRects.isEmpty()) return
        val i = index.coerceIn(0, pageRects.size - 1)
        if (!verticalScroll) {
            // Paginated: jump the scroll window to the page's row and land at its top.
            currentRow = rowIndexOf(i)
            flipOffsetX = 0.0
            val t = rowTargetScroll(currentRow)
            scrollX = t.x
            scrollY = t.y
            clampScroll()
            return
        }
        // Navigate to the page's whole layout row, so a Double/Cover spread centres as one.
        val row = rowOf(i)
        val top = row.minOf { pageRects[it].top }
        val centerX = (row.minOf { pageRects[it].left } + row.maxOf { pageRects[it].right }) / 2.0
        // Scroll so the page top clears the toolbar with a small gap, so no part of the
        // page is hidden behind the chrome (or centre when the whole document fits vertically).
        scrollY = if (freeMovement && contentH * zoom < clearH) {
            (contentH / 2.0) * zoom - clearCenter().y
        } else {
            (top * zoom - TOP_GAP - insetTop).coerceAtLeast(-insetTop)
        }
        scrollX = centerX * zoom - clearCenter().x
        clampScroll()
    }

    /** After a relayout, scroll by as much as [page] moved from [was], so it stays where it showed. */
    fun keepPageInPlace(page: Page, was: Rect) {
        val i = document.pages.indexOfFirst { it === page }
        val now = pageRects.getOrNull(i) ?: return
        scrollX += (now.left - was.left) * zoom
        scrollY += (now.top - was.top) * zoom
        if (!verticalScroll) currentRow = rowIndexOf(i)
        clampScroll()
    }

    /** End a paginated turn on [row], at the height the slide showed it at. */
    fun landFlip(row: Int) {
        val rows = rowRanges()
        if (row !in rows.indices) return
        val landing = flipLandingScrollY(row)
        goToPage(rows[row].first)
        scrollY = landing
        clampScroll()
        clearSharpViewport()
    }

    // --- zoom ---

    fun setZoomAnchored(focusViewport: Pt, newZoom: Double) {
        if (zoomLocked) return
        val z = newZoom.coerceIn(minZoom, maxZoom)
        if (abs(z - zoom) < 1e-9) return
        fitWidthActive = false // an explicit zoom step leaves the fit magnets
        fitHeightActive = false
        val anchor = viewportToContent(focusViewport)
        zoom = z
        scrollX = anchor.x * z - focusViewport.x
        scrollY = anchor.y * z - focusViewport.y
        invalidateCachesForZoom()
        clampScroll()
    }

    /** The scroll, zoom and fit magnets at one moment, for [restoreView]. */
    class ViewSnapshot(val zoom: Double, val scrollX: Double, val scrollY: Double, val fitWidth: Boolean, val fitHeight: Boolean)

    fun snapshotView(): ViewSnapshot = ViewSnapshot(zoom, scrollX, scrollY, fitWidthActive, fitHeightActive)

    /** Put the view back as [v] found it, rebuilding the page caches if the zoom moved. */
    fun restoreView(v: ViewSnapshot) {
        if (abs(v.zoom - zoom) >= 1e-9) {
            zoom = v.zoom
            invalidateCachesForZoom()
        }
        fitWidthActive = v.fitWidth
        fitHeightActive = v.fitHeight
        scrollX = v.scrollX
        scrollY = v.scrollY
        clampScroll()
    }

    fun zoomByStep(zoomIn: Boolean) {
        val factor = if (zoomIn) ZOOM_STEP else 1.0 / ZOOM_STEP
        setZoomAnchored(clearCenter(), zoom * factor)
    }

    /** Pull the live zoom back inside [minZoom]..[maxZoom] after the limits changed, anchored at
     *  the viewport centre. Deliberately ignores the zoom lock: a limit edit must always win. */
    fun clampZoomToLimits() {
        val z = zoom.coerceIn(minZoom, maxZoom)
        if (abs(z - zoom) < 1e-9) return
        if (viewportW == 0 || viewportH == 0) {
            zoom = z // not laid out yet; the initial view will place the scroll
            return
        }
        val focus = clearCenter()
        val anchor = viewportToContent(focus)
        zoom = z
        fitWidthActive = false
        fitHeightActive = false
        scrollX = anchor.x * z - focus.x
        scrollY = anchor.y * z - focus.y
        invalidateCachesForZoom()
        clampScroll()
    }

    fun fitWidth() {
        if (zoomLocked || contentW <= 0.0 || viewportW == 0) return
        val cur = currentPageIndex()
        zoom = fitWidthZoom()
        fitWidthActive = true
        fitHeightActive = false
        invalidateCachesForZoom()
        goToPage(cur)
    }

    fun fitHeight() {
        val pages = document.pages
        if (zoomLocked || pages.isEmpty() || viewportH == 0) return
        val cur = currentPageIndex()
        val magnet = fitHeightZoom() // paginated: land exactly where a pinch's height magnet does
        zoom = if (magnet > 0.0) magnet else ((clearH - 60.0) / displayH(pages[cur])).coerceIn(minZoom, maxZoom)
        fitWidthActive = false
        fitHeightActive = magnet > 0.0
        invalidateCachesForZoom()
        goToPage(cur)
    }

    fun fitPage() {
        val pages = document.pages
        if (zoomLocked || pages.isEmpty() || viewportW == 0 || viewportH == 0) return
        val cur = currentPageIndex()
        val page = pages[cur]
        val w = displayW(page) + 2 * sideMargin
        val h = displayH(page) + 2 * vertMargin
        if (w <= 0.0 || h <= 0.0) return
        zoom = min(clearW / w, clearH / h).coerceIn(minZoom, maxZoom)
        fitWidthActive = false
        fitHeightActive = false
        invalidateCachesForZoom()
        goToPage(cur)
    }

    // --- fit-to-width snapping (spec 05) ---

    /** The zoom at which the page column exactly fills the viewport width, or 0 if not measurable.
     *  Paginated mode fits the current row (the strip as a whole is never the fit target). */
    fun fitWidthZoom(): Double {
        if (viewportW == 0) return 0.0
        if (!verticalScroll) {
            val rows = rowRanges()
            if (rows.isEmpty()) return 0.0
            val w = rowBounds(rows[currentRow.coerceIn(0, rows.lastIndex)]).w + 2 * sideMargin
            return if (w <= 0.0) 0.0 else (clearW / w).coerceIn(minZoom, maxZoom)
        }
        if (contentW <= 0.0) return 0.0
        return (clearW / contentW).coerceIn(minZoom, maxZoom)
    }

    /** The zoom at which the current paginated row (plus the vertical margins) exactly fills
     *  the viewport height. 0 in vertical mode — the height magnet only exists paginated. */
    fun fitHeightZoom(): Double {
        if (verticalScroll || viewportH == 0) return 0.0
        val rows = rowRanges()
        if (rows.isEmpty()) return 0.0
        val h = rowBounds(rows[currentRow.coerceIn(0, rows.lastIndex)]).h + 2 * vertMargin
        return if (h <= 0.0) 0.0 else (clearH / h).coerceIn(minZoom, maxZoom)
    }

    /**
     * Live magnetic fit for a pinch: within [SNAP_TO_FIT_WIDTH] of fit-to-width the zoom sticks
     * to exactly fit-width and sets [fitWidthActive]; in paginated mode fit-to-height is a second
     * magnet with the same band and feel ([fitHeightZoom] / [fitHeightActive]), the nearer target
     * winning when both grab. Otherwise [raw] passes through and both flags clear. Returns the
     * zoom the caller applies; the false→true transition of either flag surfaces the lock hint.
     */
    fun snapZoomToFit(raw: Double): Double {
        if (freeMovement) { // free movement pinches straight past both magnets
            fitWidthActive = false
            fitHeightActive = false
            return raw
        }
        val fitW = fitWidthZoom()
        val fitH = fitHeightZoom()
        val nearW = fitW > 0.0 && abs(raw - fitW) <= fitW * SNAP_TO_FIT_WIDTH
        val nearH = fitH > 0.0 && abs(raw - fitH) <= fitH * SNAP_TO_FIT_WIDTH
        val pickH = nearH && (!nearW || abs(raw - fitH) < abs(raw - fitW))
        fitHeightActive = pickH
        fitWidthActive = nearW && !pickH
        return when {
            pickH -> fitH
            fitWidthActive -> fitW
            else -> raw
        }
    }

    /**
     * Re-fit to the available space after a viewport resize (e.g. the sidebar opened/closed), but
     * only while [fitWidthActive] (or, paginated, [fitHeightActive]). Deliberately ignores
     * [zoomLocked]: when the usable space changes, staying fit matters more than holding the
     * exact zoom number.
     */
    fun reflowFitWidthForResize() {
        if (contentW <= 0.0 || viewportW == 0) return
        if (fitHeightActive && !verticalScroll) {
            val fit = fitHeightZoom()
            if (fit <= 0.0) return
            zoom = fit
            invalidateCachesForZoom()
            clampScroll() // the row window recentres/pins for the new zoom
            return
        }
        if (!fitWidthActive) return
        // Keep the content under the viewport's vertical centre put (don't jump to the page top) and
        // re-centre horizontally; only the width-driven zoom changes.
        val center = clearCenter()
        val centerContentY = viewportToContent(center).y
        zoom = fitWidthZoom()
        scrollX = if (freeVertical()) (contentW / 2.0) * zoom - center.x else minScrollX()
        scrollY = centerContentY * zoom - center.y
        invalidateCachesForZoom()
        clampScroll()
    }

    // --- initial view (on opening a document) ---

    /**
     * Set zoom + scroll directly when opening a document. Unlike [setZoomAnchored] this
     * ignores the zoom lock — switching documents isn't a user zoom gesture, and the new
     * document must land at its own view rather than inherit the previous one's.
     */
    fun setView(newZoom: Double, sx: Double, sy: Double) {
        zoom = newZoom.coerceIn(minZoom, maxZoom)
        scrollX = sx
        scrollY = sy
        syncCurrentRowToScroll() // paginated: land on the restored row before the clamp
        // A restored view that lands at a fit target should still auto-refit on resize.
        val targetW = fitWidthZoom()
        val targetH = fitHeightZoom()
        fitHeightActive = targetH > 0.0 && abs(zoom - targetH) <= targetH * SNAP_TO_FIT_WIDTH
        fitWidthActive = !fitHeightActive && targetW > 0.0 && abs(zoom - targetW) <= targetW * SNAP_TO_FIT_WIDTH
        invalidateCachesForZoom()
        clampScroll()
    }

    /** Fit page width and scroll to the first page, ignoring the zoom lock (document open). */
    fun resetViewToFitWidth() {
        if (contentW <= 0.0 || viewportW == 0) return
        currentRow = 0 // paginated fit-width targets the first row
        zoom = fitWidthZoom()
        fitWidthActive = true
        fitHeightActive = false
        invalidateCachesForZoom()
        goToPage(0)
    }

    /**
     * Apply [pendingInitialView] (or fit width when it's null/[InitialView.FitWidth]) once the
     * viewport is sized, and mark the initial fit done. Called when a document is opened and from
     * the view's first layout; resets the view explicitly so a previous document's zoom/scroll
     * (or a stale zoom lock) can never carry over.
     */
    fun establishInitialView() {
        if (viewportW <= 0 || viewportH <= 0) return
        when (val v = pendingInitialView) {
            is InitialView.Restore -> setView(v.zoom, v.scrollX, v.scrollY)
            else -> resetViewToFitWidth()
        }
        pendingInitialView = null
        didInitialFit = true
    }

    // --- page cache ---

    /** The width [page]'s footprint shows at: its height when the view turns it a quarter. */
    private fun across(page: Page): Double = footprint(page).let { if (sidewaysBands) it.h else it.w }

    /**
     * The finest resolution [page] is cached at: where its row fills the viewport's width. A page
     * however long is then never wider than the screen in its caches, so what they cost follows the
     * screens of it held, not its size; zoom past this and the sharp viewport renders what shows.
     * By footprints, not outer sizes: room still under the pen must not resize the caches it is
     * drawn through.
     */
    private fun capRes(page: Page): Double {
        if (viewportW <= 0) return Double.MAX_VALUE
        val pages = document.pages
        val row = indexOfPage[page]?.takeIf { pages.getOrNull(it) === page }?.let { rowOf(it) }
        var w = if (row == null) across(page) else (row.last - row.first) * pageGap
        if (row != null) for (i in row) w += across(pages[i])
        return if (w <= 0.0) Double.MAX_VALUE else viewportW * renderScale / w
    }

    private fun clampedRes(page: Page): Double = min(zoom * renderScale, capRes(page)).coerceAtLeast(0.01)

    /** True when the view shows pages a quarter turned, so their bands are cut across the page. */
    private val sidewaysBands: Boolean get() = rotationDeg == 90 || rotationDeg == 270

    /** Whether this layer is cut for [page]'s footprint and resolution as they now are; any resolution serves a pinch. */
    private fun PageLayer.fits(page: Page): Boolean =
        grid.cover == footprint(page) && grid.sideways == sidewaysBands &&
            (zoomingInProgress || abs(grid.res - clampedRes(page)) < 1e-6)

    /**
     * [page]'s layer in [store] for the footprint and resolution it shows at. When those have moved
     * a new layer is begun and the one it had is kept to draw from meanwhile; a size gone back to
     * finds the layer it left, so a margin slider dragged back shows that page at once.
     */
    private fun layerFor(store: Layers, page: Page): PageLayer {
        val now = store.now[page]
        if (now != null && now.fits(page)) return now
        val old = store.before[page]
        if (old != null && old.fits(page)) {
            store.now[page] = old
            if (now != null && now.built() > 0) store.before[page] = now else store.before.remove(page)
            return old
        }
        // One layer is kept to draw from meanwhile: whichever of the two holds more.
        if (now != null && now.built() > 0 && (old == null || now.built() >= old.built())) {
            old?.free()
            store.before[page] = now
        } else {
            now?.free()
        }
        val grid = BandGrid(footprint(page), clampedRes(page), sidewaysBands, store.bandPx, store.gutter)
        return PageLayer(grid).also { store.now[page] = it }
    }

    /**
     * The layer of [store] to draw [page] from, if one built for its footprint has a band. A stale
     * *resolution* is still blitted (scaled) so a pinch never flashes, but a stale *footprint* is
     * not: stretching a whole page while a margin slider is dragged reads far worse than bare paper.
     */
    private fun drawable(store: Layers, page: Page): PageLayer? {
        val f = footprint(page)
        val now = store.now[page]
        if (now != null && now.built() > 0) return now.takeIf { it.cover == f }
        return store.before[page]?.takeIf { it.cover == f && it.grid.sideways == sidewaysBands }
    }

    /** Ask for every band of [page]'s layer in [store] that is missing or out of date. */
    private fun request(store: Layers, page: Page) {
        val layer = layerFor(store, page)
        for (k in layer.bands.indices) if (layer.bands[k]?.stale != false) schedule(store, page, layer, k)
    }

    /** [page]'s whole ink layer, rasterizing on the calling thread every band it lacks. */
    fun cacheFor(page: Page): PageLayer {
        val layer = layerFor(ink, page)
        for (k in layer.bands.indices) {
            if (layer.bands[k]?.stale == false) continue
            layer.free(k)
            layer.bands[k] = renderInk(page, layer.grid, k, cacheItems(page), includeFlow = !flowLifted)
        }
        return layer
    }

    /**
     * Like [cacheFor] but never rasterizes on the calling (UI) thread: asks for the bands [page]'s
     * ink lacks on [runAsync] and returns the layer to blit meanwhile (see [drawable]), or null, in
     * which case the caller draws bare paper until a build lands.
     */
    fun cacheForOrSchedule(page: Page): PageLayer? {
        request(ink, page)
        return drawable(ink, page)
    }

    /** Rasterize band [k] of [layer] off the calling thread, unless a build of that band of [page] is in flight. */
    private fun schedule(store: Layers, page: Page, layer: PageLayer, k: Int, ahead: Boolean = false) {
        val key = BandKey(page, k)
        if (!store.pending.add(key)) return
        if (ahead) prefetching = true
        val gen = store.gen
        val grid = layer.grid
        val build: () -> Band
        if (store === ink) {
            val items = cacheItems(page) // snapshot on the UI thread
            val withFlow = !flowLifted // snapshot too; the generation guard discards stale builds
            build = { renderInk(page, grid, k, items, withFlow) }
        } else {
            val seq = markupSeq // the build reads the markups after this, so it holds every edit up to here
            build = { renderBackground(page, grid, k).also { it.seq = seq } }
        }
        runAsync {
            val band = build()
            postToMain {
                store.pending.remove(key)
                if (ahead) prefetching = false
                val meanwhile = store.meanwhile.remove(key)
                if (gen == store.gen && store.now[page] === layer) {
                    layer.free(k) // the out-of-date band it replaces
                    layer.bands[k] = band
                    // The build painted from what it read then; a repair since, as a playhead crossing ink, would be lost.
                    meanwhile?.let { repaintBand(grid, k, band, page, it) }
                } else {
                    band.surface.recycle()
                    // Thrown away for an edit made since, with the band it was to replace still showing: ask again.
                    if (store.now[page] === layer && layer.bands[k]?.stale == true) schedule(store, page, layer, k)
                }
                // Repaint either way. A build the page outgrew mid-flight (a margin drag bumps the
                // generation on every tick) is discarded here, and the draw loop is what schedules
                // its replacement — but every frame while this one was in flight was turned away by
                // [Layers.pending], so without this the page would sit blank until something else drew.
                onCacheReady?.invoke()
                // Made room for before the next is begun, so builds landing between frames never pile up over the budget.
                if (stamp > 0) prune()
                pump()
            }
        }
    }

    /** Start the build of the nearest wanted band that lacks one, if none ahead of the screen is in flight. */
    private fun pump() {
        if (pumping) return
        pumping = true
        try {
            // An inline [runAsync] lands each build before the next is looked for, hence the loop.
            while (!prefetching && !zoomingInProgress) {
                val w = wanted.firstOrNull {
                    it.store.now[it.page] === it.layer && it.layer.bands[it.index]?.stale != false &&
                        BandKey(it.page, it.index) !in it.store.pending
                } ?: break
                schedule(w.store, w.page, w.layer, w.index, ahead = true)
            }
        } finally {
            pumping = false
        }
    }

    /** A page's cached ink either side of its typed text (see [PageLayers]). */
    private class InkItems(val under: List<CanvasItem>, val over: List<CanvasItem>)

    /**
     * The ink a playback fades, read by every painter of a page, off the main thread too; immutable
     * and swapped whole. Whoever changes it repaints what changed: a fade baked into a cache stays.
     */
    @Volatile
    var fade: InkFade? = null

    /** Paint one item, faint if the playback fades it. */
    fun paintItem(item: CanvasItem, r: Renderer) {
        val f = fade
        if (f == null || !f.fades(item)) return item.paint(r)
        r.saveLayerAlpha(item.paintBounds(), InkFade.ALPHA)
        item.paint(r)
        r.restore()
    }

    /**
     * Items baked into a page's ink cache: all but lifted items and highlighters ([isHighlighterInk]
     * — those composite live so they MULTIPLY against the background). While the flow is lifted, so
     * are the fills under it, which the view then paints live beneath it.
     */
    private fun cacheItems(page: Page): InkItems {
        val n = PageLayers.underText(page.items)
        val under = if (flowLifted) emptyList() else page.items.subList(0, n).filter { !isLiftedItem(it) }
        val over = page.items.subList(n, page.items.size).filter { !isLiftedItem(it) && !it.isHighlighterInk() }
        return InkItems(under, over)
    }

    /** Rasterize band [k] of [grid] of [page]'s ink: what of [items] and the flow its surface holds. */
    private fun renderInk(page: Page, grid: BandGrid, k: Int, items: InkItems, includeFlow: Boolean): Band {
        val surface = surfaceFactory.create(grid.surfaceW(k), grid.surfaceH(k), 1.0)
        surface.fill(TRANSPARENT)
        val r = surface.renderer()
        grid.enter(r, k) // the grid covers the margins, so page space starts inset
        val held = grid.held(k)
        val f = fade
        InkFade.paint(items.under, r, f, { it.paintBounds().intersects(held) }) { it.paint(r) }
        if (includeFlow) paintFlow?.invoke(page, r, held)
        InkFade.paint(items.over, r, f, { it.paintBounds().intersects(held) }) { it.paint(r) }
        noteGeometryBuilt(page)
        return Band(surface)
    }

    /** Record that [page]'s strokes now hold ribbon geometry. Called from the cache threads too, so
     *  the set is synchronized; it is touched once per page build, never per item. */
    fun noteGeometryBuilt(page: Page) {
        synchronized(geomPages) { geomPages.add(page) }
    }

    /**
     * Release ribbon geometry for every page outside [keep], and forget it. Only the pages that
     * actually leave are walked, so a steady scroll pays for one page.
     *
     * [Stroke.releaseGeometry] drops the ribbon but keeps the bounds rects: [Stroke.bounds] is built
     * from the geometry, and band selection reads it across pages it never painted, so discarding it
     * too would rebuild the whole ribbon just to hand back a rectangle.
     */
    fun releaseGeometryExcept(keep: Set<Page>) {
        synchronized(geomPages) {
            if (geomPages.isEmpty()) return
            val it = geomPages.iterator()
            while (it.hasNext()) {
                val page = it.next()
                if (page in keep) continue
                for (item in page.items) if (item is Stroke) item.releaseGeometry()
                it.remove()
            }
        }
    }

    /**
     * The cached opaque-ribbon bitmap for highlighter [stroke] on [page], built lazily and reused
     * until the stroke is edited (its [Stroke.geometry] instance changes), its colour changes, or the
     * resolution moves — rebuilt on zoom-settle, while mid-pinch the stale-resolution bitmap is
     * blitted scaled (like the page caches). The caller composites it with the stroke's alpha and
     * MULTIPLY so it darkens against the live page; see [CanvasView].
     */
    fun highlighterCacheFor(stroke: Stroke, page: Page): HighlighterCacheEntry {
        val res = clampedRes(page)
        val g = stroke.geometry()
        val opaque = stroke.renderColor.withAlpha(255)
        val existing = hlCaches[stroke]
        if (existing != null && existing.geom === g && existing.opaque == opaque && existing.cuts === stroke.cuts &&
            (zoomingInProgress || abs(existing.res - res) < 1e-6)
        ) {
            return existing
        }
        existing?.surface?.recycle()
        return buildHighlighter(stroke, res, g, opaque).also { hlCaches[stroke] = it }
    }

    private fun buildHighlighter(stroke: Stroke, res: Double, g: StrokeGeometry, opaque: Rgba): HighlighterCacheEntry {
        val cover = stroke.bounds().outset(HL_PAD)
        val w = ceil(cover.w * res).toInt().coerceAtLeast(1)
        val h = ceil(cover.h * res).toInt().coerceAtLeast(1)
        val surface = surfaceFactory.create(w, h, 1.0)
        surface.fill(TRANSPARENT)
        val r = surface.renderer()
        r.scale(res, res)
        r.translate(-cover.left, -cover.top)
        stroke.paintHighlighterRibbon(r)
        return HighlighterCacheEntry(surface, res, g, opaque, cover, stroke.cuts)
    }

    /**
     * Whether [page] has anything in its background layer — a PDF page, a resolved ruling or markups.
     * Plain colour pages return false so no (large, transparent) background surface is allocated,
     * even though [paintPageBackground] is always installed for the pattern path.
     */
    fun hasPageBackground(page: Page): Boolean =
        paintPageBackground != null &&
            (page.pdfPage != null || effectiveTemplate(page) != PageTemplates.NONE || page.markups.isNotEmpty())

    /**
     * The page's rendered background layer (PDF/template) at the current resolution,
     * or null when the document has no page background. Built once and reused across
     * ink edits — rebuilt only when the resolution changes — so erasing/repairing ink
     * never re-rasterizes the (expensive) background.
     */
    fun backgroundFor(page: Page): PageLayer? {
        if (!hasPageBackground(page)) return null
        val layer = layerFor(bg, page)
        for (k in layer.bands.indices) {
            if (layer.bands[k]?.stale == false) continue
            layer.free(k)
            layer.bands[k] = renderBackground(page, layer.grid, k).also { it.seq = markupSeq }
        }
        return layer
    }

    /** Non-blocking counterpart to [backgroundFor]; see [cacheForOrSchedule]. */
    fun backgroundForOrSchedule(page: Page): PageLayer? {
        if (!hasPageBackground(page)) return null
        request(bg, page)
        return drawable(bg, page)
    }

    /** Rasterize band [k] of [grid] of [page]'s background. */
    private fun renderBackground(page: Page, grid: BandGrid, k: Int): Band {
        val surface = surfaceFactory.create(grid.surfaceW(k), grid.surfaceH(k), 1.0)
        surface.fill(TRANSPARENT)
        val r = surface.renderer()
        grid.enter(r, k)
        paintPageBackground?.invoke(page, r, grid.res, grid.held(k))
        return Band(surface)
    }

    /**
     * Draw [page]'s ink where it shows in [seen] (page space), asking for the bands missing there.
     * [r] stands in page space. A band not built yet shows what the layer before it holds there.
     */
    fun drawInk(r: Renderer, page: Page, seen: Rect) = drawLayer(ink, r, page, seen)

    /** [drawInk] for the background layer of a page that has one. */
    fun drawBackground(r: Renderer, page: Page, seen: Rect) {
        if (hasPageBackground(page)) drawLayer(bg, r, page, seen)
    }

    private fun drawLayer(store: Layers, r: Renderer, page: Page, seen: Rect) {
        val layer = layerFor(store, page)
        val g = layer.grid
        val old = store.before[page]?.takeIf { it.cover == g.cover && it.grid.sideways == g.sideways }
        for (k in g.meeting(seen)) {
            val band = layer.bands[k]
            if (band == null || band.stale) schedule(store, page, layer, k)
            if (band != null) {
                r.drawRaster(band.surface, g.shown(k), g.source(k))
            } else if (old != null) {
                r.save()
                r.clipRect(g.shown(k))
                drawBands(r, old, seen)
                r.restore()
            }
        }
    }

    /** Draw the built bands of [layer] that show in [seen] (page space), or all of them. [r] stands in page space. */
    fun drawBands(r: Renderer, layer: PageLayer, seen: Rect? = null) {
        val g = layer.grid
        for (k in if (seen == null) layer.bands.indices else g.meeting(seen)) {
            layer.bands[k]?.let { r.drawRaster(it.surface, g.shown(k), g.source(k)) }
        }
    }

    /** Ask for the bands of [page]'s layers that show in [seen] (page space) and are missing, without drawing. */
    fun askFor(page: Page, seen: Rect) {
        askFor(ink, page, seen)
        if (hasPageBackground(page)) askFor(bg, page, seen)
    }

    private fun askFor(store: Layers, page: Page, seen: Rect) {
        val layer = layerFor(store, page)
        for (k in layer.grid.meeting(seen)) if (layer.bands[k]?.stale != false) schedule(store, page, layer, k)
    }

    /** Markup edits made so far; each background layer and sharp frame records the last one it holds. */
    private var markupSeq = 0

    private class Unbaked(val seq: Int, val markup: TextMarkup)

    /** Markups an edit put on a page that a layer on screen may not hold yet, by page. Main thread only. */
    private val unbaked = HashMap<Page, MutableList<Unbaked>>()

    /** A markup edit came in while a sharp render was under way, which may have read the page before it. */
    private var sharpAgain = false

    /**
     * [page]'s markups changed: its background bands are painted afresh on the cache thread and
     * swapped in as each is done, the old ones shown meanwhile, and the sharp viewport likewise.
     * Patching just the region they cover would leave seams: PDFium draws a region's pixels visibly
     * differently from the same pixels of a whole band, around images near its edges. [added] are
     * the markups the edit put on; [unbakedMarkups] hands them to the overlay until the layers on
     * screen hold them, so nothing blinks while the new bands render.
     */
    fun rebakeBackground(page: Page, added: List<TextMarkup>) {
        val seq = ++markupSeq
        if (added.isNotEmpty()) unbaked.getOrPut(page) { ArrayList() }.addAll(added.map { Unbaked(seq, it) })
        // A build under way may have read the markups before the edit.
        bg.gen++
        if (pendingSharp) sharpAgain = true else if (sharpFrame?.gen == sharpGen) requestSharpViewport()
        val layer = bg.now[page] ?: return
        for (band in layer.bands) band?.stale = true
        askAgain(bg, page, layer)
    }

    /** Ask for the out-of-date bands of [layer] on screen at once, and the rest in their turn. */
    private fun askAgain(store: Layers, page: Page, layer: PageLayer) {
        if (!layer.fits(page)) return // the draw loop begins a layer anew
        val seen = seenOf(page)
        if (seen != null) for (k in layer.grid.meeting(seen)) if (layer.bands[k]?.stale == true) schedule(store, page, layer, k)
        pump()
    }

    /** The last markup edit held by every built band of [page]'s background that shows, 0 with none to draw. */
    private fun heldSeq(page: Page): Int {
        val layer = bg.now[page]?.takeIf { it.cover == footprint(page) } ?: return 0
        val seen = seenOf(page)
        var held = Int.MAX_VALUE
        for (k in if (seen == null) layer.bands.indices else layer.grid.meeting(seen)) {
            layer.bands[k]?.let { held = min(held, it.seq) }
        }
        return if (held == Int.MAX_VALUE) 0 else held
    }

    /** [page]'s markups the layers on screen don't hold yet, for the overlay to draw meanwhile. */
    fun unbakedMarkups(page: Page): List<TextMarkup> {
        val list = unbaked[page] ?: return emptyList()
        var held = heldSeq(page)
        val frame = sharpFrame
        if (frame != null && frame.gen == sharpGen && isPastResolutionCap()) held = min(held, frame.seq)
        val now = page.markups
        list.removeAll { u -> u.seq <= held || now.none { it === u.markup } }
        if (list.isEmpty()) unbaked.remove(page)
        return list.map { it.markup }
    }

    /** Append a single just-committed stroke into an existing cache (cheap), else rebuild. */
    fun appendToCache(page: Page, item: CanvasItem) {
        if (item.isHighlighterInk()) return // composited live over the page, never cached
        appendToSharpInk(page, item) // keep the sharp viewport crisp without a full re-render
        if (pendingSharp) pendingSharpEdits.add(page to item.paintBounds().outset(SHARP_EDIT_PAD))
        val bounds = item.paintBounds()
        ink.now[page]?.let { appendTo(it, page, item, bounds) }
        ink.before[page]?.let { appendTo(it, page, item, bounds) }
    }

    /** Paint [item] onto the bands of [layer] that hold it; a band whose build is in flight gets it again when that lands. */
    private fun appendTo(layer: PageLayer, page: Page, item: CanvasItem, bounds: Rect) {
        val g = layer.grid
        for (k in g.meeting(bounds, held = true)) {
            layer.bands[k]?.let {
                val r = it.surface.renderer()
                r.save()
                g.enter(r, k)
                paintItem(item, r)
                r.restore()
            }
            noteMeanwhile(page, k, bounds)
        }
    }

    private fun noteMeanwhile(page: Page, k: Int, rect: Rect) {
        val key = BandKey(page, k)
        if (key in ink.pending) ink.meanwhile[key] = ink.meanwhile[key]?.union(rect) ?: rect
    }

    /**
     * Repair just [dirtyRect] (page-local content space) of [page]'s ink layer in
     * place, instead of rebuilding the whole page — used after the eraser removes
     * strokes from a small area. Clears the region and repaints only the surviving
     * items overlapping it, so the cost scales with the dirty area, not the page. The
     * separate background layer is untouched, so this works for PDF pages too.
     *
     * Returns false when there is no live cache to repair; the caller should then
     * [invalidatePage] for a full rebuild.
     */
    fun repairRegion(page: Page, dirtyRect: Rect): Boolean {
        repairSharpInk(page, dirtyRect) // erase from the sharp ink layer in place, no re-render
        if (pendingSharp) pendingSharpEdits.add(page to dirtyRect)
        val now = ink.now[page]
        now?.let { repaintLayer(it, page, dirtyRect) }
        ink.before[page]?.let { repaintLayer(it, page, dirtyRect) }
        return now != null
    }

    private fun repaintLayer(layer: PageLayer, page: Page, rect: Rect) {
        for (k in layer.grid.meeting(rect, held = true)) {
            layer.bands[k]?.let { repaintBand(layer.grid, k, it, page, rect) }
            noteMeanwhile(page, k, rect)
        }
    }

    /** Clear [rect] (page-local) of [band]'s ink and paint [page]'s items there again. */
    private fun repaintBand(grid: BandGrid, k: Int, band: Band, page: Page, rect: Rect) {
        val r = band.surface.renderer()
        r.save()
        grid.enter(r, k)
        r.clipRect(rect)
        r.clear()
        paintInk(page, r, rect)
        r.restore()
        noteGeometryBuilt(page) // a repaint builds ribbons too, on a page that may be out of reach by now
    }

    /** Repaint [page]'s cached ink where it meets [region]: the fills under its typed text, the text, then the rest. */
    private fun paintInk(page: Page, r: Renderer, region: Rect) {
        val n = PageLayers.underText(page.items)
        val f = fade
        if (!flowLifted) {
            InkFade.paint(page.items.subList(0, n), r, f, { !isLiftedItem(it) && it.paintBounds().intersects(region) }) { it.paint(r) }
            paintFlow?.invoke(page, r, region)
        }
        InkFade.paint(page.items.subList(n, page.items.size), r, f, {
            !isLiftedItem(it) && !it.isHighlighterInk() && it.paintBounds().intersects(region)
        }) { it.paint(r) }
    }

    /**
     * Paint the fills under [page]'s typed text that its ink cache leaves out, beneath the cache: all
     * of them while the flow is lifted (lifted ones go through [paintLiftedUnderText] instead).
     */
    fun paintUnderText(page: Page, r: Renderer, region: Rect) {
        if (!flowLifted) return
        val n = PageLayers.underText(page.items)
        InkFade.paint(page.items.subList(0, n), r, fade, { !isLiftedItem(it) && it.paintBounds().intersects(region) }) { it.paint(r) }
    }

    /** True when [item] is one of the fills under [page]'s typed text. */
    fun isUnderText(page: Page, item: CanvasItem): Boolean {
        val n = PageLayers.underText(page.items)
        for (i in 0 until n) if (page.items[i] === item) return true
        return false
    }

    /**
     * Paints [page]'s lifted (selected) fills that sit under its typed text, so a selected fill stays
     * under the ink it is filled in. Called at the page's display origin, before its page transform.
     */
    var paintLiftedUnderText: ((page: Page, r: Renderer) -> Unit)? = null

    fun invalidatePage(page: Page) {
        ink.drop(page)
        if (roomView?.page === page && roomView?.live == false) roomView = null
        ink.gen++
        sharpGen++
    }

    /**
     * Page-style invalidation. The paper colour is filled **live** each frame in [CanvasView] (not
     * baked into either cached layer), so a colour-only change just needs the sharp viewport — which
     * *does* bake the paper — to re-render: bump [sharpGen]. A ruling, by contrast, lives in the
     * background cache, so a pattern/spacing/pattern-colour change must drop that page's background
     * and let the draw loop rebuild via [backgroundForOrSchedule]. These do **not** early-return on a
     * missing cache, so a plain page that *gains* a ruling rebuilds correctly; one that loses it stops
     * drawing a background (see [hasPageBackground]).
     */
    fun invalidatePaper() {
        sharpGen++
    }

    fun invalidateBackground(page: Page) {
        bg.drop(page)
        bg.gen++
        sharpGen++
    }

    fun invalidateAllBackgrounds() {
        bg.clear()
        bg.gen++
        sharpGen++
    }

    /**
     * Page footprints changed (a margin edit). The surfaces are kept rather than cleared, but they
     * are not drawn while they are the wrong size (see [cacheForOrSchedule]) — the page shows bare
     * paper until its rebuild lands. Keeping them means a slider dragged back to a size already
     * rasterized shows that page again at once, with nothing to rebuild. The size mismatch itself is
     * what schedules the rebuild (see [layerFor]).
     */
    fun invalidatePageGeometry() {
        ink.gen++
        bg.gen++
        sharpGen++
    }

    fun invalidateAllCaches() {
        roomView = null
        ink.clear()
        bg.clear()
        unbaked.clear()
        for (entry in hlCaches.values) entry.surface.recycle()
        hlCaches.clear()
        wetInk.clear()
        ink.gen++
        bg.gen++
        sharpGen++
    }

    /**
     * The settled part of the stroke under the pen, baked into a raster once and blitted after
     * (see [WetInkCache]). Held here because the surface factory and the render resolution are, and
     * because one buffer serves every stroke the note ever draws rather than one per stroke.
     */
    private val wetInk = WetInkCache(surfaceFactory)

    /**
     * Paint the live stroke, through the wet cache when it will take it. Called inside the page's
     * own transform, so everything the cache does is in page space, exactly like the page caches.
     *
     * The surface is capped at twice the viewport's pixels: a stroke sweeping a deeply zoomed page
     * could ask for a buffer many times the screen it is drawn on, and past that cap redrawing the
     * ribbon is the cheaper of the two.
     */
    fun paintLiveStroke(r: Renderer, stroke: Stroke) {
        val res = (zoom * renderScale).coerceAtLeast(0.01)
        val cap = 2L * max(viewportW, 1) * max(viewportH, 1)
        if (!wetInk.paint(r, stroke, res, cap)) stroke.paint(r)
    }

    /**
     * Rebuild every live page ink cache **off-thread**: the undo/redo path for a command that can't
     * say what it touched ([com.xnotes.core.history.Command.touched] returned null), so there are no
     * regions to repair and the whole page has to be re-rendered. Doing that inline is what timed
     * out input — a full page of ink is the same work [renderInk] does on the cache thread, times
     * every cached page, on the thread the tap arrived on.
     *
     * So it schedules instead: each band stays where it is and keeps being blitted until its
     * rebuild lands, so the page shows its pre-edit content for a frame or two rather than
     * blanking (the flicker [invalidateAllCaches] caused). [Band.stale] is what stops a band being
     * taken as built — an edit changes neither the cover nor the resolution, so nothing else
     * would notice.
     *
     * Bumps the ink generation so a build scheduled before the edit is discarded on publish rather than
     * overwriting the page with its pre-edit snapshot, and [sharpGen] because the sharp viewport can
     * only be patched by region ([repairSharpInk]) and there are none: it drops to the soft cache
     * underneath and re-renders once the view settles. The (PDF/template) background layer is left
     * untouched — undo/redo never edits it, so it must not flash either.
     */
    fun refreshAllInk() {
        refreshInk(ink.now.keys.toList())
    }

    /** [refreshAllInk] for just [pages]: rebuilt off-thread, their old surfaces blitted meanwhile. */
    fun refreshInk(pages: Collection<Page>) {
        ink.gen++
        sharpGen++
        for (page in pages) staleInk(page, null)
    }

    /** Mark [page]'s ink bands that hold [rect] (all of them when null) out of date, to be built again nearest the screen first. */
    private fun staleInk(page: Page, rect: Rect?) {
        ink.before[page]?.let { old ->
            for (k in if (rect == null) old.bands.indices else old.grid.meeting(rect, held = true)) old.bands[k]?.stale = true
        }
        val layer = ink.now[page] ?: return
        for (k in if (rect == null) layer.bands.indices else layer.grid.meeting(rect, held = true)) layer.bands[k]?.stale = true
        askAgain(ink, page, layer)
    }

    /**
     * [refreshInk] for a change spread over [regions] (page-local) too wide to repaint inline, as
     * playback's fade coming or going: the sharp viewport is patched there in place, not dropped,
     * so a deep zoom stays crisp while the pages rebuild.
     */
    fun refreshInkRegions(regions: Map<Page, Rect>) {
        if (regions.isEmpty()) return
        ink.gen++
        for ((page, rect) in regions) {
            val padded = rect.outset(SHARP_EDIT_PAD)
            repairSharpInk(page, padded)
            if (pendingSharp) pendingSharpEdits.add(page to padded)
            staleInk(page, padded)
        }
    }

    /**
     * The in-place undo/redo repair, confined to the regions the
     * undone command actually disturbed (see [com.xnotes.core.history.Command.touched]) — surfaces
     * kept, background layer untouched, sharp viewport patched, minus the full-page re-rasterization
     * of every cached page that made a single undo tap cost the whole visible band. Rects are unioned
     * per page, so each page is repainted at most once — and the sharp viewport is patched rather
     * than dropped, which is what keeps a deep zoom crisp across an undo. A page with no live cache
     * has nothing to repair and falls out of [repairRegion] on its own, having still patched the
     * sharp layer. The unbounded fallback is [refreshAllInk].
     */
    fun repairInkRegions(regions: List<Pair<Page, Rect>>) {
        if (regions.isNotEmpty()) {
            val byPage = LinkedHashMap<Page, Rect>()
            for ((page, rect) in regions) {
                byPage[page] = byPage[page]?.union(rect) ?: rect
            }
            for ((page, rect) in byPage) repairRegion(page, rect.outset(SHARP_EDIT_PAD))
        }
        ink.gen++
    }

    /**
     * Invalidate caches after a *zoom* without dropping the surfaces. The old-resolution
     * bitmaps stay in the maps, so [cacheForOrSchedule]/[backgroundForOrSchedule] keep
     * blitting them (scaled) for the new zoom until the sharp rebuild lands — avoiding the
     * one-frame empty-canvas flash that clearing would cause when a pinch ends. A page whose
     * clamped resolution is unchanged (already at its cap, see [capRes]) matches on res and
     * is returned as-is, so it is neither flashed nor needlessly rebuilt.
     *
     * Bumping the generations discards any in-flight build captured at the previous one, so
     * a stale-resolution surface can't be published over the maps after the zoom changed. Use
     * [invalidateAllCaches] instead when page *content* changed — that must rebuild even at the
     * same resolution.
     */
    fun invalidateCachesForZoom() {
        ink.gen++
        bg.gen++
    }

    /** [page]'s ink layer as it stands, built or not. */
    fun inkLayer(page: Page): PageLayer? = ink.now[page]

    /** [page]'s background layer as it stands, built or not. */
    fun backgroundLayer(page: Page): PageLayer? = bg.now[page]

    /** The pages with a band wanted as of the last frame, nearest the screen first. */
    fun wantedPages(): List<Page> = wanted.map { it.page }.distinct()

    /** How far band [k] of [grid], a layer of page [i], is from [view] (content space), in viewport px; 0 when it shows. */
    private fun distance(i: Int, grid: BandGrid, k: Int, view: Rect): Double {
        val at = fromPageSpaceRect(i, grid.onPage(k))
        val dx = max(max(view.left - at.right, at.left - view.right), 0.0)
        val dy = max(max(view.top - at.bottom, at.top - view.bottom), 0.0)
        // To an eighth of a pixel, so two bands as far off as each other tie whatever the rounding on the way here.
        return Math.round(max(dx, dy) * zoom * 8.0) / 8.0
    }

    /** Every band of the layers of [row]'s pages, each as far from [view] as it lies, plus [extra]. */
    private fun wantRow(row: IntRange, view: Rect, extra: Double, into: MutableList<Want>) {
        for (i in row) {
            val page = document.pages[i]
            for (store in arrayOf(bg, ink)) {
                if (store === bg && !hasPageBackground(page)) continue
                val layer = layerFor(store, page)
                val g = layer.grid
                for (k in 0 until g.count) into += Want(store, page, layer, k, distance(i, g, k, view) + extra, g.bytes(k))
            }
        }
    }

    /**
     * The bands to have built, nearest the screen first: those on it, then outward below and above
     * until [FILL_SHARE] of the budget is spoken for. Paginated, the row shown and then each
     * neighbour as a turn to it would show it. Sized in bytes and never by a count of pages, which
     * flips as page edges cross the screen and used to evict what had just been built. Null while
     * the note is not laid out.
     */
    private fun plan(): List<Want>? {
        val pages = document.pages
        val rows = rowRanges()
        if (rows.isEmpty() || pageRects.size != pages.size || viewportW <= 0 || viewportH <= 0) return null
        stamp++
        val view = visibleContentRect()
        val fill = (cacheBudget() * FILL_SHARE).toLong()
        val wants = ArrayList<Want>()
        if (verticalScroll) {
            fun top(r: Int) = rows[r].minOf { pageRects[it].top }
            fun bottom(r: Int) = rows[r].maxOf { pageRects[it].bottom }
            // Rows on screen are first..last (last = first - 1 when the view sits in a gap).
            var first = 0
            while (first < rows.size && bottom(first) < view.top) first++
            var last = rows.lastIndex
            while (last >= 0 && top(last) > view.bottom) last--
            for (r in first..last) wantRow(rows[r], view, 0.0, wants)
            var lo = first - 1
            var hi = last + 1
            while (lo >= 0 || hi <= rows.lastIndex) {
                val below = if (hi <= rows.lastIndex) (top(hi) - view.bottom) * zoom else Double.MAX_VALUE
                val above = if (lo >= 0) (view.top - bottom(lo)) * zoom else Double.MAX_VALUE
                // A row further out holds only bands further out: stop once the nearer ones take the share.
                val reach = min(below, above)
                if (wants.sumOf { if (it.distance <= reach) it.bytes else 0L } >= fill) break
                if (below <= above) wantRow(rows[hi++], view, 0.0, wants) else wantRow(rows[lo--], view, 0.0, wants)
            }
        } else {
            val shown = currentRow.coerceIn(0, rows.lastIndex)
            wantRow(rows[shown], view, 0.0, wants)
            for (r in intArrayOf(shown + 1, shown - 1)) {
                if (r !in rows.indices) continue
                // Where a turn to it lands, and a screen further off than the row shown.
                val landing = visibleFor(rowTargetScroll(r).x, flipLandingScrollY(r), zoom)
                wantRow(rows[r], landing, viewportH.toDouble(), wants)
            }
        }
        wants.sortBy { it.distance }
        var held = 0L
        var n = 0
        while (n < wants.size && (wants[n].distance <= 0.0 || held + wants[n].bytes <= fill)) held += wants[n++].bytes
        for (k in 0 until n) wants[k].let { it.layer.wantedAt[it.index] = stamp }
        return if (n == wants.size) wants else ArrayList(wants.subList(0, n))
    }

    /**
     * Runs after the frame has drawn, and asked for, the bands on screen: builds outward from them
     * one at a time, and lets go of what the budget cannot hold. A pinch moving the zoom keeps what
     * is cached but builds nothing ahead.
     */
    fun prefetchAndPrune() {
        wanted = plan() ?: return
        near = wanted.mapTo(HashSet()) { it.page }
        prune()
        if (!zoomingInProgress) {
            pump()
            prefetchMath?.let { fetch -> for (page in near) fetch(page, zoom) }
        }
    }

    private class Spare(val layer: PageLayer, val index: Int, val distance: Double, val bytes: Long)

    /**
     * Let go of what the last [plan] has no use for: the layers of pages that are gone, a page's
     * old layer once the new one has every band wanted of it, bands out of date and out of reach,
     * and, over budget, the unwanted bands furthest from the screen. Ribbons and highlighter
     * bitmaps go with the pages no wanted band is on.
     */
    private fun prune() {
        val unfinished = HashSet<PageLayer>()
        for (w in wanted) if (w.layer.bands[w.index] == null) unfinished += w.layer
        var total = 0L
        for (store in stores) {
            for (map in arrayOf(store.now, store.before)) {
                val each = map.entries.iterator()
                while (each.hasNext()) {
                    val (page, layer) = each.next()
                    val done = map === store.before && store.now[page]?.let { it !in unfinished } ?: true
                    if (done || page !in indexOfPage) {
                        layer.free()
                        each.remove()
                        continue
                    }
                    var keep = false
                    for (k in layer.bands.indices) {
                        val wantedNow = layer.wantedAt[k] == stamp
                        val band = layer.bands[k]
                        if (band != null && band.stale && !wantedNow && map === store.now) layer.free(k)
                        else if (band != null) total += layer.grid.bytes(k)
                        if (wantedNow || layer.bands[k] != null) keep = true
                    }
                    if (!keep) each.remove()
                }
            }
        }
        val budget = cacheBudget()
        if (total > budget) {
            val view = visibleContentRect()
            val spare = ArrayList<Spare>()
            for (store in stores) for (map in arrayOf(store.now, store.before)) for ((page, layer) in map) {
                val i = indexOfPage[page] ?: continue
                for (k in layer.bands.indices) {
                    if (layer.bands[k] == null || layer.wantedAt[k] == stamp) continue
                    spare += Spare(layer, k, distance(i, layer.grid, k, view), layer.grid.bytes(k))
                }
            }
            spare.sortByDescending { it.distance }
            for (s in spare) {
                if (total <= budget || s.distance <= 0.0) break
                s.layer.free(s.index)
                total -= s.bytes
            }
        }
        if (hlCaches.isNotEmpty()) {
            val keep = HashSet<Stroke>()
            for (p in near) for (it in p.items) if (it is Stroke && it.tool == Tool.HIGHLIGHTER) keep.add(it)
            val each = hlCaches.entries.iterator()
            while (each.hasNext()) {
                val (stroke, entry) = each.next()
                if (stroke in keep) continue
                entry.surface.recycle()
                each.remove()
            }
        }
        releaseGeometryExcept(near)
    }

    /**
     * The pages whose rect intersects the viewport, as a contiguous `first..last` index range
     * (pages stack vertically, so the visible set is always contiguous), or null when none is
     * visible. Used for the debug readout.
     */
    fun visiblePageRange(): IntRange? {
        val visible = visibleContentRect()
        val drawable = drawablePageRange()
        var first = -1
        var last = -1
        for (i in pageRects.indices) {
            if (i !in drawable) continue
            val pr = pageRects.getOrNull(i) ?: continue
            if (!pr.intersects(visible)) continue
            if (first < 0) first = i
            last = i
        }
        return if (first < 0) null else first..last
    }

    // --- sharp viewport ---

    /** True when the current zoom shows a visible page finer than it is cached ([capRes]). */
    fun isPastResolutionCap(): Boolean {
        val target = zoom * renderScale
        val visible = visibleContentRect()
        for (i in pageRects.indices) {
            val pr = pageRects.getOrNull(i) ?: continue
            if (!pr.intersects(visible)) continue
            val page = document.pages.getOrNull(i) ?: continue
            if (target > capRes(page) * (1 + 1e-9)) return true
        }
        return false
    }

    /**
     * Ready sharp layers (background then ink) plus the affine transform to blit them at: each is
     * drawn into `Rect(dx, dy, base.width * scale, base.height * scale)`.
     */
    class SharpBlit(val base: RasterSurface, val ink: RasterSurface, val scale: Double, val dx: Double, val dy: Double)

    /**
     * The sharp viewport layers to blit, or null when there isn't a usable one. It stays usable
     * across a *pan or zoom* (same content): rendered for an earlier view, we re-fit it with a
     * scale + translate so the content lines up — the part still on screen stays sharp (crisper than
     * the soft cache even when scaled), and whatever falls outside it uses the soft cache underneath
     * until the settled re-render lands. Only a non-incremental content edit ([sharpGen] moved)
     * makes it unusable; writes and erases patch the ink layer directly ([appendToSharpInk] /
     * [repairSharpInk]) so they keep it valid.
     */
    fun sharpViewportBlit(): SharpBlit? {
        val f = sharpFrame ?: return null
        // A viewport raster holds complete pixels, including paper/background. Unlike the
        // per-page caches it cannot be re-used on a different paginated row: otherwise the
        // previous page is drawn across the new one until its fresh sharp frame finishes.
        if (f.gen != sharpGen || pageFlipAnimating || (!verticalScroll && (flipOffsetX != 0.0 || f.row != currentRow))) return null
        val scale = zoom / f.z
        val o = origin() // live origin, incl. overscroll lift, so the sharp page rides the pull too
        val o0 = originFor(f.sx, f.sy, f.z)
        // Surface pixel p maps to screen scale*p + (o - scale*o0).
        return SharpBlit(f.base, f.ink, scale, o.x - scale * o0.x, o.y - scale * o0.y)
    }

    /** Drop the sharp viewport surface (e.g. once the zoom falls back below the cap). */
    fun clearSharpViewport() {
        sharpFrame = null
    }

    /**
     * Render the current viewport — paper + background + ink for the visible pages — at full zoom
     * resolution into one viewport-sized surface, off the UI thread, tagged with the exact view it
     * was rendered for. Used past the resolution cap so a deep zoom stays razor-sharp without
     * caching whole pages; the result is reused only while the view is unchanged (see
     * [sharpSurfaceForView]) and re-rendered when the user pans/zooms to a new area.
     */
    fun requestSharpViewport() {
        if (pendingSharp || zoomingInProgress || pageFlipAnimating || (!verticalScroll && flipOffsetX != 0.0) ||
            viewportW <= 0 || viewportH <= 0) return
        if (!isPastResolutionCap()) return
        val gen = sharpGen
        val row = currentRow
        val sx = scrollX
        val sy = scrollY
        val z = zoom
        val vw = viewportW
        val vh = viewportH
        val res = z * renderScale
        val o = originFor(sx, sy, z)
        val visible = visibleFor(sx, sy, z)
        val bg = palette.bg
        // Snapshot the visible pages and their items on the UI thread.
        val drawable = drawablePageRange()
        val draws = ArrayList<SharpPageSnap>()
        for (i in document.pages.indices) {
            if (i !in drawable) continue
            val pr = pageRects.getOrNull(i) ?: continue
            if (!pr.intersects(visible)) continue
            val page = document.pages[i]
            // The visible slice in page-local display coords, mapped to page space for the painters.
            val displayRegion = Rect.fromPoints(
                Pt(max(pr.left, visible.left) - pr.left, max(pr.top, visible.top) - pr.top),
                Pt(min(pr.right, visible.right) - pr.left, min(pr.bottom, visible.bottom) - pr.top),
            )
            draws.add(SharpPageSnap(page, pr, cacheItems(page), displayRectToPage(page, displayRegion), i))
        }
        if (draws.isEmpty()) return
        pendingSharp = true
        pendingSharpEdits.clear()
        val seq = markupSeq
        val withFlow = !flowLifted // snapshot; a session toggle bumps sharpGen and discards this
        runAsync {
            val base = surfaceFactory.create(vw, vh, 1.0).also { it.fill(bg) }
            val ink = surfaceFactory.create(vw, vh, 1.0).also { it.fill(TRANSPARENT) }
            renderSharpFrame(base, ink, o, z, res, draws, withFlow)
            postToMain {
                pendingSharp = false
                if (gen == sharpGen && row == currentRow && !pageFlipAnimating &&
                    sx == scrollX && sy == scrollY && z == zoom
                ) {
                    sharpFrame = SharpFrame(base, ink, sx, sy, z, gen, row, seq)
                    // Replay edits committed during the build; the item snapshot predates them.
                    for ((p, rect) in pendingSharpEdits) repairSharpInk(p, rect)
                    onCacheReady?.invoke()
                }
                pendingSharpEdits.clear()
                if (sharpAgain) {
                    sharpAgain = false
                    requestSharpViewport()
                }
            }
        }
    }

    /** Paint the [base] (paper/background/labels) and [ink] (flow + strokes) sharp layers. */
    private fun renderSharpFrame(
        base: RasterSurface,
        ink: RasterSurface,
        o: Pt,
        z: Double,
        res: Double,
        draws: List<SharpPageSnap>,
        withFlow: Boolean = !flowLifted,
    ) {
        val rb = base.renderer()
        rb.translate(o.x, o.y)
        rb.scale(z, z)
        val ri = ink.renderer()
        ri.translate(o.x, o.y)
        ri.scale(z, z)
        for (d in draws) {
            fillPaper(rb, d.page, d.pr, d.page)
            rb.save()
            rb.clipRect(d.pr)
            rb.translate(d.pr.left, d.pr.top)
            applyPageTransform(rb, d.page)
            if (paintPageBackground != null && d.region.w > 0.0 && d.region.h > 0.0) {
                paintPageBackground?.invoke(d.page, rb, res, d.region)
            }
            rb.restore()
            ri.save()
            ri.clipRect(d.pr)
            ri.translate(d.pr.left, d.pr.top)
            applyPageTransform(ri, d.page)
            val f = fade
            InkFade.paint(d.items.under, ri, f) { it.paint(ri) }
            if (withFlow && d.region.w > 0.0 && d.region.h > 0.0) {
                paintFlow?.invoke(d.page, ri, d.region)
            }
            InkFade.paint(d.items.over, ri, f) { it.paint(ri) }
            ri.restore()
        }
    }

    /** Paint a just-committed stroke into the live sharp ink layer so it stays crisp (no re-render). */
    private fun appendToSharpInk(page: Page, item: CanvasItem) {
        val f = sharpFrame ?: return
        val idx = document.pages.indexOf(page)
        val pr = pageRects.getOrNull(idx) ?: return
        val o0 = originFor(f.sx, f.sy, f.z)
        val r = f.ink.renderer()
        r.save()
        r.translate(o0.x, o0.y)
        r.scale(f.z, f.z)
        r.clipRect(pr)
        r.translate(pr.left, pr.top)
        applyPageTransform(r, page)
        paintItem(item, r)
        r.restore()
    }

    /** Repair an erased region of the live sharp ink layer in place (background layer untouched). */
    private fun repairSharpInk(page: Page, dirtyRect: Rect) {
        val f = sharpFrame ?: return
        val idx = document.pages.indexOf(page)
        val pr = pageRects.getOrNull(idx) ?: return
        val o0 = originFor(f.sx, f.sy, f.z)
        val r = f.ink.renderer()
        r.save()
        r.translate(o0.x, o0.y)
        r.scale(f.z, f.z)
        r.clipRect(pr)
        r.translate(pr.left, pr.top)
        applyPageTransform(r, page)
        r.clipRect(dirtyRect)
        r.clear()
        paintInk(page, r, dirtyRect)
        r.restore()
    }

    internal fun originFor(sx: Double, sy: Double, z: Double): Pt {
        val cw = contentW * z
        val ch = contentH * z
        val ox = if (!verticalScroll || freeMovement || cw >= clearW) -sx else insetLeft + (clearW - cw) / 2.0
        val oy = if (!verticalScroll || freeMovement || ch >= clearH) -sy else insetTop + (clearH - ch) / 2.0
        return Pt(ox, oy)
    }

    private fun visibleFor(sx: Double, sy: Double, z: Double): Rect {
        val o = originFor(sx, sy, z)
        return Rect.fromPoints(
            Pt(-o.x / z, -o.y / z),
            Pt((viewportW - o.x) / z, (viewportH - o.y) / z),
        )
    }

    /** One layer's share of the page caches: its bytes, and the screens of it built without a gap above and below the view. */
    class LayerSnapshot(val bytes: Long, val above: Double, val below: Double)

    /** What the page caches hold, for the debug overlay: each layer, and the bytes held in all against the budget. */
    class CacheSnapshot(val ink: LayerSnapshot, val background: LayerSnapshot, val bytes: Long, val budget: Long)

    fun cacheSnapshot(): CacheSnapshot {
        val layers = stores.map { store ->
            val held = store.now.values.sumOf { it.bytes() } + store.before.values.sumOf { it.bytes() }
            LayerSnapshot(held, reach(store, down = false), reach(store, down = true))
        }
        var bytes = layers.sumOf { it.bytes }
        for (e in hlCaches.values) bytes += e.surface.width.toLong() * e.surface.height * 4L
        return CacheSnapshot(layers[0], layers[1], bytes, cacheBudget())
    }

    /**
     * Screens of [store]'s layer built without a gap from the view's edge on, downward or upward.
     * A page with no such layer is no gap, nor is the end of the note; paginated, the row shown.
     */
    private fun reach(store: Layers, down: Boolean): Double {
        if (pageRects.size != document.pages.size || viewportH <= 0) return 0.0
        val view = visibleContentRect()
        val edge = if (down) view.bottom else view.top
        var end = edge
        val rows = if (verticalScroll) rowRanges() else listOf(drawablePageRange())
        for (row in if (down) rows else rows.asReversed()) {
            var gap: Double? = null
            var far: Double? = null
            for (i in row) {
                val pr = pageRects[i]
                if (if (down) pr.bottom <= edge else pr.top >= edge) continue
                far = if (down) max(far ?: pr.bottom, pr.bottom) else min(far ?: pr.top, pr.top)
                val g = gapPast(store, i, edge, down) ?: continue
                gap = if (gap == null) g else if (down) min(gap, g) else max(gap, g)
            }
            end = gap ?: far ?: continue
            if (gap != null) break
        }
        return abs(end - edge) * zoom / viewportH
    }

    /** Where page [i]'s first unbuilt band of [store] past [edge] begins (content space), or null with none. */
    private fun gapPast(store: Layers, i: Int, edge: Double, down: Boolean): Double? {
        val page = document.pages[i]
        if (store === bg && !hasPageBackground(page)) return null
        val pr = pageRects[i]
        val layer = store.now[page]?.takeIf { it.fits(page) } ?: return if (down) max(pr.top, edge) else min(pr.bottom, edge)
        var gap: Double? = null
        for (k in layer.bands.indices) {
            if (layer.bands[k] != null) continue
            val at = fromPageSpaceRect(i, layer.grid.onPage(k))
            if (if (down) at.bottom <= edge else at.top >= edge) continue
            val from = if (down) max(at.top, edge) else min(at.bottom, edge)
            gap = if (gap == null) from else if (down) min(gap, from) else max(gap, from)
        }
        return gap
    }

    /** Last note-open timings (ms), for the debug overlay; -1 until the first open. [lastOpenReadMs]
     *  is the off-thread file read alone; [lastOpenTotalMs] is the whole open the 160ms spinner sees. */
    var lastOpenReadMs = -1L
    var lastOpenTotalMs = -1L

    /** [lastOpenReadMs] broken into its phases: decompressing the manifest, turning that text into
     *  the model, streaming the images and PDF out, and re-simplifying ink from an old writer. */
    var lastOpenInflateMs = -1L
    var lastOpenParseMs = -1L
    var lastOpenAssetsMs = -1L
    var lastOpenCompactMs = -1L

    /** Whether the open compacted legacy ink (pre-writer-43 file), for the debug overlay. */
    var lastOpenCompacted = false

    /** The opened file's on-disk size at open time (static) and the size of the most recent
     *  save/autosave (live); -1 until a file-backed note is open. For the debug overlay. */
    var openFileBytes = -1L
    var lastSaveBytes = -1L

    /** Last note-save timings (ms), for the debug overlay; -1 until the first save.
     *  [lastSaveTotalMs] is the whole save the editor runs, from snapshot to bookkeeping done;
     *  the other three are its phases: the model deep copy, the encode+deflate into the temp
     *  file, and the temp-to-SAF byte copy. Set by the editor's note-save paths only. */
    var lastSaveTotalMs = -1L
    var lastSaveSnapshotMs = -1L
    var lastSaveEncodeMs = -1L
    var lastSaveCopyMs = -1L

    /** [lastSaveEncodeMs] split in two: the deflated manifest against the stored assets (images and
     *  the embedded PDF) streamed in behind it, which a PDF-backed note re-copies on every save. */
    var lastSaveManifestMs = -1L
    var lastSaveAssetsMs = -1L

    /** Of [lastSaveManifestMs], the part spent compressing rather than generating the JSON, and the
     *  uncompressed size that went into the deflater. */
    var lastSaveDeflateMs = -1L
    var lastSaveManifestBytes = -1L

    /** Autosave status for the debug overlay, set by the editor: "idle", "pending", "in progress",
     *  "done" or "failed". */
    var autosaveStatus = "idle"

    companion object {
        const val MARGIN = 48.0

        /** How long an expected edge move waits for its resize, and how far off a bar's thickness it may land, in px. */
        private const val EDGE_MOVE_MS = 1000L
        private const val EDGE_MOVE_SLOP = 3.0
        const val MIN_ZOOM = 0.12
        const val MAX_ZOOM = 16.0
        const val ZOOM_STEP = 1.25

        /** While a pinch's zoom is within this fraction of [fitWidthZoom] it sticks to fit-to-width. */
        const val SNAP_TO_FIT_WIDTH = 0.05
        const val CTRL_WHEEL_BASE = 1.01

        /** Rows in an ink band: few, since a pen lift makes every band it drew on upload again. */
        const val INK_BAND_PX = 512

        /** Rows in a background band: about a page, since PDFium pays again for every region asked of it. */
        const val BG_BAND_PX = 2048

        const val INK_GUTTER_PX = 1

        /** The share of the budget built ahead of the screen. The rest is what scrolling back finds still there. */
        const val FILL_SHARE = 0.75

        /** Wide enough that a picture a PDF enlarges many times over samples the same either side of a seam. */
        const val BG_GUTTER_PX = 32

        /** Padding (content px) around a highlighter's bounds in its cached bitmap, for AA edges. */
        const val HL_PAD = 2.0

        /** Padding (content px) around a replayed or repaired dirty rect, for AA edges. */
        const val SHARP_EDIT_PAD = 2.0

        /** Gap (viewport px) left above the page top so nothing hides behind the toolbar. */
        const val TOP_GAP = 16.0
        val TRANSPARENT = Rgba(0, 0, 0, 0)
    }
}
