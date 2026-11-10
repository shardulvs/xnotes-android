package com.xnotes.settings

import com.xnotes.core.tools.ToolbarAnchor
import com.xnotes.core.tools.ToolbarBar
import com.xnotes.core.tools.ToolbarDistribution
import com.xnotes.core.tools.ToolbarEntry
import com.xnotes.core.tools.ToolbarGeometry
import com.xnotes.core.tools.ToolbarItem
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.core.tools.ToolbarRegion
import com.xnotes.core.tools.ToolbarScale
import com.xnotes.core.tools.ToolbarSection
import com.xnotes.core.tools.ToolbarWorkspace
import com.xnotes.core.tools.ToolbarWorkspaces

/** Ready-to-use XNotes designs, organised by workflow instead of third-party brands. */
object ToolbarWorkspaceTemplates {
    private fun layout(vararg items: ToolbarItem) =
        ToolbarLayout(listOf(ToolbarSection(items.map { ToolbarEntry(it) })))

    private fun twoRows(top: List<ToolbarItem>, bottom: List<ToolbarItem>) =
        ToolbarLayout(listOf(ToolbarSection(
            entries = top.map { ToolbarEntry(it, lane = 0) } +
                bottom.map { ToolbarEntry(it, lane = 1) },
            lanes = 2,
        )))

    private fun regions(
        start: List<ToolbarItem>,
        center: List<ToolbarItem>,
        end: List<ToolbarItem>,
    ) = ToolbarLayout(listOf(
        ToolbarSection(start.map { ToolbarEntry(it) }, region = ToolbarRegion.START),
        ToolbarSection(center.map { ToolbarEntry(it) }, region = ToolbarRegion.CENTER),
        ToolbarSection(end.map { ToolbarEntry(it) }, region = ToolbarRegion.END),
    ), distribution = ToolbarDistribution.REGIONS)

    private fun bar(
        id: String,
        name: String,
        layout: ToolbarLayout,
        anchor: ToolbarAnchor = ToolbarAnchor.TOP_CENTER,
        floating: Boolean = false,
        scale: ToolbarScale = ToolbarScale.REGULAR,
        columns: Int = 1,
    ) = ToolbarBar(id, name, layout, geometry = ToolbarGeometry(
        anchor = anchor, floating = floating, scale = scale, columns = columns,
    ))
    private fun pair(
        id: String,
        name: String,
        look: ToolbarLook,
        note: ToolbarWorkspace,
        canvas: ToolbarWorkspace,
    ) = ToolbarWorkspacePreset(id, name, ToolbarWorkspaces(note, canvas), look)

