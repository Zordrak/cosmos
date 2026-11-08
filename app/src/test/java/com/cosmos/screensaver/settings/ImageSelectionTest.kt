package com.cosmos.screensaver.settings

import com.cosmos.screensaver.catalogue.CatalogueEntry
import com.cosmos.screensaver.catalogue.CatalogueStoreTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageSelectionTest {
    private fun image(id: String, width: Int, height: Int, checked: Boolean = true): CatalogueEntry {
        val entry = CatalogueStoreTest.entry(id).copy(widthPx = width, heightPx = height)
        return if (checked) entry else entry.copy(rightsBasis = CatalogueEntry.RIGHTS_NOT_CHECKED, policyVersion = "")
    }

    @Test
    fun defaultsToPersonalUseAnd4k() {
        assertEquals(ImageSelection(LicenceMode.ANY_AVAILABLE, QualityPreference.UHD_ONLY), ImageSelection())
    }

    @Test
    fun uhdOnlyAcceptsNear4kInBothDimensions() {
        val selection = ImageSelection()
        val entries = listOf(
            image("a", 3840, 2160),
            image("b", 3700, 2100),
            image("c", 6000, 2000),
            image("d", 1920, 1080)
        )

        assertEquals(listOf("a", "b"), selection.eligible(entries).map { it.providerId })
    }

    @Test
    fun openLicenceModeOnlyShowsVerifiedImages() {
        val entries = listOf(image("a", 4000, 2250), image("b", 4000, 2250, checked = false), image("c", 4000, 2250).copy(policyVersion = "0"))

        assertEquals(listOf("a"), ImageSelection(LicenceMode.OPEN_LICENCE_ONLY).eligible(entries).map { it.providerId })
        assertEquals(3, ImageSelection().eligible(entries).size)
    }

    @Test
    fun preferred4kUses1080pOnlyUntilEnough4kImagesExist() {
        val selection = ImageSelection(quality = QualityPreference.UHD_PREFERRED)
        val few = listOf(image("hd", 1920, 1080), image("uhd", 3840, 2160))
        assertTrue(selection.fallbackActive(few))
        assertEquals(2, selection.eligible(few).size)

        val many = few + (1..ImageSelection.FALLBACK_TARGET).map { image("u$it", 3840, 2160) }
        assertFalse(selection.fallbackActive(many))
        assertFalse(selection.eligible(many).any { it.providerId == "hd" })
    }

    @Test
    fun anyQualityAcceptsEverything() {
        assertEquals(1, ImageSelection(quality = QualityPreference.ANY).eligible(listOf(image("a", 10, 10))).size)
    }

    @Test
    fun fingerprintChangesWithEverySetting() {
        val fingerprints = LicenceMode.entries.flatMap { licence ->
            QualityPreference.entries.map { ImageSelection(licence, it).fingerprint }
        }
        assertEquals(fingerprints.size, fingerprints.toSet().size)
        assertNotEquals(ImageSelection().fingerprint, ImageSelection(LicenceMode.OPEN_LICENCE_ONLY).fingerprint)
    }
}