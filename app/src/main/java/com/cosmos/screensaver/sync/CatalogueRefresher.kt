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
import com.cosmos.screensaver.source.DjangoplicityParser
import com.cosmos.screensaver.source.DjangoplicitySite
import com.cosmos.screensaver.source.Fetcher
import com.cosmos.screensaver.source.ResourceTooLargeException
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
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
            if (rejected > 0) append(", excluded $rejected")
            if (withdrawn > 0) append(", withdrew $withdrawn")
            append('.')
            if (errors.isNotEmpty()) append(" Problems: ").append(errors.joinToString("; "))
        }
}

/**
 * Discovers candidate images, validates rights per asset, downloads and verifies them, and
 * admits them to the local catalogue. Nothing reaches the catalogue without a rights clearance.
 */
internal class CatalogueRefresher(
    private val fetcher: Fetcher,
    private val store: CatalogueStore,
    private val inspector: ImageInspector,
    private val territory: String?,
    private val sites: Set<DjangoplicitySite>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
    private val limits: Limits = Limits()
) {
    data class Limits(
        val maxEntries: Int = 60,
        val maxTotalBytes: Long = 400L * 1024 * 1024,
        val maxPagesPerSite: Int = 16,
        val maxAdmissionsPerSite: Int = 6,
        val rejectionRetryMillis: Long = 30L * 24 * 60 * 60 * 1000,
        val minLongEdgePx: Int = 1600,
        /** TVs are landscape; portrait images would be shown as a narrow strip, so skip them. */
        val minAspectRatio: Double = 1.0,
        val aspectTolerance: Double = 0.02
    )

    private sealed interface Outcome {
        data class Admitted(val entry: CatalogueEntry) : Outcome
        data class Rejected(val reason: String) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    fun refresh(): RefreshResult {
        val startedAt = clock()
        val initial = store.load()
        // Entries validated under an older rights policy must be re-validated before display.
        val entries = initial.entries
            .filter { it.policyVersion == LicenceValidator.POLICY_VERSION }
            .toMutableList()
        val rejections = initial.rejections
            .filterValues { startedAt - it.atEpochMillis in 0 until limits.rejectionRetryMillis }
            .toMutableMap()
        val errors = mutableListOf<String>()
        val withdrawnKeys = mutableSetOf<String>()
        var admitted = 0
        var rejected = 0
        var anySiteVerified = false

        store.imagesDirectory.mkdirs()
        for (site in sites.sortedBy { it.ordinal }) {
            val policyRetrievedAt = clock()
            val policyHtml = try {
                fetcher.fetchText(site.policyUrl)
            } catch (error: IOException) {
                errors += "${site.label} usage policy unavailable (${error.message})"
                continue
            }
            if (!DjangoplicityParser.policyIsCurrent(policyHtml, site)) {
                // Fail closed: the licence statement relied on is no longer published.
                val withdrawn = entries.filter { it.provider == site.provider }
                withdrawnKeys += withdrawn.map { it.key }
                entries.removeAll(withdrawn)
                errors += "${site.label} usage policy no longer matches; its images were withdrawn"
                continue
            }
            anySiteVerified = true
            val policy = VerifiedPolicy(site, policyRetrievedAt)

            val discovered = site.discoveryUrls.flatMap { url ->
                try {
                    DjangoplicityParser.discoveredIds(fetcher.fetchText(url), site)
                } catch (error: IOException) {
                    errors += "${site.label} listing unavailable (${error.message})"
                    emptyList()
                }
            }.distinct()
            val known = entries.map { it.key }.toSet()
            val candidates = discovered
                .filter { CatalogueEntry.keyOf(site.provider, it).let { key -> key !in known && key !in rejections } }
                .shuffled(random)

            var pages = 0
            var siteAdmissions = 0
            for (id in candidates) {
                if (pages >= limits.maxPagesPerSite || siteAdmissions >= limits.maxAdmissionsPerSite) break
                pages++
                val key = CatalogueEntry.keyOf(site.provider, id)
                when (val outcome = admit(site, policy, id)) {
                    is Outcome.Admitted -> {
                        entries += outcome.entry
                        // Persist immediately so slow devices can show images before the refresh ends.
                        store.update { current ->
                            current.copy(entries = current.entries.filterNot { it.key == outcome.entry.key } + outcome.entry)
                        }
                        admitted++
                        siteAdmissions++
                    }
                    is Outcome.Rejected -> {
                        rejections[key] = RejectionRecord(outcome.reason, clock())
                        rejected++
                    }
                    is Outcome.Failed -> errors += "$id: ${outcome.reason}"
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
            val result = partial.copy(total = merged.size)
            Catalogue(
                entries = merged,
                rejections = rejections,
                lastAttemptAtEpochMillis = finishedAt,
                lastSuccessAtEpochMillis = if (anySiteVerified) finishedAt else current.lastSuccessAtEpochMillis,
                lastSummary = result.summary
            )
        }
        deleteUnreferencedFiles(saved.entries.map { it.fileName }.toSet())
        return partial.copy(total = saved.entries.size)
    }

    private fun admit(site: DjangoplicitySite, policy: VerifiedPolicy, id: String): Outcome {
        val pageUrl = site.pageUrl(id)
        val pageRetrievedAt = clock()
        val html = try {
            fetcher.fetchText(pageUrl)
        } catch (error: IOException) {
            return Outcome.Failed("page unavailable (${error.message})")
        }
        val page = DjangoplicityParser.assetPage(html, site) ?: return Outcome.Rejected("PAGE_UNREADABLE")
        if (page.id != id) return Outcome.Rejected("PAGE_ID_MISMATCH")
        if (page.type != OBSERVATION) return Outcome.Rejected("NOT_AN_OBSERVATION")
        val assetUrl = page.imageUrls.firstOrNull() ?: return Outcome.Rejected("NO_SUITABLE_RENDITION")
        if (page.widthPx != null && page.heightPx != null &&
            page.widthPx < page.heightPx * limits.minAspectRatio
        ) {
            return Outcome.Rejected("NOT_LANDSCAPE")
        }

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
        val rights = when (decision) {
            is RightsDecision.Rejected -> return Outcome.Rejected("RIGHTS_${decision.reason.name}")
            is RightsDecision.RightsCleared -> decision.rights
        }

        val temporary = File(store.imagesDirectory, "download-${UUID.randomUUID()}.part")
        try {
            val download = try {
                fetcher.download(assetUrl, temporary)
            } catch (error: ResourceTooLargeException) {
                return Outcome.Rejected("IMAGE_TOO_LARGE")
            } catch (error: IOException) {
                return Outcome.Failed("download failed (${error.message})")
            }
            if (!isJpeg(temporary)) return Outcome.Rejected("NOT_A_JPEG")
            val dimensions = inspector.inspect(temporary) ?: return Outcome.Rejected("UNDECODABLE_IMAGE")
            if (max(dimensions.width, dimensions.height) < limits.minLongEdgePx) {
                return Outcome.Rejected("IMAGE_TOO_SMALL")
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

            return Outcome.Admitted(
                CatalogueEntry(
                    provider = site.provider,
                    providerId = id,
                    title = page.title,
                    sourceLabel = site.label,
                    sourcePageUrl = pageUrl,
                    assetUrl = assetUrl,
                    credit = rights.creatorCredit,
                    rightsBasis = rights.basis.name,
                    licenceName = LICENCE_NAME,
                    licenceUrl = rights.licenceUrl ?: return Outcome.Rejected("RIGHTS_INVALID_LICENCE_URL"),
                    territory = rights.intendedUse.territory,
                    evidence = rights.evidenceSources.map {
                        EvidenceRecord(it.evidenceUrl, it.exactStatement, it.scope.name, it.retrievedAtEpochMillis)
                    },
                    validatedAtEpochMillis = rights.validatedAtEpochMillis,
                    policyVersion = rights.policyVersion,
                    admittedAtEpochMillis = clock(),
                    sha256 = download.sha256,
                    byteCount = download.byteCount,
                    widthPx = dimensions.width,
                    heightPx = dimensions.height,
                    fileName = fileName
                )
            )
        } finally {
            temporary.delete()
        }
    }

    private fun enforceCapacity(entries: List<CatalogueEntry>): List<CatalogueEntry> {
        val newestFirst = entries.sortedByDescending { it.admittedAtEpochMillis }
        val kept = mutableListOf<CatalogueEntry>()
        var bytes = 0L
        for (entry in newestFirst) {
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
        const val OBSERVATION = "Observation"
        const val LICENCE_NAME = "CC BY 4.0"
    }
}
