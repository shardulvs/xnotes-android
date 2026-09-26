package com.xnotes.ui

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xnotes.canvas.InteractionController
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.history.History
import com.xnotes.core.history.LockItems
import com.xnotes.core.history.RestyleItems
import com.xnotes.core.infinite.AddCanvasItem
import com.xnotes.core.infinite.AddCanvasItems
import com.xnotes.core.infinite.CanvasBackground
import com.xnotes.core.infinite.CanvasSelection
import com.xnotes.core.infinite.CanvasViewport
import com.xnotes.core.infinite.EraseCanvasItems
import com.xnotes.core.infinite.MeshPart
import com.xnotes.core.infinite.MeshedItem
import com.xnotes.core.infinite.Minimap
import com.xnotes.core.infinite.OnCanvas
import com.xnotes.core.infinite.OverlayTessellator
import com.xnotes.core.infinite.ReplaceCanvasItems
import com.xnotes.core.infinite.StrokeTessellator
import com.xnotes.core.infinite.EraseSession
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.infinite.GlowSpec
import com.xnotes.core.infinite.InkPass
import com.xnotes.core.infinite.ItemMesher
import com.xnotes.core.infinite.Waypoint
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.DrawStyle
import com.xnotes.core.model.FunctionSpec
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.deepCopy
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.tools.InkPalette
import com.xnotes.core.tools.ShapeConfig
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.core.vector.VectorMesher
import com.xnotes.gl.CanvasScene
import com.xnotes.gl.InfiniteCanvasView
import com.xnotes.ui.theme.Palette

/**
 * The infinite canvas's orchestrator, mirroring [Editor]'s role for the paged notebook: it owns the
 * document, the view, the history stack and the gesture layer, and exposes the Compose-observable
 * state the chrome reads.
 *
 * It is a sibling of [Editor], not a mode inside it. [Editor] is already the largest file in the
 * project and every file operation would have grown a document-type branch. It also deliberately
 * creates no temp directories of its own at construction: [Editor]'s constructor purges the shared
 * ones, so a second object doing the same would delete the open note's live files.
 */
@Stable
class InfiniteEditor(context: Context) : ToolPopupHost, SelectionMenuHost, LongPressMenuHost, ToastHost {

    private val appContext = context.applicationContext

    var document: InfiniteDocument = InfiniteDocument()
        private set

    val history = History()

    val view = InfiniteCanvasView(appContext)

    /** The front buffer wet ink goes into, above [view] and transparent whenever no pen is down. */
    val pad = com.xnotes.gl.GlWetPad(appContext)

    /**
     * The two surfaces, in order. They are siblings rather than one view because the canvas needs a
     * multisampled config and the front buffer cannot have one.
     */
    val surfaces = android.widget.FrameLayout(appContext).apply {
        addView(view, android.widget.FrameLayout.LayoutParams(-1, -1))
        addView(pad, android.widget.FrameLayout.LayoutParams(-1, -1))
    }

    val viewport: CanvasViewport get() = view.viewport

    val interaction = InfiniteInteraction(
        viewport = view.viewport,
        requestRender = { view.publish() },
        onViewChanged = { onViewChanged() },
        setInteractive = { active, linger -> view.setInteractive(active, linger) },
        configFor = { configFor(it) },
        onWetStroke = { publishWetStroke(it) },
        onCommitStroke = { commitStroke(it) },
        onEraseBegin = { EraseSession(document) },
        onEraseEnd = { commitErase(it) },
        onEraserCursor = { at, radius -> view.setEraserCursor(at, radius) },
        onPendingShape = { publishPendingShape(it) },
        onCommitShape = { commitItem(it) },
        shapeConfig = { shapeConfig },
        inkColor = { inkColor },
        detectShapes = { detectShapes },
        selection = { selection },
        itemsIn = { rect -> document.itemsIn(rect) },
        onSelectionChanged = { publishOverlay() },
        onCommitSelection = { commitSelection(it) },
        onLiftSelection = { items, at -> scene.setLift(items, at) },
        devicePxPerDp = { devicePxPerDp },
        onMinimapPress = { vx, vy -> minimapTap(vx, vy) },
        onContextMenu = { vp, content, locked -> contextMenu = ContextMenuTarget(vp.x, vp.y, content, locked) },
        hasPasteContent = { hasClipboardItems || clipboardHasImage },
        onToolChanged = { adoptTool(it) },
    )

    private val devicePxPerDp = appContext.resources.displayMetrics.density.toDouble()
    private val imageCodec = com.xnotes.platform.AndroidImageCodec()

    /** Per-tool style, with the toolbar's active ink colour folded in at draw time. */
    private val toolConfigs = HashMap<Tool, ToolConfig>()

    /** The armed tool, mirrored into Compose so the toolbar can show which one it is. */
    var tool by mutableStateOf(Tool.PEN)
        private set

    /** The active ink colour, used by any tool without a colour override of its own. */
    var inkColor by mutableStateOf(InkPalette.DEFAULT)
        private set

    /** The shape tool's style. */
    var shapeConfig by mutableStateOf(ShapeConfig())
        private set

    /** Whether a held freehand stroke may snap to the shape it looks like. */
    var detectShapes by mutableStateOf(true)
        private set

    /** Live zoom, mirrored into Compose so a readout can follow a pinch frame by frame. */
    var zoomPercent by mutableStateOf(100)
        private set

    /** Zoom lock: pinch and the zoom buttons stop changing the zoom, as in the paged editor. */
    var zoomLocked by mutableStateOf(false)
        private set

    var title by mutableStateOf("Untitled")
        private set

    var canUndo by mutableStateOf(false)
        private set

    var canRedo by mutableStateOf(false)
        private set

    /** Set when the GL surface refuses to come up, so the host can say so instead of showing black. */
    var renderFailure by mutableStateOf<String?>(null)
        private set

    /** Set while some items are left undrawn because the device could not spare the memory. */
    var outOfMemory by mutableStateOf(false)
        private set

    /** Fired after any edit that makes the document dirty, so the host can schedule an autosave. */
    var onContentChanged: (() -> Unit)? = null

    /** Whether the minimap is shown. */
    var minimapVisible by mutableStateOf(true)
        private set

    fun toggleMinimap() {
        minimapVisible = !minimapVisible
        view.minimapVisible = minimapVisible
    }

    /**
     * Disappearing ink: strokes are held on screen and melt away instead of joining the document.
     * The same hold and fade the paged canvas uses, so the wand behaves alike on either surface.
     */
    var wandEnabled by mutableStateOf(false)
        private set

    fun toggleWand() {
        wandEnabled = !wandEnabled
        interaction.wandEnabled = wandEnabled
        if (!wandEnabled) clearFading()
    }

    /** Saved views, mirrored into Compose so the chrome can list them. */
    var waypoints by mutableStateOf<List<Waypoint>>(emptyList())
        private set

    /** Whether the debug HUD is up; toggled by a four-finger tap, as on the paged canvas. */
    var debugVisible by mutableStateOf(false)
        private set

    fun toggleDebug() {
        debugVisible = !debugVisible
        view.publish()
    }

    /** What is selected, and the arithmetic of moving, scaling and rotating it. */
    var selection = CanvasSelection(document)
        private set

    /** True while anything is selected, so the chrome can offer the actions that need one. */
    var hasSelection by mutableStateOf(false)
        private set

    override var toastText by mutableStateOf<String?>(null)
        private set
    override var toastToken by mutableStateOf(0)
        private set

    /** Flash a brief themed pill at the bottom of the canvas (delete/copy/cut/paste feedback). */
    fun showToast(text: String) {
        toastText = text
        toastToken++
    }

    /** Set while cut composes copy+delete internally, so only the "Cut" pill shows. */
    private var suppressToast = false

    /** The GL-side mirror of the document. Fed by [modelListener]; never reads the model itself. */
    private val scene = CanvasScene()

