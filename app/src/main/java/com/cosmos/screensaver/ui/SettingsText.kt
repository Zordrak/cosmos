package com.cosmos.screensaver.ui

import android.content.Context
import com.cosmos.screensaver.R
import com.cosmos.screensaver.settings.CaptionMode
import com.cosmos.screensaver.settings.CaptionSize
import com.cosmos.screensaver.settings.OrderMode
import com.cosmos.screensaver.settings.CollectionPreset
import com.cosmos.screensaver.settings.DisplayMode
import com.cosmos.screensaver.settings.DownloadMode
import com.cosmos.screensaver.settings.SourceChoice
import com.cosmos.screensaver.settings.IdleReading
import com.cosmos.screensaver.settings.IdleTime
import com.cosmos.screensaver.settings.QualityPreference
import com.cosmos.screensaver.settings.SummaryLabels

/** Every user-visible label for a setting value, in one place so pages and summaries cannot disagree. */
internal class SettingsText(private val context: Context) : SummaryLabels {
    private fun s(id: Int) = context.getString(id)

    override fun collection(preset: CollectionPreset) = s(
        when (preset) {
            CollectionPreset.EVERYTHING -> R.string.collection_everything
            CollectionPreset.DEEP_SPACE -> R.string.collection_deep_space
            CollectionPreset.PICTURE_OF_THE_DAY -> R.string.collection_potd
            CollectionPreset.NASA_ONLY -> R.string.collection_nasa
            CollectionPreset.CUSTOM -> R.string.collection_custom
        }
    )

    fun collectionSummary(preset: CollectionPreset) = s(
        when (preset) {
            CollectionPreset.EVERYTHING -> R.string.collection_everything_summary
            CollectionPreset.DEEP_SPACE -> R.string.collection_deep_space_summary
            CollectionPreset.PICTURE_OF_THE_DAY -> R.string.collection_potd_summary
            CollectionPreset.NASA_ONLY -> R.string.collection_nasa_summary
            CollectionPreset.CUSTOM -> R.string.collection_custom_summary
        }
    )

    override fun quality(quality: QualityPreference) = s(
        when (quality) {
            QualityPreference.UHD_ONLY -> R.string.quality_uhd
            QualityPreference.UHD_PREFERRED -> R.string.quality_uhd_preferred
            QualityPreference.FHD_OR_BETTER -> R.string.quality_fhd
            QualityPreference.HD_OR_BETTER -> R.string.quality_hd
            QualityPreference.SD_OR_BETTER -> R.string.quality_sd
            QualityPreference.ANY -> R.string.quality_any
        }
    )

    fun qualitySummary(quality: QualityPreference) = s(
        when (quality) {
            QualityPreference.UHD_ONLY -> R.string.quality_uhd_summary
            QualityPreference.UHD_PREFERRED -> R.string.quality_uhd_preferred_summary
            QualityPreference.FHD_OR_BETTER -> R.string.quality_fhd_summary
            QualityPreference.HD_OR_BETTER -> R.string.quality_hd_summary
            QualityPreference.SD_OR_BETTER -> R.string.quality_sd_summary
            QualityPreference.ANY -> R.string.quality_any_summary
        }
    )

    fun displayMode(mode: DisplayMode) = s(
        when (mode) {
            DisplayMode.FILL -> R.string.fit_fill
            DisplayMode.FIT -> R.string.fit_fit
            DisplayMode.STRETCH -> R.string.fit_stretch
            DisplayMode.AUTO -> R.string.fit_auto
            DisplayMode.ORIGINAL -> R.string.fit_original
        }
    )

    fun displayModeSummary(mode: DisplayMode) = s(
        when (mode) {
            DisplayMode.FILL -> R.string.fit_fill_summary
            DisplayMode.FIT -> R.string.fit_fit_summary
            DisplayMode.STRETCH -> R.string.fit_stretch_summary
            DisplayMode.AUTO -> R.string.fit_auto_summary
            DisplayMode.ORIGINAL -> R.string.fit_original_summary
        }
    )

    fun orderMode(mode: OrderMode) = s(
        when (mode) {
            OrderMode.FAIR -> R.string.order_fair
            OrderMode.RANDOM -> R.string.order_random
            OrderMode.SEQUENTIAL -> R.string.order_sequential
        }
    )

    fun orderModeSummary(mode: OrderMode) = s(
        when (mode) {
            OrderMode.FAIR -> R.string.order_fair_summary
            OrderMode.RANDOM -> R.string.order_random_summary
            OrderMode.SEQUENTIAL -> R.string.order_sequential_summary
        }
    )

