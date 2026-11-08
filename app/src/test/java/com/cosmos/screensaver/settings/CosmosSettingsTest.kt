package com.cosmos.screensaver.settings

import com.cosmos.screensaver.rights.AssetProvider
import org.junit.Assert.assertEquals
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
