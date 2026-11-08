package com.cosmos.screensaver.screensaver

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntheticArtworkGeometryTest {
    @Test
    fun markersAreVisibleNearAllFourCornersAndPointInward() {
        val width = SyntheticArtworkGeometry.WIDTH
        val height = SyntheticArtworkGeometry.HEIGHT
        val halfStroke = SyntheticArtworkGeometry.MARKER_STROKE_WIDTH / 2f
        val markers = SyntheticArtworkGeometry.cornerMarkers

        assertTrue(halfStroke > 0f)
        assertEquals(4, markers.size)
        assertEquals(
            setOf(true to true, true to false, false to true, false to false),
            markers.map { (it.x < width / 2f) to (it.y < height / 2f) }.toSet()
        )

        markers.forEach { marker ->
            val isLeft = marker.x < width / 2f
            val isTop = marker.y < height / 2f
            val xInset = if (isLeft) marker.x else width - marker.x
            val yInset = if (isTop) marker.y else height - marker.y
            val horizontalLength = marker.horizontalEndX - marker.x
            val verticalLength = marker.verticalEndY - marker.y

            assertTrue(xInset >= halfStroke && xInset <= width * 0.01f)
            assertTrue(yInset >= halfStroke && yInset <= height * 0.01f)
            assertTrue(marker.horizontalEndX in halfStroke..(width - halfStroke))
            assertTrue(marker.verticalEndY in halfStroke..(height - halfStroke))
            assertTrue(abs(horizontalLength) >= 48f)
            assertTrue(abs(verticalLength) >= 48f)
            assertEquals(isLeft, horizontalLength > 0f)
            assertEquals(isTop, verticalLength > 0f)
        }
    }
}
