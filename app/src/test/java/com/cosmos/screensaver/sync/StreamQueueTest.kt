package com.cosmos.screensaver.sync

import com.cosmos.screensaver.catalogue.PictureStats
import com.cosmos.screensaver.catalogue.StreamAttempt
import com.cosmos.screensaver.catalogue.StreamOrder
import com.cosmos.screensaver.catalogue.UserState
import com.cosmos.screensaver.rights.AssetProvider
import com.cosmos.screensaver.settings.OrderMode
import com.cosmos.screensaver.settings.SourceWeight
import com.cosmos.screensaver.source.CandidateResult
import com.cosmos.screensaver.source.Discovery
import com.cosmos.screensaver.source.Fetcher
import com.cosmos.screensaver.source.Lead
import com.cosmos.screensaver.source.SourceAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    private val normal: (AssetProvider) -> SourceWeight = { SourceWeight.NORMAL }

    private fun queueOf(vararg adapters: Adapter, isBlocked: (String) -> Boolean = { false }) = StreamQueue().also {
        it.refill(adapters.toList(), noFetcher, Random(1), isBlocked)
    }

    private fun choose(
        queue: StreamQueue,
        state: UserState = UserState(),
        mode: OrderMode = OrderMode.FAIR,
        now: Long = 1_000L,
        failed: Set<String> = emptySet(),
        random: Random = Random(7)
    ) = queue.choose(mode, state, now, normal, failed, random)

    @Test
    fun blockedLeadsNeverEnterTheQueue() {
        val queue = queueOf(Adapter(AssetProvider.NASA, listOf("a", "b", "c")), isBlocked = { it == "NASA:b" })

        assertEquals(2, queue.size)
        val seen = mutableSetOf<String>()
        var state = UserState()
        repeat(2) {
            val choice = choose(queue, state)!!
            seen += choice.leadKey
            state = state.copy(streamOrder = choice.next)
        }
        assertEquals(setOf("NASA:a", "NASA:c"), seen)
    }

    @Test
    fun sequentialModeServesLeadsInKeyOrderAndWrapsAround() {
        val queue = queueOf(Adapter(AssetProvider.NASA, listOf("c", "a", "b")))
        var state = UserState()
        val order = (1..4).map {
            val choice = choose(queue, state, OrderMode.SEQUENTIAL)!!
            state = state.copy(streamOrder = choice.next)
            choice.leadKey
        }

        assertEquals(listOf("NASA:a", "NASA:b", "NASA:c", "NASA:a"), order)
    }

    @Test
    fun randomModeIsAnIndependentChoiceThatCanRepeatEverythingButTheLastLead() {
        val queue = queueOf(Adapter(AssetProvider.NASA, listOf("a", "b", "c")))
        val random = Random(3)
        var state = UserState()
        val picks = (1..60).map {
            val choice = choose(queue, state, OrderMode.RANDOM, random = random)!!
            state = state.copy(streamOrder = choice.next)
            choice.leadKey
        }

        assertEquals(setOf("NASA:a", "NASA:b", "NASA:c"), picks.toSet())
        assertTrue(picks.zipWithNext().none { (x, y) -> x == y })
    }

    @Test
    fun failedAndCoolingLeadsAreSkippedUntilTheCooldownEnds() {
        val queue = queueOf(Adapter(AssetProvider.NASA, listOf("a", "b")))
        val failedOnce = StreamQueue.withFailure(StreamOrder(), "NASA:a", 1_000L)
        val state = UserState(streamOrder = failedOnce)
        val retryAt = failedOnce.attempts.single().retryAfterEpochMillis

        assertEquals("NASA:b", choose(queue, state, now = 2_000L)!!.leadKey)
        val afterCooldown = (1..20).map { choose(queue, state, OrderMode.RANDOM, now = retryAt + 1, random = Random(it))!!.leadKey }
        assertTrue("NASA:a" in afterCooldown)
    }

    @Test
    fun theFailedSetOfThisCallIsSkippedToo() {
        val queue = queueOf(Adapter(AssetProvider.NASA, listOf("a", "b")))

        assertEquals("NASA:b", choose(queue, failed = setOf("NASA:a"))!!.leadKey)
        assertNull(choose(queue, failed = setOf("NASA:a", "NASA:b")))
    }

    @Test
    fun cooldownGrowsWithRepeatedFailuresAndIsCapped() {
        var order = StreamOrder()
        var now = 0L
        val delays = (1..12).map {
            order = StreamQueue.withFailure(order, "NASA:a", now)
            val attempt = order.attempts.single()
            (attempt.retryAfterEpochMillis - now).also { now = attempt.retryAfterEpochMillis + 1 }
        }

        assertTrue(delays[1] > delays[0])
        assertEquals(24L * 60 * 60 * 1000, delays.last())
        assertEquals(12, order.attempts.single().failures)
    }

    @Test
    fun attemptsAndAssociationsStayBounded() {
        var order = StreamOrder()
        repeat(StreamQueue.MAX_ATTEMPTS + 50) { order = StreamQueue.withFailure(order, "NASA:$it", 10L) }
        repeat(StreamQueue.MAX_LEAD_HASHES + 50) { order = StreamQueue.withAssociation(order, "NASA:$it", "h$it") }

        assertEquals(StreamQueue.MAX_ATTEMPTS, order.attempts.size)
        assertEquals(StreamQueue.MAX_LEAD_HASHES, order.leadHashes.size)
        assertEquals("NASA:${StreamQueue.MAX_LEAD_HASHES + 49}", order.leadHashes.last().first)
        assertFalse(order.leadHashes.any { it.first == "NASA:0" })
    }

    @Test
    fun acceptingALeadRecordsItsPictureAndClearsItsFailures() {
        val failed = StreamQueue.withFailure(StreamOrder(), "NASA:a", 1_000L)
        val chosen = failed.copy(lastAcceptedLead = "NASA:a")

        val accepted = StreamQueue.withAccepted(failed, chosen, "NASA:a", "hash-a")

        assertEquals("NASA:a", accepted.lastAcceptedLead)
        assertEquals(listOf("NASA:a" to "hash-a"), accepted.leadHashes)
        assertTrue(accepted.attempts.isEmpty())
    }

    @Test
    fun aFavouriteLeadBecomesKnownAndOutranksOthersOnceItsPictureIsAssociated() {
        val queue = queueOf(Adapter(AssetProvider.NASA, listOf("a", "b", "c", "d")))
        val state = UserState(
            favourites = setOf("hash-c"),
            stats = mapOf("hash-c" to PictureStats(shownCount = 1, lastShownOrdinal = 1)),
            streamOrder = StreamOrder(leadHashes = listOf("NASA:c" to "hash-c"))
        )
        var cState = state
        var cPicks = 0
        repeat(200) {
            val choice = choose(queue, cState, OrderMode.FAIR, random = Random(it))!!
            if (choice.leadKey == "NASA:c") cPicks++
            cState = state.copy(streamOrder = choice.next.copy(attempts = emptyList()))
        }

        assertTrue("favourite picked $cPicks times", cPicks > 200 / 4)
    }

    @Test
    fun sourceWeightsSteerFairStreamingTowardsMoreWeightedSources() {
        val queue = queueOf(
            Adapter(AssetProvider.NASA, listOf("n1", "n2", "n3")),
            Adapter(AssetProvider.ESA_HUBBLE, listOf("h1", "h2", "h3"))
        )
        val weights = { provider: AssetProvider -> if (provider == AssetProvider.ESA_HUBBLE) SourceWeight.MORE else SourceWeight.LESS }
        var state = UserState()
        val random = Random(11)
        var hubble = 0
        repeat(300) {
            val choice = queue.choose(OrderMode.FAIR, state, 1_000L, weights, emptySet(), random)!!
            if (choice.adapter.provider == AssetProvider.ESA_HUBBLE) hubble++
            state = state.copy(streamOrder = choice.next)
        }

        assertTrue("hubble picked $hubble of 300", hubble > 180)
    }

    @Test
    fun anEmptyQueueChoosesNothingAndNeedsARefill() {
        val queue = StreamQueue()

        assertNull(choose(queue))
        assertTrue(queue.needsRefill())
    }

    @Test
    fun needsRefillOnceEveryLeadHasHadATurn() {
        val queue = queueOf(Adapter(AssetProvider.NASA, listOf("a", "b")))

        assertFalse(queue.needsRefill())
        queue.noteServed()
        assertFalse(queue.needsRefill())
        queue.noteServed()
        assertTrue(queue.needsRefill())
    }

    @Test
    fun choosingDoesNotChangeAnyState() {
        val queue = queueOf(Adapter(AssetProvider.NASA, listOf("a", "b")))
        val state = UserState()

        val choice = choose(queue, state)

        assertNotNull(choice)
        assertEquals(UserState(), state)
        assertEquals(2, queue.size)
    }

    @Test
    fun clearForgetsEveryLead() {
        val queue = queueOf(Adapter(AssetProvider.NASA, listOf("a")))
        queue.clear()

        assertEquals(0, queue.size)
        assertNull(choose(queue))
    }

    @Test
    fun anAttemptRecordCarriesItsCooldownThroughState() {
        val attempt = StreamAttempt("NASA:a", 2, 100L, 500L)
        val state = UserState(streamOrder = StreamOrder(attempts = listOf(attempt)))
        val queue = queueOf(Adapter(AssetProvider.NASA, listOf("a", "b")))

        assertEquals("NASA:b", choose(queue, state, OrderMode.SEQUENTIAL, now = 200L)!!.leadKey)
        assertEquals("NASA:a", choose(queue, state, OrderMode.SEQUENTIAL, now = 600L)!!.leadKey)
    }
}
