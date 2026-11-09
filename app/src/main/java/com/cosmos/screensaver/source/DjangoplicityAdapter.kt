package com.cosmos.screensaver.source

import java.io.IOException
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/** ESA/Hubble, ESA/Webb, ESO and NOIRLab, which all publish through the Djangoplicity platform. */
internal class DjangoplicityAdapter(
    private val site: DjangoplicitySite,
    private val clock: () -> Long = System::currentTimeMillis
) : SourceAdapter {
    override val provider get() = site.provider
    override val label get() = site.label

    override fun discover(fetcher: Fetcher, random: Random): Discovery {
        val errors = mutableListOf<String>()
        var reached = false
        val ids = site.discoveryUrls.flatMap { url ->
            try {
                DjangoplicityParser.discoveredIds(fetcher.fetchText(url), site).also { reached = true }
            } catch (error: IOException) {
                errors += "${site.label} listing unavailable (${error.describe()})"
                emptyList()
            }
        }.distinct()
        return Discovery(ids.map { id -> Lead(id) { resolve(fetcher, id) } }, errors, reached)
    }

    override fun recheck(fetcher: Fetcher, providerId: String): String? {
        val page = DjangoplicityParser.assetPage(fetcher.fetchText(site.pageUrl(providerId)), site) ?: return null
        return ContentFilter.check(page.title, page.description)
    }

    private fun resolve(fetcher: Fetcher, id: String): CandidateResult {
        val pageUrl = site.pageUrl(id)
        val retrievedAt = clock()
        val html = try {
            fetcher.fetchText(pageUrl)
        } catch (error: IOException) {
            return CandidateResult.Failed("page unavailable (${error.describe()})")
        }
        val page = DjangoplicityParser.assetPage(html, site)
            ?: return CandidateResult.Skip(SourceRejections.PAGE_UNREADABLE)
        if (page.id != id) return CandidateResult.Skip(SourceRejections.PAGE_ID_MISMATCH)
        if (!site.acceptsType(page.type)) return CandidateResult.Skip(SourceRejections.UNSUITABLE_TYPE)
        ContentFilter.check(page.title, page.description)?.let { return CandidateResult.Skip(it) }
        val width = page.widthPx
        val height = page.heightPx
        val options = Rendition.entries.filter { it in page.renditions }.map { rendition ->
            val estimate = if (width != null && height != null && width > 0 && height > 0) {
                estimatedSize(rendition, width, height)
            } else {
                null
            }
            RenditionOption(page.renditions.getValue(rendition), imageMaxBytes(rendition), estimate?.first, estimate?.second)
        }
        if (options.isEmpty()) return CandidateResult.Skip(SourceRejections.NO_SUITABLE_RENDITION)
        return CandidateResult.Ready(
            Candidate(
                provider = site.provider,
                id = id,
                title = page.title,
                pageUrl = pageUrl,
                credit = page.credit,
                options = options,
                declaredWidth = width,
                declaredHeight = height,
                copyrightMarked = page.exceptionMarkers.isNotEmpty(),
                djangoplicitySite = site,
                djangoplicityPage = page,
                pageRetrievedAtEpochMillis = retrievedAt,
                description = page.description
            )
        )
    }

    private fun estimatedSize(rendition: Rendition, width: Int, height: Int): Pair<Int, Int> = when (rendition) {
        Rendition.PUBLICATION -> {
            val scale = min(1.0, Rendition.PUBLICATION_MAX_WIDTH.toDouble() / width)
            (width * scale).roundToInt() to (height * scale).roundToInt()
        }
        Rendition.LARGE -> width to height
    }
}
