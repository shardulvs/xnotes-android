package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xnotes.R
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/*
 * The pieces of a dialog: the settings panel's sheet and cards, with buttons across the foot, so a
 * dialog reads as the panels and the tool menus do.
 */

/** How wide a dialog is unless it asks for more. */
private val SHEET_WIDTH = 320.dp

/** How a dialog's button reads: the one that goes ahead is filled, and one that destroys is red. */
internal enum class ButtonKind {
    PLAIN,

    /** The one way ahead. */
    CONFIRM,

    /** One of two ways ahead, neither the default. */
    CHOICE,

    /** The way ahead when it destroys something. */
    DESTROY,

    /** A destroying way out beside the way ahead. */
    DISCARD,
}

/**
 * The app's dialog: its [title] over an optional [text], with [action] at the title's end. The
 * cards of [content] scroll when the window is short; the buttons of [foot] stay put.
 */
@Composable
internal fun SheetDialog(
    title: String,
    onDismiss: () -> Unit,
    width: Dp = SHEET_WIDTH,
    text: String? = null,
    gap: Dp = 12.dp,
    properties: DialogProperties = DialogProperties(),
    action: (@Composable RowScope.() -> Unit)? = null,
    foot: (@Composable ColumnScope.() -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    // The eyedropper samples what is under a dialog, so the dialog steps aside while it is armed.
    if (Eyedropper.target != null) return
    val palette = LocalPalette.current
    Dialog(onDismissRequest = onDismiss, properties = properties) {
        DialogSheet(width, gap) {
            // Held in from the sheet's round corners, as a tool menu's title is.
            Column(
                Modifier.padding(
                    start = 10.dp,
                    top = if (action == null) 8.dp else 2.dp,
                    end = if (action == null) 10.dp else 2.dp,
                    bottom = if (text == null) 0.dp else 4.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        color = palette.text.toComposeColor(),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 22.sp,
                        modifier = Modifier.weight(1f),
                    )
                    action?.invoke(this)
                }
                if (text != null) Text(text, color = palette.textDim.toComposeColor(), fontSize = 14.sp, lineHeight = 20.sp)
            }
            if (content != null) {
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(gap),
                    content = content,
                )
            }
            if (foot != null) Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = foot)
        }
    }
}

/** A dialog's frame: the panel's sheet, [width] across unless the window is narrower. */
@Composable
private fun DialogSheet(width: Dp, gap: Dp, content: @Composable ColumnScope.() -> Unit) {
    val shape = MaterialTheme.shapes.large
    // Sized text keeps material's 24sp line otherwise, which the fixed rows clip.
    ProvideTextStyle(LocalTextStyle.current.copy(lineHeight = 1.3.em)) {
        Column(
            Modifier
                .width(width)
                .clip(shape)
                .background(panelColors().sheet)
                .border(1.dp, LocalPalette.current.border.toComposeColor(), shape)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(gap),
            content = content,
        )
    }
}

