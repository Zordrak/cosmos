package com.cosmos.screensaver.screensaver

import com.cosmos.screensaver.catalogue.CachedOrder
import com.cosmos.screensaver.catalogue.FairState
import com.cosmos.screensaver.catalogue.SequentialCursor
import com.cosmos.screensaver.catalogue.UserState
import com.cosmos.screensaver.rights.AssetProvider
import com.cosmos.screensaver.settings.OrderMode
import com.cosmos.screensaver.settings.SelectionSnapshot
import com.cosmos.screensaver.settings.SourceWeight
import java.util.Locale
import kotlin.random.Random

/** One distinct, eligible picture as the picker sees it. [hash] is the picture identity (C1). */
internal data class PickCandidate(
    val hash: String,
    val provider: String,
    val title: String,
    val weight: SourceWeight = SourceWeight.NORMAL,
    val favourite: Boolean = false,
    val admittedAtEpochMillis: Long = 0L,
    val shownCount: Long = 0L,
    val lastShownOrdinal: Long = 0L
)

/**
 * The chosen picture and the ordering state to store once (and only once) it has actually been
 * displayed. [priorityNew] is true when a newly arrived picture jumped the queue.
 */
internal class OrderPick(val hash: String, val next: CachedOrder, val priorityNew: Boolean)

/**
 * Chooses the next saved picture. Pure: no I/O, no clock; every input and the random source are
 * parameters, so the sequence is reproducible in tests and the state survives restarts.
 *
 * FAIR is a small weighted virtual-service scheduler. Each picture has an integer service score;
 * showing it adds its cost (Normal 4, Less 8, More 2, halved for a favourite) and the lowest score
 * is served next, so with equal weights every picture is shown once before any repeats and with
 * unequal weights frequency is inversely proportional to cost. New pictures wait in a FIFO and are
 * served ahead of the ordinary pool after every four ordinary displays.
 */
internal object ImagePicker {
    const val MAX_SCORE = 8
    const val PRIORITY_AFTER = 4
    private const val MAX_RECENT = 5

    fun cost(weight: SourceWeight, favourite: Boolean): Int {
        val base = when (weight) {
            SourceWeight.LESS -> 8
            SourceWeight.NORMAL -> 4
            SourceWeight.MORE -> 2
        }
        return if (favourite) base / 2 else base
    }

    /**
     * @param state the stored order state (not modified).
     * @param libraryHashes every picture identity saved on the device, so history of pictures that are
     * merely filtered out is kept while permanently removed ones are forgotten.
     * @param lastShownHash the last picture actually displayed (cached or streamed), never repeated
     * immediately when another choice exists.
     * @param excluded pictures that failed during this attempt only.
     */
    fun pick(
        mode: OrderMode,
        state: CachedOrder,
        candidates: List<PickCandidate>,
        libraryHashes: Set<String>,
        lastShownHash: String?,
        excluded: Set<String>,
        random: Random
    ): OrderPick? {
        val members = candidates.distinctBy { it.hash }.sortedBy { it.hash }
        val usable = members.filter { it.hash !in excluded }
        if (usable.isEmpty()) return null
        return when (mode) {
            OrderMode.RANDOM -> pickRandom(state, usable, lastShownHash, random)
            OrderMode.SEQUENTIAL -> pickSequential(state, usable, lastShownHash)
            OrderMode.FAIR -> pickFair(state, members, usable, libraryHashes, lastShownHash, random)
        }
    }

    private fun pickRandom(state: CachedOrder, usable: List<PickCandidate>, last: String?, random: Random): OrderPick {
        val pool = usable.filter { it.hash != last }.ifEmpty { usable }
        return OrderPick(pool[random.nextInt(pool.size)].hash, state, priorityNew = false)
    }

    private val tupleOrder = Comparator<PickCandidate> { a, b ->
        compareValues(a.provider, b.provider).takeIf { it != 0 }
            ?: compareValues(a.title.lowercase(Locale.ROOT), b.title.lowercase(Locale.ROOT)).takeIf { it != 0 }
            ?: compareValues(a.hash, b.hash)
    }

    private fun comesAfter(candidate: PickCandidate, cursor: SequentialCursor): Boolean {
        val byProvider = candidate.provider.compareTo(cursor.provider)
        if (byProvider != 0) return byProvider > 0
        val byTitle = candidate.title.lowercase(Locale.ROOT).compareTo(cursor.title)
        if (byTitle != 0) return byTitle > 0
        return candidate.hash.compareTo(cursor.id) > 0
    }

    private fun pickSequential(state: CachedOrder, usable: List<PickCandidate>, last: String?): OrderPick {
        val ordered = usable.sortedWith(tupleOrder)
        val cursor = state.cursor
        val start = if (cursor == null) 0 else ordered.indexOfFirst { comesAfter(it, cursor) }.let { if (it < 0) 0 else it }
        val chosen = (0 until ordered.size).map { ordered[(start + it) % ordered.size] }
            .firstOrNull { it.hash != last } ?: ordered[start]
        val next = state.copy(cursor = SequentialCursor(chosen.provider, chosen.title.lowercase(Locale.ROOT), chosen.hash))
        return OrderPick(chosen.hash, next, priorityNew = false)
    }

