package com.xnotes

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.focusable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.IntentCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.xnotes.R
import com.xnotes.ui.CanvasToastPill
import com.xnotes.ui.Editor
import com.xnotes.ui.Toolbar
import com.xnotes.ui.ToolbarAround
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.XnotesTheme
import com.xnotes.ui.theme.toComposeColor
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.hrm.latex.renderer.export.rememberLatexExporter
import com.hrm.latex.renderer.measure.rememberLatexMeasurer

/** Minimum time the launch loader stays up, so its animation is briefly seen even
 *  when the session restores instantly. */
private const val MIN_LOADER_MS = 600L

/** Whether the display has a camera cutout (notch/hole-punch). False below API 29, which has no
 *  cutout API; such devices fall back to fullscreen by default. Also false for a context with no
 *  display of its own, which cannot answer the question. */
internal fun deviceHasDisplayCutout(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
    val display = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) context.display
        else @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
    }.getOrNull()
    return display?.cutout != null
}

/** The whole screen's shorter side in dp, the same in any orientation, window or split; 0 if unknown. */
internal fun deviceShortSideDp(context: Context): Double {
    val metrics = runCatching {
        DisplayMetrics().also {
            @Suppress("DEPRECATION")
            context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(it)
        }
    }.getOrNull() ?: return 0.0
    return minOf(metrics.widthPixels, metrics.heightPixels) / metrics.density.toDouble()
}

/** The standard touch action an old One UI S-Pen-button code stands in for, or -1 for anything else. */
private fun standardPenAction(action: Int): Int = when (action) {
    211 -> MotionEvent.ACTION_DOWN
    212 -> MotionEvent.ACTION_UP
    213 -> MotionEvent.ACTION_MOVE
    else -> -1
}

class MainActivity : ComponentActivity() {

