package com.xnotes.ui

import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolbarToolGroup
import org.junit.Assert.assertEquals
import org.junit.Test

class PenStrokeIconsTest {
    @Test fun writingToolsUseTheSameDrawableInLauncherAndSettings() {
        val items = ToolbarToolGroup.defaultWritingGroup().items
        assertEquals(6, items.size)
        for (item in items) {
            val tool = requireNotNull(Tool.fromId(item.id))
            assertEquals(penStrokeIcon(tool), penStrokeIcon(item))
        }
        assertEquals(6, items.map(::penStrokeIcon).distinct().size)
    }
}
