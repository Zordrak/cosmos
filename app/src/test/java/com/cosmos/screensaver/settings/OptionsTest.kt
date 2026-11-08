package com.cosmos.screensaver.settings

import com.cosmos.screensaver.rights.AssetProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OptionsTest {
    @Test
    fun everythingPresetEnablesEverySource() {
        assertEquals(AssetProvider.entries.toSet(), CollectionPreset.EVERYTHING.providers)
        assertEquals(6, AssetProvider.entries.size)
    }

    @Test
    fun presetsAreDistinctAndCustomHasNoFixedSet() {
        assertEquals(setOf(AssetProvider.APOD), CollectionPreset.PICTURE_OF_THE_DAY.providers)
        assertEquals(setOf(AssetProvider.NASA, AssetProvider.APOD), CollectionPreset.NASA_ONLY.providers)
        assertFalse(AssetProvider.NASA in CollectionPreset.DEEP_SPACE.providers!!)
        assertNull(CollectionPreset.CUSTOM.providers)
    }

    @Test
    fun resolutionRequiresBothDimensionsWithoutTolerance() {
        assertTrue(Resolution.UHD.accepts(3840, 2160))
        assertTrue(Resolution.UHD.accepts(7680, 4320))
        assertFalse(Resolution.UHD.accepts(3839, 2160))
        assertFalse(Resolution.UHD.accepts(6000, 2000))
    }

    @Test
    fun imagesBeyond8kAreOutOfRange() {
        assertTrue(ImageSelection.withinMaximum(7680, 4320))
        assertFalse(ImageSelection.withinMaximum(7681, 3000))
        assertFalse(ImageSelection.withinMaximum(0, 100))
    }

    @Test
    fun onlyLandscapeFrom4by3To21by9Qualifies() {
        assertTrue(ImageSelection.suitableAspect(1600, 1200))
        assertTrue(ImageSelection.suitableAspect(3840, 2160))
        assertTrue(ImageSelection.suitableAspect(5040, 2160))
        assertFalse(ImageSelection.suitableAspect(1000, 1000))
        assertFalse(ImageSelection.suitableAspect(2160, 3840))
        assertFalse(ImageSelection.suitableAspect(8000, 2000))
    }

    @Test
    fun fingerprintChangesWithAnySettingThatChangesAdmission() {
        val base = ImageSelection()
        assertNotEquals(base.fingerprint, base.copy(quality = QualityPreference.FHD_OR_BETTER).fingerprint)
        assertNotEquals(base.fingerprint, base.copy(licenceMode = LicenceMode.OPEN_LICENCE_ONLY).fingerprint)
        assertEquals(base.fingerprint, ImageSelection().fingerprint)
    }
}