    // The editor owns the fullscreen state (persisted preference, default depends on the display
    // cutout); this activity just applies it to the window. Fullscreen draws edge to edge and lets
    // the swipe-in transient bars overlay (no resize), non-fullscreen insets under the bars.
    private var editor: Editor? = null
    // A PDF handed to us by another app ("Open with" / Share); consumed once the editor is ready.
    private var pendingPdfImport by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.xnotes.platform.FontCatalog.init(this)
        com.xnotes.platform.TemplateLibrary.init(this)
        setTheme(com.xnotes.R.style.Theme_Xnotes) // leave the dark launch/splash theme behind
        applyFullscreen(!deviceHasDisplayCutout(this)) // provisional; reconciled once the editor loads prefs
        pendingPdfImport = pdfImportUri(intent)
        setContent {
            val context = LocalContext.current
            val ed = remember { Editor(context).also { editor = it } }
            LaunchedEffect(ed.fullscreen) { applyFullscreen(ed.fullscreen) }
            // Re-arm the Filen periodic sync to match the saved preferences.
            LaunchedEffect(Unit) { com.xnotes.sync.filen.FilenSyncManager.reschedule(context) }
            var ready by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                val start = android.os.SystemClock.uptimeMillis()
                ed.restoreSession() // heavy load off-thread; loader animates meanwhile
                val elapsed = android.os.SystemClock.uptimeMillis() - start
                if (elapsed < MIN_LOADER_MS) kotlinx.coroutines.delay(MIN_LOADER_MS - elapsed)
                ready = true
                ed.prewarmBackstage() // warm recents/explorer caches so the first backstage open is instant
            }
            XnotesTheme(ed.palette, ed.cornerStyle) {
                CompositionLocalProvider(com.xnotes.ui.LocalToolbarLook provides ed.toolbarLook) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (ready) EditorScreen(
                            ed,
                            fullscreen = ed.fullscreen,
                            onToggleFullscreen = ed::toggleFullscreen,
                            importPdfUri = pendingPdfImport,
                            onImportConsumed = { pendingPdfImport = null },
                        )
                        androidx.compose.animation.AnimatedVisibility(
                            visible = !ready,
                            enter = androidx.compose.animation.EnterTransition.None,
                            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(280)),
                        ) {
                            com.xnotes.ui.XnotesLoader()
                        }
                        com.xnotes.ui.EyedropperOverlay()
                    }
                }
            }
        }
    }

    // Stylus side-button keys (Bluetooth/USI pens report the button only this way) are caught here,
    // before Compose focus routing, so the controller sees both press and release regardless of
    // which view holds focus. Other keys fall through to the normal dispatch.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // The pen writes in whichever pane has the focus, so its button belongs to that pane too.
        if (editor?.active?.onStylusButtonKey(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    // Older Samsung builds (seen on a Tab S6 Lite, Android 13; gone by Android 15) tag a stylus
    // stroke made with the S-Pen button held with proprietary action codes instead of DOWN/MOVE/UP,
    // so the view tree never opens a touch target and the whole stroke is dropped. Rewrite them
    // here, the last point they are intact, and dispatch normally. A no-op on every other device.
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val standard = standardPenAction(ev.actionMasked)
        if (standard < 0 || ev.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) {
            return super.dispatchTouchEvent(ev)
        }
        val rewritten = MotionEvent.obtain(ev)
        rewritten.action = standard
        try {
            return super.dispatchTouchEvent(rewritten)
        } finally {
            rewritten.recycle()
        }
    }

    // A PDF arriving while we're already running: singleTask reuses this instance via onNewIntent.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pdfImportUri(intent)?.let { pendingPdfImport = it }
    }

    // The PDF uri carried by an inbound VIEW ("Open with") or SEND ("Share") intent, else null.
    private fun pdfImportUri(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND ->
            if (intent.type == "application/pdf")
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else null
        else -> null
    }

    // uiMode is in configChanges, so a system dark/light flip lands here instead of recreating us.
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val night = newConfig.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        editor?.onSystemDarkModeChanged(night == android.content.res.Configuration.UI_MODE_NIGHT_YES)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && editor?.fullscreen == true) applyFullscreen(true) // re-hide transient bars after they swipe in
    }

    override fun onStart() {
        super.onStart()
        com.xnotes.sync.filen.FilenSyncManager.syncOnAppOpen(this)
    }

    override fun onPause() {
        super.onPause()
        editor?.persist()
    }

    private fun applyFullscreen(fullscreen: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (fullscreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

@Composable
private fun EditorScreen(
    editor: Editor,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    importPdfUri: Uri? = null,
    onImportConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    // The LaTeX renderer resolves its fonts through the composition, so this is the
    // only place it can be built; the flow reaches it through MathRendering after.
    val mathMeasurer = rememberLatexMeasurer()
    val mathExporter = rememberLatexExporter()
    val mathDensity = LocalDensity.current
    LaunchedEffect(mathMeasurer, mathExporter, mathDensity) {
        com.xnotes.platform.MathRendering.install(mathMeasurer, mathExporter, mathDensity)
        com.xnotes.core.model.TextItem.mathTypesetter = com.xnotes.platform.MathRendering
        editor.refreshFlowMath()
    }
    // Backstage is the root of the stack; the editor is pushed on top only when a note is open
    // (editor.noteOpen). Every launch starts on backstage.
    var backstageView by remember { mutableStateOf(com.xnotes.ui.BackstageView.HOME) }
    var showShareChooser by remember { mutableStateOf(false) }
    var guardAction by remember { mutableStateOf<GuardRequest?>(null) }
    var pendingAfterSave by remember { mutableStateOf<(() -> Unit)?>(null) }
    // The pane an image was picked for, and where a long-press menu asked it to land. Held together
    // so the picker's result lands in the pane that opened it, whatever the focus does meanwhile.
    var pendingInsert by remember { mutableStateOf<PendingInsert?>(null) }
    var pendingPageInsert by remember { mutableStateOf<PendingPageInsert?>(null) }
    var pendingImagePage by remember { mutableStateOf<PendingPageInsert?>(null) }
    var pendingPhoto by remember { mutableStateOf<PendingPhoto?>(null) }
    var pendingImportInto by remember { mutableStateOf<String?>(null) }
    // The same, for the infinite canvas.
    var pendingCanvasInsert by remember { mutableStateOf<PendingInsert?>(null) }
    var pendingShareUri by remember { mutableStateOf<String?>(null) }
    var pendingSaveCopyUri by remember { mutableStateOf<String?>(null) }
    // A finished PDF render awaiting a SAF "Save as" destination (open-note / file / pages export).
    var pendingExportTemp by remember { mutableStateOf<java.io.File?>(null) }
    // In-flight PDF render; drives the progress dialog, null hides it.
    var exportProgress by remember { mutableStateOf<ExportProgress?>(null) }
    // The running export's coroutine and its own cancel flag. Each export gets a fresh flag so a new
    // export can abort the previous one (set its flag, then join it) without un-cancelling itself.
    var exportJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var exportCancel by remember { mutableStateOf<java.util.concurrent.atomic.AtomicBoolean?>(null) }
    // The pane whose page indices await a SAF "Save as" destination (side-panel page export).
    var pendingExportPages by remember { mutableStateOf<PendingPages?>(null) }
    val scope = rememberCoroutineScope()
    val resolver = context.contentResolver
    val rwFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    // Read-only files open over everything; following a [[link]] stacks another, Back pops one.
    val readerStack = remember { androidx.compose.runtime.mutableStateListOf<com.xnotes.ui.ReaderFile>() }

    // Which pane a "Save as" writes: the focused one at the moment the picker opened.
    var savePane by remember { mutableStateOf(editor) }
    val createLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        uri?.let {
            runCatching { resolver.takePersistableUriPermission(it, rwFlags) }
            val name = displayNameOf(resolver, it)
            runCatching { resolver.openOutputStream(it, "wt")?.use { o -> savePane.save(o, it.toString(), name) } }
                .onSuccess { val p = pendingAfterSave; pendingAfterSave = null; p?.invoke() }
                .onFailure { editor.message = context.getString(R.string.err_save_note); pendingAfterSave = null }
        }
    }

    // "Import file" takes one file or many. One remembers the pick and shows the name dialog at once, with
    // the (possibly large) copy + conversion happening at Save under the "Importing…" loader. Many skips
    // naming (each note takes its source file's name) and the explorer imports them one at a time. The
    // format (pdf / image / text) is classified from each picked file here.
    val importPdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        fun pendingOf(u: android.net.Uri): com.xnotes.ui.PendingImport {
            val name = displayNameOf(resolver, u) ?: "Document"
            val mime = runCatching { resolver.getType(u) }.getOrNull() ?: ""
            val kind = com.xnotes.ui.ImportKind.classify(name, mime)
            return com.xnotes.ui.PendingImport(kind, com.xnotes.core.util.Paths.stem(name), u.toString(), name, mime)
        }
        when {
            uris.isEmpty() -> Unit
            uris.size == 1 -> pendingOf(uris[0]).let { editor.requestImport(it.kind, it.defaultName, it.uri, it.sourceName, it.mime) }
            else -> editor.requestImports(uris.map(::pendingOf))
        }
        if (uris.isNotEmpty()) {
            backstageView = com.xnotes.ui.BackstageView.HOME
            editor.goHomeAll() // land on backstage to name the single pick, or to run the batch
        }
    }
    // Exact MIME types the "Import file" picker offers, so it only shows files we can actually
    // import (an explicit allowlist rather than image/* + text/* wildcards, which some pickers
    // expand to unrelated files).
    val importMimeTypes = arrayOf(
        "application/pdf",
        // images
        "image/png", "image/jpeg", "image/webp", "image/gif", "image/bmp",
        "image/svg+xml", "image/heic", "image/heif",
        // plain text / markdown / csv / html / rtf
        "text/plain", "text/markdown", "text/x-markdown",
        "text/csv", "text/comma-separated-values", "application/csv",
        "text/html", "text/rtf", "application/rtf",
        // ebook / office
        "application/epub+zip",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    )
    // A PDF "Save as" destination. The note is already rendered into [pendingExportTemp]
    // (off-thread, behind the progress dialog), so the picker just chooses where to copy it —
    // shared by the open-note export, the explorer-file export, and side-panel page saves.
    val savePdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri ->
        val temp = pendingExportTemp; pendingExportTemp = null
        if (temp != null) {
            if (uri != null) {
                val ok = runCatching { resolver.openOutputStream(uri)?.use { o -> temp.inputStream().use { it.copyTo(o) } } != null }.getOrDefault(false)
                editor.message = if (ok) context.getString(R.string.exported_pdf) else context.getString(R.string.err_export_pdf)
            }
            temp.delete() // discard the temp whether saved or the picker was dismissed
        }
    }

    // Save a copy of an explorer file (a note or a canvas) elsewhere.
    val saveCopyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val src = pendingSaveCopyUri; pendingSaveCopyUri = null
        if (uri != null && src != null) {
            runCatching { resolver.openOutputStream(uri)?.use { o -> editor.copyFileTo(src, o) } }
                .onFailure { editor.message = context.getString(R.string.err_save_copy) }
        }
    }

    // Save a single selected page as a PNG.
    val savePageImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png"),
    ) { uri ->
        val pending = pendingExportPages; pendingExportPages = null
        val index = pending?.pages?.firstOrNull()
        if (uri != null && pending != null && index != null) {
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val ok = runCatching {
                    val png = pending.editor.pageImagePng(index) ?: return@runCatching false
                    resolver.openOutputStream(uri)?.use { it.write(png) } != null
                }.getOrDefault(false)
                if (!ok) editor.message = context.getString(R.string.err_save_image)
            }
        }
    }

    // Save several selected pages as individual PNGs into a folder the user picks.
    val savePagesImagesTreeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        val pending = pendingExportPages; pendingExportPages = null
        if (treeUri != null && pending != null && pending.pages.isNotEmpty()) {
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val stem = pending.editor.title
                val saved = runCatching {
                    val parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                        treeUri, android.provider.DocumentsContract.getTreeDocumentId(treeUri),
                    )
                    var n = 0
                    for (index in pending.pages) {
                        val png = pending.editor.pageImagePng(index) ?: continue
                        val name = "%s-p%02d.png".format(stem, index + 1)
                        val file = android.provider.DocumentsContract.createDocument(resolver, parent, "image/png", name) ?: continue
                        resolver.openOutputStream(file)?.use { it.write(png) }
                        n++
                    }
                    n
                }.getOrDefault(0)
                editor.message = if (saved > 0) context.resources.getQuantityString(R.plurals.saved_images, saved, saved) else context.getString(R.string.err_save_images)
            }
        }
    }

    val insertImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val pending = pendingInsert; pendingInsert = null
        if (uri != null && pending != null) {
            runCatching {
                resolver.openInputStream(uri)?.use { s -> pending.editor.insertImageAt(s.readBytes(), pending.at) }
            }.onFailure { editor.message = context.getString(R.string.err_read_image) }
        }
    }

    // A file whose pages go into the open note, from a page's menu in the side panel.
    val insertPagesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val pending = pendingPageInsert; pendingPageInsert = null
        if (uri == null || pending == null) return@rememberLauncherForActivityResult
        val name = displayNameOf(resolver, uri) ?: "Document"
        val mime = runCatching { resolver.getType(uri) }.getOrNull() ?: ""
        scope.launch {
            if (!pending.editor.importIntoOpenNote(uri.toString(), name, mime, pending.at)) editor.message = context.getString(R.string.err_read_file)
        }
    }

    // A file appended to a note chosen in the explorer.
    val importIntoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val note = pendingImportInto; pendingImportInto = null
        if (uri == null || note == null) return@rememberLauncherForActivityResult
        val name = displayNameOf(resolver, uri) ?: "Document"
        val mime = runCatching { resolver.getType(uri) }.getOrNull() ?: ""
        scope.launch {
            editor.message = context.getString(if (editor.importIntoNoteFile(note, uri.toString(), name, mime)) R.string.imported_into_note else R.string.err_read_file)
        }
    }

    // A picked image as a page of its own.
    val imagePageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val pending = pendingImagePage; pendingImagePage = null
        if (uri == null || pending == null) return@rememberLauncherForActivityResult
        val name = displayNameOf(resolver, uri) ?: "image"
        scope.launch {
            if (!pending.editor.insertImagePage(uri, name, pending.at)) editor.message = context.getString(R.string.err_read_image)
        }
    }

    // A photo from the camera app, written straight into a cache file we hand it.
    val takePhotoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val pending = pendingPhoto; pendingPhoto = null
        if (pending == null) return@rememberLauncherForActivityResult
        if (!taken || pending.file.length() == 0L) { pending.file.delete(); return@rememberLauncherForActivityResult }
        scope.launch {
            val target = pending.editor
            when {
                pending.page != null -> target.insertImagePage(android.net.Uri.fromFile(pending.file), pending.file.name, pending.page)
                target.canvasOpen -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { pending.file.readBytes() }
                    .let { target.infinite.insertImage(it, pending.at) }
                else -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { pending.file.readBytes() }
                    .let { target.insertImageAt(it, pending.at) }
            }
            pending.file.delete()
        }
    }
    fun takePhoto(target: Editor, at: com.xnotes.core.geometry.Pt?, page: Int?) {
        val dir = java.io.File(context.cacheDir, "camera").apply { mkdirs() }
        val file = java.io.File.createTempFile("photo", ".jpg", dir)
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        pendingPhoto = PendingPhoto(target, file, at, page)
        runCatching { takePhotoLauncher.launch(uri) }.onFailure {
            pendingPhoto = null
            file.delete()
            editor.message = context.getString(R.string.err_no_camera)
        }
    }

    // Images picked for the sticker library (multi-select), stored on disk by the editor.
    val addStickersLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri ->
            runCatching { resolver.openInputStream(uri)?.use { s -> editor.addSticker(s.readBytes()) } }
                .onFailure { editor.message = context.getString(R.string.err_read_image) }
        }
    }

    // A user Helix code theme (.toml), parsed + stored by the editor.
    val importCodeThemeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }
                .getOrNull()
                ?.let { editor.importCodeTheme(it, displayNameOf(resolver, uri)) }
                ?: run { editor.message = context.getString(R.string.err_read_file) }
        }
    }

    // A user page template (.xtemplate), checked and stored in the template library.
    val importTemplateLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }
                .getOrNull()
                ?.let { editor.importTemplate(it) }
                ?: run { editor.message = context.getString(R.string.err_read_file) }
        }
    }

    // A user font (.ttf/.otf), stored + registered by the editor.
    val importFontLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }
                .getOrNull()
                ?.let { editor.importFont(it, displayNameOf(resolver, uri)) }
                ?: run { editor.message = context.getString(R.string.err_read_file) }
        }
    }

    // Grant a folder for the in-app explorer (a one-time system folder picker).
    val pickRootLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            runCatching { resolver.takePersistableUriPermission(it, rwFlags) }
            editor.updateBrowseRoot(it.toString())
        }
    }

    /** Open the "Save as" picker for [target], remembering which pane its result belongs to. */
    fun launchSaveAs(target: Editor) {
        savePane = target
        createLauncher.launch("${target.title}.xnote")
    }

    fun saveOrPrompt() {
        val target = editor.active
        val uri = target.currentUri
        if (uri == null) { launchSaveAs(target); return }
        // The write is off the main thread now, so the Save-As fallback fires from its callback.
        target.saveToThen(uri) { ok -> if (!ok) launchSaveAs(target) }
    }

    // The prompt is about one pane's note, so it asks about — and saves — the pane being acted on.
    fun guarded(target: Editor = editor.active, action: () -> Unit) {
        when {
            // A canvas keeps its own autosave, and dirty/autosaveUri describe the paged buffer
            // underneath it, so asking about those here would prompt over a note nobody is looking at.
            target.canvasOpen -> action()
            target.autosaveUri != null -> action() // autosaved notes are flushed on doc-swap; no prompt
            target.dirty -> guardAction = GuardRequest(target, action)
            else -> action()
        }
    }

    /** Run [action] once every open pane has settled its unsaved changes, prompting one at a time. */
    fun guardedAll(action: () -> Unit) {
        val panes = editor.openPanes
        fun step(i: Int) {
            if (i >= panes.size) action() else guarded(panes[i]) { step(i + 1) }
        }
        step(0)
    }

    // An image picked for the infinite canvas: read the bytes and hand them straight over.
    val insertCanvasImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val pending = pendingCanvasInsert; pendingCanvasInsert = null
        if (uri == null || pending == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { resolver.openInputStream(uri)?.use { input -> input.readBytes() } }.getOrNull()
            }
            if (bytes != null) pending.editor.infinite.insertImage(bytes, pending.at)
        }
    }

    /** Read the file at [uriStr] into [target]. The extension picks the surface: the two document
     *  types share the explorer but not much else. Reads off-thread behind the "Opening note…"
     *  spinner so a big embedded PDF doesn't freeze the UI. */
    suspend fun openInto(target: Editor, uriStr: String) {
        val name = displayNameOf(resolver, Uri.parse(uriStr))
        if (com.xnotes.core.util.DocumentKind.ofName(name ?: "") == com.xnotes.core.util.DocumentKind.CANVAS) {
            target.openCanvasAsync(uriStr, name)
        } else {
            target.openAsync(uriStr, name)
        }
    }

    fun openTreeFile(uriStr: String) {
        scope.launch { openInto(editor, uriStr) }
    }

    /** Open two picked files together, one per pane, and start the split focused on the first. */
    fun openSplit(firstUri: String, secondUri: String) {
        editor.splitRatio = 0.5f
        editor.focusPane(com.xnotes.ui.Pane.PRIMARY)
        val second = editor.secondaryPane()
        scope.launch {
            openInto(editor, firstUri)
            openInto(second, secondUri)
            // A file that would not open leaves no half-built pane behind; the other stays as it is.
            if (!second.noteOpen) {
                editor.abandonSecondary()
                editor.message = context.getString(R.string.err_open_second)
            }
        }
    }

    fun stemOf(uriStr: String): String =
        com.xnotes.core.util.Paths.stem(displayNameOf(resolver, Uri.parse(uriStr)) ?: "Note")

    /**
     * The kind of the stored document at [uriStr], read from its file name, falling back to a note
     * for anything unrecognizable. Every place that has to name a copy of that file goes through
     * here: spelling an extension at the call site is how an `.xcanvas` ended up shared as `.xnote`.
     */
    fun kindOf(uriStr: String): com.xnotes.core.util.DocumentKind =
        com.xnotes.core.util.DocumentKind.ofName(displayNameOf(resolver, Uri.parse(uriStr)).orEmpty())
            ?: com.xnotes.core.util.DocumentKind.NOTE

    /** What the export dialog counts for the stored document at [uriStr]: a note's pages, a canvas's items. */
    fun countingOf(uriStr: String): String = ExportProgress.countingFor(kindOf(uriStr))

    // Render a PDF off the main thread into a temp file behind a cancellable progress dialog;
    // only once it finishes does [onReady] run — opening the SAF picker or a share sheet.
    // Dismissing the dialog flips this export's cancel flag, which aborts the page loop AND the
    // write stream, so the half-written temp is discarded. A fresh export first aborts and joins any
    // previous one, so they never overlap. [shareDir] picks the cache subdir: FileProvider only
    // exposes cache/share, so shares render there; plain "save" exports use cache/export.
    fun runPdfExport(
        stem: String,
        shareDir: Boolean,
        counting: String = "page",
        render: (java.io.OutputStream, (Int, Int) -> Unit, () -> Boolean) -> Unit,
        onReady: (java.io.File) -> Unit,
    ) {
        val prevJob = exportJob
        val prevCancel = exportCancel
        val cancel = java.util.concurrent.atomic.AtomicBoolean(false)
        exportCancel = cancel // the dialog's Cancel flips THIS export's flag
        exportProgress = ExportProgress(0, 0, counting) // show the dialog at once; the render fills in the real total
        exportJob = scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // Abort any still-running previous export (its write stream throws on the next buffer) and
            // wait for it to fully unwind before we touch the shared temp dir — no overlapping saves.
            prevCancel?.set(true)
            prevJob?.join()
            val dir = java.io.File(context.cacheDir, if (shareDir) "share" else "export").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() } // keep only the file this export produces
            val temp = java.io.File(dir, "$stem.pdf")
            val ok = runCatching {
                java.io.FileOutputStream(temp).use { fo ->
                    // Abort the (otherwise uninterruptible) PdfBox save the moment Cancel is tapped.
                    val o = CancellableOutputStream(fo) { cancel.get() }
                    render(o, { done, total -> if (!cancel.get()) exportProgress = ExportProgress(done, total, counting) }, { cancel.get() })
                }
            }.isSuccess
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (exportCancel === cancel) exportProgress = null // only the latest export owns the dialog
                when {
                    cancel.get() -> temp.delete()
                    ok -> onReady(temp)
                    else -> { temp.delete(); if (exportCancel === cancel) editor.message = context.getString(R.string.err_export_pdf) }
                }
            }
        }
    }

    fun launchShare(file: java.io.File, stem: String, mime: String) {
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, context.getString(R.string.share_named, stem)))
    }

    fun shareFile(uriStr: String, asPdf: Boolean) {
        val stem = stemOf(uriStr)
        if (asPdf) {
            // Render with progress, then share the finished PDF (writes into cache/share for FileProvider).
            runPdfExport(stem, shareDir = true, counting = countingOf(uriStr),
                render = { o, prog, cancel -> editor.exportFileToPdf(uriStr, o, prog, cancel) },
                onReady = { temp -> runCatching { launchShare(temp, stem, "application/pdf") }.onFailure { editor.message = context.getString(R.string.err_share_note) } })
        } else {
            // Sharing the bundle itself is just a fast byte copy — no render, no dialog needed. It
            // keeps the source's own extension, so a canvas is shared as a canvas.
            runCatching {
                val dir = java.io.File(context.cacheDir, "share").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() } // keep only the file we're about to share
                val file = java.io.File(dir, "$stem${kindOf(uriStr).suffix}")
                java.io.FileOutputStream(file).use { o -> editor.copyFileTo(uriStr, o) }
                launchShare(file, stem, "application/octet-stream")
            }.onFailure { editor.message = context.getString(R.string.err_share_note) }
        }
    }

    // Share several stored files at once, each as the bundle it is (no PDF render for a batch).
    fun shareFiles(uris: List<String>) {
        if (uris.isEmpty()) return
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val intent = runCatching {
                val dir = java.io.File(context.cacheDir, "share").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val auth = "${context.packageName}.fileprovider"
                val taken = HashSet<String>()
                val out = ArrayList<Uri>()
                for (u in uris) {
                    val stem = stemOf(u)
                    val suffix = kindOf(u).suffix
                    var name = "$stem$suffix"
                    var n = 2
                    while (!taken.add(name.lowercase())) name = "$stem (${n++})$suffix"
                    val file = java.io.File(dir, name)
                    java.io.FileOutputStream(file).use { o -> editor.copyFileTo(u, o) }
                    out.add(androidx.core.content.FileProvider.getUriForFile(context, auth, file))
                }
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "application/octet-stream"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, out)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }.getOrNull()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (intent != null) context.startActivity(Intent.createChooser(intent, context.resources.getQuantityString(R.plurals.share_n_notes, uris.size, uris.size))) else editor.message = context.getString(R.string.err_share_notes)
            }
        }
    }

    // Share the selected side-panel pages: as one PDF, or as one/many PNGs (ACTION_SEND_MULTIPLE).
    fun sharePages(from: Editor, pages: List<Int>, asPdf: Boolean) {
        if (pages.isEmpty()) return
        val stem = from.title
        if (asPdf) {
            runPdfExport(stem, shareDir = true,
                render = { o, prog, cancel -> from.exportPagesToPdf(pages, o, prog, cancel) },
                onReady = { temp -> runCatching { launchShare(temp, stem, "application/pdf") }.onFailure { editor.message = context.getString(R.string.err_share_pages) } })
            return
        }
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val auth = "${context.packageName}.fileprovider"
            val intent = runCatching {
                val dir = java.io.File(context.cacheDir, "share").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val uris = ArrayList<Uri>()
                for (index in pages) {
                    val png = from.pageImagePng(index) ?: continue
                    val file = java.io.File(dir, "%s-p%02d.png".format(stem, index + 1))
                    java.io.FileOutputStream(file).use { it.write(png) }
                    uris.add(androidx.core.content.FileProvider.getUriForFile(context, auth, file))
                }
                when {
                    uris.isEmpty() -> null
                    uris.size == 1 -> Intent(Intent.ACTION_SEND).apply { type = "image/png"; putExtra(Intent.EXTRA_STREAM, uris[0]); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    else -> Intent(Intent.ACTION_SEND_MULTIPLE).apply { type = "image/png"; putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                }
            }.getOrNull()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (intent != null) context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_named, stem))) else editor.message = context.getString(R.string.err_share_pages)
            }
        }
    }

    fun savePagesAsPdf(from: Editor, pages: List<Int>) {
        if (pages.isEmpty()) return
        runPdfExport(from.title, shareDir = false,
            render = { o, prog, cancel -> from.exportPagesToPdf(pages, o, prog, cancel) },
            onReady = { temp -> pendingExportTemp = temp; savePdfLauncher.launch("${from.title}.pdf") })
    }

    // One page -> a single PNG (CreateDocument); several -> a folder the user picks (one PNG per page).
    fun savePagesAsImages(from: Editor, pages: List<Int>) {
        if (pages.isEmpty()) return
        pendingExportPages = PendingPages(from, pages)
        if (pages.size == 1) savePageImageLauncher.launch("%s-p%02d.png".format(from.title, pages[0] + 1))
        else savePagesImagesTreeLauncher.launch(null)
    }

    // Every shortcut acts on the focused pane, so the same KeyActions serve both of them.
    editor.keyActions = remember {
        Editor.KeyActions(
            newNote = { guarded { editor.active.newNote() } },
            open = { backstageView = com.xnotes.ui.BackstageView.HOME; guardedAll { editor.goHomeAll() } },
            save = { saveOrPrompt() },
            saveAs = { launchSaveAs(editor.active) },
            exportPdf = {
                val from = editor.active
                runPdfExport(from.title, shareDir = false,
                    render = { o, prog, cancel -> from.exportPdf(o, prog, cancel) },
                    onReady = { temp -> pendingExportTemp = temp; savePdfLauncher.launch("${from.title}.pdf") })
            },
            preferences = { backstageView = com.xnotes.ui.BackstageView.PREFERENCES; guardedAll { editor.goHomeAll() } },
            fullscreen = onToggleFullscreen,
        )
    }

    LaunchedEffect(editor.message) {
        editor.message?.let {
            val action = editor.messageAction
            editor.messageAction = null
            val result = snackbar.showSnackbar(
                it, actionLabel = action?.first,
                duration = if (action != null) androidx.compose.material3.SnackbarDuration.Long else androidx.compose.material3.SnackbarDuration.Short,
            )
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) action?.second?.invoke()
            editor.message = null
        }
    }
    // The second pane raises its own messages; surface them through the same snackbar.
    val secondaryMessage = editor.secondary?.message
    LaunchedEffect(secondaryMessage) {
        if (secondaryMessage != null) {
            snackbar.showSnackbar(secondaryMessage)
            editor.secondary?.message = null
        }
    }

    // A PDF opened from another app ("Open with"/Share): set up a pending import and land on the
    // backstage so its name dialog appears, exactly like the in-app "Import PDF" button. With no
    // folder chosen yet, fall back to App storage so the explorer renders and the note autosaves.
    LaunchedEffect(importPdfUri) {
        val src = importPdfUri ?: return@LaunchedEffect
        onImportConsumed() // consume once; the body has no suspend point so it runs to completion
        val stem = com.xnotes.core.util.Paths.stem(displayNameOf(resolver, src) ?: "Document")
        guardedAll {
            if (editor.browseRoot == null) editor.useInternalStorage()
            editor.requestImport(com.xnotes.ui.ImportKind.PDF, stem, src.toString())
            backstageView = com.xnotes.ui.BackstageView.HOME
            editor.goHomeAll()
        }
    }

    // In fullscreen the window runs edge to edge and the swipe-in system bars are transient, so
    // zero the content insets: otherwise their inset animates 0 -> N -> 0 and resizes the canvas,
    // forcing a re-render every time the bars hide. Non-fullscreen keeps the normal bar insets.
    // A DOCKED keyboard lifts only the open panes (the bottom format bar rides right above it) and
    // the snackbar; Home stays put and the keyboard slides over it. A floating keyboard reports no
    // inset. Insets are consumed below so inner imePadding only adds what the bars don't cover.
    val contentInsets = if (fullscreen) WindowInsets(0, 0, 0, 0) else WindowInsets.systemBars
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar, Modifier.windowInsetsPadding(WindowInsets.ime.exclude(contentInsets))) },
        contentWindowInsets = contentInsets,
    ) { inner ->
        Box(modifier = Modifier.fillMaxSize().padding(inner).consumeWindowInsets(contentInsets)) {
            // BASE LAYER: backstage is the root of the stack — always present underneath.
            com.xnotes.ui.Backstage(
                editor = editor,
                view = backstageView,
                onSelectView = { backstageView = it },
                onExitApp = { (context as? android.app.Activity)?.finish() },
                onImportCodeTheme = { importCodeThemeLauncher.launch(arrayOf("*/*")) },
                onImportFont = { importFontLauncher.launch(arrayOf("*/*")) },
                onImportPdf = { importPdfLauncher.launch(importMimeTypes) },
                onOpenFile = { uri ->
                    val name = displayNameOf(resolver, Uri.parse(uri)).orEmpty()
                    if (com.xnotes.core.util.ReaderKind.isReadable(name)) readerStack.add(com.xnotes.ui.ReaderFile(uri, name))
                    else guarded(editor) { openTreeFile(uri) }
                },
                onPickRoot = { pickRootLauncher.launch(null) },
                onShareFile = { uri -> pendingShareUri = uri; showShareChooser = true },
                onSaveCopyFile = { uri -> pendingSaveCopyUri = uri; saveCopyLauncher.launch("${stemOf(uri)}${kindOf(uri).suffix}") },
                onExportFilePdf = { uri ->
                    runPdfExport(stemOf(uri), shareDir = false, counting = countingOf(uri),
                        render = { o, prog, cancel -> editor.exportFileToPdf(uri, o, prog, cancel) },
                        onReady = { temp -> pendingExportTemp = temp; savePdfLauncher.launch("${stemOf(uri)}.pdf") })
                },
                onOpenSplit = { first, second -> guardedAll { openSplit(first, second) } },
                onShareFiles = { uris -> shareFiles(uris) },
                onImportIntoNote = { note ->
                    pendingImportInto = note
                    importIntoLauncher.launch(importMimeTypes + arrayOf("application/octet-stream"))
                },
                onOpenBeside = { uri ->
                    editor.currentUri?.takeIf { !editor.isSameDocument(it, uri) }?.let { first -> { guardedAll { openSplit(first, uri) } } }
                },
            )

            // TOP LAYER: the open panes (toolbar + canvas each), pushed over backstage. Back acts on
            // the focused pane; its handlers live here so — composed after backstage — they win while
            // a note is open.
            val focused = editor.active
            if (focused.noteOpen) {
                // While a text box is open, Back commits-or-dismisses it (and hides the keyboard).
                BackHandler(enabled = focused.editingField != null) { focused.commitText() }
                // A live flow caret session ends first (flushing its typing burst).
                BackHandler(enabled = focused.flowEditingActive) { focused.flowText.endSession() }
                // Otherwise Back closes that pane (guarded for unsaved edits); the other one stays.
                BackHandler(enabled = focused.editingField == null && !focused.flowEditingActive) {
                    guarded(focused) { focused.goHome() }
                }
            }

            val actions = PaneActions(
                onToggleFullscreen = onToggleFullscreen,
                onOpenBackstage = {
                    backstageView = com.xnotes.ui.BackstageView.HOME
                    guardedAll { editor.goHomeAll() }
                },
                onClosePane = { pane -> guarded(pane) { pane.goHome() } },
                onInsertImage = { pane, at ->
                    pendingInsert = PendingInsert(pane, at)
                    insertImageLauncher.launch(arrayOf("image/*"))
                },
                onInsertCanvasImage = { pane, at ->
                    pendingCanvasInsert = PendingInsert(pane, at)
                    insertCanvasImageLauncher.launch(arrayOf("image/*"))
                },
                onAddStickers = { addStickersLauncher.launch(arrayOf("image/*")) },
                onImportTemplate = { importTemplateLauncher.launch(arrayOf("*/*")) },
                onSharePages = { pane, pages, asPdf -> sharePages(pane, pages, asPdf) },
                onSavePagesAsPdf = { pane, pages -> savePagesAsPdf(pane, pages) },
                onSavePagesAsImages = { pane, pages -> savePagesAsImages(pane, pages) },
                onInsertPages = { pane, kind, at ->
                    when (kind) {
                        com.xnotes.ui.PageInsert.FILE -> {
                            pendingPageInsert = PendingPageInsert(pane, at)
                            insertPagesLauncher.launch(importMimeTypes + arrayOf("application/octet-stream"))
                        }
                        com.xnotes.ui.PageInsert.IMAGE -> {
                            pendingImagePage = PendingPageInsert(pane, at)
                            imagePageLauncher.launch(arrayOf("image/*"))
                        }
                        com.xnotes.ui.PageInsert.PHOTO -> takePhoto(pane, null, at)
                    }
                },
                onTakePhoto = { pane, at -> takePhoto(pane, at, null) },
                onImagePage = { pane ->
                    pendingImagePage = PendingPageInsert(pane, pane.pageIndex + 1)
                    imagePageLauncher.launch(arrayOf("image/*"))
                },
            )
            SplitHost(editor, actions)

            readerStack.lastOrNull()?.let { file ->
                androidx.compose.runtime.key(file.uri) {
                    com.xnotes.ui.ReaderScreen(
                        editor = editor,
                        file = file,
                        onClose = { readerStack.removeLastOrNull() },
                        onWikiLink = { target ->
                            scope.launch {
                                val root = editor.browseRoot
                                val hit = root?.let { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { editor.findLinkedFile(it, target) } }
                                when {
                                    hit == null -> editor.say(context.getString(R.string.reader_link_missing, target))
                                    com.xnotes.core.util.ReaderKind.isReadable(hit.name) -> readerStack.add(com.xnotes.ui.ReaderFile(hit.documentUri, hit.name))
                                    else -> { readerStack.clear(); guarded(editor) { openTreeFile(hit.documentUri) } }
                                }
                            }
                        },
                    )
                }
            }
        }
    }
    if (showShareChooser) {
        val shareUri = pendingShareUri
        // Remembered because resolving the kind is a SAF query, and this is composition.
        val shareSuffix = remember(shareUri) {
            shareUri?.let { kindOf(it).suffix } ?: com.xnotes.core.util.DocumentKind.NOTE.suffix
        }
        com.xnotes.ui.AlertDialog(
            onDismissRequest = { showShareChooser = false; pendingShareUri = null },
            title = { androidx.compose.material3.Text(stringResource(R.string.share_note)) },
            text = { androidx.compose.material3.Text(stringResource(R.string.share_as, shareUri?.let { stemOf(it) } ?: "")) },
            confirmButton = {
                androidx.compose.foundation.layout.Row {
                    androidx.compose.material3.TextButton(onClick = { showShareChooser = false; pendingShareUri = null; shareUri?.let { shareFile(it, asPdf = false) } }) {
                        androidx.compose.material3.Text(stringResource(R.string.file_of_kind, shareSuffix))
                    }
                    androidx.compose.material3.TextButton(onClick = { showShareChooser = false; pendingShareUri = null; shareUri?.let { shareFile(it, asPdf = true) } }) {
                        androidx.compose.material3.Text("PDF")
                    }
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showShareChooser = false; pendingShareUri = null }) {
                    androidx.compose.material3.Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    guardAction?.let { request ->
        val guarded = request.editor
        val action = request.action
        com.xnotes.ui.AlertDialog(
            onDismissRequest = { guardAction = null },
            title = { androidx.compose.material3.Text(stringResource(R.string.unsaved_changes)) },
            text = { androidx.compose.material3.Text(stringResource(R.string.unsaved_changes_prompt, guarded.title)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    guardAction = null
                    val uri = guarded.currentUri
                    if (uri != null) {
                        // Off-thread: what the prompt was guarding runs once the bytes have landed.
                        guarded.saveToThen(uri) { action() }
                    } else {
                        pendingAfterSave = action
                        launchSaveAs(guarded)
                    }
                }) { androidx.compose.material3.Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                androidx.compose.foundation.layout.Row {
                    androidx.compose.material3.TextButton(onClick = { guardAction = null; action() }) {
                        androidx.compose.material3.Text(stringResource(R.string.discard))
                    }
                    androidx.compose.material3.TextButton(onClick = { guardAction = null }) {
                        androidx.compose.material3.Text(stringResource(R.string.cancel))
                    }
                }
            },
        )
    }
    exportProgress?.let { p ->
        PdfExportDialog(done = p.done, total = p.total, counting = p.counting, onCancel = {
            exportCancel?.set(true) // abort this export's page loop AND its write stream
            exportProgress = null   // hide at once; the job then discards the half-written temp
        })
    }
    if (editor.importing) {
        // A batch counts files, so it gets a determinate ring; a single import has nothing to count.
        val batch = editor.importProgress
        if (batch != null) PdfImportBatchDialog(batch.done, batch.total) { editor.cancelImportInProgress() }
        else PdfImportDialog(isPdf = editor.pendingImport?.kind != com.xnotes.ui.ImportKind.OPEN, onCancel = { editor.cancelImportInProgress() }) // the stream-copy stops at its next buffer
    }
    // Tapping a note reads it off-thread (editor.opening). Only show the spinner once the read has run
    // long enough to matter, so opening a small note never flashes a dialog; a big PDF gets the loader.
    var showOpening by remember { mutableStateOf(false) }
    val opening = editor.opening || editor.secondary?.opening == true
    LaunchedEffect(opening) {
        if (opening) { delay(160); showOpening = opening } else showOpening = false
    }
    if (showOpening && opening) {
        SpinnerDialog(stringResource(R.string.opening_note), onCancel = {
            // Discard whichever pane's note is still being read when it returns.
            editor.cancelOpenInProgress()
            editor.secondary?.cancelOpenInProgress()
            showOpening = false // dismiss at once; opening clears as the reads unwind
        })
    }
    // A dirty note is flushed off-thread on close/pause; show the saving overlay only once the write is
    // slow enough to matter, so closing a small note never flashes a dialog.
    var showSaving by remember { mutableStateOf(false) }
    val saving = editor.savingNote || editor.secondary?.savingNote == true
    LaunchedEffect(saving) {
        if (saving) { delay(160); showSaving = saving } else showSaving = false
    }
    if (showSaving && saving) SavingDialog()
}

/** A pending "unsaved changes" prompt: the pane it asks about, and what to run once it's settled. */
private class GuardRequest(val editor: Editor, val action: () -> Unit)

/** A picked image on its way into [editor], at [at] when a long-press chose the spot. */
private class PendingInsert(val editor: Editor, val at: com.xnotes.core.geometry.Pt?)

/** Pages about to go into [editor] before page [at], once a picker or the camera returns. */
private class PendingPageInsert(val editor: Editor, val at: Int)

/** A photo being taken into [file]: for [editor]'s page [page] when set, else placed at [at]. */
private class PendingPhoto(val editor: Editor, val file: java.io.File, val at: com.xnotes.core.geometry.Pt?, val page: Int?)

/** Side-panel pages of [editor] waiting on a SAF "Save as" destination. */
private class PendingPages(val editor: Editor, val pages: List<Int>)

/**
 * A running PDF export's progress: [done] of [total], and what those are. [counting] exists because
 * a canvas has no pages to count — it exports as one, and reports the items it is drawing instead —
 * so the dialog cannot assume the unit. `total < 0` marks the final write phase, where [done] is a
 * 0..1000 permille of the output bytes.
 */
private class ExportProgress(val done: Int, val total: Int, val counting: String) {
    companion object {
        /** What a document of [kind] counts on its way out. */
        fun countingFor(kind: com.xnotes.core.util.DocumentKind): String =
            if (kind == com.xnotes.core.util.DocumentKind.CANVAS) "item" else "page"
    }
}

/**
 * What a pane can ask the screen around it to do: open a SAF picker, run an export, or change the
 * window. Each callback takes the pane it is acting for, because in a split there are two of them
 * and a picker's result has to come back to the one that opened it.
 */
private class PaneActions(
    val onToggleFullscreen: () -> Unit,
    /** Leave for the backstage, closing every open pane. */
    val onOpenBackstage: () -> Unit,
    /** Close just this pane, leaving the other one to fill the window. */
    val onClosePane: (Editor) -> Unit,
    val onInsertImage: (Editor, com.xnotes.core.geometry.Pt?) -> Unit,
    val onInsertCanvasImage: (Editor, com.xnotes.core.geometry.Pt?) -> Unit,
    val onAddStickers: () -> Unit,
    val onImportTemplate: () -> Unit,
    val onSharePages: (Editor, List<Int>, Boolean) -> Unit,
    val onSavePagesAsPdf: (Editor, List<Int>) -> Unit,
    val onSavePagesAsImages: (Editor, List<Int>) -> Unit,
    val onInsertPages: (Editor, com.xnotes.ui.PageInsert, Int) -> Unit,
    /** Take a photo and place it at the content point, or the page centre when null. */
    val onTakePhoto: (Editor, com.xnotes.core.geometry.Pt?) -> Unit,
    /** Pick an image and add it as a page after the current one. */
    val onImagePage: (Editor) -> Unit,
)

/** Neither pane of a split may be squeezed below this share of the split axis. */
private const val MIN_PANE_RATIO = 0.18f

/** How wide the divider is to a finger. Mostly empty around the line, so it is easy to catch. */
private val DIVIDER = 16.dp

/** The accent line drawn down the middle of the divider, the same weight as a pane's focus line. */
private val DIVIDER_LINE = 2.dp

/**
 * Makes the open panes opaque to touch. Compose stops hit-testing lower siblings once a higher one
 * is hit, so this node — hit anywhere over the panes — keeps a tap that no child handled from
 * reaching the backstage composed underneath. It never consumes, so children still see every event.
 */
private val SwallowTouches = Modifier.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent(PointerEventPass.Initial)
    }
}

