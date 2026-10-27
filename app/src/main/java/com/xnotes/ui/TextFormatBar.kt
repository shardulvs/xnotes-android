package com.xnotes.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignJustify
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.FormatAlignRight
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.FontFace
import com.xnotes.core.text.ListKind
import com.xnotes.core.text.ParaAlign
import com.xnotes.core.text.Paragraph
import com.xnotes.core.tools.Tool
import com.xnotes.platform.FontCatalog
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlin.math.roundToInt

/**
 * The bottom text-format strip, shown while the inline text tool is armed (and no
 * legacy box edit is open). Sits as the last child of the editor column, so the
 * adjustResize window floats it directly above the soft keyboard. Controls, in
 * order: checkbox item, font colour, highlight, font face, size, bold/italic/
 * underline/strikethrough, heading level, ordered and unordered lists, code block
 * (tap toggles, long-press picks the language), table, alignment (cycles), indent,
 * outdent. Inside a table cell the heading, list, code, table and indent controls
 * disable. Centred when it fits, scrollable when it does not.
 */
@Composable
fun TextFormatBar(editor: Editor) {
    if (editor.tool != Tool.TEXT || editor.editingField != null) return
    val palette = LocalPalette.current
    editor.flowSelTick // recompose whenever the caret, selection or pending style moves
    editor.contentVersion
    val style = editor.flowCaretStyle()
    val para = editor.flowCaretParagraph()
    val inCell = para?.table != null
    var tableDialog by remember { mutableStateOf(false) }

    // The bar rides the bottom edge, so its tips open upward.
    CompositionLocalProvider(LocalTipSide provides TipSide.ABOVE) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(palette.panel.toComposeColor())
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarIcon(Icons.Outlined.CheckBox, stringResource(R.string.checkbox_item), active = para?.list == ListKind.CHECK, enabled = !inCell) {
                editor.flowToggleList(ListKind.CHECK)
            }
            BarDivider()
            FontColorButton(editor, style.color)
            HighlightButton(editor, style.highlight)
            BarDivider()
            FontFaceButton(editor, style.face)
            SizeStepper(editor.flowShownSizePt()) { editor.flowAdjustSize(it) }
            BarDivider()
            BarIcon(Icons.Filled.FormatBold, stringResource(R.string.bold), active = style.bold, shortcut = Keys.BOLD) { editor.flowToggleBold() }
            BarIcon(Icons.Filled.FormatItalic, stringResource(R.string.italic), active = style.italic, shortcut = Keys.ITALIC) { editor.flowToggleItalic() }
            BarIcon(Icons.Filled.FormatUnderlined, stringResource(R.string.underline), active = style.underline, shortcut = Keys.UNDERLINE) { editor.flowToggleUnderline() }
            BarIcon(Icons.Filled.FormatStrikethrough, stringResource(R.string.strikethrough), active = style.strike, shortcut = Keys.STRIKE) { editor.flowToggleStrike() }
            BarDivider()
            HeadingButton(editor, para?.headingLevel ?: 0, enabled = !inCell)
            BarIcon(Icons.Filled.FormatListNumbered, stringResource(R.string.ordered_list), active = para?.list == ListKind.ORDERED, enabled = !inCell) {
                editor.flowToggleList(ListKind.ORDERED)
            }
            BarIcon(Icons.AutoMirrored.Filled.FormatListBulleted, stringResource(R.string.bullet_list), active = para?.list == ListKind.BULLET, enabled = !inCell) {
                editor.flowToggleList(ListKind.BULLET)
            }
            CodeBlockButton(editor, para?.codeLang, enabled = !inCell)
            BarIcon(Icons.Filled.Functions, stringResource(R.string.equation), active = style.math, enabled = editor.flowEditingActive) {
                editor.flowToggleMath()
            }
            BarIcon(Icons.Outlined.TableChart, stringResource(R.string.insert_table), enabled = editor.flowEditingActive && !inCell) {
                tableDialog = true
            }
            BarDivider()
            BarIcon(alignIcon(para?.align ?: ParaAlign.LEFT), stringResource(R.string.alignment), active = para != null && para.align != ParaAlign.LEFT) {
                editor.flowCycleAlign()
            }
            BarIcon(Icons.AutoMirrored.Filled.FormatIndentIncrease, stringResource(R.string.indent), enabled = !inCell, shortcut = Keys.INDENT) { editor.flowIndent(1) }
            BarIcon(Icons.AutoMirrored.Filled.FormatIndentDecrease, stringResource(R.string.outdent), enabled = (para?.indent ?: 0) > 0, shortcut = Keys.OUTDENT) {
                editor.flowIndent(-1)
            }
        }
    }
    if (tableDialog) TableDialog(editor, null) { tableDialog = false }
}

