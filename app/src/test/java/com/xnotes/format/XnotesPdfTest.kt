package com.xnotes.format

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Document
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.util.zip.Deflater
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The bytes of an xnotes PDF, on PDFs built by hand the way PdfBox and other writers lay them out.
 * What these protect is a note that reopens: every case ends by reading the note back out of the file.
 */
class XnotesPdfTest {

    private val latin = Charsets.ISO_8859_1

    private fun temp(bytes: ByteArray): File =
        File.createTempFile("xnotes", ".pdf").apply { writeBytes(bytes); deleteOnExit() }

    private fun <T> File.rw(block: (FileChannel) -> T): T = RandomAccessFile(this, "rw").use { block(it.channel) }

    private fun zipFile(bytes: ByteArray): File =
        File.createTempFile("note", ".xnote").apply { writeBytes(bytes); deleteOnExit() }

    private fun appendTail(pdf: File, note: ByteArray): XnotesPdf.Tail? =
        pdf.rw { ch -> FileInputStream(zipFile(note)).use { XnotesPdf.appendTail(ch, it.channel) } }

    private fun readAll(w: FileWindow): ByteArray {
        val buf = ByteBuffer.allocate(w.size().toInt())
        while (buf.hasRemaining()) if (w.read(buf, buf.position().toLong()) < 0) break
        return buf.array()
    }

    /** The note inside [pdf], which has to be ours. */
    private fun noteIn(pdf: File): ByteArray = pdf.rw { ch ->
        val ours = XnotesPdf.inspect(ch) as XnotesPdf.Inspection.Ours
        readAll(XnotesPdf.embedded(ch, ours)!!)
    }

    private fun ours(pdf: File): XnotesPdf.Inspection.Ours? = pdf.rw { XnotesPdf.inspect(it) as? XnotesPdf.Inspection.Ours }

    private fun stamp(pdf: File): Boolean = pdf.rw { XnotesPdf.stamp(it) }

    /** Bytes that read like the inside of a PDF, which a stored `source.pdf` puts in the middle of the note. */
    private fun note(tag: String, size: Int = 600): ByteArray {
        val seed = "PK $tag 1 0 obj << /Length 9 >> stream\nendstream endobj xref trailer startxref 9 %%EOF\n".toByteArray(latin)
        return ByteArray(size) { seed[it % seed.size] }
    }

    // --- PDFs by hand ---

    private fun body(note: ByteArray) = MiniPdf.body(note)

    private fun exported(note: ByteArray, marker: Boolean = true, extraObjects: Int = 0, encrypt: Boolean = false) =
        MiniPdf.exported(note, marker, extraObjects, encrypt)

    private fun endingInAStream(note: ByteArray) = MiniPdf.endingInAStream(note)

    private fun contains(bytes: ByteArray, word: String, from: Int = 0): Boolean {
        val w = word.toByteArray(latin)
        return (from..bytes.size - w.size).any { i -> w.indices.all { bytes[i + it] == w[it] } }
    }

    // --- the marker ---

    @Test fun anExportIsOursOnceItsLengthIsStamped() {
        val note = note("first")
        val pdf = temp(exported(note))
        val before = pdf.readBytes()

        assertNull("an open marker proves nothing yet", ours(pdf))
        assertTrue(stamp(pdf))

        val found = ours(pdf)!!
        assertFalse(found.stale)
        assertArrayEquals(note, noteIn(pdf))
        assertEquals("stamping moves no byte", before.size.toLong(), pdf.length())
        val digits = "%013d".format(before.size)
        assertTrue(contains(pdf.readBytes(), "/Len ($digits)"))
    }

    @Test fun aPdfWithoutTheMarkerIsPlain() {
        val pdf = temp(exported(note("plain"), marker = false))

        assertTrue(pdf.rw { XnotesPdf.inspect(it) } is XnotesPdf.Inspection.Plain)
        assertFalse(stamp(pdf))
        assertNull(appendTail(pdf, note("x")))
    }

