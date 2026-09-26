package com.xnotes.gl

import android.opengl.GLES30
import android.util.Log
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.InkPass
import com.xnotes.core.infinite.ItemMesher
import com.xnotes.core.infinite.GlowSpec
import com.xnotes.core.infinite.LiftTransform
import com.xnotes.core.infinite.MeshData
import com.xnotes.core.infinite.Minimap
import com.xnotes.core.infinite.MeshPart
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Rgba
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.floor

/** Which part of the item under the pen a batch of triangles is. */
enum class WetKind {
    /** Replaces the lot: a selection overlay, a shape being dragged out, or clearing everything. */
    WHOLE,

    /** Added to the run of the stroke that has stopped moving, and never rewritten. */
    SETTLED,

    /** Replaces the few points still moving under the nib. */
    TAIL,
}

/**
 * The GL-thread-owned mirror of the document: one record per committed item, holding where its
 * triangles live and what it takes to draw them. The model itself is never touched from here.
 * Edits arrive as messages, already tessellated on the thread that made them, so a frame never
 * races an edit and tessellation never blocks the render thread.
 */
class CanvasScene(private val store: GeometryStore = GeometryStore()) : GlScene {

    /** One run of triangles in one colour. An item is one or more, drawn in order. */
    private class Part(
        val slice: BufferSlice,
        val pass: InkPass,
        val coverColor: Rgba,
        val coverAlpha: Double,
        val glow: GlowSpec? = null,
    )

    private class Record(
        val item: CanvasItem,
        val parts: List<Part>,
        var bounds: Rect,
        var z: Int,
        /** Set for a placed image, which is drawn as a texture rather than as coloured triangles. */
        val image: ImageItem? = null,
    )

    /** An edit handed over from the main thread. */
    private sealed class Edit {
        class Upsert(
            val item: CanvasItem,
            val parts: List<MeshPart>,
            val bounds: Rect,
            /** Non-null for an image, which draws from a texture rather than from the parts. */
            val image: ImageItem? = null,
            /**
             * Set on the message that commits the stroke under the pen. Clearing the wet buffer
             * and adding the committed stroke have to land in the same message: as two, a frame
             * can fall between them and show one blank frame where the stroke should be.
             */
            val clearsWet: Boolean = false,
        ) : Edit()

        /**
         * The item under the pen. [WetKind] says whether these triangles replace everything, add
         * to the run that has stopped moving, or replace the run that has not.
         */
        class Wet(val parts: List<MeshPart>, val bounds: Rect, val kind: WetKind) : Edit()

        /** Items being dragged, and how far from where their triangles sit. Empty ends the drag. */
        class Lift(val items: List<CanvasItem>, val at: LiftTransform) : Edit()

        class Remove(val item: CanvasItem) : Edit()
        class Order(val items: List<CanvasItem>) : Edit()

        /** Room for the batch queued right behind it, so the buffers grow once for all of it. */
        class Reserve(val vertices: Long, val indices: Long) : Edit()
        object Reset : Edit()
    }

    private val pending = ConcurrentLinkedQueue<Edit>()

    /** Edits held back by [batch], to be queued behind one reservation for the lot. */
    private var staged: ArrayList<Edit>? = null

    /**
     * The stroke under the pen lives in its own buffers, kept apart from the committed geometry so
     * growing it never disturbs that allocation. On pen up both are cleared and the finished stroke
     * arrives through the normal upsert path.
     *
     * There are two of them because the stroke has two halves. [wetStore] holds the runs that have
     * stopped moving: they are appended once each and never touched again, which is what keeps a
     * long stroke from re-uploading everything it has laid down on every sample. [tailStore] holds
     * the few points still in play, and is cleared and refilled each time — as its own buffer, so
     * that churn cannot leave a trail of dead allocations through the settled runs. Everything the
     * selection overlay and a dragged-out shape publish goes through the tail too: none of them can
     * be on screen while a stroke is being drawn.
     */
    private val wetStore = GeometryStore()
    private var wetParts: List<Part> = emptyList()
    private val tailStore = GeometryStore()
    private var tailParts: List<Part> = emptyList()
    private var wetBounds = Rect(0.0, 0.0, 0.0, 0.0)
    private val records = IdentityHashMap<CanvasItem, Record>()

    /**
     * The selection under a drag. Its triangles stay exactly where they were tessellated and the
     * drag reaches the shader as a turn and a shift, so moving or rotating a selection costs a
     * couple of uniforms rather than a re-tessellation and a re-upload of every selected item on
     * every touch sample.
     *
     * A lifted item draws above the rest for the length of the drag, which is what the paged canvas
     * does with its own lifted selection.
     */
    private val lifted = ArrayList<CanvasItem>()
    private var liftAt = LiftTransform.NONE

    /** Records bucketed by content chunk, so a frame only visits what the viewport overlaps. */
    private val buckets = HashMap<Long, ArrayList<Record>>()
    private val oversized = ArrayList<Record>()

    /** Next unseen depth, for an item that arrives before its order message. */
    private var nextZ = 0

    /** Bumped by any change to the records, so the cached halo layer knows it is stale. */
    private var revision = 0

    /** What the cached halo layer was built for; a mismatch means rebuild it. */
    private var haloKey: String? = null

    /** The same for the dragged selection's halos, plus the point in the drag they were built at. */
    private var liftHaloKey: String? = null
    private var liftHaloAt = LiftTransform.NONE

    private var ink: InkShader? = null
    private var cover: CoverShader? = null
    private var imageShader: ImageShader? = null
    private var minimapShader: MinimapShader? = null
    private var glowShader: GlowShader? = null
    private val glowTarget = GlowTarget()

    /** Textures for placed images, decoded at the size the current zoom needs. */
    val textures = TextureCache()

    /** Runs a decode off the render thread; installed by the host. */
    var decodeOn: (Runnable) -> Unit = { it.run() }

    /** Told on the render thread when committed items start or stop being left out for memory. */
    var onOutOfMemory: ((Boolean) -> Unit)? = null
    private var reportedOutOfMemory = false

    private var contextGen = -1

    /** Reused per frame so drawing allocates nothing. */
    private val visible = ArrayList<Record>()
    private val deferred = ArrayList<Pair<Record, Part>>()

    /** Items currently drawn, for the debug readout. */
    val itemCount: Int get() = records.size

    var lastDrawCalls: Int = 0
        private set

    var lastVisibleItems: Int = 0
        private set

    /** Milliseconds the newest tessellation took, published by whoever produced it. */
    @Volatile
    var lastTessellateMs: Double = 0.0

