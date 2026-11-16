package com.xnotes.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageSampleSizeTest {

    // A 12 MP phone photo.
    private val w = 4000
    private val h = 3000

    @Test
    fun aBoxAsBigAsThePhotoDecodesEveryPixel() {
        assertEquals(1, sampleSize(w, h, w, h))
        assertEquals(1, sampleSize(w, h, 4096, 4096))
    }

    @Test
    fun nearbyBoxesShareOneStepSoADragOrResizeHitsTheCache() {
        val step = sampleSize(w, h, 1000, 750)
        for (bw in 900..1000) assertEquals(step, sampleSize(w, h, bw, bw * 3 / 4))
    }

    @Test
    fun theStepOnlyChangesPastAHalving() {
        assertEquals(4, sampleSize(w, h, 1000, 750))
        assertEquals(2, sampleSize(w, h, 1001, 750))
        assertEquals(2, sampleSize(w, h, 2000, 1500))
        assertEquals(1, sampleSize(w, h, 2001, 1500))
    }

    @Test
    fun theDecodeAlwaysCoversTheBoxAndNeverDoublesIt() {
        for (box in listOf(1, 37, 300, 999, 1000, 1001, 1999, 2000, 3000)) {
            val ss = sampleSize(w, h, box, box)
            assertTrue(w / ss >= box || ss == 1)
            assertTrue(h / ss >= box || ss == 1)
            // One step further would have been too small on some side.
            assertTrue(w / (ss * 2) < box || h / (ss * 2) < box)
        }
    }
}