    @Test fun whatDoesNotEndLikeAPdfIsUnreadable() {
        val withOurWords = "junk\n%xnotes-tail\nmore junk".toByteArray() + ByteArray(5000) { 7 }
        for (bytes in listOf(ByteArray(0), "hello".toByteArray(), ByteArray(9000) { 7 }, "startxref\n99999\n%%EOF\n".toByteArray(), withOurWords)) {
            assertTrue(temp(bytes).rw { XnotesPdf.inspect(it) } is XnotesPdf.Inspection.Unreadable)
        }
    }

    @Test fun theNoteIsFoundBehindALongTable() {
        val note = note("far")
        val pdf = temp(exported(note, extraObjects = 3000)) // a table far longer than the stretch read from the end
        assertTrue(stamp(pdf))

        assertArrayEquals(note, noteIn(pdf))
    }

    @Test fun aTrailerWrittenTightReadsTheSame() {
        val note = note("tight")
        val w = body(note)
        val xref = w.table(8)
        w.put("trailer<</Size 8/Root 1 0 R/Info 3 0 R % a comment\r/XNotes<</V 1/Obj 7 0 R/Len(0000000000000)>>/ID[(a\\)b)<0A>]>>\r\n")
        w.end(xref)
        val pdf = temp(w.out.toByteArray())

        assertTrue(stamp(pdf))
        assertArrayEquals(note, noteIn(pdf))
    }

    // --- another app ---

    @Test fun bytesAppendedByAnotherAppAreNoticed() {
        val note = note("mine")
        val pdf = temp(exported(note))
        stamp(pdf)
        val own = pdf.length()
        FileOutputStream(pdf, true).use { it.write("\n% touched\n".toByteArray()) }

        val found = pdf.rw { XnotesPdf.inspect(it) } as XnotesPdf.Inspection.Appended
        assertEquals(own, found.ownLength)
        val back = pdf.rw { ch -> readAll(XnotesPdf.embedded(FileWindow(ch, 0L, found.ownLength), found.own)!!) }
        assertArrayEquals(note, back)
    }

    /** An incremental update by another app: one more object, a table, and a trailer pointing back at ours. */
    private fun updateBy(pdf: File, keepsOurKey: Boolean) {
        val prev = String(pdf.readBytes(), latin).let { it.substring(it.lastIndexOf("startxref") + 9).trim().lines().first().toInt() }
        val base = pdf.length().toInt()
        val w = MiniPdf.Writer()
        w.put("\n")
        val at = base + w.out.size()
        w.put("9 0 obj\n<<\n/Type /Annot\n/Subtype /Highlight\n>>\nendobj\n")
        val xref = base + w.out.size()
        w.put("xref\n9 1\n%010d 00000 n\r\n".format(at))
        w.put("trailer\n<<\n/Root 1 0 R\n/Size 10\n/Prev $prev\n")
        if (keepsOurKey) w.put(String(pdf.readBytes(), latin).let { it.substring(it.lastIndexOf("/XNotes"), it.lastIndexOf(">>\nstartxref")) })
        w.put(">>\n")
        w.end(xref)
        FileOutputStream(pdf, true).use { it.write(w.out.toByteArray()) }
    }

    @Test fun anotherAppsUpdateIsNoticedWhetherOrNotItKeepsOurKey() {
        for (keeps in listOf(false, true)) {
            val note = note("mine")
            val pdf = temp(exported(note))
            stamp(pdf)
            val own = pdf.length()
            updateBy(pdf, keepsOurKey = keeps)

            val found = pdf.rw { XnotesPdf.inspect(it) } as XnotesPdf.Inspection.Appended
            assertEquals(own, found.ownLength)
            assertFalse(found.own.stale)
        }
    }

    @Test fun anUpdateAfterATailLeavesTheTailsNoteReadable() {
        val pdf = temp(exported(note("old")))
        stamp(pdf)
        val newer = note("newer", 900)
        val tail = appendTail(pdf, newer)!!
        updateBy(pdf, keepsOurKey = false)

        val found = pdf.rw { XnotesPdf.inspect(it) } as XnotesPdf.Inspection.Appended
        assertEquals(tail.end, found.ownLength)
        assertTrue(found.own.stale)
        val back = pdf.rw { ch -> readAll(XnotesPdf.embedded(FileWindow(ch, 0L, found.ownLength), found.own)!!) }
        assertArrayEquals(newer, back)
    }

