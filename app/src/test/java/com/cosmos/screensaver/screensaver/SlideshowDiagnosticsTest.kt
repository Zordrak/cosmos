package com.cosmos.screensaver.screensaver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlideshowDiagnosticsTest {
    @Test
    fun describesThePictureTimingAndLibrary() {
        val d = SlideshowDiagnostics().apply {
            renderPath = "Full 4K: 3840x2160 on the video plane (OMX.MTK.VIDEO.DECODER.AVC)"
            display = "panel 3840x2160 · apps 1920x1080"
            pictureNumber = 3
            title = "Dusty Nebula"
            source = "NOIRLab noirlab2301a"
            fileWidth = 4000
            fileHeight = 2164
            fileBytes = 2_621_440
            fit = "fill"
            drawnBox = "1920x1080+0+0"
            frameDecodeMillis = 210
            sharpDecodeMillis = 900
            sharpConvertMillis = 1100
            sharpShowMillis = 300
            saved = 400
            showable = 380
            order = "fair"
            intervalSeconds = 60
            refresh = "400 images available. This refresh added 12"
        }

        val lines = d.lines(usedMb = 120, maxMb = 512, upSeconds = 125)

        assertEquals("COSMOS DIAGNOSTICS", lines.first())
        assertTrue(lines.contains("Picture    #3  Dusty Nebula"))
        assertTrue(lines.contains("Source     NOIRLab noirlab2301a · 4000x2164 · 2.5 MB"))
        assertTrue(lines.contains("Timing     prepared in 210 ms · 4K decode 900 ms · convert 1100 ms · video 300 ms"))
        assertTrue(lines.contains("Library    400 saved · 380 showable · fair · every 60s"))
        assertEquals("Memory     120 / 512 MB · up 2:05", lines.last())
    }

    @Test
    fun saysWhenNothingIsShownYet() {
        val lines = SlideshowDiagnostics().lines(10, 512, 0)
        assertTrue(lines.contains("Picture    none yet"))
        assertTrue(lines.any { it.startsWith("Library    counting") })
    }
}
