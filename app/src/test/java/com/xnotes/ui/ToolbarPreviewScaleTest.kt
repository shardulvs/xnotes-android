package com.xnotes.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolbarPreviewScaleTest {
    @Test
    fun scalesEveryControlByThePreviewToWindowWidthRatio() {
        assertEquals(0.75f, toolbarPreviewScale(600.dp, 800.dp), 0.0001f)
        assertEquals(0.5f, toolbarPreviewScale(480.dp, 960.dp), 0.0001f)
    }

    @Test
    fun doesNotEnlargeControlsWhenPreviewIsWiderThanWindow() {
        assertEquals(1f, toolbarPreviewScale(1200.dp, 960.dp), 0.0001f)
    }

    @Test
    fun preservesTheRealWindowAspectRatioInBothOrientations() {
        assertEquals(1.6f, toolbarPreviewAspectRatio(960.dp, 600.dp), 0.0001f)
        assertEquals(0.6f, toolbarPreviewAspectRatio(600.dp, 1000.dp), 0.0001f)
    }

    @Test
    fun handlesInvalidDimensionsWithoutDividingByZero() {
        assertEquals(1f, toolbarPreviewScale(480.dp, 0.dp), 0.0001f)
        assertEquals(1f, toolbarPreviewScale(0.dp, 960.dp), 0.0001f)
        assertEquals(1.6f, toolbarPreviewAspectRatio(960.dp, 0.dp), 0.0001f)
    }
}
