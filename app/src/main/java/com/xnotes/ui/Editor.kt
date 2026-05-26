package com.xnotes.ui

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xnotes.canvas.CanvasState
import com.xnotes.canvas.CanvasView
import com.xnotes.canvas.EditingField
import com.xnotes.canvas.InteractionController
import com.xnotes.core.geometry.Rect
import com.xnotes.core.history.AddItem
import com.xnotes.core.history.AddPage
import com.xnotes.core.history.DeletePage
import com.xnotes.core.history.History
import com.xnotes.core.model.Bookmark
import com.xnotes.core.model.Document
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Orientation
import com.xnotes.core.model.Page
import com.xnotes.core.model.PageSize
import com.xnotes.core.model.Rgba
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
import kotlinx.coroutines.launch

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
    private var lastSessionContentVersion = -1
    private var sessionLoaded = false

    /** In-memory cache of recent-file thumbnails, keyed by SAF URI (pruned to the recent list). */
    private val recentThumbs = java.util.concurrent.ConcurrentHashMap<String, android.graphics.Bitmap>()
    /** Cached page counts per recent URI (filled alongside the thumbnail's doc load). */
    private val recentPages = java.util.concurrent.ConcurrentHashMap<String, Int>()
    /** On-disk thumbnail cache so the backstage opens instantly across launches. */
    private val thumbCache = com.xnotes.platform.RecentThumbnailCache(java.io.File(appContext.filesDir, "recent_thumbs"))
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
        onSelectionChanged = { selected -> hasSelection = selected },
        onToolChanged = { t -> tool = t },
        onTextEditStart = { field -> editingField = field },
        onTextEditEnd = { editingField = null },
        onSelectionMenu = { rect -> selectionMenu = rect },
        onContextMenu = { vp, content -> contextMenu = ContextMenuTarget(vp.x, vp.y, content) },
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
        pdfSource = state.document.pdfBytes?.let { com.xnotes.platform.PdfSource.create(appContext, it) }
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
                    src.renderRegion(pi, fullW, fullH, rx, ry, rw, rh, settings.prefs.pdfDarkMode)?.let { bg ->
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

    fun importPdf(bytes: ByteArray) {
        val source = com.xnotes.platform.PdfSource.create(appContext, bytes)
        if (source == null) {
            message = "Could not import the PDF."
            return
        }
        val doc = com.xnotes.platform.PdfImporter.import(bytes, source, state.document.dpi)
        source.close()
        doc.dirty = true
        replaceDocument(doc)
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

    fun exportPdf(output: OutputStream) {
        try {
            com.xnotes.platform.PdfExporter.export(state.document, pdfSource, output)
            message = "Exported to PDF."
        } catch (e: Exception) {
            message = "Could not export to PDF."
        }
    }

    val preferences: Preferences get() = settings.prefs

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
        currentUri?.let { settings = settings.rememberFile(it); recentFiles = settings.recentFiles }
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
            state.invalidateAllCaches()
            if (snap.zoom > 0.0) {
                state.zoom = snap.zoom.coerceIn(CanvasState.MIN_ZOOM, CanvasState.MAX_ZOOM)
                state.scrollX = snap.scrollX
                state.scrollY = snap.scrollY
                state.didInitialFit = true // keep the restored zoom/scroll instead of fitting width
            }
            zoomLocked = snap.zoomLocked
            state.zoomLocked = snap.zoomLocked
            state.relayout()
            if (state.viewportW > 0) {
                if (!state.didInitialFit) {
                    state.fitWidth()
                    state.didInitialFit = true
                }
                state.clampScroll()
            }
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

    /** Renders a page to a thumbnail bitmap (paper + PDF/template background + items). */
    fun renderThumbnail(pageIndex: Int, widthPx: Int): android.graphics.Bitmap? {
        val page = state.document.pages.getOrNull(pageIndex) ?: return null
        val scale = widthPx / page.width
        val w = widthPx.coerceAtLeast(1)
        val h = (page.height * scale).toInt().coerceAtLeast(1)
        val surface = com.xnotes.platform.AndroidRasterSurface.create(w, h)
        surface.fill(state.paperColor(page))
        val r = surface.renderer()
        r.scale(scale, scale)
        state.paintPageBackground?.invoke(page, r, scale, com.xnotes.core.geometry.Rect(0.0, 0.0, page.width, page.height))
        for (item in page.items) item.paint(r)
        return surface.bitmap
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

    private fun rememberRecent(uri: String) {
        settings = settings.rememberFile(uri)
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
            val keep = settings.recentFiles.toSet()
            recentThumbs.keys.retainAll(keep)
            recentPages.keys.retainAll(keep)
            recentInfoCache.keys.retainAll(keep)
        }
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
                        src.renderPage(pi, w, h, settings.prefs.pdfDarkMode)?.let { bg ->
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

    /** Resolves a note file name: blank -> "untitled_N.xnote" (N avoids conflicts); else ensures .xnote. */
    private fun resolveNoteName(treeUri: String, parentDocId: String, raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isNotEmpty()) return if (trimmed.endsWith(".xnote", ignoreCase = true)) trimmed else "$trimmed.xnote"
        val taken = browseChildren(treeUri, parentDocId).map { it.name.lowercase() }.toSet()
        var n = 1
        while ("untitled_$n.xnote" in taken) n++
        return "untitled_$n.xnote"
    }

    /** Creates a blank `.xnote` under [parentDocId]; returns its URI, or null. IO — call off-thread. */
    fun createBlankNoteFile(treeUri: String, parentDocId: String, rawName: String): String? = runCatching {
        val name = resolveNoteName(treeUri, parentDocId, rawName)
        val parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(android.net.Uri.parse(treeUri), parentDocId)
        val uri = android.provider.DocumentsContract.createDocument(
            appContext.contentResolver, parent, "application/octet-stream", name,
        ) ?: return null
        val blank = Document.blank(Document.DEFAULT_NEW_PAGES, settings.prefs.defaultPageSize, settings.prefs.defaultPageOrientation)
        appContext.contentResolver.openOutputStream(uri, "wt")?.use { codec.write(blank, it) }
        uri.toString()
    }.getOrNull()

    /** Adopts a just-created folder note [uri] as the current document (blank, autosaving). Main thread. */
    fun adoptFolderNote(uri: String) {
        val doc = Document.blank(Document.DEFAULT_NEW_PAGES, settings.prefs.defaultPageSize, settings.prefs.defaultPageOrientation)
        doc.path = uri
        doc.displayName = queryDisplayName(android.net.Uri.parse(uri))
        doc.dirty = false
        replaceDocument(doc)   // clears autosaveUri
        maybeBindAutosave(uri) // it lives in the folder, so it autosaves
        rememberRecent(uri)
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
            settings = settings.copy(recentFiles = settings.recentFiles.map { if (it == docUri) resultUri else it })
            recentFiles = settings.recentFiles
            settingsRepo.save(settings)
            invalidateRecentThumb(docUri)
        }
        return true
    }

    /** Deletes a document (file or folder). IO, call off-thread. */
    fun deleteDocument(docUri: String): Boolean = runCatching {
        val ok = android.provider.DocumentsContract.deleteDocument(appContext.contentResolver, android.net.Uri.parse(docUri))
        if (ok && state.document.path == docUri) autosaveUri = null // its backing file is gone
        ok
    }.getOrDefault(false)

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

    // --- sharing (writes the current document for an ACTION_SEND intent) ---

    fun writeCurrentDocument(out: OutputStream) = codec.write(state.document, out)

    fun writeCurrentPdf(out: OutputStream) =
        com.xnotes.platform.PdfExporter.export(state.document, pdfSource, out)

    private fun replaceDocument(doc: Document) {
        flushAutosave() // save the outgoing note if it was autosaving to the folder
        autosaveUri = null
        controller.commitTextEdit()
        controller.clearSelection()
        state.document = doc
        rebuildPdfSource()
        history.clear()
        state.invalidateAllCaches()
        state.didInitialFit = false
        state.relayout()
        if (state.viewportW > 0) {
            state.fitWidth()
            state.didInitialFit = true
        }
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

    // --- pages ---

    fun addPage() {
        val ref = state.document.pages.getOrNull(state.currentPageIndex())
        val (w, h) = if (ref != null) {
            ref.width to ref.height
        } else {
            PageSize.A4.pixels(Orientation.PORTRAIT, state.document.dpi)
        }
        val page = Page(w, h)
        val index = state.document.pages.size
        state.document.pages.add(index, page)
        history.push(AddPage(state.document, page, index))
        state.document.dirty = true
        state.relayout()
        refreshContent()
        view.requestRender()
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
        flushAutosave()
        autosaveUri = null
        state.document = Document.blank(
            Document.DEFAULT_NEW_PAGES,
            settings.prefs.defaultPageSize,
            settings.prefs.defaultPageOrientation,
        )
        history.clear()
        controller.clearSelection()
        state.invalidateAllCaches()
        state.didInitialFit = false
        state.relayout()
        if (state.viewportW > 0) {
            state.fitWidth()
            state.didInitialFit = true
        }
        refreshContent()
        view.requestRender()
    }
}
