package com.xnotes

import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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

class MainActivity : ComponentActivity() {

    private var fullscreen = true // open in full screen by default
    private var editor: Editor? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(com.xnotes.R.style.Theme_Xnotes) // leave the dark launch/splash theme behind
        applyFullscreen()
        setContent {
            val context = LocalContext.current
            val ed = remember { Editor(context).also { editor = it } }
            var ready by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                val start = android.os.SystemClock.uptimeMillis()
                ed.restoreSession() // heavy load off-thread; loader animates meanwhile
                val elapsed = android.os.SystemClock.uptimeMillis() - start
                if (elapsed < MIN_LOADER_MS) kotlinx.coroutines.delay(MIN_LOADER_MS - elapsed)
                ready = true
                ed.prewarmBackstage() // warm recents/explorer caches so the first backstage open is instant
            }
            XnotesTheme(ed.palette) {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (ready) EditorScreen(ed, onToggleFullscreen = ::toggleFullscreen)
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && fullscreen) applyFullscreen() // re-hide transient bars after they swipe in
    }

    override fun onPause() {
        super.onPause()
        editor?.persist()
    }

    override fun onDestroy() {
        super.onDestroy()
        editor?.stopPresentation()
    }

    private fun toggleFullscreen() {
        fullscreen = !fullscreen
        applyFullscreen()
    }

    private fun applyFullscreen() {
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
private fun EditorScreen(editor: Editor, onToggleFullscreen: () -> Unit) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var showPresentation by remember { mutableStateOf(false) }
    // Fresh install / last session on home -> open Home; last session inside a note -> open that note.
    var showHome by remember { mutableStateOf(editor.startOnHome) }
    var backstageView by remember { mutableStateOf(com.xnotes.ui.BackstageView.RECENT) }
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
    // Flipped by the dialog's Cancel/dismiss so the background render aborts at the next page.
    val exportCancelled = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    // Page indices awaiting a SAF "Save as" destination (side-panel page export).
    var pendingExportPages by remember { mutableStateOf<List<Int>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val resolver = context.contentResolver
    val rwFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    // "Open…" reads the picked .xnote and asks for a name before saving it into the folder (it opens only when tapped).
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val stem = com.xnotes.core.util.Paths.stem(displayNameOf(resolver, it) ?: "Note")
            runCatching {
                val bytes = resolver.openInputStream(it)?.use { s -> s.readBytes() }
                if (bytes != null) {
                    editor.requestImport(com.xnotes.ui.ImportKind.OPEN, stem, bytes)
                    backstageView = com.xnotes.ui.BackstageView.RECENT
                    showHome = true
                }
            }.onFailure { editor.message = "Could not open the note." }
        }
    }
    val createLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        uri?.let {
            runCatching { resolver.takePersistableUriPermission(it, rwFlags) }
            val name = displayNameOf(resolver, it)
            runCatching { resolver.openOutputStream(it)?.use { o -> editor.save(o, it.toString(), name) } }
                .onSuccess { val p = pendingAfterSave; pendingAfterSave = null; p?.invoke() }
                .onFailure { editor.message = "Could not save the note."; pendingAfterSave = null }
        }
    }

    // "Import PDF" reads the picked PDF and asks for a name before saving it as an .xnote in the folder (opens only when tapped).
    val importPdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val stem = com.xnotes.core.util.Paths.stem(displayNameOf(resolver, it) ?: "Document")
            runCatching {
                val bytes = resolver.openInputStream(it)?.use { s -> s.readBytes() }
                if (bytes != null) {
                    editor.requestImport(com.xnotes.ui.ImportKind.PDF, stem, bytes)
                    backstageView = com.xnotes.ui.BackstageView.RECENT
                    showHome = true
                }
            }.onFailure { editor.message = "Could not import the PDF." }
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
            runCatching { resolver.openOutputStream(Uri.parse(uri), "wt")?.use { o -> editor.save(o, uri) } }
                .onFailure { createLauncher.launch("${editor.title}.xnote") }
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

    fun openRecent(uriStr: String) {
        val uri = Uri.parse(uriStr)
        val name = displayNameOf(resolver, uri)
        val opened = runCatching {
            resolver.openInputStream(uri)?.use { s -> editor.open(s, uriStr, name) } != null
        }.getOrDefault(false)
        if (!opened) {
            editor.message = "Couldn’t open that note — it may have been moved or deleted."
            editor.removeRecentFile(uriStr)
        }
    }

    fun openTreeFile(uriStr: String) {
        val uri = Uri.parse(uriStr)
        val name = displayNameOf(resolver, uri)
        val opened = runCatching {
            resolver.openInputStream(uri)?.use { s -> editor.open(s, uriStr, name) } != null
        }.getOrDefault(false)
        if (!opened) editor.message = "Could not open that note."
    }

    fun stemOf(uriStr: String): String =
        com.xnotes.core.util.Paths.stem(displayNameOf(resolver, Uri.parse(uriStr)) ?: "Note")

    // Render a PDF off the main thread into a temp file behind a cancellable progress dialog;
    // only once it reaches 100% does [onReady] run — opening the SAF picker or a share sheet.
    // Dismissing the dialog flips [exportCancelled], so the render aborts at the next page and
    // the half-written temp is discarded. [shareDir] picks the cache subdir: FileProvider only
    // exposes cache/share, so shares render there; plain "save" exports use cache/export.
    fun runPdfExport(
        stem: String,
        shareDir: Boolean,
        render: (java.io.OutputStream, (Int, Int) -> Unit, () -> Boolean) -> Unit,
        onReady: (java.io.File) -> Unit,
    ) {
        exportCancelled.set(false)
        exportProgress = 0 to 0 // show the dialog at once; the render fills in the real total
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val dir = java.io.File(context.cacheDir, if (shareDir) "share" else "export").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() } // keep only the file this export produces
            val temp = java.io.File(dir, "$stem.pdf")
            val ok = runCatching {
                java.io.FileOutputStream(temp).use { o ->
                    // Ignore a late progress tick after Cancel, so the just-hidden dialog can't flash back.
                    render(o, { done, total -> if (!exportCancelled.get()) exportProgress = done to total }, { exportCancelled.get() })
                }
            }.isSuccess
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                exportProgress = null // hide the dialog
                when {
                    exportCancelled.get() -> temp.delete()
                    ok -> onReady(temp)
                    else -> { temp.delete(); editor.message = "Could not export to PDF." }
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
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    editor.keyActions = remember {
        Editor.KeyActions(
            newNote = { guarded { editor.newNote() } },
            open = {
                if (editor.browseRoot != null) openLauncher.launch(arrayOf("*/*"))
                else { backstageView = com.xnotes.ui.BackstageView.RECENT; showHome = true }
            },
            save = { saveOrPrompt() },
            saveAs = { createLauncher.launch("${editor.title}.xnote") },
            exportPdf = {
                runPdfExport(editor.title, shareDir = false,
                    render = { o, prog, cancel -> editor.exportPdf(o, prog, cancel) },
                    onReady = { temp -> pendingExportTemp = temp; savePdfLauncher.launch("${editor.title}.pdf") })
            },
            preferences = { backstageView = com.xnotes.ui.BackstageView.PREFERENCES; showHome = true },
            fullscreen = onToggleFullscreen,
        )
    }

    LaunchedEffect(editor.message) {
        editor.message?.let {
            snackbar.showSnackbar(it)
            editor.message = null
        }
    }

    // Remember the current surface so relaunch returns to it (home vs. the open note).
    LaunchedEffect(showHome) { editor.setStartOnHome(showHome) }

    // While a text box is open, Back commits-or-dismisses it (and hides the keyboard).
    BackHandler(enabled = editor.editingField != null) { editor.commitText() }

    // From inside the editor, the back button returns to Home (not out of the app).
    BackHandler(enabled = !showHome && editor.editingField == null) {
        backstageView = com.xnotes.ui.BackstageView.RECENT
        showHome = true
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { ke ->
                    ke.type == KeyEventType.KeyDown && editor.handleKeyDown(ke.nativeKeyEvent)
                },
        ) {
            Toolbar(
                editor,
                onToggleFullscreen = onToggleFullscreen,
                onOpenBackstage = { backstageView = com.xnotes.ui.BackstageView.RECENT; showHome = true },
                onInsertImage = { pendingInsertContent = null; insertImageLauncher.launch(arrayOf("image/*")) },
                onPresent = { showPresentation = true },
            )
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (editor.sidebarVisible) {
                    com.xnotes.ui.SidePanel(
                        editor,
                        onSharePages = { pages, asPdf -> sharePages(pages, asPdf) },
                        onSavePagesAsPdf = { pages -> savePagesAsPdf(pages) },
                        onSavePagesAsImages = { pages -> savePagesAsImages(pages) },
                    )
                }
                Box(modifier = Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                    AndroidView(factory = { editor.view }, modifier = Modifier.fillMaxSize())
                    editor.editingField?.let { field ->
                        com.xnotes.ui.TextEditorOverlay(editor, field)
                    }
                    com.xnotes.ui.SelectionMenu(editor)
                    com.xnotes.ui.TextStyleBar(editor)
                    com.xnotes.ui.LongPressMenu(editor, onInsertImageAt = { c ->
                        pendingInsertContent = c
                        insertImageLauncher.launch(arrayOf("image/*"))
                    })
                    ZoomLockHint(editor)
                    RefiningPdfHint(editor)
                }
            }
        }
    }

    if (showHome) {
        com.xnotes.ui.Backstage(
            editor = editor,
            view = backstageView,
            onSelectView = { backstageView = it },
            onExitApp = { (context as? android.app.Activity)?.finish() },
            onOpenSystem = { openLauncher.launch(arrayOf("*/*")) },
            onImportPdf = { importPdfLauncher.launch(arrayOf("application/pdf")) },
            onOpenRecent = { uri -> showHome = false; guarded { openRecent(uri) } },
            onOpenFile = { uri -> showHome = false; guarded { openTreeFile(uri) } },
            onPickRoot = { pickRootLauncher.launch(null) },
            onShareFile = { uri -> pendingShareUri = uri; showShareChooser = true },
            onSaveCopyFile = { uri -> pendingSaveCopyUri = uri; saveCopyLauncher.launch("${stemOf(uri)}.xnote") },
            onExportFilePdf = { uri ->
                runPdfExport(stemOf(uri), shareDir = false,
                    render = { o, prog, cancel -> editor.exportFileToPdf(uri, o, prog, cancel) },
                    onReady = { temp -> pendingExportTemp = temp; savePdfLauncher.launch("${stemOf(uri)}.pdf") })
            },
            onDismiss = { showHome = false },
        )
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
                        runCatching { resolver.openOutputStream(Uri.parse(uri), "wt")?.use { o -> editor.save(o, uri) } }
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
            exportCancelled.set(true) // the background render stops at its next page boundary
            exportProgress = null     // hide at once; runPdfExport then discards the temp file
        })
    }
}

