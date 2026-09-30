package com.xnotes.platform

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File

/** Joins two PDFs into one file, for putting one note's PDF pages into another's. IO. */
object PdfMerge {

    private const val SCRATCH_MEM_BYTES = 16L * 1024 * 1024

    /** Write [first] then [second] into [out]; returns how many pages [first] had, or null on failure. */
    fun merge(context: Context, first: File, second: File, out: File): Int? = runCatching {
        PDFBoxResourceLoader.init(context.applicationContext)
        val mem = MemoryUsageSetting.setupMixed(SCRATCH_MEM_BYTES).setTempDir(context.cacheDir)
        val count = PDDocument.load(first, mem).use { it.numberOfPages }
        PDFMergerUtility().apply {
            addSource(first)
            addSource(second)
            destinationFileName = out.absolutePath
        }.mergeDocuments(mem)
        count
    }.getOrElse {
        out.delete()
        null
    }
}
