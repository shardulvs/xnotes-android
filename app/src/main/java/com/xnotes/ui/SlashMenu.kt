package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.core.text.CalloutKind
import com.xnotes.core.text.EmojiShortcodes
import com.xnotes.core.text.SlashCommands
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

private val ROW_HEIGHT = 36.dp
private val MENU_WIDTH = 252.dp
private const val VISIBLE_ROWS = 6

/**
 * The "/" command menu, hanging under the caret. Like [FlowEditMenu] it is a plain
 * offset box and not a popup, so it never takes focus and the keyboard stays up
 * while the query is typed. The query lives in the paragraph itself; this only
 * lists what it currently matches, read fresh each recomposition, so there is no
 * menu state to keep in step with the text. Enter commits the first ready row.
 */
@Composable
fun SlashMenu(editor: Editor) {
    editor.flowSelTick // recompose as the caret moves and the query grows
    editor.contentVersion
    val query = editor.slashQuery() ?: return
    val entries = SlashCommands.candidates(query)
    if (entries.isEmpty()) return
    val anchor = editor.slashAnchor() ?: return
    val palette = LocalPalette.current
    val density = LocalDensity.current

    val menuHeight = with(density) { (ROW_HEIGHT * minOf(entries.size, VISIBLE_ROWS) + 8.dp).toPx() }
    val menuWidth = with(density) { MENU_WIDTH.toPx() }
    val gap = with(density) { 6.dp.toPx() }
    val viewport = editor.viewportSize()
    // Under the caret when it fits, over it when the keyboard has eaten the bottom.
    val below = anchor.bottom.toFloat() + gap
    val y = if (below + menuHeight <= viewport.y) below else (anchor.top.toFloat() - menuHeight - gap)
    val x = anchor.left.toFloat().coerceIn(0f, (viewport.x.toFloat() - menuWidth).coerceAtLeast(0f))
    val armed = editor.slashArmed(query, entries)
    val scroll = rememberScrollState()
    // Keep the arrowed-to row on screen; the menu shows six of them at a time.
    val armedIndex = entries.indexOfFirst { it === armed }
    val rowPx = with(density) { ROW_HEIGHT.toPx() }
    LaunchedEffect(armedIndex, entries.size) {
        if (armedIndex >= 0) {
            val top = armedIndex * rowPx
            val over = top + rowPx - (scroll.value + rowPx * VISIBLE_ROWS)
            if (top < scroll.value) scroll.animateScrollTo(top.toInt())
            else if (over > 0f) scroll.animateScrollTo((scroll.value + over).toInt())
        }
    }

    Column(
        modifier = Modifier
            .offset(with(density) { x.toDp() }, with(density) { y.coerceAtLeast(0f).toDp() })
            .width(MENU_WIDTH)
            .heightIn(max = ROW_HEIGHT * VISIBLE_ROWS + 8.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(palette.menuBg.toComposeColor())
            .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.medium)
            .padding(vertical = 4.dp)
            .verticalScroll(scroll),
    ) {
        for (entry in entries) {
            SlashRow(entry, ready = SlashCommands.ready(query, entry), highlighted = entry === armed) {
                editor.runSlash(query, entry)
            }
        }
    }
}

/**
 * One menu row. The keyword leads, in monospace, so what the row says is what
 * typing it looks like: with six headings on screen at once, "Heading 2" does not
 * tell you that h2 is the one to finish. Any argument follows as a dim placeholder,
 * which reads as a blank to fill rather than a value to copy. The plain name comes
 * after that, and the marker the entry duplicates trails.
 */
