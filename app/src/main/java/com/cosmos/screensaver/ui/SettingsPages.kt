package com.cosmos.screensaver.ui

import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.cosmos.screensaver.R
import com.cosmos.screensaver.rights.AssetProvider
import com.cosmos.screensaver.settings.CaptionMode
import com.cosmos.screensaver.settings.CaptionSize
import com.cosmos.screensaver.settings.OrderMode
import com.cosmos.screensaver.settings.COLLECTION_CHOICES
import com.cosmos.screensaver.settings.COLLECTION_CYCLE
import com.cosmos.screensaver.settings.CollectionPreset
import com.cosmos.screensaver.settings.CosmosSettings
import com.cosmos.screensaver.settings.DisplayMode
import com.cosmos.screensaver.settings.HomeSummaries
import com.cosmos.screensaver.settings.LicenceMode
import com.cosmos.screensaver.settings.QualityPreference
import com.cosmos.screensaver.settings.ScreensaverSelection
import com.cosmos.screensaver.settings.applyCollection
import com.cosmos.screensaver.settings.qualityChoices
import com.cosmos.screensaver.source.Sources
import com.cosmos.screensaver.sync.RefreshCoordinator

/** The settings pages. Home is the hub; each other page is one decision area reached from it. */
internal enum class Page { HOME, WHAT_TO_SHOW, SOURCES, SLIDESHOW, SAVED_PICTURES, WHEN_IT_STARTS, MORE, ABOUT }

/** What a page needs from the screen hosting it. */
internal interface PageEnv {
    val activity: Activity
    val settings: CosmosSettings
    val text: SettingsText
    val snapshot: LibrarySnapshot
    fun openPage(page: Page)

    /** A viewing choice changed; [refresh] also asks the sources for pictures matching it. */
    fun viewingChanged(refresh: Boolean)
    fun lookForNewPictures()
    fun startPreview()
    fun openTvScreensaverSettings()
    fun screensaverSelection(): ScreensaverSelection
}

/** The focusable rows of a page and a hook that refreshes its left pane. */
internal class PageContent(val rows: List<SettingsRow>, val updateLeft: () -> Unit = {})

internal class SettingsPages(private val env: PageEnv) {
    private val activity get() = env.activity
    private val settings get() = env.settings
    private val text get() = env.text

    fun build(page: Page, left: LinearLayout): PageContent = when (page) {
        Page.HOME -> home(left)
        Page.WHAT_TO_SHOW -> whatToShow(left)
        Page.SOURCES -> sources(left)
        Page.SLIDESHOW -> slideshow(left)
        Page.SAVED_PICTURES -> savedPictures(left)
        Page.WHEN_IT_STARTS -> whenItStarts(left)
        Page.MORE -> more(left)
        Page.ABOUT -> about(left)
    }

    private fun s(id: Int) = activity.getString(id)

    private fun home(left: LinearLayout): PageContent {
        val ui = SettingsUi(activity)
        left.addView(ui.text(s(R.string.app_name), SettingsType.HOME_TITLE, Color.WHITE, bold = true))
        left.addView(ui.text(s(R.string.home_tagline), SettingsType.LEFT_PANE, SettingsColors.SECONDARY).apply {
            setPadding(0, ui.dp(8), 0, ui.dp(28))
        })
        val updateStatus = statusViews(ui, left)
        left.addView(View(activity), LinearLayout.LayoutParams(1, 0, 1f))
        left.addView(ui.text(s(R.string.home_privacy), 14f, SettingsColors.MUTED))

        ui.action("preview", s(R.string.home_preview), { s(R.string.home_preview_summary) }) { env.startPreview() }
        ui.action(
            "what", s(R.string.home_what_to_show), { s(R.string.home_what_to_show_summary) },
            value = { HomeSummaries.whatToShow(text, settings.collection, settings.quality) }
        ) { env.openPage(Page.WHAT_TO_SHOW) }
        ui.action(
            "slideshow", s(R.string.home_slideshow), { s(R.string.home_slideshow_summary) },
            value = { HomeSummaries.slideshow(text, settings.intervalSeconds, orderLabel = text.orderMode(settings.orderMode)) }
        ) { env.openPage(Page.SLIDESHOW) }
        ui.action(
            "saved", s(R.string.home_saved), { s(R.string.home_saved_summary) },
            value = {
                HomeSummaries.savedPictures(text, env.snapshot.saved, settings.streamOnly) {
                    Formatter.formatShortFileSize(activity, it)
                }.ifEmpty { null }
            }
        ) { env.openPage(Page.SAVED_PICTURES) }
        ui.action("when", s(R.string.home_when), { s(R.string.home_when_summary) }) { env.openPage(Page.WHEN_IT_STARTS) }
        ui.action("more", s(R.string.home_more), { s(R.string.home_more_summary) }) { env.openPage(Page.MORE) }
        return PageContent(ui.rows, updateStatus)
    }

