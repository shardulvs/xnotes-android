package com.xnotes.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

private const val REPO_URL = "https://github.com/shardulvs/xnotes-android"
private const val SPONSOR_URL = "https://github.com/sponsors/shardulvs"
private const val ISSUES_URL = "https://github.com/shardulvs/xnotes-android/issues/new"
private const val FDROID_URL = "https://f-droid.org/en/packages/com.xnotes"
private const val LICENSE_URL = "https://github.com/shardulvs/xnotes-android/blob/master/LICENSE"

/**
 * The About backstage pane: app identity + the one place that points users back at the
 * project. Since the app ships with no accounts or telemetry, these links are the only
 * feedback channel. The bug/feature links open GitHub's new-issue form with the body
 * pre-filled (app version + device + OS for bug reports). Back from here returns to Home.
 */
@Composable
fun AboutPane() {
    val palette = LocalPalette.current
    val ctx = LocalContext.current

    val appIcon = remember {
        runCatching { ctx.packageManager.getApplicationIcon(ctx.packageName).toBitmap(144, 144).asImageBitmap() }.getOrNull()
    }
    val version = remember {
        runCatching {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode else pi.versionCode.toLong()
            "${pi.versionName} ($code)"
        }.getOrDefault("")
    }

    fun open(url: String) {
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(top = 16.dp, bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (appIcon != null) {
                Image(appIcon, "xnotes", modifier = Modifier.size(72.dp))
                Spacer(Modifier.height(16.dp))
            }
            Text("xnotes", color = palette.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = 24.sp)
            Spacer(Modifier.height(5.dp))
            Text(
                "A handwriting notes and sketching app for Android",
                color = palette.textDim.toComposeColor(), fontSize = 13.sp, textAlign = TextAlign.Center,
            )
            if (version.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                // Tap to copy, so a version string is easy to paste into a report.
                Row(
                    Modifier.clickable { copyVersion(ctx, version) }.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Version $version", color = palette.textDim.toComposeColor(), fontSize = 12.sp)
                    Spacer(Modifier.width(6.dp))
                    Icon(XnotesIcons.copy, "Copy version", tint = palette.textDim.toComposeColor(), modifier = Modifier.size(12.dp))
                }
            }

            Spacer(Modifier.height(28.dp))
            Text("Help make xnotes better", color = palette.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(14.dp))

            // Three rectangular buttons, side by side; each fills with the accent while pressed.
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AboutButton(XnotesIcons.bug, "Report a bug") { open(bugReportUrl(version)) }
                AboutButton(XnotesIcons.idea, "Request a feature") { open(featureRequestUrl()) }
                AboutButton(XnotesIcons.heart, "Sponsor") { open(SPONSOR_URL) }
            }

            Spacer(Modifier.height(26.dp))
            // Quiet, single-line growth nudge (a link, never a popup).
            Row(
                Modifier.clickable { open(REPO_URL) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Enjoying xnotes? ", color = palette.textDim.toComposeColor(), fontSize = 12.sp)
                Text("Star it on GitHub", color = palette.accent.toComposeColor(), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }

            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("MIT License", color = palette.textDim.toComposeColor(), fontSize = 11.sp, modifier = Modifier.clickable { open(LICENSE_URL) })
                Text("   ·   ", color = palette.textDim.toComposeColor(), fontSize = 11.sp)
                Text("F-Droid", color = palette.textDim.toComposeColor(), fontSize = 11.sp, modifier = Modifier.clickable { open(FDROID_URL) })
            }
        }
    }
}

/** A rectangular link button: line glyph above a label. Inverts to the accent while pressed. */
@Composable
private fun RowScope.AboutButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val accent = palette.accent.toComposeColor()
    val onAccent = palette.bg.toComposeColor()
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .background(if (pressed) accent else Color.Transparent)
            .border(1.dp, if (pressed) accent else palette.border.toComposeColor(), RectangleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = if (pressed) onAccent else accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(8.dp))
        Text(
            label,
            color = if (pressed) onAccent else palette.text.toComposeColor(),
            fontSize = 12.sp, lineHeight = 14.sp, textAlign = TextAlign.Center,
        )
    }
}

private fun copyVersion(ctx: Context, version: String) {
    runCatching {
        val clip = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText("xnotes version", "xnotes $version"))
        Toast.makeText(ctx, "Version copied", Toast.LENGTH_SHORT).show()
    }
}

/** GitHub new-issue link with a bug template and the reporter's version/device/OS pre-filled. */
private fun bugReportUrl(version: String): String {
    val body = """
        **What happened?**


        **Steps to reproduce**
        1.
        2.

        **What did you expect instead?**


        ---
        xnotes ${version.ifEmpty { "(unknown)" }}
        ${Build.MANUFACTURER} ${Build.MODEL}
        Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
    """.trimIndent()
    return "$ISSUES_URL?title=${Uri.encode("[Bug] ")}&body=${Uri.encode(body)}"
}

/** GitHub new-issue link with a lightweight feature template. */
private fun featureRequestUrl(): String {
    val body = """
        **What would you like xnotes to do?**

    """.trimIndent()
    return "$ISSUES_URL?title=${Uri.encode("[Feature] ")}&body=${Uri.encode(body)}"
}
