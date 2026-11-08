package com.cosmos.screensaver.sync

import com.cosmos.screensaver.catalogue.CatalogueEntry
import com.cosmos.screensaver.catalogue.CatalogueStore
import com.cosmos.screensaver.rights.AssetProvider
import com.cosmos.screensaver.rights.LicenceValidator
import com.cosmos.screensaver.source.Download
import com.cosmos.screensaver.source.DjangoplicitySite
import com.cosmos.screensaver.source.Fetcher
import com.cosmos.screensaver.source.Fixtures
import com.cosmos.screensaver.source.ResourceTooLargeException
import com.cosmos.screensaver.source.sha256Of
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.random.Random

class CatalogueRefresherTest {
    private val hubble = DjangoplicitySite.HUBBLE
    private val webb = DjangoplicitySite.WEBB
    private lateinit var directory: File
    private lateinit var store: CatalogueStore
    private lateinit var fetcher: FakeFetcher
    private val dimensions = mutableMapOf<String, ImageDimensions>()
    private var now = 1_800_000_000_000L

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("cosmos-refresher").toFile()
        store = CatalogueStore(directory)
        fetcher = FakeFetcher()
        DjangoplicitySite.entries.forEach { site ->
            fetcher.documents[site.policyUrl] = Fixtures.policyPage(site)
            fetcher.documents[site.discoveryUrls[0]] = Fixtures.feed(site)
            fetcher.documents[site.discoveryUrls[1]] = Fixtures.listing()
        }
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun admitsRightsClearedLandscapeObservationsWithFullProvenance() {
        publish(hubble, "heic9901a")
        publish(webb, "weic9901a")

        val result = refresher().refresh()

        assertEquals(2, result.admitted)
        val catalogue = store.load()
        assertEquals(setOf("ESA_HUBBLE:heic9901a", "ESA_WEBB:weic9901a"), catalogue.entries.map { it.key }.toSet())
        val entry = catalogue.entries.single { it.provider == AssetProvider.ESA_HUBBLE }
        assertEquals("Synthetic nebula & friends", entry.title)
        assertEquals("ESA/Synthetic & Example Team", entry.credit)
        assertEquals("https://esahubble.org/images/heic9901a/", entry.sourcePageUrl)
        assertEquals("https://cdn.esahubble.org/archives/images/publicationjpg/heic9901a.jpg", entry.assetUrl)
        assertEquals("CC_BY_4_0", entry.rightsBasis)
        assertEquals(LicenceValidator.CC_BY_4_0_URL, entry.licenceUrl)
        assertEquals("GB", entry.territory)
        assertEquals(LicenceValidator.POLICY_VERSION, entry.policyVersion)
        assertEquals(listOf(hubble.policyUrl, entry.sourcePageUrl), entry.evidence.map { it.url })
        assertEquals(CatalogueEntry.ALTERATION_NONE, entry.alteration)
        val file = store.imageFile(entry)
        assertEquals(entry.sha256, sha256Of(file))
        assertEquals("${entry.sha256}.jpg", file.name)
        assertEquals(setOf(file.name, store.imageFile(catalogue.entries.single { it != entry }).name),
            store.imagesDirectory.list()!!.toSet())
        assertTrue(catalogue.lastSummary.startsWith("2 images available"))
        assertEquals(now, catalogue.lastSuccessAtEpochMillis)
    }

