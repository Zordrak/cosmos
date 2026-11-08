package com.cosmos.screensaver.screensaver

import com.cosmos.screensaver.catalogue.CatalogueEntry
import kotlin.random.Random

/** Shuffles through every available image before repeating, never showing one twice in a row. */
internal class SlideshowOrder(private val random: Random = Random.Default) {
    private val queue = ArrayDeque<String>()
    private var last: String? = null

    fun next(available: List<String>): String? {
        val distinct = available.distinct()
        if (distinct.isEmpty()) return null
        queue.retainAll(distinct.toSet())
        if (queue.isEmpty()) {
            val shuffled = distinct.shuffled(random).toMutableList()
            if (shuffled.size > 1 && shuffled.first() == last) {
                shuffled.add(shuffled.removeAt(0))
            }
            queue.addAll(shuffled)
        }
        return queue.removeFirst().also { last = it }
    }
}

internal data class Attribution(
    val title: String,
    val credit: String,
    val source: String
) {
    companion object {
        /** The credit is reproduced in full and unaltered, as the source licences require. */
        fun of(entry: CatalogueEntry) = Attribution(
            title = entry.title,
            credit = "Credit: ${entry.credit}",
            source = "${entry.sourceLabel}  \u00b7  ${entry.licenceName}  \u00b7  ${displayUrl(entry.sourcePageUrl)}"
        )

        fun displayUrl(url: String) = url.removePrefix("https://").removeSuffix("/")
    }
}

/** Largest power-of-two sample size that still leaves at least the fitted on-screen resolution. */
internal fun sampleSizeFor(imageWidth: Int, imageHeight: Int, targetWidth: Int, targetHeight: Int): Int {
    if (imageWidth <= 0 || imageHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) return 1
    val scale = minOf(targetWidth.toDouble() / imageWidth, targetHeight.toDouble() / imageHeight)
    if (scale >= 1.0) return 1
    var sample = 1
    while (sample * 2 <= 1.0 / scale) sample *= 2
    return sample
}
