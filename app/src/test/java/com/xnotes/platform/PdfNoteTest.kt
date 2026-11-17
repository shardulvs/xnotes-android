package com.xnotes.platform

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.xnotes.format.FileWindow
import com.xnotes.format.MiniPdf
import com.xnotes.format.XnotesPdf
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * The note going into a PDF through PdfBox and coming back out through the byte layer, which is
 * the round trip every xnotes PDF has to survive to reopen.
 */
class PdfNoteTest {

    private val latin = Charsets.ISO_8859_1
    private val embeddedFiles = COSName.getPDFName("EmbeddedFiles")

    private fun temp(suffix: String, bytes: ByteArray = ByteArray(0)): File =
        File.createTempFile("xnotes", suffix).apply { writeBytes(bytes); deleteOnExit() }

    /** Bytes that read like the inside of a PDF, as a stored `source.pdf` in the note does. */
    private fun bytes(tag: String, size: Int = 3000): ByteArray {
        val seed = "PK $tag 1 0 obj << /Length 9 >> stream\nendstream endobj xref trailer startxref 9 %%EOF\n".toByteArray(latin)
        return ByteArray(size) { seed[it % seed.size] }
    }

    /** [doc] saved with [note] inside, as an export does it. */
    private fun saved(doc: PDDocument, note: PdfNote): File {
        val out = temp(".pdf")
        note.attachTo(doc)
        FileOutputStream(out).use { doc.save(it) }
        note.seal(out)
        return out
    }

    private fun readAll(w: FileWindow): ByteArray {
        val buf = ByteBuffer.allocate(w.size().toInt())
        while (buf.hasRemaining()) if (w.read(buf, buf.position().toLong()) < 0) break
        return buf.array()
    }

    /** The note as xnotes reads it: straight from the file's bytes. */
    private fun noteIn(pdf: File): ByteArray = RandomAccessFile(pdf, "r").use { raf ->
        val ours = XnotesPdf.inspect(raf.channel) as XnotesPdf.Inspection.Ours
        assertFalse(ours.stale)
        readAll(XnotesPdf.embedded(raf.channel, ours)!!)
    }

    private fun leaf(doc: PDDocument): COSArray {
        val names = doc.documentCatalog.cosObject.getDictionaryObject(COSName.NAMES) as COSDictionary
        return (names.getDictionaryObject(embeddedFiles) as COSDictionary).getDictionaryObject(COSName.NAMES) as COSArray
    }

    /** The attachments a reader lists, in the order the file names them. */
    private fun attachments(pdf: File): List<String> = PDDocument.load(pdf).use { doc ->
        val leaf = leaf(doc)
        (0 until leaf.size() step 2).map { (leaf.getObject(it) as COSString).string }
    }

    /** The bytes of the attachment [name], read the way any PDF reader gets at them. */
    private fun attachment(pdf: File, name: String): ByteArray = PDDocument.load(pdf).use { doc ->
        val leaf = leaf(doc)
        val at = (0 until leaf.size() step 2).first { (leaf.getObject(it) as COSString).string == name }
        val spec = leaf.getObject(at + 1) as COSDictionary
        assertEquals("Source", spec.getNameAsString(COSName.getPDFName("AFRelationship")))
        val stream = (spec.getDictionaryObject(COSName.getPDFName("EF")) as COSDictionary).getDictionaryObject(COSName.F) as COSStream
        stream.createInputStream().use { it.readBytes() }
    }

    private fun trailerOf(pdf: File): String = String(pdf.readBytes(), latin).let { it.substring(it.lastIndexOf("trailer")) }

    @Test fun aSavedDocumentCarriesItsNote() {
        val content = bytes("first")
        val pdf = PDDocument().use { doc ->
            doc.addPage(PDPage())
            saved(doc, PdfNote(temp(".xnote", content), "Lecture 3.xnote"))
        }

        assertArrayEquals(content, noteIn(pdf))
        assertEquals(listOf("Lecture 3.xnote"), attachments(pdf))
        assertArrayEquals(content, attachment(pdf, "Lecture 3.xnote"))
        assertTrue(trailerOf(pdf).contains("/Len (%013d)".format(pdf.length())))
    }

