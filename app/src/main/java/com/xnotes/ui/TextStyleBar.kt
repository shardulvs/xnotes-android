package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.core.pal.FontFace
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlin.math.roundToInt

/** The four selectable faces and their labels, in the order the picker lists them. */
private val FACES = listOf(
    FontFace.SANS to "Sans",
    FontFace.SERIF to "Serif",
    FontFace.MONO to "Mono",
    FontFace.HAND to "Hand",
)

private fun faceLabel(face: FontFace): String = FACES.firstOrNull { it.first == face }?.second ?: "Mono"

/**
 * Floating font/size bar for the active text box (the one being edited, or a lone
 * selected one). Anchored above the box; colour is driven by the toolbar swatches,
 * so this carries only the family picker and a point-size stepper. When the box is
 * merely selected (not editing) the generic selection menu also sits above it, so
 * this bar stacks one row higher.
 */
@Composable
fun TextStyleBar(editor: Editor) {
    val bar = editor.textBar ?: return
    val palette = LocalPalette.current
    val density = LocalDensity.current

    val barWidth = 196.dp
    val barHeightPx = with(density) { 44.dp.toPx() }
    val barWidthPx = with(density) { barWidth.toPx() }
    val gapPx = with(density) { 8.dp.toPx() }
    // When the box is only selected (not editing), the generic selection menu also sits above it,
    // so raise this bar by that menu's height + a gap to stack cleanly above it.
    val stackPx = if (!bar.editing) with(density) { 56.dp.toPx() } else 0f
    val minX = with(density) { 8.dp.toPx() }

    val rect = bar.rect
    val centerX = ((rect.left + rect.right) / 2.0).toFloat()
    val xPx = (centerX - barWidthPx / 2f).coerceAtLeast(minX)
    val above = rect.top.toFloat() - barHeightPx - gapPx - stackPx
    val yPx = if (above > 0f) above else rect.bottom.toFloat() + gapPx + stackPx

    val xDp = with(density) { xPx.toDp() }
    val yDp = with(density) { yPx.toDp() }

    Row(
        modifier = Modifier
            .offset(xDp, yDp)
            .height(44.dp)
            .width(barWidth)
            .clip(RoundedCornerShape(10.dp))
            .background(palette.menuBg.toComposeColor())
            .border(1.dp, palette.border.toComposeColor(), RoundedCornerShape(10.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FacePicker(current = bar.face) { editor.setTextFace(it) }
        Box(
            Modifier.width(1.dp).fillMaxHeight().padding(vertical = 8.dp)
                .background(palette.border.toComposeColor()),
        )
        SizeStepper(
            size = bar.pointSize,
            onDelta = { editor.setTextPointSize(bar.pointSize + it) },
        )
    }
}

@Composable
private fun FacePicker(current: FontFace, onPick: (FontFace) -> Unit) {
    val palette = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .clickable { open = true }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                faceLabel(current),
                color = palette.text.toComposeColor(),
                style = TextStyle(fontFamily = current.toComposeFamily(), fontSize = 15.sp),
            )
            Text(" ▾", color = palette.textDim.toComposeColor(), fontSize = 11.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((face, label) in FACES) {
                DropdownMenuItem(
                    text = {
                        Text(
                            label,
                            color = palette.text.toComposeColor(),
                            style = TextStyle(fontFamily = face.toComposeFamily(), fontSize = 15.sp),
                        )
                    },
                    onClick = { onPick(face); open = false },
                )
            }
        }
    }
}

@Composable
private fun SizeStepper(size: Double, onDelta: (Double) -> Unit) {
    val palette = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepButton("−") { onDelta(-1.0) } // minus
        Text(
            size.roundToInt().toString(),
            color = palette.text.toComposeColor(),
            fontSize = 15.sp,
            modifier = Modifier.width(26.dp),
            style = TextStyle(fontFamily = FontFamily.Monospace),
        )
        StepButton("+") { onDelta(1.0) }
    }
}

@Composable
private fun StepButton(glyph: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Box(
        modifier = Modifier.size(40.dp).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = palette.text.toComposeColor(), fontSize = 20.sp)
    }
}