    val presets: List<ToolbarWorkspacePreset> = listOf(
        pair(
            "builtin-original", "Original", ToolbarLook(),
            ToolbarWorkspace.fromLegacy("note-primary", "Main toolbar", ToolbarLayout.DEFAULT),
            ToolbarWorkspace.fromLegacy("canvas-primary", "Main toolbar", ToolbarLayout.CANVAS_DEFAULT),
        ),
        pair(
            "builtin-focus", "Escrita sem distrações",
            ToolbarLook(position = ToolbarPosition.BOTTOM, size = ToolbarSize.COMPACT, floating = true),
            ToolbarWorkspace(listOf(bar("note-primary", "Escrita", layout(
                ToolbarItem.PEN, ToolbarItem.HIGHLIGHTER, ToolbarItem.ERASER, ToolbarItem.LASSO,
                ToolbarItem.COLORS, ToolbarItem.UNDO, ToolbarItem.REDO,
            ), anchor = ToolbarAnchor.BOTTOM_CENTER, floating = true, scale = ToolbarScale.COMPACT))),
            ToolbarWorkspace(listOf(bar("canvas-primary", "Escrita", layout(
                ToolbarItem.PEN, ToolbarItem.HIGHLIGHTER, ToolbarItem.ERASER, ToolbarItem.LASSO,
                ToolbarItem.COLORS, ToolbarItem.UNDO, ToolbarItem.REDO,
            ), anchor = ToolbarAnchor.BOTTOM_CENTER, floating = true, scale = ToolbarScale.COMPACT))),
        ),
        pair(
            "builtin-study", "Caderno de estudos",
            ToolbarLook(position = ToolbarPosition.TOP, size = ToolbarSize.REGULAR),
            ToolbarWorkspace(listOf(
                bar("note-primary", "Documento", layout(
                    ToolbarItem.HOME, ToolbarItem.TITLE, ToolbarItem.SIDEBAR, ToolbarItem.PAGE_NAV,
                    ToolbarItem.UNDO, ToolbarItem.REDO, ToolbarItem.VIEW,
                )),
                bar("writing", "Anotação", layout(
                    ToolbarItem.PEN, ToolbarItem.DASHED, ToolbarItem.HIGHLIGHTER,
                    ToolbarItem.ERASER, ToolbarItem.LASSO, ToolbarItem.COLORS,
                ), anchor = ToolbarAnchor.BOTTOM_CENTER, floating = true, scale = ToolbarScale.COMPACT),
            )),
            ToolbarWorkspace(listOf(
                bar("canvas-primary", "Documento", layout(
                    ToolbarItem.HOME, ToolbarItem.TITLE, ToolbarItem.WAYPOINTS,
                    ToolbarItem.MINIMAP, ToolbarItem.UNDO, ToolbarItem.REDO,
                )),
                bar("writing", "Anotação", layout(
                    ToolbarItem.PEN, ToolbarItem.DASHED, ToolbarItem.HIGHLIGHTER,
                    ToolbarItem.ERASER, ToolbarItem.LASSO, ToolbarItem.COLORS,
                ), anchor = ToolbarAnchor.BOTTOM_CENTER, floating = true, scale = ToolbarScale.COMPACT),
            )),
        ),
        pair(
            "builtin-pdf", "Revisão de documentos",
            ToolbarLook(position = ToolbarPosition.TOP, size = ToolbarSize.COMPACT),
            ToolbarWorkspace(listOf(
                bar("note-primary", "Navegação", layout(
                    ToolbarItem.HOME, ToolbarItem.TITLE, ToolbarItem.PAGE_NAV,
                    ToolbarItem.UNDO, ToolbarItem.REDO, ToolbarItem.FIT,
                )),
                bar("annotation", "Anotação", layout(
                    ToolbarItem.PEN, ToolbarItem.HIGHLIGHTER, ToolbarItem.ERASER,
                    ToolbarItem.LASSO, ToolbarItem.MARKUP, ToolbarItem.TEXT_BOX,
                    ToolbarItem.COLORS,
                ), anchor = ToolbarAnchor.CENTER_RIGHT, floating = true, scale = ToolbarScale.COMPACT),
            )),
            ToolbarWorkspace(listOf(
                bar("canvas-primary", "Navegação", layout(
                    ToolbarItem.HOME, ToolbarItem.TITLE, ToolbarItem.WAYPOINTS,
                    ToolbarItem.MINIMAP, ToolbarItem.UNDO, ToolbarItem.REDO, ToolbarItem.FIT,
                )),
                bar("annotation", "Anotação", layout(
                    ToolbarItem.PEN, ToolbarItem.HIGHLIGHTER, ToolbarItem.ERASER,
                    ToolbarItem.LASSO, ToolbarItem.WAND, ToolbarItem.IMAGE, ToolbarItem.COLORS,
                ), anchor = ToolbarAnchor.CENTER_RIGHT, floating = true, scale = ToolbarScale.COMPACT),
            )),
        ),
        pair(
            "builtin-drawing", "Desenho livre",
            ToolbarLook(position = ToolbarPosition.TOP, size = ToolbarSize.COMPACT, floating = true),
            ToolbarWorkspace(listOf(
                bar("note-primary", "Traços", twoRows(
                    listOf(ToolbarItem.PEN, ToolbarItem.DASHED, ToolbarItem.CALLIGRAPHY, ToolbarItem.HIGHLIGHTER),
                    listOf(ToolbarItem.ERASER, ToolbarItem.LASSO, ToolbarItem.SHAPE, ToolbarItem.RULER),
                ), anchor = ToolbarAnchor.CENTER_LEFT, floating = true, scale = ToolbarScale.COMPACT),
                bar("palette", "Cores", layout(ToolbarItem.COLORS, ToolbarItem.STYLES),
                    anchor = ToolbarAnchor.BOTTOM_CENTER, floating = true, scale = ToolbarScale.COMPACT),
                bar("navigation", "Ações", layout(ToolbarItem.HOME, ToolbarItem.UNDO, ToolbarItem.REDO),
                    anchor = ToolbarAnchor.TOP_RIGHT, floating = true, scale = ToolbarScale.COMPACT),
            )),
            ToolbarWorkspace(listOf(
                bar("canvas-primary", "Traços", twoRows(
                    listOf(ToolbarItem.PEN, ToolbarItem.DASHED, ToolbarItem.CALLIGRAPHY, ToolbarItem.HIGHLIGHTER),
                    listOf(ToolbarItem.ERASER, ToolbarItem.LASSO, ToolbarItem.SHAPE, ToolbarItem.WAND),
                ), anchor = ToolbarAnchor.CENTER_LEFT, floating = true, scale = ToolbarScale.COMPACT),
                bar("palette", "Cores", layout(ToolbarItem.COLORS, ToolbarItem.STYLES),
                    anchor = ToolbarAnchor.BOTTOM_CENTER, floating = true, scale = ToolbarScale.COMPACT),
                bar("navigation", "Ações", layout(ToolbarItem.HOME, ToolbarItem.UNDO, ToolbarItem.REDO),
                    anchor = ToolbarAnchor.TOP_RIGHT, floating = true, scale = ToolbarScale.COMPACT),
            )),
        ),
        pair(
            "builtin-markup", "Marcação rápida",
            ToolbarLook(position = ToolbarPosition.BOTTOM, size = ToolbarSize.COMPACT, floating = true),
            ToolbarWorkspace(listOf(
                bar("note-primary", "Marcação", layout(
                    ToolbarItem.PEN, ToolbarItem.DASHED, ToolbarItem.HIGHLIGHTER,
                    ToolbarItem.ERASER, ToolbarItem.LASSO, ToolbarItem.RULER,
                    ToolbarItem.SHAPE, ToolbarItem.COLORS,
                ), anchor = ToolbarAnchor.BOTTOM_CENTER, floating = true, scale = ToolbarScale.COMPACT),
                bar("document", "Documento", layout(
                    ToolbarItem.HOME, ToolbarItem.TITLE, ToolbarItem.UNDO, ToolbarItem.REDO,
                ), anchor = ToolbarAnchor.TOP_LEFT, floating = true, scale = ToolbarScale.COMPACT),
            )),
            ToolbarWorkspace(listOf(
                bar("canvas-primary", "Marcação", layout(
                    ToolbarItem.PEN, ToolbarItem.DASHED, ToolbarItem.HIGHLIGHTER,
                    ToolbarItem.ERASER, ToolbarItem.LASSO, ToolbarItem.SHAPE, ToolbarItem.COLORS,
                ), anchor = ToolbarAnchor.BOTTOM_CENTER, floating = true, scale = ToolbarScale.COMPACT),
                bar("document", "Documento", layout(
                    ToolbarItem.HOME, ToolbarItem.TITLE, ToolbarItem.UNDO, ToolbarItem.REDO,
                ), anchor = ToolbarAnchor.TOP_LEFT, floating = true, scale = ToolbarScale.COMPACT),
            )),
        ),
        pair(
            "builtin-complete", "Barra completa",
            ToolbarLook(position = ToolbarPosition.TOP, size = ToolbarSize.REGULAR),
            ToolbarWorkspace(listOf(bar("note-primary", "Organização", regions(
                start = listOf(ToolbarItem.HOME, ToolbarItem.TITLE, ToolbarItem.UNDO, ToolbarItem.REDO),
                center = listOf(ToolbarItem.PEN, ToolbarItem.HIGHLIGHTER, ToolbarItem.ERASER,
                    ToolbarItem.LASSO, ToolbarItem.SHAPE, ToolbarItem.TEXT_BOX, ToolbarItem.IMAGE,
                    ToolbarItem.COLORS),
                end = listOf(ToolbarItem.PAGE_NAV),
            )))),
            ToolbarWorkspace(listOf(bar("canvas-primary", "Organização", regions(
                start = listOf(ToolbarItem.HOME, ToolbarItem.TITLE, ToolbarItem.UNDO, ToolbarItem.REDO),
                center = listOf(ToolbarItem.PEN, ToolbarItem.HIGHLIGHTER, ToolbarItem.ERASER,
                    ToolbarItem.LASSO, ToolbarItem.SHAPE, ToolbarItem.IMAGE, ToolbarItem.COLORS),
                end = listOf(ToolbarItem.WAYPOINTS),
            )))),
        ),
        pair(
            "builtin-two-rows", "Duas linhas",
            ToolbarLook(position = ToolbarPosition.BOTTOM, size = ToolbarSize.REGULAR, floating = true),
            ToolbarWorkspace(listOf(
                bar("note-primary", "Ferramentas", twoRows(
                    listOf(ToolbarItem.PEN, ToolbarItem.DASHED, ToolbarItem.HIGHLIGHTER, ToolbarItem.ERASER),
                    listOf(ToolbarItem.LASSO, ToolbarItem.SHAPE, ToolbarItem.IMAGE, ToolbarItem.COLORS),
                ), anchor = ToolbarAnchor.BOTTOM_CENTER, floating = true),
                bar("navigation", "Navegação", layout(
                    ToolbarItem.HOME, ToolbarItem.TITLE, ToolbarItem.PAGE_NAV,
                    ToolbarItem.UNDO, ToolbarItem.REDO,
                ), anchor = ToolbarAnchor.TOP_CENTER, floating = true, scale = ToolbarScale.COMPACT),
            )),
            ToolbarWorkspace(listOf(
                bar("canvas-primary", "Ferramentas", twoRows(
                    listOf(ToolbarItem.PEN, ToolbarItem.DASHED, ToolbarItem.HIGHLIGHTER, ToolbarItem.ERASER),
                    listOf(ToolbarItem.LASSO, ToolbarItem.SHAPE, ToolbarItem.IMAGE, ToolbarItem.COLORS),
                ), anchor = ToolbarAnchor.BOTTOM_CENTER, floating = true),
                bar("navigation", "Navegação", layout(
                    ToolbarItem.HOME, ToolbarItem.TITLE, ToolbarItem.WAYPOINTS,
                    ToolbarItem.UNDO, ToolbarItem.REDO,
                ), anchor = ToolbarAnchor.TOP_CENTER, floating = true, scale = ToolbarScale.COMPACT),
            )),
        ),
    )
}
