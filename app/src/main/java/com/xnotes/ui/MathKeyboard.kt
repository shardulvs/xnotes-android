package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hrm.latex.renderer.Latex
import com.hrm.latex.renderer.model.LatexConfig
import com.hrm.latex.renderer.model.LatexTheme
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/**
 * One key of the maths keyboard: [label] is LaTeX drawn on the key, [insert] the source put in, in
 * which `#` marks where the cursor lands (and where a selection is wrapped).
 */
internal class MathKey(val label: String, val insert: String)

private fun k(insert: String, label: String = insert.replace("#", "\\square").replace("{}", "{\\square}")) = MathKey(label, insert)

/** Digits and the everyday operators, the first page a phone shows. */
private val MATH_NUMBER_KEYS =
    listOf("7", "8", "9", "\\div", "4", "5", "6", "\\cdot", "1", "2", "3", "-", "0", ".", "=", "+", "x", "y", "(", ")").map { k(it) }

/** Structures, calculus, functions and symbols, in that order; the pager fills each page before the next. */
internal val MATH_FUNCTION_KEYS = listOf(
    listOf(
        k("\\frac{#}{}"), k("^{2}", "\\square^{2}"), k("^{#}", "\\square^{\\square}"), k("_{#}", "\\square_{\\square}"),
        k("\\sqrt{#}"), k("\\sqrt[3]{#}"), k("\\sqrt[#]{}", "\\sqrt[\\square]{\\square}"),
        k("\\left(#\\right)", "(\\square)"), k("\\left|#\\right|", "|\\square|"), k("\\left(#\\right)'", "(\\square)'"),
    ),
    listOf(
        k("\\frac{d}{dx}#", "\\frac{d}{dx}"), k("\\frac{d}{d#}", "\\frac{d}{d\\square}"), k("\\frac{d^{2}}{dx^{2}}#", "\\frac{d^2}{dx^2}"),
        k("\\int # \\, dx", "\\int \\square\\,dx"), k("\\int_{#}^{} \\, dx", "\\int_{\\square}^{\\square}"),
        k("\\sum_{#}^{}", "\\sum_{\\square}^{\\square}"), k("\\lim_{# \\to }", "\\lim_{\\square}"), k("\\infty"), k("\\to"),
    ),
    listOf(
        k("\\sin #", "\\sin"), k("\\cos #", "\\cos"), k("\\tan #", "\\tan"), k("\\cot #", "\\cot"),
        k("\\ln #", "\\ln"), k("\\log_{#}", "\\log_{\\square}"), k("e^{#}", "e^{\\square}"), k("\\pi"), k("e"),
    ),
    listOf(
        "\\neq", "<", ">", "\\le", "\\ge", "\\pm", "\\approx", "\\alpha", "\\beta", "\\gamma", "\\theta",
        "\\lambda", "\\mu", "\\sigma", "\\varphi", "\\omega", "\\Delta",
    ).map { k(it) },
).flatten()

/**
 * What a key puts in after [before], wrapping [selected] at the template's `#`, and where the cursor
 * lands within it. A letter right after a command would run into its name (\pi then e must not
 * read \pie), so it is spaced off.
 */
internal fun latexInsertion(before: String, selected: String, template: String): Pair<String, Int> {
    val mark = template.indexOf('#').takeIf { it >= 0 } ?: template.length
    val body = template.replace("#", "")
    val afterCommand = Regex("\\\\[a-zA-Z]+$").containsMatchIn(before)
    val spaced = if (body.firstOrNull()?.isLetter() == true && afterCommand) " " else ""
    return (spaced + body.substring(0, mark) + selected + body.substring(mark)) to (spaced.length + mark + selected.length)
}

/** Key sizes: bigger keys on a tablet, where the keyboard has the room. */
internal class MathKeySize(val minW: Dp, val minH: Dp, val font: TextUnit, val gap: Dp) {
    companion object {
        val NORMAL = MathKeySize(52.dp, 44.dp, 15.sp, 4.dp)
        val LARGE = MathKeySize(68.dp, 58.dp, 19.sp, 6.dp)
    }
}

private val LocalMathKeySize = staticCompositionLocalOf { MathKeySize.NORMAL }

/** LaTeX in the palette's text colour with nothing painted behind it, so it sits on any surface. */
@Composable
internal fun latexTheme(): LatexTheme = LatexTheme.light(LocalPalette.current.text.toComposeColor(), Color.Transparent)

/** A maths key with its LaTeX label, at least the key size, growing to fit what it shows. */
@Composable
internal fun MathKeyButton(key: MathKey, onKey: (MathKey) -> Unit, modifier: Modifier = Modifier) {
    val config = LatexConfig(fontSize = LocalMathKeySize.current.font, theme = latexTheme())
    KeyFrame(onClick = { onKey(key) }, modifier = modifier) { Latex(latex = key.label, config = config) }
}

/** A plain-text key (ABC, backspace, arrows) matching the maths keys. */
@Composable
internal fun TextKey(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    KeyFrame(onClick = onClick, modifier = modifier, raised = true) {
        Text(label, color = palette.text.toComposeColor(), fontSize = LocalMathKeySize.current.font * 0.8f, maxLines = 1, softWrap = false)
    }
}

