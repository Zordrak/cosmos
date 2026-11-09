package com.cosmos.screensaver.ui

import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * The one way every screen writes a size. The size choices are 512 MB, 1 GB, 2 GB and so on in
 * binary units, so 1 GB here is 1,024 MB. Using the system formatter (which switches to 1,000-based
 * units) would make "4 GB" on a size row disagree with the amounts beside it.
 */
internal object ByteSizes {
    private const val KIB = 1024L
    private const val MIB = KIB * 1024
    private const val GIB = MIB * 1024
    private val UNITS = listOf("B" to 1L, "KB" to KIB, "MB" to MIB, "GB" to GIB)

    /** "0 B", "512 KB", "379 MB", "2.5 GB", "4 GB". Negative sizes are shown as zero. */
    fun format(bytes: Long, locale: Locale = Locale.getDefault()): String {
        val value = bytes.coerceAtLeast(0L)
        var index = UNITS.indexOfLast { value >= it.second }.coerceAtLeast(0)
        var shown = display(value, UNITS[index].second, locale)
        // 1,023.6 MB rounds up to 1,024 MB: say 1 GB instead.
        if (index < UNITS.lastIndex && roundedWhole(value, UNITS[index].second) >= 1024) {
            index++
            shown = display(value, UNITS[index].second, locale)
        }
        return "$shown ${UNITS[index].first}"
    }

    private fun roundedWhole(bytes: Long, unit: Long) = Math.round(bytes.toDouble() / unit)

    private fun display(bytes: Long, unit: Long, locale: Locale): String {
        if (unit == 1L) return bytes.toString()
        val value = bytes.toDouble() / unit
        if (value >= 10) return Math.round(value).toString()
        val tenths = Math.round(value * 10)
        if (tenths % 10 == 0L) return (tenths / 10).toString()
        val separator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
        return "${tenths / 10}$separator${tenths % 10}"
    }
}