    @Test fun aFileCutShortIsNotOurs() {
        val bytes = exported(note("cut"))
        val pdf = temp(bytes)
        stamp(pdf)
        val stamped = pdf.readBytes()
        // The same file with a byte gone from its middle: the marker's length no longer fits it.
        val damaged = temp(stamped.copyOfRange(0, 40) + stamped.copyOfRange(41, stamped.size))

        assertTrue(damaged.rw { XnotesPdf.inspect(it) } is XnotesPdf.Inspection.Unreadable)
    }

    // --- tails ---

    @Test fun aTailCarriesTheNewerNoteAndLeavesTheExportUntouched() {
        val pdf = temp(exported(note("old")))
        stamp(pdf)
        val export = pdf.readBytes()
        val newer = note("newer", 2500)

        val tail = appendTail(pdf, newer)!!

        val now = pdf.readBytes()
        assertEquals(export.size.toLong(), tail.start)
        assertEquals(now.size.toLong(), tail.end)
        assertArrayEquals(export, now.copyOfRange(0, export.size))
        assertTrue(String(now, latin).endsWith("%%EOF\n"))
        assertTrue(ours(pdf)!!.stale)
        assertArrayEquals(newer, noteIn(pdf))
        assertArrayEquals(newer, pdf.rw { readAll(tail.window(it)) })
    }

    @Test fun aTailIsCutAndWrittenAgainLongerOrShorter() {
        val pdf = temp(exported(note("old")))
        stamp(pdf)
        val export = pdf.length()
        var tail = appendTail(pdf, note("one", 2000))!!

        for (size in listOf(5000, 300, 2000)) {
            val next = note("size $size", size)
            tail = pdf.rw { ch -> FileInputStream(zipFile(next)).use { XnotesPdf.rewriteTail(ch, tail, it.channel) } }!!

            assertEquals(export, tail.start)
            assertEquals(pdf.length(), tail.end)
            assertTrue(String(pdf.readBytes(), latin).endsWith("%%EOF\n"))
            assertTrue(ours(pdf)!!.stale)
            assertArrayEquals(next, noteIn(pdf))
        }
    }

    @Test fun aLaterSessionAppendsAfterAnEarlierTail() {
        val pdf = temp(exported(note("old")))
        stamp(pdf)
        val first = appendTail(pdf, note("first session", 1200))!!
        val second = appendTail(pdf, note("second session", 700))!!

        assertEquals("the earlier tail is not cut", first.end, second.start)
        assertArrayEquals(note("second session", 700), noteIn(pdf))
        // What the first session left is still a whole file of ours underneath.
        val under = pdf.rw { ch ->
            val w = FileWindow(ch, 0L, first.end)
            readAll(XnotesPdf.embedded(w, XnotesPdf.inspect(w) as XnotesPdf.Inspection.Ours)!!)
        }
        assertArrayEquals(note("first session", 1200), under)
    }

    @Test fun aTailIsATableLikeTheExportItFollows() {
        val pdf = temp(exported(note("old")))
        stamp(pdf)
        val tail = appendTail(pdf, note("newer"))!!

        val after = pdf.readBytes()
        assertTrue(contains(after, "trailer", tail.start.toInt() + 700))
        assertFalse("a chain that mixes tables and streams is one some readers refuse", contains(after, "/XRef", tail.start.toInt()))
    }

    @Test fun onlyAFileThatIsOursTakesATail() {
        val unstamped = temp(exported(note("open marker")))
        val stream = temp(endingInAStream(note("stream")))
        val touched = temp(exported(note("touched"))).also { stamp(it); FileOutputStream(it, true).use { o -> o.write(10) } }

        for (pdf in listOf(unstamped, stream, touched)) {
            val before = pdf.readBytes()
            assertNull(appendTail(pdf, note("x")))
            assertArrayEquals(before, pdf.readBytes())
        }
        assertTrue(stream.rw { XnotesPdf.inspect(it) } is XnotesPdf.Inspection.Plain)
    }