    @Test
    fun rejectsAssetsThatFailAnyCheckAndRemembersWhy() {
        publish(hubble, "heic9901a", type = "Collage")
        publish(hubble, "heic9902a", caption = "Used with permission of the observatory.")
        publish(hubble, "heic9903a", creditNotice = false)
        publish(hubble, "heic9904a", width = 2000, height = 3000)
        publish(hubble, "heic9905a", actual = ImageDimensions(1200, 675), width = 1200, height = 675)
        publish(hubble, "heic9906a", actual = ImageDimensions(4000, 3000))
        publish(hubble, "heic9907a", renditions = emptyList())
        publish(hubble, "heic9908a", notJpeg = true)
        publish(hubble, "heic9909a", tooLarge = true)

        val result = refresher().refresh()

        assertEquals(0, result.admitted)
        assertEquals(
            mapOf(
                "ESA_HUBBLE:heic9901a" to "NOT_AN_OBSERVATION",
                "ESA_HUBBLE:heic9902a" to "RIGHTS_CONFLICTING_EVIDENCE",
                "ESA_HUBBLE:heic9903a" to "RIGHTS_MISSING_RIGHTS_STATEMENT",
                "ESA_HUBBLE:heic9904a" to "NOT_LANDSCAPE",
                "ESA_HUBBLE:heic9905a" to "IMAGE_TOO_SMALL",
                "ESA_HUBBLE:heic9906a" to "DIMENSION_MISMATCH",
                "ESA_HUBBLE:heic9907a" to "NO_SUITABLE_RENDITION",
                "ESA_HUBBLE:heic9908a" to "NOT_A_JPEG",
                "ESA_HUBBLE:heic9909a" to "IMAGE_TOO_LARGE"
            ),
            store.load().rejections.mapValues { it.value.reason }
        )
        assertEquals(emptyList<String>(), store.imagesDirectory.list()!!.toList())
    }

    @Test
    fun rejectsEverythingWhenTheTerritoryIsUnknown() {
        publish(hubble, "heic9901a")

        refresher(territory = null).refresh()

        assertEquals(emptyList<CatalogueEntry>(), store.load().entries)
        assertEquals("RIGHTS_INVALID_USE_TERRITORY", store.load().rejections.values.single().reason)
    }

    @Test
    fun doesNotRetryRecentRejectionsButRetriesOldOnes() {
        publish(hubble, "heic9901a", type = "Collage")
        refresher().refresh()
        val pageUrl = hubble.pageUrl("heic9901a")
        publish(hubble, "heic9901a")
        fetcher.requests.clear()

        refresher().refresh()
        assertFalse(pageUrl in fetcher.requests)

        now += 31L * 24 * 60 * 60 * 1000
        refresher().refresh()
        assertTrue(pageUrl in fetcher.requests)
        assertEquals(listOf("ESA_HUBBLE:heic9901a"), store.load().entries.map { it.key })
    }

    @Test
    fun withdrawsASitesImagesWhenItsPolicyChanges() {
        publish(hubble, "heic9901a")
        publish(webb, "weic9901a")
        refresher().refresh()
        fetcher.documents[hubble.policyUrl] = Fixtures.policyPage(hubble, "All rights reserved.")

        val result = refresher().refresh()

        assertEquals(1, result.withdrawn)
        assertEquals(listOf("ESA_WEBB:weic9901a"), store.load().entries.map { it.key })
        assertEquals(1, store.imagesDirectory.list()!!.size)
    }

    @Test
    fun keepsExistingImagesWhenOffline() {
        publish(hubble, "heic9901a")
        refresher().refresh()
        fetcher.offline = true

        val result = refresher().refresh()

        assertEquals(1, result.total)
        assertEquals(listOf("ESA_HUBBLE:heic9901a"), store.load().entries.map { it.key })
        assertTrue(store.load().lastSummary.contains("usage policy unavailable"))
    }

    @Test
    fun onlyRefreshesEnabledSites() {
        publish(hubble, "heic9901a")
        publish(webb, "weic9901a")

        refresher(sites = setOf(webb)).refresh()

        assertEquals(listOf("ESA_WEBB:weic9901a"), store.load().entries.map { it.key })
        assertFalse(hubble.policyUrl in fetcher.requests)
    }

    @Test
    fun enforcesCapacityKeepingTheNewestImages() {
        listOf("heic9901a", "heic9902a", "heic9903a").forEach { publish(hubble, it) }
        val limits = CatalogueRefresher.Limits(maxEntries = 2, maxAdmissionsPerSite = 1)

        repeat(3) {
            now += 1000
            refresher(limits = limits).refresh()
        }

        val entries = store.load().entries
        assertEquals(2, entries.size)
        assertEquals(2, store.imagesDirectory.list()!!.size)
        assertTrue(entries.all { it.admittedAtEpochMillis > 1_800_000_001_000L })
    }

