package com.xnotes.platform

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.xnotes.core.util.DocumentKind
import com.xnotes.format.XnotesPdf
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.Collections
import java.util.IdentityHashMap

/**
 * The `.xnote` an xnotes PDF carries: the bundle [file], attached under [name], which is the PDF's
 * own name with the note's extension.
 *
 * It goes in as a standard file attachment, so a reader lists it and can save it out, and its bytes
 * lie in the PDF unfiltered, so xnotes reads the note straight from the file ([XnotesPdf]).
 */
class PdfNote(val file: File, val name: String) {

    /**
     * Attach the note to [doc] and leave the marker open in its trailer. Once [doc] is saved, the
     * file is finished by [seal].
     */
    fun attachTo(doc: PDDocument) {
        val stream = FileStream(file)
        val spec = COSDictionary().apply {
            setItem(COSName.TYPE, COSName.getPDFName("Filespec"))
            setItem(COSName.F, COSString(name))
            setItem(UF, COSString(name))
            setItem(EF, COSDictionary().apply { setItem(COSName.F, stream) })
            setItem(COSName.getPDFName("AFRelationship"), COSName.getPDFName("Source"))
        }
        val catalog = doc.documentCatalog.cosObject
        val names = catalog.getDictionaryObject(COSName.NAMES) as? COSDictionary
            ?: COSDictionary().also { catalog.setItem(COSName.NAMES, it) }
        names.setItem(EMBEDDED_FILES, leaf(names.getDictionaryObject(EMBEDDED_FILES) as? COSDictionary, spec))

        val cos = doc.document
        val trailer = cos.trailer
        // PdfBox writes a document it read from a cross-reference stream with a stream again, and
        // carries no key of ours into one. A table keeps the whole trailer.
        cos.setIsXRefStream(false)
        if (trailer.getDictionaryObject(COSName.TYPE) == COSName.getPDFName("XRef")) {
            for (key in STREAM_KEYS) trailer.removeItem(COSName.getPDFName(key))
        }
        trailer.setItem(
            COSName.getPDFName(XnotesPdf.KEY),
            COSDictionary().apply {
                isDirect = true
                setInt(COSName.V, XnotesPdf.VERSION)
                setItem(COSName.getPDFName("Obj"), stream)
                setItem(COSName.getPDFName("Len"), COSString("0".repeat(XnotesPdf.LENGTH_DIGITS)))
            },
        )
    }

    /**
     * Finish [pdf], the file [attachTo]'s document was saved to: stamp its length into the marker,
     * then read the note back the way opening it will. Throws when it does not come back whole,
     * which is a file that must not replace the user's.
     */
    fun seal(pdf: File) {
        RandomAccessFile(pdf, "rw").use { raf ->
            val ch = raf.channel
            if (!XnotesPdf.stamp(ch)) throw IOException("no marker in the exported PDF")
            val ours = XnotesPdf.inspect(ch) as? XnotesPdf.Inspection.Ours
            val inside = ours?.takeUnless { it.stale }?.let { XnotesPdf.embedded(ch, it) }
            if (inside == null || inside.size() != file.length()) throw IOException("the exported PDF does not hold its note")
        }
    }

    /** The attachments [old] lists, with the note in place of any of its name, as one sorted leaf. */
    private fun leaf(old: COSDictionary?, spec: COSDictionary): COSDictionary {
        val key = COSString(name)
        val pairs = entries(old).filterNot { it.first.bytes.contentEquals(key.bytes) } + (key to spec)
        return leafOf(pairs)
    }

    /** A stream whose bytes are [file]'s, read as the document is saved and never copied into PdfBox's scratch space. */
    private class FileStream(private val file: File) : COSStream() {
        init {
            setItem(COSName.TYPE, COSName.getPDFName("EmbeddedFile"))
            setLong(COSName.LENGTH, file.length())
            setItem(COSName.getPDFName("Params"), COSDictionary().apply { isDirect = true; setLong(COSName.SIZE, file.length()) })
        }

        override fun createRawInputStream(): InputStream = FileInputStream(file)
    }

