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
            source = listOf(
                entry.sourceLabel,
                entry.licenceName,
                displayUrl(entry.licenceUrl),
                displayUrl(entry.sourcePageUrl)
            )
                .filter { it.isNotBlank() }
                .joinToString("  \u00b7  ")
        )

        fun displayUrl(url: String) = url.removePrefix("https://").removeSuffix("/")
    }
}
