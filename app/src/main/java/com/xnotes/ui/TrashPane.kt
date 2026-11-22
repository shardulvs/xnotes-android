package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.core.util.DocumentKind
import com.xnotes.settings.Preferences
import com.xnotes.settings.ThumbShape
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

/**
 * Trash: what was deleted, where it came from, when, and how long it has left before it is permanently deleted. Items
 * go back to their folder on Restore; Empty trash and Delete permanently remove them.
 */
@Composable
internal fun TrashPane(editor: Editor, sidebarOpen: Boolean, onShowSidebar: () -> Unit, onOpenPreferences: () -> Unit) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val root = editor.browseRoot
    val prefs = remember(editor.prefsVersion) { editor.preferences }
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var confirmEmpty by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<TrashItem?>(null) }
    var busy by remember { mutableStateOf(false) }
    val items by produceState<List<TrashItem>?>(null, root, refresh) {
        value = if (root == null) emptyList() else withContext(Dispatchers.IO) {
            editor.purgeExpiredTrash(root, prefs.trashDays)
            editor.listTrash(root)
        }
    }
    val rootName by produceState(root?.let { editor.cachedRootName(it) }, root) {
        value = root?.let { r -> withContext(Dispatchers.IO) { editor.browseRootName(r) } }
    }
    val now = remember(items) { System.currentTimeMillis() }
    val clock24 = android.text.format.DateFormat.is24HourFormat(context)
    val zone = ZoneId.systemDefault()
    val shown = items.orEmpty().filter { query.isBlank() || it.entry.name.contains(query.trim(), ignoreCase = true) }

    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val density = LocalDensity.current
            var emptyWidth by remember { mutableStateOf(0.dp) }
            // Open, the search covers the title: it gets what the menu button and Empty trash leave, up to 300dp.
            val searchRoom = maxWidth - emptyWidth - 12.dp - (if (sidebarOpen) 0.dp else 48.dp)
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!sidebarOpen) {
                    Tooltip(stringResource(R.string.show_sidebar)) {
                        IconButton(onClick = onShowSidebar) {
                            Icon(XnotesIcons.menu, stringResource(R.string.show_sidebar), tint = palette.text.toComposeColor(), modifier = Modifier.size(24.dp))
                        }
                    }
                }
                Text(stringResource(R.string.trash), color = palette.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 1, modifier = Modifier.weight(1f))
                ExplorerSearchField(query, { query = it }, expandedWidth = searchRoom.coerceIn(40.dp, 300.dp))
                Spacer(Modifier.width(12.dp))
                val shape = MaterialTheme.shapes.medium
                val enabled = !busy && !items.isNullOrEmpty()
                Row(
                    Modifier.onSizeChanged { emptyWidth = with(density) { it.width.toDp() } }
                        .height(40.dp).clip(shape).border(1.dp, palette.border.toComposeColor(), shape)
                        .clickable(enabled = enabled) { confirmEmpty = true }.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val tint = if (enabled) palette.accent.toComposeColor() else palette.textDim.toComposeColor().copy(alpha = 0.5f)
                    Icon(XnotesIcons.trash, null, tint = tint, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.empty_trash), color = if (enabled) palette.text.toComposeColor() else tint, fontSize = 14.sp, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        val bannerShape = MaterialTheme.shapes.medium
        Row(
            Modifier.fillMaxWidth().clip(bannerShape).background(palette.surface.toComposeColor())
                .border(1.dp, palette.border.toComposeColor(), bannerShape).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(XnotesIcons.clock, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(18.dp))
            Text(
                when (prefs.trashDays) {
                    0 -> stringResource(R.string.trash_off_hint)
                    Preferences.TRASH_FOREVER -> stringResource(R.string.trash_forever_hint)
                    else -> pluralStringResource(R.plurals.trash_deleted_after, prefs.trashDays, prefs.trashDays)
                },
                color = palette.text.toComposeColor(), fontSize = 13.5.sp, modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.change), color = palette.accent.toComposeColor(), fontSize = 13.5.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(MaterialTheme.shapes.extraSmall).clickable(onClick = onOpenPreferences).padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val wide = maxWidth >= 720.dp
            val list = items
            when {
                root == null -> EmptyPane(stringResource(R.string.trash_no_root))
                list == null -> EmptyPane(stringResource(R.string.loading))
                list.isEmpty() -> EmptyPane(stringResource(R.string.trash_empty), EmptyArt.TRASH)
                shown.isEmpty() -> EmptyPane(stringResource(R.string.trash_no_match, query.trim()))
                else -> Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().height(36.dp).padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        TrashHead(stringResource(R.string.sort_name), Modifier.weight(1f).padding(start = 54.dp))
                        if (wide) {
                            TrashHead(stringResource(R.string.trash_was_in), Modifier.width(170.dp))
                            TrashHead(stringResource(R.string.trash_deleted), Modifier.width(150.dp), on = true)
                            TrashHead(stringResource(R.string.trash_removed_in), Modifier.width(110.dp))
                        }
                        Spacer(Modifier.width(150.dp))
                    }
                    Hairline()
                    // The keyboard slides over the rows; its height joins the end padding so the last ones scroll clear.
                    val endPad = WindowInsets.ime.exclude(WindowInsets.systemBars).add(WindowInsets(bottom = 16.dp)).asPaddingValues()
                    LazyColumn(Modifier.weight(1f), contentPadding = endPad) {
                        items(shown, key = { it.wrapperDocId }) { item ->
                            TrashRow(
                                editor, item, wide, rootName ?: stringResource(R.string.the_top_folder), now, clock24, zone, prefs.trashDays,
                                onRestore = {
                                    busy = true
                                    scope.launch {
                                        val ok = withContext(Dispatchers.IO) { editor.restoreTrash(root, item) }
                                        busy = false
                                        refresh++
                                        editor.say(if (ok) context.getString(R.string.restored_item, label(item)) else context.getString(R.string.err_restore_item, label(item)))
                                    }
                                },
                                onDelete = { confirmDelete = item },
                            )
                            Hairline()
                        }
                    }
                    Text(
                        stringResource(R.string.restore_hint, rootName ?: stringResource(R.string.the_top_folder)),
                        color = palette.textDim.toComposeColor(), fontSize = 12.5.sp, modifier = Modifier.padding(start = 8.dp, top = 10.dp),
                    )
                }
            }
        }
    }

    if (confirmEmpty && root != null) {
        SheetDialog(
            title = stringResource(R.string.empty_trash_confirm),
            onDismiss = { confirmEmpty = false },
            text = stringResource(R.string.empty_trash_body),
            foot = {
                ConfirmButtons(stringResource(R.string.empty_trash), { confirmEmpty = false }, ButtonKind.DESTROY) {
                    confirmEmpty = false
                    busy = true
                    scope.launch {
                        withContext(Dispatchers.IO) { editor.emptyTrash(root) }
                        busy = false
                        refresh++
                    }
                }
            },
        )
    }
    confirmDelete?.let { item ->
        if (root == null) return@let
        SheetDialog(
            title = stringResource(R.string.delete_permanently_confirm),
            onDismiss = { confirmDelete = null },
            text = stringResource(R.string.delete_permanently_body, label(item)),
            foot = {
                ConfirmButtons(stringResource(R.string.delete), { confirmDelete = null }, ButtonKind.DESTROY) {
                    confirmDelete = null
                    scope.launch {
                        withContext(Dispatchers.IO) { editor.deleteTrash(root, item) }
                        refresh++
                    }
                }
            },
        )
    }
}

