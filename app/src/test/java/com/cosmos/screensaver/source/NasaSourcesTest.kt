package com.cosmos.screensaver.source

import com.cosmos.screensaver.rights.AssetProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import kotlin.random.Random

class NasaSourcesTest {
    private val apodJson = """
        [
          {"slug":"apod-2026-november-8-a-synthetic-nebula",
           "link":"https://science.nasa.gov/image-detail/apod-2026-november-8-a-synthetic-nebula/",
           "title":{"rendered":"APOD: 2026 November 8 &#8211; A Synthetic Nebula"},
           "content":{"rendered":"<p><a href=\"https://assets.science.nasa.gov/content/dam/science/cds/apod/neb.jpg\">img</a></p><p>Image Credit: Synthetic Observatory Authors &amp; editors: A. Person</p>"}},
          {"slug":"apod-2026-november-7-copyrighted",
           "link":"https://science.nasa.gov/image-detail/apod-2026-november-7-copyrighted/",
           "title":{"rendered":"APOD: 2026 November 7 &#8211; Copyrighted"},
           "content":{"rendered":"<p><a href=\"https://assets.science.nasa.gov/content/dam/science/cds/apod/c.png\">img</a></p><p>Image Credit &amp; Copyright: Someone Authors &amp; editors: A. Person</p>"}},
          {"slug":"not-apod","link":"https://science.nasa.gov/x/","title":{"rendered":"x"},"content":{"rendered":""}},
          {"slug":"apod-no-image","link":"https://science.nasa.gov/y/","title":{"rendered":"y"},"content":{"rendered":"<p>No image here</p>"}}
        ]
    """.trimIndent()

    private val libraryJson = """
        {"collection":{"items":[
          {"data":[{"media_type":"image","nasa_id":"PIA99001","title":"Synthetic Spiral Galaxy","photographer":"NASA/Synthetic","description":"A galaxy."}],
           "links":[{"rel":"canonical","href":"https://images-assets.nasa.gov/image/PIA99001/PIA99001~orig.jpg"},
                    {"rel":"preview","href":"https://images-assets.nasa.gov/image/PIA99001/PIA99001~medium.jpg","width":1280,"height":720}]},
          {"data":[{"media_type":"image","nasa_id":"PIA99002","title":"Artist's concept of a rocket","description":"x"}],
           "links":[{"rel":"canonical","href":"https://images-assets.nasa.gov/image/PIA99002/PIA99002~orig.jpg"}]},
          {"data":[{"media_type":"video","nasa_id":"VID99003","title":"A video"}],"links":[]},
          {"data":[{"media_type":"image","nasa_id":"PIA99004","title":"Offsite","description":"x"}],
           "links":[{"rel":"canonical","href":"https://example.com/PIA99004~orig.jpg"}]}
        ]}}
    """.trimIndent()

    @Test
    fun parsesApodArticlesAndFlagsCopyrightedOnes() {
        val items = ApodParser.items(apodJson)

        assertEquals(listOf("apod-2026-november-8-a-synthetic-nebula", "apod-2026-november-7-copyrighted"), items.map { it.slug })
        val first = items[0]
        assertEquals("A Synthetic Nebula", first.title)
        assertEquals("Synthetic Observatory", first.credit)
        assertEquals("https://assets.science.nasa.gov/content/dam/science/cds/apod/neb.jpg", first.imageUrl)
        assertFalse(first.copyrightMarked)
        assertTrue(items[1].copyrightMarked)
    }

    @Test
    fun apodParserToleratesGarbage() {
        assertEquals(emptyList<ApodItem>(), ApodParser.items("not json"))
    }

    @Test
    fun parsesLibraryItemsAndSkipsUnwantedOnes() {
        val items = NasaLibraryParser.items(libraryJson)

        assertEquals(listOf("PIA99001"), items.map { it.nasaId })
        val item = items.single()
        assertEquals("NASA/Synthetic", item.credit)
        assertEquals("https://images-assets.nasa.gov/image/PIA99001/PIA99001~orig.jpg", item.originalUrl)
        assertEquals(1280, item.previewWidth)
        assertFalse(item.copyrightMarked)
        assertEquals(emptyList<NasaLibraryItem>(), NasaLibraryParser.items("{"))
    }

    @Test
    fun skipsHardwareAndNonAstronomicalItems() {
        fun json(id: String, title: String, description: String) =
            """{"collection":{"items":[{"data":[{"media_type":"image","nasa_id":"$id","title":"$title",
              "description":"$description"}],"links":[{"rel":"canonical","href":
              "https://images-assets.nasa.gov/image/$id/$id~orig.jpg"}]}]}}"""

        fun ids(title: String, description: String) =
            NasaLibraryParser.items(json("PIA99010", title, description)).map { it.nasaId }

        assertEquals(emptyList<String>(), ids("Fission Surface Power", "A reactor for the Moon."))
        assertEquals(emptyList<String>(), ids("IPEx Testbed Filming", "Testing on the Moon."))
        assertEquals(emptyList<String>(), ids("Orion spacecraft", "Heading to the Moon."))
        assertEquals(emptyList<String>(), ids("Lunch with visitors", "People eating."))
        assertEquals(emptyList<String>(), ids("Comet 67P Changes", "A comet seen over time."))
        assertEquals(emptyList<String>(), ids("Comet 67P", "Top center images: ESA/Rosetta. A comet."))
        assertEquals(emptyList<String>(), ids("A Comet", "An annotated view of a comet."))
        assertEquals(listOf("PIA99010"), ids("Barred Spiral Galaxy", "A galaxy of stars."))
    }

