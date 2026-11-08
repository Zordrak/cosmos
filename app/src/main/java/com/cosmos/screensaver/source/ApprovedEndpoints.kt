package com.cosmos.screensaver.source

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * The only hosts Cosmos may contact. Every request and every redirect target is checked here,
 * so adding a source requires an explicit, reviewed change to this list.
 */
internal object ApprovedEndpoints {
    private val HOSTS = setOf(
        "esahubble.org", "www.esahubble.org", "cdn.esahubble.org",
        "esawebb.org", "www.esawebb.org", "cdn.esawebb.org"
    )

    fun isApproved(url: String): Boolean {
        if (url != url.trim()) return false
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return false
        }
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.rawUserInfo == null &&
            (uri.port == -1 || uri.port == 443) &&
            host in HOSTS
    }
}
