package com.cosmos.screensaver.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContentFilterTest {
    private val rejected = SourceRejections.NOT_A_PHOTOGRAPH

    @Test
    fun rejectsTheSolarSpectrumChartDescription() {
        val description = "An echelle spectrum of the Sun, with wavelength increasing from left to right and bottom to top. " +
            "Solar Flux Atlas from 296 to 1300 nm."
        assertEquals(rejected, ContentFilter.check("Visible Solar Spectrum", description))
    }

    @Test
    fun rejectsExplicitChartsAndMultiPanelGraphics() {
        assertEquals(rejected, ContentFilter.check("Solar system infographic"))
        assertEquals(rejected, ContentFilter.check("Orbit diagram"))
        assertEquals(rejected, ContentFilter.check("Mission Timeline"))
        assertEquals(rejected, ContentFilter.check("Comet", "Top left image: the comet in March. Bottom right image: April."))
        assertEquals(rejected, ContentFilter.check("Comet", "A multi-panel view of the nucleus."))
        assertEquals(rejected, ContentFilter.check("Comet", "A time-lapse sequence of the tail."))
        assertEquals(rejected, ContentFilter.check("Comet", "Side-by-side comparison of two eclipses."))
    }

    @Test
    fun acceptsGenuinePhotographsThatUseSimilarWords() {
        assertNull(ContentFilter.check("Multiwavelength composite of the Crab Nebula", "A composite of X-ray, optical and infrared data."))
        assertNull(ContentFilter.check("Seamless mosaic of the Orion Nebula", "A mosaic of many exposures."))
        assertNull(ContentFilter.check("A spectrum of colours in the Lagoon Nebula", "Spectrum of colours across the cloud."))
        assertNull(ContentFilter.check("The camera on the telescope", "An instrument photograph of the detector."))
        assertNull(ContentFilter.check("Stacked exposures of M31", "Exposures taken over several nights were stacked."))
    }

    @Test
    fun matchesWholeWordsOnly() {
        assertNull(ContentFilter.check("Photograph of Mapping Mars"))
        assertNull(ContentFilter.check("Chartreuse glow over the Chartres cathedral"))
        assertNull(ContentFilter.check("Graphene sheets under a telescope"))
    }

    @Test
    fun clipLimitsLongText() {
        assertEquals(4000, ContentFilter.clip("x".repeat(9000)).length)
        assertEquals("short", ContentFilter.clip("short"))
    }
}
