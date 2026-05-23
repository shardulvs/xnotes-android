package com.xnotes

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.xnotes.ui.Editor
import com.xnotes.ui.Toolbar
import com.xnotes.ui.theme.XnotesTheme

class MainActivity : ComponentActivity() {

    private var fullscreen = false
    private var editor: Editor? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            val ed = remember { Editor(context).also { editor = it } }
            XnotesTheme(ed.palette) {
                EditorScreen(ed, onToggleFullscreen = ::toggleFullscreen)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        editor?.persist()
    }

    private fun toggleFullscreen() {
        fullscreen = !fullscreen
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
    var showPreferences by remember { mutableStateOf(false) }
    val resolver = context.contentResolver
    val rwFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching { resolver.takePersistableUriPermission(it, rwFlags) }
            runCatching { resolver.openInputStream(it)?.use { s -> editor.open(s, it.toString()) } }
                .onFailure { editor.message = "Could not open the note." }
        }
    }
    val createLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        uri?.let {
            runCatching { resolver.takePersistableUriPermission(it, rwFlags) }
            runCatching { resolver.openOutputStream(it)?.use { o -> editor.save(o, it.toString()) } }
                .onFailure { editor.message = "Could not save the note." }
        }
    }

    val importPdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching { resolver.openInputStream(it)?.use { s -> editor.importPdf(s.readBytes()) } }
                .onFailure { editor.message = "Could not import the PDF." }
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

    val insertImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching { resolver.openInputStream(it)?.use { s -> editor.insertImage(s.readBytes()) } }
                .onFailure { editor.message = "Could not read the image." }
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

    LaunchedEffect(editor.message) {
        editor.message?.let {
            snackbar.showSnackbar(it)
            editor.message = null
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            Toolbar(
                editor,
                onToggleFullscreen = onToggleFullscreen,
                onOpen = { openLauncher.launch(arrayOf("*/*")) },
                onSave = { saveOrPrompt() },
                onSaveAs = { createLauncher.launch("${editor.title}.xnote") },
                onImportPdf = { importPdfLauncher.launch(arrayOf("application/pdf")) },
                onExportPdf = { exportPdfLauncher.launch("${editor.title}.pdf") },
                onInsertImage = { insertImageLauncher.launch(arrayOf("image/*")) },
                onPreferences = { showPreferences = true },
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(factory = { editor.view }, modifier = Modifier.fillMaxSize())
                editor.editingField?.let { field ->
                    com.xnotes.ui.TextEditorOverlay(editor, field)
                }
            }
        }
    }

    if (showPreferences) {
        com.xnotes.ui.PreferencesDialog(
            initial = editor.preferences,
            onDismiss = { showPreferences = false },
            onSave = { editor.applyPreferences(it); showPreferences = false },
        )
    }
}
