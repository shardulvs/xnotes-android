package com.xnotes.core.model

import com.xnotes.core.geometry.Flip
import com.xnotes.core.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageCropTest {

    private fun picture(orientation: Int = 0, flip: Flip = Flip.NONE, angle: Double = 0.0) =
        ImageItem(ImageData(java.io.File("p"), 100, 200), Rect(0.0, 0.0, 100.0, 200.0), orientation, angle, flip)

    private val bottomHalfOfLeft = Rect.ltrb(0.25, 0.5, 0.75, 1.0)

    @Test fun anUprightPictureCropsTheSamePixelsItShows() {
        assertEquals(Rect.ltrb(25.0, 100.0, 75.0, 200.0), ImageCrop.sourceRect(picture(), 100, 200, bottomHalfOfLeft))
    }

    @Test fun aTurnedPictureCropsInItsOwnFrame() {
        // Turned a quarter clockwise, the picture's bottom shows on the box's left.
        assertEquals(Rect.ltrb(0.0, 100.0, 100.0, 200.0), ImageCrop.sourceRect(picture(orientation = 90), 100, 200, Rect.ltrb(0.0, 0.0, 0.5, 1.0)))
        assertEquals(Rect.ltrb(0.0, 0.0, 100.0, 100.0), ImageCrop.sourceRect(picture(orientation = 270), 100, 200, Rect.ltrb(0.0, 0.0, 0.5, 1.0)))
        assertEquals(Rect.ltrb(25.0, 0.0, 75.0, 100.0), ImageCrop.sourceRect(picture(orientation = 180), 100, 200, bottomHalfOfLeft))
    }

    @Test fun aMirroredPictureCropsWhatShowsWhereItShows() {
        assertEquals(Rect.ltrb(50.0, 0.0, 100.0, 200.0), ImageCrop.sourceRect(picture(flip = Flip.X), 100, 200, Rect.ltrb(0.0, 0.0, 0.5, 1.0)))
        assertEquals(Rect.ltrb(0.0, 0.0, 100.0, 100.0), ImageCrop.sourceRect(picture(flip = Flip.Y), 100, 200, Rect.ltrb(0.0, 0.5, 1.0, 1.0)))
    }

    @Test fun theBoxShrinksToThePartAndKeepsItsTurn() {
        assertEquals(Rect(25.0, 100.0, 50.0, 100.0), ImageCrop.croppedRect(picture(), bottomHalfOfLeft))
        // A quarter turn clockwise carries the part's offset below the centre over to its left.
        val turned = ImageCrop.croppedRect(picture(angle = Math.PI / 2), bottomHalfOfLeft)
        assertEquals(-25.0, turned.x, 1e-9)
        assertEquals(50.0, turned.y, 1e-9)
        assertEquals(50.0, turned.w, 1e-9)
        assertEquals(100.0, turned.h, 1e-9)
    }

    @Test fun theWholePictureIsNothingToCrop() {
        assertTrue(ImageCrop.isWhole(Rect.ltrb(0.0, 0.0, 1.0, 1.0)))
        assertFalse(ImageCrop.isWhole(bottomHalfOfLeft))
    }
}
