package com.xnotes.platform

import android.content.Context
import android.print.PrintAttributes
import android.print.SilentPdfPrinter
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/** Prints reader HTML to an A4 PDF through the WebView's own print engine, so it keeps the reader's look with real text. */
object ReaderPrint {
    suspend fun toPdf(context: Context, html: String, landscape: Boolean, out: File): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val web = WebView(context)
            web.settings.javaScriptEnabled = false
            web.settings.allowFileAccess = false
            web.settings.allowContentAccess = false
            web.settings.blockNetworkLoads = true
            web.webViewClient = object : WebViewClient() {
                var started = false
                override fun onPageFinished(view: WebView, url: String?) {
                    if (started) return
                    started = true
                    val attributes = PrintAttributes.Builder()
                        .setMediaSize(if (landscape) PrintAttributes.MediaSize.ISO_A4.asLandscape() else PrintAttributes.MediaSize.ISO_A4)
                        .setResolution(PrintAttributes.Resolution("pdf", "pdf", 300, 300))
                        // Margins come from the page's @page rule; Chromium scales these attribute margins up about 4x.
                        .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                        .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                        .build()
                    SilentPdfPrinter.print(view.createPrintDocumentAdapter("n-notes"), attributes, out) { ok ->
                        view.destroy()
                        if (cont.isActive) cont.resume(ok && out.length() > 0)
                    }
                }
            }
            web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        }
    }
}
