package com.xnotes.platform

import com.xnotes.canvas.ViewOverrides
import com.xnotes.canvas.ViewSettings
import com.xnotes.canvas.ViewingMode
import com.xnotes.core.model.Rgba
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ViewStateStoreTest {

    private fun store(): JsonStore =
        JsonStore(File(Files.createTempDirectory("xnotes-views").toFile(), "view_states.json"))

    @Test fun roundTripsViewAndOverrides() {
        val json = store()
        val vo = ViewOverrides(
            mode = ViewingMode.DOUBLE,
            verticalScroll = false,
            contrast = 150,
            invert = 100,
            brightness = 60,
            sepia = 10,
            multiply = Rgba(255, 240, 160),
            screen = Rgba(20, 40, 80),
            keepImages = true,
            rotation = 90,
            scrollbar = true,
            freeMovement = true,
        )
        ViewStateStore(json).put("auth|doc1", 1.5, 12.0, 34.0, vo)

        val v = ViewStateStore(json).get("auth|doc1")!!
        assertEquals(1.5, v.zoom, 1e-9)
        assertEquals(12.0, v.scrollX, 1e-9)
        assertEquals(34.0, v.scrollY, 1e-9)
        assertEquals(vo, v.overrides)
    }

    @Test fun legacyEntryWithoutOverridesInheritsEverything() {
        val json = store()
        // An entry written before the View menu existed: zoom/scroll only.
        json.write(JSONObject().put("auth|old", JSONObject().put("zoom", 2.0).put("scrollX", 5.0).put("scrollY", 7.0)))

        val v = ViewStateStore(json).get("auth|old")!!
        assertEquals(2.0, v.zoom, 1e-9)
        assertEquals(ViewOverrides(), v.overrides)
        assertNull(v.overrides.invert) // every field inherits the global defaults
    }

    @Test fun perNoteEraEntryLoadsItsFieldsAsOverrides() {
        val json = store()
        // An entry from when settings were per-note only: explicitly-written fields
        // (non-defaults back then) must survive as overrides.
        json.write(
            JSONObject().put(
                "auth|v1",
                JSONObject().put("zoom", 1.0).put("scrollX", 0.0).put("scrollY", 0.0)
                    .put("invert", 100).put("rotation", 180),
            ),
        )

        val vo = ViewStateStore(json).get("auth|v1")!!.overrides
        assertEquals(100, vo.invert)
        assertEquals(180, vo.rotation)
        assertNull(vo.contrast)
        assertNull(vo.mode)
        // Resolved against defaults, the overrides shadow just those two fields.
        val resolved = vo.resolve(ViewSettings(contrast = 130))
        assertEquals(100, resolved.invert)
        assertEquals(180, resolved.rotation)
        assertEquals(130, resolved.contrast)
    }

    @Test fun inheritedFieldsAreOmittedFromJson() {
        val json = store()
        ViewStateStore(json).put("auth|doc", 1.0, 0.0, 0.0, ViewOverrides())
        val entry = json.read().getJSONObject("auth|doc")
        assertEquals(setOf("zoom", "scrollX", "scrollY"), entry.keys().asSequence().toSet())
    }
}
