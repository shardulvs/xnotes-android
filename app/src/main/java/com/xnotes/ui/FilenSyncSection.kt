package com.xnotes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.settings.Preferences
import com.xnotes.sync.filen.FilenApi
import com.xnotes.sync.filen.FilenSession
import com.xnotes.sync.filen.FilenSyncManager
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SYNC_INTERVALS = listOf(
    15 to "Every 15 minutes",
    30 to "Every 30 minutes",
    60 to "Every hour",
    180 to "Every 3 hours",
    360 to "Every 6 hours",
    720 to "Every 12 hours",
    1440 to "Once a day",
)

private val SYNC_DIRECTIONS = listOf(
    "both" to "Both ways",
    "up" to "N-Notes to Filen",
    "down" to "Filen to N-Notes",
    "off" to "None - off",
)

@Composable
fun FilenSyncSection(editor: Editor) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    var prefs by remember { mutableStateOf(editor.preferences) }
    fun update(p: Preferences) {
        prefs = p
        editor.applyPreferences(p)
        FilenSyncManager.reschedule(context)
    }
    LaunchedEffect(editor.prefsVersion) { prefs = editor.preferences }

    var session by remember { mutableStateOf(FilenSyncManager.session(context)) }
    val status by FilenSyncManager.status.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Filen sync", color = palette.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(
            "Back up and sync your notes to Filen end-to-end encrypted cloud storage.",
            color = palette.textDim.toComposeColor(), fontSize = 12.sp,
        )
        if (session == null) {
            FilenSignIn(palette, onSignedIn = { session = it })
        } else {
            FilenSignedIn(
                editor = editor,
                prefs = prefs,
                session = session!!,
                status = status,
                onUpdate = ::update,
                onSignOut = {
                    FilenSyncManager.logout(context)
                    session = null
                },
            )
        }
    }
}

@Composable
private fun FilenSignIn(palette: com.xnotes.ui.theme.Palette, onSignedIn: (FilenSession) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var twoFactor by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    FilenField("Email", email, KeyboardType.Email) { email = it }
    FilenField("Password", password, KeyboardType.Password, mask = true) { password = it }
    FilenField("Two-factor code (optional)", twoFactor, KeyboardType.Number) { twoFactor = it }
    error?.let { Text(it, color = palette.accent.toComposeColor(), fontSize = 12.sp) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = {
                error = null
                busy = true
                scope.launch {
                    val result = FilenSyncManager.login(context, email, password, twoFactor)
                    busy = false
                    result.fold({ onSignedIn(it) }, { error = it.message ?: "Sign-in failed" })
                }
            },
            enabled = !busy && email.isNotBlank() && password.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = palette.accent.toComposeColor(), contentColor = palette.bg.toComposeColor()),
        ) { Text("Sign in") }
        if (busy) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = palette.accent.toComposeColor(), strokeWidth = 2.dp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilenSignedIn(
    editor: Editor,
    prefs: Preferences,
    session: FilenSession,
    status: FilenSyncManager.Status,
    onUpdate: (Preferences) -> Unit,
    onSignOut: () -> Unit,
) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showPicker by remember { mutableStateOf(false) }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Signed in as ${session.email}", color = palette.text.toComposeColor(), fontSize = 13.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = onSignOut) { Text("Sign out", fontSize = 13.sp) }
    }

    FilenLabel("Backup folder")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            prefs.filenFolderName.ifEmpty { "None selected" },
            color = if (prefs.filenFolderName.isEmpty()) palette.textDim.toComposeColor() else palette.text.toComposeColor(),
            fontSize = 13.sp, modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { showPicker = true }) { Text("Choose", fontSize = 13.sp) }
    }

    FilenCheckRow("Enable sync", prefs.filenSyncEnabled) { onUpdate(prefs.copy(filenSyncEnabled = it)) }
    FilenCheckRow("Only on Wi-Fi", prefs.filenWifiOnly) { onUpdate(prefs.copy(filenWifiOnly = it)) }

    FilenLabel("When to sync")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CheckChip("Automatically", prefs.filenAutoSync) { onUpdate(prefs.copy(filenAutoSync = !prefs.filenAutoSync)) }
        CheckChip("On note close", prefs.filenSyncOnNoteExit) { onUpdate(prefs.copy(filenSyncOnNoteExit = !prefs.filenSyncOnNoteExit)) }
        CheckChip("On app open", prefs.filenSyncOnAppOpen) { onUpdate(prefs.copy(filenSyncOnAppOpen = !prefs.filenSyncOnAppOpen)) }
    }

    FilenLabel("Sync direction")
    OptionDropdown(SYNC_DIRECTIONS, prefs.filenSyncDirection) { onUpdate(prefs.copy(filenSyncDirection = it)) }

    FilenLabel("Sync frequency")
    OptionDropdown(SYNC_INTERVALS.map { it.first.toString() to it.second }, prefs.filenSyncIntervalMinutes.toString()) {
        onUpdate(prefs.copy(filenSyncIntervalMinutes = it.toInt()))
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = { scope.launch { FilenSyncManager.syncNow(context) } },
            enabled = !status.running && prefs.filenFolderUuid.isNotEmpty() && prefs.filenSyncDirection != "off",
            colors = ButtonDefaults.buttonColors(containerColor = palette.accent.toComposeColor(), contentColor = palette.bg.toComposeColor()),
        ) { Text("Sync now") }
        if (status.running) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = palette.accent.toComposeColor(), strokeWidth = 2.dp)
    }
    val statusLine = buildString {
        if (status.message.isNotEmpty()) append(status.message)
        if (status.lastSyncMs > 0) {
            if (isNotEmpty()) append("  -  ")
            append("last ${SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(status.lastSyncMs))}")
        }
    }
    if (statusLine.isNotEmpty()) Text(statusLine, color = palette.textDim.toComposeColor(), fontSize = 12.sp)

    if (showPicker) {
        FilenFolderPicker(
            baseUuid = session.baseFolderUuid,
            onDismiss = { showPicker = false },
            onSelected = { uuid, path ->
                showPicker = false
                onUpdate(prefs.copy(filenFolderUuid = uuid, filenFolderName = path))
            },
        )
    }
}

