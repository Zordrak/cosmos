package com.cosmos.screensaver.screensaver

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.service.dreams.DreamService
import android.util.Log

class CosmosDreamService : DreamService() {
    private var controller: SlideshowController? = null
    private var stage: SlideshowStage? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        // A non-interactive dream is dismissed by the platform on first user input.
        isInteractive = false
        isFullscreen = true
        // Whatever is behind the picture while it loads must be plain black, never a themed colour.
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        val created = SlideshowStage.create(this)
        stage = created
        setContentView(created.root)
        controller = created.controller(this)
        Log.i(TAG, "Dream window attached (native-resolution layer: ${created.sharp?.let { "${it.frameWidth}x${it.frameHeight}" } ?: "none"})")
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
        stage?.release()
        stage = null
        super.onDetachedFromWindow()
    }

    companion object {
        private const val TAG = "CosmosDream"
    }
}
