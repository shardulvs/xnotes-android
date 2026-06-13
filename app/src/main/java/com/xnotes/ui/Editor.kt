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
import com.xnotes.canvas.TextBar
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
import com.xnotes.core.pal.FontFace
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
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
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

/**
 * One entry (folder or .xnote file) in the in-app explorer. [documentUri] is a SAF document URI.
 * [modified] is SAF's last-modified time; [created] is the app-tracked creation time used for grid
 * ordering (SAF exposes no creation time — see [CreationTimeStore]).
 */
data class BrowseEntry(
    val name: String,
    val documentUri: String,
    val isDir: Boolean,
    val size: Long = 0,
    val modified: Long = 0,
    val created: Long = 0,
)

/** Whether a pending import came from the PDF picker or the system "Open…" file picker. */
enum class ImportKind { PDF, OPEN }

/** A picked file awaiting a name before it's saved into the explorer's current folder. */
data class PendingImport(val kind: ImportKind, val defaultName: String, val uri: String)

@Stable
class Editor(context: Context) {

    private val appContext = context.applicationContext
    private val settingsRepo = SettingsRepository(context)
    private var settings = settingsRepo.load()
    private var pdfSource: com.xnotes.platform.PdfSource? = null

    /** Private cache dir holding each note's source PDF as a file, so a large PDF is never held
     *  whole in RAM (the renderer memory-maps it). Purged on launch to drop temp files orphaned
     *  by a previous crash — safe here because no real note is open yet at construction. */
    private val pdfDir = java.io.File(appContext.cacheDir, "pdfsrc").apply { mkdirs(); listFiles()?.forEach { it.delete() } }

    /** The temp PDF file backing the currently open document, tracked so it's deleted when the note
     *  is swapped out (transient docs used for export/thumbnails manage their own files locally). */
    private var openPdfTemp: java.io.File? = null

    val state = CanvasState(
        Document.blank(Document.DEFAULT_NEW_PAGES, settings.prefs.defaultPageSize, settings.prefs.defaultPageOrientation),
        AndroidSurfaceFactory(),
        Palette.forAppearance(settings.prefs.uiAppearance, settings.prefs.accentColor),
    )
    val history = History()
    val view = CanvasView(context).also { it.state = state }
    private val textMeasurer = AndroidTextMeasurer()
    private val imageCodec = AndroidImageCodec()
    private val codec = DocumentCodec(imageCodec, textMeasurer)
    private val session = com.xnotes.platform.SessionStore(java.io.File(appContext.filesDir, "session"), codec, pdfDir)
    private val viewStates = com.xnotes.platform.ViewStateStore(com.xnotes.platform.JsonStore.viewStates(appContext))
    private var lastSessionContentVersion = -1
    private var sessionLoaded = false

