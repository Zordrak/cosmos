package com.cosmos.screensaver.settings

import com.cosmos.screensaver.rights.AssetProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CosmosSettingsTest {
    @Test
    fun defaultsToFillSubtleCreditsAndEverything() {
        assertEquals(DisplayMode.FILL, parseEnumPreference(null, CosmosSettings.DEFAULT_DISPLAY_MODE))
        assertEquals(CaptionMode.SUBTLE, parseEnumPreference(null, CosmosSettings.DEFAULT_CAPTION_MODE))
        assertEquals(LicenceMode.ANY_AVAILABLE, parseEnumPreference(null, LicenceMode.ANY_AVAILABLE))
        assertEquals(CollectionPreset.EVERYTHING, CosmosSettings.collectionFor(AssetProvider.entries.toSet()))
    }

    @Test
    fun acceptsEveryChoosableMode() {
        assertEquals(DisplayMode.STRETCH, parseEnumPreference("STRETCH", CosmosSettings.DEFAULT_DISPLAY_MODE))
        assertEquals(CaptionMode.OFF, parseEnumPreference("OFF", CosmosSettings.DEFAULT_CAPTION_MODE))
        assertEquals(DisplayMode.FILL, parseEnumPreference("garbage", CosmosSettings.DEFAULT_DISPLAY_MODE))
    }

    @Test
    fun captionSizeDefaultsToMediumAndIgnoresInvalidValues() {
        assertEquals(CaptionSize.MEDIUM, CosmosSettings.DEFAULT_CAPTION_SIZE)
        assertEquals(CaptionSize.MEDIUM, parseEnumPreference(null, CosmosSettings.DEFAULT_CAPTION_SIZE))
        assertEquals(CaptionSize.MEDIUM, parseEnumPreference("STANDARD", CosmosSettings.DEFAULT_CAPTION_SIZE))
        assertEquals(CaptionSize.LARGE, parseEnumPreference("LARGE", CosmosSettings.DEFAULT_CAPTION_SIZE))
    }

    @Test
    fun cacheLimitDefaultsToAutomaticAndRejectsInvalidStoredValues() {
        val prefs = FakeSharedPreferences()
        val settings = CosmosSettings(prefs)
        assertEquals(0L, settings.cacheLimitBytes)

        prefs.edit().putLong("cache_limit_bytes", 12345L).apply()
        assertEquals(0L, settings.cacheLimitBytes)

        prefs.edit().putLong("cache_limit_bytes", -1L).apply()
        assertEquals(0L, settings.cacheLimitBytes)

        settings.cacheLimitBytes = CosmosSettings.CACHE_LIMIT_CHOICES[2]
        assertEquals(CosmosSettings.CACHE_LIMIT_CHOICES[2], settings.cacheLimitBytes)
    }

    @Test(expected = IllegalArgumentException::class)
    fun cacheLimitSetterRejectsValuesThatAreNotChoices() {
        CosmosSettings(FakeSharedPreferences()).cacheLimitBytes = 7L
    }

    @Test
    fun theCheckedLimitWriteReportsWhetherItWasSaved() {
        val settings = CosmosSettings(FakeSharedPreferences())
        assertTrue(settings.writeCacheLimit(CosmosSettings.CACHE_LIMIT_CHOICES[2]))
        assertEquals(CosmosSettings.CACHE_LIMIT_CHOICES[2], settings.cacheLimitBytes)
        assertFalse(settings.writeCacheLimit(7L))
        assertEquals(CosmosSettings.CACHE_LIMIT_CHOICES[2], settings.cacheLimitBytes)
    }

    @Test
    fun resettingViewingSettingsLeavesTheSpaceLimitAndCursorAlone() {
        val settings = CosmosSettings(FakeSharedPreferences())
        settings.cacheLimitBytes = CosmosSettings.CACHE_LIMIT_CHOICES[3]
        settings.refreshCursor = 5

        settings.resetViewingSettings()

        assertEquals(CosmosSettings.CACHE_LIMIT_CHOICES[3], settings.cacheLimitBytes)
        assertEquals(5, settings.refreshCursor)
    }

    @Test
    fun refreshCursorNeverGoesNegative() {
        val settings = CosmosSettings(FakeSharedPreferences())
        settings.refreshCursor = -4
        assertEquals(0, settings.refreshCursor)
        settings.refreshCursor = 3
        assertEquals(3, settings.refreshCursor)
    }

    @Test
    fun captionSizeHasFiveWidelySpacedStepsWithTheOldLargeAsMedium() {
        assertEquals(listOf(0.75f, 0.95f, 1.25f, 1.6f, 2.1f), CaptionSize.entries.map { it.scale })
        assertEquals(1.25f, CaptionSize.MEDIUM.scale, 0f)
        CaptionSize.entries.zipWithNext().forEach { (a, b) -> assertTrue(b.scale / a.scale >= 1.25f) }
    }

    @Test
    fun namesPresetsAndFallsBackToCustom() {
        assertEquals(
            CollectionPreset.DEEP_SPACE,
            CosmosSettings.collectionFor(
                setOf(AssetProvider.ESA_HUBBLE, AssetProvider.ESA_WEBB, AssetProvider.ESO, AssetProvider.NOIRLAB)
            )
        )
        assertEquals(CollectionPreset.PICTURE_OF_THE_DAY, CosmosSettings.collectionFor(setOf(AssetProvider.APOD)))
        assertEquals(
            CollectionPreset.NASA_ONLY,
            CosmosSettings.collectionFor(setOf(AssetProvider.NASA, AssetProvider.APOD))
        )
        assertEquals(CollectionPreset.CUSTOM, CosmosSettings.collectionFor(setOf(AssetProvider.ESO)))
    }
}
