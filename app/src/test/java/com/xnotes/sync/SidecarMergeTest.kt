package com.xnotes.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SidecarMergeTest {
    @Test
    fun joinsMapsAndListsWithLocalWinning() {
        val local = """{"version":1,"colors":{"a.xnote":"#ff0000","b.xnote":"#00ff00"},"pins":["x","y"]}"""
        val remote = """{"version":1,"colors":{"a.xnote":"#0000ff","c.xnote":"#111111"},"pins":["y","z"]}"""
        val out = JSONObject(SidecarMerge.merge(local, remote))
        val colors = out.getJSONObject("colors")
        assertEquals("#ff0000", colors.getString("a.xnote"))
        assertEquals("#00ff00", colors.getString("b.xnote"))
        assertEquals("#111111", colors.getString("c.xnote"))
        val pins = out.getJSONArray("pins")
        assertEquals(listOf("x", "y", "z"), (0 until pins.length()).map { pins.getString(it) })
    }
}
