package com.cosmos.screensaver.settings

import com.cosmos.screensaver.rights.AssetProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadModeSettingsTest {
    @Test
    fun `download mode defaults to automatic and ignores unknown values`() {
        val prefs = FakeSharedPreferences()
        val s = CosmosSettings(prefs)
        assertEquals(DownloadMode.ALWAYS, s.downloadMode)
        s.downloadMode = DownloadMode.PAUSED
        assertEquals(DownloadMode.PAUSED, s.downloadMode)
        prefs.data["download_mode"] = "SOMETHING_NEW"
        assertEquals(DownloadMode.ALWAYS, s.downloadMode)
    }

    @Test
    fun `reset leaves the download mode alone`() {
        val prefs = FakeSharedPreferences()
        val s = CosmosSettings(prefs)
        s.downloadMode = DownloadMode.UNMETERED_ONLY
        s.resetViewingSettings()
        assertEquals(DownloadMode.UNMETERED_ONLY, s.downloadMode)
    }

    @Test
    fun `a source choice combines enabled and weight`() {
        val s = CosmosSettings(FakeSharedPreferences())
        val webb = AssetProvider.ESA_WEBB
        s.setSourceChoice(webb, SourceChoice.MORE)
        assertTrue(s.isEnabled(webb))
        assertEquals(SourceWeight.MORE, s.sourceWeight(webb))
        assertEquals(SourceChoice.MORE, s.sourceChoice(webb))

        s.setSourceChoice(webb, SourceChoice.LESS)
        assertEquals(SourceChoice.LESS, s.sourceChoice(webb))

        s.setSourceChoice(webb, SourceChoice.NORMAL)
        assertEquals(SourceChoice.NORMAL, s.sourceChoice(webb))
    }

    @Test
    fun `off keeps the weight so turning the source back on restores it`() {
        val s = CosmosSettings(FakeSharedPreferences())
        val webb = AssetProvider.ESA_WEBB
        s.setSourceChoice(webb, SourceChoice.MORE)
        s.setSourceChoice(webb, SourceChoice.OFF)
        assertFalse(s.isEnabled(webb))
        assertEquals(SourceChoice.OFF, s.sourceChoice(webb))
        assertEquals(SourceWeight.MORE, s.sourceWeight(webb))
        s.setEnabled(webb, true)
        assertEquals(SourceChoice.MORE, s.sourceChoice(webb))
    }

    @Test
    fun `source choice of combines enabled and weight`() {
        assertEquals(SourceChoice.OFF, SourceChoice.of(false, SourceWeight.MORE))
        assertEquals(SourceChoice.LESS, SourceChoice.of(true, SourceWeight.LESS))
        assertEquals(SourceChoice.NORMAL, SourceChoice.of(true, SourceWeight.NORMAL))
        assertEquals(SourceChoice.MORE, SourceChoice.of(true, SourceWeight.MORE))
    }
}
