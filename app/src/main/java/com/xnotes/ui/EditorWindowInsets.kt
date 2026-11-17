package com.xnotes.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable

/**
 * Some tablet keyboards retain a nonzero IME inset after hiding. Applying it unconditionally
 * shrinks the editor and lifts bottom docks. Keep system-bar ownership in the outer Scaffold;
 * windowInsetsPadding consumes those insets before applying the visible keyboard remainder.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun visibleImeInsets(): WindowInsets =
    editorImeInsets(WindowInsets.isImeVisible, WindowInsets.ime)

internal fun editorImeInsets(visible: Boolean, reported: WindowInsets): WindowInsets =
    if (visible) reported else WindowInsets(0, 0, 0, 0)
