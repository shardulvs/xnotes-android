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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The in-place save: a bundle's manifest is replaced without the assets ahead of it being read or
 * written. These drive exactly the sequence the editor drives, against a real file on disk, because
 * the failure this code can produce is a note that no longer opens.
 */
class ZipTailTest {

    private val codec = DocumentCodec(FakeImageCodec(), FakeTextMeasurer())

    private fun imageFile(bytes: ByteArray): File =
        File.createTempFile("img", null).apply { writeBytes(bytes); deleteOnExit() }

    /** A note carrying one big image, so there is a real asset prefix to preserve. */
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

    private fun write(doc: Document, to: File) {
        FileOutputStream(to).use { codec.write(doc, it) }
    }

    /** The editor's in-place path, verbatim enough to be worth testing. */
    private fun splice(file: File, doc: Document): Long {
        val assets = codec.imageAssets(doc)
        val keepCount = assets.size + if (doc.pdfFile != null) 1 else 0
        val dir = FileInputStream(file).use { ZipTail.read(it.channel) } ?: return -1
        val ordered = dir.entries.sortedBy { it.localOffset }
        val tailStart = ordered[keepCount].localOffset
        val tmp = File.createTempFile("tail", ".zip").apply { deleteOnExit() }
        FileOutputStream(tmp).use { out ->
            ZipOutputStream(out).use { zos ->
                zos.setLevel(Deflater.BEST_SPEED)
                codec.writeTail(zos, doc, assets)
            }
        }
        return FileInputStream(tmp).use { tail ->
            val tailDir = ZipTail.read(tail.channel)!!
            RandomAccessFile(file, "rw").use { raf ->
                ZipTail.splice(raf.channel, tailStart, ordered.subList(0, keepCount), tail.channel, tailDir)
            }
        }
    }

    private fun entriesOf(file: File): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(FileInputStream(file)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                names += e.name
                zis.readBytes()
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        return names
    }

    private fun readBack(file: File): Document {
        val imageDir = Files.createTempDirectory("xnotes-splice-img").toFile()
        return FileInputStream(file).use { codec.read(it, imageDir = imageDir) }
    }

    @Test fun aSplicedNoteReadsBackWithTheNewInk() {
        val bytes = ByteArray(4096) { (it % 251).toByte() }
        val image = imageFile(bytes)
        val file = File.createTempFile("note", ".xnote").apply { deleteOnExit() }
        write(docWith(strokes = 2, image = image), file)

        val edited = docWith(strokes = 9, image = image)
        assertTrue(splice(file, edited) > 0)

        val back = readBack(file)
        assertEquals(listOf("assets/image-000.png", "manifest.json"), entriesOf(file))
        assertEquals(10, back.pages[0].items.size) // the image plus nine strokes
        assertArrayEquals(bytes, (back.pages[0].items[0] as ImageItem).image.file.readBytes())
        assertEquals(11.5, (back.pages[0].items[9] as Stroke).samples[1].x, 1e-9) // stroke i=8
    }

    @Test fun aShorterManifestLeavesNoTrailingRubbish() {
        val image = imageFile(ByteArray(4096) { 3 })
        val file = File.createTempFile("note", ".xnote").apply { deleteOnExit() }
        write(docWith(strokes = 60, image = image), file)
        val long = file.length()

        val length = splice(file, docWith(strokes = 1, image = image))

        assertTrue("the file should have shrunk", length < long)
        assertEquals(length, file.length()) // truncated to exactly what was written
        assertEquals(2, readBack(file).pages[0].items.size)
    }

    @Test fun theAssetBytesAreNeverTouched() {
        val image = imageFile(ByteArray(8192) { (it % 97).toByte() })
        val file = File.createTempFile("note", ".xnote").apply { deleteOnExit() }
        write(docWith(strokes = 3, image = image), file)
        val dir = FileInputStream(file).use { ZipTail.read(it.channel) }!!
        val tailStart = dir.entries.sortedBy { it.localOffset }[1].localOffset
        val before = ByteArray(tailStart.toInt())
        FileInputStream(file).use { it.read(before) }

        splice(file, docWith(strokes = 40, image = image))

        val after = ByteArray(tailStart.toInt())
        FileInputStream(file).use { it.read(after) }
        assertArrayEquals(before, after)
    }

