package com.xnotes.settings

import com.xnotes.core.model.Orientation
import com.xnotes.core.model.PageSize
import com.xnotes.core.model.Rgba
import com.xnotes.core.util.NameTemplate
import org.json.JSONObject

/**
 * Global, document-independent preferences (spec 09 §4). Forgiving load: every
 * field falls back to its default if missing or malformed.
 */
data class Preferences(
    val uiAppearance: String = "system", // "system" (follows OS dark/light) | "dark" | "light" | "oled"
    val materialMode: MaterialColourMode = MaterialColourMode.DUAL,
    val materialSingleSeed: Rgba = DEFAULT_MATERIAL_SINGLE,
    val materialDualSeed: Rgba = DEFAULT_MATERIAL_DUAL,
    val materialStyle: MaterialStyle = MaterialStyle.TONAL_SPOT,
    /** Material contrast level, -1 (reduced) .. 0 (standard) .. 1 (high). */
    val materialContrast: Double = 0.0,
    val materialSurfaceSeed: Rgba = DEFAULT_MATERIAL_SURFACE,
    val cornerStyle: CornerStyle = CornerStyle.ROUNDED,
    val toolbarLook: ToolbarLook = ToolbarLook(),
    val hideWindowDecoration: Boolean = false,
    val pageColor: Rgba? = null, // null ⇒ follow theme paper
    val pageTemplatePdf: String? = null,
    val defaultTemplate: String = "color", // "color" | "pdf"
    val defaultPageSize: PageSize = PageSize.A4,
    /** Filename template for new notes; see [com.xnotes.core.util.NameTemplate]. */
    val newNoteNameTemplate: String = NameTemplate.DEFAULT,
    val defaultPageOrientation: Orientation = Orientation.PORTRAIT,
    /** Width of a [PageSize.CUSTOM] page, in millimetres. Ignored by every named size. */
    val customPageWidthMm: Double = 210.0,
    /** Height of a [PageSize.CUSTOM] page, in millimetres. Ignored by every named size. */
    val customPageHeightMm: Double = 297.0,
    /** Whether a finger draws (true) or pans (false, default). The stylus always draws. */
    val fingerDraws: Boolean = false,
    /** Panning allowed while zoom is locked: "single" (default) | "double" | "none". */
    val zoomLockPan: String = "single",
    /** Whether holding a freehand ink stroke still snaps it to a recognized shape. */
    val detectShapes: Boolean = false,
    /** Whether rotating a selection snaps to the nearest 90 degrees. */
    val snapRotation90: Boolean = false,
    /** Language for handwriting-to-text (Convert to text): BCP-47 tag, "cs" (Czech) | "en" (English). */
    val ocrLanguage: String = "cs",
    /** Tool the stylus side button activates while held; "none" disables it. */
    val penButtonTool: String = "eraser",
    /** Whether the side-button tool also activates during hover (no contact needed); eraser/pan only. */
    val penButtonHover: Boolean = false,
    /** Action mapped to a clean two-finger tap; "none" (default) disables it. */
    val twoFingerTap: String = "none",
    /** Action mapped to a clean three-finger tap; "none" (default) disables it. */
    val threeFingerTap: String = "none",
    /** Action mapped to a stylus barrel double-tap (pens with no side button, e.g. Huawei M-Pencil
     *  whose double-tap arrives as keycode 718); "none" (default) disables it. */
    val stylusDoubleTap: String = "none",
    /** Action mapped to a momentary stylus side-button click (pens that report the button as a vendor
     *  key press rather than a held state, e.g. HONOR Magic-Pencil whose click arrives as keycode
     *  333); "none" (default) disables it. */
    val stylusButtonTap: String = "none",
    /** Horizontal margin (px) on each side of the page column; 0 ⇒ fit-width fills the screen. */
    val sideMargin: Double = 16.0,
    /** Whether the hairline outline around every page is left undrawn. */
    val hidePageBorders: Boolean = false,
    /** User zoom floor: when enabled, no zoom path goes below [minZoomPercent]%. */
    val minZoomEnabled: Boolean = false,
    val minZoomPercent: Int = 50,
    /** User zoom ceiling: when enabled, no zoom path goes above [maxZoomPercent]%. */
    val maxZoomEnabled: Boolean = false,
    val maxZoomPercent: Int = 400,
    /** Infinite canvas zoom range, as percentages. Far wider than a page's, since a canvas is
     *  read at both a wall's scale and a hair's. */
    val canvasMinZoomPercent: Int = 2,
    val canvasMaxZoomPercent: Int = 6400,
    /** Long-edge cap (px) for the on-screen page cache; higher holds more of the page ready at deep zoom. */
    val maxCacheResolution: Int = 2048,
    /** Whether wet ink keeps off the front buffer, so every stroke takes the ordinary canvas path. */
    val disableFrontBuffering: Boolean = false,
    /** Open in fullscreen; null ⇒ auto (on unless the display has a camera cutout). */
    val startFullscreen: Boolean? = null,
    /** An imported Helix code theme's file path, or null for the built-in colours. */
    val codeThemePath: String? = null,
    /** The imported code theme's original file name, shown in preferences. */
    val codeThemeName: String? = null,
    /** Language "Paste as Code" assigns to pasted blocks; "plain" pastes unhighlighted. */
    val defaultCodeLanguage: String = "cpp",
    /** Language the format bar's code toggle last applied; "" until a language is picked. */
    val lastCodeLanguage: String = "",
    // Filen cloud sync (opt-in). Credentials live in the encrypted FilenSecureStore, not here.
    val filenSyncEnabled: Boolean = false,
    val filenFolderUuid: String = "",
    val filenFolderName: String = "",
    val filenAutoSync: Boolean = true,
    val filenWifiOnly: Boolean = false,
    val filenSyncIntervalMinutes: Int = 60,
    val filenSyncOnNoteExit: Boolean = false,
    val filenSyncOnAppOpen: Boolean = false,
    /** Which way notes flow: "both", "up" (local to Filen), "down" (Filen to local), "off". */
    val filenSyncDirection: String = "both",
    /** Whether typed markdown markers (# - ** `) convert the text as you write. */
    val markdownInput: Boolean = true,
    /** Whether typing "/" at a word start opens the command menu. */
    val slashCommands: Boolean = true,
    /** Layouts the explorer header's switcher offers, in switcher order. */
    val switcherLayouts: List<ExplorerLayout> = ExplorerLayout.entries,
    val sidebarRecent: Boolean = true,
    val sidebarPinned: Boolean = true,
    val sidebarColours: Boolean = true,
    val sidebarTrash: Boolean = true,
    val sidebarFiles: Boolean = true,
    val sidebarSync: Boolean = true,
    val sidebarAbout: Boolean = true,
    /** Where Home opens: "top" (the notes folder), "last" (the folder last shown) or "shelves" (the notes folder under Recent and Pinned). */
    val homeOpensTo: String = "top",
    /** How the explorer writes dates: "relative" (2 days ago), "day" (Wed 17:30) or "date" (16 Sep 2026). */
    val dateStyle: String = "day",
    /** Tapping a file previews it instead of opening it. */
    val tapPreviews: Boolean = false,
    /** Days a deleted item waits in Trash: 0 deletes at once, [TRASH_FOREVER] keeps it until Trash is emptied. */
    val trashDays: Int = 30,
    /** Each folder keeps its own view instead of every folder sharing one. */
    val perFolderViews: Boolean = false,
    val showCreateButton: Boolean = true,
    val showFolderCounts: Boolean = true,
    val showExtensions: Boolean = false,
    /** Hides files and folders whose name starts with a dot (.obsidian, .git) from the explorer. */
    val hideDotItems: Boolean = true,
    /** The reader's light/dark choice; null follows the app theme. Never written to the file. */
    val readerDark: Boolean? = null,
    /** Whether an exported PDF gets a bookmark for each heading of the note's text. */
    val pdfHeadingBookmarks: Boolean = true,
) {
    /**
     * A new note's page size in document pixels. A named size is laid out under
     * [orientation]; a custom one is taken as typed, since the two fields already say
     * which way round the page goes.
     */
    fun newPagePixels(dpi: Int = PageSize.DEFAULT_DPI, orientation: Orientation = defaultPageOrientation): Pair<Double, Double> =
        if (defaultPageSize == PageSize.CUSTOM) {
            PageSize.mmToPx(customPageWidthMm, dpi) to PageSize.mmToPx(customPageHeightMm, dpi)
        } else {
            defaultPageSize.pixels(orientation, dpi)
        }

    fun toJson(): JSONObject = JSONObject()
        .put("ui_appearance", uiAppearance)
        .put("hide_window_decoration", hideWindowDecoration)
        .put("page_color", pageColor?.let { Rgba.toHex(it) } ?: JSONObject.NULL)
        .put("page_template_pdf", pageTemplatePdf ?: JSONObject.NULL)
        .put("default_template", defaultTemplate)
        .put("default_page_size", defaultPageSize.displayName)
        .put("new_note_name_template", newNoteNameTemplate)
        .put("default_page_orientation", defaultPageOrientation.toName())
        .put("custom_page_width_mm", customPageWidthMm)
        .put("custom_page_height_mm", customPageHeightMm)
        .put("finger_draws", fingerDraws)
        .put("zoom_lock_pan", zoomLockPan)
        .put("detect_shapes", detectShapes)
        .put("snap_rotation_90", snapRotation90)
        .put("ocr_language", ocrLanguage)
        .put("pen_button_tool", penButtonTool)
        .put("pen_button_hover", penButtonHover)
        .put("two_finger_tap", twoFingerTap)
        .put("three_finger_tap", threeFingerTap)
        .put("stylus_double_tap", stylusDoubleTap)
        .put("stylus_button_tap", stylusButtonTap)
        .put("side_margin", sideMargin)
        .put("hide_page_borders", hidePageBorders)
        .put("min_zoom_enabled", minZoomEnabled)
        .put("min_zoom_percent", minZoomPercent)
        .put("max_zoom_enabled", maxZoomEnabled)
        .put("max_zoom_percent", maxZoomPercent)
        .put("canvas_min_zoom_percent", canvasMinZoomPercent)
        .put("canvas_max_zoom_percent", canvasMaxZoomPercent)
        .put("max_cache_resolution", maxCacheResolution)
        .put("disable_front_buffering", disableFrontBuffering)
        .apply {
            if (materialMode != MaterialColourMode.SYSTEM) put("material_mode", materialMode.id)
            if (materialSingleSeed != DEFAULT_MATERIAL_SINGLE) put("material_single_seed", Rgba.toHex(materialSingleSeed))
            if (materialDualSeed != DEFAULT_MATERIAL_DUAL) put("material_dual_seed", Rgba.toHex(materialDualSeed))
            // Keep the active colours readable by versions with a shared accent seed.
            when (materialMode) {
                MaterialColourMode.SYSTEM -> Unit
                MaterialColourMode.SINGLE -> put("material_seed", Rgba.toHex(materialSingleSeed))
                MaterialColourMode.DUAL -> put("material_seed", Rgba.toHex(materialDualSeed)).put("material_dual_tone", true)
            }
            if (materialStyle != MaterialStyle.TONAL_SPOT) put("material_style", materialStyle.id)
            if (materialContrast != 0.0) put("material_contrast", materialContrast)
            if (materialMode == MaterialColourMode.DUAL || materialSurfaceSeed != DEFAULT_MATERIAL_SURFACE) {
                put("material_surface_seed", Rgba.toHex(materialSurfaceSeed))
            }
            if (cornerStyle != CornerStyle.ROUNDED) put("corner_style", cornerStyle.id)
            if (toolbarLook.position != ToolbarPosition.TOP) put("toolbar_position", toolbarLook.position.id)
            if (toolbarLook.size != ToolbarSize.REGULAR) put("toolbar_size", toolbarLook.size.id)
            if (toolbarLook.floating) put("toolbar_floating", true)
            startFullscreen?.let { put("start_fullscreen", it) }
            codeThemePath?.let { put("code_theme_path", it) }
            codeThemeName?.let { put("code_theme_name", it) }
            put("default_code_language", defaultCodeLanguage)
            lastCodeLanguage.takeIf { it.isNotEmpty() }?.let { put("last_code_language", it) }
            put("filen_sync_enabled", filenSyncEnabled)
            put("filen_folder_uuid", filenFolderUuid)
            put("filen_folder_name", filenFolderName)
            put("filen_auto_sync", filenAutoSync)
            put("filen_wifi_only", filenWifiOnly)
            put("filen_sync_interval_minutes", filenSyncIntervalMinutes)
            put("filen_sync_on_note_exit", filenSyncOnNoteExit)
            put("filen_sync_on_app_open", filenSyncOnAppOpen)
            put("filen_sync_direction", filenSyncDirection)
            put("markdown_input", markdownInput)
            put("slash_commands", slashCommands)
        }
        .put("switcher_layouts", org.json.JSONArray().apply { switcherLayouts.forEach { put(it.id) } })
        .put("sidebar_recent", sidebarRecent)
        .put("sidebar_pinned", sidebarPinned)
        .put("sidebar_colours", sidebarColours)
        .put("sidebar_trash", sidebarTrash)
        .put("sidebar_files", sidebarFiles)
        .put("sidebar_sync", sidebarSync)
        .put("sidebar_about", sidebarAbout)
        .put("home_opens_to", homeOpensTo)
        .put("date_style", dateStyle)
        .put("tap_previews", tapPreviews)
        .put("trash_days", trashDays)
        .put("per_folder_views", perFolderViews)
        .put("show_create_button", showCreateButton)
        .put("show_folder_counts", showFolderCounts)
        .put("show_extensions", showExtensions)
        .put("hide_dot_items", hideDotItems)
        .apply { readerDark?.let { put("reader_dark", it) } }
        .put("pdf_heading_bookmarks", pdfHeadingBookmarks)

    companion object {
        val DEFAULT_ACCENT = Rgba(0, 230, 118, 255)
        val DEFAULT_MATERIAL_SINGLE = Rgba(244, 67, 54, 255)
        val DEFAULT_MATERIAL_DUAL = Rgba(154, 124, 66, 255)
        val DEFAULT_MATERIAL_SURFACE = Rgba(61, 117, 230, 255)

        /** [trashDays] for keeping deleted items until Trash is emptied by hand. */
        const val TRASH_FOREVER = -1

        /** Settable range of a custom page's sides, in millimetres. */
        const val CUSTOM_PAGE_MIN_MM = 10.0
        const val CUSTOM_PAGE_MAX_MM = 2000.0

        /** Settable range of the min/max zoom limits, in percent (within the hard zoom bounds). */
        const val ZOOM_LIMIT_MIN_PCT = 20
        const val ZOOM_LIMIT_MAX_PCT = 1600

        fun fromJson(o: JSONObject?): Preferences {
            if (o == null) return Preferences()
            val appearance = o.optString("ui_appearance", "system")
                .let { if (it == "dark" || it == "light" || it == "oled") it else "system" }
            val template = o.optString("default_template", "color").let { if (it == "pdf") "pdf" else "color" }
            val zoomLockPan = o.optString("zoom_lock_pan", "single").let { if (it == "double" || it == "none") it else "single" }
            val tapActions = setOf("none", "undo", "redo", "toggle_pan", "toggle_eraser", "toggle_previous")
            fun tapAction(key: String) = o.optString(key, "none").let { if (it in tapActions) it else "none" }
            val legacySeed = Rgba.fromHex(o.optString("material_seed"))
            val legacyDual = o.optBoolean("material_dual_tone", false)
            val materialMode = MaterialColourMode.fromId(o.optString("material_mode")) ?: when {
                legacyDual -> MaterialColourMode.DUAL
                legacySeed != null -> MaterialColourMode.SINGLE
                else -> MaterialColourMode.SYSTEM
            }
            val legacyDualAccent = legacySeed ?: Rgba.fromHex(o.optString("accent_color")) ?: DEFAULT_ACCENT
            return Preferences(
                uiAppearance = appearance,
                materialMode = materialMode,
                materialSingleSeed = Rgba.fromHex(o.optString("material_single_seed"))
                    ?: legacySeed?.takeIf { !legacyDual } ?: DEFAULT_MATERIAL_SINGLE,
                materialDualSeed = Rgba.fromHex(o.optString("material_dual_seed"))
                    ?: if (legacyDual) legacyDualAccent else DEFAULT_MATERIAL_DUAL,
                materialStyle = MaterialStyle.fromId(o.optString("material_style")),
                materialContrast = o.optDouble("material_contrast", 0.0).takeIf { it in -1.0..1.0 } ?: 0.0,
                materialSurfaceSeed = Rgba.fromHex(o.optString("material_surface_seed"))
                    ?: if (legacyDual && !o.has("material_mode")) Rgba(33, 150, 243, 255) else DEFAULT_MATERIAL_SURFACE,
                cornerStyle = CornerStyle.fromId(o.optString("corner_style")),
                toolbarLook = ToolbarLook(
                    position = ToolbarPosition.fromId(o.optString("toolbar_position")),
                    size = ToolbarSize.fromId(o.optString("toolbar_size")),
                    floating = o.optBoolean("toolbar_floating", false),
                ),
                hideWindowDecoration = o.optBoolean("hide_window_decoration", false),
                pageColor = if (o.isNull("page_color")) null else Rgba.fromHex(o.optString("page_color")),
                pageTemplatePdf = if (o.isNull("page_template_pdf")) null else o.optString("page_template_pdf").ifEmpty { null },
                defaultTemplate = template,
                defaultPageSize = PageSize.fromName(o.optString("default_page_size", "A4")),
                newNoteNameTemplate = o.optString("new_note_name_template", NameTemplate.DEFAULT)
                    .ifBlank { NameTemplate.DEFAULT },
                defaultPageOrientation = Orientation.fromName(o.optString("default_page_orientation", "portrait")),
                customPageWidthMm = o.optDouble("custom_page_width_mm", 210.0)
                    .coerceIn(CUSTOM_PAGE_MIN_MM, CUSTOM_PAGE_MAX_MM),
                customPageHeightMm = o.optDouble("custom_page_height_mm", 297.0)
                    .coerceIn(CUSTOM_PAGE_MIN_MM, CUSTOM_PAGE_MAX_MM),
                fingerDraws = o.optBoolean("finger_draws", false),
                zoomLockPan = zoomLockPan,
                detectShapes = o.optBoolean("detect_shapes", false),
                snapRotation90 = o.optBoolean("snap_rotation_90", false),
                ocrLanguage = o.optString("ocr_language", "cs").ifEmpty { "cs" },
                penButtonTool = o.optString("pen_button_tool", "eraser").ifEmpty { "eraser" },
                penButtonHover = o.optBoolean("pen_button_hover", false),
                twoFingerTap = tapAction("two_finger_tap"),
                threeFingerTap = tapAction("three_finger_tap"),
                stylusDoubleTap = tapAction("stylus_double_tap"),
                stylusButtonTap = tapAction("stylus_button_tap"),
                sideMargin = o.optDouble("side_margin", 16.0).coerceIn(0.0, 80.0),
                hidePageBorders = o.optBoolean("hide_page_borders", false),
                minZoomEnabled = o.optBoolean("min_zoom_enabled", false),
                minZoomPercent = o.optInt("min_zoom_percent", 50).coerceIn(ZOOM_LIMIT_MIN_PCT, ZOOM_LIMIT_MAX_PCT),
                maxZoomEnabled = o.optBoolean("max_zoom_enabled", false),
                maxZoomPercent = o.optInt("max_zoom_percent", 400).coerceIn(ZOOM_LIMIT_MIN_PCT, ZOOM_LIMIT_MAX_PCT),
                canvasMinZoomPercent = o.optInt("canvas_min_zoom_percent", 2).coerceIn(1, 100),
                canvasMaxZoomPercent = o.optInt("canvas_max_zoom_percent", 6400).coerceIn(200, 100000),
                maxCacheResolution = o.optInt("max_cache_resolution", 2048).coerceIn(1024, 4096),
                disableFrontBuffering = o.optBoolean("disable_front_buffering", false),
                startFullscreen = if (o.has("start_fullscreen")) o.getBoolean("start_fullscreen") else null,
                codeThemePath = o.optString("code_theme_path").ifEmpty { null },
                codeThemeName = o.optString("code_theme_name").ifEmpty { null },
                defaultCodeLanguage = o.optString("default_code_language", "cpp")
                    .lowercase().trim().ifEmpty { "cpp" },
                lastCodeLanguage = o.optString("last_code_language").lowercase().trim(),
                filenSyncEnabled = o.optBoolean("filen_sync_enabled", false),
                filenFolderUuid = o.optString("filen_folder_uuid"),
                filenFolderName = o.optString("filen_folder_name"),
                filenAutoSync = o.optBoolean("filen_auto_sync", true),
                filenWifiOnly = o.optBoolean("filen_wifi_only", false),
                filenSyncIntervalMinutes = o.optInt("filen_sync_interval_minutes", 60).coerceIn(15, 1440),
                filenSyncOnNoteExit = o.optBoolean("filen_sync_on_note_exit", false),
                filenSyncOnAppOpen = o.optBoolean("filen_sync_on_app_open", false),
                filenSyncDirection = o.optString("filen_sync_direction", "both")
                    .let { if (it == "up" || it == "down" || it == "off") it else "both" },
                markdownInput = o.optBoolean("markdown_input", true),
                slashCommands = o.optBoolean("slash_commands", true),
                switcherLayouts = o.optJSONArray("switcher_layouts")?.let { a ->
                    (0 until a.length()).mapNotNull { ExplorerLayout.fromId(a.optString(it)) }.distinct()
                } ?: ExplorerLayout.entries,
                sidebarRecent = o.optBoolean("sidebar_recent", true),
                sidebarPinned = o.optBoolean("sidebar_pinned", true),
                sidebarColours = o.optBoolean("sidebar_colours", true),
                sidebarTrash = o.optBoolean("sidebar_trash", true),
                sidebarFiles = o.optBoolean("sidebar_files", true),
                sidebarSync = o.optBoolean("sidebar_sync", true),
                sidebarAbout = o.optBoolean("sidebar_about", true),
                homeOpensTo = o.optString("home_opens_to", "top").let { if (it == "last" || it == "shelves") it else "top" },
                dateStyle = o.optString("date_style", "day").let { if (it == "relative" || it == "date") it else "day" },
                tapPreviews = o.optBoolean("tap_previews", false),
                trashDays = o.optInt("trash_days", 30).let { if (it == TRASH_FOREVER || it in 0..365) it else 30 },
                perFolderViews = o.optBoolean("per_folder_views", false),
                showCreateButton = o.optBoolean("show_create_button", true),
                showFolderCounts = o.optBoolean("show_folder_counts", true),
                showExtensions = o.optBoolean("show_extensions", false),
                hideDotItems = o.optBoolean("hide_dot_items", true),
                readerDark = if (o.has("reader_dark")) o.optBoolean("reader_dark") else null,
                pdfHeadingBookmarks = o.optBoolean("pdf_heading_bookmarks", true),
            )
        }
    }
}
