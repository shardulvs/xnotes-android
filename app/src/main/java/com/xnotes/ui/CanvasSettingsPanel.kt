package com.xnotes.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.core.infinite.CanvasBackground
import com.xnotes.core.infinite.Waypoint
import com.xnotes.core.model.PagePattern
import com.xnotes.core.model.PageStyle
import com.xnotes.settings.SwatchRole
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlin.math.roundToInt

/*
 * The canvas bar's gear and its panel, built like the note's: zoom, Fit all and the toggles on the
 * front page, its background styles and its saved views a row away.
 */

private enum class CanvasPage(val depth: Int) { HOME(0), STYLES(1), WAYPOINTS(1) }

@Composable
internal fun CanvasSettingsButton(editor: InfiniteEditor, fullscreen: Boolean, onToggleFullscreen: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    SettingsButton(open, { open = it }) {
        var page by remember { mutableStateOf(CanvasPage.HOME) }
        PanelPages(page, { it.depth }) { p ->
            when (p) {
                CanvasPage.HOME -> CanvasHome(editor, fullscreen, onToggleFullscreen) { page = it }
                CanvasPage.STYLES -> CanvasStylesPage(editor) { page = CanvasPage.HOME }
                CanvasPage.WAYPOINTS -> WaypointsPage(editor, onJumped = { open = false }) { page = CanvasPage.HOME }
            }
        }
    }
}