    private fun pickFair(
        state: CachedOrder,
        members: List<PickCandidate>,
        usable: List<PickCandidate>,
        libraryHashes: Set<String>,
        last: String?,
        random: Random
    ): OrderPick {
        val fair = reconcile(state.fair, members, libraryHashes)
        val usableHashes = usable.map { it.hash }.toSet()
        val pendingSet = fair.pendingNew.toSet()
        val ordinary = usable.filter { it.hash !in pendingSet }
        val pendingUsable = fair.pendingNew.filter { it in usableHashes }
        val pendingNotLast = pendingUsable.filter { it != last }
        val ordinaryNotLast = ordinary.filter { it.hash != last }

        val priority = when {
            pendingUsable.isEmpty() -> false
            ordinary.isEmpty() -> true
            fair.regularSinceNew >= PRIORITY_AFTER -> true
            ordinaryNotLast.isEmpty() && pendingNotLast.isNotEmpty() -> true
            else -> false
        }
        val byHash = members.associateBy { it.hash }
        val chosen: PickCandidate = if (priority) {
            byHash.getValue(pendingNotLast.firstOrNull() ?: pendingUsable.first())
        } else {
            chooseOrdinary(ordinaryNotLast.ifEmpty { ordinary }, fair, members, random)
        }

        val scores = fair.scores.toMutableMap()
        var pending = fair.pendingNew
        var regular = fair.regularSinceNew
        val chosenCost = cost(chosen.weight, chosen.favourite)
        if (priority) {
            pending = pending - chosen.hash
            val floor = ordinary.minOfOrNull { scores[it.hash] ?: 0 } ?: 0
            scores[chosen.hash] = floor + chosenCost
            regular = 0
        } else {
            scores[chosen.hash] = (scores[chosen.hash] ?: 0) + chosenCost
            regular = minOf(PRIORITY_AFTER, regular + 1)
        }
        val pendingAfter = pending.toSet()
        val activePool = usable.filter { it.hash !in pendingAfter }
        val floor = activePool.minOfOrNull { scores[it.hash] ?: 0 } ?: 0
        val normalised = scores.mapValues { (it.value - floor).coerceIn(0, MAX_SCORE) }
        val next = fair.copy(scores = normalised, pendingNew = pending, regularSinceNew = regular)
        return OrderPick(chosen.hash, state.copy(fair = next), priorityNew = priority)
    }

    /** Lowest score wins; among equals prefer pictures outside the few most recently shown, then pick at random. */
    private fun chooseOrdinary(pool: List<PickCandidate>, fair: FairState, members: List<PickCandidate>, random: Random): PickCandidate {
        val lowest = pool.minOf { fair.scores[it.hash] ?: 0 }
        val tied = pool.filter { (fair.scores[it.hash] ?: 0) == lowest }
        val recentCount = minOf(MAX_RECENT, members.size / 4)
        val recent = members.filter { it.lastShownOrdinal > 0 }
            .sortedByDescending { it.lastShownOrdinal }
            .take(recentCount)
            .map { it.hash }
            .toSet()
        val preferred = tied.filter { it.hash !in recent }.ifEmpty { tied }
        return preferred[random.nextInt(preferred.size)]
    }

    /**
     * Brings the stored state in line with the pictures eligible now without resetting anyone:
     * keeps scores and the FIFO, queues genuinely new pictures once, and rejoins returning ones at
     * the current floor.
     */
    internal fun reconcile(fair: FairState, members: List<PickCandidate>, libraryHashes: Set<String>): FairState {
        val memberHashes = members.map { it.hash }.toSet()
        if (!fair.initialized) {
            return FairState(
                initialized = true,
                scores = members.associate { it.hash to 0 },
                known = memberHashes,
                pendingNew = emptyList(),
                regularSinceNew = PRIORITY_AFTER
            )
        }
        val scores = fair.scores.filterKeys { it in memberHashes }.toMutableMap()
        val pending = fair.pendingNew.filter { it in memberHashes && it !in scores }.distinct()
        val floor = scores.values.minOrNull() ?: 0
        val arrivals = mutableListOf<PickCandidate>()
        for (member in members) {
            if (member.hash in scores || member.hash in pending) continue
            if (member.hash !in fair.known && member.shownCount == 0L) arrivals += member else scores[member.hash] = floor
        }
        val queued = pending + arrivals.sortedWith(compareBy({ it.admittedAtEpochMillis }, { it.hash })).map { it.hash }
        val known = (fair.known + memberHashes).filter { it in libraryHashes || it in memberHashes }.toSet()
        return fair.copy(scores = scores, known = known, pendingNew = queued)
    }

    /** One candidate per distinct picture, using the strongest weight among its eligible aliases (never summed). */
    fun candidates(
        snapshot: SelectionSnapshot,
        state: UserState,
        weightOf: (AssetProvider) -> SourceWeight,
        skipKeys: Set<String> = emptySet()
    ): List<PickCandidate> = snapshot.aliasesByIdentity.mapNotNull { (identity, aliases) ->
        val live = aliases.filter { it.key !in skipKeys }
        val entry = live.firstOrNull() ?: return@mapNotNull null
        val stats = state.stats[identity]
        PickCandidate(
            hash = identity,
            provider = entry.provider.name,
            title = entry.title,
            weight = live.map { weightOf(it.provider) }.maxByOrNull { it.ordinal } ?: SourceWeight.NORMAL,
            favourite = state.isFavourite(entry.sha256),
            admittedAtEpochMillis = live.minOf { it.admittedAtEpochMillis },
            shownCount = stats?.shownCount ?: 0L,
            lastShownOrdinal = stats?.lastShownOrdinal ?: 0L
        )
    }
}