    @Test fun aSourceIndexedByAStreamIsSavedWithATable() {
        // What an export's fast path does: load the PDF the note annotates, and save that document.
        val source = temp(".pdf", MiniPdf.endingInAStream(bytes("older")))
        val content = bytes("newer", 5000)

        val pdf = PDDocument.load(source).use { doc ->
            assertTrue(doc.document.isXRefStream)
            saved(doc, PdfNote(temp(".xnote", content), "note.xnote"))
        }

        assertArrayEquals(content, noteIn(pdf))
        assertFalse(String(pdf.readBytes(), latin).contains("/XRef"))
        // Nothing of the stream's own dictionary is left behind in the trailer.
        for (key in listOf("/W ", "/Index", "/Filter", "/DecodeParms", "/Length", "/Type")) assertFalse(key, trailerOf(pdf).contains(key))
        // The source had an attachment of that name already; the note takes its place.
        assertEquals(listOf("note.xnote"), attachments(pdf))
        assertArrayEquals(content, attachment(pdf, "note.xnote"))
    }

    @Test fun attachmentsTheSourceCameWithStay() {
        val pdf = PDDocument().use { doc ->
            doc.addPage(PDPage())
            // A name tree in two leaves under a root, the shape a larger tree has.
            fun spec(text: String) = COSDictionary().apply {
                setItem(COSName.TYPE, COSName.getPDFName("Filespec"))
                val stream = doc.document.createCOSStream()
                stream.createOutputStream().use { it.write(text.toByteArray()) }
                setItem(COSName.getPDFName("EF"), COSDictionary().apply { setItem(COSName.F, stream) })
            }
            fun kid(name: String) = COSDictionary().apply {
                setItem(COSName.NAMES, COSArray().apply { add(COSString(name)); add(spec(name)) })
                setItem(COSName.getPDFName("Limits"), COSArray().apply { add(COSString(name)); add(COSString(name)) })
            }
            val root = COSDictionary().apply { setItem(COSName.KIDS, COSArray().apply { add(kid("b.txt")); add(kid("z.txt")) }) }
            doc.documentCatalog.cosObject.setItem(COSName.NAMES, COSDictionary().apply { setItem(embeddedFiles, root) })
            saved(doc, PdfNote(temp(".xnote", bytes("mine")), "m.xnote"))
        }

        assertEquals(listOf("b.txt", "m.xnote", "z.txt"), attachments(pdf))
        assertArrayEquals(bytes("mine"), noteIn(pdf))
    }

    @Test fun aNameOutsideLatinSurvives() {
        val name = "Лекция 3.xnote"
        val pdf = PDDocument().use { doc ->
            doc.addPage(PDPage())
            saved(doc, PdfNote(temp(".xnote", bytes("x")), name))
        }

        assertEquals(listOf(name), attachments(pdf))
    }

    @Test fun aSaveTailFitsOnAnExportedFile() {
        val pdf = PDDocument().use { doc ->
            doc.addPage(PDPage())
            saved(doc, PdfNote(temp(".xnote", bytes("exported")), "n.xnote"))
        }
        val newer = bytes("saved since", 4200)

        RandomAccessFile(pdf, "rw").use { raf ->
            FileInputStream(temp(".xnote", newer)).use { zip -> assertTrue(XnotesPdf.appendTail(raf.channel, zip.channel) != null) }
            val ours = XnotesPdf.inspect(raf.channel) as XnotesPdf.Inspection.Ours
            assertTrue(ours.stale)
            assertArrayEquals(newer, readAll(XnotesPdf.embedded(raf.channel, ours)!!))
        }
        // And a reader following the file's own index gets the newer note too.
        assertArrayEquals(newer, attachment(pdf, "n.xnote"))
    }

    // --- a PDF another app has written since ---

    /** What another app's save leaves: every object written out again with a page of its own, our marker's length now wrong. */
    private fun rewrittenByAnotherApp(pdf: File): File {
        val out = temp(".pdf")
        PDDocument.load(pdf).use { doc ->
            doc.addPage(PDPage())
            doc.save(out)
        }
        return out
    }