    fun captionMode(mode: CaptionMode) = s(
        when (mode) {
            CaptionMode.SUBTLE -> R.string.credits_subtle
            CaptionMode.DETAILED -> R.string.credits_detailed
            CaptionMode.OFF -> R.string.credits_off
        }
    )

    fun captionModeSummary(mode: CaptionMode) = s(
        when (mode) {
            CaptionMode.SUBTLE -> R.string.credits_subtle_summary
            CaptionMode.DETAILED -> R.string.credits_detailed_summary
            CaptionMode.OFF -> R.string.credits_off_summary
        }
    )

    fun captionSize(size: CaptionSize) = s(
        when (size) {
            CaptionSize.SMALL -> R.string.credit_size_small
            CaptionSize.STANDARD -> R.string.credit_size_standard
            CaptionSize.LARGE -> R.string.credit_size_large
            CaptionSize.EXTRA_LARGE -> R.string.credit_size_extra_large
        }
    )

    /** "15 seconds", "1 minute": the value shown on the Time per picture row and in its picker. */
    fun intervalValue(seconds: Int): String =
        if (seconds < 60) {
            context.resources.getQuantityString(R.plurals.seconds, seconds, seconds)
        } else {
            context.resources.getQuantityString(R.plurals.minutes, seconds / 60, seconds / 60)
        }

    /** The TV's inactivity timeout as the user reads it: "10 minutes", or an odd system value exactly as it is. */
    fun idleValue(reading: IdleReading): String = when (reading) {
        is IdleReading.Known -> {
            val whole = IdleTime.wholeMinutes(reading.millis)
            if (whole != null) {
                context.resources.getQuantityString(R.plurals.minutes, whole, whole)
            } else {
                val (minutes, seconds) = IdleTime.minutesAndSeconds(reading.millis)
                if (minutes == 0) {
                    context.resources.getQuantityString(R.plurals.seconds, seconds, seconds)
                } else {
                    context.getString(R.string.idle_minutes_seconds, minutes, seconds)
                }
            }
        }
        is IdleReading.Unknown -> s(R.string.idle_value_unknown)
        IdleReading.Unsupported -> s(R.string.idle_value_unsupported)
    }

    /** The Home value for "When it starts", or null when the TV's value is not known. */
    fun idleHome(reading: IdleReading): String? =
        (reading as? IdleReading.Known)?.let { context.getString(R.string.idle_home_value, idleValue(it)) }

    /** The label of one idle-time choice, e.g. "10 minutes". */
    fun idleChoiceLabel(minutes: Int): String = context.resources.getQuantityString(R.plurals.minutes, minutes, minutes)

    /** "Every minute", "Every 30 seconds": the Home summary form. */
    override fun interval(seconds: Int): String = when {
        seconds == 60 -> s(R.string.summary_every_minute)
        seconds < 60 -> context.resources.getQuantityString(R.plurals.every_seconds, seconds, seconds)
        else -> context.resources.getQuantityString(R.plurals.every_minutes, seconds / 60, seconds / 60)
    }

    override fun picturesAndSize(count: Int, size: String): String =
        context.resources.getQuantityString(R.plurals.summary_saved_pictures, count, count, size)

    override val noSavedPictures: String get() = s(R.string.summary_no_saved_pictures)
    override val unreadable: String get() = s(R.string.summary_unreadable)
    override val playingWithoutSaving: String get() = s(R.string.summary_playing_without_saving)
    override val savingPaused: String get() = s(R.string.summary_saving_paused)

    fun downloadMode(mode: DownloadMode) = s(
        when (mode) {
            DownloadMode.ALWAYS -> R.string.download_always
            DownloadMode.UNMETERED_ONLY -> R.string.download_unmetered
            DownloadMode.PAUSED -> R.string.download_paused
        }
    )

    fun downloadModeSummary(mode: DownloadMode) = s(
        when (mode) {
            DownloadMode.ALWAYS -> R.string.download_always_summary
            DownloadMode.UNMETERED_ONLY -> R.string.download_unmetered_summary
            DownloadMode.PAUSED -> R.string.download_paused_summary
        }
    )

    fun sourceChoice(choice: SourceChoice) = s(
        when (choice) {
            SourceChoice.OFF -> R.string.choice_off
            SourceChoice.LESS -> R.string.choice_less
            SourceChoice.NORMAL -> R.string.choice_normal
            SourceChoice.MORE -> R.string.choice_more
        }
    )

    fun sourceChoiceDescription(choice: SourceChoice) = s(
        when (choice) {
            SourceChoice.OFF -> R.string.choice_off_description
            SourceChoice.LESS -> R.string.choice_less_description
            SourceChoice.NORMAL -> R.string.choice_normal_description
            SourceChoice.MORE -> R.string.choice_more_description
        }
    )
}