@Composable
private fun FilenFolderPicker(baseUuid: String, onDismiss: () -> Unit, onSelected: (String, String) -> Unit) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stack by remember { mutableStateOf(listOf(baseUuid to "Drive root")) }
    var folders by remember { mutableStateOf<List<Pair<String, FilenApi.RemoteFolder>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var newFolder by remember { mutableStateOf("") }
    var reloadKey by remember { mutableStateOf(0) }
    val current = stack.last()
    val pathLabel = if (stack.size == 1) "Drive root" else stack.drop(1).joinToString("/") { it.second }

    LaunchedEffect(current.first, reloadKey) {
        loading = true
        error = null
        FilenSyncManager.listFolders(context, current.first).fold(
            { folders = it.sortedBy { f -> f.first.lowercase() }; loading = false },
            { error = it.message; loading = false },
        )
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onSelected(current.first, pathLabel) }) { Text("Use this folder") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = palette.menuBg.toComposeColor(),
        title = { Text("Choose Filen folder", color = palette.text.toComposeColor(), fontSize = 16.sp) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (stack.size > 1) {
                        TextButton(onClick = { stack = stack.dropLast(1) }) { Text("Up", fontSize = 13.sp) }
                    }
                    Text(pathLabel, color = palette.textDim.toComposeColor(), fontSize = 12.sp)
                }
                Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(8.dp))) {
                    when {
                        loading -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(24.dp), color = palette.accent.toComposeColor(), strokeWidth = 2.dp)
                        error != null -> Text(error!!, color = palette.accent.toComposeColor(), fontSize = 12.sp, modifier = Modifier.align(Alignment.Center))
                        folders.isEmpty() -> Text("No subfolders", color = palette.textDim.toComposeColor(), fontSize = 12.sp, modifier = Modifier.align(Alignment.Center))
                        else -> Column(Modifier.verticalScroll(rememberScrollState())) {
                            folders.forEach { (name, folder) ->
                                Text(
                                    name, color = palette.text.toComposeColor(), fontSize = 14.sp,
                                    modifier = Modifier.fillMaxWidth()
                                        .clickable { stack = stack + (folder.uuid to name) }
                                        .padding(vertical = 10.dp, horizontal = 8.dp),
                                )
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newFolder, onValueChange = { newFolder = it }, singleLine = true,
                        placeholder = { Text("New folder name") }, modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = newFolder.isNotBlank(),
                        onClick = {
                            val name = newFolder.trim()
                            newFolder = ""
                            scope.launch {
                                FilenSyncManager.createFolder(context, current.first, name).onSuccess { reloadKey++ }
                            }
                        },
                    ) { Text("Create") }
                }
            }
        },
    )
}

@Composable
private fun FilenField(label: String, value: String, keyboard: KeyboardType, mask: Boolean = false, onChange: (String) -> Unit) {
    FilenLabel(label)
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        visualTransformation = if (mask) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun FilenLabel(text: String) {
    val palette = LocalPalette.current
    Text(text, color = palette.accent.toComposeColor(), fontSize = 13.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun FilenCheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onChange(it) })
        Spacer(Modifier.width(4.dp))
        Text(label, color = palette.text.toComposeColor(), fontSize = 14.sp)
    }
}