    override fun describe(into: GlStats): GlStats = into.copy(
        items = records.size,
        visibleItems = lastVisibleItems,
        drawCalls = lastDrawCalls,
        vertices = store.usedVertices,
        vertexCapacity = store.vertexCapacity,
        indices = store.usedIndices,
        indexCapacity = store.indexCapacity,
        geometryBytes = store.gpuBytes + wetStore.gpuBytes + tailStore.gpuBytes,
        liveGeometryBytes = store.liveBytes + wetStore.liveBytes + tailStore.liveBytes,
        wetVertices = wetParts.sumOf { it.slice.vertexCount } + tailParts.sumOf { it.slice.vertexCount },
        textures = textures.textureCount,
        textureBytes = textures.residentBytes,
        texturesPending = textures.pendingCount,
        lastTessellateMs = lastTessellateMs,
    )

    // --- main-thread API ---

    fun upsert(item: CanvasItem, parts: List<MeshPart>, bounds: Rect, clearsWet: Boolean = false) {
        post(Edit.Upsert(item, parts, bounds, null, clearsWet))
    }

    /** File a placed image. It carries no geometry: the renderer draws it as a textured quad. */
    fun upsertImage(item: ImageItem, bounds: Rect) {
        post(Edit.Upsert(item, emptyList(), bounds, item, false))
    }

    fun remove(item: CanvasItem) {
        post(Edit.Remove(item))
    }

    /** Publish the document's z order; the scene draws back to front by it. */
    fun setOrder(items: List<CanvasItem>) {
        post(Edit.Order(ArrayList(items)))
    }

    /** Publish the in-progress stroke's triangles, or clear it when [mesh] is null. */
    fun setWet(mesh: MeshData?, color: Rgba, pass: InkPass, bounds: Rect) {
        post(
            Edit.Wet(
                if (mesh == null) emptyList() else listOf(MeshPart(mesh, color, pass)),
                bounds,
                WetKind.WHOLE,
            ),
        )
    }

    /** Publish an in-progress item made of several runs, which is what a filled shape is. An empty
     *  list clears the whole wet buffer, settled runs included. */
    fun setWetParts(parts: List<MeshPart>, bounds: Rect) {
        post(Edit.Wet(parts, bounds, WetKind.WHOLE))
    }

    /** Add a run of the stroke under the pen that has stopped moving; it is never rewritten. */
    fun appendWetRun(parts: List<MeshPart>, bounds: Rect) {
        post(Edit.Wet(parts, bounds, WetKind.SETTLED))
    }

    /** Replace the run still moving under the nib, leaving the settled ones where they are. */
    fun setWetTail(parts: List<MeshPart>, bounds: Rect) {
        post(Edit.Wet(parts, bounds, WetKind.TAIL))
    }

    /** Draw [items] displaced by [at] until this is called again with an empty list. */
    fun setLift(items: List<CanvasItem>, at: LiftTransform) {
        post(Edit.Lift(ArrayList(items), at))
    }

    fun reset() {
        post(Edit.Reset)
    }

    /** Queue what [block] publishes behind one reservation, so a whole load grows the buffers once. */
    fun batch(block: () -> Unit) {
        if (staged != null) return block()
        val edits = ArrayList<Edit>()
        staged = edits
        try {
            block()
        } finally {
            staged = null
            var vertices = 0L
            var indices = 0L
            for (edit in edits) {
                if (edit !is Edit.Upsert) continue
                for (part in edit.parts) {
                    vertices += part.mesh.vertexCount
                    indices += part.mesh.indices.size
                }
            }
            pending.add(Edit.Reserve(vertices, indices))
            pending.addAll(edits)
        }
    }

    private fun post(edit: Edit) {
        val batch = staged
        if (batch != null) batch.add(edit) else pending.add(edit)
    }

    // --- GL thread ---

    override fun onContextCreated(contextGen: Int) {
        this.contextGen = contextGen
        ink = null
        cover = null
        imageShader = null
        minimapShader = null
        glowShader = null
        glowTarget.onContextCreated(contextGen)
        try {
            ink = InkShader(contextGen)
            cover = CoverShader(contextGen)
            imageShader = ImageShader(contextGen)
            minimapShader = MinimapShader(contextGen)
            glowShader = GlowShader(contextGen)
        } catch (e: GlShaderException) {
            Log.e(TAG, "ink shaders unavailable", e)
        }
        textures.onContextCreated(contextGen)
        // The mirrors survived, so the whole document re-uploads without re-tessellating anything.
        store.onContextCreated(contextGen)
        wetStore.onContextCreated(contextGen)
        tailStore.onContextCreated(contextGen)
    }

    override fun drawContent(frame: FrameState) {
        drainEdits()
        textures.beginFrame()
        textures.uploadPending()
        glowTarget.resize(frame.widthPx, frame.heightPx, contextGen)
        val program = ink ?: return
        if (program.contextGen != contextGen) return
        if (records.isEmpty() && wetParts.isEmpty() && tailParts.isEmpty()) {
            lastDrawCalls = 0
            lastVisibleItems = 0
            return
        }

        val camChunkX = floor(frame.scrollX / GeometryStore.CHUNK_SIZE)
        val camChunkY = floor(frame.scrollY / GeometryStore.CHUNK_SIZE)
        program.begin(
            camChunkX, camChunkY,
            frame.scrollX - camChunkX * GeometryStore.CHUNK_SIZE,
            frame.scrollY - camChunkY * GeometryStore.CHUNK_SIZE,
            frame.zoom, frame.widthPx.toDouble(), frame.heightPx.toDouble(),
        )
        if (!store.bindForDraw(contextGen)) return
        store.bindAttributes(program)


        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        collectVisible(frame)
        lastVisibleItems = visible.size
        lastDrawCalls = 0

        // Halos of everything already committed go into a layer that is rebuilt only when the view
        // or the content moves. Re-blurring each of them every frame made the cost of neon scale
        // with how much of it was on screen: nine strokes took the canvas to a fifth of the
        // refresh rate, while none of them had actually changed.
        drawCommittedHalos(program, frame, camChunkX, camChunkY)

        // Back to front, batching every run of opaque items that happens to be contiguous in the
        // buffer into one call. Freshly loaded content is laid down in z order, so a screenful of
        // it is usually a single draw.
        deferred.clear()
        var runStart = -1
        var runCount = 0
        for (record in visible) {
            if (record.image != null) {
                flushRun(runStart, runCount)
                runStart = -1
                runCount = 0
                drawImage(record, record.image, frame)
                rebind(program, frame, camChunkX, camChunkY)
                store.bindForDraw(contextGen)
                store.bindAttributes(program)
                continue
            }
            for (part in record.parts) {
                @Suppress("UNUSED_EXPRESSION")
                when (part.pass) {
                    InkPass.MULTIPLY, InkPass.SCREEN -> {
                        // Highlighters composite over the finished picture, matching the paged
                        // canvas, so their blend acts on what is beneath instead of washing it out.
                        deferred.add(record to part)
                    }
                    InkPass.OPAQUE -> {
                        if (runStart >= 0 && part.slice.indexOffset == runStart + runCount) {
                            runCount += part.slice.indexCount
                        } else {
                            flushRun(runStart, runCount)
                            runStart = part.slice.indexOffset
                            runCount = part.slice.indexCount
                        }
                    }
                    InkPass.TRANSLUCENT, InkPass.EVEN_ODD -> {
                        flushRun(runStart, runCount)
                        runStart = -1
                        runCount = 0
                        drawMasked(record, part, frame, part.pass)
                        rebind(program, frame, camChunkX, camChunkY)
                    }
                    InkPass.GLOW -> {
                        // The halo is already on screen from the cached layer; this draws the lit
                        // body and the white core over it, from the very same triangles.
                        flushRun(runStart, runCount)
                        runStart = -1
                        runCount = 0
                        drawGlowBody(program, store, part)
                    }
                }
            }
        }
        flushRun(runStart, runCount)

        for ((record, part) in deferred) {
            drawMasked(record, part, frame, part.pass)
            rebind(program, frame, camChunkX, camChunkY)
        }

        drawLifted(program, frame, camChunkX, camChunkY)
        drawWet(program, frame, camChunkX, camChunkY)
        if (frame.minimapVisible) drawMinimap(frame)

        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        program.disableAttributes()
    }

