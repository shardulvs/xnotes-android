package com.xnotes.platform

import com.xnotes.canvas.PdfColorFilter
import com.xnotes.canvas.ViewOverrides
import com.xnotes.canvas.ViewSettings
import com.xnotes.canvas.ViewingMode
import com.xnotes.core.model.Rgba
import org.json.JSONObject

/**
 * JSON (de)serialization for the global [ViewSettings] defaults (the View menu's Global
 * tab, stored in settings.json). Defaults are omitted on write and every field is
 * forgiving on read, so older files load with the field's default.
 */
internal object ViewSettingsJson {

    fun write(o: JSONObject, s: ViewSettings): JSONObject = o.apply {
        if (s.mode != ViewingMode.SINGLE) put("mode", s.mode.id)
        if (!s.verticalScroll) put("verticalScroll", false)
        if (s.freeMovement) put("freeMovement", true)
        if (s.contrast != 100) put("contrast", s.contrast)
        if (s.invert != 0) put("invert", s.invert)
        if (s.brightness != 100) put("brightness", s.brightness)
        if (s.sepia != 0) put("sepia", s.sepia)
        if (s.multiply != PdfColorFilter.MULTIPLY_OFF) put("multiply", Rgba.toHex(s.multiply))
        if (s.screen != PdfColorFilter.SCREEN_OFF) put("screen", Rgba.toHex(s.screen))
        if (s.keepImages) put("keepImages", true)
        if (s.rotation != 0) put("rotation", s.rotation)
        if (s.scrollbar) put("scrollbar", true)
    }

    fun read(o: JSONObject): ViewSettings = ViewSettings(
        mode = ViewingMode.fromId(o.optString("mode", ViewingMode.SINGLE.id)),
        verticalScroll = o.optBoolean("verticalScroll", true),
        freeMovement = o.optBoolean("freeMovement", false),
        contrast = o.optInt("contrast", 100).coerceIn(0, 200),
        invert = o.optInt("invert", 0).coerceIn(0, 100),
        brightness = o.optInt("brightness", 100).coerceIn(0, 200),
        sepia = o.optInt("sepia", 0).coerceIn(0, 200),
        multiply = blendColor(o, "multiply") ?: PdfColorFilter.MULTIPLY_OFF,
        screen = blendColor(o, "screen") ?: PdfColorFilter.SCREEN_OFF,
        keepImages = o.optBoolean("keepImages", false),
        rotation = ViewSettings.normalizeRotation(o.optInt("rotation", 0)),
        scrollbar = o.optBoolean("scrollbar", false),
    )
}

/** A blend-filter colour as `#rrggbb`; null when absent or malformed. Always opaque: alpha is
 *  meaningless for a blend and would otherwise break the identity ("off") comparison. */
private fun blendColor(o: JSONObject, key: String): Rgba? =
    Rgba.fromHex(o.optString(key))?.withAlpha(255)

/**
 * JSON (de)serialization for per-note [ViewOverrides], shared by the per-note view store
 * and the session sidecar. Null (inherit-the-default) fields are omitted on write and an
 * absent key reads back as null, so entries written before a field existed keep
 * inheriting it — including entries from the era when these values were per-note only,
 * whose explicitly-written fields load as overrides.
 */
internal object ViewOverridesJson {

    fun write(o: JSONObject, s: ViewOverrides): JSONObject = o.apply {
        s.mode?.let { put("mode", it.id) }
        s.verticalScroll?.let { put("verticalScroll", it) }
        s.freeMovement?.let { put("freeMovement", it) }
        s.contrast?.let { put("contrast", it) }
        s.invert?.let { put("invert", it) }
        s.brightness?.let { put("brightness", it) }
        s.sepia?.let { put("sepia", it) }
        s.multiply?.let { put("multiply", Rgba.toHex(it)) }
        s.screen?.let { put("screen", Rgba.toHex(it)) }
        s.keepImages?.let { put("keepImages", it) }
        s.rotation?.let { put("rotation", it) }
        s.scrollbar?.let { put("scrollbar", it) }
    }

    fun read(o: JSONObject): ViewOverrides = ViewOverrides(
        mode = if (o.has("mode")) ViewingMode.fromId(o.optString("mode")) else null,
        verticalScroll = if (o.has("verticalScroll")) o.optBoolean("verticalScroll", true) else null,
        freeMovement = if (o.has("freeMovement")) o.optBoolean("freeMovement", false) else null,
        contrast = if (o.has("contrast")) o.optInt("contrast", 100).coerceIn(0, 200) else null,
        invert = if (o.has("invert")) o.optInt("invert", 0).coerceIn(0, 100) else null,
        brightness = if (o.has("brightness")) o.optInt("brightness", 100).coerceIn(0, 200) else null,
        sepia = if (o.has("sepia")) o.optInt("sepia", 0).coerceIn(0, 200) else null,
        multiply = blendColor(o, "multiply"),
        screen = blendColor(o, "screen"),
        keepImages = if (o.has("keepImages")) o.optBoolean("keepImages", false) else null,
        rotation = if (o.has("rotation")) ViewSettings.normalizeRotation(o.optInt("rotation", 0)) else null,
        scrollbar = if (o.has("scrollbar")) o.optBoolean("scrollbar", false) else null,
    )
}
