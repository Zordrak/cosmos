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
    }
    private val size = { bytes: Long -> "${bytes}B" }

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
        assertEquals("", HomeSummaries.savedPictures(labels, SavedPicturesState.Loading, false, size))
        assertEquals("none", HomeSummaries.savedPictures(labels, SavedPicturesState.Loaded(0, 0), false, size))
        assertEquals("unreadable", HomeSummaries.savedPictures(labels, SavedPicturesState.Unreadable, false, size))
    }

    @Test
    fun `normal shows physical count and size`() {
        assertEquals("91 pictures \u00b7 5B", HomeSummaries.savedPictures(labels, SavedPicturesState.Loaded(91, 5), false, size))
    }

    @Test
    fun `playing without saving wins over a loaded library but not over unreadable`() {
        assertEquals("streaming", HomeSummaries.savedPictures(labels, SavedPicturesState.Loaded(3, 3), true, size))
        assertEquals("unreadable", HomeSummaries.savedPictures(labels, SavedPicturesState.Unreadable, true, size))
    }
}
