package com.xnotes.platform

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.DragEvent
import android.view.View
import androidx.core.text.HtmlCompat
import com.xnotes.core.geometry.Pt
import com.xnotes.core.importing.DroppedContent
import com.xnotes.core.model.ImageData
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Receives global Android drags without changing the clipboard or the active drawing tool. */
object ExternalDrop {
    class Target(val imageDir: File, val insert: suspend (List<DroppedContent>) -> Boolean)

    class Receiver(val handle: (Int, DragEvent, Pt) -> Boolean)

    fun create(
        view: View, scope: CoroutineScope, permissionContext: Context,
        prepare: (Pt) -> Target?, onFailure: () -> Unit,
        indicatorColor: () -> Int,
    ): Receiver {
        val debug = view.context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        val marker = DropMarker(view.resources.displayMetrics.density, indicatorColor)
        var showing = false
        fun hide() {
            if (showing) view.overlay.remove(marker)
            showing = false
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) = hide()
        })
        return Receiver { action, event, at ->
            if (debug && action != DragEvent.ACTION_DRAG_LOCATION) {
                android.util.Log.d("XnotesDrop", "action=$action types=${event.clipDescription?.filterMimeTypes("*/*")?.joinToString()}")
            }
            when (action) {
                DragEvent.ACTION_DRAG_STARTED -> accepts(event.clipDescription)
                DragEvent.ACTION_DRAG_ENTERED, DragEvent.ACTION_DRAG_LOCATION -> {
                    if (prepare(at) != null) {
                        marker.setBounds(0, 0, view.width, view.height)
                        marker.at = at
                        if (!showing) view.overlay.add(marker)
                        showing = true
                        marker.invalidateSelf()
                    } else hide()
                    true
                }
                DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> { hide(); true }
                DragEvent.ACTION_DROP -> {
                    hide()
                    val target = prepare(at)
                    val clip = event.clipData
                    if (target == null || clip == null) false else {
                        // Ask while handling ACTION_DROP, before any asynchronous URI reads.
                        val activity = activity(permissionContext) ?: activity(view.rootView.context)
                        val permissions = runCatching { activity?.requestDragAndDropPermissions(event) }.getOrNull()
                        // Start before returning, so cancellation still runs the cleanup below.
                        scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            val content = mutableListOf<DroppedContent>()
                            var inserted = false
                            try {
                                withContext(Dispatchers.IO) { read(view.context, clip, target.imageDir, content) }
                                if (content.isNotEmpty()) inserted = target.insert(content)
                                if (debug) android.util.Log.d("XnotesDrop", "items=${content.size} inserted=$inserted")
                                if (content.isEmpty()) onFailure()
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                onFailure()
                            } finally {
                                permissions?.release()
                                if (!inserted) content.filterIsInstance<DroppedContent.Image>().forEach { it.data.file.delete() }
                            }
                        }
                        true
                    }
                }
                else -> false
            }
        }
    }

    private fun activity(context: Context): Activity? = when (context) {
        is Activity -> context
        is ContextWrapper -> if (context.baseContext !== context) activity(context.baseContext) else null
        else -> null
    }

    fun accepts(description: ClipDescription?): Boolean = description != null &&
        (description.hasMimeType("text/plain") || description.hasMimeType("text/html") ||
            description.hasMimeType("text/uri-list") || description.hasMimeType("image/*"))

    private fun read(context: Context, clip: ClipData, dir: File, into: MutableList<DroppedContent>) {
        val resolver = context.contentResolver
        for (index in 0 until minOf(clip.itemCount, MAX_ITEMS)) {
            val item = clip.getItemAt(index)
            val uri = item.uri
            val type = uri?.let { runCatching { resolver.getType(it) }.getOrNull() }
            if (uri?.scheme == "content" && (type?.startsWith("image/") == true ||
                    (type == null && clip.description.hasMimeType("image/*")))) {
                var file: File? = null
                try {
                    val copied = File.createTempFile("drop", null, dir)
                    file = copied
                    resolver.openInputStream(uri)?.use { input ->
                        copied.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var total = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= MAX_IMAGE_BYTES) { "Dropped image is too large" }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    val size = AndroidImageCodec().probeFile(copied.path)
                    if (size != null && size.width > 0 && size.height > 0) {
                        into += DroppedContent.Image(ImageData(copied, size.width, size.height))
                        file = null // ownership passes to the caller
                        continue
                    }
                } catch (_: Exception) {
                    // Another item, or the source's accompanying text, can still be inserted.
                } finally {
                    file?.delete()
                }
            }
            val text = item.text?.toString() ?: item.htmlText?.let {
                HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
            } ?: when {
                uri?.scheme == "http" || uri?.scheme == "https" -> uri.toString()
                uri?.scheme == "content" && type == "text/plain" -> runCatching {
                    resolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                        val chars = CharArray(MAX_TEXT_CHARS)
                        val result = StringBuilder()
                        while (result.length < MAX_TEXT_CHARS) {
                            val n = reader.read(chars, 0, minOf(chars.size, MAX_TEXT_CHARS - result.length))
                            if (n < 0) break
                            result.append(chars, 0, n)
                        }
                        result.toString()
                    }
                }.getOrNull()
                else -> null
            }
            if (!text.isNullOrBlank()) into += DroppedContent.Text(text.take(MAX_TEXT_CHARS))
        }
    }

    private const val MAX_ITEMS = 32
    private const val MAX_IMAGE_BYTES = 32L * 1024 * 1024
    private const val MAX_TEXT_CHARS = 1_000_000

    internal class DropMarker(density: Float, private val indicatorColor: () -> Int) : Drawable() {
        var at = Pt.ZERO
        private val radius = 14f * density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
        }
        override fun draw(canvas: Canvas) {
            paint.color = indicatorColor()
            val x = at.x.toFloat()
            val y = at.y.toFloat()
            canvas.drawCircle(x, y, radius, paint)
            canvas.drawLine(x - radius / 2, y, x + radius / 2, y, paint)
            canvas.drawLine(x, y - radius / 2, x, y + radius / 2, paint)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
