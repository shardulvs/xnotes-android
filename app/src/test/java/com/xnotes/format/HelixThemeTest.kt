package com.xnotes.format

import com.xnotes.core.model.Rgba
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HelixThemeTest {

    private val sample = """
        # Author: someone

        comment = { fg = "comment", modifiers = ["italic"] }
        constant = { fg = "orange" }
        "constant.numeric" = { fg = "#ff9e64" }
        "constant.character.escape" = { fg = "magenta" }
        function = { fg = "blue", modifiers = ["italic"] }
        keyword = { fg = "purple" }
        string = "#9ece6a"
        type = { fg = "aqua" }
        "variable.other.member" = { fg = "green" }
        punctuation = { fg = "turquoise" }
        tag = "#f7768e"
        "diff.plus" = "#449dab"
        "diff.minus" = { fg = "#914c54" }

        "ui.background" = { bg = "#1a1b26" }
        "ui.text" = { fg = "fg" }

        [palette]
        comment = "#565f89"
        orange = "#ff9e64"
        magenta = "#bb9af7"
        blue = "#7aa2f7"
        purple = "#9d7cd8"
        aqua = "#2ac3de"
        green = "#73daca"
        turquoise = "#89ddff"
        fg = "#c0caf5"
    """.trimIndent()

    @Test
    fun parsesScopesPaletteAndBackground() {
        val theme = HelixTheme.parse(sample)!!
        assertEquals(Rgba(0x9d, 0x7c, 0xd8, 255), theme.colorFor("keyword"))
        assertEquals(Rgba(0x9d, 0x7c, 0xd8, 255), theme.colorFor("keyword.return"))
        assertEquals(Rgba(0x9e, 0xce, 0x6a, 255), theme.colorFor("string"))
        assertEquals(Rgba(0xff, 0x9e, 0x64, 255), theme.colorFor("number"))
        assertEquals(Rgba(0xbb, 0x9a, 0xf7, 255), theme.colorFor("escape"))
        assertEquals(Rgba(0x73, 0xda, 0xca, 255), theme.colorFor("property"))
        assertEquals(Rgba(0x56, 0x5f, 0x89, 255), theme.colorFor("comment"))
        assertEquals(Rgba(0xf7, 0x76, 0x8e, 255), theme.colorFor("tag"))
        assertEquals(Rgba(0x44, 0x9d, 0xab, 255), theme.colorFor("diff.plus"))
        assertEquals(Rgba(0x91, 0x4c, 0x54, 255), theme.colorFor("diff.minus"))
        assertEquals(Rgba(0x1a, 0x1b, 0x26, 255), theme.background)
    }

    @Test
    fun subScopeFeedsAMissingParentSlot() {
        val theme = HelixTheme.parse(
            """
            "keyword.control" = { fg = "#111111" }
            string = "#222222"
            comment = "#333333"
            function = "#444444"
            """.trimIndent(),
        )!!
        assertEquals(Rgba(0x11, 0x11, 0x11, 255), theme.colorFor("keyword"))
    }

    @Test
    fun rejectsNonThemesAndShortHexSurvives() {
        assertNull(HelixTheme.parse("not a theme at all"))
        assertNull(HelixTheme.parse("keyword = \"#123456\""))
        val theme = HelixTheme.parse(
            """
            keyword = "#f00"
            string = "#0f0"
            comment = "#00f"
            function = "#fff"
            """.trimIndent(),
        )!!
        assertEquals(Rgba(255, 0, 0, 255), theme.colorFor("keyword"))
        assertNull(theme.background)
    }
}