    @Test fun anotherAppsUpdateWrittenAsAStreamIsNoticed() {
        val note = note("mine")
        val pdf = temp(exported(note))
        stamp(pdf)
        val own = pdf.length()
        val prev = String(pdf.readBytes(), latin).let { it.substring(it.lastIndexOf("startxref") + 9).trim().lines().first().toInt() }
        val w = MiniPdf.Writer()
        w.put("\n")
        val xref = own.toInt() + w.out.size()
        w.put("9 0 obj\n<<\n/Type /XRef\n/Size 10\n/W [1 2 0]\n/Index [9 1]\n/Root 1 0 R\n/Prev $prev\n/Filter /FlateDecode\n/Length 5\n>>\nstream\r\n")
        w.out.write(byteArrayOf(120, 1, 3, 0, 0)) // rows this never has to read
        w.put("\r\nendstream\nendobj\n")
        w.end(xref)
        FileOutputStream(pdf, true).use { it.write(w.out.toByteArray()) }

        val found = pdf.rw { XnotesPdf.inspect(it) } as XnotesPdf.Inspection.Appended
        assertEquals(own, found.ownLength)
        val back = pdf.rw { ch -> readAll(XnotesPdf.embedded(FileWindow(ch, 0L, found.ownLength), found.own)!!) }
        assertArrayEquals(note, back)
    }

    // --- a write that stopped part way ---

    private fun torn(pdf: File): XnotesPdf.Inspection.Torn = pdf.rw { XnotesPdf.inspect(it) } as XnotesPdf.Inspection.Torn

    private fun noteUnder(pdf: File, found: XnotesPdf.Inspection.Torn): ByteArray =
        pdf.rw { ch -> readAll(XnotesPdf.embedded(FileWindow(ch, 0L, found.ownLength), found.own)!!) }

    @Test fun aTailCutShortAnywhereLeavesTheNoteUnderItReadable() {
        val older = note("exported")
        val whole = temp(exported(older))
        stamp(whole)
        val export = whole.length()
        val tail = appendTail(whole, note("never finished", 9000))!!
        val full = whole.readBytes()

        // In its first line, in its head, in the note, in the closing bytes, and one byte short of done.
        for (cut in listOf(export + 5, export + 20, tail.zipStart + 4000, tail.zipStart + tail.zipLength + 30, tail.end - 1)) {
            val pdf = temp(full.copyOfRange(0, cut.toInt()))

            val found = torn(pdf)
            assertEquals("cut at $cut", export, found.ownLength)
            assertFalse(found.own.stale)
            assertArrayEquals(older, noteUnder(pdf, found))

            // Cut back to what is whole, it is ours again and takes the next save.
            pdf.rw { it.truncate(found.ownLength) }
            assertFalse(ours(pdf)!!.stale)
            assertNotNull(appendTail(pdf, note("saved after all")))
            assertArrayEquals(note("saved after all"), noteIn(pdf))
        }
    }

    @Test fun aLaterTailCutShortFallsBackToTheTailBeforeIt() {
        val pdf = temp(exported(note("exported")))
        stamp(pdf)
        val first = appendTail(pdf, note("first session", 1500))!!
        val second = appendTail(pdf, note("second session", 2500))!!
        pdf.rw { it.truncate(second.zipStart + 700) }

        val found = torn(pdf)
        assertEquals(first.end, found.ownLength)
        assertTrue(found.own.stale)
        assertArrayEquals(note("first session", 1500), noteUnder(pdf, found))
    }

    @Test fun aTailOpenForSplicingHasNoEndUntilItIsClosed() {
        val older = note("exported")
        val pdf = temp(exported(older))
        stamp(pdf)
        val tail = appendTail(pdf, note("saved", 3000))!!

        pdf.rw { XnotesPdf.openTail(it, tail) }

        // A crash here must not leave a file that reads as whole around a half-spliced note.
        val found = torn(pdf)
        assertEquals(tail.start, found.ownLength)
        assertArrayEquals(older, noteUnder(pdf, found))

        val closed = pdf.rw { XnotesPdf.closeTail(it, tail, tail.zipLength) }!!
        assertEquals(pdf.length(), closed.end)
        assertArrayEquals(note("saved", 3000), noteIn(pdf))
    }

