package com.cosmos.screensaver.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeSummariesTest {
    private val labels = object : SummaryLabels {
        override fun collection(preset: CollectionPreset) = preset.name
        override fun quality(quality: QualityPreference) = quality.name
        override fun interval(seconds: Int) = "every $seconds"
        override fun picturesAndSize(count: Int, size: String) = "$count pictures \u00b7 $size"
        override val noSavedPictures = "none"
        override val unreadable = "unreadable"
        override val playingWithoutSaving = "streaming"
        override val savingPaused = "paused"
    }
    private val size = { bytes: Long -> "${bytes}B" }

    private fun saved(state: SavedPicturesState, playing: Boolean = false, paused: Boolean = false) =
        HomeSummaries.savedPictures(labels, state, playing, paused, size)

    @Test
    fun `what to show joins collection and quality`() {
        assertEquals(
            "EVERYTHING \u00b7 UHD_ONLY",
            HomeSummaries.whatToShow(labels, CollectionPreset.EVERYTHING, QualityPreference.UHD_ONLY)
        )
    }

    @Test
    fun `slideshow omits an unknown order`() {
        assertEquals("every 60", HomeSummaries.slideshow(labels, 60, null))
        assertEquals("every 60 \u00b7 shuffled", HomeSummaries.slideshow(labels, 60, "shuffled"))
    }

    @Test
    fun `loading is blank, empty is distinct and unreadable is distinct`() {
        assertEquals("", saved(SavedPicturesState.Loading))
        assertEquals("none", saved(SavedPicturesState.Loaded(0, 0)))
        assertEquals("unreadable", saved(SavedPicturesState.Unreadable))
    }

    @Test
    fun `normal shows physical count and size`() {
        assertEquals("91 pictures \u00b7 5B", saved(SavedPicturesState.Loaded(91, 5)))
    }

    @Test
    fun `playing without saving wins over a loaded library but not over unreadable`() {
        assertEquals("streaming", saved(SavedPicturesState.Loaded(3, 3), playing = true))
        assertEquals("unreadable", saved(SavedPicturesState.Unreadable, playing = true))
    }

    @Test
    fun `paused saving is shown quietly, below playing without saving and unreadable`() {
        assertEquals("paused", saved(SavedPicturesState.Loaded(3, 3), paused = true))
        assertEquals("paused", saved(SavedPicturesState.Loading, paused = true))
        assertEquals("streaming", saved(SavedPicturesState.Loaded(3, 3), playing = true, paused = true))
        assertEquals("unreadable", saved(SavedPicturesState.Unreadable, paused = true))
    }
}
