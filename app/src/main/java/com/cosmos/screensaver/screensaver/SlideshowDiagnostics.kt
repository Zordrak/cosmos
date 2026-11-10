package com.cosmos.screensaver.screensaver

import java.util.Locale

/** What the on-screen diagnostics panel reports. Written from the slideshow's threads, read on the main thread. */
internal class SlideshowDiagnostics {
    @Volatile var renderPath = ""
    @Volatile var display = ""
    @Volatile var pictureNumber = 0
    @Volatile var title = ""
    @Volatile var source = ""
    @Volatile var fileWidth = 0
    @Volatile var fileHeight = 0
    @Volatile var fileBytes = 0L
    @Volatile var fit = ""
    @Volatile var drawnBox = ""
    @Volatile var frameDecodeMillis = 0L
    @Volatile var sharpDecodeMillis = -1L
    @Volatile var sharpConvertMillis = -1L
    @Volatile var sharpShowMillis = -1L
    @Volatile var saved = -1
    @Volatile var showable = -1
    @Volatile var indexed = -1
    @Volatile var order = ""
    @Volatile var intervalSeconds = 0
    @Volatile var streaming = false
    @Volatile var refresh = ""

    /** The panel's lines; [usedMb], [maxMb] and [upSeconds] are sampled when drawn. */
    fun lines(usedMb: Long, maxMb: Long, upSeconds: Long): List<String> {
        val lines = mutableListOf("COSMOS DIAGNOSTICS")
        lines += "Rendering  $renderPath"
        lines += "Display    $display"
        if (pictureNumber > 0) {
            lines += "Picture    #$pictureNumber  ${title.take(MAX_TITLE)}"
            val size = if (fileWidth > 0) "${fileWidth}x$fileHeight" else "size unknown"
            lines += "Source     $source · $size · ${megabytes(fileBytes)}"
            lines += "Fit        $fit · drawn at $drawnBox"
            val timing = StringBuilder("prepared in ${frameDecodeMillis} ms")
            if (sharpDecodeMillis >= 0) timing.append(" · 4K decode ${sharpDecodeMillis} ms")
            if (sharpConvertMillis >= 0) timing.append(" · convert ${sharpConvertMillis} ms")
            if (sharpShowMillis >= 0) timing.append(" · video ${sharpShowMillis} ms")
            lines += "Timing     $timing"
        } else {
            lines += "Picture    none yet"
        }
        val library = when {
            streaming && indexed >= 0 -> "$indexed found online"
            streaming -> "online index loading…"
            saved >= 0 -> "$saved saved · $showable showable"
            else -> "counting…"
        }
        lines += "Library    $library · $order · every ${intervalSeconds}s" + if (streaming) " · streaming" else ""
        if (refresh.isNotEmpty()) lines += "Refresh    ${refresh.take(MAX_TITLE)}"
        lines += "Memory     $usedMb / $maxMb MB · up ${upSeconds / 60}:${String.format(Locale.ROOT, "%02d", upSeconds % 60)}"
        return lines
    }

    private fun megabytes(bytes: Long) = String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))

    private companion object {
        const val MAX_TITLE = 70
    }
}
