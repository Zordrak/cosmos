package com.cosmos.screensaver.screensaver

import com.cosmos.screensaver.catalogue.CatalogueStoreTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class SlideshowTest {
    @Test
    fun showsEveryImageBeforeRepeatingAndNeverTwiceInARow() {
        val order = SlideshowOrder(Random(3))
        val keys = listOf("a", "b", "c", "d")
        var last: String? = null
        repeat(50) { round ->
            val cycle = (0 until keys.size).map {
                order.next(keys)!!.also { key ->
                    assertNotEquals("round $round", last, key)
                    last = key
                }
            }
            assertEquals(keys.toSet(), cycle.toSet())
        }
    }

    @Test
    fun adaptsWhenImagesAreRemoved() {
        val order = SlideshowOrder(Random(1))
        order.next(listOf("a", "b", "c"))
        assertEquals("c", order.next(listOf("c")))
        assertNull(order.next(emptyList()))
    }

    @Test
    fun attributionReproducesTheFullCredit() {
        val entry = CatalogueStoreTest.entry("heic9901a").copy(credit = "ESA/Hubble & NASA, A. Synthetic")

        val attribution = Attribution.of(entry)

        assertEquals("Synthetic heic9901a", attribution.title)
        assertEquals("Credit: ESA/Hubble & NASA, A. Synthetic", attribution.credit)
        assertEquals("ESA/Hubble  \u00b7  CC BY 4.0  \u00b7  esahubble.org/images/heic9901a", attribution.source)
    }
}
