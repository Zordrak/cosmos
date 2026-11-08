package com.cosmos.screensaver.source

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovedEndpointsTest {
    @Test
    fun allowsOnlyHttpsOnApprovedHosts() {
        listOf(
            "https://esahubble.org/copyright/",
            "https://cdn.esawebb.org/archives/images/publicationjpg/weic9901a.jpg",
            "https://WWW.esahubble.org:443/images/feed/",
            "https://images-api.nasa.gov/search?q=nebula",
            "https://images-assets.nasa.gov/image/x/x~orig.jpg",
            "https://science.nasa.gov/wp-json/wp/v2/image-article",
            "https://assets.science.nasa.gov/dynamicimage/x.jpg"
        ).forEach { assertTrue(it, ApprovedEndpoints.isApproved(it)) }

        listOf(
            "http://esahubble.org/copyright/",
            "https://esahubble.org.example.com/",
            "https://example.com/esahubble.org/",
            "https://user@esahubble.org/",
            "https://esahubble.org:8443/",
            "https://images-api.nasa.gov.example.com/",
            "http://images-api.nasa.gov/search",
            " https://esahubble.org/",
            "not a url"
        ).forEach { assertFalse(it, ApprovedEndpoints.isApproved(it)) }
    }

    @Test
    fun everyGeneratedSiteUrlIsApproved() {
        DjangoplicitySite.entries.forEach { site ->
            (site.discoveryUrls + site.policyUrl + site.pageUrl("abc9901a"))
                .forEach { assertTrue(it, ApprovedEndpoints.isApproved(it)) }
            assertTrue(ApprovedEndpoints.isApproved("https://${site.cdnHost}/archives/images/large/x.jpg"))
        }
    }
}
