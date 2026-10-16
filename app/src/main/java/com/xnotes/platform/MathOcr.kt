package com.xnotes.platform

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Stroke
import com.xnotes.core.text.RecognizedLatex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.security.MessageDigest

/**
 * Handwritten maths to LaTeX, fully on device, as an optional add-on. The model
 * (pix2text-mfr 1.5, MIT) is about 120 MB and ONNX Runtime's core library another
 * 25-40 MB per CPU type, so neither is bundled: Preferences downloads both into
 * app storage and can delete them again. The APK carries only ONNX Runtime's small
 * JNI bridge, whose dependency on libonnxruntime.so is met by loading the
 * downloaded copy first. Recognition renders the selected strokes to an image and
 * runs the model's encoder once, then its decoder greedily, one token at a time.
 */
object MathOcr {

    sealed interface State {
        data object Missing : State
        data class Downloading(val done: Long, val total: Long) : State
        data object Installed : State
        data class Failed(val message: String) : State
    }

    /**
     * One download: fetched from [url] ([size] bytes, [sha256] of what is fetched) and saved as
     * [name], gunzipped on the way when [installedSize] differs from [size].
     */
    private class AddonFile(val name: String, val url: String, val size: Long, val sha256: String, val installedSize: Long = size)

    /** The math-addon pre-release in this app's repo, mirroring pix2text-mfr 1.5 (Hugging Face revision 1cef9f0) and ONNX Runtime 1.30.0. */
    private const val ADDON = "https://github.com/NeDDy3z/n-notes/releases/download/math-addon/"
    private const val RUNTIME_LIB = "libonnxruntime.so"

    private val MODEL_FILES = listOf(
        AddonFile("encoder_model.onnx", ADDON + "encoder_model.onnx", 87_510_770, "080a3f660f08bc9ebcacdd96e34be6b6400f8c7e62d7cd0dd8251badc37f610b"),
        AddonFile("decoder_model.onnx", ADDON + "decoder_model.onnx", 32_026_253, "917deb98e91a0453c5f234f58a0f32f9fb037de8527c7eb4ed394daf9e692f2a"),
        AddonFile("tokenizer.json", ADDON + "tokenizer.json", 113_168, "4ffbeb2143e6a38324bb6111b7a8109530d38a076a8439aa5777535f0a32758a"),
    )

    /** ONNX Runtime 1.30.0's core library, matching the bridge in the APK, per CPU type. */
    private val RUNTIME_FILES = mapOf(
        "arm64-v8a" to AddonFile(RUNTIME_LIB, ADDON + "libonnxruntime-1.30.0-arm64-v8a.so.gz", 12_381_936, "e5eefba49721afb5b17d55f7d8eb16ec0f64c384c6ff08d49950393fcf086f24", 32_990_480),
        "armeabi-v7a" to AddonFile(RUNTIME_LIB, ADDON + "libonnxruntime-1.30.0-armeabi-v7a.so.gz", 11_179_985, "081ce89dd67b4c5e664e4a24695b3826e43c15c91d55ea817714998394a89c85", 23_311_344),
        "x86_64" to AddonFile(RUNTIME_LIB, ADDON + "libonnxruntime-1.30.0-x86_64.so.gz", 14_373_545, "104769accac47a9459de4355c693048b6e80408a21449fc892c874b5054cba62", 39_348_488),
    )

    /** The runtime build this process can load: its own bitness, the device's preferred CPU type. */
    private val runtimeFile: AddonFile? by lazy {
        val abis = if (android.os.Process.is64Bit()) android.os.Build.SUPPORTED_64_BIT_ABIS else android.os.Build.SUPPORTED_32_BIT_ABIS
        abis.firstNotNullOfOrNull { RUNTIME_FILES[it] }
    }

    private val files: List<AddonFile> get() = MODEL_FILES + listOfNotNull(runtimeFile)

    /** False on a CPU type with no runtime build; Preferences then says so instead of offering it. */
    val supported: Boolean get() = runtimeFile != null

    val DOWNLOAD_BYTES: Long get() = files.sumOf { it.size }

    @Volatile private var runtimeLoaded = false

    private const val INPUT = 384
    private const val BOS = 1L
    private const val EOS = 2
    private const val MAX_TOKENS = 256

