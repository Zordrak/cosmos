package com.cosmos.screensaver.screensaver

import com.cosmos.screensaver.settings.CaptionMode
import com.cosmos.screensaver.settings.DisplayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewPlacementTest {
    private fun place(
        mode: DisplayMode,
        viewportWidth: Int = 1920,
        viewportHeight: Int = 1080,
        bitmapWidth: Int = 960,
        bitmapHeight: Int = 720,
        frameWidth: Int = 480,
        frameHeight: Int = 270
    ) = previewPlacement(
        mode, 3200, 2400, viewportWidth, viewportHeight, bitmapWidth, bitmapHeight, frameWidth, frameHeight
    )

    @Test
    fun fitLeavesBarsAndShowsTheWholeBitmap() {
        val placed = place(DisplayMode.FIT)
        assertEquals(IntBox(0, 0, 960, 720), placed.source)
        assertEquals(IntBox(60, 0, 420, 270), placed.destination)
    }

    @Test
    fun fillTrimsTopAndBottomOfAFourByThreePicture() {
        val placed = place(DisplayMode.FILL)
        assertEquals(IntBox(0, 90, 960, 630), placed.source)
        assertEquals(IntBox(0, 0, 480, 270), placed.destination)
    }

    @Test
    fun stretchUsesTheWholePictureAndTheWholeFrame() {
        val placed = place(DisplayMode.STRETCH)
        assertEquals(IntBox(0, 0, 960, 720), placed.source)
        assertEquals(IntBox(0, 0, 480, 270), placed.destination)
    }

    @Test
    fun autoFitsThisPictureBecauseFillWouldTrimTooMuch() {
        assertEquals(place(DisplayMode.FIT), place(DisplayMode.AUTO))
    }

    @Test
    fun originalSizeUsesTheOriginalImageSizeNotTheMiniatureSize() {
        val placed = place(DisplayMode.ORIGINAL)
        assertEquals(IntBox(192, 198, 768, 522), placed.source)
        assertEquals(IntBox(0, 0, 480, 270), placed.destination)
    }

    @Test
    fun originalSizeOnA4kPanelShowsRelativelyMoreOfTheSurroundings() {
        val placed = place(DisplayMode.ORIGINAL, viewportWidth = 3840, viewportHeight = 2160)
        assertEquals(IntBox(0, 36, 960, 684), placed.source)
        assertEquals(IntBox(40, 0, 440, 270), placed.destination)
    }

    @Test
    fun aLowerResolutionBitmapMapsTheSameCrop() {
        val placed = place(DisplayMode.FILL, bitmapWidth = 320, bitmapHeight = 240)
        assertEquals(IntBox(0, 30, 320, 210), placed.source)
    }

    @Test
    fun aPanelThatIsNotExactlySixteenByNineKeepsItsOwnProportions() {
        val placed = place(DisplayMode.FIT, viewportWidth = 4096, viewportHeight = 2160, frameWidth = 512, frameHeight = 270)
        assertEquals(IntBox(0, 0, 960, 720), placed.source)
        assertEquals(270, placed.destination.height)
        assertTrue(placed.destination.width < 512)
    }

    @Test
    fun everyModeStaysInsideTheBitmapAndTheFrame() {
        DisplayMode.entries.forEach { mode ->
            val placed = place(mode)
            assertTrue(mode.name, placed.source.left >= 0 && placed.source.right <= 960 && placed.source.width > 0)
            assertTrue(mode.name, placed.source.top >= 0 && placed.source.bottom <= 720 && placed.source.height > 0)
            assertTrue(mode.name, placed.destination.right <= 480 && placed.destination.bottom <= 270)
            assertTrue(mode.name, placed.destination.width > 0 && placed.destination.height > 0)
        }
    }
}

class CaptionGeometryTest {
    private val attribution = Attribution("Title", "Credit: Someone", "Source")

    @Test
    fun layoutKeyChangesWithEveryInputThatAffectsLayout() {
        val base = CaptionGeometry.key(attribution, CaptionMode.SUBTLE, 1f, 1920, 1080)
        assertEquals(base, CaptionGeometry.key(attribution.copy(), CaptionMode.SUBTLE, 1f, 1920, 1080))
        assertNotEquals(base, CaptionGeometry.key(attribution.copy(title = "Other"), CaptionMode.SUBTLE, 1f, 1920, 1080))
        assertNotEquals(base, CaptionGeometry.key(attribution, CaptionMode.DETAILED, 1f, 1920, 1080))
        assertNotEquals(base, CaptionGeometry.key(attribution, CaptionMode.SUBTLE, 1.25f, 1920, 1080))
        assertNotEquals(base, CaptionGeometry.key(attribution, CaptionMode.SUBTLE, 1f, 3840, 2160))
    }

    @Test
    fun detailedCaptionsGetMoreRoomAndAPaddedPanel() {
        assertTrue(CaptionGeometry.maxTextWidth(1920, detailed = true) > CaptionGeometry.maxTextWidth(1920, detailed = false))
        assertTrue(CaptionGeometry.padding(true) > CaptionGeometry.padding(false))
        assertTrue(CaptionGeometry.margin(true) > CaptionGeometry.margin(false))
        assertEquals(1, CaptionGeometry.maxTextWidth(0, detailed = false))
    }
}
