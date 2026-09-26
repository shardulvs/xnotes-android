package com.xnotes.canvas

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.PageInsets
import com.xnotes.core.model.PageTemplates
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.insets
import com.xnotes.core.model.resolvedPageColor
import com.xnotes.core.model.resolvedTemplate
import com.xnotes.core.pal.Pen
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

/**
 * A rasterized page cache plus the resolution it was built at and the page-space rect it covers
 * (the page's footprint at build time). A margin edit moves that rect, so the entry is rebuilt on a
 * mismatch — and blitted stretched into the new one meanwhile, like a stale-resolution entry is
 * during a pinch.
 */
class CacheEntry(val surface: RasterSurface, val res: Double, val cover: Rect = Rect(0.0, 0.0, 0.0, 0.0))

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
)

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

    /** The middle of the clear area, where zoom steps anchor. */
    fun clearCenter(): Pt = Pt(insetLeft + clearW / 2.0, insetTop + clearH / 2.0)

    /** User-set zoom limits (preferences); every zoom path clamps into [minZoom]..[maxZoom]. */
    var minZoom: Double = MIN_ZOOM
    var maxZoom: Double = MAX_ZOOM

    /**
     * Long-edge cap (content px) for the on-screen page caches; zoom past it renders the visible
     * region live (the sharp viewport) instead of caching the whole page. User-set in preferences.
     */
    var maxCachePx: Double = 2048.0

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

    /** Margin (content px) above and below the content. Paginated it follows [sideMargin], so a
     *  0 px preference lets the fit-height magnet fill the viewport; vertical scrolling keeps the
     *  fixed [MARGIN] that holds the first page clear of the toolbar. */
    val vertMargin: Double get() = if (verticalScroll) MARGIN else sideMargin

    /** How pages group into layout rows (Single / Double / Cover); see [rowRanges]. */
    var viewingMode: ViewingMode = ViewingMode.SINGLE

    /**
     * True = continuous vertical scrolling (rows stack top-down). False = paginated: rows
     * lay out as a horizontal strip, the scroll window is clamped to [currentRow]'s span,
     * and the interaction layer flips between rows (edge-swipe; the change is instant).
     */
    var verticalScroll: Boolean = true

    /** The row the paginated view is on (its scroll window); moved by flips and [goToPage]. */
    var currentRow: Int = 0

    /**
     * Paginated edge-pull (viewport px, signed; positive pulls toward the next row). Like
     * [overscrollY] it is purely visual — [origin] shifts by it so the current row slides
     * against empty background under the finger — and on release the interaction layer
     * flips instantly or drops the pull.
     */
    var flipOffsetX: Double = 0.0

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

    /** Page width including its margins (unrotated). */
    fun outerW(page: Page): Double = insets(page).let { it.left + page.width + it.right }

    /** Page height including its margins (unrotated). */
    fun outerH(page: Page): Double = insets(page).let { it.top + page.height + it.bottom }

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
     * True while a flow-text caret session is live: screen ink caches build WITHOUT
     * the flow and [CanvasView] paints it immediate-mode each frame instead, so a
     * keystroke never waits for a cache rebuild. Session start/end must invalidate
     * the flow-bearing pages so the layers swap cleanly.
     */
    var flowLifted: Boolean = false

    /**
     * Per-page caches, split into two layers so an ink edit never re-rasterizes the
     * (costly) page background: [caches] holds the transparent **ink** layer (the
     * strokes/shapes/text), [bgCaches] holds the rendered **background** layer
     * (PDF/template) and stays empty when there is none. Both are blitted — background
     * then ink — over the paper fill. Surfaces are intentionally **not** recycled on
     * eviction; GC reclaims them once nothing holds them. Both maps are touched only on
     * the main thread.
     */
    private val caches = HashMap<Page, CacheEntry>()
    private val bgCaches = HashMap<Page, CacheEntry>()

    /**
     * Per-highlighter opaque-ribbon bitmaps. Highlighters can't be baked into [caches] (they
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

    /** Pages with a build in flight, so we never queue the same page twice. */
    private val pendingInk = HashSet<Page>()
    private val pendingBg = HashSet<Page>()

    /**
     * Pages whose cached ink is known to be out of date and is being rebuilt off-thread (see
     * [refreshAllInk]). The surface stays in [caches] and keeps being blitted meanwhile, so the page
     * shows its pre-edit content for a frame or two rather than blanking; the flag is what makes
     * [usableFor] say no, since an edit leaves the entry's cover and resolution both matching.
     */
    private val staleInk = HashSet<Page>()

    /**
     * Bumped by every cache invalidation. An in-flight async build captures the value at
     * schedule time and its result is discarded if the generation has since moved on, so
     * an edit (or zoom) that lands mid-build never gets overwritten by the stale surface.
     */
    private var cacheGen = 0

    // --- sharp viewport (past the resolution cap) ---

    // Two layers, like the page cache, so an erase can clear ink in a region without
    // disturbing the (PDF/paper) background underneath: [base] holds window bg + paper +
    // border + background + labels, [ink] holds just the strokes on a transparent surface.
    private class SharpFrame(
        val base: RasterSurface,
        val ink: RasterSurface,
        val sx: Double,
        val sy: Double,
        val z: Double,
        val gen: Int,
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
        val items: List<CanvasItem>,
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
            var y = vertMargin // vertical top margin (keeps the page below the toolbar)
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

    /** The paginated scroll target for [rowIndex]: keep the zoom, land at the row's top. */
    fun rowTargetScroll(rowIndex: Int): Pt {
        val rows = rowRanges()
        if (rows.isEmpty()) return Pt(0.0, 0.0)
        val rb = rowBounds(rows[rowIndex.coerceIn(0, rows.lastIndex)])
        val minX = (rb.left - sideMargin) * zoom - insetLeft
        val maxX = (rb.right + sideMargin) * zoom - viewportW + insetRight
        val sx = if (maxX < minX) (minX + maxX) / 2.0 else minX
        val sy = (rb.top * zoom - TOP_GAP - insetTop).coerceAtLeast(-insetTop)
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
        // Paginated: the row clamp centres the row when it fits, so the scroll always wins; the
        // whole strip must never be centred (two pages would push each single-page row off centre).
        val ox = (if (!verticalScroll || cw >= clearW) -scrollX else insetLeft + (clearW - cw) / 2.0) - flipOffsetX
        val oy = (if (ch < clearH) insetTop + (clearH - ch) / 2.0 else -scrollY) - overscrollY
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

    // A floating bar lets the scroll run past the content's edges by as much as it covers.
    fun minScrollX(): Double = -insetLeft
    fun minScrollY(): Double = -insetTop
    fun maxScrollX(): Double = max(minScrollX(), ceil(contentW * zoom - viewportW + insetRight))
    fun maxScrollY(): Double = max(minScrollY(), ceil(contentH * zoom - viewportH + insetBottom))

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
        val maxY = ((rb.bottom + vertMargin) * zoom - viewportH + insetBottom).coerceAtLeast(-insetTop)
        return Pt(cx, sy.coerceIn(-insetTop, maxY))
    }

    fun scrollBy(dx: Double, dy: Double) {
        scrollX += dx
        scrollY += dy
        clampScroll()
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
        // page is hidden behind the chrome.
        scrollY = (top * zoom - TOP_GAP - insetTop).coerceAtLeast(-insetTop)
        scrollX = centerX * zoom - clearCenter().x
        clampScroll()
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
        scrollX = minScrollX()
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

    private fun clampedRes(page: Page): Double {
        var res = zoom * renderScale
        val longEdge = max(outerW(page), outerH(page))
        if (longEdge * res > maxCachePx) res = maxCachePx / longEdge
        return res.coerceAtLeast(0.01)
    }

    fun cacheFor(page: Page): CacheEntry {
        val res = clampedRes(page)
        caches[page]?.let { if (it.usableFor(page, res)) return it }
        return buildCache(page, res).also { caches[page] = it; staleInk.remove(page) }
    }

    /** Whether this entry still matches [page]'s footprint and the [target] resolution, and holds
     *  the page's current content ([staleInk]). */
    private fun CacheEntry.usableFor(page: Page, target: Double): Boolean =
        page !in staleInk && cover == footprint(page) && (zoomingInProgress || abs(res - target) < 1e-6)

    /**
     * Like [cacheFor] but never rasterizes on the calling (UI) thread: returns the ready
     * surface when one exists, otherwise schedules the build on [runAsync] and returns the
     * stale-resolution surface to blit meanwhile (or null when the page has never been
     * cached, in which case the caller draws bare paper until the build lands).
     */
    fun cacheForOrSchedule(page: Page): CacheEntry? {
        val res = clampedRes(page)
        val existing = caches[page]
        if (existing != null && existing.usableFor(page, res)) return existing
        scheduleInk(page, res)
        // Sync scheduler filled it; async leaves the stale entry (or null). A stale *resolution* is
        // still blitted (scaled) so a pinch never flashes, but a stale *footprint* is not: stretching
        // a whole page while a margin slider is dragged reads far worse than bare paper does.
        return caches[page]?.takeIf { it.cover == footprint(page) }
    }

    private fun scheduleInk(page: Page, res: Double) {
        if (!pendingInk.add(page)) return
        val gen = cacheGen
        val items = cacheItems(page) // snapshot on the UI thread
        val withFlow = !flowLifted // snapshot too; the generation guard discards stale builds
        runAsync {
            val entry = renderInk(page, res, items, withFlow)
            postToMain {
                pendingInk.remove(page)
                if (gen == cacheGen) {
                    caches[page] = entry
                    staleInk.remove(page)
                }
                // Repaint either way. A build the page outgrew mid-flight (a margin drag bumps the
                // generation on every tick) is discarded here, and the draw loop is what schedules
                // its replacement — but every frame while this one was in flight was turned away by
                // [pendingInk], so without this the page would sit blank until something else drew.
                onCacheReady?.invoke()
            }
        }
    }

    /** Items baked into a page's ink cache: all but lifted items and highlighters
     *  ([isHighlighterInk] — those composite live so they MULTIPLY against the background). */
    private fun cacheItems(page: Page): List<CanvasItem> =
        page.items.filter { !isLiftedItem(it) && !it.isHighlighterInk() }

    private fun buildCache(page: Page, res: Double): CacheEntry =
        renderInk(page, res, cacheItems(page), includeFlow = !flowLifted)

    private fun renderInk(page: Page, res: Double, items: List<CanvasItem>, includeFlow: Boolean): CacheEntry {
        val cover = footprint(page)
        val w = ceil(cover.w * res).toInt().coerceAtLeast(1)
        val h = ceil(cover.h * res).toInt().coerceAtLeast(1)
        val surface = surfaceFactory.create(w, h, 1.0)
        surface.fill(TRANSPARENT)
        val r = surface.renderer()
        r.scale(res, res)
        r.translate(-cover.left, -cover.top) // the surface covers the margins, so page space starts inset
        if (includeFlow) paintFlow?.invoke(page, r, cover)
        for (item in items) item.paint(r)
        noteGeometryBuilt(page)
        return CacheEntry(surface, res, cover)
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
        if (existing != null && existing.geom === g && existing.opaque == opaque &&
            (zoomingInProgress || abs(existing.res - res) < 1e-6)
        ) {
            return existing
        }
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
        return HighlighterCacheEntry(surface, res, g, opaque, cover)
    }

    /**
     * Whether [page] has anything in its background layer — a PDF page or a resolved ruling.
     * Plain colour pages return false so no (large, transparent) background surface is allocated,
     * even though [paintPageBackground] is always installed for the pattern path.
     */
    fun hasPageBackground(page: Page): Boolean =
        paintPageBackground != null && (page.pdfPage != null || effectiveTemplate(page) != PageTemplates.NONE)

    /**
     * The page's rendered background layer (PDF/template) at the current resolution,
     * or null when the document has no page background. Built once and reused across
     * ink edits — rebuilt only when the resolution changes — so erasing/repairing ink
     * never re-rasterizes the (expensive) background.
     */
    fun backgroundFor(page: Page): CacheEntry? {
        if (!hasPageBackground(page)) return null
        val res = clampedRes(page)
        val existing = bgCaches[page]
        if (existing != null && existing.usableFor(page, res)) return existing
        return buildBackground(page, res).also { bgCaches[page] = it }
    }

    /** Non-blocking counterpart to [backgroundFor]; see [cacheForOrSchedule]. */
    fun backgroundForOrSchedule(page: Page): CacheEntry? {
        if (!hasPageBackground(page)) return null
        val res = clampedRes(page)
        val existing = bgCaches[page]
        if (existing != null && existing.usableFor(page, res)) return existing
        scheduleBg(page, res)
        return bgCaches[page]?.takeIf { it.cover == footprint(page) }
    }

    private fun scheduleBg(page: Page, res: Double) {
        if (!pendingBg.add(page)) return
        val gen = cacheGen
        runAsync {
            val entry = buildBackground(page, res)
            postToMain {
                pendingBg.remove(page)
                if (gen == cacheGen) bgCaches[page] = entry
                onCacheReady?.invoke() // discarded or not; see [scheduleInk]
            }
        }
    }

    private fun buildBackground(page: Page, res: Double): CacheEntry {
        val cover = footprint(page)
        val w = ceil(cover.w * res).toInt().coerceAtLeast(1)
        val h = ceil(cover.h * res).toInt().coerceAtLeast(1)
        val surface = surfaceFactory.create(w, h, 1.0)
        surface.fill(TRANSPARENT)
        val r = surface.renderer()
        r.scale(res, res)
        r.translate(-cover.left, -cover.top)
        paintPageBackground?.invoke(page, r, res, cover)
        return CacheEntry(surface, res, cover)
    }

    /** Append a single just-committed stroke into an existing cache (cheap), else rebuild. */
    fun appendToCache(page: Page, item: CanvasItem) {
        if (item.isHighlighterInk()) return // composited live over the page, never cached
        appendToSharpInk(page, item) // keep the sharp viewport crisp without a full re-render
        if (pendingSharp) pendingSharpEdits.add(page to item.paintBounds().outset(SHARP_EDIT_PAD))
        val res = clampedRes(page)
        val existing = caches[page]
        if (existing == null || !existing.usableFor(page, res)) {
            invalidatePage(page)
            return
        }
        val cover = existing.cover
        val r = existing.surface.renderer()
        r.scale(res, res)
        r.translate(-cover.left, -cover.top)
        item.paint(r)
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
        val entry = caches[page] ?: return false
        val cover = entry.cover
        val r = entry.surface.renderer()
        r.save()
        r.scale(entry.res, entry.res)
        r.translate(-cover.left, -cover.top)
        r.clipRect(dirtyRect)
        r.clear()
        if (!flowLifted) paintFlow?.invoke(page, r, dirtyRect)
        for (item in page.items) {
            if (!isLiftedItem(item) && !item.isHighlighterInk() && item.paintBounds().intersects(dirtyRect)) item.paint(r)
        }
        r.restore()
        return true
    }

    fun invalidatePage(page: Page) {
        caches.remove(page)
        staleInk.remove(page)
        cacheGen++
        sharpGen++
    }

    /**
     * Page-style invalidation. The paper colour is filled **live** each frame in [CanvasView] (not
     * baked into [caches]/[bgCaches]), so a colour-only change just needs the sharp viewport — which
     * *does* bake the paper — to re-render: bump [sharpGen]. A ruling, by contrast, lives in the
     * background cache, so a pattern/spacing/pattern-colour change must drop that page's background
     * and let the draw loop rebuild via [backgroundForOrSchedule]. Unlike
     * [refreshBackground] (the PDF-refine path) these do **not** early-return on a missing cache, so a
     * plain page that *gains* a ruling rebuilds correctly; one that loses it stops drawing a background
     * (see [hasPageBackground]).
     */
    fun invalidatePaper() {
        sharpGen++
    }

    fun invalidateBackground(page: Page) {
        bgCaches.remove(page)
        cacheGen++
        sharpGen++
    }

    fun invalidateAllBackgrounds() {
        bgCaches.clear()
        cacheGen++
        sharpGen++
    }

    /**
     * Page footprints changed (a margin edit). The surfaces are kept rather than cleared, but they
     * are not drawn while they are the wrong size (see [cacheForOrSchedule]) — the page shows bare
     * paper until its rebuild lands. Keeping them means a slider dragged back to a size already
     * rasterized shows that page again at once, with nothing to rebuild. The size mismatch itself is
     * what schedules the rebuild (see [usableFor]).
     */
    fun invalidatePageGeometry() {
        cacheGen++
        sharpGen++
    }

    fun invalidateAllCaches() {
        caches.clear()
        staleInk.clear()
        bgCaches.clear()
        hlCaches.clear()
        wetInk.clear()
        cacheGen++
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
     * So it schedules instead, exactly like [refreshBackground] does for a PDF page: the current
     * surface stays in [caches] and keeps being blitted until the rebuild lands, so the page shows
     * its pre-edit content for a frame or two rather than blanking (the flicker [invalidateAllCaches]
     * caused). [staleInk] is what stops [usableFor] handing that surface back as current — an edit
     * changes neither the cover nor the resolution, so nothing else would notice.
     *
     * Bumps [cacheGen] so a build scheduled before the edit is discarded on publish rather than
     * overwriting the page with its pre-edit snapshot, and [sharpGen] because the sharp viewport can
     * only be patched by region ([repairSharpInk]) and there are none: it drops to the soft cache
     * underneath and re-renders once the view settles. The (PDF/template) background layer is left
     * untouched — undo/redo never edits it, so it must not flash either.
     */
    fun refreshAllInk() {
        cacheGen++
        sharpGen++
        for (page in caches.keys.toList()) {
            staleInk.add(page)
            scheduleInk(page, clampedRes(page))
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
        cacheGen++
    }

    /**
     * Re-render only [page]'s background layer (e.g. a PDF page whose embedded-image colours just
     * finished parsing), swapping the refreshed surface in when it's ready and leaving the current
     * one on screen until then so the page never blanks. Unlike a global background flush this
     * touches *only* [page]: other pages' cached backgrounds and in-flight builds are left intact,
     * so refining one page never re-rasterizes — or flickers — the rest of the visible pages.
     *
     * Skips pages with no live background cache (off-screen now): they render stamped on their own
     * when next scrolled into view. The rebuild is scheduled at the current generation, so on the
     * single-threaded cache executor it lands after the (already-published) provisional build and
     * its stamped surface wins.
     */
    fun refreshBackground(page: Page) {
        if (paintPageBackground == null) return
        if (!bgCaches.containsKey(page)) return
        sharpGen++ // also refine the sharp viewport if it's covering this page (deep zoom)
        scheduleBg(page, clampedRes(page))
    }

    /**
     * Invalidate caches after a *zoom* without dropping the surfaces. The old-resolution
     * bitmaps stay in the maps, so [cacheForOrSchedule]/[backgroundForOrSchedule] keep
     * blitting them (scaled) for the new zoom until the sharp rebuild lands — avoiding the
     * one-frame empty-canvas flash that clearing would cause when a pinch ends. A page whose
     * clamped resolution is unchanged (already at the [maxCachePx] cap) matches on res and
     * is returned as-is, so it is neither flashed nor needlessly rebuilt.
     *
     * Bumping [cacheGen] discards any in-flight build captured at the previous generation, so
     * a stale-resolution surface can't be published over the maps after the zoom changed. Use
     * [invalidateAllCaches] instead when page *content* changed — that must rebuild even at the
     * same resolution.
     */
    fun invalidateCachesForZoom() {
        cacheGen++
    }

    fun dropCachesExcept(visible: Set<Page>) {
        caches.keys.retainAll(visible)
        staleInk.retainAll(visible)
        bgCaches.keys.retainAll(visible)
        if (hlCaches.isNotEmpty()) {
            val keep = HashSet<Stroke>()
            for (p in visible) for (it in p.items) if (it is Stroke && it.tool == Tool.HIGHLIGHTER) keep.add(it)
            hlCaches.keys.retainAll(keep)
        }
        releaseGeometryExcept(visible)
    }

    /** Pages to build ahead of the viewport ([prefetch], nearest first) and pages whose caches survive the frame ([keep]). */
    class CacheBand(val prefetch: List<Page>, val keep: Set<Page>)

    /**
     * Rows within a screenful of the viewport (at least the adjacent one; paginated, one row either side), kept one
     * row further. Never sized by the visible page count: that flips as page edges cross the viewport, and every
     * flip evicted the pages just prefetched.
     */
    fun cacheBand(): CacheBand {
        val pages = document.pages
        val rows = rowRanges()
        if (rows.isEmpty()) return CacheBand(emptyList(), emptySet())
        if (pageRects.size != pages.size) return CacheBand(emptyList(), pages.toHashSet()) // not laid out yet
        // Rows on screen are first..last (last = first - 1 when the view sits in a gap); lo..hi adds the prefetch.
        var first: Int
        var last: Int
        var lo: Int
        var hi: Int
        if (verticalScroll) {
            val v = visibleContentRect()
            fun top(r: Int) = rows[r].minOf { pageRects[it].top }
            fun bottom(r: Int) = rows[r].maxOf { pageRects[it].bottom }
            first = 0
            while (first < rows.size && bottom(first) < v.top) first++
            last = rows.lastIndex
            while (last >= 0 && top(last) > v.bottom) last--
            hi = last + 1
            while (hi < rows.lastIndex && top(hi + 1) <= v.bottom + v.h) hi++
            lo = first - 1
            while (lo > 0 && bottom(lo - 1) >= v.top - v.h) lo--
        } else {
            first = currentRow.coerceIn(0, rows.lastIndex)
            last = first
            lo = first - 1
            hi = first + 1
        }
        val prefetch = ArrayList<Page>()
        for (r in first..last) for (i in rows[r]) prefetch += pages[i]
        for (d in 1..max(hi - last, first - lo)) {
            for (r in intArrayOf(last + d, first - d)) {
                if (r in lo..hi && r in rows.indices) for (i in rows[r]) prefetch += pages[i]
            }
        }
        val keep = HashSet<Page>()
        for (r in max(lo - 1, 0)..min(hi + 1, rows.lastIndex)) for (i in rows[r]) keep += pages[i]
        return CacheBand(prefetch, keep)
    }

    /** Runs after the visible pages are scheduled, so they build first; a pinch moving the zoom keeps the band but prefetches nothing. */
    fun prefetchAndPrune() {
        val band = cacheBand()
        if (!zoomingInProgress) {
            for (page in band.prefetch) {
                backgroundForOrSchedule(page)
                cacheForOrSchedule(page)
            }
        }
        dropCachesExcept(band.keep)
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

    /** True when the current zoom pushes a visible page past [maxCachePx] (its cache is clamped). */
    fun isPastResolutionCap(): Boolean {
        val target = zoom * renderScale
        val visible = visibleContentRect()
        for (i in pageRects.indices) {
            val pr = pageRects.getOrNull(i) ?: continue
            if (!pr.intersects(visible)) continue
            val page = document.pages[i]
            if (target * max(outerW(page), outerH(page)) > maxCachePx) return true
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
        if (f.gen != sharpGen) return null
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
        if (pendingSharp || zoomingInProgress || viewportW <= 0 || viewportH <= 0) return
        if (!isPastResolutionCap()) return
        val gen = sharpGen
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
        val withFlow = !flowLifted // snapshot; a session toggle bumps sharpGen and discards this
        runAsync {
            val base = surfaceFactory.create(vw, vh, 1.0).also { it.fill(bg) }
            val ink = surfaceFactory.create(vw, vh, 1.0).also { it.fill(TRANSPARENT) }
            renderSharpFrame(base, ink, o, z, res, draws, withFlow)
            postToMain {
                pendingSharp = false
                if (gen == sharpGen && sx == scrollX && sy == scrollY && z == zoom) {
                    sharpFrame = SharpFrame(base, ink, sx, sy, z, gen)
                    // Replay edits committed during the build; the item snapshot predates them.
                    for ((p, rect) in pendingSharpEdits) repairSharpInk(p, rect)
                    onCacheReady?.invoke()
                }
                pendingSharpEdits.clear()
            }
        }
    }

    /** Paint the [base] (paper/border/background/labels) and [ink] (flow + strokes) sharp layers. */
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
        val border = Pen(palette.paperBorder, 1.0, cosmetic = true)
        for (d in draws) {
            rb.fillRect(d.pr, paperColor(d.page))
            if (pageBorders) rb.strokeRect(d.pr, border)
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
            if (withFlow && d.region.w > 0.0 && d.region.h > 0.0) {
                paintFlow?.invoke(d.page, ri, d.region)
            }
            for (item in d.items) item.paint(ri)
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
        item.paint(r)
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
        if (!flowLifted) paintFlow?.invoke(page, r, dirtyRect)
        for (item in page.items) {
            if (!isLiftedItem(item) && !item.isHighlighterInk() && item.paintBounds().intersects(dirtyRect)) item.paint(r)
        }
        r.restore()
    }

    private fun originFor(sx: Double, sy: Double, z: Double): Pt {
        val cw = contentW * z
        val ch = contentH * z
        val ox = if (!verticalScroll || cw >= clearW) -sx else insetLeft + (clearW - cw) / 2.0
        val oy = if (ch < clearH) insetTop + (clearH - ch) / 2.0 else -sy
        return Pt(ox, oy)
    }

    private fun visibleFor(sx: Double, sy: Double, z: Double): Rect {
        val o = originFor(sx, sy, z)
        return Rect.fromPoints(
            Pt(-o.x / z, -o.y / z),
            Pt((viewportW - o.x) / z, (viewportH - o.y) / z),
        )
    }

    /** A read-only count of the live page caches and their bitmap bytes, for the debug overlay. */
    class CacheSnapshot(
        val visiblePages: Int,
        val inkPages: Int,
        val bgPages: Int,
        val bytes: Long,
    )

    fun cacheSnapshot(): CacheSnapshot {
        var bytes = 0L
        for (e in caches.values) bytes += e.surface.width.toLong() * e.surface.height * 4L
        for (e in bgCaches.values) bytes += e.surface.width.toLong() * e.surface.height * 4L
        for (e in hlCaches.values) bytes += e.surface.width.toLong() * e.surface.height * 4L
        val visible = visiblePageRange()?.let { it.last - it.first + 1 } ?: 0
        return CacheSnapshot(visible, caches.size, bgCaches.size, bytes)
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

    /**
     * The pixel dimensions the current page's cache *would* be built at for the current
     * zoom (i.e. [clampedRes] applied). Tracks live while zooming — unlike the actual
     * cached surface, which is blitted stale-scaled during a pinch and only rebuilt when
     * the gesture ends. Used by the debug overlay's `res` line; returns 0×0 with no pages.
     */
    fun targetRasterSize(): Pair<Int, Int> {
        val pages = document.pages
        if (pages.isEmpty()) return 0 to 0
        val page = pages[currentPageIndex()]
        val res = clampedRes(page)
        val w = ceil(outerW(page) * res).toInt().coerceAtLeast(1)
        val h = ceil(outerH(page) * res).toInt().coerceAtLeast(1)
        return w to h
    }

    companion object {
        const val MARGIN = 48.0
        const val MIN_ZOOM = 0.12
        const val MAX_ZOOM = 16.0
        const val ZOOM_STEP = 1.25

        /** While a pinch's zoom is within this fraction of [fitWidthZoom] it sticks to fit-to-width. */
        const val SNAP_TO_FIT_WIDTH = 0.05
        const val CTRL_WHEEL_BASE = 1.01

        /** Padding (content px) around a highlighter's bounds in its cached bitmap, for AA edges. */
        const val HL_PAD = 2.0

        /** Padding (content px) around a replayed or repaired dirty rect, for AA edges. */
        const val SHARP_EDIT_PAD = 2.0

        /** Gap (viewport px) left above the page top so nothing hides behind the toolbar. */
        const val TOP_GAP = 16.0
        val TRANSPARENT = Rgba(0, 0, 0, 0)
    }
}
