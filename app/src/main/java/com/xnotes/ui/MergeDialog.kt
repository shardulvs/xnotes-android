package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.xnotes.R
import com.xnotes.core.util.DocumentKind
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlin.math.roundToInt

/** A note's row in the merge list. */
private val ROW = 44.dp

/**
 * Merge's review: the notes picked, in pick order, each draggable by its handle into the order
 * they go in, and the new note's name, the first note's to start with.
 */
@Composable
internal fun MergeDialog(picked: List<BrowseEntry>, onDismiss: () -> Unit, onMerge: (List<BrowseEntry>, String) -> Unit) {
    val palette = LocalPalette.current
    val order = remember { mutableStateListOf(*picked.toTypedArray()) }
    var name by remember { mutableStateOf(DocumentKind.stripSuffix(picked.first().name)) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var drift by remember { mutableFloatStateOf(0f) }
    val rowPx = with(LocalDensity.current) { ROW.toPx() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.merge_notes)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.new_note_name)) },
                )
                Text(stringResource(R.string.merge_order), color = palette.textDim.toComposeColor(), fontSize = 12.sp)
                Column {
                    order.forEachIndexed { i, entry ->
                        key(entry.documentUri) {
                            val lifted = dragging == entry.documentUri
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(ROW)
                                    .zIndex(if (lifted) 1f else 0f)
                                    .offset { IntOffset(0, if (lifted) drift.roundToInt() else 0) }
                                    .clip(MaterialTheme.shapes.small)
                                    .background((if (lifted) palette.selectionBackground else palette.menuBg).toComposeColor()),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "${i + 1}",
                                    color = palette.textDim.toComposeColor(),
                                    fontSize = 13.sp,
                                    modifier = Modifier.width(28.dp).padding(start = 8.dp),
                                )
                                Text(
                                    DocumentKind.stripSuffix(entry.name),
                                    color = palette.text.toComposeColor(),
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Box(
                                    Modifier
                                        .size(ROW)
                                        .pointerInput(entry.documentUri) {
                                            detectDragGestures(
                                                onDragStart = { dragging = entry.documentUri; drift = 0f },
                                                onDragEnd = { dragging = null; drift = 0f },
                                                onDragCancel = { dragging = null; drift = 0f },
                                            ) { change, amount ->
                                                change.consume()
                                                drift += amount.y
                                                val from = order.indexOfFirst { it.documentUri == entry.documentUri }
                                                val to = (from + (drift / rowPx).roundToInt()).coerceIn(0, order.lastIndex)
                                                if (from >= 0 && to != from) {
                                                    order.add(to, order.removeAt(from))
                                                    drift -= (to - from) * rowPx
                                                }
                                            }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(XnotesIcons.grip, stringResource(R.string.drag_to_reorder), tint = palette.textDim.toComposeColor(), modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onMerge(order.toList(), name.trim()) }) { Text(stringResource(R.string.merge)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
