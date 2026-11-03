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
import com.xnotes.settings.CloudFolder
import com.xnotes.settings.Preferences
import com.xnotes.sync.CloudService
import com.xnotes.sync.CloudServices
import com.xnotes.sync.CloudStorage
import com.xnotes.sync.SyncManager
import com.xnotes.sync.filen.FilenService
import com.xnotes.sync.nextcloud.NextcloudService
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

private fun syncDirections(service: String) = listOf(
    "both" to "Both ways",
    "up" to "N-Notes to $service",
    "down" to "$service to N-Notes",
    "off" to "None - off",
)

@Composable
fun CloudSyncSection(editor: Editor) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    var prefs by remember { mutableStateOf(editor.preferences) }
    fun update(p: Preferences) {
        prefs = p
        editor.applyPreferences(p)
        SyncManager.reschedule(context)
    }
    LaunchedEffect(editor.prefsVersion) { prefs = editor.preferences }
    LaunchedEffect(prefs.cloudService) { SyncManager.primeStatus(context) }

    val status by SyncManager.status.collectAsState()
    val service = CloudServices.byId(prefs.cloudService)
    var account by remember(service) { mutableStateOf(service?.account(context)) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Cloud sync", color = palette.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(
            "Back up and sync your notes to a cloud service. Only the selected service syncs.",
            color = palette.textDim.toComposeColor(), fontSize = 12.sp,
        )
        SyncLabel("Cloud service")
        OptionDropdown(
            listOf(CloudServices.NONE to "None - off") + CloudServices.all.map { it.id to it.displayName },
            prefs.cloudService,
        ) { update(prefs.copy(cloudService = it)) }
        when {
            service == null -> Unit
            account == null -> ServiceSignIn(service, onSignedIn = { account = service.account(context) })
            else -> SignedIn(
                service = service,
                account = account!!,
                prefs = prefs,
                status = status,
                onUpdate = ::update,
                onSignOut = {
                    SyncManager.signOut(context, service)
                    account = null
                },
            )
        }
    }
}

/** Each service signs in its own way, so this is the one place that knows the concrete services. */
@Composable
private fun ServiceSignIn(service: CloudService, onSignedIn: () -> Unit) {
    when (service) {
        FilenService -> FilenSignIn(onSignedIn)
        NextcloudService -> NextcloudSignIn(onSignedIn)
    }
}

@Composable
private fun NextcloudSignIn(onSignedIn: () -> Unit) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    SyncField("Server address", server, KeyboardType.Uri) { server = it }
    SyncField("User name", user, KeyboardType.Text) { user = it }
    SyncField("App password", password, KeyboardType.Password, mask = true) { password = it }
    Text(
        "Create an app password in Nextcloud under Settings - Security - Devices & sessions.",
        color = palette.textDim.toComposeColor(), fontSize = 12.sp,
    )
    error?.let { Text(it, color = palette.accent.toComposeColor(), fontSize = 12.sp) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = {
                error = null
                busy = true
                scope.launch {
                    val result = NextcloudService.signIn(context, server, user, password)
                    busy = false
                    result.fold({ onSignedIn() }, { error = it.message ?: "Sign-in failed" })
                }
            },
            enabled = !busy && server.isNotBlank() && user.isNotBlank() && password.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = palette.accent.toComposeColor(), contentColor = palette.bg.toComposeColor()),
        ) { Text("Sign in") }
        if (busy) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = palette.accent.toComposeColor(), strokeWidth = 2.dp)
    }
}

