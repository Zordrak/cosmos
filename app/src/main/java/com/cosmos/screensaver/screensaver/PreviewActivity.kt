package com.cosmos.screensaver.screensaver

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager

/** Runs the same slideshow as the screensaver, so it can be checked without waiting for idle. */
class PreviewActivity : Activity() {
    private var controller: SlideshowController? = null
    private var stage: SlideshowStage? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val created = SlideshowStage.create(this)
        stage = created
        setContentView(created.root)
        controller = created.controller(this)
    }

    override fun onDestroy() {
        stage?.release()
        stage = null
        super.onDestroy()
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
