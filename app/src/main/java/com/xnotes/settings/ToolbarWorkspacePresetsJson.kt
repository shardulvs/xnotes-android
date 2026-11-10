package com.xnotes.settings

import com.xnotes.core.model.Rgba
import com.xnotes.core.tools.InkPalette
import com.xnotes.core.tools.ToolbarWorkspaces
import org.json.JSONArray
import org.json.JSONObject

/** Named snapshots of both toolbar profiles, geometry and global toolbar appearance. */
data class ToolbarWorkspacePreset(
    val id: String,
    val name: String,
    val workspaces: ToolbarWorkspaces,
    val look: ToolbarLook = ToolbarLook(),
    val colors: List<Rgba> = InkPalette.presets,
    val colorCount: Int = 5,
)

internal object ToolbarWorkspacePresetsJson {
    private const val MAX_PRESETS = 20

    fun write(presets: List<ToolbarWorkspacePreset>): JSONArray = JSONArray().apply {
        presets.take(MAX_PRESETS).forEach { preset ->
            put(JSONObject()
                .put("id", preset.id)
                .put("name", preset.name)
                .put("workspaces", ToolbarWorkspaceJson.write(preset.workspaces))
                .put("toolbar_look", JSONObject()
                    .put("position", preset.look.position.id)
                    .put("size", preset.look.size.id)
                    .put("floating", preset.look.floating))
                .put("color_count", preset.colorCount)
                .put("colors", JSONArray().apply { preset.colors.forEach { color ->
                    put(JSONArray().put(color.r).put(color.g).put(color.b).put(color.a))
                } }))
        }
    }

    fun read(json: JSONArray?): List<ToolbarWorkspacePreset> {
        if (json == null) return emptyList()
        val result = mutableListOf<ToolbarWorkspacePreset>()
        val usedIds = mutableSetOf<String>()
        for (index in 0 until minOf(json.length(), MAX_PRESETS)) {
            val entry = json.optJSONObject(index) ?: continue
            val id = entry.optString("id").take(80)
            val name = entry.optString("name").trim().take(48)
            val profiles = ToolbarWorkspaceJson.read(entry.optJSONObject("workspaces")) ?: continue
            if (id.isBlank() || name.isBlank() || !usedIds.add(id)) continue
            val lookJson = entry.optJSONObject("toolbar_look")
            val look = ToolbarLook(
                position = ToolbarPosition.fromId(lookJson?.optString("position") ?: ""),
                size = ToolbarSize.fromId(lookJson?.optString("size") ?: ""),
                floating = lookJson?.optBoolean("floating", false) ?: false,
            )
            val colorJson = entry.optJSONArray("colors")
            val colors = if (colorJson == null) InkPalette.presets else {
                (0 until minOf(colorJson.length(), InkPalette.MAX_SWATCHES)).mapNotNull { colorIndex ->
                    val raw = colorJson.optJSONArray(colorIndex) ?: return@mapNotNull null
                    Rgba.fromList((0 until raw.length()).map { raw.optInt(it, 0) })
                }.let { found -> (found + InkPalette.presets.drop(found.size)).take(InkPalette.MAX_SWATCHES) }
            }
            result.add(ToolbarWorkspacePreset(
                id, name, profiles, look, colors,
                entry.optInt("color_count", 5).coerceIn(1, InkPalette.MAX_SWATCHES),
            ))
        }
        return result
    }
}
