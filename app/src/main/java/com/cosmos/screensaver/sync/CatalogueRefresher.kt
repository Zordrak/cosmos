package com.cosmos.screensaver.sync

import com.cosmos.screensaver.catalogue.Catalogue
import com.cosmos.screensaver.catalogue.CatalogueEntry
import com.cosmos.screensaver.catalogue.CatalogueStore
import com.cosmos.screensaver.catalogue.EvidenceRecord
import com.cosmos.screensaver.catalogue.RejectionRecord
import com.cosmos.screensaver.rights.DjangoplicityEvidenceVerifier
import com.cosmos.screensaver.rights.LicenceValidator
import com.cosmos.screensaver.rights.RequestedUse
import com.cosmos.screensaver.rights.RightsCandidate
import com.cosmos.screensaver.rights.RightsDecision
import com.cosmos.screensaver.rights.RightsUseScope
import com.cosmos.screensaver.rights.VerifiedPolicy
import com.cosmos.screensaver.settings.ImageSelection
import com.cosmos.screensaver.settings.LicenceMode
import com.cosmos.screensaver.settings.Resolution
import com.cosmos.screensaver.source.AssetPage
import com.cosmos.screensaver.source.DjangoplicityParser
import com.cosmos.screensaver.source.DjangoplicitySite
import com.cosmos.screensaver.source.Fetcher
import com.cosmos.screensaver.source.Rendition
import com.cosmos.screensaver.source.ResourceTooLargeException
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

internal data class ImageDimensions(val width: Int, val height: Int)

/** Returns the decoded dimensions of a JPEG, or null if it is not a decodable JPEG. */
internal fun interface ImageInspector {
    fun inspect(file: File): ImageDimensions?
}

internal data class RefreshResult(
    val admitted: Int,
    val rejected: Int,
    val withdrawn: Int,
    val total: Int,
    val errors: List<String>
) {
    val summary: String
        get() = buildString {
            append("$total images available. This refresh added $admitted")
            if (rejected > 0) append(", skipped $rejected")
            if (withdrawn > 0) append(", withdrew $withdrawn")
            append('.')
            if (errors.isNotEmpty()) append(" Problems: ").append(errors.joinToString("; "))
        }
}

/**
 * Discovers candidate images, applies the user's [ImageSelection], downloads and verifies them,
 * and admits them to the local catalogue. In [LicenceMode.OPEN_LICENCE_ONLY] every asset must
 * also pass the fail-closed [LicenceValidator]; in [LicenceMode.ANY_AVAILABLE] (personal use)
 * rights are recorded as not checked.
 */