    @Test fun splicingTwiceInARowStaysValid() {
        val image = imageFile(ByteArray(2048) { 9 })
        val file = File.createTempFile("note", ".xnote").apply { deleteOnExit() }
        write(docWith(strokes = 1, image = image), file)

        splice(file, docWith(strokes = 20, image = image))
        splice(file, docWith(strokes = 5, image = image))

        assertEquals(6, readBack(file).pages[0].items.size)
        assertEquals(listOf("assets/image-000.png", "manifest.json"), entriesOf(file))
    }

    @Test fun aSplicedNoteMatchesOneWrittenWhole() {
        val image = imageFile(ByteArray(1024) { 5 })
        val spliced = File.createTempFile("spliced", ".xnote").apply { deleteOnExit() }
        val whole = File.createTempFile("whole", ".xnote").apply { deleteOnExit() }
        write(docWith(strokes = 2, image = image), spliced)
        val edited = docWith(strokes = 7, image = image)
        splice(spliced, edited)
        write(edited, whole)

        assertEquals(entriesOf(whole), entriesOf(spliced))
        assertEquals(readBack(whole).pages[0].items.size, readBack(spliced).pages[0].items.size)
    }

    @Test fun readRefusesWhatItDidNotWrite() {
        val file = File.createTempFile("odd", ".zip").apply { deleteOnExit() }
        // A zip carrying a comment: never something this wrote, so never something to patch blind.
        FileOutputStream(file).use { out ->
            ZipOutputStream(out).use { zos ->
                zos.setComment("hello")
                zos.putNextEntry(ZipEntry("a.txt"))
                zos.write(byteArrayOf(1, 2, 3))
                zos.closeEntry()
            }
        }
        assertNull(FileInputStream(file).use { ZipTail.read(it.channel) })

        val empty = File.createTempFile("empty", ".zip").apply { deleteOnExit() }
        assertNull(FileInputStream(empty).use { ZipTail.read(it.channel) })
    }

    // --- telling one save from another, which is what decides whether autosave forks ---

    private fun directoryOf(file: File): ZipTail.Directory =
        FileInputStream(file).use { ZipTail.read(it.channel) }!!

    /** A one-entry zip holding [text], its entry stamped as written at [time]. */
    private fun zipOf(text: String, time: Long): File {
        val file = File.createTempFile("save", ".zip").apply { deleteOnExit() }
        FileOutputStream(file).use { out ->
            ZipOutputStream(out).use { zos ->
                zos.putNextEntry(ZipEntry("manifest.json").apply { this.time = time })
                zos.write(text.toByteArray())
                zos.closeEntry()
            }
        }
        return file
    }

    @Test fun aFileThatOnlyGotANewModifiedTimeIsTheSameSave() {
        val file = File.createTempFile("note", ".xnote").apply { deleteOnExit() }
        write(docWith(strokes = 3, image = imageFile(ByteArray(4096) { 7 })), file)
        val known = directoryOf(file)

        // Storage whose modified time settles late looks like this: the stamp moves, the bytes do not.
        assertTrue(file.setLastModified(file.lastModified() - 3_600_000L))

        assertTrue(known.sameAs(directoryOf(file)))
    }

    @Test fun moreInkIsADifferentSaveSplicedOrWrittenWhole() {
        val image = imageFile(ByteArray(4096) { 7 })
        val file = File.createTempFile("note", ".xnote").apply { deleteOnExit() }
        write(docWith(strokes = 2, image = image), file)
        val known = directoryOf(file)

        splice(file, docWith(strokes = 9, image = image))
        val spliced = directoryOf(file)
        assertFalse(known.sameAs(spliced))
        assertTrue(spliced.sameAs(directoryOf(file))) // reading it again is still that save

        write(docWith(strokes = 4, image = image), file)
        assertFalse(spliced.sameAs(directoryOf(file)))
    }

    @Test fun otherContentOfTheSameSizeIsADifferentSave() {
        val at = 1_790_000_000_000L
        val one = zipOf("ink a", at)
        val other = zipOf("ink b", at)

        assertEquals(one.length(), other.length()) // nothing but the checksum tells them apart
        assertFalse(directoryOf(one).sameAs(directoryOf(other)))
    }

    @Test fun theSameContentSavedAtAnotherTimeIsADifferentSave() {
        val at = 1_790_000_000_000L
        val known = directoryOf(zipOf("ink", at))

        assertTrue(known.sameAs(directoryOf(zipOf("ink", at))))
        assertFalse(known.sameAs(directoryOf(zipOf("ink", at + 60_000L))))
    }
}
