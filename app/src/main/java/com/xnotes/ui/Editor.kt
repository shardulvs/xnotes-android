package com.xnotes.ui

import android.content.Context
import android.util.LruCache
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.xnotes.canvas.CanvasState
import com.xnotes.canvas.CanvasView
import com.xnotes.canvas.EditingField
import com.xnotes.canvas.InitialView
import com.xnotes.canvas.InteractionController
import com.xnotes.core.geometry.Rect
import com.xnotes.core.history.AddItem
import com.xnotes.core.history.AddPage
import com.xnotes.core.history.Command
import com.xnotes.core.history.CompositeCommand
import com.xnotes.core.history.DeletePage
import com.xnotes.core.history.EraseItems
import com.xnotes.core.history.History
import com.xnotes.core.model.Bookmark
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.Document
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Orientation
import com.xnotes.core.model.Page
import com.xnotes.core.model.PageSize
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.deepCopy
import com.xnotes.core.tools.InkPalette
import com.xnotes.core.tools.ShapeConfig
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.format.DocumentCodec
import com.xnotes.format.XNoteFormatException
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidSurfaceFactory
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.settings.Preferences
import com.xnotes.settings.SettingsRepository
import com.xnotes.ui.theme.Palette
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app-side glue between the imperative canvas (CanvasView + CanvasState +
 * InteractionController + History) and the Compose chrome. Exposes Compose-
 * observable state and the actions the toolbar/menus invoke.
 */
/** Target of the long-press paste context menu (viewport position + paste point). */
data class ContextMenuTarget(val viewportX: Double, val viewportY: Double, val content: com.xnotes.core.geometry.Pt)

/** One entry (folder or .xnote file) in the in-app explorer. [documentUri] is a SAF document URI. */
data class BrowseEntry(
    val name: String,
    val documentUri: String,
    val isDir: Boolean,
    val size: Long = 0,
    val modified: Long = 0,
)

/** Whether a pending import came from the PDF picker or the system "Open…" file picker. */
enum class ImportKind { PDF, OPEN }

/** A picked file awaiting a name before it's saved into the explorer's current folder. */
data class PendingImport(val kind: ImportKind, val defaultName: String, val bytes: ByteArray)

/** A recent note's thumbnail plus the details shown in the backstage list view. */
data class RecentInfo(
    val thumbnail: android.graphics.Bitmap?,
    val label: String,
    val pageCount: Int,
    val location: String?,
    val modified: Long,
    val sizeBytes: Long,
)

@Stable
class Editor(context: Context) {

    private val appContext = context.applicationContext
    private val settingsRepo = SettingsRepository(context)
    private var settings = settingsRepo.load()
    private var pdfSource: com.xnotes.platform.PdfSource? = null

    val state = CanvasState(
        Document.blank(Document.DEFAULT_NEW_PAGES, settings.prefs.defaultPageSize, settings.prefs.defaultPageOrientation),
        AndroidSurfaceFactory(),
        Palette.forAppearance(settings.prefs.isDark, settings.prefs.accentColor),
    )
    val history = History()
    val view = CanvasView(context).also { it.state = state }
    private val textMeasurer = AndroidTextMeasurer()
    private val imageCodec = AndroidImageCodec()
    private val codec = DocumentCodec(imageCodec, textMeasurer)
    private val session = com.xnotes.platform.SessionStore(java.io.File(appContext.filesDir, "session"), codec)
    private val viewStates = com.xnotes.platform.ViewStateStore(com.xnotes.platform.JsonStore.viewStates(appContext))
    private var lastSessionContentVersion = -1
    private var sessionLoaded = false

