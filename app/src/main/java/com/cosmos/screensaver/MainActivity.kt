package com.cosmos.screensaver

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(72, 64, 72, 64)
            setBackgroundColor(0xFF050914.toInt())
        }

        val title = TextView(this).apply {
            text = getString(R.string.home_title)
            textSize = 30f
            setTextColor(0xFFFFFFFF.toInt())
        }
        val description = TextView(this).apply {
            text = getString(R.string.home_description) + "\n\n" +
                getString(R.string.home_instructions)
            textSize = 18f
            setTextColor(0xFFD5E2F5.toInt())
            setPadding(0, 24, 0, 24)
        }
        val settingsStatus = TextView(this).apply {
            textSize = 16f
            setTextColor(0xFFFFCF78.toInt())
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val settingsButton = Button(this).apply {
            text = getString(R.string.home_open_settings)
            setOnClickListener {
                settingsStatus.text = ""
                launchDreamSettings(
                    launch = { startActivity(Intent(Settings.ACTION_DREAM_SETTINGS)) },
                    onUnavailable = { error ->
                        Log.w(TAG, "Unable to open system screensaver settings", error)
                        settingsStatus.setText(R.string.home_settings_unavailable)
                    }
                )
            }
        }

        layout.addView(title)
        layout.addView(description)
        layout.addView(settingsButton)
        layout.addView(settingsStatus)
        setContentView(layout)
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
