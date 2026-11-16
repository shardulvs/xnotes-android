package com.xnotes.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.xnotes.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import com.xnotes.sync.SyncLog
import com.xnotes.sync.SyncManager
import com.xnotes.sync.filen.FilenService
import com.xnotes.sync.nextcloud.NextcloudService
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.WarningColor
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SYNC_INTERVALS = listOf(
    0 to R.string.sync_never,
    15 to R.string.sync_every_15_minutes,
    30 to R.string.sync_every_30_minutes,
    60 to R.string.sync_every_hour,
    180 to R.string.sync_every_3_hours,
    360 to R.string.sync_every_6_hours,
    720 to R.string.sync_every_12_hours,
    1440 to R.string.sync_once_a_day,
)

@Composable
private fun syncDirections(service: String) = listOf(
    "both" to stringResource(R.string.sync_both_ways),
    "up" to stringResource(R.string.sync_direction_up, service),
    "down" to stringResource(R.string.sync_direction_down, service),
    "off" to stringResource(R.string.sync_none_off),
)

@Composable
fun CloudSyncSection(editor: Editor, onOpenLog: () -> Unit) {
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
    // Asked at sign-in so the sync progress and error notifications can show; a refusal only hides them.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.pref_cloud_sync), color = palette.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(
            stringResource(R.string.sync_intro),
            color = palette.textDim.toComposeColor(), fontSize = 12.sp,
        )
        Text(
            stringResource(R.string.sync_new_warning),
            color = WarningColor, fontSize = 12.sp,
        )
        SyncLabel(stringResource(R.string.sync_cloud_service))
        OptionDropdown(
            listOf(CloudServices.NONE to stringResource(R.string.sync_none_off)) + CloudServices.all.map { it.id to it.displayName },
            prefs.cloudService,
        ) { update(prefs.copy(cloudService = it)) }
        when {
            service == null -> Unit
            account == null -> ServiceSignIn(service, onSignedIn = {
                account = service.account(context)
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            })
            else -> SignedIn(
                service = service,
                account = account!!,
                prefs = prefs,
                status = status,
                onUpdate = ::update,
                onOpenLog = onOpenLog,
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

    SyncField(stringResource(R.string.sync_server_address), server, KeyboardType.Uri) { server = it }
    SyncField(stringResource(R.string.sync_user_name), user, KeyboardType.Text) { user = it }
    SyncField(stringResource(R.string.sync_app_password), password, KeyboardType.Password, mask = true) { password = it }
    Text(
        stringResource(R.string.sync_nextcloud_app_password_hint),
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
                    result.fold({ onSignedIn() }, { error = it.message ?: context.getString(R.string.sync_sign_in_failed) })
                }
            },
            enabled = !busy && server.isNotBlank() && user.isNotBlank() && password.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = palette.accent.toComposeColor(), contentColor = palette.bg.toComposeColor()),
        ) { Text(stringResource(R.string.sync_sign_in)) }
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

    SyncField(stringResource(R.string.sync_email), email, KeyboardType.Email) { email = it }
    SyncField(stringResource(R.string.sync_password), password, KeyboardType.Password, mask = true) { password = it }
    SyncField(stringResource(R.string.sync_two_factor_code), twoFactor, KeyboardType.Number) { twoFactor = it }
    error?.let { Text(it, color = palette.accent.toComposeColor(), fontSize = 12.sp) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = {
                error = null
                busy = true
                scope.launch {
                    val result = FilenService.signIn(context, email, password, twoFactor)
                    busy = false
                    result.fold({ onSignedIn() }, { error = it.message ?: context.getString(R.string.sync_sign_in_failed) })
                }
            },
            enabled = !busy && email.isNotBlank() && password.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = palette.accent.toComposeColor(), contentColor = palette.bg.toComposeColor()),
        ) { Text(stringResource(R.string.sync_sign_in)) }
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
    onOpenLog: () -> Unit,
    onSignOut: () -> Unit,
) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showPicker by remember { mutableStateOf(false) }
    val folder = prefs.cloudFolders[service.id]

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.sync_signed_in_as, account), color = palette.text.toComposeColor(), fontSize = 13.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = onSignOut) { Text(stringResource(R.string.sync_sign_out), fontSize = 13.sp) }
    }

    if (prefs.syncIntervalMinutes > 0 && !rememberBatteryUnrestricted()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.sync_battery_restricted),
                color = WarningColor, fontSize = 12.sp, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { BatteryRestriction.requestLift(context) }) { Text(stringResource(R.string.battery_allow), fontSize = 13.sp) }
        }
    }

    SyncLabel(stringResource(R.string.sync_backup_folder))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            folder?.name?.ifEmpty { null } ?: stringResource(R.string.sync_none_selected),
            color = if (folder?.name.isNullOrEmpty()) palette.textDim.toComposeColor() else palette.text.toComposeColor(),
            fontSize = 13.sp, modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { showPicker = true }) { Text(stringResource(R.string.sync_choose), fontSize = 13.sp) }
    }

    SyncCheckRow(stringResource(R.string.sync_wifi_only), prefs.syncWifiOnly) { onUpdate(prefs.copy(syncWifiOnly = it)) }
    SyncCheckRow(stringResource(R.string.sync_keep_screen_on), prefs.syncKeepScreenOn) { onUpdate(prefs.copy(syncKeepScreenOn = it)) }

    SyncLabel(stringResource(R.string.sync_when))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CheckChip(stringResource(R.string.sync_on_note_close), prefs.syncOnNoteExit) { onUpdate(prefs.copy(syncOnNoteExit = !prefs.syncOnNoteExit)) }
        CheckChip(stringResource(R.string.sync_on_app_open), prefs.syncOnAppOpen) { onUpdate(prefs.copy(syncOnAppOpen = !prefs.syncOnAppOpen)) }
        CheckChip(stringResource(R.string.sync_on_app_close), prefs.syncOnAppClose) { onUpdate(prefs.copy(syncOnAppClose = !prefs.syncOnAppClose)) }
    }
    Text(stringResource(R.string.sync_many_options_warning), color = WarningColor, fontSize = 12.sp)

    SyncLabel(stringResource(R.string.sync_direction))
    OptionDropdown(syncDirections(service.displayName), prefs.syncDirection) { onUpdate(prefs.copy(syncDirection = it)) }

    SyncLabel(stringResource(R.string.sync_frequency))
    OptionDropdown(SYNC_INTERVALS.map { it.first.toString() to stringResource(it.second) }, prefs.syncIntervalMinutes.toString()) {
        val minutes = it.toInt()
        onUpdate(prefs.copy(syncIntervalMinutes = minutes))
        if (prefs.syncIntervalMinutes == 0 && minutes > 0 && !BatteryRestriction.lifted(context)) BatteryRestriction.requestLift(context)
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = { scope.launch { SyncManager.syncNow(context) } },
            enabled = !status.running && !folder?.id.isNullOrEmpty() && prefs.syncDirection != "off",
            colors = ButtonDefaults.buttonColors(containerColor = palette.accent.toComposeColor(), contentColor = palette.bg.toComposeColor()),
        ) { Text(stringResource(R.string.sync_now)) }
        TextButton(onClick = onOpenLog) { Text(stringResource(R.string.sync_log), fontSize = 13.sp) }
    }
    if (status.running) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (status.total > 0) {
                LinearProgressIndicator(
                    progress = { status.done.toFloat() / status.total },
                    modifier = Modifier.weight(1f),
                    color = palette.accent.toComposeColor(), trackColor = palette.border.toComposeColor(),
                )
                Text("${status.done}/${status.total}", color = palette.textDim.toComposeColor(), fontSize = 12.sp)
            } else {
                // Both sides are still being listed, so the total is not known yet.
                LinearProgressIndicator(modifier = Modifier.weight(1f), color = palette.accent.toComposeColor(), trackColor = palette.border.toComposeColor())
                Text(stringResource(R.string.sync_listing_files), color = palette.textDim.toComposeColor(), fontSize = 12.sp)
            }
        }
    }
    val statusLine = buildString {
        if (status.message.isNotEmpty()) append(status.message)
        if (status.lastSyncMs > 0) {
            if (isNotEmpty()) append("  -  ")
            append(stringResource(R.string.sync_last, SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(status.lastSyncMs))))
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
                // Picking the folder finishes the setup, so this is when automatic sync first needs the battery exemption.
                if (prefs.syncIntervalMinutes > 0 && !BatteryRestriction.lifted(context)) BatteryRestriction.requestLift(context)
            },
        )
    }
}

