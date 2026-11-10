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

    @Test fun distributionIsOptInAndSectionsKeepTheirIndependentRows() {
        val base = ToolbarLayout(listOf(sec(ToolbarItem.PEN), sec(ToolbarItem.COLORS)))
        assertEquals(ToolbarDistribution.CONTINUOUS, base.distribution)
        val regions = base.setSectionLanes(1, 2)
            .setSectionRegion(1, ToolbarRegion.END)
            .setDistribution(ToolbarDistribution.REGIONS)
        assertEquals(2, regions.sections[1].lanes)
        assertEquals(ToolbarRegion.END, regions.sections[1].region)
        assertEquals(ToolbarRegion.START, regions.sections[0].region)
        assertEquals(ToolbarDistribution.REGIONS, regions.distribution)
        assertEquals(2, regions.addSection(3, ToolbarRegion.CENTER).sections[1].lanes)
        assertEquals(ToolbarRegion.CENTER, regions.addSection(3, ToolbarRegion.CENTER).sections.last().region)
        assertEquals(base, regions.setDistribution(ToolbarDistribution.CONTINUOUS)
            .setSectionRegion(1, ToolbarRegion.START).setSectionLanes(1, 1))
    }

    @Test fun switchingModesKeepsExactOrderAndExplicitRegionAssignments() {
        val layout = ToolbarLayout(listOf(
            ToolbarSection(listOf(ToolbarEntry(ToolbarItem.PEN)), region = ToolbarRegion.END),
            ToolbarSection(listOf(ToolbarEntry(ToolbarItem.HOME)), region = ToolbarRegion.START),
            ToolbarSection(listOf(ToolbarEntry(ToolbarItem.TITLE)), region = ToolbarRegion.CENTER),
            ToolbarSection(listOf(ToolbarEntry(ToolbarItem.ERASER)), region = ToolbarRegion.START),
        ), distribution = ToolbarDistribution.REGIONS)
        val continuous = layout.setDistribution(ToolbarDistribution.CONTINUOUS)
        assertEquals(layout.sections, continuous.sections)
        assertEquals(continuous, continuous.setDistribution(ToolbarDistribution.CONTINUOUS))
        val roundTrip = continuous.setDistribution(ToolbarDistribution.REGIONS)
        assertEquals(layout, roundTrip)
    }

    @Test fun switchingMainToolbarToRegionsDoesNotChangeDefault() {
        val original = ToolbarLayout.DEFAULT
        val regions = original.setDistribution(ToolbarDistribution.REGIONS)
        assertEquals(original.sections, regions.sections)
        assertTrue(regions.sections.all { it.region == ToolbarRegion.START })
        assertEquals(original, regions.setDistribution(ToolbarDistribution.CONTINUOUS))
    }

    @Test fun collectingRegionsPreservesToolOrderLanesAndIndividualSizes() {
        val original = ToolbarLayout(listOf(
            ToolbarSection(listOf(ToolbarEntry(ToolbarItem.PEN, sizeDp = 60)), lanes = 2, region = ToolbarRegion.CENTER),
            ToolbarSection(listOf(ToolbarEntry(ToolbarItem.COLORS)), region = ToolbarRegion.END),
        ), distribution = ToolbarDistribution.REGIONS)
        val repaired = original.gatherSectionsAtStart()
        assertEquals(original.sections.map { it.entries }, repaired.sections.map { it.entries })
        assertEquals(original.sections.map { it.lanes }, repaired.sections.map { it.lanes })
        assertTrue(repaired.sections.all { it.region == ToolbarRegion.START })
        assertEquals(repaired, repaired.gatherSectionsAtStart())
    }



    @Test fun spacersCanRepeatMoveAndResizeWithoutChangingDefaultToolbar() {
        val base = ToolbarLayout.DEFAULT
        val withGaps = base.addItem(0, 0, ToolbarItem.SPACER).addItem(0, 0, ToolbarItem.SPACER)
        val indices = withGaps.sections[0].entries.withIndex()
            .filter { it.value.item == ToolbarItem.SPACER }.map { it.index }
        assertEquals(2, indices.size)
        val sized = withGaps.setEntrySize(0, indices[0], 12).setEntrySize(0, indices[1], 280)
        assertEquals(listOf(12, 280), indices.map { sized.sections[0].entries[it].sizeDp })
        assertEquals(sized, sized.setEntrySize(0, indices[0], 321))
        assertEquals(280, sized.setDistribution(ToolbarDistribution.REGIONS)
            .setDistribution(ToolbarDistribution.CONTINUOUS).sections[0].entries[indices[1]].sizeDp)
        assertEquals(base, ToolbarLayout.DEFAULT)
        assertEquals(base, base.withMissingItemsAppended())
    }

    @Test fun defaultContainsEveryItemOnce() {
        val items = ToolbarLayout.DEFAULT.items()
        assertEquals(ToolbarLayout.NOTE_ITEMS - ToolbarItem.SPACER, items.toSet())
        assertEquals(ToolbarLayout.NOTE_ITEMS.size - 1, items.size)
    }

    @Test fun defaultRoundTripsThroughRaw() {
        assertEquals(ToolbarLayout.DEFAULT, ToolbarLayout.fromRaw(ToolbarLayout.DEFAULT.toRaw()))
    }





    @Test fun sectionsHaveIndependentUnlimitedLanes() {
        val linear = ToolbarLayout(listOf(sec(
            ToolbarItem.PEN, ToolbarItem.DASHED, ToolbarItem.CALLIGRAPHY,
            ToolbarItem.SPEED, ToolbarItem.TAPER, ToolbarItem.ERASER, ToolbarItem.COLORS,
        )))
        val double = linear.setSectionLanes(0, 2)
        assertEquals(2, double.sections.single().lanes)
        assertEquals(1, double.sections.single().crossCells)
        val moved = double.moveItem(0, 6, 0, 7, toLane = 1)
        assertEquals(2, moved.sections.single().crossCells)
        assertEquals(ToolbarItem.COLORS, moved.sections.single().entriesInLane(1).single().item)
        assertEquals(6, moved.sections.single().entriesInLane(0).size)
        assertEquals(linear, moved.setSectionLanes(0, 1))
    }

    @Test fun threeLanesAllowIndependentStacksAndPreserveExistingRows() {
        val base = ToolbarLayout(listOf(sec(
            ToolbarItem.PEN, ToolbarItem.ERASER, ToolbarItem.DASHED, ToolbarItem.HIGHLIGHTER,
        )))
        assertEquals(1, base.sections.single().lanes)
        val triple = base.setSectionLanes(0, 3)
            .moveItem(0, 1, 0, 4, toLane = 1)
            .moveItem(0, 0, 0, 3, toLane = 2)
        assertEquals(3, triple.sections.single().lanes)
        assertEquals(3, triple.sections.single().crossCells)
        assertEquals(listOf(ToolbarItem.DASHED, ToolbarItem.HIGHLIGHTER),
            triple.sections.single().entriesInLane(0).map { it.item })
        assertEquals(ToolbarItem.ERASER, triple.sections.single().entriesInLane(1).single().item)
        assertEquals(ToolbarItem.PEN, triple.sections.single().entriesInLane(2).single().item)
        val double = triple.setSectionLanes(0, 2)
        assertEquals(2, double.sections.single().crossCells)
        assertEquals(listOf(ToolbarItem.PEN, ToolbarItem.ERASER),
            double.sections.single().entriesInLane(1).map { it.item }.sortedBy { it.ordinal })
        assertEquals(triple.items().toSet(), double.items().toSet())
        val single = triple.setSectionLanes(0, 1)
        assertEquals(1, single.sections.single().crossCells)
        assertTrue(single.sections.single().entries.all { it.lane == 0 })
        assertEquals(3, base.addSection(3).sections.last().lanes)
        assertEquals(1, base.addSection().sections.last().lanes)
        assertEquals(base, base.addSection(4))
    }

    @Test fun movingAcrossSectionsAndLanesPreservesAllTools() {
        val first = sec(ToolbarItem.PEN, ToolbarItem.DASHED, ToolbarItem.CALLIGRAPHY, ToolbarItem.SPEED)
        val other = sec(ToolbarItem.HIGHLIGHTER, ToolbarItem.COLORS)
        val layout = ToolbarLayout(listOf(first, other)).setSectionLanes(0, 2)
        val moved = layout.moveItem(1, 1, 0, 3, toLane = 1)
        assertEquals(5, moved.sections[0].entries.size)
        assertEquals(1, moved.sections[1].entries.size)
        assertEquals(ToolbarItem.COLORS, moved.sections[0].entriesInLane(1).single().item)
        assertEquals(2, moved.sections[0].crossCells)
        assertEquals(layout.items().toSet(), moved.items().toSet())
    }

    @Test fun colorBlocksDivideCurrentPaletteAndReflowWhenColorCountChanges() {
        val one = ToolbarLayout(listOf(sec(ToolbarItem.COLORS))).setSectionLanes(0, 2)
        val two = one.setColorBlocks(2)
        val entries = two.sections.single().entries
        assertEquals(2, two.colorBlockCount)
        assertEquals(listOf(0, 1), entries.map { it.lane })
        assertEquals((0..2).toList(), entries[0].colorIndices(5).toList())
        assertEquals((3..4).toList(), entries[1].colorIndices(5).toList())
        assertEquals((0..4).toList(), entries[0].colorIndices(10).toList())
        assertEquals((5..9).toList(), entries[1].colorIndices(10).toList())
        val three = two.setColorBlocks(3)
        assertEquals(listOf(0, 1, 0), three.sections.single().entries.map { it.lane })
        assertEquals((0..2).toList(), three.sections.single().entries[0].colorIndices(9).toList())
        assertEquals((3..5).toList(), three.sections.single().entries[1].colorIndices(9).toList())
        assertEquals((6..8).toList(), three.sections.single().entries[2].colorIndices(9).toList())
        assertEquals(1, three.setColorBlocks(1).colorBlockCount)
        assertEquals(one, three.setColorBlocks(1))
    }

    @Test fun emptyRawYieldsDefault() {
        assertEquals(ToolbarLayout.DEFAULT, ToolbarLayout.fromRaw(emptyList()))
        assertEquals(ToolbarLayout.DEFAULT, ToolbarLayout.fromRaw(listOf(emptyList(), emptyList())))
    }

    @Test fun unknownIdsDropped() {
        val raw = ToolbarLayout.DEFAULT.toRaw().toMutableList()
        raw[0] = listOf("frobnicate" to true) + raw[0]
        val back = ToolbarLayout.fromRaw(raw)
        assertEquals(ToolbarLayout.NOTE_ITEMS.size - 1, back.items().size)
        assertEquals(ToolbarLayout.NOTE_ITEMS - ToolbarItem.SPACER, back.items().toSet())
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
        assertEquals(ToolbarLayout.NOTE_ITEMS - ToolbarItem.SPACER, back.items().toSet())
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
        assertEquals(ToolbarLayout.NOTE_ITEMS - ToolbarItem.SPACER, back.items().toSet())
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

    @Test fun sectionsMoveAcrossRegionsAsIntactBlocks() {
        val l = ToolbarLayout(listOf(sec(ToolbarItem.HOME), sec(ToolbarItem.TITLE), sec(ToolbarItem.PEN)),
            distribution = ToolbarDistribution.REGIONS)
        val moved = l.moveSectionToRegion(0, ToolbarRegion.END)
        assertEquals(listOf(ToolbarItem.TITLE, ToolbarItem.PEN, ToolbarItem.HOME),
            moved.sections.flatMap { it.entries }.map { it.item })
        assertEquals(ToolbarRegion.END, moved.sections.last().region)
        assertEquals(moved, moved.moveSectionToRegion(2, ToolbarRegion.END, 2))
        val centered = moved.moveSectionToRegion(1, ToolbarRegion.CENTER)
        assertEquals(listOf(ToolbarRegion.START, ToolbarRegion.CENTER, ToolbarRegion.END),
            centered.sections.map { it.region })
    }

    @Test fun sectionReorderingWithinOneRegionUsesTheOriginalAnchor() {
        val l = ToolbarLayout(listOf(sec(ToolbarItem.HOME), sec(ToolbarItem.TITLE), sec(ToolbarItem.PEN)),
            distribution = ToolbarDistribution.REGIONS)
        val moved = l.moveSectionToRegion(2, ToolbarRegion.START, beforeSectionIndex = 0)
        assertEquals(listOf(ToolbarItem.PEN, ToolbarItem.HOME, ToolbarItem.TITLE),
            moved.sections.flatMap { it.entries }.map { it.item })
    }

    @Test fun colorsCanBeDuplicatedAndAreRepartitionedWhenRemoved() {
        val l = ToolbarLayout(listOf(sec(ToolbarItem.COLORS)))
        val doubled = l.addItem(0, 0, ToolbarItem.COLORS)
        assertEquals(2, doubled.colorBlockCount)
        assertEquals(listOf(0, 1), doubled.sections[0].entries.map { it.colorPart })
        assertEquals(listOf(2, 2), doubled.sections[0].entries.map { it.colorParts })
        val reduced = doubled.removeItem(0, 0)
        assertEquals(1, reduced.colorBlockCount)
        assertEquals(0, reduced.sections[0].entries.single().colorPart)
        assertEquals(1, reduced.sections[0].entries.single().colorParts)
    }

    @Test fun ordinaryItemsAreUniqueButHiddenItemsCanBeRestoredIntoAnotherSection() {
        val l = ToolbarLayout(listOf(sec(ToolbarItem.HOME), ToolbarSection(emptyList())))
        assertEquals(l, l.addItem(1, 0, ToolbarItem.HOME))
        val restored = l.toggleVisible(0, 0).addItem(1, 0, ToolbarItem.HOME)
        assertTrue(restored.sections[0].entries.isEmpty())
        assertEquals(ToolbarItem.HOME, restored.sections[1].entries.single().item)
        assertTrue(restored.sections[1].entries.single().visible)
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

    @Test fun movingOneToolIntoEmptyRegionCreatesASectionWithoutChangingOtherTools() {
        val original = ToolbarLayout(listOf(
            ToolbarSection(listOf(ToolbarEntry(ToolbarItem.PEN, sizeDp = 60), ToolbarEntry(ToolbarItem.COLORS)),
                lanes = 2, region = ToolbarRegion.START),
            ToolbarSection(listOf(ToolbarEntry(ToolbarItem.ERASER)), region = ToolbarRegion.END),
        ), distribution = ToolbarDistribution.REGIONS)
        val moved = original.moveItemToNewRegionSection(0, 0, ToolbarRegion.CENTER)
        assertEquals(listOf(ToolbarRegion.START, ToolbarRegion.CENTER, ToolbarRegion.END),
            moved.sections.map { it.region })
        assertEquals(2, moved.sections[0].lanes)
        assertEquals(listOf(ToolbarItem.COLORS), moved.sections[0].entries.map { it.item })
        assertEquals(ToolbarItem.PEN, moved.sections[1].entries.single().item)
        assertEquals(60, moved.sections[1].entries.single().sizeDp)
        assertEquals(0, moved.sections[1].entries.single().lane)
        assertEquals(ToolbarItem.ERASER, moved.sections[2].entries.single().item)
        assertEquals(original, original.moveItemToNewRegionSection(0, 99, ToolbarRegion.CENTER))
    }

    @Test fun draggingDuplicatedColorsRepartitionsTheirSwatches() {
        val original = ToolbarLayout(listOf(
            ToolbarSection(listOf(ToolbarEntry(ToolbarItem.COLORS, colorPart = 0, colorParts = 2)),
                region = ToolbarRegion.START),
            ToolbarSection(listOf(ToolbarEntry(ToolbarItem.COLORS, colorPart = 1, colorParts = 2)),
                region = ToolbarRegion.END),
        ), distribution = ToolbarDistribution.REGIONS)
        val moved = original.moveItemToNewRegionSection(0, 0, ToolbarRegion.CENTER)
        assertEquals(0, moved.sections[1].entries.single().colorPart)
        assertEquals(1, moved.sections[2].entries.single().colorPart)
        val reordered = original.moveItem(0, 0, 1, 1)
        assertEquals(listOf(0, 1), reordered.sections[1].entries.map { it.colorPart })
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
        assertEquals(ToolbarLayout.CANVAS_ITEMS - ToolbarItem.SPACER, items.toSet())
        assertEquals(ToolbarLayout.CANVAS_ITEMS.size - 1, items.size)
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
        assertEquals(ToolbarLayout.CANVAS_ITEMS - ToolbarItem.SPACER, back.items().toSet())
    }

    @Test fun aCanvasLayoutStoredBeforeAnItemExistedGrowsIt() {
        val raw = ToolbarLayout.CANVAS_DEFAULT.toRaw()
            .map { s -> s.filterNot { it.first == ToolbarItem.MINIMAP.id } }
        val back = ToolbarLayout.fromRaw(raw, ToolbarLayout.CANVAS_ITEMS, ToolbarLayout.CANVAS_DEFAULT)
        assertEquals(ToolbarLayout.CANVAS_ITEMS - ToolbarItem.SPACER, back.items().toSet())
        assertTrue(back.sections.last().entries.any { it.item == ToolbarItem.MINIMAP && it.visible })
    }

    @Test fun anEmptyCanvasLayoutFallsBackToTheCanvasDefault() {
        assertEquals(
            ToolbarLayout.CANVAS_DEFAULT,
            ToolbarLayout.fromRaw(emptyList(), ToolbarLayout.CANVAS_ITEMS, ToolbarLayout.CANVAS_DEFAULT),
        )
    }

    @Test fun missingTapeSlotsInRightAfterTheWandOnBothBars() {
        // A layout stored before tape existed, with the wand moved and hidden: tape follows it.
        val note = ToolbarLayout.fromRaw(listOf(listOf("pen" to true, "wand" to false, "shape" to true)))
        val noteItems = note.items()
        assertEquals(noteItems.indexOf(ToolbarItem.WAND) + 1, noteItems.indexOf(ToolbarItem.TAPE))
        assertTrue(note.sections.flatMap { it.entries }.single { it.item == ToolbarItem.TAPE }.visible)
        val canvas = ToolbarLayout.fromRaw(listOf(listOf("wand" to true, "shape" to true)), ToolbarLayout.CANVAS_ITEMS, ToolbarLayout.CANVAS_DEFAULT)
        assertEquals(listOf(ToolbarItem.WAND, ToolbarItem.TAPE, ToolbarItem.SHAPE), canvas.items().take(3))
    }

    @Test fun bothDefaultsSetTapeRightAfterTheWand() {
        for (d in listOf(ToolbarLayout.DEFAULT, ToolbarLayout.CANVAS_DEFAULT)) {
            val items = d.items()
            assertEquals(items.indexOf(ToolbarItem.WAND) + 1, items.indexOf(ToolbarItem.TAPE))
        }
        assertTrue(ToolbarItem.TAPE in ToolbarLayout.CANVAS_ITEMS)
    }

    @Test fun insertSpaceSlotsInRightAfterTheLassoOnTheNoteBarOnly() {
        val note = ToolbarLayout.fromRaw(listOf(listOf("pan" to true, "lasso" to true, "screenshot" to true)))
        val items = note.items()
        assertEquals(items.indexOf(ToolbarItem.LASSO) + 1, items.indexOf(ToolbarItem.INSERT_SPACE))
        assertTrue(ToolbarItem.INSERT_SPACE !in ToolbarLayout.CANVAS_ITEMS)
        assertTrue(ToolbarItem.INSERT_SPACE !in ToolbarLayout.CANVAS_DEFAULT.items())
        val canvas = ToolbarLayout.fromRaw(listOf(listOf("lasso" to true, "insert_space" to true)), ToolbarLayout.CANVAS_ITEMS, ToolbarLayout.CANVAS_DEFAULT)
        assertTrue(ToolbarItem.INSERT_SPACE !in canvas.items())
    }
}
