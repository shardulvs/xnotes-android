package com.xnotes.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.xnotes.R
import com.xnotes.core.tools.ToolbarItem
import com.xnotes.ui.icons.XnotesIcons

/** Toolbar imagery shared by the editor and its customizer. Keep icon semantics in one place. */
@Composable
internal fun toolbarItemIcon(item: ToolbarItem): ImageVector = when (item) {
    ToolbarItem.HOME -> XnotesIcons.home
    ToolbarItem.TITLE -> XnotesIcons.edit
    ToolbarItem.SIDEBAR -> XnotesIcons.sidebar
    ToolbarItem.PEN -> ImageVector.vectorResource(R.drawable.ic_stroke_regular)
    ToolbarItem.DASHED -> ImageVector.vectorResource(R.drawable.ic_stroke_dashed)
    ToolbarItem.CALLIGRAPHY -> ImageVector.vectorResource(R.drawable.ic_stroke_calligraphy)
    ToolbarItem.SPEED -> ImageVector.vectorResource(R.drawable.ic_stroke_speed)
    ToolbarItem.TAPER -> ImageVector.vectorResource(R.drawable.ic_stroke_taper)
    ToolbarItem.HIGHLIGHTER -> ImageVector.vectorResource(R.drawable.ic_stroke_highlighter)
    ToolbarItem.ERASER -> XnotesIcons.eraser
    ToolbarItem.PAN -> XnotesIcons.pan
    ToolbarItem.SELECT -> XnotesIcons.select
    ToolbarItem.LASSO -> XnotesIcons.lasso
    ToolbarItem.INSERT_SPACE -> XnotesIcons.insertSpace
    ToolbarItem.SCREENSHOT -> XnotesIcons.scissors
    ToolbarItem.WAND -> XnotesIcons.magicWand
    ToolbarItem.TAPE -> XnotesIcons.tape
    ToolbarItem.SHAPE -> XnotesIcons.shape
    ToolbarItem.RULER -> XnotesIcons.ruler
    ToolbarItem.TEXT -> XnotesIcons.text
    ToolbarItem.TEXT_BOX -> XnotesIcons.textBox
    ToolbarItem.MARKUP -> XnotesIcons.markup
    ToolbarItem.IMAGE -> XnotesIcons.paperclip
    ToolbarItem.UNDO -> XnotesIcons.undo
    ToolbarItem.REDO -> XnotesIcons.redo
    ToolbarItem.PAGE_NAV -> XnotesIcons.prev
    ToolbarItem.STYLES -> XnotesIcons.sliders
    ToolbarItem.MARGINS -> XnotesIcons.margins
    ToolbarItem.VIEW -> XnotesIcons.view
    ToolbarItem.ZOOM -> XnotesIcons.zoomIn
    ToolbarItem.FIT -> XnotesIcons.fit
    ToolbarItem.ZOOM_LOCK -> XnotesIcons.lock
    ToolbarItem.FULLSCREEN -> XnotesIcons.fullscreen
    ToolbarItem.COLORS -> XnotesIcons.more
    ToolbarItem.WAYPOINTS -> XnotesIcons.bookmark
    ToolbarItem.MINIMAP -> XnotesIcons.map
}
