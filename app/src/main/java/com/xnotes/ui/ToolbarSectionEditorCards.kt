package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.core.tools.ToolbarRegion
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/**
 * A bordered section box: a header strip (grab area for the section drag) with the delete X at its
 * right, then the tool chips. The header is sized to the chips' width by [SectionCardScaffold] so
 * the strip beside the X fills the whole top edge without forcing the card to the full pane width.
 */
@Composable
internal fun SectionCard(
    label: String,
    canDelete: Boolean,
    lanes: Int,
    region: ToolbarRegion,
    regionsEnabled: Boolean,
    onLanesChange: (Int) -> Unit,
    onRegionChange: (ToolbarRegion) -> Unit,
    targeted: Boolean,
    dimmed: Boolean,
    onDelete: () -> Unit,
    onBounds: (Rect) -> Unit,
    onGrabBounds: (Rect) -> Unit,
    onGrabDragStart: (Offset) -> Unit,
    onGrabDrag: (Offset) -> Unit,
    onGrabDrop: () -> Unit,
    onGrabCancel: () -> Unit,
    content: @Composable () -> Unit,
) {
    val toolbarStrings = androidx.compose.ui.platform.LocalContext.current
    val palette = LocalPalette.current
    var sectionMenuOpen by remember { mutableStateOf(false) }
    SectionCardScaffold(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (dimmed) 0.35f else 1f)
            .onGloballyPositioned { onBounds(it.boundsInRoot()) }
            .clip(MaterialTheme.shapes.small)
            .border(1.dp, (if (targeted) palette.accent else palette.border).toComposeColor(), MaterialTheme.shapes.small)
            .padding(start = 6.dp, top = 2.dp, end = 2.dp, bottom = 6.dp),
        header = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionDragStrip(
                    label = label,
                    // Explicit height: the card is measured with an unbounded max height, so
                    // fillMaxHeight would collapse the grab strip to 0px and nothing could be pressed.
                    modifier = Modifier.weight(1f).height(34.dp),
                    onBounds = onGrabBounds,
                    onDragStart = onGrabDragStart,
                    onDrag = onGrabDrag,
                    onDrop = onGrabDrop,
                    onCancel = onGrabCancel,
                )
                Box {
                    Tooltip(toolbarStrings.getString(R.string.toolbar_config_configure_section_rows)) {
                        IconButton(onClick = { sectionMenuOpen = true }, modifier = Modifier.size(26.dp)) {
                            Icon(
                                XnotesIcons.more, toolbarStrings.getString(R.string.toolbar_config_configure_section_rows),
                                tint = palette.textDim.toComposeColor(),
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                    DropdownMenu(expanded = sectionMenuOpen, onDismissRequest = { sectionMenuOpen = false }) {
                        for (count in 1..3) {
                            DropdownMenuItem(
                                text = { Text(if (count == 1) "1 linha" else "$count linhas") },
                                leadingIcon = if (lanes == count) {
                                    { Icon(XnotesIcons.check, null, modifier = Modifier.size(16.dp)) }
                                } else null,
                                onClick = {
                                    sectionMenuOpen = false
                                    onLanesChange(count)
                                },
                            )
                        }
                        if (regionsEnabled) {
                            HorizontalDivider()
                            for (target in ToolbarRegion.entries) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(when (target) {
                                        ToolbarRegion.START -> R.string.toolbar_region_start
                                        ToolbarRegion.CENTER -> R.string.toolbar_region_center
                                        ToolbarRegion.END -> R.string.toolbar_region_end
                                    })) },
                                    leadingIcon = if (region == target) {
                                        { Icon(XnotesIcons.check, null, modifier = Modifier.size(16.dp)) }
                                    } else null,
                                    onClick = {
                                        sectionMenuOpen = false
                                        onRegionChange(target)
                                    },
                                )
                            }
                        }
                    }
                }
                Tooltip(stringResource(R.string.delete_section)) {
                    IconButton(onClick = onDelete, enabled = canDelete, modifier = Modifier.size(26.dp)) {
                        Icon(
                            XnotesIcons.close, stringResource(R.string.delete_section),
                            tint = (if (canDelete) palette.textDim else palette.border).toComposeColor(),
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        },
        body = content,
    )
}

/** Stacks [header] above [body] and sizes the header to the body's measured width. */
@Composable
private fun SectionCardScaffold(
    modifier: Modifier,
    header: @Composable () -> Unit,
    body: @Composable () -> Unit,
) {
    Layout(contents = listOf(body, header), modifier = modifier) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val bodyPlaceable = measurables[0].first().measure(loose)
        val width = bodyPlaceable.width.coerceIn(constraints.minWidth, constraints.maxWidth)
        val headerPlaceable = measurables[1].first().measure(loose.copy(minWidth = width, maxWidth = width))
        layout(width, headerPlaceable.height + bodyPlaceable.height) {
            headerPlaceable.place(0, 0)
            bodyPlaceable.place(0, headerPlaceable.height)
        }
    }
}

/** The transparent top strip beside the X; long-press and drag it to reorder the whole section. */
@Composable
private fun SectionDragStrip(
    label: String,
    modifier: Modifier,
    onBounds: (Rect) -> Unit,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDrop: () -> Unit,
    onCancel: () -> Unit,
) {
    val dStart by rememberUpdatedState(onDragStart)
    val dMove by rememberUpdatedState(onDrag)
    val dEnd by rememberUpdatedState(onDrop)
    val dCancel by rememberUpdatedState(onCancel)
    val palette = LocalPalette.current
    Row(
        modifier
            .onGloballyPositioned { onBounds(it.boundsInRoot()) }
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { dStart(it) },
                    onDrag = { _, delta -> dMove(delta) },
                    onDragEnd = { dEnd() },
                    onDragCancel = { dCancel() },
                )
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(XnotesIcons.menu, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(18.dp))
        Text(label, color = palette.text.toComposeColor(), style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
internal fun AddSectionChip(onClick: (Int) -> Unit) {
    val toolbarStrings = androidx.compose.ui.platform.LocalContext.current
    val palette = LocalPalette.current
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .clip(MaterialTheme.shapes.small)
                .border(1.dp, palette.accent.toComposeColor(), MaterialTheme.shapes.small)
                .clickable { menuOpen = true }
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(XnotesIcons.plus, stringResource(R.string.add_section), tint = palette.accent.toComposeColor(), modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.add_section), color = palette.accent.toComposeColor(), fontSize = 12.sp)
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(text = { Text(toolbarStrings.getString(R.string.toolbar_config_one_row)) }, onClick = {
                menuOpen = false
                onClick(1)
            })
            DropdownMenuItem(text = { Text(toolbarStrings.getString(R.string.toolbar_config_two_rows)) }, onClick = {
                menuOpen = false
                onClick(2)
            })
            DropdownMenuItem(text = { Text(toolbarStrings.getString(R.string.toolbar_config_three_rows)) }, onClick = {
                menuOpen = false
                onClick(3)
            })
        }
    }
}

@Composable
internal fun InsertionCaret() {
    Box(Modifier.fillMaxWidth().height(2.dp).background(LocalPalette.current.accent.toComposeColor()))
}

/** Marker between layer-style section cards. */
@Composable
internal fun SectionCaret() {
    Box(Modifier.fillMaxWidth().height(3.dp)
        .clip(RoundedCornerShape(2.dp)).background(LocalPalette.current.accent.toComposeColor()))
}
