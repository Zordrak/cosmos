package com.cosmos.screensaver.sync

import com.cosmos.screensaver.catalogue.Catalogue
import com.cosmos.screensaver.catalogue.CatalogueEntry
import com.cosmos.screensaver.catalogue.CatalogueStore
import com.cosmos.screensaver.catalogue.EvidenceRecord
import com.cosmos.screensaver.catalogue.RejectionRecord
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
import com.cosmos.screensaver.settings.Resolution
import com.cosmos.screensaver.source.Candidate
import com.cosmos.screensaver.source.CandidateResult
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
import java.util.UUID
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random

internal data class ImageDimensions(val width: Int, val height: Int)

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
    val lowSpace: Boolean = false
) {
    val summary: String
        get() = buildString {
            append("$total images available. This refresh added $admitted")
            if (rejected > 0) append(", skipped $rejected")
            if (withdrawn > 0) append(", withdrew $withdrawn")
            append('.')
            if (lowSpace) append(" Not enough free space to keep a library; images are streamed instead.")
            if (errors.isNotEmpty()) append(" Problems: ").append(errors.joinToString("; "))
        }
}

/**
 * Discovers candidate images from the enabled sources, applies the user's [ImageSelection], and
 * admits downloads to the local library within a storage budget. Rights are checked only when
 * the selection asks for an open licence.
 */
