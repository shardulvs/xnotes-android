package com.xnotes.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.core.util.DocumentKind
import com.xnotes.core.util.ReaderKind
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The explorer's folder as an expandable tree for the sidebar. Only open folders are listed, each with the
 * explorer's own listing (so the dot-item filter applies); tapping a folder folds it, long-pressing opens it.
 */
@Composable
internal fun SidebarFileTree(editor: Editor, onOpenFile: (BrowseEntry) -> Unit, onOpenFolder: (BrowseEntry) -> Unit) {
    val root = editor.browseRoot ?: return
    val expanded = remember(root) { mutableStateMapOf<String, Boolean>() }
    val rootId = remember(root) { editor.browseRootDocId(root) }
    Column(Modifier.fillMaxWidth()) {
        TreeLevel(editor, root, rootId, 0, expanded, onOpenFile, onOpenFolder)
    }
}

@Composable
private fun TreeLevel(
    editor: Editor,
    root: String,
    docId: String,
    depth: Int,
    expanded: SnapshotStateMap<String, Boolean>,
    onOpenFile: (BrowseEntry) -> Unit,
    onOpenFolder: (BrowseEntry) -> Unit,
) {
    val children by produceState(editor.cachedChildren(root, docId), root, docId, editor.treeVersion, editor.noteOpen) {
        value = withContext(Dispatchers.IO) { editor.browseChildren(root, docId) }
    }
    val sorted = remember(children) { children.orEmpty().sortedWith(compareBy<BrowseEntry>({ !it.isDir }, { it.name.lowercase() })) }
    val showExtensions = remember(editor.prefsVersion) { editor.preferences.showExtensions }
    for (e in sorted) {
        key(e.documentUri) {
            val open = e.isDir && expanded[e.documentUri] == true
            TreeRow(
                e, depth, open, showExtensions,
                onClick = { if (e.isDir) expanded[e.documentUri] = !open else onOpenFile(e) },
                onLongClick = if (e.isDir) ({ onOpenFolder(e) }) else null,
            )
            if (open) TreeLevel(editor, root, editor.browseDocId(e.documentUri), depth + 1, expanded, onOpenFile, onOpenFolder)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TreeRow(e: BrowseEntry, depth: Int, open: Boolean, showExtensions: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    val palette = LocalPalette.current
    val dim = palette.textDim.toComposeColor()
    Row(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 14.dp + 14.dp * depth, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (e.isDir) {
            Icon(XnotesIcons.chevronDown, null, tint = dim, modifier = Modifier.size(16.dp).rotate(if (open) 0f else -90f))
        } else {
            Spacer(Modifier.width(16.dp))
        }
        Spacer(Modifier.width(4.dp))
        Icon(treeIcon(e), null, tint = e.color?.let { codeTint(it, palette) } ?: dim, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            if (e.isDir || showExtensions) e.name else DocumentKind.stripSuffix(e.name),
            color = palette.text.toComposeColor(),
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun treeIcon(e: BrowseEntry): ImageVector = when {
    e.isDir -> XnotesIcons.folder
    DocumentKind.ofName(e.name) == DocumentKind.CANVAS -> XnotesIcons.canvas
    ReaderKind.isReadable(e.name) -> XnotesIcons.lock
    else -> XnotesIcons.file
}