/**
 * Lays the open panes over the backstage. Both open is a split: side by side in landscape, stacked
 * in portrait, with a draggable divider between them. One open is that pane full-screen, and none
 * leaves the backstage showing.
 *
 * The panes are placed by offset and size inside one Box rather than by a Row that becomes a Column,
 * so a pane keeps its composition node — and with it its canvas view, raster caches and GL context —
 * when the split opens, closes or the device turns.
 */
@Composable
private fun SplitHost(editor: Editor, actions: PaneActions) {
    val second = editor.secondary
    val split = editor.noteOpen && second?.noteOpen == true
    // A pane that closed on its own leaves the other one full-screen; once neither is open the
    // second editor is released, freeing its canvas and GL surfaces.
    LaunchedEffect(editor.noteOpen, second?.noteOpen) {
        if (second != null && !second.noteOpen) editor.releaseClosedSecondary()
    }
    if (!editor.noteOpen && second?.noteOpen != true) return

    val sideBySide = LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val ratio = editor.splitRatio.coerceIn(MIN_PANE_RATIO, 1f - MIN_PANE_RATIO)
    BoxWithConstraints(Modifier.fillMaxSize().then(SwallowTouches).imePadding()) {
        val fullW = maxWidth
        val fullH = maxHeight
        val full = if (sideBySide) fullW else fullH
        // The panes meet along the split axis and take all of it; the divider is not a gap between
        // them but a handle floating over the seam, so there is no strip of its own colour to show
        // beside the toolbars. Across the axis both run full.
        val firstExtent = if (split) full * ratio else full
        val secondExtent = if (split) full - firstExtent else full

        /** Sizes a pane to [extent] along the split axis and the whole window across it. */
        fun paneSize(extent: Dp) = Modifier.size(
            if (sideBySide) extent else fullW,
            if (sideBySide) fullH else extent,
        )

        /** Offsets a pane [along] the split axis from the top-left of the editor area. */
        fun paneOffset(along: Dp) = Modifier.offset(
            x = if (sideBySide) along else 0.dp,
            y = if (sideBySide) 0.dp else along,
        )

        if (editor.noteOpen) {
            EditorPane(
                editor = editor,
                app = editor,
                actions = actions,
                closable = split,
                modifier = paneSize(firstExtent),
            )
        }
        if (second?.noteOpen == true) {
            EditorPane(
                editor = second,
                app = editor,
                actions = actions,
                closable = split,
                modifier = paneOffset(if (split) firstExtent else 0.dp).then(paneSize(secondExtent)),
            )
        }
        // Composed last so it sits over both panes and catches the drag before either of them.
        if (split) {
            SplitDivider(
                sideBySide = sideBySide,
                extentPx = with(LocalDensity.current) { full.toPx() },
                ratio = editor.splitRatio,
                onRatio = { editor.splitRatio = it },
                modifier = paneOffset((firstExtent - DIVIDER / 2).coerceAtLeast(0.dp))
                    .then(paneSize(DIVIDER)),
            )
        }
    }
}

