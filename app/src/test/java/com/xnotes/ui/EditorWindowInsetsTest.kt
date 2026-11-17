package com.xnotes.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Test

class EditorWindowInsetsTest {
    private val density = Density(1f)

    @Test fun hiddenKeyboardCannotLiftBottomDockWithStaleTabletInset() {
        val stale = WindowInsets(bottom = 160)
        assertEquals(0, editorImeInsets(false, stale).getBottom(density))
    }

    @Test fun visibleKeyboardStillReservesSpaceAboveSystemBars() {
        val keyboard = editorImeInsets(true, WindowInsets(bottom = 600))
        assertEquals(600, keyboard.getBottom(density))
        // The outer Scaffold has already reserved this area in non-fullscreen mode.
        assertEquals(552, keyboard.exclude(WindowInsets(bottom = 48)).getBottom(density))
    }

    @Test fun floatingKeyboardDoesNotResizeEditor() {
        assertEquals(0, editorImeInsets(true, WindowInsets(0, 0, 0, 0)).getBottom(density))
    }
}