    /** In-memory cache of recent-file thumbnails, keyed by SAF URI (pruned to the recent list). */
    private val recentThumbs = java.util.concurrent.ConcurrentHashMap<String, android.graphics.Bitmap>()
    /** Cached page counts per recent URI (filled alongside the thumbnail's doc load). */
    private val recentPages = java.util.concurrent.ConcurrentHashMap<String, Int>()
    /** On-disk thumbnail cache so the backstage opens instantly across launches. */
    private val thumbCache = com.xnotes.platform.RecentThumbnailCache(java.io.File(appContext.filesDir, "recent_thumbs"))
    /** [contentVersion] at which the open note's recent thumbnail was last rendered, so it's
     *  re-rendered only after a real edit — never on the autosave/write path. */
    private var openThumbVersion = -1
    /**
     * Side-panel page thumbnails, rendered once and reused so scrolling the panel doesn't re-render.
     * Keyed by [Page] **identity** (not index) so a drag-reorder keeps each page's bitmap instead of
     * re-rendering every row; the whole cache is dropped when [contentVersion] moves (a real edit).
     */
    private val pageThumbs = object : LruCache<Page, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: Page, value: ImageBitmap) = value.width * value.height * 4
    }
    private var pageThumbsVersion = -1
    /** In-memory caches so reopening the backstage paints instantly (seed first, refresh after). */
    private val recentInfoCache = java.util.concurrent.ConcurrentHashMap<String, RecentInfo>()
    private val browseCache = java.util.concurrent.ConcurrentHashMap<String, List<BrowseEntry>>()
    private val rootNameCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** When non-null, the current note lives in the granted folder and autosaves to this URI. */
    var autosaveUri: String? = null
        private set
    private val autosaveScope = kotlinx.coroutines.MainScope()
    private var autosaveJob: kotlinx.coroutines.Job? = null

    var tool by mutableStateOf(Tool.DEFAULT)
        private set
    var palette by mutableStateOf(state.palette)
        private set
    var zoomPercent by mutableStateOf(100)
        private set

    /** Bumped each time a pinch snaps to fit-to-width; the toolbar's transient lock hint observes
     *  this to (re)show itself and re-arm its auto-dismiss timer. */
    var zoomLockHint by mutableStateOf(0)
        private set

    /** Bumped when a pinch breaks past the fit-to-width magnet; the lock hint observes this to
     *  dismiss itself immediately. */
    var zoomLockHintDismiss by mutableStateOf(0)
        private set
    var pageIndex by mutableStateOf(0)
        private set
    var pageCount by mutableStateOf(state.document.pages.size)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    var activeColorIndex by mutableStateOf(0)
        private set
    var toolbarColors by mutableStateOf(InkPalette.toolbarDefaults)
        private set
    var renderScale by mutableStateOf(1.0)
        private set
    var sidebarVisible by mutableStateOf(false)
    /** Backstage recent view mode: true = thumbnail grid, false = list. */
    var recentGrid by mutableStateOf(settings.recentGrid)
        private set
    /** Granted explorer root (a SAF tree URI), or null until the user picks a folder. */
    var browseRoot by mutableStateOf(settings.browseRoot)
        private set
    /** A picked PDF/.xnote awaiting a name before it's saved into the explorer; drives the inline name field. */
    var pendingImport by mutableStateOf<PendingImport?>(null)
        private set
    var zoomLocked by mutableStateOf(false)
        private set
    var hasSelection by mutableStateOf(false)
        private set
    var shapeConfig by mutableStateOf(ShapeConfig())
        private set
    var message by mutableStateOf<String?>(null)
    var editingField by mutableStateOf<EditingField?>(null)
        private set

    /** Viewport rect to anchor the on-selection menu, or null when hidden. */
    var selectionMenu by mutableStateOf<com.xnotes.core.geometry.Rect?>(null)
        private set

    /** Long-press paste context menu target, or null when hidden. */
    var contextMenu by mutableStateOf<ContextMenuTarget?>(null)
        private set
    var title by mutableStateOf(state.document.title)
        private set
    var dirty by mutableStateOf(false)
        private set

    /** Bumped whenever page content changes, to refresh thumbnails. */
    var contentVersion by mutableStateOf(0)
        private set

    /** Bumped when the bookmark list changes. */
    var bookmarkVersion by mutableStateOf(0)
        private set

    /** True while a dark-mode PDF's embedded-image colours are still being parsed off-thread (only
     *  when [Preferences.pdfDarkMode] + [Preferences.pdfKeepImageColors]); drives the canvas hint. */
    var isRefiningPdf by mutableStateOf(false)
        private set

    /** Pages the side panel has selected, by **identity** so reorder/delete never breaks the set. */
    private val selectedPages = mutableStateListOf<Page>()

    /** Deep-cloned pages held for paste (cleared when the document changes). A snapshot list so
     *  paste affordances recompose when it gains/loses contents. */
    private val pageClipboard = mutableStateListOf<Page>()

    /** The current document's storage location (a SAF content URI string), or null. */
    val currentUri: String? get() = state.document.path

    /** Most-recent-first SAF URIs of recently opened/saved notes (capped at 10); observable. */
    var recentFiles by mutableStateOf(settings.recentFiles)
        private set

    val bookmarks: List<Bookmark> get() = state.document.bookmarks.toList()

    val controller = InteractionController(
        state,
        history,
        textMeasurer,
        requestRender = { onRender() },
        onContentChanged = { refreshContent() },
        onViewChanged = { refreshView() },
        onFitWidthSnapped = { showZoomLockHint() },
        onFitWidthReleased = { hideZoomLockHint() },
        onSelectionChanged = { selected -> hasSelection = selected },
        onToolChanged = { t -> tool = t },
        onTextEditStart = { field -> editingField = field },
        onTextEditEnd = { editingField = null },
        onSelectionMenu = { rect -> selectionMenu = rect },
        onContextMenu = { vp, content -> contextMenu = ContextMenuTarget(vp.x, vp.y, content) },
        onAddPageAtEnd = { addPageAtEnd() },
        onHaptic = { runCatching { view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS) } },
    )

    val presentation = com.xnotes.presentation.PresentationController(
        state,
        imageCodec,
        liveStroke = { controller.activeLiveStrokePage?.let { pi -> controller.activeLiveStroke?.let { pi to it } } },
        onStateChanged = { refreshPresentation() },
    )

    var presentationRunning by mutableStateOf(false)
        private set
    var presentationClients by mutableStateOf(0)
        private set
    var presentationUrl by mutableStateOf("")
        private set

    private fun onRender() {
        view.requestRender()
        presentation.notifyChanged()
    }

    init {
        view.input = { controller.onTouch(it) }
        view.hover = { controller.onHover(it) }
        view.drawOverlay = { renderer, _ -> controller.drawOverlay(renderer) }
        view.afterLayout = { refreshView() }
        controller.clipboardHasImage = { clipboardImageUri() != null }
        maybeAutoEnableFingerDraw()
        applySettings()
        rebuildPdfSource()
        // Clean up legacy duplicate recents (the same note remembered under both a tree
        // URI and a plain document URI before they were de-duped by document identity).
        val cleanedRecents = dedupRecents(settings.recentFiles)
        if (cleanedRecents != settings.recentFiles) {
            settings = settings.copy(recentFiles = cleanedRecents)
            recentFiles = cleanedRecents
            settingsRepo.save(settings)
        }
    }

    // --- selection menu / clipboard ---

    val hasClipboardItems: Boolean get() = controller.hasClipboardItems()
    val clipboardHasImage: Boolean get() = clipboardImageUri() != null

    fun copySelection() = controller.copySelection()
    fun cutSelection() = controller.cutSelection()
    fun duplicateSelection() = controller.duplicateSelection()
    fun dismissSelectionMenu() { selectionMenu = null }
    fun dismissContextMenu() { contextMenu = null }

    fun pasteItemsAt(content: com.xnotes.core.geometry.Pt) {
        controller.pasteItemsAt(content)
    }

    fun pasteClipboardImageAt(content: com.xnotes.core.geometry.Pt) {
        val uri = clipboardImageUri() ?: run { message = "The clipboard has no image to paste."; return }
        runCatching { appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
            .getOrNull()
            ?.let { insertImageAt(it, content) }
            ?: run { message = "The clipboard has no image to paste." }
    }

    private fun clipboardImageUri(): android.net.Uri? {
        val cm = appContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val uri = clip.getItemAt(0).uri ?: return null
        val type = appContext.contentResolver.getType(uri)
        val isImage = type?.startsWith("image/") == true || clip.description?.hasMimeType("image/*") == true
        return if (isImage) uri else null
    }

    private fun rebuildPdfSource() {
        pdfSource?.close()
        pdfSource = state.document.pdfBytes?.let { com.xnotes.platform.PdfSource.create(appContext, it) }
        pdfSource?.onImagesReady = { index ->
            view.post {
                // Only the page whose embedded-image colours just parsed needs re-rendering; refresh it
                // in place and leave every other page's cached background alone (no full flush, no flicker).
                state.document.pages.forEach { if (it.pdfPage == index) state.refreshBackground(it) }
                view.requestRender()
                maybeFinishRefining()
            }
        }
        // Optimistic: assume the first visible page needs colour-correction; cleared once parsed.
        isRefiningPdf = pdfSource != null && settings.prefs.pdfDarkMode && settings.prefs.pdfKeepImageColors
        installPdfBackground()
        state.invalidateAllCaches()
    }

    private fun installPdfBackground() {
        val src = pdfSource
        state.paintPageBackground = if (src == null) {
            null
        } else {
            { page, renderer, res, region ->
                val pi = page.pdfPage
                if (pi != null) {
                    val fullW = (page.width * res).toInt()
                    val fullH = (page.height * res).toInt()
                    val rx = (region.left * res).toInt()
                    val ry = (region.top * res).toInt()
                    val rw = kotlin.math.ceil(region.w * res).toInt()
                    val rh = kotlin.math.ceil(region.h * res).toInt()
                    val invert = settings.prefs.pdfDarkMode
                    src.renderRegion(pi, fullW, fullH, rx, ry, rw, rh, invert, keepImages = invert && settings.prefs.pdfKeepImageColors)?.let { bg ->
                        renderer.drawRaster(
                            bg,
                            com.xnotes.core.geometry.Rect(region.left, region.top, region.w, region.h),
                        )
                        bg.recycle()
                    }
                }
            }
        }
    }

    /**
     * Recompute [isRefiningPdf]: true while any *visible* PDF-backed page still lacks parsed image
     * rects (and the keep-image-colours feature is on). Pages with no images, rotated, or that fail
     * to parse cache an empty result, so they count as resolved and the hint clears.
     */
    private fun maybeFinishRefining() {
        val src = pdfSource
        if (src == null || !(settings.prefs.pdfDarkMode && settings.prefs.pdfKeepImageColors)) {
            isRefiningPdf = false
            return
        }
        val visible = state.visibleContentRect()
        isRefiningPdf = state.document.pages.withIndex().any { (i, p) ->
            val pi = p.pdfPage
            pi != null &&
                state.pageRects.getOrNull(i)?.intersects(visible) == true &&
                !src.hasImageRects(pi)
        }
    }

    fun insertImage(bytes: ByteArray) = insertImageAt(bytes, null)

    /** Insert an image, centred on [atContent] (or on the current page when null). */
    fun insertImageAt(bytes: ByteArray, atContent: com.xnotes.core.geometry.Pt?) {
        val raster = imageCodec.decode(bytes)
        if (raster == null) {
            message = "Could not read the image."
            return
        }
        val index = (atContent?.let { state.pageIndexAtContent(it) } ?: state.currentPageIndex())
            .coerceIn(0, state.document.pages.lastIndex)
        val page = state.document.pages[index]
        val pr = state.pageRects.getOrNull(index)
        val maxW = page.width * 0.6
        val maxH = page.height * 0.6
        val scale = minOf(1.0, maxW / raster.width, maxH / raster.height)
        val w = raster.width * scale
        val h = raster.height * scale
        val rect = if (atContent != null && pr != null) {
            Rect((atContent.x - pr.left - w / 2).coerceIn(0.0, page.width - w), (atContent.y - pr.top - h / 2).coerceIn(0.0, page.height - h), w, h)
        } else {
            Rect((page.width - w) / 2.0, (page.height - h) / 2.0, w, h)
        }
        val item = ImageItem(raster, rect)
        page.items.add(item)
        state.appendToCache(page, item)
        history.push(AddItem(page, item))
        state.document.dirty = true
        refreshContent()
        view.requestRender()
    }

    fun pasteImage() {
        val clipboard = appContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val uri = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        if (uri == null) {
            message = "The clipboard has no image to paste."
            return
        }
        runCatching { appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
            .getOrNull()
            ?.let { insertImage(it) }
            ?: run { message = "The clipboard has no image to paste." }
    }

    /**
     * Flatten the open note to a PDF written to [out], reporting per-page progress and
     * polling [isCancelled] so a long export can show a dialog and be aborted. The caller
     * runs this off the main thread; it throws on failure (no message side-effects) so the
     * caller can tell success / failure / cancel apart.
     */
    fun exportPdf(
        out: OutputStream,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ) {
        // Render against a private PdfSource built from the document's own bytes, not the
        // live [pdfSource]: export now runs off the main thread, and Android's PdfRenderer
        // is single-threaded — sharing it with the canvas's background cache builder would
        // crash. A plain note has null bytes and renders identically.
        val src = state.document.pdfBytes?.let { com.xnotes.platform.PdfSource.create(appContext, it) }
        try {
            com.xnotes.platform.PdfExporter.export(appContext, state.document, src, out, state::paperColor, onProgress, isCancelled)
        } finally {
            src?.close()
        }
    }

    val preferences: Preferences get() = settings.prefs

    /**
     * First-run only: a device with no stylus/pen cannot draw at all under the default
     * finger-pans behaviour, so turn finger-draw on automatically. Runs once per install
     * (guarded by [Settings.fingerDrawAutoChecked]); only ever flips the default off→on,
     * never on a device that has a pen — so a stylus tablet keeps finger-pans and any
     * later choice in the Preferences dialog is preserved.
     */
    private fun maybeAutoEnableFingerDraw() {
        if (settings.fingerDrawAutoChecked) return
        var prefs = settings.prefs
        if (!prefs.fingerDraws && !com.xnotes.platform.DeviceCapabilities.hasStylus(appContext)) {
            prefs = prefs.copy(fingerDraws = true)
        }
        settings = settings.copy(prefs = prefs, fingerDrawAutoChecked = true)
        settingsRepo.save(settings)
    }

    private fun applySettings() {
        toolbarColors = settings.toolbarColors
        activeColorIndex = settings.activeColor.coerceIn(0, toolbarColors.lastIndex)
        // Render always at 1x (the DPI/supersampling control was removed).
        renderScale = 1.0
        state.renderScale = 1.0
        sidebarVisible = settings.sidebarVisible
        shapeConfig = settings.shapeConfig
        controller.shapeConfig = settings.shapeConfig
        for (t in ToolDefaults.persistedTools) controller.setToolConfig(t, settings.configFor(t))
        controller.inkColor = toolbarColors[activeColorIndex]
        applyPagePrefsToState(settings.prefs)
    }

    private fun applyPagePrefsToState(p: Preferences) {
        palette = Palette.forAppearance(p.isDark, p.accentColor)
        state.palette = palette
        state.pageColorOverride = if (p.defaultTemplate == "color") p.pageColor else null
        controller.fingerDraws = p.fingerDraws
        controller.penButtonTool = if (p.penButtonTool == "none") null else (Tool.fromId(p.penButtonTool) ?: Tool.ERASER)
        state.sideMargin = p.sideMargin
        state.relayout()
    }

    /** Apply edited preferences live and persist (used by the Preferences dialog). */
    fun applyPreferences(p: Preferences) {
        val marginChanged = p.sideMargin != settings.prefs.sideMargin
        settings = settings.copy(prefs = p)
        applyPagePrefsToState(p)
        state.invalidateAllCaches()
        maybeFinishRefining() // feature may have just toggled on/off
        if (marginChanged) {
            state.fitWidth() // re-fit so the new side margin takes effect immediately
            refreshView()
        }
        settingsRepo.save(settings)
        view.requestRender()
    }

    /** Snapshot live state into settings and save (call on pause/stop). */
    fun persist() {
        val tools = ToolDefaults.persistedTools.associateWith { controller.configFor(it) }
        settings = settings.copy(
            tools = tools,
            shapeConfig = controller.shapeConfig,
            toolbarColors = toolbarColors,
            activeColor = activeColorIndex,
            sidebarVisible = sidebarVisible,
            renderScale = renderScale,
        )
        flushAutosave() // write the current note back to its folder file if it autosaves
        saveViewState() // remember this folder note's view for next time
        currentUri?.let {
            settings = settings.copy(recentFiles = dedupRecents(listOf(it) + settings.recentFiles))
            recentFiles = settings.recentFiles
        }
        settingsRepo.save(settings)
        saveSession()
    }

    /** Persist the working session (open document + zoom/scroll) so the next launch
     *  reopens this note where the user left off, unsaved edits included. */
    private fun saveSession() {
        if (!sessionLoaded) return // don't overwrite the saved note before restore has applied
        val contentChanged = contentVersion != lastSessionContentVersion
        session.save(state.document, state.zoom, state.scrollX, state.scrollY, zoomLocked, writeDocument = contentChanged)
        lastSessionContentVersion = contentVersion
    }

    /** Reopen the last session (document + view state). The heavy load runs off the
     *  main thread; the apply runs on the caller's (main) dispatcher. A no-op when
     *  there is no saved session. Drives the launch loader, so it's safe to await. */
    suspend fun restoreSession() {
        val snap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { session.load() }
        if (snap != null) {
            state.document = snap.document
            rebuildPdfSource()
            history.clear()
            controller.resetGestureState()
            state.invalidateAllCaches()
            // Prefer this note's own remembered view (folder notes); fall back to the session's
            // saved view for a non-folder/unsaved note; otherwise fit width.
            val saved = viewKey(snap.document.path)?.let { viewStates.get(it) }
            state.pendingInitialView = when {
                saved != null -> InitialView.Restore(saved.zoom, saved.scrollX, saved.scrollY)
                snap.zoom > 0.0 -> InitialView.Restore(snap.zoom, snap.scrollX, snap.scrollY)
                else -> InitialView.FitWidth
            }
            state.didInitialFit = false
            zoomLocked = snap.zoomLocked
            state.zoomLocked = snap.zoomLocked
            state.relayout()
            if (state.viewportW > 0) state.establishInitialView()
            maybeBindAutosave(state.document.path) // resume autosave if the restored note is in the folder
            refreshContent()
            view.requestRender()
        }
        sessionLoaded = true
    }

    private fun refreshView() {
        zoomPercent = (state.zoom * 100).roundToInt()
        pageIndex = state.currentPageIndex()
        if (controller.editingItem != null) editingField = controller.editingField()
    }

    fun updateEditingText(text: String) {
        controller.updateEditingText(text)
    }

    fun commitText(text: String? = null) {
        controller.commitTextEdit(text)
    }

    private fun refreshContent() {
        canUndo = history.canUndo
        canRedo = history.canRedo
        pageCount = state.document.pages.size
        dirty = state.document.dirty
        title = state.document.title
        contentVersion++
        refreshView()
        if (autosaveUri != null && state.document.dirty) scheduleAutosave()
    }

    // --- side panel ---

    /** A live snapshot of the document's pages, for the side panel (recompose keyed on [contentVersion]). */
    fun pagesSnapshot(): List<Page> = state.document.pages.toList()

    fun pageAt(index: Int): Page? = state.document.pages.getOrNull(index)

    /**
     * A page's height/width ratio. The side panel reserves each thumbnail row's height from this
     * so rows don't grow when their bitmap finishes loading — that resizing was what made the
     * scrollbar thumb wobble while scrolling (its size is derived from the visible rows' heights).
     */
    fun pageAspectRatio(page: Page): Float = (page.height / page.width).toFloat()

    /**
     * Renders a page to a thumbnail bitmap (paper + PDF/template background + items). [active] is
     * polled before the costly steps (PDF background, each item) so a render abandoned mid-flight —
     * the side-panel row scrolled out of view — bails out instead of burning CPU the scroll needs.
     */
    fun renderThumbnail(page: Page, widthPx: Int, active: () -> Boolean = { true }): android.graphics.Bitmap? {
        val scale = widthPx / page.width
        val w = widthPx.coerceAtLeast(1)
        val h = (page.height * scale).toInt().coerceAtLeast(1)
        val surface = com.xnotes.platform.AndroidRasterSurface.create(w, h)
        surface.fill(state.paperColor(page))
        val r = surface.renderer()
        r.scale(scale, scale)
        if (!active()) return null
        val src = pdfSource
        val pi = page.pdfPage
        if (src != null && pi != null) {
            // One-shot and off the main thread: parse image colours inline so the thumbnail is correct now.
            val invert = settings.prefs.pdfDarkMode
            src.renderPage(pi, w, h, invert, keepImages = invert && settings.prefs.pdfKeepImageColors, blockingImages = true)?.let { bg ->
                r.drawRaster(bg, com.xnotes.core.geometry.Rect(0.0, 0.0, page.width, page.height))
                bg.recycle()
            }
        } else {
            state.paintPageBackground?.invoke(page, r, scale, com.xnotes.core.geometry.Rect(0.0, 0.0, page.width, page.height))
        }
        for (item in itemsSnapshot(page)) {
            if (!active()) return null
            item.paint(r)
        }
        return surface.bitmap
    }

    /**
     * A defensive copy of a page's items: thumbnails render off the main thread, so iterating
     * [Page.items] directly can race a main-thread edit and throw [ConcurrentModificationException].
     * Retries a few times (an edit is momentary), then gives up rather than crash — a dropped frame
     * re-renders on the next [contentVersion] bump.
     */
    private fun itemsSnapshot(page: Page): List<CanvasItem> {
        repeat(8) {
            try {
                return ArrayList(page.items)
            } catch (_: java.util.ConcurrentModificationException) {
                // a main-thread edit landed mid-copy; retry
            }
        }
        return emptyList()
    }

    /** An already-rendered side-panel thumbnail for [page] at the current content, or null. */
    fun cachedPageThumbnail(page: Page): ImageBitmap? = synchronized(pageThumbs) {
        if (pageThumbsVersion != contentVersion) {
            pageThumbs.evictAll()
            pageThumbsVersion = contentVersion
        }
        pageThumbs.get(page)
    }

    /**
     * The side-panel thumbnail for [page], rendered off the main thread and cached so scrolling the
     * panel reuses bitmaps instead of re-rendering each page on every pass — that re-render churn
     * (heap allocation + GC) was what made the panel scroll janky. Keyed by page identity, so a
     * reorder keeps it; dropped wholesale when [contentVersion] moves (see [cachedPageThumbnail]).
     */
    suspend fun pageThumbnail(page: Page, widthPx: Int): ImageBitmap? {
        cachedPageThumbnail(page)?.let { return it }
        return withContext(Dispatchers.Default) {
            cachedPageThumbnail(page)?.let { return@withContext it }
            val bmp = renderThumbnail(page, widthPx, active = { isActive })?.asImageBitmap() ?: return@withContext null
            synchronized(pageThumbs) { pageThumbs.put(page, bmp) }
            bmp
        }
    }

    fun addBookmark(label: String) {
        state.document.bookmarks.add(Bookmark(state.currentPageIndex(), label))
        state.document.dirty = true
        bookmarkVersion++
        dirty = true
    }

    fun removeBookmark(index: Int) {
        if (index in state.document.bookmarks.indices) {
            state.document.bookmarks.removeAt(index)
            state.document.dirty = true
            bookmarkVersion++
            dirty = true
        }
    }

    // --- file operations (SAF streams provided by the activity) ---

    fun open(input: InputStream, uri: String, name: String? = null) {
        try {
            val doc = codec.read(input)
            doc.path = uri
            doc.displayName = name
            doc.dirty = false
            replaceDocument(doc)
            maybeBindAutosave(uri) // resume autosaving if this note lives in the granted folder
            rememberRecent(uri)
        } catch (e: XNoteFormatException) {
            message = e.message ?: "Not an xnotes document."
        } catch (e: Exception) {
            message = "Could not open the note."
        }
    }

    fun save(output: OutputStream, uri: String, name: String? = null) {
        try {
            codec.write(state.document, output)
            state.document.path = uri
            if (name != null) state.document.displayName = name
            state.document.dirty = false
            maybeBindAutosave(uri) // saving into the folder makes it autosave thereafter
            refreshContent()
            rememberRecent(uri)
            invalidateRecentThumb(uri) // content changed; re-render its thumbnail next time
        } catch (e: Exception) {
            message = "Could not save the note."
        }
    }

    // --- recent files (backstage) ---

    /**
     * Canonical identity of a recent note — provider authority + document id — so the
     * same file reached as a tree URI (the in-app explorer / a folder note) and as a
     * plain document URI (the system "Open…" picker) counts once. Falls back to the raw
     * string for non-document URIs.
     */
    private fun recentKey(uri: String): String = runCatching {
        val u = android.net.Uri.parse(uri)
        "${u.authority}|${android.provider.DocumentsContract.getDocumentId(u)}"
    }.getOrDefault(uri)

    /** Most-recent-first, one entry per [recentKey] (keeps the earliest), capped at 10. */
    private fun dedupRecents(uris: List<String>): List<String> {
        val seen = HashSet<String>()
        return uris.filter { seen.add(recentKey(it)) }.take(10)
    }

    private fun rememberRecent(uri: String) {
        settings = settings.copy(recentFiles = dedupRecents(listOf(uri) + settings.recentFiles))
        recentFiles = settings.recentFiles
        settingsRepo.save(settings)
        thumbCache.prune(settings.recentFiles.toSet()) // drop the file that fell off the capped list
    }

    /** Drop a recent entry (e.g., the file was moved or deleted) and its cached thumbnail. */
    fun removeRecentFile(uri: String) {
        settings = settings.copy(recentFiles = settings.recentFiles.filter { it != uri })
        recentFiles = settings.recentFiles
        settingsRepo.save(settings)
        invalidateRecentThumb(uri)
    }

    /** The storage display name for a document/tree URI (no extension stripped), or null. */
    private fun queryDisplayName(uri: android.net.Uri): String? = runCatching {
        appContext.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0) c.getString(i) else null
            } else null
        }
    }.getOrNull()

    /** A short, user-visible label for a recent file (storage display name, sans extension). */
    fun recentLabel(uri: String): String {
        val u = android.net.Uri.parse(uri)
        return com.xnotes.core.util.Paths.stem(queryDisplayName(u) ?: u.lastPathSegment ?: "Note")
    }

    /**
     * A recent note's thumbnail ([widthPx] wide) plus list-view details. Heavy — loads
     * and decodes the whole note — so call off the main thread; thumbnail and page
     * count are cached per URI.
     */
    fun recentInfo(uri: String, widthPx: Int): RecentInfo {
        if (uri == currentUri) {
            // The open note is the only recent that changes while the app runs, and its file lags
            // behind the live document (edits autosave on a debounce). Render it from the in-memory
            // document — but only when its content actually moved since the last render (by
            // contentVersion), so an unchanged note reuses the cached bitmap. This happens lazily
            // here (only when the backstage asks), so the edit/autosave path does no thumbnail work.
            val haveThumb = recentThumbs[uri]?.let { !it.isRecycled } ?: false
            if (!haveThumb || contentVersion != openThumbVersion) {
                val doc = state.document
                runCatching { renderDocThumbnail(doc, widthPx) }.getOrNull()?.let {
                    recentThumbs[uri] = it
                    recentPages[uri] = doc.pages.size
                    // Persist to disk too, or a relaunch (empty memory cache) would load the old PNG.
                    thumbCache.store(uri, it, doc.pages.size)
                }
                openThumbVersion = contentVersion
            }
        } else {
            val haveThumb = recentThumbs[uri]?.let { !it.isRecycled } ?: false
            if (!haveThumb || !recentPages.containsKey(uri)) {
                val cached = thumbCache.load(uri) // L2: on disk, avoids decoding the whole note
                if (cached != null) {
                    recentThumbs[uri] = cached.first
                    recentPages[uri] = cached.second
                } else {
                    val doc = runCatching {
                        appContext.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { codec.read(it) }
                    }.getOrNull()
                    if (doc != null) {
                        val pages = doc.pages.size
                        recentPages[uri] = pages
                        renderDocThumbnail(doc, widthPx)?.let {
                            recentThumbs[uri] = it
                            thumbCache.store(uri, it, pages)
                        }
                    }
                }
            }
        }
        val keep = settings.recentFiles.toSet()
        recentThumbs.keys.retainAll(keep)
        recentPages.keys.retainAll(keep)
        recentInfoCache.keys.retainAll(keep)
        val (size, modified) = recentStat(uri)
        return RecentInfo(
            thumbnail = recentThumbs[uri]?.takeIf { !it.isRecycled },
            label = recentLabel(uri),
            pageCount = recentPages[uri] ?: 0,
            location = recentLocation(uri),
            modified = modified,
            sizeBytes = size,
        ).also { recentInfoCache[uri] = it }
    }

    /** Drop a recent note's cached thumbnail (memory + disk) so it re-renders with fresh content. */
    private fun invalidateRecentThumb(uri: String) {
        recentThumbs.remove(uri)
        recentPages.remove(uri)
        recentInfoCache.remove(uri)
        thumbCache.remove(uri)
    }

    /** Last-computed details for a recent URI, to seed the UI instantly before the refresh. */
    fun cachedRecentInfo(uri: String): RecentInfo? = recentInfoCache[uri]

    /** Renders a loaded document's first page to a [widthPx]-wide thumbnail bitmap. */
    private fun renderDocThumbnail(doc: Document, widthPx: Int): android.graphics.Bitmap? {
        val page = doc.pages.firstOrNull() ?: return null
        val scale = widthPx.toDouble() / page.width
        val w = widthPx.coerceAtLeast(1)
        val h = (page.height * scale).toInt().coerceAtLeast(1)
        val surface = com.xnotes.platform.AndroidRasterSurface.create(w, h)
        surface.fill(state.paperColor(page))
        val r = surface.renderer()
        r.scale(scale, scale)
        doc.pdfBytes?.let { bytes ->
            runCatching {
                com.xnotes.platform.PdfSource.create(appContext, bytes)?.let { src ->
                    page.pdfPage?.let { pi ->
                        src.renderPage(pi, w, h, settings.prefs.pdfDarkMode, keepImages = settings.prefs.pdfDarkMode && settings.prefs.pdfKeepImageColors, blockingImages = true)?.let { bg ->
                            r.drawRaster(bg, Rect(0.0, 0.0, page.width, page.height))
                            bg.recycle()
                        }
                    }
                    src.close()
                }
            }
        }
        for (item in page.items) item.paint(r)
        return surface.bitmap
    }

    /** (sizeBytes, lastModifiedMillis) for a recent URI, each 0 when unknown. */
    private fun recentStat(uri: String): Pair<Long, Long> = runCatching {
        appContext.contentResolver.query(
            android.net.Uri.parse(uri),
            arrayOf(android.provider.OpenableColumns.SIZE, android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) {
                val si = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                val mi = c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val size = if (si >= 0 && !c.isNull(si)) c.getLong(si) else 0L
                val mod = if (mi >= 0 && !c.isNull(mi)) c.getLong(mi) else 0L
                size to mod
            } else 0L to 0L
        } ?: (0L to 0L)
    }.getOrDefault(0L to 0L)

    /** The parent folder name of a recent URI (best-effort from its document id), or null. */
    private fun recentLocation(uri: String): String? = runCatching {
        val id = android.provider.DocumentsContract.getDocumentId(android.net.Uri.parse(uri))
        val path = id.substringAfter(':', "")
        if ('/' !in path) null else path.substringBeforeLast('/').substringAfterLast('/').ifEmpty { null }
    }.getOrNull()

    /** Empty the recent-notes list (and its caches). */
    fun clearRecentFiles() {
        settings = settings.copy(recentFiles = emptyList())
        recentFiles = settings.recentFiles
        settingsRepo.save(settings)
        recentThumbs.clear()
        recentPages.clear()
        recentInfoCache.clear()
        thumbCache.prune(emptySet())
    }

    fun updateRecentGrid(grid: Boolean) {
        recentGrid = grid
        settings = settings.copy(recentGrid = grid)
        settingsRepo.save(settings)
    }

    /** Whether the next launch should open the home screen (true) or the last-open note (false). */
    val startOnHome: Boolean get() = settings.startOnHome

    /** Record whether the home screen is the current surface, so relaunch returns to it. */
    fun setStartOnHome(home: Boolean) {
        if (settings.startOnHome != home) {
            settings = settings.copy(startOnHome = home)
            settingsRepo.save(settings)
        }
    }

    // --- in-app file explorer (a user-granted SAF tree) ---

    fun updateBrowseRoot(treeUri: String) {
        browseRoot = treeUri
        settings = settings.copy(browseRoot = treeUri)
        settingsRepo.save(settings)
        browseCache.clear()
        rootNameCache.clear()
    }

    /** Forget the granted folder: release its SAF permission and clear the root. */
    fun clearBrowseRoot() {
        browseRoot?.let { old ->
            runCatching {
                appContext.contentResolver.releasePersistableUriPermission(
                    android.net.Uri.parse(old),
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        browseRoot = null
        settings = settings.copy(browseRoot = null)
        settingsRepo.save(settings)
        browseCache.clear()
        rootNameCache.clear()
        viewStates.clear() // forget every remembered per-note view for the released folder
        clearRecentFiles() // every recent lived in the folder we just released, so clear them too
    }

    /** The granted root folder's display name (e.g. "Documents"), or null. */
    fun browseRootName(treeUri: String): String? {
        val tree = android.net.Uri.parse(treeUri)
        val root = android.provider.DocumentsContract.buildDocumentUriUsingTree(
            tree, android.provider.DocumentsContract.getTreeDocumentId(tree),
        )
        return queryDisplayName(root)?.also { rootNameCache[treeUri] = it }
    }

    /** Cached root-folder name, to seed the breadcrumb instantly before the refresh. */
    fun cachedRootName(treeUri: String): String? = rootNameCache[treeUri]

    /** Creates a subfolder [name] under [parentDocId] in tree [treeUri]; IO, call off-thread. */
    fun createFolder(treeUri: String, parentDocId: String, name: String): Boolean = runCatching {
        val parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(android.net.Uri.parse(treeUri), parentDocId)
        android.provider.DocumentsContract.createDocument(
            appContext.contentResolver, parent, android.provider.DocumentsContract.Document.MIME_TYPE_DIR, name,
        ) != null
    }.getOrDefault(false)

    /** Resolves a note file name: blank -> "untitled_N.xnote"; else ensures .xnote and avoids conflicts with a "_N" suffix. */
    private fun uniqueNoteName(treeUri: String, parentDocId: String, raw: String): String {
        val taken = browseChildren(treeUri, parentDocId).map { it.name.lowercase() }.toSet()
        val base = raw.trim().removeSuffix(".xnote").removeSuffix(".XNOTE").trim()
        if (base.isEmpty()) {
            var n = 1
            while ("untitled_$n.xnote" in taken) n++
            return "untitled_$n.xnote"
        }
        if ("${base.lowercase()}.xnote" !in taken) return "$base.xnote"
        var n = 1
        while ("${base.lowercase()}_$n.xnote" in taken) n++
        return "${base}_$n.xnote"
    }

    /** Creates a new `.xnote` named [name] under [parentDocId], written by [write]; returns its URI, or null. */
    private fun createNoteFile(treeUri: String, parentDocId: String, name: String, write: (OutputStream) -> Unit): String? = runCatching {
        val parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(android.net.Uri.parse(treeUri), parentDocId)
        val uri = android.provider.DocumentsContract.createDocument(
            appContext.contentResolver, parent, "application/octet-stream", name,
        ) ?: return null
        appContext.contentResolver.openOutputStream(uri, "wt")?.use { write(it) }
        uri.toString()
    }.getOrNull()

    /** Creates a blank `.xnote` under [parentDocId]; returns its URI, or null. IO — call off-thread. */
    fun createBlankNoteFile(treeUri: String, parentDocId: String, rawName: String): String? {
        val name = uniqueNoteName(treeUri, parentDocId, rawName)
        val blank = Document.blank(Document.DEFAULT_NEW_PAGES, settings.prefs.defaultPageSize, settings.prefs.defaultPageOrientation)
        return createNoteFile(treeUri, parentDocId, name) { codec.write(blank, it) }
    }

    /** Imports [pdfBytes] into a new `.xnote` under [parentDocId] (named after [rawName]); returns its URI, or null. IO. */
    fun createPdfNoteFile(treeUri: String, parentDocId: String, rawName: String, pdfBytes: ByteArray): String? {
        val source = com.xnotes.platform.PdfSource.create(appContext, pdfBytes) ?: return null
        val doc = com.xnotes.platform.PdfImporter.import(pdfBytes, source, state.document.dpi)
        source.close()
        val name = uniqueNoteName(treeUri, parentDocId, rawName)
        return createNoteFile(treeUri, parentDocId, name) { codec.write(doc, it) }
    }

    /** Saves picked `.xnote` [bytes] into a new file under [parentDocId] (named after [rawName]); returns its URI, or null. IO. */
    fun createNoteFileFromBytes(treeUri: String, parentDocId: String, rawName: String, bytes: ByteArray): String? {
        runCatching { codec.read(java.io.ByteArrayInputStream(bytes)) }.getOrNull() ?: return null // validate it's a real .xnote
        val name = uniqueNoteName(treeUri, parentDocId, rawName)
        return createNoteFile(treeUri, parentDocId, name) { it.write(bytes) }
    }

    /** A picked PDF/.xnote now awaits a name (shown by the explorer's inline field) before being saved into the folder. */
    fun requestImport(kind: ImportKind, defaultName: String, bytes: ByteArray) {
        pendingImport = PendingImport(kind, defaultName, bytes)
    }

    /** Discards a pending import (the user cancelled the name prompt). */
    fun cancelImport() { pendingImport = null }

    /** Saves a pending import into [parentDocId] under [treeUri] as [rawName]; returns its URI, or null, then clears the request. IO. */
    fun commitImport(treeUri: String, parentDocId: String, rawName: String): String? {
        val pending = pendingImport ?: return null
        val uri = when (pending.kind) {
            ImportKind.PDF -> createPdfNoteFile(treeUri, parentDocId, rawName, pending.bytes)
            ImportKind.OPEN -> createNoteFileFromBytes(treeUri, parentDocId, rawName, pending.bytes)
        }
        if (uri != null) pendingImport = null
        return uri
    }

    /** Renames a document (file or folder) to [newName]; follows the open note. IO, call off-thread. */
    fun renameDocument(docUri: String, newName: String): Boolean {
        val result = runCatching {
            android.provider.DocumentsContract.renameDocument(appContext.contentResolver, android.net.Uri.parse(docUri), newName)
        }
        if (result.isFailure) return false
        val resultUri = result.getOrNull()?.toString() ?: docUri
        if (state.document.path == docUri) {
            state.document.path = resultUri
            state.document.displayName = newName
            if (autosaveUri == docUri) autosaveUri = resultUri
            title = state.document.title
        }
        if (resultUri != docUri) {
            settings = settings.copy(recentFiles = dedupRecents(settings.recentFiles.map { if (it == docUri) resultUri else it }))
            recentFiles = settings.recentFiles
            settingsRepo.save(settings)
            invalidateRecentThumb(docUri)
        }
        return true
    }

    /**
     * Renames the currently open note. With a backing file it renames the file (and
     * follows it, like [renameDocument]); with none it just sets the in-memory title
     * the next save will use. Main thread — touches Compose state; the file rename is
     * a quick provider call. Returns false on a blank name or a failed file rename.
     */
    fun renameCurrentDocument(rawName: String): Boolean {
        val name = rawName.trim()
        if (name.isEmpty()) return false
        val fileName = if (name.endsWith(".xnote", ignoreCase = true)) name else "$name.xnote"
        val uri = currentUri
        return if (uri != null) {
            renameDocument(uri, fileName)
        } else {
            state.document.displayName = fileName
            title = state.document.title
            true
        }
    }

    /** Deletes a document (file or folder), then erases every trace of it. IO, call off-thread. */
    fun deleteDocument(docUri: String): Boolean = runCatching {
        val ok = android.provider.DocumentsContract.deleteDocument(appContext.contentResolver, android.net.Uri.parse(docUri))
        if (ok) purgeDeleted(docUri)
        ok
    }.getOrDefault(false)

    /**
     * Erase every trace of a just-deleted document — or, when it's a folder, everything beneath
     * it: discard the open note if it was the deleted file, drop matching recents, and discard
     * their cached thumbnails and remembered views. Matching is by document identity (authority +
     * id), not the raw URI string, so a file reached through more than one URI form is fully
     * purged. Discarding the open note is what stops it from coming back — via [persist] re-adding
     * it to recents, autosave rewriting its file, or the unsaved-changes guard offering to save it.
     */
    private fun purgeDeleted(docUri: String) {
        val target = android.net.Uri.parse(docUri)
        val delId = runCatching { android.provider.DocumentsContract.getDocumentId(target) }.getOrNull() ?: return
        val auth = target.authority
        fun matches(uri: String): Boolean {
            val u = android.net.Uri.parse(uri)
            val rid = runCatching { android.provider.DocumentsContract.getDocumentId(u) }.getOrNull() ?: return false
            return u.authority == auth && (rid == delId || rid.startsWith("$delId/"))
        }

        // Forget the deleted note's remembered zoom/scroll — and every note's under a deleted folder.
        // View-state keys are document identities ("$auth|$id", see recentKey), so match them the same
        // way as recents. This runs whether or not the note is on screen, so a same-named file later
        // created in this folder (the local provider reuses the path-derived id) starts at fit-width
        // instead of inheriting the dead note's view.
        val keyPrefix = "$auth|$delId"
        viewStates.removeMatching { it == keyPrefix || it.startsWith("$keyPrefix/") }

        // The note on screen was just deleted. Detach it at once — cancel autosave, drop its path,
        // mark it clean — so nothing can rewrite the file or prompt to "save" it back, then drop the
        // document itself for a fresh blank note on the main thread. The identity guard skips that
        // reset if the user has meanwhile opened another note.
        if (currentUri?.let { matches(it) } == true) {
            val deleted = state.document
            autosaveJob?.cancel()
            autosaveUri = null
            deleted.path = null
            deleted.dirty = false
            dirty = false
            autosaveScope.launch { if (state.document === deleted) newNote() }
        }

        val removed = settings.recentFiles.filter { matches(it) }
        if (removed.isNotEmpty()) {
            settings = settings.copy(recentFiles = settings.recentFiles - removed.toSet())
            recentFiles = settings.recentFiles
            removed.forEach { invalidateRecentThumb(it) }
        }
        settingsRepo.save(settings)
    }

    /** Copies [sourceUri] into the folder [targetParentDocId] within [treeUri]. IO, call off-thread. */
    fun copyDocumentInto(treeUri: String, sourceUri: String, targetParentDocId: String): Boolean = runCatching {
        val target = android.provider.DocumentsContract.buildDocumentUriUsingTree(android.net.Uri.parse(treeUri), targetParentDocId)
        android.provider.DocumentsContract.copyDocument(appContext.contentResolver, android.net.Uri.parse(sourceUri), target) != null
    }.getOrDefault(false)

    /** Moves [sourceUri] from [sourceParentDocId] into [targetParentDocId] within [treeUri]; follows the open note. IO. */
    fun moveDocumentInto(treeUri: String, sourceUri: String, sourceParentDocId: String, targetParentDocId: String): Boolean = runCatching {
        val tree = android.net.Uri.parse(treeUri)
        val sourceParent = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, sourceParentDocId)
        val target = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, targetParentDocId)
        val newUri = android.provider.DocumentsContract.moveDocument(appContext.contentResolver, android.net.Uri.parse(sourceUri), sourceParent, target)
        if (newUri != null && state.document.path == sourceUri) {
            state.document.path = newUri.toString()
            if (autosaveUri == sourceUri) autosaveUri = newUri.toString()
        }
        newUri != null
    }.getOrDefault(false)

    // --- autosave (notes living in the granted folder write back automatically) ---

    private fun isUnderTree(fileUri: String, treeUri: String): Boolean = runCatching {
        val f = android.net.Uri.parse(fileUri)
        val t = android.net.Uri.parse(treeUri)
        if (f.authority != t.authority) return false
        val treeId = android.provider.DocumentsContract.getTreeDocumentId(t)
        val fileId = android.provider.DocumentsContract.getDocumentId(f)
        fileId == treeId || fileId.startsWith("$treeId/")
    }.getOrDefault(false)

    private fun maybeBindAutosave(uri: String?) {
        autosaveUri = if (uri != null && browseRoot?.let { isUnderTree(uri, it) } == true) uri else null
    }

    private fun scheduleAutosave() {
        val uri = autosaveUri ?: return
        autosaveJob?.cancel()
        autosaveJob = autosaveScope.launch {
            kotlinx.coroutines.delay(1200L) // debounce: write after a short idle
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    appContext.contentResolver.openOutputStream(android.net.Uri.parse(uri), "wt")?.use { codec.write(state.document, it) } != null
                }.getOrDefault(false)
            }
            if (ok) { state.document.dirty = false; dirty = false }
        }
    }

    /** Write the current note to its autosave file now (synchronous, main thread); a no-op when not autosaving. */
    fun flushAutosave() {
        autosaveJob?.cancel()
        val uri = autosaveUri ?: return
        if (!state.document.dirty) return
        val ok = runCatching {
            appContext.contentResolver.openOutputStream(android.net.Uri.parse(uri), "wt")?.use { codec.write(state.document, it) } != null
        }.getOrDefault(false)
        if (ok) { state.document.dirty = false; dirty = false; invalidateRecentThumb(uri) }
    }

    // --- per-document view state (folder notes remember their own zoom + scroll) ---

    /**
     * The view-state key for a note in the granted folder — its document identity, shared with
     * [recentKey] — or null when it isn't a folder document, so only folder notes remember a view.
     */
    private fun viewKey(uri: String?): String? {
        val u = uri ?: return null
        val root = browseRoot ?: return null
        return if (isUnderTree(u, root)) recentKey(u) else null
    }

    /** Remember the current note's view (zoom + scroll); a no-op unless it's a laid-out folder note. */
    private fun saveViewState() {
        if (!state.didInitialFit || state.viewportW <= 0) return // nothing meaningful established yet
        val key = viewKey(currentUri) ?: return
        viewStates.put(key, state.zoom, state.scrollX, state.scrollY)
    }

    /**
     * Choose a just-installed document's initial view — its remembered view for a folder note,
     * else fit-width — and apply it now if the viewport is sized, else on the next layout. Setting
     * it explicitly is what stops the previous document's zoom/scroll from carrying over.
     */
    private fun installInitialView(path: String?) {
        val saved = viewKey(path)?.let { viewStates.get(it) }
        state.pendingInitialView =
            if (saved != null) InitialView.Restore(saved.zoom, saved.scrollX, saved.scrollY) else InitialView.FitWidth
        state.didInitialFit = false
        if (state.viewportW > 0) state.establishInitialView()
    }

    /** The document id of the explorer root, for listing its top-level children. */
    fun browseRootDocId(treeUri: String): String =
        android.provider.DocumentsContract.getTreeDocumentId(android.net.Uri.parse(treeUri))

    /** The document id of a folder entry, for descending into it. */
    fun browseDocId(documentUri: String): String =
        android.provider.DocumentsContract.getDocumentId(android.net.Uri.parse(documentUri))

    /** Lists folders and `.xnote` files under [parentDocId] within tree [treeUri]; IO, call off-thread. */
    fun browseChildren(treeUri: String, parentDocId: String): List<BrowseEntry> {
        val tree = android.net.Uri.parse(treeUri)
        val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        val out = ArrayList<BrowseEntry>()
        runCatching {
            appContext.contentResolver.query(
                childrenUri,
                arrayOf(
                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
                    android.provider.DocumentsContract.Document.COLUMN_SIZE,
                    android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    val id = c.getString(1) ?: continue
                    val isDir = c.getString(2) == android.provider.DocumentsContract.Document.MIME_TYPE_DIR
                    if (isDir || name.endsWith(".xnote", ignoreCase = true)) {
                        val docUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id).toString()
                        val size = if (!c.isNull(3)) c.getLong(3) else 0L
                        val modified = if (!c.isNull(4)) c.getLong(4) else 0L
                        out.add(BrowseEntry(name, docUri, isDir, size, modified))
                    }
                }
            }
        }
        val result = out.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
        browseCache["$treeUri|$parentDocId"] = result
        return result
    }

    /** Last-listed children for a folder, to seed the explorer instantly before the refresh. */
    fun cachedChildren(treeUri: String, parentDocId: String): List<BrowseEntry>? = browseCache["$treeUri|$parentDocId"]

    /** Warm the backstage caches off-thread (after launch) so its first open paints instantly. */
    fun prewarmBackstage() {
        autosaveScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    browseRoot?.let { root ->
                        browseRootName(root)
                        browseChildren(root, browseRootDocId(root))
                    }
                    settings.recentFiles.forEach { recentInfo(it, 300) }
                }
            }
        }
    }

    // --- per-file actions in the explorer (operate on a stored note URI, not the open document) ---

    /** Streams a stored note's raw bytes to [out] (share-as-.xnote / save-a-copy). */
    fun copyFileTo(srcUri: String, out: OutputStream) {
        appContext.contentResolver.openInputStream(android.net.Uri.parse(srcUri))?.use { it.copyTo(out) }
    }

    /** Loads the note at [srcUri] and writes it flattened to a PDF in [out] (share-as-PDF / export). */
    fun exportFileToPdf(
        srcUri: String,
        out: OutputStream,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ) {
        val doc = appContext.contentResolver.openInputStream(android.net.Uri.parse(srcUri))?.use { codec.read(it) } ?: return
        val src = doc.pdfBytes?.let { com.xnotes.platform.PdfSource.create(appContext, it) }
        try {
            com.xnotes.platform.PdfExporter.export(appContext, doc, src, out, { page -> state.paperColor(page) }, onProgress, isCancelled)
        } finally {
            src?.close()
        }
    }

    private fun replaceDocument(doc: Document) {
        saveViewState() // remember the outgoing folder note's view before switching away
        flushAutosave() // save the outgoing note if it was autosaving to the folder
        autosaveUri = null
        controller.commitTextEdit()
        controller.clearSelection()
        controller.resetGestureState() // drop the outgoing note's fling/elastic so it can't bleed in
        clearPageSelection()
        pageClipboard.clear() // clones reference the outgoing document; don't paste them into another
        state.document = doc
        rebuildPdfSource()
        history.clear()
        state.invalidateAllCaches()
        state.relayout()
        installInitialView(doc.path) // this note's remembered view, or fit width — never the last note's
        refreshContent()
        view.requestRender()
    }

    // --- tools & colour ---

    fun selectTool(t: Tool) {
        controller.setTool(t)
        tool = t
    }

    fun pickColor(index: Int) {
        activeColorIndex = index
        controller.inkColor = toolbarColors[index.coerceIn(0, toolbarColors.lastIndex)]
    }

    fun setSwatchColor(index: Int, color: Rgba) {
        toolbarColors = toolbarColors.toMutableList().also { it[index] = color }
        settings = settings.rememberColor(color)
        pickColor(index)
    }

    fun setShapeKind(kind: com.xnotes.core.tools.ShapeKind) {
        shapeConfig = shapeConfig.copy(shape = kind)
        controller.shapeConfig = shapeConfig
    }

    fun updateShapeConfig(config: ShapeConfig) {
        shapeConfig = config
        controller.shapeConfig = config
    }

    /** The live config for a stroke tool (read by its config popup). */
    fun toolConfig(t: Tool): com.xnotes.core.tools.ToolConfig = controller.configFor(t)

    fun updateToolConfig(t: Tool, config: com.xnotes.core.tools.ToolConfig) {
        controller.setToolConfig(t, config.copy(rgba = controller.inkColor))
    }

    val recentColors: List<Rgba> get() = settings.recentColors

    // --- history ---

    fun undo() {
        history.undo()
        afterHistory()
    }

    fun redo() {
        history.redo()
        afterHistory()
    }

    private fun afterHistory() {
        controller.clearSelection()
        state.relayout()
        state.invalidateAllCaches()
        state.document.dirty = true
        state.clampScroll()
        refreshContent()
        view.requestRender()
    }

    // --- view ---

    private fun afterView() {
        refreshView()
        view.requestRender()
    }

    fun zoomIn() { state.zoomByStep(true); afterView() }
    fun zoomOut() { state.zoomByStep(false); afterView() }
    fun fitWidth() { state.fitWidth(); afterView() }
    fun fitHeight() { state.fitHeight(); afterView() }
    fun fitPage() { state.fitPage(); afterView() }
    fun prevPage() { state.goToPage(state.currentPageIndex() - 1); afterView() }
    fun nextPage() { state.goToPage(state.currentPageIndex() + 1); afterView() }
    fun goToPage(index: Int) { state.goToPage(index); afterView() }

    fun toggleZoomLock() {
        zoomLocked = !zoomLocked
        state.zoomLocked = zoomLocked
    }

    /** Show (or re-arm) the transient "lock zoom" hint after a pinch snaps to fit-to-width. */
    fun showZoomLockHint() { zoomLockHint += 1 }

    /** Dismiss the "lock zoom" hint when a pinch breaks past the fit-to-width magnet. */
    fun hideZoomLockHint() { zoomLockHintDismiss += 1 }

    // --- pages ---

    /**
     * Insert a blank page at [index] (clamped into range), sized from the page at [refIndex] so the
     * note stays uniform (falling back to A4 portrait). Undoable; relayouts and refreshes. Returns
     * the new page's final index.
     */
    private fun insertBlankPageAt(index: Int, refIndex: Int): Int {
        val pages = state.document.pages
        val ref = pages.getOrNull(refIndex) ?: pages.getOrNull(index) ?: pages.lastOrNull()
        val (w, h) = if (ref != null) ref.width to ref.height else PageSize.A4.pixels(Orientation.PORTRAIT, state.document.dpi)
        val at = index.coerceIn(0, pages.size)
        val page = Page(w, h)
        controller.clearSelection() // inserting shifts later page indices; drop any stale item selection
        pages.add(at, page)
        history.push(AddPage(state.document, page, at))
        state.document.dirty = true
        state.relayout()
        refreshContent()
        view.requestRender()
        return at
    }

    /** Common tail for a side-panel page edit: re-layout, refresh the chrome, repaint. */
    private fun afterPageEdit() {
        controller.clearSelection()
        state.document.dirty = true
        state.relayout()
        state.clampScroll()
        refreshContent()
        view.requestRender()
    }

    /** Toolbar "Add page": insert a blank page right after the current one (sized from it) and go to it. */
    fun addPage() {
        val current = state.currentPageIndex()
        val at = insertBlankPageAt(current + 1, current)
        goToPage(at)
    }

    /**
     * Append a blank page at the very end — used by the pull-past-the-end gesture. Stays at the
     * current scroll position so the user is not yanked to the new page; they can scroll to it.
     */
    fun addPageAtEnd() {
        insertBlankPageAt(state.document.pages.size, state.document.pages.lastIndex)
    }

    fun deleteCurrentPage() {
        if (state.document.pages.size <= 1) {
            message = "A note must keep at least one page."
            return
        }
        val index = state.currentPageIndex()
        val page = state.document.pages[index]
        state.document.pages.removeAt(index)
        history.push(DeletePage(state.document, page, index))
        state.document.dirty = true
        state.invalidatePage(page)
        state.relayout()
        refreshContent()
        view.requestRender()
    }

    // --- side-panel page operations (operate on explicit page indices) ---

    /** Insert a blank page right after [index] (sized from it) and reveal it. */
    fun insertPageAfter(index: Int) {
        goToPage(insertBlankPageAt(index + 1, index))
    }

    /** Clear all of a page's items but keep the page (and its PDF/template background). Undoable. */
    fun erasePage(index: Int) {
        val page = pageAt(index) ?: return
        if (page.items.isEmpty()) { message = "That page is already empty."; return }
        val removals = page.items.map { page to it }
        page.items.clear()
        history.push(EraseItems(removals))
        state.invalidatePage(page)
        afterPageEdit()
    }

    /** Deep-clone [indices] (document order) into the page clipboard for a later paste. */
    fun copyPages(indices: List<Int>) {
        val pages = indices.distinct().sorted().mapNotNull { pageAt(it) }
        if (pages.isEmpty()) return
        pageClipboard.clear()
        pages.forEach { pageClipboard.add(it.deepCopy(textMeasurer)) }
    }

    /** Copy [indices] to the clipboard then delete them (kept ≥ 1 page). */
    fun cutPages(indices: List<Int>) {
        if (indices.isEmpty()) return
        if (indices.distinct().size >= state.document.pages.size) {
            message = "A note must keep at least one page."
            return
        }
        copyPages(indices)
        deletePages(indices)
    }

    /** Insert fresh clones of the page clipboard right after [index]; selects nothing, reveals the first. */
    fun pastePagesAfter(index: Int) {
        if (pageClipboard.isEmpty()) return
        val pages = state.document.pages
        val firstAt = (index + 1).coerceIn(0, pages.size)
        var at = firstAt
        val cmds = ArrayList<Command>()
        for (src in pageClipboard) {
            val clone = src.deepCopy(textMeasurer) // fresh clone each paste, so repeated pastes are independent
            pages.add(at, clone)
            cmds.add(AddPage(state.document, clone, at))
            at++
        }
        history.push(CompositeCommand(cmds))
        afterPageEdit()
        goToPage(firstAt)
    }

    /** Delete [indices] as one undoable edit, refusing to empty the note. */
    fun deletePages(indices: List<Int>) {
        val pages = state.document.pages
        val targets = indices.filter { it in pages.indices }.distinct().sortedDescending()
        if (targets.isEmpty()) return
        if (targets.size >= pages.size) {
            message = "A note must keep at least one page."
            return
        }
        val cmds = ArrayList<Command>()
        for (i in targets) { // descending, so each removeAt index stays valid and DeletePage stores the original index
            val page = pages[i]
            pages.removeAt(i)
            state.invalidatePage(page)
            cmds.add(DeletePage(state.document, page, i))
        }
        history.push(CompositeCommand(cmds))
        clearPageSelection()
        afterPageEdit()
    }

    // --- side-panel page selection (multi-select) ---

    val canPastePages: Boolean get() = pageClipboard.isNotEmpty()
    val pageSelectionCount: Int get() = selectedPages.size
    val inPageSelectionMode: Boolean get() = selectedPages.isNotEmpty()

    fun isPageSelected(index: Int): Boolean {
        val p = pageAt(index) ?: return false
        return selectedPages.any { it === p }
    }

    /** Selected page indices in document order. */
    fun selectedPageIndices(): List<Int> =
        state.document.pages.mapIndexedNotNull { i, p -> if (selectedPages.any { it === p }) i else null }

    /** Toggle a page's membership in the selection (entering selection mode on the first add). */
    fun togglePageSelection(index: Int) {
        val p = pageAt(index) ?: return
        val at = selectedPages.indexOfFirst { it === p }
        if (at >= 0) selectedPages.removeAt(at) else selectedPages.add(p)
    }

    fun clearPageSelection() {
        if (selectedPages.isNotEmpty()) selectedPages.clear()
    }

    // --- export a subset of pages (side-panel Share / Save as) ---

    /** Flatten the pages at [indices] (document order) into a PDF written to [out]. */
    fun exportPagesToPdf(
        indices: List<Int>,
        out: OutputStream,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ) {
        val pages = indices.distinct().sorted().mapNotNull { pageAt(it) }
        if (pages.isEmpty()) return
        val sub = Document(dpi = state.document.dpi, pdfBytes = state.document.pdfBytes)
        sub.pages.addAll(pages) // share the page objects; export only reads them
        // A private source per export — see [exportPdf]: the canvas's cache thread may be
        // touching the live [pdfSource], and PdfRenderer can't be shared across threads.
        val src = sub.pdfBytes?.let { com.xnotes.platform.PdfSource.create(appContext, it) }
        try {
            com.xnotes.platform.PdfExporter.export(appContext, sub, src, out, state::paperColor, onProgress, isCancelled)
        } finally {
            src?.close()
        }
    }

    /** PNG bytes for page [index], rendered at full page resolution (paper + background + items), or null. */
    fun pageImagePng(index: Int): ByteArray? {
        val page = pageAt(index) ?: return null
        val bmp = renderThumbnail(page, page.width.toInt().coerceAtLeast(1)) ?: return null
        return java.io.ByteArrayOutputStream().use { out ->
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }

    // --- selection edits ---

    fun deleteSelection() = controller.deleteSelection()
    fun selectAll() = controller.selectAll()
    fun bringToFront() = controller.bringToFront()
    fun escape() = controller.escape()

    fun toggleSidebar() {
        sidebarVisible = !sidebarVisible
    }

    // --- presentation ---

    val presentationDefaults get() = settings.presentation

    fun startPresentation(port: Int, scope: String, mode: String): String? {
        val d = settings.presentation
        val error = presentation.start(port, scope == "lan", mode, d.quality, d.maxFps)
        refreshPresentation()
        if (error == null) {
            settings = settings.copy(presentation = d.copy(port = port, scope = scope, mode = mode))
            settingsRepo.save(settings)
        }
        return error
    }

    fun stopPresentation() {
        presentation.stop()
        refreshPresentation()
    }

    fun setPresentationMode(mode: String) {
        presentation.setMode(mode)
        settings = settings.copy(presentation = settings.presentation.copy(mode = mode))
        refreshPresentation()
    }

    private fun refreshPresentation() {
        presentationRunning = presentation.running
        presentationClients = presentation.clientCount
        presentationUrl = presentation.url()
    }

    // --- keyboard shortcuts (spec 11 §2) ---

    /** File-ish actions that live in the Compose layer (SAF launchers, dialogs). */
    class KeyActions(
        val newNote: () -> Unit = {},
        val open: () -> Unit = {},
        val save: () -> Unit = {},
        val saveAs: () -> Unit = {},
        val exportPdf: () -> Unit = {},
        val preferences: () -> Unit = {},
        val fullscreen: () -> Unit = {},
    )

    var keyActions = KeyActions()

    fun handleKeyDown(e: android.view.KeyEvent): Boolean {
        // While editing a text box, let the field consume keys (only Escape commits).
        if (editingField != null) {
            if (e.keyCode == android.view.KeyEvent.KEYCODE_ESCAPE) { escape(); return true }
            return false
        }
        val ctrl = e.isCtrlPressed
        val shift = e.isShiftPressed
        when {
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_Z && shift -> redo()
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_Z -> undo()
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_N -> keyActions.newNote()
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_O -> keyActions.open()
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_S && shift -> keyActions.saveAs()
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_S -> keyActions.save()
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_E -> keyActions.exportPdf()
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_A -> selectAll()
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_B -> toggleSidebar()
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_COMMA -> keyActions.preferences()
            ctrl && (e.keyCode == android.view.KeyEvent.KEYCODE_PLUS || e.keyCode == android.view.KeyEvent.KEYCODE_EQUALS) -> zoomIn()
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_MINUS -> zoomOut()
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_0 -> fitWidth()
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_9 -> fitPage()
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_8 -> fitHeight()
            ctrl -> return false
            e.keyCode == android.view.KeyEvent.KEYCODE_DEL || e.keyCode == android.view.KeyEvent.KEYCODE_FORWARD_DEL -> deleteSelection()
            e.keyCode == android.view.KeyEvent.KEYCODE_ESCAPE -> escape()
            e.keyCode == android.view.KeyEvent.KEYCODE_PAGE_UP -> prevPage()
            e.keyCode == android.view.KeyEvent.KEYCODE_PAGE_DOWN -> nextPage()
            e.keyCode == android.view.KeyEvent.KEYCODE_F11 -> keyActions.fullscreen()
            e.keyCode == android.view.KeyEvent.KEYCODE_P -> selectTool(Tool.PEN)
            e.keyCode == android.view.KeyEvent.KEYCODE_C -> selectTool(Tool.CALLIGRAPHY)
            e.keyCode == android.view.KeyEvent.KEYCODE_H -> selectTool(Tool.HIGHLIGHTER)
            e.keyCode == android.view.KeyEvent.KEYCODE_E -> selectTool(Tool.ERASER)
            e.keyCode == android.view.KeyEvent.KEYCODE_V -> selectTool(Tool.SELECT)
            e.keyCode == android.view.KeyEvent.KEYCODE_L -> selectTool(Tool.LASSO)
            e.keyCode == android.view.KeyEvent.KEYCODE_S -> selectTool(Tool.SHAPE)
            e.keyCode == android.view.KeyEvent.KEYCODE_T -> selectTool(Tool.TEXT)
            else -> return false
        }
        return true
    }

    fun newNote() {
        saveViewState()
        flushAutosave()
        autosaveUri = null
        state.document = Document.blank(
            Document.DEFAULT_NEW_PAGES,
            settings.prefs.defaultPageSize,
            settings.prefs.defaultPageOrientation,
        )
        history.clear()
        controller.clearSelection()
        controller.resetGestureState() // drop the outgoing note's fling/elastic so it can't bleed in
        clearPageSelection()
        pageClipboard.clear()
        state.invalidateAllCaches()
        state.relayout()
        installInitialView(null) // a fresh in-memory note: fit width
        refreshContent()
        view.requestRender()
    }
}