    /** Count, storage and last-checked lines, shared by Home and Saved pictures. Returns their updater. */
    private fun statusViews(ui: SettingsUi, left: LinearLayout): () -> Unit {
        val status = ui.text("", 20f, SettingsColors.ACCENT, bold = true).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val storage = ui.text("", 15f, SettingsColors.SECONDARY).apply { setPadding(0, ui.dp(6), 0, 0) }
        val lastChecked = ui.text("", 15f, SettingsColors.SECONDARY).apply { setPadding(0, ui.dp(8), 0, 0) }
        left.addView(status)
        left.addView(storage)
        left.addView(lastChecked)
        return {
            val snap = env.snapshot
            val noSources = settings.enabledProviders.isEmpty()
            when {
                noSources -> status.set(s(R.string.home_no_sources), SettingsColors.WARNING)
                snap.unreadable -> status.set(s(R.string.home_unreadable), SettingsColors.WARNING)
                !snap.loaded -> status.set("", SettingsColors.ACCENT)
                else -> status.set(
                    activity.resources.getQuantityString(R.plurals.home_image_count, snap.eligible, snap.eligible),
                    SettingsColors.ACCENT
                )
            }
            if (snap.loaded && !snap.unreadable) {
                val size = Formatter.formatShortFileSize(activity, snap.bytes)
                if (snap.lowOnSpace) {
                    storage.set(activity.getString(R.string.home_low_space, size), SettingsColors.WARNING)
                } else {
                    storage.set(activity.getString(R.string.home_storage, size), SettingsColors.SECONDARY)
                }
            } else {
                storage.set("", SettingsColors.SECONDARY)
            }
            lastChecked.text = when {
                RefreshCoordinator.isRunning -> s(R.string.home_refreshing)
                snap.lastAttemptAtEpochMillis > 0 -> activity.getString(
                    R.string.home_last_refresh,
                    DateUtils.getRelativeTimeSpanString(snap.lastAttemptAtEpochMillis),
                    snap.lastSummary
                )
                else -> ""
            }
        }
    }

    private fun TextView.set(value: String, color: Int) {
        if (text.toString() != value) text = value
        setTextColor(color)
    }

    private fun subPageLeft(ui: SettingsUi, left: LinearLayout, title: Int, body: Int?) {
        left.addView(ui.text(s(title), SettingsType.PAGE_TITLE + 6f, Color.WHITE, bold = true))
        if (body != null) {
            left.addView(ui.text(s(body), SettingsType.LEFT_PANE, SettingsColors.SECONDARY).apply {
                setPadding(0, ui.dp(12), 0, 0)
            })
        }
    }

