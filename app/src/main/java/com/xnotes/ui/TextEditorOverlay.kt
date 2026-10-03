package com.xnotes.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.relocation.BringIntoViewModifierNode
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import com.xnotes.canvas.EditingField
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.FontFace
import com.xnotes.platform.FontCatalog
import com.xnotes.ui.theme.toComposeColor
import kotlin.math.abs
import kotlin.math.roundToInt

private val composeFamilies = java.util.concurrent.ConcurrentHashMap<String, FontFamily>()

/** Combine underline + strikethrough into one Compose decoration, or null for neither. */
private fun textDecorationOf(underline: Boolean, strike: Boolean): TextDecoration? = when {
    underline && strike -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
    underline -> TextDecoration.Underline
    strike -> TextDecoration.LineThrough
    else -> null
}

/** Drop memoized Compose families after a font import/removal changed resolution. */
internal fun invalidateComposeFamilies() = composeFamilies.clear()

/** Maps a [FontFace] to a Compose family: generic tokens directly, the rest via the catalog. */
internal fun FontFace.toComposeFamily(): FontFamily = when (this) {
    FontFace.SANS -> FontFamily.SansSerif
    FontFace.SERIF -> FontFamily.Serif
    FontFace.MONO -> FontFamily.Monospace
    FontFace.HAND -> FontFamily.Cursive
    else -> composeFamilies.getOrPut(id) {
        FontFamily(FontCatalog.resolve(this, bold = false, italic = false).typeface)
    }
}

/**
 * Swallows the text field's bring-into-view requests. On focus (and every caret move) Compose
 * asks the platform to reveal the caret via requestRectangleOnScreen; with the keyboard open in
 * an edge-to-edge window, ViewRootImpl answers by panning the whole window, which visibly lifts
 * a box taller than the viewport. Dispatch stops at the nearest [BringIntoViewModifierNode], so
 * a no-op ancestor keeps the request from ever reaching the platform: the page must never move
 * on its own during a box edit.
 */
private object NoBringIntoViewElement : ModifierNodeElement<NoBringIntoViewNode>() {
    override fun create() = NoBringIntoViewNode()
    override fun update(node: NoBringIntoViewNode) {}
    override fun hashCode(): Int = 0
    override fun equals(other: Any?): Boolean = other === this
}

private class NoBringIntoViewNode : Modifier.Node(), BringIntoViewModifierNode {
    override suspend fun bringIntoView(childCoordinates: LayoutCoordinates, boundsProvider: () -> Rect?) {}
}

/**
 * The in-place text-box editor (PAL §13): a native field overlaid on the canvas at the box's
 * on-screen position and size, with a zoom-scaled font matching the baked text. It does **not**
 * commit itself — the canvas is the single authority for ending an edit (tap outside / tool switch
 * / Back). It only mirrors keystrokes back to the model and grows with content.
 *
 * The field never scrolls its own text. It is measured with unbounded height so it sizes to its
 * content, and a one-finger drag on the box is rerouted to a document pan ([Editor.panWhileEditing])
 * with the edit kept live and the box following the page. The page never scrolls on its own — a
 * box must stay exactly where it is for the whole edit; reaching an off-screen caret is the
 * user's pan. The pan delta is taken in root coordinates (the field itself moves with the page,
 * so its own local frame would self-cancel). Touches off the box reach the canvas as before
 * (tap to commit, drag to scroll).
 */
