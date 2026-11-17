package com.xnotes.platform

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** How an export loads the PDF a note annotates. */
class PdfExporterSourceTest {

    private fun protectedPdf(aes: Boolean): File {
        val file = File.createTempFile("locked", ".pdf").apply { deleteOnExit() }
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            doc.addPage(PDPage())
            // An owner password and no user password: it opens for anyone, with restrictions.
            val policy = StandardProtectionPolicy("owner", "", AccessPermission().apply { setCanPrint(false) })
            policy.encryptionKeyLength = 128
            policy.isPreferAES = aes
            doc.protect(policy)
            doc.save(file)
        }
        return file
    }

    @Test fun aSourceUnderAnOwnerPasswordIsSavedWithoutIt() {
        for (aes in listOf(true, false)) {
            val out = File.createTempFile("export", ".pdf").apply { deleteOnExit() }

            PdfExporter.loadSource(protectedPdf(aes), MemoryUsageSetting.setupMainMemoryOnly())!!.use { src ->
                assertTrue(src.isEncrypted)
                src.save(out) // PdfBox refuses this while the protection stays on
            }

            PDDocument.load(out).use {
                assertFalse(it.isEncrypted)
                assertEquals(2, it.numberOfPages)
            }
        }
    }
}
