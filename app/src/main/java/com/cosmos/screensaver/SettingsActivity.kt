package com.cosmos.screensaver

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.cosmos.screensaver.ui.Page
import com.cosmos.screensaver.ui.SettingsScreen

/** Hosts one settings sub-page (chosen by [EXTRA_PAGE]); Back returns to the page that opened it. */
class SettingsActivity : Activity() {
    private lateinit var screen: SettingsScreen

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requested = intent.getStringExtra(EXTRA_PAGE)
        val page = Page.entries.firstOrNull { it.name == requested && it != Page.HOME }
        if (page == null) {
            finish()
            return
        }
        screen = SettingsScreen(this, page)
        screen.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        if (::screen.isInitialized) screen.onResume(autoRefresh = false)
    }

    override fun onPause() {
        if (::screen.isInitialized) screen.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::screen.isInitialized) screen.onSaveInstanceState(outState)
    }

    internal companion object {
        const val EXTRA_PAGE = "page"

        fun intent(context: Context, page: Page): Intent =
            Intent(context, SettingsActivity::class.java).putExtra(EXTRA_PAGE, page.name)
    }
}