/**
 * One pane: its toolbar over its canvas, with the menus and overlays that belong to that document.
 * A paged note and an infinite canvas get different chrome, since the paged toolbar is mostly pages,
 * viewing modes and text, none of which mean anything on a canvas.
 *
 * [app] is the primary editor, which owns the split; [editor] is this pane's own. Touching anywhere
 * in the pane gives it the focus, so the keyboard and the file actions follow the pen.
 */
@Composable
private fun EditorPane(
    editor: Editor,
    app: Editor,
    actions: PaneActions,
    closable: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val focusRequester = remember { FocusRequester() }
    val focused = app.active === editor
    // This pane owns the keyboard while it is the focused one; (re)grab it as that changes.
    LaunchedEffect(focused, editor.noteOpen) {
        if (focused && editor.noteOpen) runCatching { focusRequester.requestFocus() }
    }
    val takeFocus = Modifier.pointerInput(editor) {
        awaitPointerEventScope {
            while (true) {
                // Initial pass and never consumed: the pane notices the touch, the canvas still gets it.
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press) app.focusPane(editor.pane)
            }
        }
    }
    Column(
        modifier = modifier
            .background(palette.bg.toComposeColor())
            .then(takeFocus)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { ke ->
                ke.type == KeyEventType.KeyDown && editor.handleKeyDown(ke.nativeKeyEvent)
            },
    ) {
        val onClose = if (closable) ({ actions.onClosePane(editor) }) else null
        // In a split, a hairline over the toolbar marks the pane the pen and the keyboard are in.
        if (closable) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background((if (focused) palette.accent else palette.border).toComposeColor()),
            )
        }
        if (editor.canvasOpen) {
            val canvas = editor.infinite
            val bar: @Composable () -> Unit = {
                com.xnotes.ui.InfiniteToolbar(
                    canvas,
                    onOpenBackstage = actions.onOpenBackstage,
                    onInsertImage = { actions.onInsertCanvasImage(editor, null) },
                    onTakePhoto = { actions.onTakePhoto(editor, null) },
                    onClosePane = onClose,
                )
            }
            ToolbarAround(bar, onCover = canvas::setToolbarCover) { floatingBar ->
                Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
                    AndroidView(
                        factory = { detached(canvas.surfaces) },
                        modifier = Modifier.fillMaxSize(),
                        update = { canvas.view.publish() },
                    )
                    floatingBar()
                    com.xnotes.ui.SelectionMenu(canvas)
                    com.xnotes.ui.LongPressMenu(canvas, onInsertImageAt = { c -> actions.onInsertCanvasImage(editor, c) }, onTakePhotoAt = { c -> actions.onTakePhoto(editor, c) })
                    com.xnotes.ui.CanvasDebugOverlay(canvas)
                    CanvasToastPill(canvas)
                }
            }
        } else {
            val bar: @Composable () -> Unit = {
                Toolbar(
                    editor,
                    onToggleFullscreen = actions.onToggleFullscreen,
                    onOpenBackstage = actions.onOpenBackstage,
                    onInsertImage = { actions.onInsertImage(editor, null) },
                    onAddStickers = actions.onAddStickers,
                    onClosePane = onClose,
                    onImportTemplate = actions.onImportTemplate,
                    onTakePhoto = { actions.onTakePhoto(editor, null) },
                    onImagePage = { actions.onImagePage(editor) },
                )
            }
            ToolbarAround(bar, onCover = editor::setToolbarCover) { floatingBar ->
                Row(modifier = Modifier.fillMaxSize()) {
                    if (editor.sidebarVisible) {
                        com.xnotes.ui.SidePanel(
                            editor,
                            onSharePages = { pages, asPdf -> actions.onSharePages(editor, pages, asPdf) },
                            onSavePagesAsPdf = { pages -> actions.onSavePagesAsPdf(editor, pages) },
                            onSavePagesAsImages = { pages -> actions.onSavePagesAsImages(editor, pages) },
                            onInsertPages = { kind, at -> actions.onInsertPages(editor, kind, at) },
                        )
                    }
                    Box(modifier = Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                        AndroidView(
                            factory = { detached(editor.surfaces) },
                            modifier = Modifier.fillMaxSize(),
                            update = { editor.view.requestRender() }, // repaint on (re)attach so a push never flashes blank
                        )
                        editor.editingField?.let { field ->
                            com.xnotes.ui.TextEditorOverlay(editor, field)
                        }
                        floatingBar()
                        com.xnotes.ui.SelectionMenu(editor)
                        com.xnotes.ui.ScreenshotMenu(editor)
                        com.xnotes.ui.CropMenu(editor)
                        com.xnotes.ui.TextStyleBar(editor)
                        com.xnotes.ui.LongPressMenu(editor, onInsertImageAt = { c -> actions.onInsertImage(editor, c) }, onTakePhotoAt = { c -> actions.onTakePhoto(editor, c) })
                        com.xnotes.ui.FlowEditMenu(editor)
                        com.xnotes.ui.SlashMenu(editor)
                        com.xnotes.ui.FlowTableMenu(editor)
                        com.xnotes.ui.TableChrome(editor)
                        if (editor.findOpen) com.xnotes.ui.FindBar(
                            editor.findQuery, editor::setFind,
                            if (editor.findQuery.isBlank()) "" else if (editor.findHits.isEmpty()) "0 / 0" else "${editor.findIndex + 1} / ${editor.findHits.size}",
                            { editor.findStep(false) }, { editor.findStep(true) }, { editor.toggleFind() },
                        )
                        ZoomLockHint(editor)
                        RefiningPdfHint(editor)
                        CanvasToastPill(editor)
                    }
                }
            }
            // Last children of the resized column: ride directly above the soft keyboard.
            com.xnotes.ui.TextFormatBar(editor)
            com.xnotes.ui.MathKeyboardPanel(editor)
            com.xnotes.ui.TextBoxFormatBar(editor)
        }
    }
}

