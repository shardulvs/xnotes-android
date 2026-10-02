// The LaTeX library keeps its layout and drawing Kotlin-internal; this file alone reaches past that.
@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.xnotes.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import com.hrm.latex.parser.IncrementalLatexParser
import com.hrm.latex.renderer.layout.LatexRenderResult
import com.hrm.latex.renderer.layout.LatexRenderer
import com.hrm.latex.renderer.model.LatexConfig
import com.hrm.latex.renderer.model.LatexFontFamilies
import com.hrm.latex.renderer.model.defaultLatexFontFamilies
import com.hrm.latex.renderer.model.toContext

/**
 * The few calls that set a formula as vectors rather than as the bitmap the library's public
 * exporter returns. The library marks them internal, so they are kept to this one file, where a
 * library update that moves them fails the build here rather than somewhere far away.
 */
object LatexInternals {

    /** The library's default fonts, read through the composition exactly as its own composables do. */
    @Composable
    fun defaultFonts(): LatexFontFamilies = defaultLatexFontFamilies()

    /** [latex] laid out, as a handle for the calls below; null when it sets nothing. */
    internal fun layout(latex: String, config: LatexConfig, fonts: LatexFontFamilies, text: TextMeasurer, density: Density): Any? {
        val nodes = IncrementalLatexParser().apply { append(latex) }.getCurrentDocument().children
        if (nodes.isEmpty()) return null
        val r = LatexRenderer.measure(nodes, config.toContext(false, fonts), text, density)
        return r.takeIf { it.layout.width > 0f && it.layout.height > 0f }
    }

    internal fun width(layout: Any): Float = (layout as LatexRenderResult).canvasWidth

    internal fun height(layout: Any): Float = (layout as LatexRenderResult).canvasHeight

    /** Draw [layout] into [scope] as the library's renderer does over a transparent background. */
    internal fun draw(scope: DrawScope, layout: Any) {
        val r = layout as LatexRenderResult
        r.layout.draw(scope, r.horizontalPadding, r.verticalPadding)
    }
}
