package com.cosmos.screensaver

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.cosmos.screensaver.screensaver.PreviewActivity
import com.cosmos.screensaver.settings.CaptionMode
import com.cosmos.screensaver.settings.CosmosSettings
import com.cosmos.screensaver.settings.DisplayMode
import com.cosmos.screensaver.settings.LicenceMode
import com.cosmos.screensaver.settings.QualityPreference
import com.cosmos.screensaver.settings.cycle
import com.cosmos.screensaver.source.DjangoplicitySite
import com.cosmos.screensaver.sync.RefreshCoordinator

/** TV settings screen: a status pane on the left and grouped, remote-friendly rows on the right. */
class MainActivity : Activity() {
    private lateinit var settings: CosmosSettings
    private lateinit var status: TextView
    private lateinit var lastRefresh: TextView
    private val rows = mutableListOf<Row>()
    private val handler = Handler(Looper.getMainLooper())
    private var clearArmed = false
    private val statusPoller = object : Runnable {
        override fun run() {
            updateAll()
            if (RefreshCoordinator.isRunning) handler.postDelayed(this, 1_500L)
        }
    }

    /** A focusable settings row whose texts are recomputed by [update]. */
    private inner class Row(
        val view: LinearLayout,
        val summary: TextView,
        val value: TextView?,
        val update: () -> Unit
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = CosmosSettings(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BACKGROUND)
            setPadding(dp(56), dp(40), dp(40), dp(32))
        }

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), dp(40), 0)
        }
        info.addView(text(getString(R.string.app_name), 40f, Color.WHITE, bold = true))
        info.addView(text(getString(R.string.home_tagline), 17f, SECONDARY).apply { setPadding(0, dp(8), 0, dp(28)) })
        status = text("", 20f, ACCENT, bold = true).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        info.addView(status)
        lastRefresh = text("", 14f, SECONDARY).apply { setPadding(0, dp(8), 0, 0) }
        info.addView(lastRefresh)
        info.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        info.addView(text(getString(R.string.home_privacy), 13f, MUTED))
        root.addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.38f))

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(16), dp(24))
        }
        buildRows(list)
        root.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            addView(list)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.62f))

        setContentView(root)
        rows.first().view.requestFocus()
    }

    private fun buildRows(list: LinearLayout) {
        list.addView(header(R.string.section_screensaver))
        list.addView(action(R.string.row_preview, { getString(R.string.row_preview_summary) }) {
            startActivity(Intent(this, PreviewActivity::class.java))
        })
        list.addView(action(R.string.row_set_screensaver, { getString(R.string.row_set_screensaver_summary) }) {
            launchDreamSettings(
                launch = { startActivity(Intent(Settings.ACTION_DREAM_SETTINGS)) },
                onUnavailable = { error ->
                    Log.w(TAG, "Unable to open system screensaver settings", error)
                    lastRefresh.setText(R.string.home_settings_unavailable)
                }
            )
        })

        list.addView(header(R.string.section_display))
        list.addView(choice(
            R.string.row_fit, DisplayMode.entries, { settings.displayMode }, { settings.displayMode = it },
            label = ::displayModeLabel, summary = ::displayModeSummary
        ))
        list.addView(choice(
            R.string.row_interval, CosmosSettings.INTERVAL_CHOICES_SECONDS,
            { settings.intervalSeconds }, { settings.intervalSeconds = it },
            label = ::intervalLabel, summary = { getString(R.string.row_interval_summary) }
        ))
        list.addView(choice(
            R.string.row_credits, CaptionMode.entries, { settings.captionMode }, { settings.captionMode = it },
            label = ::captionLabel, summary = ::captionSummary
        ))

        list.addView(header(R.string.section_images))
        list.addView(choice(
            R.string.row_quality, QualityPreference.entries, { settings.quality }, { settings.quality = it },
            label = ::qualityLabel, summary = ::qualitySummary, refreshes = true
        ))
        list.addView(choice(
            R.string.row_licence, LicenceMode.entries, { settings.licenceMode }, { settings.licenceMode = it },
            label = ::licenceLabel, summary = ::licenceSummary, refreshes = true
        ))

        list.addView(header(R.string.section_sources))
        DjangoplicitySite.entries.forEach { site ->
            list.addView(choice(
                site.label, listOf(true, false), { settings.isEnabled(site) }, { settings.setEnabled(site, it) },
                label = { getString(if (it) R.string.value_on else R.string.value_off) },
                summary = { site.domain }, refreshes = true
            ))
        }

        list.addView(header(R.string.section_library))
        list.addView(action(R.string.row_refresh, ::refreshSummary) {
            if (RefreshCoordinator.isRunning) return@action
            RefreshCoordinator.requestRefresh(this, force = true) { updateAll() }
            startPolling()
        })
        list.addView(action(R.string.row_clear, {
            getString(if (clearArmed) R.string.row_clear_confirm else R.string.row_clear_summary)
        }) {
            if (RefreshCoordinator.isRunning) return@action
            if (clearArmed) RefreshCoordinator.store(this).clear()
            clearArmed = !clearArmed
            updateAll()
        })
    }

    override fun onResume() {
        super.onResume()
        updateAll()
        RefreshCoordinator.requestRefresh(this) { updateAll() }
        startPolling()
    }

    override fun onPause() {
        handler.removeCallbacks(statusPoller)
        super.onPause()
    }

    private fun startPolling() {
        handler.removeCallbacks(statusPoller)
        handler.post(statusPoller)
    }

    private fun updateAll() {
        rows.forEach { it.update() }
        val catalogue = RefreshCoordinator.store(this).load()
        val enabled = settings.enabledSites.map { it.provider }.toSet()
        val count = settings.selection.eligible(catalogue.entries.filter { it.provider in enabled }).size
        status.text = resources.getQuantityString(R.plurals.home_image_count, count, count)
        lastRefresh.text = when {
            settings.enabledSites.isEmpty() -> getString(R.string.home_no_sources)
            RefreshCoordinator.isRunning -> getString(R.string.home_refreshing)
            catalogue.lastAttemptAtEpochMillis > 0 -> getString(
                R.string.home_last_refresh,
                DateUtils.getRelativeTimeSpanString(catalogue.lastAttemptAtEpochMillis),
                catalogue.lastSummary
            )
            else -> ""
        }
    }

    private fun refreshSummary(): String =
        getString(if (RefreshCoordinator.isRunning) R.string.home_refreshing else R.string.row_refresh_summary)

    private fun header(title: Int) = text(getString(title).uppercase(), 13f, ACCENT, bold = true).apply {
        letterSpacing = 0.12f
        setPadding(dp(16), dp(24), 0, dp(6))
    }

    private fun action(title: Int, summary: () -> String, onSelect: () -> Unit): View =
        row(getString(title), summary, value = null, onSelect = onSelect, onStep = null)

    private fun <T> choice(
        title: Int,
        values: List<T>,
        get: () -> T,
        set: (T) -> Unit,
        label: (T) -> String,
        summary: (T) -> String,
        refreshes: Boolean = false
    ): View = choice(getString(title), values, get, set, label, summary, refreshes)

    private fun <T> choice(
        title: String,
        values: List<T>,
        get: () -> T,
        set: (T) -> Unit,
        label: (T) -> String,
        summary: (T) -> String,
        refreshes: Boolean = false
    ): View {
        val step = { direction: Int ->
            set(values.cycle(get(), direction))
            if (refreshes) {
                RefreshCoordinator.requestRefresh(this, force = true) { updateAll() }
                startPolling()
            }
            updateAll()
        }
        return row(title, { summary(get()) }, value = { label(get()) }, onSelect = { step(1) }, onStep = step)
    }

    private fun row(
        title: String,
        summary: () -> String,
        value: (() -> String)?,
        onSelect: () -> Unit,
        onStep: ((Int) -> Unit)?
    ): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            background = rowBackground()
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(title, 18f, Color.WHITE))
        val summaryView = text("", 13f, SECONDARY).apply { setPadding(0, dp(2), 0, 0) }
        texts.addView(summaryView)
        container.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val valueView = value?.let {
            text("", 17f, ACCENT, bold = true).apply {
                gravity = Gravity.END
                setPadding(dp(16), 0, 0, 0)
            }.also(container::addView)
        }
        container.setOnClickListener { onSelect() }
        if (onStep != null) {
            container.setOnKeyListener { _, keyCode, event ->
                val direction = when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                    KeyEvent.KEYCODE_DPAD_LEFT -> -1
                    else -> return@setOnKeyListener false
                }
                if (event.action == KeyEvent.ACTION_DOWN) onStep(direction)
                true
            }
        }
        container.setOnFocusChangeListener { _, focused ->
            if (!focused && clearArmed) {
                clearArmed = false
                updateAll()
            }
            valueView?.text = value?.let { decorate(it(), focused) }
        }
        val row = Row(container, summaryView, valueView) {
            summaryView.text = summary()
            valueView?.text = value?.let { decorate(it(), container.isFocused) }
        }
        rows += row
        row.update()
        return container.also {
            it.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(2) }
        }
    }

    /** Arrows hint that left and right change the focused value. */
    private fun decorate(value: String, focused: Boolean) = if (focused) "\u2039  $value  \u203a" else value

    private fun rowBackground() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
            setColor(FOCUSED)
            cornerRadius = dp(10).toFloat()
        })
        addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
    }

    private fun displayModeLabel(mode: DisplayMode) = getString(
        when (mode) {
            DisplayMode.FILL -> R.string.fit_fill
            DisplayMode.FIT -> R.string.fit_fit
            DisplayMode.AUTO -> R.string.fit_auto
            DisplayMode.ORIGINAL -> R.string.fit_original
            DisplayMode.STRETCH -> R.string.fit_stretch
        }
    )

    private fun displayModeSummary(mode: DisplayMode) = getString(
        when (mode) {
            DisplayMode.FILL -> R.string.fit_fill_summary
            DisplayMode.FIT -> R.string.fit_fit_summary
            DisplayMode.AUTO -> R.string.fit_auto_summary
            DisplayMode.ORIGINAL -> R.string.fit_original_summary
            DisplayMode.STRETCH -> R.string.fit_stretch_summary
        }
    )

    private fun captionLabel(mode: CaptionMode) = getString(
        when (mode) {
            CaptionMode.SUBTLE -> R.string.credits_subtle
            CaptionMode.DETAILED -> R.string.credits_detailed
            CaptionMode.OFF -> R.string.value_off
        }
    )

    private fun captionSummary(mode: CaptionMode) = getString(
        when (mode) {
            CaptionMode.SUBTLE -> R.string.credits_subtle_summary
            CaptionMode.DETAILED -> R.string.credits_detailed_summary
            CaptionMode.OFF -> R.string.credits_off_summary
        }
    )

    private fun qualityLabel(quality: QualityPreference) = getString(
        when (quality) {
            QualityPreference.UHD_ONLY -> R.string.quality_uhd
            QualityPreference.UHD_PREFERRED -> R.string.quality_uhd_preferred
            QualityPreference.FHD_OR_BETTER -> R.string.quality_fhd
            QualityPreference.HD_OR_BETTER -> R.string.quality_hd
            QualityPreference.SD_OR_BETTER -> R.string.quality_sd
            QualityPreference.ANY -> R.string.quality_any
        }
    )

    private fun qualitySummary(quality: QualityPreference) = getString(
        when (quality) {
            QualityPreference.UHD_ONLY -> R.string.quality_uhd_summary
            QualityPreference.UHD_PREFERRED -> R.string.quality_uhd_preferred_summary
            QualityPreference.ANY -> R.string.quality_any_summary
            else -> R.string.quality_minimum_summary
        }
    )

    private fun licenceLabel(mode: LicenceMode) = getString(
        when (mode) {
            LicenceMode.ANY_AVAILABLE -> R.string.licence_any
            LicenceMode.OPEN_LICENCE_ONLY -> R.string.licence_open
        }
    )

    private fun licenceSummary(mode: LicenceMode) = getString(
        when (mode) {
            LicenceMode.ANY_AVAILABLE -> R.string.licence_any_summary
            LicenceMode.OPEN_LICENCE_ONLY -> R.string.licence_open_summary
        }
    )

    private fun intervalLabel(seconds: Int): String =
        if (seconds < 60) {
            resources.getQuantityString(R.plurals.seconds, seconds, seconds)
        } else {
            resources.getQuantityString(R.plurals.minutes, seconds / 60, seconds / 60)
        }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "CosmosMain"
        private const val BACKGROUND = 0xFF070B16.toInt()
        private const val FOCUSED = 0x26FFFFFF
        private const val ACCENT = 0xFF8CC8FF.toInt()
        private const val SECONDARY = 0xFFAFC0D8.toInt()
        private const val MUTED = 0xFF7A8AA3.toInt()
    }
}

internal fun launchDreamSettings(
    launch: () -> Unit,
    onUnavailable: (RuntimeException) -> Unit
) {
    try {
        launch()
    } catch (error: ActivityNotFoundException) {
        onUnavailable(error)
    } catch (error: SecurityException) {
        onUnavailable(error)
    }
}