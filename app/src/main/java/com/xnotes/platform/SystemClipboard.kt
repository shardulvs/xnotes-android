package com.xnotes.platform

import android.content.ClipboardManager
import android.content.Context

/**
 * What the Android clipboard is holding, in the terms this app cares about: an image.
 *
 * Both canvases paste images and both ask the same two questions, so they ask them here rather than
 * each keeping its own copy of the answer.
 */
object SystemClipboard {

    private fun manager(context: Context) = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    /** Whether the clip declares an image. Asks only the clipboard service, never the app that copied it, so a touch can afford it. */
    fun hasImage(context: Context): Boolean = manager(context)?.primaryClipDescription?.hasMimeType("image/*") == true

    /** Whether the clip declares text, asked as cheaply as [hasImage]. */
    fun hasText(context: Context): Boolean = manager(context)?.primaryClipDescription?.hasMimeType("text/*") == true

    /** SVG markup sitting on the clipboard as plain text (copied source), as insertable bytes. */
    private fun svgBytes(clip: android.content.ClipData): ByteArray? {
        val text = clip.getItemAt(0).text?.toString() ?: return null
        val t = text.trimStart('﻿').trimStart()
        val looksSvg = t.startsWith("<svg") || ((t.startsWith("<?xml") || t.startsWith("<!")) && t.contains("<svg"))
        return if (looksSvg) text.toByteArray() else null
    }

    /** Write [bmp] as a PNG to the FileProvider-exposed cache and make it the primary clip; false when that failed. */
    fun putImage(context: Context, bmp: android.graphics.Bitmap, label: String): Boolean = runCatching {
        val dir = java.io.File(context.cacheDir, "clipboard").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = java.io.File(dir, "capture.png")
        java.io.FileOutputStream(file).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newUri(context.contentResolver, label, uri))
        true
    }.getOrDefault(false)

    /** The declared image's bytes, or copied SVG source. Reads from the app that copied it, so never call it on the main thread. */
    fun imageBytes(context: Context): ByteArray? {
        val clip = manager(context)?.primaryClip?.takeIf { it.itemCount > 0 } ?: return null
        val uri = clip.getItemAt(0).uri?.takeIf { clip.description.hasMimeType("image/*") }
        if (uri != null) {
            runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
                .getOrNull()
                ?.let { return it }
        }
        return svgBytes(clip)
    }
}
