package com.cosmos.screensaver

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateUtils
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.cosmos.screensaver.screensaver.PreviewActivity
import com.cosmos.screensaver.settings.CosmosSettings
import com.cosmos.screensaver.source.DjangoplicitySite
import com.cosmos.screensaver.sync.RefreshCoordinator

class MainActivity : Activity() {
    private lateinit var settings: CosmosSettings
    private lateinit var status: TextView
    private lateinit var refreshButton: Button
    private lateinit var intervalButton: Button
    private lateinit var clearButton: Button
    private val sourceButtons = linkedMapOf<DjangoplicitySite, Button>()
    private val handler = Handler(Looper.getMainLooper())
    private var clearArmed = false
    private val statusPoller = object : Runnable {
        override fun run() {
            updateStatus()
            if (RefreshCoordinator.isRunning) handler.postDelayed(this, 1_500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = CosmosSettings(this)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(72, 48, 72, 48)
            setBackgroundColor(0xFF050914.toInt())
        }
        layout.addView(text(getString(R.string.home_title), 30f, 0xFFFFFFFF.toInt()))
        layout.addView(text(getString(R.string.home_description), 17f, 0xFFD5E2F5.toInt()).apply {
            setPadding(0, 16, 0, 16)
        })
        status = text("", 16f, 0xFF8CC8FF.toInt()).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(0, 0, 0, 16)
        }
        layout.addView(status)
        val settingsStatus = text("", 16f, 0xFFFFCF78.toInt()).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }

        val previewButton = button(getString(R.string.home_preview)) {
            startActivity(Intent(this, PreviewActivity::class.java))
        }
        refreshButton = button(getString(R.string.home_refresh)) {
            RefreshCoordinator.requestRefresh(this, force = true) { updateStatus() }
            handler.removeCallbacks(statusPoller)
            handler.post(statusPoller)
        }
        val screensaverButton = button(getString(R.string.home_open_settings)) {
            settingsStatus.text = ""
            launchDreamSettings(
                launch = { startActivity(Intent(Settings.ACTION_DREAM_SETTINGS)) },
                onUnavailable = { error ->
                    Log.w(TAG, "Unable to open system screensaver settings", error)
                    settingsStatus.setText(R.string.home_settings_unavailable)
                }
            )
        }
        layout.addView(row(previewButton, refreshButton, screensaverButton))

        intervalButton = button("") {
            settings.intervalSeconds = CosmosSettings.nextInterval(settings.intervalSeconds)
            updateControls()
        }
        DjangoplicitySite.entries.forEach { site ->
            sourceButtons[site] = button("") {
                settings.setEnabled(site, !settings.isEnabled(site))
                updateControls()
            }
        }
        clearButton = button(getString(R.string.home_clear)) {
            if (RefreshCoordinator.isRunning) return@button
            if (!clearArmed) {
                clearArmed = true
                clearButton.setText(R.string.home_clear_confirm)
            } else {
                clearArmed = false
                RefreshCoordinator.store(this).clear()
                clearButton.setText(R.string.home_clear)
                updateStatus()
            }
        }
        layout.addView(row(intervalButton, *sourceButtons.values.toTypedArray(), clearButton))
        layout.addView(settingsStatus)
        layout.addView(text(getString(R.string.home_privacy), 14f, 0xFF9FB0C8.toInt()).apply {
            setPadding(0, 24, 0, 0)
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(0xFF050914.toInt())
            addView(layout)
        })
        previewButton.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        updateControls()
        RefreshCoordinator.requestRefresh(this) { updateStatus() }
        handler.removeCallbacks(statusPoller)
        handler.post(statusPoller)
    }

    override fun onPause() {
        handler.removeCallbacks(statusPoller)
        super.onPause()
    }

    private fun updateControls() {
        intervalButton.text = getString(R.string.home_interval, intervalLabel(settings.intervalSeconds))
        sourceButtons.forEach { (site, button) ->
            val state = getString(if (settings.isEnabled(site)) R.string.home_on else R.string.home_off)
            button.text = getString(R.string.home_source, site.label, state)
        }
        updateStatus()
    }

    private fun updateStatus() {
        val catalogue = RefreshCoordinator.store(this).load()
        val enabled = settings.enabledSites.map { it.provider }.toSet()
        val count = catalogue.entries.count { it.provider in enabled }
        val lines = mutableListOf(resources.getQuantityString(R.plurals.home_image_count, count, count))
        if (RefreshCoordinator.isRunning) {
            lines += getString(R.string.home_refreshing)
        } else if (catalogue.lastAttemptAtEpochMillis > 0) {
            val whenText = DateUtils.getRelativeTimeSpanString(catalogue.lastAttemptAtEpochMillis)
            lines += getString(R.string.home_last_refresh, whenText, catalogue.lastSummary)
        }
        if (settings.enabledSites.isEmpty()) lines += getString(R.string.home_no_sources)
        status.text = lines.joinToString("\n")
        refreshButton.isEnabled = !RefreshCoordinator.isRunning
        clearButton.isEnabled = !RefreshCoordinator.isRunning
    }

    private fun intervalLabel(seconds: Int): String =
        if (seconds < 60) {
            resources.getQuantityString(R.plurals.seconds, seconds, seconds)
        } else {
            resources.getQuantityString(R.plurals.minutes, seconds / 60, seconds / 60)
        }

    private fun text(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, 8, 0, 8)
        views.forEach { addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
    }

    companion object {
        private const val TAG = "CosmosMain"
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
