package com.cosmos.screensaver.screensaver

import android.content.Context
import android.view.View
import android.widget.FrameLayout

/**
 * The slideshow's views: the 1080p-class [view] for cross-fades and credits, and, where the panel
 * is larger and the TV can decode it, a [sharp] native-resolution layer underneath.
 */
internal class SlideshowStage private constructor(val root: View, val view: SlideshowView, val sharp: SharpLayer?) {
    fun controller(context: Context) = SlideshowController(context, view, sharp)

    /** Frees the native-resolution decoder. Call when the window goes away. */
    fun release() {
        sharp?.release()
    }

    companion object {
        fun create(context: Context): SlideshowStage {
            val size = SharpLayer.nativeSize(context)
            if (size == null) {
                val view = SlideshowView(context)
                return SlideshowStage(view, view, null)
            }
            val sharp = SharpLayer(context, size.first, size.second)
            val view = SlideshowView(context, overlay = true)
            val root = FrameLayout(context).apply {
                val full = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                addView(sharp, full)
                addView(view, FrameLayout.LayoutParams(full))
            }
            return SlideshowStage(root, view, sharp)
        }
    }
}
