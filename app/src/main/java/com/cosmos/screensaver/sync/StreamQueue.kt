package com.cosmos.screensaver.sync

import com.cosmos.screensaver.source.Fetcher
import com.cosmos.screensaver.source.Lead
import com.cosmos.screensaver.source.SourceAdapter
import java.io.IOException
import kotlin.random.Random

/** Leads waiting to be streamed, interleaved across sources, and the ones already tried. */
internal class StreamQueue {
    val pending = ArrayDeque<Pair<SourceAdapter, Lead>>()
    val tried = mutableSetOf<String>()

    /** Discovers new leads from every source and queues them round-robin, in random order. */
    fun refill(adapters: List<SourceAdapter>, fetcher: Fetcher, random: Random) {
        val discovered = adapters.shuffled(random).map { adapter ->
            adapter to try {
                adapter.discover(fetcher, random).leads
            } catch (error: IOException) {
                emptyList()
            }
        }
        fun fresh() = discovered.map { (adapter, leads) ->
            leads.filter { "${adapter.provider.name}:${it.id}" !in tried }
                .shuffled(random)
                .map { adapter to it }
        }
        var perSource = fresh()
        // A discovery may only offer images that were already shown; forget them and go round again.
        if (perSource.all { it.isEmpty() }) {
            tried.clear()
            perSource = fresh()
        }
        val longest = perSource.maxOfOrNull { it.size } ?: 0
        for (index in 0 until longest) perSource.forEach { leads -> leads.getOrNull(index)?.let(pending::addLast) }
    }
}
