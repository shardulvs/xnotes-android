package com.xnotes

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.focusable
import androidx.compose.ui.Modifier
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
import com.xnotes.ui.theme.XnotesTheme

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
    var pendingExportUri by remember { mutableStateOf<String?>(null) }
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
    val exportPdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri ->
        uri?.let {
            runCatching { resolver.openOutputStream(it)?.use { o -> editor.exportPdf(o) } }
                .onFailure { editor.message = "Could not export to PDF." }
        }
    }

    // Save a copy of an explorer file (.xnote) elsewhere, and export an explorer file to PDF.
    val saveCopyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val src = pendingSaveCopyUri; pendingSaveCopyUri = null
        if (uri != null && src != null) {
            runCatching { resolver.openOutputStream(uri)?.use { o -> editor.copyFileTo(src, o) } }
                .onFailure { editor.message = "Could not save a copy." }
        }
    }
    val exportFilePdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri ->
        val src = pendingExportUri; pendingExportUri = null
        if (uri != null && src != null) {
            runCatching { resolver.openOutputStream(uri)?.use { o -> editor.exportFileToPdf(src, o) } }
                .onFailure { editor.message = "Could not export to PDF." }
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

    fun shareFile(uriStr: String, asPdf: Boolean) {
        runCatching {
            val stem = stemOf(uriStr)
            val dir = java.io.File(context.cacheDir, "share").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() } // keep only the file we're about to share
            val ext = if (asPdf) "pdf" else "xnote"
            val file = java.io.File(dir, "$stem.$ext")
            java.io.FileOutputStream(file).use { o ->
                if (asPdf) editor.exportFileToPdf(uriStr, o) else editor.copyFileTo(uriStr, o)
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = if (asPdf) "application/pdf" else "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "Share $stem"))
        }.onFailure { editor.message = "Could not share the note." }
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
            exportPdf = { exportPdfLauncher.launch("${editor.title}.pdf") },
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
                    com.xnotes.ui.SidePanel(editor)
                }
                Box(modifier = Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                    AndroidView(factory = { editor.view }, modifier = Modifier.fillMaxSize())
                    editor.editingField?.let { field ->
                        com.xnotes.ui.TextEditorOverlay(editor, field)
                    }
                    com.xnotes.ui.SelectionMenu(editor)
                    com.xnotes.ui.LongPressMenu(editor, onInsertImageAt = { c ->
                        pendingInsertContent = c
                        insertImageLauncher.launch(arrayOf("image/*"))
                    })
                }
            }
        }
    }

    if (showHome) {
        com.xnotes.ui.Backstage(
            editor = editor,
            view = backstageView,
            onSelectView = { backstageView = it },
            onOpenSystem = { openLauncher.launch(arrayOf("*/*")) },
            onImportPdf = { importPdfLauncher.launch(arrayOf("application/pdf")) },
            onOpenRecent = { uri -> showHome = false; guarded { openRecent(uri) } },
            onOpenFile = { uri -> showHome = false; guarded { openTreeFile(uri) } },
            onPickRoot = { pickRootLauncher.launch(null) },
            onShareFile = { uri -> pendingShareUri = uri; showShareChooser = true },
            onSaveCopyFile = { uri -> pendingSaveCopyUri = uri; saveCopyLauncher.launch("${stemOf(uri)}.xnote") },
            onExportFilePdf = { uri -> pendingExportUri = uri; exportFilePdfLauncher.launch("${stemOf(uri)}.pdf") },
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
