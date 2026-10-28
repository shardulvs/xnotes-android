package com.xnotes.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The xtemplate repo's AI.md, bundled byte for byte: the brief, the spec and examples in one text. */
private const val GUIDE = "templates/AI.md"

private val PAGE_PROMPTS = listOf(R.string.ai_prompt_page_dots, R.string.ai_prompt_page_habits, R.string.ai_prompt_page_lab, R.string.ai_prompt_page_reading)
private val COVER_PROMPTS = listOf(R.string.ai_prompt_cover_photo, R.string.ai_prompt_cover_painting, R.string.ai_prompt_cover_pattern, R.string.ai_prompt_cover_leather)

/**
 * New note's Generate using AI tab, in three steps: copy the guide for an AI assistant, ask it for
 * a template (with prompts to try), and import the `.xtemplate` file it made. [coversOnly] keeps
 * to covers, for the cover picker of an open note; [wide] sets the example prompts in two columns.
 */
@Composable
internal fun AiTemplateTab(coversOnly: Boolean, wide: Boolean, onImport: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val text = palette.text.toComposeColor()
    val dim = palette.textDim.toComposeColor()
    val accent = palette.accent.toComposeColor()
    val card = palette.menuBg.toComposeColor()
    val guide by produceState<ByteArray?>(null) {
        value = withContext(Dispatchers.IO) { runCatching { context.assets.open(GUIDE).use { it.readBytes() } }.getOrNull() }
    }
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    // What went to the clipboard last, so its button says so for a moment.
    var copied by remember { mutableStateOf<Any?>(null) }
    LaunchedEffect(copied) {
        if (copied != null) {
            delay(2000)
            copied = null
        }
    }
    fun copy(what: Any, s: String) {
        clipboard?.setPrimaryClip(ClipData.newPlainText("xtemplate", s))
        copied = what
    }

    @Composable
    fun Step(n: Int, title: String, content: @Composable () -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(26.dp).background(palette.selectionBackground.toComposeColor(), CircleShape), contentAlignment = Alignment.Center) {
                    Text(n.toString(), color = palette.selectionForeground.toComposeColor(), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                Text(title, color = text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
            // The step's body lines up under its title, past the number.
            Column(Modifier.padding(start = 38.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
        }
    }

    @Composable
    fun Prompts(ids: List<Int>) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in ids.chunked(if (wide) 2 else 1)) {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (id in row) Prompt(id, copied == id, Modifier.weight(1f).fillMaxHeight()) { copy(id, it) }
                    if (row.size < (if (wide) 2 else 1)) Spacer(Modifier.weight(1f))
                }
            }
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(28.dp)) {
        Text(stringResource(R.string.ai_intro), color = dim, fontSize = 14.sp, lineHeight = 20.sp)

        Step(1, stringResource(R.string.ai_step_copy)) {
            Row(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(card).padding(start = 16.dp, top = 14.dp, end = 10.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(XnotesIcons.file, null, tint = accent, modifier = Modifier.size(28.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.ai_guide_name), color = text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        guide?.let { Text(stringResource(R.string.ai_guide_size, (it.size + 512) / 1024), color = dim, fontSize = 12.sp) }
                    }
                    Text(stringResource(R.string.ai_guide_about), color = dim, fontSize = 12.sp, lineHeight = 17.sp)
                }
                val shareLabel = stringResource(R.string.share)
                IconButton(
                    onClick = {
                        val g = guide ?: return@IconButton
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, String(g, Charsets.UTF_8))
                        runCatching { context.startActivity(Intent.createChooser(send, null)) }
                    },
                    enabled = guide != null,
                ) { Icon(XnotesIcons.share, shareLabel, tint = dim) }
                val done = copied == GUIDE
                FilledTonalButton(
                    onClick = { guide?.let { copy(GUIDE, String(it, Charsets.UTF_8)) } },
                    enabled = guide != null,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = palette.selectionBackground.toComposeColor(),
                        contentColor = palette.selectionForeground.toComposeColor(),
                    ),
                ) {
                    Icon(if (done) XnotesIcons.check else XnotesIcons.copy, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(if (done) R.string.copied else R.string.copy), fontWeight = FontWeight.Medium)
                }
            }
        }

        Step(2, stringResource(R.string.ai_step_ask)) {
            Text(stringResource(R.string.ai_step_ask_about), color = dim, fontSize = 13.sp, lineHeight = 19.sp)
            // A heading of their own, so the prompts read as requests to borrow, not text every request needs.
            Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.ai_examples), color = text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(stringResource(R.string.ai_examples_about), color = dim, fontSize = 12.sp, lineHeight = 17.sp)
            }
            if (!coversOnly) {
                Text(stringResource(R.string.ai_page_templates), color = dim, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
                Prompts(PAGE_PROMPTS)
            }
            Text(stringResource(R.string.ai_cover_templates), color = dim, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
            Prompts(COVER_PROMPTS)
        }

        Step(3, stringResource(R.string.ai_step_import)) {
            Action(XnotesIcons.importDoc, stringResource(R.string.import_template_file), stringResource(R.string.import_template_about), onImport)
        }
    }
}

/** An example request: its text, and a copy mark that turns into a tick once it is on the clipboard. */
@Composable
private fun Prompt(@StringRes id: Int, copied: Boolean, modifier: Modifier, onCopy: (String) -> Unit) {
    val palette = LocalPalette.current
    val prompt = stringResource(id)
    Row(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .background(palette.menuBg.toComposeColor())
            .clickable(onClickLabel = stringResource(R.string.copy), role = Role.Button) { onCopy(prompt) }
            .padding(start = 14.dp, top = 12.dp, end = 10.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(prompt, color = palette.text.toComposeColor(), fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f))
        Icon(
            if (copied) XnotesIcons.check else XnotesIcons.copy, null,
            tint = (if (copied) palette.accent else palette.textDim).toComposeColor(), modifier = Modifier.size(18.dp),
        )
    }
}

/** A step's action: its icon, what it does, and a line about it. */
@Composable
private fun Action(icon: ImageVector, title: String, about: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, tint = palette.accent.toComposeColor(), modifier = Modifier.size(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = palette.text.toComposeColor(), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(about, color = palette.textDim.toComposeColor(), fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}
