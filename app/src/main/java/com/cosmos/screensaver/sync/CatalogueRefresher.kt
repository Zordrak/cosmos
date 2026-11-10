package com.cosmos.screensaver.sync

import com.cosmos.screensaver.catalogue.CatalogueEntry
import com.cosmos.screensaver.catalogue.CatalogueStore
import com.cosmos.screensaver.catalogue.EvidenceRecord
import com.cosmos.screensaver.catalogue.LibraryMaintenance
import com.cosmos.screensaver.catalogue.RejectionRecord
import com.cosmos.screensaver.catalogue.UserStateStore
import com.cosmos.screensaver.rights.AssetProvider
import com.cosmos.screensaver.rights.DjangoplicityEvidenceVerifier
import com.cosmos.screensaver.rights.LicenceValidator
import com.cosmos.screensaver.rights.RequestedUse
import com.cosmos.screensaver.rights.RightsBasis
import com.cosmos.screensaver.rights.RightsCandidate
import com.cosmos.screensaver.rights.RightsDecision
import com.cosmos.screensaver.rights.RightsUseScope
import com.cosmos.screensaver.rights.VerifiedPolicy
import com.cosmos.screensaver.settings.ImageSelection
import com.cosmos.screensaver.settings.LicenceMode
import com.cosmos.screensaver.settings.OrderMode
import com.cosmos.screensaver.settings.SourceWeight
import com.cosmos.screensaver.settings.Resolution
import com.cosmos.screensaver.settings.SelectionSnapshot
import com.cosmos.screensaver.source.Candidate
import com.cosmos.screensaver.source.CandidateResult
import com.cosmos.screensaver.source.ContentFilter
import com.cosmos.screensaver.source.DjangoplicityParser
import com.cosmos.screensaver.source.DjangoplicitySite
import com.cosmos.screensaver.source.Fetcher
import com.cosmos.screensaver.source.ImageHeader
import com.cosmos.screensaver.source.Lead
import com.cosmos.screensaver.source.LeadFilter
import com.cosmos.screensaver.source.LeadStatus
import com.cosmos.screensaver.source.RenditionOption
import com.cosmos.screensaver.source.ResourceTooLargeException
import com.cosmos.screensaver.source.SourceAdapter
import com.cosmos.screensaver.source.SourceRejections
import com.cosmos.screensaver.source.describe
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random

internal data class ImageDimensions(val width: Int, val height: Int)

/** A streamed picture, the lead it came from, and the order state to store once its frame is shown. */
internal class Streamed(val entry: CatalogueEntry, val leadKey: String, val next: com.cosmos.screensaver.catalogue.StreamOrder)

/** Returns the decoded dimensions of a JPEG or PNG, or null if it is not a decodable image. */
internal fun interface ImageInspector {
    fun inspect(file: File): ImageDimensions?
}

internal data class RefreshResult(
    val admitted: Int,
    val rejected: Int,
    val withdrawn: Int,
    val total: Int,
    val errors: List<String>,
    val space: SpaceStatus = SpaceStatus()
) {
    /** The device is short of space (kept for callers that only care about streaming). */
    val lowSpace: Boolean get() = space.devicePressure

    val summary: String
        get() = buildString {
            append("$total images available. This refresh added $admitted")
            if (rejected > 0) append(", skipped $rejected")
            if (withdrawn > 0) append(", withdrew $withdrawn")
            append('.')
            if (space.devicePressure) append(" Not enough free space to keep a library; images are streamed instead.")
            if (space.favouritesPreventSaving) append(" Favourites fill the space for pictures, so no new pictures were saved.")
            if (space.userLimitFull) append(" The space for pictures is full.")
            if (space.measurementUnavailable) append(" Storage information unavailable; nothing was saved or removed.")
            if (errors.isNotEmpty()) append(" Problems: ").append(errors.joinToString("; "))
        }
}

/**
 * Discovers candidate images from the enabled sources, applies the user's [ImageSelection], and
 * admits downloads to the local library within a storage budget. Rights are checked only when
 * the selection asks for an open licence.
 *
 * It works on the LIVE catalogue and [userState] at every decision rather than on a copy taken at
 * the start, because a refresh runs for minutes while the user may block, favourite or remove
 * pictures. Network and image work never hold the library lock; every commit is a short
 * transaction under it. Blocked pictures are never admitted and favourites are never evicted.
 */