/** A key in the app's chip style: the surface with a hairline border, the modifier keys a step raised. */
@Composable
private fun KeyFrame(onClick: () -> Unit, modifier: Modifier, raised: Boolean = false, content: @Composable () -> Unit) {
    val palette = LocalPalette.current
    val size = LocalMathKeySize.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .defaultMinSize(minWidth = size.minW, minHeight = size.minH)
            .clip(MaterialTheme.shapes.small)
            .background((if (raised) palette.surfaceHi else palette.surface).toComposeColor())
            .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) { content() }
}

/**
 * [keys] in a grid that fills its box: as many columns and rows as the key size allows, each page
 * full before the next begins, swiped sideways, with a dot per page underneath.
 */
@Composable
internal fun MathKeyPager(keys: List<MathKey>, onKey: (MathKey) -> Unit, modifier: Modifier = Modifier) {
    val size = LocalMathKeySize.current
    BoxWithConstraints(modifier) {
        val dots = 18.dp
        val cols = ((maxWidth + size.gap) / (size.minW + size.gap)).toInt().coerceAtLeast(1)
        val rows = if (constraints.hasBoundedHeight) ((maxHeight - dots + size.gap) / (size.minH + size.gap)).toInt().coerceAtLeast(1) else 3
        val pages = keys.chunked(cols * rows)
        val pager = rememberPagerState { pages.size }
        Column {
            HorizontalPager(pager, verticalAlignment = Alignment.Top) { page ->
                Column(verticalArrangement = Arrangement.spacedBy(size.gap)) {
                    pages[page].chunked(cols).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(size.gap)) {
                            row.forEach { MathKeyButton(it, onKey, Modifier.weight(1f)) }
                            repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            if (pages.size > 1) PageDots(pager.pageCount, pager.currentPage, Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
internal fun PageDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        repeat(count) { i ->
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background((if (current == i) palette.accent else palette.border).toComposeColor()),
            )
        }
    }
}

/** The number block as a fixed four-column grid, so the digits sit where a keypad has them. */
@Composable
private fun NumberGrid(onKey: (MathKey) -> Unit, modifier: Modifier = Modifier) {
    val gap = LocalMathKeySize.current.gap
    Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        MATH_NUMBER_KEYS.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEach { MathKeyButton(it, onKey, Modifier.weight(1f)) }
            }
        }
    }
}

/** What the maths keyboard's keys do to whatever it types into. */
internal class MathKeyActions(
    val onKey: (String) -> Unit,
    val onAbc: () -> Unit,
    val onMove: (Int) -> Unit,
    val onDelete: () -> Unit,
    val onEnter: () -> Unit,
)

/**
 * The maths keyboard: on a wide screen the number block beside the function pages with bigger keys;
 * on a phone one or the other, swapped by a key as a phone keyboard swaps letters for symbols.
 */
@Composable
internal fun MathKeys(actions: MathKeyActions, modifier: Modifier = Modifier) {
    val wide = LocalConfiguration.current.screenWidthDp >= COMPACT_WIDTH_DP
    val size = if (wide) MathKeySize.LARGE else MathKeySize.NORMAL
    var functions by remember { mutableStateOf(false) }
    val onKey: (MathKey) -> Unit = { actions.onKey(it.insert) }
    CompositionLocalProvider(LocalMathKeySize provides size) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(size.gap)) {
            // Tall enough for the number block's five rows, so switching pages never jumps.
            Box(Modifier.fillMaxWidth().height(size.minH * 5 + size.gap * 4 + 4.dp)) {
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        NumberGrid(onKey, Modifier.weight(0.38f))
                        MathKeyPager(MATH_FUNCTION_KEYS, onKey, Modifier.weight(0.62f).fillMaxHeight())
                    }
                } else if (functions) {
                    MathKeyPager(MATH_FUNCTION_KEYS, onKey, Modifier.fillMaxSize())
                } else {
                    NumberGrid(onKey)
                }
            }
            // Every key shares the row by weight, space the widest, so a narrow phone never squeezes one.
            Row(horizontalArrangement = Arrangement.spacedBy(size.gap)) {
                TextKey("ABC", actions.onAbc, Modifier.weight(1f))
                if (!wide) TextKey(if (functions) "123" else "f(x)", { functions = !functions }, Modifier.weight(1f))
                TextKey("<", { actions.onMove(-1) }, Modifier.weight(0.8f))
                TextKey(">", { actions.onMove(1) }, Modifier.weight(0.8f))
                TextKey("space", { actions.onKey(" ") }, Modifier.weight(2f))
                TextKey("Del", actions.onDelete, Modifier.weight(1f))
                TextKey("Enter", actions.onEnter, Modifier.weight(1.2f))
            }
        }
    }
}

/** The maths keyboard docked in place of the soft keyboard while a formula is typed in flowing text. */
@Composable
fun MathKeyboardPanel(editor: Editor) {
    if (!editor.mathKeyboardShown || !editor.flowEditingActive) return
    val palette = LocalPalette.current
    MathKeys(
        MathKeyActions(
            onKey = { editor.flowMathKey(it) },
            onAbc = { editor.setMathKeyboard(false) },
            onMove = { editor.flowMathMove(it) },
            onDelete = { editor.flowMathBackspace() },
            onEnter = { editor.flowMathEnter() },
        ),
        Modifier
            .fillMaxWidth()
            .background(palette.panel.toComposeColor())
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}