/**
 * The handle over the seam between two split panes. Dragging it moves the boundary along the split
 * axis, keeping both panes at least [MIN_PANE_RATIO] of it; double-tapping puts it back in the
 * middle. Only the line is painted — the margin either side of it is what the finger catches, and it
 * stays transparent so the panes it lies over show through.
 */
@Composable
private fun SplitDivider(
    sideBySide: Boolean,
    extentPx: Float,
    ratio: Float,
    onRatio: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    // Read inside the long-lived drag gesture, which does not restart as the ratio moves.
    val ratioNow = rememberUpdatedState(ratio)
    val onRatioNow = rememberUpdatedState(onRatio)
    var dragged by remember { mutableStateOf(0f) }
    Box(
        modifier = modifier
            .pointerInput(sideBySide, extentPx) {
                detectDragGestures(
                    onDragStart = { dragged = ratioNow.value },
                    onDrag = { change, drag ->
                        change.consume()
                        if (extentPx <= 0f) return@detectDragGestures
                        val along = if (sideBySide) drag.x else drag.y
                        dragged = (dragged + along / extentPx).coerceIn(MIN_PANE_RATIO, 1f - MIN_PANE_RATIO)
                        onRatioNow.value(dragged)
                    },
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { onRatioNow.value(0.5f) })
            },
        contentAlignment = Alignment.Center,
    ) {
        // The line itself: thin, accent, and running the whole way, so it closes the frame the two
        // panes' focus lines start along their toolbars. The empty margin either side of it is what
        // the finger actually catches.
        Box(
            Modifier
                .fillMaxSize()
                .padding(
                    horizontal = if (sideBySide) (DIVIDER - DIVIDER_LINE) / 2 else 0.dp,
                    vertical = if (sideBySide) 0.dp else (DIVIDER - DIVIDER_LINE) / 2,
                )
                .clip(RoundedCornerShape(DIVIDER_LINE / 2))
                .background(palette.accent.toComposeColor()),
        )
    }
}

