package com.xnotes.settings

import com.xnotes.core.tools.ToolbarAnchor
import com.xnotes.core.tools.ToolbarDockAlignment
import com.xnotes.core.tools.ToolbarDockEdge
import com.xnotes.core.tools.ToolbarBar
import com.xnotes.core.tools.ToolbarDistribution
import com.xnotes.core.tools.ToolbarEntry
import com.xnotes.core.tools.ToolbarGeometry
import com.xnotes.core.tools.ToolbarItem
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.core.tools.ToolbarScale
import com.xnotes.core.tools.ToolbarRegion
import com.xnotes.core.tools.ToolbarSection
import com.xnotes.core.tools.ToolbarWorkspace
import com.xnotes.core.tools.ToolbarWorkspaces
import org.json.JSONArray
import org.json.JSONObject

/**
 * Versioned settings-only codec. Never write toolbar workspace state into document files.
 * Deliberately does not use ToolbarLayout.fromRaw(): it appends all missing tools, which is
 * reserved for reading the standalone original toolbar layout, not compact secondary bars.
 */
internal object ToolbarWorkspaceJson {
    private const val VERSION = 6
    private const val MAX_BARS = 32
    private const val MAX_SECTIONS = 32
    private const val MAX_ENTRIES = 128

    fun write(value: ToolbarWorkspaces): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("shared", value.shared)
        .put("note", writeWorkspace(value.note))
        .put("canvas", writeWorkspace(value.canvas))

    fun read(value: JSONObject?): ToolbarWorkspaces? {
        val raw = value ?: return null
        // Only the first upstream schema is supported; v1–v5 and v7 were
        // experimental, never part of a published XNotes workspace format.
        // An unknown version is treated like missing workspace settings, so
        // Settings.effectiveToolbarWorkspaces() uses the original toolbar layout.
        if (raw.optInt("version", -1) != VERSION) return null
        val note = readWorkspace(raw.optJSONObject("note"), ToolbarLayout.NOTE_ITEMS) ?: return null
        val canvas = readWorkspace(raw.optJSONObject("canvas"), ToolbarLayout.CANVAS_ITEMS) ?: return null
        return ToolbarWorkspaces(note, canvas, shared = raw.optBoolean("shared", false))
    }

    private fun writeWorkspace(workspace: ToolbarWorkspace): JSONObject =
        JSONObject().put("bars", JSONArray().apply {
            for (bar in workspace.bars) put(JSONObject()
                .put("id", bar.id)
                .put("name", bar.name)
                .put("visible", bar.visible)
                .put("geometry", writeGeometry(bar.geometry))
                .put("distribution", bar.layout.distribution.name)
                .put("pin_start", bar.layout.pinStart)
                .put("pin_end", bar.layout.pinEnd)
                .put("sections", JSONArray().apply {
                    for (section in bar.layout.sections) put(JSONObject()
                        .put("lanes", section.lanes)
                        .put("region", section.region.name)
                        .put("entries", JSONArray().apply {
                            for (entry in section.entries) put(JSONObject()
                                .put("id", entry.item.id)
                                .put("visible", entry.visible)
                                .put("lane", entry.lane)
                                .put("color_part", entry.colorPart)
                                .put("color_parts", entry.colorParts)
                                .apply { entry.sizeDp?.let { put("size_dp", it) } })
                        }))
                }))
        })

    private fun writeGeometry(g: ToolbarGeometry): JSONObject = JSONObject()
        .put("anchor", g.anchor.name)
        .put("scale", g.scale.name)
        .put("floating", g.floating)
        .put("movable", g.movable)
        .put("columns", g.columns)
        .put("dock_lane", g.dockLane)
        .put("dock_alignment", g.dockAlignment.name)
        .apply {
            g.visualOrder?.let { put("visual_order", it) }
            g.dockEdge?.let { put("dock_edge", it.name) }
            g.widthDp?.let { put("width_dp", it) }
            g.heightDp?.let { put("height_dp", it) }
            g.paddingDp?.let { put("padding_dp", it) }
            g.spacingDp?.let { put("spacing_dp", it) }
            g.elementSizeDp?.let { put("element_size_dp", it) }
        }

