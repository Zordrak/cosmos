package com.cosmos.screensaver.screensaver

internal object SyntheticArtworkGeometry {
    const val WIDTH = 1920f
    const val HEIGHT = 1080f
    const val MARKER_STROKE_WIDTH = 6f

    private const val MARKER_INSET = 8f
    private const val MARKER_LENGTH = 72f

    val cornerMarkers = listOf(
        CornerMarker(
            MARKER_INSET, MARKER_INSET,
            MARKER_INSET + MARKER_LENGTH, MARKER_INSET + MARKER_LENGTH
        ),
        CornerMarker(
            WIDTH - MARKER_INSET, MARKER_INSET,
            WIDTH - MARKER_INSET - MARKER_LENGTH, MARKER_INSET + MARKER_LENGTH
        ),
        CornerMarker(
            MARKER_INSET, HEIGHT - MARKER_INSET,
            MARKER_INSET + MARKER_LENGTH, HEIGHT - MARKER_INSET - MARKER_LENGTH
        ),
        CornerMarker(
            WIDTH - MARKER_INSET, HEIGHT - MARKER_INSET,
            WIDTH - MARKER_INSET - MARKER_LENGTH, HEIGHT - MARKER_INSET - MARKER_LENGTH
        )
    )

    data class CornerMarker(
        val x: Float,
        val y: Float,
        val horizontalEndX: Float,
        val verticalEndY: Float
    )
}
