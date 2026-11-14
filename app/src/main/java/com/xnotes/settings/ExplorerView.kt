package com.xnotes.settings

import org.json.JSONObject

/** The explorer's ways of laying out a folder. */
enum class ExplorerLayout(val id: String) {
    GRID("grid"),
    GALLERY("gallery"),
    LIST("list"),
    COLUMNS("columns"),
    TREE("tree"),
    TIMELINE("timeline");

    companion object {
        fun fromId(id: String?): ExplorerLayout? = entries.firstOrNull { it.id == id }
    }
}

/** How big the explorer draws its tiles; each step is fewer, larger columns. */
enum class TileSize(val id: String, val label: String) {
    S("s", "S"),
    M("m", "M"),
    L("l", "L"),
    XL("xl", "XL");

    companion object {
        fun fromId(id: String?): TileSize = entries.firstOrNull { it.id == id } ?: M
    }
}

/** A tile's thumbnail: the top of the first page, cropped square, or the whole first page. */
enum class ThumbShape(val id: String) {
    TOP("top"),
    PAGE("page");

    companion object {
        fun fromId(id: String?): ThumbShape = entries.firstOrNull { it.id == id } ?: TOP
    }
}

/** What the explorer puts its items under a heading by. */
enum class GroupBy(val id: String) {
    NONE("none"),
    DATE("date"),
    KIND("kind"),
    COLOUR("colour");

    companion object {
        fun fromId(id: String?): GroupBy = entries.firstOrNull { it.id == id } ?: DATE
    }
}

/** Where folders go among the files: in their own row above them, sorted in with them, or left out. */
enum class FolderPlacement(val id: String) {
    TOP("top"),
    MIXED("mixed"),
    HIDDEN("hidden");

    companion object {
        fun fromId(id: String?): FolderPlacement = entries.firstOrNull { it.id == id } ?: TOP
    }
}

/** Everything the View options popover sets for a folder. Loading is forgiving: anything missing takes its default. */
data class ExplorerView(
    val layout: ExplorerLayout = ExplorerLayout.GRID,
    val tileSize: TileSize = TileSize.M,
    val thumb: ThumbShape = ThumbShape.TOP,
    val showKind: Boolean = true,
    val showPages: Boolean = true,
    val showTime: Boolean = true,
    val showSize: Boolean = false,
    val showColour: Boolean = true,
    val groupBy: GroupBy = GroupBy.DATE,
    val sortKey: ExplorerSortKey = ExplorerSortKey.MODIFIED,
    val descending: Boolean = true,
    val folders: FolderPlacement = FolderPlacement.TOP,
    /** List rows without thumbnails, so more fit on screen. */
    val compactRows: Boolean = false,
    /** The Timeline places files on the day they were created, or else the day they were last saved. */
    val timelineByCreated: Boolean = true,
    /** Whether Tree lists notes and canvases alongside its folders. */
    val treeShowFiles: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("layout", layout.id)
        .put("tile_size", tileSize.id)
        .put("thumb", thumb.id)
        .put("show_kind", showKind)
        .put("show_pages", showPages)
        .put("show_time", showTime)
        .put("show_size", showSize)
        .put("show_colour", showColour)
        .put("group_by", groupBy.id)
        .put("sort_key", sortKey.id)
        .put("descending", descending)
        .put("folders", folders.id)
        .put("compact_rows", compactRows)
        .put("timeline_by_created", timelineByCreated)
        .put("tree_show_files", treeShowFiles)

    companion object {
        fun fromJson(o: JSONObject?): ExplorerView {
            if (o == null) return ExplorerView()
            return ExplorerView(
                layout = ExplorerLayout.fromId(o.optString("layout")) ?: ExplorerLayout.GRID,
                tileSize = TileSize.fromId(o.optString("tile_size")),
                thumb = ThumbShape.fromId(o.optString("thumb")),
                showKind = o.optBoolean("show_kind", true),
                showPages = o.optBoolean("show_pages", true),
                showTime = o.optBoolean("show_time", true),
                showSize = o.optBoolean("show_size", false),
                showColour = o.optBoolean("show_colour", true),
                groupBy = GroupBy.fromId(o.optString("group_by")),
                sortKey = ExplorerSortKey.fromId(o.optString("sort_key", "modified")),
                descending = o.optBoolean("descending", true),
                folders = FolderPlacement.fromId(o.optString("folders")),
                compactRows = o.optBoolean("compact_rows", false),
                timelineByCreated = o.optBoolean("timeline_by_created", true),
                treeShowFiles = o.optBoolean("tree_show_files", false),
            )
        }
    }
}

/** A note or canvas opened on this device, newest first in [Settings.recentDocs]. */
data class RecentDoc(val uri: String, val name: String, val opened: Long)
