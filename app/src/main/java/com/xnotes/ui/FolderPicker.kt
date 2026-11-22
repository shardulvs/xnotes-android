package com.xnotes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The folder list keeps one height, so the dialog holds still from folder to folder. */
private val LIST_HEIGHT = 300.dp

/**
 * Picks a folder in the tree under [root] to move things into, starting at [start] (a path of (document id,
 * name) below the top folder). [blocked] folders, the ones being moved, can't be opened or picked.
 */
@Composable
internal fun FolderPickerDialog(
    editor: Editor,
    root: String,
    rootName: String,
    title: String,
    confirmLabel: String,
    start: List<Pair<String, String>>,
    blocked: (docId: String) -> Boolean,
    onPick: (docId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    val rootId = remember(root) { editor.browseRootDocId(root) }
    val path = remember { mutableStateListOf<Pair<String, String>>().apply { addAll(start) } }
    val here = path.lastOrNull()?.first ?: rootId
    val folders by produceState(editor.cachedChildren(root, here)?.filter { it.isDir }, root, here) {
        value = withContext(Dispatchers.IO) { editor.browseChildren(root, here).filter { it.isDir } }
    }
    SheetDialog(
        title = title,
        onDismiss = onDismiss,
        width = 360.dp,
        foot = { ConfirmButtons(confirmLabel, onDismiss, enabled = !blocked(here)) { onPick(here) } },
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            Crumb("${rootName}/", current = path.isEmpty()) { path.clear() }
            path.forEachIndexed { i, (_, name) ->
                Crumb("$name/", current = i == path.lastIndex) { while (path.size > i + 1) path.removeAt(path.lastIndex) }
            }
        }
        PanelCard(Modifier.height(LIST_HEIGHT), padding = PaddingValues(6.dp)) {
            val list = folders
            when {
                list == null -> EmptyNote(stringResource(R.string.loading), Modifier.fillMaxHeight())
                list.isEmpty() -> EmptyNote(stringResource(R.string.no_folders_here), Modifier.fillMaxHeight())
                else -> LazyColumn(Modifier.fillMaxWidth()) {
                    items(list, key = { it.documentUri }) { f ->
                        val id = editor.browseDocId(f.documentUri)
                        val off = blocked(id)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .clip(MaterialTheme.shapes.small)
                                .clickable(enabled = !off) { path.add(id to f.name) }
                                .alpha(if (off) 0.4f else 1f)
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            val code = f.color?.let { codeTint(it, palette) }
                            Icon(XnotesIcons.folder, null, tint = code ?: palette.textDim.toComposeColor(), modifier = Modifier.size(20.dp))
                            Text(f.name, color = palette.text.toComposeColor(), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Icon(XnotesIcons.next, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}
