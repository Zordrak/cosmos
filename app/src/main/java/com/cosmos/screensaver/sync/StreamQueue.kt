package com.cosmos.screensaver.sync

import com.cosmos.screensaver.catalogue.CachedOrder
import com.cosmos.screensaver.catalogue.StreamAttempt
import com.cosmos.screensaver.catalogue.StreamOrder
import com.cosmos.screensaver.catalogue.UserState
import com.cosmos.screensaver.rights.AssetProvider
import com.cosmos.screensaver.screensaver.ImagePicker
import com.cosmos.screensaver.screensaver.PickCandidate
import com.cosmos.screensaver.settings.OrderMode
import com.cosmos.screensaver.settings.SourceWeight
import com.cosmos.screensaver.source.Fetcher
import com.cosmos.screensaver.source.Lead
import com.cosmos.screensaver.source.SourceAdapter
import java.io.IOException
import kotlin.math.min
import kotlin.random.Random

/** The lead [ImagePicker] chose, and the order state to store if (and only if) its frame is shown. */
internal class StreamChoice(
    val leadKey: String,
    val adapter: SourceAdapter,
    val lead: Lead,
    val next: StreamOrder
)

/**
 * Leads (pictures found in a source listing) that can be streamed now. Discovery is a changing
 * subset of what a source offers, so leads are never persisted; only stable keys, the order state,
 * failed attempts and a bounded lead-to-picture association are, in [StreamOrder].
 *
 * Ordering reuses [ImagePicker] over lead keys: FAIR (weighted by source, favourites boosted once
 * a lead's picture is known), RANDOM (independent uniform choice) and SEQUENTIAL (provider, lead id).
 */
internal class StreamQueue {
    private class Available(val adapter: SourceAdapter, val lead: Lead)

    private val leads = linkedMapOf<String, Available>()
    private var servedSinceRefill = 0

    val size: Int get() = leads.size

    fun keyOf(adapter: SourceAdapter, lead: Lead) = "${adapter.provider.name}:${lead.id}"

    /** Forgets every lead, e.g. when the sources or quality changed and old leads may no longer apply. */
    fun clear() {
        leads.clear()
        servedSinceRefill = 0
    }

    /** True when every discovered lead has had a turn, so a new discovery is worth its network cost. */
    fun needsRefill(): Boolean = leads.isEmpty() || servedSinceRefill >= leads.size

    fun noteServed() {
        servedSinceRefill++
    }

    /** Replaces the available leads with a fresh discovery from every source, skipping blocked keys. */
    fun refill(
        adapters: List<SourceAdapter>,
        fetcher: Fetcher,
        random: Random,
        isBlocked: (key: String) -> Boolean = { false }
    ) {
        val found = linkedMapOf<String, Available>()
        adapters.shuffled(random).forEach { adapter ->
            val discovered = try {
                adapter.discover(fetcher, random).leads
            } catch (error: IOException) {
                emptyList()
            }
            discovered.forEach { lead ->
                val key = keyOf(adapter, lead)
                if (!isBlocked(key) && key !in found) found[key] = Available(adapter, lead)
            }
        }
        leads.clear()
        leads.putAll(found)
        servedSinceRefill = 0
    }

    /**
     * Chooses the next lead without changing any state. [failed] are leads that failed during this
     * call; leads still cooling down after earlier failures are skipped too.
     */
    fun choose(
        mode: OrderMode,
        state: UserState,
        nowMillis: Long,
        weightOf: (AssetProvider) -> SourceWeight,
        failed: Set<String>,
        random: Random
    ): StreamChoice? {
        if (leads.isEmpty()) return null
        val order = state.streamOrder
        val hashes = order.leadHashes.toMap()
        val candidates = leads.map { (key, available) ->
            val hash = hashes[key]
            val stats = hash?.let { state.stats[it] }
            PickCandidate(
                hash = key,
                provider = available.adapter.provider.name,
                title = "",
                weight = weightOf(available.adapter.provider),
                favourite = hash != null && state.isFavourite(hash),
                shownCount = stats?.shownCount ?: 0L,
                lastShownOrdinal = stats?.lastShownOrdinal ?: 0L
            )
        }
        val cooling = order.attempts.filter { it.retryAfterEpochMillis > nowMillis }.map { it.leadKey }.toSet()
        val pick = ImagePicker.pick(
            mode = mode,
            state = CachedOrder(order.fair, order.cursor),
            candidates = candidates,
            libraryHashes = hashes.keys,
            lastShownHash = order.lastAcceptedLead,
            excluded = failed + cooling,
            random = random,
            trackArrivals = false
        ) ?: return null
        val available = leads.getValue(pick.hash)
        return StreamChoice(
            leadKey = pick.hash,
            adapter = available.adapter,
            lead = available.lead,
            next = order.copy(fair = pick.next.fair, cursor = pick.next.cursor, lastAcceptedLead = pick.hash)
        )
    }

    companion object {
        const val MAX_LEAD_HASHES = 3000
        const val MAX_ATTEMPTS = 500
        private const val BASE_COOLDOWN_MILLIS = 15L * 60 * 1000
        private const val MAX_COOLDOWN_MILLIS = 24L * 60 * 60 * 1000

        /** Records a failed attempt with an exponentially growing cooldown, bounded in size. */
        fun withFailure(order: StreamOrder, leadKey: String, nowMillis: Long): StreamOrder {
            val failures = (order.attempts.firstOrNull { it.leadKey == leadKey }?.failures ?: 0) + 1
            val cooldown = min(MAX_COOLDOWN_MILLIS, BASE_COOLDOWN_MILLIS shl min(failures - 1, 7))
            val attempt = StreamAttempt(leadKey, failures, nowMillis, nowMillis + cooldown)
            val kept = order.attempts.filter { it.leadKey != leadKey && it.retryAfterEpochMillis > nowMillis }
            return order.copy(attempts = (kept + attempt).takeLast(MAX_ATTEMPTS))
        }

        /** Remembers which picture a lead turned out to be, oldest first and bounded. */
        fun withAssociation(order: StreamOrder, leadKey: String, hash: String): StreamOrder {
            if (hash.isBlank()) return order
            val others = order.leadHashes.filter { it.first != leadKey }
            return order.copy(leadHashes = (others + (leadKey to hash)).takeLast(MAX_LEAD_HASHES))
        }

        /** Applies an accepted display: the chosen order state, the association, and a clean attempt record. */
        fun withAccepted(current: StreamOrder, chosen: StreamOrder, leadKey: String, hash: String): StreamOrder {
            val merged = current.copy(fair = chosen.fair, cursor = chosen.cursor, lastAcceptedLead = leadKey)
            val associated = withAssociation(merged, leadKey, hash)
            return associated.copy(attempts = associated.attempts.filter { it.leadKey != leadKey })
        }
    }
}