internal class CatalogueRefresher(
    private val fetcher: Fetcher,
    private val store: CatalogueStore,
    private val userState: UserStateStore,
    private val inspector: ImageInspector,
    private val territory: String?,
    private val adapters: List<SourceAdapter>,
    private val selection: ImageSelection = ImageSelection(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
    private val limits: Limits = Limits(),
    /** The budget for the library, given the bytes it (plus any file being added) occupies now. */
    private val budgetFor: (usedBytes: Long) -> CacheBudget = { CacheBudget.fixed(Long.MAX_VALUE) },
    /** Index into the provider-ordered sources at which this refresh starts, so every source gets a turn. */
    private val startIndex: Int = 0,
    /** True for a picture (by source key, and by content hash once known) that an undoable removal holds; it must not be saved again. */
    private val isStashed: (key: String, sha256: String) -> Boolean = { _, _ -> false },
    /** True while saving must wait (a removal can still be undone). */
    private val holdAdmissions: () -> Boolean = { false },
    /** Where progress is reported (what is downloading, how fast); null reports nothing. */
    private val progress: ProgressTracker? = null,
    /** False once the download mode no longer permits saving; checked before each source and picture. */
    private val allowSaving: () -> Boolean = { true },
    /**
     * Records each qualifying picture's metadata (page, rights, chosen rendition URL and declared
     * size) without downloading it. Used for the online index that Play without saving draws from.
     */
    private val indexOnly: Boolean = false
) {
    data class Limits(
        /**
         * A source whose leads keep failing to qualify this many times in a row is left alone for
         * the rest of the pass: it has nothing more worth saving right now. Not a cap on saving.
         */
        val maxConsecutiveMisses: Int = 500,
        /** A source whose downloads keep failing (unreachable, for instance) is left alone for the pass. */
        val maxConsecutiveFailures: Int = 6,
        val minBudgetBytes: Long = 50L * 1024 * 1024,
        val downloadReserveBytes: Long = 12L * 1024 * 1024,
        /** Free space that must remain after a download is written. */
        val headroomFloorBytes: Long = 90L * 1024 * 1024,
        /** A download is not started when less than this may be written. */
        val minDownloadBytes: Long = 1L * 1024 * 1024,
        val rejectionRetryMillis: Long = 30L * 24 * 60 * 60 * 1000,
        /** Saved pictures screened against the current content rules per refresh, one request each. */
        val maxRechecksPerRefresh: Int = 60
    )

    private sealed interface Outcome {
        data class Admitted(val entry: CatalogueEntry) : Outcome
        data class Rejected(val reason: String) : Outcome
        data class Failed(val reason: String) : Outcome
        data class NoSpace(val reason: NoRoomReason) : Outcome

        /** Not saved because an undoable removal holds it; no decision is remembered. */
        data object Held : Outcome
        data class Stored(
            val fileName: String,
            val sha256: String,
            val byteCount: Long,
            val width: Int,
            val height: Int
        ) : Outcome
    }

    private class Licence(
        val basis: String,
        val name: String,
        val url: String,
        val evidence: List<EvidenceRecord>,
        val validatedAt: Long,
        val policyVersion: String,
        val territory: String
    )

    private sealed interface LicenceOutcome {
        data class Cleared(val licence: Licence) : LicenceOutcome
        data class Rejected(val reason: String) : LicenceOutcome
        data class Failed(val reason: String) : LicenceOutcome
    }

    private sealed interface PolicyState {
        data class Current(val policy: VerifiedPolicy) : PolicyState
        data class Unavailable(val reason: String) : PolicyState
        data object Mismatch : PolicyState
    }

    private val providers = adapters.map { it.provider }.toSet()

    /** One source's place in a pass: its queue of untried leads and why it may have stopped. */
    private class SourceTurn(val adapter: SourceAdapter) {
        private val keyPrefix = "${adapter.provider.name}:"
        val queue = ArrayDeque<Lead>()
        var hasFresh = false
        var discoveries = 0
        var misses = 0
        var failures = 0
        var finished = false

        fun keyOf(id: String) = "$keyPrefix$id"
    }
    private val policies = mutableMapOf<DjangoplicitySite, PolicyState>()
    private var withdrawnCount = 0

    /** The run being reported to; a refresher serves one operation at a time. */
    private var run: ProgressRun? = null

    /** Starts the walk through the sources at [startIndex], wrapping round, so none is always last. */
    private fun rotated(sorted: List<SourceAdapter>): List<SourceAdapter> {
        if (sorted.isEmpty()) return sorted
        val offset = Math.floorMod(startIndex, sorted.size)
        return sorted.drop(offset) + sorted.take(offset)
    }

    fun refresh(): RefreshResult {
        val current = progress?.begin()
        run = current
        try {
            return runRefresh()
        } catch (error: Throwable) {
            current?.fail(error)
            throw error
        } finally {
            current?.end()
            run = null
        }
    }

    private fun runRefresh(): RefreshResult {
        val startedAt = clock()
        val initial = store.loadStrict()
        // An unreadable list of blocked pictures must stop a refresh, not let them back in.
        userState.loadStrict()
        val fingerprint = fingerprintOf(selection, providers)
        val selectionChanged = initial.selectionFingerprint != fingerprint
        store.imagesDirectory.mkdirs()

        val rejections = initial.rejections
            .filterValues { startedAt - it.atEpochMillis in 0 until limits.rejectionRetryMillis }
            .filterValues { !(selectionChanged && isSettingsDependent(it.reason)) }
            .toMutableMap()
        val droppedRejectionKeys = initial.rejections.keys - rejections.keys
        val newRejections = mutableMapOf<String, RejectionRecord>()
        var flushedRejections = 0
        val errors = mutableListOf<String>()
        var admitted = 0
        var rejected = 0
        var anySourceReached = false
        var space = SpaceStatus()
        var stoppedByPolicy = false
        var interrupted = false

        withdrawnCount += LibraryMaintenance.screenLocal(store, userState, filesExpected = !indexOnly).removed

        val entriesAtStart = store.loadStrict().entries
        val usedAtStart = SelectionSnapshot.physicalBytes(entriesAtStart)
        val budgetAtStart = budgetFor(usedAtStart)
        if (!budgetAtStart.known) {
            space = SpaceStatus(measurementUnavailable = true)
        } else if (budgetAtStart.budgetBytes < limits.minBudgetBytes) {
            space = SpaceStatus(devicePressure = true)
        } else if (!allowSaving()) {
            stoppedByPolicy = true
        } else {
            recheckSavedPictures(newRejections, rejections)
            // Every lead this pass has dealt with (resolved, or deliberately passed over), so a source's
            // frontier always moves on and a pass always ends. What was already showable when the pass
            // began counts as dealt with, so a picture removed meanwhile is not fetched straight back.
            val tried = snapshot().eligibleKeys.toMutableSet()
            val turns = mutableListOf<SourceTurn>()
            sources@ for (adapter in rotated(adapters.sortedBy { it.provider.ordinal })) {
                if (!allowSaving()) {
                    stoppedByPolicy = true
                    break@sources
                }
                val site = DjangoplicitySite.entries.firstOrNull { it.provider == adapter.provider }
                if (site != null && selection.licenceMode == LicenceMode.OPEN_LICENCE_ONLY) {
                    when (val state = policyFor(site)) {
                        is PolicyState.Unavailable -> {
                            errors += state.reason
                            run?.error(ProgressErrorKind.DISCOVERY, state.reason, adapter.label)
                            continue@sources
                        }
                        PolicyState.Mismatch -> {
                            errors += "${site.label} usage policy no longer matches; its licensed images were withdrawn"
                            continue@sources
                        }
                        is PolicyState.Current -> Unit
                    }
                }
                turns += SourceTurn(adapter)
            }

            /** Finds the source's next untried leads: pictures removed for room first, then new ones. */
            fun replenish(turn: SourceTurn): Boolean {
                val adapter = turn.adapter
                run?.discovering(adapter.label)
                // Pictures evicted earlier in this very pass must not look new.
                store.load().rejections.forEach { (key, record) ->
                    if (record.reason == LibraryMaintenance.EVICTED && key !in rejections) rejections[key] = record
                }
                val satisfied = snapshot().eligibleKeys
                val state = userState.load()
                // Worked out once per discovery: a listing can name thousands of pictures.
                val required = requiredResolution()
                val leadFilter = object : LeadFilter {
                    override fun status(id: String): LeadStatus {
                        val key = turn.keyOf(id)
                        if (key in tried || key in satisfied || state.isBlocked(key, null) || isStashed(key, "")) {
                            return LeadStatus.DONE
                        }
                        return when (rejections[key]?.reason) {
                            null -> LeadStatus.FRESH
                            LibraryMaintenance.EVICTED -> LeadStatus.RETIRED
                            else -> LeadStatus.DONE
                        }
                    }

                    override fun fits(width: Int, height: Int) = required?.accepts(width, height) ?: true
                }
                val discovery = adapter.discover(fetcher, random, leadFilter)
                if (discovery.incomplete) interrupted = true
                if (turn.discoveries++ == 0) {
                    errors += discovery.errors
                    discovery.errors.lastOrNull()?.let { run?.error(ProgressErrorKind.DISCOVERY, it, adapter.label) }
                }
                if (discovery.reached) anySourceReached = true
                val (retiredLeads, freshLeads) = discovery.leads
                    .filter { lead -> leadFilter.status(lead.id) != LeadStatus.DONE }
                    .distinctBy { it.id }
                    .partition { rejections[turn.keyOf(it.id)]?.reason == LibraryMaintenance.EVICTED }
                turn.hasFresh = freshLeads.isNotEmpty()
                turn.queue.addAll(retiredLeads.sortedBy { rejections.getValue(turn.keyOf(it.id)).atEpochMillis })
                turn.queue.addAll(freshLeads.shuffled(random))
                return turn.queue.isNotEmpty()
            }

            /**
             * One source's turn: resolve its leads until one picture is saved. The source is finished
             * when it has nothing new left, or keeps failing, so the others carry on without it.
             */
            fun takeTurn(turn: SourceTurn): Boolean {
                val adapter = turn.adapter
                var spareRoom: Boolean? = null
                while (true) {
                    if (holdAdmissions()) return false
                    if (!allowSaving()) {
                        stoppedByPolicy = true
                        return false
                    }
                    if (turn.queue.isEmpty() && !replenish(turn)) {
                        turn.finished = true
                        return true
                    }
                    val lead = turn.queue.removeFirst()
                    val key = turn.keyOf(lead.id)
                    if (!tried.add(key)) continue
                    if (rejections[key]?.reason == LibraryMaintenance.EVICTED) {
                        // Never bring back what this same pass removed; otherwise only into spare room,
                        // or by rotation once this source has nothing new left to try.
                        if (rejections.getValue(key).atEpochMillis >= startedAt) continue
                        if (spareRoom == null) spareRoom = hasSpareRoom()
                        if (spareRoom != true && turn.hasFresh) continue
                    }
                    spareRoom = null
                    run?.discovering(adapter.label)
                    val outcome = try {
                        when (val result = lead.resolve()) {
                            is CandidateResult.Skip -> Outcome.Rejected(result.reason)
                            is CandidateResult.Failed -> Outcome.Failed(result.reason)
                            is CandidateResult.Ready -> when (val made = admit(result.candidate, key)) {
                                is Outcome.Admitted -> if (indexOnly) commitIndexed(made.entry) else commit(made.entry)
                                else -> made
                            }
                        }
                    } catch (error: IOException) {
                        Outcome.Failed(error.describe())
                    }
                    when (outcome) {
                        is Outcome.Admitted -> {
                            admitted++
                            turn.misses = 0
                            turn.failures = 0
                            run?.admitted()
                            return true
                        }
                        is Outcome.Rejected -> {
                            val record = RejectionRecord(outcome.reason, clock())
                            rejections[key] = record
                            newRejections[key] = record
                            rejected++
                            turn.failures = 0
                            run?.skipped()
                            // A long pass keeps its decisions as it goes, so being stopped part-way
                            // does not mean fetching the same unsuitable pages again next time.
                            if (newRejections.size - flushedRejections >= REJECTION_FLUSH_EVERY) {
                                store.update { it.copy(rejections = it.rejections + newRejections) }
                                flushedRejections = newRejections.size
                            }
                        }
                        is Outcome.Failed -> {
                            errors += "${lead.id}: ${outcome.reason}"
                            run?.error(ProgressErrorKind.DOWNLOAD, outcome.reason, adapter.label)
                            if (++turn.failures >= limits.maxConsecutiveFailures) {
                                interrupted = true
                                turn.finished = true
                                return true
                            }
                        }
                        Outcome.Held -> Unit
                        is Outcome.NoSpace -> {
                            space = SpaceStatus.of(outcome.reason)
                            if (outcome.reason == NoRoomReason.UNREADABLE) errors += "saved pictures could not be read"
                            return false
                        }
                        is Outcome.Stored -> error("admit() never returns Stored")
                    }
                    if (++turn.misses >= limits.maxConsecutiveMisses) {
                        turn.finished = true
                        return true
                    }
                }
            }

            // The sources take turns a picture at a time, so each gets a fair share of the space,
            // and the pass carries on until the library is full or every source has nothing new.
            rounds@ while (!stoppedByPolicy && turns.any { !it.finished }) {
                for (turn in turns) {
                    if (turn.finished) continue
                    if (!takeTurn(turn)) break@rounds
                }
            }
        }

        val finishedAt = clock()
        val partial = RefreshResult(admitted, rejected, withdrawnCount, 0, errors.distinct().take(4), space)
        val saved = store.update { current ->
            // Only this refresh's own decisions are applied; admissions, removals and blocks made
            // meanwhile (by this refresh or by the user) are already in [current] and are kept.
            val available = SelectionSnapshot.build(current.entries, selection, providers, userState.load()).eligible.size
            current.copy(
                rejections = (current.rejections - droppedRejectionKeys) + newRejections,
                lastAttemptAtEpochMillis = if (stoppedByPolicy) current.lastAttemptAtEpochMillis else finishedAt,
                lastSuccessAtEpochMillis = if (anySourceReached) finishedAt else current.lastSuccessAtEpochMillis,
                lastSummary = if (stoppedByPolicy) current.lastSummary else partial.copy(total = available).summary,
                // A pass cut short by the download mode is not a completed look, so the selection stays due.
                selectionFingerprint = if (stoppedByPolicy) current.selectionFingerprint else fingerprint,
                // Room left but a source could not be read: worth looking again soon, not in a day.
                moreToFetch = if (stoppedByPolicy) current.moreToFetch else interrupted && !space.any
            )
        }
        LibraryMaintenance.deleteOrphanFiles(store, clock())
        val total = SelectionSnapshot.build(saved.entries, selection, providers, userState.load()).eligible.size
        return partial.copy(total = total)
    }

    /**
     * Fetches one qualifying image for immediate display, without keeping a library. The file is
     * stored in [store]; the caller must delete it after use. Used when there is too little free
     * space to cache images. Returns null when no image could be obtained.
     */
    fun streamNext(
        queue: StreamQueue,
        mode: OrderMode,
        weightOf: (AssetProvider) -> SourceWeight,
        isCancelled: () -> Boolean = { false }
    ): Streamed? {
        val current = progress?.begin()
        run = current
        try {
            return streamProbe(queue, mode, weightOf, isCancelled)
        } catch (error: Throwable) {
            current?.fail(error)
            throw error
        } finally {
            current?.end()
            run = null
        }
    }

    private fun streamProbe(
        queue: StreamQueue,
        mode: OrderMode,
        weightOf: (AssetProvider) -> SourceWeight,
        isCancelled: () -> Boolean
    ): Streamed? {
        store.imagesDirectory.mkdirs()
        val failed = mutableSetOf<String>()
        var refilledThisCall = false
        fun refill() {
            run?.discovering()
            val quick = queue.isEmpty
            queue.refill(adapters, fetcher, random, { key -> userState.load().isBlocked(key, null) }, quick) { label ->
                run?.discovering(label)
            }
            refilledThisCall = true
        }
        repeat(MAX_STREAM_PROBES) {
            if (isCancelled()) return null
            if (queue.needsRefill() && !refilledThisCall) refill()
            val state = userState.load()
            var choice = queue.choose(mode, state, clock(), weightOf, failed, random)
            if (choice == null && !refilledThisCall) {
                refill()
                choice = queue.choose(mode, userState.load(), clock(), weightOf, failed, random)
            }
            if (choice == null) return null
            queue.noteServed()
            val key = choice.leadKey
            if (state.isBlocked(key, null)) {
                failed += key
                return@repeat
            }
            val outcome = try {
                when (val result = choice.lead.resolve()) {
                    is CandidateResult.Ready -> admit(result.candidate, key)
                    else -> null
                }
            } catch (error: IOException) {
                null
            }
            if (outcome !is Outcome.Admitted) {
                failed += key
                run?.skipped()
                recordStreamFailure(key)
                return@repeat
            }
            val entry = outcome.entry
            val file = store.imageFile(entry)
            LibraryMaintenance.release(file)
            val current = userState.load()
            when {
                isCancelled() -> {
                    file.delete()
                    return null
                }
                current.isBlocked(entry.key, entry.sha256) -> {
                    file.delete()
                    failed += key
                    recordStreamFailure(key)
                }
                current.lastShownHash == entry.sha256 -> {
                    // The same picture again (e.g. under another name): learn the alias, try another lead.
                    file.delete()
                    failed += key
                    userState.tryUpdate { s ->
                        s.copy(streamOrder = StreamQueue.withAssociation(s.streamOrder, key, entry.sha256))
                    }
                }
                else -> return Streamed(entry, key, choice.next)
            }
        }
        return null
    }

    private fun recordStreamFailure(leadKey: String) {
        val now = clock()
        userState.tryUpdate { s -> s.copy(streamOrder = StreamQueue.withFailure(s.streamOrder, leadKey, now)) }
    }

    private fun snapshot(): SelectionSnapshot =
        SelectionSnapshot.build(store.loadStrict().entries, selection, providers, userState.loadStrict())

    /** The resolution new images must meet; lower while a fallback quality is in use. */
    private fun requiredResolution(): Resolution? =
        if (snapshot().fallbackActive) selection.quality.fallback else selection.quality.minimum

    /**
     * Screens saved pictures that were last checked against older content rules. Their stored
     * metadata has no description, so each one is re-read from its source; a hit withdraws it (or
     * hides it, if it is a favourite) and a clean result is remembered so it is not asked again.
     */
    private fun recheckSavedPictures(
        newRejections: MutableMap<String, RejectionRecord>,
        rejections: MutableMap<String, RejectionRecord>
    ) {
        val pending = store.load().entries
            .filter { it.contentPolicy != ContentFilter.POLICY_VERSION && it.hiddenReason.isBlank() }
            .take(limits.maxRechecksPerRefresh)
        for (entry in pending) {
            val adapter = adapters.firstOrNull { it.provider == entry.provider } ?: continue
            val reason = try {
                adapter.recheck(fetcher, entry.providerId)
            } catch (error: IOException) {
                continue
            }
            if (reason == null) {
                stampContentPolicy(entry)
            } else {
                withdrawnCount += LibraryMaintenance.withdraw(store, userState, reason) { it.key == entry.key }
                val record = RejectionRecord(reason, clock())
                rejections[entry.key] = record
                newRejections[entry.key] = record
            }
        }
    }

    private fun stampContentPolicy(entry: CatalogueEntry) = CatalogueStore.locked {
        try {
            store.update { current ->
                current.copy(
                    entries = current.entries.map {
                        if (it.key == entry.key && it.sha256 == entry.sha256) {
                            it.copy(contentPolicy = ContentFilter.POLICY_VERSION)
                        } else {
                            it
                        }
                    }
                )
            }
        } catch (error: IOException) {
            // Left unstamped; it is checked again next time.
        }
    }

    /**
     * Picks the renditions worth downloading, smallest first. Declared sizes let most
     * low-resolution images be skipped without downloading anything.
     */
    private fun renditionPlan(options: List<RenditionOption>, required: Resolution?): List<RenditionOption> {
        val suitable = options.filter { option ->
            val width = option.width
            val height = option.height
            if (width == null || height == null) true
            else ImageSelection.withinMaximum(width, height) && (required?.accepts(width, height) ?: true)
        }
        return if (required == null) suitable.take(1) else suitable
    }

    /**
     * Resolves rights and obtains the file. The returned entry's file is on disk and claimed so
     * cleanup leaves it alone; [commit] (or the streaming caller) releases it.
     */
    private fun admit(candidate: Candidate, key: String): Outcome {
        val declaredWidth = candidate.declaredWidth
        val declaredHeight = candidate.declaredHeight
        if (declaredWidth != null && declaredHeight != null && !ImageSelection.suitableAspect(declaredWidth, declaredHeight)) {
            return Outcome.Rejected(SourceRejections.NOT_LANDSCAPE)
        }
        val existing = store.load().entries.firstOrNull { it.key == key }
        val required = requiredResolution()
        val plan = renditionPlan(candidate.options, required)
        if (plan.isEmpty()) {
            val exceedsMaximum = candidate.options.any { option ->
                val width = option.width
                val height = option.height
                width != null && height != null && !ImageSelection.withinMaximum(width, height) &&
                    (required?.accepts(width, height) ?: true)
            }
            return Outcome.Rejected(if (exceedsMaximum) EXCEEDS_MAX_DIMENSIONS else LOW_RESOLUTION)
        }

        var lastRejection = LOW_RESOLUTION
        for (option in plan) {
            val licence = when (val outcome = licenceFor(candidate, option)) {
                is LicenceOutcome.Cleared -> outcome.licence
                is LicenceOutcome.Rejected -> return Outcome.Rejected(outcome.reason)
                is LicenceOutcome.Failed -> return Outcome.Failed(outcome.reason)
            }

            if (indexOnly) {
                // Recorded from the page alone; the file is fetched (and its size checked) when shown.
                val width = option.width
                val height = option.height
                if (width == null || height == null) return Outcome.Failed("size not declared")
                return Outcome.Admitted(entryFor(candidate, option, licence, "", 0L, width, height, ""))
            }

            val stored = reuse(existing, option.url)
                ?: when (val downloaded = download(candidate, key, option, required)) {
                    is Outcome.Stored -> downloaded
                    is Outcome.Rejected -> {
                        lastRejection = downloaded.reason
                        // A larger rendition may still qualify.
                        if (downloaded.reason == LOW_RESOLUTION) continue
                        return downloaded
                    }
                    else -> return downloaded
                }
            if (required != null && !required.accepts(stored.width, stored.height)) {
                LibraryMaintenance.release(File(store.imagesDirectory, stored.fileName))
                lastRejection = LOW_RESOLUTION
                continue
            }
            if (!ImageSelection.withinMaximum(stored.width, stored.height)) {
                LibraryMaintenance.release(File(store.imagesDirectory, stored.fileName))
                lastRejection = EXCEEDS_MAX_DIMENSIONS
                continue
            }

            return Outcome.Admitted(
                entryFor(candidate, option, licence, stored.sha256, stored.byteCount, stored.width, stored.height, stored.fileName)
            )
        }
        return Outcome.Rejected(lastRejection)
    }

    private fun entryFor(
        candidate: Candidate,
        option: RenditionOption,
        licence: Licence,
        sha256: String,
        byteCount: Long,
        width: Int,
        height: Int,
        fileName: String
    ) = CatalogueEntry(
        provider = candidate.provider,
        providerId = candidate.id,
        title = candidate.title,
        sourceLabel = sourceLabel(candidate),
        sourcePageUrl = candidate.pageUrl,
        assetUrl = option.url,
        credit = candidate.credit.ifBlank { sourceLabel(candidate) },
        rightsBasis = licence.basis,
        licenceName = licence.name,
        licenceUrl = licence.url,
        territory = licence.territory,
        evidence = licence.evidence,
        validatedAtEpochMillis = licence.validatedAt,
        policyVersion = licence.policyVersion,
        admittedAtEpochMillis = clock(),
        sha256 = sha256,
        byteCount = byteCount,
        widthPx = width,
        heightPx = height,
        fileName = fileName,
        contentPolicy = ContentFilter.POLICY_VERSION
    )

    /** Saves a picture recorded without its file (the online index): no file to claim or clean up. */
    private fun commitIndexed(entry: CatalogueEntry): Outcome = CatalogueStore.locked {
        try {
            if (userState.loadStrict().isBlocked(entry.key, null)) return@locked Outcome.Rejected(LibraryMaintenance.BLOCKED)
            store.update { current ->
                current.copy(
                    entries = current.entries.filterNot { it.key == entry.key } + entry,
                    rejections = current.rejections - entry.key
                )
            }
            Outcome.Admitted(entry)
        } catch (error: IOException) {
            Outcome.Failed("unable to record picture (${error.describe()})")
        }
    }

    /**
     * Saves an admitted entry in one short transaction that re-reads the live state: a picture
     * blocked while it downloaded is discarded and stays blocked, and a favourite is never
     * replaced by a different rendition of the same source key.
     */
    private fun commit(entry: CatalogueEntry): Outcome = CatalogueStore.locked {
        // Holding the lock makes releasing the claim safe: cleanup cannot run until we finish.
        LibraryMaintenance.release(store.imageFile(entry))
        try {
            val state = userState.loadStrict()
            if (state.isBlocked(entry.key, entry.sha256)) {
                userState.addBlockedAlias(entry.key, entry.sha256)
                LibraryMaintenance.discardIfUnreferenced(store, entry.fileName)
                return@locked Outcome.Rejected(LibraryMaintenance.BLOCKED)
            }
            if (isStashed(entry.key, entry.sha256)) {
                LibraryMaintenance.discardIfUnreferenced(store, entry.fileName)
                return@locked Outcome.Held
            }
            val previous = store.loadStrict().entries.firstOrNull { it.key == entry.key }
            if (previous != null && previous.sha256 != entry.sha256 && state.isFavourite(previous.sha256)) {
                LibraryMaintenance.discardIfUnreferenced(store, entry.fileName)
                return@locked Outcome.Rejected(FAVOURITE_KEPT)
            }
            store.update { current ->
                current.copy(
                    entries = current.entries.filterNot { it.key == entry.key } + entry,
                    rejections = if (current.rejections[entry.key]?.reason == LibraryMaintenance.EVICTED) {
                        current.rejections - entry.key
                    } else {
                        current.rejections
                    }
                )
            }
            if (previous != null && previous.fileName != entry.fileName) {
                LibraryMaintenance.discardIfUnreferenced(store, previous.fileName)
            }
            Outcome.Admitted(entry)
        } catch (error: IOException) {
            LibraryMaintenance.discardIfUnreferenced(store, entry.fileName)
            Outcome.Failed("unable to save image (${error.describe()})")
        }
    }

    private fun sourceLabel(candidate: Candidate) =
        candidate.djangoplicitySite?.label ?: com.cosmos.screensaver.source.Sources.label(candidate.provider)

    private fun licenceFor(candidate: Candidate, option: RenditionOption): LicenceOutcome {
        if (selection.licenceMode == LicenceMode.ANY_AVAILABLE) {
            return LicenceOutcome.Cleared(
                Licence(
                    basis = CatalogueEntry.RIGHTS_NOT_CHECKED,
                    name = "",
                    url = "",
                    evidence = emptyList(),
                    validatedAt = 0L,
                    policyVersion = "",
                    territory = territory.orEmpty()
                )
            )
        }
        val site = candidate.djangoplicitySite
        val page = candidate.djangoplicityPage
        if (site == null || page == null) return publicDomainLicence(candidate)

        val policy = when (val state = policyFor(site)) {
            is PolicyState.Current -> state.policy
            is PolicyState.Unavailable -> return LicenceOutcome.Failed(state.reason)
            PolicyState.Mismatch -> return LicenceOutcome.Rejected("RIGHTS_POLICY_CHANGED")
        }
        val decision = LicenceValidator(
            verifier = DjangoplicityEvidenceVerifier(policy, page, candidate.pageRetrievedAtEpochMillis),
            currentTimeMillis = clock
        ).validate(
            RightsCandidate(
                provider = candidate.provider,
                providerId = candidate.id,
                sourcePageUrl = candidate.pageUrl,
                originalAssetUrl = option.url,
                creatorCredit = candidate.credit,
                intendedUse = RequestedUse(territory, RightsUseScope.PERSONAL_NONCOMMERCIAL_SCREENSAVER)
            )
        )
        val rights = when (decision) {
            is RightsDecision.Rejected -> return LicenceOutcome.Rejected("RIGHTS_${decision.reason.name}")
            is RightsDecision.RightsCleared -> decision.rights
        }
        return LicenceOutcome.Cleared(
            Licence(
                basis = rights.basis.name,
                name = LICENCE_NAME,
                url = rights.licenceUrl ?: return LicenceOutcome.Rejected("RIGHTS_INVALID_LICENCE_URL"),
                evidence = rights.evidenceSources.map {
                    EvidenceRecord(it.evidenceUrl, it.exactStatement, it.scope.name, it.retrievedAtEpochMillis)
                },
                validatedAt = rights.validatedAtEpochMillis,
                policyVersion = rights.policyVersion,
                territory = rights.intendedUse.territory
            )
        )
    }

    /** NASA-credited works are public domain unless the page marks them as copyrighted. */
    private fun publicDomainLicence(candidate: Candidate): LicenceOutcome {
        if (candidate.provider != AssetProvider.NASA && candidate.provider != AssetProvider.APOD) {
            return LicenceOutcome.Rejected("RIGHTS_UNSUPPORTED_SOURCE")
        }
        if (candidate.copyrightMarked) return LicenceOutcome.Rejected("RIGHTS_COPYRIGHT_MARKED")
        val now = clock()
        return LicenceOutcome.Cleared(
            Licence(
                basis = RightsBasis.NASA_PUBLIC_DOMAIN.name,
                name = "NASA public domain",
                url = NASA_MEDIA_GUIDELINES_URL,
                evidence = listOf(
                    EvidenceRecord(
                        candidate.pageUrl,
                        "NASA-published image with no copyright marking on its page",
                        "ASSET_PAGE",
                        now
                    )
                ),
                validatedAt = now,
                policyVersion = LicenceValidator.POLICY_VERSION,
                territory = territory.orEmpty()
            )
        )
    }

    private fun policyFor(site: DjangoplicitySite): PolicyState = policies.getOrPut(site) {
        val retrievedAt = clock()
        val html = try {
            fetcher.fetchText(site.policyUrl)
        } catch (error: IOException) {
            return@getOrPut PolicyState.Unavailable("${site.label} usage policy unavailable (${error.describe()})")
        }
        if (DjangoplicityParser.policyIsCurrent(html, site)) {
            PolicyState.Current(VerifiedPolicy(site, retrievedAt))
        } else {
            // Favourites are kept but hidden; everything else licensed under the old policy goes.
            withdrawnCount += LibraryMaintenance.withdraw(store, userState, "RIGHTS_POLICY_CHANGED") {
                it.provider == site.provider && it.rightsBasis == RightsBasis.CC_BY_4_0.name
            }
            PolicyState.Mismatch
        }
    }

    /** Reuses an already downloaded copy of [assetUrl] instead of fetching it again. */
    private fun reuse(existing: CatalogueEntry?, assetUrl: String): Outcome.Stored? {
        if (existing == null || existing.assetUrl != assetUrl) return null
        val file = File(store.imagesDirectory, existing.fileName)
        if (!file.isFile) return null
        LibraryMaintenance.claim(file)
        return Outcome.Stored(existing.fileName, existing.sha256, existing.byteCount, existing.widthPx, existing.heightPx)
    }

    private fun download(candidate: Candidate, key: String, option: RenditionOption, required: Resolution?): Outcome {
        val room = makeRoom(min(option.maxBytes, limits.downloadReserveBytes))
        if (room is Room.Full) return Outcome.NoSpace(room.reason)
        // Never let one write take the device below the headroom floor, whatever the library's own budget says.
        val writable = (room as Room.Ok).freeBytes.let { if (it == Long.MAX_VALUE) it else it - limits.headroomFloorBytes }
        if (writable < limits.minDownloadBytes) return Outcome.NoSpace(NoRoomReason.DEVICE_PRESSURE)
        val allowedBytes = min(option.maxBytes, writable)
        val temporary = File(store.imagesDirectory, "download-${UUID.randomUUID()}.part")
        LibraryMaintenance.claim(temporary)
        var claimedTarget: File? = null
        var stored = false
        try {
            val download = try {
                run?.downloading(candidate.title, sourceLabel(candidate))
                fetcher.download(option.url, temporary, allowedBytes)
            } catch (error: ResourceTooLargeException) {
                return if (allowedBytes < option.maxBytes) Outcome.NoSpace(NoRoomReason.DEVICE_PRESSURE)
                else Outcome.Rejected(IMAGE_TOO_LARGE)
            } catch (error: IOException) {
                return Outcome.Failed("download failed (${error.describe()})")
            }
            run?.checking()
            val header = temporary.inputStream().use { input ->
                val buffer = ByteArray(HEADER_BYTES)
                buffer.copyOf(input.read(buffer).coerceAtLeast(0))
            }
            val extension = when {
                ImageHeader.isJpeg(header) -> "jpg"
                ImageHeader.isPng(header) -> "png"
                else -> return Outcome.Rejected("NOT_AN_IMAGE")
            }
            val dimensions = inspector.inspect(temporary) ?: return Outcome.Rejected("UNDECODABLE_IMAGE")
            if (dimensions.width <= 0 || dimensions.height <= 0) return Outcome.Rejected("UNDECODABLE_IMAGE")
            if (!ImageSelection.withinMaximum(dimensions.width, dimensions.height)) {
                return Outcome.Rejected(EXCEEDS_MAX_DIMENSIONS)
            }
            if (required != null && !required.accepts(dimensions.width, dimensions.height)) {
                return Outcome.Rejected(LOW_RESOLUTION)
            }
            if (!ImageSelection.suitableAspect(dimensions.width, dimensions.height)) {
                return Outcome.Rejected(SourceRejections.NOT_LANDSCAPE)
            }
            val declaredWidth = candidate.declaredWidth
            val declaredHeight = candidate.declaredHeight
            if (declaredWidth != null && declaredHeight != null && declaredHeight > 0) {
                val expected = declaredWidth.toDouble() / declaredHeight
                val actual = dimensions.width.toDouble() / dimensions.height
                if (abs(actual - expected) / expected > ImageSelection.ASPECT_TOLERANCE) {
                    return Outcome.Rejected("DIMENSION_MISMATCH")
                }
            }
            if (userState.load().isBlocked(key, download.sha256)) {
                userState.addBlockedAlias(key, download.sha256)
                return Outcome.Rejected(LibraryMaintenance.BLOCKED)
            }

            val fileName = "${download.sha256}.$extension"
            val target = File(store.imagesDirectory, fileName)
            val alreadySaved = target.isFile
            // The bytes are only on disk once; a second picture with identical bytes shares the file.
            if (!alreadySaved) {
                val room = makeRoom(0L, onDiskBytes = download.byteCount)
                if (room is Room.Full) return Outcome.NoSpace(room.reason)
            }
            LibraryMaintenance.claim(target)
            claimedTarget = target
            if (alreadySaved && target.length() == download.byteCount && sha256Of(target) == download.sha256) {
                temporary.delete()
            } else {
                // Missing, or damaged on disk: replace it with the freshly verified download.
                if (target.exists() && !target.delete()) return Outcome.Failed("unable to replace damaged image")
                if (!temporary.renameTo(target)) return Outcome.Failed("unable to store image")
            }
            stored = true
            return Outcome.Stored(fileName, download.sha256, download.byteCount, dimensions.width, dimensions.height)
        } finally {
            temporary.delete()
            LibraryMaintenance.release(temporary)
            if (!stored) claimedTarget?.let(LibraryMaintenance::release)
        }
    }

    /** True when another picture fits without removing any: the only time a retired picture may return. */
    private fun hasSpareRoom(): Boolean {
        val used = SelectionSnapshot.physicalBytes(store.load().entries)
        val budget = budgetFor(used)
        return budget.known && used + limits.downloadReserveBytes <= budget.budgetBytes
    }

    private sealed interface Room {
        data class Ok(val freeBytes: Long) : Room
        data class Full(val reason: NoRoomReason) : Room
    }

    /**
     * Evicts whole pictures until [needBytes] more fit in the budget: first those the current view
     * would not show, then, only from pictures already shown since they were saved, the least
     * recently shown from whichever source holds the most. A picture not yet seen is never replaced
     * by another, so how fast the library changes follows how much it is watched. Favourites are
     * never evicted; when nothing may go, or the free space cannot be measured, this says why.
     * [onDiskBytes] is a downloaded file that is not in the catalogue yet, but already occupies space.
     */
    private fun makeRoom(needBytes: Long, onDiskBytes: Long = 0L): Room {
        var latest = CacheBudget.UNKNOWN
        val result = CacheTrimmer.evictUntil(store, userState, selection, providers, clock, replacing = true) { saved ->
            val used = saved + onDiskBytes
            latest = budgetFor(used)
            // An unknown measurement stops eviction: never delete on a guess.
            !latest.known || used + needBytes <= latest.budgetBytes
        }
        return when {
            result.unreadable -> Room.Full(NoRoomReason.UNREADABLE)
            !latest.known -> Room.Full(NoRoomReason.UNAVAILABLE)
            result.satisfied -> Room.Ok(latest.freeBytes)
            latest.devicePressure || latest.spaceConstrained -> Room.Full(NoRoomReason.DEVICE_PRESSURE)
            result.protectedRemain -> Room.Full(NoRoomReason.FAVOURITES)
            else -> Room.Full(NoRoomReason.USER_LIMIT)
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val LICENCE_NAME = "CC BY 4.0"
        const val NASA_MEDIA_GUIDELINES_URL = "https://www.nasa.gov/nasa-brand-center/images-and-media/"
        const val LOW_RESOLUTION = "LOW_RESOLUTION"
        const val IMAGE_TOO_LARGE = "IMAGE_TOO_LARGE"
        const val EXCEEDS_MAX_DIMENSIONS = "EXCEEDS_MAX_DIMENSIONS"
        const val FAVOURITE_KEPT = "FAVOURITE_KEPT"
        private const val HEADER_BYTES = 32
        private const val REJECTION_FLUSH_EVERY = 20
        private const val MAX_STREAM_PROBES = 12

        /** What the saved library was last refreshed for: the image rules and which sources are on. */
        internal fun fingerprintOf(selection: ImageSelection, providers: Set<AssetProvider>): String =
            selection.fingerprint + "|" + providers.map { it.name }.sorted().joinToString(",")

        /** Rejections that a changed selection or policy could overturn. */
        internal fun isSettingsDependent(reason: String) =
            reason.startsWith("RIGHTS_") || reason in setOf(
                LOW_RESOLUTION, IMAGE_TOO_LARGE, EXCEEDS_MAX_DIMENSIONS, "IMAGE_TOO_SMALL",
                SourceRejections.UNSUITABLE_TYPE, "NOT_AN_OBSERVATION", SourceRejections.NO_SUITABLE_RENDITION,
                SourceRejections.NOT_A_PHOTOGRAPH
            )
    }
}