    /**
     * The minimap: a panel, a dot per item, and the viewport's own outline.
     *
     * It is drawn from item bounds rather than by running the scene through a second transform,
     * which would cost a second full pass of everything visible. At this scale a stroke is a dot
     * either way, so the cheap version conveys exactly as much.
     */
    private fun drawMinimap(frame: FrameState) {
        val shader = minimapShader ?: return
        val panel = Minimap.panel(frame.widthPx, frame.heightPx, frame.insetRight, frame.insetBottom)
        val visible = Rect(frame.scrollX, frame.scrollY, frame.widthPx / frame.zoom, frame.heightPx / frame.zoom)
        val extent = Minimap.mappedExtent(frame.contentBounds, visible)
        val paperDim = Rgba(frame.paper.r, frame.paper.g, frame.paper.b, 210)
        shader.fill(panel, paperDim, frame.widthPx, frame.heightPx)
        shader.outline(panel, 1.0, frame.accent.withAlpha(90), frame.widthPx, frame.heightPx)

        val dot = frame.accent.withAlpha(150)
        for (record in records.values) {
            val mapped = Minimap.toPanel(record.bounds, extent, panel)
            // Everything reads as at least a dot, so a single thin stroke is not invisible.
            val w = mapped.w.coerceAtLeast(MINIMAP_DOT_PX)
            val h = mapped.h.coerceAtLeast(MINIMAP_DOT_PX)
            shader.fill(Rect(mapped.x, mapped.y, w, h), dot, frame.widthPx, frame.heightPx)
        }
        shader.outline(
            Minimap.toPanel(visible, extent, panel), 1.5, frame.accent,
            frame.widthPx, frame.heightPx,
        )
        lastDrawCalls += 9
    }

    /**
     * The selection under a drag, drawn above the rest with the drag pushed in as a uniform.
     *
     * Nothing is re-tessellated and nothing is re-uploaded, which is the whole point: the cost of
     * dragging is the same for one stroke and for a hundred. The opaque batching the main loop does
     * is skipped, since a selection is a handful of items rather than a screenful.
     */
    private fun drawLifted(program: InkShader, frame: FrameState, camChunkX: Double, camChunkY: Double) {
        if (lifted.isEmpty()) return
        val view = viewRect(frame)
        drawLiftedHalos(program, frame, camChunkX, camChunkY)
        applyView(program, frame, camChunkX, camChunkY, liftAt)
        if (!store.bindForDraw(contextGen)) return
        store.bindAttributes(program)
        for (item in lifted) {
            val record = records[item] ?: continue
            val bounds = liftAt.bounds(record.bounds)
            if (!bounds.intersects(view)) continue
            if (record.image != null) {
                drawImage(record, record.image, frame, liftAt)
                applyView(program, frame, camChunkX, camChunkY, liftAt)
                store.bindForDraw(contextGen)
                store.bindAttributes(program)
                continue
            }
            for (part in record.parts) {
                when (part.pass) {
                    InkPass.OPAQUE -> {
                        store.drawRange(part.slice.indexOffset, part.slice.indexCount)
                        lastDrawCalls++
                    }
                    InkPass.TRANSLUCENT, InkPass.EVEN_ODD, InkPass.MULTIPLY, InkPass.SCREEN -> {
                        drawMasked(
                            store, part.slice, bounds, part.coverColor, part.coverAlpha,
                            frame, part.pass,
                        )
                        applyView(program, frame, camChunkX, camChunkY, liftAt)
                        store.bindForDraw(contextGen)
                        store.bindAttributes(program)
                    }
                    // The halo is already down from the layer above; this is the lit body and core.
                    InkPass.GLOW -> drawGlowBody(program, store, part)
                }
            }
        }
        rebind(program, frame, camChunkX, camChunkY)
    }

