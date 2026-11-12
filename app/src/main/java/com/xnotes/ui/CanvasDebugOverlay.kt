package com.xnotes.ui

import android.os.Debug
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.xnotes.canvas.DebugOverlay
import com.xnotes.gl.GlStats
import com.xnotes.platform.AndroidRenderer
import kotlinx.coroutines.delay

/**
 * The GL canvas's debug HUD, toggled by a four-finger tap and drawn with the paged HUD's panel.
 * Composed over the surface rather than drawn in the GL frame, so it cannot perturb what it measures.
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

    val lines = buildList {
        addAll(editor.interaction.steadyReadout.lines())
        add("")
        add("%.0f fps   %.1f ms".format(stats.fps, stats.frameMs))
        add("visible    ${stats.visibleItems} / ${stats.items}")
        add("draws      ${stats.drawCalls}")
        add("geometry   %.0f / %.0f MB".format(stats.liveGeometryBytes / MB, stats.geometryBytes / MB))
        add("textures   ${stats.textures}  %.0f MB".format(stats.textureBytes / MB))
        add("heap  %.0f / %.0f MB".format(memory.heapUsedMb, memory.heapMaxMb))
        add("pss    %.0f MB".format(memory.pssMb))
        add("native %.0f MB".format(memory.nativeMb))
        add("gfx    %.0f MB".format(memory.gfxMb))
        add("front     ${editor.pad.hud}")
        if (editor.view.msaaSamples < 2) add("msaa       none")
        editor.renderFailure?.let { add("GL FAILED $it") }
        if (editor.outOfMemory) add("GEOMETRY OUT OF MEMORY")
    }

    Spacer(
        Modifier.fillMaxSize().drawBehind {
            drawIntoCanvas { DebugOverlay.drawPanel(AndroidRenderer(it.nativeCanvas), lines, size.width.toDouble()) }
        },
    )
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

private const val MB = 1024.0 * 1024.0
private const val REFRESH_MS = 250L
