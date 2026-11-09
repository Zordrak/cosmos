package com.cosmos.screensaver.settings

/** The wording used by [HomeSummaries], supplied from string resources (and faked in tests). */
internal interface SummaryLabels {
    fun collection(preset: CollectionPreset): String
    fun quality(quality: QualityPreference): String
    fun interval(seconds: Int): String
    fun picturesAndSize(count: Int, size: String): String
    val noSavedPictures: String
    val unreadable: String
    val playingWithoutSaving: String
    val savingPaused: String
}

/** What is known about the saved pictures. Unknown-yet and unreadable are distinct from empty. */
internal sealed class SavedPicturesState {
    object Loading : SavedPicturesState()
    object Unreadable : SavedPicturesState()
    data class Loaded(val files: Int, val bytes: Long) : SavedPicturesState()
}

/** Concise, truthful values for the Home rows (a design target of about 28 characters, never a truncation). */
internal object HomeSummaries {
    fun whatToShow(labels: SummaryLabels, collection: CollectionPreset, quality: QualityPreference): String =
        "${labels.collection(collection)} \u00b7 ${labels.quality(quality)}"

    fun slideshow(labels: SummaryLabels, intervalSeconds: Int, orderLabel: String?): String =
        listOfNotNull(labels.interval(intervalSeconds), orderLabel).joinToString(" \u00b7 ")

    /** Unreadable always wins; then the two quiet modes, so a forgotten test toggle is never a mystery. */
    fun savedPictures(
        labels: SummaryLabels,
        state: SavedPicturesState,
        playingWithoutSaving: Boolean,
        savingPaused: Boolean,
        formatSize: (Long) -> String
    ): String = when {
        state is SavedPicturesState.Unreadable -> labels.unreadable
        playingWithoutSaving -> labels.playingWithoutSaving
        savingPaused -> labels.savingPaused
        state is SavedPicturesState.Loading -> ""
        state is SavedPicturesState.Loaded ->
            if (state.files == 0) labels.noSavedPictures else labels.picturesAndSize(state.files, formatSize(state.bytes))
        else -> ""
    }
}