/**
 * Determinate "Exporting to PDF…" dialog shown while a (possibly large) note is flattened to a PDF
 * off the main thread. The ring fills 0→100% by page; dismissing it (Cancel, back, or tapping
 * outside) aborts the render via [onCancel]. Styled to match the app's monospace surfaces.
 */
@Composable
private fun PdfExportDialog(done: Int, total: Int, onCancel: () -> Unit) {
    val palette = LocalPalette.current
    // Once every page is rendered, PdfDocument.writeTo() serializes the whole file in one opaque,
    // unmeasurable step — slow for a big PDF. Show an animated spinner for it instead of a frozen
    // 100% ring, so it reads as "still working", not stuck.
    val finalizing = total > 0 && done >= total
    val fraction = if (total > 0) done.toFloat() / total else 0f
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
                if (finalizing) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.fillMaxSize(),
                        color = palette.accent.toComposeColor(),
                        strokeWidth = 4.dp,
                    )
                } else {
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
                        fontFamily = FontFamily.Monospace,
                        fontSize = 15.sp,
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                "Exporting to PDF…",
                color = palette.text.toComposeColor(),
                fontFamily = FontFamily.Monospace,
                fontSize = 15.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    total <= 0 -> "Preparing…"
                    done < total -> "page $done / $total"
                    else -> "Writing $total page${if (total == 1) "" else "s"}…"
                },
                color = palette.textDim.toComposeColor(),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(14.dp))
            androidx.compose.material3.TextButton(onClick = onCancel) {
                Text("Cancel", color = palette.accent.toComposeColor(), fontFamily = FontFamily.Monospace)
            }
        }
    }
}