/** A button of a dialog's foot. */
@Composable
internal fun SheetButton(
    label: String,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.PLAIN,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    val card = panelColors().card
    val (fill, ink) = when {
        !enabled -> card to palette.disabled.toComposeColor()
        kind == ButtonKind.CONFIRM -> palette.accent.toComposeColor() to palette.onAccent.toComposeColor()
        kind == ButtonKind.CHOICE -> palette.selectionBackground.toComposeColor() to palette.selectionForeground.toComposeColor()
        kind == ButtonKind.DESTROY -> palette.danger.toComposeColor() to palette.materialColors.onError.toComposeColor()
        kind == ButtonKind.DISCARD -> card to palette.danger.toComposeColor()
        else -> card to palette.text.toComposeColor()
    }
    Box(
        modifier
            .height(44.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(fill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A row of a dialog's buttons, each as wide as the others. */
@Composable
internal fun DialogButtons(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

/** One button of [DialogButtons]. */
@Composable
internal fun RowScope.DialogButton(label: String, kind: ButtonKind = ButtonKind.PLAIN, enabled: Boolean = true, onClick: () -> Unit) {
    SheetButton(label, Modifier.weight(1f), kind, enabled, onClick)
}

/** The usual foot: Cancel beside the button that goes ahead. */
@Composable
internal fun ConfirmButtons(
    confirm: String,
    onCancel: () -> Unit,
    kind: ButtonKind = ButtonKind.CONFIRM,
    enabled: Boolean = true,
    onConfirm: () -> Unit,
) {
    DialogButtons {
        DialogButton(stringResource(R.string.cancel), onClick = onCancel)
        DialogButton(confirm, kind, enabled, onConfirm)
    }
}

/**
 * A dialog's text field: a card under an optional [label], outlined in the accent while it is
 * typed in and in the danger colour over its [error]. More than one of [lines] makes it a box.
 */
@Composable
internal fun DialogField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    label: String? = null,
    placeholder: String? = null,
    error: String? = null,
    lines: Int = 1,
    autoFocus: Boolean = false,
    onDone: (() -> Unit)? = null,
    onEscape: (() -> Unit)? = null,
) {
    val palette = LocalPalette.current
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    if (autoFocus) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label != null) {
            Text(label, color = palette.textDim.toComposeColor(), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp))
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { ev ->
                    when {
                        ev.type != KeyEventType.KeyDown -> false
                        lines == 1 && onDone != null && (ev.key == Key.Enter || ev.key == Key.NumPadEnter) -> { onDone(); true }
                        onEscape != null && ev.key == Key.Escape -> { onEscape(); true }
                        else -> false
                    }
                },
            textStyle = TextStyle(color = palette.text.toComposeColor(), fontSize = 15.sp, lineHeight = 21.sp),
            singleLine = lines == 1,
            minLines = lines,
            maxLines = if (lines == 1) 1 else 10,
            keyboardOptions = if (lines == 1) KeyboardOptions(imeAction = ImeAction.Done) else KeyboardOptions.Default,
            keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
            cursorBrush = SolidColor(palette.accent.toComposeColor()),
            decorationBox = { inner ->
                val shape = MaterialTheme.shapes.medium
                val edge = when {
                    error != null -> palette.danger
                    focused -> palette.accent
                    else -> null
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(shape)
                        .background(panelColors().card)
                        .then(if (edge != null) Modifier.border(1.5.dp, edge.toComposeColor(), shape) else Modifier)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = if (lines == 1) Alignment.CenterStart else Alignment.TopStart,
                ) {
                    if (placeholder != null && value.text.isEmpty()) {
                        Text(placeholder, color = palette.textDim.toComposeColor(), fontSize = 15.sp, lineHeight = 21.sp, maxLines = 1)
                    }
                    inner()
                }
            },
        )
        if (error != null) {
            Text(error, color = palette.danger.toComposeColor(), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
}

/** [DialogField] over plain text, the caret kept at its end when the text changes from outside. */
@Composable
internal fun DialogField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String? = null,
    placeholder: String? = null,
    error: String? = null,
    lines: Int = 1,
    autoFocus: Boolean = false,
    onDone: (() -> Unit)? = null,
    onEscape: (() -> Unit)? = null,
) {
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    DialogField(
        value = if (field.text == value) field else TextFieldValue(value, TextRange(value.length)),
        onValueChange = {
            field = it
            if (it.text != value) onValueChange(it.text)
        },
        label = label,
        placeholder = placeholder,
        error = error,
        lines = lines,
        autoFocus = autoFocus,
        onDone = onDone,
        onEscape = onEscape,
    )
}

/** One answer of a dialog that asks which: what it does over what that costs, on a card that takes the tap. */
@Composable
internal fun DialogChoice(title: String, detail: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(panelColors().card)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 16.dp, top = 14.dp, end = 12.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = palette.text.toComposeColor(), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(detail, color = palette.textDim.toComposeColor(), fontSize = 13.sp)
        }
        Icon(XnotesIcons.next, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(16.dp))
    }
}

/**
 * Work under way: a ring, turning until there is a [fraction] to fill it to, around [inRing], over
 * what is being done and how far along it is. Without [onCancel] there is no way out of it.
 */
@Composable
internal fun ProgressDialog(
    title: String,
    detail: String,
    fraction: Float? = null,
    inRing: String? = null,
    dismissOnClickOutside: Boolean = true,
    keepScreenOn: Boolean = false,
    onCancel: (() -> Unit)?,
) {
    val palette = LocalPalette.current
    Dialog(
        onDismissRequest = { onCancel?.invoke() },
        properties = DialogProperties(dismissOnBackPress = onCancel != null, dismissOnClickOutside = onCancel != null && dismissOnClickOutside),
    ) {
        if (keepScreenOn) {
            val view = LocalView.current
            DisposableEffect(view) {
                view.keepScreenOn = true
                onDispose { view.keepScreenOn = false }
            }
        }
        DialogSheet(280.dp, 12.dp) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(panelColors().card)
                    .padding(start = 16.dp, top = 24.dp, end = 16.dp, bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                    if (fraction == null) {
                        CircularProgressIndicator(Modifier.fillMaxSize(), color = palette.accent.toComposeColor(), strokeWidth = 5.dp)
                    } else {
                        CircularProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxSize(),
                            color = palette.accent.toComposeColor(),
                            trackColor = palette.border.toComposeColor(),
                            strokeWidth = 5.dp,
                        )
                        if (inRing != null) Text(inRing, color = palette.text.toComposeColor(), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(title, color = palette.text.toComposeColor(), fontSize = 15.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                Text(detail, color = palette.textDim.toComposeColor(), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (onCancel != null) SheetButton(stringResource(R.string.cancel), Modifier.fillMaxWidth(), onClick = onCancel)
        }
    }
}
