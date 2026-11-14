package com.xnotes.ui

import androidx.compose.ui.unit.dp
import com.xnotes.core.tools.ToolbarGeometry
import com.xnotes.core.tools.ToolbarEntry
import com.xnotes.core.tools.ToolbarItem
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.core.tools.ToolbarSection
import com.xnotes.settings.ToolbarSize
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolbarGeometrySizingTest {
    @Test fun automaticSettingsPreserveLegacyDimensions() {
        val metrics = resolvedBarMetrics(ToolbarSize.REGULAR, ToolbarGeometry())
        assertEquals(42.dp, metrics.button)
        assertEquals(4.dp, metrics.padding)
        assertEquals(2.dp, metrics.spacing)
        assertEquals(50.dp, metrics.thickness)
        assertEquals(50.dp, toolbarDepth(metrics, null))
    }

    @Test fun explicitMeasurementsResolveIdenticallyForPreviewAndLiveBar() {
        val geometry = ToolbarGeometry(
            widthDp = 260, heightDp = 110, paddingDp = 6,
            spacingDp = 7, elementSizeDp = 56,
        )
        val metrics = resolvedBarMetrics(ToolbarSize.COMPACT, geometry)
        val layout = ToolbarLayout(listOf(ToolbarSection(
            listOf(
                ToolbarEntry(ToolbarItem.PEN, lane = 0),
                ToolbarEntry(ToolbarItem.ERASER, lane = 1),
            ),
            lanes = 2,
        )))
        assertEquals(56.dp, metrics.button)
        assertEquals(68.dp, metrics.thickness)
        assertEquals(131.dp, toolbarDepth(metrics, layout))
        assertEquals(260.dp, toolbarWidth(metrics, layout, geometry))
        assertEquals(131.dp, toolbarHeight(metrics, layout, geometry))
        val sized = layout.setEntrySize(0, 0, 72)
        assertEquals(72.dp, entryBarMetrics(metrics, sized.sections[0].entries[0]).button)
        assertEquals(147.dp, toolbarDepth(metrics, sized))
    }
}