    /**
     * The dragged selection's halos, blurred once and then slid and turned to follow the finger.
     *
     * A blur commutes with a translation, and an isotropic Gaussian commutes with a rotation too, so
     * a drag never needs a second one: the layer is built with the selection where it was when the
     * drag began, and every frame after that is one textured quad read at an offset and an angle.
     * Re-blurring per frame is what made dragging neon crawl, the same way it once made inking with
     * it crawl.
     *
     * It is rebuilt when the drag has travelled or turned far enough that content could have entered
     * from off screen, which a screen-sized buffer cannot have captured.
     */
    private fun drawLiftedHalos(
        program: InkShader,
        frame: FrameState,
        camChunkX: Double,
        camChunkY: Double,
    ) {
        val shader = glowShader ?: return
        if (!glowTarget.ready) return
        val glowing = lifted.mapNotNull { records[it] }
            .filter { record -> record.parts.any { it.pass == InkPass.GLOW } }
        if (glowing.isEmpty()) {
            liftHaloKey = null
            return
        }
        val key = "$revision|${frame.zoom}|${frame.scrollX}|${frame.scrollY}|${frame.widthPx}x${frame.heightPx}"
        val since = liftAt.since(liftHaloAt)
        val driftPx = kotlin.math.hypot(since.dx, since.dy) * frame.zoom
        val turned = kotlin.math.abs(kotlin.math.atan2(since.b, since.a))
        // A scale is only exactly a read of the blurred picture while it stays uniform, so a
        // stretched halo is re-blurred once it has strayed far enough to show.
        val stretched = since.linearScale !in LIFT_HALO_KEEP_SCALE..(1.0 / LIFT_HALO_KEEP_SCALE)
        if (key != liftHaloKey || driftPx > LIFT_HALO_REBUILD_PX ||
            turned > LIFT_HALO_REBUILD_RAD || stretched
        ) {
            liftHaloAt = liftAt
            glowTarget.bind(glowTarget.liftLayerIndex)
            GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
            GLES30.glClearColor(0f, 0f, 0f, 0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            for (record in glowing) {
                for (part in record.parts) {
                    if (part.pass != InkPass.GLOW) continue
                    drawGlow(
                        program, store, record, part, frame, camChunkX, camChunkY,
                        targetLayer = glowTarget.liftLayerIndex, lift = liftHaloAt,
                    )
                }
            }
            glowTarget.unbind(frame.widthPx, frame.heightPx)
            liftHaloKey = key
        }
        // What is left of the drag since the layer was built, as a slide and a turn of the finished
        // picture. Device y runs down and the buffer's runs up, so the vertical term changes sign.
        // The turn needs no sign flip: reading the source at the inverse angle and the axis flip
        // cancel, leaving the drag's own angle.
        val rest = liftAt.since(liftHaloAt)
        val slideX = (rest.dx * frame.zoom / frame.widthPx).toFloat()
        val slideY = (rest.dy * frame.zoom / frame.heightPx).toFloat()
        val pivotDeviceX = (rest.pivot.x - frame.scrollX) * frame.zoom
        val pivotDeviceY = (rest.pivot.y - frame.scrollY) * frame.zoom
        // A texture read runs destination to source, so the map goes in inverted. The buffer's v
        // axis runs up while the device's y runs down, which flips the two off-diagonal terms.
        val inv = rest.inverseLinear()
        shader.compositeOver(
            glowTarget.texture(glowTarget.liftLayerIndex), 1.0,
            uvOffsetX = -slideX, uvOffsetY = slideY,
            uvMapA = inv[0].toFloat(), uvMapB = (-inv[1]).toFloat(),
            uvMapC = (-inv[2]).toFloat(), uvMapD = inv[3].toFloat(),
            uvPivotX = (pivotDeviceX / frame.widthPx).toFloat(),
            uvPivotY = (1.0 - pivotDeviceY / frame.heightPx).toFloat(),
            uvSizeX = frame.widthPx.toFloat(), uvSizeY = frame.heightPx.toFloat(),
        )
        lastDrawCalls++
    }

    private fun viewRect(frame: FrameState) = Rect(
        frame.scrollX, frame.scrollY, frame.widthPx / frame.zoom, frame.heightPx / frame.zoom,
    )

    /**
     * The stroke under the pen, drawn last because it is the newest thing on the canvas. It lives
     * in its own buffer, so it is bound over the committed one for these few triangles and the
     * committed geometry's allocation is never disturbed while a stroke grows.
     */
    private fun drawWet(program: InkShader, frame: FrameState, camChunkX: Double, camChunkY: Double) {
        // Settled runs first, then the one still under the nib over it, so the two overlap the way
        // they were meshed to.
        drawWetBuffer(wetStore, wetParts, program, frame, camChunkX, camChunkY)
        drawWetBuffer(tailStore, tailParts, program, frame, camChunkX, camChunkY)
        if (wetParts.isEmpty() && tailParts.isEmpty()) return
        // Leave the committed buffers bound for the next frame's first draw.
        store.bindForDraw(contextGen)
        store.bindAttributes(program)
    }

    private fun drawWetBuffer(
        buffer: GeometryStore,
        parts: List<Part>,
        program: InkShader,
        frame: FrameState,
        camChunkX: Double,
        camChunkY: Double,
    ) {
        if (parts.isEmpty()) return
        if (!buffer.bindForDraw(contextGen)) return
        buffer.bindAttributes(program)
        // The settled runs are put into a fresh buffer in order, so they land back to back and a
        // whole stroke's worth of them collapses into one call rather than one per run.
        var runStart = -1
        var runCount = 0
        fun flush() {
            if (runStart >= 0 && runCount > 0) {
                buffer.drawRange(runStart, runCount)
                lastDrawCalls++
            }
            runStart = -1
            runCount = 0
        }
        for (part in parts) {
            if (part.pass == InkPass.OPAQUE) {
                if (runStart >= 0 && part.slice.indexOffset == runStart + runCount) {
                    runCount += part.slice.indexCount
                } else {
                    flush()
                    runStart = part.slice.indexOffset
                    runCount = part.slice.indexCount
                }
                continue
            }
            flush()
            if (part.pass == InkPass.GLOW) {
                drawGlow(program, buffer, wetRecord(), part, frame, camChunkX, camChunkY)
            } else {
                drawMasked(
                    buffer, part.slice, wetBounds, part.coverColor, part.coverAlpha,
                    frame, part.pass,
                )
            }
            rebind(program, frame, camChunkX, camChunkY)
            buffer.bindForDraw(contextGen)
            buffer.bindAttributes(program)
        }
        flush()
    }

    /**
     * A placed image as one textured quad. The corners are worked out in double precision here and
     * handed over in clip space, so an image a long way from the origin is placed exactly.
     *
     * Only the drag's shift is honoured, never its turn: an image's angle lives in the model, so a
     * selection holding one is turned by the model rather than lifted. The stored angle is folded
     * into the corners here, which is all a rotation costs on a quad.
     */
    private fun drawImage(
        record: Record,
        image: ImageItem,
        frame: FrameState,
        lift: LiftTransform = LiftTransform.NONE,
    ) {
        val shader = imageShader ?: return
        val rect = image.rect.translate(lift.dx, lift.dy)
        // Decode for the size the image actually occupies on screen right now.
        val onScreenEdge = (maxOf(rect.w, rect.h) * frame.zoom).toInt().coerceAtLeast(1)
        val texture = textures.textureFor(image.image, onScreenEdge, decodeOn)
        if (texture == 0) return
        val corners = FloatArray(8)
        val xs = doubleArrayOf(rect.left, rect.right, rect.left, rect.right)
        val ys = doubleArrayOf(rect.top, rect.top, rect.bottom, rect.bottom)
        val turn = image.angle
        val co = kotlin.math.cos(turn)
        val sn = kotlin.math.sin(turn)
        for (i in 0 until 4) {
            var wx = xs[i]
            var wy = ys[i]
            if (turn != 0.0) {
                val ox = wx - rect.centerX
                val oy = wy - rect.centerY
                wx = rect.centerX + ox * co - oy * sn
                wy = rect.centerY + ox * sn + oy * co
            }
            val dx = (wx - frame.scrollX) * frame.zoom
            val dy = (wy - frame.scrollY) * frame.zoom
            corners[2 * i] = (dx / frame.widthPx * 2.0 - 1.0).toFloat()
            corners[2 * i + 1] = (1.0 - dy / frame.heightPx * 2.0).toFloat()
        }
        shader.draw(corners, texture, image.orientation / 90)
        lastDrawCalls++
    }

    /**
     * Neon's two halos: the geometry into an offscreen buffer, blurred across and back, then
     * composited under the item at the halo's own brightness. Twice, wide and faint under tight and
     * bright, which is the layering the paged renderer paints.
     *
     * The buffers are a fraction of the viewport, since a blur hides the resolution it was computed
     * at, and each halo composites once rather than per overlap, so a self-crossing scribble cannot
     * pile up into a brighter blob.
     */
    /**
     * Composite every committed item's halo, rebuilding the layer they share only when the view or
     * the content has actually moved. While a stroke is being drawn neither has, so this is one
     * textured quad however much neon is on screen.
     *
     * The halos land under all the ink rather than each under its own item. They are soft and
     * diffuse, so the difference shows only where a neon stroke sits beneath other content, which
     * is a small price for not paying a blur per item per frame.
     */
    private fun drawCommittedHalos(
        program: InkShader,
        frame: FrameState,
        camChunkX: Double,
        camChunkY: Double,
    ) {
        val shader = glowShader ?: return
        if (!glowTarget.ready) return
        val glowing = visible.filter { record -> record.parts.any { it.pass == InkPass.GLOW } }
        if (glowing.isEmpty()) {
            haloKey = null
            return
        }
        val key = "$revision|${frame.zoom}|${frame.scrollX}|${frame.scrollY}|${frame.widthPx}x${frame.heightPx}"
        if (key != haloKey) {
            buildHaloLayer(program, glowing, frame, camChunkX, camChunkY)
            haloKey = key
            rebind(program, frame, camChunkX, camChunkY)
            store.bindForDraw(contextGen)
            store.bindAttributes(program)
        }
        shader.compositeOver(glowTarget.texture(glowTarget.layerIndex), 1.0)
        lastDrawCalls++
        rebind(program, frame, camChunkX, camChunkY)
        store.bindForDraw(contextGen)
        store.bindAttributes(program)
    }

    /** Blur every committed halo into the shared layer, once, for this view. */
    private fun buildHaloLayer(
        program: InkShader,
        glowing: List<Record>,
        frame: FrameState,
        camChunkX: Double,
        camChunkY: Double,
    ) {
        glowTarget.bind(glowTarget.layerIndex)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        for (record in glowing) {
            for (part in record.parts) {
                if (part.pass != InkPass.GLOW) continue
                drawGlow(
                    program, store, record, part, frame, camChunkX, camChunkY,
                    targetLayer = glowTarget.layerIndex,
                )
            }
        }
        glowTarget.unbind(frame.widthPx, frame.heightPx)
    }

    /** Neon's lit body and white core, both from the same triangles the halo came from. */
    private fun drawGlowBody(program: InkShader, from: GeometryStore, part: Part) {
        val spec = part.glow ?: return
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        program.setOverride(spec.bodyColor)
        from.drawRange(part.slice.indexOffset, part.slice.indexCount)
        program.setOverride(spec.coreColor)
        program.setWidthScale(spec.coreScale)
        from.drawRange(part.slice.indexOffset, part.slice.indexCount)
        program.setWidthScale(1.0)
        program.clearOverride()
        lastDrawCalls += 2
    }

    private fun drawGlow(
        program: InkShader,
        from: GeometryStore,
        record: Record,
        part: Part,
        frame: FrameState,
        camChunkX: Double,
        camChunkY: Double,
        /** Glow buffer to accumulate into, or -1 to composite straight onto the screen. */
        targetLayer: Int = -1,
        lift: LiftTransform = LiftTransform.NONE,
    ) {
        val shader = glowShader ?: return
        val spec = part.glow ?: return
        if (!glowTarget.ready) return
        val bounds = lift.bounds(record.bounds)

        for (halo in 0 until 2) {
            val radius = if (halo == 0) spec.wideRadius else spec.tightRadius
            val alpha = if (halo == 0) spec.wideAlpha else spec.tightAlpha
            if (alpha <= 0.0 || radius <= 0.0) continue
            // The blur works in the buffer's own pixels, so the content radius comes through the
            // zoom and then the buffer's own ratio to the screen.
            val radiusPx = (radius * frame.zoom * bufferRatioX(frame))
                .coerceAtMost(GlowShader.MAX_TAPS.toDouble() * 2.0)
            if (radiusPx < 0.4) continue

            // Only the stroke's own patch of screen is touched, grown by the blur's reach. A halo
            // is a small thing on a large display, and clearing and blurring the whole buffer for
            // each one is what made inking with neon crawl.
            val patch = glowPatch(bounds, radiusPx / bufferRatioX(frame), frame) ?: continue
            GLES30.glEnable(GLES30.GL_SCISSOR_TEST)

            scissorInBuffer(patch, frame)
            glowTarget.bind(0)
            GLES30.glClearColor(0f, 0f, 0f, 0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            // Exactly the projection the screen pass uses, with nothing divided by anything. The
            // downscale is already done: glViewport maps clip space onto the smaller buffer, so
            // rendering the same picture into it renders it smaller. Shrinking the zoom as well
            // applied the downscale twice and put the halo down at half size in the corner, which
            // is what a halo sitting away from its stroke looks like. It also means the whole
            // buffer is exactly the whole screen, so nothing has to be cropped on the way back.
            applyView(program, frame, camChunkX, camChunkY, lift)
            from.bindForDraw(contextGen)
            from.bindAttributes(program)
            GLES30.glDisable(GLES30.GL_BLEND)
            from.drawRange(part.slice.indexOffset, part.slice.indexCount)
            program.disableAttributes()

            glowTarget.bind(1)
            shader.blur(glowTarget.texture(0), radiusPx, true, glowTarget.bufferWidth, glowTarget.bufferHeight)
            glowTarget.bind(0)
            shader.blur(glowTarget.texture(1), radiusPx, false, glowTarget.bufferWidth, glowTarget.bufferHeight)

            // The buffer is the screen, so every hop samples it one to one.
            if (targetLayer >= 0) {
                glowTarget.bind(targetLayer)
                scissorInBuffer(patch, frame)
            } else {
                glowTarget.unbind(frame.widthPx, frame.heightPx)
                GLES30.glScissor(patch[0], frame.heightPx - patch[1] - patch[3], patch[2], patch[3])
            }
            shader.compositeOver(glowTarget.texture(0), alpha)
            GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
            lastDrawCalls += 4
        }
        if (targetLayer >= 0) return

        // The lit body and the white core, both from the very same triangles: the shader overrides
        // the colour and scales the stored spine offset, so neon costs one tessellation and one
        // copy in the buffer rather than three of each.
        applyView(program, frame, camChunkX, camChunkY, lift)
        from.bindForDraw(contextGen)
        from.bindAttributes(program)
        drawGlowBody(program, from, part)
    }

    /**
     * The item's patch of screen, in device pixels with y down, grown by the blur's reach and
     * clipped to the viewport. Null when it falls entirely off screen.
     */
    private fun glowPatch(bounds: Rect, reachPx: Double, frame: FrameState): IntArray? {
        val pad = reachPx * 2.0 + 4.0
        val x0 = ((bounds.left - frame.scrollX) * frame.zoom - pad).toInt().coerceIn(0, frame.widthPx)
        val y0 = ((bounds.top - frame.scrollY) * frame.zoom - pad).toInt().coerceIn(0, frame.heightPx)
        val x1 = ((bounds.right - frame.scrollX) * frame.zoom + pad).toInt().coerceIn(0, frame.widthPx)
        val y1 = ((bounds.bottom - frame.scrollY) * frame.zoom + pad).toInt().coerceIn(0, frame.heightPx)
        if (x1 <= x0 || y1 <= y0) return null
        return intArrayOf(x0, y0, x1 - x0, y1 - y0)
    }

    /** Buffer pixels per screen pixel. The buffer covers the whole screen, so this is its ratio. */
    private fun bufferRatioX(frame: FrameState): Double =
        if (frame.widthPx <= 0) 1.0 else glowTarget.bufferWidth.toDouble() / frame.widthPx

    private fun bufferRatioY(frame: FrameState): Double =
        if (frame.heightPx <= 0) 1.0 else glowTarget.bufferHeight.toDouble() / frame.heightPx

    /** Set the scissor for a glow-buffer pass, converting the device patch into buffer pixels. */
    private fun scissorInBuffer(patch: IntArray, frame: FrameState) {
        val rx = bufferRatioX(frame)
        val ry = bufferRatioY(frame)
        val x = kotlin.math.floor(patch[0] * rx).toInt().coerceIn(0, glowTarget.bufferWidth)
        val yTop = kotlin.math.floor(patch[1] * ry).toInt().coerceIn(0, glowTarget.bufferHeight)
        val w = (kotlin.math.ceil(patch[2] * rx).toInt() + 1).coerceAtMost(glowTarget.bufferWidth - x)
        val h = (kotlin.math.ceil(patch[3] * ry).toInt() + 1).coerceAtMost(glowTarget.bufferHeight - yTop)
        // The buffer shares the framebuffer's y-up convention, so the patch flips into it.
        val y = (glowTarget.bufferHeight - yTop - h).coerceAtLeast(0)
        if (w <= 0 || h <= 0) return
        GLES30.glScissor(x, y, w, h)
    }

    private fun flushRun(start: Int, count: Int) {
        if (start < 0 || count <= 0) return
        store.drawRange(start, count)
        lastDrawCalls++
    }

    /**
     * Stencil the stroke, then paint its colour through the mask exactly once. The cover zeroes the
     * stencil as it draws, so consecutive strokes need no clear between them.
     */
    /** Re-point the ink program after a cover draw took the program and buffer bindings away. */
    private fun rebind(program: InkShader, frame: FrameState, camChunkX: Double, camChunkY: Double) =
        applyView(program, frame, camChunkX, camChunkY)

    /** Bind the ink program for this frame's view, optionally displaced for a dragged selection. */
    private fun applyView(
        program: InkShader,
        frame: FrameState,
        camChunkX: Double,
        camChunkY: Double,
        lift: LiftTransform = LiftTransform.NONE,
    ) {
        program.begin(
            camChunkX, camChunkY,
            frame.scrollX - camChunkX * GeometryStore.CHUNK_SIZE,
            frame.scrollY - camChunkY * GeometryStore.CHUNK_SIZE,
            frame.zoom, frame.widthPx.toDouble(), frame.heightPx.toDouble(),
        )
        if (lift.isIdentity) return
        // The pivot goes in the camera's own chunk frame, the frame the vertices rebuild themselves
        // in, so nothing large is ever subtracted from anything large.
        program.setLift(
            lift.pivot.x - camChunkX * GeometryStore.CHUNK_SIZE,
            lift.pivot.y - camChunkY * GeometryStore.CHUNK_SIZE,
            lift.a, lift.b, lift.c, lift.d, lift.linearScale, lift.dx, lift.dy,
        )
    }

    private fun drawMasked(record: Record, part: Part, frame: FrameState, pass: InkPass) =
        drawMasked(store, part.slice, record.bounds, part.coverColor, part.coverAlpha, frame, pass)

    private fun drawMasked(
        from: GeometryStore,
        slice: BufferSlice,
        bounds: Rect,
        coverColor: Rgba,
        coverAlpha: Double,
        frame: FrameState,
        pass: InkPass,
    ) {
        val quad = cover ?: return
        // An even-odd fill inverts instead of replacing, so the parity of how many times a pixel
        // was covered is the fill rule itself: a hole cancels, a self-crossing cancels, and the
        // cover then paints exactly what the outline encloses without a single triangle of setup.
        val invert = pass == InkPass.EVEN_ODD
        GLES30.glEnable(GLES30.GL_STENCIL_TEST)
        GLES30.glStencilFunc(GLES30.GL_ALWAYS, if (invert) 0 else 1, 0xFF)
        GLES30.glStencilOp(
            GLES30.GL_KEEP, GLES30.GL_KEEP,
            if (invert) GLES30.GL_INVERT else GLES30.GL_REPLACE,
        )
        GLES30.glStencilMask(0xFF)
        GLES30.glColorMask(false, false, false, false)
        from.drawRange(slice.indexOffset, slice.indexCount)
        lastDrawCalls++

        GLES30.glColorMask(true, true, true, true)
        GLES30.glStencilFunc(if (invert) GLES30.GL_NOTEQUAL else GLES30.GL_EQUAL, if (invert) 0 else 1, 0xFF)
        GLES30.glStencilOp(GLES30.GL_KEEP, GLES30.GL_KEEP, GLES30.GL_ZERO)
        when (pass) {
            // Exact multiply: the destination is scaled by the source and nothing is added.
            InkPass.MULTIPLY -> GLES30.glBlendFunc(GLES30.GL_DST_COLOR, GLES30.GL_ZERO)
            // Exact screen: src + dst - src*dst, the multiply reflected about white.
            InkPass.SCREEN -> GLES30.glBlendFunc(GLES30.GL_ONE_MINUS_DST_COLOR, GLES30.GL_ONE)
            else -> GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        }
        val r = clipRect(bounds, frame)
        // A multiply/screen cover must present the ink already faded by its own alpha (toward white
        // and toward black respectively), since the blend function cannot apply an alpha of its own.
        val color = when (pass) {
            InkPass.MULTIPLY -> ItemMesher.multiplyColor(coverColor, coverAlpha)
            InkPass.SCREEN -> ItemMesher.screenColor(coverColor, coverAlpha)
            else -> coverColor
        }
        val alpha = if (pass == InkPass.MULTIPLY || pass == InkPass.SCREEN) 1.0 else coverAlpha
        quad.draw(r[0], r[1], r[2], r[3], color, alpha)
        lastDrawCalls++

        GLES30.glDisable(GLES30.GL_STENCIL_TEST)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
    }

    /** Content bounds to clip space, grown a couple of pixels so the mask is never clipped short. */
    private fun clipRect(bounds: Rect, frame: FrameState): FloatArray {
        val pad = 2.0
        val x0 = (bounds.left - frame.scrollX) * frame.zoom - pad
        val y0 = (bounds.top - frame.scrollY) * frame.zoom - pad
        val x1 = (bounds.right - frame.scrollX) * frame.zoom + pad
        val y1 = (bounds.bottom - frame.scrollY) * frame.zoom + pad
        val w = frame.widthPx.toDouble()
        val h = frame.heightPx.toDouble()
        return floatArrayOf(
            (x0 / w * 2.0 - 1.0).coerceIn(-1.0, 1.0).toFloat(),
            (1.0 - y0 / h * 2.0).coerceIn(-1.0, 1.0).toFloat(),
            (x1 / w * 2.0 - 1.0).coerceIn(-1.0, 1.0).toFloat(),
            (1.0 - y1 / h * 2.0).coerceIn(-1.0, 1.0).toFloat(),
        )
    }

    // --- record bookkeeping ---

    private fun drainEdits() {
        while (true) {
            when (val edit = pending.poll() ?: break) {
                is Edit.Upsert -> applyUpsert(edit)
                is Edit.Wet -> applyWet(edit)
                is Edit.Lift -> applyLift(edit)
                is Edit.Remove -> applyRemove(edit.item)
                is Edit.Order -> applyOrder(edit.items)
                is Edit.Reserve -> store.reserve(edit.vertices, edit.indices)
                Edit.Reset -> applyReset()
            }
        }
        reportMemory()
    }

    private fun reportMemory() {
        val short = store.outOfMemory
        if (short == reportedOutOfMemory) return
        reportedOutOfMemory = short
        if (short) Log.w(TAG, "geometry buffers could not grow; some items are not drawn")
        onOutOfMemory?.invoke(short)
    }

    private fun applyUpsert(edit: Edit.Upsert) {
        revision++
        // Committing the wet stroke is one step, not two: the buffer is released and the finished
        // stroke takes its place within a single frame's drain, so nothing can be drawn between.
        if (edit.clearsWet) clearWetBuffer()
        // An item edited in place keeps its depth: only a structural change reorders, and that
        // arrives as its own message.
        val previousZ = records[edit.item]?.z
        applyRemove(edit.item)
        val parts = ArrayList<Part>(edit.parts.size)
        for (part in edit.parts) {
            // A translucent run accumulates at full alpha and gets its alpha back at cover time,
            // which is what stops its own overlaps from compounding.
            val baked = if (part.pass == InkPass.OPAQUE) part.color else part.color.withAlpha(255)
            val slice = store.put(part.mesh, baked) ?: continue
            parts.add(Part(slice, part.pass, part.color.withAlpha(255), part.color.a / 255.0, part.glow))
        }
        if (parts.isEmpty() && edit.image == null) return
        val record = Record(edit.item, parts, edit.bounds, previousZ ?: nextZ++, edit.image)
        records[edit.item] = record
        fileRecord(record)
    }

    private fun applyWet(edit: Edit.Wet) {
        when (edit.kind) {
            WetKind.WHOLE -> {
                clearWetBuffer()
                if (edit.parts.isEmpty()) return
                wetBounds = edit.bounds
                tailParts = build(edit.parts, tailStore)
            }
            WetKind.SETTLED -> {
                // Appended, never rewritten: this run is ink that can no longer change.
                wetBounds = edit.bounds
                if (edit.parts.isEmpty()) return
                wetParts = wetParts + build(edit.parts, wetStore)
            }
            WetKind.TAIL -> {
                // Its own buffer, cleared whole, so the churn under the nib cannot fragment the
                // settled runs sitting next to it.
                wetBounds = edit.bounds
                tailStore.clear()
                tailParts = if (edit.parts.isEmpty()) emptyList() else build(edit.parts, tailStore)
            }
        }
    }

    private fun build(parts: List<MeshPart>, into: GeometryStore): List<Part> {
        val built = ArrayList<Part>(parts.size)
        for (part in parts) {
            // A translucent run accumulates at full alpha and gets its alpha back at cover time,
            // which is what stops its own overlaps from compounding.
            val baked = if (part.pass == InkPass.OPAQUE) part.color else part.color.withAlpha(255)
            val slice = into.put(part.mesh, baked) ?: continue
            built.add(Part(slice, part.pass, part.color.withAlpha(255), part.color.a / 255.0, part.glow))
        }
        return built
    }

    private fun applyLift(edit: Edit.Lift) {
        // Only which items are lifted can stale the shared halo layer, since a lifted item is left
        // out of it. How far the drag has got cannot: that is drawn separately. Bumping the
        // revision for the offset too rebuilt every committed halo on screen on every touch
        // sample, which is the cost the layer exists to avoid.
        if (!sameItems(lifted, edit.items)) {
            revision++
            lifted.clear()
            lifted.addAll(edit.items)
        }
        liftAt = edit.at
    }

    private fun sameItems(a: List<CanvasItem>, b: List<CanvasItem>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) if (a[i] !== b[i]) return false
        return true
    }

    /** A stand-in record for the wet item, so the glow pass can read its bounds like any other. */
    private fun wetRecord(): Record = Record(WET_KEY, emptyList(), wetBounds, 0)

    private fun clearWetBuffer() {
        wetStore.clear()
        wetParts = emptyList()
        tailStore.clear()
        tailParts = emptyList()
    }

    private fun applyRemove(item: CanvasItem) {
        revision++
        val record = records.remove(item) ?: return
        unfileRecord(record)
        for (part in record.parts) store.free(part.slice)
    }

    private fun applyOrder(items: List<CanvasItem>) {
        revision++
        for (i in items.indices) records[items[i]]?.z = i
    }

    private fun applyReset() {
        revision++
        nextZ = 0
        lifted.clear()
        records.clear()
        buckets.clear()
        oversized.clear()
        store.clear()
    }

    // --- culling ---

    private fun collectVisible(frame: FrameState) {
        visible.clear()
        val view = viewRect(frame)
        for (record in oversized) if (record.bounds.intersects(view)) visible.add(record)
        val x0 = chunkOf(view.left)
        val y0 = chunkOf(view.top)
        val x1 = chunkOf(view.right)
        val y1 = chunkOf(view.bottom)
        if ((x1 - x0 + 1).toLong() * (y1 - y0 + 1).toLong() > MAX_QUERY_CELLS) {
            // Zoomed so far out that walking cells costs more than testing everything.
            for (record in records.values) if (record.bounds.intersects(view)) visible.add(record)
            visible.sortBy { it.z }
            dedupeSorted()
            return
        }
        for (cy in y0..y1) {
            for (cx in x0..x1) {
                val bucket = buckets[key(cx, cy)] ?: continue
                for (record in bucket) if (record.bounds.intersects(view)) visible.add(record)
            }
        }
        visible.sortBy { it.z }
        dedupeSorted()
    }

    /**
     * Drop the repeats a record filed in several chunks produced, and anything a drag has lifted;
     * the list is already z sorted. A lifted record draws in its own pass, from where it is being
     * dragged to rather than from where its triangles sit.
     */
    private fun dedupeSorted() {
        if (visible.isEmpty()) return
        var write = 0
        for (read in visible.indices) {
            val record = visible[read]
            if (write > 0 && record === visible[write - 1]) continue
            if (isLifted(record.item)) continue
            visible[write] = record
            write++
        }
        while (visible.size > write) visible.removeAt(visible.size - 1)
    }

    private fun isLifted(item: CanvasItem): Boolean {
        for (i in lifted.indices) if (lifted[i] === item) return true
        return false
    }

    private fun fileRecord(record: Record) {
        val x0 = chunkOf(record.bounds.left)
        val y0 = chunkOf(record.bounds.top)
        val x1 = chunkOf(record.bounds.right)
        val y1 = chunkOf(record.bounds.bottom)
        if ((x1 - x0 + 1).toLong() * (y1 - y0 + 1).toLong() > MAX_FILE_CELLS) {
            oversized.add(record)
            return
        }
        for (cy in y0..y1) {
            for (cx in x0..x1) buckets.getOrPut(key(cx, cy)) { ArrayList(4) }.add(record)
        }
    }

    private fun unfileRecord(record: Record) {
        val x0 = chunkOf(record.bounds.left)
        val y0 = chunkOf(record.bounds.top)
        val x1 = chunkOf(record.bounds.right)
        val y1 = chunkOf(record.bounds.bottom)
        if ((x1 - x0 + 1).toLong() * (y1 - y0 + 1).toLong() > MAX_FILE_CELLS) {
            oversized.removeAll { it === record }
            return
        }
        for (cy in y0..y1) {
            for (cx in x0..x1) {
                val k = key(cx, cy)
                val bucket = buckets[k] ?: continue
                bucket.removeAll { it === record }
                if (bucket.isEmpty()) buckets.remove(k)
            }
        }
    }

    private fun chunkOf(v: Double): Int {
        if (!v.isFinite()) return 0
        return floor(v / GeometryStore.CHUNK_SIZE).coerceIn(-32768.0, 32767.0).toInt()
    }

    private fun key(cx: Int, cy: Int): Long = (cx.toLong() shl 32) or (cy.toLong() and 0xffffffffL)

    companion object {
        private const val TAG = "xnotes.gl"

        /** Widest cell span a record is binned across before it is just tested on every frame. */
        private const val MAX_FILE_CELLS = 256L

        /** Cells a cull will walk before it gives up and scans every record instead. */
        private const val MAX_QUERY_CELLS = 4096L

        /** Smallest a minimap marker is drawn, so one thin stroke is still visible. */
        private const val MINIMAP_DOT_PX = 1.5

        /**
         * Device pixels a drag may slide its halo layer before it is blurred again. The layer only
         * holds what was on screen when it was built, so a long drag has to refresh it to pick up
         * anything that has since come into view.
         */
        private const val LIFT_HALO_REBUILD_PX = 96.0

        /** And how far it can turn, for the same reason: about a sixth of a full turn. */
        private const val LIFT_HALO_REBUILD_RAD = 1.0

        /** Smallest residual scale a stretched halo is still read rather than blurred again. */
        private const val LIFT_HALO_KEEP_SCALE = 0.85

        /** Identity for the wet item's stand-in record; never filed or drawn as itself. */
        private val WET_KEY: CanvasItem = object : CanvasItem {
            override val kind = "wet"
            override val resizable = false
            override var locked = false
            override var link: String? = null
            override fun paint(r: com.xnotes.core.pal.Renderer) = Unit
            override fun bounds() = Rect(0.0, 0.0, 0.0, 0.0)
            override fun translate(dx: Double, dy: Double) = Unit
            override fun contains(p: com.xnotes.core.geometry.Pt) = false
            override fun centroid() = com.xnotes.core.geometry.Pt.ZERO
            override fun intersectsCircle(cx: Double, cy: Double, radius: Double) = false
            override fun snapshotGeometry(): com.xnotes.core.model.GeometrySnapshot =
                throw UnsupportedOperationException()
            override fun restoreGeometry(snap: com.xnotes.core.model.GeometrySnapshot) = Unit
            override fun applyTransform(t: com.xnotes.core.geometry.Affine) = Unit
        }
    }
}
