package com.xnotes.settings

import com.xnotes.core.model.Rgba
import com.xnotes.core.tools.EraseMode
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
    /** Persisted SAF tree URI for the in-app file explorer's root folder, or null. */
    val browseRoot: String? = null,
    /** Whether the next launch opens the home screen (true) or the last-open note (false). */
    val startOnHome: Boolean = true,
    val sidebarVisible: Boolean = false,
    val renderScale: Double = 1.0,
    val presentation: PresentationSettings = PresentationSettings(),
    val prefs: Preferences = Preferences(),
    /** One-shot flag: the first-run stylus check (which may auto-enable finger-draw) has run. */
    val fingerDrawAutoChecked: Boolean = false,
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
            .apply { browseRoot?.let { put("browse_root", it) } }
            .put("start_on_home", startOnHome)
            .put("sidebar_visible", sidebarVisible)
            .put("render_scale", renderScale)
            .put("presentation", presentation.toJson())
            .put("prefs", prefs.toJson())
            .put("finger_draw_auto_checked", fingerDrawAutoChecked)
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
                browseRoot = o.optString("browse_root", "").ifEmpty { null },
                startOnHome = o.optBoolean("start_on_home", true),
                sidebarVisible = o.optBoolean("sidebar_visible", false),
                renderScale = o.optDouble("render_scale", 1.0),
                presentation = PresentationSettings.fromJson(o.optJSONObject("presentation")),
                prefs = Preferences.fromJson(o.optJSONObject("prefs")),
                fingerDrawAutoChecked = o.optBoolean("finger_draw_auto_checked", false),
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
            .put("speed_strength", c.speedStrength)
            .put("taper_length", c.taperLength)
            .put("neon", c.neon)
            .put("neon_strength", c.neonStrength)
            .put("dash_length", c.dashLength)
            .put("dash_gap", c.dashGap)
            .put("erase_mode", c.eraseMode.id)
            .put("switch_back_after_erase", c.switchBackAfterErase)
            .put("straight_line", c.straightLine)
            .put("rgba", rgbaArr(c.rgba))

        private fun toolConfig(o: JSONObject, tool: Tool): ToolConfig {
            val d = ToolDefaults.configFor(tool)
            return ToolConfig(
                baseWidth = o.optDouble("base_width", d.baseWidth),
                pressureEnabled = o.optBoolean("pressure_enabled", d.pressureEnabled),
                pressureMinFactor = o.optDouble("pressure_min_factor", d.pressureMinFactor),
                directionStrength = o.optDouble("direction_strength", d.directionStrength),
                rgba = Rgba.fromList(o.optJSONArray("rgba")?.let { a -> (0 until a.length()).map { a.optInt(it, 0) } }) ?: d.rgba,
                speedStrength = o.optDouble("speed_strength", d.speedStrength),
                taperLength = o.optDouble("taper_length", d.taperLength),
                neon = o.optBoolean("neon", d.neon),
                neonStrength = o.optDouble("neon_strength", d.neonStrength),
                dashLength = o.optDouble("dash_length", d.dashLength),
                dashGap = o.optDouble("dash_gap", d.dashGap),
                eraseMode = EraseMode.fromId(o.optString("erase_mode", d.eraseMode.id)),
                switchBackAfterErase = o.optBoolean("switch_back_after_erase", d.switchBackAfterErase),
                straightLine = o.optBoolean("straight_line", d.straightLine),
            )
        }

        private fun shapeConfigJson(c: ShapeConfig) = JSONObject()
            .put("shape", c.shape.id).put("stroke_width", c.strokeWidth).put("fill", c.fill)
            .put("neon", c.neon).put("neon_strength", c.neonStrength)

        private fun shapeConfig(o: JSONObject) = ShapeConfig(
            shape = ShapeKind.fromId(o.optString("shape", "rectangle")),
            strokeWidth = o.optDouble("stroke_width", 3.0),
            fill = o.optBoolean("fill", false),
            neon = o.optBoolean("neon", false),
            neonStrength = o.optDouble("neon_strength", 0.6),
        )
    }
}