/**
 * Hands a reused View back to [AndroidView]. A pane's canvas can move to a fresh composition node
 * when the split opens or closes, and the new node parents the view itself, so it has to leave the
 * old parent first rather than throw.
 */
private fun <T : android.view.View> detached(view: T): T =
    view.also { (it.parent as? android.view.ViewGroup)?.removeView(it) }

/**
 * Wraps a PDF export's output stream and throws the instant [cancelled] turns true, so PdfBox's
 * otherwise-uninterruptible `save()` (which writes the whole document in one call) aborts promptly
 * when the user taps Cancel, instead of running to completion on a background thread.
 */
private class CancellableOutputStream(
    private val out: java.io.OutputStream,
    private val cancelled: () -> Boolean,
) : java.io.OutputStream() {
    override fun write(b: Int) { if (cancelled()) throw java.io.InterruptedIOException(); out.write(b) }
    override fun write(b: ByteArray, off: Int, len: Int) {
        if (cancelled()) throw java.io.InterruptedIOException()
        out.write(b, off, len)
    }
    override fun flush() { out.flush() }
    override fun close() { out.close() }
}

/**
 * "Exporting to PDF…" dialog shown while a (possibly large) note is flattened to a PDF off the main
 * thread. An animated spinner runs through the preparing/page phases (where there's no meaningful
 * percentage), then a determinate ring fills 0→100% during the final write. Dismissing it (Cancel,
 * back, or tapping outside) aborts the export via [onCancel]. Styled to match the monospace surfaces.
 */
