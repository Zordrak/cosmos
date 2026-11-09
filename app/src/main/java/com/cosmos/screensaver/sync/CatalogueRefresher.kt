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
    private val startIndex: Int = 0
) {
    data class Limits(
        val maxLeadsPerSource: Int = 60,
        val maxAdmissionsPerSource: Int = 8,
        /** Once the library is full, each refresh swaps in at most this many new images. */
        val maxAdmissionsWhenFull: Int = 15,
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
    private val policies = mutableMapOf<DjangoplicitySite, PolicyState>()
    private var withdrawnCount = 0

    /** Starts the walk through the sources at [startIndex], wrapping round, so none is always last. */
    private fun rotated(sorted: List<SourceAdapter>): List<SourceAdapter> {
        if (sorted.isEmpty()) return sorted
        val offset = Math.floorMod(startIndex, sorted.size)
        return sorted.drop(offset) + sorted.take(offset)
    }

    fun refresh(): RefreshResult {
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
        val errors = mutableListOf<String>()
        var admitted = 0
        var rejected = 0
        var anySourceReached = false
        var space = SpaceStatus()

        withdrawnCount += LibraryMaintenance.screenLocal(store, userState).removed

        val entriesAtStart = store.loadStrict().entries
        val usedAtStart = SelectionSnapshot.physicalBytes(entriesAtStart)
        val budgetAtStart = budgetFor(usedAtStart)
        if (!budgetAtStart.known) {
            space = SpaceStatus(measurementUnavailable = true)
        } else if (budgetAtStart.budgetBytes < limits.minBudgetBytes) {
            space = SpaceStatus(devicePressure = true)
        } else {
            recheckSavedPictures(newRejections, rejections)
            val full = usedAtStart + limits.downloadReserveBytes > budgetAtStart.budgetBytes
            var runAdmissions = 0
            sources@ for (adapter in rotated(adapters.sortedBy { it.provider.ordinal })) {
                val site = DjangoplicitySite.entries.firstOrNull { it.provider == adapter.provider }
                if (site != null && selection.licenceMode == LicenceMode.OPEN_LICENCE_ONLY) {
                    when (val state = policyFor(site)) {
                        is PolicyState.Unavailable -> {
                            errors += state.reason
                            continue@sources
                        }
                        PolicyState.Mismatch -> {
                            errors += "${site.label} usage policy no longer matches; its licensed images were withdrawn"
                            continue@sources
                        }
                        is PolicyState.Current -> Unit
                    }
                }
                val discovery = adapter.discover(fetcher, random)
                errors += discovery.errors
                if (discovery.reached) anySourceReached = true

                val satisfied = snapshot().eligibleKeys
                val state = userState.load()
                val keyPrefix = "${adapter.provider.name}:"
                val leads = discovery.leads
                    .filter { lead ->
                        val key = "$keyPrefix${lead.id}"
                        key !in satisfied && key !in rejections && !state.isBlocked(key, null)
                    }
                    .shuffled(random)

                var resolved = 0
                var sourceAdmissions = 0
                for (lead in leads) {
                    if (resolved >= limits.maxLeadsPerSource || sourceAdmissions >= limits.maxAdmissionsPerSource) break
                    if (full && runAdmissions >= limits.maxAdmissionsWhenFull) break@sources
                    resolved++
                    val key = "$keyPrefix${lead.id}"
                    val outcome = try {
                        when (val result = lead.resolve()) {
                            is CandidateResult.Skip -> Outcome.Rejected(result.reason)
                            is CandidateResult.Failed -> Outcome.Failed(result.reason)
                            is CandidateResult.Ready -> when (val made = admit(result.candidate, key)) {
                                is Outcome.Admitted -> commit(made.entry)
                                else -> made
                            }
                        }
                    } catch (error: IOException) {
                        Outcome.Failed(error.describe())
                    }
                    when (outcome) {
                        is Outcome.Admitted -> {
                            admitted++
                            sourceAdmissions++
                            runAdmissions++
                        }
                        is Outcome.Rejected -> {
                            val record = RejectionRecord(outcome.reason, clock())
                            rejections[key] = record
                            newRejections[key] = record
                            rejected++
                        }
                        is Outcome.Failed -> errors += "${lead.id}: ${outcome.reason}"
                        is Outcome.NoSpace -> {
                            space = SpaceStatus.of(outcome.reason)
                            if (outcome.reason == NoRoomReason.UNREADABLE) errors += "saved pictures could not be read"
                            break@sources
                        }
                        is Outcome.Stored -> error("admit() never returns Stored")
                    }
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
                lastAttemptAtEpochMillis = finishedAt,
                lastSuccessAtEpochMillis = if (anySourceReached) finishedAt else current.lastSuccessAtEpochMillis,
                lastSummary = partial.copy(total = available).summary,
                selectionFingerprint = fingerprint
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
        store.imagesDirectory.mkdirs()
        val failed = mutableSetOf<String>()
        var refilledThisCall = false
        fun refill() {
            queue.refill(adapters, fetcher, random) { key -> userState.load().isBlocked(key, null) }
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
                CatalogueEntry(
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
                    sha256 = stored.sha256,
                    byteCount = stored.byteCount,
                    widthPx = stored.width,
                    heightPx = stored.height,
                    fileName = stored.fileName,
                    contentPolicy = ContentFilter.POLICY_VERSION
                )
            )
        }
        return Outcome.Rejected(lastRejection)
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
            val previous = store.loadStrict().entries.firstOrNull { it.key == entry.key }
            if (previous != null && previous.sha256 != entry.sha256 && state.isFavourite(previous.sha256)) {
                LibraryMaintenance.discardIfUnreferenced(store, entry.fileName)
                return@locked Outcome.Rejected(FAVOURITE_KEPT)
            }
            store.update { current ->
                current.copy(entries = current.entries.filterNot { it.key == entry.key } + entry)
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
                fetcher.download(option.url, temporary, allowedBytes)
            } catch (error: ResourceTooLargeException) {
                return if (allowedBytes < option.maxBytes) Outcome.NoSpace(NoRoomReason.DEVICE_PRESSURE)
                else Outcome.Rejected(IMAGE_TOO_LARGE)
            } catch (error: IOException) {
                return Outcome.Failed("download failed (${error.describe()})")
            }
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

    private sealed interface Room {
        data class Ok(val freeBytes: Long) : Room
        data class Full(val reason: NoRoomReason) : Room
    }

    /**
     * Evicts whole pictures, those that no longer qualify first and then the oldest, until
     * [needBytes] more fit in the budget. Favourites are never evicted; when only favourites
     * remain, or the free space cannot be measured, this says why instead of a generic "no space".
     * [onDiskBytes] is a downloaded file that is not in the catalogue yet, but already occupies space.
     */
    private fun makeRoom(needBytes: Long, onDiskBytes: Long = 0L): Room {
        var latest = CacheBudget.UNKNOWN
        val result = CacheTrimmer.evictUntil(store, userState, selection, providers) { saved ->
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
