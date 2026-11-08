package com.cosmos.screensaver.screensaver

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager

/** Runs the same slideshow as the screensaver, so it can be checked without waiting for idle. */
class PreviewActivity : Activity() {
    private var controller: SlideshowController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val view = SlideshowView(this)
        setContentView(view)
        controller = SlideshowController(this, view)
    }

    override fun onStart() {
        super.onStart()
        controller?.start()
    }

    override fun onStop() {
        controller?.stop()
        super.onStop()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        finish()
        return true
    }
}