    private fun inspect(pdf: File): XnotesPdf.Inspection = RandomAccessFile(pdf, "r").use { XnotesPdf.inspect(it.channel) }

    @Test fun theNoteComesBackOutOfAPdfAnotherAppRewrote() {
        val content = bytes("mine", 4000)
        val ours = PDDocument().use { doc ->
            doc.addPage(PDPage())
            saved(doc, PdfNote(temp(".xnote", content), "Lecture 3.xnote"))
        }
        val theirs = rewrittenByAnotherApp(ours)
        assertTrue("its bytes no longer say it is ours", inspect(theirs) is XnotesPdf.Inspection.Plain)

        val out = temp(".xnote")
        assertTrue(PdfNote.extract(theirs, out))
        assertArrayEquals(content, out.readBytes())
    }

    @Test fun theNewestNoteComesOutOfAPdfWithATailToo() {
        val ours = PDDocument().use { doc ->
            doc.addPage(PDPage())
            saved(doc, PdfNote(temp(".xnote", bytes("exported")), "n.xnote"))
        }
        val newer = bytes("saved since", 5200)
        RandomAccessFile(ours, "rw").use { raf ->
            FileInputStream(temp(".xnote", newer)).use { zip -> assertTrue(XnotesPdf.appendTail(raf.channel, zip.channel) != null) }
        }

        val out = temp(".xnote")
        assertTrue(PdfNote.extract(rewrittenByAnotherApp(ours), out))
        assertArrayEquals(newer, out.readBytes())
    }

    @Test fun aPdfWithNoNoteGivesNone() {
        val bare = temp(".pdf")
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            doc.save(bare)
        }

        assertFalse(PdfNote.extract(bare, temp(".xnote")))
    }

    @Test fun strippingLeavesAPlainPdf() {
        val ours = PDDocument().use { doc ->
            doc.addPage(PDPage())
            doc.addPage(PDPage())
            saved(doc, PdfNote(temp(".xnote", bytes("mine")), "n.xnote"))
        }
        val plain = temp(".pdf")

        PdfNote.strip(rewrittenByAnotherApp(ours), plain)

        assertTrue(inspect(plain) is XnotesPdf.Inspection.Plain)
        assertFalse(String(plain.readBytes(), latin).contains("XNotes"))
        assertFalse(PdfNote.extract(plain, temp(".xnote")))
        PDDocument.load(plain).use { doc ->
            assertEquals(3, doc.numberOfPages)
            assertEquals(null, doc.documentCatalog.cosObject.getDictionaryObject(COSName.NAMES))
        }
    }

    @Test fun strippingKeepsTheAttachmentsThatAreNotOurs() {
        val ours = PDDocument().use { doc ->
            doc.addPage(PDPage())
            val other = COSDictionary().apply { setItem(COSName.TYPE, COSName.getPDFName("Filespec")) }
            val tree = COSDictionary().apply { setItem(COSName.NAMES, COSArray().apply { add(COSString("b.txt")); add(other) }) }
            doc.documentCatalog.cosObject.setItem(COSName.NAMES, COSDictionary().apply { setItem(embeddedFiles, tree) })
            saved(doc, PdfNote(temp(".xnote", bytes("mine")), "n.xnote"))
        }
        assertEquals(listOf("b.txt", "n.xnote"), attachments(ours))
        val plain = temp(".pdf")

        PdfNote.strip(ours, plain)

        assertEquals(listOf("b.txt"), attachments(plain))
    }

    @Test fun sealingRefusesAFileThatDoesNotHoldTheNote() {
        val bare = temp(".pdf")
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            doc.save(bare)
        }
        val other = PDDocument().use { doc ->
            doc.addPage(PDPage())
            saved(doc, PdfNote(temp(".xnote", bytes("one", 1000)), "n.xnote"))
        }

        for (pdf in listOf(bare, other)) {
            try {
                PdfNote(temp(".xnote", bytes("another", 2000)), "n.xnote").seal(pdf)
                fail("sealed a file that does not hold the note")
            } catch (_: IOException) {
            }
        }
    }
}
