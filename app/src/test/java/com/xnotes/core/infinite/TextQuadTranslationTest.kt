package com.xnotes.core.infinite

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextQuadTranslationTest {
    private val original = doubleArrayOf(10.0, 20.0, 110.0, 20.0,
        10.0, 80.0, 110.0, 80.0)

    @Test fun movingAnExistingTextQuadDoesNotRequireRerasterization() {
        val moved = doubleArrayOf(17.5, -4.0, 117.5, -4.0,
            17.5, 56.0, 117.5, 56.0)
        assertTrue(isRigidTextQuadMotion(original, moved))
        assertTrue(isRigidTextQuadMotion(original, original))
    }

    @Test fun sideResizeCanMoveTheTextWithoutScalingItsLetters() {
        val origin = Pt(10.0, 20.0)
        val fixedLeftEdge = fixedSizeTextLift(origin, Affine.scaleAbout(origin, 1.5, 1.0))
        val fixedRightEdge = fixedSizeTextLift(origin,
            Affine.scaleAbout(Pt(110.0, 20.0), 1.5, 1.0))
        val topLeft = origin
        val bottomRight = Pt(110.0, 80.0)
        assertEquals(0.0, fixedLeftEdge.dx, 1e-9)
        assertEquals(-50.0, fixedRightEdge.dx, 1e-9)
        assertEquals(0.0, fixedRightEdge.dy, 1e-9)
        assertEquals(topLeft.distanceTo(bottomRight),
            fixedLeftEdge.apply(topLeft).distanceTo(fixedLeftEdge.apply(bottomRight)), 1e-9)
        assertEquals(topLeft.distanceTo(bottomRight),
            fixedRightEdge.apply(topLeft).distanceTo(fixedRightEdge.apply(bottomRight)), 1e-9)
    }

    @Test fun unchangedLineBreaksCanReuseTheirCentredTexture() {
        val ends = intArrayOf(17, 36, 52, 75)
        assertTrue(textWrapChanged(null, ends))
        assertFalse(textWrapChanged(ends, intArrayOf(17, 36, 52, 75)))
        assertTrue(textWrapChanged(ends, intArrayOf(18, 36, 52, 75)))
        assertTrue(textWrapChanged(ends, intArrayOf(17, 36, 75)))
    }

    @Test fun widthOrHeightChangeMustRequestNewLayout() {
        assertFalse(isRigidTextQuadMotion(original, doubleArrayOf(
            10.0, 20.0, 130.0, 20.0, 10.0, 80.0, 130.0, 80.0)))
        assertFalse(isRigidTextQuadMotion(original, doubleArrayOf(
            10.0, 20.0, 110.0, 20.0, 10.0, 100.0, 110.0, 100.0)))
    }

    @Test fun aRotatedQuadReusesItsTextureWithoutJumpingOnRelease() {
        val turn = Affine.rotateAbout(Pt(60.0, 50.0), Math.PI / 3.0)
        val rotated = original.copyOf()
        for (i in 0 until 8 step 2) {
            val point = turn.apply(Pt(original[i], original[i + 1]))
            rotated[i] = point.x
            rotated[i + 1] = point.y
        }
        assertTrue(isRigidTextQuadMotion(original, rotated))
        assertTrue(isRigidTextQuadMotion(original, original))
    }

    @Test fun changingTheQuadSizeOrShearingStillRequiresNewTextRaster() {
        val resized = Affine.scaleAbout(Pt(10.0, 20.0), 1.4, 1.0)
        val resizedCorners = original.copyOf()
        for (i in 0 until 8 step 2) {
            val p = resized.apply(Pt(original[i], original[i + 1]))
            resizedCorners[i] = p.x
            resizedCorners[i + 1] = p.y
        }
        assertFalse(isRigidTextQuadMotion(original, resizedCorners))
        val broken = original.copyOf().also { it[6] += 20.0 }
        assertFalse(isRigidTextQuadMotion(original, broken))
    }

    @Test fun veryTallEditableTextKeepsItsNaturalBitmapResolutionWhenItFitsGpu() {
        val width = 158.69140625
        val height = 5323.5
        assertEquals(1.0, textBitmapScale(width, height, 16383.0, preview = true), 1e-9)
        assertEquals(1.0, textBitmapScale(width, height, 16383.0, preview = false), 1e-9)
        // On weaker hardware the texture must still respect GL's supported dimensions.
        assertEquals(4096.0 / height,
            textBitmapScale(width, height, 4096.0, preview = false), 1e-9)
    }

    @Test fun ordinaryPreviewsKeepTheSmallAllocationBudget() {
        val scale = textBitmapScale(1000.0, 1000.0, 16383.0, preview = true)
        assertEquals(kotlin.math.sqrt(350_000.0 / 1_000_000.0), scale, 1e-9)
        assertEquals(1.0, textBitmapScale(1000.0, 1000.0, 16383.0, preview = false), 1e-9)
    }

    @Test fun zoomLevelsChooseStableDetailTiers() {
        assertEquals(1.0, textRasterZoomTier(0.5), 0.0)
        assertEquals(1.0, textRasterZoomTier(1.25), 0.0)
        assertEquals(2.0, textRasterZoomTier(1.5), 0.0)
        assertEquals(2.0, textRasterZoomTier(2.5), 0.0)
        assertEquals(4.0, textRasterZoomTier(3.0), 0.0)
        assertEquals(4.0, textRasterZoomTier(8.0), 0.0)
        assertEquals(1.0, textRasterZoomTier(Double.NaN), 0.0)
    }

    @Test fun higherZoomRendersMorePixelsWithoutChangingDocumentGeometry() {
        assertEquals(1.0, textBitmapScale(100.0, 50.0, 16383.0,
            preview = false), 1e-9)
        assertEquals(2.0, textBitmapScale(100.0, 50.0, 16383.0,
            preview = false, targetScale = 2.0), 1e-9)
        assertEquals(4.0, textBitmapScale(100.0, 50.0, 16383.0,
            preview = false, targetScale = 4.0), 1e-9)
        assertEquals(kotlin.math.sqrt(2.0), textBitmapScale(1000.0, 1000.0,
            16383.0, preview = false, targetScale = 4.0), 1e-9)
        assertEquals(4096.0 / 5323.5, textBitmapScale(158.69140625, 5323.5,
            4096.0, preview = false, targetScale = 4.0), 1e-9)
    }

    @Test fun refinementsNeedMeaningfulGainAndNeverAcceptInvalidScale() {
        assertFalse(shouldRefineTextBitmap(2.0, 2.0))
        assertFalse(shouldRefineTextBitmap(2.0, 2.4))
        assertTrue(shouldRefineTextBitmap(1.0, 2.0))
        assertFalse(shouldRefineTextBitmap(0.0, 2.0))
        assertFalse(shouldRefineTextBitmap(1.0, Double.NaN))
    }

    @Test fun rotationCanReuseTextureButInvalidCoordinatesCannot() {
        assertTrue(isRigidTextQuadMotion(original, doubleArrayOf(
            110.0, 20.0, 110.0, 120.0, 50.0, 20.0, 50.0, 120.0)))
        assertFalse(isRigidTextQuadMotion(original, doubleArrayOf(Double.NaN)))
    }
}
