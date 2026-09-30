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
import com.xnotes.R
import com.xnotes.canvas.CanvasState
import com.xnotes.canvas.CanvasView
import com.xnotes.canvas.EditingField
import com.xnotes.canvas.FlowTextController
import com.xnotes.canvas.InitialView
import com.xnotes.canvas.InteractionController
import com.xnotes.canvas.TextBar
import com.xnotes.core.geometry.Pt
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
import com.xnotes.core.model.FunctionSpec
import com.xnotes.core.model.TextItem
import com.xnotes.core.model.Document
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Orientation
import com.xnotes.core.model.Page
import com.xnotes.core.model.PageMargins
import com.xnotes.core.model.PagePattern
import com.xnotes.core.model.PageSize
import com.xnotes.core.model.PageStyle
import com.xnotes.core.model.PageTemplates
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.FontFace
import com.xnotes.core.pal.Renderer
import com.xnotes.core.model.deepCopy
import com.xnotes.core.model.snapshot
import com.xnotes.core.model.insets
import com.xnotes.core.model.resolvedPageColor
import com.xnotes.core.text.CellIndex
import com.xnotes.core.text.CharStyle
import com.xnotes.core.text.DeadKeyLatch
import com.xnotes.core.text.FlowDefaults
import com.xnotes.core.text.FlowEditor
import com.xnotes.core.text.FlowFrame
import com.xnotes.core.text.FlowLayout
import com.xnotes.core.text.FlowMargins
import com.xnotes.core.text.FlowPainter
import com.xnotes.platform.TemplateLibrary
import com.xnotes.core.text.FlowPos
import com.xnotes.core.text.FlowRange
import com.xnotes.core.text.InputRules
import com.xnotes.core.text.ListKind
import com.xnotes.core.text.PageBox
import com.xnotes.core.text.ParaAlign
import com.xnotes.core.text.Paragraph
import com.xnotes.core.text.PlainMath
import com.xnotes.core.text.SlashCommands
import com.xnotes.core.text.FlowTable
import com.xnotes.core.text.TableDefaults
import com.xnotes.core.text.TableEditor
import com.xnotes.core.text.TableSnapshot
import com.xnotes.core.text.TableStyle
import com.xnotes.core.text.withTableSeparators
import com.xnotes.core.text.wordBoundary
import com.xnotes.core.text.MathCaret
import com.xnotes.core.tools.InkPalette
import com.xnotes.core.tools.ShapeConfig
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.core.util.DocumentKind
import com.xnotes.core.util.NameTemplate
import com.xnotes.format.DocumentCodec
import com.xnotes.format.SvgColors
import com.xnotes.format.XNoteFormatException
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidSurfaceFactory
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.settings.ExplorerSortKey
import com.xnotes.settings.ExplorerView
import com.xnotes.sync.filen.FilenLocalStore
import com.xnotes.settings.LiveSettings
import com.xnotes.settings.Preferences
import com.xnotes.settings.CornerStyle
import com.xnotes.settings.ToolbarLook
import com.xnotes.settings.MaterialColourMode
import com.xnotes.settings.Settings
import com.xnotes.settings.SettingsRepository
import com.xnotes.ui.theme.MaterialColors
import com.xnotes.ui.theme.Palette
import com.xnotes.ui.theme.dynamicMaterialColors
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
/**
 * Where a long press landed. [locked] is the pinned item under it, if there was one, and it turns
 * the menu into a single offer to release that item.
 */
data class ContextMenuTarget(
    val viewportX: Double,
    val viewportY: Double,
    val content: com.xnotes.core.geometry.Pt,
    val locked: com.xnotes.core.model.CanvasItem? = null,
)

/**
 * One entry (folder or .xnote file) in the in-app explorer. [documentUri] is a SAF document URI.
 * [modified] is SAF's last-modified time; [created] is the app-tracked creation time used for grid
 * ordering (SAF exposes no creation time — see [CreationTimeStore]). [parentDocId] is the listing
 * folder (where colour writes go), and [color] is the item's explorer colour code, if any.
 */
data class BrowseEntry(
    val name: String,
    val documentUri: String,
    val isDir: Boolean,
    val size: Long = 0,
    val modified: Long = 0,
    val created: Long = 0,
    val parentDocId: String = "",
    val color: Rgba? = null,
)

/**
 * How a picked file should be imported: as a PDF-backed note, an image note, a text note (extracted
 * from txt/csv/rtf/html/docx/xlsx/epub), or an existing `.xnote`/`.xcanvas` opened as-is.
 */
enum class ImportKind {
    PDF, IMAGE, TEXT, OPEN;

    companion object {
        /** The document extensions the "Import file" action understands (for the picker + UI hint). */
        val IMPORT_EXTENSIONS = listOf("pdf", "png", "jpg", "jpeg", "webp", "gif", "bmp", "svg",
            "txt", "md", "markdown", "csv", "rtf", "html", "htm", "epub", "docx", "xlsx")

        private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "svg", "heic", "heif")
        private val TEXT_EXT = setOf("txt", "md", "markdown", "csv", "rtf", "html", "htm", "epub", "docx", "xlsx")

        /** Classify a picked file by extension (then mime) into the importer that should handle it. */
        fun classify(name: String, mime: String?): ImportKind {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when {
                ext == "xnote" || ext == "xcanvas" -> OPEN
                ext == "pdf" || mime == "application/pdf" -> PDF
                ext in IMAGE_EXT || mime?.startsWith("image/") == true -> IMAGE
                ext in TEXT_EXT || mime?.startsWith("text/") == true -> TEXT
                else -> TEXT // best effort for unknown types
            }
        }
    }
}

/** Every note and canvas under a folder, and the names of the folders they sit in, by document id. */
class TreeFiles(val files: List<BrowseEntry>, val folderNames: Map<String, String>)

/**
 * A deleted note, canvas or folder waiting in Trash. [entry] is the item where it now lies; [path] names the
 * folders it was deleted from, below the top folder, so it can go back even on another device.
 */
class TrashItem(val wrapperDocId: String, val entry: BrowseEntry, val path: List<String>, val deleted: Long)

/** A document from Recent as the explorer shows it: the file now, when it was opened and the folder it sits in. */
class RecentEntry(val entry: BrowseEntry, val opened: Long, val where: String?)

/** A picked file awaiting a name before it's saved into the explorer's current folder. */
data class PendingImport(
    val kind: ImportKind,
    val defaultName: String,
    val uri: String,
    /** Original file name (with extension) and MIME, kept so the text importer can pick a parser. */
    val sourceName: String = "",
    val mime: String = "",
    /** The folder to save into instead of the explorer's current one, and the name prompt's title (Convert to note). */
    val parentDocId: String? = null,
    val title: String? = null,
)

/** How far a multi-file PDF import has got; drives the count in the import dialog. */
data class ImportProgress(val done: Int, val total: Int)

/** Per-folder colour sidecar: a hidden ".xnote" dir holding "colors.json" (item name -> hex). */
private const val SIDECAR_DIR = ".xnote"
private const val SIDECAR_FILE = "colors.json"
/** In the root folder's sidecar only: the names given to colour codes, so they sync with the notes. */
private const val COLOR_NAMES_FILE = "color-names.json"

/** Inside the top folder's sidecar: deleted items, each in a folder of its own with a [TRASH_INFO] beside it. */
private const val TRASH_DIR = "trash"
private const val TRASH_INFO = "trash.json"

/** How long after a tap its settings change is written, clear of the 150 ms animations it starts. */
private const val SETTINGS_SAVE_DELAY_MS = 400L

/** How many opened documents Recent remembers. */
private const val RECENT_MAX = 40


/** Recent, shared by both panes of a split so a note opened in either shows up; null until first read from settings. */
private val sharedRecents = androidx.compose.runtime.mutableStateOf<List<com.xnotes.settings.RecentDoc>?>(null)

/** Longer side a baked crop is capped to, so a large photo's crop stays fast and never OOMs. */
private const val CROP_MAX_PX = 2048

/**
 * Which pane of a split view an editor drives. [PRIMARY] is the app's one editor in the ordinary
 * single-note case and the first pane of a split; [SECONDARY] is the second pane, built only when a
 * split opens. The two differ in the app-level chrome they own, not in what they can edit: the
 * secondary never purges the shared temp dirs (the primary may have a note live in them), keeps its
 * session in its own slot, and leaves the tool/toolbar preference snapshot to the primary.
 */
enum class Pane { PRIMARY, SECONDARY }

@Stable
class Editor(context: Context, val pane: Pane = Pane.PRIMARY) : ToolPopupHost, SelectionMenuHost, LongPressMenuHost, ToastHost {

    private val appContext = context.applicationContext

    /** The context the views are built from, kept so a second pane can be built the same way. The
     *  application context will not do: it is not attached to a display, so the cutout probe throws
     *  on it and any View made from it misses the activity's theme. */
    private val viewContext = context
    private val settingsRepo = SettingsRepository(context)

    /** Read and written through the process-wide [LiveSettings] so both split panes share one copy. */
    private var settings: Settings
        get() = LiveSettings.get(settingsRepo)
        set(value) { LiveSettings.set(value) }
    private var pdfSource: com.xnotes.platform.PdfSource? = null

    private val deviceHasDisplayCutout = com.xnotes.deviceHasDisplayCutout(context)

    /** The flow defaults a new note starts with when none are saved: text sized for this screen. */
    val factoryFlow = FlowDefaults(sizePt = FlowDefaults.sizeForScreen(com.xnotes.deviceShortSideDp(context)))