private fun alignIcon(align: ParaAlign): ImageVector = when (align) {
    ParaAlign.LEFT -> Icons.Filled.FormatAlignLeft
    ParaAlign.CENTER -> Icons.Filled.FormatAlignCenter
    ParaAlign.RIGHT -> Icons.Filled.FormatAlignRight
    ParaAlign.JUSTIFY -> Icons.Filled.FormatAlignJustify
}

/** Short form shown on the active code toggle; "" is plain/unhighlighted code. */
private fun shortLangLabel(lang: String): String = when (lang) {
    "" -> "txt"
    "bash" -> "sh"
    "javascript" -> "js"
    "kotlin" -> "kt"
    "python" -> "py"
    "rust" -> "rs"
    "typescript" -> "ts"
    else -> lang.take(4)
}

/**
 * Heading level: a bare H, gaining its number while one is on, so the button reads
 * the same either way. Material has no H glyph (only Title, a T), and a letter that
 * turns into H2 beats an icon that does. A tap opens the level menu rather than
 * toggling, since no one level is the obvious default, and each entry trails its
 * markdown marker so the bar also teaches the shortcut it duplicates.
 */
@Composable
private fun HeadingButton(editor: Editor, level: Int, enabled: Boolean) {
    val palette = LocalPalette.current
    var menuOpen by remember { mutableStateOf(false) }
    // The letter carries no meaning for a screen reader, so name the control instead.
    val label = stringResource(R.string.heading)
    Box {
        Tooltip(label) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(enabled = enabled) { menuOpen = true }
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                val tint = when {
                    !enabled -> palette.disabled
                    level > 0 -> palette.accent
                    else -> palette.textDim
                }
                Text(
                    if (level > 0) "H$level" else "H",
                    color = tint.toComposeColor(),
                    fontSize = if (level > 0) 12.sp else 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            for (n in 0..Paragraph.MAX_HEADING) {
                ChoiceMenuItem(
                    n == level,
                    text = { fg ->
                        Text(
                            if (n == 0) stringResource(R.string.body_text) else stringResource(R.string.heading_n, n),
                            color = fg,
                            fontSize = 14.sp,
                            fontWeight = if (n > 0) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    trailingIcon = if (n > 0 && editor.markdownInput) {
                        { fg ->
                            Text(
                                "#".repeat(n),
                                color = fg.copy(alpha = 0.72f),
                                fontSize = 12.sp,
                            )
                        }
                    } else {
                        null
                    },
                    onClick = {
                        editor.flowSetHeading(n)
                        menuOpen = false
                    },
                )
            }
        }
    }
}

/** Code block: tap toggles it like a list, long-press picks the block's language.
 *  While on, the icon gives way to the active language's short form. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CodeBlockButton(editor: Editor, lang: String?, enabled: Boolean) {
    val palette = LocalPalette.current
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Tooltip(stringResource(R.string.code_block), hint = stringResource(R.string.tip_hold_for_language)) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .combinedClickable(
                        enabled = enabled,
                        onClick = { editor.flowToggleCode() },
                        onLongClick = { menuOpen = true },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (lang != null) {
                    Text(
                        shortLangLabel(lang),
                        color = palette.accent.toComposeColor(),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                } else {
                    Icon(
                        Icons.Filled.Code,
                        contentDescription = stringResource(R.string.code_block),
                        tint = if (enabled) palette.textDim.toComposeColor() else palette.disabled.toComposeColor(),
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            val current = when {
                lang == null -> editor.lastCodeLanguage()
                lang.isEmpty() -> "plain"
                else -> lang
            }
            for (token in editor.codeLanguageChoices()) {
                ChoiceMenuItem(
                    token == current,
                    text = { fg ->
                        Text(
                            if (token == "plain") stringResource(R.string.code_plain) else token,
                            color = fg,
                            fontSize = 14.sp,
                        )
                    },
                    onClick = {
                        editor.flowSetCodeLanguage(token)
                        menuOpen = false
                    },
                )
            }
        }
    }
}

@Composable
private fun BarIcon(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean = false,
    enabled: Boolean = true,
    shortcut: String? = null,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    val tint = when {
        !enabled -> palette.disabled.toComposeColor()
        active -> palette.accent.toComposeColor()
        else -> palette.textDim.toComposeColor()
    }
    Tooltip(contentDescription, shortcut = shortcut) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(44.dp)) {
            Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun BarDivider() {
    Box(
        Modifier
            .width(1.dp)
            .fillMaxHeight()
            .padding(vertical = 12.dp)
            .background(LocalPalette.current.border.toComposeColor()),
    )
}

/** Font colour: an "A" over a bar in the caret's colour, opening the shared picker. */
@Composable
private fun FontColorButton(editor: Editor, current: Rgba?) {
    val palette = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    val shown = current ?: editor.flowDefaultColor()
    Box {
        Tooltip(stringResource(R.string.text_colour)) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).clickable { open = true },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "A",
                        color = palette.text.toComposeColor(),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Box(
                        Modifier
                            .width(16.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(1.5.dp))
                            .background(shown.toComposeColor()),
                    )
                }
            }
        }
        if (open) {
            ColorPickerPopup(
                initial = shown,
                recents = editor.recentColors,
                onDismiss = { open = false },
                onPick = { editor.flowSetCharColor(it); open = false },
            )
        }
    }
}

