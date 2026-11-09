package com.cosmos.screensaver.screensaver

import com.cosmos.screensaver.settings.DisplayMode
import kotlin.math.roundToInt

/** A [source] rectangle in decoded-bitmap pixels and a [destination] rectangle in miniature-frame pixels. */
internal data class PreviewPlacement(val source: IntBox, val destination: IntBox)

/**
 * Places an image in a miniature of the TV screen exactly as the real slideshow would. The real placement is
 * computed from the ORIGINAL image size on a virtual viewport the size of the panel, so Original size and the
 * crop come out right; only then are the results mapped into the (smaller) decoded bitmap and the miniature.
 */
internal fun previewPlacement(
    mode: DisplayMode,
    imageWidth: Int,
    imageHeight: Int,
    viewportWidth: Int,
    viewportHeight: Int,
    bitmapWidth: Int,
    bitmapHeight: Int,
    frameWidth: Int,
    frameHeight: Int
): PreviewPlacement {
    require(bitmapWidth > 0 && bitmapHeight > 0 && frameWidth > 0 && frameHeight > 0)
    val real = placement(mode, imageWidth, imageHeight, viewportWidth, viewportHeight, viewportWidth)
    val toBitmapX = bitmapWidth.toDouble() / imageWidth
    val toBitmapY = bitmapHeight.toDouble() / imageHeight
    val toFrameX = frameWidth.toDouble() / viewportWidth
    val toFrameY = frameHeight.toDouble() / viewportHeight
    val source = IntBox(
        (real.source.left * toBitmapX).roundToInt().coerceIn(0, bitmapWidth - 1),
        (real.source.top * toBitmapY).roundToInt().coerceIn(0, bitmapHeight - 1),
        (real.source.right * toBitmapX).roundToInt().coerceIn(1, bitmapWidth),
        (real.source.bottom * toBitmapY).roundToInt().coerceIn(1, bitmapHeight)
    )
    val destination = IntBox(
        (real.destination.left * toFrameX).roundToInt().coerceIn(0, frameWidth - 1),
        (real.destination.top * toFrameY).roundToInt().coerceIn(0, frameHeight - 1),
        (real.destination.right * toFrameX).roundToInt().coerceIn(1, frameWidth),
        (real.destination.bottom * toFrameY).roundToInt().coerceIn(1, frameHeight)
    )
    return PreviewPlacement(source, destination)
}
