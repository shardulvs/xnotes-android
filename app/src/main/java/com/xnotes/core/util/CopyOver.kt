package com.xnotes.core.util

import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.OutputStream

/**
 * Copies [temp] over a destination that is emptied the moment it is opened, and says whether the
 * bytes went out. [temp] is opened first: when it has gone, [open] never runs and the destination
 * keeps what it holds.
 */
fun copyOver(temp: File, bufferSize: Int, open: () -> OutputStream?): Boolean {
    val source = try {
        FileInputStream(temp)
    } catch (e: FileNotFoundException) {
        return false
    }
    return source.use { input -> open()?.use { input.copyTo(it, bufferSize) } != null }
}
