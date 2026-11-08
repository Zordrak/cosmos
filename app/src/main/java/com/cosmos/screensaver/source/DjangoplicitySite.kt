package com.cosmos.screensaver.source

import com.cosmos.screensaver.rights.AssetProvider

/**
 * ESA/Hubble and ESA/Webb run the same publishing platform, so both share one parser.
 * Every URL produced here must also satisfy [ApprovedEndpoints].
 */
internal enum class DjangoplicitySite(
    val provider: AssetProvider,
    val label: String,
    val host: String,
    val cdnHost: String
) {
    HUBBLE(AssetProvider.ESA_HUBBLE, "ESA/Hubble", "esahubble.org", "cdn.esahubble.org"),
    WEBB(AssetProvider.ESA_WEBB, "ESA/Webb", "esawebb.org", "cdn.esawebb.org");

    val policyUrl: String get() = "https://$host/copyright/"
    val discoveryUrls: List<String>
        get() = listOf("https://$host/images/feed/", "https://$host/images/archive/top100/")

    /** Exact sentence each site's usage policy must still contain for any asset to be admitted. */
    val policyStatement: String
        get() = "$label images, videos and web texts are released under the Creative Commons " +
            "Attribution 4.0 International license and may on a non-exclusive basis be reproduced " +
            "without fee provided they are clearly and visibly credited."

    fun pageUrl(id: String) = "https://$host/images/$id/"

    companion object {
        fun forProvider(provider: AssetProvider): DjangoplicitySite? = entries.firstOrNull { it.provider == provider }
    }
}
