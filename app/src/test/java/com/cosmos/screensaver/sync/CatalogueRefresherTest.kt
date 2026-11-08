package com.cosmos.screensaver.sync

import com.cosmos.screensaver.catalogue.CatalogueEntry
import com.cosmos.screensaver.catalogue.CatalogueStore
import com.cosmos.screensaver.rights.AssetProvider
import com.cosmos.screensaver.rights.LicenceValidator
import com.cosmos.screensaver.settings.ImageSelection
import com.cosmos.screensaver.settings.LicenceMode
import com.cosmos.screensaver.settings.QualityPreference
import com.cosmos.screensaver.source.DjangoplicityAdapter
import com.cosmos.screensaver.source.Download
import com.cosmos.screensaver.source.DjangoplicitySite
import com.cosmos.screensaver.source.Fetcher
import com.cosmos.screensaver.source.Fixtures
import com.cosmos.screensaver.source.Rendition
import com.cosmos.screensaver.source.ResourceTooLargeException
import com.cosmos.screensaver.source.Candidate
import com.cosmos.screensaver.source.CandidateResult
import com.cosmos.screensaver.source.Discovery
import com.cosmos.screensaver.source.Lead
import com.cosmos.screensaver.source.RenditionOption
import com.cosmos.screensaver.source.SourceAdapter
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
    private val eso = DjangoplicitySite.ESO
    private val noirlab = DjangoplicitySite.NOIRLAB

    /** Licence checking on, as for a published build. Most rights tests use this. */
    private val licensed = ImageSelection(LicenceMode.OPEN_LICENCE_ONLY, QualityPreference.FHD_OR_BETTER)
    private val personal = ImageSelection(LicenceMode.ANY_AVAILABLE, QualityPreference.UHD_ONLY)
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
            fetcher.documents[site.discoveryUrls[1]] = Fixtures.listing(site)
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
                "ESA_HUBBLE:heic9901a" to "UNSUITABLE_TYPE",
                "ESA_HUBBLE:heic9902a" to "RIGHTS_CONFLICTING_EVIDENCE",
                "ESA_HUBBLE:heic9903a" to "RIGHTS_MISSING_RIGHTS_STATEMENT",
                "ESA_HUBBLE:heic9904a" to "NOT_LANDSCAPE",
                "ESA_HUBBLE:heic9905a" to "LOW_RESOLUTION",
                "ESA_HUBBLE:heic9906a" to "DIMENSION_MISMATCH",
                "ESA_HUBBLE:heic9907a" to "NO_SUITABLE_RENDITION",
                "ESA_HUBBLE:heic9908a" to "NOT_AN_IMAGE",
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
        val limits = CatalogueRefresher.Limits(maxEntries = 2, maxAdmissionsPerSource = 1)

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

        val result = refresher(limits = CatalogueRefresher.Limits(maxAdmissionsPerSource = 3)).refresh()

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

    @Test
    fun revalidatesEntriesAdmittedUnderAnOlderRightsPolicy() {
        publish(hubble, "heic9901a")
        refresher().refresh()
        store.update { current -> current.copy(entries = current.entries.map { it.copy(credit = "Old", policyVersion = "1") }) }

        refresher().refresh()

        val entry = store.load().entries.single()
        assertEquals(LicenceValidator.POLICY_VERSION, entry.policyVersion)
        assertTrue(entry.credit != "Old")
    }

    @Test
    fun storesEachAdmissionBeforeTheRefreshFinishes() {
        publish(hubble, "heic9901a")
        publish(hubble, "heic9902a")
        val storedWhenInspecting = mutableListOf<Int>()
        val recording = ImageInspector { file ->
            storedWhenInspecting += store.load().entries.size
            dimensions[String(file.readBytes(), Charsets.ISO_8859_1).substring(3)]
        }

        refresher(inspector = recording).refresh()

        assertEquals(listOf(0, 1), storedWhenInspecting)
        assertEquals(2, store.load().entries.size)
    }

    @Test
    fun admitsEsoAndNoirlabImagesWithVerifiedRights() {
        publish(eso, "eso9901a")
        publish(noirlab, "noirlab9901a")

        refresher(sites = setOf(eso, noirlab)).refresh()

        val entries = store.load().entries.associateBy { it.provider }
        assertEquals("https://cdn.eso.org/images/publicationjpg/eso9901a.jpg", entries.getValue(AssetProvider.ESO).assetUrl)
        assertEquals(
            "https://storage.noirlab.edu/media/archives/images/publicationjpg/noirlab9901a.jpg",
            entries.getValue(AssetProvider.NOIRLAB).assetUrl
        )
        assertTrue(entries.values.all { it.rightsBasis == "CC_BY_4_0" })
    }

    @Test
    fun personalUseAdmitsPublicImagesWithoutCheckingRights() {
        publish(hubble, "heic9901a", caption = "Image courtesy of a partner.", creditNotice = false, credit = null)
        fetcher.documents[hubble.policyUrl] = Fixtures.policyPage(hubble, "All rights reserved.")

        refresher(selection = personal).refresh()

        val entry = store.load().entries.single()
        assertEquals(CatalogueEntry.RIGHTS_NOT_CHECKED, entry.rightsBasis)
        assertEquals("ESA/Hubble", entry.credit)
        assertEquals("", entry.licenceName)
        assertEquals(emptyList<Any>(), entry.evidence)
        assertFalse(hubble.policyUrl in fetcher.requests)
    }

    @Test
    fun personalUseStillSkipsNonImagery() {
        publish(hubble, "heic9901a", type = "Chart")

        refresher(selection = personal).refresh()

        assertEquals("UNSUITABLE_TYPE", store.load().rejections.values.single().reason)
    }

    @Test
    fun skipsLowResolutionImagesWithoutDownloadingThem() {
        publish(hubble, "heic9901a", width = 1920, height = 1080)

        refresher(selection = personal).refresh()

        assertEquals("LOW_RESOLUTION", store.load().rejections.values.single().reason)
        assertTrue(fetcher.requests.none { it.contains("cdn.") })
    }

    @Test
    fun downloadsTheFullSizeRenditionWhenThePublicationCopyIsTooSmallFor4k() {
        publish(hubble, "heic9901a", width = 7000, height = 3150, large = ImageDimensions(7000, 3150))

        refresher(selection = personal).refresh()

        val entry = store.load().entries.single()
        assertEquals(hubble.renditionUrl(Rendition.LARGE, "heic9901a"), entry.assetUrl)
        assertEquals(7000, entry.widthPx)
        assertFalse(hubble.renditionUrl(Rendition.PUBLICATION, "heic9901a") in fetcher.requests)
    }

    @Test
    fun triesTheFullSizeRenditionWhenTheDownloadedCopyIsSmallerThanDeclared() {
        publish(
            hubble, "heic9901a",
            actual = ImageDimensions(3000, 1688),
            large = ImageDimensions(4000, 2250)
        )

        refresher(selection = personal).refresh()

        assertEquals(hubble.renditionUrl(Rendition.LARGE, "heic9901a"), store.load().entries.single().assetUrl)
        assertEquals(1, store.imagesDirectory.list()!!.size)
    }

    @Test
    fun preferred4kFallsBackTo1080pOnlyWhileFew4kImagesExist() {
        publish(hubble, "heic9901a")
        publish(hubble, "heic9902a", width = 1920, height = 1080)
        val selection = ImageSelection(LicenceMode.ANY_AVAILABLE, QualityPreference.UHD_PREFERRED)

        refresher(selection = selection).refresh()

        assertEquals(2, store.load().entries.size)
        assertEquals(2, selection.eligible(store.load().entries).size)
    }

    @Test
    fun retriesSettingsDependentRejectionsWhenSettingsChange() {
        publish(hubble, "heic9901a", width = 1920, height = 1080)
        publish(hubble, "heic9902a", width = 2000, height = 3000)
        refresher(selection = personal).refresh()
        assertEquals(2, store.load().rejections.size)

        refresher(selection = personal.copy(quality = QualityPreference.FHD_OR_BETTER)).refresh()

        assertEquals(listOf("ESA_HUBBLE:heic9901a"), store.load().entries.map { it.key })
        assertEquals(mapOf("ESA_HUBBLE:heic9902a" to "NOT_LANDSCAPE"), store.load().rejections.mapValues { it.value.reason })
    }

    @Test
    fun revalidatesPersonalUseImagesWithoutDownloadingThemAgain() {
        publish(hubble, "heic9901a")
        refresher(selection = personal).refresh()

        refresher(selection = licensed).refresh()

        val entries = store.load().entries
        assertEquals(1, entries.size)
        assertEquals("CC_BY_4_0", entries.single().rightsBasis)
        assertEquals(1, store.imagesDirectory.list()!!.size)
        assertEquals(listOf(hubble.renditionUrl(Rendition.PUBLICATION, "heic9901a")),
            fetcher.requests.filter { it.contains("cdn.") })
    }

    @Test
    fun rejectsImagesLargerThan8k() {
        publish(hubble, "heic9901a", width = 7681, height = 4000, large = ImageDimensions(7681, 4000))

        refresher(selection = personal).refresh()

        assertEquals(emptyList<CatalogueEntry>(), store.load().entries)
        assertEquals("EXCEEDS_MAX_DIMENSIONS", store.load().rejections.values.single().reason)
    }

    @Test
    fun stops4kQualityFromAdmittingAlmost4k() {
        publish(hubble, "heic9901a", width = 3839, height = 2160)

        refresher(selection = personal).refresh()

        assertEquals("LOW_RESOLUTION", store.load().rejections.values.single().reason)
    }

    @Test
    fun evictsTheOldestImagesToStayWithinTheStorageBudget() {
        val mib = 1024L * 1024
        (1..4).forEach { id -> publish(hubble, "heic990${id}a") }
        (1..4).forEach { id -> fetcher.sizes[hubble.renditionUrl(Rendition.PUBLICATION, "heic990${id}a")] = 40 * mib }
        val limits = CatalogueRefresher.Limits(maxAdmissionsPerSource = 1)

        repeat(4) {
            now += 1000
            refresher(selection = personal, limits = limits, budgetFor = { 100 * mib }).refresh()
        }

        val entries = store.load().entries
        assertEquals(2, entries.size)
        assertTrue(entries.sumOf { it.byteCount } <= 100 * mib)
        assertEquals(entries.map { it.fileName }.toSet(), store.imagesDirectory.list()!!.toSet())
    }

    @Test
    fun downloadsNothingWhenTheBudgetIsBelowTheMinimum() {
        publish(hubble, "heic9901a")

        val result = refresher(selection = personal, budgetFor = { 10L * 1024 * 1024 }).refresh()

        assertTrue(result.lowSpace)
        assertEquals(0, result.admitted)
        assertEquals(emptyList<CatalogueEntry>(), store.load().entries)
        assertTrue(fetcher.requests.none { it.contains("cdn.") })
    }

    @Test
    fun capsRotationWhenTheLibraryIsFull() {
        (1..6).forEach { publish(hubble, "heic990${it}a") }
        val limits = CatalogueRefresher.Limits(maxEntries = 2, maxAdmissionsPerSource = 8, maxAdmissionsWhenFull = 1)
        refresher(selection = personal, limits = limits).refresh()
        assertEquals(2, store.load().entries.size)
        now += 1000

        val result = refresher(selection = personal, limits = limits).refresh()

        assertEquals(1, result.admitted)
        assertEquals(2, store.load().entries.size)
    }

    @Test
    fun openLicenceModeClearsNasaWorksUnlessMarkedCopyrighted() {
        val nasa = StubAdapter(AssetProvider.NASA, "n1" to false, "n2" to true)
        fetcher.images[nasa.url("n1")] = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1)
        dimensions["\u0001"] = ImageDimensions(4000, 2250)
        fetcher.images[nasa.url("n2")] = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1)

        refresher(sites = emptySet(), selection = licensed, extraAdapters = listOf(nasa)).refresh()

        val entry = store.load().entries.single()
        assertEquals("NASA_PUBLIC_DOMAIN", entry.rightsBasis)
        assertEquals("RIGHTS_COPYRIGHT_MARKED", store.load().rejections.getValue("NASA:n2").reason)
    }

    @Test
    fun personalUseAdmitsCopyrightMarkedNasaWorks() {
        val nasa = StubAdapter(AssetProvider.NASA, "n2" to true)
        fetcher.images[nasa.url("n2")] = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1)
        dimensions["\u0001"] = ImageDimensions(4000, 2250)

        refresher(sites = emptySet(), selection = personal, extraAdapters = listOf(nasa)).refresh()

        assertEquals(CatalogueEntry.RIGHTS_NOT_CHECKED, store.load().entries.single().rightsBasis)
    }

    private class StubAdapter(
        override val provider: AssetProvider,
        vararg items: Pair<String, Boolean>
    ) : SourceAdapter {
        override val label = "Stub"
        private val items = items.toList()

        fun url(id: String) = "https://images-assets.nasa.gov/image/$id/$id~orig.jpg"

        override fun discover(fetcher: Fetcher, random: Random) = Discovery(
            items.map { (id, copyright) ->
                Lead(id) {
                    CandidateResult.Ready(
                        Candidate(
                            provider = provider,
                            id = id,
                            title = "Stub $id",
                            pageUrl = "https://images.nasa.gov/details/$id",
                            credit = "NASA",
                            options = listOf(RenditionOption(url(id), 1024, 4000, 2250)),
                            declaredWidth = 4000,
                            declaredHeight = 2250,
                            copyrightMarked = copyright
                        )
                    )
                }
            },
            emptyList(),
            true
        )
    }
    private fun refresher(
        territory: String? = "GB",
        sites: Set<DjangoplicitySite> = DjangoplicitySite.entries.toSet(),
        selection: ImageSelection = licensed,
        limits: CatalogueRefresher.Limits = CatalogueRefresher.Limits(),
        inspector: ImageInspector = ImageInspector { dimensions[String(it.readBytes(), Charsets.ISO_8859_1).substring(3)] },
        budgetFor: (Long) -> Long = { Long.MAX_VALUE },
        extraAdapters: List<SourceAdapter> = emptyList()
    ) = CatalogueRefresher(
        fetcher = fetcher,
        store = store,
        inspector = inspector,
        territory = territory,
        adapters = sites.map { DjangoplicityAdapter(it) { now } } + extraAdapters,
        selection = selection,
        clock = { now },
        random = Random(7),
        limits = limits,
        budgetFor = budgetFor
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
        tooLarge: Boolean = false,
        credit: String? = "ESA/Synthetic &amp; Example Team",
        large: ImageDimensions? = null
    ) {
        val feedUrl = site.discoveryUrls[0]
        val ids = Regex("""/images/([a-z0-9]+)/</link>""").findAll(fetcher.documents[feedUrl]!!)
            .map { it.groupValues[1] }.toMutableSet().apply { add(id) }
        fetcher.documents[feedUrl] = Fixtures.feed(site, *ids.toTypedArray())
        fetcher.documents[site.pageUrl(id)] = Fixtures.assetPage(
            site, id, type = type, width = width, height = height, caption = caption,
            creditNotice = creditNotice, renditions = renditions, credit = credit
        )
        val url = site.renditionUrl(Rendition.PUBLICATION, id)
        val magic = if (notJpeg) "PNG" else "\u00ff\u00d8\u00ff"
        fetcher.images[url] = (magic + id).toByteArray(Charsets.ISO_8859_1)
        if (tooLarge) fetcher.tooLarge += url
        dimensions[id] = actual
        if (large != null) {
            fetcher.images[site.renditionUrl(Rendition.LARGE, id)] = (magic + "$id-large").toByteArray(Charsets.ISO_8859_1)
            dimensions["$id-large"] = large
        }
    }

    private class FakeFetcher : Fetcher {
        val documents = mutableMapOf<String, String>()
        val images = mutableMapOf<String, ByteArray>()
        val tooLarge = mutableSetOf<String>()
        val sizes = mutableMapOf<String, Long>()
        val requests = mutableListOf<String>()
        var offline = false

        override fun fetchText(url: String, maxBytes: Int): String {
            requests += url
            if (offline) throw IOException("offline")
            return documents[url] ?: throw IOException("HTTP 404 from $url")
        }

        override fun fetchPrefix(url: String, maxBytes: Int): ByteArray {
            requests += url
            return images[url]?.copyOf(minOf(maxBytes, images.getValue(url).size)) ?: throw IOException("HTTP 404 from $url")
        }

        override fun download(url: String, destination: File, maxBytes: Long): Download {
            requests += url
            if (offline) throw IOException("offline")
            if (url in tooLarge) throw ResourceTooLargeException("too large")
            val bytes = images[url] ?: throw IOException("HTTP 404 from $url")
            destination.writeBytes(bytes)
            return Download(sha256Of(destination), sizes[url] ?: bytes.size.toLong())
        }
    }
}
