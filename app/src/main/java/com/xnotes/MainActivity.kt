package com.xnotes

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.focusable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.IntentCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.xnotes.ui.Editor
import com.xnotes.ui.Toolbar
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.XnotesTheme
import com.xnotes.ui.theme.toComposeColor
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Minimum time the launch loader stays up, so its animation is briefly seen even
 *  when the session restores instantly. */
private const val MIN_LOADER_MS = 600L

/** Whether the display has a camera cutout (notch/hole-punch). False below API 29, which has no
 *  cutout API; such devices fall back to fullscreen by default. */
internal fun deviceHasDisplayCutout(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
    val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) context.display
        else @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
    return display?.cutout != null
}

class MainActivity : ComponentActivity() {

    // The editor owns the fullscreen state (persisted preference, default depends on the display
    // cutout); this activity just applies it to the window. Fullscreen draws edge to edge and lets
    // the swipe-in transient bars overlay (no resize), non-fullscreen insets under the bars.
    private var editorLeft: Editor? = null
    private var editorRight: Editor? = null
    private var editor: Editor? = null
    // A PDF handed to us by another app ("Open with" / Share); consumed once the editor is ready.
    private var pendingPdfImport by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.xnotes.platform.FontCatalog.init(this)
        setTheme(com.xnotes.R.style.Theme_Xnotes) // leave the dark launch/splash theme behind
        applyFullscreen(!deviceHasDisplayCutout(this)) // provisional; reconciled once the editor loads prefs
        pendingPdfImport = pdfImportUri(intent)
        setContent {
            val context = LocalContext.current
            val edLeft = remember { Editor(context, "left").also { editorLeft = it } }
            val edRight = remember { Editor(context, "right").also { editorRight = it } }
            var activeEditor by remember { mutableStateOf(edLeft) }
            editor = activeEditor

            LaunchedEffect(activeEditor.fullscreen) { applyFullscreen(activeEditor.fullscreen) }
            var ready by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                val start = android.os.SystemClock.uptimeMillis()
                kotlinx.coroutines.coroutineScope {
                    launch { edLeft.restoreSession() }
                    launch { edRight.restoreSession() }
                }
                val elapsed = android.os.SystemClock.uptimeMillis() - start
                if (elapsed < MIN_LOADER_MS) kotlinx.coroutines.delay(MIN_LOADER_MS - elapsed)
                ready = true
                edLeft.prewarmBackstage() // warm recents/explorer caches so the first backstage open is instant
                edRight.prewarmBackstage()
            }
            XnotesTheme(activeEditor.palette) {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (ready) EditorScreen(
                        editorLeft = edLeft,
                        editorRight = edRight,
                        activeEditor = activeEditor,
                        onSetActiveEditor = { activeEditor = it },
                        fullscreen = activeEditor.fullscreen,
                        onToggleFullscreen = { activeEditor.toggleFullscreen() },
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
                }
            }
        }
    }

    // Stylus side-button keys (Bluetooth/USI pens report the button only this way) are caught here,
    // before Compose focus routing, so the controller sees both press and release regardless of
    // which view holds focus. Other keys fall through to the normal dispatch.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (editor?.onStylusButtonKey(event) == true) return true
        return super.dispatchKeyEvent(event)
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && editor?.fullscreen == true) applyFullscreen(true) // re-hide transient bars after they swipe in
    }

    override fun onPause() {
        super.onPause()
        editorLeft?.persist()
        editorRight?.persist()
    }

    override fun onDestroy() {
        super.onDestroy()
        editorLeft?.stopPresentation()
        editorRight?.stopPresentation()
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
    editorLeft: Editor,
    editorRight: Editor,
    activeEditor: Editor,
    onSetActiveEditor: (Editor) -> Unit,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    importPdfUri: Uri? = null,
    onImportConsumed: () -> Unit = {},
) {
    val editor = activeEditor
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var showPresentation by remember { mutableStateOf(false) }
    // Backstage is the root of the stack; the editor is pushed on top only when a note is open
    // (editor.noteOpen). Every launch starts on backstage.
    var backstageView by remember { mutableStateOf(com.xnotes.ui.BackstageView.HOME) }
    var showShareChooser by remember { mutableStateOf(false) }
    var guardAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingAfterSave by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingInsertContent by remember { mutableStateOf<com.xnotes.core.geometry.Pt?>(null) }
    var pendingShareUri by remember { mutableStateOf<String?>(null) }
    var pendingSaveCopyUri by remember { mutableStateOf<String?>(null) }
    // A finished PDF render awaiting a SAF "Save as" destination (open-note / file / pages export).
    var pendingExportTemp by remember { mutableStateOf<java.io.File?>(null) }
    // In-flight PDF render: (pagesDone, totalPages) drives the progress dialog; null hides it.
    var exportProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    // The running export's coroutine and its own cancel flag. Each export gets a fresh flag so a new
    // export can abort the previous one (set its flag, then join it) without un-cancelling itself.
    var exportJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var exportCancel by remember { mutableStateOf<java.util.concurrent.atomic.AtomicBoolean?>(null) }
    // Page indices awaiting a SAF "Save as" destination (side-panel page export).
    var pendingExportPages by remember { mutableStateOf<List<Int>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val resolver = context.contentResolver
    val rwFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    // "Open…" remembers the picked .xnote and shows the name dialog at once; it's copied into the folder at Save.
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { u ->
            val stem = com.xnotes.core.util.Paths.stem(displayNameOf(resolver, u) ?: "Note")
            editor.requestImport(com.xnotes.ui.ImportKind.OPEN, stem, u.toString())
            backstageView = com.xnotes.ui.BackstageView.HOME
            editor.goHome() // land on backstage to name/place the pending import
        }
    }
    val createLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        uri?.let {
            runCatching { resolver.takePersistableUriPermission(it, rwFlags) }
            val name = displayNameOf(resolver, it)
            runCatching { resolver.openOutputStream(it, "wt")?.use { o -> editor.save(o, it.toString(), name) } }
                .onSuccess { val p = pendingAfterSave; pendingAfterSave = null; p?.invoke() }
                .onFailure { editor.message = "Could not save the note."; pendingAfterSave = null }
        }
    }

    // "Import PDF" remembers the picked PDF and shows the name dialog at once; the (possibly large) copy
    // into the .xnote happens at Save, under the "Importing PDF…" loader, so the dialog isn't delayed.
    val importPdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { u ->
            val stem = com.xnotes.core.util.Paths.stem(displayNameOf(resolver, u) ?: "Document")
            editor.requestImport(com.xnotes.ui.ImportKind.PDF, stem, u.toString())
            backstageView = com.xnotes.ui.BackstageView.HOME
            editor.goHome() // land on backstage to name/place the pending import
        }
    }
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
                editor.message = if (ok) "Exported to PDF." else "Could not export to PDF."
            }
            temp.delete() // discard the temp whether saved or the picker was dismissed
        }
    }

    // Save a copy of an explorer file (.xnote) elsewhere.
    val saveCopyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val src = pendingSaveCopyUri; pendingSaveCopyUri = null
        if (uri != null && src != null) {
            runCatching { resolver.openOutputStream(uri)?.use { o -> editor.copyFileTo(src, o) } }
                .onFailure { editor.message = "Could not save a copy." }
        }
    }

    // Save a single selected page as a PNG.
    val savePageImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png"),
    ) { uri ->
        val pages = pendingExportPages; pendingExportPages = emptyList()
        val index = pages.firstOrNull()
        if (uri != null && index != null) {
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val ok = runCatching {
                    val png = editor.pageImagePng(index) ?: return@runCatching false
                    resolver.openOutputStream(uri)?.use { it.write(png) } != null
                }.getOrDefault(false)
                if (!ok) editor.message = "Could not save the image."
            }
        }
    }

    // Save several selected pages as individual PNGs into a folder the user picks.
    val savePagesImagesTreeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        val pages = pendingExportPages; pendingExportPages = emptyList()
        if (treeUri != null && pages.isNotEmpty()) {
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val stem = editor.title
                val saved = runCatching {
                    val parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                        treeUri, android.provider.DocumentsContract.getTreeDocumentId(treeUri),
                    )
                    var n = 0
                    for (index in pages) {
                        val png = editor.pageImagePng(index) ?: continue
                        val name = "%s-p%02d.png".format(stem, index + 1)
                        val file = android.provider.DocumentsContract.createDocument(resolver, parent, "image/png", name) ?: continue
                        resolver.openOutputStream(file)?.use { it.write(png) }
                        n++
                    }
                    n
                }.getOrDefault(0)
                editor.message = if (saved > 0) "Saved $saved image${if (saved == 1) "" else "s"}." else "Could not save the images."
            }
        }
    }

    val insertImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching {
                resolver.openInputStream(it)?.use { s -> editor.insertImageAt(s.readBytes(), pendingInsertContent) }
            }.onFailure { editor.message = "Could not read the image." }
        }
        pendingInsertContent = null
    }

    // A user Helix code theme (.toml), parsed + stored by the editor.
    val importCodeThemeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }
                .getOrNull()
                ?.let { editor.importCodeTheme(it, displayNameOf(resolver, uri)) }
                ?: run { editor.message = "Could not read the file." }
        }
    }

    // A user font (.ttf/.otf), stored + registered by the editor.
    val importFontLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }
                .getOrNull()
                ?.let { editor.importFont(it, displayNameOf(resolver, uri)) }
                ?: run { editor.message = "Could not read the file." }
        }
    }

    // Grant a folder for the in-app explorer (a one-time system folder picker).
    val pickRootLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            runCatching { resolver.takePersistableUriPermission(it, rwFlags) }
            editor.updateBrowseRoot(it.toString())
        }
    }

    fun saveOrPrompt() {
        val uri = editor.currentUri
        if (uri != null) {
            if (!editor.saveTo(uri)) createLauncher.launch("${editor.title}.xnote")
        } else {
            createLauncher.launch("${editor.title}.xnote")
        }
    }

    fun guarded(action: () -> Unit) {
        when {
            editor.autosaveUri != null -> action() // autosaved notes are flushed on doc-swap; no prompt
            editor.dirty -> guardAction = action
            else -> action()
        }
    }

    fun openTreeFile(uriStr: String) {
        val name = displayNameOf(resolver, Uri.parse(uriStr))
        // Read off-thread behind the "Opening note…" spinner so a big embedded PDF doesn't freeze the UI.
        scope.launch { editor.openAsync(uriStr, name) }
    }

    fun stemOf(uriStr: String): String =
        com.xnotes.core.util.Paths.stem(displayNameOf(resolver, Uri.parse(uriStr)) ?: "Note")

    // Render a PDF off the main thread into a temp file behind a cancellable progress dialog;
    // only once it finishes does [onReady] run — opening the SAF picker or a share sheet.
    // Dismissing the dialog flips this export's cancel flag, which aborts the page loop AND the
    // write stream, so the half-written temp is discarded. A fresh export first aborts and joins any
    // previous one, so they never overlap. [shareDir] picks the cache subdir: FileProvider only
    // exposes cache/share, so shares render there; plain "save" exports use cache/export.
    fun runPdfExport(
        stem: String,
        shareDir: Boolean,
        render: (java.io.OutputStream, (Int, Int) -> Unit, () -> Boolean) -> Unit,
        onReady: (java.io.File) -> Unit,
    ) {
        val prevJob = exportJob
        val prevCancel = exportCancel
        val cancel = java.util.concurrent.atomic.AtomicBoolean(false)
        exportCancel = cancel // the dialog's Cancel flips THIS export's flag
        exportProgress = 0 to 0 // show the dialog at once; the render fills in the real total
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
                    render(o, { done, total -> if (!cancel.get()) exportProgress = done to total }, { cancel.get() })
                }
            }.isSuccess
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (exportCancel === cancel) exportProgress = null // only the latest export owns the dialog
                when {
                    cancel.get() -> temp.delete()
                    ok -> onReady(temp)
                    else -> { temp.delete(); if (exportCancel === cancel) editor.message = "Could not export to PDF." }
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
        context.startActivity(Intent.createChooser(send, "Share $stem"))
    }

    fun shareFile(uriStr: String, asPdf: Boolean) {
        val stem = stemOf(uriStr)
        if (asPdf) {
            // Render with progress, then share the finished PDF (writes into cache/share for FileProvider).
            runPdfExport(stem, shareDir = true,
                render = { o, prog, cancel -> editor.exportFileToPdf(uriStr, o, prog, cancel) },
                onReady = { temp -> runCatching { launchShare(temp, stem, "application/pdf") }.onFailure { editor.message = "Could not share the note." } })
        } else {
            // A plain .xnote share is just a fast byte copy — no render, no dialog needed.
            runCatching {
                val dir = java.io.File(context.cacheDir, "share").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() } // keep only the file we're about to share
                val file = java.io.File(dir, "$stem.xnote")
                java.io.FileOutputStream(file).use { o -> editor.copyFileTo(uriStr, o) }
                launchShare(file, stem, "application/octet-stream")
            }.onFailure { editor.message = "Could not share the note." }
        }
    }

    // Share the selected side-panel pages: as one PDF, or as one/many PNGs (ACTION_SEND_MULTIPLE).
    fun sharePages(pages: List<Int>, asPdf: Boolean) {
        if (pages.isEmpty()) return
        val stem = editor.title
        if (asPdf) {
            runPdfExport(stem, shareDir = true,
                render = { o, prog, cancel -> editor.exportPagesToPdf(pages, o, prog, cancel) },
                onReady = { temp -> runCatching { launchShare(temp, stem, "application/pdf") }.onFailure { editor.message = "Could not share the pages." } })
            return
        }
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val auth = "${context.packageName}.fileprovider"
            val intent = runCatching {
                val dir = java.io.File(context.cacheDir, "share").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val uris = ArrayList<Uri>()
                for (index in pages) {
                    val png = editor.pageImagePng(index) ?: continue
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
                if (intent != null) context.startActivity(Intent.createChooser(intent, "Share $stem")) else editor.message = "Could not share the pages."
            }
        }
    }

    fun savePagesAsPdf(pages: List<Int>) {
        if (pages.isEmpty()) return
        runPdfExport(editor.title, shareDir = false,
            render = { o, prog, cancel -> editor.exportPagesToPdf(pages, o, prog, cancel) },
            onReady = { temp -> pendingExportTemp = temp; savePdfLauncher.launch("${editor.title}.pdf") })
    }

    // One page -> a single PNG (CreateDocument); several -> a folder the user picks (one PNG per page).
    fun savePagesAsImages(pages: List<Int>) {
        if (pages.isEmpty()) return
        pendingExportPages = pages
        if (pages.size == 1) savePageImageLauncher.launch("%s-p%02d.png".format(editor.title, pages[0] + 1))
        else savePagesImagesTreeLauncher.launch(null)
    }

    val focusRequester = remember { FocusRequester() }
    // The editor owns keyboard focus while it's on top; (re)grab it each time a note is pushed.
    LaunchedEffect(editorLeft.noteOpen) { if (editorLeft.noteOpen) runCatching { focusRequester.requestFocus() } }
    editor.keyActions = remember {
        Editor.KeyActions(
            newNote = { guarded { editor.newNote() } },
            open = {
                if (editor.browseRoot != null) openLauncher.launch(arrayOf("*/*"))
                else { backstageView = com.xnotes.ui.BackstageView.HOME; guarded { editor.goHome() } }
            },
            save = { saveOrPrompt() },
            saveAs = { createLauncher.launch("${editor.title}.xnote") },
            exportPdf = {
                runPdfExport(editor.title, shareDir = false,
                    render = { o, prog, cancel -> editor.exportPdf(o, prog, cancel) },
                    onReady = { temp -> pendingExportTemp = temp; savePdfLauncher.launch("${editor.title}.pdf") })
            },
            preferences = { backstageView = com.xnotes.ui.BackstageView.PREFERENCES; guarded { editor.goHome() } },
            fullscreen = onToggleFullscreen,
        )
    }

    LaunchedEffect(editorLeft.message) {
        editorLeft.message?.let {
            snackbar.showSnackbar(it)
            editorLeft.message = null
        }
    }
    LaunchedEffect(editorRight.message) {
        editorRight.message?.let {
            snackbar.showSnackbar(it)
            editorRight.message = null
        }
    }

    // A PDF opened from another app ("Open with"/Share): set up a pending import and land on the
    // backstage so its name dialog appears, exactly like the in-app "Import PDF" button. With no
    // folder chosen yet, fall back to App storage so the explorer renders and the note autosaves.
    LaunchedEffect(importPdfUri) {
        val src = importPdfUri ?: return@LaunchedEffect
        onImportConsumed() // consume once; the body has no suspend point so it runs to completion
        val stem = com.xnotes.core.util.Paths.stem(displayNameOf(resolver, src) ?: "Document")
        guarded {
            if (editor.browseRoot == null) editor.useInternalStorage()
            editor.requestImport(com.xnotes.ui.ImportKind.PDF, stem, src.toString())
            backstageView = com.xnotes.ui.BackstageView.HOME
            editor.goHome()
        }
    }

    // In fullscreen the window runs edge to edge and the swipe-in system bars are transient, so
    // zero the content insets: otherwise their inset animates 0 -> N -> 0 and resizes the canvas,
    // forcing a re-render every time the bars hide. Non-fullscreen keeps the normal bar insets.
    // The IME inset joins both cases so a DOCKED keyboard lifts the content (the bottom format
    // bar rides right above it); a floating keyboard reports no inset and the bar stays at the
    // bottom. Insets are consumed below so inner imePadding fields don't pad a second time.
    val contentInsets = if (fullscreen) WindowInsets.ime else WindowInsets.systemBars.union(WindowInsets.ime)
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, contentWindowInsets = contentInsets) { inner ->
        Box(modifier = Modifier.fillMaxSize().padding(inner).consumeWindowInsets(contentInsets)) {
            // BASE LAYER: backstage is the root of the stack — always present underneath.
            com.xnotes.ui.Backstage(
                editor = editorLeft,
                view = backstageView,
                onSelectView = { backstageView = it },
                onExitApp = { (context as? android.app.Activity)?.finish() },
                onImportCodeTheme = { importCodeThemeLauncher.launch(arrayOf("*/*")) },
                onImportFont = { importFontLauncher.launch(arrayOf("*/*")) },
                onOpenSystem = { openLauncher.launch(arrayOf("*/*")) },
                onImportPdf = { importPdfLauncher.launch(arrayOf("application/pdf")) },
                onOpenFile = { uri -> guarded { openTreeFile(uri) } },
                onPickRoot = { pickRootLauncher.launch(null) },
                onShareFile = { uri -> pendingShareUri = uri; showShareChooser = true },
                onSaveCopyFile = { uri -> pendingSaveCopyUri = uri; saveCopyLauncher.launch("${stemOf(uri)}.xnote") },
                onExportFilePdf = { uri ->
                    runPdfExport(stemOf(uri), shareDir = false,
                        render = { o, prog, cancel -> editorLeft.exportFileToPdf(uri, o, prog, cancel) },
                        onReady = { temp -> pendingExportTemp = temp; savePdfLauncher.launch("${stemOf(uri)}.pdf") })
                },
            )

            // TOP LAYER: the editor (toolbar + canvas), pushed only when a note is open. Its
            // BackHandlers live here so — composed after backstage — they take priority while open.
            if (editorLeft.noteOpen) {
                // While a text box is open, Back commits-or-dismisses it (and hides the keyboard).
                BackHandler(enabled = editorLeft.editingField != null) { editorLeft.commitText() }
                // A live flow caret session ends first (flushing its typing burst).
                BackHandler(enabled = editorLeft.flowEditingActive) { editorLeft.flowText.endSession() }
                // Otherwise Back closes the note and pops to backstage (guarded for unsaved edits).
                BackHandler(enabled = editorLeft.editingField == null && !editorLeft.flowEditingActive && (!editorLeft.splitScreenActive || !editorRight.noteOpen)) {
                    guarded { editorLeft.goHome() }
                }

                if (editorLeft.splitScreenActive && editorRight.noteOpen) {
                    BackHandler(enabled = editorRight.editingField != null) { editorRight.commitText() }
                    BackHandler(enabled = editorRight.flowEditingActive) { editorRight.flowText.endSession() }
                    BackHandler(enabled = editorRight.editingField == null && !editorRight.flowEditingActive) {
                        editorRight.goHome()
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(LocalPalette.current.bg.toComposeColor())
                ) {
                    // LEFT COLUMN
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .focusRequester(focusRequester)
                            .focusable()
                            .pointerInput(Unit) {
                                detectTapGestures(onPress = { onSetActiveEditor(editorLeft) })
                            }
                            .onPreviewKeyEvent { ke ->
                                ke.type == KeyEventType.KeyDown && editorLeft.handleKeyDown(ke.nativeKeyEvent)
                            },
                    ) {
                        Toolbar(
                            editorLeft,
                            onToggleFullscreen = onToggleFullscreen,
                            onOpenBackstage = { backstageView = com.xnotes.ui.BackstageView.HOME; guarded { editorLeft.goHome() } },
                            onInsertImage = { pendingInsertContent = null; insertImageLauncher.launch(arrayOf("image/*")) },
                            onPresent = { showPresentation = true },
                        )
                        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            if (editorLeft.sidebarVisible) {
                                com.xnotes.ui.SidePanel(
                                    editorLeft,
                                    onSharePages = { pages, asPdf -> sharePages(pages, asPdf) },
                                    onSavePagesAsPdf = { pages -> savePagesAsPdf(pages) },
                                    onSavePagesAsImages = { pages -> savePagesAsImages(pages) },
                                )
                            }
                            Box(modifier = Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                                AndroidView(
                                    factory = { editorLeft.view },
                                    modifier = Modifier.fillMaxSize(),
                                    update = { it.requestRender() }, // repaint on (re)attach so a push never flashes blank
                                )
                                editorLeft.editingField?.let { field ->
                                    com.xnotes.ui.TextEditorOverlay(editorLeft, field)
                                }
                                com.xnotes.ui.SelectionMenu(editorLeft)
                                com.xnotes.ui.ScreenshotMenu(editorLeft)
                                com.xnotes.ui.TextStyleBar(editorLeft)
                                com.xnotes.ui.LongPressMenu(editorLeft, onInsertImageAt = { c ->
                                    pendingInsertContent = c
                                    insertImageLauncher.launch(arrayOf("image/*"))
                                })
                                com.xnotes.ui.FlowEditMenu(editorLeft)
                                ZoomLockHint(editorLeft)
                                RefiningPdfHint(editorLeft)
                            }
                        }
                        com.xnotes.ui.TextFormatBar(editorLeft)
                    }

                    if (editorLeft.splitScreenActive) {
                        Box(
                            modifier = Modifier
                                .width(4.dp)
                                .fillMaxHeight()
                                .background(LocalPalette.current.border.toComposeColor())
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .pointerInput(Unit) {
                                    detectTapGestures(onPress = { onSetActiveEditor(editorRight) })
                                }
                                .onPreviewKeyEvent { ke ->
                                    ke.type == KeyEventType.KeyDown && editorRight.handleKeyDown(ke.nativeKeyEvent)
                                }
                        ) {
                            if (!editorRight.noteOpen) {
                                com.xnotes.ui.Backstage(
                                    editor = editorRight,
                                    view = com.xnotes.ui.BackstageView.HOME,
                                    onSelectView = {},
                                    onExitApp = {},
                                    onImportCodeTheme = {},
                                    onImportFont = {},
                                    onOpenSystem = {},
                                    onImportPdf = {},
                                    onOpenFile = { uri ->
                                        val name = displayNameOf(resolver, Uri.parse(uri))
                                        scope.launch { editorRight.openAsync(uri, name) }
                                    },
                                    onPickRoot = {},
                                    onShareFile = {},
                                    onSaveCopyFile = {},
                                    onExportFilePdf = {}
                                )
                            } else {
                                Toolbar(
                                    editorRight,
                                    onToggleFullscreen = onToggleFullscreen,
                                    onOpenBackstage = { editorRight.goHome() },
                                    onInsertImage = { pendingInsertContent = null; insertImageLauncher.launch(arrayOf("image/*")) },
                                    onPresent = { showPresentation = true },
                                )
                                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                    if (editorRight.sidebarVisible) {
                                        com.xnotes.ui.SidePanel(
                                            editorRight,
                                            onSharePages = { pages, asPdf -> sharePages(pages, asPdf) },
                                            onSavePagesAsPdf = { pages -> savePagesAsPdf(pages) },
                                            onSavePagesAsImages = { pages -> savePagesAsImages(pages) },
                                        )
                                    }
                                    Box(modifier = Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                                        AndroidView(
                                            factory = { editorRight.view },
                                            modifier = Modifier.fillMaxSize(),
                                            update = { it.requestRender() }, // repaint on (re)attach so a push never flashes blank
                                        )
                                        editorRight.editingField?.let { field ->
                                            com.xnotes.ui.TextEditorOverlay(editorRight, field)
                                        }
                                        com.xnotes.ui.SelectionMenu(editorRight)
                                        com.xnotes.ui.ScreenshotMenu(editorRight)
                                        com.xnotes.ui.TextStyleBar(editorRight)
                                        com.xnotes.ui.LongPressMenu(editorRight, onInsertImageAt = { c ->
                                            pendingInsertContent = c
                                            insertImageLauncher.launch(arrayOf("image/*"))
                                        })
                                        com.xnotes.ui.FlowEditMenu(editorRight)
                                        ZoomLockHint(editorRight)
                                        RefiningPdfHint(editorRight)
                                    }
                                }
                                com.xnotes.ui.TextFormatBar(editorRight)
                            }
                        }
                    }
                }
            }
        }
    }
    if (showShareChooser) {
        val shareUri = pendingShareUri
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showShareChooser = false; pendingShareUri = null },
            title = { androidx.compose.material3.Text("Share note") },
            text = { androidx.compose.material3.Text("Share “${shareUri?.let { stemOf(it) } ?: ""}” as:") },
            confirmButton = {
                androidx.compose.foundation.layout.Row {
                    androidx.compose.material3.TextButton(onClick = { showShareChooser = false; pendingShareUri = null; shareUri?.let { shareFile(it, asPdf = false) } }) {
                        androidx.compose.material3.Text(".xnote file")
                    }
                    androidx.compose.material3.TextButton(onClick = { showShareChooser = false; pendingShareUri = null; shareUri?.let { shareFile(it, asPdf = true) } }) {
                        androidx.compose.material3.Text("PDF")
                    }
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showShareChooser = false; pendingShareUri = null }) {
                    androidx.compose.material3.Text("Cancel")
                }
            },
        )
    }
    if (showPresentation) {
        com.xnotes.ui.PresentationDialog(editor = editor, onDismiss = { showPresentation = false })
    }
    guardAction?.let { action ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { guardAction = null },
            title = { androidx.compose.material3.Text("Unsaved changes") },
            text = { androidx.compose.material3.Text("Save changes to “${editor.title}” before continuing?") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    guardAction = null
                    val uri = editor.currentUri
                    if (uri != null) {
                        editor.saveTo(uri)
                        action()
                    } else {
                        pendingAfterSave = action
                        createLauncher.launch("${editor.title}.xnote")
                    }
                }) { androidx.compose.material3.Text("Save") }
            },
            dismissButton = {
                androidx.compose.foundation.layout.Row {
                    androidx.compose.material3.TextButton(onClick = { guardAction = null; action() }) {
                        androidx.compose.material3.Text("Discard")
                    }
                    androidx.compose.material3.TextButton(onClick = { guardAction = null }) {
                        androidx.compose.material3.Text("Cancel")
                    }
                }
            },
        )
    }
    exportProgress?.let { (done, total) ->
        PdfExportDialog(done = done, total = total, onCancel = {
            exportCancel?.set(true) // abort this export's page loop AND its write stream
            exportProgress = null   // hide at once; the job then discards the half-written temp
        })
    }
    if (editor.importing) {
        // OPEN imports an .xnote; everything else is the PDF import the loader is named for.
        PdfImportDialog(
            isPdf = editor.pendingImport?.kind != com.xnotes.ui.ImportKind.OPEN,
            onCancel = { editor.cancelImportInProgress() }, // the stream-copy stops at its next buffer
        )
    }
    // Tapping a note reads it off-thread (editor.opening). Only show the spinner once the read has run
    // long enough to matter, so opening a small note never flashes a dialog; a big PDF gets the loader.
    var showOpening by remember { mutableStateOf(false) }
    LaunchedEffect(editor.opening) {
        if (editor.opening) { delay(160); showOpening = editor.opening } else showOpening = false
    }
    if (showOpening && editor.opening) {
        SpinnerDialog("Opening note…", onCancel = {
            editor.cancelOpenInProgress() // discard the loaded note when its read returns
            showOpening = false           // dismiss at once; editor.opening clears as the read unwinds
        })
    }
    // A dirty note is flushed off-thread on close/pause; show the saving overlay only once the write is
    // slow enough to matter, so closing a small note never flashes a dialog.
    var showSaving by remember { mutableStateOf(false) }
    LaunchedEffect(editor.savingNote) {
        if (editor.savingNote) { delay(160); showSaving = editor.savingNote } else showSaving = false
    }
    if (showSaving && editor.savingNote) SavingDialog()
}

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
private fun PdfExportDialog(done: Int, total: Int, onCancel: () -> Unit) {
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
                .clip(RoundedCornerShape(14.dp))
                .background(palette.surface.toComposeColor())
                .border(1.dp, palette.border.toComposeColor(), RoundedCornerShape(14.dp))
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
                "Exporting to PDF…",
                color = palette.text.toComposeColor(),
                fontSize = 15.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    writing -> "Writing the PDF…"
                    total == 0 -> "Preparing…"
                    done < total -> "page $done / $total"
                    else -> "Writing $total page${if (total == 1) "" else "s"}…"
                },
                color = palette.textDim.toComposeColor(),
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(14.dp))
            androidx.compose.material3.TextButton(onClick = onCancel) {
                Text("Cancel", color = palette.accent.toComposeColor())
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
private fun SpinnerDialog(title: String, onCancel: () -> Unit) {
    val palette = LocalPalette.current
    androidx.compose.ui.window.Dialog(onDismissRequest = onCancel) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(palette.surface.toComposeColor())
                .border(1.dp, palette.border.toComposeColor(), RoundedCornerShape(14.dp))
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
            Text("This may take a moment.", color = palette.textDim.toComposeColor(), fontSize = 13.sp)
            Spacer(Modifier.height(14.dp))
            androidx.compose.material3.TextButton(onClick = onCancel) {
                Text("Cancel", color = palette.accent.toComposeColor())
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
                .clip(RoundedCornerShape(14.dp))
                .background(palette.surface.toComposeColor())
                .border(1.dp, palette.border.toComposeColor(), RoundedCornerShape(14.dp))
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
            Text("Saving your notes…", color = palette.text.toComposeColor(), fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            Text("This may take a moment.", color = palette.textDim.toComposeColor(), fontSize = 13.sp)
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
    SpinnerDialog(if (isPdf) "Importing PDF…" else "Importing note…", onCancel)

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
        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
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
                .clip(RoundedCornerShape(6.dp))
                .background(palette.surface.toComposeColor())
                .border(1.dp, palette.border.toComposeColor(), RoundedCornerShape(6.dp))
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
                "Refining PDF colours $done/$total pages…",
                color = palette.textDim.toComposeColor(),
                fontFamily = FontFamily.Monospace,
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
        modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(palette.surface.toComposeColor())
                .border(1.dp, palette.border.toComposeColor(), RoundedCornerShape(6.dp))
                .clickable { editor.toggleZoomLock(); armToken++ }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (locked) XnotesIcons.lock else XnotesIcons.unlock,
                contentDescription = if (locked) "Unlock zoom" else "Lock zoom at fit width",
                tint = (if (locked) palette.accent else palette.textDim).toComposeColor(),
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (locked) "Unlock zoom" else "Lock zoom",
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