    private fun whatToShow(left: LinearLayout): PageContent {
        val ui = SettingsUi(activity)
        subPageLeft(ui, left, R.string.home_what_to_show, null)
        val matching = ui.text("", SettingsType.LEFT_PANE, SettingsColors.ACCENT).apply {
            setPadding(0, ui.dp(16), 0, 0)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        left.addView(ui.text(s(R.string.what_left_title), 14f, SettingsColors.MUTED).apply { setPadding(0, ui.dp(24), 0, 0) })
        left.addView(matching)

        ui.choice(
            "collection", s(R.string.row_collection),
            options = {
                COLLECTION_CHOICES.map {
                    PickerOption(
                        it, text.collection(it), text.collectionSummary(it),
                        recommended = it == CosmosSettings.DEFAULT_COLLECTION
                    )
                }
            },
            current = { settings.collection },
            onChosen = {
                if (applyCollection(settings, it)) env.openPage(Page.SOURCES) else env.viewingChanged(refresh = true)
            },
            summary = text::collectionSummary,
            valueLabel = text::collection,
            cycle = { COLLECTION_CYCLE }
        )
        ui.action(
            "sources", s(R.string.row_sources), { s(R.string.row_sources_summary) },
            value = { activity.getString(R.string.summary_sources_count, settings.enabledProviders.size, AssetProvider.entries.size) }
        ) { env.openPage(Page.SOURCES) }
        ui.choice(
            "quality", s(R.string.row_quality),
            options = {
                qualityChoices(settings.quality).map {
                    PickerOption(
                        it, text.quality(it), text.qualitySummary(it),
                        recommended = it == CosmosSettings.DEFAULT_QUALITY
                    )
                }
            },
            current = { settings.quality },
            onChosen = {
                settings.quality = it
                env.viewingChanged(refresh = true)
            },
            summary = text::qualitySummary,
            valueLabel = text::quality
        )
        ui.switch(
            "licence", s(R.string.row_licence),
            summary = { s(if (it) R.string.licence_on_summary else R.string.licence_off_summary) },
            get = { settings.licenceMode == LicenceMode.OPEN_LICENCE_ONLY },
            set = {
                settings.licenceMode = if (it) LicenceMode.OPEN_LICENCE_ONLY else LicenceMode.ANY_AVAILABLE
                env.viewingChanged(refresh = true)
            }
        )
        return PageContent(ui.rows) {
            val snap = env.snapshot
            matching.text = when {
                !snap.loaded || snap.unreadable -> ""
                snap.eligible == 0 -> s(R.string.what_left_none)
                else -> activity.resources.getQuantityString(R.plurals.what_left_count, snap.eligible, snap.eligible)
            }
        }
    }

    private fun sources(left: LinearLayout): PageContent {
        val ui = SettingsUi(activity)
        subPageLeft(ui, left, R.string.sources_left_title, R.string.sources_left_body)
        AssetProvider.entries.forEach { provider ->
            ui.switch(
                "source_${provider.name}", Sources.label(provider),
                summary = { if (it) sourceAddress(provider) else s(R.string.source_off_summary) },
                get = { settings.isEnabled(provider) },
                set = {
                    settings.setEnabled(provider, it)
                    env.viewingChanged(refresh = true)
                }
            )
        }
        return PageContent(ui.rows)
    }

    private fun sourceAddress(provider: AssetProvider) = when (provider) {
        AssetProvider.NASA -> "images.nasa.gov"
        AssetProvider.APOD -> "science.nasa.gov \u00b7 Astronomy Picture of the Day"
        AssetProvider.ESA_HUBBLE -> "esahubble.org"
        AssetProvider.ESA_WEBB -> "esawebb.org"
        AssetProvider.ESO -> "eso.org"
        AssetProvider.NOIRLAB -> "noirlab.edu"
    }

    private fun slideshow(left: LinearLayout): PageContent {
        val ui = SettingsUi(activity)
        left.addView(ui.text(s(R.string.home_slideshow), SettingsType.PAGE_TITLE + 6f, Color.WHITE, bold = true))
        val preview = PreviewPane(activity)
        left.addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ui.dp(16)
        })
        val fitLine = ui.text("", SettingsType.LEFT_PANE, SettingsColors.ACCENT).apply { setPadding(0, ui.dp(12), 0, 0) }
        left.addView(fitLine)
        left.addView(ui.text(s(R.string.slideshow_left_body), SettingsType.LEFT_PANE, SettingsColors.SECONDARY).apply {
            setPadding(0, ui.dp(8), 0, 0)
        })
        val updatePreview = {
            preview.update(settings.displayMode, settings.captionMode, settings.captionSize.scale)
            val summary = text.displayModeSummary(settings.displayMode)
            if (fitLine.text.toString() != summary) fitLine.text = summary
        }
        ui.onChanged = updatePreview
        ui.choice(
            "interval", s(R.string.row_interval),
            options = {
                CosmosSettings.INTERVAL_CHOICES_SECONDS.map {
                    PickerOption(it, text.intervalValue(it), recommended = it == CosmosSettings.DEFAULT_INTERVAL_SECONDS)
                }
            },
            current = { settings.intervalSeconds },
            onChosen = { settings.intervalSeconds = it },
            summary = { s(R.string.row_interval_summary) },
            valueLabel = text::intervalValue
        )
        ui.choice(
            "order", s(R.string.row_order),
            options = {
                OrderMode.entries.map {
                    PickerOption(
                        it, text.orderMode(it), text.orderModeSummary(it),
                        recommended = it == CosmosSettings.DEFAULT_ORDER_MODE
                    )
                }
            },
            current = { settings.orderMode },
            onChosen = { settings.orderMode = it },
            summary = text::orderModeSummary,
            valueLabel = text::orderMode
        )
        ui.choice(
            "fit", s(R.string.row_fit),
            options = {
                DisplayMode.entries.map {
                    PickerOption(
                        it, text.displayMode(it), text.displayModeSummary(it),
                        recommended = it == CosmosSettings.DEFAULT_DISPLAY_MODE
                    )
                }
            },
            current = { settings.displayMode },
            onChosen = { settings.displayMode = it },
            summary = text::displayModeSummary,
            valueLabel = text::displayMode
        )
        ui.choice(
            "credits", s(R.string.row_credits),
            options = {
                CaptionMode.entries.map {
                    PickerOption(
                        it, text.captionMode(it), text.captionModeSummary(it),
                        recommended = it == CosmosSettings.DEFAULT_CAPTION_MODE
                    )
                }
            },
            current = { settings.captionMode },
            onChosen = { settings.captionMode = it },
            summary = text::captionModeSummary,
            valueLabel = text::captionMode
        )
        ui.choice(
            "credit_size", s(R.string.row_credit_size),
            options = {
                CaptionSize.entries.map {
                    PickerOption(it, text.captionSize(it), recommended = it == CosmosSettings.DEFAULT_CAPTION_SIZE)
                }
            },
            current = { settings.captionSize },
            onChosen = { settings.captionSize = it },
            summary = { s(R.string.row_credit_size_summary) },
            valueLabel = text::captionSize,
            enabledReason = { if (settings.captionMode == CaptionMode.OFF) s(R.string.credit_size_off_reason) else null }
        )
        ui.action("fullscreen", s(R.string.row_fullscreen), { s(R.string.row_fullscreen_summary) }) { env.startPreview() }
        return PageContent(ui.rows, updatePreview)
    }

    private fun savedPictures(left: LinearLayout): PageContent {
        val ui = SettingsUi(activity)
        subPageLeft(ui, left, R.string.saved_left_title, null)
        val updateStatus = statusViews(ui, left)
        ui.action(
            "refresh", s(R.string.row_refresh),
            { s(if (RefreshCoordinator.isRunning) R.string.home_refreshing else R.string.row_refresh_summary) }
        ) {
            if (!RefreshCoordinator.isRunning) env.lookForNewPictures()
        }
        return PageContent(ui.rows, updateStatus)
    }

    private fun whenItStarts(left: LinearLayout): PageContent {
        val ui = SettingsUi(activity)
        subPageLeft(ui, left, R.string.home_when, R.string.when_left_body)
        ui.action(
            "tv_screensaver", s(R.string.row_tv_screensaver),
            summary = {
                s(
                    when (env.screensaverSelection()) {
                        ScreensaverSelection.COSMOS -> R.string.tv_screensaver_cosmos_summary
                        ScreensaverSelection.OTHER -> R.string.tv_screensaver_other_summary
                        ScreensaverSelection.NONE -> R.string.tv_screensaver_none_summary
                        ScreensaverSelection.UNKNOWN -> R.string.tv_screensaver_unknown_summary
                    }
                )
            },
            value = {
                s(
                    when (env.screensaverSelection()) {
                        ScreensaverSelection.COSMOS -> R.string.tv_screensaver_cosmos
                        ScreensaverSelection.OTHER -> R.string.tv_screensaver_other
                        ScreensaverSelection.NONE -> R.string.tv_screensaver_none
                        ScreensaverSelection.UNKNOWN -> R.string.tv_screensaver_unknown
                    }
                )
            }
        ) { env.openTvScreensaverSettings() }
        return PageContent(ui.rows)
    }

    private fun more(left: LinearLayout): PageContent {
        val ui = SettingsUi(activity)
        subPageLeft(ui, left, R.string.home_more, R.string.more_left_body)
        ui.switch(
            "stream_only", s(R.string.row_stream_only),
            summary = { s(if (it) R.string.stream_only_on_summary else R.string.stream_only_off_summary) },
            get = { settings.streamOnly },
            set = { settings.streamOnly = it }
        )
        // Disabled until a protected, undoable command exists; it must never wipe the library directly.
        ui.action(
            "clear", s(R.string.row_clear), { s(R.string.row_clear_summary) },
            enabledReason = { s(R.string.row_clear_unavailable) }
        ) {}
        ui.action("reset", s(R.string.row_reset), { s(R.string.row_reset_summary) }) {
            ConfirmDialog.show(
                activity, s(R.string.reset_confirm_title), s(R.string.reset_confirm_message), s(R.string.reset_confirm_button)
            ) {
                settings.resetViewingSettings()
                env.viewingChanged(refresh = true)
            }
        }
        ui.action("about", s(R.string.row_about), { s(R.string.row_about_summary) }) { env.openPage(Page.ABOUT) }
        return PageContent(ui.rows)
    }

    private fun about(left: LinearLayout): PageContent {
        val ui = SettingsUi(activity)
        subPageLeft(ui, left, R.string.row_about, null)
        ui.action("version", s(R.string.about_row_version), { versionName() }) {}
        ui.action("privacy", s(R.string.about_row_privacy), { s(R.string.about_privacy) }) {}
        ui.action("licences", s(R.string.about_row_pictures), { s(R.string.about_licence) }) {}
        return PageContent(ui.rows)
    }

    private fun versionName(): String = try {
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName.orEmpty()
    } catch (error: PackageManager.NameNotFoundException) {
        ""
    }
}
