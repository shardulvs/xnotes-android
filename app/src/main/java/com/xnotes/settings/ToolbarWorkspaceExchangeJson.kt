package com.xnotes.settings

import org.json.JSONObject

/**
 * Portable, versioned export. Only toolbar presets are included, never documents,
 * user credentials or unrelated settings.
 */
object ToolbarWorkspaceExchangeJson {
    private const val FORMAT = "xnotes-toolbar-workspaces"
    private const val VERSION = 1

    fun export(presets: List<ToolbarWorkspacePreset>): String = JSONObject()
        .put("format", FORMAT)
        .put("version", VERSION)
        .put("presets", ToolbarWorkspacePresetsJson.write(presets))
        .toString(2)

    fun import(raw: String): List<ToolbarWorkspacePreset> {
        val json = JSONObject(raw)
        require(json.optString("format") == FORMAT) { "Arquivo incompatível com workspaces do XNotes." }
        require(json.optInt("version") == VERSION) { "Versão de arquivo não suportada." }
        return ToolbarWorkspacePresetsJson.read(json.optJSONArray("presets")).also {
            require(it.isNotEmpty()) { "Nenhum workspace válido encontrado." }
        }
    }
}