/**
 * Subtle, non-blocking hint shown bottom-right while a dark-mode PDF's embedded-image colours are
 * still being parsed in the background ([Editor.isRefiningPdf]). The page is already visible
 * (inverted); this just signals that the images will snap to their true colours shortly.
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
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(palette.surface.toComposeColor())
                .border(1.dp, palette.border.toComposeColor(), RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = palette.accent.toComposeColor(),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Refining PDF colours…",
                color = palette.textDim.toComposeColor(),
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/**
 * Transient "lock zoom" affordance, centred just below the toolbar. Appears when a pinch snaps to
 * fit-to-width ([Editor.zoomLockHint] bumps), auto-dismisses after a moment, and locks the zoom on
 * tap. Only ever shown while unlocked, since a locked pinch can't reach fit-width to trigger it.
 */
@Composable
private fun BoxScope.ZoomLockHint(editor: Editor) {
    val palette = LocalPalette.current
    var visible by remember { mutableStateOf(false) }
    var justLocked by remember { mutableStateOf(false) }
    LaunchedEffect(editor.zoomLockHint) {
        if (editor.zoomLockHint > 0) {
            justLocked = false // a fresh snap shows the open "tap to lock" affordance again
            visible = true
            delay(2500)
            visible = false
        }
    }
    // After tapping, briefly hold the closed+accent padlock as confirmation, then dismiss.
    LaunchedEffect(justLocked) {
        if (justLocked) {
            delay(700)
            visible = false
            justLocked = false
        }
    }
    // Breaking past the magnet dismisses the hint at once (unless mid lock-confirmation).
    LaunchedEffect(editor.zoomLockHintDismiss) {
        if (editor.zoomLockHintDismiss > 0 && !justLocked) visible = false
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
                .clickable(enabled = !locked) { editor.toggleZoomLock(); justLocked = true }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (locked) XnotesIcons.lock else XnotesIcons.unlock,
                contentDescription = "Lock zoom at fit width",
                tint = (if (locked) palette.accent else palette.textDim).toComposeColor(),
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Lock zoom",
                color = palette.text.toComposeColor(),
                fontFamily = FontFamily.Monospace,
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
