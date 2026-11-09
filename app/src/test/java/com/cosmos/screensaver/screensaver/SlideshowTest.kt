package com.cosmos.screensaver.screensaver

import com.cosmos.screensaver.catalogue.CatalogueStoreTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SlideshowTest {
    fun attributionReproducesTheFullCredit() {
        val entry = CatalogueStoreTest.entry("heic9901a").copy(credit = "ESA/Hubble & NASA, A. Synthetic")

        val attribution = Attribution.of(entry)

        assertEquals("Synthetic heic9901a", attribution.title)
        assertEquals("Credit: ESA/Hubble & NASA, A. Synthetic", attribution.credit)
        assertEquals("ESA/Hubble  \u00b7  CC BY 4.0  \u00b7  creativecommons.org/licenses/by/4.0  \u00b7  esahubble.org/images/heic9901a", attribution.source)
    }
}
