package com.cosmos.screensaver.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.cosmos.screensaver.R
import com.cosmos.screensaver.SettingsActivity
import com.cosmos.screensaver.launchDreamSettings
import com.cosmos.screensaver.screensaver.PreviewActivity
import com.cosmos.screensaver.settings.CosmosSettings
import com.cosmos.screensaver.settings.ScreensaverSelection
import com.cosmos.screensaver.sync.LibraryStorage
import com.cosmos.screensaver.sync.RefreshCoordinator

/**
 * The shared scaffold of every settings page: a left information pane and a right list of rows. It
 * loads the library snapshot off the main thread, keeps the rows current while a refresh runs, and
 * restores focus and scroll position when the activity is recreated.
 */
internal class SettingsScreen(override val activity: Activity, private val page: Page) : PageEnv {
    override val settings = CosmosSettings(activity)
    override val text = SettingsText(activity)
    override var snapshot = LibrarySnapshot()
        private set

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var content: PageContent
    private lateinit var scroll: ScrollView
    private var requested = 0L
    private var applied = 0L
    private var pollUntil = 0L

    private val poller = object : Runnable {
        override fun run() {
            reloadSnapshot()
            updateAll()
            if (RefreshCoordinator.isRunning || System.currentTimeMillis() < pollUntil) {
                handler.postDelayed(this, POLL_MILLIS)
            }
        }
    }

    fun onCreate(savedInstanceState: Bundle?) {
        val left = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), dp(40), 0)
        }
        content = SettingsPages(this).build(page, left)

        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(16), dp(24))
        }
        content.rows.forEach { list.addView(it.view) }
        scroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            addView(list)
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(SettingsColors.BACKGROUND)
            setPadding(dp(56), dp(40), dp(40), dp(32))
            addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.38f))
            addView(scroll, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.62f))
        }
        activity.setContentView(root)

        val savedId = savedInstanceState?.getString(STATE_FOCUS)
        val target = content.rows.firstOrNull { it.id == savedId } ?: content.rows.firstOrNull()
        target?.view?.requestFocus()
        savedInstanceState?.getInt(STATE_SCROLL)?.let { y -> scroll.post { scroll.scrollTo(0, y) } }
        updateAll()
    }

    fun onResume(autoRefresh: Boolean) {
        updateAll()
        reloadSnapshot()
        if (autoRefresh) {
            val appContext = activity.applicationContext
            // requestRefresh reads the library, so it runs on the storage thread, never on the main thread.
            LibraryStorage.post { RefreshCoordinator.requestRefresh(appContext) { reloadSnapshot() } }
            startPolling()
        } else if (RefreshCoordinator.isRunning) {
            startPolling()
        }
    }

    fun onPause() {
        handler.removeCallbacks(poller)
    }

    fun onSaveInstanceState(outState: Bundle) {
        val focused = activity.currentFocus
        content.rows.firstOrNull { it.view === focused }?.let { outState.putString(STATE_FOCUS, it.id) }
        outState.putInt(STATE_SCROLL, scroll.scrollY)
    }

    override fun openPage(page: Page) {
        activity.startActivity(SettingsActivity.intent(activity, page))
    }

    override fun viewingChanged(refresh: Boolean) {
        if (refresh) requestForcedRefresh()
        reloadSnapshot()
        updateAll()
    }

    override fun lookForNewPictures() {
        requestForcedRefresh()
        updateAll()
    }

    override fun startPreview() {
        activity.startActivity(Intent(activity, PreviewActivity::class.java))
    }

    override fun openTvScreensaverSettings() {
        launchDreamSettings(
            launch = { activity.startActivity(Intent(Settings.ACTION_DREAM_SETTINGS)) },
            onUnavailable = { error ->
                Log.w(TAG, "Unable to open system screensaver settings", error)
                Toast.makeText(activity, R.string.home_settings_unavailable, Toast.LENGTH_LONG).show()
            }
        )
    }

    override fun screensaverSelection(): ScreensaverSelection = try {
        ScreensaverSelection.parse(
            Settings.Secure.getString(activity.contentResolver, SCREENSAVER_COMPONENTS), activity.packageName
        )
    } catch (error: SecurityException) {
        ScreensaverSelection.UNKNOWN
    }

    private fun requestForcedRefresh() {
        val appContext = activity.applicationContext
        LibraryStorage.post { RefreshCoordinator.requestRefresh(appContext, force = true) { reloadSnapshot() } }
        startPolling()
    }

    private fun startPolling() {
        // The refresh starts on the storage thread, so keep polling briefly until it is visibly running.
        pollUntil = System.currentTimeMillis() + POLL_GRACE_MILLIS
        handler.removeCallbacks(poller)
        handler.post(poller)
    }

    private fun reloadSnapshot() {
        val revision = ++requested
        val appContext = activity.applicationContext
        LibraryStorage.post {
            val loaded = LibrarySnapshot.load(appContext, CosmosSettings(appContext), revision)
            handler.post {
                if (activity.isFinishing || activity.isDestroyed || loaded.revision <= applied) return@post
                applied = loaded.revision
                snapshot = loaded
                updateAll()
            }
        }
    }

    private fun updateAll() {
        content.rows.forEach { it.update() }
        content.updateLeft()
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "CosmosSettings"
        const val STATE_FOCUS = "focused_row"
        const val STATE_SCROLL = "scroll_y"
        const val POLL_MILLIS = 1_500L
        const val POLL_GRACE_MILLIS = 3_000L
        const val SCREENSAVER_COMPONENTS = "screensaver_components"
    }
}
