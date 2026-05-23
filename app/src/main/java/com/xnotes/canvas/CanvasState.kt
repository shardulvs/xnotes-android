package com.xnotes.canvas

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.RasterSurface
import com.xnotes.core.pal.SurfaceFactory
import com.xnotes.ui.theme.Palette
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** A rasterized page cache plus the resolution it was built at. */
class CacheEntry(val surface: RasterSurface, val res: Double)

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
    var pageColorOverride: Rgba? = null
    var didInitialFit: Boolean = false

    /** Horizontal margin on each side of the page column (0 ⇒ fit-width fills the viewport). */
    var sideMargin: Double = MARGIN

    /** While true (during a pinch/zoom drag) caches are blitted stale-scaled
     *  instead of rebuilt every frame; they rebuild at the final resolution when
     *  the gesture ends. */
    var zoomingInProgress: Boolean = false

    /** When true, zoom is fixed (pinch pans only, zoom buttons/fit are no-ops). */
    var zoomLocked: Boolean = false

    /** Items excluded from the cache (lifted for selection/editing); set by the interaction layer. */
    var isLiftedItem: (CanvasItem) -> Boolean = { false }

    /** Optional page-background painter (PDF / template) drawn into the cache before items. */
    var paintPageBackground: ((page: Page, renderer: com.xnotes.core.pal.Renderer, res: Double) -> Unit)? = null

    /**
     * Per-page ink caches. Surfaces are intentionally **not** recycled on eviction:
     * the presentation thread reads a page's cache surface off the main thread, so a
     * surface must stay valid after it leaves this map — GC reclaims it once nothing
     * holds it. The map itself is touched only on the main thread.
     */
    private val caches = HashMap<Page, CacheEntry>()

    var pageRects: List<Rect> = emptyList()
        private set
    var contentW: Double = 2 * MARGIN
        private set
    var contentH: Double = 2 * MARGIN
        private set

    // --- layout ---

    fun relayout() {
        val pages = document.pages
        if (pages.isEmpty()) {
            pageRects = emptyList()
            contentW = 2 * sideMargin
            contentH = 2 * MARGIN
            return
        }
        val maxW = pages.maxOf { it.width }
        val rects = ArrayList<Rect>(pages.size)
        var y = MARGIN // vertical top margin (keeps the page below the toolbar)
        for (p in pages) {
            val left = sideMargin + (maxW - p.width) / 2.0
            rects.add(Rect(left, y, p.width, p.height))
            y += p.height + GAP
        }
        pageRects = rects
        contentW = maxW + 2 * sideMargin
        contentH = (y - GAP) + MARGIN
        clampScroll()
    }

    // --- transforms ---

    fun origin(): Pt {
        val cw = contentW * zoom
        val ch = contentH * zoom
        val ox = if (cw < viewportW) (viewportW - cw) / 2.0 else -scrollX
        val oy = if (ch < viewportH) (viewportH - ch) / 2.0 else -scrollY
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

    fun maxScrollX(): Double = max(0.0, ceil(contentW * zoom - viewportW))
    fun maxScrollY(): Double = max(0.0, ceil(contentH * zoom - viewportH))

    fun clampScroll() {
        scrollX = scrollX.coerceIn(0.0, maxScrollX())
        scrollY = scrollY.coerceIn(0.0, maxScrollY())
    }

    fun scrollBy(dx: Double, dy: Double) {
        scrollX += dx
        scrollY += dy
        clampScroll()
    }

    // --- pages & navigation ---

    fun paperColor(page: Page): Rgba = pageColorOverride ?: palette.paper

    /** Index of the page whose rect contains a content-space point, or null. */
    fun pageIndexAtContent(p: Pt): Int? {
        for (i in pageRects.indices) if (pageRects[i].contains(p)) return i
        return null
    }

    /** The current page (spec 05 §4): contains the viewport vertical centre, biased by half a gap. */
    fun currentPageIndex(): Int {
        if (pageRects.isEmpty()) return 0
        val centerY = viewportToContent(Pt(viewportW / 2.0, viewportH / 2.0)).y
        for (i in pageRects.indices) {
            if (pageRects[i].bottom + GAP / 2.0 > centerY) return i
        }
        return pageRects.size - 1
    }

    fun goToPage(index: Int) {
        if (pageRects.isEmpty()) return
        val i = index.coerceIn(0, pageRects.size - 1)
        val pr = pageRects[i]
        // Scroll so the page label (just above the page top) clears the toolbar with a small gap,
        // so no part of the page is hidden behind the chrome.
        scrollY = ((pr.top - PAGE_LABEL_OFFSET) * zoom - TOP_GAP).coerceAtLeast(0.0)
        scrollX = pr.centerX * zoom - viewportW / 2.0
        clampScroll()
    }

    // --- zoom ---

    fun setZoomAnchored(focusViewport: Pt, newZoom: Double) {
        if (zoomLocked) return
        val z = newZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (abs(z - zoom) < 1e-9) return
        val anchor = viewportToContent(focusViewport)
        zoom = z
        scrollX = anchor.x * z - focusViewport.x
        scrollY = anchor.y * z - focusViewport.y
        invalidateAllCaches()
        clampScroll()
    }

    fun zoomByStep(zoomIn: Boolean) {
        val factor = if (zoomIn) ZOOM_STEP else 1.0 / ZOOM_STEP
        setZoomAnchored(Pt(viewportW / 2.0, viewportH / 2.0), zoom * factor)
    }

    fun fitWidth() {
        if (zoomLocked || contentW <= 0.0 || viewportW == 0) return
        val cur = currentPageIndex()
        zoom = (viewportW / contentW).coerceIn(MIN_ZOOM, MAX_ZOOM)
        invalidateAllCaches()
        goToPage(cur)
    }

    fun fitHeight() {
        val pages = document.pages
        if (zoomLocked || pages.isEmpty() || viewportH == 0) return
        val cur = currentPageIndex()
        zoom = ((viewportH - 60.0) / pages[cur].height).coerceIn(MIN_ZOOM, MAX_ZOOM)
        invalidateAllCaches()
        goToPage(cur)
    }

    fun fitPage() {
        val pages = document.pages
        if (zoomLocked || pages.isEmpty() || viewportW == 0 || viewportH == 0) return
        val cur = currentPageIndex()
        val page = pages[cur]
        zoom = min((viewportW - 60.0) / page.width, (viewportH - 60.0) / page.height).coerceIn(MIN_ZOOM, MAX_ZOOM)
        invalidateAllCaches()
        goToPage(cur)
    }

    // --- page cache ---

    private fun clampedRes(page: Page): Double {
        var res = zoom * renderScale
        val longest = max(page.width, page.height) * res
        if (longest > MAX_CACHE_PX) res = MAX_CACHE_PX / max(page.width, page.height)
        return res.coerceAtLeast(0.01)
    }

    fun cacheFor(page: Page): CacheEntry {
        val res = clampedRes(page)
        val existing = caches[page]
        if (existing != null && (zoomingInProgress || abs(existing.res - res) < 1e-6)) return existing
        return buildCache(page, res).also { caches[page] = it }
    }

    private fun buildCache(page: Page, res: Double): CacheEntry {
        val w = ceil(page.width * res).toInt().coerceAtLeast(1)
        val h = ceil(page.height * res).toInt().coerceAtLeast(1)
        val surface = surfaceFactory.create(w, h, 1.0)
        surface.fill(TRANSPARENT)
        val r = surface.renderer()
        r.scale(res, res)
        paintPageBackground?.invoke(page, r, res)
        for (item in page.items) if (!isLiftedItem(item)) item.paint(r)
        return CacheEntry(surface, res)
    }

    /** Append a single just-committed stroke into an existing cache (cheap), else rebuild. */
    fun appendToCache(page: Page, item: CanvasItem) {
        val res = clampedRes(page)
        val existing = caches[page]
        if (existing == null || abs(existing.res - res) > 1e-6) {
            invalidatePage(page)
            return
        }
        val r = existing.surface.renderer()
        r.scale(res, res)
        item.paint(r)
    }

    /**
     * Repair just [dirtyRect] (page-local content space) of [page]'s cache in
     * place, instead of rebuilding the whole page — used after the eraser removes
     * strokes from a small area. Clears the region and repaints only the surviving
     * items overlapping it, so the cost scales with the dirty area, not the page.
     *
     * Returns false when it can't safely repair (no live cache, or a baked page
     * background that would be costly to re-rasterize); the caller should then
     * [invalidatePage] for a full rebuild.
     */
    fun repairRegion(page: Page, dirtyRect: Rect): Boolean {
        val entry = caches[page] ?: return false
        if (paintPageBackground != null) return false
        val r = entry.surface.renderer()
        r.save()
        r.scale(entry.res, entry.res)
        r.clipRect(dirtyRect)
        r.clear()
        for (item in page.items) {
            if (!isLiftedItem(item) && item.bounds().intersects(dirtyRect)) item.paint(r)
        }
        r.restore()
        return true
    }

    fun invalidatePage(page: Page) {
        caches.remove(page)
    }

    fun invalidateAllCaches() {
        caches.clear()
    }

    fun dropCachesExcept(visible: Set<Page>) {
        val it = caches.iterator()
        while (it.hasNext()) {
            val (page, _) = it.next()
            if (page !in visible) it.remove()
        }
    }

    companion object {
        const val MARGIN = 48.0
        const val GAP = 38.0
        const val MIN_ZOOM = 0.12
        const val MAX_ZOOM = 8.0
        const val ZOOM_STEP = 1.25
        const val CTRL_WHEEL_BASE = 1.01
        const val MAX_CACHE_PX = 4096.0

        /** The page label sits ~26px above the page top (content space). */
        const val PAGE_LABEL_OFFSET = 26.0

        /** Gap (viewport px) left above the page label so nothing hides behind the toolbar. */
        const val TOP_GAP = 16.0
        val TRANSPARENT = Rgba(0, 0, 0, 0)
    }
}
