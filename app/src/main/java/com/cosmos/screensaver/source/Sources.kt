package com.cosmos.screensaver.source

import com.cosmos.screensaver.catalogue.CatalogueEntry
import com.cosmos.screensaver.rights.AssetProvider
import java.io.IOException
import kotlin.random.Random

/** One downloadable version of an image. Dimensions are the best estimate before downloading. */
internal data class RenditionOption(val url: String, val maxBytes: Long, val width: Int?, val height: Int?)

internal data class Candidate(
    val provider: AssetProvider,
    val id: String,
    val title: String,
    val pageUrl: String,
    val credit: String,
    /** Smallest first. */
    val options: List<RenditionOption>,
    /** Declared size of the original, used to skip unsuitable images before downloading. */
    val declaredWidth: Int? = null,
    val declaredHeight: Int? = null,
    /** The source marks the image as copyrighted or otherwise not a plain public work. */
    val copyrightMarked: Boolean = false,
    val djangoplicitySite: DjangoplicitySite? = null,
    val djangoplicityPage: AssetPage? = null,
    val pageRetrievedAtEpochMillis: Long = 0L,
    /** Cleaned body text already fetched with the asset's page or listing; may be empty. */
    val description: String = ""
) {
    val key: String get() = CatalogueEntry.keyOf(provider, id)
}

internal sealed interface CandidateResult {
    data class Ready(val candidate: Candidate) : CandidateResult
    data class Skip(val reason: String) : CandidateResult
    data class Failed(val reason: String) : CandidateResult
}

/** An image found in a listing. [resolve] does whatever further fetching is needed to describe it. */
internal class Lead(val id: String, val resolve: () -> CandidateResult)

internal data class Discovery(val leads: List<Lead>, val errors: List<String>, val reached: Boolean)

/** How the library already regards a picture a listing names. */
internal enum class LeadStatus {
    /** Never seen, or a past decision that has expired: worth resolving. */
    FRESH,

    /** Was saved and then removed to make room: wanted back only if there is spare room. */
    RETIRED,

    /** Saved, rejected, blocked or held aside: nothing to do. */
    DONE
}

/** Lets an adapter skip what the library has dealt with, so a crawl can reach pictures it has not. */
internal interface LeadFilter {
    fun status(id: String): LeadStatus

    /** False when a picture of this declared size can never qualify under the current settings. */
    fun fits(width: Int, height: Int): Boolean = true

    companion object {
        val EVERYTHING_FRESH = object : LeadFilter {
            override fun status(id: String) = LeadStatus.FRESH
        }
    }
}

internal interface SourceAdapter {
    val provider: AssetProvider
    val label: String

    fun discover(fetcher: Fetcher, random: Random): Discovery

    /** As [discover], but may go deeper into the source's archive than what [filter] already accounts for. */
    fun discover(fetcher: Fetcher, random: Random, filter: LeadFilter): Discovery = discover(fetcher, random)

    /**
     * Re-reads one already saved picture's description and returns a rejection reason if it should
     * no longer be shown. Null means acceptable or unknown. Throws [IOException] when unreachable.
     */
    @Throws(IOException::class)
    fun recheck(fetcher: Fetcher, providerId: String): String? = null
}

internal object SourceRejections {
    const val PAGE_UNREADABLE = "PAGE_UNREADABLE"
    const val PAGE_ID_MISMATCH = "PAGE_ID_MISMATCH"
    const val UNSUITABLE_TYPE = "UNSUITABLE_TYPE"
    const val NO_SUITABLE_RENDITION = "NO_SUITABLE_RENDITION"
    const val NOT_LANDSCAPE = "NOT_LANDSCAPE"
    const val NOT_A_PHOTOGRAPH = "NOT_A_PHOTOGRAPH"
}

internal fun IOException.describe(): String = message ?: javaClass.simpleName

internal fun imageMaxBytes(rendition: Rendition?) = when (rendition) {
    Rendition.PUBLICATION -> 30L * 1024 * 1024
    else -> 60L * 1024 * 1024
}
