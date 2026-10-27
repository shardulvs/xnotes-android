package com.xnotes.core.model

import com.xnotes.core.history.Regroup
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupsTest {

    private fun ink(group: Int? = null) =
        Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), mutableListOf(Sample(0.0, 0.0, 1.0))).also { it.group = group }

    @Test fun aMemberBringsItsWholeGroupButNotALockedOne() {
        val a = ink(1)
        val b = ink(1)
        val locked = ink(1).also { it.locked = true }
        val loose = ink()
        val all = listOf(a, b, locked, loose, ink(2))
        assertEquals(listOf(a, b), Groups.withMembers(listOf(a), all) { it })
        assertEquals(listOf(loose), Groups.withMembers(listOf(loose), all) { it })
    }

    @Test fun groupingMergesGroupsAndUngroupFreesEveryItem() {
        val items = listOf(ink(1), ink(1), ink(2), ink())
        assertTrue(Groups.canGroup(items))
        val group = Regroup(items, Groups.nextId(items)).also { it.redo() }
        assertTrue(items.all { it.group == 3 })
        assertFalse(Groups.canGroup(items))
        assertTrue(Groups.hasGroup(items))
        group.undo()
        assertEquals(listOf(1, 1, 2, null), items.map { it.group })
        Regroup(items, null).redo()
        assertTrue(items.all { it.group == null })
        assertFalse(Groups.hasGroup(items))
    }

    @Test fun aLoneMemberIsNoGroup() {
        assertFalse(Groups.hasGroup(listOf(ink(4), ink())))
        assertFalse(Groups.canGroup(listOf(ink(4))))
    }

    @Test fun copiesGetFreshIdsKeepingTheirFellowsTogether() {
        val existing = listOf(ink(1), ink(5))
        val copies = listOf(ink(1), ink(1), ink(5), ink())
        Groups.refresh(copies, existing)
        assertEquals(listOf(6, 6, 7, null), copies.map { it.group })
        assertNull(copies[3].group)
    }

    @Test fun aCopyKeepsItsGroup() {
        assertEquals(3, ink(3).deepCopy(com.xnotes.core.FakeTextMeasurer()).group)
    }
}