@Composable
private fun FilenSignIn(onSignedIn: () -> Unit) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var twoFactor by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    SyncField("Email", email, KeyboardType.Email) { email = it }
    SyncField("Password", password, KeyboardType.Password, mask = true) { password = it }
    SyncField("Two-factor code (optional)", twoFactor, KeyboardType.Number) { twoFactor = it }
    error?.let { Text(it, color = palette.accent.toComposeColor(), fontSize = 12.sp) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = {
                error = null
                busy = true
                scope.launch {
                    val result = FilenService.signIn(context, email, password, twoFactor)
                    busy = false
                    result.fold({ onSignedIn() }, { error = it.message ?: "Sign-in failed" })
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
private fun SignedIn(
    service: CloudService,
    account: String,
    prefs: Preferences,
    status: SyncManager.Status,
    onUpdate: (Preferences) -> Unit,
    onSignOut: () -> Unit,
) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showPicker by remember { mutableStateOf(false) }
    val folder = prefs.cloudFolders[service.id]

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Signed in as $account", color = palette.text.toComposeColor(), fontSize = 13.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = onSignOut) { Text("Sign out", fontSize = 13.sp) }
    }

    SyncLabel("Backup folder")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            folder?.name?.ifEmpty { null } ?: "None selected",
            color = if (folder?.name.isNullOrEmpty()) palette.textDim.toComposeColor() else palette.text.toComposeColor(),
            fontSize = 13.sp, modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { showPicker = true }) { Text("Choose", fontSize = 13.sp) }
    }

    SyncCheckRow("Only on Wi-Fi", prefs.syncWifiOnly) { onUpdate(prefs.copy(syncWifiOnly = it)) }
    SyncCheckRow("Keep screen on while syncing", prefs.syncKeepScreenOn) { onUpdate(prefs.copy(syncKeepScreenOn = it)) }

    SyncLabel("When to sync")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CheckChip("Automatically", prefs.syncAuto) { onUpdate(prefs.copy(syncAuto = !prefs.syncAuto)) }
        CheckChip("On note close", prefs.syncOnNoteExit) { onUpdate(prefs.copy(syncOnNoteExit = !prefs.syncOnNoteExit)) }
        CheckChip("On app open", prefs.syncOnAppOpen) { onUpdate(prefs.copy(syncOnAppOpen = !prefs.syncOnAppOpen)) }
    }

    SyncLabel("Sync direction")
    OptionDropdown(syncDirections(service.displayName), prefs.syncDirection) { onUpdate(prefs.copy(syncDirection = it)) }

    SyncLabel("Sync frequency")
    OptionDropdown(SYNC_INTERVALS.map { it.first.toString() to it.second }, prefs.syncIntervalMinutes.toString()) {
        onUpdate(prefs.copy(syncIntervalMinutes = it.toInt()))
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = { scope.launch { SyncManager.syncNow(context) } },
            enabled = !status.running && !folder?.id.isNullOrEmpty() && prefs.syncDirection != "off",
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
        FolderPicker(
            service = service,
            onDismiss = { showPicker = false },
            onSelected = { id, path ->
                showPicker = false
                onUpdate(prefs.copy(cloudFolders = prefs.cloudFolders + (service.id to CloudFolder(id, path))))
            },
        )
    }
}

@Composable
private fun FolderPicker(service: CloudService, onDismiss: () -> Unit, onSelected: (String, String) -> Unit) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stack by remember { mutableStateOf(listOf(service.rootFolderId(context) to "Drive root")) }
    var folders by remember { mutableStateOf<List<CloudStorage.RemoteFolder>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var newFolder by remember { mutableStateOf("") }
    var reloadKey by remember { mutableStateOf(0) }
    val current = stack.last()
    val pathLabel = if (stack.size == 1) "Drive root" else stack.drop(1).joinToString("/") { it.second }

    LaunchedEffect(current.first, reloadKey) {
        loading = true
        error = null
        SyncManager.listFolders(context, service, current.first).fold(
            { folders = it.sortedBy { f -> f.name.lowercase() }; loading = false },
            { error = it.message; loading = false },
        )
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onSelected(current.first, pathLabel) }) { Text("Use this folder") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = palette.menuBg.toComposeColor(),
        title = { Text("Choose ${service.displayName} folder", color = palette.text.toComposeColor(), fontSize = 16.sp) },
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
                            folders.forEach { folder ->
                                Text(
                                    folder.name, color = palette.text.toComposeColor(), fontSize = 14.sp,
                                    modifier = Modifier.fillMaxWidth()
                                        .clickable { stack = stack + (folder.id to folder.name) }
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
                                SyncManager.createFolder(context, service, current.first, name).onSuccess { reloadKey++ }
                            }
                        },
                    ) { Text("Create") }
                }
            }
        },
    )
}

@Composable
private fun SyncField(label: String, value: String, keyboard: KeyboardType, mask: Boolean = false, onChange: (String) -> Unit) {
    SyncLabel(label)
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        visualTransformation = if (mask) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SyncLabel(text: String) {
    val palette = LocalPalette.current
    Text(text, color = palette.accent.toComposeColor(), fontSize = 13.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun SyncCheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
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
