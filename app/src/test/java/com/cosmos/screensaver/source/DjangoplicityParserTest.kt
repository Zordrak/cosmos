package com.cosmos.screensaver.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DjangoplicityParserTest {
    private val hubble = DjangoplicitySite.HUBBLE
    private val webb = DjangoplicitySite.WEBB

    @Test
    fun parsesHubbleStyleAssetPage() {
        val page = DjangoplicityParser.assetPage(Fixtures.assetPage(hubble, "heic9901a"), hubble)!!

        assertEquals("heic9901a", page.id)
        assertEquals("Synthetic nebula & friends", page.title)
        assertEquals("Observation", page.type)
        assertEquals(4000, page.widthPx)
        assertEquals(2250, page.heightPx)
        assertEquals("ESA/Synthetic & Example Team", page.credit)
        assertEquals(
            listOf(
                "https://cdn.esahubble.org/archives/images/publicationjpg/heic9901a.jpg",
                "https://cdn.esahubble.org/archives/images/large/heic9901a.jpg"
            ),
            page.imageUrls
        )
        assertTrue(page.linksUsagePolicy)
        assertTrue(page.statesMandatoryCredit)
        assertEquals(emptyList<String>(), page.exceptionMarkers)
    }

    @Test
    fun parsesWebbStyleTableWithPaddedCells() {
        val page = DjangoplicityParser.assetPage(Fixtures.assetPage(webb, "weic9901a", type = "Collage"), webb)!!

        assertEquals("weic9901a", page.id)
        assertEquals("Collage", page.type)
        assertEquals(listOf("publicationjpg", "large").map {
            "https://cdn.esawebb.org/archives/images/$it/weic9901a.jpg"
        }, page.imageUrls)
    }

    @Test
    fun ignoresRenditionsOnOtherHosts() {
        val html = Fixtures.assetPage(hubble, "heic9901a", renditions = emptyList()) +
            "<a href=\"https://cdn.example.org/archives/images/publicationjpg/heic9901a.jpg\">x</a>" +
            "<a href=\"https://cdn.esawebb.org/archives/images/publicationjpg/heic9901a.jpg\">x</a>"

        assertEquals(emptyList<String>(), DjangoplicityParser.assetPage(html, hubble)!!.imageUrls)
    }

    @Test
    fun detectsExceptionWordingInCaptionOrCredit() {
        val captioned = Fixtures.assetPage(hubble, "heic9901a", caption = "Image courtesy of a partner.")
        val credited = Fixtures.assetPage(hubble, "heic9901b", credit = "&copy; Example Observatory")

        assertEquals(listOf("courtesy"), DjangoplicityParser.assetPage(captioned, hubble)!!.exceptionMarkers)
        assertTrue(DjangoplicityParser.assetPage(credited, hubble)!!.exceptionMarkers.contains("\u00a9"))
    }

    @Test
    fun reportsMissingNoticeAndPolicyLink() {
        val page = DjangoplicityParser.assetPage(
            Fixtures.assetPage(hubble, "heic9901a", creditNotice = false, policyLink = false),
            hubble
        )!!

        assertFalse(page.statesMandatoryCredit)
        assertFalse(page.linksUsagePolicy)
    }

    @Test
    fun rejectsPagesWithoutRequiredParts() {
        assertNull(DjangoplicityParser.assetPage("<html><h1>Nothing</h1></html>", hubble))
        val noCredit = Fixtures.assetPage(hubble, "heic9901a").replace("<div class=\"credit\">", "<div>")
        assertNull(DjangoplicityParser.assetPage(noCredit, hubble))
        val badId = Fixtures.assetPage(hubble, "potw")
        assertNull(DjangoplicityParser.assetPage(badId, hubble))
    }

    @Test
    fun discoversIdsFromFeedsAndListingsOnTheSameSiteOnly() {
        val feed = Fixtures.feed(hubble, "heic9901a", "potw9901a", "heic9901a") +
            "<link>https://esawebb.org/images/weic9901a/</link>"
        assertEquals(listOf("heic9901a", "potw9901a"), DjangoplicityParser.discoveredIds(feed, hubble))

        val listing = Fixtures.listing("weic9901a", "weic9902a") +
            "<a href=\"https://esahubble.org/images/heic9903a/\">other site</a>"
        assertEquals(listOf("weic9901a", "weic9902a"), DjangoplicityParser.discoveredIds(listing, webb))
    }

    @Test
    fun excludesNavigationPaths() {
        listOf("archive", "potw", "top100", "viewall", "a", "Upper").forEach {
            assertFalse(it, DjangoplicityParser.isAssetId(it))
        }
        assertTrue(DjangoplicityParser.isAssetId("opo0501a"))
    }

    @Test
    fun policyMustStillContainTheExactLicenceStatement() {
        assertTrue(DjangoplicityParser.policyIsCurrent(Fixtures.policyPage(hubble), hubble))
        assertTrue(DjangoplicityParser.policyIsCurrent(Fixtures.policyPage(webb), webb))
        assertFalse(DjangoplicityParser.policyIsCurrent(Fixtures.policyPage(hubble), webb))
        val changed = Fixtures.policyPage(hubble, hubble.policyStatement.replace("Attribution 4.0", "Attribution-NonCommercial 4.0"))
        assertFalse(DjangoplicityParser.policyIsCurrent(changed, hubble))
    }

    @Test
    fun decodesEntitiesAndNormalisesWhitespace() {
        assertEquals(
            "Peißker & Özsaraç \u2014 \u00a9 x",
            HtmlText.toPlainText("<p>Pei&#223;ker  &amp;\n &#214;zsara&ccedil; &mdash; &copy; <b>x</b></p>")
        )
        assertEquals("<", HtmlText.decodeEntities("&#x3c;"))
        assertEquals("&unknown;", HtmlText.decodeEntities("&unknown;"))
        assertEquals("a b", HtmlText.toPlainText("a<script>var x = 1;</script>b"))
    }

    @Test
    fun inlineLinksDoNotAddSpacesToCredits() {
        assertEquals(
            "NASA, ESA, and A. Person (Example Institute) and the Team",
            HtmlText.toPlainText(
                "<p><a href=\"https://example.org\">NASA</a>, <a href=\"https://example.org\">ESA</a>, " +
                    "and A. Person (<a href=\"https://example.org\">Example Institute</a>) and the Team</p>"
            )
        )
        assertEquals("Line one Line two", HtmlText.toPlainText("<p>Line one</p><p>Line two</p>"))
    }
}