    @Test
    fun capsAdmissionsPerRefresh() {
        (1..9).forEach { publish(hubble, "heic990${it}a") }

        val result = refresher(limits = CatalogueRefresher.Limits(maxAdmissionsPerSite = 3)).refresh()

        assertEquals(3, result.admitted)
    }

    @Test
    fun preservesEntriesRemovedConcurrently() {
        publish(hubble, "heic9901a")
        refresher().refresh()
        publish(hubble, "heic9902a")
        val concurrentRemoval = object : ImageInspector {
            override fun inspect(file: File): ImageDimensions? {
                store.remove("ESA_HUBBLE:heic9901a")
                return dimensions[String(file.readBytes(), Charsets.ISO_8859_1).substring(3)]
            }
        }

        refresher(inspector = concurrentRemoval).refresh()

        assertEquals(listOf("ESA_HUBBLE:heic9902a"), store.load().entries.map { it.key })
    }

    private fun refresher(
        territory: String? = "GB",
        sites: Set<DjangoplicitySite> = DjangoplicitySite.entries.toSet(),
        limits: CatalogueRefresher.Limits = CatalogueRefresher.Limits(),
        inspector: ImageInspector = ImageInspector { dimensions[String(it.readBytes(), Charsets.ISO_8859_1).substring(3)] }
    ) = CatalogueRefresher(
        fetcher = fetcher,
        store = store,
        inspector = inspector,
        territory = territory,
        sites = sites,
        clock = { now },
        random = Random(7),
        limits = limits
    )

    private fun publish(
        site: DjangoplicitySite,
        id: String,
        type: String = "Observation",
        width: Int = 4000,
        height: Int = 2250,
        actual: ImageDimensions = ImageDimensions(width, height),
        caption: String = "A project-created description.",
        creditNotice: Boolean = true,
        renditions: List<String> = listOf("publicationjpg", "large"),
        notJpeg: Boolean = false,
        tooLarge: Boolean = false
    ) {
        val feedUrl = site.discoveryUrls[0]
        val ids = Regex("""/images/([a-z0-9]+)/</link>""").findAll(fetcher.documents[feedUrl]!!)
            .map { it.groupValues[1] }.toMutableSet().apply { add(id) }
        fetcher.documents[feedUrl] = Fixtures.feed(site, *ids.toTypedArray())
        fetcher.documents[site.pageUrl(id)] = Fixtures.assetPage(
            site, id, type = type, width = width, height = height, caption = caption,
            creditNotice = creditNotice, renditions = renditions
        )
        val url = "https://${site.cdnHost}/archives/images/publicationjpg/$id.jpg"
        val magic = if (notJpeg) "PNG" else "\u00ff\u00d8\u00ff"
        fetcher.images[url] = (magic + id).toByteArray(Charsets.ISO_8859_1)
        if (tooLarge) fetcher.tooLarge += url
        dimensions[id] = actual
    }

    private class FakeFetcher : Fetcher {
        val documents = mutableMapOf<String, String>()
        val images = mutableMapOf<String, ByteArray>()
        val tooLarge = mutableSetOf<String>()
        val requests = mutableListOf<String>()
        var offline = false

        override fun fetchText(url: String, maxBytes: Int): String {
            requests += url
            if (offline) throw IOException("offline")
            return documents[url] ?: throw IOException("HTTP 404 from $url")
        }

        override fun download(url: String, destination: File, maxBytes: Long): Download {
            requests += url
            if (offline) throw IOException("offline")
            if (url in tooLarge) throw ResourceTooLargeException("too large")
            val bytes = images[url] ?: throw IOException("HTTP 404 from $url")
            destination.writeBytes(bytes)
            return Download(sha256Of(destination), bytes.size.toLong())
        }
    }
}