    private val _state = MutableStateFlow<State>(State.Missing)
    val state: StateFlow<State> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var download: Job? = null

    private fun dir(context: Context) = File(context.filesDir, "addons/math-ocr")

    fun isInstalled(context: Context): Boolean = supported && files.all { File(dir(context), it.name).length() == it.installedSize }

    /** Sync [state] with what is on disk; a download in flight is left to report itself. */
    fun refresh(context: Context) {
        if (_state.value is State.Downloading) return
        _state.value = if (isInstalled(context)) State.Installed else State.Missing
    }

    fun startDownload(context: Context) {
        if (download?.isActive == true) return
        val app = context.applicationContext
        download = scope.launch {
            val target = dir(app).apply { mkdirs() }
            var done = 0L
            try {
                for (f in files) {
                    val out = File(target, f.name)
                    if (out.length() == f.installedSize) { done += f.size; continue }
                    val part = File(target, f.name + ".part")
                    val digest = MessageDigest.getInstance("SHA-256")
                    val conn = URL(f.url).openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = true
                    conn.connectTimeout = 20_000
                    conn.readTimeout = 30_000
                    try {
                        if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
                        val hashed = java.security.DigestInputStream(conn.inputStream, digest)
                        val counted = object : java.io.FilterInputStream(hashed) {
                            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { n ->
                                if (n > 0) {
                                    done += n
                                    _state.value = State.Downloading(done, DOWNLOAD_BYTES)
                                }
                            }
                        }
                        val input = if (f.installedSize != f.size) java.util.zip.GZIPInputStream(counted, 64 * 1024) else counted
                        input.use { src ->
                            part.outputStream().use { dst ->
                                val buf = ByteArray(64 * 1024)
                                while (isActive) {
                                    val n = src.read(buf)
                                    if (n < 0) break
                                    dst.write(buf, 0, n)
                                }
                                // Whatever gunzip left unread still counts toward the checksum.
                                while (isActive && counted.read(buf) >= 0) Unit
                            }
                        }
                    } finally {
                        conn.disconnect()
                    }
                    if (!isActive) { part.delete(); return@launch }
                    val sha = digest.digest().joinToString("") { "%02x".format(it) }
                    if (sha != f.sha256 || part.length() != f.installedSize) {
                        part.delete()
                        error("${f.name} is corrupt, try again")
                    }
                    part.renameTo(out)
                }
                _state.value = State.Installed
            } catch (e: Exception) {
                _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun cancelDownload(context: Context) {
        download?.cancel()
        download = null
        dir(context).listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
        _state.value = if (isInstalled(context)) State.Installed else State.Missing
    }

    fun delete(context: Context) {
        cancelDownload(context)
        dir(context).deleteRecursively()
        _state.value = State.Missing
    }

    /**
     * Recognize [strokes] (content px, inside [bounds]) as LaTeX, or "" when nothing came out.
     * Loads the model for this call and frees it after, so its ~200 MB never idles in memory.
     * Blocks; call off the main thread.
     */
    fun recognize(context: Context, strokes: List<Stroke>, bounds: Rect): String {
        val d = dir(context)
        if (!runtimeLoaded) {
            System.load(File(d, RUNTIME_LIB).path)
            runtimeLoaded = true
        }
        val vocab = loadVocab(File(d, "tokenizer.json"))
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
            // KleidiAI's SME kernels trap (SIGILL) where a CPU reports SME it cannot run, as
            // emulators on SME hosts do; the plain kernels are plenty for a model this small.
            addConfigEntry("mlas.disable_kleidiai", "1")
        }
        val pixels = render(strokes, bounds)
        val ids = ArrayList<Long>().apply { add(BOS) }
        env.createSession(File(d, "encoder_model.onnx").path, opts).use { enc ->
            env.createSession(File(d, "decoder_model.onnx").path, opts).use { dec ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(pixels), longArrayOf(1, 3, INPUT.toLong(), INPUT.toLong())).use { image ->
                    enc.run(mapOf("pixel_values" to image)).use { encoded ->
                        val hidden = encoded[0] as OnnxTensor
                        while (ids.size < MAX_TOKENS) {
                            val next = OnnxTensor.createTensor(env, LongBuffer.wrap(ids.toLongArray()), longArrayOf(1, ids.size.toLong())).use { input ->
                                dec.run(mapOf("input_ids" to input, "encoder_hidden_states" to hidden)).use { out ->
                                    lastArgmax(out[0] as OnnxTensor, ids.size)
                                }
                            }
                            if (next == EOS) break
                            ids.add(next.toLong())
                        }
                    }
                }
            }
        }
        return RecognizedLatex.clean(decode(ids, vocab))
    }

    private fun lastArgmax(logits: OnnxTensor, steps: Int): Int {
        val buf = logits.floatBuffer
        val vocab = (logits.info.shape[2]).toInt()
        val base = (steps - 1) * vocab
        var best = 0
        var bestV = Float.NEGATIVE_INFINITY
        for (k in 0 until vocab) {
            val v = buf.get(base + k)
            if (v > bestV) { bestV = v; best = k }
        }
        return best
    }

    /**
     * The strokes drawn black on white at a scale the model reads best (glyphs roughly
     * 100 to 200 px tall, 4 px lines), then squeezed to the 384 px square it takes,
     * as a normalized CHW float image.
     */
    private fun render(strokes: List<Stroke>, bounds: Rect): FloatArray {
        val scale = minOf(160.0 / maxOf(bounds.h, bounds.w / 10.0, 1.0), 2400.0 / maxOf(bounds.w, 1.0))
        val pad = 24
        val w = (bounds.w * scale).toInt() + 2 * pad
        val h = (bounds.h * scale).toInt() + 2 * pad
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 4f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        for (s in strokes) {
            val n = s.sampleCount
            if (n == 0) continue
            fun x(i: Int) = ((s.xAt(i) - bounds.left) * scale + pad).toFloat()
            fun y(i: Int) = ((s.yAt(i) - bounds.top) * scale + pad).toFloat()
            if (n == 1) {
                canvas.drawPoint(x(0), y(0), paint)
                continue
            }
            val path = Path().apply { moveTo(x(0), y(0)) }
            for (i in 1 until n) path.lineTo(x(i), y(i))
            canvas.drawPath(path, paint)
        }
        val sized = Bitmap.createScaledBitmap(bmp, INPUT, INPUT, true)
        bmp.recycle()
        val px = IntArray(INPUT * INPUT)
        sized.getPixels(px, 0, INPUT, 0, 0, INPUT, INPUT)
        sized.recycle()
        val plane = INPUT * INPUT
        val out = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val c = px[i]
            out[i] = ((c shr 16 and 0xFF) / 255f - 0.5f) / 0.5f
            out[plane + i] = ((c shr 8 and 0xFF) / 255f - 0.5f) / 0.5f
            out[2 * plane + i] = ((c and 0xFF) / 255f - 0.5f) / 0.5f
        }
        return out
    }

