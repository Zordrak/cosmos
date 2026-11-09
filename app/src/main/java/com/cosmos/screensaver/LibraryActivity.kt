package com.cosmos.screensaver

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.cosmos.screensaver.ui.LibraryScreen

/** The saved pictures browser, or the blocked pictures list when [EXTRA_BLOCKED] is set. Not exported. */
class LibraryActivity : Activity() {
    private lateinit var screen: LibraryScreen

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen = LibraryScreen(this, blockedMode = intent.getBooleanExtra(EXTRA_BLOCKED, false))
        screen.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        screen.onResume()
    }

    override fun onPause() {
        screen.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        screen.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        screen.onDestroy()
        super.onDestroy()
    }

    internal companion object {
        const val EXTRA_BLOCKED = "blocked"

        fun intent(context: Context, blocked: Boolean): Intent =
            Intent(context, LibraryActivity::class.java).putExtra(EXTRA_BLOCKED, blocked)
    }
}
