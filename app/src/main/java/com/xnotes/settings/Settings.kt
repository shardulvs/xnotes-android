package com.xnotes.settings

import com.xnotes.core.model.Rgba
import com.xnotes.core.tools.InkPalette
import com.xnotes.core.tools.ShapeConfig
import com.xnotes.core.tools.ShapeKind
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import com.xnotes.core.tools.ToolDefaults
import org.json.JSONArray
import org.json.JSONObject

/** Presentation-server defaults (spec 09 §5 / 12 §10). */
data class PresentationSettings(
    val port: Int = 8000,
    val scope: String = "localhost", // "localhost" | "lan"
    val mode: String = "page", // "page" | "follow"
    val quality: String = "medium", // "low" | "medium" | "high"
    val maxFps: Int = 30,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("port", port).put("scope", scope).put("mode", mode)
        .put("quality", quality).put("max_fps", maxFps)

    companion object {
        fun fromJson(o: JSONObject?): PresentationSettings {
            if (o == null) return PresentationSettings()
            return PresentationSettings(
                port = o.optInt("port", 8000),
                scope = if (o.optString("scope", "localhost") == "lan") "lan" else "localhost",
                mode = if (o.optString("mode", "page") == "follow") "follow" else "page",
                quality = o.optString("quality", "medium").let { if (it in setOf("low", "medium", "high")) it else "medium" },
                maxFps = o.optInt("max_fps", 30),
            )
        }
    }
}

/** All persistent non-document state (spec 09 §2). */
data class Settings(
    val tools: Map<Tool, ToolConfig> = emptyMap(),
    val shapeConfig: ShapeConfig = ShapeConfig(),
    val toolbarColors: List<Rgba> = InkPalette.toolbarDefaults,
    val activeColor: Int = 0,
    val recentColors: List<Rgba> = emptyList(),
    val recentFiles: List<String> = emptyList(),
    /** Backstage recent view: true = thumbnail grid, false = list. */
    val recentGrid: Boolean = true,
    /** Persisted SAF tree URI for the in-app file explorer's root folder, or null. */
    val browseRoot: String? = null,
    val sidebarVisible: Boolean = false,
    val renderScale: Double = 1.0,
    val presentation: PresentationSettings = PresentationSettings(),
    val prefs: Preferences = Preferences(),
) {
    fun configFor(tool: Tool): ToolConfig = tools[tool] ?: ToolDefaults.configFor(tool)

    /** Push a colour to the front of recent colours, de-duped, capped at 24. */
    fun rememberColor(c: Rgba): Settings =
        copy(recentColors = (listOf(c) + recentColors.filter { it != c }).take(24))

    fun toJson(): JSONObject {
        val toolsObj = JSONObject()
        for (tool in ToolDefaults.persistedTools) {
            toolsObj.put(tool.id, toolConfigJson(configFor(tool)))
        }
        toolsObj.put("shape", shapeConfigJson(shapeConfig))
        return JSONObject()
            .put("tools", toolsObj)
            .put("toolbar_colors", JSONArray().apply { toolbarColors.forEach { put(rgbaArr(it)) } })
            .put("active_color", activeColor)
            .put("recent_colors", JSONArray().apply { recentColors.forEach { put(rgbaArr(it)) } })
            .put("recent_files", JSONArray().apply { recentFiles.forEach { put(it) } })
            .put("recent_grid", recentGrid)
            .apply { browseRoot?.let { put("browse_root", it) } }
            .put("sidebar_visible", sidebarVisible)
            .put("render_scale", renderScale)
            .put("presentation", presentation.toJson())
            .put("prefs", prefs.toJson())
    }

    companion object {
        fun fromJson(o: JSONObject): Settings {
            val toolsObj = o.optJSONObject("tools")
            val tools = HashMap<Tool, ToolConfig>()
            if (toolsObj != null) {
                for (tool in ToolDefaults.persistedTools) {
                    toolsObj.optJSONObject(tool.id)?.let { tools[tool] = toolConfig(it, tool) }
                }
            }
            val shape = toolsObj?.optJSONObject("shape")?.let { shapeConfig(it) } ?: ShapeConfig()

            val colors = rgbaList(o.optJSONArray("toolbar_colors")).toMutableList()
            while (colors.size < 5) colors.add(InkPalette.toolbarDefaults[colors.size])

            return Settings(
                tools = tools,
                shapeConfig = shape,
                toolbarColors = colors.take(5),
                activeColor = o.optInt("active_color", 0).coerceIn(0, 4),
                recentColors = rgbaList(o.optJSONArray("recent_colors")).take(24),
                recentFiles = (0 until (o.optJSONArray("recent_files")?.length() ?: 0))
                    .mapNotNull { o.optJSONArray("recent_files")?.optString(it) }
                    .filter { it.isNotEmpty() }.take(10),
                recentGrid = o.optBoolean("recent_grid", true),
                browseRoot = o.optString("browse_root", "").ifEmpty { null },
                sidebarVisible = o.optBoolean("sidebar_visible", false),
                renderScale = o.optDouble("render_scale", 1.0),
                presentation = PresentationSettings.fromJson(o.optJSONObject("presentation")),
                prefs = Preferences.fromJson(o.optJSONObject("prefs")),
            )
        }

        private fun rgbaArr(c: Rgba) = JSONArray().put(c.r).put(c.g).put(c.b).put(c.a)

        private fun rgbaList(arr: JSONArray?): List<Rgba> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val a = arr.optJSONArray(i) ?: return@mapNotNull null
                Rgba.fromList((0 until a.length()).map { a.optInt(it, 0) })
            }
        }

        private fun toolConfigJson(c: ToolConfig) = JSONObject()
            .put("base_width", c.baseWidth)
            .put("pressure_enabled", c.pressureEnabled)
            .put("pressure_min_factor", c.pressureMinFactor)
            .put("direction_strength", c.directionStrength)
            .put("rgba", rgbaArr(c.rgba))

        private fun toolConfig(o: JSONObject, tool: Tool): ToolConfig {
            val d = ToolDefaults.configFor(tool)
            return ToolConfig(
                baseWidth = o.optDouble("base_width", d.baseWidth),
                pressureEnabled = o.optBoolean("pressure_enabled", d.pressureEnabled),
                pressureMinFactor = o.optDouble("pressure_min_factor", d.pressureMinFactor),
                directionStrength = o.optDouble("direction_strength", d.directionStrength),
                rgba = Rgba.fromList(o.optJSONArray("rgba")?.let { a -> (0 until a.length()).map { a.optInt(it, 0) } }) ?: d.rgba,
            )
        }

        private fun shapeConfigJson(c: ShapeConfig) = JSONObject()
            .put("shape", c.shape.id).put("stroke_width", c.strokeWidth).put("fill", c.fill)

        private fun shapeConfig(o: JSONObject) = ShapeConfig(
            shape = ShapeKind.fromId(o.optString("shape", "rectangle")),
            strokeWidth = o.optDouble("stroke_width", 3.0),
            fill = o.optBoolean("fill", false),
        )
    }
}