    @Test fun aRewriteThatFailsLeavesTheFileAsItWasBeforeTheTailOrTorn() {
        val older = note("exported")
        val pdf = temp(exported(older))
        stamp(pdf)
        val export = pdf.length()
        val tail = appendTail(pdf, note("saved", 3000))!!
        val next = zipFile(note("too short a source", 2000))

        // A source that runs dry part way, as a failing read would.
        val threw = pdf.rw { ch ->
            FileInputStream(next).use { zip ->
                runCatching { XnotesPdf.rewriteTail(ch, tail, FileWindow(zip.channel, 0L, 2500L)) }.isFailure
            }
        }

        assertTrue(threw)
        val found = torn(pdf)
        assertEquals(export, found.ownLength)
        assertArrayEquals(older, noteUnder(pdf, found))
    }

    @Test fun theEndingTellsOneSaveFromAnother() {
        val pdf = temp(exported(note("exported")))
        stamp(pdf)
        val fresh = pdf.rw { XnotesPdf.ending(it) }
        assertTrue(pdf.setLastModified(pdf.lastModified() - 3_600_000L))
        assertArrayEquals("a new modified time alone changes nothing", fresh, pdf.rw { XnotesPdf.ending(it) })

        var tail = appendTail(pdf, note("one", 800))!!
        val one = pdf.rw { XnotesPdf.ending(it) }
        assertFalse(fresh.contentEquals(one))

        // The same length of note with other bytes in it is another save.
        tail = pdf.rw { ch -> FileInputStream(zipFile(note("two", 800))).use { XnotesPdf.rewriteTail(ch, tail, it.channel) } }!!
        assertFalse(one.contentEquals(pdf.rw { XnotesPdf.ending(it) }))
        assertEquals(pdf.length(), tail.end)
    }

    @Test fun readingAndWritingGoThroughTheTwoHalvesOfADescriptor() {
        val pdf = temp(exported(note("exported")))
        stamp(pdf)
        val newer = note("through two channels", 2600)

        RandomAccessFile(pdf, "rw").use { raf ->
            // What Android gives for a provider's file: a channel that only reads and one that only writes.
            val ch = ReadWriteChannel(FileInputStream(raf.fd).channel, FileOutputStream(raf.fd).channel)
            val tail = FileInputStream(zipFile(newer)).use { XnotesPdf.appendTail(ch, it.channel) }!!
            assertEquals(ch.size(), tail.end)
            val ours = XnotesPdf.inspect(ch) as XnotesPdf.Inspection.Ours
            assertTrue(ours.stale)
            assertArrayEquals(newer, readAll(XnotesPdf.embedded(ch, ours)!!))
        }
        assertArrayEquals(newer, noteIn(pdf))
    }

    @Test fun anEncryptedPdfTakesNoTail() {
        val pdf = temp(exported(note("locked"), encrypt = true))
        stamp(pdf)
        val before = pdf.readBytes()

        assertNull(appendTail(pdf, note("x")))
        assertArrayEquals(before, pdf.readBytes())
    }

    // --- the note spliced in place, as the editor saves ---

    private val codec = DocumentCodec(FakeImageCodec(), FakeTextMeasurer())

    private fun docWith(strokes: Int, image: File): Document {
        val doc = Document.blank(count = 1)
        doc.pages[0].items.add(ImageItem(ImageData(image, 8, 8), Rect(0.0, 0.0, 8.0, 8.0)))
        repeat(strokes) { i ->
            doc.pages[0].items.add(
                Stroke(
                    Tool.PEN,
                    ToolDefaults.configFor(Tool.PEN),
                    mutableListOf(Sample(i + 1.5, 2.25, 1.0), Sample(i + 3.5, 4.75, 0.5)),
                ),
            )
        }
        return doc
    }

    private fun bundle(doc: Document): ByteArray = ByteArrayOutputStream().also { codec.write(doc, it) }.toByteArray()

