package com.cosmos.screensaver.source

import com.cosmos.screensaver.rights.AssetProvider

internal enum class Rendition(val path: String) {
    /** Full-quality JPEG capped at [PUBLICATION_MAX_WIDTH] pixels wide; typically 2–5 MB. */
    PUBLICATION("publicationjpg"),

    /** Full-resolution JPEG; can be tens of megabytes. Used only when needed for the quality tier. */
    LARGE("large");

    companion object {
        const val PUBLICATION_MAX_WIDTH = 4000
    }
}

/**
 * ESA/Hubble, ESA/Webb, ESO and NOIRLab all publish through the Djangoplicity platform, so
 * they share one parser. Every URL produced here must also satisfy [ApprovedEndpoints].
 */
internal enum class DjangoplicitySite(
    val provider: AssetProvider,
    val label: String,
    /** Registrable domain; pages may be linked with or without `www.`. */
    val domain: String,
    val webHost: String,
    val pagePath: String,
    val cdnHost: String,
    val cdnPath: String,
    val policyPath: String,
    /** Exact sentence the usage policy must still contain when licence checking is enabled. */
    val policyStatement: String,
    private val extraListings: List<String>,
    /** Archive categories of observations, crawled page by page beyond the headline listings. */
    val categories: List<String>,
    private val pageSegment: String
) {
    HUBBLE(
        AssetProvider.ESA_HUBBLE, "ESA/Hubble", "esahubble.org", "esahubble.org", "/images/",
        "cdn.esahubble.org", "/archives/images/", "/copyright/",
        esaStatement("ESA/Hubble"),
        listOf("archive/top100/page/2/", "potw/"),
        listOf("galaxies", "nebulae", "starclusters", "stars", "cosmology", "blackholes", "solarsystem"),
        "page"
    ),
    WEBB(
        AssetProvider.ESA_WEBB, "ESA/Webb", "esawebb.org", "esawebb.org", "/images/",
        "cdn.esawebb.org", "/archives/images/", "/copyright/",
        esaStatement("ESA/Webb"),
        listOf("archive/top100/page/2/", "archive/category/nebulae/", "archive/category/galaxies/"),
        listOf("galaxies", "nebulae", "nircam", "miri", "stars", "pictureofthemonth", "firstimages", "solarsystem"),
        "page"
    ),
    ESO(
        AssetProvider.ESO, "ESO", "eso.org", "www.eso.org", "/public/images/",
        "cdn.eso.org", "/images/", "/public/outreach/copyright/",
        "are licensed under a Creative Commons Attribution 4.0 International License, and may on a " +
            "non-exclusive basis be reproduced without fee provided the credit is clear and visible.",
        listOf("potw/"),
        listOf("galaxies", "nebulae", "starclusters", "stars", "galaxyclusters", "cosmology", "blackholes", "solarsystem"),
        "list"
    ),
    NOIRLAB(
        AssetProvider.NOIRLAB, "NOIRLab", "noirlab.edu", "noirlab.edu", "/public/images/",
        "storage.noirlab.edu", "/media/archives/images/", "/public/copyright/",
        "are licensed under a Creative Commons Attribution 4.0 International License, and hence may on a " +
            "non-exclusive basis be reproduced without fee provided the credit is clear and visible.",
        listOf("archive/top100/page/2/", "iotw/"),
        listOf("galaxies", "nebulae", "starclusters", "stars", "galaxyclusters", "cosmology", "blackholes", "solarsystem", "multiwavelength"),
        "page"
    );

    val hosts: Set<String> get() = setOf(domain, "www.$domain", webHost, cdnHost)
    val policyUrl: String get() = "https://$webHost$policyPath"
    val discoveryUrls: List<String>
        get() = (listOf("feed/", "archive/top100/") + extraListings).map { "https://$webHost$pagePath$it" }

    /** One page of a category listing; the first page is the category's own address. */
    fun categoryUrl(category: String, page: Int): String =
        "https://$webHost${pagePath}archive/category/$category/" + if (page <= 1) "" else "$pageSegment/$page/"

    fun pageUrl(id: String) = "https://$webHost$pagePath$id/"

    fun renditionUrl(rendition: Rendition, id: String) = "https://$cdnHost$cdnPath${rendition.path}/$id.jpg"

    /** Only observations suit a space screensaver; photographs show people, buildings or hardware. */
    fun acceptsType(type: String): Boolean = type == OBSERVATION

    companion object {
        const val OBSERVATION = "Observation"

        fun forProvider(provider: AssetProvider): DjangoplicitySite? = entries.firstOrNull { it.provider == provider }
    }
}

private fun esaStatement(label: String) =
    "$label images, videos and web texts are released under the Creative Commons " +
        "Attribution 4.0 International license and may on a non-exclusive basis be reproduced " +
        "without fee provided they are clearly and visibly credited."