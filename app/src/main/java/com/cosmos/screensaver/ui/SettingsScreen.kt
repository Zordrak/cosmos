package com.cosmos.screensaver.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.cosmos.screensaver.LibraryActivity
import com.cosmos.screensaver.R
import com.cosmos.screensaver.SettingsActivity
import com.cosmos.screensaver.launchDreamSettings
import com.cosmos.screensaver.screensaver.PreviewActivity
import com.cosmos.screensaver.settings.CosmosSettings
import com.cosmos.screensaver.settings.AndroidIdleSetting
import com.cosmos.screensaver.settings.IdleTime
import com.cosmos.screensaver.settings.ScreensaverSelection
import com.cosmos.screensaver.sync.LibraryStorage
import com.cosmos.screensaver.sync.Meteredness
import com.cosmos.screensaver.sync.RefreshCoordinator
import com.cosmos.screensaver.sync.RefreshJobService
import com.cosmos.screensaver.sync.RefreshReason

/**
 * The shared scaffold of every settings page: a left information pane and a right list of rows. It
 * loads the library snapshot off the main thread, keeps the rows current while a refresh runs, and
 * restores focus and scroll position when the activity is recreated.
 */
internal class SettingsScreen(override val activity: Activity, private val page: Page) : PageEnv {
    override val settings = CosmosSettings(activity)
    override val text = SettingsText(activity)
    override val idleTime = IdleTime(AndroidIdleSetting(activity)) { message, error -> Log.w(TAG, message, error) }
    override var snapshot = LibrarySnapshot()
        private set

    /** What the TV last said about the connection; refreshed off the main thread when the page opens or the download mode changes. */
    override var meteredness = Meteredness.UNKNOWN_NOW
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
            val undoPending = snapshot.pending != null
            if (undoPending || RefreshCoordinator.isRunning || System.currentTimeMillis() < pollUntil) {
                handler.postDelayed(this, if (undoPending) UNDO_POLL_MILLIS else POLL_MILLIS)
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
        updateAll()

        val savedId = savedInstanceState?.getString(STATE_FOCUS)
        val visibleRows = content.rows.filter { it.view.visibility == View.VISIBLE }
        val target = visibleRows.firstOrNull { it.id == savedId } ?: visibleRows.firstOrNull()
        target?.view?.requestFocus()
        savedInstanceState?.getInt(STATE_SCROLL)?.let { y -> scroll.post { scroll.scrollTo(0, y) } }
    }

    fun onResume(autoRefresh: Boolean) {
        updateAll()
        reloadSnapshot()
        if (autoRefresh) {
            val appContext = activity.applicationContext
            // requestRefresh reads the library, so it runs on the storage thread, never on the main thread.
            LibraryStorage.post {
                refreshMeteredness()
                RefreshCoordinator.requestRefresh(appContext) { reloadSnapshot() }
            }
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

    override fun openLibrary() {
        activity.startActivity(LibraryActivity.intent(activity))
    }

    override fun viewingChanged(refresh: Boolean) {
        if (refresh) requestRefresh(RefreshReason.SETTINGS_CHANGED)
        reloadSnapshot()
        updateAll()
    }

    override fun libraryChanged() {
        reloadSnapshot()
        startPolling()
    }

    override fun lookForNewPictures() {
        requestRefresh(RefreshReason.EXPLICIT_SEARCH)
        updateAll()
    }

    override fun downloadModeChanged() {
        val appContext = activity.applicationContext
        LibraryStorage.post {
            RefreshJobService.reconcile(appContext)
            refreshMeteredness()
            RefreshCoordinator.requestRefresh(appContext) { reloadSnapshot() }
        }
        reloadSnapshot()
        updateAll()
        startPolling()
    }

    /** Re-reads what the TV says about the connection; off the main thread, then redraws. */
    private fun refreshMeteredness() {
        val reading = RefreshCoordinator.meteredness(activity.applicationContext)
        handler.post {
            if (activity.isFinishing || activity.isDestroyed || reading == meteredness) return@post
            meteredness = reading
            updateAll()
        }
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

    override fun requestWriteSettings() {
        launchDreamSettings(
            launch = {
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${activity.packageName}"))
                )
            },
            onUnavailable = { error ->
                Log.w(TAG, "Unable to open the write-settings permission screen", error)
                launchDreamSettings(
                    launch = { activity.startActivity(Intent(Settings.ACTION_DREAM_SETTINGS)) },
                    onUnavailable = { fallbackError ->
                        Log.w(TAG, "Unable to open system screensaver settings", fallbackError)
                        Toast.makeText(activity, R.string.idle_permission_unavailable, Toast.LENGTH_LONG).show()
                    }
                )
            }
        )
    }

    override fun screensaverSelection(): ScreensaverSelection = try {
        val resolver = activity.contentResolver
        val enabled = try {
            Settings.Secure.getInt(resolver, SCREENSAVER_ENABLED)
        } catch (missing: Settings.SettingNotFoundException) {
            null
        }
        ScreensaverSelection.parse(Settings.Secure.getString(resolver, SCREENSAVER_COMPONENTS), activity.packageName, enabled)
    } catch (error: SecurityException) {
        ScreensaverSelection.UNKNOWN
    }

    private fun requestRefresh(reason: RefreshReason) {
        val appContext = activity.applicationContext
        LibraryStorage.post { RefreshCoordinator.requestRefresh(appContext, reason) { reloadSnapshot() } }
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
        if (!::content.isInitialized) return
        content.rows.forEach { it.update() }
        content.updateLeft()
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "CosmosSettings"
        const val STATE_FOCUS = "focused_row"
        const val STATE_SCROLL = "scroll_y"
        const val POLL_MILLIS = 1_500L
        const val UNDO_POLL_MILLIS = 1_000L
        const val POLL_GRACE_MILLIS = 3_000L
        const val SCREENSAVER_COMPONENTS = "screensaver_components"
        const val SCREENSAVER_ENABLED = "screensaver_enabled"
    }
}