    @Test
    fun everyNasaUrlTheAdaptersUseIsApproved() {
        assertTrue(ApprovedEndpoints.isApproved(ApodAdapter.listingUrl(3)))
        assertTrue(ApprovedEndpoints.isApproved(NasaLibraryAdapter.searchUrl("hubble deep field")))
        assertTrue(ApprovedEndpoints.isApproved("https://images-assets.nasa.gov/image/PIA99001/PIA99001~orig.jpg"))
    }

    @Test
    fun apodAdapterProbesDimensionsFromTheImageHeader() {
        val fetcher = FakeFetcher(
            documents = mapOf(ApodAdapter.listingUrl(1) to apodJson),
            prefixes = mapOf(
                "https://assets.science.nasa.gov/content/dam/science/cds/apod/neb.jpg" to ImageHeaderTest.jpeg(5000, 3000)
            )
        )

        val discovery = ApodAdapter(pagesPerRefresh = 1).discover(fetcher, Random(1))
        val lead = discovery.leads.single { it.id.endsWith("synthetic-nebula") }
        val ready = lead.resolve() as CandidateResult.Ready

        assertTrue(discovery.reached)
        assertEquals(AssetProvider.APOD, ready.candidate.provider)
        assertEquals(5000, ready.candidate.declaredWidth)
        assertEquals(3000, ready.candidate.options.single().height)
    }

    @Test
    fun libraryAdapterFiltersByPreviewAspectAndProbesTheOriginal() {
        val query = NasaLibraryAdapter.QUERIES.first()
        val portrait = libraryJson.replace("\"width\":1280,\"height\":720", "\"width\":720,\"height\":1280")
        val adapter = NasaLibraryAdapter(queriesPerRefresh = NasaLibraryAdapter.QUERIES.size)
        val allQueries = NasaLibraryAdapter.QUERIES.associate { NasaLibraryAdapter.searchUrl(it) to libraryJson }

        val landscape = adapter.discover(FakeFetcher(documents = allQueries), Random(1))
        assertEquals(listOf("PIA99001"), landscape.leads.map { it.id })
        assertEquals("nebula", query)

        val rejected = adapter.discover(
            FakeFetcher(documents = NasaLibraryAdapter.QUERIES.associate { NasaLibraryAdapter.searchUrl(it) to portrait }),
            Random(1)
        )
        assertEquals(emptyList<String>(), rejected.leads.map { it.id })

        val fetcher = FakeFetcher(
            documents = allQueries,
            prefixes = mapOf(
                "https://images-assets.nasa.gov/image/PIA99001/PIA99001~orig.jpg" to ImageHeaderTest.png(6000, 4000)
            )
        )
        val ready = adapter.discover(fetcher, Random(1)).leads.single().resolve() as CandidateResult.Ready
        assertEquals(6000, ready.candidate.declaredWidth)
        assertEquals("https://images.nasa.gov/details/PIA99001", ready.candidate.pageUrl)
    }

    @Test
    fun unreadableHeadersAreSkippedAndUnreachableImagesFail() {
        val bad = FakeFetcher(
            documents = mapOf(ApodAdapter.listingUrl(1) to apodJson),
            prefixes = mapOf("https://assets.science.nasa.gov/content/dam/science/cds/apod/neb.jpg" to byteArrayOf(1, 2, 3))
        )
        val lead = ApodAdapter(1).discover(bad, Random(1)).leads.first()
        assertTrue(lead.resolve() is CandidateResult.Skip)

        val missing = FakeFetcher(documents = mapOf(ApodAdapter.listingUrl(1) to apodJson))
        assertTrue(ApodAdapter(1).discover(missing, Random(1)).leads.first().resolve() is CandidateResult.Failed)
        assertNull(ImageHeader.dimensions(byteArrayOf()))
    }

    private class FakeFetcher(
        private val documents: Map<String, String> = emptyMap(),
        private val prefixes: Map<String, ByteArray> = emptyMap()
    ) : Fetcher {
        override fun fetchText(url: String, maxBytes: Int) = documents[url] ?: throw IOException("HTTP 404 from $url")
        override fun fetchPrefix(url: String, maxBytes: Int) = prefixes[url] ?: throw IOException("HTTP 404 from $url")
        override fun download(url: String, destination: File, maxBytes: Long): Download = throw IOException("unused")
    }
}
