package com.cosmos.screensaver.sync

import com.cosmos.screensaver.rights.AssetProvider
import com.cosmos.screensaver.source.CandidateResult
import com.cosmos.screensaver.source.Discovery
import com.cosmos.screensaver.source.Fetcher
import com.cosmos.screensaver.source.Lead
import com.cosmos.screensaver.source.SourceAdapter
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import kotlin.random.Random

class StreamQueueTest {
    private class Adapter(override val provider: AssetProvider, private val ids: List<String>) : SourceAdapter {
        override val label = "Stub"
        override fun discover(fetcher: Fetcher, random: Random) =
            Discovery(ids.map { Lead(it) { CandidateResult.Skip("n/a") } }, emptyList(), true)
    }

    private val noFetcher = object : Fetcher {
        override fun fetchText(url: String, maxBytes: Int) = error("unused")
        override fun fetchPrefix(url: String, maxBytes: Int) = error("unused")
        override fun download(url: String, destination: File, maxBytes: Long) = error("unused")
    }

    @Test
    fun blockedLeadsNeverEnterThePendingQueue() {
        val queue = StreamQueue()
        queue.refill(
            listOf(Adapter(AssetProvider.NASA, listOf("a", "b", "c"))),
            noFetcher,
            Random(1),
            isBlocked = { it == "NASA:b" }
        )

        assertEquals(setOf("a", "c"), queue.pending.map { it.second.id }.toSet())
    }

    @Test
    fun whenEverythingIsTriedOrBlockedOnlyBlockedLeadsStayOut() {
        val queue = StreamQueue()
        queue.tried += setOf("NASA:a", "NASA:b")
        queue.refill(
            listOf(Adapter(AssetProvider.NASA, listOf("a", "b"))),
            noFetcher,
            Random(1),
            isBlocked = { it == "NASA:a" }
        )

        assertEquals(listOf("b"), queue.pending.map { it.second.id })
    }
}