    private fun loadVocab(file: File): Map<Int, String> {
        val root = JSONObject(file.readText())
        val out = HashMap<Int, String>()
        val vocab = root.getJSONObject("model").getJSONObject("vocab")
        for (k in vocab.keys()) out[vocab.getInt(k)] = k
        return out
    }

    /** Byte-level BPE: each token character stands for one byte of UTF-8 (GPT-2's table). */
    private fun decode(ids: List<Long>, vocab: Map<Int, String>): String {
        val bytes = java.io.ByteArrayOutputStream()
        for (id in ids) {
            if (id <= 4) continue
            val token = vocab[id.toInt()] ?: continue
            for (ch in token) BYTE_OF[ch]?.let { bytes.write(it) }
        }
        return bytes.toString("UTF-8").trim()
    }

    private val BYTE_OF: Map<Char, Int> by lazy {
        val bs = ArrayList<Int>()
        bs.addAll('!'.code..'~'.code)
        bs.addAll(0xA1..0xAC)
        bs.addAll(0xAE..0xFF)
        val cs = ArrayList(bs)
        var n = 0
        for (b in 0 until 256) {
            if (b !in bs) {
                bs.add(b)
                cs.add(256 + n)
                n++
            }
        }
        bs.indices.associate { cs[it].toChar() to bs[it] }
    }
}
