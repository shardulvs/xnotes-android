package com.xnotes.gl

/**
 * A snapshot of what the render thread just did, for the debug HUD. Written on the GL thread and
 * read on the main thread, so it is one immutable value swapped wholesale rather than a set of
 * fields that could be read half updated.
 */
data class GlStats(
    /** Frames actually drawn in the last second. A count, not a smoothed interval. */
    val fps: Double = 0.0,
    /** Milliseconds of work inside the frame, excluding the wait for the display. */
    val frameMs: Double = 0.0,
    /** Items the scene holds geometry for. */
    val items: Int = 0,
    /** Items that survived the cull this frame. */
    val visibleItems: Int = 0,
    /** Draw calls this frame. A climb at a fixed item count means the buffer has fragmented. */
    val drawCalls: Int = 0,
    /** Bytes the GPU holds for committed geometry, capacity rather than occupancy. */
    val geometryBytes: Long = 0,
    /** Bytes live geometry actually references; the gap to [geometryBytes] is fragmentation. */
    val liveGeometryBytes: Long = 0,
    /** Image textures resident on the GPU. */
    val textures: Int = 0,
    val textureBytes: Long = 0,
)
