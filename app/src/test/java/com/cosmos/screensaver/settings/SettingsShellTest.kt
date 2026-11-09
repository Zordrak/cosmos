package com.cosmos.screensaver.settings

import com.cosmos.screensaver.rights.AssetProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsShellTest {
    private fun settings(prefs: FakeSharedPreferences = FakeSharedPreferences()) = CosmosSettings(prefs)

    @Test
    fun `quality offers four levels`() {
        assertEquals(
            listOf(
                QualityPreference.UHD_ONLY, QualityPreference.UHD_PREFERRED,
                QualityPreference.FHD_OR_BETTER, QualityPreference.ANY
            ),
            qualityChoices(QualityPreference.UHD_ONLY)
        )
    }

    @Test
    fun `a legacy quality stays selectable in enum order and is not rewritten`() {
        val prefs = FakeSharedPreferences()
        prefs.data["quality"] = QualityPreference.HD_OR_BETTER.name
        val s = settings(prefs)
        assertEquals(
            listOf(
                QualityPreference.UHD_ONLY, QualityPreference.UHD_PREFERRED,
                QualityPreference.FHD_OR_BETTER, QualityPreference.HD_OR_BETTER, QualityPreference.ANY
            ),
            qualityChoices(s.quality)
        )
        assertEquals(QualityPreference.HD_OR_BETTER.name, prefs.data["quality"])
    }

    @Test
    fun `choosing Custom opens Sources and changes nothing`() {
        val s = settings()
        s.collection = CollectionPreset.DEEP_SPACE
        val before = s.enabledProviders
        assertTrue(applyCollection(s, CollectionPreset.CUSTOM))
        assertEquals(before, s.enabledProviders)
    }

    @Test
    fun `choosing a preset applies it`() {
        val s = settings()
        assertFalse(applyCollection(s, CollectionPreset.NASA_ONLY))
        assertEquals(CollectionPreset.NASA_ONLY, s.collection)
    }

    @Test
    fun `cycle list excludes Custom and the choices list ends with it`() {
        assertFalse(COLLECTION_CYCLE.contains(CollectionPreset.CUSTOM))
        assertEquals(CollectionPreset.CUSTOM, COLLECTION_CHOICES.last())
    }

    @Test
    fun `settings round trip`() {
        val s = settings()
        s.intervalSeconds = 300
        s.displayMode = DisplayMode.FIT
        s.captionMode = CaptionMode.DETAILED
        s.quality = QualityPreference.ANY
        s.licenceMode = LicenceMode.OPEN_LICENCE_ONLY
        s.streamOnly = true
        s.setEnabled(AssetProvider.ESO, false)
        assertEquals(300, s.intervalSeconds)
        assertEquals(DisplayMode.FIT, s.displayMode)
        assertEquals(CaptionMode.DETAILED, s.captionMode)
        assertEquals(QualityPreference.ANY, s.quality)
        assertEquals(LicenceMode.OPEN_LICENCE_ONLY, s.licenceMode)
        assertTrue(s.streamOnly)
        assertFalse(s.isEnabled(AssetProvider.ESO))
        assertEquals(CollectionPreset.CUSTOM, s.collection)
    }

    @Test
    fun `reset restores defaults and keeps unrelated keys`() {
        val prefs = FakeSharedPreferences()
        val s = settings(prefs)
        s.intervalSeconds = 300
        s.displayMode = DisplayMode.FIT
        s.captionMode = CaptionMode.OFF
        s.quality = QualityPreference.ANY
        s.licenceMode = LicenceMode.OPEN_LICENCE_ONLY
        s.streamOnly = true
        s.collection = CollectionPreset.DEEP_SPACE
        prefs.data["space_limit_bytes"] = 123L
        prefs.data["download_mode"] = "WIFI_ONLY"
        prefs.data["idle_minutes"] = 7

        s.resetViewingSettings()

        assertEquals(CosmosSettings.DEFAULT_INTERVAL_SECONDS, s.intervalSeconds)
        assertEquals(CosmosSettings.DEFAULT_DISPLAY_MODE, s.displayMode)
        assertEquals(CosmosSettings.DEFAULT_CAPTION_MODE, s.captionMode)
        assertEquals(CosmosSettings.DEFAULT_QUALITY, s.quality)
        assertEquals(CosmosSettings.DEFAULT_LICENCE_MODE, s.licenceMode)
        assertFalse(s.streamOnly)
        assertEquals(CosmosSettings.DEFAULT_COLLECTION, s.collection)
        assertEquals(123L, prefs.data["space_limit_bytes"])
        assertEquals("WIFI_ONLY", prefs.data["download_mode"])
        assertEquals(7, prefs.data["idle_minutes"])
    }
}
