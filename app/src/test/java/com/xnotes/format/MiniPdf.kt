package com.xnotes.format

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/** PDFs written by hand for the tests, laid out the way PdfBox lays out what it saves. */
internal object MiniPdf {

    class Writer {
        val out = ByteArrayOutputStream()
        val at = HashMap<Int, Int>()

        fun put(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))

        fun obj(n: Int, body: String) {
            at[n] = out.size()
            put("$n 0 obj\n$body\nendobj\n")
        }

        fun stream(n: Int, dict: String, data: ByteArray) {
            at[n] = out.size()
            put("$n 0 obj\n<<\n/Length ${data.size}\n$dict>>\nstream\r\n")
            out.write(data)
            put("\r\nendstream\nendobj\n")
        }

        /** A table of objects 0 until [size], PdfBox's way: one subsection, entries ending in CR LF. */
        fun table(size: Int): Int {
            val xref = out.size()
            put("xref\n0 $size\n0000000000 65535 f\r\n")
            for (n in 1 until size) put("%010d 00000 n\r\n".format(at.getValue(n)))
            return xref
        }

        fun end(xref: Int) = put("startxref\n$xref\n%%EOF\n")
    }

    private const val OPEN_MARKER = "/XNotes <<\n/V 1\n/Obj 7 0 R\n/Len (0000000000000)\n>>\n"

    /** A page and the attachment, as far as the last object. */
    fun body(note: ByteArray, extraObjects: Int = 0): Writer = Writer().apply {
        put("%PDF-1.4\n%öäüß\n")
        obj(1, "<<\n/Type /Catalog\n/Pages 2 0 R\n/Names 4 0 R\n>>")
        obj(2, "<<\n/Type /Pages\n/Kids [3 0 R]\n/Count 1\n>>")
        obj(3, "<<\n/Type /Page\n/Parent 2 0 R\n/MediaBox [0.0 0.0 595.27563 841.8898]\n>>")
        obj(4, "<<\n/EmbeddedFiles 5 0 R\n>>")
        obj(5, "<<\n/Names [(note.xnote) 6 0 R]\n>>")
        obj(6, "<<\n/Type /Filespec\n/F (note.xnote)\n/UF (note.xnote)\n/EF <<\n/F 7 0 R\n>>\n/AFRelationship /Source\n>>")
        stream(7, "/Type /EmbeddedFile\n", note)
        repeat(extraObjects) { obj(8 + it, "<<\n/Filler $it\n>>") }
    }

    /** An export as PdfBox saves one: a table, and the marker in the trailer still to be stamped. */
    fun exported(note: ByteArray, marker: Boolean = true, extraObjects: Int = 0, encrypt: Boolean = false): ByteArray {
        val w = body(note, extraObjects)
        val size = 8 + extraObjects
        val xref = w.table(size)
        w.put("trailer\n<<\n/Root 1 0 R\n/ID [<554A400BB9E303F3409BC3B2BE8837B9> <9F09EBAA9D7E05D849D824E5AEA53B0B>]\n/Size $size\n")
        if (encrypt) w.put("/Encrypt 2 0 R\n")
        if (marker) w.put(OPEN_MARKER)
        w.put(">>\n")
        w.end(xref)
        return w.out.toByteArray()
    }

    /** A PDF that ends in a cross-reference stream with its rows deflated, as PdfBox leaves one it loaded that way. */
    fun endingInAStream(note: ByteArray): ByteArray {
        val w = body(note)
        val rows = ByteArrayOutputStream()
        rows.write(byteArrayOf(0, 0, 0))
        for (n in 1..7) {
            val at = w.at.getValue(n)
            rows.write(byteArrayOf(1, (at ushr 8).toByte(), at.toByte()))
        }
        val packed = ByteArrayOutputStream()
        val deflater = Deflater()
        deflater.setInput(rows.toByteArray())
        deflater.finish()
        val buf = ByteArray(256)
        while (!deflater.finished()) packed.write(buf, 0, deflater.deflate(buf))
        val xref = w.out.size()
        w.put("8 0 obj\n<<\n/Length ${packed.size()}\n/Root 1 0 R\n/ID [<554A400BB9E303F3409BC3B2BE8837B9> <95ED4FB1DF76AD0EDC5D53C19293E748>]\n")
        w.put("/Type /XRef\n/Size 9\n/Index [0 8]\n/W [1 2 0]\n/Filter /FlateDecode\n>>\nstream\r\n")
        w.out.write(packed.toByteArray())
        w.put("\r\nendstream\nendobj\n")
        w.end(xref)
        return w.out.toByteArray()
    }
}