@Composable
private fun PdfExportDialog(done: Int, total: Int, counting: String, onCancel: () -> Unit) {
    val palette = LocalPalette.current
    // Only the final PDF write has a meaningful, moving percentage (reported as byte progress with
    // total = -1, done a 0..1000 permille). Preparing and the page phase sit at 0% (the page loop is
    // near-instant on the common path), so show an animated spinner there instead of a frozen 0% ring,
    // and switch to the determinate ring + percentage once writing begins.
    val writing = total < 0
    val fraction = if (writing) (done / 1000f).coerceIn(0f, 1f) else 0f
    androidx.compose.ui.window.Dialog(onDismissRequest = onCancel) {
        Column(
            modifier = Modifier
                .clip(MaterialTheme.shapes.large)
                .background(palette.surface.toComposeColor())
                .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.large)
                .padding(horizontal = 32.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                if (writing) {
                    androidx.compose.material3.CircularProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxSize(),
                        color = palette.accent.toComposeColor(),
                        trackColor = palette.border.toComposeColor(),
                        strokeWidth = 4.dp,
                    )
                    Text(
                        "${(fraction * 100).roundToInt()}%",
                        color = palette.text.toComposeColor(),
                        fontSize = 15.sp,
                    )
                } else {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.fillMaxSize(),
                        color = palette.accent.toComposeColor(),
                        strokeWidth = 4.dp,
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                stringResource(R.string.exporting_pdf),
                color = palette.text.toComposeColor(),
                fontSize = 15.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    writing -> stringResource(R.string.writing_pdf)
                    total == 0 -> stringResource(R.string.preparing)
                    done < total -> stringResource(if (counting == "item") R.string.export_item_progress else R.string.export_page_progress, done, total)
                    else -> pluralStringResource(if (counting == "item") R.plurals.export_writing_items else R.plurals.export_writing_pages, total, total)
                },
                color = palette.textDim.toComposeColor(),
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(14.dp))
            androidx.compose.material3.TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel), color = palette.accent.toComposeColor())
            }
        }
    }
}

