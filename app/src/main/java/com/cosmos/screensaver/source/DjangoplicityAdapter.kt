package com.cosmos.screensaver.source

import com.cosmos.screensaver.settings.ImageSelection
import java.io.IOException
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/** ESA/Hubble, ESA/Webb, ESO and NOIRLab, which all publish through the Djangoplicity platform. */
internal class DjangoplicityAdapter(
    private val site: DjangoplicitySite,
    private val listings: ListingCache = ListingCache(null),
    private val clock: () -> Long = System::currentTimeMillis
) : SourceAdapter {
    override val provider get() = site.provider
    override val label get() = site.label

    override fun discover(fetcher: Fetcher, random: Random): Discovery =
        discover(fetcher, random, LeadFilter.EVERYTHING_FRESH)

    /** The headline listings' pictures, read once per adapter (one refresh pass) and replayed after. */
    private var headline: List<String>? = null

    /**
     * The headline listings come first. The archive's category pages are then walked in rotation
     * a page at a time, skipping every picture [filter] has already dealt with, so the crawl always
     * continues from the first thing not yet accounted for rather than from a remembered position.
     * Pages already read are replayed from [listings]; only a few new ones are fetched per call, so
     * a pass calls this again whenever it has worked through what the last call returned.
     */
    override fun discover(fetcher: Fetcher, random: Random, filter: LeadFilter): Discovery {
        val errors = mutableListOf<String>()
        var reached = headline != null
        val seen = mutableSetOf<String>()
        val fresh = mutableListOf<String>()
        val retired = mutableListOf<String>()
        fun offer(id: String) {
            if (!seen.add(id)) return
            when (filter.status(id)) {
                LeadStatus.FRESH -> fresh += id
                LeadStatus.RETIRED -> retired += id
                LeadStatus.DONE -> Unit
            }
        }
        val known = headline ?: run {
            val ids = mutableListOf<String>()
            for (url in site.discoveryUrls) {
                try {
                    ids += DjangoplicityParser.discoveredIds(fetcher.fetchText(url), site)
                    reached = true
                } catch (error: IOException) {
                    errors += "${site.label} listing unavailable (${error.describe()})"
                }
            }
            if (reached) headline = ids
            ids
        }
        known.forEach(::offer)
        if (reached) crawlCategories(fetcher, filter, ::offer) { fresh.size }
        listings.flush()
        val ids = retired + fresh
        return Discovery(ids.map { id -> Lead(id) { resolve(fetcher, id) } }, errors, reached)
    }

    private fun crawlCategories(
        fetcher: Fetcher,
        filter: LeadFilter,
        offer: (String) -> Unit,
        freshCount: () -> Int
    ) {
        val lastPage = mutableMapOf<String, Int>()
        val finished = mutableSetOf<String>()
        var fetches = 0
        for (depth in 1..ListingCache.MAX_PAGES) {
            var walked = false
            for (category in site.categories) {
                if (category in finished) continue
                if (depth > (lastPage[category] ?: Int.MAX_VALUE)) {
                    finished += category
                    continue
                }
                val key = "${site.name}/$category/$depth"
                var page = listings.get(key)
                val now = clock()
                val ttl = if (depth == 1) HEAD_REFETCH_MILLIS else BODY_REFETCH_MILLIS
                val stale = page == null || now - page.fetchedAtEpochMillis !in 0..ttl
                if (stale && fetches < MAX_LISTING_FETCHES && freshCount() < LEAD_WINDOW) {
                    fetches++
                    try {
                        val html = fetcher.fetchText(site.categoryUrl(category, depth))
                        val known = if (depth == 1) DjangoplicityParser.listingLastPage(html) else lastPage[category] ?: 1
                        page = ListingPage(now, known, DjangoplicityParser.listingItems(html))
                        listings.put(key, page)
                    } catch (error: IOException) {
                        // A page that is missing or unreachable ends this category for now.
                    }
                }
                if (page == null || page.items.isEmpty()) {
                    finished += category
                    continue
                }
                if (depth == 1) lastPage[category] = page.lastPage
                walked = true
                for (item in page.items) {
                    if (ImageSelection.suitableAspect(item.width, item.height) && filter.fits(item.width, item.height)) {
                        offer(item.id)
                    }
                }
            }
            if (!walked) break
        }
    }

    private companion object {
        const val MAX_LISTING_FETCHES = 10
        const val LEAD_WINDOW = 60
        const val HEAD_REFETCH_MILLIS = 3L * 24 * 60 * 60 * 1000
        const val BODY_REFETCH_MILLIS = 14L * 24 * 60 * 60 * 1000
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
