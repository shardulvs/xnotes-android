package com.xnotes.ui

import android.os.Debug
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.gl.GlStats
import com.xnotes.ui.theme.toComposeColor
import com.xnotes.core.model.Rgba
import kotlinx.coroutines.delay

/**
 * The GL canvas's debug HUD, toggled by a four-finger tap like the paged canvas's.
 *
 * It reports rather more than the paged one because the GL path has failure modes the paged path
 * simply does not have. Buffers that grow without bound, an EGL context silently rebuilt under the
 * app, a cull that stopped culling, a draw-call count creeping up as the geometry buffer
 * fragments, a driver that quietly refused multisampling: none of those show as anything but "it
 * feels wrong" until you can read them.
 *
 * Drawn as Compose over the surface rather than inside the GL frame, so it costs the render thread
 * nothing and cannot itself perturb what it is measuring.
 */
@Composable
fun CanvasDebugOverlay(editor: InfiniteEditor) {
    if (!editor.debugVisible) return
    var stats by remember { mutableStateOf(GlStats()) }
    var memory by remember { mutableStateOf(MemorySample()) }
    var tick by remember { mutableStateOf(0) }

    // The canvas only repaints on interaction, so the HUD drives its own refresh; without it the
    // frame rate would freeze at its last value instead of falling to zero when idle.
    LaunchedEffect(Unit) {
        while (true) {
            stats = editor.view.stats
            if (tick % 4 == 0) memory = sampleMemory()
            tick++
            editor.requestRender()
            delay(REFRESH_MS)
        }
    }

    val v = editor.viewport
    val lines = buildList {
        // The rate is a count over the last second, and the panel's own rate is next to it, so a
        // number at the cap is recognizable as the cap rather than as headroom.
        add("fps       %.0f / %.0f".format(stats.fps, stats.displayHz))
        add("frame     %.2f ms".format(stats.frameMs))
        add("worst     %.1f ms".format(stats.worstFrameMs))
        add("late      ${stats.jankFrames}")
        add("req/frame %.1f".format(stats.requestsPerFrame))
        add("step      %.1f px".format(stats.stepPx))
        add("jitter    %.0f%%".format(stats.stepJitter * 100))
        add("zoom      %.3fx".format(v.zoom))
        add("scroll    %.0f, %.0f".format(v.scrollX, v.scrollY))
        add("viewport  ${v.widthPx} x ${v.heightPx}")
        add("")
        add("items     ${stats.items}")
        add("visible   ${stats.visibleItems}")
        add("draws     ${stats.drawCalls}")
        add("wet verts ${stats.wetVertices}")
        add("front     ${editor.pad.hud}")
        add("textures  ${stats.textures} (${stats.texturesPending} pending)")
        add("tex vram  ${fmtBytes(stats.textureBytes)}")
        add("tessel    %.2f ms".format(stats.lastTessellateMs))
        add("")
        add("verts     ${stats.vertices} / ${stats.vertexCapacity}")
        add("indices   ${stats.indices} / ${stats.indexCapacity}")
        add("geom vram ${fmtBytes(stats.geometryBytes)}")
        add("geom live ${fmtBytes(stats.liveGeometryBytes)}")
        add("frag      ${fmtPercent(stats.liveGeometryBytes, stats.geometryBytes)}")
        add("")
        add("msaa      ${if (stats.msaaSamples >= 2) "${stats.msaaSamples}x" else "none"}")
        add("ctx gen   ${stats.contextGen}")
        add("gpu       ${stats.renderer.take(28)}")
        add("check     ${stats.selfCheck.take(28)}")
        add("")
        add("heap  %.0f / %.0f MB".format(memory.heapUsedMb, memory.heapMaxMb))
        add("pss    %.0f MB".format(memory.pssMb))
        add("native %.0f MB".format(memory.nativeMb))
        add("gfx    %.0f MB".format(memory.gfxMb))
        editor.renderFailure?.let {
            add("")
            add("GL FAILED $it")
        }
        if (editor.outOfMemory) {
            add("")
            add("GEOMETRY OUT OF MEMORY")
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .background(PANEL_BG.toComposeColor())
                .width(232.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            for (line in lines) {
                Text(
                    line,
                    color = TEXT.toComposeColor(),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                )
            }
            Text(
                "four finger tap to dismiss",
                color = TEXT_DIM.toComposeColor(),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

private data class MemorySample(
    val heapUsedMb: Double = 0.0,
    val heapMaxMb: Double = 0.0,
    val pssMb: Double = 0.0,
    val nativeMb: Double = 0.0,
    val gfxMb: Double = 0.0,
)

/**
 * Total, native and graphics memory. [Debug.getMemoryInfo] walks `/proc/self/smaps`, so it is far
 * too heavy to read every refresh and is sampled a quarter as often as the rest.
 */
private fun sampleMemory(): MemorySample {
    val info = Debug.MemoryInfo()
    Debug.getMemoryInfo(info)
    fun stat(key: String) = (info.getMemoryStat(key)?.toLongOrNull() ?: 0L) / 1024.0
    val rt = Runtime.getRuntime()
    return MemorySample(
        heapUsedMb = (rt.totalMemory() - rt.freeMemory()) / MB,
        heapMaxMb = rt.maxMemory() / MB,
        pssMb = stat("summary.total-pss"),
        nativeMb = stat("summary.native-heap"),
        gfxMb = stat("summary.graphics"),
    )
}

private fun fmtBytes(bytes: Long): String =
    if (bytes < 1024 * 1024) "%.0f KB".format(bytes / 1024.0) else "%.2f MB".format(bytes / MB)

private fun fmtPercent(live: Long, total: Long): String =
    if (total <= 0) "-" else "%.0f%% used".format(live * 100.0 / total)

private const val MB = 1024.0 * 1024.0
private const val REFRESH_MS = 250L
private val PANEL_BG = Rgba(0, 0, 0, 180)
private val TEXT = Rgba(238, 238, 238)
private val TEXT_DIM = Rgba(175, 175, 175)
