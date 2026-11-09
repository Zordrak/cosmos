package com.cosmos.screensaver.screensaver

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import com.cosmos.screensaver.settings.CosmosSettings
import com.cosmos.screensaver.settings.Sharpness

/**
 * The slideshow's views: the logical-size [view] for fades and credits and, when Full 4K is chosen
 * and the TV can do it, a [sharp] native-resolution layer underneath that shows the pictures.
 */
internal class SlideshowStage private constructor(val root: View, val view: SlideshowView, val sharp: SharpLayer?) {
    fun controller(context: Context) = SlideshowController(context, view, sharp)

    /** Frees the native-resolution decoder. Call when the window goes away. */
    fun release() {
        sharp?.release()
    }

    companion object {
        fun create(context: Context): SlideshowStage {
            val wanted = CosmosSettings(context).sharpness == Sharpness.FULL_4K
            val size = if (wanted) SharpLayer.nativeSize(context) else null
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