    private fun readWorkspace(value: JSONObject?, available: Set<ToolbarItem>): ToolbarWorkspace? {
        val rawBars = value?.optJSONArray("bars") ?: return null
        if (rawBars.length() == 0) return null
        val ids = HashSet<String>()
        val bars = ArrayList<ToolbarBar>()
        for (index in 0 until minOf(rawBars.length(), MAX_BARS)) {
            val raw = rawBars.optJSONObject(index) ?: continue
            val id = raw.optString("id").trim()
            if (id.isEmpty() || id.length > 64 || !ids.add(id)) continue
            val rawSections = raw.optJSONArray("sections") ?: continue
            val seen = HashSet<ToolbarItem>()
            val sections = ArrayList<ToolbarSection>()
            for (i in 0 until minOf(rawSections.length(), MAX_SECTIONS)) {
                val entries = ArrayList<ToolbarEntry>()
                val section = rawSections.optJSONObject(i) ?: continue
                val rawEntries = section.optJSONArray("entries") ?: continue
                for (j in 0 until minOf(rawEntries.length(), MAX_ENTRIES)) {
                    val entry = rawEntries.optJSONObject(j) ?: continue
                    // The workspace schema accepts canonical tool IDs only.
                    // Historic experimental aliases belong to no released schema.
                    val itemId = entry.optString("id")
                    val item = ToolbarItem.entries.firstOrNull { it.id == itemId } ?: continue
                    if (item !in available || (item != ToolbarItem.COLORS && item != ToolbarItem.SPACER && !seen.add(item))) continue
                    entries.add(ToolbarEntry(
                        item = item,
                        visible = entry.optBoolean("visible", true),
                        lane = entry.optInt("lane", 0).coerceIn(0, 2),
                        colorPart = entry.optInt("color_part", 0).coerceIn(0, 63),
                        colorParts = entry.optInt("color_parts", 1).coerceIn(1, 64),
                        sizeDp = entry.optInt("size_dp").takeIf { entry.has("size_dp") }?.let {
                            if (item == ToolbarItem.SPACER) it.coerceIn(4, 320) else it.coerceIn(28, 72)
                        },
                    ))
                }
                val lanes = section.optInt("lanes", 1).coerceIn(1, 3)
                val region = ToolbarRegion.entries.firstOrNull { it.name == section.optString("region") }
                    ?: ToolbarRegion.START
                sections.add(ToolbarSection(entries, lanes, region).normalized())
            }
            bars.add(ToolbarBar(
                id = id,
                name = raw.optString("name", "Toolbar").take(64),
                layout = ToolbarLayout(
                    sections.ifEmpty { listOf(ToolbarSection(emptyList())) },
                    ToolbarDistribution.entries.firstOrNull { it.name == raw.optString("distribution") }
                        ?: ToolbarDistribution.CONTINUOUS,
                    pinStart = raw.optBoolean("pin_start", false),
                    pinEnd = raw.optBoolean("pin_end", false),
                ),
                visible = raw.optBoolean("visible", true),
                geometry = readGeometry(raw.optJSONObject("geometry")),
            ))
        }
        return bars.takeIf { it.isNotEmpty() }?.let(::ToolbarWorkspace)
    }

    private fun readGeometry(raw: JSONObject?): ToolbarGeometry {
        if (raw == null) return ToolbarGeometry()
        val anchor = ToolbarAnchor.entries.firstOrNull { it.name == raw.optString("anchor") }
            ?: ToolbarAnchor.TOP_CENTER
        val scale = ToolbarScale.entries.firstOrNull { it.name == raw.optString("scale") }
            ?: ToolbarScale.REGULAR
        return ToolbarGeometry(
            anchor = anchor,
            scale = scale,
            floating = raw.optBoolean("floating", false),
            movable = raw.optBoolean("movable", true),
            widthDp = raw.optInt("width_dp").takeIf { raw.has("width_dp") },
            heightDp = raw.optInt("height_dp").takeIf { raw.has("height_dp") },
            paddingDp = raw.optInt("padding_dp").takeIf { raw.has("padding_dp") },
            spacingDp = raw.optInt("spacing_dp").takeIf { raw.has("spacing_dp") },
            elementSizeDp = raw.optInt("element_size_dp").takeIf { raw.has("element_size_dp") },
            columns = raw.optInt("columns", 1),
            dockEdge = ToolbarDockEdge.entries.firstOrNull { it.name == raw.optString("dock_edge") },
            dockLane = raw.optInt("dock_lane", 0),
            visualOrder = raw.optInt("visual_order").takeIf { raw.has("visual_order") && it in 0..128 },
            dockAlignment = ToolbarDockAlignment.entries.firstOrNull { it.name == raw.optString("dock_alignment") }
                ?: ToolbarDockAlignment.CENTER,
        ).constrained()
    }
}
