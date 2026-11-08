package com.cosmos.screensaver.screensaver

import android.service.dreams.DreamService
import android.util.Log

class CosmosDreamService : DreamService() {
    private var controller: SlideshowController? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        // A non-interactive dream is dismissed by the platform on first user input.
        isInteractive = false
        isFullscreen = true
        val view = SlideshowView(this)
        setContentView(view)
        controller = SlideshowController(this, view)
        Log.i(TAG, "Dream window attached")
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        controller?.start()
        Log.i(TAG, "Dream started")
    }

    override fun onDreamingStopped() {
        Log.i(TAG, "Dream stopped")
        controller?.stop()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        Log.i(TAG, "Dream window detached")
        controller?.stop()
        controller = null
        super.onDetachedFromWindow()
    }

    companion object {
        private const val TAG = "CosmosDream"
    }
}
