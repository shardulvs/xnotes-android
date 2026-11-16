package com.xnotes.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import com.xnotes.platform.AppIcon
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/** How long a press rests, without moving, before the icon picker opens. */
private const val PICKER_HOLD_MS = 3000L

private val GAP = 8.dp
private val CELL_PAD = 8.dp
private val FRAME_PAD = 16.dp

/** Opens the icon picker once a press has rested here for [PICKER_HOLD_MS]; nothing shows before that. */
internal fun Modifier.holdForIconPicker(onOpen: () -> Unit): Modifier = pointerInput(onOpen) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val letGo = withTimeoutOrNull(PICKER_HOLD_MS) {
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
            }
        }
        if (letGo == null) onOpen()
    }
}

/** [icon] the way a launcher shows it: its adaptive layers cut to the device's icon shape. */
@Composable
internal fun AppIconImage(icon: AppIcon, size: Dp, contentDescription: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bitmap = remember(icon, px) {
        runCatching { context.getDrawable(icon.icon)?.toBitmap(px, px)?.asImageBitmap() }.getOrNull()
    }
    if (bitmap != null) Image(bitmap, contentDescription, modifier.size(size)) else Box(modifier.size(size))
}

/** Every launcher icon, in rows of four, the one in use wearing the selection; a tap switches to one at once. */
@Composable
internal fun IconPicker(current: AppIcon, onPick: (AppIcon) -> Unit, onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    val shape = MaterialTheme.shapes.large
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.padding(24.dp)) {
            val size = ((maxWidth - FRAME_PAD * 2 - GAP * 3) / 4 - CELL_PAD * 2).coerceIn(40.dp, 64.dp)
            Column(
                Modifier
                    .clip(shape)
                    .background(palette.menuBg.toComposeColor())
                    .border(1.dp, palette.border.toComposeColor(), shape)
                    .padding(FRAME_PAD)
                    .selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(GAP),
            ) {
                AppIcon.entries.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
                        row.forEach { icon -> IconChoice(icon, icon == current, size) { onPick(icon) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun IconChoice(icon: AppIcon, selected: Boolean, size: Dp, onPick: () -> Unit) {
    val palette = LocalPalette.current
    val shape = MaterialTheme.shapes.medium
    Box(
        Modifier
            .clip(shape)
            .background(if (selected) palette.selectionBackground.toComposeColor() else Color.Transparent)
            .border(1.dp, if (selected) palette.accent.toComposeColor() else Color.Transparent, shape)
            .selectable(selected, role = Role.RadioButton, onClick = onPick)
            .padding(CELL_PAD),
    ) {
        AppIconImage(icon, size, stringResource(icon.label))
    }
}