    /** Whether the OS is in dark mode right now; resolves the "system" appearance. */
    private var systemInDarkMode = (context.resources.configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

    /** Whether the window runs in fullscreen. Persisted via [Preferences.startFullscreen]; when unset
     *  it defaults to on unless the display has a camera cutout. The activity observes this and drives
     *  the window. */
    var fullscreen by mutableStateOf(settings.prefs.startFullscreen ?: !deviceHasDisplayCutout)
        private set

    /** Prepares one of the shared temp dirs. The launch purge drops temps orphaned by a crash, which
     *  is safe for the primary editor because no real note is open yet at construction. A secondary
     *  pane is built mid-session, with the primary's note already live in these dirs, so it only
     *  takes the handle. */
    private fun tempDir(name: String): java.io.File =
        java.io.File(appContext.filesDir, name).apply {
            mkdirs()
            if (pane == Pane.PRIMARY) listFiles()?.forEach { it.delete() }
        }

    /** Private dir holding each note's source PDF as a file, so a large PDF is never held whole in
     *  RAM (the renderer memory-maps it). Under filesDir, not the reclaimable cacheDir: the OS could
     *  evict a cacheDir copy mid-session, and the next autosave would then fail to re-embed the PDF.
     *  Both panes share it; the files inside carry unique temp names. */
    private val pdfDir = tempDir("pdfsrc")

    /** Encoded inserted images, streamed to disk so a note full of large images never loads all their
     *  bytes into the heap; the renderer decodes from these files on demand. Under filesDir (not the
     *  reclaimable cacheDir). */
    private val imageDir = tempDir("noteimg")

    /** Scratch dir for [writeNoteSafely]: a note is encoded here in full before any SAF file is
     *  touched, so a failed encode can never truncate a good note. Lives under filesDir (not the
     *  reclaimable cacheDir). */
    private val saveTmpDir = tempDir("savetmp")

    /** The sticker library: encoded image files kept under filesDir across sessions (never purged).
     *  Only [java.io.File] handles are held in memory; thumbnails and inserts decode from disk. */
    // The directory keeps its old "stamps" name so libraries saved before the rename still load.
    private val stickerDir = java.io.File(appContext.filesDir, "stamps").apply { mkdirs() }

    /** Sticker files, oldest first (names embed the add time so a plain name sort is stable). */
    var stickers: List<java.io.File> by mutableStateOf(listStickers())
        private set

    private fun listStickers(): List<java.io.File> = stickerDir.listFiles()?.sortedBy { it.name }.orEmpty()

    /** Re-read the library into both panes: they browse one shared directory, so a sticker added
     *  or removed on one toolbar has to show up on the other. */
    private fun refreshStickers() {
        stickers = listStickers()
        sibling?.let { it.stickers = it.listStickers() }
    }

    /** The temp PDF file backing the currently open document, tracked so it's deleted when the note
     *  is swapped out (transient docs used for export/thumbnails manage their own files locally). */
    private var openPdfTemp: java.io.File? = null

    val state = CanvasState(
        blankDocument().also { stampNewNoteDefaults(it) },
        AndroidSurfaceFactory(),
        buildPalette(settings.prefs),
    )
    val history = History()
    val view = CanvasView(context).also { it.state = state }

    /** The front buffer wet ink goes into, above [view] and transparent whenever no pen is down. */
    val pad = com.xnotes.gl.GlWetPad(context, onTop = true)

    /**
     * The two surfaces, in order. The pad is a sibling above the canvas rather than part of it: the
     * canvas paints into the window and the front buffer has to be a surface of its own.
     */
    val surfaces = android.widget.FrameLayout(context).apply {
        addView(view, android.widget.FrameLayout.LayoutParams(-1, -1))
        addView(pad, android.widget.FrameLayout.LayoutParams(-1, -1))
    }
    private val textMeasurer = AndroidTextMeasurer()
    private val imageCodec = AndroidImageCodec()
    private val codec = DocumentCodec(imageCodec, textMeasurer)
    private val canvasCodec = com.xnotes.format.CanvasCodec(imageCodec)

    /** One flow layout + snapshot: repainted from cache threads, so only the published frame is read. */
    private class PublishedFlow(val frame: FlowFrame, val indexOf: Map<Page, Int>)
    private val flowLayout = FlowLayout(textMeasurer, com.xnotes.platform.MathRendering)

    private val treeSitter = com.xnotes.platform.TreeSitterHighlighter(appContext)
    private val highlighter: com.xnotes.core.text.CodeHighlighter? =
        treeSitter.takeIf { com.xnotes.platform.TreeSitterNative.loaded }

    /** A user-imported Helix code theme (parsed once); null = the built-in dark/light pair. */
    private var customCodeTheme: com.xnotes.core.text.HighlightTheme? =
        settings.prefs.codeThemePath?.let { path ->
            runCatching { com.xnotes.format.HelixTheme.parse(java.io.File(path).readText()) }.getOrNull()
        }

    private fun activeCodeTheme(): com.xnotes.core.text.HighlightTheme = customCodeTheme
        ?: if (resolvedAppearance(settings.prefs) != "light") {
            com.xnotes.core.text.HighlightTheme.DARK
        } else {
            com.xnotes.core.text.HighlightTheme.LIGHT
        }

    /** Derived spans per code paragraph, keyed by its revision (main thread only). */
    private val highlightCache = HashMap<Paragraph, Pair<Int, List<com.xnotes.core.text.HighlightSpan>>>()
    private var highlightJob: kotlinx.coroutines.Job? = null

    @Volatile private var publishedFlow: PublishedFlow? = null

    // Declared this early because the constructor already republishes the flow, which reads them.

    /** The table in structure-edit mode (handles and row/column chrome over it), or null. */
    var editingTable by mutableStateOf<FlowTable?>(null)
        private set

    /** Bumped whenever the table chrome must re-place itself (layout or view moved). */
    var tableChromeTick by mutableStateOf(0)
        private set

    /** The table whose action bar is up (a long press landed in it), or null. */
    var tableMenu by mutableStateOf<FlowTable?>(null)
        private set
    private var publishedFlowStamp = -1L
    private var publishedPageList: List<Page> = emptyList()
    /** Each pane keeps its working session in its own slot, so two open notes never overwrite
     *  each other's saved document. */
    private val sessionDir = java.io.File(appContext.filesDir, if (pane == Pane.PRIMARY) "session" else "session-b")
    private val session = com.xnotes.platform.SessionStore(sessionDir, codec, pdfDir, imageDir)
    private val canvasSession = com.xnotes.platform.CanvasSessionStore(sessionDir, canvasCodec, imageDir)
    private val viewStates = com.xnotes.platform.ViewStateStore(com.xnotes.platform.JsonStore.viewStates(appContext))
    private var lastSessionContentVersion = -1
    private var sessionLoaded = false

    // The disk cache is shared by both panes and remembers which theme produced its pixels.
    private val thumbCache = com.xnotes.platform.NoteThumbnailCache(java.io.File(appContext.filesDir, "note_thumbs"))
    @Volatile private var thumbnailGeneration = -1L
    private var thumbnailTheme: String? = null
    var thumbnailVersion by mutableStateOf(0)
        private set
    /** In-memory note-tile thumbnails keyed by SAF URI, bounded by bytes (Compose owns the pixels —
     *  no manual recycle). */
    private val noteThumbs = object : LruCache<String, ImageBitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    /** App-tracked creation times for explorer ordering (SAF exposes only last-modified). */
    private val createdStore = com.xnotes.platform.CreationTimeStore(com.xnotes.platform.JsonStore.createdTimes(appContext))
    private val docMeta = com.xnotes.platform.DocMetaStore(com.xnotes.platform.JsonStore.docMeta(appContext))
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

    /** The note autosave's debounce timer. Cancelled freely: nothing has been written yet. */
    private var noteDebounceJob: kotlinx.coroutines.Job? = null

    /**
     * The note write itself, from snapshot to bookkeeping. Never cancelled. The write blocks with
     * no suspension point, so cancelling it does not stop the bytes; it only skips everything after
     * them, leaving the note dirty, the thumbnail stale and a fork unadopted (so the next save forks
     * again). Callers [kotlinx.coroutines.Job.join] it instead, which also keeps at most one write
     * in flight and one waiting behind it.
     */
    private var noteWriteJob: kotlinx.coroutines.Job? = null

    /** Serializes every write to a note file so an autosave and a flush/Save-As can't truncate and
     *  copy the same destination at once (which would corrupt it). */
    private val saveLock = Any()

    /** Buffer for the temp-to-storage copy every save ends with. `copyTo`'s 8 KB default turns a
     *  large note into thousands of round trips through the provider; a megabyte at a time doesn't. */
    private val copyBuffer = 1024 * 1024

    /** Below this much ahead of the manifest, a note is rebuilt whole rather than spliced: writing
     *  in place is only worth its (brief) window of an invalid file when the assets are the save. */
    private val MIN_SPLICE_BYTES = 1024L * 1024L

    var tool by mutableStateOf(Tool.DEFAULT)
        private set
    var palette by mutableStateOf(state.palette)
        private set
    /** How round the chrome is, [Preferences.cornerStyle]. */
    var cornerStyle by mutableStateOf(CornerStyle.ROUNDED)
        private set
    /** How the toolbar is drawn, [Preferences.toolbarLook]. */
    var toolbarLook by mutableStateOf(ToolbarLook())
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
    /** Set when a canvas edit changed a persisted style, so the next pause writes it out. */
    private var settingsDirty = false

    var activeColorIndex by mutableStateOf(0)
        private set
    var toolbarColors by mutableStateOf(InkPalette.presets)
        private set
    var toolbarColorCount by mutableStateOf(5)
        private set
    var toolbarLayout by mutableStateOf(ToolbarLayout.DEFAULT)

    /** The infinite canvas's own bar. Its own layout: the two surfaces hold different items. */
    var canvasToolbarLayout by mutableStateOf(ToolbarLayout.CANVAS_DEFAULT)
        private set
    var renderScale by mutableStateOf(1.0)
        private set
    var sidebarVisible by mutableStateOf(false)
    /** Granted explorer root (a SAF tree URI), or null until the user picks a folder. */
    var browseRoot by mutableStateOf(settings.browseRoot)
        private set
    /** Folders pinned to the home sidebar on this device, in pin order. */
    var pinnedFolders by mutableStateOf(settings.pinnedFolders)
        private set
    /** Names given to colour codes (only named colours reach the sidebar), read from the root's sidecar. */
    var colorNames by mutableStateOf<Map<Rgba, String>>(emptyMap())
        private set
    /** The view every folder shares, and the fallback for folders without one of their own. */
    var explorerView by mutableStateOf(settings.explorerView)
        private set
    /** Views set in a single folder, by folder key; consulted only while views are per folder. */
    var folderViews by mutableStateOf(settings.folderViews)
        private set
    /** A picked PDF awaiting a name before it's imported into the explorer; drives the inline name field. */
    var pendingImport by mutableStateOf<PendingImport?>(null)
        private set
    /** True while a committed import is being written off-thread; drives the "Importing…" dialog. */
    var importing by mutableStateOf(false)
        private set
    /** PDFs picked together, awaiting a batch import into the current folder; each keeps its own name. */
    var pendingImports by mutableStateOf<List<PendingImport>>(emptyList())
        private set
    /** Files finished so far in a batch import; null for a single-file import. */
    var importProgress by mutableStateOf<ImportProgress?>(null)
        private set
    /** Flipped by the import dialog's Cancel so the in-flight stream-copy aborts at its next buffer. */
    private val importCancelled = java.util.concurrent.atomic.AtomicBoolean(false)
    /** True while a tapped note is being read off-thread; drives the "Opening note…" dialog. */
    var opening by mutableStateOf(false)
        private set
    /** True while a dirty note is being flushed to its folder file off-thread on close; drives the
     *  "Saving your notes…" dialog so quitting a large note never freezes the UI (ANR). */
    var savingNote by mutableStateOf(false)
        private set
    /** Flipped by the open dialog's Cancel so a slow open is discarded instead of swapped in. */
    private val openCancelled = java.util.concurrent.atomic.AtomicBoolean(false)
    var zoomLocked by mutableStateOf(false)
        private set

    /** Global View-menu defaults every note without overrides follows (persisted in settings.json). */
    var viewDefaults by mutableStateOf(settings.viewDefaults)
        private set

    /** The open note's View-menu overrides (stored app-side like zoom/scroll). */
    var viewOverrides by mutableStateOf(com.xnotes.canvas.ViewOverrides())
        private set

    /** The open note's effective View settings: [viewOverrides] resolved over [viewDefaults].
     *  This is what the canvas, caches and thumbnails all consume. */
    var viewSettings by mutableStateOf(settings.viewDefaults)
        private set
    var rulerVisible by mutableStateOf(false)
        private set
    var wandEnabled by mutableStateOf(false)
        private set
    var hasSelection by mutableStateOf(false)
        private set
    var shapeConfig by mutableStateOf(ShapeConfig())
        private set
    var message by mutableStateOf<String?>(null)
    /** A button the next [message] carries, such as Undo, with what it does. */
    var messageAction by mutableStateOf<Pair<String, () -> Unit>?>(null)

    /** Show [text], with [action] as a button on it. */
    fun say(text: String, action: Pair<String, () -> Unit>? = null) {
        messageAction = action
        message = text
    }

    /** How many items wait in Trash in the current folder tree. */
    var trashCount by mutableStateOf(0)
        private set

    /** Bumped when files change behind the explorer's back (an undone trash, say), so it lists again. */
    var treeVersion by mutableStateOf(0)
        private set

    /** Has the explorer list again after files changed on disk behind it (a sync, say). */
    fun relistTree() {
        treeVersion++
    }

    override var toastText by mutableStateOf<String?>(null)
        private set
    override var toastToken by mutableStateOf(0)
        private set

    /** Flash a brief themed pill at the bottom of the canvas (delete/copy/cut/paste feedback). */
    fun showToast(text: String) {
        toastText = text
        toastToken++
    }

    /** Bumped on every preferences change so open panes refresh (e.g. after an .scm import). */
    var prefsVersion by mutableStateOf(0)
        private set
    var editingField by mutableStateOf<EditingField?>(null)
        private set

    /** The floating text style bar's target (active box rect + style), or null when no box is active. */
    var textBar by mutableStateOf<TextBar?>(null)
        private set

    /** Viewport rect to anchor the on-selection menu, or null when hidden. */
    var selectionMenu by mutableStateOf<com.xnotes.core.geometry.Rect?>(null)
        private set

    override val selectionMenuRect: com.xnotes.core.geometry.Rect? get() = selectionMenu


    override val selectionFunction: FunctionSpec? get() = controller.singleSelectedFunction()?.function
    override fun setSelectionFunction(spec: FunctionSpec): Boolean = controller.setSelectedFunction(spec)

    // Gates the Crop action; vector (SVG) sources have no bitmap to bake, so crop is not offered.
    override val selectionIsImage: Boolean get() {
        val img = controller.singleSelectedImage() ?: return false
        return !com.xnotes.platform.ImageDecoder.isVector(img.image.file.path)
    }
    override fun cropSelection() = controller.beginCrop()

    override val selectionIsText: Boolean get() = controller.singleSelectedText() != null
    override fun editSelectionText() = controller.editSelectedText()

    override val selectionMath: String? get() = controller.singleSelectedText()?.takeIf { it.math }?.text
    override fun setSelectionMath(latex: String): Boolean = controller.setSelectedMath(latex)

    override val selectionCanLink: Boolean get() = controller.singleSelectedItem() != null
    override val selectionLink: String? get() = controller.singleSelectedItem()?.link
    override fun setSelectionLink(url: String?) = controller.setSelectedLink(url?.trim()?.ifEmpty { null })

    override val selectionHasInk: Boolean get() = controller.inkSelection() != null

    /** Recognize the selected handwriting off-thread, then swap it for a text box (undoable). */
    override fun convertSelectionToText() {
        val sel = controller.inkSelection() ?: return
        val lang = settings.prefs.ocrLanguage
        android.widget.Toast.makeText(appContext, "Recognizing handwriting...", android.widget.Toast.LENGTH_SHORT).show()
        autosaveScope.launch {
            val text = try {
                withContext(Dispatchers.IO) { com.xnotes.platform.HandwritingOcr.recognize(sel.strokes, lang) }
            } catch (e: Exception) {
                android.widget.Toast.makeText(appContext, "Handwriting recognition failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }
            if (text.isBlank()) {
                android.widget.Toast.makeText(appContext, "No text recognized", android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            controller.replaceInkWithText(sel, text)
        }
    }

    override val canConvertToMath: Boolean get() = com.xnotes.platform.MathOcr.isInstalled(appContext)

    override fun convertSelectionToMath() {
        val sel = controller.inkSelection() ?: return
        android.widget.Toast.makeText(appContext, "Recognizing math...", android.widget.Toast.LENGTH_SHORT).show()
        autosaveScope.launch {
            val latex = try {
                withContext(Dispatchers.Default) { com.xnotes.platform.MathOcr.recognize(appContext, sel.strokes, sel.bounds) }
            } catch (e: Throwable) {
                android.widget.Toast.makeText(appContext, "Math recognition failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }
            if (latex.isBlank()) {
                android.widget.Toast.makeText(appContext, "No math recognized", android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            controller.replaceInkWithMath(sel, latex)
        }
    }

    private var cropBaking = false

    /** Apply the in-progress crop: bake the trimmed bitmap off-thread and swap it in (undoable).
     *  The region decode + PNG re-encode used to run on the main thread and jank/ANR on big photos. */
    fun applyActiveCrop() {
        if (cropBaking) return
        val (img, cropRect) = controller.cropTarget() ?: return
        cropBaking = true
        autosaveScope.launch {
            val baked = try {
                withContext(Dispatchers.IO) { bakeCrop(img, cropRect) }
            } finally {
                cropBaking = false
            }
            controller.finishCrop(baked?.first, baked?.second)
        }
    }

    fun cancelCrop() = controller.cancelCrop()

    /** Decode [img]'s source, crop it to [cropRect] (page-local, within the image), and save a new
     *  PNG. Returns the new image data + its rect (the crop region), or null on failure. */
    private fun bakeCrop(img: com.xnotes.core.model.ImageItem, cropRect: com.xnotes.core.geometry.Rect): Pair<ImageData, Rect>? {
        if (img.rect.w <= 0.0 || img.rect.h <= 0.0) return null
        val path = img.image.file.path
        val size = imageCodec.probeFile(path) ?: return null
        val sw = size.width
        val sh = size.height
        if (sw <= 0 || sh <= 0) return null
        // The crop region in source pixels.
        val fx = ((cropRect.left - img.rect.left) / img.rect.w).coerceIn(0.0, 1.0)
        val fy = ((cropRect.top - img.rect.top) / img.rect.h).coerceIn(0.0, 1.0)
        val x = (fx * sw).toInt().coerceIn(0, sw - 1)
        val y = (fy * sh).toInt().coerceIn(0, sh - 1)
        val w = (cropRect.w / img.rect.w * sw).toInt().coerceIn(1, sw - x)
        val h = (cropRect.h / img.rect.h * sh).toInt().coerceIn(1, sh - y)
        // Decode only the crop region, downsampled, so a big photo never allocates its full pixels
        // (the old whole-image decode both lagged and OOM-crashed on large photos). Any failure or
        // OOM is swallowed and leaves the image uncropped rather than crashing the app.
        val cropped = decodeCropRegion(path, x, y, w, h) ?: return null
        return try {
            val file = java.io.File.createTempFile("crop", ".png", imageDir)
            java.io.FileOutputStream(file).use { cropped.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            val out = imageCodec.probeFile(file.path) ?: return null
            // Keep a rotated crop where it visually sits: the piece now turns about its own centre,
            // not the original's, so shift the new box by the difference the turn introduces.
            ImageData(file, out.width, out.height) to placedCropRect(img, cropRect)
        } catch (t: Throwable) {
            message = "Couldn't crop this image (it may be too large)."
            null
        } finally {
            cropped.recycle()
        }
    }

    /** Decode the [x,y,w,h] pixel region of the image at [path], downsampled so its longer side stays
     *  within [CROP_MAX_PX]. Region-decodes when the format allows it (no full-image alloc); falls
     *  back to a downsampled whole-image decode otherwise. Null on any failure or out-of-memory. */
    @Suppress("DEPRECATION")
    private fun decodeCropRegion(path: String, x: Int, y: Int, w: Int, h: Int): android.graphics.Bitmap? {
        var ss = 1
        while (w / ss > CROP_MAX_PX || h / ss > CROP_MAX_PX) ss *= 2
        val opts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = ss
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
        runCatching {
            val dec = android.graphics.BitmapRegionDecoder.newInstance(path, false) ?: return@runCatching null
            try {
                dec.decodeRegion(android.graphics.Rect(x, y, x + w, y + h), opts)
            } finally {
                dec.recycle()
            }
        }.getOrNull()?.let { return it }
        // Fallback: some formats can't be region-decoded, so decode the whole image downsampled and
        // cut the region out of that. Still bounded by inSampleSize, so no full-resolution alloc.
        return runCatching {
            val whole = android.graphics.BitmapFactory.decodeFile(path, opts) ?: return null
            val rx = (x / ss).coerceIn(0, whole.width - 1)
            val ry = (y / ss).coerceIn(0, whole.height - 1)
            val rw = (w / ss).coerceIn(1, whole.width - rx)
            val rh = (h / ss).coerceIn(1, whole.height - ry)
            val cut = android.graphics.Bitmap.createBitmap(whole, rx, ry, rw, rh)
            // createBitmap may hand back the source when the region is the whole image.
            if (cut !== whole) whole.recycle()
            cut
        }.getOrNull()
    }

    /** Reposition the crop rect so a rotated image's cropped piece stays visually in place. */
    private fun placedCropRect(
        img: com.xnotes.core.model.ImageItem,
        cropRect: com.xnotes.core.geometry.Rect,
    ): Rect {
        if (img.angle == 0.0) return cropRect
        val oldC = img.rect.center
        val newC = cropRect.center
        val dx = newC.x - oldC.x
        val dy = newC.y - oldC.y
        val cs = kotlin.math.cos(img.angle)
        val sn = kotlin.math.sin(img.angle)
        // T = (R - I) * (newC - oldC): the shift a turn about newC adds versus a turn about oldC.
        val tx = (dx * cs - dy * sn) - dx
        val ty = (dx * sn + dy * cs) - dy
        return cropRect.translate(tx, ty)
    }

    /** Viewport rect to anchor the screenshot tool's "copy as image" menu, or null when hidden. */
    var screenshotMenu by mutableStateOf<com.xnotes.core.geometry.Rect?>(null)
        private set

    /** Viewport rect to anchor the crop Apply/Cancel bar, or null when not cropping. */
    var cropMenu by mutableStateOf<com.xnotes.core.geometry.Rect?>(null)
        private set

    /** Long-press paste context menu target, or null when hidden. */
    override var contextMenu by mutableStateOf<ContextMenuTarget?>(null)
        private set
    var title by mutableStateOf(state.document.title)
        private set
    var dirty by mutableStateOf(false)
        private set

    /** True when a note is open (the editor is pushed on top of backstage); false = backstage is the
     *  bare root of the stack. Starts false so every launch lands on home with no phantom note. */
    var noteOpen by mutableStateOf(false)
        private set

    /** True when the open document is an infinite canvas rather than a paged note, so the editor
     *  layer hosts the GL canvas and its own chrome. Meaningful only while [noteOpen]. */
    var canvasOpen by mutableStateOf(false)
        private set

    private var infiniteOrNull: InfiniteEditor? = null

    /** The infinite canvas's orchestrator, built on first use. It hangs off this editor rather
     *  than standing beside it so it can never race the temp-directory purge in this constructor,
     *  which would delete an open note's live PDF and image files out from under it. */
    val infinite: InfiniteEditor
        get() = infiniteOrNull ?: InfiniteEditor(appContext).also {
            infiniteOrNull = it
            it.applyPalette(palette)
            // Both editors write inserted images into the same purged-on-launch temp dir.
            it.imageDir = imageDir
            for (t in ToolDefaults.persistedTools) it.setToolConfig(t, settings.configFor(t))
            it.armShapeConfig(settings.shapeConfig)
            it.armDetectShapes(settings.prefs.detectShapes)
            it.onDetectShapesChanged = { v -> snapHeldToShapes = v }
            it.toolbarColors = toolbarColors
            it.recentColors = recentColors
            it.toolbarColorCount = toolbarColorCount
            it.toolbarLayout = canvasToolbarLayout
            it.pad.frontBuffering = !settings.prefs.disableFrontBuffering
            it.pickColor(activeColorIndex)
            // A style tuned on the canvas is the same style, so it persists through this editor.
            it.onToolStyleChanged = { settingsDirty = true }
            it.onSwatchColorChanged = { index, color -> adoptSwatchColor(index, color) }
            // The new-canvas background lives in the settings file this editor owns.
            it.newCanvasBackground = newCanvasBackground
            it.onSaveNewCanvasBackground = { bg -> saveNewCanvasBackground(bg) }
            it.onColorRemembered = { color ->
                settings = settings.rememberColor(color)
                it.recentColors = recentColors
            }
        }

    /** Take on a swatch the canvas recoloured, so both bars show it and the next save keeps it. */
    private fun adoptSwatchColor(index: Int, color: Rgba) {
        if (index !in toolbarColors.indices) return
        toolbarColors = toolbarColors.toMutableList().also { it[index] = color }
        activeColorIndex = index
        controller.pickInk(color)
        settingsDirty = true
    }

    /** The canvas's autosave binding, the sibling of [autosaveUri] for the paged note. */
    var canvasAutosaveUri: String? = null
        private set

    /** The canvas siblings of [noteDebounceJob] / [noteWriteJob], for the same reasons. */
    private var canvasDebounceJob: kotlinx.coroutines.Job? = null
    private var canvasWriteJob: kotlinx.coroutines.Job? = null

    /** Open a fresh, unsaved infinite canvas on top of backstage. */
    fun newCanvas() {
        val doc = com.xnotes.core.infinite.InfiniteDocument(created = System.currentTimeMillis())
        settings.newCanvasBackground?.let { doc.background = it }
        openCanvasDocument(doc, uri = null, displayName = null)
    }

    /**
     * Read the `.xcanvas` at [uri] and push its editor on top of backstage. IO, so call it off the
     * main thread; the model swap itself hops back to the main thread.
     */
    suspend fun openCanvasAsync(uri: String, name: String?): Boolean {
        val doc = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                appContext.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use {
                    canvasCodec.read(it, imageDir)
                }?.also { it.created = settleCreated(uri, it.created) }
            }.getOrNull()
        } ?: return false
        openCanvasDocument(doc, uri, name)
        rememberOpened(uri, name)
        return true
    }

    private fun openCanvasDocument(
        doc: com.xnotes.core.infinite.InfiniteDocument,
        uri: String?,
        displayName: String?,
    ) {
        flushAutosave() // a paged note may be open underneath; do not leave its edits unwritten
        doc.path = uri
        doc.displayName = displayName
        val canvas = infinite
        canvas.replaceDocument(doc)
        canvas.applyPalette(palette)
        canvas.applyInputPrefs(settings.prefs.fingerDraws, controller.penButtonTool, settings.prefs.zoomLockPan)
        canvas.applyZoomRange(settings.prefs.canvasMinZoomPercent, settings.prefs.canvasMaxZoomPercent)
        canvas.onContentChanged = { scheduleCanvasAutosave() }
        // Only a canvas living under the granted folder autosaves; anything else is left alone,
        // matching how a note opened from outside the root behaves.
        canvasAutosaveUri = if (uri != null && browseRoot?.let { isUnderTree(uri, it) } == true) uri else null
        lastCanvasStamp = canvasAutosaveUri?.let { stampOf(it) }
        canvasOpen = true
        noteOpen = true
    }

    /** Write [doc] to [uri] through a private temp, so a failed encode never truncates a good file. */
    private fun writeCanvasSafely(uri: String, doc: com.xnotes.core.infinite.InfiniteDocument): Boolean =
        synchronized(saveLock) {
            runCatching {
                val tmp = java.io.File.createTempFile("save", ".xcanvas", saveTmpDir)
                try {
                    java.io.FileOutputStream(tmp).use { canvasCodec.write(doc, it) }
                    val out = appContext.contentResolver.openOutputStream(android.net.Uri.parse(uri), "wt")
                        ?: return@runCatching false
                    out.use { java.io.FileInputStream(tmp).use { input -> input.copyTo(it, copyBuffer) } }
                    lastCanvasStamp = stampOf(uri)
                    true
                } finally {
                    tmp.delete()
                }
            }.getOrDefault(false)
        }

    private fun scheduleCanvasAutosave() {
        val uri = canvasAutosaveUri ?: return
        canvasDebounceJob?.cancel()
        canvasDebounceJob = autosaveScope.launch {
            kotlinx.coroutines.delay(1200L) // debounce: write after a short idle
            canvasWriteJob?.join() // wait out a write already going out rather than racing it
            val canvas = infiniteOrNull ?: return@launch
            val doc = canvas.document
            if (!doc.dirty) return@launch
            startCanvasWrite(uri, doc, doc.displayName ?: doc.title)
        }
    }

    /**
     * The canvas sibling of [startNoteWrite], tracked as [canvasWriteJob] and never cancelled. The
     * writer gets its own item list over the live items ([InfiniteDocument.snapshotForWrite]), taken
     * here on the main thread; the bookkeeping stays on the live [doc].
     */
    private fun startCanvasWrite(
        uri: String,
        doc: com.xnotes.core.infinite.InfiniteDocument,
        title: String,
        onDone: (() -> Unit)? = null,
    ) {
        val snapshot = doc.snapshotForWrite()
        val wasDirty = doc.dirty
        doc.dirty = false // the snapshot holds these edits; see [startNoteWrite]
        canvasWriteJob = autosaveScope.launch {
            val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                saveCanvasGuarded(uri, snapshot, title)
            }
            if (res != null) {
                if (infiniteOrNull?.document === doc) res.fork?.let { adoptCanvasFork(it) }
                invalidateThumb(res.uri)
            } else if (wasDirty && infiniteOrNull?.document === doc) {
                doc.dirty = true // nothing was written; the edits are still unsaved
            }
            onDone?.invoke()
        }
    }

    /** Start writing the open canvas to its file; a no-op when it is not a folder canvas or not
     *  dirty. The sibling of [flushAutosave]: [startCanvasWrite] snapshots on the main thread and
     *  puts the bytes out on IO, so pausing over a dense canvas never blocks on SAF. */
    fun flushCanvasAutosave() {
        canvasDebounceJob?.cancel() // only the debounce; a write already going out is left to finish
        val uri = canvasAutosaveUri ?: return
        val doc = infiniteOrNull?.document ?: return
        if (!doc.dirty) return
        startCanvasWrite(uri, doc, doc.displayName ?: doc.title)
    }

    /**
     * Flush the open canvas to its folder file off the main thread, then run [onDone] on the main
     * thread. Shows the "Saving your notes…" overlay while it runs when [showOverlay]. Runs [onDone]
     * at once (no save) when the canvas isn't a folder canvas or isn't dirty, so the common case
     * stays instant. The sibling of [flushThen], for the same reason: a dense canvas takes long
     * enough to write that doing it on the main thread freezes the UI on close.
     */
    private fun flushCanvasThen(showOverlay: Boolean, onDone: () -> Unit) {
        canvasDebounceJob?.cancel()
        val uri = canvasAutosaveUri
        val doc = infiniteOrNull?.document
        if (uri == null || doc == null || !doc.dirty) { onDone(); return }
        val title = doc.displayName ?: doc.title
        if (showOverlay) savingNote = true
        autosaveScope.launch {
            canvasWriteJob?.join() // a write already going out finishes first; it may be all there was
            if (!doc.dirty) { savingNote = false; onDone(); return@launch }
            startCanvasWrite(uri, doc, title) {
                savingNote = false
                onDone()
            }
        }
    }

    /** Bumped whenever page content changes, to refresh thumbnails. */
    var contentVersion by mutableStateOf(0)
        private set

    /** Bumped when the bookmark list changes. */
    var bookmarkVersion by mutableStateOf(0)
        private set

    /** The open PDF's extracted outline (its table of contents), empty for non-PDF notes or PDFs with
     *  no outline. Parsed off-thread on document open; [tocVersion] bumps when it arrives. */
    private var toc: List<com.xnotes.platform.PdfOutlineEntry> = emptyList()
    val tableOfContents: List<com.xnotes.platform.PdfOutlineEntry> get() = toc
    var tocVersion by mutableStateOf(0)
        private set

    /** True while a filtered PDF's embedded-image colours are still being parsed off-thread (only
     *  when the note's resolved View settings filter pages and keep image colours); drives the
     *  canvas hint. */
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

    /** Deep-cloned pages held for paste. Process-wide ([PageClipboard]) so a copy survives a document
     *  switch and can be pasted into another note; a snapshot list, so paste affordances recompose. */
    private val pageClipboard get() = PageClipboard.pages

    /** The current document's storage location (a SAF content URI string), or null. */
    val currentUri: String? get() = state.document.path

    val bookmarks: List<Bookmark> get() = state.document.bookmarks.toList()

    val controller: InteractionController = InteractionController(
        state,
        history,
        textMeasurer,
        requestRender = { onRender() },
        onContentChanged = { refreshContent() },
        onViewChanged = { refreshView() },
        onFitWidthSnapped = { showZoomLockHint() },
        onFitWidthReleased = { hideZoomLockHint() },
        onSelectionChanged = { selected -> hasSelection = selected; refreshTextBar() },
        onToolChanged = { t ->
            tool = t
            if (t != Tool.TEXT) {
                endTableEdit()
                tableMenu = null
            }
        },
        onTextEditStart = { field -> editingField = field; refreshTextBar() },
        onTextEditEnd = { editingField = null; refreshTextBar() },
        onSelectionMenu = { rect -> selectionMenu = rect },
        onScreenshotMenu = { rect -> screenshotMenu = rect },
        onCropMenu = { rect -> cropMenu = rect },
        onContextMenu = { vp, content, locked -> contextMenu = ContextMenuTarget(vp.x, vp.y, content, locked) },
        onAddPageAtEnd = { addPageAtEnd() },
        onHaptic = { runCatching { view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS) } },
    )

    /**
     * Accents from a physical keyboard's dead keys, composed through the layout the user
     * selected. Declared ahead of [flowText] because its caret callback clears the latch.
     */
    private val deadKeys = DeadKeyLatch { accent, ch ->
        android.view.KeyCharacterMap.getDeadChar(accent, ch)
    }

    val flowText: FlowTextController = FlowTextController(
        state,
        history,
        flow = { state.document.flow },
        frame = { publishedFlow?.frame },
        slotHeight = { flowLayout.defaultSlotHeight(state.document.flow) },
        onChanged = { live -> onFlowChanged(live) },
        onFlushed = { onFlowFlushed() },
        onSessionChanged = { active -> onFlowSessionChanged(active) },
        onViewChanged = { refreshView() },
        requestRender = { onRender() },
    ).also { ctrl ->
        controller.flowText = ctrl
        ctrl.onHaptic = {
            runCatching { view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS) }
        }
        ctrl.onCaretChanged = {
            flowSelTick++
            flowContextMenu = null
            deadKeys.clear()
            // A dismissal only covers the slash it was made against: stepping back onto
            // or before it means the user is starting over, so the menu may open again.
            slashDismissed = slashDismissed?.takeIf { d ->
                val at = ctrl.selection.normalized().start
                at.para == d.para && at.offset > d.offset
            }
            // Typing re-filters the menu, so any arrowed-to row stops meaning anything.
            slashSelected = -1
            // Walking out of a formula is what releases the edge it was held at.
            if (mathHeld >= 0L && !heldMathHoldsCaret()) mathHeld = -1L
            // Opening or closing a formula changes how the paragraph is shaped,
            // not just where the caret sits, so the frame has to be laid out
            // again. Nothing else here does: a caret move cannot otherwise
            // reshape. The comparison is against what is published rather than
            // what was last asked for, because an edit lays out before it moves
            // the caret and can leave a frame behind that neither one matches.
            if (revealedMathKey() != revealedMath) {
                republishFlow(invalidate = true)
                onRender()
            }
        }
        ctrl.onSlashEnter = { commitSlashMenu() }
        ctrl.styleAt = { pos -> mathStyleAt(pos) }
        ctrl.onContextMenu = { viewport -> flowContextMenu = flowMenuAnchor(viewport) }
        ctrl.gated = { tableEditLive() }
        ctrl.onTableHold = { table ->
            tableMenu = table
            tableChromeTick++
        }
        ctrl.onGatedTap = { endTableEdit() }
        ctrl.caretMetricsFor = { style ->
            val flow = state.document.flow
            val para = flow.paragraphs.getOrNull(ctrl.selection.normalized().start.para) ?: Paragraph()
            textMeasurer.metrics(com.xnotes.core.text.resolveFont(flow, para, style))
        }
    }

    /** Bumped whenever the flow caret/selection or pending style moves (the format bar keys on it). */
    var flowSelTick by mutableStateOf(0)
        private set

    private val flowInput = com.xnotes.canvas.FlowInput(view, flowText, { state.document.flow })
        .also {
            view.flowInput = it
            it.onPaste = { pastePlainAtCaret() }
            it.onBackspaceSpecial = { flowBackspaceSpecial() }
            it.onForwardDeleteSpecial = { flowForwardDeleteSpecial() }
        }

    /** True while the inline text caret session is live (drives the bottom format bar/IME). */
    var flowEditingActive by mutableStateOf(false)
        private set

    /** The clipboard's text content, or null. */
    private fun clipboardText(): String? {
        val cm = appContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager ?: return null
        val clip = cm.primaryClip?.takeIf { it.itemCount > 0 } ?: return null
        return clip.getItemAt(0).coerceToText(appContext)?.toString()?.takeIf { it.isNotEmpty() }
    }

    fun clipboardHasText(): Boolean = clipboardText() != null

    /** Viewport bounds the flow-editing action bar anchors to, or null when closed. */
    var flowContextMenu by mutableStateOf<Rect?>(null)
        private set

    fun dismissFlowContextMenu() {
        flowContextMenu = null
    }

    /** The flow selection's viewport bounds (the caret's rect when collapsed). */
    private fun flowMenuAnchor(fallback: Pt): Rect {
        val sel = flowText.selection.normalized()
        val rects = publishedFlow?.frame?.let { f ->
            val local = if (sel.collapsed) listOfNotNull(f.caretRect(sel.end)) else f.selectionRects(sel)
            local.mapNotNull { (pi, r) ->
                state.pageRects.getOrNull(pi)?.let { pr -> r.translate(pr.left, pr.top) }
            }
        }.orEmpty()
        if (rects.isEmpty()) return Rect(fallback.x, fallback.y, 0.0, 0.0)
        val tl = state.contentToViewport(Pt(rects.minOf { it.left }, rects.minOf { it.top }))
        val br = state.contentToViewport(Pt(rects.maxOf { it.right }, rects.maxOf { it.bottom }))
        return Rect(tl.x, tl.y, br.x - tl.x, br.y - tl.y)
    }

    /** Paste the clipboard's text verbatim at the flow caret. */
    fun pastePlainAtCaret() {
        if (!flowText.active) return
        val text = clipboardText() ?: return
        flowText.replaceExternal(flowText.selection, text)
    }

    /** Parse the clipboard's text as markdown and paste it at the caret's font size. */
    fun pasteMarkdownAtCaret() {
        if (!flowText.active) return
        val text = clipboardText() ?: return
        val caretSize = flowCaretStyle().sizePt
        val paras = com.xnotes.core.text.MarkdownParser.parse(text, caretSize ?: flowDefaultSizePt(), newTableDefaults.style)
        if (caretSize != null) {
            for (p in paras) {
                for (run in p.runs) {
                    if (run.style.sizePt == null) run.style = run.style.copy(sizePt = caretSize)
                }
            }
        }
        // Pasted tables arrive with their text, so their columns can be fitted up front.
        val frame = publishedFlow?.frame
        val page = frame?.caretRect(flowText.selection.end)?.first ?: 0
        frame?.pages?.getOrNull(page)?.contentRect?.w?.let { width ->
            val cells = CellIndex(paras)
            for (b in cells.tables) {
                b.table.widths = flowLayout.fitColumns(state.document.flow, b.grid(paras), b.table.style, width)
            }
        }
        insertFlowParagraphs(paras)
    }

    /** Paste the clipboard's text as a code block, at the caret's font size. */
    fun pasteAsCodeAtCaret() {
        if (!flowText.active) return
        val text = clipboardText() ?: return
        val lang = settings.prefs.defaultCodeLanguage.takeIf { it != "plain" } ?: ""
        val style = CharStyle(sizePt = flowCaretStyle().sizePt)
        insertFlowParagraphs(
            text.split('\n').map { line ->
                Paragraph(
                    if (line.isEmpty()) mutableListOf() else mutableListOf(com.xnotes.core.text.Run(line, style)),
                    codeLang = lang,
                )
            },
        )
    }

    /**
     * Splice ready-made paragraphs at the caret as one undo step (rich paste). Inside a
     * table cell they join the cell as plain rich text (a pasted table flattens); in body
     * text any pasted table gets the empty lines it needs around it.
     */
    private fun insertFlowParagraphs(input: List<Paragraph>) {
        if (input.isEmpty()) return
        flowText.flushBurst()
        val flow = state.document.flow
        val ed = FlowEditor(flow)
        val cmds = mutableListOf<Command>()
        val sel = flowText.selection.normalized()
        var p = sel.start
        if (!sel.collapsed) {
            val (c, np) = ed.replaceRange(sel, "")
            c?.let(cmds::add)
            p = np
        }
        val caretPara = flow.paragraphs.getOrNull(p.para)
        val cellTable = caretPara?.table
        var paras: List<Paragraph> = if (cellTable == null) input else input.onEach {
            it.table = cellTable
            it.cellStart = false
            it.list = ListKind.NONE
            it.checked = false
            it.codeLang = null
            it.indent = 0
        }
        val lastIndex: Int
        when {
            caretPara == null -> {
                paras = withTableSeparators(paras, null, null)
                cmds.add(ed.insertParagraphs(0, paras))
                lastIndex = paras.size - 1
            }
            cellTable != null && caretPara.length == 0 -> {
                paras.first().cellStart = caretPara.cellStart
                cmds.add(ed.replaceParagraphs(p.para, 1, paras))
                lastIndex = p.para + paras.size - 1
            }
            caretPara.length == 0 && caretPara.isDefaultStyle() -> {
                // Pasting on an empty plain line replaces it, so no stray blank stays above.
                paras = withTableSeparators(paras, flow.paragraphs.getOrNull(p.para - 1), flow.paragraphs.getOrNull(p.para + 1))
                cmds.add(ed.replaceParagraphs(p.para, 1, paras))
                lastIndex = p.para + paras.size - 1
            }
            else -> {
                val (c2, _) = ed.replaceRange(FlowRange.caret(p), "\n")
                c2?.let(cmds::add)
                paras = withTableSeparators(paras, flow.paragraphs.getOrNull(p.para), flow.paragraphs.getOrNull(p.para + 1))
                cmds.add(ed.insertParagraphs(p.para + 1, paras))
                lastIndex = p.para + paras.size
            }
        }
        val cmd = if (cmds.size == 1) cmds[0] else CompositeCommand(cmds)
        val caretTo = FlowPos(lastIndex, flow.paragraphs.getOrNull(lastIndex)?.length ?: 0)
        flowText.commitEdit(cmd, caretTo)
    }

    /**
     * A flow mutation landed. While the session is live the flow is lifted out of the
     * caches, so keystrokes only republish the layout snapshot and repaint; everything
     * else (checkbox taps, menu pastes, session-less edits) takes the full invalidate +
     * refresh path so the baked layer and chrome follow.
     */
    private fun onFlowChanged(live: Boolean) {
        tableMenu = null
        state.document.dirty = true
        republishFlow(invalidate = !live)
        if (live) onRender() else refreshContent()
    }

    /** A burst landed. */
    private fun onFlowFlushed() {
        refreshContent()
    }

    private fun onFlowSessionChanged(active: Boolean) {
        flowEditingActive = active
        deadKeys.clear()
        if (active) {
            flowInput.startSession()
        } else {
            flowContextMenu = null
            setMathKeyboard(false)
            flowInput.endSession()
            refreshContent()
        }
        onRender()
    }

    /**
     * A repaint was asked for. The canvas skips it while the front buffer owns the stroke under the
     * pen: it is not drawing that ink, so the frame would come out the same, and the blit is work
     * on the thread the pen's samples have to get through.
     */
    private fun onRender() {
        if (controller.frontInk?.live != true) view.requestRender()
    }

    init {
        view.input = { ev ->
            // Any fresh canvas touch quietly retires the flow action bar and still does its job.
            if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                flowContextMenu = null
                tableMenu = null
            }
            controller.onTouch(ev)
        }
        view.onTwoFingerTap = { dispatchTapGesture(preferences.twoFingerTap) }
        view.onThreeFingerTap = { dispatchTapGesture(preferences.threeFingerTap) }
        view.hover = { controller.onHover(it) }
        view.genericMotion = { controller.onGenericMotion(it) }
        view.drawOverlay = { renderer, _ -> controller.drawOverlay(renderer) }
        controller.frontInk = com.xnotes.canvas.FrontInk(state, view, pad)
        pad.onSurfaceLost = { controller.frontInk?.surfaceLost() }
        view.debugOverlay.frontHud = { controller.frontInk?.hud }
        view.afterLayout = { refreshView() }
        view.onScrollbarScrolled = { refreshView() }
        // The canvas starts at built-in defaults; push any non-default global View settings
        // (mode/rotation/scroll direction/scrollbar) into it before the first document lands.
        onViewSettingsChanged(com.xnotes.canvas.ViewSettings(), viewSettings)
        view.onKey = { e -> e.action == android.view.KeyEvent.ACTION_DOWN && handleKeyDown(e) }
        controller.clipboardHasImage = { clipboardImageUri() != null }
        controller.onLinkTap = onLinkTap@{ pageIndex, pageLocal ->
            // A canvas item with a hyperlink wins over a PDF link under the same point (it sits on top).
            state.document.pages.getOrNull(pageIndex)?.items
                ?.lastOrNull { it.link != null && it.contains(pageLocal) }
                ?.link?.let { openUrl(it); return@onLinkTap true }
            // A hyperlink in the flow text body (under any items, over a PDF's own links).
            publishedFlow?.let { pf ->
                state.document.pages.getOrNull(pageIndex)?.let { page ->
                    pf.indexOf[page]?.let { fi ->
                        pf.frame.linkAt(fi, pageLocal)?.let { openUrl(it); return@onLinkTap true }
                    }
                }
            }
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

    override val hasClipboardItems: Boolean get() = controller.hasClipboardItems()
    override val clipboardHasImage: Boolean get() = clipboardImageUri() != null

    override fun copySelection() {
        if (hasSelection) showToast("Copied")
        controller.copySelection()
    }
    override fun cutSelection() {
        if (hasSelection) showToast("Cut")
        controller.cutSelection()
    }
    override fun duplicateSelection() = controller.duplicateSelection()
    override fun lockSelection() = controller.lockSelection()
    override fun unlockItem(item: com.xnotes.core.model.CanvasItem) = controller.unlockItem(item)
    override fun dismissSelectionMenu() { selectionMenu = null }
    override fun dismissContextMenu() { contextMenu = null }
    fun dismissScreenshot() = controller.clearScreenshot()

    /** Render the screenshot tool's capture rectangle to a PNG and put it on the system clipboard. */
    fun copyScreenshotAsImage() {
        val rect = controller.screenshotRect ?: return
        val bmp = renderRegionBitmap(rect)
        val ok = bmp != null && putBitmapOnClipboard(bmp, "xnotes capture")
        controller.clearScreenshot()
        controller.switchBackAfterScreenshot() // return to the previous pen, like the eraser
        message = if (ok) appContext.getString(R.string.image_copied) else appContext.getString(R.string.err_copy_image)
    }

    /** Render a content-space rectangle (whatever it overlaps: pages, backgrounds, ink, the gap)
     *  into a bitmap, the screenshot tool's "what I see" capture. */
    private fun renderRegionBitmap(content: com.xnotes.core.geometry.Rect): android.graphics.Bitmap? {
        if (content.w <= 0.0 || content.h <= 0.0) return null
        // 2x content for crispness, but cap the longest side so a big capture can't blow up memory.
        val maxDim = 4096.0
        val res = minOf(2.0, maxDim / content.w, maxDim / content.h).coerceAtLeast(0.05)
        val w = kotlin.math.ceil(content.w * res).toInt().coerceIn(1, maxDim.toInt())
        val h = kotlin.math.ceil(content.h * res).toInt().coerceIn(1, maxDim.toInt())
        val surface = com.xnotes.platform.AndroidRasterSurface.create(w, h)
        surface.fill(state.palette.bg) // the canvas/gap colour shows through any off-page area
        val r = surface.renderer()
        r.scale(res, res)
        r.translate(-content.left, -content.top) // content (left, top) -> output (0, 0)
        for (i in state.document.pages.indices) {
            val pr = state.pageRects.getOrNull(i) ?: continue
            if (!pr.intersects(content)) continue
            val page = state.document.pages[i]
            r.withSave {
                r.clipRect(pr)
                r.fillRect(pr, state.paperColor(page))
                val cover = state.footprint(page)
                r.translate(pr.left - cover.left, pr.top - cover.top) // into page space (past the margins)
                val local = com.xnotes.core.geometry.Rect.ltrb(
                    (content.left - pr.left).coerceAtLeast(0.0) + cover.left,
                    (content.top - pr.top).coerceAtLeast(0.0) + cover.top,
                    (content.right - pr.left).coerceAtMost(cover.w) + cover.left,
                    (content.bottom - pr.top).coerceAtMost(cover.h) + cover.top,
                )
                if (local.w > 0.0 && local.h > 0.0) {
                    state.paintPageBackground?.invoke(page, r, res, local)
                    state.paintFlow?.invoke(page, r, local)
                }
                for (item in itemsSnapshot(page)) item.paint(r)
            }
        }
        return surface.bitmap
    }

    /** Write [bmp] to the FileProvider-exposed cache and set it as the primary clip (an image uri). */
    private fun putBitmapOnClipboard(bmp: android.graphics.Bitmap, label: String): Boolean = runCatching {
        val dir = java.io.File(appContext.cacheDir, "clipboard").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = java.io.File(dir, "capture.png")
        java.io.FileOutputStream(file).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val uri = androidx.core.content.FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)
        val cm = appContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newUri(appContext.contentResolver, label, uri))
        true
    }.getOrDefault(false)

    override fun pasteItemsAt(content: com.xnotes.core.geometry.Pt) {
        if (hasClipboardItems) showToast("Inserted")
        controller.pasteItemsAt(content)
    }

    override fun pasteClipboardImageAt(content: com.xnotes.core.geometry.Pt) {
        val uri = clipboardImageUri() ?: run {
            clipboardSvgBytes()?.let { insertImageAt(it, content); showToast("Inserted") }
                ?: run { message = appContext.getString(R.string.err_clipboard_no_image) }
            return
        }
        runCatching { appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
            .getOrNull()
            ?.let { insertImageAt(it, content); showToast("Inserted") }
            ?: run { message = appContext.getString(R.string.err_clipboard_no_image) }
    }

    /** SVG markup sitting on the clipboard as plain text (copied source), as insertable bytes. */
    private fun clipboardSvgBytes(): ByteArray? = com.xnotes.platform.SystemClipboard.svgBytes(appContext)

    private fun clipboardImageUri(): android.net.Uri? =
        com.xnotes.platform.SystemClipboard.imageUri(appContext)

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
                if (pdfSource != null && pdfKeepsImageColors()) {
                    refiningTotal = total
                    refiningDone = done
                    isRefiningPdf = done < total
                }
            }
        }
        installPageBackground()
        installFlowPainter()
        installHighlighter()
        republishFlow(invalidate = false)
        state.invalidateAllCaches()
        refreshToc()
        startPdfRefine() // kick the up-front sweep over all pages + drive the "Refining k/N" hint
    }

    /** Re-extract the open PDF's outline off-thread (a one-shot PdfBox read, see [com.xnotes.platform.PdfOutline]),
     *  publishing it to the Contents tab when it lands. Clears the TOC immediately so a non-PDF note or a
     *  document swap never shows the previous note's outline; a stale parse that finishes after another
     *  swap is dropped by the file-identity guard. */
    private fun refreshToc() {
        toc = emptyList()
        tocVersion++
        val file = state.document.pdfFile ?: return
        autosaveScope.launch {
            val entries = withContext(Dispatchers.IO) { com.xnotes.platform.PdfOutline.extract(appContext, file) }
            if (state.document.pdfFile === file) {
                toc = entries
                tocVersion++
            }
        }
    }

    /** Navigate to the page an outline [entry] points at: the note page whose [Page.pdfPage] is the
     *  entry's source PDF page, falling back to the nearest preceding imported page so an entry into a
     *  trimmed import still lands somewhere. No-op when the entry has no resolvable page. */
    fun goToTocEntry(entry: com.xnotes.platform.PdfOutlineEntry) {
        val dest = entry.destPage
        if (dest < 0) return
        val pages = state.document.pages
        val exact = pages.indexOfFirst { it.pdfPage == dest }
        val target = if (exact >= 0) exact else pages.indexOfLast { (it.pdfPage ?: -1) in 0..dest }
        if (target >= 0) goToPage(target)
    }

    /** Records [doc] as the open note for source-PDF temp-file lifetime: deletes the previously open
     *  note's temp PDF (unless [doc] reuses the same file) and tracks [doc]'s. Call on every document
     *  swap so a closed note's (possibly huge) cached PDF doesn't linger on disk. */
    private fun adoptOpenPdf(doc: Document) {
        val keep = doc.pdfFile
        // The outgoing note's flush is still streaming this file into its bundle, so the delete waits
        // that write out rather than pulling the source out from under it.
        val pending = noteWriteJob
        openPdfTemp?.let { old ->
            if (old != keep) autosaveScope.launch { pending?.join(); old.delete() }
        }
        openPdfTemp = keep
    }

    /**
     * Installs the page-background painter: the source-PDF raster (when the page links one) plus the
     * page-style ruling (lines/dots/grid) on top. Always non-null — so plain notes get rulings too —
     * and reads [pdfSource] live, so a single install survives document swaps; [CanvasState.hasPageBackground]
     * gates which pages actually allocate a background surface (a plain colour page allocates none).
     */
    private fun installPageBackground() {
        state.paintPageBackground = { page, renderer, res, region ->
            val src = pdfSource
            val pi = page.pdfPage
            val content = com.xnotes.core.geometry.Rect(0.0, 0.0, page.width, page.height)
            if (src != null && pi != null) {
                // The raster covers the page's content box only; a margin is paper beside it.
                val slice = clampToContent(region, page)
                if (slice != null) {
                    val fullW = (page.width * res).toInt()
                    val fullH = (page.height * res).toInt()
                    val rx = (slice.left * res).toInt()
                    val ry = (slice.top * res).toInt()
                    val rw = kotlin.math.ceil(slice.w * res).toInt()
                    val rh = kotlin.math.ceil(slice.h * res).toInt()
                    src.renderRegion(pi, fullW, fullH, rx, ry, rw, rh, pdfPageFilter())?.let { bg ->
                        renderer.drawRaster(bg, slice)
                        bg.recycle()
                    }
                }
            }
            // A template covers a blank note page whole; on an imported PDF page it rules the
            // margins only, so the page itself is never drawn over.
            TemplateLibrary.paint(renderer, state.document, page, state.footprint(page), region)
        }
    }

    /** [region] cropped to [page]'s content box (its stored size), or null when it misses it. */
    private fun clampToContent(region: com.xnotes.core.geometry.Rect, page: Page): com.xnotes.core.geometry.Rect? {
        val l = region.left.coerceAtLeast(0.0)
        val t = region.top.coerceAtLeast(0.0)
        val r = region.right.coerceAtMost(page.width)
        val b = region.bottom.coerceAtMost(page.height)
        return if (r > l && b > t) com.xnotes.core.geometry.Rect(l, t, r - l, b - t) else null
    }

    /**
     * Installs the flow-text painter: it draws the PUBLISHED layout snapshot for the page
     * (never the live model — cache threads call this), under the ink and over the page
     * background via [CanvasState.paintFlow]. A page unknown to the snapshot paints nothing.
     */
    private fun installFlowPainter() {
        state.paintFlow = { page, renderer, region ->
            val pf = publishedFlow
            val idx = pf?.indexOf?.get(page)
            if (pf != null && idx != null) FlowPainter.paintPage(renderer, pf.frame, idx, region)
        }
    }

    /** Feed the layout derived highlight colours (published on the main thread only). */
    private fun installHighlighter() {
        flowLayout.codeSpans = spans@{ para ->
            val (rev, spans) = highlightCache[para] ?: return@spans null
            if (rev != para.rev) return@spans null
            val theme = activeCodeTheme()
            spans.mapNotNull { s ->
                theme.colorFor(s.capture)?.let { com.xnotes.core.text.CodeSpan(s.start, s.end, it) }
            }
        }
        flowLayout.codeBackground = { activeCodeTheme().background }
        flowLayout.autoColor = { defaultTextColor() }
        flowLayout.accentColor = { palette.accent }
        flowLayout.ruleColor = { tableRuleColor() }
        flowLayout.revealedMath = { revealedMathIn(it) }
    }

    /**
     * Debounced async highlighting: snapshot the dirty contiguous same-language code
     * blocks on the main thread, parse each block as ONE text off-thread (so
     * multi-line strings/comments highlight right), then publish spans per paragraph
     * only when its revision still matches, and republish the layout.
     */
    private fun scheduleHighlight() {
        val hl = highlighter ?: return
        highlightJob?.cancel()
        highlightJob = autosaveScope.launch {
            kotlinx.coroutines.delay(150)
            val flow = state.document.flow
            highlightCache.keys.retainAll(flow.paragraphs.toHashSet())
            class Block(val paras: List<Paragraph>, val revs: IntArray, val text: String, val lang: String)
            val blocks = mutableListOf<Block>()
            var i = 0
            while (i < flow.paragraphs.size) {
                val lang = flow.paragraphs[i].codeLang
                if (lang.isNullOrEmpty() || !hl.supports(lang)) {
                    i++
                    continue
                }
                val start = i
                while (i < flow.paragraphs.size && flow.paragraphs[i].codeLang == lang) i++
                val paras = flow.paragraphs.subList(start, i).toList()
                if (paras.any { highlightCache[it]?.first != it.rev }) {
                    val revs = IntArray(paras.size) { paras[it].rev }
                    blocks.add(Block(paras, revs, paras.joinToString("\n") { it.plainText() }, lang))
                }
            }
            if (blocks.isEmpty()) return@launch
            val results = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                blocks.map { it to hl.highlight(it.text, it.lang) }
            }
            var changed = false
            for ((block, spans) in results) {
                if (spans == null) continue
                if (block.paras.withIndex().any { (k, p) -> p.rev != block.revs[k] }) continue
                val sorted = spans.sortedBy { it.start }
                var offset = 0
                for (p in block.paras) {
                    val len = p.length
                    val mine = sorted.mapNotNull { s ->
                        val a = (s.start - offset).coerceAtLeast(0)
                        val b = (s.end - offset).coerceAtMost(len)
                        if (b > a) com.xnotes.core.text.HighlightSpan(a, b, s.capture) else null
                    }
                    highlightCache[p] = p.rev to mine
                    offset += len + 1
                    changed = true
                }
            }
            if (changed) {
                republishFlow(invalidate = !flowText.active)
                onRender()
            }
        }
    }

    /** A cheap content fingerprint of the flow (structure + every paragraph revision). */
    private fun flowStamp(): Long {
        val flow = state.document.flow
        var s = flow.rev.toLong()
        for (p in flow.paragraphs) s = s * 31 + p.rev
        return s
    }

    /**
     * Recompute and publish the flow layout for the current document. With [invalidate]
     * the pages the flow covered before or after the change drop their ink caches, so
     * the baked layer follows the text; the undo path instead repaints in place.
     */
    private fun republishFlow(invalidate: Boolean) {
        val doc = state.document
        val oldFlow = publishedFlow
        val oldPages = publishedPageList
        val frame = flowLayout.layout(doc.flow, doc.pages.map { PageBox(it.width, it.height) }, doc.dpi)
        // What this frame actually shows, not what the caller meant it to: an edit
        // republishes before it moves the caret, so a frame can be laid out against
        // a caret the edit has already invalidated. Recording the intent instead
        // let that stale frame stand, because the next check saw nothing new.
        revealedMath = revealedMathKey()
        val index = HashMap<Page, Int>(doc.pages.size * 2)
        doc.pages.forEachIndexed { i, p -> index[p] = i }
        publishedFlow = PublishedFlow(frame, index)
        publishedFlowStamp = flowStamp()
        if (editingTable != null || tableMenu != null) tableChromeTick++
        publishedPageList = doc.pages.toList()
        scheduleHighlight()
        if (invalidate) {
            val stale = HashSet<Page>()
            oldFlow?.frame?.pagesWithLines()?.forEach { i -> oldPages.getOrNull(i)?.let(stale::add) }
            frame.pagesWithLines().forEach { i -> publishedPageList.getOrNull(i)?.let(stale::add) }
            stale.forEach { if (index.containsKey(it)) state.invalidatePage(it) }
        }
    }

    /** Republish the flow when its content or the page list moved (cheap no-op otherwise). */
    private fun republishFlowIfStale() {
        if (publishedFlowStamp == flowStamp() && publishedPageList == state.document.pages) return
        republishFlow(invalidate = true)
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
        if (src == null || !pdfKeepsImageColors()) {
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
        val file = runCatching {
            java.io.File.createTempFile("img", null, imageDir).apply { writeBytes(bytes) }
        }.getOrNull()
        val size = file?.let { imageCodec.probeFile(it.path) }
        if (file == null || size == null || size.width <= 0 || size.height <= 0) {
            file?.delete()
            message = appContext.getString(R.string.err_read_image)
            return
        }
        val index = (atContent?.let { state.pageIndexAtContent(it) } ?: state.currentPageIndex())
            .coerceIn(0, state.document.pages.lastIndex)
        val page = state.document.pages[index]
        val pr = state.pageRects.getOrNull(index)
        val maxW = page.width * 0.6
        val maxH = page.height * 0.6
        val scale = minOf(1.0, maxW / size.width, maxH / size.height)
        val w = size.width * scale
        val h = size.height * scale
        // Placed anywhere on the paper, margins included, but never hanging off it.
        val cover = state.footprint(page)
        val rect = if (atContent != null && pr != null) {
            Rect(
                (atContent.x - pr.left - w / 2 + cover.left).coerceIn(cover.left, cover.right - w),
                (atContent.y - pr.top - h / 2 + cover.top).coerceIn(cover.top, cover.bottom - h),
                w, h,
            )
        } else {
            Rect((page.width - w) / 2.0, (page.height - h) / 2.0, w, h)
        }
        val item = ImageItem(ImageData(file, size.width, size.height), rect)
        page.items.add(item)
        state.appendToCache(page, item)
        history.push(AddItem(page, item))
        state.document.dirty = true
        refreshContent()
        view.requestRender()
    }

    // --- table tool ---

    /** Column/row counts and line width for the next table drawn with the table tool (set in the
     *  tool's config popup). The table is then dragged onto the page like a shape. Not persisted. */
    var tableToolCols by mutableStateOf(3)
    var tableToolRows by mutableStateOf(3)
    var tableToolWidth by mutableStateOf(2.0)

    /** Save an encoded image into the on-disk sticker library (validated by a probe decode). */
    fun addSticker(bytes: ByteArray) {
        val file = runCatching {
            java.io.File.createTempFile("stamp-${System.currentTimeMillis()}-", null, stickerDir)
                .apply { writeBytes(bytes) }
        }.getOrNull()
        val size = file?.let { imageCodec.probeFile(it.path) }
        if (file == null || size == null || size.width <= 0 || size.height <= 0) {
            file?.delete()
            message = appContext.getString(R.string.err_read_image)
            return
        }
        refreshStickers()
    }

    fun removeSticker(file: java.io.File) {
        file.delete()
        refreshStickers()
    }

    /** Insert a sticker as a fresh copy, so the note never references the library file itself. */
    fun insertSticker(file: java.io.File) {
        val bytes = runCatching { file.takeIf { it.isFile }?.readBytes() }.getOrNull()
        if (bytes == null) {
            message = appContext.getString(R.string.err_read_sticker)
            refreshStickers()
            return
        }
        insertImage(bytes)
    }

    fun pasteImage() {
        val clipboard = appContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val uri = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        if (uri == null) {
            clipboardSvgBytes()?.let { insertImage(it) }
                ?: run { message = appContext.getString(R.string.err_clipboard_no_image) }
            return
        }
        runCatching { appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
            .getOrNull()
            ?.let { insertImage(it) }
            ?: run { message = appContext.getString(R.string.err_clipboard_no_image) }
    }

    /**
     * A [FlowLayout] carrying the active code theme, for transient layout passes (PDF
     * export, explorer tiles). The async [highlightCache] only feeds the live published
     * layout, so [flow]'s code blocks are parsed synchronously here — cheap (blocks are
     * small) and safe off-thread (the highlighter is stateless per call).
     */
    private fun themedFlowLayout(flow: com.xnotes.core.text.TextFlow): FlowLayout {
        val layout = FlowLayout(textMeasurer, com.xnotes.platform.MathRendering)
        val theme = activeCodeTheme()
        layout.codeBackground = { theme.background }
        layout.autoColor = { defaultTextColor() }
        val accent = palette.accent
        layout.accentColor = { accent }
        val rule = tableRuleColor()
        layout.ruleColor = { rule }
        val hl = highlighter ?: return layout
        val spans = HashMap<Paragraph, List<com.xnotes.core.text.CodeSpan>>()
        var i = 0
        while (i < flow.paragraphs.size) {
            val lang = flow.paragraphs[i].codeLang
            if (lang.isNullOrEmpty() || !hl.supports(lang)) {
                i++
                continue
            }
            val start = i
            while (i < flow.paragraphs.size && flow.paragraphs[i].codeLang == lang) i++
            val paras = flow.paragraphs.subList(start, i)
            // Contiguous same-language paragraphs parse as ONE text, like [scheduleHighlight].
            val sorted = hl.highlight(paras.joinToString("\n") { it.plainText() }, lang)
                ?.sortedBy { it.start } ?: continue
            var offset = 0
            for (p in paras) {
                val len = p.length
                spans[p] = sorted.mapNotNull { s ->
                    val a = (s.start - offset).coerceAtLeast(0)
                    val b = (s.end - offset).coerceAtMost(len)
                    if (b > a) theme.colorFor(s.capture)?.let { com.xnotes.core.text.CodeSpan(a, b, it) } else null
                }
                offset += len + 1
            }
        }
        layout.codeSpans = { spans[it] }
        return layout
    }

    /**
     * Flow painters for a PDF export of [doc]: a private layout pass over its own pages
     * (exports run off-thread and may target transient/subset documents, so the published
     * on-screen snapshot is never reused). Pages foreign to [doc] paint nothing.
     */
    private fun flowExportHooks(doc: Document): com.xnotes.platform.PdfExporter.FlowExport {
        if (doc.flow.isEmpty) return com.xnotes.platform.PdfExporter.FlowExport.NONE
        val frame = themedFlowLayout(doc.flow)
            .layout(doc.flow, doc.pages.map { PageBox(it.width, it.height) }, doc.dpi)
        val index = HashMap<Page, Int>(doc.pages.size * 2)
        doc.pages.forEachIndexed { i, p -> index[p] = i }
        return com.xnotes.platform.PdfExporter.FlowExport(
            paint = { page, r, region -> index[page]?.let { FlowPainter.paintPage(r, frame, it, region) } },
            bounds = { page -> index[page]?.let { frame.pageFlowBounds(it) } },
        )
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
            com.xnotes.platform.PdfExporter.export(
                appContext, state.document, src, out,
                { exportPaper(state.document, it) },
                { page, r -> paintExportRuling(state.document, page, r) },
                onProgress, isCancelled,
                flow = flowExportHooks(state.document),
            )
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
        toolbarColorCount = settings.toolbarColorCount
        toolbarLayout = settings.toolbarLayout
        canvasToolbarLayout = settings.canvasToolbarLayout
        activeColorIndex = settings.activeColor.coerceIn(0, toolbarColorCount - 1)
        // Render always at 1x (the DPI/supersampling control was removed).
        renderScale = 1.0
        state.renderScale = 1.0
        sidebarVisible = settings.sidebarVisible
        shapeConfig = settings.shapeConfig
        controller.shapeConfig = settings.shapeConfig
        controller.tableToolDefaults = { com.xnotes.canvas.TableToolDefaults(tableToolCols, tableToolRows, tableToolWidth) }
        for (t in ToolDefaults.persistedTools) controller.setToolConfig(t, settings.configFor(t))
        controller.inkColor = toolbarColors[activeColorIndex]
        selectTool(settings.lastTool)
        pushToolsToCanvas()
        applyPagePrefsToState(settings.prefs)
    }

    /** The appearance mode to render: "system" follows the OS dark/light state. */
    private fun resolvedAppearance(p: Preferences): String =
        if (p.uiAppearance == "system") (if (systemInDarkMode) "dark" else "light") else p.uiAppearance

    /** The chrome palette for [p]: the system scheme (default-seeded below Android 12) or a custom seed. */
    private fun buildPalette(p: Preferences, appearance: String = resolvedAppearance(p)): Palette {
        val dark = appearance != "light"
        val m = when (p.materialMode) {
            MaterialColourMode.DUAL -> MaterialColors.seeded(p.materialDualSeed, dark, p.materialStyle, p.materialSurfaceSeed, p.materialContrast)
            MaterialColourMode.SINGLE -> MaterialColors.seeded(p.materialSingleSeed, dark, p.materialStyle, contrast = p.materialContrast)
            MaterialColourMode.SYSTEM -> dynamicMaterialColors(appContext, dark = dark)
                ?: MaterialColors.seeded(Preferences.DEFAULT_ACCENT, dark = dark)
        }
        return Palette.material(appearance, m)
    }

    /** The app's theme in its light or dark variant, for the reader; the current mode (OLED included) when it already matches. */
    fun readerPalette(dark: Boolean): Palette {
        val p = settings.prefs
        val appearance = resolvedAppearance(p)
        if ((appearance != "light") == dark) return buildPalette(p, appearance)
        return buildPalette(p, if (dark) "dark" else "light")
    }

    /** The OS dark/light state flipped (uiMode arrives via onConfigurationChanged, no activity
     *  recreation): under the "system" appearance, rebuild the chrome and themed content live. */
    fun onSystemDarkModeChanged(dark: Boolean) {
        secondary?.onSystemDarkModeChanged(dark) // the other pane rethemes with this one
        if (systemInDarkMode == dark) return
        systemInDarkMode = dark
        if (settings.prefs.uiAppearance != "system") return
        applyPagePrefsToState(settings.prefs)
        republishFlow(invalidate = true)
        state.invalidateAllCaches()
        refreshView()
        prefsVersion++
        view.requestRender()
    }

    private fun applyPagePrefsToState(p: Preferences) {
        palette = buildPalette(p)
        cornerStyle = p.cornerStyle
        toolbarLook = p.toolbarLook
        state.palette = palette
        infiniteOrNull?.applyPalette(palette)
        infiniteOrNull?.applyInputPrefs(
            p.fingerDraws,
            if (p.penButtonTool == "none") null else (Tool.fromId(p.penButtonTool) ?: Tool.ERASER),
            p.zoomLockPan,
            p.snapRotation90,
        )
        infiniteOrNull?.applyZoomRange(p.canvasMinZoomPercent, p.canvasMaxZoomPercent)
        // Both surfaces' pads: the switch is about the device, not about one of them.
        pad.frontBuffering = !p.disableFrontBuffering
        infiniteOrNull?.pad?.frontBuffering = !p.disableFrontBuffering
        state.pageColorOverride = if (p.defaultTemplate == "color") p.pageColor else null
        val theme = "$palette|${state.pageColorOverride}"
        if (thumbnailTheme != theme) {
            thumbnailTheme = theme
            thumbnailGeneration = thumbCache.useTheme(theme)
            synchronized(noteThumbs) { noteThumbs.evictAll() }
            synchronized(pageThumbs) { pageThumbs.evictAll() }
            thumbnailVersion++
        }
        controller.fingerDraws = p.fingerDraws
        controller.zoomLockPan = p.zoomLockPan
        controller.detectShapes = p.detectShapes
        controller.snapRotation90 = p.snapRotation90
        flowText.markdownInput = p.markdownInput
        flowText.slashCommands = p.slashCommands
        controller.penButtonTool = if (p.penButtonTool == "none") null else (Tool.fromId(p.penButtonTool) ?: Tool.ERASER)
        controller.penButtonHover = p.penButtonHover
        state.sideMargin = p.sideMargin
        state.pageBorders = !p.hidePageBorders
        state.maxCachePx = p.maxCacheResolution.toDouble()
        state.minZoom = if (p.minZoomEnabled) p.minZoomPercent / 100.0 else CanvasState.MIN_ZOOM
        state.maxZoom = (if (p.maxZoomEnabled) p.maxZoomPercent / 100.0 else CanvasState.MAX_ZOOM)
            .coerceAtLeast(state.minZoom)
        state.clampZoomToLimits()
        state.relayout()
    }

    /** Whether typed markdown markers convert; the text tool's config popup toggles it. */
    var markdownInput by mutableStateOf(settings.prefs.markdownInput)
        private set

    /** Set the Markdown shortcuts preference, applying it to both panes and persisting it. */
    fun setMarkdownInputPref(on: Boolean) {
        settings = settings.copy(prefs = settings.prefs.copy(markdownInput = on))
        settingsRepo.save(settings)
        for (editor in listOfNotNull(this, sibling)) {
            editor.markdownInput = on
            editor.flowText.markdownInput = on
        }
    }

    /** Whether "/" opens the command menu; the text tool's config popup toggles it. */
    var slashCommands by mutableStateOf(settings.prefs.slashCommands)
        private set

    /** Set the Slash commands preference, applying it to both panes and persisting it. */
    fun setSlashCommandsPref(on: Boolean) {
        settings = settings.copy(prefs = settings.prefs.copy(slashCommands = on))
        settingsRepo.save(settings)
        for (editor in listOfNotNull(this, sibling)) {
            editor.slashCommands = on
            editor.flowText.slashCommands = on
            if (!on) editor.slashDismissed = null
        }
    }

    /** Set fullscreen and persist it as an explicit choice (used by the toolbar, F11, and the
     *  Preferences toggle); lightweight, no canvas refresh. */
    fun setFullscreenPref(v: Boolean) {
        fullscreen = v
        settings = settings.copy(prefs = settings.prefs.copy(startFullscreen = v))
        settingsRepo.save(settings)
    }

    fun toggleFullscreen() = setFullscreenPref(!fullscreen)

    /** Apply edited preferences live and persist (used by the Preferences dialog). */
    fun applyPreferences(p: Preferences) {
        val marginChanged = p.sideMargin != settings.prefs.sideMargin
        settings = settings.copy(prefs = p)
        for (editor in listOfNotNull(this, sibling)) {
            editor.fullscreen = p.startFullscreen ?: !editor.deviceHasDisplayCutout
            editor.applyPagePrefsToState(p)
            editor.republishFlow(invalidate = true)
            editor.state.invalidateAllCaches()
            if (marginChanged) {
                if (editor.state.fitHeightActive) editor.state.fitHeight() else editor.state.fitWidth()
            }
            editor.refreshView()
            editor.prefsVersion++
            editor.view.requestRender()
        }
        settingsRepo.save(settings)
    }

    /** Apply preferences only the home screen reads, without the canvas refresh [applyPreferences] does. */
    fun applyHomePreferences(p: Preferences) {
        settings = settings.copy(prefs = p)
        cornerStyle = p.cornerStyle
        toolbarLook = p.toolbarLook
        saveSettingsSoon()
        prefsVersion++
    }

    /** Apply an edited toolbar layout live and persist (used by the toolbar customiser). */
    fun applyToolbarLayout(layout: ToolbarLayout) {
        toolbarLayout = layout
        settings = settings.copy(toolbarLayout = layout)
        settingsRepo.save(settings)
    }

    /** The same, for the infinite canvas's bar. */
    fun applyCanvasToolbarLayout(layout: ToolbarLayout) {
        canvasToolbarLayout = layout
        infiniteOrNull?.toolbarLayout = layout
        settings = settings.copy(canvasToolbarLayout = layout)
        settingsRepo.save(settings)
    }

    /** Set how many colour swatches the toolbar shows (1-7) and persist. */
    fun applyToolbarColorCount(count: Int) {
        val c = count.coerceIn(1, 7)
        toolbarColorCount = c
        infiniteOrNull?.toolbarColorCount = c
        if (activeColorIndex >= c) pickColor(c - 1)
        settings = settings.copy(toolbarColorCount = c)
        settingsRepo.save(settings)
    }

    /**
     * Hand the canvas the same persisted pen styles, shape style and ink the paged editor uses, so
     * a pen tuned on one surface is that pen on the other rather than a second one that happens to
     * look similar.
     */
    private fun pushToolsToCanvas() {
        val canvas = infiniteOrNull ?: return
        for (t in ToolDefaults.persistedTools) canvas.setToolConfig(t, settings.configFor(t))
        canvas.armShapeConfig(settings.shapeConfig)
        canvas.toolbarColors = toolbarColors
        canvas.recentColors = recentColors
        canvas.toolbarColorCount = toolbarColorCount
        canvas.toolbarLayout = canvasToolbarLayout
        canvas.pickColor(activeColorIndex)
    }

    /** Snapshot live state into settings and save (call on pause/stop). */
    fun persist() {
        // A style tuned on the canvas is the same style, so whichever surface was last used wins.
        val fromCanvas = infiniteOrNull
        if (fromCanvas != null && canvasOpen) {
            for (t in ToolDefaults.persistedTools) controller.setToolConfig(t, fromCanvas.toolConfig(t))
            controller.shapeConfig = fromCanvas.shapeConfig
            shapeConfig = fromCanvas.shapeConfig
            activeColorIndex = fromCanvas.activeColorIndex
        }
        // Both panes of a split hold their own live tool state, so only the primary snapshots it
        // back into settings; otherwise the second pane's pens would overwrite the first pane's.
        if (pane == Pane.PRIMARY) {
            val tools = ToolDefaults.persistedTools.associateWith { controller.configFor(it) }
            settings = settings.copy(
                tools = tools,
                shapeConfig = controller.shapeConfig,
                toolbarColors = toolbarColors,
                toolbarColorCount = toolbarColorCount,
                activeColor = activeColorIndex,
                lastTool = tool,
                sidebarVisible = sidebarVisible,
                renderScale = renderScale,
            )
        }
        if (noteOpen) saveViewState() // remember this folder note's view for next time
        settingsRepo.save(settings)
        // The note + session writes are heavy for a big note; run them off the main thread so pressing
        // Home never freezes the UI. The process survives to finish them; the debounced autosave during
        // editing bounds any loss on an immediate hard kill.
        flushThen(showOverlay = false) {}
        flushCanvasAutosave()
        saveSession()
        saveCanvasSession()
        secondary?.persist() // the other pane's note has to be flushed on pause too
    }

    /** Persist the open canvas, or clear the stored one when a canvas is not what is open. */
    private fun saveCanvasSession() {
        val canvas = infiniteOrNull
        if (!canvasOpen || canvas == null) {
            canvasSession.clear()
            return
        }
        canvasSession.save(canvas.document, writeDocument = true)
    }

    /** Persist the working session (open document + zoom/scroll) so the next launch
     *  reopens this note where the user left off, unsaved edits included. */
    private fun saveSession() {
        if (!sessionLoaded) return // don't overwrite the saved note before restore has applied
        if (!noteOpen || canvasOpen) { session.clear(); return } // nothing paged on top: wipe any stale session
        val contentChanged = contentVersion != lastSessionContentVersion
        lastSessionContentVersion = contentVersion
        // Snapshot on the main thread, then write the session off-thread, so saving a big note's
        // session never freezes the UI. Unconditionally, even for a view-state-only refresh: the
        // store re-encodes whatever it is handed when the session file is missing, and handing it
        // the live document would put the writer back on the mutating model.
        val snapshot = state.document.snapshot()
        val zoom = state.zoom
        val sx = state.scrollX
        val sy = state.scrollY
        val locked = zoomLocked
        val vo = viewOverrides
        autosaveScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                session.save(snapshot, zoom, sx, sy, locked, vo, writeDocument = contentChanged)
            }
        }
    }

    /** Reopen the last session (document + view state). The heavy load runs off the
     *  main thread; the apply runs on the caller's (main) dispatcher. A no-op when
     *  there is no saved session. Drives the launch loader, so it's safe to await. */
    suspend fun restoreSession() {
        // A canvas was open last time: it takes precedence, since only one document is ever on top.
        val canvasDoc = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (canvasSession.exists()) canvasSession.load() else null
        }
        if (canvasDoc != null) {
            openCanvasDocument(canvasDoc, canvasDoc.path, canvasDoc.displayName)
            sessionLoaded = true
            return
        }
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
            installViewOverrides(saved?.overrides ?: snap.viewOverrides)
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
        if (editingTable != null || tableMenu != null) tableChromeTick++
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

    /** Set the active text box's colour (and the ink new boxes are created with). */
    fun setTextColor(color: Rgba) {
        controller.pickInk(color)
        refreshTextBar()
    }

    fun toggleTextBold() { controller.toggleTextBold(); refreshTextBar() }
    fun toggleTextItalic() { controller.toggleTextItalic(); refreshTextBar() }
    fun toggleTextUnderline() { controller.toggleTextUnderline(); refreshTextBar() }
    fun toggleTextStrike() { controller.toggleTextStrike(); refreshTextBar() }
    fun cycleTextAlign() { controller.cycleTextAlign(); refreshTextBar() }

    fun updateEditingText(text: String) {
        controller.updateEditingText(text)
    }

    fun commitText(text: String? = null) {
        controller.commitTextEdit(text, restoreTool = true)
    }

    /** A one-finger drag over the editor scrolls the page (edit stays open), not the box's own text. */
    fun panWhileEditing(dxFinger: Float, dyFinger: Float) {
        controller.panWhileEditing(dxFinger.toDouble(), dyFinger.toDouble())
    }

    // --- page styles (paper colour + ruling): document-wide ("All Pages") and per-page ---

    /** The document-wide style override; per-page styles layer on top (see [PageStyle]). */
    val documentStyle: PageStyle get() = state.document.style

    /** The current page's own style override (an empty [PageStyle] when there is no page). */
    val currentPageStyle: PageStyle
        get() = state.document.pages.getOrNull(state.currentPageIndex())?.style ?: PageStyle()

    /** The saved All Pages style stamped onto newly created notes (empty ⇒ app built-ins). */
    var newNoteStyle by mutableStateOf(settings.newNoteStyle)
        private set

    /** Save (or, passing an empty style, forget) the All Pages style new notes start with. */
    fun saveNewNoteStyle(style: PageStyle) {
        if (newNoteStyle == style) return
        // New notes copy their template from the library, so adopt one only this note carries.
        style.template?.let { if (TemplateLibrary.entry(it) == null) keepNoteTemplate(it) }
        newNoteStyle = style
        settings = settings.copy(newNoteStyle = style)
        settingsRepo.save(settings)
    }

    /** The flow defaults stamped onto newly created notes: the saved ones, else [factoryFlow]. */
    var newNoteFlow by mutableStateOf(settings.newNoteFlow ?: factoryFlow)
        private set

    /** Save (or, passing [factoryFlow], forget) the flow defaults new notes start with. */
    fun saveNewNoteFlow(defaults: FlowDefaults) {
        if (newNoteFlow == defaults) return
        newNoteFlow = defaults
        settings = settings.copy(newNoteFlow = defaults.takeIf { it != factoryFlow })
        settingsRepo.save(settings)
    }

    /** The saved background stamped onto newly created canvases (null ⇒ app built-ins). */
    var newCanvasBackground by mutableStateOf(settings.newCanvasBackground)
        private set

    /** Save (or, passing null, forget) the background new canvases start with. */
    fun saveNewCanvasBackground(background: com.xnotes.core.infinite.CanvasBackground?) {
        if (newCanvasBackground == background) return
        newCanvasBackground = background
        settings = settings.copy(newCanvasBackground = background)
        settingsRepo.save(settings)
    }

    /** A blank document at the user's default page size, custom dimensions included. */
    private fun blankDocument(): Document {
        val (w, h) = settings.prefs.newPagePixels()
        return Document.blankPixels(Document.DEFAULT_NEW_PAGES, w, h).also { it.created = System.currentTimeMillis() }
    }

    /** Stamp the saved new-note defaults (page style + flow config) onto a fresh [doc]. */
    private fun stampNewNoteDefaults(doc: Document): Document {
        doc.style = settings.newNoteStyle
        TemplateLibrary.embed(doc, doc.style.template)
        // A default whose template left the library falls back to the built-in ruling.
        doc.style.template?.let { key ->
            if (key != PageTemplates.NONE && !PageTemplates.isBuiltIn(key) && key !in doc.templates) {
                doc.style = doc.style.withTemplate(null, PageTemplates.NONE)
            }
        }
        (settings.newNoteFlow ?: factoryFlow).applyTo(doc.flow)
        return doc
    }

    /** Replace the document-wide ("All Pages") style override. */
    fun setDocumentStyle(style: PageStyle) {
        val prev = state.document.style
        if (prev == style) return
        TemplateLibrary.embed(state.document, style.template)
        state.document.style = style
        applyStyleChange(prev, style, state.document.pages.toList())
    }

    /** Replace the current page's style override. */
    fun setCurrentPageStyle(style: PageStyle) {
        val page = state.document.pages.getOrNull(state.currentPageIndex()) ?: return
        val prev = page.style
        if (prev == style) return
        TemplateLibrary.embed(state.document, style.template)
        page.style = style
        applyStyleChange(prev, style, listOf(page))
    }

    // --- page margins (extra paper on any edge): document-wide ("All Pages") and per-page ---

    /** The document-wide margin override; per-page margins layer on top (see [PageMargins]). */
    val documentMargins: PageMargins get() = state.document.margins

    /** The current page's own margin override (an empty [PageMargins] when there is no page). */
    val currentPageMargins: PageMargins
        get() = state.document.pages.getOrNull(state.currentPageIndex())?.margins ?: PageMargins()

    /** Replace the document-wide ("All Pages") margin override. */
    fun setDocumentMargins(margins: PageMargins) {
        if (state.document.margins == margins) return
        state.document.margins = margins
        applyMarginChange()
    }

    /** Replace the current page's margin override. */
    fun setCurrentPageMargins(margins: PageMargins) {
        val page = state.document.pages.getOrNull(state.currentPageIndex()) ?: return
        if (page.margins == margins) return
        page.margins = margins
        applyMarginChange()
    }

    /**
     * Apply a margin change and persist it (dirty -> autosave) — deliberately **not** onto the undo
     * stack, like a style change. A margin resizes the paper, so every cached surface is now the
     * wrong shape: they are dropped rather than repaired, and the document is laid out again.
     */
    private fun applyMarginChange() {
        state.invalidatePageGeometry()
        state.relayout()
        state.document.dirty = true
        refreshContent()
        view.requestRender()
    }

    /**
     * Apply a style change to the caches and persist it (dirty -> autosave) — deliberately **not**
     * onto the undo stack. The paper colour is filled live each frame, so a colour-only change just
     * repaints; a ruling change ([pages] are the pages it may affect) rebuilds their background caches.
     */
    private fun applyStyleChange(prev: PageStyle, next: PageStyle, pages: List<Page>) {
        val rulingChanged = prev.template != next.template ||
            prev.patternColor != next.patternColor ||
            prev.spacing != next.spacing ||
            prev.accentColor != next.accentColor ||
            prev.params != next.params ||
            prev.colors != next.colors
        if (rulingChanged) {
            if (pages.size == 1) state.invalidateBackground(pages[0]) else state.invalidateAllBackgrounds()
        } else {
            state.invalidatePaper()
        }
        state.document.dirty = true
        refreshContent()
        view.requestRender()
    }

    // --- export-time style resolution: resolved against the document being exported (which may be a
    //     closed note loaded by URI, or a page subset), not necessarily the open one ---

    private fun exportPaper(doc: Document, page: Page): Rgba =
        page.resolvedPageColor(doc, state.pageColorOverride) ?: state.palette.paper

    private fun paintExportRuling(doc: Document, page: Page, r: Renderer) {
        val cover = exportFootprint(doc, page)
        TemplateLibrary.paint(r, doc, page, cover, cover)
    }

    /** [page]'s whole paper in page space, margins included, resolved against [doc] (export/thumbnails). */
    private fun exportFootprint(doc: Document, page: Page): com.xnotes.core.geometry.Rect {
        val i = page.insets(doc)
        return com.xnotes.core.geometry.Rect(-i.left, -i.top, i.left + page.width + i.right, i.top + page.height + i.bottom)
    }

    private fun refreshContent() {
        republishFlowIfStale()
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
     * A page's display height/width ratio (rotation-aware). The side panel reserves each thumbnail
     * row's height from this so rows don't grow when their bitmap finishes loading — that resizing
     * was what made the scrollbar thumb wobble while scrolling (its size is derived from the
     * visible rows' heights).
     */
    fun pageAspectRatio(page: Page): Float = (state.displayH(page) / state.displayW(page)).toFloat()

    /**
     * Renders a page to a thumbnail bitmap (paper + PDF/template background + items). [active] is
     * polled before the costly steps (PDF background, each item) so a render abandoned mid-flight —
     * the side-panel row scrolled out of view — bails out instead of burning CPU the scroll needs.
     */
    fun renderThumbnail(page: Page, widthPx: Int, active: () -> Boolean = { true }): android.graphics.Bitmap? {
        val cover = state.footprint(page)
        val scale = widthPx / cover.w
        val w = widthPx.coerceAtLeast(1)
        val h = (cover.h * scale).toInt().coerceAtLeast(1)
        val surface = com.xnotes.platform.AndroidRasterSurface.create(w, h)
        surface.fill(state.paperColor(page))
        val r = surface.renderer()
        r.scale(scale, scale)
        r.translate(-cover.left, -cover.top)
        if (!active()) return null
        val src = pdfSource
        val pi = page.pdfPage
        if (src != null && pi != null) {
            // Pure consumer: never parses. Stamps real image colours only if the linear sweep has
            // already found this page's locations, otherwise draws it filtered; the row re-renders
            // with real colours once the sweep reaches the page (see onImagesReady → pdfThumbTick).
            val pw = (page.width * scale).toInt().coerceAtLeast(1)
            val ph = (page.height * scale).toInt().coerceAtLeast(1)
            src.renderPage(pi, pw, ph, pdfPageFilter())?.let { bg ->
                r.drawRaster(bg, com.xnotes.core.geometry.Rect(0.0, 0.0, page.width, page.height))
                bg.recycle()
            }
            // The margins are outside the raster, so their ruling still has to be painted.
            state.paintPageBackground?.invoke(page, r, scale, cover)
        } else {
            state.paintPageBackground?.invoke(page, r, scale, cover)
        }
        state.paintFlow?.invoke(page, r, cover)
        for (item in itemsSnapshot(page)) {
            if (!active()) return null
            item.paint(r)
        }
        // Painting built this page's ribbons, and the grid renders pages nowhere near the viewport.
        // Register it so the frame loop's sweep reclaims them like any other off-band page.
        state.noteGeometryBuilt(page)
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
        val generation = thumbnailGeneration
        val version = contentVersion
        cachedPageThumbnail(page)?.let { return it }
        return withContext(Dispatchers.Default) {
            cachedPageThumbnail(page)?.let { return@withContext it }
            // Render in page space sized so the rotated result lands at [widthPx] wide.
            val renderW = (state.outerW(page) * (widthPx / state.displayW(page))).roundToInt().coerceAtLeast(1)
            val bmp = renderThumbnail(page, renderW, active = { isActive && isCurrentThumbnail(generation) })
                ?.let { rotateForView(it) } ?: return@withContext null
            synchronized(pageThumbs) {
                if (!isActive || !isCurrentThumbnail(generation) || version != contentVersion) {
                    bmp.recycle()
                    null
                } else {
                    bmp.asImageBitmap().also { pageThumbs.put(page, it) }
                }
            }
        }
    }

    /** Rotate a page-space bitmap by the view rotation, for thumbnails that mirror the canvas. */
    private fun rotateForView(bmp: android.graphics.Bitmap): android.graphics.Bitmap {
        val deg = viewSettings.rotation
        if (deg == 0) return bmp
        val m = android.graphics.Matrix().apply { postRotate(deg.toFloat()) }
        return android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
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

    /**
     * Open the note at [uri], driving the "Opening note…" dialog via [opening]. The heavy part is
     * [DocumentCodec.read] streaming a (possibly large) embedded PDF out to a temp file; on the main
     * thread that would freeze the UI and stall the spinner, so only the read runs on IO and the
     * document swap ([replaceDocument], which only invalidates caches and requests an off-thread
     * render) stays on the caller's main thread. A mid-read Cancel ([cancelOpenInProgress]) discards
     * the loaded note and leaves the explorer as it was. A second tap while one open is in flight is
     * ignored, so two reads never race to swap in a document.
     */
    suspend fun openAsync(uri: String, name: String? = null) {
        if (opening) return
        openCancelled.set(false)
        opening = true
        val t0 = System.nanoTime()
        var readMs = -1L
        try {
            val readStart = System.nanoTime()
            var fileBytes = -1L
            val timing = com.xnotes.format.DocumentCodec.ReadTiming()
            val doc = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                fileBytes = fileSizeOf(uri)
                appContext.contentResolver.openInputStream(android.net.Uri.parse(uri))
                    ?.use { codec.read(it, pdfDir, imageDir, timing) }
                    ?.also { it.created = settleCreated(uri, it.created) }
            }
            readMs = (System.nanoTime() - readStart) / 1_000_000
            state.lastOpenInflateMs = timing.inflateMs
            state.lastOpenParseMs = timing.parseMs
            state.lastOpenAssetsMs = timing.assetsMs
            state.lastOpenCompactMs = timing.compactMs
            android.util.Log.i(
                "xnotes.save",
                "open read ${readMs}ms = inflate ${timing.inflateMs} + parse ${timing.parseMs}" +
                    " + assets ${timing.assetsMs} + compact ${timing.compactMs}, $fileBytes bytes",
            )
            if (doc == null) { message = appContext.getString(R.string.err_open_that_note); return }
            if (openCancelled.get()) { doc.pdfFile?.delete(); deleteImageTemps(doc); return } // tapped Cancel mid-read; stay put
            doc.path = uri
            doc.displayName = name
            doc.dirty = false
            replaceDocument(doc)
            state.openFileBytes = fileBytes
            state.lastSaveBytes = fileBytes // the on-disk size, until the first autosave rewrites it
            maybeBindAutosave(uri) // resume autosaving if this note lives in the granted folder
            noteOpen = true // push the editor on top of backstage (only on a successful open)
            rememberOpened(uri, name)
        } catch (e: XNoteFormatException) {
            message = appContext.getString(R.string.err_not_xnotes)
        } catch (e: Exception) {
            message = appContext.getString(R.string.err_open_note)
        } finally {
            // Record the timings for the debug overlay, so a genuinely fast open (read < 160ms, no
            // spinner) can be told apart from a bug where the spinner is wrongly skipped.
            state.lastOpenReadMs = readMs
            state.lastOpenTotalMs = (System.nanoTime() - t0) / 1_000_000
            opening = false
        }
    }

    /** Aborts an in-flight open (the dialog's Cancel) so the loaded note is discarded, not shown. */
    fun cancelOpenInProgress() { openCancelled.set(true) }

    fun save(output: OutputStream, uri: String, name: String? = null) {
        try {
            synchronized(saveLock) { codec.write(state.document, output) }
            state.document.path = uri
            if (name != null) state.document.displayName = name
            state.document.dirty = false
            maybeBindAutosave(uri) // saving into the folder makes it autosave thereafter
            refreshContent()
            invalidateThumb(uri) // content changed; re-render its tile next time it's shown
        } catch (e: Throwable) {
            // Throwable, not Exception: an OutOfMemoryError mid-encode must surface a message, not crash.
            message = appContext.getString(R.string.err_save_note)
        }
    }

    /**
     * Save the current document over its existing SAF file at [uri] via [writeNoteSafely], so a
     * failed encode never truncates the note, then run [onDone] on the main thread with whether the
     * bytes landed. A failure shows a message, and lets the caller fall back to a Save-As picker.
     *
     * The write goes out on [autosaveScope] + IO like every other save here. Doing it inline was an
     * ANR: an explicit save targets whatever the system picker handed over, and openOutputStream on
     * a cloud provider is an untimed binder call into that provider's process.
     */
    fun saveToThen(uri: String, onDone: (Boolean) -> Unit) {
        noteDebounceJob?.cancel() // this write supersedes a pending debounce
        val doc = state.document
        val startNs = System.nanoTime()
        // Pointers, on the main thread: the writer gets its own page lists over the live items.
        val snapshot = doc.snapshot()
        state.lastSaveSnapshotMs = msSince(startNs)
        // Clean from here, not once the bytes land; see [startNoteWrite] for why.
        val wasDirty = doc.dirty
        doc.dirty = false
        dirty = false
        savingNote = true
        val pending = noteWriteJob // captured, so the join below is on the previous write, not itself
        noteWriteJob = autosaveScope.launch {
            pending?.join() // a write already going out finishes first rather than racing this one
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                writeNoteSafely(uri, snapshot)
            }
            savingNote = false
            if (ok) {
                // The note may have been switched while the bytes were going out; only the document
                // this save belongs to may have its path and autosave binding touched.
                if (state.document === doc) {
                    doc.path = uri
                    maybeBindAutosave(uri)
                    refreshContent()
                }
                invalidateThumb(uri)
            } else {
                if (wasDirty && state.document === doc) { doc.dirty = true; dirty = true }
                message = appContext.getString(R.string.err_save_note)
            }
            state.lastSaveTotalMs = msSince(startNs)
            onDone(ok)
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
     *  the live document off-thread, which a main-thread edit can mutate underneath it. [filter] and
     *  [rotation] are the note's own View settings resolved against the global defaults. */
    private fun renderDocThumbnailSquare(doc: Document, sidePx: Int, filter: com.xnotes.canvas.PdfPageFilter, rotation: Int = 0): android.graphics.Bitmap? {
        val page = doc.pages.firstOrNull() ?: return null
        val side = sidePx.coerceAtLeast(1)
        val cover = exportFootprint(doc, page)
        val turned = rotation == 90 || rotation == 270
        val shownW = if (turned) cover.h else cover.w
        val shownH = if (turned) cover.w else cover.h
        // An upright page shows its top across the whole tile.
        if (!turned && shownW <= shownH) return paintDocPage(doc, 0, side, side, side.toDouble() / cover.w, rotation, filter)
        // Otherwise the page as shown spans the tile's width: centred top to bottom when it's wider than tall.
        val whole = renderDocPage(doc, 0, (side * shownH / shownW).roundToInt().coerceAtLeast(1), filter, rotation) ?: return null
        val tile = com.xnotes.platform.AndroidRasterSurface.create(side, side)
        tile.fill(page.resolvedPageColor(doc, state.pageColorOverride) ?: state.palette.paper)
        android.graphics.Canvas(tile.bitmap).drawBitmap(whole, 0f, if (shownW > shownH) (side - whole.height) / 2f else 0f, null)
        whole.recycle()
        return tile.bitmap
    }

    /** Page [index] whole, [heightPx] tall once turned by [rotation], at the page's own shape. */
    private fun renderDocPage(doc: Document, index: Int, heightPx: Int, filter: com.xnotes.canvas.PdfPageFilter, rotation: Int = 0): android.graphics.Bitmap? {
        val page = doc.pages.getOrNull(index) ?: return null
        val cover = exportFootprint(doc, page)
        val turned = rotation == 90 || rotation == 270
        val scale = heightPx.coerceAtLeast(1) / (if (turned) cover.w else cover.h)
        val w = (cover.w * scale).roundToInt().coerceAtLeast(1)
        val h = (cover.h * scale).roundToInt().coerceAtLeast(1)
        return paintDocPage(doc, index, w, h, scale, rotation, filter)
    }

    /** Page [index] painted from its top-left corner at [scale] onto a [w]×[h] surface, then turned by [rotation]. */
    private fun paintDocPage(
        doc: Document, index: Int, w: Int, h: Int, scale: Double, rotation: Int, filter: com.xnotes.canvas.PdfPageFilter,
    ): android.graphics.Bitmap? {
        val page = doc.pages.getOrNull(index) ?: return null
        val cover = exportFootprint(doc, page)
        val surface = com.xnotes.platform.AndroidRasterSurface.create(w, h)
        // Resolve the paper colour against this note's own document/page style (not the open note's).
        surface.fill(page.resolvedPageColor(doc, state.pageColorOverride) ?: state.palette.paper)
        val r = surface.renderer()
        r.scale(scale, scale)
        r.translate(-cover.left, -cover.top)
        doc.pdfFile?.let { file ->
            runCatching {
                com.xnotes.platform.PdfSource.create(appContext, file)?.let { src ->
                    page.pdfPage?.let { pi ->
                        if (filter.stampImages) src.ensureImageRects(pi)
                        val pw = (page.width * scale).toInt().coerceAtLeast(1)
                        val ph = (page.height * scale).toInt().coerceAtLeast(1)
                        src.renderPage(pi, pw, ph, filter)?.let { bg ->
                            r.drawRaster(bg, Rect(0.0, 0.0, page.width, page.height))
                            bg.recycle()
                        }
                    }
                    src.close()
                }
            }
        }
        // A closed note's flow is laid out locally (the published snapshot serves the open note only).
        if (!doc.flow.isEmpty) {
            val frame = themedFlowLayout(doc.flow)
                .layout(doc.flow, doc.pages.map { PageBox(it.width, it.height) }, doc.dpi)
            FlowPainter.paintPage(r, frame, index, Rect(0.0, 0.0, page.width, page.height))
        }
        for (item in itemsSnapshot(page)) item.paint(r)
        val bmp = surface.bitmap
        if (rotation == 0) return bmp
        val m = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
        return android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    /**
     * The square tile for an infinite canvas: its content framed with a little margin, drawn with
     * the ordinary CPU renderer rather than through GL. The items are the same [CanvasItem]s the
     * paged note holds, so they paint themselves, and doing it here keeps thumbnails off the render
     * thread entirely, with no readback and nothing that needs a live EGL context.
     */
    private fun renderCanvasThumbnailSquare(
        doc: com.xnotes.core.infinite.InfiniteDocument,
        sidePx: Int,
    ): android.graphics.Bitmap? = renderCanvasThumbnail(doc, sidePx, sidePx)

    /** A canvas's content framed with a little margin in a [wPx]×[hPx] tile. */
    private fun renderCanvasThumbnail(
        doc: com.xnotes.core.infinite.InfiniteDocument,
        wPx: Int,
        hPx: Int,
    ): android.graphics.Bitmap? {
        val w = wPx.coerceAtLeast(1)
        val h = hPx.coerceAtLeast(1)
        val surface = com.xnotes.platform.AndroidRasterSurface.create(w, h)
        surface.fill(doc.background.paperColor ?: state.palette.paper)
        val bounds = doc.contentBounds()
        if (bounds != null && bounds.w > 0.0 && bounds.h > 0.0) {
            val r = surface.renderer()
            val margin = minOf(w, h) * 0.06
            val scale = minOf((w - 2 * margin) / bounds.w, (h - 2 * margin) / bounds.h).coerceAtMost(1.0)
            // Centre the content in the tile, whichever way round it is.
            r.translate(
                margin + (w - 2 * margin - bounds.w * scale) / 2.0,
                margin + (h - 2 * margin - bounds.h * scale) / 2.0,
            )
            r.scale(scale, scale)
            r.translate(-bounds.left, -bounds.top)
            for (item in doc.items.toList()) item.paint(r)
        }
        return surface.bitmap
    }

    /** The square thumbnail for the canvas at [uri]; null when it is not a readable canvas. */
    suspend fun canvasTileThumbnail(uri: String): ImageBitmap? {
        val generation = thumbnailGeneration
        synchronized(noteThumbs) { noteThumbs.get(uri)?.let { return it } }
        return withContext(thumbDispatcher) {
            delay(150)
            if (!isActive || !isCurrentThumbnail(generation)) return@withContext null
            synchronized(noteThumbs) { noteThumbs.get(uri)?.let { return@withContext it } }
            val bmp = thumbCache.load(uri, generation) ?: run {
                val doc = runCatching {
                    appContext.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use {
                        canvasCodec.read(it, imageDir)
                    }
                }.getOrNull() ?: return@withContext null
                doc.created?.let { createdStore.put(documentKey(uri), it) }
                try {
                    renderCanvasThumbnailSquare(doc, tilePx)?.also { thumbCache.store(uri, it, generation) }
                        ?: return@withContext null
                } finally {
                    deleteCanvasImageTemps(doc)
                }
            }
            publishThumbnail(uri, bmp, generation)
        }
    }

    private fun isCurrentThumbnail(generation: Long): Boolean =
        generation == thumbnailGeneration && thumbCache.isCurrent(generation)

    private fun publishThumbnail(key: String, bitmap: android.graphics.Bitmap, generation: Long): ImageBitmap? =
        synchronized(noteThumbs) {
            if (!isCurrentThumbnail(generation)) {
                bitmap.recycle()
                null
            } else {
                bitmap.asImageBitmap().also { noteThumbs.put(key, it) }
            }
        }

    /** The square tile for the document at [uri], whichever kind it is. */
    suspend fun tileThumbnail(uri: String, name: String): ImageBitmap? =
        if (com.xnotes.core.util.ReaderKind.isReadable(name)) {
            readerThumbnail(uri, name)
        } else if (com.xnotes.core.util.DocumentKind.ofName(name) == com.xnotes.core.util.DocumentKind.CANVAS) {
            canvasTileThumbnail(uri)
        } else {
            noteTileThumbnail(uri)
        }

    /** A read-only PDF's first page (memory-cached only); null for the other reader kinds, which tile as text. */
    private suspend fun readerThumbnail(uri: String, name: String): ImageBitmap? {
        if (com.xnotes.core.util.ReaderKind.ofName(name) != com.xnotes.core.util.ReaderKind.PDF) return null
        val key = "reader|$uri"
        synchronized(noteThumbs) { noteThumbs.get(key)?.let { return it } }
        return withContext(thumbDispatcher) {
            delay(150)
            if (!isActive) return@withContext null
            val bmp = com.xnotes.platform.ReaderPdf.renderFirstPage(appContext, android.net.Uri.parse(uri), pageThumbPx)
                ?.asImageBitmap() ?: return@withContext null
            synchronized(noteThumbs) { noteThumbs.put(key, bmp) }
            bmp
        }
    }

    /** The square side (px) explorer tiles render at — fixed so rotation/column changes don't re-render. */
    private val tilePx = 600

    /** The height (px) whole-page thumbnails render at. */
    private val pageThumbPx = 600

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
        val generation = thumbnailGeneration
        synchronized(noteThumbs) { noteThumbs.get(uri)?.let { return it } }
        return withContext(thumbDispatcher) {
            delay(150) // let a quick scroll-past cancel this before any heavy work begins
            if (!isActive || !isCurrentThumbnail(generation)) return@withContext null
            synchronized(noteThumbs) { noteThumbs.get(uri)?.let { return@withContext it } }
            val bmp = thumbCache.load(uri, generation) ?: run {
                val doc = runCatching {
                    appContext.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { codec.read(it, pdfDir, imageDir) }
                }.getOrNull() ?: return@withContext null
                doc.created?.let { createdStore.put(documentKey(uri), it) }
                try {
                    val vs = viewSettingsFor(uri)
                    renderDocThumbnailSquare(doc, tilePx, pdfPageFilterFor(vs), vs.rotation)?.also { thumbCache.store(uri, it, generation) } ?: return@withContext null
                } finally {
                    doc.pdfFile?.delete() // transient doc loaded just for a thumbnail; drop its extracts
                    deleteImageTemps(doc)
                }
            }
            publishThumbnail(uri, bmp, generation)
        }
    }

    /** The key a document's whole-page thumbnail is cached under, beside its square tile's. */
    private fun pageThumbKey(uri: String): String = "$uri#page"

    /** An already-rendered whole-page thumbnail for [uri], to seed a tile instantly. */
    fun cachedPageThumb(uri: String): ImageBitmap? = synchronized(noteThumbs) { noteThumbs.get(pageThumbKey(uri)) }

    /**
     * The first page whole, for Gallery and the "Whole page" tiles: [pageThumbPx] tall at the page's own shape,
     * or for a canvas its content in a landscape frame of the same size. Cached like the square tiles, and dropped
     * with them when the document changes.
     */
    suspend fun pageThumbnail(uri: String, name: String): ImageBitmap? {
        if (com.xnotes.core.util.ReaderKind.isReadable(name)) return readerThumbnail(uri, name)
        val generation = thumbnailGeneration
        val key = pageThumbKey(uri)
        synchronized(noteThumbs) { noteThumbs.get(key)?.let { return it } }
        return withContext(thumbDispatcher) {
            delay(150)
            if (!isActive || !isCurrentThumbnail(generation)) return@withContext null
            synchronized(noteThumbs) { noteThumbs.get(key)?.let { return@withContext it } }
            val bmp = thumbCache.load(key, generation) ?: run {
                val u = android.net.Uri.parse(uri)
                val rendered = if (DocumentKind.ofName(name) == DocumentKind.CANVAS) {
                    val doc = runCatching { appContext.contentResolver.openInputStream(u)?.use { canvasCodec.read(it, imageDir) } }.getOrNull()
                        ?: return@withContext null
                    try {
                        renderCanvasThumbnail(doc, (pageThumbPx * 1.414).roundToInt(), pageThumbPx)
                    } finally {
                        deleteCanvasImageTemps(doc)
                    }
                } else {
                    val doc = runCatching { appContext.contentResolver.openInputStream(u)?.use { codec.read(it, pdfDir, imageDir) } }.getOrNull()
                        ?: return@withContext null
                    try {
                        val vs = viewSettingsFor(uri)
                        renderDocPage(doc, 0, pageThumbPx, pdfPageFilterFor(vs), vs.rotation)
                    } finally {
                        doc.pdfFile?.delete()
                        deleteImageTemps(doc)
                    }
                } ?: return@withContext null
                rendered.also { thumbCache.store(key, it, generation) }
            }
            publishThumbnail(key, bmp, generation)
        }
    }

    /** The first [max] pages of the note at [uri], each [heightPx] tall, for a preview's page strip; empty for a canvas. */
    suspend fun pageStrip(uri: String, name: String, max: Int, heightPx: Int): List<ImageBitmap> {
        val generation = thumbnailGeneration
        if (DocumentKind.ofName(name) == DocumentKind.CANVAS) return emptyList()
        return withContext(thumbDispatcher) {
            val doc = runCatching {
                appContext.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { codec.read(it, pdfDir, imageDir) }
            }.getOrNull() ?: return@withContext emptyList()
            try {
                val vs = viewSettingsFor(uri)
                val filter = pdfPageFilterFor(vs)
                (0 until minOf(max, doc.pages.size)).mapNotNull { i ->
                    if (!isActive || !isCurrentThumbnail(generation)) return@withContext emptyList()
                    runCatching { renderDocPage(doc, i, heightPx, filter, vs.rotation) }.getOrNull()?.asImageBitmap()
                }.takeIf { isActive && isCurrentThumbnail(generation) } ?: emptyList()
            } finally {
                doc.pdfFile?.delete()
                deleteImageTemps(doc)
            }
        }
    }

    /** Delete the temp image files backing a transient document (thumbnail/export/cancelled open), so
     *  they don't pile up in [imageDir] until the next launch purge. Never called on the open note. */
    private fun deleteImageTemps(doc: Document) {
        for (page in doc.pages) for (item in page.items) {
            if (item is ImageItem) runCatching { item.image.file.delete() }
        }
    }

    /** [deleteImageTemps] for a canvas, whose items are one flat list rather than per page. */
    private fun deleteCanvasImageTemps(doc: com.xnotes.core.infinite.InfiniteDocument) {
        for (item in doc.items) if (item is ImageItem) runCatching { item.image.file.delete() }
    }

    /** Drop a note's cached tile (memory + disk) so it re-renders with fresh content next time it's shown. */
    private fun invalidateThumb(uri: String) {
        synchronized(noteThumbs) { noteThumbs.remove(uri); noteThumbs.remove(pageThumbKey(uri)) }
        thumbCache.remove(uri)
        thumbCache.remove(pageThumbKey(uri))
    }

    /**
     * Render the just-closed note's tile from the in-memory document and cache it (memory + disk), so
     * the grid shows it instantly and current. Runs on the low-priority [thumbDispatcher]; identity-
     * guarded so that if the user has already opened another note (state.document changed) it bails
     * rather than caching the wrong pixels. Called from [goHome] — never during editing.
     */
    private suspend fun regenerateClosedNoteThumb(uri: String) {
        val generation = thumbnailGeneration
        val doc = state.document
        // The closing note's own filter + rotation, captured before any swap.
        val filter = pdfPageFilter()
        val rotation = viewSettings.rotation
        withContext(thumbDispatcher) {
            if (state.document !== doc) return@withContext // a new note was opened; don't cache stale pixels
            val bmp = runCatching { renderDocThumbnailSquare(doc, tilePx, filter, rotation) }.getOrNull() ?: return@withContext
            thumbCache.store(uri, bmp, generation)
            // The whole-page tile is drawn again the next time it's shown.
            synchronized(noteThumbs) { noteThumbs.remove(pageThumbKey(uri)) }
            thumbCache.remove(pageThumbKey(uri))
            publishThumbnail(uri, bmp, generation)
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
        colorNames = emptyMap()
        browseRoot = treeUri
        settings = settings.copy(browseRoot = treeUri)
        settingsRepo.save(settings)
        browseCache.clear()
        rootNameCache.clear()
    }

    /** Browse the app's own private storage instead of a granted folder; no SAF grant needed. */
    fun useInternalStorage() {
        updateBrowseRoot(com.xnotes.platform.AppStorageDocumentsProvider.treeUri(appContext).toString())
    }

    /** The pins under the current explorer root; the rest wait for their folder to be the root again. */
    val sidebarPins: List<com.xnotes.settings.PinnedFolder>
        get() = browseRoot?.let { root -> pinnedFolders.filter { isUnderTree(it.uri, root) } }.orEmpty()

    fun isPinned(uri: String): Boolean {
        val key = documentKey(uri)
        return pinnedFolders.any { documentKey(it.uri) == key }
    }

    fun pinFolder(uri: String, name: String) {
        if (!isPinned(uri)) savePins(pinnedFolders + com.xnotes.settings.PinnedFolder(uri, name))
    }

    fun unpinFolder(uri: String) {
        val key = documentKey(uri)
        savePins(pinnedFolders.filterNot { documentKey(it.uri) == key })
    }

    private fun savePins(pins: List<com.xnotes.settings.PinnedFolder>) {
        if (pins == pinnedFolders) return
        pinnedFolders = pins
        settings = settings.copy(pinnedFolders = pins)
        settingsRepo.save(settings)
    }

    /** The explorer's (doc id, name) stack from [treeUri]'s root down to [folderUri], or null when it can't be reached. IO. */
    fun folderChain(treeUri: String, folderUri: String): List<Pair<String, String>>? {
        val tree = android.net.Uri.parse(treeUri)
        val rootId = browseRootDocId(treeUri)
        val path = runCatching {
            android.provider.DocumentsContract.findDocumentPath(appContext.contentResolver, android.net.Uri.parse(folderUri))?.path
        }.getOrNull()
        // A provider that can't walk the path still lets us jump straight in, one level under the root.
        val ids = path?.indexOf(rootId)?.takeIf { it >= 0 }?.let { path.drop(it + 1) } ?: listOf(browseDocId(folderUri))
        return ids.map { id ->
            val name = queryDisplayName(android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id)) ?: return null
            id to name
        }
    }

    /** On wide screens, whether the home sidebar is collapsed to its icon rail. */
    var backstageRail by mutableStateOf(settings.backstageRail)
        private set

    fun showRail(rail: Boolean) {
        if (settings.backstageRail == rail) return
        backstageRail = rail
        settings = settings.copy(backstageRail = rail)
        // Written after the fold settles: serializing every setting on the tap stalled the fold's first frame.
        saveSettingsSoon()
    }

    private var settingsSaveJob: kotlinx.coroutines.Job? = null

    /** Persist [settings] a moment after the tap that changed them; changes in between share the one write. */
    private fun saveSettingsSoon() {
        settingsSaveJob?.cancel()
        settingsSaveJob = autosaveScope.launch { delay(SETTINGS_SAVE_DELAY_MS); settingsRepo.save(settings) }
    }

    /** Notes and canvases opened on this device, newest first. */
    val recentDocs: List<com.xnotes.settings.RecentDoc>
        get() = sharedRecents.value ?: settings.recentDocs.also { sharedRecents.value = it }

    private fun setRecents(list: List<com.xnotes.settings.RecentDoc>) {
        if (list == recentDocs) return
        sharedRecents.value = list
        settings = settings.copy(recentDocs = list)
        saveSettingsSoon()
    }

    /** Put [uri] at the front of Recent. Main thread. */
    private fun rememberOpened(uri: String, name: String?) {
        val key = documentKey(uri)
        val entry = com.xnotes.settings.RecentDoc(uri, name ?: uri.substringAfterLast('/'), System.currentTimeMillis())
        setRecents((listOf(entry) + recentDocs.filterNot { documentKey(it.uri) == key }).take(RECENT_MAX))
    }

    /** Forget everything in Recent. */
    fun clearRecents() = setRecents(emptyList())

    /** Drop from Recent everything at or under [docUri], a deleted or trashed document. */
    private fun dropRecentsWithin(docUri: String) {
        val key = documentKey(docUri)
        autosaveScope.launch { setRecents(recentDocs.filterNot { com.xnotes.core.util.DocKeys.within(documentKey(it.uri), key) }) }
    }

    /**
     * What Recent shows under [treeUri]: each document as it is now, when it was opened and the folder it's in.
     * A document that has gone is dropped from Recent. One query per document; IO.
     */
    fun recentEntries(treeUri: String): List<RecentEntry> {
        val tree = android.net.Uri.parse(treeUri)
        val colors = HashMap<String, Map<String, Rgba>>()
        val folders = HashMap<String, String?>()
        val gone = ArrayList<String>()
        val out = recentDocs.filter { isUnderTree(it.uri, treeUri) }.mapNotNull { r ->
            val docId = runCatching { android.provider.DocumentsContract.getDocumentId(android.net.Uri.parse(r.uri)) }.getOrNull() ?: return@mapNotNull null
            val uri = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, docId)
            val stamp = stampOf(uri.toString()) ?: run { gone.add(r.uri); return@mapNotNull null }
            val name = queryDisplayName(uri) ?: r.name
            val parent = parentDocIdOf(uri.toString())
            val color = parent?.let { p ->
                colors.getOrPut(p) { findChildDocId(tree, p, SIDECAR_DIR, dir = true)?.let { readSidecarColors(tree, it) }.orEmpty() }[name]
            }
            val where = parent?.let { p -> folders.getOrPut(p) { queryDisplayName(android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, p)) } }
            val entry = BrowseEntry(
                name, uri.toString(), false, stamp.size.coerceAtLeast(0), stamp.modified.coerceAtLeast(0),
                createdStore.get(documentKey(uri.toString())) ?: 0, parent.orEmpty(), color,
            )
            RecentEntry(entry, r.opened, where)
        }
        if (gone.isNotEmpty()) autosaveScope.launch { setRecents(recentDocs.filterNot { it.uri in gone }) }
        return out.distinctBy { it.entry.documentUri }
    }

    /** The folder the explorer last showed, for Home to open on again. */
    val lastFolder: String? get() = settings.lastFolder

    fun setLastFolder(uri: String?) {
        if (settings.lastFolder == uri) return
        settings = settings.copy(lastFolder = uri)
        saveSettingsSoon()
    }

    /** The key a folder's own view is kept under: the same identity [documentKey] gives its document uri. */
    fun folderKey(treeUri: String, docId: String): String = "${android.net.Uri.parse(treeUri).authority}|$docId"

    /** The view folder [key] shows in. */
    fun viewFor(key: String?): ExplorerView =
        if (settings.prefs.perFolderViews && key != null) folderViews[key] ?: explorerView else explorerView

    /** Changes folder [key]'s view, or every folder's while views are shared; a null [view] resets it. */
    fun setView(key: String?, view: ExplorerView?) {
        if (settings.prefs.perFolderViews && key != null) {
            folderViews = if (view == null) folderViews - key else folderViews + (key to view)
            settings = settings.copy(folderViews = folderViews)
        } else {
            explorerView = view ?: ExplorerView()
            settings = settings.copy(explorerView = explorerView)
        }
        saveSettingsSoon()
    }

    /** Sets the layout every folder without a view of its own opens in (Preferences' default layout). */
    fun setDefaultLayout(layout: com.xnotes.settings.ExplorerLayout) {
        explorerView = explorerView.copy(layout = layout)
        settings = settings.copy(explorerView = explorerView)
        saveSettingsSoon()
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
        browseRoot?.let { old -> savePins(pinnedFolders.filterNot { isUnderTree(it.uri, old) }) }
        colorNames = emptyMap()
        browseRoot = null
        settings = settings.copy(browseRoot = null)
        settingsRepo.save(settings)
        browseCache.clear()
        rootNameCache.clear()
        viewStates.clear() // forget every remembered per-note view for the released folder
        createdStore.clear() // and every tracked creation time — the keys only meant anything for that folder
        docMeta.clear()
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

    /**
     * The stem a new note gets from the filename template, expanded against the current moment.
     * A template carrying `#` advances its number past every name in [takenLower] (lowercased,
     * with extension); one that doesn't is returned as-is, for the caller to de-duplicate.
     */
    fun newNoteStem(takenLower: Set<String>): String {
        val template = settings.prefs.newNoteNameTemplate
        val c = java.util.Calendar.getInstance()
        val stem = NameTemplate.expand(
            template,
            c.get(java.util.Calendar.YEAR),
            c.get(java.util.Calendar.MONTH) + 1,
            c.get(java.util.Calendar.DAY_OF_MONTH),
            c.get(java.util.Calendar.HOUR_OF_DAY),
            c.get(java.util.Calendar.MINUTE),
            c.get(java.util.Calendar.SECOND),
        )
        if (!NameTemplate.hasSequence(template)) return stem
        var n = 1
        while (DocumentKind.entries.any { "${NameTemplate.withSequence(stem, n).lowercase()}${it.suffix}" in takenLower }) n++
        return NameTemplate.withSequence(stem, n)
    }

    /** Resolves a document file name: blank -> the filename template; else ensures the extension
     *  for [kind] and avoids conflicts with a "_N" suffix. */
    private fun uniqueDocumentName(
        treeUri: String,
        parentDocId: String,
        raw: String,
        kind: com.xnotes.core.util.DocumentKind,
    ): String {
        val ext = kind.suffix
        val taken = browseChildren(treeUri, parentDocId).map { it.name.lowercase() }.toSet()
        val base = com.xnotes.core.util.DocumentKind.stripSuffix(raw.trim()).trim()
        if (base.isEmpty()) {
            val stem = newNoteStem(taken)
            if ("${stem.lowercase()}$ext" !in taken) return "$stem$ext"
            var n = 1
            while ("${stem.lowercase()}_$n$ext" in taken) n++
            return "${stem}_$n$ext"
        }
        if ("${base.lowercase()}$ext" !in taken) return "$base$ext"
        var n = 1
        while ("${base.lowercase()}_$n$ext" in taken) n++
        return "${base}_$n$ext"
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

    /** Creates a blank `.xcanvas` under [parentDocId]; returns its URI, or null. IO — call off-thread. */
    fun createBlankCanvasFile(treeUri: String, parentDocId: String, rawName: String): String? {
        val name = uniqueDocumentName(treeUri, parentDocId, rawName, com.xnotes.core.util.DocumentKind.CANVAS)
        return createNoteFile(treeUri, parentDocId, name) {
            canvasCodec.write(com.xnotes.core.infinite.InfiniteDocument(created = System.currentTimeMillis()), it)
        }
    }

    /** Creates a blank `.xnote` under [parentDocId]; returns its URI, or null. IO — call off-thread.
     *  [style] overrides the new-note default page style (template + colour) when non-null. */
    fun createBlankNoteFile(treeUri: String, parentDocId: String, rawName: String, style: PageStyle? = null): String? {
        val name = uniqueDocumentName(treeUri, parentDocId, rawName, com.xnotes.core.util.DocumentKind.NOTE)
        val blank = blankDocument().also { stampNewNoteDefaults(it); if (style != null) it.style = style }
        return createNoteFile(treeUri, parentDocId, name) { codec.write(blank, it) }
    }

    /** Imports the PDF at [pdfFile] into a new `.xnote` under [parentDocId] (named after [rawName]);
     *  returns its URI, or null. The PDF is streamed straight into the bundle, never held in RAM. IO. */
    fun createPdfNoteFile(treeUri: String, parentDocId: String, rawName: String, pdfFile: java.io.File): String? {
        val source = com.xnotes.platform.PdfSource.create(appContext, pdfFile) ?: return null
        val doc = com.xnotes.platform.PdfImporter.import(source, state.document.dpi) // doc.pdfFile = pdfFile
        stampNewNoteDefaults(doc)
        doc.created = System.currentTimeMillis()
        val name = uniqueDocumentName(treeUri, parentDocId, rawName, com.xnotes.core.util.DocumentKind.NOTE)
        val uri = createNoteFile(treeUri, parentDocId, name) { codec.write(doc, it) { importCancelled.get() } }
        source.close()
        return uri
    }

    /** Saves the picked `.xnote` at [file] into a new file under [parentDocId] (named after [rawName]);
     *  returns its URI, or null. Streamed copy, so a big embedded PDF never loads into RAM. IO. */
    fun createNoteFileFromFile(treeUri: String, parentDocId: String, rawName: String, file: java.io.File): String? {
        runCatching { java.io.FileInputStream(file).use { codec.read(it) } }.getOrNull() ?: return null // validate it's a real .xnote (no PDF extraction)
        val name = uniqueDocumentName(treeUri, parentDocId, rawName, com.xnotes.core.util.DocumentKind.NOTE)
        return createNoteFile(treeUri, parentDocId, name) { out -> copyStream(java.io.FileInputStream(file), out) { importCancelled.get() } }
    }

    /** Imports the image at [imgFile] into a new single-page `.xnote` whose page matches the image,
     *  the image filling it edge to edge. Returns its URI, or null. IO — call off-thread. */
    fun createImageNoteFile(treeUri: String, parentDocId: String, rawName: String, imgFile: java.io.File): String? {
        val doc = imageDocument(imgFile) ?: return null
        val name = uniqueDocumentName(treeUri, parentDocId, rawName, com.xnotes.core.util.DocumentKind.NOTE)
        return createNoteFile(treeUri, parentDocId, name) { codec.write(doc, it) { importCancelled.get() } }
    }

    /** A page [pageW] wide as tall as the image at [imgFile] needs (within 1:4 either way), the image
     *  filling it edge to edge; null when the file is not an image. IO. */
    private fun imagePage(imgFile: java.io.File, pageW: Double): Page? {
        val size = imageCodec.probeFile(imgFile.path) ?: return null
        if (size.width <= 0 || size.height <= 0) return null
        val pageH = (pageW * size.height.toDouble() / size.width).coerceIn(pageW * 0.25, pageW * 4.0)
        val page = Page(pageW, pageH)
        page.items.add(ImageItem(ImageData(imgFile, size.width, size.height), Rect(0.0, 0.0, pageW, pageH)))
        return page
    }

    /** A new single-page note holding the image at [imgFile] edge to edge. IO. */
    private fun imageDocument(imgFile: java.io.File): Document? {
        val page = imagePage(imgFile, settings.prefs.newPagePixels().first) ?: return null
        val doc = Document(dpi = state.document.dpi)
        doc.pages.add(page)
        stampNewNoteDefaults(doc)
        return doc
    }

    /** Imports [file] (txt/csv/rtf/html/md/docx/xlsx/epub) as a new `.xnote` whose flow text holds the
     *  content, laid out across as many pages as it needs. Markdown is parsed into rich text (headings,
     *  bold/italic, lists, code); the other formats are extracted as plain paragraphs. Returns its URI. IO. */
    fun createTextNoteFile(treeUri: String, parentDocId: String, rawName: String, file: java.io.File, sourceName: String, mime: String): String? {
        val doc = textDocument(file, sourceName.ifEmpty { rawName }, mime)
        val docName = uniqueDocumentName(treeUri, parentDocId, rawName, com.xnotes.core.util.DocumentKind.NOTE)
        return createNoteFile(treeUri, parentDocId, docName) { codec.write(doc, it) { importCancelled.get() } }
    }

    /** A new note whose flow text holds [file]'s content (named [name] for its type), paginated. IO. */
    private fun textDocument(file: java.io.File, name: String, mime: String): Document {
        val ext = name.substringAfterLast('.', "").lowercase()
        val isMarkdown = ext == "md" || ext == "markdown" || mime == "text/markdown" || mime == "text/x-markdown"
        val doc = blankDocument()
        stampNewNoteDefaults(doc)
        val paragraphs: List<com.xnotes.core.text.Paragraph> = if (isMarkdown) {
            val text = runCatching { file.readText() }.getOrDefault("")
            com.xnotes.core.text.MarkdownParser.parse(text, doc.flow.defaultSizePt)
        } else {
            com.xnotes.platform.DocImport.extract(file, name, mime)
                .map { com.xnotes.core.text.Paragraph().apply { runs.add(com.xnotes.core.text.Run(it)) } }
        }
        if (paragraphs.isNotEmpty()) {
            doc.flow.paragraphs.clear()
            doc.flow.paragraphs.addAll(paragraphs)
            paginateFlow(doc)
        }
        return doc
    }

    /** Append pages (sized like the last) until [doc]'s flow text stops overflowing past the real pages. */
    private fun paginateFlow(doc: Document) {
        if (doc.flow.isEmpty) return
        var guard = 0
        while (guard++ < 4000) {
            val frame = flowLayout.layout(doc.flow, doc.pages.map { com.xnotes.core.text.PageBox(it.width, it.height) }, doc.dpi)
            val need = frame.extraPagesNeeded
            if (need <= 0) break
            val last = doc.pages.last()
            repeat(need) { doc.pages.add(Page(last.width, last.height)) }
        }
    }

    // --- putting one note or file into another ---

    /**
     * The picked [staged] file built into a note whose files (source PDF, images) live in this
     * editor's own dirs, so they outlive the staged copy; null when it will not read. IO.
     */
    private fun buildImportedDocument(kind: ImportKind, staged: java.io.File, sourceName: String, mime: String): Document? {
        fun keep(dir: java.io.File, suffix: String) =
            java.io.File.createTempFile("imp", suffix, dir).also { staged.copyTo(it, overwrite = true) }
        return when (kind) {
            ImportKind.PDF -> {
                val file = keep(pdfDir, ".pdf")
                val source = com.xnotes.platform.PdfSource.create(appContext, file) ?: return null
                try { com.xnotes.platform.PdfImporter.import(source, state.document.dpi) } finally { source.close() }
            }
            ImportKind.IMAGE -> imageDocument(keep(imageDir, "." + sourceName.substringAfterLast('.', "img")))
            ImportKind.TEXT -> textDocument(staged, sourceName, mime)
            ImportKind.OPEN -> runCatching { java.io.FileInputStream(staged).use { codec.read(it, pdfDir, imageDir) } }.getOrNull()
        }
    }

    /** Where [appendDocument] finds the source's PDF pages: [file] becomes the note's PDF (the source's,
     *  or both merged, the source's pages [offset] on), or else [flatten] is baked into images. */
    private class PdfPlan(val file: java.io.File?, val offset: Int, val flatten: java.io.File?)

    /** Merge [src]'s PDF into [target]'s when both have one. IO. */
    private fun planPdf(target: java.io.File?, src: Document): PdfPlan {
        val srcPdf = src.pdfFile
        if (srcPdf == null || src.pages.none { it.pdfPage != null }) return PdfPlan(null, 0, null)
        if (target == null) return PdfPlan(srcPdf, 0, null)
        val out = java.io.File.createTempFile("merged", ".pdf", pdfDir)
        val offset = com.xnotes.platform.PdfMerge.merge(appContext, target, srcPdf, out) ?: return PdfPlan(null, 0, srcPdf)
        return PdfPlan(out, offset, null)
    }

    /** Add the image read from [uri] (named [name]) as a page of its own before page [at], undoably. */
    suspend fun insertImagePage(uri: android.net.Uri, name: String, at: Int): Boolean {
        val doc = state.document
        val width = doc.pages.getOrNull((at - 1).coerceAtLeast(0))?.width ?: settings.prefs.newPagePixels().first
        val page = withContext(Dispatchers.IO) {
            runCatching {
                val file = java.io.File.createTempFile("page", "." + name.substringAfterLast('.', "img"), imageDir)
                appContext.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } } ?: return@runCatching null
                imagePage(file, width)
            }.getOrNull()
        } ?: return false
        if (state.document !== doc) return false
        val i = at.coerceIn(0, doc.pages.size)
        doc.pages.add(i, page)
        history.push(AddPage(doc, page, i))
        afterPageEdit()
        goToPage(i)
        return true
    }

    /** Copy the picked file at [uri] in and build it as a note; null when it will not read. IO. */
    private fun readImport(uri: String, name: String, mime: String): Document? {
        val staged = runCatching {
            appContext.contentResolver.openInputStream(android.net.Uri.parse(uri))?.let { stageImport(it) }
        }.getOrNull() ?: return null
        return try {
            buildImportedDocument(ImportKind.classify(name, mime), staged, name, mime)
        } finally {
            staged.delete()
        }
    }

    /** Put the file at [uri] into the open note before page [at], as one undoable step. */
    suspend fun importIntoOpenNote(uri: String, name: String, mime: String, at: Int): Boolean {
        importCancelled.set(false)
        importing = true
        val doc = state.document
        val prepared = try {
            withContext(Dispatchers.IO) { readImport(uri, name, mime)?.let { it to planPdf(doc.pdfFile, it) } }
        } finally {
            importing = false
        }
        val (src, plan) = prepared ?: return false
        if (state.document !== doc) return false
        val pdfBefore = doc.pdfFile
        history.push(appendDocument(doc, src, at, plan) { rebuildPdfSource() })
        if (doc.pdfFile != pdfBefore) rebuildPdfSource()
        afterPageEdit()
        goToPage(at.coerceIn(0, doc.pages.lastIndex))
        return true
    }

    /** Append the file at [uri] to the end of the note at [noteUri], open or not. */
    suspend fun importIntoNoteFile(noteUri: String, uri: String, name: String, mime: String): Boolean {
        val open = state.document.path
        if (open != null && isSameDocument(open, noteUri)) return importIntoOpenNote(uri, name, mime, state.document.pages.size)
        importCancelled.set(false)
        importing = true
        return try {
            withContext(Dispatchers.IO) {
                val target = readNote(noteUri) ?: return@withContext false
                val src = readImport(uri, name, mime) ?: return@withContext false
                appendDocument(target, src, target.pages.size, planPdf(target.pdfFile, src)) {}
                writeDocument(noteUri, target)
            }
        } finally {
            importing = false
        }
    }

    /** Join the notes at [uris], in order, into a new note named [rawName] under [parentDocId]; its URI or null. */
    suspend fun mergeNotes(uris: List<String>, treeUri: String, parentDocId: String, rawName: String): String? {
        if (uris.size < 2) return null
        importCancelled.set(false)
        importing = true
        return try {
            withContext(Dispatchers.IO) {
                val docs = uris.map { readNote(it) ?: return@withContext null }
                val base = docs.first()
                for (next in docs.drop(1)) appendDocument(base, next, base.pages.size, planPdf(base.pdfFile, next)) {}
                base.path = null
                base.displayName = null
                base.created = System.currentTimeMillis()
                val name = uniqueDocumentName(treeUri, parentDocId, rawName, com.xnotes.core.util.DocumentKind.NOTE)
                createNoteFile(treeUri, parentDocId, name) { codec.write(base, it) { importCancelled.get() } }
            }
        } finally {
            importing = false
        }
    }

    private fun readNote(uri: String): Document? = runCatching {
        appContext.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { codec.read(it, pdfDir, imageDir) }
    }.getOrNull()

    /** Encode [doc] in full first, then replace the file at [uri], so a failed encode never truncates it. IO. */
    private fun writeDocument(uri: String, doc: Document): Boolean = runCatching {
        val tmp = java.io.File.createTempFile("merge", ".xnote", saveTmpDir)
        try {
            java.io.FileOutputStream(tmp).use { codec.write(doc, it) }
            appContext.contentResolver.openOutputStream(android.net.Uri.parse(uri), "wt")?.use { out ->
                java.io.FileInputStream(tmp).use { it.copyTo(out) }
            } ?: return@runCatching false
            true
        } finally {
            tmp.delete()
        }
    }.getOrDefault(false)

    /**
     * Put [src]'s pages into [target] before page [at], as one undoable command (already applied).
     * A source PDF is merged into the target's so its pages stay vector; the source's flowing text
     * follows the target's and is pushed down to start on the first page put in. IO for PDF merges.
     */
    private fun appendDocument(target: Document, src: Document, at: Int, plan: PdfPlan, onPdfSwap: () -> Unit): Command {
        val cmds = ArrayList<Command>()
        val insertAt = at.coerceIn(0, target.pages.size)
        if (plan.file != null && plan.file != target.pdfFile) {
            cmds.add(com.xnotes.core.history.SetPdfFile(target, target.pdfFile, plan.file, onPdfSwap))
            target.pdfFile = plan.file
        }
        src.pages.forEachIndexed { k, p ->
            val page = p.deepCopy(textMeasurer)
            page.style = page.style.over(src.style)
            if (plan.flatten != null) flattenForeignPdfPage(page, plan.flatten) else page.pdfPage = page.pdfPage?.let { it + plan.offset }
            target.pages.add(insertAt + k, page)
            cmds.add(AddPage(target, page, insertAt + k))
        }
        for (b in target.bookmarks) if (b.page >= insertAt) b.page += src.pages.size
        src.bookmarks.forEach { target.bookmarks.add(Bookmark(it.page + insertAt, it.label)) }
        target.templates = src.templates + target.templates
        if (!src.flow.isEmpty) cmds.addAll(appendFlow(target, src, insertAt))
        return CompositeCommand(cmds)
    }

    /** Follow [target]'s flowing text with [src]'s, padded with blank lines so it starts on page
     *  [firstPage], then add pages for whatever overflows. Returns the applied commands. */
    private fun appendFlow(target: Document, src: Document, firstPage: Int): List<Command> {
        val flow = target.flow
        val paras = flow.paragraphs
        val removed = if (flow.isEmpty) paras.toList() else emptyList()
        val index = paras.size - removed.size
        val incoming = src.flow.paragraphs.map { it.deepCopy() }
        // Try the text in place and add blank lines ahead of it until its first line reaches its page:
        // most of a page at a time while it is pages short, then a line at a time.
        repeat(removed.size) { paras.removeAt(index) }
        paras.addAll(index, incoming)
        flow.touch()
        val pad = ArrayList<com.xnotes.core.text.Paragraph>()
        var guard = 0
        while (guard++ < 400) {
            val frame = flowLayout.layout(flow, target.pages.map { com.xnotes.core.text.PageBox(it.width, it.height) }, target.dpi)
            val (page, caret) = frame.caretRect(FlowPos(index + pad.size, 0)) ?: break
            if (page >= firstPage) break
            val pageH = target.pages.getOrNull(page)?.height ?: break
            val lines = if (page < firstPage - 1) ((pageH / caret.h.coerceAtLeast(1.0)) * 0.9).toInt().coerceAtLeast(1) else 1
            val blanks = List(lines) { com.xnotes.core.text.Paragraph() }
            paras.addAll(index, blanks)
            pad.addAll(blanks)
            flow.touch()
        }
        // Put the flow back and apply it all as one splice, so one undo takes the padding with the text.
        repeat(pad.size + incoming.size) { paras.removeAt(index) }
        paras.addAll(index, removed)
        val splice = com.xnotes.core.history.FlowSplice(flow, index, removed, pad + incoming).also { it.redo() }
        val cmds = arrayListOf<Command>(splice)
        val before = target.pages.size
        paginateFlow(target)
        for (i in before until target.pages.size) cmds.add(AddPage(target, target.pages[i], i))
        return cmds
    }

    private fun PageStyle.over(base: PageStyle) = PageStyle(
        pageColor = pageColor ?: base.pageColor,
        template = template ?: base.template,
        patternColor = patternColor ?: base.patternColor,
        spacing = spacing ?: base.spacing,
        accentColor = accentColor ?: base.accentColor,
        params = params ?: base.params,
        colors = colors ?: base.colors,
    )

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

    /** A picked PDF (referenced by content [uri]) now awaits a name before being saved into the
     *  folder. The file is deliberately **not** copied yet — that happens at [commitImport], under the
     *  import loader — so the name dialog can appear instantly instead of after a big copy. */
    fun requestImport(
        kind: ImportKind, defaultName: String, uri: String, sourceName: String = "", mime: String = "",
        parentDocId: String? = null, title: String? = null,
    ) {
        pendingImport = PendingImport(kind, defaultName, uri, sourceName, mime, parentDocId, title)
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
            createImportedNoteFile(treeUri, parentDocId, rawName, staged, pending)
        } finally {
            staged.delete()
        }
        if (uri != null || importCancelled.get()) {
            pendingImport = null
        }
        return uri
    }

    private fun createImportedNoteFile(treeUri: String, parentDocId: String, rawName: String, staged: java.io.File, item: PendingImport): String? =
        when (item.kind) {
            ImportKind.PDF -> createPdfNoteFile(treeUri, parentDocId, rawName, staged)
            ImportKind.IMAGE -> createImageNoteFile(treeUri, parentDocId, rawName, staged)
            ImportKind.TEXT -> createTextNoteFile(treeUri, parentDocId, rawName, staged, item.sourceName, item.mime)
            ImportKind.OPEN -> createNoteFileFromFile(treeUri, parentDocId, rawName, staged)
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

    /** Queues a multi-file PDF pick; the explorer commits it into whichever folder it is showing. */
    fun requestImports(items: List<PendingImport>) {
        pendingImports = items
    }

    /** Imports every queued PDF into [parentDocId] one at a time; returns how many landed. Each file
     *  is staged to its own temp, written into its `.xnote`, then dropped before the next is opened,
     *  so a batch costs the same memory as a single import however long the list is. Names come from
     *  the source files. Cancel stops after the file in flight, discarding only that one; whatever
     *  already landed stays. [importProgress] drives the dialog's count. IO — runs off-thread. */
    suspend fun commitImportsAsync(treeUri: String, parentDocId: String): Int {
        val items = pendingImports
        if (items.isEmpty()) return 0
        importCancelled.set(false)
        importing = true
        importProgress = ImportProgress(0, items.size)
        return try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                var done = 0
                for (item in items) {
                    if (importCancelled.get()) break
                    val staged = runCatching {
                        appContext.contentResolver.openInputStream(android.net.Uri.parse(item.uri))?.let { stageImport(it) }
                    }.getOrNull()
                    if (staged != null) {
                        try {
                            if (createImportedNoteFile(treeUri, parentDocId, item.defaultName, staged, item) != null) done++
                        } finally {
                            staged.delete()
                        }
                    }
                    importProgress = ImportProgress(done, items.size)
                }
                done
            }
        } finally {
            pendingImports = emptyList()
            importProgress = null
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
        onDocumentMoved(docUri, resultUri, newName)
        return true
    }

    /** Carry everything keyed by a document's identity across a rename or move; [newName] is set for a rename. */
    private fun onDocumentMoved(oldUri: String, newUri: String, newName: String? = null) {
        val from = documentKey(oldUri)
        val to = documentKey(newUri)
        if (oldUri != newUri) {
            createdStore.rekeyTree(from, to)
            viewStates.rekeyTree(from, to)
            docMeta.rekeyTree(from, to)
            invalidateThumb(oldUri)
        }
        val tree = browseRoot?.let { android.net.Uri.parse(it) } ?: return
        fun follow(uri: String): String? {
            val key = com.xnotes.core.util.DocKeys.moved(documentKey(uri), from, to) ?: return null
            return android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, key.substringAfter('|')).toString()
        }
        autosaveScope.launch {
            setRecents(recentDocs.map { r -> follow(r.uri)?.let { r.copy(uri = it) } ?: r })
            val movedViews = folderViews.mapKeys { (k, _) -> com.xnotes.core.util.DocKeys.moved(k, from, to) ?: k }
            if (movedViews != folderViews) {
                folderViews = movedViews
                settings = settings.copy(folderViews = movedViews)
                saveSettingsSoon()
            }
            settings.lastFolder?.let { last -> follow(last)?.let { setLastFolder(it) } }
            savePins(pinnedFolders.map { pin ->
                val key = com.xnotes.core.util.DocKeys.moved(documentKey(pin.uri), from, to) ?: return@map pin
                val uri = if (key == documentKey(pin.uri)) pin.uri
                else android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, key.substringAfter('|')).toString()
                com.xnotes.settings.PinnedFolder(uri, if (key == to && newName != null) newName else pin.name)
            })
        }
    }

    /** The created time for a file just read: its own when it records one, else the earliest clue; cached either way. IO. */
    private fun settleCreated(uri: String, own: Long?): Long? {
        val key = documentKey(uri)
        val created = own ?: com.xnotes.core.util.DocKeys.inferCreated(createdStore.get(key), stampOf(uri)?.modified)
        created?.let { createdStore.put(key, it) }
        return created
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

        // Forget the deleted note's remembered zoom/scroll — and every note's under a deleted folder.
        // View-state and creation-time keys are document identities ("$auth|$id", see documentKey), so
        // match them by prefix. This runs whether or not the note is on screen, so a same-named file later
        // created in this folder (the local provider reuses the path-derived id) starts at fit-width
        // instead of inheriting the dead note's view.
        val keyPrefix = "$auth|$delId"
        viewStates.removeMatching { it == keyPrefix || it.startsWith("$keyPrefix/") }

        detachIfOpen(docUri)

        // Forget the deleted item's tracked creation time (and the whole subtree's, for a folder),
        // matched the same way as the view state, and drop its cached tile.
        createdStore.removeMatching { it == keyPrefix || it.startsWith("$keyPrefix/") }
        docMeta.removeMatching { com.xnotes.core.util.DocKeys.within(it, keyPrefix) }
        invalidateThumb(docUri)
        dropPinsWithin(docUri)
        dropRecentsWithin(docUri)
    }

    /** Drop every pin at or under [docUri], a deleted or trashed folder. */
    private fun dropPinsWithin(docUri: String) {
        val key = documentKey(docUri)
        autosaveScope.launch { savePins(pinnedFolders.filterNot { com.xnotes.core.util.DocKeys.within(documentKey(it.uri), key) }) }
    }

    /**
     * The note on screen was just deleted or trashed. Detach it at once (cancel autosave, drop its path, mark it
     * clean) so nothing can rewrite the file or prompt to "save" it back, then drop the document itself for a
     * fresh blank note on the main thread. The identity guard skips that reset if another note opened meanwhile.
     */
    private fun detachIfOpen(docUri: String) {
        val target = android.net.Uri.parse(docUri)
        val delId = runCatching { android.provider.DocumentsContract.getDocumentId(target) }.getOrNull() ?: return
        val auth = target.authority
        fun matches(uri: String): Boolean {
            val u = android.net.Uri.parse(uri)
            val rid = runCatching { android.provider.DocumentsContract.getDocumentId(u) }.getOrNull() ?: return false
            return u.authority == auth && (rid == delId || rid.startsWith("$delId/"))
        }
        if (currentUri?.let { matches(it) } == true) {
            val deleted = state.document
            noteDebounceJob?.cancel()
            autosaveUri = null
            deleted.path = null
            deleted.dirty = false
            dirty = false
            // Only reset to a fresh page if the deleted note is actually on screen; while on backstage
            // (noteOpen == false) the detached buffer is left as-is so we don't pop into a blank editor.
            autosaveScope.launch { if (state.document === deleted && noteOpen) newNote() }
        }
    }

    /**
     * Copies [sourceUri] into the folder [targetParentDocId] within [treeUri]. On a name clash — most
     * often pasting a copy into the same folder — a file is duplicated under a free "… copy" name
     * rather than failing. (A folder can't be byte-streamed, so a folder name clash still fails.)
     * IO, call off-thread.
     */
    fun copyDocumentInto(treeUri: String, sourceUri: String, targetParentDocId: String): Boolean =
        copyDocumentAs(treeUri, sourceUri, targetParentDocId) != null

    /** [copyDocumentInto], returning the copy's name, or null when it failed. */
    private fun copyDocumentAs(treeUri: String, sourceUri: String, targetParentDocId: String): String? = runCatching {
        val tree = android.net.Uri.parse(treeUri)
        val target = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, targetParentDocId)
        val src = android.net.Uri.parse(sourceUri)
        val srcName = queryDisplayName(src) ?: return@runCatching null
        // Native copy first: it succeeds outright when there's no name clash (e.g. a different folder).
        // Wrapped on its own, because providers *throw* (rather than return null) on a same-name clash —
        // we must catch that here so it falls through to making a renamed duplicate below instead of
        // failing the whole paste.
        val direct = runCatching {
            android.provider.DocumentsContract.copyDocument(appContext.contentResolver, src, target)
        }.getOrNull()
        if (direct != null) return@runCatching queryDisplayName(direct) ?: srcName
        // The clash case (usually pasting into the same folder): duplicate under a free "… copy" name.
        val isDir = appContext.contentResolver.getType(src) == android.provider.DocumentsContract.Document.MIME_TYPE_DIR
        val taken = browseChildren(treeUri, targetParentDocId).mapTo(HashSet()) { it.name.lowercase() }
        val newName = uniqueCopyName(srcName, taken, splitExtension = !isDir)
        if (isDir) {
            newName.takeIf { copyFolderAs(treeUri, src, target, newName) }
        } else {
            val newUri = android.provider.DocumentsContract.createDocument(
                appContext.contentResolver, target, "application/octet-stream", newName,
            ) ?: return@runCatching null
            val copied = runCatching {
                appContext.contentResolver.openInputStream(src)?.use { input ->
                    appContext.contentResolver.openOutputStream(newUri, "wt")?.use { output -> input.copyTo(output); true } ?: false
                } ?: false
            }.getOrDefault(false)
            if (!copied) { runCatching { android.provider.DocumentsContract.deleteDocument(appContext.contentResolver, newUri) }; return@runCatching null }
            queryDisplayName(newUri) ?: newName
        }
    }.getOrNull()

    /** Copies [entries] into [targetParentDocId], colour codes included; returns how many were copied. IO. */
    fun copyEntriesInto(treeUri: String, entries: List<BrowseEntry>, targetParentDocId: String): Int {
        val colors = HashMap<String, Rgba?>()
        var copied = 0
        for (e in entries) {
            val name = copyDocumentAs(treeUri, e.documentUri, targetParentDocId) ?: continue
            copied++
            e.color?.let { colors[name] = it }
        }
        setItemColors(treeUri, targetParentDocId, colors)
        return copied
    }

    /**
     * Moves [entries] into [targetParentDocId], each from the folder it was listed in, carrying their colour
     * codes from the old folder's sidecar to the new one's. A folder never moves into itself. Returns how many
     * ended up in the target. IO.
     */
    fun moveEntriesInto(treeUri: String, entries: List<BrowseEntry>, targetParentDocId: String): Int {
        val carried = HashMap<String, Rgba?>()
        val cleared = HashMap<String, HashMap<String, Rgba?>>()
        var moved = 0
        for (e in entries) {
            if (e.parentDocId == targetParentDocId) { moved++; continue }
            if (e.isDir && com.xnotes.core.util.DocKeys.within(targetParentDocId, browseDocId(e.documentUri))) continue
            if (!moveDocumentInto(treeUri, e.documentUri, e.parentDocId, targetParentDocId)) continue
            moved++
            val c = e.color ?: continue
            carried[e.name] = c
            cleared.getOrPut(e.parentDocId) { HashMap() }[e.name] = null
        }
        for ((parent, changes) in cleared) setItemColors(treeUri, parent, changes)
        setItemColors(treeUri, targetParentDocId, carried)
        return moved
    }

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
        newUri?.let { onDocumentMoved(sourceUri, it.toString()) }
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

    // --- divergence guard (something else rewrote the file underneath the open editor) ---

    /** Size + last-modified of a SAF document: the evidence that a file did or did not move under us. */
    private data class DocStamp(val size: Long, val modified: Long)

    /** The stamp the note's autosave file carried after this editor last wrote it. */
    @Volatile private var lastNoteStamp: DocStamp? = null

    /** The canvas sibling of [lastNoteStamp]. */
    @Volatile private var lastCanvasStamp: DocStamp? = null

    /** Where a fork landed: the new file's uri, and the name to show the user. */
    private class Fork(val uri: String, val name: String)

    /** Where a guarded save landed; [fork] is null when it wrote the file in place as usual. */
    private class SaveResult(val uri: String, val fork: Fork?)

    /** Size + mtime of the document at [uri], or null when the provider will not report either. */
    private fun stampOf(uri: String): DocStamp? = runCatching {
        appContext.contentResolver.query(
            android.net.Uri.parse(uri),
            arrayOf(
                android.provider.OpenableColumns.SIZE,
                android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ),
            null, null, null,
        )?.use { c ->
            if (!c.moveToFirst()) return@use null
            val size = if (c.isNull(0)) -1L else c.getLong(0)
            val modified = if (c.isNull(1)) -1L else c.getLong(1)
            if (size < 0L && modified < 0L) null else DocStamp(size, modified)
        }
    }.getOrNull()

    /**
     * Has something else rewritten [uri] since this editor last wrote it? A folder-sync app pulling
     * in the other device's copy does exactly that, and so does the other split pane holding the same
     * note. Writing in place would then throw away whatever they put there, with no error and no
     * conflict copy, so the caller forks instead.
     *
     * An unreadable stamp is **not** evidence. A provider that won't report size or mtime must never
     * trigger a fork: a spurious duplicate file is a worse default than the plain write.
     */
    private fun changedUnderneath(uri: String, known: DocStamp?): Boolean {
        if (known == null) return false
        val now = stampOf(uri) ?: return false
        return now != known
    }

    /** The parent folder's document id for the file at [uri], or null when it has none. */
    private fun parentDocIdOf(uri: String): String? {
        val id = runCatching {
            android.provider.DocumentsContract.getDocumentId(android.net.Uri.parse(uri))
        }.getOrNull() ?: return null
        return id.substringBeforeLast('/', "").ifEmpty { null }
    }

    /** The name a fork of [title] takes: the note's own name, de-duplicated the usual way. */
    private fun forkName(root: String, parentId: String, title: String, kind: com.xnotes.core.util.DocumentKind): String =
        uniqueDocumentName(root, parentId, com.xnotes.core.util.DocumentKind.stripSuffix(title), kind)

    /**
     * Write [doc] to a new sibling of [uri], leaving the file that changed underneath us untouched.
     * Both versions survive and both sync onward; nothing is merged and nothing is overwritten.
     */
    private fun forkNote(uri: String, doc: Document, title: String): Fork? {
        val root = browseRoot ?: return null
        val parentId = parentDocIdOf(uri) ?: return null
        val name = forkName(root, parentId, title, com.xnotes.core.util.DocumentKind.NOTE)
        val forked = createNoteFile(root, parentId, name) { codec.write(doc, it) } ?: return null
        lastNoteStamp = stampOf(forked)
        return Fork(forked, name)
    }

    /** The canvas sibling of [forkNote]. */
    private fun forkCanvas(uri: String, doc: com.xnotes.core.infinite.InfiniteDocument, title: String): Fork? {
        val root = browseRoot ?: return null
        val parentId = parentDocIdOf(uri) ?: return null
        val name = forkName(root, parentId, title, com.xnotes.core.util.DocumentKind.CANVAS)
        val forked = createNoteFile(root, parentId, name) { canvasCodec.write(doc, it) } ?: return null
        lastCanvasStamp = stampOf(forked)
        return Fork(forked, name)
    }

    /** Autosave [doc] to [uri], forking instead of overwriting when the file moved under us. IO. */
    private fun saveNoteGuarded(uri: String, doc: Document, title: String): SaveResult? = synchronized(saveLock) {
        if (changedUnderneath(uri, lastNoteStamp)) {
            val fork = forkNote(uri, doc, title) ?: return null
            return SaveResult(fork.uri, fork)
        }
        if (!writeNoteSafely(uri, doc)) return null
        SaveResult(uri, null)
    }

    /** The canvas sibling of [saveNoteGuarded]. IO. */
    private fun saveCanvasGuarded(
        uri: String,
        doc: com.xnotes.core.infinite.InfiniteDocument,
        title: String,
    ): SaveResult? = synchronized(saveLock) {
        if (changedUnderneath(uri, lastCanvasStamp)) {
            val fork = forkCanvas(uri, doc, title) ?: return null
            return SaveResult(fork.uri, fork)
        }
        if (!writeCanvasSafely(uri, doc)) return null
        SaveResult(uri, null)
    }

    /** Point the open note at the copy it just forked into, and say so. Main thread. */
    private fun adoptNoteFork(fork: Fork) {
        state.document.path = fork.uri
        state.document.displayName = fork.name
        autosaveUri = fork.uri
        refreshContent()
        message = appContext.getString(R.string.note_forked, com.xnotes.core.util.DocumentKind.stripSuffix(fork.name))
    }

    /** The canvas sibling of [adoptNoteFork]. Main thread. */
    private fun adoptCanvasFork(fork: Fork) {
        val canvas = infiniteOrNull ?: return
        canvas.document.path = fork.uri
        canvas.document.displayName = fork.name
        canvasAutosaveUri = fork.uri
        refreshContent()
        message = appContext.getString(R.string.canvas_forked, com.xnotes.core.util.DocumentKind.stripSuffix(fork.name))
    }

    private fun maybeBindAutosave(uri: String?) {
        autosaveUri = if (uri != null && browseRoot?.let { isUnderTree(uri, it) } == true) uri else null
        lastNoteStamp = autosaveUri?.let { stampOf(it) }
    }

    /**
     * Write [doc] into its SAF file at [uri] without ever truncating a good note on a failed encode.
     * The bytes are serialized to a private temp first, so a missing embedded PDF, an OOM, or any
     * codec error aborts *before* the destination is opened; only a complete temp is copied over.
     * The old path opened the destination in "wt" (truncate) and encoded straight into it, so a
     * throw mid-encode left the note as 0 bytes. Returns true only when [uri] now holds the note.
     */
    private fun writeNoteSafely(uri: String, doc: Document): Boolean = synchronized(saveLock) {
        // Replace only the manifest when everything ahead of it is already right. A splice that
        // fails part way leaves a file that is not a valid zip, and falling through to the full
        // rewrite below is what puts it right, so this must stay ordered this way.
        if (writeNoteInPlace(uri, doc)) return true
        runCatching {
            val tmp = java.io.File.createTempFile("save", ".xnote", saveTmpDir)
            try {
                val encodeStart = System.nanoTime()
                val timing = com.xnotes.format.DocumentCodec.WriteTiming()
                java.io.FileOutputStream(tmp).use { codec.write(doc, it, timing = timing) }
                val copyStart = System.nanoTime()
                val out = appContext.contentResolver.openOutputStream(android.net.Uri.parse(uri), "wt")
                    ?: return@runCatching false
                out.use { java.io.FileInputStream(tmp).use { input -> input.copyTo(it, copyBuffer) } }
                state.lastSaveEncodeMs = msBetween(encodeStart, copyStart) // all four for the debug overlay
                state.lastSaveCopyMs = msSince(copyStart)
                state.lastSaveManifestMs = timing.manifestMs
                state.lastSaveAssetsMs = timing.assetsMs
                state.lastSaveDeflateMs = timing.deflateMs
                state.lastSaveManifestBytes = timing.manifestBytes
                state.lastSaveBytes = tmp.length() // live file size for the debug overlay
                lastNoteStamp = stampOf(uri) // read back what the provider reports, not what we wrote
                true
            } finally {
                tmp.delete()
            }
        }.getOrDefault(false)
    }

    /** Elapsed milliseconds since a [System.nanoTime] mark, for the debug overlay's save timings. */
    private fun msSince(startNs: Long): Long = msBetween(startNs, System.nanoTime())

    private fun msBetween(startNs: Long, endNs: Long): Long = (endNs - startNs) / 1_000_000L

    /**
     * Save by replacing only the tail of the note already at [uri]: its manifest, and the flow
     * entry with it. Everything ahead of them is left exactly where it lies, which for a note built
     * from a PDF is nearly the whole file. See [com.xnotes.format.ZipTail].
     *
     * Returns false, having written nothing, when this is not a bundle it can patch: a different
     * set of assets, a provider that will not hand out a writable handle, a zip with anything
     * unexpected in it, or too little ahead of the manifest for the splice to be worth its risk.
     */
    private fun writeNoteInPlace(uri: String, doc: Document): Boolean = runCatching {
        val assets = codec.imageAssets(doc)
        val expected = ArrayList<Pair<String, Long>>(assets.size + 1)
        for ((name, file) in assets) expected.add(name to file.length())
        doc.pdfFile?.let { expected.add("assets/source.pdf" to it.length()) }
        if (expected.isEmpty()) return@runCatching false

        val target = android.net.Uri.parse(uri)
        // Streams built on a descriptor the provider owns are never closed here: closing one closes
        // that descriptor, and the ParcelFileDescriptor would then close a number the system may
        // already have handed to something else. Only the descriptor itself is closed, once.
        val existing = appContext.contentResolver.openFileDescriptor(target, "r")?.use { pfd ->
            com.xnotes.format.ZipTail.read(java.io.FileInputStream(pfd.fileDescriptor).channel)
        } ?: return@runCatching false

        // Every asset has to still be the same file in the same place, or the manifest is not the
        // only thing that changed and the whole bundle has to be rebuilt.
        val ordered = existing.entries.sortedBy { it.localOffset }
        if (ordered.size <= expected.size) return@runCatching false
        for (i in expected.indices) {
            val e = ordered[i]
            if (e.name != expected[i].first || e.size != expected[i].second) return@runCatching false
            if (e.method != java.util.zip.ZipEntry.STORED) return@runCatching false
        }
        val tailStart = ordered[expected.size].localOffset
        if (tailStart < MIN_SPLICE_BYTES) return@runCatching false

        val encodeStart = System.nanoTime()
        val timing = com.xnotes.format.DocumentCodec.WriteTiming()
        val tmp = java.io.File.createTempFile("tail", ".zip", saveTmpDir)
        try {
            // The new tail is built as an ordinary little zip of its own, so java.util.zip keeps
            // doing the deflating, the checksums and the entry headers.
            java.io.FileOutputStream(tmp).use { out ->
                java.util.zip.ZipOutputStream(out).use { zos ->
                    zos.setLevel(java.util.zip.Deflater.BEST_SPEED)
                    codec.writeTail(zos, doc, assets, timing)
                }
            }
            val spliceStart = System.nanoTime()
            val length = java.io.FileInputStream(tmp).use { tail ->
                val tailDir = com.xnotes.format.ZipTail.read(tail.channel)
                    ?: return@runCatching false
                appContext.contentResolver.openFileDescriptor(target, "rw")?.use { pfd ->
                    com.xnotes.format.ZipTail.splice(
                        java.io.FileOutputStream(pfd.fileDescriptor).channel,
                        tailStart,
                        ordered.subList(0, expected.size),
                        tail.channel,
                        tailDir,
                    )
                } ?: -1L
            }
            if (length < 0L) return@runCatching false
            state.lastSaveEncodeMs = msBetween(encodeStart, spliceStart) // all six for the overlay
            state.lastSaveCopyMs = msSince(spliceStart)
            state.lastSaveManifestMs = msBetween(encodeStart, spliceStart)
            state.lastSaveAssetsMs = 0L // the whole point: nothing ahead of the manifest is touched
            state.lastSaveDeflateMs = timing.deflateMs
            state.lastSaveManifestBytes = timing.manifestBytes
            state.lastSaveBytes = length
            lastNoteStamp = stampOf(uri) // read back what the provider reports, not what we wrote
            true
        } finally {
            tmp.delete()
        }
    }.getOrDefault(false)

    /** The on-disk size of the SAF document at [uri] in bytes, or -1 when the provider won't say. */
    private fun fileSizeOf(uri: String): Long = runCatching {
        appContext.contentResolver.query(
            android.net.Uri.parse(uri),
            arrayOf(android.provider.OpenableColumns.SIZE),
            null, null, null,
        )?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L } ?: -1L
    }.getOrDefault(-1L)

    private fun scheduleAutosave() {
        val uri = autosaveUri ?: return
        noteDebounceJob?.cancel()
        state.autosaveStatus = "pending" // debounce running; drives the debug overlay
        noteDebounceJob = autosaveScope.launch {
            kotlinx.coroutines.delay(1200L) // debounce: write after a short idle
            noteWriteJob?.join() // wait out a write already going out rather than racing it
            val startNs = System.nanoTime() // the debug overlay's save timings start once the debounce fires
            // Pointers, on the main thread: the writer gets its own page lists over the live items
            // (see [snapshot]), so it never iterates a list the pen is adding to.
            val snapshot = state.document.snapshot()
            state.lastSaveSnapshotMs = msSince(startNs)
            startNoteWrite(uri, snapshot, state.document.displayName ?: state.document.title, startNs)
        }
    }

    /**
     * Write [snapshot] to [uri] off the main thread and do the bookkeeping after it, tracked as
     * [noteWriteJob]. Launched into [autosaveScope] rather than as a child of the caller, so that
     * cancelling a debounce (or a flush) can never cancel a write that is already going out.
     * [startNs] is when the caller began the save, for the debug overlay's total.
     */
    private fun startNoteWrite(
        uri: String,
        snapshot: Document,
        title: String,
        startNs: Long,
        onDone: (() -> Unit)? = null,
    ) {
        val doc = state.document
        // Clean from here, not once the bytes land: [snapshot] already holds these edits, so an edit
        // arriving mid-write has to mark the document again and be carried by the next autosave.
        // Clearing the flag afterwards instead would swallow that edit until something else set it.
        val wasDirty = doc.dirty
        doc.dirty = false
        dirty = false
        state.autosaveStatus = "in progress"
        noteWriteJob = autosaveScope.launch {
            val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                saveNoteGuarded(uri, snapshot, title)
            }
            if (res != null) {
                // The note may have been closed or switched while the bytes were going out. The write
                // still counts, but only the document it belongs to may have its flags touched.
                if (state.document === doc) res.fork?.let { adoptNoteFork(it) }
                invalidateThumb(res.uri) // file changed on disk; drop the stale tile so the grid re-renders it
            } else if (wasDirty && state.document === doc) {
                doc.dirty = true; dirty = true // nothing was written; the edits are still unsaved
            }
            state.autosaveStatus = if (res != null) "done" else "failed"
            state.lastSaveTotalMs = msSince(startNs)
            android.util.Log.i(
                "xnotes.save",
                "save ${state.lastSaveTotalMs}ms = snapshot ${state.lastSaveSnapshotMs}" +
                    " + encode ${state.lastSaveEncodeMs} (json ${state.lastSaveManifestMs}" +
                    " of which deflate ${state.lastSaveDeflateMs} over" +
                    " ${state.lastSaveManifestBytes} raw bytes, assets ${state.lastSaveAssetsMs})" +
                    " + saf ${state.lastSaveCopyMs}" +
                    ", ${state.lastSaveBytes} bytes, ${res != null}",
            )
            onDone?.invoke()
        }
    }

    /**
     * Start writing the current note to its autosave file; a no-op when not autosaving or clean.
     * Returns as soon as the snapshot is taken, so a document swap never blocks the main thread on
     * SAF; the bytes go out through [startNoteWrite]. That is safe for the swap callers because the
     * snapshot already holds every edit. Callers that must see the write *finish* use [flushThen].
     *
     * No join on a write already going out: [saveLock] serializes the two, so this one cannot land
     * before the earlier one has finished with the file.
     */
    fun flushAutosave() {
        noteDebounceJob?.cancel() // only the debounce; a write already going out is left to finish
        val uri = autosaveUri ?: return
        if (!state.document.dirty) return
        val startNs = System.nanoTime()
        val snapshot = state.document.snapshot() // main thread: its own page lists over the live items
        state.lastSaveSnapshotMs = msSince(startNs)
        startNoteWrite(uri, snapshot, state.document.displayName ?: state.document.title, startNs)
    }

    /**
     * Flush the open note to its folder file off the main thread, then run [onDone] on the main thread.
     * Shows the "Saving your notes…" overlay while it runs when [showOverlay]. Runs [onDone] at once
     * (no save) when the note isn't a folder note or isn't dirty, so the common case stays instant.
     * Off-threading the write is what stops a large note from freezing the UI (ANR) on close/pause.
     */
    private fun flushThen(showOverlay: Boolean, onDone: () -> Unit) {
        noteDebounceJob?.cancel()
        val uri = autosaveUri
        if (uri == null || !state.document.dirty) { onDone(); return }
        if (showOverlay) savingNote = true
        autosaveScope.launch {
            noteWriteJob?.join() // a write already going out finishes first; it may be all there was
            if (!state.document.dirty) { savingNote = false; onDone(); return@launch }
            val startNs = System.nanoTime()
            val snapshot = state.document.snapshot() // main thread: its own page lists over the live items
            state.lastSaveSnapshotMs = msSince(startNs)
            startNoteWrite(uri, snapshot, state.document.displayName ?: state.document.title, startNs) {
                savingNote = false
                onDone()
            }
        }
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
        viewStates.put(key, state.zoom, state.scrollX, state.scrollY, viewOverrides)
    }

    /** Update the global View-menu defaults (the menu's "Default for all notes" checkbox),
     *  persist them, and re-resolve the open note — a note only follows the change where it
     *  has no override of its own. */
    fun updateViewDefaults(new: com.xnotes.canvas.ViewSettings) {
        if (viewDefaults == new) return
        viewDefaults = new
        settings = settings.copy(viewDefaults = new)
        settingsRepo.save(settings)
        applyResolvedViewSettings()
    }

    /** Update the open note's View-menu overrides and persist them. */
    fun updateViewOverrides(new: com.xnotes.canvas.ViewOverrides) {
        if (viewOverrides == new) return
        viewOverrides = new
        applyResolvedViewSettings()
        saveViewState()
    }

    /** Install a just-opened note's View-menu overrides (no persistence — nothing changed yet). */
    private fun installViewOverrides(o: com.xnotes.canvas.ViewOverrides) {
        viewOverrides = o
        applyResolvedViewSettings()
    }

    /** Re-resolve the effective settings and react to whatever actually changed. */
    private fun applyResolvedViewSettings() {
        val prev = viewSettings
        val resolved = viewOverrides.resolve(viewDefaults)
        if (prev == resolved) return
        viewSettings = resolved
        onViewSettingsChanged(prev, resolved)
    }

    /** Push a settings change into the canvas/caches; each View-menu feature reacts here. */
    private fun onViewSettingsChanged(prev: com.xnotes.canvas.ViewSettings, new: com.xnotes.canvas.ViewSettings) {
        if (prev.mode != new.mode || prev.rotation != new.rotation || prev.verticalScroll != new.verticalScroll) {
            // Re-group / re-orient / re-flow the pages, keep the reader on the same page, and
            // re-fit a fit-width view to the new row width (a Double spread is about twice as
            // wide; a 90 degree turn swaps every page's footprint).
            val cur = if (state.didInitialFit) state.currentPageIndex() else 0
            state.viewingMode = new.mode
            state.rotationDeg = new.rotation
            state.verticalScroll = new.verticalScroll
            state.flipOffsetX = 0.0
            if (new.verticalScroll) state.fitHeightActive = false // a paginated-only magnet
            state.relayout()
            if (state.didInitialFit) {
                state.currentRow = state.rowIndexOf(cur) // paginated fits read the row
                when {
                    state.fitWidthActive -> state.zoom = state.fitWidthZoom()
                    state.fitHeightActive -> state.fitHeightZoom().takeIf { it > 0.0 }?.let { state.zoom = it }
                }
                state.invalidateCachesForZoom()
                state.goToPage(cur)
                refreshView()
            }
        }
        if (prev.rotation != new.rotation) {
            // Every thumbnail mirrors the canvas orientation: drop them all to re-render.
            synchronized(pageThumbs) { pageThumbs.evictAll() }
            pdfThumbTick++
            currentUri?.let { invalidateThumb(it) }
        }
        val filterChanged = prev.contrast != new.contrast || prev.invert != new.invert ||
            prev.brightness != new.brightness || prev.sepia != new.sepia ||
            prev.multiply != new.multiply || prev.screen != new.screen ||
            prev.keepImages != new.keepImages
        if (filterChanged) {
            state.invalidateAllBackgrounds()
            if (evictPdfPageThumbnails()) pdfThumbTick++
            currentUri?.let { invalidateThumb(it) } // the recents tile re-renders with the new filter
            startPdfRefine() // a filter change can newly require the image-box sweep
        }
        view.scrollbarEnabled = new.scrollbar
        view.requestRender()
    }

    /** The open note's PDF colour filter (its resolved View-menu settings). */
    private fun pdfPageFilter(): com.xnotes.canvas.PdfPageFilter = pdfPageFilterFor(viewSettings)

    private fun pdfPageFilterFor(vs: com.xnotes.canvas.ViewSettings): com.xnotes.canvas.PdfPageFilter =
        com.xnotes.canvas.PdfPageFilter.of(
            vs.contrast, vs.invert, vs.brightness, vs.sepia, vs.multiply, vs.screen,
            keepImages = vs.keepImages,
        )

    /** A note's resolved View-menu settings by URI: the open note's live value, a folder note's
     *  remembered overrides over the global defaults, else the defaults themselves. */
    private fun viewSettingsFor(uri: String?): com.xnotes.canvas.ViewSettings = when {
        uri != null && uri == currentUri -> viewSettings
        else -> (viewKey(uri)?.let { viewStates.get(it)?.overrides } ?: com.xnotes.canvas.ViewOverrides())
            .resolve(viewDefaults)
    }

    /** True when the open note's PDF render stamps image colours (so image boxes are needed). */
    private fun pdfKeepsImageColors(): Boolean = viewSettings.keepImages &&
        !com.xnotes.canvas.PdfColorFilter.isIdentity(
            viewSettings.contrast, viewSettings.invert, viewSettings.brightness, viewSettings.sepia,
            viewSettings.multiply, viewSettings.screen,
        )

    /** Drop every cached side-panel thumbnail backed by a PDF page (the filter changed). */
    private fun evictPdfPageThumbnails(): Boolean = synchronized(pageThumbs) {
        var evicted = false
        state.document.pages.forEach { if (it.pdfPage != null && pageThumbs.remove(it) != null) evicted = true }
        evicted
    }

    /**
     * Choose a just-installed document's initial view — its remembered view for a folder note,
     * else fit-width — and apply it now if the viewport is sized, else on the next layout. Setting
     * it explicitly is what stops the previous document's zoom/scroll from carrying over. The
     * note's View-menu settings ride along (folder notes remember them; anything else resets).
     */
    private fun installInitialView(path: String?) {
        val saved = viewKey(path)?.let { viewStates.get(it) }
        installViewOverrides(saved?.overrides ?: com.xnotes.canvas.ViewOverrides())
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

    private fun hiddenByDot(name: String): Boolean = name.startsWith(".") && preferences.hideDotItems

    /** Flips the dot-item filter and re-lists every open folder. */
    fun setHideDotItems(hide: Boolean) {
        if (hide == preferences.hideDotItems) return
        applyHomePreferences(preferences.copy(hideDotItems = hide))
        browseCache.clear()
        folderCounts.clear()
        treeVersion++
    }

    /** Lists folders, documents and reader files under [parentDocId] within tree [treeUri]; IO, call off-thread. */
    fun browseChildren(treeUri: String, parentDocId: String): List<BrowseEntry> {
        val tree = android.net.Uri.parse(treeUri)
        val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        val out = ArrayList<BrowseEntry>()
        var sidecarDocId: String? = null
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
                    if (isDir) {
                        // The colour sidecar is captured (read below) but never listed, whatever the dot setting.
                        if (name == SIDECAR_DIR) { sidecarDocId = id; continue }
                        if (hiddenByDot(name)) continue
                    } else if (!com.xnotes.core.util.ReaderKind.isListed(name) || hiddenByDot(name)) {
                        continue
                    }
                    val docUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id).toString()
                    val size = if (!c.isNull(3)) c.getLong(3) else 0L
                    val modified = if (!c.isNull(4)) c.getLong(4) else 0L
                    out.add(BrowseEntry(name, docUri, isDir, size, modified, parentDocId = parentDocId))
                }
            }
        }
        val colors = sidecarDocId?.let { readSidecarColors(tree, it) }.orEmpty()
        // SAF reports no creation time, so an item seen for the first time is stamped with the earlier of
        // now and its modified time: nothing was created after its last edit, and a fresh item gets now.
        val now = System.currentTimeMillis()
        createdStore.stampMissing(out.associate { documentKey(it.documentUri) to (com.xnotes.core.util.DocKeys.inferCreated(now, it.modified) ?: now) })
        val withCreated = out.map {
            it.copy(created = createdStore.get(documentKey(it.documentUri)) ?: now, color = colors[it.name])
        }
        val result = withCreated.sortedWith(explorerComparator(explorerView.sortKey, explorerView.descending) { it.created })
        browseCache["$treeUri|$parentDocId"] = result
        return result
    }

    // --- per-folder colour sidecar (a hidden ".xnote/colors.json" beside the items it colours) ---

    /** Doc id of the child named [name] under [parentDocId] (a dir when [dir], else a file), or null. */
    private fun findChildDocId(tree: android.net.Uri, parentDocId: String, name: String, dir: Boolean): String? = runCatching {
        val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        appContext.contentResolver.query(
            childrenUri,
            arrayOf(
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val isDir = c.getString(2) == android.provider.DocumentsContract.Document.MIME_TYPE_DIR
                if (isDir == dir && c.getString(0) == name) return@use c.getString(1)
            }
            null
        }
    }.getOrNull()

    /** Reads the colour map (item name -> colour) from sidecar dir [sidecarDocId]. Forgiving. */
    private fun readSidecarColors(tree: android.net.Uri, sidecarDocId: String): Map<String, Rgba> {
        val fileId = findChildDocId(tree, sidecarDocId, SIDECAR_FILE, dir = false) ?: return emptyMap()
        val fileUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, fileId)
        val text = runCatching {
            appContext.contentResolver.openInputStream(fileUri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull() ?: return emptyMap()
        return runCatching {
            val obj = org.json.JSONObject(text).optJSONObject("colors") ?: return emptyMap()
            val map = HashMap<String, Rgba>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                Rgba.fromHex(obj.optString(k))?.let { map[k] = it }
            }
            map
        }.getOrDefault(emptyMap())
    }

    /** Overwrites sidecar dir [sidecarDocId]'s colors.json with [map], creating the file if needed. */
    private fun writeSidecarColors(tree: android.net.Uri, sidecarDocId: String, map: Map<String, Rgba>): Boolean {
        var fileId = findChildDocId(tree, sidecarDocId, SIDECAR_FILE, dir = false)
        if (fileId == null) {
            val dirUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, sidecarDocId)
            val created = android.provider.DocumentsContract.createDocument(
                appContext.contentResolver, dirUri, "application/octet-stream", SIDECAR_FILE,
            ) ?: return false
            fileId = android.provider.DocumentsContract.getDocumentId(created)
        }
        val colors = org.json.JSONObject()
        for ((k, v) in map) colors.put(k, Rgba.toHex(v))
        val obj = org.json.JSONObject().put("version", 1).put("colors", colors)
        val fileUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, fileId)
        return runCatching {
            appContext.contentResolver.openOutputStream(fileUri, "wt")?.use { it.write(obj.toString().toByteArray(Charsets.UTF_8)) }
            true
        }.getOrDefault(false)
    }

    /** Sets (or clears, when [color] is null) the explorer colour for [itemName] in folder [parentDocId];
     *  persisted to that folder's hidden ".xnote/colors.json". IO, call off-thread. */
    fun setItemColor(treeUri: String, parentDocId: String, itemName: String, color: Rgba?): Boolean =
        setItemColors(treeUri, parentDocId, mapOf(itemName to color))

    /** Applies [changes] (item name to colour, null clears it) to [parentDocId]'s sidecar in one read and one write. IO. */
    fun setItemColors(treeUri: String, parentDocId: String, changes: Map<String, Rgba?>): Boolean = runCatching {
        if (changes.isEmpty()) return@runCatching true
        val tree = android.net.Uri.parse(treeUri)
        val sidecarId = sidecarDir(tree, parentDocId, create = changes.values.any { it != null })
            ?: return@runCatching true // nothing stored, nothing to clear
        val map = readSidecarColors(tree, sidecarId).toMutableMap()
        for ((name, c) in changes) if (c == null) map.remove(name) else map[name] = c
        writeSidecarColors(tree, sidecarId, map)
    }.getOrDefault(false)

    /** Doc id of [parentDocId]'s hidden sidecar dir, made on demand when [create]; null when absent or on failure. */
    private fun sidecarDir(tree: android.net.Uri, parentDocId: String, create: Boolean): String? {
        findChildDocId(tree, parentDocId, SIDECAR_DIR, dir = true)?.let { return it }
        if (!create) return null
        val parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, parentDocId)
        val made = android.provider.DocumentsContract.createDocument(
            appContext.contentResolver, parent, android.provider.DocumentsContract.Document.MIME_TYPE_DIR, SIDECAR_DIR,
        ) ?: return null
        return android.provider.DocumentsContract.getDocumentId(made)
    }

    /** Re-reads [colorNames] for the current root, which another device may have changed. IO. */
    fun loadColorNames() {
        val root = browseRoot ?: run { colorNames = emptyMap(); return }
        val tree = android.net.Uri.parse(root)
        colorNames = runCatching {
            sidecarDir(tree, browseRootDocId(root), create = false)?.let { readColorNames(tree, it) }
        }.getOrNull().orEmpty()
    }

    /** Names [color] for the whole folder tree; a blank [name] forgets it. IO. */
    fun setColorName(color: Rgba, name: String?): Boolean = runCatching {
        val root = browseRoot ?: return@runCatching false
        val tree = android.net.Uri.parse(root)
        val clear = name.isNullOrBlank()
        val sidecarId = sidecarDir(tree, browseRootDocId(root), create = !clear) ?: return@runCatching clear
        val map = readColorNames(tree, sidecarId).toMutableMap()
        if (clear) map.remove(color) else map[color] = name!!.trim()
        writeSidecarJson(tree, sidecarId, COLOR_NAMES_FILE, org.json.JSONObject().put("version", 1).put(
            "names", org.json.JSONObject().apply { for ((c, n) in map) put(Rgba.toHex(c), n) },
        )).also { if (it) colorNames = map }
    }.getOrDefault(false)

    private fun readColorNames(tree: android.net.Uri, sidecarDocId: String): Map<Rgba, String> {
        val fileId = findChildDocId(tree, sidecarDocId, COLOR_NAMES_FILE, dir = false) ?: return emptyMap()
        val fileUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, fileId)
        val text = runCatching {
            appContext.contentResolver.openInputStream(fileUri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull() ?: return emptyMap()
        return runCatching {
            val obj = org.json.JSONObject(text).optJSONObject("names") ?: return emptyMap()
            val map = HashMap<Rgba, String>()
            for (k in obj.keys()) {
                val color = Rgba.fromHex(k) ?: continue
                obj.optString(k).trim().takeIf { it.isNotEmpty() }?.let { map[color] = it }
            }
            map
        }.getOrDefault(emptyMap())
    }

    /** Overwrites [fileName] in sidecar dir [sidecarDocId] with [obj], creating the file if needed. */
    private fun writeSidecarJson(tree: android.net.Uri, sidecarDocId: String, fileName: String, obj: org.json.JSONObject): Boolean {
        val fileId = findChildDocId(tree, sidecarDocId, fileName, dir = false) ?: run {
            val dirUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, sidecarDocId)
            val made = android.provider.DocumentsContract.createDocument(
                appContext.contentResolver, dirUri, "application/octet-stream", fileName,
            ) ?: return false
            android.provider.DocumentsContract.getDocumentId(made)
        }
        val fileUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, fileId)
        return runCatching {
            appContext.contentResolver.openOutputStream(fileUri, "wt")?.use { it.write(obj.toString().toByteArray(Charsets.UTF_8)) }
            true
        }.getOrDefault(false)
    }

    /** Every folder and note in the tree carrying [color], in the explorer's sort order. One query per folder; IO. */
    fun findByColor(treeUri: String, color: Rgba): List<BrowseEntry> {
        val out = ArrayList<BrowseEntry>()
        val seen = HashSet<String>()
        val stack = ArrayDeque<String>().apply { addLast(browseRootDocId(treeUri)) }
        while (stack.isNotEmpty()) {
            val docId = stack.removeLast()
            if (!seen.add(docId)) continue // guard against any cyclic SAF links
            for (e in browseChildren(treeUri, docId)) {
                if (e.color == color) out.add(e)
                if (e.isDir) stack.addLast(browseDocId(e.documentUri))
            }
        }
        return out.sortedWith(explorerComparator(explorerView.sortKey, explorerView.descending) { it.created })
    }

    /** Carries a colour across a rename: moves key [oldName] -> [newName] in [parentDocId]'s sidecar. */
    fun moveItemColor(treeUri: String, parentDocId: String, oldName: String, newName: String): Boolean = runCatching {
        val tree = android.net.Uri.parse(treeUri)
        val sidecarId = findChildDocId(tree, parentDocId, SIDECAR_DIR, dir = true) ?: return@runCatching true
        val map = readSidecarColors(tree, sidecarId).toMutableMap()
        val c = map.remove(oldName) ?: return@runCatching true
        map[newName] = c
        writeSidecarColors(tree, sidecarId, map)
    }.getOrDefault(false)

    /** The page count and PDF flag [entry]'s tile shows, if they're known for the file as it is now. Instant. */
    fun cachedMeta(entry: BrowseEntry): com.xnotes.platform.DocMetaStore.Meta? =
        docMeta.get(documentKey(entry.documentUri), entry.modified)

    /** [cachedMeta], reading the file's manifest when the cache has nothing current, and taking the created time it records; null for folders. IO. */
    fun docMetaFor(entry: BrowseEntry): com.xnotes.platform.DocMetaStore.Meta? {
        if (entry.isDir) return null
        val kind = DocumentKind.ofName(entry.name) ?: return null
        val key = documentKey(entry.documentUri)
        docMeta.get(key, entry.modified)?.let { return it }
        val meta = if (kind == DocumentKind.CANVAS) {
            val peek = peekFile(entry.documentUri, { canvasCodec.peek(it) }, { canvasCodec.peek(it) }) ?: return null
            peek.created?.let { createdStore.put(key, it) }
            // A canvas has no pages or PDF; the entry just marks it read at this modified time.
            com.xnotes.platform.DocMetaStore.Meta(0, false, entry.modified)
        } else {
            val peek = peekFile(entry.documentUri, { codec.peek(it) }, { codec.peek(it) }) ?: return null
            peek.created?.let { createdStore.put(key, it) }
            com.xnotes.platform.DocMetaStore.Meta(peek.pages, peek.hasPdf, entry.modified)
        }
        return meta.also { docMeta.put(key, it) }
    }

    /** [entry]'s created time as known now, which its file's own record, read after the listing, may have replaced. Instant. */
    fun createdOf(entry: BrowseEntry): Long = createdStore.get(documentKey(entry.documentUri)) ?: entry.created

    /** Whether a note annotates a PDF, from whatever was last read of it, however old. */
    fun knownPdf(entry: BrowseEntry): Boolean = docMeta.latest(documentKey(entry.documentUri))?.pdf == true

    private fun <T> peekFile(uri: String, viaChannel: (java.nio.channels.FileChannel) -> T?, viaStream: (java.io.InputStream) -> T?): T? {
        val u = android.net.Uri.parse(uri)
        runCatching {
            appContext.contentResolver.openFileDescriptor(u, "r")?.use { pfd -> viaChannel(java.io.FileInputStream(pfd.fileDescriptor).channel) }
        }.getOrNull()?.let { return it }
        return runCatching { appContext.contentResolver.openInputStream(u)?.use { viaStream(it) } }.getOrNull()
    }

    private val folderCounts = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Int>>()

    /** How many notes, canvases and folders [folder] holds, as the explorer would list them. IO, cached by the folder's mtime. */
    fun folderItemCount(treeUri: String, folder: BrowseEntry): Int {
        folderCounts[folder.documentUri]?.takeIf { it.first == folder.modified && folder.modified > 0 }?.let { return it.second }
        val tree = android.net.Uri.parse(treeUri)
        val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, browseDocId(folder.documentUri))
        var n = 0
        runCatching {
            appContext.contentResolver.query(
                childrenUri,
                arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME, android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    val isDir = c.getString(1) == android.provider.DocumentsContract.Document.MIME_TYPE_DIR
                    if (name != SIDECAR_DIR && !hiddenByDot(name) && (isDir || com.xnotes.core.util.ReaderKind.isListed(name))) n++
                }
            }
        }
        folderCounts[folder.documentUri] = folder.modified to n
        return n
    }

    /** Last-listed children for a folder, to seed the explorer instantly before the refresh. */
    fun cachedChildren(treeUri: String, parentDocId: String): List<BrowseEntry>? = browseCache["$treeUri|$parentDocId"]

    /**
     * Recursively finds notes at or below [startDocId] in tree [treeUri] whose name (sans `.xnote`)
     * contains [query], case-insensitively. Folders are descended into but not themselves returned;
     * results follow the explorer's chosen sort order. Does one provider query per folder, so it's IO
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
                    val display = com.xnotes.core.util.DocumentKind.stripSuffix(e.name)
                    if (display.contains(needle, ignoreCase = true)) out.add(e)
                }
            }
        }
        return out.sortedWith(explorerComparator(explorerView.sortKey, explorerView.descending) { it.created })
    }

    // --- Trash (a hidden folder in the top folder's sidecar, so it travels with the notes) ---

    /** The Trash folder under [treeUri]'s top folder, made on demand when [create]; null when there is none. */
    private fun trashDir(treeUri: String, create: Boolean): String? {
        val tree = android.net.Uri.parse(treeUri)
        val side = sidecarDir(tree, browseRootDocId(treeUri), create) ?: return null
        findChildDocId(tree, side, TRASH_DIR, dir = true)?.let { return it }
        if (!create) return null
        val made = android.provider.DocumentsContract.createDocument(
            appContext.contentResolver, android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, side),
            android.provider.DocumentsContract.Document.MIME_TYPE_DIR, TRASH_DIR,
        ) ?: return null
        return android.provider.DocumentsContract.getDocumentId(made)
    }

    /** The folder names from the top folder down to [folderDocId], or null when the provider can't say. IO. */
    private fun namesTo(treeUri: String, folderDocId: String): List<String>? {
        if (folderDocId == browseRootDocId(treeUri)) return emptyList()
        val uri = android.provider.DocumentsContract.buildDocumentUriUsingTree(android.net.Uri.parse(treeUri), folderDocId).toString()
        return folderChain(treeUri, uri)?.map { it.second }
    }

    /** (id, name, is a folder) for each child of [docId]. */
    private fun childRows(tree: android.net.Uri, docId: String): List<Triple<String, String, Boolean>> {
        val out = ArrayList<Triple<String, String, Boolean>>()
        runCatching {
            appContext.contentResolver.query(
                android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId),
                arrayOf(
                    android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    out.add(Triple(id, c.getString(1) ?: continue, c.getString(2) == android.provider.DocumentsContract.Document.MIME_TYPE_DIR))
                }
            }
        }
        return out
    }

    /**
     * Moves [entries] into Trash, each into a folder of its own with a note of where it was, when and in what
     * colour. Returns what went in, for an undo, and what couldn't (a provider that can't move, say). IO.
     */
    fun trashEntries(treeUri: String, entries: List<BrowseEntry>): Pair<List<TrashItem>, List<BrowseEntry>> {
        val tree = android.net.Uri.parse(treeUri)
        val resolver = appContext.contentResolver
        val trash = runCatching { trashDir(treeUri, create = true) }.getOrNull() ?: return emptyList<TrashItem>() to entries
        val trashed = ArrayList<TrashItem>()
        val failed = ArrayList<BrowseEntry>()
        val cleared = HashMap<String, HashMap<String, Rgba?>>()
        for (e in entries) {
            val item = runCatching {
                val now = System.currentTimeMillis()
                val wrapper = android.provider.DocumentsContract.createDocument(
                    resolver, android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, trash),
                    android.provider.DocumentsContract.Document.MIME_TYPE_DIR, "$now-${java.util.UUID.randomUUID().toString().take(6)}",
                ) ?: return@runCatching null
                val wrapperId = android.provider.DocumentsContract.getDocumentId(wrapper)
                val path = namesTo(treeUri, e.parentDocId).orEmpty()
                val info = org.json.JSONObject()
                    .put("version", 1)
                    .put("name", e.name)
                    .put("path", org.json.JSONArray(path))
                    .put("deleted", java.time.Instant.ofEpochMilli(now).toString())
                    .apply { e.color?.let { put("colour", Rgba.toHex(it)) } }
                val moved = if (writeSidecarJson(tree, wrapperId, TRASH_INFO, info)) {
                    runCatching {
                        android.provider.DocumentsContract.moveDocument(
                            resolver, android.net.Uri.parse(e.documentUri),
                            android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, e.parentDocId), wrapper,
                        )
                    }.getOrNull()
                } else null
                if (moved == null) {
                    runCatching { android.provider.DocumentsContract.deleteDocument(resolver, wrapper) }
                    return@runCatching null
                }
                // The open note stops writing to where it was; what's keyed by its identity follows it in.
                detachIfOpen(e.documentUri)
                onDocumentMoved(e.documentUri, moved.toString())
                dropPinsWithin(moved.toString())
                dropRecentsWithin(moved.toString())
                TrashItem(wrapperId, e.copy(documentUri = moved.toString(), parentDocId = wrapperId), path, now)
            }.getOrNull()
            if (item == null) {
                failed.add(e)
            } else {
                trashed.add(item)
                if (e.color != null) cleared.getOrPut(e.parentDocId) { HashMap() }[e.name] = null
            }
        }
        for ((parent, changes) in cleared) setItemColors(treeUri, parent, changes)
        refreshTrashCount(treeUri)
        return trashed to failed
    }

    /** What waits in Trash, newest first. A wrapper whose item has gone is cleared away. IO. */
    fun listTrash(treeUri: String): List<TrashItem> {
        val tree = android.net.Uri.parse(treeUri)
        val trash = trashDir(treeUri, create = false) ?: run { trashCount = 0; return emptyList() }
        val out = ArrayList<TrashItem>()
        for ((wrapperId, wrapperName, isDir) in childRows(tree, trash)) {
            if (!isDir) continue
            val children = childRows(tree, wrapperId)
            val info = children.firstOrNull { !it.third && it.second == TRASH_INFO }?.let { (id, _, _) ->
                runCatching {
                    appContext.contentResolver.openInputStream(android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id))
                        ?.use { org.json.JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
                }.getOrNull()
            }
            val (itemId, itemName, itemIsDir) = children.firstOrNull { it.third || it.second != TRASH_INFO } ?: run {
                runCatching { android.provider.DocumentsContract.deleteDocument(appContext.contentResolver, android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, wrapperId)) }
                continue
            }
            val itemUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, itemId).toString()
            val stamp = stampOf(itemUri)
            val deleted = info?.optString("deleted")?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
                ?: wrapperName.substringBefore('-').toLongOrNull() ?: 0L
            val path = info?.optJSONArray("path")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).ifEmpty { null } } }.orEmpty()
            val entry = BrowseEntry(
                name = itemName, documentUri = itemUri, isDir = itemIsDir,
                size = stamp?.size?.coerceAtLeast(0) ?: 0, modified = stamp?.modified?.coerceAtLeast(0) ?: 0,
                created = createdStore.get(documentKey(itemUri)) ?: 0, parentDocId = wrapperId,
                color = info?.optString("colour")?.let { Rgba.fromHex(it) },
            )
            out.add(TrashItem(wrapperId, entry, path, deleted))
        }
        trashCount = out.size
        return out.sortedByDescending { it.deleted }
    }

    /** Recount Trash for the sidebar. IO. */
    fun refreshTrashCount(treeUri: String) {
        val trash = trashDir(treeUri, create = false)
        trashCount = if (trash == null) 0 else childRows(android.net.Uri.parse(treeUri), trash).count { it.third }
    }

    /**
     * Puts [item] back in the folder it came from, or in the top folder when that one is gone, under a free
     * name if its own is taken there, colour code and all. IO.
     */
    fun restoreTrash(treeUri: String, item: TrashItem): Boolean = runCatching {
        val tree = android.net.Uri.parse(treeUri)
        val resolver = appContext.contentResolver
        val rootId = browseRootDocId(treeUri)
        val dest = item.path.fold<String, String?>(rootId) { at, name -> at?.let { findChildDocId(tree, it, name, dir = true) } } ?: rootId
        var uri = android.net.Uri.parse(item.entry.documentUri)
        var name = item.entry.name
        val taken = childRows(tree, dest).mapTo(HashSet()) { it.second.lowercase() }
        if (name.lowercase() in taken) {
            name = freeName(name, taken, splitExtension = !item.entry.isDir)
            uri = android.provider.DocumentsContract.renameDocument(resolver, uri, name) ?: return@runCatching false
        }
        val moved = android.provider.DocumentsContract.moveDocument(
            resolver, uri, android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, item.wrapperDocId),
            android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, dest),
        ) ?: return@runCatching false
        onDocumentMoved(item.entry.documentUri, moved.toString(), name.takeIf { it != item.entry.name })
        item.entry.color?.let { setItemColor(treeUri, dest, name, it) }
        runCatching { android.provider.DocumentsContract.deleteDocument(resolver, android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, item.wrapperDocId)) }
        refreshTrashCount(treeUri)
        true
    }.getOrDefault(false)

    /** "Name (2).xnote": [name] made free among [taken] (lowercased). */
    private fun freeName(name: String, taken: Set<String>, splitExtension: Boolean): String {
        val dot = if (splitExtension) name.lastIndexOf('.') else -1
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 2
        var candidate = "$stem ($n)$ext"
        while (candidate.lowercase() in taken) candidate = "$stem (${++n})$ext"
        return candidate
    }

    /** Takes [items] back out of Trash, off the screen that trashed them, and has the explorer list again. */
    fun undoTrash(treeUri: String, items: List<TrashItem>) {
        autosaveScope.launch {
            withContext(Dispatchers.IO) { items.forEach { restoreTrash(treeUri, it) } }
            treeVersion++
        }
    }

    /** Deletes [item] for good. IO. */
    fun deleteTrash(treeUri: String, item: TrashItem, recount: Boolean = true): Boolean = runCatching {
        val wrapper = android.provider.DocumentsContract.buildDocumentUriUsingTree(android.net.Uri.parse(treeUri), item.wrapperDocId)
        val ok = android.provider.DocumentsContract.deleteDocument(appContext.contentResolver, wrapper)
        if (ok) purgeDeleted(item.entry.documentUri)
        if (recount) refreshTrashCount(treeUri)
        ok
    }.getOrDefault(false)

    /** Deletes everything in Trash for good; returns how many items went. IO. */
    fun emptyTrash(treeUri: String): Int {
        val gone = listTrash(treeUri).count { deleteTrash(treeUri, it, recount = false) }
        refreshTrashCount(treeUri)
        return gone
    }

    /** Deletes for good whatever has waited in Trash longer than [days]; nothing when Trash keeps things until emptied. IO. */
    fun purgeExpiredTrash(treeUri: String, days: Int) {
        if (days <= 0) { refreshTrashCount(treeUri); return }
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        listTrash(treeUri).filter { it.deleted in 1 until cutoff }.forEach { deleteTrash(treeUri, it, recount = false) }
        refreshTrashCount(treeUri)
    }

    /** Every note and canvas in [startDocId], and in its subfolders too when [recursive]. One query per folder; IO. */
    fun filesUnder(treeUri: String, startDocId: String, recursive: Boolean): TreeFiles {
        val tree = android.net.Uri.parse(treeUri)
        val files = ArrayList<BrowseEntry>()
        val names = HashMap<String, String>()
        queryDisplayName(android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, startDocId))?.let { names[startDocId] = it }
        val seen = HashSet<String>()
        val stack = ArrayDeque<String>().apply { addLast(startDocId) }
        while (stack.isNotEmpty()) {
            val docId = stack.removeLast()
            if (!seen.add(docId)) continue // guard against any cyclic SAF links
            for (e in browseChildren(treeUri, docId)) {
                if (!e.isDir) files.add(e)
                else if (recursive) {
                    val id = browseDocId(e.documentUri)
                    names[id] = e.name
                    stack.addLast(id)
                }
            }
        }
        return TreeFiles(files, names)
    }

    /**
     * The file an Obsidian [[link]] points at: [target] by name (a bare name also matches "name.md"), nearest the
     * top folder first, since links name files, not paths. Null when nothing matches. IO.
     */
    fun findLinkedFile(treeUri: String, target: String): BrowseEntry? {
        val base = target.substringAfterLast('/').trim().lowercase()
        if (base.isEmpty()) return null
        val wanted = setOf(base, "$base.md")
        val seen = HashSet<String>()
        val queue = ArrayDeque<String>().apply { addLast(browseRootDocId(treeUri)) }
        while (queue.isNotEmpty()) {
            val docId = queue.removeFirst()
            if (!seen.add(docId)) continue
            val children = browseChildren(treeUri, docId)
            children.firstOrNull { !it.isDir && it.name.lowercase() in wanted }?.let { return it }
            children.filter { it.isDir }.forEach { queue.addLast(browseDocId(it.documentUri)) }
        }
        return null
    }

    /** Whether two uris name the same document, however each was reached. */
    fun isSameDocument(a: String, b: String): Boolean = documentKey(a) == documentKey(b)

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

    /**
     * Loads the document at [srcUri] and writes it flattened to a PDF in [out] (share-as-PDF /
     * export). Dispatches on the stored file's kind, because a canvas is a different bundle read by a
     * different codec and flattened by a different exporter. The decision lives here rather than at
     * the call sites so neither of them can forget it and export an `.xcanvas` as a paged note.
     */
    fun exportFileToPdf(
        srcUri: String,
        out: OutputStream,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ) {
        val name = queryDisplayName(android.net.Uri.parse(srcUri)).orEmpty()
        if (com.xnotes.core.util.DocumentKind.ofName(name) == com.xnotes.core.util.DocumentKind.CANVAS) {
            exportCanvasFileToPdf(srcUri, out, onProgress, isCancelled)
            return
        }
        val doc = appContext.contentResolver.openInputStream(android.net.Uri.parse(srcUri))?.use { codec.read(it, pdfDir, imageDir) } ?: return
        val src = doc.pdfFile?.let { com.xnotes.platform.PdfSource.create(appContext, it) }
        try {
            com.xnotes.platform.PdfExporter.export(
                appContext, doc, src, out,
                { exportPaper(doc, it) },
                { page, r -> paintExportRuling(doc, page, r) },
                onProgress, isCancelled,
                flow = flowExportHooks(doc),
            )
        } finally {
            src?.close()
            doc.pdfFile?.delete() // transient doc loaded just for export; drop its extracts
            deleteImageTemps(doc)
        }
    }

    /**
     * The canvas half of [exportFileToPdf]: one page cut to the drawing. The paper is the canvas's own
     * colour where it set one, else the theme's, which is what the explorer tile and the live canvas
     * both show.
     */
    private fun exportCanvasFileToPdf(
        srcUri: String,
        out: OutputStream,
        onProgress: (Int, Int) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val doc = appContext.contentResolver.openInputStream(android.net.Uri.parse(srcUri))
            ?.use { canvasCodec.read(it, imageDir) } ?: return
        try {
            com.xnotes.platform.CanvasPdfExporter.export(
                appContext, doc, out,
                doc.background.paperColor ?: state.palette.paper,
                onProgress, isCancelled,
            )
        } finally {
            deleteCanvasImageTemps(doc) // transient doc loaded just for export; drop its extracts
        }
    }

    private fun replaceDocument(doc: Document) {
        saveViewState() // remember the outgoing folder note's view before switching away
        flowText.endSession() // flushes the typing burst so the autosave below carries it
        flushAutosave() // save the outgoing note if it was autosaving to the folder
        autosaveUri = null
        // File readouts belong to the outgoing note; a file-backed open re-sets them after the swap.
        state.lastOpenCompacted = doc.compactedOnLoad
        state.openFileBytes = -1L
        state.lastSaveBytes = -1L
        controller.commitTextEdit()
        controller.clearSelection()
        controller.resetGestureState() // drop the outgoing note's fling/elastic so it can't bleed in
        clearPageSelection()
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

    /** Run the action a two/three-finger tap or stylus double-tap is mapped to; "none" does nothing. */
    private fun dispatchTapGesture(action: String) = when (action) {
        "undo" -> undo()
        "redo" -> redo()
        "toggle_pan" -> toggleTool(Tool.PAN)
        "toggle_eraser" -> toggleTool(Tool.ERASER)
        "toggle_previous" -> toggleToPreviousTool()
        else -> Unit
    }

    /** Arm [target], or if it is already armed, return to the previous tool (no-op if none yet). */
    private fun toggleTool(target: Tool) {
        if (controller.tool == target) controller.previousTool?.let { selectTool(it) }
        else selectTool(target)
    }

    /** Switch to the single previous tool; no-op on a fresh launch with no previous tool. */
    private fun toggleToPreviousTool() {
        controller.previousTool?.let { selectTool(it) }
    }

    fun pickColor(index: Int) {
        activeColorIndex = index
        // pickInk also recolours the active text box (editing or selected), so the 5 toolbar
        // swatches double as the text colour control.
        controller.pickInk(toolbarColors[index.coerceIn(0, toolbarColors.lastIndex)])
        refreshTextBar()
    }

    /** Live swatch recolour while the picker is open: applies to the canvas but does *not* yet
     *  commit to recents (a spectrum drag fires this on every sample and would flood the list). */
    override fun setSwatchColor(index: Int, color: Rgba) {
        toolbarColors = toolbarColors.toMutableList().also { it[index] = color }
        pickColor(index)
    }

    /** Commit the swatch's current colour to the recent-colours list — called once the picker closes. */
    override fun rememberSwatchColor(index: Int) {
        toolbarColors.getOrNull(index)?.let { settings = settings.rememberColor(it) }
    }

    fun setShapeKind(kind: com.xnotes.core.tools.ShapeKind) {
        shapeConfig = shapeConfig.copy(shape = kind)
        controller.shapeConfig = shapeConfig
    }

    override fun updateShapeConfig(config: ShapeConfig) {
        shapeConfig = config
        controller.shapeConfig = config
    }

    /** The live config for a stroke tool (read by its config popup). */
    override fun toolConfig(tool: Tool): com.xnotes.core.tools.ToolConfig = controller.configFor(tool)

    override var snapHeldToShapes: Boolean
        get() = settings.prefs.detectShapes
        set(v) {
            settings = settings.copy(prefs = settings.prefs.copy(detectShapes = v))
            controller.detectShapes = v
            infiniteOrNull?.armDetectShapes(v)
            settingsRepo.save(settings)
        }

    override val hostShapeConfig: ShapeConfig get() = shapeConfig
    override val hostToolbarColors: List<Rgba> get() = toolbarColors
    override val hostActiveColorIndex: Int get() = activeColorIndex
    override val hostRecentColors: List<Rgba> get() = recentColors

    override fun updateToolConfig(tool: Tool, config: com.xnotes.core.tools.ToolConfig) {
        controller.setToolConfig(tool, config.copy(rgba = controller.inkColor))
    }

    val recentColors: List<Rgba> get() = settings.recentColors

    // --- history ---

    fun undo() {
        flowText.flushBurst() // the open typing burst is the first thing Ctrl+Z takes back
        val command = history.nextUndo
        val pagesBefore = state.document.pages.size
        val was = touchedRegions(command)
        history.undo()
        afterHistory(
            structural = state.document.pages.size != pagesBefore,
            regions = spanning(was, touchedRegions(command)),
        )
    }

    fun redo() {
        flowText.flushBurst()
        val command = history.nextRedo
        val pagesBefore = state.document.pages.size
        val was = touchedRegions(command)
        history.redo()
        afterHistory(
            structural = state.document.pages.size != pagesBefore,
            regions = spanning(was, touchedRegions(command)),
        )
    }

    /**
     * Where [command]'s items sit right now, page-local. Read on both sides of an undo/redo so an
     * item that moved, resized or reflowed is repaired where it was *and* where it landed; null
     * (the command can't say) asks for the old full repaint of every cached page.
     */
    private fun touchedRegions(command: Command?): List<Pair<Page, Rect>>? {
        if (command == null) return emptyList()
        return command.touched(itemPageLocator())?.map { (page, item) -> page to item.paintBounds() }
    }

    private fun spanning(
        before: List<Pair<Page, Rect>>?,
        after: List<Pair<Page, Rect>>?,
    ): List<Pair<Page, Rect>>? = if (before == null || after == null) null else before + after

    /**
     * Finds the page an item sits on, for commands that hold items but not pages. The index is built
     * on first use and only then: most commands carry their own page and never ask.
     */
    private fun itemPageLocator(): (CanvasItem) -> Page? {
        var index: HashMap<CanvasItem, Page>? = null
        return { item ->
            val built = index ?: HashMap<CanvasItem, Page>().also { map ->
                for (page in state.document.pages) for (it in page.items) map[it] = page
                index = map
            }
            built[item]
        }
    }

    private fun afterHistory(structural: Boolean, regions: List<Pair<Page, Rect>>?) {
        controller.clearSelection()
        if (structural) state.relayout() // page add/remove shifts layout; page-keyed caches survive
        // The in-place repaint below reads the published flow snapshot: republish it first
        // so an undone/redone flow edit repaints at its post-history layout.
        republishFlowIfStale()
        // Repair the ink caches rather than dropping them — dropping blanked every visible page to
        // bare paper for a frame (the undo/redo flicker). Only AddPage/DeletePage change the page
        // set, so relayout (which re-renders the sharp viewport) is gated on that. A command that
        // named its regions repairs just those, here and now; one that couldn't hands every cached
        // page to the cache thread instead, because repainting them all inline is a stall long
        // enough to time out input on a dense note.
        if (regions == null) state.refreshAllInk() else state.repairInkRegions(regions)
        if (flowText.active) flowInput.reconcile() // undone/redone text must reach the IME mirror
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
    fun prevPage() { state.goToPage(state.prevPageIndex(state.currentPageIndex())); afterView() }
    fun nextPage() { state.goToPage(state.nextPageIndex(state.currentPageIndex())); afterView() }
    fun goToPage(index: Int) { state.goToPage(index); afterView() }

    fun toggleZoomLock() {
        zoomLocked = !zoomLocked
        state.zoomLocked = zoomLocked
    }

    fun toggleRuler() {
        controller.toggleRuler()
        rulerVisible = controller.rulerVisible()
    }

    fun toggleWand() {
        controller.toggleWand()
        wandEnabled = controller.wandEnabled()
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
    private fun insertBlankPageAt(index: Int, refIndex: Int, turned: Boolean = false): Int {
        val pages = state.document.pages
        val ref = pages.getOrNull(refIndex) ?: pages.getOrNull(index) ?: pages.lastOrNull()
        val (rw, rh) = if (ref != null) ref.width to ref.height else PageSize.A4.pixels(Orientation.PORTRAIT, state.document.dpi)
        val (w, h) = if (turned) rh to rw else rw to rh
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
            message = appContext.getString(R.string.err_keep_one_page)
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

    /** Insert a blank page right after [index] (sized from it, or its other orientation when [turned]) and reveal it. */
    fun insertPageAfter(index: Int, turned: Boolean = false) {
        goToPage(insertBlankPageAt(index + 1, index, turned))
    }

    /** Whether the page at [index] is wider than it is tall. */
    fun isLandscapePage(index: Int): Boolean = pageAt(index)?.let { it.width > it.height } == true

    /** Clear all of a page's items but keep the page (and its PDF/template background). Undoable. */
    fun erasePage(index: Int) {
        val page = pageAt(index) ?: return
        if (page.items.isEmpty()) { message = appContext.getString(R.string.page_already_empty); return }
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
        PageClipboard.set(pages.map { it.deepCopy(textMeasurer) }, state.document.pdfFile)
    }

    /** Copy [indices] to the clipboard then delete them (kept ≥ 1 page). */
    fun cutPages(indices: List<Int>) {
        if (indices.isEmpty()) return
        if (indices.distinct().size >= state.document.pages.size) {
            message = appContext.getString(R.string.err_keep_one_page)
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
            flattenForeignPdfPage(clone)
            pages.add(at, clone)
            cmds.add(AddPage(state.document, clone, at))
            at++
        }
        history.push(CompositeCommand(cmds))
        afterPageEdit()
        goToPage(firstAt)
    }

    /**
     * If [page] is backed by a source PDF that the current note does not share (a cross-note paste),
     * bake that PDF page into a background image and drop the [Page.pdfPage] link, so the pasted page
     * stands alone. Same-PDF pastes (duplicating within a PDF note) keep the vector link untouched.
     * On any failure the background is simply dropped, leaving a blank page with the copied items.
     */
    private fun flattenForeignPdfPage(page: Page, srcPdf: java.io.File? = PageClipboard.sourcePdfFile) {
        val pi = page.pdfPage ?: return
        if (srcPdf != null && srcPdf === state.document.pdfFile) return // same PDF note: keep the link
        page.pdfPage = null
        if (srcPdf == null) return
        val bg = runCatching {
            val src = com.xnotes.platform.PdfSource.create(appContext, srcPdf) ?: return@runCatching null
            try {
                val w = page.width.toInt().coerceAtLeast(1)
                val h = page.height.toInt().coerceAtLeast(1)
                val surface = src.renderRegion(pi, w, h, 0, 0, w, h) ?: return@runCatching null
                val bytes = java.io.ByteArrayOutputStream().use { out ->
                    surface.bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                    out.toByteArray()
                }
                surface.recycle()
                bytes
            } finally {
                src.close()
            }
        }.getOrNull() ?: return
        val file = runCatching {
            java.io.File.createTempFile("pdfpg", null, imageDir).apply { writeBytes(bg) }
        }.getOrNull() ?: return
        val size = imageCodec.probeFile(file.path)
        if (size == null || size.width <= 0 || size.height <= 0) { file.delete(); return }
        val img = ImageItem(ImageData(file, size.width, size.height), Rect(0.0, 0.0, page.width, page.height))
        page.items.add(0, img) // behind the copied ink/items
    }

    /** Delete [indices] as one undoable edit, refusing to empty the note. */
    fun deletePages(indices: List<Int>) {
        val pages = state.document.pages
        val targets = indices.filter { it in pages.indices }.distinct().sortedDescending()
        if (targets.isEmpty()) return
        if (targets.size >= pages.size) {
            message = appContext.getString(R.string.err_keep_one_page)
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
        sub.style = state.document.style // carry the note's "all pages" style into the subset export
        sub.templates = state.document.templates
        // A private source per export — see [exportPdf]: the canvas's cache thread may be
        // touching the live [pdfSource], and PdfRenderer can't be shared across threads. The
        // shared PDF file is read-only and owned by the open document, so closing src won't delete it.
        val src = sub.pdfFile?.let { com.xnotes.platform.PdfSource.create(appContext, it) }
        try {
            com.xnotes.platform.PdfExporter.export(
                appContext, sub, src, out,
                { exportPaper(sub, it) },
                { page, r -> paintExportRuling(sub, page, r) },
                onProgress, isCancelled,
                // The subset shares the open note's page objects, so its flow lines map through.
                flow = flowExportHooks(state.document),
            )
        } finally {
            src?.close()
        }
    }

    /** PNG bytes for page [index], rendered at full page resolution (paper + background + items), or null. */
    fun pageImagePng(index: Int): ByteArray? {
        val page = pageAt(index) ?: return null
        // One-shot export: find this page's image locations now so the PNG is correct regardless of how
        // far the background sweep has reached (renderThumbnail itself is a pure consumer).
        if (pdfKeepsImageColors()) {
            page.pdfPage?.let { pi -> pdfSource?.ensureImageRects(pi) }
        }
        val bmp = renderThumbnail(page, state.outerW(page).toInt().coerceAtLeast(1)) ?: return null
        return java.io.ByteArrayOutputStream().use { out ->
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }

    // --- selection edits ---

    override fun deleteSelection() {
        if (hasSelection) showToast("Deleted")
        controller.deleteSelection()
    }
    fun selectAll() = controller.selectAll()
    override fun bringToFront() = controller.bringToFront()
    override fun selectionStyles() = controller.selectionStyles()

    override fun restyleSelection(color: Rgba?, width: Double?, preview: Boolean) {
        controller.restyleSelection(color, width, preview)
        if (!preview) color?.let { settings = settings.rememberColor(it) }
    }

    fun escape() {
        if (editingTable != null) endTableEdit() else controller.escape()
    }

    fun toggleSidebar() {
        sidebarVisible = !sidebarVisible
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
        // A canvas is on top: it owns the keyboard, and understands only its own shortcuts.
        if (canvasOpen) return infinite.handleKeyDown(e)
        // A live flow caret session owns the keyboard first (Ctrl+B means bold here).
        if (flowText.active && handleFlowKey(e)) return true
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

    /** Keys while the flow caret is live: editing, navigation and formatting shortcuts. */
    private fun handleFlowKey(e: android.view.KeyEvent): Boolean {
        val ctrl = e.isCtrlPressed
        val shift = e.isShiftPressed
        when {
            // Escape backs out of the slash menu first; a second one ends the session.
            e.keyCode == android.view.KeyEvent.KEYCODE_ESCAPE ->
                if (slashQuery() != null) dismissSlashMenu() else flowText.endSession()
            ctrl && shift && e.keyCode == android.view.KeyEvent.KEYCODE_Z -> redo()
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_Z -> undo()
            ctrl && shift && e.keyCode == android.view.KeyEvent.KEYCODE_X ->
                flowToggleStyle({ it.strike }) { s, v -> s.copy(strike = v) }
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_B ->
                flowToggleStyle({ it.bold }) { s, v -> s.copy(bold = v) }
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_I ->
                flowToggleStyle({ it.italic }) { s, v -> s.copy(italic = v) }
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_U ->
                flowToggleStyle({ it.underline }) { s, v -> s.copy(underline = v) }
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_A -> flowText.selectAll()
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_C -> flowCopySelection(cut = false)
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_X -> flowCopySelection(cut = true)
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_V -> pastePlainAtCaret()
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT -> flowMoveWord(forward = false, extend = shift)
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> flowMoveWord(forward = true, extend = shift)
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_DEL -> flowDeleteWord(forward = false)
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_FORWARD_DEL -> flowDeleteWord(forward = true)
            ctrl -> return false // other Ctrl combos (save, zoom...) stay global
            e.keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                e.keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER ->
                flowText.applyReplace(flowText.selection, "\n")
            e.keyCode == android.view.KeyEvent.KEYCODE_DEL -> flowDeleteKey(forward = false)
            e.keyCode == android.view.KeyEvent.KEYCODE_FORWARD_DEL -> flowDeleteKey(forward = true)
            e.keyCode == android.view.KeyEvent.KEYCODE_TAB ->
                if (!flowTabCell(back = shift) && !flowTabIndent(back = shift)) {
                    flowText.applyReplace(flowText.selection, "\t")
                }
            e.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT -> flowMoveHorizontal(-1, shift)
            e.keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> flowMoveHorizontal(1, shift)
            // With the menu open the arrows walk it; Shift still means select text.
            e.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP ->
                if (shift || !moveSlashSelection(-1)) flowMoveVertical(-1, shift)
            e.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN ->
                if (shift || !moveSlashSelection(1)) flowMoveVertical(1, shift)
            e.keyCode == android.view.KeyEvent.KEYCODE_MOVE_HOME -> flowLineEdge(start = true, extend = shift)
            e.keyCode == android.view.KeyEvent.KEYCODE_MOVE_END -> flowLineEdge(start = false, extend = shift)
            else -> {
                val ch = e.unicodeChar
                if (ch == 0 || e.isAltPressed) return false
                val typed = deadKeys.accept(ch) ?: return true // a dead key waiting for its letter
                flowText.applyReplace(flowText.selection, typed)
            }
        }
        return true
    }

    // --- flow formatting (the bottom format bar + shortcuts) ---

    /** The style the bar reports: pending/typing style at a caret, the first char of a selection. */
    fun flowCaretStyle(): CharStyle {
        val sel = flowText.selection.normalized()
        val editor = FlowEditor(state.document.flow)
        return if (sel.collapsed) {
            flowText.pendingStyle ?: editor.charStyleAt(sel.start)
        } else {
            editor.styleAtRangeStart(sel)
        }
    }

    /** The paragraph under the caret / selection start, for the bar's paragraph toggles. */
    fun flowCaretParagraph(): Paragraph? =
        state.document.flow.paragraphs.getOrNull(flowText.selection.normalized().start.para)

    fun flowDefaultSizePt(): Double = state.document.flow.defaultSizePt
    /**
     * The flow's base text colour for the active paper: the near-white default on
     * dark/OLED paper, a near-black on light paper (where near-white would vanish).
     */
    private fun defaultTextColor(): Rgba =
        if (palette.isDark) com.xnotes.core.text.TextFlow.DEFAULT_COLOR else Rgba(28, 28, 28, 255)

    /** The effective base text colour: the flow's explicit default, else the theme auto colour. */
    fun flowDefaultColor(): Rgba = state.document.flow.defaultColor ?: defaultTextColor()
    fun flowDefaultFace(): FontFace = state.document.flow.defaultFace

    /** Rewrite character styles over the selection, or arm them for the next typed run. */
    fun flowSetChar(apply: (CharStyle) -> CharStyle) {
        if (!flowText.active) return
        val sel = flowText.selection.normalized()
        if (sel.collapsed) {
            flowText.pendingStyle = apply(flowCaretStyle())
            flowSelTick++
            onRender() // the caret previews the pending style (e.g. its new size)
            return
        }
        flowText.flushBurst()
        flowText.commitEdit(FlowEditor(state.document.flow).setCharStyle(sel) { apply(it) }, null)
        flowSelTick++
    }

    /** Toggle a character style on the selection, or arm it for the next typed run. */
    private fun flowToggleStyle(prop: (CharStyle) -> Boolean, apply: (CharStyle, Boolean) -> CharStyle) {
        val target = !prop(flowCaretStyle())
        flowSetChar { apply(it, target) }
    }

    fun flowToggleBold() = flowToggleStyle({ it.bold }) { s, v -> s.copy(bold = v) }
    fun flowToggleItalic() = flowToggleStyle({ it.italic }) { s, v -> s.copy(italic = v) }
    fun flowToggleUnderline() = flowToggleStyle({ it.underline }) { s, v -> s.copy(underline = v) }
    fun flowToggleStrike() = flowToggleStyle({ it.strike }) { s, v -> s.copy(strike = v) }
    fun flowSetCharColor(c: Rgba?) = flowSetChar { it.copy(color = c) }
    fun flowSetCharHighlight(c: Rgba?) = flowSetChar { it.copy(highlight = c) }
    fun flowSetCharFace(f: FontFace?) = flowSetChar { it.copy(face = f) }

    /** The hyperlink covering the caret / selection start, or null (drives the bar's link state). */
    fun flowCaretLink(): String? = flowCaretStyle().link

    /**
     * Set, edit or remove ([url] == null) the hyperlink on the flow text. With a selection it links
     * that span. From a bare caret it edits the whole link the caret sits on, or, with no link and a
     * given [url], inserts the url itself as new linked text.
     */
    fun flowSetCharLink(url: String?) {
        if (!flowText.active) return
        val link = url?.trim()?.ifEmpty { null }
        val sel = flowText.selection.normalized()
        if (!sel.collapsed) {
            flowSetChar { it.copy(link = link) }
            return
        }
        val existing = FlowEditor(state.document.flow).linkRangeAt(sel.start)
        if (existing != null) {
            flowText.flushBurst()
            flowText.commitEdit(FlowEditor(state.document.flow).setCharStyle(existing) { it.copy(link = link) }, null)
            flowSelTick++
        } else if (link != null) {
            flowText.replaceExternal(FlowRange.caret(sel.start), link, CharStyle(link = link))
            flowSelTick++
        }
    }

    /** Step the bar's shown size by [delta] and apply that one size across the selection. */
    fun flowAdjustSize(delta: Double) {
        val shown = flowCaretStyle().sizePt ?: flowDefaultSizePt()
        val target = (shown + delta).coerceIn(6.0, 96.0)
        flowSetChar { it.copy(sizePt = target) }
    }

    /** Apply a paragraph-property change over the selection as one undo step. */
    private fun flowParaOp(mutate: (Paragraph) -> Unit) {
        if (!flowText.active) return
        flowText.flushBurst()
        flowText.commitEdit(FlowEditor(state.document.flow).setParaStyle(flowText.selection, mutate), null)
        flowSelTick++
    }

    /** Toggle the paragraph(s) into [kind], or back to plain text when already that kind. */
    fun flowToggleList(kind: ListKind) =
        flowSetList(if (flowCaretParagraph()?.list == kind) ListKind.NONE else kind)

    /** Put the paragraph(s) into [target] outright; the slash menu asks, it does not toggle. */
    fun flowSetList(target: ListKind) {
        flowParaOp {
            it.list = target
            if (target != ListKind.CHECK) it.checked = false
            if (target != ListKind.NONE) it.codeLang = null
        }
    }

    /**
     * Make the selected paragraph(s) heading [level], or body text at 0. The runs
     * carry the size, so they are restyled alongside the paragraph flag; this is
     * also the path that keeps headings reachable with markdown shortcuts off.
     */
    fun flowSetHeading(level: Int) {
        if (!flowText.active) return
        val flow = state.document.flow
        val sel = flowText.selection.normalized()
        val last = sel.end.para.coerceAtMost(flow.paragraphs.size - 1)
        if (sel.start.para > last) return
        // Read before the flag moves: only a paragraph that *was* a heading gets its
        // runs reset, so choosing Body never strips hand-applied bold from body text.
        val were = (sel.start.para..last).map { flow.paragraphs[it].headingLevel > 0 }
        if (level == 0 && were.none { it }) return
        flowText.flushBurst()
        val ed = FlowEditor(flow)
        val cmds = mutableListOf<Command>()
        val style = if (level > 0) Paragraph.headingStyle(level, flow.defaultSizePt) else null
        ed.setParaStyle(sel) { it.headingLevel = level }?.let { cmds += it }
        for (i in sel.start.para..last) {
            val para = flow.paragraphs[i]
            if (para.length == 0 || para.table != null) continue
            if (style == null && !were[i - sel.start.para]) continue
            ed.setCharStyle(FlowRange(FlowPos(i, 0), FlowPos(i, para.length))) {
                it.copy(bold = style != null, sizePt = style?.sizePt)
            }?.let { cmds += it }
        }
        flowText.commitEdit(ed.combined(cmds), null)
        if (sel.collapsed) flowText.pendingStyle = style ?: CharStyle.DEFAULT
        flowSelTick++
    }

    // --- the slash command menu ---

    /** The slash whose menu the user dismissed, so it stays shut until they move off it. */
    internal var slashDismissed: FlowPos? = null

    /** Row the arrow keys have picked, or -1 while the highlight is still implicit. */
    var slashSelected by mutableStateOf(-1)
        private set

    /**
     * The open slash query, or null. Read straight off the flow during composition,
     * so the menu tracks the caret with no state to keep in step; [flowSelTick] and
     * [contentVersion] are what make the read happen again.
     */
    fun slashQuery(): SlashCommands.Query? {
        if (!slashCommands || !flowText.active) return null
        val sel = flowText.selection
        if (!sel.collapsed) return null
        val q = SlashCommands.queryAt(state.document.flow, sel.start) ?: return null
        val d = slashDismissed
        if (d != null && d.para == q.para && d.offset == q.slash) return null
        return q
    }

    /** The caret's viewport rect, which the menu hangs under. */
    fun slashAnchor(): Rect? = flowText.caretViewportRect()

    /** The canvas viewport in px, so chrome anchored to a point can keep itself on screen. */
    fun viewportSize(): Pt = Pt(state.viewportW.toDouble(), state.viewportH.toDouble())

    /**
     * What a floating toolbar covers of this pane's pages, in px per edge. The fits, page jumps and
     * scroll range keep clear of it, and whatever sat at the clear area's corner stays there.
     */
    fun setToolbarCover(left: Double, top: Double, right: Double, bottom: Double) {
        val st = state
        if (st.insetLeft == left && st.insetTop == top && st.insetRight == right && st.insetBottom == bottom) return
        val widthChanged = left + right != st.insetLeft + st.insetRight
        st.scrollX -= left - st.insetLeft
        st.scrollY -= top - st.insetTop
        st.insetLeft = left
        st.insetTop = top
        st.insetRight = right
        st.insetBottom = bottom
        if (widthChanged && st.didInitialFit) st.reflowFitWidthForResize()
        st.clampScroll()
        refreshView()
        view.requestRender()
    }

    /** Shut the menu for the slash it is on, leaving the typed text alone. */
    fun dismissSlashMenu() {
        val q = slashQuery() ?: return
        slashDismissed = FlowPos(q.para, q.slash)
        slashSelected = -1
        flowSelTick++
    }

    /**
     * The entry Enter would commit. Arrowing to a row arms it outright; until then
     * the top ready row is armed, but only once a keyword is under way, so a bare
     * "/" still breaks the line rather than running whatever happens to be first.
     */
    fun slashArmed(query: SlashCommands.Query, entries: List<SlashCommands.Entry>): SlashCommands.Entry? {
        entries.getOrNull(slashSelected)?.let { return it }
        if (query.text.isEmpty()) return null
        return entries.firstOrNull { SlashCommands.ready(query, it) }
    }

    /** Arrow the menu's highlight by [delta], wrapping; false when no menu is open. */
    private fun moveSlashSelection(delta: Int): Boolean {
        val query = slashQuery() ?: return false
        val count = SlashCommands.candidates(query).size
        if (count == 0) return false
        val from = if (slashSelected in 0 until count) slashSelected else if (delta > 0) -1 else 0
        slashSelected = ((from + delta) % count + count) % count
        flowSelTick++
        return true
    }

    /**
     * Enter while the menu is open commits its first ready entry; true when it did.
     * A bare "/" commits nothing, so ending a line on a slash still just breaks it.
     */
    private fun commitSlashMenu(): Boolean {
        val q = slashQuery() ?: return false
        val entry = slashArmed(q, SlashCommands.candidates(q)) ?: return false
        return runSlash(q, entry)
    }

    /**
     * Run [entry] against [q]: the query text is deleted first, as its own undo step,
     * then the command applies to the clean paragraph. Arguments are validated before
     * anything is deleted, so a bad one leaves the typed text where the user can fix it.
     */
    fun runSlash(q: SlashCommands.Query, entry: SlashCommands.Entry): Boolean {
        if (!flowText.active || !SlashCommands.ready(q, entry)) return false
        val arg = q.arg
        val size = if (entry.kind == SlashCommands.Kind.SIZE) arg.toDoubleOrNull() ?: return false else 0.0
        val color = if (entry.kind == SlashCommands.Kind.COLOR) SvgColors.parse(arg) ?: return false else null
        val stamp = when (entry.kind) {
            SlashCommands.Kind.DATE -> dateStamp(java.time.format.FormatStyle.MEDIUM, time = false)
            SlashCommands.Kind.TIME -> dateStamp(java.time.format.FormatStyle.SHORT, time = true)
            else -> null
        }
        flowText.flushBurst()
        // The IME still holds the query text the strip is about to remove.
        flowText.mirrorStale = true
        val flow = state.document.flow
        val (cmd, caret) = if (stamp != null) {
            SlashCommands.replace(flow, q, stamp)
        } else {
            SlashCommands.strip(flow, q)
        }
        // The strip rides along with whatever the command does next, so the pair is a
        // single undo: one step puts the whole query back as the text that was typed.
        flowText.placeCaret(caret)
        flowText.pendingPrefix = cmd
        slashDismissed = null
        when (entry.kind) {
            SlashCommands.Kind.HEADING -> flowSetHeading(entry.level)
            SlashCommands.Kind.BODY -> flowSetHeading(0)
            SlashCommands.Kind.BULLET -> flowSetList(ListKind.BULLET)
            SlashCommands.Kind.ORDERED -> flowSetList(ListKind.ORDERED)
            SlashCommands.Kind.TODO -> flowSetList(ListKind.CHECK)
            SlashCommands.Kind.CODE -> flowSetCodeLanguage(slashCodeLanguage(arg))
            SlashCommands.Kind.TABLE -> slashInsertTable(arg)
            SlashCommands.Kind.SIZE -> flowSetChar { it.copy(sizePt = size.coerceIn(6.0, 96.0)) }
            SlashCommands.Kind.COLOR -> flowSetCharColor(color)
            SlashCommands.Kind.MATH -> flowInsertMath(arg)
            SlashCommands.Kind.DATE, SlashCommands.Kind.TIME -> Unit
        }
        // A command that changed nothing (already a bullet, say) leaves the strip
        // unpushed, so push it alone rather than lose the undo for it.
        flowText.pendingPrefix?.let { leftover ->
            flowText.pendingPrefix = null
            flowText.commitEdit(leftover, null)
        }
        flowSelTick++
        return true
    }

    /** Today's date or the time, in the reader's locale. */
    private fun dateStamp(style: java.time.format.FormatStyle, time: Boolean): String {
        val fmt = if (time) {
            java.time.format.DateTimeFormatter.ofLocalizedTime(style)
        } else {
            java.time.format.DateTimeFormatter.ofLocalizedDate(style)
        }
        return java.time.LocalDateTime.now().format(fmt)
    }

    /** The language "/code xyz" means: a known token, else whatever the bar last used. */
    private fun slashCodeLanguage(arg: String): String =
        codeLanguageChoices().firstOrNull { it.equals(arg, ignoreCase = true) } ?: lastCodeLanguage()

    /** "/table 3x4" sizes the grid; anything unparsed falls back to the saved default. */
    private fun slashInsertTable(arg: String) {
        val m = Regex("^(\\d{1,2})\\s*[x×*]\\s*(\\d{1,2})$").find(arg.trim())
        val rows = m?.groupValues?.get(1)?.toIntOrNull() ?: newTableDefaults.rows
        val cols = m?.groupValues?.get(2)?.toIntOrNull() ?: newTableDefaults.cols
        flowInsertTable(rows, cols, newTableDefaults.style)
    }

    /** Toggle the paragraph(s) into code lines in the last-used language, or back to plain. */
    fun flowToggleCode() {
        if (flowCaretParagraph()?.codeLang != null) {
            flowParaOp { it.codeLang = null }
        } else {
            flowStartCode(lastCodeLanguage())
        }
    }

    /** Language token ("plain" or a tree-sitter id) the code toggle arms next. */
    fun lastCodeLanguage(): String =
        settings.prefs.lastCodeLanguage.ifEmpty { settings.prefs.defaultCodeLanguage }

    /** Tokens the language menu offers: plain plus every highlightable language. */
    fun codeLanguageChoices(): List<String> =
        listOf("plain") + (if (treeSitterAvailable) scmLanguages() else emptyList())

    /** Pick a code language: retarget the caret's whole code block, or start one. */
    fun flowSetCodeLanguage(token: String) {
        if (!flowText.active) return
        if (flowCaretParagraph()?.codeLang != null) {
            val lang = if (token == "plain") "" else token
            val flow = state.document.flow
            val sel = flowText.selection.normalized()
            var first = sel.start.para
            while (first > 0 && flow.paragraphs[first - 1].codeLang != null) first--
            var last = sel.end.para.coerceAtMost(flow.paragraphs.size - 1)
            while (last + 1 < flow.paragraphs.size && flow.paragraphs[last + 1].codeLang != null) last++
            flowText.flushBurst()
            flowText.commitEdit(
                FlowEditor(flow).setParaStyle(FlowRange(FlowPos(first, 0), FlowPos(last, 0))) {
                    if (it.codeLang != null) it.codeLang = lang
                },
                null,
            )
            flowSelTick++
        } else {
            flowStartCode(token)
        }
        settings = settings.copy(prefs = settings.prefs.copy(lastCodeLanguage = token))
        settingsRepo.save(settings)
    }

    /** Make the selected paragraph(s) code lines in [token]'s language. */
    private fun flowStartCode(token: String) {
        val lang = if (token == "plain") "" else token
        flowParaOp {
            it.codeLang = lang
            it.list = ListKind.NONE
            it.checked = false
        }
    }

    fun flowCycleAlign() {
        val next = when (flowCaretParagraph()?.align ?: ParaAlign.LEFT) {
            ParaAlign.LEFT -> ParaAlign.CENTER
            ParaAlign.CENTER -> ParaAlign.RIGHT
            ParaAlign.RIGHT -> ParaAlign.JUSTIFY
            ParaAlign.JUSTIFY -> ParaAlign.LEFT
        }
        flowParaOp { it.align = next }
    }

    fun flowIndent(delta: Int) = flowParaOp { it.indent += delta }

    /** Tab inside a list item nests it (Shift un-nests) rather than typing a tab. */
    private fun flowTabIndent(back: Boolean): Boolean {
        val para = flowCaretParagraph() ?: return false
        if (para.list == ListKind.NONE) return false
        if (back && para.indent == 0) return true
        flowIndent(if (back) -1 else 1)
        return true
    }

    // --- flow tables ---

    /** The size and look new tables start from (factory values = nothing saved). */
    var newTableDefaults by mutableStateOf(settings.newTable)
        private set

    /** Save (or, passing factory values, forget) what the insert dialog starts from. */
    fun saveNewTableDefaults(d: TableDefaults) {
        if (newTableDefaults == d) return
        newTableDefaults = d
        settings = settings.copy(newTable = d)
        settingsRepo.save(settings)
    }

    /** A table's Auto rule colour: a grey, lighter on dark paper and darker on light paper. */
    fun tableRuleColor(): Rgba = if (palette.isDark) Rgba(140, 140, 140) else Rgba(100, 100, 100)

    /** The table the caret or selection start sits in, or null. */
    fun flowCaretTable(): FlowTable? = flowCaretParagraph()?.table

    /** Insert an empty table at the caret (never inside another table) as one undo step. */
    fun flowInsertTable(rows: Int, cols: Int, style: TableStyle) {
        if (!flowText.active) return
        flowText.flushBurst()
        val (cmd, caret) = TableEditor(state.document.flow).insertTable(
            flowText.selection.normalized().end,
            rows.coerceIn(1, TableDefaults.MAX_ROWS),
            cols.coerceIn(1, TableDefaults.MAX_COLS),
            style.clamped(),
        ) ?: return
        flowText.commitEdit(cmd, caret)
        flowText.requestIme() // the dialog took the keyboard; the new first cell wants it back
        flowSelTick++
    }

    /** The table whose style dialog is open (from its long-press menu), or null. */
    var tableStyling by mutableStateOf<FlowTable?>(null)
        private set

    fun openTableStyle(table: FlowTable) {
        dismissTableMenus()
        tableStyling = table
    }

    fun dismissTableMenu() {
        tableMenu = null
    }

    /** Both action bars go when a table action runs. */
    private fun dismissTableMenus() {
        flowContextMenu = null
        tableMenu = null
    }

    fun closeTableStyle() {
        tableStyling = null
        flowReshowIme()
    }

    /** A dialog took the keyboard from a live caret: give it back. */
    fun flowReshowIme() {
        if (flowText.active) flowText.requestIme()
    }

    /**
     * Enter structure-edit mode on [table]. The caret session ends (keyboard away,
     * cells not writable) but the flow stays lifted out of the page caches, so the
     * live previews of width and height drags repaint without re-baking a page.
     */
    fun startTableEdit(table: FlowTable) {
        dismissTableMenus()
        flowText.endSession()
        editingTable = table
        setFlowLifted(true)
        tableChromeTick++
    }

    fun endTableEdit() {
        if (editingTable == null) return
        editingTable = null
        if (!flowText.active) setFlowLifted(false)
    }

    private fun setFlowLifted(lifted: Boolean) {
        if (state.flowLifted == lifted) return
        state.flowLifted = lifted
        publishedFlow?.frame?.pagesWithLines()?.forEach { i ->
            state.document.pages.getOrNull(i)?.let(state::invalidatePage)
        }
        onRender()
    }

    /** True while [editingTable] is still in the document (undo can take it away). */
    private fun tableEditLive(): Boolean {
        val t = editingTable ?: return false
        if (CellIndex(state.document.flow.paragraphs).blockOf(t) != null) return true
        endTableEdit()
        return false
    }

    /** [table]'s page slices in the published layout (page index attached). */
    fun tableFrags(table: FlowTable): List<Pair<Int, com.xnotes.core.text.TableFrag>> =
        publishedFlow?.frame?.fragsOf(table).orEmpty()

    /** A page-local rect on page [pageIndex] in viewport px, or null when that page is not laid out. */
    fun pageRectToViewport(pageIndex: Int, r: Rect): Rect? {
        if (state.pageRects.getOrNull(pageIndex) == null) return null
        val c = state.fromPageSpaceRect(pageIndex, r)
        val tl = state.contentToViewport(Pt(c.left, c.top))
        val br = state.contentToViewport(Pt(c.right, c.bottom))
        return Rect(tl.x, tl.y, br.x - tl.x, br.y - tl.y)
    }

    /** Viewport px per content px. */
    val viewZoom: Double get() = state.zoom

    /** Apply one table edit as its own undo step; edit mode ends if the table went away. */
    private fun tableEdit(build: (TableEditor) -> Pair<Command, FlowPos?>?) {
        flowText.flushBurst()
        val (cmd, caret) = build(TableEditor(state.document.flow)) ?: return
        flowText.commitEdit(cmd, caret)
        tableEditLive()
        tableChromeTick++
        flowSelTick++
    }

    fun tableInsertRow(table: FlowTable, at: Int) = tableEdit { it.insertRow(table, at) }
    fun tableInsertCol(table: FlowTable, at: Int) = tableEdit { it.insertCol(table, at) }
    fun tableDeleteRow(table: FlowTable, row: Int) = tableEdit { it.deleteRow(table, row) }
    fun tableDeleteCol(table: FlowTable, col: Int) = tableEdit { it.deleteCol(table, col) }
    fun tableMoveRow(table: FlowTable, from: Int, to: Int) = tableEdit { ed -> ed.moveRow(table, from, to)?.let { it to null } }
    fun tableMoveCol(table: FlowTable, from: Int, to: Int) = tableEdit { ed -> ed.moveCol(table, from, to)?.let { it to null } }

    fun tableDelete(table: FlowTable) {
        dismissTableMenus()
        tableEdit { it.deleteTable(table) }
        endTableEdit()
    }

    /** Show [snapshot] on [table] without an undo step (a drag or the style popup in progress). */
    fun tablePreview(table: FlowTable, snapshot: TableSnapshot) {
        if (table.snapshot() == snapshot) return
        snapshot.applyTo(table)
        state.document.flow.touch()
        state.document.dirty = true
        republishFlow(invalidate = !state.flowLifted)
        onRender()
    }

    /** Close a preview begun at [before]: the table's current state lands as one undo step. */
    fun tableCommitPreview(table: FlowTable, before: TableSnapshot) {
        val after = table.snapshot()
        if (after == before) return
        before.applyTo(table)
        tableEdit { ed -> ed.setSnapshot(table, after)?.let { it to null } }
    }

    /** The magic wand: column widths that even out the cell heights across each row. */
    fun tableAutoFit(table: FlowTable) {
        dismissTableMenus()
        val frag = tableFrags(table).firstOrNull()?.second ?: return
        val widths = flowLayout.fitColumns(state.document.flow, table, frag.right - frag.left)
        tableEdit { ed -> ed.setSnapshot(table, table.snapshot().copy(widths = widths))?.let { it to null } }
    }

    // --- flow document config (the text tool's popup: margins + defaults; not undoable) ---

    /** The document's flow defaults as one value, for the text tool's config popup. */
    fun flowConfigValue(): FlowDefaults = FlowDefaults.of(state.document.flow)

    /** Replace the document's flow defaults (clamped), relayout, and mark it dirty. */
    fun setFlowConfig(config: FlowDefaults) {
        val next = config.copy(
            sizePt = config.sizePt.coerceIn(6.0, 96.0),
            margins = FlowMargins(
                config.margins.leftMm.coerceIn(FlowMargins.MIN_MM, FlowMargins.MAX_MM),
                config.margins.topMm.coerceIn(FlowMargins.MIN_MM, FlowMargins.MAX_MM),
                config.margins.rightMm.coerceIn(FlowMargins.MIN_MM, FlowMargins.MAX_MM),
                config.margins.bottomMm.coerceIn(FlowMargins.MIN_MM, FlowMargins.MAX_MM),
            ),
        )
        if (next == FlowDefaults.of(state.document.flow)) return
        next.applyTo(state.document.flow)
        flowConfigChanged()
    }

    private fun flowConfigChanged() {
        state.document.dirty = true
        republishFlow(invalidate = true)
        if (flowText.active) flowText.ensureCaretVisible()
        refreshContent()
        onRender()
    }

    // --- user code themes (Helix .toml) ---

    val treeSitterAvailable: Boolean get() = highlighter != null

    fun scmLanguages(): List<String> = com.xnotes.platform.TreeSitterHighlighter.SUPPORTED.sorted()

    val hasCustomCodeTheme: Boolean get() = customCodeTheme != null

    /** Adopt a Helix theme file for code colours; reports via [message]. */
    fun importCodeTheme(bytes: ByteArray, sourceName: String? = null) {
        val parsed = com.xnotes.format.HelixTheme.parse(String(bytes, Charsets.UTF_8))
        if (parsed == null) {
            message = appContext.getString(R.string.err_helix_theme)
            return
        }
        val file = java.io.File(java.io.File(appContext.filesDir, "theme").apply { mkdirs() }, "code.toml")
        runCatching { file.writeBytes(bytes) }.onFailure {
            message = appContext.getString(R.string.err_store_theme)
            return
        }
        customCodeTheme = parsed
        applyPreferences(settings.prefs.copy(codeThemePath = file.path, codeThemeName = sourceName))
        retheme()
        message = appContext.getString(R.string.code_theme_imported)
    }

    /** Write every setting (pens, colours, toolbars, preferences, sync options) and the imported
     *  fonts, templates, stickers and code theme to [out] as one zip. The Filen login stays behind. IO. */
    fun exportSettings(out: java.io.OutputStream) {
        com.xnotes.settings.SettingsBackup.export(settings.toJson(), appContext.filesDir, out)
    }

    /** Adopt settings from a zip written by [exportSettings], applying them live in both panes. */
    suspend fun importSettings(input: java.io.InputStream): Boolean {
        val current = settings.toJson()
        val adopted = withContext(Dispatchers.IO) {
            runCatching { com.xnotes.settings.SettingsBackup.import(input, current, appContext.filesDir) }.getOrNull()
        } ?: return false
        settings = com.xnotes.settings.Settings.fromJson(adopted)
        settingsRepo.save(settings)
        com.xnotes.platform.FontCatalog.init(appContext)
        com.xnotes.platform.TemplateLibrary.init(appContext)
        for (editor in listOfNotNull(this, sibling)) {
            editor.stickers = editor.listStickers()
            editor.customCodeTheme = settings.prefs.codeThemePath?.let { path ->
                runCatching { com.xnotes.format.HelixTheme.parse(java.io.File(path).readText()) }.getOrNull()
            }
            editor.applySettings()
        }
        applyPreferences(settings.prefs)
        for (editor in listOfNotNull(this, sibling)) editor.retheme()
        return true
    }

    /** Back to the built-in dark/light code colours. */
    fun resetCodeTheme() {
        settings.prefs.codeThemePath?.let { runCatching { java.io.File(it).delete() } }
        customCodeTheme = null
        applyPreferences(settings.prefs.copy(codeThemePath = null, codeThemeName = null))
        retheme()
    }

    /** Colours changed, classification didn't: republish so frames rebuild with the new theme. */
    private fun retheme() {
        republishFlow(invalidate = true)
        onRender()
    }

    // --- user fonts (Preferences imports .ttf/.otf; notes reference them by name) ---

    val customFonts = mutableStateListOf<com.xnotes.platform.FontCatalog.Choice>().apply {
        addAll(com.xnotes.platform.FontCatalog.customFonts())
    }

    /** Adopt a font file for the pickers; reports via [message]. */
    fun importFont(bytes: ByteArray, sourceName: String?) {
        com.xnotes.platform.FontCatalog.importFont(appContext, bytes, sourceName)
            .onSuccess { message = appContext.getString(R.string.font_imported, it.id) }
            .onFailure { message = it.message ?: appContext.getString(R.string.err_import_font) }
        fontsChanged()
    }

    /** Adopt [bytes] as a user page template, reporting the outcome as a message. */
    fun importTemplate(bytes: ByteArray) {
        if (bytes.size > com.xnotes.format.TemplateReader.MAX_BYTES) {
            message = appContext.getString(R.string.err_import_template, appContext.getString(R.string.template_too_large))
            return
        }
        runCatching { TemplateLibrary.import(String(bytes, Charsets.UTF_8)) }
            .onSuccess {
                message = appContext.getString(R.string.template_imported, it.template.name)
                TemplateLibraryUi.version++
            }
            .onFailure { message = appContext.getString(R.string.err_import_template, it.message ?: "") }
    }

    /** Forget an imported template. Notes that use it keep drawing it from their own copy. */
    fun removeTemplate(key: String) {
        TemplateLibrary.remove(key)
        TemplateLibraryUi.version++
    }

    /** Add a template this note carries, but the library lacks, to the library. */
    fun keepNoteTemplate(key: String) {
        state.document.templates[key]?.let { importTemplate(it.toByteArray(Charsets.UTF_8)) }
    }

    /** The templates to offer: the library's, then any this note carries that the library lacks. */
    fun templateChoices(): List<TemplateLibrary.Entry> {
        val lib = TemplateLibrary.all()
        val known = lib.mapTo(HashSet()) { it.key }
        val carried = state.document.templates.mapNotNull { (key, text) ->
            if (key in known) return@mapNotNull null
            val t = TemplateLibrary.template(state.document, key) ?: return@mapNotNull null
            TemplateLibrary.Entry(key, t, text, TemplateLibrary.Source.NOTE)
        }
        return lib + carried.sortedBy { it.template.name.lowercase() }
    }

    fun templateFor(key: String): com.xnotes.core.template.Template? = TemplateLibrary.template(state.document, key)

    /** The note's resolution: content px per inch, for turning template mm into the style's px. */
    val documentDpi: Int get() = state.document.dpi

    /** The current page's size in mm, for template previews. */
    val currentPageMm: Pair<Double, Double>
        get() {
            val p = state.document.pages.getOrNull(state.currentPageIndex()) ?: return 210.0 to 297.0
            val k = 25.4 / state.document.dpi
            return p.width * k to p.height * k
        }

    fun removeCustomFont(face: FontFace) {
        com.xnotes.platform.FontCatalog.removeCustomFont(face)
        fontsChanged()
    }

    /**
     * The bar's equation control: selected text becomes the formula it spells.
     * Plain maths ("lim x->0 sin(x)/x") is rewritten as LaTeX first; text that
     * already is LaTeX is kept. With nothing selected there is nothing to set,
     * so it says so rather than leaving a placeholder the caret has already
     * stepped out of.
     */
    fun flowToggleMath() {
        if (!flowText.active) return
        val sel = flowText.selection.normalized()
        if (mathKeyboardShown) {
            setMathKeyboard(false)
            return
        }
        // With nothing selected the button starts a formula at the caret, typed on the maths keyboard.
        if (sel.collapsed) {
            flowText.pendingStyle = flowCaretStyle().copy(math = true)
            setMathKeyboard(true)
            return
        }
        val on = !flowCaretStyle().math
        flowText.flushBurst()
        if (on && sel.start.para == sel.end.para) {
            val plain = state.document.flow.paragraphs[sel.start.para].plainText().substring(sel.start.offset, sel.end.offset)
            // Spaces around the selection stay outside the formula, as plain text.
            val from = sel.start.offset + (plain.length - plain.trimStart().length)
            val to = sel.end.offset - (plain.length - plain.trimEnd().length)
            if (to > from) {
                val range = FlowRange(FlowPos(sel.start.para, from), FlowPos(sel.start.para, to))
                val ed = FlowEditor(state.document.flow)
                val style = ed.styleAtRangeStart(range).copy(math = true)
                val (cmd, caret) = ed.replaceRange(range, PlainMath.toLatex(plain.trim()), style)
                flowText.mirrorStale = true
                flowText.commitEdit(cmd, caret)
                flowSelTick++
                setMathKeyboard(true)
                return
            }
        }
        flowText.commitEdit(
            FlowEditor(state.document.flow).setCharStyle(sel) { it.copy(math = on) },
            null,
        )
        flowSelTick++
    }

    /** One find-in-note hit: the boxes (page space) it covers on page [page]. */
    class FindHit(val page: Int, val rects: List<com.xnotes.core.geometry.Rect>)

    var findOpen by mutableStateOf(false)
        private set
    var findQuery by mutableStateOf("")
        private set
    var findHits by mutableStateOf<List<FindHit>>(emptyList())
        private set
    var findIndex by mutableStateOf(0)
        private set
    private var findJob: kotlinx.coroutines.Job? = null

    fun toggleFind() {
        findOpen = !findOpen
        runFind()
    }

    fun setFind(query: String) {
        findQuery = query
        runFind()
    }

    /** Search the flowing text, every text box and the note's PDF text for [findQuery]. */
    private fun runFind() {
        findJob?.cancel()
        val q = findQuery.trim().lowercase()
        if (!findOpen || q.isEmpty()) {
            findHits = emptyList()
            publishFind()
            return
        }
        val doc = state.document
        val local = ArrayList<FindHit>()
        val frame = publishedFlow?.frame
        doc.flow.paragraphs.forEachIndexed { p, para ->
            val text = para.plainText().lowercase()
            var at = text.indexOf(q)
            while (at >= 0) {
                val rects = frame?.selectionRects(FlowRange(FlowPos(p, at), FlowPos(p, at + q.length))).orEmpty()
                rects.groupBy { it.first }.forEach { (page, rs) -> local.add(FindHit(page, rs.map { it.second })) }
                at = text.indexOf(q, at + q.length)
            }
        }
        doc.pages.forEachIndexed { i, page ->
            for (item in page.items) {
                if (item is com.xnotes.core.model.TextItem && item.text.lowercase().contains(q)) local.add(FindHit(i, listOf(item.bounds())))
            }
        }
        val pdf = doc.pdfFile
        findJob = autosaveScope.launch {
            val fromPdf = if (pdf == null) emptyList() else {
                delay(200)
                withContext(Dispatchers.IO) {
                    val text = com.xnotes.platform.PdfText.pages(appContext, pdf)
                    val hits = com.xnotes.platform.PdfText.find(text, q)
                    doc.pages.flatMapIndexed { i, page ->
                        val n = page.pdfPage ?: return@flatMapIndexed emptyList()
                        hits.filter { it.page == n }.map { m ->
                            FindHit(i, m.rects.map { r -> com.xnotes.core.geometry.Rect(r.x * page.width, r.y * page.height, r.w * page.width, r.h * page.height) })
                        }
                    }
                }
            }
            findHits = (local + fromPdf).sortedWith(compareBy({ it.page }, { it.rects.first().y }, { it.rects.first().x }))
            findIndex = 0
            revealFind()
        }
    }

    fun findStep(forward: Boolean) {
        if (findHits.isEmpty()) return
        findIndex = (findIndex + if (forward) 1 else findHits.size - 1) % findHits.size
        revealFind()
    }

    /** Bring the current hit on screen, unless it already is, and repaint the highlights. */
    private fun revealFind() {
        publishFind()
        val hit = findHits.getOrNull(findIndex) ?: return
        val r = hit.rects.firstOrNull() ?: return
        if (state.pageRects.getOrNull(hit.page) == null) return
        val content = state.fromPageSpaceRect(hit.page, r)
        val top = state.contentToViewport(Pt(content.left, content.top))
        val bottom = state.contentToViewport(Pt(content.right, content.bottom))
        val visible = top.y >= state.insetTop && bottom.y <= state.viewportH - state.insetBottom &&
            top.x >= 0 && bottom.x <= state.viewportW
        if (visible) return
        state.goToPage(hit.page)
        if (state.verticalScroll) {
            val mid = state.contentToViewport(Pt(0.0, content.centerY)).y
            state.scrollBy(0.0, mid - (state.insetTop + state.clearH / 2.0))
        }
        afterView()
    }

    private fun publishFind() {
        controller.findHighlights = findHits.flatMap { h -> h.rects.map { h.page to it } }
        controller.findCurrent = findHits.getOrNull(findIndex)?.let { h -> h.rects.map { h.page to it } }.orEmpty()
        onRender()
    }

    /** The maths keyboard stands in for the soft keyboard while a formula is typed into the flow. */
    var mathKeyboardShown by mutableStateOf(false)
        private set

    fun setMathKeyboard(on: Boolean) {
        if (mathKeyboardShown == on) return
        mathKeyboardShown = on
        // Text typed after the formula is plain again.
        if (!on && flowText.active) flowText.pendingStyle = flowCaretStyle().copy(math = false, mathDisplay = false)
        flowInput.imeSuppressed = on
    }

    /** Type a maths key's [template] into the formula at the caret, the cursor landing at its `#`. */
    fun flowMathKey(template: String) {
        if (!flowText.active) return
        val sel = flowText.selection.normalized()
        val para = state.document.flow.paragraphs.getOrNull(sel.start.para) ?: return
        val plain = para.plainText()
        val selected = if (sel.start.para == sel.end.para) plain.substring(sel.start.offset, sel.end.offset) else ""
        val (text, cursor) = latexInsertion(plain.substring(0, sel.start.offset), selected, template)
        val style = flowCaretStyle().copy(math = true)
        flowText.mirrorStale = true
        flowText.applyReplace(sel, text, style)
        flowText.setSelection(FlowRange.caret(FlowPos(sel.start.para, sel.start.offset + cursor)))
        flowText.pendingStyle = style
        flowInput.reconcile()
        flowSelTick++
    }

    fun flowMathBackspace() {
        if (!flowText.active) return
        val sel = flowText.selection.normalized()
        val flow = state.document.flow
        val range = when {
            !sel.collapsed -> sel
            sel.start.offset > 0 -> FlowRange(FlowPos(sel.start.para, sel.start.offset - 1), sel.start)
            sel.start.para > 0 -> FlowRange(FlowPos(sel.start.para - 1, flow.paragraphs[sel.start.para - 1].length), sel.start)
            else -> return
        }
        flowText.mirrorStale = true
        flowText.applyReplace(range, "")
        flowInput.reconcile()
        flowSelTick++
    }

    fun flowMathEnter() {
        if (!flowText.active) return
        flowText.mirrorStale = true
        flowText.applyReplace(flowText.selection.normalized(), "\n")
        flowInput.reconcile()
        flowSelTick++
    }

    /** Step the caret [by] characters within its paragraph (or onto the neighbouring one). */
    fun flowMathMove(by: Int) {
        if (!flowText.active) return
        val flow = state.document.flow
        val at = flowText.selection.normalized().let { if (by < 0) it.start else it.end }
        val len = flow.paragraphs.getOrNull(at.para)?.length ?: return
        val next = when {
            at.offset + by in 0..len -> FlowPos(at.para, at.offset + by)
            by < 0 && at.para > 0 -> FlowPos(at.para - 1, flow.paragraphs[at.para - 1].length)
            by > 0 && at.para < flow.paragraphs.size - 1 -> FlowPos(at.para + 1, 0)
            else -> at
        }
        flowText.setSelection(FlowRange.caret(next))
        flowText.pendingStyle = flowCaretStyle().copy(math = true)
        flowSelTick++
    }

    /**
     * Put [latex] in at the caret as a formula, drawn as one straight away. The
     * caret lands against its closing edge, which is outside it, so the next key
     * types beside the equation rather than into its LaTeX. the caret
     * lands against its closing edge, which is where the source would otherwise
     * open, and the next key types beside it rather than into it.
     */
    fun flowInsertMath(latex: String) {
        if (!flowText.active) return
        val text = PlainMath.toLatex(latex)
        if (text.isEmpty() || '\n' in text) return
        flowText.flushBurst()
        flowText.mirrorStale = true
        val at = flowText.selection.normalized().start
        val (cmd, caret) = FlowEditor(state.document.flow).insertText(at, text, CharStyle(math = true))
        flowText.commitEdit(cmd, caret)
        flowText.pendingStyle = CharStyle.DEFAULT
        flowSelTick++
    }

    /**
     * The formula open in the frame that is currently published, packed as
     * paragraph and offset, or -1. Set by [republishFlow] from the state it
     * actually laid out, so a caret move can tell whether what is on screen still
     * matches where the caret is.
     */
    private var revealedMath = -1L

    /**
     * The formula an arrow key stepped into at one of its edges, or -1. A formula's
     * edge offset is two places at once: beside the equation, and at the very end
     * (or start) of its LaTeX. Nothing in the position tells them apart, so which
     * one the caret is at is where it came from, and this is that memory.
     */
    private var mathHeld = -1L

    private fun packMath(para: Int, start: Int): Long = (para.toLong() shl 32) or start.toLong()

    /**
     * Which formula the caret is inside right now, as a single comparable value,
     * so a caret move can tell whether the paragraph needs shaping again. Inside
     * means strictly within it, or at an edge the caret was stepped onto.
     */
    private fun revealedMathKey(): Long {
        if (!flowText.active) return -1L
        val sel = flowText.selection.normalized()
        // Selecting over a formula is not editing it, and a drag whose end walks
        // through one would otherwise re-shape the flow on every pointer move.
        if (!sel.collapsed) return -1L
        val para = state.document.flow.paragraphs.getOrNull(sel.start.para) ?: return -1L
        val held = if ((mathHeld ushr 32).toInt() == sel.start.para) (mathHeld and 0xFFFFFFFFL).toInt() else -1
        val start = MathCaret.revealed(para, sel.start.offset, held)
        return if (start < 0) -1L else packMath(sel.start.para, start)
    }

    /** The math run the caret is inside, as its char offset into [para], or -1. */
    private fun revealedMathIn(para: Paragraph): Int {
        val key = revealedMathKey()
        if (key < 0L) return -1
        if (state.document.flow.paragraphs.getOrNull((key ushr 32).toInt()) !== para) return -1
        return (key and 0xFFFFFFFFL).toInt()
    }

    /** True while the caret still lies within the formula [mathHeld] names. */
    private fun heldMathHoldsCaret(): Boolean {
        if (mathHeld < 0L) return false
        val sel = flowText.selection
        if (!sel.collapsed || (mathHeld ushr 32).toInt() != sel.start.para) return false
        val para = state.document.flow.paragraphs.getOrNull(sel.start.para) ?: return false
        return MathCaret.holds(para, sel.start.offset, (mathHeld and 0xFFFFFFFFL).toInt())
    }

    /**
     * Spend an arrow press stepping into the formula the caret is standing at the
     * edge of, instead of moving past it. Going left that edge is its end, going
     * right its start, so either way the press lands the caret at the near end of
     * the LaTeX with the source open. True when the press was spent doing it.
     */
    private fun stepIntoMath(delta: Int): Boolean {
        if (!flowText.active) return false
        val sel = flowText.selection
        if (!sel.collapsed) return false
        val para = state.document.flow.paragraphs.getOrNull(sel.start.para) ?: return false
        val start = MathCaret.edgeAt(para, sel.start.offset, delta)
        if (start < 0) return false
        val key = packMath(sel.start.para, start)
        // Already inside: the press belongs to walking through the LaTeX.
        if (mathHeld == key) return false
        settleMathEdge(key)
        return true
    }

    /**
     * Spend an arrow press stepping back out of the formula the caret is held in,
     * when the press would otherwise carry it past the far edge. The caret stays
     * where it is and the formula is drawn again, so leaving reads the same way
     * round as arriving did. True when the press was spent doing it.
     */
    private fun stepOutOfMath(delta: Int): Boolean {
        if (mathHeld < 0L || !flowText.active) return false
        val sel = flowText.selection
        if (!sel.collapsed || (mathHeld ushr 32).toInt() != sel.start.para) return false
        val para = state.document.flow.paragraphs.getOrNull(sel.start.para) ?: return false
        val held = (mathHeld and 0xFFFFFFFFL).toInt()
        if (MathCaret.exitAt(para, sel.start.offset, delta, held) < 0) return false
        settleMathEdge(-1L)
        return true
    }

    /**
     * Take up [held] and lay the paragraph out for it. Crossing a formula's edge
     * changes what the caret means as surely as moving it does, so the armed
     * style goes the way [FlowTextController.placeCaret] sends it: a caret that
     * has just stepped into a formula must type into the formula, not carry in
     * the style that was waiting for it to type beside one.
     */
    private fun settleMathEdge(held: Long) {
        mathHeld = held
        flowText.pendingStyle = null
        republishFlow(invalidate = true)
        flowSelTick++
        onRender()
    }

    /**
     * The style typing at [pos] must take, or null to let the flow decide. Only
     * the held edge needs saying: everywhere else the run under the caret already
     * answers it, and this is the one place position alone cannot.
     */
    private fun mathStyleAt(pos: FlowPos): CharStyle? {
        if (mathHeld < 0L || (mathHeld ushr 32).toInt() != pos.para) return null
        val para = state.document.flow.paragraphs.getOrNull(pos.para) ?: return null
        return MathCaret.heldStyle(para, pos.offset, (mathHeld and 0xFFFFFFFFL).toInt())
    }

    /**
     * The maths renderer arrived. Until it does every formula measures as its own
     * source, so anything already on screen has to re-shape now that it will set.
     */
    fun refreshFlowMath() {
        val flowMath = state.document.flow.paragraphs.any { p -> p.runs.any { it.style.math } }
        val boxMath = state.document.pages.any { page -> page.items.any { it is TextItem && it.math } }
        if (!flowMath && !boxMath) return
        state.document.flow.reshapeAll()
        republishFlow(invalidate = true)
        state.invalidateAllCaches()
        onRender()
    }

    /** Font resolution moved under open content: re-shape, re-bake, re-list. */
    private fun fontsChanged() {
        customFonts.clear()
        customFonts.addAll(com.xnotes.platform.FontCatalog.customFonts())
        invalidateComposeFamilies()
        state.document.flow.reshapeAll()
        republishFlow(invalidate = true)
        state.invalidateAllCaches()
        refreshContent()
        onRender()
    }

    val flowHasSelection: Boolean get() = !flowText.selection.collapsed

    fun flowCut() = flowCopySelection(cut = true)

    fun flowCopy() = flowCopySelection(cut = false)

    fun flowDeleteSelection() {
        val sel = flowText.selection.normalized()
        if (!sel.collapsed) flowText.replaceExternal(sel, "")
    }

    private fun flowCopySelection(cut: Boolean) {
        val sel = flowText.selection.normalized()
        if (sel.collapsed) return
        val flow = state.document.flow
        val text = CellIndex(flow.paragraphs).textOf(flow.paragraphs, sel)
        val cm = appContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager ?: return
        cm.setPrimaryClip(android.content.ClipData.newPlainText("xnotes text", text))
        if (cut) flowText.replaceExternal(sel, "")
    }

    /**
     * Backspace at the start of a paragraph strips one block property (an empty
     * code line, a list marker, a heading, then an indent) instead of deleting
     * anything: see [InputRules.forBackspace]. Also the only way out for an empty
     * code line at the very start of the document, where there is no preceding
     * character to merge into. At a table edge it never merges across: a cell's
     * first line stays put, and the line after a table steps into its last cell.
     * Returns true when it applied.
     */
    fun flowBackspaceSpecial(): Boolean {
        if (!flowText.active) return false
        val sel = flowText.selection.normalized()
        if (!sel.collapsed || sel.start.offset != 0) return false
        val flow = state.document.flow
        if (sel.start.para !in flow.paragraphs.indices) return false
        val rule = InputRules.forBackspace(flow, sel.start)
            ?: return flowTableEdge(sel.start.para, forward = false)
        flowText.flushBurst()
        val result = InputRules.apply(flow, sel.start, rule)
        flowText.commitEdit(result.command, result.caret)
        flowText.pendingStyle = result.pending
        return true
    }

    /**
     * Forward delete at the end of a line whose NEXT line is a block (list item,
     * checkbox, code): the line is prepended into the block, which keeps its
     * paragraph properties, instead of the block being flattened into a plain
     * line. Only fires when the current line is not itself a block, so merging
     * two list items keeps the current item's state. Returns true when it applied.
     */
    fun flowForwardDeleteSpecial(): Boolean {
        if (!flowText.active) return false
        val sel = flowText.selection.normalized()
        if (!sel.collapsed) return false
        val flow = state.document.flow
        val para = flow.paragraphs.getOrNull(sel.start.para) ?: return false
        if (sel.start.offset < para.length) return false
        if (flowTableEdge(sel.start.para, forward = true)) return true
        if (para.list != ListKind.NONE || para.codeLang != null) return false
        val next = flow.paragraphs.getOrNull(sel.start.para + 1) ?: return false
        if (next.list == ListKind.NONE && next.codeLang == null) return false
        flowText.flushBurst()
        val range = FlowRange(FlowPos(sel.start.para, para.length), FlowPos(sel.start.para + 1, 0))
        val (cmd, caret) = FlowEditor(flow).replaceRange(range, "", adoptEndProps = true)
        flowText.commitEdit(cmd, caret)
        return true
    }

    /**
     * A delete at the edge of paragraph [p] that would merge across a table boundary:
     * refused at a cell's own edge, turned into a step into the table from the body
     * line beside it. Returns true when the key is spent.
     */
    private fun flowTableEdge(p: Int, forward: Boolean): Boolean {
        val flow = state.document.flow
        val cells = CellIndex(flow.paragraphs)
        val block = cells.blockAt(p)
        if (block != null) {
            val cell = cells.cellAt(p)
            return p == if (forward) block.cellLastPara(cell) else block.cellFirstPara(cell)
        }
        val neighbour = if (forward) p + 1 else p - 1
        if (!cells.inTable(neighbour)) return false
        flowText.placeCaret(FlowPos(neighbour, if (forward) 0 else flow.paragraphs[neighbour].length))
        return true
    }

    /**
     * Tab / Shift+Tab in a table: select the next / previous cell's text; Tab in the
     * last cell appends a row. False outside tables (the key types a tab).
     */
    private fun flowTabCell(back: Boolean): Boolean {
        val flow = state.document.flow
        val cells = CellIndex(flow.paragraphs)
        val p = flowText.selection.end.para
        val block = cells.blockAt(p) ?: return false
        val target = cells.cellAt(p) + if (back) -1 else 1
        if (target < 0) return true
        flowText.flushBurst()
        if (target >= block.cellCount) {
            val (cmd, caret) = TableEditor(flow).insertRow(block.table, block.rows) ?: return true
            flowText.commitEdit(cmd, caret)
            return true
        }
        val last = block.cellLastPara(target)
        flowText.setSelection(FlowRange(FlowPos(block.cellFirstPara(target), 0), FlowPos(last, flow.paragraphs[last].length)))
        flowText.ensureCaretVisible()
        return true
    }

    private fun flowDeleteKey(forward: Boolean) {
        if (!forward && flowBackspaceSpecial()) return
        if (forward && flowForwardDeleteSpecial()) return
        val sel = flowText.selection.normalized()
        if (!sel.collapsed) {
            flowText.applyReplace(sel, "")
            return
        }
        // Deleting into a drawn formula opens it first. The key travels the same
        // way an arrow does, so it steps onto the edge the same way: otherwise it
        // would eat a character of LaTeX with nothing on screen to show for it,
        // and the equation would quietly become a different one.
        if (stepIntoMath(if (forward) 1 else -1)) return
        val flow = state.document.flow
        val g = flow.globalOffset(sel.start)
        val range = if (forward) {
            if (g >= flow.globalOffset(flow.endPos())) return
            FlowRange(sel.start, flow.posAtGlobal(g + 1))
        } else {
            if (g <= 0) return
            FlowRange(flow.posAtGlobal(g - 1), sel.start)
        }
        flowText.applyReplace(range, "")
    }

    private fun flowMoveHorizontal(delta: Int, extend: Boolean) {
        // A formula's edges are stepped onto, not over: one press to come out of
        // the source, one to go in. Out is tried first, or a caret held at an edge
        // shared with the next formula would step straight from one into the other.
        if (!extend && stepOutOfMath(delta)) return
        if (!extend && stepIntoMath(delta)) return
        val flow = state.document.flow
        val g = (flow.globalOffset(flowText.selection.end) + delta)
            .coerceIn(0, flow.globalOffset(flow.endPos()))
        flowMoveTo(flow.posAtGlobal(g), extend)
    }

    private fun flowMoveWord(forward: Boolean, extend: Boolean) {
        val flow = state.document.flow
        flowMoveTo(wordBoundary(flow, flowText.selection.end, forward), extend)
    }

    private fun flowDeleteWord(forward: Boolean) {
        val sel = flowText.selection.normalized()
        if (!sel.collapsed) {
            flowText.applyReplace(sel, "")
            return
        }
        val flow = state.document.flow
        val to = wordBoundary(flow, sel.start, forward)
        if (to == sel.start) return
        val range = if (forward) FlowRange(sel.start, to) else FlowRange(to, sel.start)
        flowText.applyReplace(range, "")
    }

    private fun flowMoveVertical(dir: Int, extend: Boolean) {
        val pos = publishedFlow?.frame?.moveVertical(flowText.selection.end, dir) ?: return
        flowMoveTo(pos, extend)
    }

    private fun flowLineEdge(start: Boolean, extend: Boolean) {
        val pos = publishedFlow?.frame?.lineEdge(flowText.selection.end, start) ?: return
        flowMoveTo(pos, extend)
    }

    private fun flowMoveTo(pos: FlowPos, extend: Boolean) {
        if (extend) {
            flowText.setSelection(FlowRange(flowText.selection.start, pos))
        } else {
            flowText.placeCaret(pos)
        }
        flowText.ensureCaretVisible()
    }

    /** Feeder C entry point: route stylus side-button key presses (Bluetooth/USI pens) to the
     *  controller's held latch, and the vendor double-tap/click keycodes to their gesture handlers.
     *  Returns true when consumed, so the host swallows the key. */
    fun onStylusButtonKey(e: android.view.KeyEvent): Boolean {
        if (e.keyCode == penDoubleTapKeycode) return onPenDoubleTapKey(e)
        if (e.keyCode in penButtonTapKeycodes) return onPenButtonTapKey(e)
        val down = when (e.action) {
            android.view.KeyEvent.ACTION_DOWN -> true
            android.view.KeyEvent.ACTION_UP -> false
            else -> return false
        }
        // A canvas is on top: the same key stream drives its pen, so a Bluetooth or USI side
        // button behaves there exactly as it does on a note.
        if (canvasOpen) return infinite.onStylusButtonKey(e.keyCode, down)
        return controller.onStylusButtonKey(e.keyCode, down)
    }

    // Pens with no side button (e.g. Huawei M-Pencil) report a barrel double-tap as a vendor key
    // code with no standard mapping (718). One physical double-tap arrives as two quick presses, so
    // a pair within the window fires the mapped gesture once. Consumed only when the gesture is set.
    private val penDoubleTapKeycode = 718
    private val penDoubleTapMs = 600L
    private var lastPenTapMs = 0L
    private fun onPenDoubleTapKey(e: android.view.KeyEvent): Boolean {
        if (preferences.stylusDoubleTap == "none") return false
        if (e.action == android.view.KeyEvent.ACTION_DOWN) {
            val t = e.eventTime
            if (lastPenTapMs != 0L && t - lastPenTapMs <= penDoubleTapMs) {
                lastPenTapMs = 0L
                dispatchTapGesture(preferences.stylusDoubleTap)
            } else {
                lastPenTapMs = t
            }
        }
        return true
    }

    // Some pens report the side button as a momentary vendor key click, not a held state, so it can't
    // drive the hold latch; fire the mapped gesture on key-down, ignore up. HONOR Magic-Pencil 4s ->
    // 333; Lenovo Tab Pen Plus -> 601 (its one code with a clean down; it also emits 600/603/604 ups).
    private val penButtonTapKeycodes = setOf(333, 601)
    private fun onPenButtonTapKey(e: android.view.KeyEvent): Boolean {
        val action = preferences.stylusButtonTap
        if (action == "none") return false
        if (e.action == android.view.KeyEvent.ACTION_DOWN) dispatchTapGesture(action)
        return true
    }

    fun newNote() {
        saveViewState()
        flushAutosave()
        autosaveUri = null
        state.document = blankDocument().also { stampNewNoteDefaults(it) }
        rebuildPdfSource() // close the outgoing note's PDF source (a blank note has none)
        adoptOpenPdf(state.document) // and reclaim its temp PDF file now it's released
        history.clear()
        controller.clearSelection()
        controller.resetGestureState() // drop the outgoing note's fling/elastic so it can't bleed in
        clearPageSelection()
        state.invalidateAllCaches()
        state.relayout()
        installInitialView(null) // a fresh in-memory note: fit width
        refreshContent()
        view.requestRender()
        noteOpen = true // push the editor on top of backstage
    }

    // --- split view ---
    //
    // A split is two editors side by side, one per open file, each with its own toolbar. The primary
    // editor owns the arrangement (the secondary is a plain second instance that knows nothing about
    // it) and also owns the app-level chrome, so the backstage underneath always talks to the
    // primary. Which panes are drawn follows straight from [noteOpen]: both open is a split, one open
    // is that pane full-screen, neither is the backstage. Closing a pane is just its own [goHome].

    /** The second pane's editor while a split is open, else null. Only ever set on the primary. */
    var secondary: Editor? by mutableStateOf(null)
        private set

    /** The other pane's editor, from either side, while a split is open. */
    var sibling: Editor? = null
        private set

    /** The divider position, as the first pane's share of the split axis. */
    var splitRatio by mutableStateOf(0.5f)

    /** The pane that keyboard shortcuts and the file/export actions act on. */
    var focusedPane by mutableStateOf(Pane.PRIMARY)

    /** True while two panes are open together. */
    val inSplit: Boolean get() = secondary?.noteOpen == true && noteOpen

    /** The editor a note-scoped action should run against: the focused pane while both are open,
     *  otherwise whichever pane still has a note. */
    val active: Editor
        get() {
            val other = secondary ?: return this
            if (focusedPane == Pane.SECONDARY && other.noteOpen) return other
            if (!noteOpen && other.noteOpen) return other
            return this
        }

    /** Every pane with a note open, first pane first. Empty on the backstage. */
    val openPanes: List<Editor>
        get() = listOfNotNull(takeIf { it.noteOpen }, secondary?.takeIf { it.noteOpen })

    /** Build the second pane on first use. It shares the temp dirs and the live settings with this
     *  one, and keeps its own document, history, canvas and session slot. */
    fun secondaryPane(): Editor = secondary ?: Editor(viewContext, Pane.SECONDARY).also {
        it.keyActions = keyActions // the shortcuts already resolve their target pane themselves
        it.sibling = this
        sibling = it
        secondaryStarted = false
        secondary = it
    }

    /** Give up on a second pane whose file never opened, so a failed split leaves no stray editor. */
    fun abandonSecondary() {
        val other = secondary ?: return
        if (other.noteOpen) return
        secondaryStarted = true // it is not coming, so let the release go through
        releaseClosedSecondary()
    }

    /** Give [pane] the keyboard and the file actions. */
    fun focusPane(pane: Pane) {
        if (focusedPane != pane) focusedPane = pane
    }

    /** Set once the second pane has actually had a document pushed onto it, so it is not released in
     *  the moment between being built and its file finishing its off-thread read. */
    private var secondaryStarted = false

    /**
     * Drop the second pane once it has no note open, releasing its canvas and GL surfaces. Called by
     * the shell after a pane closes; a no-op while the split is live or still loading.
     */
    fun releaseClosedSecondary() {
        val other = secondary ?: return
        if (other.noteOpen) { secondaryStarted = true; return }
        if (!secondaryStarted) return
        secondaryStarted = false
        other.sibling = null
        sibling = null
        secondary = null
        focusedPane = Pane.PRIMARY
    }

    /** Close every open pane, so the backstage is what's left. */
    fun goHomeAll() {
        secondary?.goHome()
        goHome()
    }

    /** Pop back to backstage: detach the current note (flush autosave, drop the binding) and clear
     *  [noteOpen] so the editor is removed from the stack. The document stays as an inert buffer. */
    fun goHome() {
        if (!noteOpen) return
        // A canvas has none of the paged note's text sessions, autosave binding or thumbnails yet,
        // so leaving one is just popping the layer.
        if (canvasOpen) {
            flushCanvasThen(showOverlay = true) {
                canvasAutosaveUri = null
                canvasOpen = false
                noteOpen = false
                com.xnotes.sync.filen.FilenSyncManager.syncOnNoteExit(appContext)
            }
            return
        }
        commitText() // commit an open text box before leaving (also hides its keyboard)
        flowText.endSession() // end any live flow caret (flushes typing, hides the keyboard)
        saveViewState() // remember this folder note's view before leaving
        flushThen(showOverlay = true) {
            // Regenerate this folder note's grid tile now that editing is done (off-thread, low priority);
            // a non-folder note (no autosave binding) isn't in the explorer, so there's nothing to do.
            autosaveUri?.let { uri -> autosaveScope.launch { regenerateClosedNoteThumb(uri) } }
            autosaveUri = null
            noteOpen = false
            com.xnotes.sync.filen.FilenSyncManager.syncOnNoteExit(appContext)
        }
    }
}