    /** The editor's in-place save, on the note inside [pdf]: its manifest replaced, then the tail closed again. */
    private fun splice(pdf: File, tail: XnotesPdf.Tail, doc: Document): XnotesPdf.Tail = pdf.rw { ch ->
        val assets = codec.imageAssets(doc)
        val ordered = ZipTail.read(tail.window(ch))!!.entries.sortedBy { it.localOffset }
        val window = XnotesPdf.openTail(ch, tail)
        val tailStart = ordered[assets.size].localOffset
        val tmp = File.createTempFile("tail", ".zip").apply { deleteOnExit() }
        FileOutputStream(tmp).use { out ->
            ZipOutputStream(out).use { zos ->
                zos.setLevel(Deflater.BEST_SPEED)
                codec.writeTail(zos, doc, assets)
            }
        }
        val length = FileInputStream(tmp).use { t ->
            ZipTail.splice(window, tailStart, ordered.subList(0, assets.size), t.channel, ZipTail.read(t.channel)!!)
        }
        assertTrue(length > 0)
        XnotesPdf.closeTail(ch, tail, length)!!
    }

    private fun readBack(bytes: ByteArray): Document {
        val imageDir = Files.createTempDirectory("xnotes-pdf-img").toFile()
        return codec.read(ByteArrayInputStream(bytes), imageDir = imageDir)
    }

    @Test fun aSaveSplicesOnlyTheManifestInsideTheTail() {
        val pixels = ByteArray(4096) { (it % 251).toByte() }
        val image = File.createTempFile("img", null).apply { writeBytes(pixels); deleteOnExit() }
        val pdf = temp(exported(bundle(docWith(strokes = 1, image = image))))
        stamp(pdf)
        var tail = appendTail(pdf, bundle(docWith(strokes = 2, image = image)))!!
        val assetsEnd = pdf.rw { ch -> tail.zipStart + ZipTail.read(tail.window(ch))!!.entries.sortedBy { it.localOffset }[1].localOffset }
        val untouched = pdf.readBytes().copyOfRange(0, assetsEnd.toInt())

        for (strokes in listOf(40, 3, 12)) {
            tail = splice(pdf, tail, docWith(strokes = strokes, image = image))

            assertEquals(pdf.length(), tail.end)
            assertArrayEquals("nothing ahead of the manifest moves", untouched, pdf.readBytes().copyOfRange(0, assetsEnd.toInt()))
            assertTrue(ours(pdf)!!.stale)
            val inside = noteIn(pdf)
            assertEquals(tail.zipLength, inside.size.toLong())
            val back = readBack(inside)
            assertEquals(strokes + 1, back.pages[0].items.size)
            assertArrayEquals(pixels, (back.pages[0].items[0] as ImageItem).image.file.readBytes())
            // Taken out of the PDF, the note is an ordinary bundle with nothing odd about its offsets.
            val names = ArrayList<String>()
            ZipInputStream(ByteArrayInputStream(inside)).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    names += e.name
                    e = zis.nextEntry
                }
            }
            assertEquals(listOf("assets/image-000.png", "manifest.json"), names)
        }
    }

    @Test fun theCodecReadsTheNoteThroughTheWindow() {
        val image = File.createTempFile("img", null).apply { writeBytes(ByteArray(2048) { 5 }); deleteOnExit() }
        val pdf = temp(exported(bundle(docWith(strokes = 6, image = image))))
        stamp(pdf)

        pdf.rw { ch ->
            val window = XnotesPdf.embedded(ch, XnotesPdf.inspect(ch) as XnotesPdf.Inspection.Ours)!!
            assertEquals(1, codec.peek(window)!!.pages)
            val imageDir = Files.createTempDirectory("xnotes-pdf-img").toFile()
            val doc = codec.read(Channels.newInputStream(window.position(0L)), imageDir = imageDir)
            assertEquals(7, doc.pages[0].items.size)
        }
    }

    // --- the window ---

    @Test fun aWindowReadsAndWritesOnlyItsStretch() {
        val file = temp(ByteArray(100) { it.toByte() })
        file.rw { ch ->
            val w = FileWindow(ch, 40L, 20L)
            assertEquals(20L, w.size())
            val buf = ByteBuffer.allocate(64)
            assertEquals(20, w.read(buf, 0L))
            assertEquals(40.toByte(), buf.get(0))
            assertEquals(-1, w.read(ByteBuffer.allocate(4), 20L))

            w.write(ByteBuffer.wrap(byteArrayOf(1, 2, 3)), 18L) // one byte past its end: it grows
            assertEquals(21L, w.size())
            w.truncate(5L) // and cutting it cuts the file there
            assertEquals(5L, w.size())
        }
        assertEquals(45L, file.length())
    }
}