internal class CatalogueRefresher(
    private val fetcher: Fetcher,
    private val store: CatalogueStore,
    private val inspector: ImageInspector,
    private val territory: String?,
    private val adapters: List<SourceAdapter>,
    private val selection: ImageSelection = ImageSelection(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
    private val limits: Limits = Limits(),
    /** Maximum bytes the library may occupy, given the bytes it occupies now. */
    private val budgetFor: (usedBytes: Long) -> Long = { Long.MAX_VALUE }
) {
    data class Limits(
        val maxEntries: Int = 150,
        val maxLeadsPerSource: Int = 60,
        val maxAdmissionsPerSource: Int = 8,
        /** Once the library is full, each refresh swaps in at most this many new images. */
        val maxAdmissionsWhenFull: Int = 15,
        val minBudgetBytes: Long = 50L * 1024 * 1024,
        val downloadReserveBytes: Long = 12L * 1024 * 1024,
        val rejectionRetryMillis: Long = 30L * 24 * 60 * 60 * 1000
    )

    private sealed interface Outcome {
        data class Admitted(val entry: CatalogueEntry) : Outcome
        data class Rejected(val reason: String) : Outcome
        data class Failed(val reason: String) : Outcome
        data object NoSpace : Outcome
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

    fun refresh(): RefreshResult {
        val startedAt = clock()
        val initial = store.load()
        val fingerprint = selection.fingerprint
        val selectionChanged = initial.selectionFingerprint != fingerprint
        store.imagesDirectory.mkdirs()
        val entries = initial.entries.filter { File(store.imagesDirectory, it.fileName).isFile }.toMutableList()
        val rejections = initial.rejections
            .filterValues { startedAt - it.atEpochMillis in 0 until limits.rejectionRetryMillis }
            .filterValues { !(selectionChanged && isSettingsDependent(it.reason)) }
            .toMutableMap()
        val errors = mutableListOf<String>()
        val withdrawnKeys = mutableSetOf<String>()
        var admitted = 0
        var rejected = 0
        var anySourceReached = false
        var lowSpace = false

        val usedAtStart = entries.sumOf { it.byteCount }
        val budgetAtStart = budgetFor(usedAtStart)
        if (budgetAtStart < limits.minBudgetBytes) {
            lowSpace = true
        } else {
            val full = entries.size >= limits.maxEntries || usedAtStart + limits.downloadReserveBytes > budgetAtStart
            var runAdmissions = 0
            sources@ for (adapter in adapters.sortedBy { it.provider.ordinal }) {
                val site = DjangoplicitySite.entries.firstOrNull { it.provider == adapter.provider }
                if (site != null && selection.licenceMode == LicenceMode.OPEN_LICENCE_ONLY) {
                    when (val state = policyFor(site, entries, withdrawnKeys)) {
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

                val satisfied = selection.eligible(entries.filter { it.provider in providers }).map { it.key }.toSet()
                val keyPrefix = "${adapter.provider.name}:"
                val leads = discovery.leads
                    .filter { "$keyPrefix${it.id}".let { key -> key !in satisfied && key !in rejections } }
                    .shuffled(random)

                var resolved = 0
                var sourceAdmissions = 0
                for (lead in leads) {
                    if (resolved >= limits.maxLeadsPerSource || sourceAdmissions >= limits.maxAdmissionsPerSource) break
                    if (full && runAdmissions >= limits.maxAdmissionsWhenFull) break@sources
                    resolved++
                    val key = "$keyPrefix${lead.id}"
                    val outcome = when (val result = lead.resolve()) {
                        is CandidateResult.Skip -> Outcome.Rejected(result.reason)
                        is CandidateResult.Failed -> Outcome.Failed(result.reason)
                        is CandidateResult.Ready -> admit(
                            result.candidate,
                            entries.firstOrNull { it.key == key },
                            entries,
                            withdrawnKeys
                        )
                    }
                    when (outcome) {
                        is Outcome.Admitted -> {
                            entries.removeAll { it.key == key }
                            entries += outcome.entry
                            // Persist immediately so images can be shown before the refresh ends.
                            store.update { current ->
                                current.copy(entries = current.entries.filterNot { it.key == key } + outcome.entry)
                            }
                            admitted++
                            sourceAdmissions++
                            runAdmissions++
                        }
                        is Outcome.Rejected -> {
                            rejections[key] = RejectionRecord(outcome.reason, clock())
                            rejected++
                        }
                        is Outcome.Failed -> errors += "${lead.id}: ${outcome.reason}"
                        Outcome.NoSpace -> {
                            lowSpace = true
                            break@sources
                        }
                        is Outcome.Stored -> error("admit() never returns Stored")
                    }
                }
            }
        }

        val finishedAt = clock()
        val partial = RefreshResult(admitted, rejected, withdrawnKeys.size, 0, errors.distinct().take(4), lowSpace)
        val saved = store.update { current ->
            // Admissions and evictions are already stored; keep removals made concurrently
            // (e.g. integrity failures found during display) and apply withdrawals.
            val currentKeys = current.entries.map { it.key }.toSet()
            val merged = entries.filter { it.key in currentKeys && it.key !in withdrawnKeys }
            val available = selection.eligible(merged.filter { it.provider in providers }).size
            Catalogue(
                entries = merged,
                rejections = rejections,
                lastAttemptAtEpochMillis = finishedAt,
                lastSuccessAtEpochMillis = if (anySourceReached) finishedAt else current.lastSuccessAtEpochMillis,
                lastSummary = partial.copy(total = available).summary,
                selectionFingerprint = fingerprint
            )
        }
        deleteUnreferencedFiles(saved.entries.map { it.fileName }.toSet())
        return partial.copy(total = selection.eligible(saved.entries.filter { it.provider in providers }).size)
    }

    /**
     * Fetches one qualifying image for immediate display, without keeping a library. The file is
     * stored in [store]; the caller must delete it after use. Used when there is too little free
     * space to cache images. Returns null when no image could be obtained.
     */
    fun streamNext(queue: StreamQueue): CatalogueEntry? {
        store.imagesDirectory.mkdirs()
        repeat(MAX_STREAM_PROBES) {
            if (queue.pending.isEmpty()) {
                queue.refill(adapters, fetcher, random)
                if (queue.pending.isEmpty()) return null
            }
            val (adapter, lead) = queue.pending.removeFirst()
            val key = "${adapter.provider.name}:${lead.id}"
            queue.tried += key
            val outcome = try {
                when (val result = lead.resolve()) {
                    is CandidateResult.Ready -> admit(result.candidate, null, mutableListOf(), mutableSetOf())
                    else -> null
                }
            } catch (error: IOException) {
                null
            }
            if (outcome is Outcome.Admitted) return outcome.entry
        }
        return null
    }

    /** The resolution new images must meet; lower while a fallback quality is in use. */
    private fun requiredResolution(entries: List<CatalogueEntry>): Resolution? {
        val enabled = entries.filter { it.provider in providers }
        return if (selection.fallbackActive(enabled)) selection.quality.fallback else selection.quality.minimum
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

    private fun admit(
        candidate: Candidate,
        existing: CatalogueEntry?,
        entries: MutableList<CatalogueEntry>,
        withdrawnKeys: MutableSet<String>
    ): Outcome {
        val declaredWidth = candidate.declaredWidth
        val declaredHeight = candidate.declaredHeight
        if (declaredWidth != null && declaredHeight != null && !ImageSelection.suitableAspect(declaredWidth, declaredHeight)) {
            return Outcome.Rejected(SourceRejections.NOT_LANDSCAPE)
        }
        val required = requiredResolution(entries)
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
            val licence = when (val outcome = licenceFor(candidate, option, entries, withdrawnKeys)) {
                is LicenceOutcome.Cleared -> outcome.licence
                is LicenceOutcome.Rejected -> return Outcome.Rejected(outcome.reason)
                is LicenceOutcome.Failed -> return Outcome.Failed(outcome.reason)
            }

            val stored = reuse(existing, option.url)
                ?: when (val downloaded = download(candidate, option, required, entries)) {
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
                lastRejection = LOW_RESOLUTION
                continue
            }
            if (!ImageSelection.withinMaximum(stored.width, stored.height)) {
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
                    fileName = stored.fileName
                )
            )
        }
        return Outcome.Rejected(lastRejection)
    }

    private fun sourceLabel(candidate: Candidate) =
        candidate.djangoplicitySite?.label ?: com.cosmos.screensaver.source.Sources.label(candidate.provider)

    private fun licenceFor(
        candidate: Candidate,
        option: RenditionOption,
        entries: MutableList<CatalogueEntry>,
        withdrawnKeys: MutableSet<String>
    ): LicenceOutcome {
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

        val policy = when (val state = policyFor(site, entries, withdrawnKeys)) {
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

    private fun policyFor(
        site: DjangoplicitySite,
        entries: MutableList<CatalogueEntry>,
        withdrawnKeys: MutableSet<String>
    ): PolicyState = policies.getOrPut(site) {
        val retrievedAt = clock()
        val html = try {
            fetcher.fetchText(site.policyUrl)
        } catch (error: IOException) {
            return@getOrPut PolicyState.Unavailable("${site.label} usage policy unavailable (${error.describe()})")
        }
        if (DjangoplicityParser.policyIsCurrent(html, site)) {
            PolicyState.Current(VerifiedPolicy(site, retrievedAt))
        } else {
            val withdrawn = entries.filter {
                it.provider == site.provider && it.rightsBasis == RightsBasis.CC_BY_4_0.name
            }
            withdrawnKeys += withdrawn.map { it.key }
            entries.removeAll(withdrawn)
            PolicyState.Mismatch
        }
    }

    /** Reuses an already downloaded copy of [assetUrl] instead of fetching it again. */
    private fun reuse(existing: CatalogueEntry?, assetUrl: String): Outcome.Stored? {
        if (existing == null || existing.assetUrl != assetUrl) return null
        if (!File(store.imagesDirectory, existing.fileName).isFile) return null
        return Outcome.Stored(existing.fileName, existing.sha256, existing.byteCount, existing.widthPx, existing.heightPx)
    }

    private fun download(
        candidate: Candidate,
        option: RenditionOption,
        required: Resolution?,
        entries: MutableList<CatalogueEntry>
    ): Outcome {
        if (!makeRoom(entries, min(option.maxBytes, limits.downloadReserveBytes))) return Outcome.NoSpace
        val temporary = File(store.imagesDirectory, "download-${UUID.randomUUID()}.part")
        try {
            val download = try {
                fetcher.download(option.url, temporary, option.maxBytes)
            } catch (error: ResourceTooLargeException) {
                return Outcome.Rejected(IMAGE_TOO_LARGE)
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
            if (!makeRoom(entries, 0L, onDiskBytes = download.byteCount)) return Outcome.NoSpace

            val fileName = "${download.sha256}.$extension"
            val target = File(store.imagesDirectory, fileName)
            if (target.exists()) target.delete()
            if (!temporary.renameTo(target)) return Outcome.Failed("unable to store image")
            return Outcome.Stored(fileName, download.sha256, download.byteCount, dimensions.width, dimensions.height)
        } finally {
            temporary.delete()
        }
    }

    /**
     * Evicts images, those that no longer qualify first and then the oldest, until [needBytes]
     * more fit in the budget and the library has a free slot. [onDiskBytes] is a downloaded file
     * that is not in [entries] yet, but already occupies space.
     */
    private fun makeRoom(entries: MutableList<CatalogueEntry>, needBytes: Long, onDiskBytes: Long = 0L): Boolean {
        fun fits(): Boolean {
            val used = entries.sumOf { it.byteCount } + onDiskBytes
            return entries.size < limits.maxEntries && used + needBytes <= budgetFor(used)
        }
        if (fits()) return true
        val eligible = selection.eligible(entries.filter { it.provider in providers }).map { it.key }.toSet()
        val victims = entries.sortedWith(
            compareBy<CatalogueEntry> { it.key in eligible }.thenBy { it.admittedAtEpochMillis }
        )
        for (victim in victims) {
            store.remove(victim.key)
            entries.remove(victim)
            if (fits()) return true
        }
        return false
    }

    private fun deleteUnreferencedFiles(referenced: Set<String>) {
        store.imagesDirectory.listFiles()?.forEach { file ->
            if (file.name !in referenced) file.delete()
        }
    }

    companion object {
        const val LICENCE_NAME = "CC BY 4.0"
        const val NASA_MEDIA_GUIDELINES_URL = "https://www.nasa.gov/nasa-brand-center/images-and-media/"
        const val LOW_RESOLUTION = "LOW_RESOLUTION"
        const val IMAGE_TOO_LARGE = "IMAGE_TOO_LARGE"
        const val EXCEEDS_MAX_DIMENSIONS = "EXCEEDS_MAX_DIMENSIONS"
        private const val HEADER_BYTES = 32
        private const val MAX_STREAM_PROBES = 12

        /** Rejections that a changed selection or policy could overturn. */
        internal fun isSettingsDependent(reason: String) =
            reason.startsWith("RIGHTS_") || reason in setOf(
                LOW_RESOLUTION, IMAGE_TOO_LARGE, EXCEEDS_MAX_DIMENSIONS, "IMAGE_TOO_SMALL",
                SourceRejections.UNSUITABLE_TYPE, "NOT_AN_OBSERVATION", SourceRejections.NO_SUITABLE_RENDITION
            )
    }
}
