package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.xnotes.canvas.EditingField
import com.xnotes.core.pal.FontFace
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/** Maps an abstract [FontFace] to the matching Compose generic family (shared with the style bar). */
internal fun FontFace.toComposeFamily(): FontFamily = when (this) {
    FontFace.SANS -> FontFamily.SansSerif
    FontFace.SERIF -> FontFamily.Serif
    FontFace.MONO -> FontFamily.Monospace
    FontFace.HAND -> FontFamily.Cursive
}

/**
 * The in-place text-box editor (PAL §13): a native field overlaid on the canvas at
 * the box's on-screen position and size, with a zoom-scaled font matching the baked
 * text. It does **not** commit itself — the canvas is the single authority for ending
 * an edit (tap outside / tool switch / Back), which is what removed the old
 * double-commit duplication. It only mirrors keystrokes back to the model and grows
 * with content (its min height is the box's reserved area).
 */
@Composable
fun TextEditorOverlay(editor: Editor, field: EditingField) {
    val density = LocalDensity.current
    val palette = LocalPalette.current
    var text by remember { mutableStateOf(field.text) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val xDp = with(density) { field.x.toFloat().toDp() }
    val yDp = with(density) { field.y.toFloat().toDp() }
    val widthDp = with(density) { field.width.toFloat().toDp() }
    val heightDp = with(density) { field.heightPx.toFloat().toDp() }
    val fontSp = with(density) { field.fontPx.toFloat().toSp() }

    BasicTextField(
        value = text,
        onValueChange = {
            text = it
            editor.updateEditingText(it)
        },
        modifier = Modifier
            .offset(xDp, yDp)
            .widthIn(min = widthDp, max = widthDp)
            .heightIn(min = heightDp)
            .border(1.dp, palette.accent.toComposeColor())
            .background(palette.menuBg.toComposeColor().copy(alpha = 0.85f))
            .focusRequester(focusRequester),
        textStyle = TextStyle(
            color = field.rgba.toComposeColor(),
            fontFamily = field.face.toComposeFamily(),
            fontSize = fontSp,
        ),
        cursorBrush = SolidColor(palette.accent.toComposeColor()),
    )
}
