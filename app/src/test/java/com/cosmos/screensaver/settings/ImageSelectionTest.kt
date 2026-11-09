package com.cosmos.screensaver.settings

import com.cosmos.screensaver.catalogue.CatalogueEntry
import com.cosmos.screensaver.catalogue.CatalogueStoreTest
import com.cosmos.screensaver.catalogue.UserState
import com.cosmos.screensaver.rights.AssetProvider
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

    private fun eligible(selection: ImageSelection, entries: List<CatalogueEntry>) =
        SelectionSnapshot.build(entries, selection, setOf(AssetProvider.ESA_HUBBLE), UserState())

    @Test
    fun uhdOnlyNeedsAtLeast4kInBothDimensions() {
        val selection = ImageSelection()
        val entries = listOf(
            image("a", 3840, 2160),
            image("b", 3700, 2100),
            image("c", 6000, 2000),
            image("d", 1920, 1080)
        )

        assertEquals(listOf("a"), eligible(selection, entries).eligible.map { it.providerId })
    }

    @Test
    fun openLicenceModeOnlyShowsVerifiedImages() {
        val entries = listOf(image("a", 4000, 2250), image("b", 4000, 2250, checked = false), image("c", 4000, 2250).copy(policyVersion = "0"))

        assertEquals(listOf("a"), eligible(ImageSelection(LicenceMode.OPEN_LICENCE_ONLY), entries).eligible.map { it.providerId })
        assertEquals(3, eligible(ImageSelection(), entries).eligible.size)
    }

    @Test
    fun preferred4kUses1080pOnlyUntilEnough4kImagesExist() {
        val selection = ImageSelection(quality = QualityPreference.UHD_PREFERRED)
        val hd = image("hd", 1920, 1080)
        fun uhd(count: Int) = (1..count).map { image("u$it", 3840, 2160) }
        assertTrue(eligible(selection, listOf(hd) + uhd(2)).fallbackActive)
        assertEquals(3, eligible(selection, listOf(hd) + uhd(2)).eligible.size)

        val nineteen = eligible(selection, listOf(hd) + uhd(ImageSelection.FALLBACK_TARGET - 1))
        assertTrue("one short of the target still uses the fallback", nineteen.fallbackActive)
        val enough = eligible(selection, listOf(hd) + uhd(ImageSelection.FALLBACK_TARGET))
        assertFalse(enough.fallbackActive)
        assertFalse(enough.eligible.any { it.providerId == "hd" })
    }

    @Test
    fun anyQualityAcceptsEverything() {
        assertEquals(1, eligible(ImageSelection(quality = QualityPreference.ANY), listOf(image("a", 10, 10))).eligible.size)
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