package com.cosmos.screensaver.screensaver

import com.cosmos.screensaver.settings.DisplayMode
import org.junit.Assert.assertEquals
import org.junit.Test

class PlacementTest {
    @Test
    fun fillCoversTheScreenByCroppingTheOverflowingEdges() {
        val wide = placement(DisplayMode.FILL, 4000, 1000, 1920, 1080)
        assertEquals(IntBox(0, 0, 1920, 1080), wide.destination)
        assertEquals(IntBox(1111, 0, 2889, 1000), wide.source)

        val tall = placement(DisplayMode.FILL, 4000, 3000, 1920, 1080)
        assertEquals(IntBox(0, 0, 1920, 1080), tall.destination)
        assertEquals(IntBox(0, 375, 4000, 2625), tall.source)
    }

    @Test
    fun fillAndFitPreserveTheAspectRatio() {
        listOf(DisplayMode.FILL, DisplayMode.FIT, DisplayMode.AUTO).forEach { mode ->
            val placed = placement(mode, 6000, 2500, 1920, 1080)
            val sourceRatio = placed.source.width.toDouble() / placed.source.height
            val destinationRatio = placed.destination.width.toDouble() / placed.destination.height
            assertEquals(mode.name, sourceRatio, destinationRatio, 0.01)
        }
    }

    @Test
    fun fitShowsTheWholeImageWithBars() {
        val placed = placement(DisplayMode.FIT, 4000, 1000, 1920, 1080)
        assertEquals(IntBox(0, 0, 4000, 1000), placed.source)
        assertEquals(IntBox(0, 300, 1920, 780), placed.destination)
    }

    @Test
    fun autoFillsSmallMismatchesAndFitsLargeOnes() {
        assertEquals(IntBox(0, 0, 1920, 1080), placement(DisplayMode.AUTO, 4000, 2500, 1920, 1080).destination)
        assertEquals(IntBox(0, 300, 1920, 780), placement(DisplayMode.AUTO, 4000, 1000, 1920, 1080).destination)
    }

    @Test
    fun originalMapsImagePixelsToPhysicalPixels() {
        val small = placement(DisplayMode.ORIGINAL, 1920, 1080, 1920, 1080, physicalWidth = 3840)
        assertEquals(IntBox(480, 270, 1440, 810), small.destination)
        assertEquals(IntBox(0, 0, 1920, 1080), small.source)

        val large = placement(DisplayMode.ORIGINAL, 8000, 4500, 1920, 1080, physicalWidth = 3840)
        assertEquals(IntBox(0, 0, 1920, 1080), large.destination)
        assertEquals(IntBox(2080, 1170, 5920, 3330), large.source)
    }

    @Test
    fun stretchUsesTheWholeImageAndTheWholeScreen() {
        val placed = placement(DisplayMode.STRETCH, 4000, 1000, 1920, 1080)
        assertEquals(IntBox(0, 0, 4000, 1000), placed.source)
        assertEquals(IntBox(0, 0, 1920, 1080), placed.destination)
    }

    @Test
    fun samplesDownWithoutDroppingBelowTheDestination() {
        assertEquals(1, sampleSizeFor(1920, 1080, 1920, 1080))
        assertEquals(1, sampleSizeFor(3000, 1688, 1920, 1080))
        assertEquals(2, sampleSizeFor(4000, 2250, 1920, 1080))
        assertEquals(4, sampleSizeFor(9694, 6463, 1920, 1080))
        assertEquals(1, sampleSizeFor(4000, 1000, 1920, 1080))
        assertEquals(1, sampleSizeFor(0, 0, 1920, 1080))
    }
}