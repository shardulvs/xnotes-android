package com.xnotes.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarLayoutTest {

    private fun sec(vararg items: ToolbarItem) = ToolbarSection(items.map { ToolbarEntry(it) })
    private fun ToolbarLayout.toRaw(): List<List<Pair<String, Boolean>>> =
        sections.map { s -> s.entries.map { it.item.id to it.visible } }
    private fun ToolbarLayout.items() = sections.flatMap { it.entries }.map { it.item }

    @Test fun defaultMirrorsToolbarGrouping() {
        val d = ToolbarLayout.DEFAULT
        assertEquals(11, d.sections.size)
        assertEquals(listOf(ToolbarItem.HOME, ToolbarItem.TITLE), d.sections[0].entries.map { it.item })
        assertEquals(
            listOf(
                ToolbarItem.PEN, ToolbarItem.DASHED, ToolbarItem.CALLIGRAPHY, ToolbarItem.SPEED,
                ToolbarItem.TAPER, ToolbarItem.HIGHLIGHTER, ToolbarItem.ERASER,
            ),
            d.sections[2].entries.map { it.item },
        )
        assertEquals(
            listOf(ToolbarItem.PAN, ToolbarItem.SELECT, ToolbarItem.LASSO, ToolbarItem.SCREENSHOT),
            d.sections[3].entries.map { it.item },
        )
        assertEquals(
            listOf(ToolbarItem.WAND, ToolbarItem.SHAPE, ToolbarItem.RULER, ToolbarItem.TEXT, ToolbarItem.TEXT_BOX, ToolbarItem.MARKUP),
            d.sections[4].entries.map { it.item },
        )
        assertEquals(listOf(ToolbarItem.FULLSCREEN), d.sections[10].entries.map { it.item })
    }

    @Test fun defaultContainsEveryItemOnce() {
        val items = ToolbarLayout.DEFAULT.items()
        assertEquals(ToolbarLayout.NOTE_ITEMS, items.toSet())
        assertEquals(ToolbarLayout.NOTE_ITEMS.size, items.size)
    }

    @Test fun defaultRoundTripsThroughRaw() {
        assertEquals(ToolbarLayout.DEFAULT, ToolbarLayout.fromRaw(ToolbarLayout.DEFAULT.toRaw()))
    }

    @Test fun emptyRawYieldsDefault() {
        assertEquals(ToolbarLayout.DEFAULT, ToolbarLayout.fromRaw(emptyList()))
        assertEquals(ToolbarLayout.DEFAULT, ToolbarLayout.fromRaw(listOf(emptyList(), emptyList())))
    }

    @Test fun unknownIdsDropped() {
        val raw = ToolbarLayout.DEFAULT.toRaw().toMutableList()
        raw[0] = listOf("frobnicate" to true) + raw[0]
        val back = ToolbarLayout.fromRaw(raw)
        assertEquals(ToolbarLayout.NOTE_ITEMS.size, back.items().size)
        assertEquals(ToolbarLayout.NOTE_ITEMS, back.items().toSet())
    }

    @Test fun duplicateIdsKeepFirst() {
        val raw = ToolbarLayout.DEFAULT.toRaw().toMutableList()
        raw[1] = raw[1] + ("pen" to true)
        val back = ToolbarLayout.fromRaw(raw)
        assertEquals(1, back.items().count { it == ToolbarItem.PEN })
    }

    @Test fun missingItemsAppendedToLastSectionVisible() {
        val raw = ToolbarLayout.DEFAULT.toRaw()
            .map { s -> s.filterNot { it.first == ToolbarItem.FULLSCREEN.id || it.first == ToolbarItem.STYLES.id } }
        val back = ToolbarLayout.fromRaw(raw)
        assertEquals(ToolbarLayout.NOTE_ITEMS, back.items().toSet())
        val last = back.sections.last().entries
        assertTrue(last.any { it.item == ToolbarItem.FULLSCREEN && it.visible })
        assertTrue(last.any { it.item == ToolbarItem.STYLES && it.visible })
    }

    @Test fun missingTextBoxSlotsInAfterText() {
        // A layout stored before the text box tool existed: it must appear right after
        // the inline text item, not at the end of the bar.
        val raw = ToolbarLayout.DEFAULT.toRaw()
            .map { s -> s.filterNot { it.first == ToolbarItem.TEXT_BOX.id } }
        val back = ToolbarLayout.fromRaw(raw)
        val sec = back.sections.first { s -> s.entries.any { it.item == ToolbarItem.TEXT } }
        val items = sec.entries.map { it.item }
        assertEquals(items.indexOf(ToolbarItem.TEXT) + 1, items.indexOf(ToolbarItem.TEXT_BOX))
    }

    @Test fun missingMarkupSlotsInAfterTextBox() {
        // A layout stored before the text markup tool existed gains it beside the text box.
        val raw = ToolbarLayout.DEFAULT.toRaw()
            .map { s -> s.filterNot { it.first == ToolbarItem.MARKUP.id } }
        val back = ToolbarLayout.fromRaw(raw)
        val sec = back.sections.first { s -> s.entries.any { it.item == ToolbarItem.TEXT_BOX } }
        val items = sec.entries.map { it.item }
        assertEquals(items.indexOf(ToolbarItem.TEXT_BOX) + 1, items.indexOf(ToolbarItem.MARKUP))
        assertTrue(ToolbarItem.MARKUP !in ToolbarLayout.CANVAS_ITEMS)
    }

    @Test fun missingViewSlotsInAfterStyles() {
        // A layout stored before the View menu existed: it must appear right after Styles.
        val raw = ToolbarLayout.DEFAULT.toRaw()
            .map { s -> s.filterNot { it.first == ToolbarItem.VIEW.id } }
        val back = ToolbarLayout.fromRaw(raw)
        val sec = back.sections.first { s -> s.entries.any { it.item == ToolbarItem.STYLES } }
        val items = sec.entries.map { it.item }
        assertEquals(items.indexOf(ToolbarItem.STYLES) + 1, items.indexOf(ToolbarItem.VIEW))
    }

    @Test fun missingTextBoxWithoutTextAppendsToLastSection() {
        val raw = ToolbarLayout.DEFAULT.toRaw()
            .map { s -> s.filterNot { it.first == ToolbarItem.TEXT_BOX.id || it.first == ToolbarItem.TEXT.id } }
            .filter { it.isNotEmpty() }
        val back = ToolbarLayout.fromRaw(raw)
        assertEquals(ToolbarLayout.NOTE_ITEMS, back.items().toSet())
        val last = back.sections.last().entries.map { it.item }
        // TEXT lands at the end first (enum order), then TEXT_BOX slots in after it.
        assertEquals(last.indexOf(ToolbarItem.TEXT) + 1, last.indexOf(ToolbarItem.TEXT_BOX))
    }

    @Test fun hiddenFlagSurvivesRaw() {
        val hidden = ToolbarLayout.DEFAULT.toggleVisible(2, 0)
        assertFalse(hidden.sections[2].entries[0].visible)
        assertEquals(hidden, ToolbarLayout.fromRaw(hidden.toRaw()))
    }

    @Test fun addSectionAppendsEmpty() {
        val l = ToolbarLayout(listOf(sec(ToolbarItem.HOME))).addSection()
        assertEquals(2, l.sections.size)
        assertTrue(l.sections.last().entries.isEmpty())
    }

    @Test fun removeMiddleSectionMergesIntoPrevious() {
        val l = ToolbarLayout(listOf(sec(ToolbarItem.HOME, ToolbarItem.TITLE), sec(ToolbarItem.SIDEBAR), sec(ToolbarItem.PEN)))
        val r = l.removeSection(1)
        assertEquals(2, r.sections.size)
        assertEquals(listOf(ToolbarItem.HOME, ToolbarItem.TITLE, ToolbarItem.SIDEBAR), r.sections[0].entries.map { it.item })
    }

    @Test fun removeFirstSectionMergesIntoNext() {
        val l = ToolbarLayout(listOf(sec(ToolbarItem.HOME, ToolbarItem.TITLE), sec(ToolbarItem.SIDEBAR)))
        val r = l.removeSection(0)
        assertEquals(1, r.sections.size)
        assertEquals(listOf(ToolbarItem.HOME, ToolbarItem.TITLE, ToolbarItem.SIDEBAR), r.sections[0].entries.map { it.item })
    }

    @Test fun removeLastRemainingSectionIsNoOp() {
        val l = ToolbarLayout(listOf(sec(ToolbarItem.HOME)))
        assertEquals(l, l.removeSection(0))
    }

    @Test fun moveSectionReorders() {
        val l = ToolbarLayout(listOf(sec(ToolbarItem.HOME), sec(ToolbarItem.SIDEBAR), sec(ToolbarItem.PEN)))
        val r = l.moveSection(0, 2)
        assertEquals(listOf(ToolbarItem.HOME), r.sections.last().entries.map { it.item })
        assertEquals(listOf(ToolbarItem.SIDEBAR), r.sections[0].entries.map { it.item })
    }

    @Test fun moveItemWithinSectionClampsForwardTarget() {
        val l = ToolbarLayout(listOf(sec(ToolbarItem.HOME, ToolbarItem.TITLE, ToolbarItem.SIDEBAR)))
        val r = l.moveItem(0, 0, 0, 2)
        assertEquals(listOf(ToolbarItem.TITLE, ToolbarItem.HOME, ToolbarItem.SIDEBAR), r.sections[0].entries.map { it.item })
    }

    @Test fun moveItemAcrossSections() {
        val l = ToolbarLayout(listOf(sec(ToolbarItem.HOME, ToolbarItem.TITLE), sec(ToolbarItem.SIDEBAR)))
        val r = l.moveItem(0, 1, 1, 0)
        assertEquals(listOf(ToolbarItem.HOME), r.sections[0].entries.map { it.item })
        assertEquals(listOf(ToolbarItem.TITLE, ToolbarItem.SIDEBAR), r.sections[1].entries.map { it.item })
    }

    @Test fun toggleVisibleFlipsOnlyTarget() {
        val l = ToolbarLayout(listOf(sec(ToolbarItem.HOME, ToolbarItem.TITLE)))
        val r = l.toggleVisible(0, 0)
        assertFalse(r.sections[0].entries[0].visible)
        assertTrue(r.sections[0].entries[1].visible)
    }

    @Test fun visibleSectionsSkipsEmptyAndAllHidden() {
        val l = ToolbarLayout(
            listOf(
                sec(ToolbarItem.HOME),
                ToolbarSection(listOf(ToolbarEntry(ToolbarItem.TITLE, visible = false))),
                ToolbarSection(emptyList()),
                sec(ToolbarItem.PEN),
            ),
        )
        assertEquals(2, l.visibleSections.size)
    }

    // --- the canvas bar, which is its own layout with its own items ---

    @Test fun canvasDefaultHoldsEveryCanvasItemOnce() {
        val items = ToolbarLayout.CANVAS_DEFAULT.items()
        assertEquals(ToolbarLayout.CANVAS_ITEMS, items.toSet())
        assertEquals(ToolbarLayout.CANVAS_ITEMS.size, items.size)
    }

    @Test fun theTwoBarsDoNotShareTheirOwnItems() {
        assertTrue(ToolbarItem.WAYPOINTS !in ToolbarLayout.NOTE_ITEMS)
        assertTrue(ToolbarItem.MINIMAP !in ToolbarLayout.NOTE_ITEMS)
        assertTrue(ToolbarItem.PAGE_NAV !in ToolbarLayout.CANVAS_ITEMS)
        assertTrue(ToolbarItem.TEXT !in ToolbarLayout.CANVAS_ITEMS)
    }

    @Test fun theCanvasDefaultRoundTripsThroughRaw() {
        assertEquals(
            ToolbarLayout.CANVAS_DEFAULT,
            ToolbarLayout.fromRaw(
                ToolbarLayout.CANVAS_DEFAULT.toRaw(),
                ToolbarLayout.CANVAS_ITEMS,
                ToolbarLayout.CANVAS_DEFAULT,
            ),
        )
    }

    /** A stored paged layout handed to the canvas must not put page tools on it. */
    @Test fun aCanvasLayoutDropsWhatBelongsToTheOtherBar() {
        val back = ToolbarLayout.fromRaw(
            ToolbarLayout.DEFAULT.toRaw(),
            ToolbarLayout.CANVAS_ITEMS,
            ToolbarLayout.CANVAS_DEFAULT,
        )
        assertEquals(ToolbarLayout.CANVAS_ITEMS, back.items().toSet())
    }

    @Test fun aCanvasLayoutStoredBeforeAnItemExistedGrowsIt() {
        val raw = ToolbarLayout.CANVAS_DEFAULT.toRaw()
            .map { s -> s.filterNot { it.first == ToolbarItem.MINIMAP.id } }
        val back = ToolbarLayout.fromRaw(raw, ToolbarLayout.CANVAS_ITEMS, ToolbarLayout.CANVAS_DEFAULT)
        assertEquals(ToolbarLayout.CANVAS_ITEMS, back.items().toSet())
        assertTrue(back.sections.last().entries.any { it.item == ToolbarItem.MINIMAP && it.visible })
    }

    @Test fun anEmptyCanvasLayoutFallsBackToTheCanvasDefault() {
        assertEquals(
            ToolbarLayout.CANVAS_DEFAULT,
            ToolbarLayout.fromRaw(emptyList(), ToolbarLayout.CANVAS_ITEMS, ToolbarLayout.CANVAS_DEFAULT),
        )
    }
}