@Composable
private fun SlashRow(
    entry: SlashCommands.Entry,
    ready: Boolean,
    highlighted: Boolean,
    onPick: () -> Unit,
) {
    val palette = LocalPalette.current
    val fg = (if (!ready) palette.textDim else if (highlighted) palette.selectionForeground else palette.text).toComposeColor()
    val dim = if (highlighted) palette.selectionForeground.toComposeColor().copy(alpha = 0.72f) else palette.textDim.toComposeColor()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .background(if (highlighted) palette.selectionBackground.toComposeColor() else Color.Transparent)
            .clickable(enabled = ready, onClick = onPick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            entry.id,
            color = fg,
            fontSize = 13.sp,
            fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
        entry.param?.let {
            Spacer(Modifier.width(5.dp))
            Text(
                it,
                color = dim,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            slashLabel(entry),
            color = dim,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.weight(1f))
        entry.markdown?.let {
            Text(
                it,
                color = dim,
                fontSize = 11.sp,
            )
        }
    }
}

/**
 * The emoji a `:shortcode` being typed could be, hanging under the caret like the
 * slash menu and read off the text the same way. Enter puts in the highlighted one.
 */
@Composable
fun EmojiMenu(editor: Editor) {
    editor.flowSelTick
    editor.contentVersion
    val query = editor.emojiQuery() ?: return
    val matches = EmojiShortcodes.complete(query.text)
    if (matches.isEmpty()) return
    val anchor = editor.slashAnchor() ?: return
    val palette = LocalPalette.current
    val density = LocalDensity.current
    val menuHeight = with(density) { (ROW_HEIGHT * matches.size + 8.dp).toPx() }
    val menuWidth = with(density) { MENU_WIDTH.toPx() }
    val gap = with(density) { 6.dp.toPx() }
    val viewport = editor.viewportSize()
    val below = anchor.bottom.toFloat() + gap
    val y = if (below + menuHeight <= viewport.y) below else (anchor.top.toFloat() - menuHeight - gap)
    val x = anchor.left.toFloat().coerceIn(0f, (viewport.x.toFloat() - menuWidth).coerceAtLeast(0f))
    val armed = editor.emojiSelected.takeIf { it in matches.indices } ?: 0
    Column(
        modifier = Modifier
            .offset(with(density) { x.toDp() }, with(density) { y.coerceAtLeast(0f).toDp() })
            .width(MENU_WIDTH)
            .clip(MaterialTheme.shapes.medium)
            .background(palette.menuBg.toComposeColor())
            .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.medium)
            .padding(vertical = 4.dp),
    ) {
        for ((i, match) in matches.withIndex()) {
            val (name, emoji) = match
            val highlighted = i == armed
            val fg = (if (highlighted) palette.selectionForeground else palette.text).toComposeColor()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ROW_HEIGHT)
                    .background(if (highlighted) palette.selectionBackground.toComposeColor() else Color.Transparent)
                    .clickable { editor.pickEmoji(query, emoji) }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(emoji, fontSize = 18.sp)
                Spacer(Modifier.width(10.dp))
                Text(
                    ":$name:",
                    color = fg,
                    fontSize = 13.sp,
                    fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The entry's visible name. Keywords are ASCII; these are the translatable part. */
@Composable
private fun slashLabel(entry: SlashCommands.Entry): String = when (entry.kind) {
    SlashCommands.Kind.HEADING -> stringResource(R.string.heading_n, entry.level)
    SlashCommands.Kind.BODY -> stringResource(R.string.body_text)
    SlashCommands.Kind.BULLET -> stringResource(R.string.bullet_list)
    SlashCommands.Kind.ORDERED -> stringResource(R.string.ordered_list)
    SlashCommands.Kind.TODO -> stringResource(R.string.checkbox_item)
    SlashCommands.Kind.QUOTE -> stringResource(R.string.quote)
    SlashCommands.Kind.CALLOUT -> calloutLabel(CalloutKind.entries[entry.level])
    SlashCommands.Kind.CODE -> stringResource(R.string.code_block)
    SlashCommands.Kind.TABLE -> stringResource(R.string.insert_table)
    SlashCommands.Kind.PAGE_BREAK -> stringResource(R.string.page_break)
    SlashCommands.Kind.SIZE -> stringResource(R.string.size_pt)
    SlashCommands.Kind.COLOR -> stringResource(R.string.colour)
    SlashCommands.Kind.MATH -> stringResource(R.string.equation)
    SlashCommands.Kind.DATE -> stringResource(R.string.insert_date)
    SlashCommands.Kind.TIME -> stringResource(R.string.insert_time)
}

/** A callout's title, as its title row and the slash menu show it. */
@Composable
fun calloutLabel(kind: CalloutKind): String = stringResource(calloutLabelRes(kind))

fun calloutLabelRes(kind: CalloutKind): Int = when (kind) {
    CalloutKind.NOTE -> R.string.callout_note
    CalloutKind.TIP -> R.string.callout_tip
    CalloutKind.IMPORTANT -> R.string.callout_important
    CalloutKind.WARNING -> R.string.callout_warning
    CalloutKind.CAUTION -> R.string.callout_caution
}
