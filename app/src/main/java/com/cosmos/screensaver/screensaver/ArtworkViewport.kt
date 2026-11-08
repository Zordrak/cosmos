package com.cosmos.screensaver.screensaver

/**
 * Scale-to-fit geometry for the fixed 16:9 synthetic screensaver composition.
 * The returned viewport is centred and never crops or distorts the source.
 */
internal data class ArtworkViewport(
    val left: Float,
    val top: Float,
    val scale: Float
) {
    companion object {
        fun fit(
            containerWidth: Int,
            containerHeight: Int,
            contentWidth: Float = 1920f,
            contentHeight: Float = 1080f
        ): ArtworkViewport {
            require(containerWidth > 0 && containerHeight > 0)
            require(contentWidth > 0f && contentHeight > 0f)

            val scale = minOf(
                containerWidth / contentWidth,
                containerHeight / contentHeight
            )
            return ArtworkViewport(
                left = (containerWidth - contentWidth * scale) / 2f,
                top = (containerHeight - contentHeight * scale) / 2f,
                scale = scale
            )
        }
    }
}
