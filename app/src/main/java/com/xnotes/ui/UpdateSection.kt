package com.xnotes.ui

import com.xnotes.R
import androidx.compose.ui.res.stringResource
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.update.UpdateChecker
import com.xnotes.update.UpdateInstaller
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.launch

@Composable
fun UpdateSection(checkOnStartup: Boolean, onCheckOnStartup: (Boolean) -> Unit) {
    val palette = LocalPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val currentVersion = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" }.getOrDefault("")
    }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<UpdateChecker.Status?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableStateOf<Float?>(null) }

    fun open(url: String) {
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.pref_updates), color = palette.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(
            stringResource(R.string.update_intro),
            color = palette.textDim.toComposeColor(), fontSize = 12.sp,
        )
        CheckRow(stringResource(R.string.update_check_on_startup), checkOnStartup, onCheckOnStartup)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    error = null
                    status = null
                    busy = true
                    scope.launch {
                        UpdateChecker.check(currentVersion).fold(
                            { status = it },
                            { error = it.message ?: ctx.getString(R.string.update_check_failed) },
                        )
                        busy = false
                    }
                },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = palette.accent.toComposeColor(), contentColor = palette.bg.toComposeColor()),
            ) { Text(stringResource(R.string.update_check)) }
            if (busy) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = palette.accent.toComposeColor(), strokeWidth = 2.dp)
        }

        when (val s = status) {
            is UpdateChecker.Status.UpToDate ->
                Text(stringResource(R.string.update_up_to_date, s.version), color = palette.textDim.toComposeColor(), fontSize = 12.sp)
            is UpdateChecker.Status.Available -> {
                Text(
                    stringResource(R.string.update_available_version, s.version, currentVersion),
                    color = palette.accent.toComposeColor(), fontSize = 13.sp, fontWeight = FontWeight.Medium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    s.apkUrl?.let { url ->
                        TextButton(
                            onClick = {
                                error = null
                                downloadProgress = 0f
                                scope.launch {
                                    UpdateInstaller.downloadAndInstall(ctx, url) { downloadProgress = it }
                                        .onFailure { error = it.message ?: ctx.getString(R.string.update_download_failed) }
                                    downloadProgress = null
                                }
                            },
                            enabled = downloadProgress == null,
                        ) { Text(stringResource(R.string.update_download_apk), fontSize = 13.sp) }
                    }
                    TextButton(onClick = { open(s.releaseUrl) }) { Text(stringResource(R.string.update_view_release), fontSize = 13.sp) }
                }
                downloadProgress?.let {
                    Text(stringResource(R.string.update_downloading, (it * 100).toInt()), color = palette.textDim.toComposeColor(), fontSize = 12.sp)
                }
            }
            null -> {}
        }
        error?.let { Text(it, color = palette.accent.toComposeColor(), fontSize = 12.sp) }
    }
}