@Composable
private fun CanvasHome(editor: InfiniteEditor, fullscreen: Boolean, onToggleFullscreen: () -> Unit, go: (CanvasPage) -> Unit) {
    PanelCard {
        PanelCaption(stringResource(R.string.title_zoom))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZoomStepper(
                editor.zoomPercent,
                enabled = !editor.zoomLocked,
                onOut = { editor.zoomBy(1.0 / InfiniteEditor.ZOOM_STEP) },
                onIn = { editor.zoomBy(InfiniteEditor.ZOOM_STEP) },
                modifier = Modifier.weight(1f),
            )
            ZoomLockButton(editor.zoomLocked) { editor.toggleZoomLock() }
        }
        Spacer(Modifier.height(8.dp))
        FitTile(stringResource(R.string.fit_all), XnotesIcons.fitAll, Modifier.fillMaxWidth(), enabled = !editor.zoomLocked, shortcut = Keys.FIT_ALL) { editor.zoomToFit() }
    }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QuickTile(XnotesIcons.fullscreen, stringResource(R.string.toolbar_fullscreen), fullscreenState(fullscreen), fullscreen, Modifier.weight(1f)) { onToggleFullscreen() }
        QuickTile(
            XnotesIcons.map,
            stringResource(R.string.toolbar_minimap),
            stringResource(if (editor.minimapVisible) R.string.on else R.string.off),
            editor.minimapVisible,
            Modifier.weight(1f),
        ) { editor.toggleMinimap() }
    }
    Spacer(Modifier.height(10.dp))
    PanelCard(padding = PaddingValues(0.dp)) {
        val bg = editor.document.background
        NavRow(stringResource(R.string.title_styles), stringResource(patternLabel(bg.pattern)), onClick = { go(CanvasPage.STYLES) }) {
            val paper = (bg.paperColor?.toComposeColor() ?: LocalPalette.current.paper.toComposeColor())
            val ink = bg.patternColor.toComposeColor()
            val edge = LocalPalette.current.border.toComposeColor()
            Canvas(Modifier.size(32.dp, 42.dp)) {
                drawRoundRect(paper, cornerRadius = CornerRadius(3.dp.toPx()))
                pattern(bg.pattern, ink, 6.dp.toPx())
                drawRoundRect(edge, cornerRadius = CornerRadius(3.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            }
        }
        CardDivider(60.dp)
        val count = editor.waypoints.size
        NavRow(
            stringResource(R.string.title_waypoints),
            if (count == 0) stringResource(R.string.none) else pluralStringResource(R.plurals.waypoints_saved, count, count),
            onClick = { go(CanvasPage.WAYPOINTS) },
        ) {
            Icon(XnotesIcons.bookmark, null, tint = LocalPalette.current.accent.toComposeColor(), modifier = Modifier.size(22.dp))
        }
    }
}

private fun patternLabel(p: PagePattern) = when (p) {
    PagePattern.NONE -> R.string.none
    PagePattern.LINES -> R.string.pattern_lines
    PagePattern.DOTS -> R.string.pattern_dots
    PagePattern.GRID -> R.string.pattern_grid
}

/** [p] over the whole canvas at a period of [step] px. */
private fun DrawScope.pattern(p: PagePattern, ink: Color, step: Float) {
    val w = size.width
    val h = size.height
    val line = 1f.coerceAtLeast(step / 8)
    clipRect {
        when (p) {
            PagePattern.NONE -> Unit
            PagePattern.LINES -> {
                var y = step
                while (y < h) { drawLine(ink, Offset(0f, y), Offset(w, y), line); y += step }
            }
            PagePattern.GRID -> {
                var y = step
                while (y < h) { drawLine(ink, Offset(0f, y), Offset(w, y), line); y += step }
                var x = step
                while (x < w) { drawLine(ink, Offset(x, 0f), Offset(x, h), line); x += step }
            }
            PagePattern.DOTS -> {
                var y = step
                while (y < h) {
                    var x = step
                    while (x < w) { drawCircle(ink, line * 1.1f, Offset(x, y)); x += step }
                    y += step
                }
            }
        }
    }
}

/**
 * The canvas background: pattern, spacing, pattern colour and paper. A canvas has no page level
 * under it, so every control sets a real value; only "Default for new canvases" reaches further,
 * stamping the current background onto every canvas made from then on.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CanvasStylesPage(editor: InfiniteEditor, onBack: () -> Unit) {
    var background by remember { mutableStateOf(editor.document.background) }
    // As on the note's Styles page: shows once the background differs from the saved default, then stays.
    var showNewCanvasRow by remember { mutableStateOf(editor.document.background != editor.newCanvasBackground) }
    fun apply(next: CanvasBackground) {
        background = next
        editor.setBackground(next)
        if (next != editor.newCanvasBackground) showNewCanvasRow = true
    }
    val patterned = background.pattern != PagePattern.NONE

    // A stock background hides the checkbox, so Reset is also how the saved default is forgotten.
    PanelHeader(stringResource(R.string.title_styles), onBack, onReset = { apply(CanvasBackground()); editor.saveNewCanvasBackground(null) })
    PanelCaption(stringResource(R.string.caption_pattern))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (p in PagePattern.entries) {
            PatternCard(p, background, p == background.pattern, Modifier.weight(1f)) { apply(background.copy(pattern = p)) }
        }
    }
    Spacer(Modifier.height(16.dp))
    val spacing = background.clampedSpacing.toFloat()
    PanelSlider(stringResource(R.string.caption_spacing), "${spacing.roundToInt()}", spacing, PageStyle.MIN_SPACING.toFloat()..PageStyle.MAX_SPACING.toFloat(), enabled = patterned) {
        apply(background.copy(spacing = it.toDouble()))
    }
    Spacer(Modifier.height(12.dp))
    PanelCaption(stringResource(R.string.caption_pattern_colour))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // The hue at full strength; opacity is its own control.
        SwatchCircles(editor.swatchStore, SwatchRole.LINES, background.patternColor.copy(a = 255)) {
            apply(background.copy(patternColor = it.copy(a = background.patternColor.a)))
        }
    }
    Spacer(Modifier.height(12.dp))
    val opacity = background.patternColor.a / 255f * 100f
    PanelSlider(stringResource(R.string.caption_opacity), "${opacity.roundToInt()}%", opacity, 5f..100f, enabled = patterned) { pct ->
        apply(background.copy(patternColor = background.patternColor.copy(a = (pct / 100f * 255f).roundToInt().coerceIn(0, 255))))
    }
    Spacer(Modifier.height(12.dp))
    PanelCaption(stringResource(R.string.caption_paper))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PanelChip(stringResource(R.string.paper_theme), background.paperColor == null) { apply(background.copy(paperColor = null)) }
        SwatchCircles(editor.swatchStore, SwatchRole.PAPER, background.paperColor) { apply(background.copy(paperColor = it)) }
    }
    if (showNewCanvasRow && background != CanvasBackground()) {
        Spacer(Modifier.height(6.dp))
        PanelCheck(stringResource(R.string.default_for_new_canvases), background == editor.newCanvasBackground) { on ->
            editor.saveNewCanvasBackground(if (on) background else null)
        }
    }
}

/** A pattern drawn on the canvas's paper, filled while chosen. */
@Composable
private fun PatternCard(p: PagePattern, bg: CanvasBackground, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val shape = MaterialTheme.shapes.medium
    val paper = bg.paperColor?.toComposeColor() ?: palette.paper.toComposeColor()
    // The swatch shows the ruling plainly, whatever opacity the canvas uses.
    val ink = bg.patternColor.copy(a = maxOf(bg.patternColor.a, 150)).toComposeColor()
    Column(
        modifier
            .height(84.dp)
            .clip(shape)
            .background(if (selected) palette.selectionBackground.toComposeColor() else panelColors().card)
            .border(1.5.dp, if (selected) palette.accent.toComposeColor() else Color.Transparent, shape)
            .clickable(role = Role.RadioButton, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val edge = palette.border.toComposeColor()
        Canvas(Modifier.size(44.dp, 30.dp)) {
            drawRoundRect(paper, cornerRadius = CornerRadius(3.dp.toPx()))
            pattern(p, ink, 6.dp.toPx())
            drawRoundRect(edge, cornerRadius = CornerRadius(3.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(patternLabel(p)),
            color = (if (selected) palette.selectionForeground else palette.text).toComposeColor(),
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/**
 * Saved views. An infinite canvas has no pages to navigate by, so a named viewport is what stands
 * in for a bookmark: save where you are, jump back to it later.
 */
@Composable
private fun WaypointsPage(editor: InfiniteEditor, onJumped: () -> Unit, onBack: () -> Unit) {
    val palette = LocalPalette.current
    var name by remember { mutableStateOf("") }
    // A save starts a fresh field, since a focused one keeps what it holds.
    var saves by remember { mutableIntStateOf(0) }
    fun save() {
        if (name.isBlank()) return
        editor.saveWaypoint(name)
        name = ""
        saves++
    }

    PanelHeader(stringResource(R.string.title_waypoints), onBack)
    if (editor.waypoints.isEmpty()) {
        Text(stringResource(R.string.no_waypoints), color = palette.textDim.toComposeColor(), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
    } else {
        PanelCard(padding = PaddingValues(vertical = 2.dp)) {
            editor.waypoints.forEachIndexed { i, w ->
                if (i > 0) CardDivider(46.dp)
                WaypointRow(w, onJump = { editor.jumpTo(w); onJumped() }) { editor.removeWaypoint(w) }
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    PanelCaption(stringResource(R.string.caption_save_view))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val shape = MaterialTheme.shapes.small
        Box(
            Modifier
                .weight(1f)
                .height(40.dp)
                .clip(shape)
                .background(palette.bg.toComposeColor())
                .border(1.dp, palette.border.toComposeColor(), shape)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            key(saves) {
                NativeField(value = name, onText = { name = it }, modifier = Modifier.fillMaxWidth(), maxLen = Waypoint.MAX_NAME_LENGTH, textSp = 14f, onDone = ::save)
            }
        }
        PanelChip(stringResource(R.string.save), enabled = name.isNotBlank(), onClick = ::save)
    }
}

@Composable
private fun WaypointRow(w: Waypoint, onJump: () -> Unit, onRemove: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().height(52.dp).clickable(role = Role.Button, onClick = onJump).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(XnotesIcons.bookmark, null, tint = palette.accent.toComposeColor(), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(w.name, color = palette.text.toComposeColor(), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text("${Math.round(w.zoom * 100)}%", color = palette.textDim.toComposeColor(), fontSize = 12.sp)
        val remove = stringResource(R.string.remove)
        Tooltip(remove) {
            Box(Modifier.size(40.dp).clip(MaterialTheme.shapes.small).clickable(role = Role.Button, onClick = onRemove), contentAlignment = Alignment.Center) {
                Icon(XnotesIcons.close, remove, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(16.dp))
            }
        }
    }
}
