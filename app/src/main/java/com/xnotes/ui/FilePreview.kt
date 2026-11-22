package com.xnotes.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xnotes.R
import com.xnotes.core.model.Rgba
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/** What a preview can do with the file it shows; [openBeside] is null when there's no other note to pair it with. */
internal class PreviewActions(
    val open: () -> Unit,
    val openBeside: (() -> Unit)?,
    val share: () -> Unit,
    val exportPdf: () -> Unit,
    val color: (Rgba?) -> Unit,
)

/**
 * A file up close without opening it: its first page large, what it is, when it was made and changed, where
 * it lives, and a strip of its first pages. The Columns layout shows it beside the columns; with "Tapping a
 * file shows a preview" on, a tap shows it in a dialog.
 */
@Composable
internal fun FilePreview(b: ExplorerBody, e: BrowseEntry, where: String?, actions: PreviewActions, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val kind = b.kind(e)
    val pages = b.pages(e)
    val landscape = kind == EntryKind.CANVAS
    Column(modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stacked = maxWidth < 440.dp
            // The first page in its own shape, within an upright or landscape frame to match.
            val img = rememberThumb(b.editor, e, whole = true)
            val ratio = pageRatio(img, if (landscape) CANVAS_RATIO else A4_RATIO)
            val (pageW, pageH) = pageFit(ratio, if (ratio > 1f) 293.dp else 208.dp, if (ratio > 1f) 208.dp else 293.dp)
            val details: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(b.label(e), color = palette.text.toComposeColor(), fontSize = 20.sp, fontWeight = FontWeight.Medium, lineHeight = 25.sp)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(kindIcon(kind), null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(15.dp))
                            val count = if (pages > 0 && !landscape) " · " + pluralStringResource(R.plurals.pages_count, pages, pages) else ""
                            Text(kindLabel(kind) + count, color = palette.textDim.toComposeColor(), fontSize = 13.sp)
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DetailRow(stringResource(R.string.modified), formatFull(b.words, e.modified, b.clock24, b.zone))
                        DetailRow(stringResource(R.string.created), formatFull(b.words, e.created, b.clock24, b.zone))
                        DetailRow(stringResource(R.string.sort_size), formatSize(e.size))
                        if (where != null) DetailRow(stringResource(R.string.where_label), where)
                    }
                    Column(Modifier.widthIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PreviewButton(XnotesIcons.edit, stringResource(R.string.open), filled = true, onClick = actions.open)
                        PreviewButton(XnotesIcons.split, stringResource(R.string.open_side_by_side), filled = false, enabled = actions.openBeside != null) { actions.openBeside?.invoke() }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ExplorerIcon(XnotesIcons.share, stringResource(R.string.share), palette.accent.toComposeColor(), onClick = actions.share)
                        ExplorerIcon(XnotesIcons.pdfFile, stringResource(R.string.export_pdf), palette.accent.toComposeColor(), onClick = actions.exportPdf)
                        var colors by remember { mutableStateOf(false) }
                        Box {
                            ExplorerIcon(XnotesIcons.palette, stringResource(R.string.colour_code), palette.accent.toComposeColor()) { colors = true }
                            DropdownMenu(expanded = colors, onDismissRequest = { colors = false }) {
                                ColorCodeMenuContent { c -> colors = false; actions.color(c) }
                            }
                        }
                    }
                }
            }
            val page: @Composable () -> Unit = {
                val shape = RoundedCornerShape(3.dp)
                ThumbImage(img, whole = true, Modifier.size(pageW, pageH).clip(shape).border(1.dp, palette.paperBorder.toComposeColor(), shape))
            }
            if (stacked) {
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) { page(); details() }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) { page(); Box(Modifier.weight(1f)) { details() } }
            }
        }
        if (!landscape && pages > 1) {
            val stripPx = with(LocalDensity.current) { 87.dp.roundToPx() }
            val strip by key(b.editor.thumbnailVersion) {
                produceState<List<ImageBitmap>?>(null, e.documentUri, e.modified) {
                    value = b.editor.pageStrip(e.documentUri, e.name, PREVIEW_PAGES, stripPx)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.pages), color = palette.textDim.toComposeColor(), fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val shown = strip
                    if (shown == null) {
                        items(minOf(pages, PREVIEW_PAGES)) { i -> StripPage(null, i) }
                    } else {
                        itemsIndexed(shown) { i, img -> StripPage(img, i) }
                    }
                }
            }
        }
    }
}

private const val PREVIEW_PAGES = 12

@Composable
private fun StripPage(img: ImageBitmap?, index: Int) {
    val palette = LocalPalette.current
    val first = index == 0
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val shape = RoundedCornerShape(2.dp)
        Box(
            Modifier.height(87.dp).widthIn(min = 40.dp, max = 124.dp).clip(shape)
                .background(palette.paper.toComposeColor())
                .border(1.dp, if (first) palette.accent.toComposeColor() else palette.paperBorder.toComposeColor(), shape),
        ) {
            if (img != null) Image(img, null, contentScale = ContentScale.Fit, modifier = Modifier.height(87.dp))
            else Spacer(Modifier.size(62.dp, 87.dp))
        }
        Text("${index + 1}", color = (if (first) palette.accent else palette.textDim).toComposeColor(), fontSize = 12.sp)
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    val palette = LocalPalette.current
    Row {
        Text(label, color = palette.textDim.toComposeColor(), fontSize = 13.5.sp, modifier = Modifier.width(76.dp))
        Text(value, color = palette.text.toComposeColor(), fontSize = 13.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PreviewButton(icon: ImageVector, label: String, filled: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val shape = MaterialTheme.shapes.medium
    val fg = when {
        !enabled -> palette.textDim.toComposeColor().copy(alpha = 0.5f)
        filled -> palette.onAccent.toComposeColor()
        else -> palette.text.toComposeColor()
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(shape)
            .background(if (filled) palette.accent.toComposeColor() else Color.Transparent)
            .then(if (filled) Modifier else Modifier.border(1.dp, palette.border.toComposeColor(), shape))
            .clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = if (filled || !enabled) fg else palette.accent.toComposeColor(), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = fg, fontSize = 14.5.sp, fontWeight = if (filled) FontWeight.Medium else FontWeight.Normal)
    }
}

/** [FilePreview] in a dialog, for "Tapping a file shows a preview". */
@Composable
internal fun FilePreviewDialog(b: ExplorerBody, e: BrowseEntry, where: String?, actions: PreviewActions, onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val shape = MaterialTheme.shapes.large
        Box(
            Modifier
                .padding(24.dp)
                .widthIn(max = 720.dp)
                .heightIn(max = 760.dp)
                .clip(shape)
                .background(palette.menuBg.toComposeColor())
                .border(1.dp, palette.border.toComposeColor(), shape),
        ) {
            FilePreview(b, e, where, actions)
            Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                ExplorerIcon(XnotesIcons.close, stringResource(R.string.close_preview), palette.textDim.toComposeColor(), onClick = onDismiss)
            }
        }
    }
}
