package android.print

import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import java.io.File

/** Runs a [PrintDocumentAdapter] straight into a PDF file with no print dialog. In this package for the callbacks' constructors. */
object SilentPdfPrinter {
    fun print(adapter: PrintDocumentAdapter, attributes: PrintAttributes, out: File, onDone: (Boolean) -> Unit) {
        fun finish(ok: Boolean) { runCatching { adapter.onFinish() }; onDone(ok) }
        adapter.onStart()
        adapter.onLayout(null, attributes, CancellationSignal(), object : PrintDocumentAdapter.LayoutResultCallback() {
            override fun onLayoutFinished(info: PrintDocumentInfo, changed: Boolean) {
                val pfd = runCatching {
                    ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE)
                }.getOrNull() ?: return finish(false)
                adapter.onWrite(arrayOf(PageRange.ALL_PAGES), pfd, CancellationSignal(), object : PrintDocumentAdapter.WriteResultCallback() {
                    override fun onWriteFinished(pages: Array<out PageRange>) { runCatching { pfd.close() }; finish(true) }
                    override fun onWriteFailed(error: CharSequence?) { runCatching { pfd.close() }; finish(false) }
                    override fun onWriteCancelled() { runCatching { pfd.close() }; finish(false) }
                })
            }
            override fun onLayoutFailed(error: CharSequence?) = finish(false)
            override fun onLayoutCancelled() = finish(false)
        }, null)
    }
}