@Composable
fun TextEditorOverlay(editor: Editor, field: EditingField) {
    val density = LocalDensity.current
    var value by remember { mutableStateOf(TextFieldValue(field.text, TextRange(field.text.length))) }
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // Content-space geometry: the field lays its text out on the box's own pixel grid (the
    // baked painter wraps a StaticLayout at floor(width) px with a content-size font, then
    // draws through a zoom-scaled canvas). Re-wrapping at screen scale instead would break
    // lines at slightly different words, since advances and line rounding aren't linear in
    // font size. The graphicsLayer below applies the zoom at draw time, just like the canvas.
    val z = field.zoom.toFloat()
    val widthDp = with(density) { field.width.toInt().toDp() }
    val heightDp = with(density) { field.height.toFloat().toDp() }
    val fontSp = with(density) { field.fontPx.toFloat().toSp() }

    /**
     * The field is measured through this wrapper with fully unbounded constraints and placed at
     * the box's viewport position in raw px. Measured against the canvas area's own (bounded)
     * constraints, a box taller than the screen would have its outer size clamped, and the text
     * field would scroll its content to the end-of-text caret — a tall box visibly showed its
     * tail at its top. Unbounded, the field is always exactly its content's size, so there is
     * nothing to scroll and the text stays put; the host Box clips the overflow.
     */
    Layout(content = {
        BasicTextField(
            value = value,
            onValueChange = {
                value = it
                editor.updateEditingText(it.text)
            },
            modifier = Modifier
                .then(NoBringIntoViewElement)
                // Zoom (and the view's page rotation) applied at draw time about the box's
                // page-space top-left, exactly like the canvas paints the baked text.
                .graphicsLayer(
                    scaleX = z,
                    scaleY = z,
                    rotationZ = field.rotation.toFloat(),
                    transformOrigin = TransformOrigin(0f, 0f),
                )
                .widthIn(min = widthDp, max = widthDp)
                .heightIn(min = heightDp)
                // No fill: the edited box is lifted out of the ink cache, so a transparent field lets the
                // page/PDF underneath show through while typing (true WYSIWYG). The border marks the
                // bounds; its width is pre-divided so it stays 1dp after the layer scale.
                .border(Dp(1f / z), editor.state.selectionAccent().toComposeColor())
                .focusRequester(focusRequester)
                .onGloballyPositioned { coords = it }
                .pointerInput(Unit) {
                    val slop = viewConfiguration.touchSlop
                    val longPress = viewConfiguration.longPressTimeoutMillis
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        if (down.type != PointerType.Touch) return@awaitEachGesture
                        val lc = coords ?: return@awaitEachGesture
                        var lastRoot = lc.localToRoot(down.position)
                        var dragging = false
                        var yielded = false
                        var totalX = 0f
                        var totalY = 0f
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) { if (dragging) change.consume(); break }
                            if (yielded) continue
                            // Resolve to root coordinates each event so the field's own movement (it follows
                            // the page as we scroll) does not cancel out the finger delta.
                            val cur = (coords ?: lc).localToRoot(change.position)
                            val dx = cur.x - lastRoot.x
                            val dy = cur.y - lastRoot.y
                            lastRoot = cur
                            totalX += dx
                            totalY += dy
                            if (!dragging) {
                                if (abs(totalX) <= slop && abs(totalY) <= slop) continue
                                // A drag that only starts after a long hold is a text selection, not a
                                // scroll: yield so the field's own selection handling runs.
                                if (change.uptimeMillis - down.uptimeMillis >= longPress) { yielded = true; continue }
                                dragging = true
                            }
                            editor.panWhileEditing(dx, dy)
                            change.consume()
                        }
                    }
                },
            textStyle = TextStyle(
                color = field.rgba.toComposeColor(),
                fontFamily = field.face.toComposeFamily(),
                fontSize = fontSp,
                fontWeight = if (field.bold) FontWeight.Bold else null,
                fontStyle = if (field.italic) FontStyle.Italic else null,
                textDecoration = textDecorationOf(field.underline, field.strike),
                textAlign = when (field.align) {
                    com.xnotes.core.pal.HAlign.CENTER -> TextAlign.Center
                    com.xnotes.core.pal.HAlign.RIGHT -> TextAlign.End
                    com.xnotes.core.pal.HAlign.LEFT -> TextAlign.Start
                },
            ),
            cursorBrush = SolidColor(Rgba.CARET.toComposeColor()),
        )
    }) { measurables, constraints ->
        val placeable = measurables[0].measure(Constraints())
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable.place(field.x.roundToInt(), field.y.roundToInt())
        }
    }
}