    /** On-disk note-thumbnail cache (png + source mtime) so the grid paints instantly across launches. */
    private val thumbCache = com.xnotes.platform.NoteThumbnailCache(java.io.File(appContext.filesDir, "note_thumbs"))
    /** In-memory note-tile thumbnails keyed by SAF URI, bounded by bytes (Compose owns the pixels —
     *  no manual recycle). */
    private val noteThumbs = object : LruCache<String, ImageBitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    /** App-tracked creation times for explorer ordering (SAF exposes only last-modified). */
    private val createdStore = com.xnotes.platform.CreationTimeStore(com.xnotes.platform.JsonStore.createdTimes(appContext))
    /** A single lowest-priority background thread renders explorer thumbnails one at a time, so a
     *  folder of many notes fills in gradually without ever competing with the UI/render threads. */
    private val thumbDispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "note-thumbs").apply { priority = Thread.MIN_PRIORITY }
    }.asCoroutineDispatcher()
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
    /** Granted explorer root (a SAF tree URI), or null until the user picks a folder. */
    var browseRoot by mutableStateOf(settings.browseRoot)
        private set
    /** A picked PDF/.xnote awaiting a name before it's saved into the explorer; drives the inline name field. */
    var pendingImport by mutableStateOf<PendingImport?>(null)
        private set
    /** True while a committed import is being written off-thread; drives the "Importing…" dialog. */
    var importing by mutableStateOf(false)
        private set
    /** Flipped by the import dialog's Cancel so the in-flight stream-copy aborts at its next buffer. */
    private val importCancelled = java.util.concurrent.atomic.AtomicBoolean(false)
    var zoomLocked by mutableStateOf(false)
        private set
    var hasSelection by mutableStateOf(false)
        private set
    var shapeConfig by mutableStateOf(ShapeConfig())
        private set
    var message by mutableStateOf<String?>(null)
    var editingField by mutableStateOf<EditingField?>(null)
        private set

    /** The floating text style bar's target (active box rect + style), or null when no box is active. */
    var textBar by mutableStateOf<TextBar?>(null)
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

    /** True when a note is open (the editor is pushed on top of backstage); false = backstage is the
     *  bare root of the stack. Starts false so every launch lands on home with no phantom note. */
    var noteOpen by mutableStateOf(false)
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

    /** Up-front sweep progress for the "Refining PDF colours k/N pages…" hint: PDF pages whose
     *  embedded-image boxes are parsed, and the total PDF page count. Both 0 when not refining. */
    var refiningDone by mutableStateOf(0)
        private set
    var refiningTotal by mutableStateOf(0)
        private set

    /** Bumped when the sweep finishes a PDF page that has a cached side-panel thumbnail, so the panel
     *  re-renders that row from inverted to real image colours. Observed by the thumbnail producer. */
    var pdfThumbTick by mutableStateOf(0)
        private set

    /** Pages the side panel has selected, by **identity** so reorder/delete never breaks the set. */
    private val selectedPages = mutableStateListOf<Page>()

    /** Deep-cloned pages held for paste (cleared when the document changes). A snapshot list so
     *  paste affordances recompose when it gains/loses contents. */
    private val pageClipboard = mutableStateListOf<Page>()

    /** The current document's storage location (a SAF content URI string), or null. */
    val currentUri: String? get() = state.document.path

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
        onSelectionChanged = { selected -> hasSelection = selected; refreshTextBar() },
        onToolChanged = { t -> tool = t },
        onTextEditStart = { field -> editingField = field; refreshTextBar() },
        onTextEditEnd = { editingField = null; refreshTextBar() },
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
        controller.onLinkTap = onLinkTap@{ pageIndex, pageLocal ->
            val src = pdfSource ?: return@onLinkTap false
            val page = state.document.pages.getOrNull(pageIndex) ?: return@onLinkTap false
            val pdfIdx = page.pdfPage ?: return@onLinkTap false
            if (page.width <= 0.0 || page.height <= 0.0) return@onLinkTap false
            val fx = (pageLocal.x / page.width).toFloat()
            val fy = (pageLocal.y / page.height).toFloat()
            if (src.hasLinks(pdfIdx)) {
                val link = src.linkAt(pdfIdx, fx, fy) ?: return@onLinkTap false
                followLink(link)
                true
            } else {
                // Not parsed yet: parse off the main thread, then open on the main thread. The tap
                // is not blocked or consumed; on the first tap of a page the link opens a moment later.
                src.requestLinks(pdfIdx) {
                    view.post { if (pdfSource === src) src.linkAt(pdfIdx, fx, fy)?.let { followLink(it) } }
                }
                false
            }
        }
        maybeAutoEnableFingerDraw()
        applySettings()
        rebuildPdfSource()
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
        pdfSource = state.document.pdfFile?.let { com.xnotes.platform.PdfSource.create(appContext, it) }
        pdfSource?.onImagesReady = { index ->
            view.post {
                // The sweep found page [index]'s locations: snap the canvas background (refreshBackground
                // self-skips off-screen pages) and drop the now-stale inverted side-panel thumbnail so the
                // panel re-renders it un-inverted (pdfThumbTick is bumped only when a cached thumb existed).
                state.document.pages.forEach { if (it.pdfPage == index) state.refreshBackground(it) }
                if (evictPageThumbnails(index)) pdfThumbTick++
                view.requestRender()
            }
        }
        pdfSource?.onImagesProgress = { done, total ->
            view.post {
                if (pdfSource != null && settings.prefs.pdfDarkMode && settings.prefs.pdfKeepImageColors) {
                    refiningTotal = total
                    refiningDone = done
                    isRefiningPdf = done < total
                }
            }
        }
        installPdfBackground()
        state.invalidateAllCaches()
        startPdfRefine() // kick the up-front sweep over all pages + drive the "Refining k/N" hint
    }

    /** Records [doc] as the open note for source-PDF temp-file lifetime: deletes the previously open
     *  note's temp PDF (unless [doc] reuses the same file) and tracks [doc]'s. Call on every document
     *  swap so a closed note's (possibly huge) cached PDF doesn't linger on disk. */
    private fun adoptOpenPdf(doc: Document) {
        val keep = doc.pdfFile
        openPdfTemp?.let { if (it != keep) it.delete() }
        openPdfTemp = keep
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

    /** Act on a tapped PDF link: open a web/mail URL externally, or jump to an internal destination
     *  page (mapped from the source-PDF page index to whichever document page carries it). */
    private fun followLink(link: com.xnotes.platform.PdfLink) {
        val url = link.url
        if (url != null) {
            openUrl(url)
            return
        }
        val dest = link.destPage ?: return
        val docPage = state.document.pages.indexOfFirst { it.pdfPage == dest }
        if (docPage >= 0) goToPage(docPage)
    }

    /** Open an external web/mail URL in the system handler. Restricted to safe schemes; never throws. */
    private fun openUrl(url: String) {
        val u = url.trim()
        val ok = u.startsWith("http://", ignoreCase = true) ||
            u.startsWith("https://", ignoreCase = true) ||
            u.startsWith("mailto:", ignoreCase = true)
        if (!ok) return
        runCatching {
            appContext.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /**
     * Kick off (once per source) the up-front background sweep that parses every PDF page's embedded-
     * image boxes, currently-visible pages first, and prime the "Refining PDF colours k/N pages…"
     * hint. A no-op that clears the hint unless dark mode + keep-image-colours are both on, since the
     * boxes are only needed to un-invert images. Safe to call repeatedly (on open and whenever the
     * preference toggles): [PdfSource.prepAllImages] enqueues the sweep only the first time.
     */
    private fun startPdfRefine() {
        val src = pdfSource
        if (src == null || !(settings.prefs.pdfDarkMode && settings.prefs.pdfKeepImageColors)) {
            isRefiningPdf = false
            refiningDone = 0
            refiningTotal = 0
            return
        }
        refiningTotal = src.pageCount
        refiningDone = src.parsedPageCount()
        isRefiningPdf = refiningDone < refiningTotal
        if (isRefiningPdf) src.prepAllImages()
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
        val src = state.document.pdfFile?.let { com.xnotes.platform.PdfSource.create(appContext, it) }
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
        palette = Palette.forAppearance(p.uiAppearance, p.accentColor)
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
        startPdfRefine() // re-sweep / clear the hint if the dark-mode or keep-images preference toggled
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
        if (noteOpen) saveViewState() // remember this folder note's view for next time
        settingsRepo.save(settings)
        saveSession()
    }

    /** Persist the working session (open document + zoom/scroll) so the next launch
     *  reopens this note where the user left off, unsaved edits included. */
    private fun saveSession() {
        if (!sessionLoaded) return // don't overwrite the saved note before restore has applied
        if (!noteOpen) { session.clear(); return } // on backstage: nothing open -> wipe any stale session
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
            adoptOpenPdf(snap.document) // track the restored note's temp PDF for later cleanup
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
        warmVisibleLinks(pageIndex)
        if (controller.editingItem != null) editingField = controller.editingField()
        refreshTextBar()
    }

    /** Proactively parse the current page's PDF links off-thread (coalesce + cancel-stale) so a tap
     *  lands on a warm cache. Cheap and idempotent; a fast scroll keeps moving the target, so only the
     *  page it settles on is parsed, never a backlog. The on-tap parse stays as a fallback. */
    private fun warmVisibleLinks(pageIndex: Int) {
        val src = pdfSource ?: return
        val pdfIdx = state.document.pages.getOrNull(pageIndex)?.pdfPage ?: return
        src.warmLinks(pdfIdx)
    }

    /** Recompute the floating text style bar's anchor + values (it follows pan/zoom/selection). */
    private fun refreshTextBar() {
        textBar = controller.computeTextBar()
    }

    /** Set the active text box's font family (and the family new boxes are created with). */
    fun setTextFace(face: FontFace) {
        controller.setTextFace(face)
        refreshTextBar()
    }

    /** Set the active text box's point size (and the size new boxes are created with). */
    fun setTextPointSize(size: Double) {
        controller.setTextPointSize(size)
        refreshTextBar()
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
            // Pure consumer: never parses. Stamps real image colours only if the linear sweep has
            // already found this page's locations, otherwise draws it inverted; the row re-renders
            // un-inverted once the sweep reaches the page (see onImagesReady → pdfThumbTick).
            val invert = settings.prefs.pdfDarkMode
            val keep = invert && settings.prefs.pdfKeepImageColors
            src.renderPage(pi, w, h, invert, keepImages = keep)?.let { bg ->
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

    /** Drop any cached side-panel thumbnails backed by PDF page [pdfPageIndex] (its colours just
     *  finished parsing), so the panel re-renders them un-inverted. Returns true if one was evicted. */
    private fun evictPageThumbnails(pdfPageIndex: Int): Boolean = synchronized(pageThumbs) {
        var evicted = false
        state.document.pages.forEach { if (it.pdfPage == pdfPageIndex && pageThumbs.remove(it) != null) evicted = true }
        evicted
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
            val doc = codec.read(input, pdfDir)
            doc.path = uri
            doc.displayName = name
            doc.dirty = false
            replaceDocument(doc)
            maybeBindAutosave(uri) // resume autosaving if this note lives in the granted folder
            noteOpen = true // push the editor on top of backstage (only on a successful open)
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
            invalidateThumb(uri) // content changed; re-render its tile next time it's shown
        } catch (e: Exception) {
            message = "Could not save the note."
        }
    }

    // --- explorer thumbnails & document identity ---

    /**
     * Canonical identity of a document — provider authority + document id — so the same file
     * reached as a tree URI (the in-app explorer / a folder note) and as a plain document URI
     * (the system "Open…" picker) maps to one key. Shared by the per-note view state ([viewKey])
     * and the creation-time store. Falls back to the raw string for non-document URIs.
     */
    private fun documentKey(uri: String): String = runCatching {
        val u = android.net.Uri.parse(uri)
        "${u.authority}|${android.provider.DocumentsContract.getDocumentId(u)}"
    }.getOrDefault(uri)

    /** The storage display name for a document/tree URI (no extension stripped), or null. */
    private fun queryDisplayName(uri: android.net.Uri): String? = runCatching {
        appContext.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0) c.getString(i) else null
            } else null
        }
    }.getOrNull()

    /** The first page of a loaded document rendered to a [sidePx]×[sidePx] tile, cropped to the page
     *  top (the square surface clips the overflow). Uses [itemsSnapshot] because the close-hook renders
     *  the live document off-thread, which a main-thread edit can mutate underneath it. */
    private fun renderDocThumbnailSquare(doc: Document, sidePx: Int): android.graphics.Bitmap? {
        val page = doc.pages.firstOrNull() ?: return null
        val side = sidePx.coerceAtLeast(1)
        val scale = side.toDouble() / page.width
        val surface = com.xnotes.platform.AndroidRasterSurface.create(side, side)
        surface.fill(state.paperColor(page))
        val r = surface.renderer()
        r.scale(scale, scale)
        doc.pdfFile?.let { file ->
            runCatching {
                com.xnotes.platform.PdfSource.create(appContext, file)?.let { src ->
                    page.pdfPage?.let { pi ->
                        val keep = settings.prefs.pdfDarkMode && settings.prefs.pdfKeepImageColors
                        if (keep) src.ensureImageRects(pi)
                        src.renderPage(pi, side, side, settings.prefs.pdfDarkMode, keepImages = keep)?.let { bg ->
                            r.drawRaster(bg, Rect(0.0, 0.0, page.width, page.height))
                            bg.recycle()
                        }
                    }
                    src.close()
                }
            }
        }
        for (item in itemsSnapshot(page)) item.paint(r)
        return surface.bitmap
    }

    /** The square side (px) explorer tiles render at — fixed so rotation/column changes don't re-render. */
    private val tilePx = 600

    /** An already-loaded tile for [uri] at its current content, to seed the grid instantly. */
    fun cachedNoteTile(uri: String): ImageBitmap? = synchronized(noteThumbs) { noteThumbs.get(uri) }

    /**
     * The square thumbnail for the note at [uri], for the explorer grid. Returns a cached hit
     * (memory, then disk) as-is; otherwise renders off the main thread on the single low-priority
     * [thumbDispatcher] (one note at a time) so a folder of many notes fills in gradually without
     * stalling the UI. The cache is authoritative — a cached tile is shown unconditionally; a content
     * change drops it ([invalidateThumb]) so it re-renders, and closing a note regenerates it.
     */
    suspend fun noteTileThumbnail(uri: String): ImageBitmap? {
        synchronized(noteThumbs) { noteThumbs.get(uri)?.let { return it } }
        return withContext(thumbDispatcher) {
            delay(150) // let a quick scroll-past cancel this before any heavy work begins
            if (!isActive) return@withContext null
            synchronized(noteThumbs) { noteThumbs.get(uri)?.let { return@withContext it } }
            val bmp = thumbCache.load(uri) ?: run {
                val doc = runCatching {
                    appContext.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { codec.read(it, pdfDir) }
                }.getOrNull() ?: return@withContext null
                try {
                    renderDocThumbnailSquare(doc, tilePx)?.also { thumbCache.store(uri, it) } ?: return@withContext null
                } finally {
                    doc.pdfFile?.delete() // transient doc loaded just for a thumbnail — drop its extracted PDF
                }
            }
            val img = bmp.asImageBitmap()
            synchronized(noteThumbs) { noteThumbs.put(uri, img) }
            img
        }
    }

    /** Drop a note's cached tile (memory + disk) so it re-renders with fresh content next time it's shown. */
    private fun invalidateThumb(uri: String) {
        synchronized(noteThumbs) { noteThumbs.remove(uri) }
        thumbCache.remove(uri)
    }

    /**
     * Render the just-closed note's tile from the in-memory document and cache it (memory + disk), so
     * the grid shows it instantly and current. Runs on the low-priority [thumbDispatcher]; identity-
     * guarded so that if the user has already opened another note (state.document changed) it bails
     * rather than caching the wrong pixels. Called from [goHome] — never during editing.
     */
    private suspend fun regenerateClosedNoteThumb(uri: String) {
        val doc = state.document
        withContext(thumbDispatcher) {
            if (state.document !== doc) return@withContext // a new note was opened; don't cache stale pixels
            val bmp = runCatching { renderDocThumbnailSquare(doc, tilePx) }.getOrNull() ?: return@withContext
            thumbCache.store(uri, bmp)
            val img = bmp.asImageBitmap()
            synchronized(noteThumbs) { noteThumbs.put(uri, img) }
        }
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
        createdStore.clear() // and every tracked creation time — the keys only meant anything for that folder
        synchronized(noteThumbs) { noteThumbs.evictAll() }
        thumbCache.prune(emptySet()) // every cached tile belonged to the released folder
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

    /** Creates a new `.xnote` named [name] under [parentDocId], written by [write]; returns its URI, or null.
     *  If [write] throws (an IO error, or a cancelled import), the half-written file is deleted so a failed
     *  import never leaves an empty/partial note behind. */
    private fun createNoteFile(treeUri: String, parentDocId: String, name: String, write: (OutputStream) -> Unit): String? {
        val parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(android.net.Uri.parse(treeUri), parentDocId)
        val uri = runCatching {
            android.provider.DocumentsContract.createDocument(appContext.contentResolver, parent, "application/octet-stream", name)
        }.getOrNull() ?: return null
        return runCatching {
            appContext.contentResolver.openOutputStream(uri, "wt")?.use { write(it) }
            uri.toString()
        }.getOrElse {
            runCatching { android.provider.DocumentsContract.deleteDocument(appContext.contentResolver, uri) }
            null
        }
    }

    /** Creates a blank `.xnote` under [parentDocId]; returns its URI, or null. IO — call off-thread. */
    fun createBlankNoteFile(treeUri: String, parentDocId: String, rawName: String): String? {
        val name = uniqueNoteName(treeUri, parentDocId, rawName)
        val blank = Document.blank(Document.DEFAULT_NEW_PAGES, settings.prefs.defaultPageSize, settings.prefs.defaultPageOrientation)
        return createNoteFile(treeUri, parentDocId, name) { codec.write(blank, it) }
    }

    /** Imports the PDF at [pdfFile] into a new `.xnote` under [parentDocId] (named after [rawName]);
     *  returns its URI, or null. The PDF is streamed straight into the bundle, never held in RAM. IO. */
    fun createPdfNoteFile(treeUri: String, parentDocId: String, rawName: String, pdfFile: java.io.File): String? {
        val source = com.xnotes.platform.PdfSource.create(appContext, pdfFile) ?: return null
        val doc = com.xnotes.platform.PdfImporter.import(source, state.document.dpi) // doc.pdfFile = pdfFile
        val name = uniqueNoteName(treeUri, parentDocId, rawName)
        val uri = createNoteFile(treeUri, parentDocId, name) { codec.write(doc, it) { importCancelled.get() } }
        source.close()
        return uri
    }

    /** Saves the picked `.xnote` at [file] into a new file under [parentDocId] (named after [rawName]);
     *  returns its URI, or null. Streamed copy, so a big embedded PDF never loads into RAM. IO. */
    fun createNoteFileFromFile(treeUri: String, parentDocId: String, rawName: String, file: java.io.File): String? {
        runCatching { java.io.FileInputStream(file).use { codec.read(it) } }.getOrNull() ?: return null // validate it's a real .xnote (no PDF extraction)
        val name = uniqueNoteName(treeUri, parentDocId, rawName)
        return createNoteFile(treeUri, parentDocId, name) { out -> copyStream(java.io.FileInputStream(file), out) { importCancelled.get() } }
    }

    /** Streams [input] to a private temp file for a pending import; returns it, or null. The caller
     *  owns the file (it's handed to [requestImport]). Copies in small buffers so a large pick never
     *  loads into RAM. IO — call off the main thread. */
    private fun stageImport(input: InputStream): java.io.File? {
        val f = runCatching { java.io.File.createTempFile("import", ".tmp", pdfDir) }.getOrNull() ?: return null
        return runCatching {
            java.io.FileOutputStream(f).use { copyStream(input, it) { importCancelled.get() } } // closes input
            f
        }.getOrElse { f.delete(); null } // failed or cancelled mid-copy: drop the partial temp
    }

    /** Copies [input] to [out] in small buffers, polling [isCancelled] so a long copy can abort
     *  (throwing [DocumentCodec.WriteCancelled], which [createNoteFile] turns into a discarded file). */
    private fun copyStream(input: InputStream, out: OutputStream, isCancelled: () -> Boolean) {
        val buf = ByteArray(64 * 1024)
        input.use {
            while (true) {
                if (isCancelled()) throw DocumentCodec.WriteCancelled()
                val n = it.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        }
    }

    /** A picked PDF/.xnote (referenced by content [uri]) now awaits a name before being saved into the
     *  folder. The file is deliberately **not** copied yet — that happens at [commitImport], under the
     *  import loader — so the name dialog can appear instantly instead of after a big copy. */
    fun requestImport(kind: ImportKind, defaultName: String, uri: String) {
        pendingImport = PendingImport(kind, defaultName, uri)
    }

    /** Discards a pending import (the user cancelled the name prompt). Nothing was copied yet. */
    fun cancelImport() { pendingImport = null }

    /** Saves a pending import into [parentDocId] under [treeUri] as [rawName]; returns its URI, or null.
     *  Copies the picked file to a local temp first (the slow part, shown under the import loader), then
     *  builds the note and drops the temp. Clears the request on success or cancel; keeps it on a genuine
     *  failure so the user can retry the name. IO — call off-thread. */
    fun commitImport(treeUri: String, parentDocId: String, rawName: String): String? {
        val pending = pendingImport ?: return null
        importCancelled.set(false)
        val staged = runCatching {
            appContext.contentResolver.openInputStream(android.net.Uri.parse(pending.uri))?.let { stageImport(it) }
        }.getOrNull()
        val uri = if (staged == null) null else try {
            when (pending.kind) {
                ImportKind.PDF -> createPdfNoteFile(treeUri, parentDocId, rawName, staged)
                ImportKind.OPEN -> createNoteFileFromFile(treeUri, parentDocId, rawName, staged)
            }
        } finally {
            staged.delete()
        }
        if (uri != null || importCancelled.get()) {
            pendingImport = null
        }
        return uri
    }

    /** Commits the pending import off the main thread while driving the "Importing…" dialog via
     *  [importing]. Returns the new note's URI, or null on failure/cancel. On cancel [pendingImport]
     *  is cleared (dialog dismisses); on a genuine failure it's kept so the caller can show an error
     *  and let the user retry. */
    suspend fun commitImportAsync(treeUri: String, parentDocId: String, rawName: String): String? {
        importing = true
        return try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                commitImport(treeUri, parentDocId, rawName)
            }
        } finally {
            importing = false
        }
    }

    /** Aborts an in-flight import (the dialog's Cancel) so its stream-copy stops at the next buffer. */
    fun cancelImportInProgress() { importCancelled.set(true) }

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
            // The id (hence the key) changed, but it's the same logical note — carry its created time
            // across so a rename keeps its place in the grid instead of looking newly created.
            createdStore.rekey(documentKey(docUri), documentKey(resultUri))
            invalidateThumb(docUri)
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
        // View-state and creation-time keys are document identities ("$auth|$id", see documentKey), so
        // match them by prefix. This runs whether or not the note is on screen, so a same-named file later
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
            // Only reset to a fresh page if the deleted note is actually on screen; while on backstage
            // (noteOpen == false) the detached buffer is left as-is so we don't pop into a blank editor.
            autosaveScope.launch { if (state.document === deleted && noteOpen) newNote() }
        }

        // Forget the deleted item's tracked creation time (and the whole subtree's, for a folder),
        // matched the same way as the view state, and drop its cached tile.
        createdStore.removeMatching { it == keyPrefix || it.startsWith("$keyPrefix/") }
        invalidateThumb(docUri)
    }

    /**
     * Copies [sourceUri] into the folder [targetParentDocId] within [treeUri]. On a name clash — most
     * often pasting a copy into the same folder — a file is duplicated under a free "… copy" name
     * rather than failing. (A folder can't be byte-streamed, so a folder name clash still fails.)
     * IO, call off-thread.
     */
    fun copyDocumentInto(treeUri: String, sourceUri: String, targetParentDocId: String): Boolean = runCatching {
        val tree = android.net.Uri.parse(treeUri)
        val target = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, targetParentDocId)
        val src = android.net.Uri.parse(sourceUri)
        // Native copy first: it succeeds outright when there's no name clash (e.g. a different folder).
        // Wrapped on its own, because providers *throw* (rather than return null) on a same-name clash —
        // we must catch that here so it falls through to making a renamed duplicate below instead of
        // failing the whole paste.
        val direct = runCatching {
            android.provider.DocumentsContract.copyDocument(appContext.contentResolver, src, target)
        }.getOrNull()
        if (direct != null) return@runCatching true
        // The clash case (usually pasting into the same folder): duplicate under a free "… copy" name.
        val isDir = appContext.contentResolver.getType(src) == android.provider.DocumentsContract.Document.MIME_TYPE_DIR
        val srcName = queryDisplayName(src) ?: return@runCatching false
        val taken = browseChildren(treeUri, targetParentDocId).mapTo(HashSet()) { it.name.lowercase() }
        val newName = uniqueCopyName(srcName, taken, splitExtension = !isDir)
        if (isDir) {
            copyFolderAs(treeUri, src, target, newName)
        } else {
            val newUri = android.provider.DocumentsContract.createDocument(
                appContext.contentResolver, target, "application/octet-stream", newName,
            ) ?: return@runCatching false
            val copied = runCatching {
                appContext.contentResolver.openInputStream(src)?.use { input ->
                    appContext.contentResolver.openOutputStream(newUri, "wt")?.use { output -> input.copyTo(output); true } ?: false
                } ?: false
            }.getOrDefault(false)
            if (!copied) { runCatching { android.provider.DocumentsContract.deleteDocument(appContext.contentResolver, newUri) }; return@runCatching false }
            true
        }
    }.getOrDefault(false)

    /**
     * Duplicates folder [srcFolder] into [targetParent] under [newName]. The new folder starts empty, so
     * each child copies in with no name clash and the provider's native copy recurses into subfolders.
     * Best-effort: returns false if any child failed (a partial copy is left in place). IO, call off-thread.
     */
    private fun copyFolderAs(treeUri: String, srcFolder: android.net.Uri, targetParent: android.net.Uri, newName: String): Boolean {
        val dest = android.provider.DocumentsContract.createDocument(
            appContext.contentResolver, targetParent, android.provider.DocumentsContract.Document.MIME_TYPE_DIR, newName,
        ) ?: return false
        val srcDocId = android.provider.DocumentsContract.getDocumentId(srcFolder)
        var ok = true
        for (child in childDocumentUris(treeUri, srcDocId)) {
            val copied = runCatching {
                android.provider.DocumentsContract.copyDocument(appContext.contentResolver, child, dest)
            }.getOrNull()
            if (copied == null) ok = false
        }
        return ok
    }

    /** Every child document URI under [folderDocId] in tree [treeUri] (all kinds, not just notes/folders). */
    private fun childDocumentUris(treeUri: String, folderDocId: String): List<android.net.Uri> {
        val tree = android.net.Uri.parse(treeUri)
        val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderDocId)
        val out = ArrayList<android.net.Uri>()
        runCatching {
            appContext.contentResolver.query(
                childrenUri, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    out.add(android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id))
                }
            }
        }
        return out
    }

    /** A free name for a duplicate: the original if it's free, else "<stem> copy<.ext>" then "copy 2", "copy 3", … */
    private fun uniqueCopyName(name: String, taken: Set<String>, splitExtension: Boolean): String {
        if (name.lowercase() !in taken) return name
        val dot = if (splitExtension) name.lastIndexOf('.') else -1
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var candidate = "$stem copy$ext"
        var n = 2
        while (candidate.lowercase() in taken) { candidate = "$stem copy $n$ext"; n++ }
        return candidate
    }

    /** Moves [sourceUri] from [sourceParentDocId] into [targetParentDocId] within [treeUri]; follows the open note. IO. */
    fun moveDocumentInto(treeUri: String, sourceUri: String, sourceParentDocId: String, targetParentDocId: String): Boolean = runCatching {
        // Pasting a cut into the folder the items already live in is a no-op (and SAF would reject the
        // same-parent move), so report success without touching anything.
        if (sourceParentDocId == targetParentDocId) return@runCatching true
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
            if (ok) {
                state.document.dirty = false; dirty = false
                invalidateThumb(uri) // file changed on disk; drop the stale tile so the grid re-renders it
            }
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
        if (ok) { state.document.dirty = false; dirty = false; invalidateThumb(uri) }
    }

    // --- per-document view state (folder notes remember their own zoom + scroll) ---

    /**
     * The view-state key for a note in the granted folder — its document identity, shared with
     * [documentKey] — or null when it isn't a folder document, so only folder notes remember a view.
     */
    private fun viewKey(uri: String?): String? {
        val u = uri ?: return null
        val root = browseRoot ?: return null
        return if (isUnderTree(u, root)) documentKey(u) else null
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
        // Stamp any item we haven't seen before with the moment we discovered it, so the grid can
        // order by creation even though SAF reports only last-modified: items the app created are
        // discovered on the listing right after, and an externally-added file is "created" when found.
        val now = System.currentTimeMillis()
        createdStore.stampMissing(out.map { documentKey(it.documentUri) }, now)
        val withCreated = out.map { it.copy(created = createdStore.get(documentKey(it.documentUri)) ?: now) }
        val result = withCreated.sortedWith(explorerComparator { it.created })
        browseCache["$treeUri|$parentDocId"] = result
        return result
    }

    /** Last-listed children for a folder, to seed the explorer instantly before the refresh. */
    fun cachedChildren(treeUri: String, parentDocId: String): List<BrowseEntry>? = browseCache["$treeUri|$parentDocId"]

    /**
     * Recursively finds notes at or below [startDocId] in tree [treeUri] whose name (sans `.xnote`)
     * contains [query], case-insensitively. Folders are descended into but not themselves returned;
     * results are ordered newest-first like the grid. Does one provider query per folder, so it's IO
     * and can be slow on deep trees — call off-thread (and debounce the keystrokes that drive it).
     */
    fun searchNotes(treeUri: String, startDocId: String, query: String): List<BrowseEntry> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        val out = ArrayList<BrowseEntry>()
        val seen = HashSet<String>()
        val stack = ArrayDeque<String>().apply { addLast(startDocId) }
        while (stack.isNotEmpty()) {
            val docId = stack.removeLast()
            if (!seen.add(docId)) continue // guard against any cyclic SAF links
            for (e in browseChildren(treeUri, docId)) {
                if (e.isDir) {
                    stack.addLast(browseDocId(e.documentUri))
                } else {
                    val display = if (e.name.endsWith(".xnote", ignoreCase = true)) e.name.dropLast(6) else e.name
                    if (display.contains(needle, ignoreCase = true)) out.add(e)
                }
            }
        }
        return out.sortedWith(
            compareByDescending<BrowseEntry> { it.created }.thenByDescending { it.modified }.thenBy { it.name.lowercase() },
        )
    }

    /** Warm the backstage caches off-thread (after launch) so its first open paints instantly. */
    fun prewarmBackstage() {
        autosaveScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    browseRoot?.let { root ->
                        browseRootName(root)
                        browseChildren(root, browseRootDocId(root))
                    }
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
        val doc = appContext.contentResolver.openInputStream(android.net.Uri.parse(srcUri))?.use { codec.read(it, pdfDir) } ?: return
        val src = doc.pdfFile?.let { com.xnotes.platform.PdfSource.create(appContext, it) }
        try {
            com.xnotes.platform.PdfExporter.export(appContext, doc, src, out, { page -> state.paperColor(page) }, onProgress, isCancelled)
        } finally {
            src?.close()
            doc.pdfFile?.delete() // transient doc loaded just for export — drop its extracted PDF
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
        adoptOpenPdf(doc) // outgoing note's PDF source is now closed; delete its temp file
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
        // pickInk also recolours the active text box (editing or selected), so the 5 toolbar
        // swatches double as the text colour control.
        controller.pickInk(toolbarColors[index.coerceIn(0, toolbarColors.lastIndex)])
        refreshTextBar()
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
        val pagesBefore = state.document.pages.size
        history.undo()
        afterHistory(structural = state.document.pages.size != pagesBefore)
    }

    fun redo() {
        val pagesBefore = state.document.pages.size
        history.redo()
        afterHistory(structural = state.document.pages.size != pagesBefore)
    }

    private fun afterHistory(structural: Boolean) {
        controller.clearSelection()
        if (structural) state.relayout() // page add/remove shifts layout; page-keyed caches survive
        // Repair the ink caches in place rather than dropping them — dropping blanked every visible
        // page to bare paper for a frame (the undo/redo flicker). Only AddPage/DeletePage change the
        // page set, so relayout (which re-renders the sharp viewport) is gated on that.
        state.repairAllInkInPlace()
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
        val sub = Document(dpi = state.document.dpi, pdfFile = state.document.pdfFile)
        sub.pages.addAll(pages) // share the page objects; export only reads them
        // A private source per export — see [exportPdf]: the canvas's cache thread may be
        // touching the live [pdfSource], and PdfRenderer can't be shared across threads. The
        // shared PDF file is read-only and owned by the open document, so closing src won't delete it.
        val src = sub.pdfFile?.let { com.xnotes.platform.PdfSource.create(appContext, it) }
        try {
            com.xnotes.platform.PdfExporter.export(appContext, sub, src, out, state::paperColor, onProgress, isCancelled)
        } finally {
            src?.close()
        }
    }

    /** PNG bytes for page [index], rendered at full page resolution (paper + background + items), or null. */
    fun pageImagePng(index: Int): ByteArray? {
        val page = pageAt(index) ?: return null
        // One-shot export: find this page's image locations now so the PNG is correct regardless of how
        // far the background sweep has reached (renderThumbnail itself is a pure consumer).
        if (settings.prefs.pdfDarkMode && settings.prefs.pdfKeepImageColors) {
            page.pdfPage?.let { pi -> pdfSource?.ensureImageRects(pi) }
        }
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
        rebuildPdfSource() // close the outgoing note's PDF source (a blank note has none)
        adoptOpenPdf(state.document) // and reclaim its temp PDF file now it's released
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
        noteOpen = true // push the editor on top of backstage
    }

    /** Pop back to backstage: detach the current note (flush autosave, drop the binding) and clear
     *  [noteOpen] so the editor is removed from the stack. The document stays as an inert buffer. */
    fun goHome() {
        if (!noteOpen) return
        saveViewState() // remember this folder note's view before leaving
        flushAutosave() // write the note back to its folder file if it autosaves
        // Regenerate this folder note's grid tile now that editing is done (off-thread, low priority) —
        // a non-folder note (no autosave binding) isn't shown in the explorer, so there's nothing to do.
        autosaveUri?.let { uri -> autosaveScope.launch { regenerateClosedNoteThumb(uri) } }
        autosaveUri = null
        noteOpen = false
    }
}
