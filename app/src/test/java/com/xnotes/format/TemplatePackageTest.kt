package com.xnotes.format

import com.xnotes.core.template.TemplateKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TemplatePackageTest {

    private fun temp(bytes: ByteArray = byteArrayOf()): File =
        File.createTempFile("tpk", null).apply { writeBytes(bytes); deleteOnExit() }

    private val json = """{ "xtemplate": 1, "name": "Photo", "kind": "cover", "items": [
        { "type": "image", "src": "images/a.jpg", "fit": "cover" } ] }"""

    @Test fun aPackageIsToldFromPlainJsonByItsFirstBytes() {
        assertTrue(TemplatePackage.isPackage(byteArrayOf(0x50, 0x4b, 3, 4)))
        assertFalse(TemplatePackage.isPackage("{ }".toByteArray()))
        assertFalse(TemplatePackage.isPackage(temp(byteArrayOf(0x50))))
    }

    @Test fun aWrittenPackageReadsBackItsJsonAndPictures() {
        val picture = temp(byteArrayOf(1, 2, 3, 4, 5))
        val pkg = temp()
        TemplatePackage.write(pkg, json, listOf("images/a.jpg" to picture))

        assertTrue(TemplatePackage.isPackage(pkg))
        assertEquals(json, TemplatePackage.readJson(pkg))
        val out = temp()
        assertTrue(TemplatePackage.extract(pkg, "images/a.jpg", out))
        assertTrue(out.readBytes().contentEquals(picture.readBytes()))
        assertFalse(TemplatePackage.extract(pkg, "images/missing.jpg", temp()))
        assertEquals(TemplateKind.COVER, TemplateReader.read(TemplatePackage.readJson(pkg)).kind)
    }

    @Test fun picturesAreStoredAndTheJsonIsDeflated() {
        val pkg = temp()
        TemplatePackage.write(pkg, json, listOf("images/a.jpg" to temp(byteArrayOf(9, 9))))
        java.util.zip.ZipFile(pkg).use { zip ->
            assertEquals(java.util.zip.ZipEntry.DEFLATED, zip.getEntry("template.json").method)
            assertEquals(java.util.zip.ZipEntry.STORED, zip.getEntry("images/a.jpg").method)
        }
    }

    @Test fun onlyRelativePathsWithoutParentSegmentsAreAllowed() {
        assertTrue(TemplatePackage.validSrc("images/a.jpg"))
        assertTrue(TemplatePackage.validSrc("images/..a.jpg"))
        assertFalse(TemplatePackage.validSrc(""))
        assertFalse(TemplatePackage.validSrc("/etc/passwd"))
        assertFalse(TemplatePackage.validSrc("../a.jpg"))
        assertFalse(TemplatePackage.validSrc("images/../../a.jpg"))
    }

    @Test fun aPackageWithoutTemplateJsonOrTooBigIsRefused() {
        val pkg = temp()
        java.util.zip.ZipOutputStream(pkg.outputStream()).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("other.json"))
            zos.write("{}".toByteArray())
            zos.closeEntry()
        }
        assertThrows(TemplateFormatException::class.java) { TemplatePackage.readJson(pkg) }

        val big = temp()
        java.io.RandomAccessFile(big, "rw").use { it.setLength(TemplatePackage.MAX_BYTES + 1) }
        assertThrows(TemplateFormatException::class.java) { TemplatePackage.readJson(big) }
        assertThrows(TemplateFormatException::class.java) { TemplatePackage.readJson(temp("PK not a zip".toByteArray())) }
    }

    @Test fun kindReadsCoverAndEverythingElseAsPage() {
        fun kindOf(v: String?) = TemplateReader.read(
            """{ "xtemplate": 1, "name": "x", ${if (v == null) "" else "\"kind\": $v,"} "items": [] }""",
        ).kind
        assertEquals(TemplateKind.COVER, kindOf("\"cover\""))
        assertEquals(TemplateKind.PAGE, kindOf("\"page\""))
        assertEquals(TemplateKind.PAGE, kindOf("\"spine\""))
        assertEquals(TemplateKind.PAGE, kindOf("3"))
        assertEquals(TemplateKind.PAGE, kindOf(null))
    }
}
