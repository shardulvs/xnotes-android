package com.xnotes.presentation

import com.xnotes.canvas.CanvasState
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke
import com.xnotes.core.pal.RasterSurface
import com.xnotes.core.tools.Tool
import com.xnotes.platform.AndroidRasterSurface
import kotlin.math.ceil
import kotlin.math.max

/**
 * Produces presentation frames in two phases so the cost stays off the UI thread
 * (spec 12 §3):
 *
 *  - [planPage] / [planFollow] run on the **main thread** and only gather cheap
 *    references: the page geometry, the on-screen page-cache surface(s) (the same
 *    background + committed-ink bitmaps the canvas keeps current incrementally) and
 *    a *copy* of the live in-progress stroke.
 *  - [render] runs on a **background thread** and does the heavy work — the
 *    downscale-blit of the cache into the frame plus the live stroke — touching
 *    only the immutable [FramePlan], never the live document.
 *
 * Excludes presenter-only chrome (eraser cursor, selection handles, page labels)
 * and items lifted for editing. Page mode frames a whole page; follow mode mirrors
 * the presenter's viewport.
 */
class PresentationFrameSource(
    private val state: CanvasState,
    private val liveStroke: () -> Pair<Int, Stroke>?,
) {
    /** One page composited into a frame: its cache, paper, placement, highlighters and live stroke. */
    class PageDraw(
        val left: Double,
        val top: Double,
        val width: Double,
        val height: Double,
        val paper: Rgba,
        val background: RasterSurface?,
        val cache: RasterSurface,
        val highlights: List<Stroke>,
        val live: Stroke?,
    )

    /** An immutable snapshot of a frame, safe to render off the main thread. */
    class FramePlan(
        val frameW: Int,
        val frameH: Int,
        val bg: Rgba,
        val follow: Boolean,
        val outerScale: Double,
        val originX: Double,
        val originY: Double,
        val zoom: Double,
        val draws: List<PageDraw>,
    )

    /** Plan a whole-page frame fit within [longEdgeCap] (main thread). */
    fun planPage(longEdgeCap: Int): FramePlan? {
        val index = state.currentPageIndex().coerceIn(0, state.document.pages.lastIndex.coerceAtLeast(0))
        val page = state.document.pages.getOrNull(index) ?: return null
        val scale = longEdgeCap / max(page.width, page.height)
        val w = ceil(page.width * scale).toInt().coerceAtLeast(1)
        val h = ceil(page.height * scale).toInt().coerceAtLeast(1)
        val draw = PageDraw(
            0.0, 0.0, page.width, page.height, state.paperColor(page),
            state.backgroundFor(page)?.surface, state.cacheFor(page).surface, highlightsFor(page), liveSnapshotFor(index),
        )
        return FramePlan(w, h, state.paperColor(page), follow = false, outerScale = scale, 0.0, 0.0, 1.0, listOf(draw))
    }

    /** Plan a viewport-mirroring frame fit within [longEdgeCap] (main thread). */
    fun planFollow(longEdgeCap: Int): FramePlan? {
        val vw = state.viewportW.toDouble()
        val vh = state.viewportH.toDouble()
        if (vw <= 0 || vh <= 0) return planPage(longEdgeCap)
        val s = longEdgeCap / max(vw, vh)
        val w = ceil(vw * s).toInt().coerceAtLeast(1)
        val h = ceil(vh * s).toInt().coerceAtLeast(1)
        val origin = state.origin()
        val visible = state.visibleContentRect()
        val draws = ArrayList<PageDraw>()
        for (i in state.document.pages.indices) {
            val pr = state.pageRects.getOrNull(i) ?: continue
            if (!pr.intersects(visible)) continue
            val page = state.document.pages[i]
            draws.add(
                PageDraw(
                    pr.left, pr.top, page.width, page.height, state.paperColor(page),
                    state.backgroundFor(page)?.surface, state.cacheFor(page).surface, highlightsFor(page), liveSnapshotFor(i),
                ),
            )
        }
        return FramePlan(w, h, state.palette.bg, follow = true, outerScale = s, origin.x, origin.y, state.zoom, draws)
    }

    /** Render [plan] into a JPEG-ready surface (background thread; touches no live state). */
    fun render(plan: FramePlan): AndroidRasterSurface {
        val surface = AndroidRasterSurface.create(plan.frameW, plan.frameH)
        surface.fill(plan.bg)
        val r = surface.renderer()
        r.scale(plan.outerScale, plan.outerScale)
        if (plan.follow) {
            r.translate(plan.originX, plan.originY)
            r.scale(plan.zoom, plan.zoom)
        }
        for (d in plan.draws) {
            r.withSave {
                r.translate(d.left, d.top)
                if (plan.follow) r.fillRect(Rect(0.0, 0.0, d.width, d.height), d.paper)
                d.background?.let { r.drawRaster(it, Rect(0.0, 0.0, d.width, d.height)) }
                r.drawRaster(d.cache, Rect(0.0, 0.0, d.width, d.height))
                for (h in d.highlights) h.paint(r) // composite over the page so they MULTIPLY
                d.live?.paint(r)
            }
        }
        return surface
    }

    /** Safe off-thread copies of a page's committed highlighter strokes — composited live
     *  (MULTIPLY) over the cache rather than baked into it (see [com.xnotes.canvas.isHighlighterInk]). */
    private fun highlightsFor(page: Page): List<Stroke> {
        val out = ArrayList<Stroke>()
        for (item in page.items) {
            if (item is Stroke && item.tool == Tool.HIGHLIGHTER && !state.isLiftedItem(item)) {
                out.add(Stroke(item.tool, item.config, ArrayList(item.samples), item.speedScale))
            }
        }
        return out
    }

    /** A copy of the live stroke on page [pageIndex] that is safe to paint off-thread, or null. */
    private fun liveSnapshotFor(pageIndex: Int): Stroke? {
        val ls = liveStroke() ?: return null
        if (ls.first != pageIndex) return null
        val src = ls.second
        return Stroke(src.tool, src.config, ArrayList(src.samples), src.speedScale)
    }
}
