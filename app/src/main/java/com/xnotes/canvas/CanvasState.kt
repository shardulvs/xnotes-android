package com.xnotes.canvas

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.RasterSurface
import com.xnotes.core.pal.Renderer
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

    /**
     * Optional page-background painter (PDF / template). [region] is the page-local content rect
     * to render — the whole page for the page cache/thumbnails, or just the visible sub-rect for
     * the sharp viewport — so the painter can rasterize only that slice at full resolution.
     */
    var paintPageBackground: ((page: Page, renderer: Renderer, res: Double, region: Rect) -> Unit)? = null

    /**
     * Per-page caches, split into two layers so an ink edit never re-rasterizes the
     * (costly) page background: [caches] holds the transparent **ink** layer (the
     * strokes/shapes/text), [bgCaches] holds the rendered **background** layer
     * (PDF/template) and stays empty when there is none. Both are blitted — background
     * then ink — over the paper fill. Surfaces are intentionally **not** recycled on
     * eviction: the presentation thread reads them off the main thread, so they must
     * stay valid after leaving the map; GC reclaims them once nothing holds them. Both
     * maps are touched only on the main thread.
     */
    private val caches = HashMap<Page, CacheEntry>()
    private val bgCaches = HashMap<Page, CacheEntry>()

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
     * Bumped by every cache invalidation. An in-flight async build captures the value at
     * schedule time and its result is discarded if the generation has since moved on, so
     * an edit (or zoom) that lands mid-build never gets overwritten by the stale surface.
     */
    private var cacheGen = 0

    // --- sharp viewport (past the resolution cap) ---

    private class SharpFrame(val surface: RasterSurface, val sx: Double, val sy: Double, val z: Double, val gen: Int)

    private var sharpFrame: SharpFrame? = null
    private var pendingSharp = false

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

    fun relayout() {
        sharpGen++ // page layout / viewport size changed: the sharp viewport must re-render
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
        invalidateCachesForZoom()
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
        invalidateCachesForZoom()
        goToPage(cur)
    }

    fun fitHeight() {
        val pages = document.pages
        if (zoomLocked || pages.isEmpty() || viewportH == 0) return
        val cur = currentPageIndex()
        zoom = ((viewportH - 60.0) / pages[cur].height).coerceIn(MIN_ZOOM, MAX_ZOOM)
        invalidateCachesForZoom()
        goToPage(cur)
    }

    fun fitPage() {
        val pages = document.pages
        if (zoomLocked || pages.isEmpty() || viewportW == 0 || viewportH == 0) return
        val cur = currentPageIndex()
        val page = pages[cur]
        zoom = min((viewportW - 60.0) / page.width, (viewportH - 60.0) / page.height).coerceIn(MIN_ZOOM, MAX_ZOOM)
        invalidateCachesForZoom()
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

    /**
     * Like [cacheFor] but never rasterizes on the calling (UI) thread: returns the ready
     * surface when one exists, otherwise schedules the build on [runAsync] and returns the
     * stale-resolution surface to blit meanwhile (or null when the page has never been
     * cached, in which case the caller draws bare paper until the build lands).
     */
    fun cacheForOrSchedule(page: Page): CacheEntry? {
        val res = clampedRes(page)
        val existing = caches[page]
        if (existing != null && (zoomingInProgress || abs(existing.res - res) < 1e-6)) return existing
        scheduleInk(page, res)
        return caches[page] // sync scheduler filled it; async leaves the stale entry (or null)
    }

    private fun scheduleInk(page: Page, res: Double) {
        if (!pendingInk.add(page)) return
        val gen = cacheGen
        val items = page.items.filterNot(isLiftedItem) // snapshot on the UI thread
        runAsync {
            val entry = renderInk(page, res, items)
            postToMain {
                pendingInk.remove(page)
                if (gen == cacheGen) {
                    caches[page] = entry
                    onCacheReady?.invoke()
                }
            }
        }
    }

    private fun buildCache(page: Page, res: Double): CacheEntry =
        renderInk(page, res, page.items.filterNot(isLiftedItem))

    private fun renderInk(page: Page, res: Double, items: List<CanvasItem>): CacheEntry {
        val w = ceil(page.width * res).toInt().coerceAtLeast(1)
        val h = ceil(page.height * res).toInt().coerceAtLeast(1)
        val surface = surfaceFactory.create(w, h, 1.0)
        surface.fill(TRANSPARENT)
        val r = surface.renderer()
        r.scale(res, res)
        for (item in items) item.paint(r)
        return CacheEntry(surface, res)
    }

    /**
     * The page's rendered background layer (PDF/template) at the current resolution,
     * or null when the document has no page background. Built once and reused across
     * ink edits — rebuilt only when the resolution changes — so erasing/repairing ink
     * never re-rasterizes the (expensive) background.
     */
    fun backgroundFor(page: Page): CacheEntry? {
        if (paintPageBackground == null) return null
        val res = clampedRes(page)
        val existing = bgCaches[page]
        if (existing != null && (zoomingInProgress || abs(existing.res - res) < 1e-6)) return existing
        return buildBackground(page, res).also { bgCaches[page] = it }
    }

    /** Non-blocking counterpart to [backgroundFor]; see [cacheForOrSchedule]. */
    fun backgroundForOrSchedule(page: Page): CacheEntry? {
        if (paintPageBackground == null) return null
        val res = clampedRes(page)
        val existing = bgCaches[page]
        if (existing != null && (zoomingInProgress || abs(existing.res - res) < 1e-6)) return existing
        scheduleBg(page, res)
        return bgCaches[page]
    }

    private fun scheduleBg(page: Page, res: Double) {
        if (!pendingBg.add(page)) return
        val gen = cacheGen
        runAsync {
            val entry = buildBackground(page, res)
            postToMain {
                pendingBg.remove(page)
                if (gen == cacheGen) {
                    bgCaches[page] = entry
                    onCacheReady?.invoke()
                }
            }
        }
    }

    private fun buildBackground(page: Page, res: Double): CacheEntry {
        val w = ceil(page.width * res).toInt().coerceAtLeast(1)
        val h = ceil(page.height * res).toInt().coerceAtLeast(1)
        val surface = surfaceFactory.create(w, h, 1.0)
        surface.fill(TRANSPARENT)
        val r = surface.renderer()
        r.scale(res, res)
        paintPageBackground?.invoke(page, r, res, Rect(0.0, 0.0, page.width, page.height))
        return CacheEntry(surface, res)
    }

    /** Append a single just-committed stroke into an existing cache (cheap), else rebuild. */
    fun appendToCache(page: Page, item: CanvasItem) {
        sharpGen++ // the sharp viewport (if any) no longer reflects the page's content
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
        sharpGen++ // erased content: the sharp viewport must re-render
        val entry = caches[page] ?: return false
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
        cacheGen++
        sharpGen++
    }

    fun invalidateAllCaches() {
        caches.clear()
        bgCaches.clear()
        cacheGen++
        sharpGen++
    }

    /**
     * Invalidate caches after a *zoom* without dropping the surfaces. The old-resolution
     * bitmaps stay in the maps, so [cacheForOrSchedule]/[backgroundForOrSchedule] keep
     * blitting them (scaled) for the new zoom until the sharp rebuild lands — avoiding the
     * one-frame empty-canvas flash that clearing would cause when a pinch ends. A page whose
     * clamped resolution is unchanged (already at the [MAX_CACHE_PX] cap) matches on res and
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
        bgCaches.keys.retainAll(visible)
    }

    // --- sharp viewport ---

    /** True when the current zoom pushes a visible page past [MAX_CACHE_PX] (its cache is clamped). */
    fun isPastResolutionCap(): Boolean {
        val target = zoom * renderScale
        val visible = visibleContentRect()
        for (i in pageRects.indices) {
            val pr = pageRects.getOrNull(i) ?: continue
            if (!pr.intersects(visible)) continue
            if (target * max(document.pages[i].width, document.pages[i].height) > MAX_CACHE_PX) return true
        }
        return false
    }

    /** A ready sharp surface plus the viewport-pixel offset to blit it at (non-zero while panning). */
    class SharpBlit(val surface: RasterSurface, val dx: Double, val dy: Double)

    /**
     * The sharp viewport surface to blit, or null when there isn't a usable one. It stays usable
     * across a *pan* (same zoom, same content): it was rendered for an earlier scroll, so we just
     * slide it by the scroll delta — the part still on screen stays razor-sharp, and the strip that
     * panned into view falls back to the soft cache underneath until the settled re-render lands.
     * A zoom change or content edit makes it unusable (returns null).
     */
    fun sharpViewportBlit(): SharpBlit? {
        val f = sharpFrame ?: return null
        if (f.gen != sharpGen || f.z != zoom) return null
        val o = originFor(scrollX, scrollY, zoom)
        val o0 = originFor(f.sx, f.sy, f.z)
        return SharpBlit(f.surface, o.x - o0.x, o.y - o0.y)
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
        val draws = ArrayList<SharpPageSnap>()
        for (i in document.pages.indices) {
            val pr = pageRects.getOrNull(i) ?: continue
            if (!pr.intersects(visible)) continue
            val page = document.pages[i]
            val region = Rect.fromPoints(
                Pt(max(pr.left, visible.left) - pr.left, max(pr.top, visible.top) - pr.top),
                Pt(min(pr.right, visible.right) - pr.left, min(pr.bottom, visible.bottom) - pr.top),
            )
            draws.add(SharpPageSnap(page, pr, page.items.filterNot(isLiftedItem), region, i))
        }
        if (draws.isEmpty()) return
        pendingSharp = true
        runAsync {
            val surface = renderSharpSurface(vw, vh, o, z, res, bg, draws)
            postToMain {
                pendingSharp = false
                if (gen == sharpGen && sx == scrollX && sy == scrollY && z == zoom) {
                    sharpFrame = SharpFrame(surface, sx, sy, z, gen)
                    onCacheReady?.invoke()
                }
            }
        }
    }

    private fun renderSharpSurface(
        vw: Int,
        vh: Int,
        o: Pt,
        z: Double,
        res: Double,
        bg: Rgba,
        draws: List<SharpPageSnap>,
    ): RasterSurface {
        val surface = surfaceFactory.create(vw, vh, 1.0)
        surface.fill(bg)
        val r = surface.renderer()
        r.translate(o.x, o.y)
        r.scale(z, z)
        val border = Pen(palette.paperBorder, 1.0, cosmetic = true)
        for (d in draws) {
            r.fillRect(d.pr, paperColor(d.page))
            r.strokeRect(d.pr, border)
            r.save()
            r.clipRect(d.pr)
            r.translate(d.pr.left, d.pr.top)
            if (paintPageBackground != null && d.region.w > 0.0 && d.region.h > 0.0) {
                paintPageBackground?.invoke(d.page, r, res, d.region)
            }
            for (item in d.items) item.paint(r)
            r.restore()
            r.drawText(
                "%02d".format(d.index + 1),
                Rect(d.pr.left, d.pr.top - PAGE_LABEL_OFFSET, 140.0, 24.0),
                FontSpec(9.0),
                palette.textDim,
            )
        }
        return surface
    }

    private fun originFor(sx: Double, sy: Double, z: Double): Pt {
        val cw = contentW * z
        val ch = contentH * z
        val ox = if (cw < viewportW) (viewportW - cw) / 2.0 else -sx
        val oy = if (ch < viewportH) (viewportH - ch) / 2.0 else -sy
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
    class CacheSnapshot(val inkPages: Int, val bgPages: Int, val bytes: Long)

    fun cacheSnapshot(): CacheSnapshot {
        var bytes = 0L
        for (e in caches.values) bytes += e.surface.width.toLong() * e.surface.height * 4L
        for (e in bgCaches.values) bytes += e.surface.width.toLong() * e.surface.height * 4L
        return CacheSnapshot(caches.size, bgCaches.size, bytes)
    }

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
        val w = ceil(page.width * res).toInt().coerceAtLeast(1)
        val h = ceil(page.height * res).toInt().coerceAtLeast(1)
        return w to h
    }

    companion object {
        const val MARGIN = 48.0
        const val GAP = 38.0
        const val MIN_ZOOM = 0.12
        const val MAX_ZOOM = 8.0
        const val ZOOM_STEP = 1.25
        const val CTRL_WHEEL_BASE = 1.01
        const val MAX_CACHE_PX = 2048.0

        /** The page label sits ~26px above the page top (content space). */
        const val PAGE_LABEL_OFFSET = 26.0

        /** Gap (viewport px) left above the page label so nothing hides behind the toolbar. */
        const val TOP_GAP = 16.0
        val TRANSPARENT = Rgba(0, 0, 0, 0)
    }
}
