package com.xnotes.core.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileStampTest {

    // 12:34:56.789, as an sdfat exFAT card reported it before the inode was reloaded.
    private val written = FileStamp(size = 48_213L, modified = 1_790_751_896_789L)

    @Test
    fun `an untouched file matches`() {
        assertTrue(written.matches(written.copy()))
    }

    @Test
    fun `exFAT dropping the sub-second part on reload still matches`() {
        assertTrue(written.matches(written.copy(modified = 1_790_751_896_000L)))
    }

    @Test
    fun `FAT rounding an odd second down to an even one still matches`() {
        val odd = written.copy(modified = 1_790_751_897_999L)
        assertTrue(odd.matches(odd.copy(modified = 1_790_751_896_000L)))
    }

    @Test
    fun `a rewrite two seconds away does not match`() {
        assertFalse(written.matches(written.copy(modified = written.modified + 2_000L)))
        assertFalse(written.matches(written.copy(modified = written.modified - 2_000L)))
    }

    @Test
    fun `a different size never matches`() {
        assertFalse(written.matches(written.copy(size = written.size + 1)))
    }

    @Test
    fun `a missing mtime only matches another missing one`() {
        assertFalse(written.matches(written.copy(modified = -1L)))
        val sizeOnly = FileStamp(size = 48_213L, modified = -1L)
        assertTrue(sizeOnly.matches(sizeOnly.copy()))
    }
}
