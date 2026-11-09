package com.cosmos.screensaver

import android.app.Activity
import android.content.ActivityNotFoundException
import android.os.Bundle
import com.cosmos.screensaver.sync.RefreshJobService
import com.cosmos.screensaver.ui.Page
import com.cosmos.screensaver.ui.SettingsScreen

/** The launcher entry: Cosmos's Home screen. Every other settings page is a [SettingsActivity]. */
class MainActivity : Activity() {
    private lateinit var screen: SettingsScreen

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RefreshJobService.ensureScheduled(this)
        screen = SettingsScreen(this, Page.HOME)
        screen.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        screen.onResume(autoRefresh = true)
    }

    override fun onPause() {
        screen.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        screen.onSaveInstanceState(outState)
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
