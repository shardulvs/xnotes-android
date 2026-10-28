package com.xnotes.platform

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri

/**
 * What the Android clipboard is holding, in the terms this app cares about: an image.
 *
 * Both canvases paste images and both ask the same two questions, so they ask them here rather than
 * each keeping its own copy of the answer.
 */
object SystemClipboard {

    /** An image the clipboard is holding by uri, or null when it holds something else. */
    fun imageUri(context: Context): Uri? {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val uri = clip.getItemAt(0).uri ?: return null
        val type = context.contentResolver.getType(uri)
        val isImage = type?.startsWith("image/") == true || clip.description?.hasMimeType("image/*") == true
        return if (isImage) uri else null
    }

    /** SVG markup sitting on the clipboard as plain text (copied source), as insertable bytes. */
    fun svgBytes(context: Context): ByteArray? {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString() ?: return null
        val t = text.trimStart('﻿').trimStart()
        val looksSvg = t.startsWith("<svg") || ((t.startsWith("<?xml") || t.startsWith("<!")) && t.contains("<svg"))
        return if (looksSvg) text.toByteArray() else null
    }

    /** Whether there is anything here worth offering a paste for. */
    fun hasImage(context: Context): Boolean = imageUri(context) != null || svgBytes(context) != null

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

    /** The clipboard's image as bytes, reading the uri or falling back to copied SVG source. */
    fun imageBytes(context: Context): ByteArray? {
        imageUri(context)?.let { uri ->
            runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
                .getOrNull()
                ?.let { return it }
        }
        return svgBytes(context)
    }
}
