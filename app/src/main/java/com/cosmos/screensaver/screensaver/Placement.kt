package com.cosmos.screensaver.screensaver

import com.cosmos.screensaver.settings.DisplayMode
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class IntBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
}

/** Which part of the image ([source], image pixels) is drawn where on screen ([destination], view pixels). */
internal data class Placement(val source: IntBox, val destination: IntBox)

/**
 * Places an image on a screen for a [DisplayMode]. Every mode except [DisplayMode.STRETCH]
 * preserves the aspect ratio. [physicalWidth] is the panel's native width, used by
 * [DisplayMode.ORIGINAL] when the view is rendered at a lower resolution and upscaled.
 */
internal fun placement(
    mode: DisplayMode,
    imageWidth: Int,
    imageHeight: Int,
    screenWidth: Int,
    screenHeight: Int,
    physicalWidth: Int = screenWidth
): Placement {
    require(imageWidth > 0 && imageHeight > 0 && screenWidth > 0 && screenHeight > 0)
    val fitScale = min(screenWidth.toDouble() / imageWidth, screenHeight.toDouble() / imageHeight)
    val fillScale = max(screenWidth.toDouble() / imageWidth, screenHeight.toDouble() / imageHeight)
    return when (mode) {
        DisplayMode.FIT -> centred(fitScale, imageWidth, imageHeight, screenWidth, screenHeight)
        DisplayMode.FILL -> centred(fillScale, imageWidth, imageHeight, screenWidth, screenHeight)
        DisplayMode.AUTO -> centred(
            if (fitScale / fillScale >= AUTO_FILL_THRESHOLD) fillScale else fitScale,
            imageWidth, imageHeight, screenWidth, screenHeight
        )
        DisplayMode.ORIGINAL -> centred(
            screenWidth.toDouble() / physicalWidth.coerceAtLeast(1),
            imageWidth, imageHeight, screenWidth, screenHeight
        )
        DisplayMode.STRETCH -> Placement(
            IntBox(0, 0, imageWidth, imageHeight),
            IntBox(0, 0, screenWidth, screenHeight)
        )
    }
}

/** Fill only crops when at least this share of each dimension stays visible. */
internal const val AUTO_FILL_THRESHOLD = 0.8

/** Centres the image at [scale] and clips it to the screen. */
private fun centred(scale: Double, imageWidth: Int, imageHeight: Int, screenWidth: Int, screenHeight: Int): Placement {
    val scaledWidth = imageWidth * scale
    val scaledHeight = imageHeight * scale
    val visibleWidth = min(scaledWidth, screenWidth.toDouble())
    val visibleHeight = min(scaledHeight, screenHeight.toDouble())
    val sourceWidth = (visibleWidth / scale).coerceAtMost(imageWidth.toDouble())
    val sourceHeight = (visibleHeight / scale).coerceAtMost(imageHeight.toDouble())
    val sourceLeft = ((imageWidth - sourceWidth) / 2).roundToInt()
    val sourceTop = ((imageHeight - sourceHeight) / 2).roundToInt()
    val destinationLeft = ((screenWidth - visibleWidth) / 2).roundToInt()
    val destinationTop = ((screenHeight - visibleHeight) / 2).roundToInt()
    return Placement(
        IntBox(
            sourceLeft,
            sourceTop,
            (sourceLeft + sourceWidth.roundToInt()).coerceAtMost(imageWidth),
            (sourceTop + sourceHeight.roundToInt()).coerceAtMost(imageHeight)
        ),
        IntBox(
            destinationLeft,
            destinationTop,
            (destinationLeft + visibleWidth.roundToInt()).coerceAtMost(screenWidth),
            (destinationTop + visibleHeight.roundToInt()).coerceAtMost(screenHeight)
        ).let { if (it.width < 1 || it.height < 1) IntBox(it.left, it.top, it.left + 1, it.top + 1) else it }
    )
}

/**
 * Largest power-of-two sample size that keeps a decoded [regionWidth]×[regionHeight] region at
 * least as large as its [targetWidth]×[targetHeight] destination, so it is only ever downscaled.
 */
internal fun sampleSizeFor(regionWidth: Int, regionHeight: Int, targetWidth: Int, targetHeight: Int): Int {
    if (regionWidth <= 0 || regionHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) return 1
    var sample = 1
    while (regionWidth / (sample * 2) >= targetWidth && regionHeight / (sample * 2) >= targetHeight) sample *= 2
    return sample
}