    companion object {
        private val EMBEDDED_FILES = COSName.getPDFName("EmbeddedFiles")
        private val EF = COSName.getPDFName("EF")
        private val UF = COSName.getPDFName("UF")

        /** Whether an attachment named [name] is a note of ours. */
        fun isNote(name: String): Boolean = name.endsWith(DocumentKind.NOTE.suffix, ignoreCase = true)

        /**
         * Copy the note attached to [pdf] into [to]. This is for a PDF another app has written
         * since xnotes did: its bytes lie wherever, and packed however, that app left them, so
         * PdfBox reads them, a piece at a time. False when the PDF holds no note.
         */
        fun extract(pdf: File, to: File, mem: MemoryUsageSetting = MemoryUsageSetting.setupMainMemoryOnly()): Boolean =
            PDDocument.load(pdf, mem).use { doc ->
                val names = doc.documentCatalog.cosObject.getDictionaryObject(COSName.NAMES) as? COSDictionary
                val spec = entries(names?.getDictionaryObject(EMBEDDED_FILES) as? COSDictionary)
                    .lastOrNull { isNote(it.first.string) }?.second?.let { (it as? COSObject)?.`object` ?: it } as? COSDictionary
                val files = spec?.getDictionaryObject(EF) as? COSDictionary
                val stream = (files?.getDictionaryObject(COSName.F) ?: files?.getDictionaryObject(UF)) as? COSStream ?: return false
                stream.createInputStream().use { input -> FileOutputStream(to).use { input.copyTo(it, 1 shl 16) } }
                true
            }

        /**
         * Save [pdf] into [to] without our note and marker: a plain PDF, its pages as they are.
         * A PDF only becomes a note's `source.pdf`, or is kept as another app left it, through
         * here, or the old note would ride along inside it and mark it as changed ever after.
         */
        fun strip(pdf: File, to: File, mem: MemoryUsageSetting = MemoryUsageSetting.setupMainMemoryOnly()) {
            PDDocument.load(pdf, mem).use { doc ->
                if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
                val catalog = doc.documentCatalog.cosObject
                val names = catalog.getDictionaryObject(COSName.NAMES) as? COSDictionary
                val tree = names?.getDictionaryObject(EMBEDDED_FILES) as? COSDictionary
                if (names != null && tree != null) {
                    val rest = entries(tree).filterNot { isNote(it.first.string) }
                    if (rest.isNotEmpty()) {
                        names.setItem(EMBEDDED_FILES, leafOf(rest))
                    } else {
                        names.removeItem(EMBEDDED_FILES)
                        if (names.size() == 0) catalog.removeItem(COSName.NAMES)
                    }
                }
                doc.document.trailer.removeItem(COSName.getPDFName(XnotesPdf.KEY))
                BufferedOutputStream(FileOutputStream(to), 1 shl 16).use { doc.save(it) }
            }
        }

        /** Every attachment the name tree under [root] lists, by name. */
        private fun entries(root: COSDictionary?): List<Pair<COSString, COSBase>> {
            val out = ArrayList<Pair<COSString, COSBase>>()
            if (root != null) collect(root, out, Collections.newSetFromMap(IdentityHashMap()))
            return out
        }

        private fun collect(node: COSDictionary, into: MutableList<Pair<COSString, COSBase>>, seen: MutableSet<COSDictionary>) {
            if (seen.size >= MAX_NODES || !seen.add(node)) return
            (node.getDictionaryObject(COSName.NAMES) as? COSArray)?.let { pairs ->
                for (i in 0 until pairs.size() - 1 step 2) {
                    val key = pairs.getObject(i) as? COSString ?: continue
                    into.add(key to pairs.get(i + 1))
                }
            }
            (node.getDictionaryObject(COSName.KIDS) as? COSArray)?.let { kids ->
                for (i in 0 until kids.size()) (kids.getObject(i) as? COSDictionary)?.let { collect(it, into, seen) }
            }
        }

        /** [pairs] as one leaf of a name tree, which is ordered by its keys' bytes. */
        private fun leafOf(pairs: List<Pair<COSString, COSBase>>): COSDictionary {
            val array = COSArray()
            for ((k, v) in pairs.sortedWith { a, b -> compare(a.first.bytes, b.first.bytes) }) {
                array.add(k)
                array.add(v)
            }
            return COSDictionary().apply { setItem(COSName.NAMES, array) }
        }

        private fun compare(a: ByteArray, b: ByteArray): Int {
            for (i in 0 until minOf(a.size, b.size)) {
                val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
                if (d != 0) return d
            }
            return a.size - b.size
        }

        /** What a cross-reference stream's own dictionary leaves in the trailer PdfBox read from it. */
        private val STREAM_KEYS = listOf("Type", "W", "Index", "Filter", "DecodeParms", "Length")

        private const val MAX_NODES = 4096
    }
}