    /** One low-priority thread decoding images, so a big photo never stalls a frame. */
    private val decodeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "xnotes-canvas-decode").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    /** Where inserted images are written; supplied by the host so both editors share one dir. */
    var imageDir: java.io.File? = null

    /**
     * Keeps the renderer in step with the model. Every mutation goes through [InfiniteDocument],
     * including the ones history performs, so undo and redo repaint through exactly this path with
     * nothing extra to remember.
     */
    private val modelListener = object : InfiniteDocument.Listener {
        override fun onItemAdded(item: CanvasItem) {
            pushItem(item)
        }

        override fun onItemRemoved(item: CanvasItem) {
            // Drop any mesh job still running for it, so its result cannot file the record again.
            if (item is ImageItem) vectorMeshGen.remove(item)
            scene.remove(item)
        }

        override fun onOrderChanged() {
            // Once per structural edit rather than once per item, so an eraser drag that cuts a
            // dozen strokes publishes one ordering instead of a dozen.
            scene.setOrder(document.items)
            view.publish()
        }

        override fun onItemChanged(item: CanvasItem) {
            pushItem(item)
            view.publish()
        }

        override fun onReset() {
            rebuildScene()
            view.publish()
        }
    }

    init {
        view.input = { interaction.onTouch(it) }
        view.genericMotion = { interaction.onGenericMotion(it) }
        view.afterLayout = { applyInitialView() }
        view.onContextReady = { renderFailure = view.failure }
        pad.onSurfaceLost = { endFrontInk(); settleHeld() }
        view.onFourFingerTap = { toggleDebug() }
        view.minimapVisible = minimapVisible
        view.scene = scene
        // Decoding reads a file and can take tens of milliseconds, so it never runs on the render
        // thread; the finished bitmap is picked up and uploaded at the start of the next frame.
        scene.decodeOn = { work -> decodeExecutor.execute(work) }
        scene.onOutOfMemory = { short -> view.post { outOfMemory = short } }
        document.listener = modelListener
    }

    /**
     * Set only while the stroke under the pen is being committed, so the message that adds it also
     * releases the wet buffer. Two messages would let a frame fall between them and blink.
     */
    private var committingWetStroke = false

    /** Tessellate [item] and hand the triangles to the renderer, or drop it if it draws nothing. */
    private fun pushItem(item: CanvasItem) {
        // Held back while the front buffer is still the only thing showing this stroke. The
        // document and the history already have it; only its triangles wait.
        for (h in heldItems) if (h === item) return
        if (item is ImageItem) {
            if (!pushVectorImage(item)) scene.upsertImage(item, item.paintBounds())
            return
        }
        val started = System.nanoTime()
        val meshed = ItemMesher.mesh(item)
        scene.lastTessellateMs = (System.nanoTime() - started) / 1_000_000.0
        if (meshed == null || meshed.isEmpty) {
            scene.remove(item)
            return
        }
        scene.upsert(item, meshed.parts, meshed.bounds, committingWetStroke)
    }

    /**
     * A placed SVG becomes triangles like everything else, so it stays sharp at any zoom instead of
     * magnifying a texture. Nothing about it is cached as pixels, which is why a pinch costs it
     * nothing.
     *
     * The meshing runs on the decode thread. A thousand-path drawing takes long enough that doing
     * it inline would stall the edit that triggered it, and a placeholder box holds the space
     * meanwhile since there is no texture to stand in. The box also stays put when a document uses
     * only constructs this pipeline does not draw: the gap should be visible, not silent.
     *
     * Returns false for a raster image, which takes the texture path as before.
     */
    private fun pushVectorImage(item: ImageItem): Boolean {
        if (!com.xnotes.platform.ImageDecoder.isVector(item.image.file.path)) return false
        val bounds = item.paintBounds()
        scene.upsert(item, vectorPlaceholder(bounds), bounds)
        val token = vectorMeshSeq.incrementAndGet()
        vectorMeshGen[item] = token
        val rect = item.rect
        val orientation = item.orientation
        val angle = item.angle
        decodeExecutor.execute {
            val started = System.nanoTime()
            val parsed = com.xnotes.platform.VectorScenes.sceneFor(item.image.file)
            val parts = if (parsed == null) {
                emptyList()
            } else {
                VectorMesher.mesh(parsed, rect, orientation, angle, StrokeTessellator.DEFAULT_TOLERANCE)
            }
            val ms = (System.nanoTime() - started) / 1_000_000.0
            // The mesher stops at a vertex ceiling; say so rather than silently drawing part of it.
            if (parts.sumOf { it.mesh.vertexCount } >= VectorMesher.MAX_VERTICES) {
                android.util.Log.w(
                    "InfiniteEditor",
                    "svg drawn only as far as the ${VectorMesher.MAX_VERTICES} vertex ceiling: " +
                        item.image.file.path,
                )
            }
            // Claimed and filed on the main thread, so this cannot land after a removal that was
            // decided while it ran and put the item back on the canvas.
            view.post {
                if (!vectorMeshGen.remove(item, token) || parts.isEmpty()) return@post
                scene.lastTessellateMs = ms
                scene.upsert(item, parts, bounds)
                view.publish()
            }
        }
        return true
    }

    /** The faint box that holds a vector image's place until its triangles land. */
    private fun vectorPlaceholder(bounds: Rect): List<MeshPart> {
        val b = com.xnotes.core.infinite.MeshBuilder(4, 6)
        b.rect(bounds.left, bounds.top, bounds.w, bounds.h)
        return listOf(MeshPart(b.build(), VECTOR_PLACEHOLDER, InkPass.OPAQUE))
    }

    /** Which mesh job owns each vector image's record, so a stale one cannot overwrite a newer. */
    private val vectorMeshGen = java.util.concurrent.ConcurrentHashMap<ImageItem, Long>()
    private val vectorMeshSeq = java.util.concurrent.atomic.AtomicLong()

    /** Re-tessellate the whole document, after a load or a wholesale list replacement. */
    private fun rebuildScene() {
        scene.reset()
        vectorMeshGen.clear()
        scene.batch { for (item in document.items) pushItem(item) }
        scene.setOrder(document.items)
    }

    /** Repaint the canvas with whatever the model currently says. */
    fun requestRender() = view.publish()

    // --- tools ---

    fun armTool(next: Tool) {
        // Leaving the selection tools drops the selection, so its chrome cannot linger over ink.
        if (tool != next && (tool == Tool.SELECT || tool == Tool.LASSO)) interaction.clearSelection()
        adoptTool(next)
    }

    /** Show a tool the gesture layer armed by itself: a long-press grab, and its release. */
    private fun adoptTool(next: Tool) {
        tool = next
        interaction.tool = next
    }

    // --- selection actions ---

    /**
     * The selection's own clipboard, and the actions the floating menu offers.
     *
     * These are the canvas's siblings of the paged controller's, not a reuse of them: the paged ones
     * are written against pages, and every one of them would need a page index that does not exist
     * here. The behaviour they present is the same, which is what the shared [SelectionMenu] holds
     * them to.
     */
    private val clipboard = ArrayList<CanvasItem>()

    /** Whether the clipboard was filled by a cut, which the first paste spends. Same rule as the
     *  paged canvas: a cut moves items, so pasting them back finishes the move. */
    private var clipboardFromCut = false

    /** Only text needs a measurer to clone, and the canvas has none; this satisfies the signature. */
    private val textMeasurer = com.xnotes.platform.AndroidTextMeasurer()

    /** Where the floating menu sits, or null while a gesture is running or nothing is selected. */
    override var selectionMenuRect: Rect? by mutableStateOf(null)
        private set

    /** Re-anchor the floating menu over the settled selection, or take it away. */
    private fun refreshSelectionMenu() {
        val box = selection.box
        if (box == null || interaction.mode != CanvasPointerMode.IDLE) {
            selectionMenuRect = null
            return
        }
        val bounds = Rect.bounding(box.corners().map { viewport.contentToViewport(it) })
        // Lift the anchor's top clear of the rotate grip, which is drawn its arm plus its own
        // radius above the box. Without this the bar lands on the grip and buries it. The bottom
        // stays put, so the fallback placement below the selection is unchanged.
        val clearance = OverlayTessellator.GRIP_ARM_PX + OverlayTessellator.GRIP_PX / 2.0
        // The move grip under a small selection sits the same distance below, so the fallback clears it too.
        val below = if (selection.moveGrip(viewport.zoom, OverlayTessellator.GRIP_ARM_PX / viewport.zoom) != null) clearance else 0.0
        selectionMenuRect = Rect(bounds.x, bounds.y - clearance, bounds.w, bounds.h + clearance + below)
    }

    override fun dismissSelectionMenu() {
        selectionMenuRect = null
    }

    /** Delete whatever is selected, as one undoable edit. */
    override fun deleteSelection() {
        val items = selection.items
        if (items.isEmpty()) return
        if (!suppressToast) showToast("Deleted")
        val command = EraseCanvasItems.capture(document, items)
        document.removeAll(items)
        history.push(command)
        interaction.clearSelection()
        markDirty()
        refresh()
    }

    override fun copySelection() {
        if (selection.isEmpty) return
        if (!suppressToast) showToast("Copied")
        clipboard.clear()
        clipboardFromCut = false
        selection.items.mapTo(clipboard) { it.deepCopy(textMeasurer) }
    }

    override fun cutSelection() {
        if (selection.isEmpty) return
        suppressToast = true
        copySelection()
        clipboardFromCut = true
        deleteSelection()
        suppressToast = false
        showToast("Cut")
    }

    /** Clone the selection a nudge down and right, and leave the copies selected. */
    override fun duplicateSelection() {
        if (selection.isEmpty) return
        val clones = selection.items.map { it.deepCopy(textMeasurer) }
        for (clone in clones) clone.translate(DUPLICATE_NUDGE, DUPLICATE_NUDGE)
        document.addAll(clones)
        history.push(AddCanvasItems(document, clones))
        selection.select(clones)
        markDirty()
        refresh()
        publishOverlay()
    }

    /**
     * Pin the selection where it is, then put the selection away, since a locked item cannot stay
     * selected. A held finger over it is the only way back, and it offers exactly that.
     */
    override fun lockSelection() {
        if (selection.isEmpty) return
        val items = selection.items.toList()
        for (item in items) item.locked = true
        history.push(LockItems(items, true))
        interaction.clearSelection()
        markDirty()
        refresh()
        publishOverlay()
    }

    // Tables are a paged-note feature; the infinite canvas has none.
    override val selectionIsTable: Boolean get() = false
    override val tableEditing: Boolean get() = false
    override fun toggleTableEditMode() {}

    override val selectionSplinePoints: Int get() = selection.spline()?.controlPoints()?.size ?: 0

    override fun editSelectionSpline(add: Boolean) {
        val command = selection.editSpline(add) ?: return
        history.push(command)
        markDirty()
        refresh()
        publishOverlay()
    }

    override val selectionFunction: FunctionSpec? get() = selection.function()?.function

    override fun setSelectionFunction(spec: FunctionSpec): Boolean {
        val command = selection.editFunction(spec) ?: return false
        history.push(command)
        markDirty()
        refresh()
        publishOverlay()
        return true
    }

    // Crop is a paged-note image action; not offered on the infinite canvas.
    override val selectionIsImage: Boolean get() = false
    override fun cropSelection() {}

    // Text boxes and convert-to-text are paged-note actions for now; not offered on the infinite canvas.
    override val selectionIsText: Boolean get() = false
    override fun editSelectionText() {}

    // Hyperlinks on items are a paged-note action for now; not offered on the infinite canvas.
    override val selectionCanLink: Boolean get() = false
    override val selectionLink: String? get() = null
    override fun setSelectionLink(url: String?) {}

    override val selectionHasInk: Boolean get() = false
    override fun convertSelectionToText() {}
    override val canConvertToMath: Boolean get() = false
    override val selectionMath: String? get() = null
    override fun setSelectionMath(latex: String): Boolean = false
    override fun convertSelectionToMath() {}

    override fun unlockItem(item: CanvasItem) {
        if (!item.locked) return
        item.locked = false
        history.push(LockItems(listOf(item), false))
        markDirty()
        refresh()
    }

    /** Paste the clipboard at [atContent], or a nudge from where it was copied. */
    fun pasteClipboard(atContent: Pt? = null) {
        if (clipboard.isEmpty()) return
        val clones = clipboard.map { it.deepCopy(textMeasurer) }
        var box: Rect? = null
        for (clone in clones) box = box?.union(clone.bounds()) ?: clone.bounds()
        val bounds = box ?: return
        val dx: Double
        val dy: Double
        if (atContent == null) {
            dx = DUPLICATE_NUDGE
            dy = DUPLICATE_NUDGE
        } else {
            dx = atContent.x - bounds.left
            dy = atContent.y - bounds.top
        }
        for (clone in clones) clone.translate(dx, dy)
        document.addAll(clones)
        history.push(AddCanvasItems(document, clones))
        showToast("Inserted")
        if (clipboardFromCut) {
            clipboard.clear()
            clipboardFromCut = false
        }
        selection.select(clones)
        armTool(Tool.SELECT)
        markDirty()
        refresh()
        publishOverlay()
    }

    override val hasClipboardItems: Boolean get() = clipboard.isNotEmpty()

    // --- long-press paste menu ---

    /** Where a held finger opened the paste menu, or null when no menu is open. */
    override var contextMenu: ContextMenuTarget? by mutableStateOf(null)

    override val clipboardHasImage: Boolean
        get() = com.xnotes.platform.SystemClipboard.hasImage(appContext)

    override fun dismissContextMenu() {
        contextMenu = null
    }

    override fun pasteItemsAt(content: Pt) {
        pasteClipboard(content)
    }

    override fun pasteClipboardImageAt(content: Pt) {
        val bytes = com.xnotes.platform.SystemClipboard.imageBytes(appContext) ?: return
        insertImage(bytes, content)
        showToast("Inserted")
    }

    /** Put the selection on top. On a flat canvas that is purely a reorder of the item list. */
    override fun bringToFront() {
        if (selection.isEmpty) return
        val before = document.items.toList()
        val after = com.xnotes.core.infinite.bringToFrontOrder(before, selection.items)
        if (com.xnotes.core.infinite.sameOrder(before, after)) return
        document.replaceAll(after)
        history.push(ReplaceCanvasItems(document, before, after))
        markDirty()
        refresh()
        publishOverlay()
    }

    override fun selectionStyles(): List<DrawStyle> = selection.items.mapNotNull { DrawStyle.of(it) }

    /** Styles held from the first restyle preview, so a slider drag undoes in one step. */
    private var restyleBaseline: MutableMap<CanvasItem, DrawStyle>? = null

    /**
     * Recolour and/or re-thicken the selected strokes and shapes, with the same preview/commit
     * contract the paged editor uses. The command is wrapped in [OnCanvas] so undo and redo re-file
     * the index and re-mesh through exactly the path the edit did.
     */
    override fun restyleSelection(color: Rgba?, width: Double?, preview: Boolean) {
        if (selection.isEmpty) return
        val baseline = restyleBaseline ?: HashMap<CanvasItem, DrawStyle>().also { map ->
            for (item in selection.items) DrawStyle.of(item)?.let { map[item] = it }
        }
        restyleBaseline = if (preview) baseline else null
        if (color != null || width != null) {
            for (item in selection.items) {
                val current = DrawStyle.of(item) ?: continue
                DrawStyle(color ?: current.color, width ?: current.width).applyTo(item)
            }
            document.itemsChanged(selection.items)
        }
        if (!preview) {
            val entries = baseline.mapNotNull { (item, before) ->
                DrawStyle.of(item)?.takeIf { it != before }?.let { RestyleItems.Entry(item, before, it) }
            }
            if (entries.isNotEmpty()) {
                history.push(OnCanvas(document, RestyleItems(entries), entries.map { it.item }))
            }
            color?.let { onColorRemembered?.invoke(it) }
        }
        markDirty()
        refresh()
        publishOverlay()
    }

    fun armInkColor(color: Rgba) {
        inkColor = color
    }

    /** Arm swatch [index], the same way the paged toolbar picks its ink. */
    fun pickColor(index: Int) {
        if (index !in toolbarColors.indices) return
        activeColorIndex = index
        inkColor = toolbarColors[index]
        onToolStyleChanged?.invoke()
    }

    fun setToolConfig(forTool: Tool, config: ToolConfig) {
        toolConfigs[forTool] = config
    }

    fun armShapeConfig(config: ShapeConfig) {
        shapeConfig = config
    }

    fun armDetectShapes(on: Boolean) {
        detectShapes = on
    }

    fun configFor(forTool: Tool): ToolConfig {
        val base = toolConfigs.getOrPut(forTool) { ToolDefaults.configFor(forTool) }
        // A tool with a colour override always draws in its own colour; the rest follow the
        // toolbar's active ink.
        return base.copy(rgba = base.colorOverride ?: inkColor)
    }

    /**
     * The stored style for [tool], without the ink colour folded in. The popups edit this, and it
     * is what gets persisted, so a pen tuned here is the same pen on a note.
     */
    override fun toolConfig(tool: Tool): ToolConfig =
        toolConfigs.getOrPut(tool) { ToolDefaults.configFor(tool) }

    override fun updateToolConfig(tool: Tool, config: ToolConfig) {
        toolConfigs[tool] = config
        onToolStyleChanged?.invoke()
    }

    override var snapHeldToShapes: Boolean
        get() = detectShapes
        set(v) { detectShapes = v; onDetectShapesChanged?.invoke(v) }

    /** Fired when the snap-to-shapes toggle changed here, so the host can persist it. */
    var onDetectShapesChanged: ((Boolean) -> Unit)? = null

    override val hostShapeConfig: ShapeConfig get() = shapeConfig

    override fun updateShapeConfig(config: ShapeConfig) {
        shapeConfig = config
        onToolStyleChanged?.invoke()
    }

    override val hostToolbarColors: List<Rgba> get() = toolbarColors
    override val hostActiveColorIndex: Int get() = activeColorIndex
    override val hostRecentColors: List<Rgba> get() = recentColors

    /**
     * Recolour a swatch from the picker. The swatches belong to the host, which owns the settings
     * file, so the change is reported rather than only kept here: a colour mixed on the canvas is
     * the same colour on a note, and it is still there next launch.
     */
    override fun setSwatchColor(index: Int, color: Rgba) {
        if (index !in toolbarColors.indices) return
        toolbarColors = toolbarColors.toMutableList().also { it[index] = color }
        pickColor(index)
        onSwatchColorChanged?.invoke(index, color)
    }

    override fun rememberSwatchColor(index: Int) {
        toolbarColors.getOrNull(index)?.let { onColorRemembered?.invoke(it) }
    }

    /** Fired when a swatch was recoloured here, so the host can adopt it and persist it. */
    var onSwatchColorChanged: ((Int, Rgba) -> Unit)? = null

    /** Fired when the picker closed on a colour, so the host can keep it among its recents. */
    var onColorRemembered: ((Rgba) -> Unit)? = null

    /** The toolbar's swatches and recents, handed over by the host so both surfaces share them. */
    var toolbarColors by mutableStateOf(InkPalette.presets)
    var activeColorIndex by mutableStateOf(0)
    var recentColors by mutableStateOf<List<Rgba>>(emptyList())
    var toolbarColorCount by mutableStateOf(5)

    /** How this bar is arranged, handed over by the host, which owns the settings file. */
    var toolbarLayout by mutableStateOf(com.xnotes.core.tools.ToolbarLayout.CANVAS_DEFAULT)

    /** Fired when a tool's style changed here, so the host can persist it. */
    var onToolStyleChanged: (() -> Unit)? = null

    /** The saved background new canvases start with (null ⇒ app built-ins), fed by the host. */
    var newCanvasBackground by mutableStateOf<CanvasBackground?>(null)

    /** Fired when the styles popup saved (or cleared) that background, so the host can persist it. */
    var onSaveNewCanvasBackground: ((CanvasBackground?) -> Unit)? = null

    /** Save (or, passing null, forget) the background new canvases start with. */
    fun saveNewCanvasBackground(background: CanvasBackground?) {
        if (newCanvasBackground == background) return
        newCanvasBackground = background
        onSaveNewCanvasBackground?.invoke(background)
    }

    /** Latch a stylus side button that arrived as a key event, so the pen behaves as on a note. */
    fun onStylusButtonKey(keyCode: Int, down: Boolean): Boolean =
        interaction.onStylusButtonKey(keyCode, down)

    // --- drawing ---

    /** The stroke whose settled runs are already in the scene's wet buffer, by identity. */
    private var wetOwner: Stroke? = null

    /** Ribbon points of [wetOwner] already meshed into it, and the arc those points spent. */
    private var wetMeshed = 0
    private var wetArc = 0.0

    /** Whether this stroke is being painted into the front buffer rather than into the scene. */
    private var frontInk = false

    /** Points a run holds, which differs between the two paths. */
    private var runPoints = WET_RUN_POINTS

    /** Whether this stroke's route has been chosen; it needs one meshed tail to look at. */
    private var frontDecided = false

    /**
     * Committed strokes whose triangles are waiting for the front buffer to go opaque.
     *
     * More than one when strokes joined each other on the pad, which is what a hand quicker than
     * the handover produces.
     */
    private var heldItems: List<CanvasItem> = emptyList()

    /** Bumped by anything that outdates a handover in flight, so a stale capture cannot land. */
    @Volatile
    private var handoffGen = 0

    /**
     * Whether ink given back to the scene is still only in its buffers and not yet on the glass.
     *
     * Being handed over is not the same as being shown, and a wipe inside that window is a blink.
     */
    private var awaitingPublish = false

    /** The tail as it was last meshed, which a stroke joining this one has to settle first. */
    private var wetTail: List<MeshPart> = emptyList()

    /** [wetTail] of the stroke just committed, waiting for a joiner or for the handover. */
    private var handoverTail: List<MeshPart> = emptyList()

    private val mainHandler = android.os.Handler(appContext.mainLooper)

    /**
     * Hand the stroke under the pen to the scene, in two pieces where the pen allows it.
     *
     * Re-meshing and re-uploading the whole ribbon on every sample makes a frame cost what the
     * stroke has already laid down, so a long one falls behind the hand. Instead the run that has
     * stopped moving is uploaded once, [WET_RUN_POINTS] at a time and never again, and only the
     * few points still in play are rebuilt each frame. The two runs overlap by a point so the quad
     * bridging them belongs to the later one and no gap can open on the join.
     *
     * Ink the runs cannot simply be laid over each other — neon, whose halos would compound where
     * they meet, and the highlighter, which is composited at its own alpha — is meshed whole, as
     * are the taper pen and the straight-line tools, which have no settled run to speak of.
     */
    private fun publishWetStroke(stroke: Stroke?) {
        // Pen back down under the wand: the held batch goes solid again, so a long stroke cannot
        // outlive a fade that had already started.
        if (wandEnabled && stroke != null && stroke !== wandLive) {
            wandLive = stroke
            solidifyFading()
        }
        if (stroke == null) {
            endFrontInk()
            forgetWetStroke()
            scene.setWetParts(emptyList(), Rect(0.0, 0.0, 0.0, 0.0))
            return
        }
        val ribbon = stroke.wetRibbon
        if (ribbon == null || !stroke.wetCacheable) {
            endFrontInk()
            forgetWetStroke()
            val meshed = ItemMesher.mesh(stroke) ?: return
            // Every run, not just the first: a neon stroke is a halo, a lit body and a white core,
            // and keeping only one of them left the wet stroke invisible until the pen lifted.
            scene.setWetParts(meshed.parts, meshed.bounds)
            return
        }
        if (wetOwner !== stroke) {
            endFrontInk()
            forgetWetStroke()
            wetOwner = stroke
            scene.setWetParts(emptyList(), Rect(0.0, 0.0, 0.0, 0.0))
            frontDecided = false
            runPoints = WET_RUN_POINTS
        }
        val bounds = stroke.paintBounds()
        val settled = ribbon.settledCount
        if (settled - wetMeshed >= runPoints) {
            val from = (wetMeshed - 1).coerceAtLeast(0)
            ItemMesher.meshRun(stroke, ribbon, from, settled - from, wetArc)?.let {
                if (frontInk) pad.appendRun(listOf(it)) else scene.appendWetRun(listOf(it), bounds)
            }
            for (k in from + 1 until settled) {
                wetArc += kotlin.math.hypot(ribbon.cx(k) - ribbon.cx(k - 1), ribbon.cy(k) - ribbon.cy(k - 1))
            }
            wetMeshed = settled
        }
        val tailFrom = (wetMeshed - 1).coerceAtLeast(0)
        val tail = ItemMesher.meshRun(stroke, ribbon, tailFrom, ribbon.pointCount - tailFrom, wetArc)
        val parts = if (tail == null) emptyList() else listOf(tail)
        if (!frontDecided) decideFrontInk(stroke, parts)
        wetTail = parts
        if (frontInk) pad.setTail(parts) else scene.setWetTail(parts, bounds)
    }

    /**
     * Whether this stroke can go on the front buffer, decided from what it meshes to rather than
     * from the tool.
     *
     * The pad has no copy of what is under it, so anything that composites against the page cannot
     * live there: a stencilled translucent pass, or a halo. Plain triangles can, and that is what
     * a pen and a pencil are.
     */
    private fun decideFrontInk(stroke: Stroke, parts: List<MeshPart>) {
        frontDecided = true
        // Turned off for this device, so there is no pad to consult and nothing to time against it.
        if (!pad.frontBuffering) return
        if (parts.isEmpty() || parts.any { it.pass != InkPass.OPAQUE }) return
        if (joinFrontInk()) return
        // Nothing joined, so the pad has to be wiped for this stroke, and whatever it was showing
        // has to be on the glass first. Being handed over is not that: the scene has the triangles,
        // but the frame carrying them may still be out, and a handover a moment ago is exactly the
        // case where it is.
        if (heldItems.isNotEmpty() || awaitingPublish) return waitToStartFrontInk(stroke)
        takePad(stroke)
    }

    /**
     * Lay this stroke over the ones the pad is still holding, on the same view.
     *
     * A hand that comes back down inside the handover is quicker than the handover is, and wiping
     * the pad for it would take ink off the glass that the canvas has not drawn yet. The pad keeps
     * every pixel instead; only the tail changes hands, and it is settled into a run first because
     * this stroke's own tail replaces it. One handover at the end covers every stroke in the run.
     */
    private fun joinFrontInk(): Boolean {
        if (heldItems.isEmpty() || !roomToJoin()) return false
        if (!pad.extendStroke(viewport.scrollX, viewport.scrollY, viewport.zoom)) return false
        // The capture the last stroke started is of a box this one is about to grow past.
        handoffGen++
        if (handoverTail.isNotEmpty()) pad.appendRun(handoverTail)
        handoverTail = emptyList()
        pad.setTail(emptyList())
        frontInk = true
        runPoints = FRONT_RUN_POINTS
        view.setUnbufferedStylus(true)
        return true
    }

    /**
     * Whether the pad may grow any further.
     *
     * The handover ends with an opaque copy of the canvas under the whole run, and the canvas is
     * frozen behind it for a few refreshes. A sliver of it is invisible; most of the screen is not.
     */
    private fun roomToJoin(): Boolean {
        val box = pad.strokeBox() ?: return true
        val seen = view.width.toLong() * view.height
        return seen <= 0L || box.width().toLong() * box.height() * 2 <= seen
    }

    /**
     * Take the pad once the canvas has published the ink it was showing.
     *
     * The canvas has to have published the last stroke, and then the pad has to come *down*: a
     * committed frame is only queued, and a wipe of a front buffer is on the glass at the next
     * scanout whatever the canvas has queued, so wiping on the commit still drops the stroke for the
     * refresh in between. Hiding is a transaction, latched like a buffer, so both layers change in
     * one composite. The stroke goes into the scene's wet buffer until then, as every stroke does
     * before it is decided, and is taken back out when the pad takes over.
     */
    private fun waitToStartFrontInk(stroke: Stroke) {
        settleHeld()
        val gen = handoffGen
        view.publishThen {
            mainHandler.post {
                // A moved generation means another stroke has taken the pad and is answerable for it.
                if (gen == handoffGen) takePad(stroke)
            }
        }
    }

    /**
     * Take the pad, once it is down.
     *
     * Never by clearing it where it stands. A wipe of a front buffer is on the glass at the next
     * scanout whatever the canvas has queued, and a frame the canvas has *committed* is only queued:
     * it is latched a vsync later. So a wipe timed against a commit still drops whatever the pad was
     * showing for the refresh in between, which is the blink. Hiding is a transaction, latched like
     * a buffer, so both layers change in one composite; only then are the pixels this stroke's.
     */
    private fun takePad(stroke: Stroke) {
        // Already down, which is every stroke that starts after any sort of pause: take it here so
        // the caller's own publish is the first one, rather than re-entering it.
        if (!pad.showing) {
            startFrontInk()
            return
        }
        val gen = handoffGen
        pad.standDown {
            if (gen != handoffGen) return@standDown
            if (wetOwner !== stroke || !startFrontInk()) return@standDown pad.release()
            // Out of the scene's wet buffer and onto the pad, in that order.
            wetMeshed = 0
            wetArc = 0.0
            scene.setWetParts(emptyList(), Rect(0.0, 0.0, 0.0, 0.0))
            publishWetStroke(stroke)
            view.publish()
        }
    }

    /** Give the held strokes to the scene, leaving the pad alone, and outdate any capture for them. */
    private fun settleHeld(): Boolean {
        handoffGen++
        val items = heldItems
        if (items.isEmpty()) return false
        heldItems = emptyList()
        handoverTail = emptyList()
        for (item in items) pushItem(item)
        // Every handover tracks its own publication, whichever path settled: what the caller does
        // next is not what says when these triangles reached the glass.
        awaitingPublish = true
        view.publishThen { mainHandler.post { awaitingPublish = false } }
        return true
    }

    private fun startFrontInk(): Boolean {
        frontInk = pad.beginStroke(viewport.scrollX, viewport.scrollY, viewport.zoom, view.msaaSamples)
        if (!frontInk) return false
        // A shorter run on the front buffer, because there the tail is what a present has to clear
        // and rebuild, and its extent is the size of everything that present does.
        runPoints = FRONT_RUN_POINTS
        view.setUnbufferedStylus(true)
        return true
    }

    /** Give the ink back with nothing to hand over, for a stroke that was abandoned. */
    private fun endFrontInk() {
        if (!frontInk) return
        frontInk = false
        // Strokes that joined this one are still the pad's, and the pad is about to be wiped.
        settleHeld()
        view.setUnbufferedStylus(false)
        pad.endStroke()
    }

    private fun forgetWetStroke() {
        wetOwner = null
        wetMeshed = 0
        wetArc = 0.0
        wetTail = emptyList()
    }

    /**
     * Rebuild the chrome drawn over the content: the selection box and handles, or whichever
     * marquee a drag is sweeping out. It goes through the same transient buffer the wet stroke
     * uses, since inking and selecting can never happen at once.
     */
    private fun publishOverlay() {
        hasSelection = !selection.isEmpty
        refreshSelectionMenu()
        // Fixed light blue, not the theme accent, so the chrome shows on light and dark paper alike.
        val accent = com.xnotes.core.model.Rgba.SELECTION
        val zoom = viewport.zoom
        // A lasso drag owns the buffer on its own: it clears the selection when it starts and no
        // marquee can be out at the same time, so nothing else needs a place in the same publish.
        if (publishLasso(zoom, accent)) return
        lassoSettled = 0
        val parts = ArrayList<MeshPart>(3)
        var bounds: Rect? = null
        interaction.bandRect?.let {
            parts += OverlayTessellator.band(it, zoom, accent, StrokeTessellator.DEFAULT_TOLERANCE, devicePxPerDp)
            bounds = it.outset(4.0 / zoom)
        }
        selection.box?.let { box ->
            parts += OverlayTessellator.selection(
                box, zoom, accent, StrokeTessellator.DEFAULT_TOLERANCE, devicePxPerDp,
                points = selection.spline()?.controlPoints(),
                moveGrip = selection.moveGrip(zoom, OverlayTessellator.GRIP_ARM_PX / zoom),
            )
            val b = OverlayTessellator.selectionBounds(box, zoom)
            bounds = bounds?.union(b) ?: b
        }
        if (parts.isEmpty()) {
            scene.setWet(null, InkPalette.DEFAULT, InkPass.OPAQUE, Rect(0.0, 0.0, 0.0, 0.0))
        } else {
            scene.setWetParts(parts, bounds ?: Rect(0.0, 0.0, 0.0, 0.0))
        }
        view.publish()
    }

    /** Lasso vertices already in the scene's settled runs; 0 when no lasso owns the buffer. */
    private var lassoSettled = 0

    /** The zoom those runs were tessellated at, since the marquee's width is an on-screen one. */
    private var lassoZoom = 0.0

    /** Running bounds of the lasso, and how many of its points are in them. */
    private var lassoBounds = Rect(0.0, 0.0, 0.0, 0.0)
    private var lassoBounded = 0

    /** Arc the settled runs spent, which is where the dash pattern has got to. */
    private var lassoArc = 0.0

    /**
     * Publish the lasso as runs and a moving tail, and say whether it took the buffer.
     *
     * A lasso is a polyline that only grows at its end, and the marquee gives every vertex a disc
     * of its own, so rebuilding the loop on every touch sample costs the whole loop again: a long
     * one reached tens of thousands of triangles re-tessellated and re-uploaded at the pen's full
     * rate, and the drag got heavier the longer it ran. The settled stretch is uploaded once and
     * never rewritten and only the last few points are rebuilt, so a sample costs the same at the
     * end of a lasso as at its start.
     */
    private fun publishLasso(zoom: Double, accent: Rgba): Boolean {
        val points = interaction.lassoPoints
        if (points.size < 2 || zoom <= 0.0) return false
        // The marquee's width is an on-screen one, so a zoom would restate every run already up.
        // Nothing can zoom under a lasso, but starting over is the honest way to say so. A list
        // shorter than what is already published is a lasso that restarted, and starts over too.
        if (lassoSettled == 0 || points.size < lassoSettled || zoom != lassoZoom) {
            scene.setWetParts(emptyList(), EMPTY_RECT)
            lassoZoom = zoom
            lassoSettled = 1
            lassoBounded = 0
            lassoArc = 0.0
            lassoBounds = Rect(points[0].x, points[0].y, 0.0, 0.0)
        }
        while (lassoBounded < points.size) {
            val p = points[lassoBounded++]
            lassoBounds = lassoBounds.union(Rect(p.x, p.y, 0.0, 0.0))
        }
        val bounds = lassoBounds.outset(4.0 / zoom)
        if (points.size - lassoSettled >= LASSO_RUN_POINTS) {
            // From one point back, so the segment bridging this run to the last one is drawn. The
            // arc is measured through that same point, so it is both the phase this run starts at
            // and, once its own length is added, the phase the tail starts at.
            val from = lassoSettled - 1
            val run = OverlayTessellator.lassoRun(
                points, from, points.size - from, zoom, accent, StrokeTessellator.DEFAULT_TOLERANCE,
                lassoArc, devicePxPerDp,
            )
            if (run.isNotEmpty()) scene.appendWetRun(run, bounds)
            for (k in from + 1 until points.size) {
                lassoArc += points[k].distanceTo(points[k - 1])
            }
            lassoSettled = points.size
        }
        scene.setWetTail(
            OverlayTessellator.lassoTail(
                points, lassoSettled - 1, zoom, accent, StrokeTessellator.DEFAULT_TOLERANCE,
                lassoArc, devicePxPerDp,
            ),
            bounds,
        )
        view.publish()
        return true
    }

    /** A finished selection drag: record it, if it changed anything. */
    private fun commitSelection(command: com.xnotes.core.history.Command?) {
        if (command == null) return
        history.push(command)
        markDirty()
        refresh()
    }

    /** The shape being dragged out, drawn live over the committed geometry like a wet stroke. */
    private fun publishPendingShape(shape: ShapeItem?) {
        if (shape == null) {
            scene.setWet(null, InkPalette.DEFAULT, InkPass.OPAQUE, Rect(0.0, 0.0, 0.0, 0.0))
            return
        }
        val meshed = ItemMesher.mesh(shape) ?: return
        scene.setWetParts(meshed.parts, meshed.bounds)
    }

    /** Add a finished item and record the edit, the common tail of every creating tool. */
    private fun commitItem(item: CanvasItem) {
        committingWetStroke = true
        try {
            document.add(item)
        } finally {
            committingWetStroke = false
        }
        history.push(AddCanvasItem(document, item))
        markDirty()
        refresh()
    }

    /**
     * Insert an encoded image, centred on [atContent] or on the middle of the view. The bytes are
     * written to a file and the item keeps only that path, so a canvas full of photographs never
     * holds their pixels: each is decoded when drawn, at the size the screen can show.
     */
    fun insertImage(bytes: ByteArray, atContent: com.xnotes.core.geometry.Pt? = null): Boolean {
        val dir = imageDir ?: return false
        val file = runCatching {
            java.io.File.createTempFile("img", null, dir).apply { writeBytes(bytes) }
        }.getOrNull()
        val size = file?.let { imageCodec.probeFile(it.path) }
        if (file == null || size == null || size.width <= 0 || size.height <= 0) {
            file?.delete()
            return false
        }
        // Land it at a comfortable size for the current view rather than at its pixel size, which
        // on an unbounded canvas would be arbitrary.
        val visible = viewport.visibleContentRect()
        val maxW = visible.w * 0.6
        val maxH = visible.h * 0.6
        val scale = minOf(1.0, maxW / size.width, maxH / size.height)
        val w = size.width * scale
        val h = size.height * scale
        val centre = atContent ?: viewport.centerContent
        val rect = Rect(centre.x - w / 2.0, centre.y - h / 2.0, w, h)
        commitItem(ImageItem(ImageData(file, size.width, size.height), rect))
        return true
    }

    /** Pen up on an eraser drag: the whole drag is one undoable edit, however much it cut. */
    private fun commitErase(session: EraseSession) {
        val command = session.buildCommand() ?: return
        history.push(command)
        markDirty()
        refresh()
        view.publish()
    }

    /** Pen up: the finished stroke joins the document, and the edit joins the undo stack. */
    private fun commitStroke(stroke: Stroke) {
        // The commit message releases the wet buffers on the GL thread, so forget what was in them.
        val tail = wetTail
        forgetWetStroke()
        val front = frontInk
        frontInk = false
        if (!front) {
            if (wandEnabled && stroke.tool.isStroke) holdEphemeral(stroke) else commitItem(stroke)
            return
        }
        pad.freeze()
        // The tail was never settled into a run, so a stroke that joins this one has to do it.
        handoverTail = tail
        view.setUnbufferedStylus(false)
        // The wand's ink never becomes a committed item, so there is nothing to hand it over to.
        if (wandEnabled && stroke.tool.isStroke) {
            holdEphemeral(stroke)
            settleHeld()
            val gen = handoffGen
            view.publishThen { if (gen == handoffGen) pad.release() }
            return
        }
        // A capture in flight was started for a run this stroke has since grown.
        handoffGen++
        // Its triangles are held until the pad is opaque, so the canvas cannot draw the stroke
        // while the pad is also showing it. Everything else about the commit happens now.
        heldItems = heldItems + stroke
        commitItem(stroke)
        captureBehindStroke()
    }

    /**
     * Take the canvas as it stands, put it behind the ink on the pad, and only then hand over.
     *
     * This is the step that makes the handover free rather than timed. Until it runs the pad is
     * transparent and the screen is the two layers together, so hiding the pad has to be raced
     * against the canvas drawing the committed stroke, and one refresh either way is a blink or a
     * doubled antialiased edge. Once the capture is behind the ink the pad holds that composite
     * opaquely, the canvas underneath can change unseen, and the hide lands wherever it likes.
     *
     * Every failure path ends the same way, because a handover that is merely timed is far better
     * than a stroke that never reaches the canvas.
     */
    private fun captureBehindStroke() {
        val box = pad.strokeBox()
        if (box == null || box.width() <= 0 || box.height() <= 0) return handOffStroke()
        val capture = try {
            android.graphics.Bitmap.createBitmap(
                box.width(), box.height(), android.graphics.Bitmap.Config.ARGB_8888,
            )
        } catch (e: OutOfMemoryError) {
            return handOffStroke()
        }
        val gen = handoffGen
        // However the capture goes, the stroke reaches the canvas: late is a blink, never is a
        // lost stroke. One Runnable, held, because a fresh method reference cannot be cancelled.
        val timeout = Runnable { if (gen == handoffGen) handOffStroke() }
        mainHandler.postDelayed(timeout, CAPTURE_TIMEOUT_MS)
        android.view.PixelCopy.request(view, box, capture, { result ->
            mainHandler.removeCallbacks(timeout)
            if (gen != handoffGen) return@request
            if (result != android.view.PixelCopy.SUCCESS) return@request handOffStroke()
            pad.coverWith(capture, box) { if (gen == handoffGen) handOffStroke() }
        }, mainHandler)
    }

    /** Let the held strokes reach the canvas, and take the pad down once that frame is out. */
    private fun handOffStroke() {
        if (!settleHeld()) return
        // The wait is a frame long, which is long enough for a new stroke to have taken the pad.
        // Its ink would then be the only copy on screen, and this would wipe it.
        val gen = handoffGen
        view.publishThen { if (gen == handoffGen) pad.release() }
    }

    // --- disappearing ink (magic wand) ---

    /** A held stroke and the triangles it was meshed into once, recoloured as the batch fades. */
    private class FadingStroke(val stroke: Stroke, val meshed: MeshedItem)

    private val fadingStrokes = mutableListOf<FadingStroke>()
    private var fadeAlpha = 1.0
    private var fading = false
    private var fadeStartMs = 0L
    private var fadeTimer: Runnable? = null

    /** The stroke the wand last saw under the pen, so the next one can re-solidify the batch. */
    private var wandLive: Stroke? = null
    private val fadeHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private val choreographer by lazy { android.view.Choreographer.getInstance() }
    private val fadeFrame = android.view.Choreographer.FrameCallback { t -> stepFade(t) }

    /**
     * Pen up under the wand: hold the stroke rather than file it.
     *
     * It is handed to the renderer like any committed item, but never to the document — so it
     * draws, culls and blends exactly as ink does while touching no model, no history, no autosave
     * and nothing on disk. It is meshed once here and only recoloured as it melts, so a fading
     * batch costs an upload a frame instead of a tessellation.
     */
    private fun holdEphemeral(stroke: Stroke) {
        val meshed = ItemMesher.mesh(stroke)
        if (meshed == null || meshed.isEmpty) {
            scene.setWetParts(emptyList(), Rect(0.0, 0.0, 0.0, 0.0))
            view.publish()
            return
        }
        fadingStrokes.add(FadingStroke(stroke, meshed))
        // clearsWet releases the buffer under the pen in the same message this stroke arrives in,
        // so no frame can fall between the two and blink.
        scene.upsert(stroke, meshed.parts, meshed.bounds, clearsWet = true)
        solidifyFading()
        view.publish()
    }

    /** Put the batch back to full strength and restart the hold, so it melts only once drawing
     *  has paused. A no-op when nothing is being held. */
    private fun solidifyFading() {
        if (fadingStrokes.isEmpty()) return
        fading = false
        if (fadeAlpha != 1.0) {
            fadeAlpha = 1.0
            republishFading()
        }
        scheduleFade()
    }

    /** Drop the held batch and stop any pending or running fade. */
    private fun clearFading() {
        cancelFadeTimer()
        fading = false
        fadeAlpha = 1.0
        wandLive = null
        if (fadingStrokes.isEmpty()) return
        for (f in fadingStrokes) scene.remove(f.stroke)
        fadingStrokes.clear()
        view.publish()
    }

    private fun scheduleFade() {
        cancelFadeTimer()
        val r = Runnable { startFade() }
        fadeTimer = r
        fadeHandler.postDelayed(r, InteractionController.WAND_HOLD_MS)
    }

    private fun cancelFadeTimer() {
        fadeTimer?.let { fadeHandler.removeCallbacks(it) }
        fadeTimer = null
    }

    private fun startFade() {
        fadeTimer = null
        if (fadingStrokes.isEmpty()) return
        fading = true
        fadeAlpha = 1.0
        fadeStartMs = System.nanoTime() / 1_000_000L
        choreographer.postFrameCallback(fadeFrame)
    }

    private fun stepFade(frameTimeNanos: Long) {
        if (!fading) return
        val now = frameTimeNanos / 1_000_000L
        val t = ((now - fadeStartMs) / InteractionController.WAND_FADE_MS).coerceIn(0.0, 1.0)
        fadeAlpha = (1.0 - t) * (1.0 - t) // ease-out, so the batch melts rather than blinking off
        if (t >= 1.0) {
            for (f in fadingStrokes) scene.remove(f.stroke)
            fadingStrokes.clear()
            fadeAlpha = 1.0
            fading = false
        } else {
            republishFading()
        }
        view.publish()
        if (fading) choreographer.postFrameCallback(fadeFrame)
    }

    /** Re-file every held stroke at the current alpha, from the triangles it already has. */
    private fun republishFading() {
        for (f in fadingStrokes) {
            scene.upsert(f.stroke, f.meshed.parts.map { fadedPart(it, fadeAlpha) }, f.meshed.bounds)
        }
    }

    /** One meshed run at [alpha] of its own opacity; neon's halos dim with the ink they come from. */
    private fun fadedPart(part: MeshPart, alpha: Double): MeshPart {
        if (alpha >= 1.0) return part
        val g = part.glow
        return MeshPart(
            part.mesh,
            part.color.scaledAlpha(alpha),
            part.pass,
            if (g == null) {
                null
            } else {
                GlowSpec(
                    g.wideRadius, g.wideAlpha * alpha, g.tightRadius, g.tightAlpha * alpha,
                    g.bodyColor.scaledAlpha(alpha), g.coreColor.scaledAlpha(alpha), g.coreScale,
                )
            },
        )
    }

    private fun Rgba.scaledAlpha(f: Double): Rgba = withAlpha((a * f).toInt().coerceIn(0, 255))

    private fun markDirty() {
        document.dirty = true
        onContentChanged?.invoke()
    }

    /** Adopt the app's pen preferences, so the canvas and the paged note behave the same. */
    fun applyInputPrefs(
        fingerDraws: Boolean,
        penButtonTool: Tool?,
        zoomLockPan: String = "single",
        snapRotation90: Boolean = false,
    ) {
        interaction.fingerDraws = fingerDraws
        interaction.penButtonTool = penButtonTool
        interaction.zoomLockPan = zoomLockPan
        selection.snapRotation90 = snapRotation90
        this.snapRotation90 = snapRotation90
    }

    /** Mirrors prefs.snapRotation90; re-applied to each freshly built selection. */
    private var snapRotation90 = false

    fun toggleZoomLock() {
        zoomLocked = !zoomLocked
        interaction.zoomLocked = zoomLocked
    }

    /** Adopt the configured zoom range, then pull the current zoom back into it. */
    fun applyZoomRange(minPercent: Int, maxPercent: Int) {
        viewport.minZoom = (minPercent / 100.0).coerceAtLeast(0.0001)
        viewport.maxZoom = (maxPercent / 100.0).coerceAtLeast(viewport.minZoom)
        viewport.clampZoom()
        onViewChanged()
        view.publish()
    }

    /** Keyboard shortcuts. Only the ones that mean something without pages. */
    fun handleKeyDown(e: android.view.KeyEvent): Boolean {
        val ctrl = e.isCtrlPressed
        val shift = e.isShiftPressed
        when {
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_Z && shift -> redo()
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_Z -> undo()
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_Y -> redo()
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_A -> selectAll()
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_C -> copySelection()
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_X -> cutSelection()
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_V -> pasteClipboard()
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_D -> duplicateSelection()
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_0 -> zoomToFit()
            ctrl && (e.keyCode == android.view.KeyEvent.KEYCODE_PLUS || e.keyCode == android.view.KeyEvent.KEYCODE_EQUALS) -> zoomBy(ZOOM_STEP)
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_MINUS -> zoomBy(1.0 / ZOOM_STEP)
            e.keyCode == android.view.KeyEvent.KEYCODE_DEL || e.keyCode == android.view.KeyEvent.KEYCODE_FORWARD_DEL -> deleteSelection()
            e.keyCode == android.view.KeyEvent.KEYCODE_ESCAPE -> interaction.clearSelection()
            else -> return false
        }
        return true
    }

    /** Select everything unlocked on the canvas, which on an unbounded one means most of it. */
    fun selectAll() {
        if (document.isEmpty) return
        selection.select(document.items.filter { !it.locked })
        armTool(Tool.SELECT)
        publishOverlay()
    }

    fun zoomBy(factor: Double) {
        if (zoomLocked) return
        viewport.zoomAroundCenter(viewport.zoom * factor)
        onViewChanged()
        view.publish()
    }

    private var palette: Palette? = null

    /** Adopt the chrome's palette, so the paper and the selection accent match the rest of the app. */
    fun applyPalette(palette: Palette) {
        this.palette = palette
        view.paperColor = document.background.paperColor ?: palette.paper
        view.accent = palette.accent
    }

    // --- documents ---

    fun newCanvas() {
        replaceDocument(InfiniteDocument())
    }

    fun replaceDocument(next: InfiniteDocument) {
        clearFading() // whatever was melting belongs to the outgoing canvas
        document.listener = null
        document = next
        next.listener = modelListener
        selection = CanvasSelection(next).also { it.snapRotation90 = snapRotation90 }
        hasSelection = false
        history.clear()
        interaction.resetGestureState()
        view.background = next.background
        view.paperColor = next.background.paperColor ?: view.paperColor
        rebuildScene()
        appliedInitialView = false
        applyInitialView()
        refresh()
        view.publish()
    }

    // --- view ---

    private var appliedInitialView = false

    /**
     * Put the canvas where it was left, or on its content, or at the origin. Runs once the viewport
     * has a size, since both fitting and centring need one.
     */
    private fun applyInitialView() {
        if (appliedInitialView) return
        if (viewport.widthPx <= 0 || viewport.heightPx <= 0) return
        appliedInitialView = true
        val saved = document.lastView
        when {
            saved != null -> viewport.apply(saved)
            else -> document.contentBounds()?.let { viewport.fit(it) } ?: viewport.centerOn(0.0, 0.0)
        }
        onViewChanged()
        view.publish()
    }

    /** Frame every item on the canvas. */
    fun zoomToFit() {
        if (zoomLocked) return
        val bounds = document.contentBounds()
        if (bounds == null) {
            viewport.zoom = 1.0
            viewport.centerOn(0.0, 0.0)
        } else {
            viewport.fit(bounds)
        }
        onViewChanged()
        view.publish()
    }

    /** What a floating toolbar covers of the canvas, in px per edge; centring and fitting keep clear of it. */
    fun setToolbarCover(left: Double, top: Double, right: Double, bottom: Double) {
        val v = viewport
        if (v.insetLeft == left && v.insetTop == top && v.insetRight == right && v.insetBottom == bottom) return
        // What sat in the middle of the clear area stays in the middle as the area changes.
        val center = v.centerContent
        v.insetLeft = left
        v.insetTop = top
        v.insetRight = right
        v.insetBottom = bottom
        v.centerOn(center.x, center.y)
        onViewChanged()
        view.publish()
    }

    fun jumpTo(waypoint: Waypoint) {
        viewport.apply(waypoint)
        onViewChanged()
        view.publish()
    }

    /** Save the current view under [name], replacing any waypoint that already has it. */
    fun saveWaypoint(name: String) {
        val clean = Waypoint.sanitizeName(name)
        if (clean.isEmpty()) return
        document.waypoints.removeAll { it.name.equals(clean, ignoreCase = true) }
        document.waypoints.add(viewport.toWaypoint(clean))
        waypoints = document.waypoints.toList()
        markDirty()
    }

    fun removeWaypoint(waypoint: Waypoint) {
        document.waypoints.removeAll { it.name == waypoint.name }
        waypoints = document.waypoints.toList()
        markDirty()
    }

    /** A tap on the minimap: centre the view on whatever was tapped. */
    fun minimapTap(vx: Double, vy: Double): Boolean {
        if (!minimapVisible) return false
        val panel = Minimap.panel(viewport.widthPx, viewport.heightPx, viewport.insetRight, viewport.insetBottom)
        if (!panel.contains(Pt(vx, vy))) return false
        val extent = Minimap.mappedExtent(document.contentBounds(), viewport.visibleContentRect())
        val target = Minimap.toContent(Pt(vx, vy), extent, panel)
        viewport.centerOn(target.x, target.y)
        onViewChanged()
        view.publish()
        return true
    }

    fun setBackground(background: CanvasBackground) {
        document.background = background
        view.background = background
        view.paperColor = background.paperColor ?: view.paperColor
        markDirty()
    }

    // --- history ---

    // History moves geometry the selection box cannot follow: an undone rotation puts the ink back
    // upright and leaves the box turned over it. The paged editor drops the selection for the same
    // reason, so both surfaces behave alike.
    fun undo() {
        history.undo()
        interaction.clearSelection()
        markDirty()
        refresh()
        view.publish()
    }

    fun redo() {
        history.redo()
        interaction.clearSelection()
        markDirty()
        refresh()
        view.publish()
    }

    private fun onViewChanged() {
        document.lastView = viewport.toWaypoint()
        zoomPercent = Math.round(viewport.zoom * 100).toInt()
        // The menu is anchored in viewport pixels, so a pan or a zoom moves it.
        refreshSelectionMenu()
        // The minimap maps everything drawn, so its extent moves with the content, not the view.
        view.contentBounds = document.contentBounds()
    }

    private fun refresh() {
        title = document.title
        canUndo = history.canUndo
        canRedo = history.canRedo
        waypoints = document.waypoints.toList()
        view.contentBounds = document.contentBounds()
    }

    companion object {
        /** Zoom step for the keyboard, matching a comfortable notch of a pinch. */
        const val ZOOM_STEP = 1.25

        /** Content pixels a duplicate lands from its original, so the copy is visibly a copy. */
        const val DUPLICATE_NUDGE = 24.0

        /** Longest a handover waits for a capture before giving the stroke back untimed. */
        const val CAPTURE_TIMEOUT_MS = 120L

        /**
         * Ribbon points that have to settle before the wet stroke gives the scene another run.
         * It sets both halves of the cost: the tail re-meshed each frame is at most this plus the
         * pen's own lookahead, and the runs a stroke leaves behind it are its length over this.
         * Uploading each point the moment it settled would be one buffer slice per sample.
         */
        const val WET_RUN_POINTS = 96

        /**
         * The same, for a stroke on the front buffer. Far shorter, because there the tail is what
         * a present has to clear and rebuild, and it sets the size of everything that present does.
         * At panel rate the nib moves about fourteen pixels between presents, so anything much
         * above this repaints ink that has not moved.
         */
        const val FRONT_RUN_POINTS = 8

        /** Lasso vertices a settled run holds; the tail is rebuilt on every sample. */
        const val LASSO_RUN_POINTS = 16

        private val EMPTY_RECT = Rect(0.0, 0.0, 0.0, 0.0)

        /** The box that stands in for a vector image while it meshes, or where it cannot be drawn. */
        val VECTOR_PLACEHOLDER = Rgba(128, 128, 128, 36)
    }
}