private fun label(item: TrashItem): String = if (item.entry.isDir) item.entry.name else DocumentKind.stripSuffix(item.entry.name)

@Composable
private fun TrashHead(text: String, modifier: Modifier, on: Boolean = false) {
    val palette = LocalPalette.current
    Text(text, color = (if (on) palette.accent else palette.textDim).toComposeColor(), fontSize = 12.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = modifier)
}

@Composable
private fun TrashRow(
    editor: Editor,
    item: TrashItem,
    wide: Boolean,
    rootName: String,
    now: Long,
    clock24: Boolean,
    zone: ZoneId,
    days: Int,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    val palette = LocalPalette.current
    val words = rememberExplorerWords()
    val e = item.entry
    val kind = entryKind(e)
    val dim = palette.textDim.toComposeColor()
    val wasIn = item.path.lastOrNull() ?: rootName
    val deleted = formatWhen(words, item.deleted, now, "day", true, clock24, zone)
    val left = if (days > 0) (days - ((now - item.deleted) / 86_400_000L).toInt()).coerceAtLeast(0) else null
    Row(Modifier.fillMaxWidth().height(60.dp).padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        val shape = MaterialTheme.shapes.small
        if (e.isDir) {
            Box(Modifier.size(40.dp).clip(shape).background(palette.surface.toComposeColor()), contentAlignment = Alignment.Center) {
                Icon(XnotesIcons.folder, null, tint = e.color?.let { codeTint(it, palette) } ?: dim, modifier = Modifier.size(22.dp))
            }
        } else {
            EntryThumb(editor, e, ThumbShape.TOP, Modifier.size(40.dp).clip(shape).border(1.dp, palette.border.toComposeColor(), shape))
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(label(item), color = palette.text.toComposeColor(), fontSize = 14.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(kindIcon(kind), null, tint = dim, modifier = Modifier.size(12.dp))
                val sub = if (wide) kindLabel(kind) else stringResource(R.string.trash_row_sub, kindLabel(kind), wasIn, deleted)
                Text(sub, color = dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (wide) {
            Row(Modifier.width(170.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(XnotesIcons.folder, null, tint = dim, modifier = Modifier.size(14.dp))
                Text(wasIn, color = dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(deleted, color = dim, fontSize = 13.sp, maxLines = 1, modifier = Modifier.width(150.dp))
            Text(
                when (left) { null -> stringResource(R.string.never); 0 -> stringResource(R.string.today); else -> pluralStringResource(R.plurals.days_count, left, left) },
                color = if (left != null && left <= 3) palette.danger.toComposeColor() else palette.text.toComposeColor(),
                fontSize = 13.sp, maxLines = 1, modifier = Modifier.width(110.dp),
            )
        }
        Row(Modifier.width(150.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
            Row(
                Modifier.clip(MaterialTheme.shapes.small).clickable(onClick = onRestore).padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(XnotesIcons.restore, null, tint = palette.accent.toComposeColor(), modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.restore), color = palette.accent.toComposeColor(), fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
            }
            var menu by remember { mutableStateOf(false) }
            Box {
                Tooltip(stringResource(R.string.more)) {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) {
                        Icon(XnotesIcons.more, stringResource(R.string.more_for, label(item)), tint = dim, modifier = Modifier.size(17.dp))
                    }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuItem(stringResource(R.string.restore), XnotesIcons.restore, onClick = { menu = false; onRestore() })
                    MenuItem(stringResource(R.string.delete_permanently), XnotesIcons.trash, danger = true, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}