/** Highlight: an "A" on a swatch; tap clears an active highlight, or opens the picker. */
@Composable
private fun HighlightButton(editor: Editor, current: Rgba?) {
    val palette = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    Box {
        Tooltip(stringResource(if (current != null) R.string.remove_highlight else R.string.markup_highlight)) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable { if (current != null) editor.flowSetCharHighlight(null) else open = true },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background((current ?: palette.panel).toComposeColor())
                        .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.small),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "A",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (current != null) swatchInk(current) else palette.textDim.toComposeColor(),
                    )
                }
            }
        }
        if (open) {
            ColorPickerPopup(
                initial = Rgba(255, 235, 59),
                recents = editor.recentColors,
                onDismiss = { open = false },
                onPick = { editor.flowSetCharHighlight(it); open = false },
            )
        }
    }
}

/** Black or white, whichever reads against the swatch colour. */
private fun swatchInk(c: Rgba): Color =
    if (0.299 * c.r + 0.587 * c.g + 0.114 * c.b > 150) Color.Black else Color.White

/** Font face: applies to the selection, or arms for what is typed next (like bold).
 *  Labelled with the effective font's name in that font. */
@Composable
private fun FontFaceButton(editor: Editor, current: FontFace?) {
    val palette = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    val shown = current ?: editor.flowDefaultFace()
    Box {
        Tooltip(stringResource(R.string.font)) {
            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .clip(MaterialTheme.shapes.small)
                    .clickable { open = true }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    fontLabel(shown),
                    color = palette.text.toComposeColor(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 120.dp),
                    style = TextStyle(fontFamily = shown.toComposeFamily(), fontSize = 14.sp),
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            FontMenuItems(current = current, withDefault = true) {
                editor.flowSetCharFace(it)
                open = false
            }
        }
    }
}

@Composable
private fun SizeStepper(size: Double, onDelta: (Double) -> Unit) {
    val palette = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Tooltip(stringResource(R.string.smaller)) {
            Box(Modifier.size(36.dp).clip(CircleShape).clickable { onDelta(-1.0) }, contentAlignment = Alignment.Center) {
                Icon(XnotesIcons.minus, stringResource(R.string.smaller), tint = palette.textDim.toComposeColor(), modifier = Modifier.size(16.dp))
            }
        }
        Text(
            size.roundToInt().toString(),
            color = palette.text.toComposeColor(),
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(26.dp),
        )
        Tooltip(stringResource(R.string.larger)) {
            Box(Modifier.size(36.dp).clip(CircleShape).clickable { onDelta(1.0) }, contentAlignment = Alignment.Center) {
                Icon(XnotesIcons.plus, stringResource(R.string.larger), tint = palette.textDim.toComposeColor(), modifier = Modifier.size(16.dp))
            }
        }
    }
}