internal class CatalogueRefresher(
    private val fetcher: Fetcher,
    private val store: CatalogueStore,
    private val inspector: ImageInspector,
    private val territory: String?,
    private val sites: Set<DjangoplicitySite>,
    private val selection: ImageSelection = ImageSelection(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
    private val limits: Limits = Limits()
) {
    data class Limits(
        val maxEntries: Int = 80,
        val maxTotalBytes: Long = 1536L * 1024 * 1024,
        val maxPagesPerSite: Int = 40,
        val maxAdmissionsPerSite: Int = 8,
        val rejectionRetryMillis: Long = 30L * 24 * 60 * 60 * 1000,
        /** TVs are landscape; portrait images would be mostly cropped or letterboxed, so skip them. */
        val minAspectRatio: Double = 1.0,
        val aspectTolerance: Double = 0.02,
        val publicationMaxBytes: Long = 30L * 1024 * 1024,
        val largeMaxBytes: Long = 60L * 1024 * 1024
    )

    private sealed interface Outcome {
        data class Admitted(val entry: CatalogueEntry) : Outcome
        data class Rejected(val reason: String) : Outcome
        data class Failed(val reason: String) : Outcome
        data class Stored(
            val fileName: String,
            val sha256: String,
            val byteCount: Long,
            val width: Int,
            val height: Int
        ) : Outcome
    }

    private val providers = sites.map { it.provider }.toSet()

    private val checkRights get() = selection.licenceMode == LicenceMode.OPEN_LICENCE_ONLY

    fun refresh(): RefreshResult {
        val startedAt = clock()
        val initial = store.load()
        val fingerprint = selection.fingerprint
        val selectionChanged = initial.selectionFingerprint != fingerprint
        val entries = initial.entries.toMutableList()
        val rejections = initial.rejections
            .filterValues { startedAt - it.atEpochMillis in 0 until limits.rejectionRetryMillis }
            .filterValues { !(selectionChanged && isSettingsDependent(it.reason)) }
            .toMutableMap()
        val errors = mutableListOf<String>()
        val withdrawnKeys = mutableSetOf<String>()
        var admitted = 0
        var rejected = 0
        var anySiteReached = false

        store.imagesDirectory.mkdirs()
        for (site in sites.sortedBy { it.ordinal }) {
            val policy = if (checkRights) {
                val policyRetrievedAt = clock()
                val policyHtml = try {
                    fetcher.fetchText(site.policyUrl)
                } catch (error: IOException) {
                    errors += "${site.label} usage policy unavailable (${error.message})"
                    continue
                }
                if (!DjangoplicityParser.policyIsCurrent(policyHtml, site)) {
                    // Fail closed: the licence statement relied on is no longer published.
                    val withdrawn = entries.filter {
                        it.provider == site.provider && it.rightsBasis != CatalogueEntry.RIGHTS_NOT_CHECKED
                    }
                    withdrawnKeys += withdrawn.map { it.key }
                    entries.removeAll(withdrawn)
                    errors += "${site.label} usage policy no longer matches; its licensed images were withdrawn"
                    continue
                }
                VerifiedPolicy(site, policyRetrievedAt)
            } else {
                null
            }

            val discovered = site.discoveryUrls.flatMap { url ->
                try {
                    DjangoplicityParser.discoveredIds(fetcher.fetchText(url), site).also { anySiteReached = true }
                } catch (error: IOException) {
                    errors += "${site.label} listing unavailable (${error.message})"
                    emptyList()
                }
            }.distinct()
            if (policy != null) anySiteReached = true
            val satisfied = selection.eligible(entries.filter { it.provider in providers }).map { it.key }.toSet()
            val candidates = discovered
                .filter { CatalogueEntry.keyOf(site.provider, it).let { key -> key !in satisfied && key !in rejections } }
                .shuffled(random)

            var pages = 0
            var siteAdmissions = 0
            for (id in candidates) {
                if (pages >= limits.maxPagesPerSite || siteAdmissions >= limits.maxAdmissionsPerSite) break
                pages++
                val key = CatalogueEntry.keyOf(site.provider, id)
                val required = requiredResolution(entries)
                when (val outcome = admit(site, policy, id, entries.firstOrNull { it.key == key }, required)) {
                    is Outcome.Admitted -> {
                        entries.removeAll { it.key == key }
                        entries += outcome.entry
                        // Persist immediately so slow devices can show images before the refresh ends.
                        store.update { current ->
                            current.copy(entries = current.entries.filterNot { it.key == key } + outcome.entry)
                        }
                        admitted++
                        siteAdmissions++
                    }
                    is Outcome.Rejected -> {
                        rejections[key] = RejectionRecord(outcome.reason, clock())
                        rejected++
                    }
                    is Outcome.Failed -> errors += "$id: ${outcome.reason}"
                    is Outcome.Stored -> error("admit() never returns Stored")
                }
            }
        }

        val retained = enforceCapacity(entries)
        val finishedAt = clock()
        val partial = RefreshResult(admitted, rejected, withdrawnKeys.size, 0, errors.distinct().take(4))

        val saved = store.update { current ->
            // Admissions are already stored, so this keeps removals made concurrently (e.g.
            // integrity failures found during display) and applies withdrawals and capacity.
            val currentKeys = current.entries.map { it.key }.toSet()
            val merged = retained.filter { it.key in currentKeys }
            val available = selection.eligible(merged.filter { it.provider in providers }).size
            Catalogue(
                entries = merged,
                rejections = rejections,
                lastAttemptAtEpochMillis = finishedAt,
                lastSuccessAtEpochMillis = if (anySiteReached) finishedAt else current.lastSuccessAtEpochMillis,
                lastSummary = partial.copy(total = available).summary,
                selectionFingerprint = fingerprint
            )
        }
        deleteUnreferencedFiles(saved.entries.map { it.fileName }.toSet())
        return partial.copy(total = selection.eligible(saved.entries.filter { it.provider in providers }).size)
    }

    /** The resolution new images must meet; lower while a fallback quality is in use. */
    private fun requiredResolution(entries: List<CatalogueEntry>): Resolution? {
        val enabled = entries.filter { it.provider in providers }
        return if (selection.fallbackActive(enabled)) selection.quality.fallback else selection.quality.minimum
    }

    /**
     * Picks the renditions worth downloading, smallest first. The page's declared size lets most
     * low-resolution images be skipped without downloading anything.
     */
    private fun renditionPlan(page: AssetPage, required: Resolution?): List<Rendition> {
        val offered = Rendition.entries.filter { it in page.renditions }
        val width = page.widthPx
        val height = page.heightPx
        if (width == null || height == null || width <= 0 || height <= 0) {
            return if (required == null) offered.take(1) else offered
        }
        if (required == null) return offered.take(1)
        return offered.filter { rendition ->
            val (w, h) = estimatedSize(rendition, width, height)
            required.accepts(w, h)
        }
    }

    private fun estimatedSize(rendition: Rendition, width: Int, height: Int): Pair<Int, Int> = when (rendition) {
        Rendition.PUBLICATION -> {
            val scale = min(1.0, Rendition.PUBLICATION_MAX_WIDTH.toDouble() / width)
            (width * scale).roundToInt() to (height * scale).roundToInt()
        }
        Rendition.LARGE -> width to height
    }

    private fun admit(
        site: DjangoplicitySite,
        policy: VerifiedPolicy?,
        id: String,
        existing: CatalogueEntry?,
        required: Resolution?
    ): Outcome {
        val pageUrl = site.pageUrl(id)
        val pageRetrievedAt = clock()
        val html = try {
            fetcher.fetchText(pageUrl)
        } catch (error: IOException) {
            return Outcome.Failed("page unavailable (${error.message})")
        }
        val page = DjangoplicityParser.assetPage(html, site) ?: return Outcome.Rejected("PAGE_UNREADABLE")
        if (page.id != id) return Outcome.Rejected("PAGE_ID_MISMATCH")
        if (!site.acceptsType(page.type, id)) return Outcome.Rejected("UNSUITABLE_TYPE")
        if (page.renditions.isEmpty()) return Outcome.Rejected("NO_SUITABLE_RENDITION")
        if (page.widthPx != null && page.heightPx != null &&
            page.widthPx < page.heightPx * limits.minAspectRatio
        ) {
            return Outcome.Rejected("NOT_LANDSCAPE")
        }
        val plan = renditionPlan(page, required)
        if (plan.isEmpty()) return Outcome.Rejected(LOW_RESOLUTION)

        var lastRejection = LOW_RESOLUTION
        for (rendition in plan) {
            val assetUrl = page.renditions.getValue(rendition)
            val rights = if (policy != null) {
                val decision = LicenceValidator(
                    verifier = DjangoplicityEvidenceVerifier(policy, page, pageRetrievedAt),
                    currentTimeMillis = clock
                ).validate(
                    RightsCandidate(
                        provider = site.provider,
                        providerId = id,
                        sourcePageUrl = pageUrl,
                        originalAssetUrl = assetUrl,
                        creatorCredit = page.credit,
                        intendedUse = RequestedUse(territory, RightsUseScope.PERSONAL_NONCOMMERCIAL_SCREENSAVER)
                    )
                )
                when (decision) {
                    is RightsDecision.Rejected -> return Outcome.Rejected("RIGHTS_${decision.reason.name}")
                    is RightsDecision.RightsCleared -> decision.rights
                }
            } else {
                null
            }

            val stored = reuse(existing, assetUrl) ?: when (val downloaded = download(page, rendition, assetUrl, required)) {
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

            val credit = page.credit.ifBlank { site.label }
            val licenceUrl = if (rights != null) {
                rights.licenceUrl ?: return Outcome.Rejected("RIGHTS_INVALID_LICENCE_URL")
            } else {
                ""
            }
            return Outcome.Admitted(
                CatalogueEntry(
                    provider = site.provider,
                    providerId = id,
                    title = page.title,
                    sourceLabel = site.label,
                    sourcePageUrl = pageUrl,
                    assetUrl = assetUrl,
                    credit = rights?.creatorCredit ?: credit,
                    rightsBasis = rights?.basis?.name ?: CatalogueEntry.RIGHTS_NOT_CHECKED,
                    licenceName = if (rights != null) LICENCE_NAME else "",
                    licenceUrl = licenceUrl,
                    territory = rights?.intendedUse?.territory ?: territory.orEmpty(),
                    evidence = rights?.evidenceSources?.map {
                        EvidenceRecord(it.evidenceUrl, it.exactStatement, it.scope.name, it.retrievedAtEpochMillis)
                    }.orEmpty(),
                    validatedAtEpochMillis = rights?.validatedAtEpochMillis ?: 0L,
                    policyVersion = rights?.policyVersion.orEmpty(),
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


    /** Reuses an already downloaded copy of [assetUrl] instead of fetching it again. */
    private fun reuse(existing: CatalogueEntry?, assetUrl: String): Outcome.Stored? {
        if (existing == null || existing.assetUrl != assetUrl) return null
        if (!File(store.imagesDirectory, existing.fileName).isFile) return null
        return Outcome.Stored(existing.fileName, existing.sha256, existing.byteCount, existing.widthPx, existing.heightPx)
    }

    /** Returns [Outcome.Stored] on success. */
    private fun download(page: AssetPage, rendition: Rendition, assetUrl: String, required: Resolution?): Outcome {
        val maxBytes = when (rendition) {
            Rendition.PUBLICATION -> limits.publicationMaxBytes
            Rendition.LARGE -> limits.largeMaxBytes
        }
        val temporary = File(store.imagesDirectory, "download-${UUID.randomUUID()}.part")
        try {
            val download = try {
                fetcher.download(assetUrl, temporary, maxBytes)
            } catch (error: ResourceTooLargeException) {
                return Outcome.Rejected(IMAGE_TOO_LARGE)
            } catch (error: IOException) {
                return Outcome.Failed("download failed (${error.message})")
            }
            if (!isJpeg(temporary)) return Outcome.Rejected("NOT_A_JPEG")
            val dimensions = inspector.inspect(temporary) ?: return Outcome.Rejected("UNDECODABLE_IMAGE")
            if (required != null && !required.accepts(dimensions.width, dimensions.height)) {
                return Outcome.Rejected(LOW_RESOLUTION)
            }
            if (dimensions.width < dimensions.height * limits.minAspectRatio) {
                return Outcome.Rejected("NOT_LANDSCAPE")
            }
            if (page.widthPx != null && page.heightPx != null && page.heightPx > 0 && dimensions.height > 0) {
                val expected = page.widthPx.toDouble() / page.heightPx
                val actual = dimensions.width.toDouble() / dimensions.height
                if (abs(actual - expected) / expected > limits.aspectTolerance) {
                    return Outcome.Rejected("DIMENSION_MISMATCH")
                }
            }

            val fileName = "${download.sha256}.jpg"
            val target = File(store.imagesDirectory, fileName)
            if (target.exists()) target.delete()
            if (!temporary.renameTo(target)) return Outcome.Failed("unable to store image")
            return Outcome.Stored(fileName, download.sha256, download.byteCount, dimensions.width, dimensions.height)
        } finally {
            temporary.delete()
        }
    }

    /** Keeps images that qualify under the current settings first, then others while space remains. */
    private fun enforceCapacity(entries: List<CatalogueEntry>): List<CatalogueEntry> {
        val eligible = selection.eligible(entries.filter { it.provider in providers }).map { it.key }.toSet()
        val ordered = entries.sortedWith(
            compareByDescending<CatalogueEntry> { it.key in eligible }.thenByDescending { it.admittedAtEpochMillis }
        )
        val kept = mutableListOf<CatalogueEntry>()
        var bytes = 0L
        for (entry in ordered) {
            if (kept.size >= limits.maxEntries || bytes + entry.byteCount > limits.maxTotalBytes) continue
            kept += entry
            bytes += entry.byteCount
        }
        return kept
    }

    private fun deleteUnreferencedFiles(referenced: Set<String>) {
        store.imagesDirectory.listFiles()?.forEach { file ->
            if (file.name !in referenced) file.delete()
        }
    }

    private fun isJpeg(file: File): Boolean = file.inputStream().use { input ->
        val header = ByteArray(3)
        input.read(header) == 3 &&
            header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()
    }

    companion object {
        const val LICENCE_NAME = "CC BY 4.0"
        const val LOW_RESOLUTION = "LOW_RESOLUTION"
        const val IMAGE_TOO_LARGE = "IMAGE_TOO_LARGE"

        /** Rejections that a different licence or quality setting could overturn. */
        internal fun isSettingsDependent(reason: String) =
            reason.startsWith("RIGHTS_") || reason in setOf(
                LOW_RESOLUTION, IMAGE_TOO_LARGE, "IMAGE_TOO_SMALL", "UNSUITABLE_TYPE",
                "NOT_AN_OBSERVATION", "NO_SUITABLE_RENDITION"
            )
    }
}