/**
 * Indeterminate spinner dialog: a styled card with an animated [CircularProgressIndicator], a
 * [title], a "This may take a moment." line, and a Cancel button. Shared by the "Importing…"
 * (PDF/note copy) and "Opening note…" (off-thread read) flows, neither of which has a meaningful
 * percentage. Dismissing it (Cancel, back, or tapping outside) calls [onCancel]. Styled to match
 * [PdfExportDialog].
 */
@Composable
internal fun SpinnerDialog(title: String, onCancel: () -> Unit) {
    val palette = LocalPalette.current
    androidx.compose.ui.window.Dialog(onDismissRequest = onCancel) {
        Column(
            modifier = Modifier
                .clip(MaterialTheme.shapes.large)
                .background(palette.surface.toComposeColor())
                .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.large)
                .padding(horizontal = 32.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.fillMaxSize(),
                    color = palette.accent.toComposeColor(),
                    strokeWidth = 4.dp,
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(title, color = palette.text.toComposeColor(), fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.may_take_moment), color = palette.textDim.toComposeColor(), fontSize = 13.sp)
            Spacer(Modifier.height(14.dp))
            androidx.compose.material3.TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel), color = palette.accent.toComposeColor())
            }
        }
    }
}

/**
 * Non-cancelable "Saving your notes…" dialog shown while a dirty note is flushed off the main thread
 * on close or pause, so quitting a large note shows progress instead of a frozen (ANR) screen.
 */
@Composable
private fun SavingDialog() {
    val palette = LocalPalette.current
    androidx.compose.ui.window.Dialog(
        onDismissRequest = {},
        properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Column(
            modifier = Modifier
                .clip(MaterialTheme.shapes.large)
                .background(palette.surface.toComposeColor())
                .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.large)
                .padding(horizontal = 32.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.fillMaxSize(),
                    color = palette.accent.toComposeColor(),
                    strokeWidth = 4.dp,
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(stringResource(R.string.saving_notes), color = palette.text.toComposeColor(), fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.may_take_moment), color = palette.textDim.toComposeColor(), fontSize = 13.sp)
        }
    }
}

/**
 * Indeterminate "Importing PDF…"/"Importing note…" dialog shown while a picked PDF (or `.xnote`) is
 * streamed into a new note off the main thread. Import has no natural page-by-page progress — the
 * cost is copying the (possibly large) source bytes — so it shows an animated spinner.
 */
@Composable
private fun PdfImportDialog(isPdf: Boolean, onCancel: () -> Unit) =
    SpinnerDialog(if (isPdf) "Importing…" else "Importing note…", onCancel)

/**
 * Determinate "Importing PDFs…" dialog for a multi-file import: a ring filled by files finished plus
 * a running "%d of %d imported" line, so the count stays visible for the whole batch. Cancel stops
 * after the file in flight and keeps the ones already imported. Styled to match [PdfExportDialog].
 */
@Composable
private fun PdfImportBatchDialog(done: Int, total: Int, onCancel: () -> Unit) {
    val palette = LocalPalette.current
    val fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    androidx.compose.ui.window.Dialog(onDismissRequest = onCancel) {
        Column(
            modifier = Modifier
                .clip(MaterialTheme.shapes.large)
                .background(palette.surface.toComposeColor())
                .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.large)
                .padding(horizontal = 32.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxSize(),
                    color = palette.accent.toComposeColor(),
                    trackColor = palette.border.toComposeColor(),
                    strokeWidth = 4.dp,
                )
                Text("$done/$total", color = palette.text.toComposeColor(), fontSize = 15.sp)
            }
            Spacer(Modifier.height(18.dp))
            Text(stringResource(R.string.importing_pdfs), color = palette.text.toComposeColor(), fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.import_progress, done, total),
                color = palette.textDim.toComposeColor(),
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(14.dp))
            androidx.compose.material3.TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel), color = palette.accent.toComposeColor())
            }
        }
    }
}

/**
 * Subtle, non-blocking hint shown bottom-right while a dark-mode PDF's embedded-image colours are
 * still being parsed by the up-front background sweep ([Editor.isRefiningPdf]). The pages are already
 * visible; this just shows a `k/N` progress bar so the user can keep scrolling and drawing while the
 * remaining pages' images snap to their true colours.
 */
@Composable
private fun BoxScope.RefiningPdfHint(editor: Editor) {
    val palette = LocalPalette.current
    AnimatedVisibility(
        visible = editor.isRefiningPdf,
        modifier = Modifier.align(Alignment.BottomEnd).padding(com.xnotes.ui.LocalToolbarCover.current).padding(12.dp),
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        val total = editor.refiningTotal
        val done = editor.refiningDone
        val fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
        Column(
            // Fixed width so the bar's fillMaxWidth tracks the chip (not the whole screen); the label
            // wraps to two tidy lines under it.
            modifier = Modifier
                .width(190.dp)
                .clip(MaterialTheme.shapes.small)
                .background(palette.surface.toComposeColor())
                .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.small)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            androidx.compose.material3.LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = palette.accent.toComposeColor(),
                trackColor = palette.border.toComposeColor(),
                drawStopIndicator = {}, // no trailing dot
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.refining_pdf_colours, done, total),
                color = palette.textDim.toComposeColor(),
                fontSize = 13.sp,
            )
        }
    }
}

/**
 * Transient zoom-lock affordance, centred just below the toolbar. Appears when a pinch snaps to
 * fit-to-width ([Editor.zoomLockHint] bumps) and auto-dismisses after a moment. Tapping toggles the
 * zoom lock, so the same chip both locks and unlocks; each tap re-arms the dismiss timer so it
 * lingers long enough to tap again.
 */
@Composable
private fun BoxScope.ZoomLockHint(editor: Editor) {
    val palette = LocalPalette.current
    var visible by remember { mutableStateOf(false) }
    // Bumped on the initial fit-width snap and on every tap; (re)starts the auto-dismiss timer below.
    var armToken by remember { mutableStateOf(0) }
    LaunchedEffect(editor.zoomLockHint) {
        if (editor.zoomLockHint > 0) {
            visible = true
            armToken++
        }
    }
    LaunchedEffect(armToken) {
        if (armToken > 0) {
            delay(2500)
            visible = false
        }
    }
    // Breaking past the fit-width magnet dismisses the hint at once.
    LaunchedEffect(editor.zoomLockHintDismiss) {
        if (editor.zoomLockHintDismiss > 0) visible = false
    }
    val locked = editor.zoomLocked
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.TopCenter).padding(com.xnotes.ui.LocalToolbarCover.current).padding(top = 8.dp),
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Row(
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .background(palette.surface.toComposeColor())
                .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.small)
                .clickable { editor.toggleZoomLock(); armToken++ }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (locked) XnotesIcons.lock else XnotesIcons.unlock,
                contentDescription = if (locked) stringResource(R.string.unlock_zoom) else stringResource(R.string.lock_zoom_fit_width),
                tint = (if (locked) palette.accent else palette.textDim).toComposeColor(),
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (locked) stringResource(R.string.unlock_zoom) else stringResource(R.string.lock_zoom),
                color = palette.text.toComposeColor(),
                fontSize = 13.sp,
            )
        }
    }
}

/** Queries the storage provider for a document's user-visible file name, if available. */
private fun displayNameOf(resolver: android.content.ContentResolver, uri: Uri): String? = runCatching {
    resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0) c.getString(i) else null
        } else null
    }
}.getOrNull()