private class SyncRun(val header: String, val details: List<String>, val result: String?)

/** Groups the log into runs, newest first: a run starts at an unindented line, its indented lines follow and end in OK or FAILED. */
private fun syncRuns(lines: List<String>): List<SyncRun> {
    val runs = mutableListOf<Pair<String, MutableList<String>>>()
    lines.forEach { line ->
        if (!line.startsWith(" ") || runs.isEmpty()) runs.add(line to mutableListOf()) else runs.last().second.add(line.trim())
    }
    return runs.map { (header, body) ->
        val result = body.lastOrNull()?.takeIf { it.startsWith("OK") || it.startsWith("FAILED") }
        SyncRun(header, if (result != null) body.dropLast(1) else body, result)
    }.asReversed()
}

@Composable
private fun syncLineColor(line: String) = when {
    line.startsWith("FAILED") || line.startsWith("Error") -> com.xnotes.core.model.Rgba(255, 92, 92, 255).toComposeColor()
    line.startsWith("Conflict") || line.startsWith("Skipped") -> WarningColor
    line.startsWith("OK") -> LocalPalette.current.accent.toComposeColor()
    else -> LocalPalette.current.textDim.toComposeColor()
}

/** The last sync runs as a page, one row per run that opens to show what it changed. */
@Composable
fun SyncLogPage(onBack: () -> Unit) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    var lines by remember { mutableStateOf<List<String>?>(null) }
    // Read off the main thread, and again whenever a running sync adds to it.
    LaunchedEffect(Unit) {
        var seen = -1L
        while (true) {
            val modified = SyncLog.lastModified(context)
            if (modified != seen) {
                seen = modified
                lines = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { SyncLog.read(context) }
            }
            kotlinx.coroutines.delay(1_000)
        }
    }
    val runs = remember(lines) { syncRuns(lines.orEmpty()) }
    var open by remember { mutableStateOf(emptySet<String>()) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(XnotesIcons.prev, stringResource(R.string.link_back), tint = palette.text.toComposeColor(), modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.sync_log), color = palette.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.weight(1f))
            if (runs.isNotEmpty()) TextButton(onClick = { SyncLog.clear(context); lines = emptyList() }) { Text(stringResource(R.string.clear), fontSize = 13.sp) }
        }
        if (lines != null && runs.isEmpty()) {
            Text(stringResource(R.string.sync_nothing_yet), color = palette.textDim.toComposeColor(), fontSize = 13.sp, modifier = Modifier.padding(16.dp))
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            items(runs) { run ->
                val expanded = run.header in open
                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = run.details.isNotEmpty()) { open = if (expanded) open - run.header else open + run.header }
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(run.header, color = palette.text.toComposeColor(), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(run.result ?: stringResource(R.string.sync_running), color = syncLineColor(run.result ?: ""), fontSize = 12.sp)
                        }
                        if (run.details.isNotEmpty()) {
                            Text("${run.details.size}", color = palette.textDim.toComposeColor(), fontSize = 12.sp)
                            Icon(
                                if (expanded) XnotesIcons.chevronUp else XnotesIcons.chevronDown, stringResource(if (expanded) R.string.sync_hide_details else R.string.sync_show_details),
                                tint = palette.textDim.toComposeColor(), modifier = Modifier.padding(start = 4.dp).size(18.dp),
                            )
                        }
                    }
                    if (expanded) {
                        Column(Modifier.padding(top = 6.dp)) {
                            run.details.forEach { line ->
                                Text(line, color = syncLineColor(line), fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderPicker(service: CloudService, onDismiss: () -> Unit, onSelected: (String, String) -> Unit) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val driveRoot = stringResource(R.string.sync_drive_root)
    var stack by remember { mutableStateOf(listOf(service.rootFolderId(context) to driveRoot)) }
    var folders by remember { mutableStateOf<List<CloudStorage.RemoteFolder>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var newFolder by remember { mutableStateOf("") }
    var reloadKey by remember { mutableStateOf(0) }
    val current = stack.last()
    val pathLabel = if (stack.size == 1) driveRoot else stack.drop(1).joinToString("/") { it.second }

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
        confirmButton = { TextButton(onClick = { onSelected(current.first, pathLabel) }) { Text(stringResource(R.string.sync_use_this_folder)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        containerColor = palette.menuBg.toComposeColor(),
        title = { Text(stringResource(R.string.sync_choose_folder, service.displayName), color = palette.text.toComposeColor(), fontSize = 16.sp) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (stack.size > 1) {
                        TextButton(onClick = { stack = stack.dropLast(1) }) { Text(stringResource(R.string.sync_folder_up), fontSize = 13.sp) }
                    }
                    Text(pathLabel, color = palette.textDim.toComposeColor(), fontSize = 12.sp)
                }
                Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(8.dp))) {
                    when {
                        loading -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(24.dp), color = palette.accent.toComposeColor(), strokeWidth = 2.dp)
                        error != null -> Text(error!!, color = palette.accent.toComposeColor(), fontSize = 12.sp, modifier = Modifier.align(Alignment.Center))
                        folders.isEmpty() -> Text(stringResource(R.string.sync_no_subfolders), color = palette.textDim.toComposeColor(), fontSize = 12.sp, modifier = Modifier.align(Alignment.Center))
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
                        placeholder = { Text(stringResource(R.string.sync_new_folder_name)) }, modifier = Modifier.weight(1f),
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
                    ) { Text(stringResource(R.string.create)) }
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
