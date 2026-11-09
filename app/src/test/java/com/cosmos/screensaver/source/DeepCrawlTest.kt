package com.cosmos.screensaver.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.random.Random

class DeepCrawlTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val hubble = DjangoplicitySite.HUBBLE
    private val eso = DjangoplicitySite.ESO

    private fun listing(site: DjangoplicitySite, category: String, lastPage: Int, vararg items: Triple<String, Int, Int>): String {
        val objects = items.joinToString(", ") { (id, w, h) ->
            "{ id: '$id', title: 'Synthetic $id', width: $w, height: $h, src: 'https://cdn.example/$id.jpg', url: '/images/$id/', potw: '' }"
        }
        val segment = if (site == eso) "list" else "page"
        val pagination = (1..lastPage).joinToString("") {
            "<li><a href=\"${site.pagePath}archive/category/$category/$segment/$it/\">$it</a></li>"
        }
        return "<script>var items = [ $objects ];</script><ul class=\"pagination\">$pagination</ul>"
    }

    private class Fake(val documents: MutableMap<String, String> = mutableMapOf()) : Fetcher {
        val requests = mutableListOf<String>()
        override fun fetchText(url: String, maxBytes: Int): String {
            requests += url
            return documents[url] ?: throw IOException("HTTP 404 from $url")
        }
        override fun fetchPrefix(url: String, maxBytes: Int): ByteArray = throw IOException("unused")
        override fun download(url: String, destination: File, maxBytes: Long): Download = throw IOException("unused")
    }

    private fun filter(done: Set<String> = emptySet(), retired: Set<String> = emptySet(), minWidth: Int = 0) =
        object : LeadFilter {
            override fun status(id: String) = when (id) {
                in done -> LeadStatus.DONE
                in retired -> LeadStatus.RETIRED
                else -> LeadStatus.FRESH
            }

            override fun fits(width: Int, height: Int) = width >= minWidth
        }

    private fun fetcherWithGalaxies(pages: Int): Fake {
        val fake = Fake()
        fake.documents[hubble.discoveryUrls[0]] = "<rss></rss>"
        for (page in 1..pages) {
            fake.documents[hubble.categoryUrl("galaxies", page)] = listing(
                hubble, "galaxies", pages,
                Triple("heic${page}01a", 4000, 3000), Triple("heic${page}02a", 4000, 3000)
            )
        }
        return fake
    }

    @Test
    fun parsesListingObjectsAndPagination() {
        val html = listing(hubble, "galaxies", 38, Triple("heic0206a", 3857, 2893), Triple("potw1752b", 4036, 4013))

        assertEquals(
            listOf(ListingItem("heic0206a", 3857, 2893), ListingItem("potw1752b", 4036, 4013)),
            DjangoplicityParser.listingItems(html)
        )
        assertEquals(38, DjangoplicityParser.listingLastPage(html))
        assertEquals(1, DjangoplicityParser.listingLastPage("<p>no pagination</p>"))
    }

    @Test
    fun esoPagesUseTheListSegmentAndOthersUsePage() {
        assertEquals("https://esahubble.org/images/archive/category/galaxies/", hubble.categoryUrl("galaxies", 1))
        assertEquals("https://esahubble.org/images/archive/category/galaxies/page/3/", hubble.categoryUrl("galaxies", 3))
        assertEquals("https://www.eso.org/public/images/archive/category/nebulae/list/2/", eso.categoryUrl("nebulae", 2))
        val html = listing(eso, "nebulae", 12, Triple("eso1322a", 3397, 3451))
        assertEquals(12, DjangoplicityParser.listingLastPage(html))
    }

    @Test
    fun crawlGoesPastPagesWhoseEveryPictureIsAlreadyAccountedFor() {
        val fake = fetcherWithGalaxies(pages = 3)
        val adapter = DjangoplicityAdapter(hubble, ListingCache(null)) { 0L }
        val done = setOf("heic101a", "heic102a", "heic201a", "heic202a")

        val ids = adapter.discover(fake, Random(1), filter(done = done)).leads.map { it.id }

        assertEquals(listOf("heic301a", "heic302a"), ids)
    }

    @Test
    fun retiredPicturesAreReturnedAheadOfNewOnes() {
        val fake = fetcherWithGalaxies(pages = 2)
        val adapter = DjangoplicityAdapter(hubble, ListingCache(null)) { 0L }

        val ids = adapter.discover(fake, Random(1), filter(retired = setOf("heic202a"))).leads.map { it.id }

        assertEquals("heic202a", ids.first())
        assertEquals(4, ids.size)
    }

    @Test
    fun skipsPicturesThatCanNeverQualify() {
        val fake = Fake()
        fake.documents[hubble.discoveryUrls[0]] = "<rss></rss>"
        fake.documents[hubble.categoryUrl("galaxies", 1)] = listing(
            hubble, "galaxies", 1,
            Triple("heic0001a", 4000, 3000), Triple("heic0002a", 3000, 4000), Triple("heic0003a", 1500, 1000)
        )
        val adapter = DjangoplicityAdapter(hubble, ListingCache(null)) { 0L }

        val ids = adapter.discover(fake, Random(1), filter(minWidth = 3840)).leads.map { it.id }

        assertEquals(listOf("heic0001a"), ids)
    }

    @Test
    fun pagesAlreadyReadAreReplayedFromTheCacheWithoutTheNetwork() {
        val file = File(folder.root, "listings.json")
        val fake = fetcherWithGalaxies(pages = 2)
        DjangoplicityAdapter(hubble, ListingCache(file)) { 0L }.discover(fake, Random(1), filter())
        assertTrue(file.isFile)
        fake.requests.clear()

        val again = DjangoplicityAdapter(hubble, ListingCache(file)) { 1000L }
        val ids = again.discover(fake, Random(1), filter()).leads.map { it.id }

        assertEquals(4, ids.size)
        assertTrue(fake.requests.none { it.contains("/archive/category/galaxies/") })
    }

    @Test
    fun staleHeadPagesAreFetchedAgain() {
        val fake = fetcherWithGalaxies(pages = 1)
        val cache = ListingCache(null)
        DjangoplicityAdapter(hubble, cache) { 0L }.discover(fake, Random(1), filter())
        fake.requests.clear()

        DjangoplicityAdapter(hubble, cache) { 4L * 24 * 60 * 60 * 1000 }.discover(fake, Random(1), filter())

        assertTrue(fake.requests.contains(hubble.categoryUrl("galaxies", 1)))
    }

    @Test
    fun aMissingCategoryPageDoesNotFailTheDiscovery() {
        val fake = Fake()
        fake.documents[hubble.discoveryUrls[0]] = "<rss></rss>"

        val discovery = DjangoplicityAdapter(hubble, ListingCache(null)) { 0L }.discover(fake, Random(1), filter())

        assertTrue(discovery.reached)
        assertEquals(emptyList<Lead>(), discovery.leads.filter { it.id.startsWith("heic") })
    }

    @Test
    fun aTimedOutHeadlineListingStillLetsTheArchiveBeWalked() {
        val fake = object : Fetcher {
            override fun fetchText(url: String, maxBytes: Int): String = when (url) {
                hubble.categoryUrl("galaxies", 1) -> listing(hubble, "galaxies", 1, Triple("heic0001a", 4000, 3000))
                in hubble.discoveryUrls -> throw java.net.SocketTimeoutException("timeout")
                else -> throw IOException("HTTP 404 from $url")
            }
            override fun fetchPrefix(url: String, maxBytes: Int): ByteArray = throw IOException("unused")
            override fun download(url: String, destination: File, maxBytes: Long): Download = throw IOException("unused")
        }

        val discovery = DjangoplicityAdapter(hubble, ListingCache(null)) { 0L }.discover(fake, Random(1), filter())

        assertTrue(discovery.reached)
        assertEquals(listOf("heic0001a"), discovery.leads.map { it.id })
        assertTrue(discovery.errors.any { it.contains("listing unavailable") })
        assertTrue(discovery.errors.none { it.contains("archive page") })
    }

    @Test
    fun anUnreachableArchivePageIsReportedButAMissingOneIsNot() {
        val fake = object : Fetcher {
            override fun fetchText(url: String, maxBytes: Int): String = when (url) {
                hubble.discoveryUrls[0] -> "<rss></rss>"
                hubble.categoryUrl("galaxies", 1) -> throw java.net.SocketTimeoutException("timeout")
                else -> throw IOException("HTTP 404 from $url")
            }
            override fun fetchPrefix(url: String, maxBytes: Int): ByteArray = throw IOException("unused")
            override fun download(url: String, destination: File, maxBytes: Long): Download = throw IOException("unused")
        }

        val discovery = DjangoplicityAdapter(hubble, ListingCache(null)) { 0L }.discover(fake, Random(1), filter())

        assertEquals(listOf("ESA/Hubble archive page unavailable (timeout)"), discovery.errors.filter { it.contains("archive page") })
        assertTrue(discovery.incomplete)
    }

    @Test
    fun aDamagedCacheFileIsIgnored() {
        val file = File(folder.root, "listings.json")
        file.writeText("{not json")
        val cache = ListingCache(file)

        assertEquals(null, cache.get("HUBBLE/galaxies/1"))
        cache.put("HUBBLE/galaxies/1", ListingPage(5L, 2, listOf(ListingItem("heic0001a", 4000, 3000))))
        cache.flush()

        assertEquals(
            ListingPage(5L, 2, listOf(ListingItem("heic0001a", 4000, 3000))),
            ListingCache(file).get("HUBBLE/galaxies/1")
        )
        assertFalse(File(folder.root, "listings.json.tmp").exists())
    }
}
