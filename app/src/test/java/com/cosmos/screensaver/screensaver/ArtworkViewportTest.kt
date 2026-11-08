package com.cosmos.screensaver.screensaver

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkViewportTest {
    @Test
    fun fitsExact4k16By9WithoutBars() {
        val viewport = ArtworkViewport.fit(3840, 2160)

        assertEquals(0f, viewport.left, 0.001f)
        assertEquals(0f, viewport.top, 0.001f)
        assertEquals(2f, viewport.scale, 0.001f)
    }

    @Test
    fun centres16By9ArtworkVerticallyOn4By3Surface() {
        val viewport = ArtworkViewport.fit(1920, 1440)

        assertEquals(0f, viewport.left, 0.001f)
        assertEquals(180f, viewport.top, 0.001f)
        assertEquals(1f, viewport.scale, 0.001f)
    }

    @Test
    fun centres16By9ArtworkHorizontallyOnUltrawideSurface() {
        val viewport = ArtworkViewport.fit(2560, 1080)

        assertEquals(320f, viewport.left, 0.001f)
        assertEquals(0f, viewport.top, 0.001f)
        assertEquals(1f, viewport.scale, 0.001f)
    }
}
