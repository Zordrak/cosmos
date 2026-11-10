package com.cosmos.screensaver.sync

import com.cosmos.screensaver.catalogue.CatalogueEntry
import com.cosmos.screensaver.catalogue.CatalogueStore
import com.cosmos.screensaver.catalogue.CatalogueStoreTest
import com.cosmos.screensaver.catalogue.LibraryMaintenance
import com.cosmos.screensaver.catalogue.UserState
import com.cosmos.screensaver.catalogue.UserStateStore
import com.cosmos.screensaver.rights.AssetProvider
import com.cosmos.screensaver.rights.LicenceValidator
import com.cosmos.screensaver.settings.ImageSelection
import com.cosmos.screensaver.settings.LicenceMode
import com.cosmos.screensaver.settings.QualityPreference
import com.cosmos.screensaver.settings.OrderMode
import com.cosmos.screensaver.settings.SourceWeight
import com.cosmos.screensaver.settings.SelectionSnapshot
import com.cosmos.screensaver.source.DjangoplicityAdapter
import com.cosmos.screensaver.source.Download
import com.cosmos.screensaver.source.DjangoplicitySite
import com.cosmos.screensaver.source.DownloadObserver
import com.cosmos.screensaver.source.Fetcher
import com.cosmos.screensaver.source.Fixtures
import com.cosmos.screensaver.source.Rendition
import com.cosmos.screensaver.source.ResourceTooLargeException
import com.cosmos.screensaver.source.Candidate
import com.cosmos.screensaver.source.CandidateResult
import com.cosmos.screensaver.source.ContentFilter
import com.cosmos.screensaver.source.SourceRejections
import com.cosmos.screensaver.source.Discovery
import com.cosmos.screensaver.source.Lead
import com.cosmos.screensaver.source.RenditionOption
import com.cosmos.screensaver.source.SourceAdapter
import com.cosmos.screensaver.source.sha256Of
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    private lateinit var userState: UserStateStore
    private lateinit var fetcher: FakeFetcher
    private val dimensions = mutableMapOf<String, ImageDimensions>()
    private var now = 1_800_000_000_000L

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("cosmos-refresher").toFile()
        store = CatalogueStore(directory)
        userState = UserStateStore(directory)
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
    fun aPassThatFindsNothingNewSaysTheLibraryHasEverything() {
        publish(hubble, "heic9901a")
        publish(webb, "weic9901a")

        val first = refresher().refresh()
        assertFalse(first.complete)
        val second = refresher().refresh()

        assertEquals(0, second.admitted)
        assertTrue(second.complete)
        assertTrue(store.load().lastSummary.contains("every picture the sources offer"))
    }

    @Test
    fun streamNextReturnsAnImageWithoutTouchingTheLibrary() {
        publish(hubble, "heic9901a")
        val refresher = refresher(selection = personal)
        val queue = StreamQueue()

        val streamed = refresher.streamNext(queue, OrderMode.FAIR, ::normal)!!
        val entry = streamed.entry

        assertEquals("ESA_HUBBLE:heic9901a", entry.key)
        assertEquals("ESA_HUBBLE:heic9901a", streamed.leadKey)
        assertTrue(store.imageFile(entry).isFile)
        assertTrue(store.load().entries.isEmpty())
        assertEquals(0L, store.load().lastAttemptAtEpochMillis)
    }

    @Test
    fun refreshReportsProgressThatMatchesTheResult() {
        publish(hubble, "heic9901a")
        publish(webb, "weic9901a")
        val tracker = ProgressTracker(ProgressKind.SAVING, clock = { 0L }, wallClock = { now })
        fetcher.observer = tracker.observer
        fetcher.snapshotSource = tracker::snapshot

        val result = refresher(progress = tracker).refresh()

        val last = tracker.snapshot()
        assertEquals(ProgressPhase.IDLE, last.phase)
        assertEquals(1L, last.runId)
        assertEquals(result.admitted, last.admitted)
        assertEquals(result.rejected, last.skipped)
        assertEquals(now, last.startedAtEpochMillis)
        assertTrue(last.endedAtEpochMillis > 0L)
        assertEquals(2, fetcher.phasesDuringDownload.size)
        fetcher.phasesDuringDownload.forEach {
            assertEquals(ProgressPhase.DOWNLOADING, it.phase)
            assertTrue(it.title!!.isNotBlank())
            assertTrue(it.source!!.isNotBlank())
            assertEquals(0L, it.doneBytes)
            assertTrue(it.totalBytes > 0)
        }
    }

    @Test
    fun aFailedRefreshEndsItsRunAndKeepsTheError() {
        val tracker = ProgressTracker(ProgressKind.SAVING, clock = { 0L })
        val broken = object : SourceAdapter {
            override val provider = AssetProvider.NASA
            override val label = "Broken"
            override fun discover(fetcher: Fetcher, random: Random): Discovery = throw IllegalStateException("boom")
        }

        val failure = runCatching { refresher(sites = emptySet(), extraAdapters = listOf(broken), progress = tracker).refresh() }

        assertTrue(failure.isFailure)
        val last = tracker.snapshot()
        assertEquals(ProgressPhase.IDLE, last.phase)
        assertEquals(ProgressErrorKind.UNEXPECTED, last.error!!.kind)
        assertEquals("boom", last.error!!.message)
    }

    @Test
    fun discoveryProblemsAreRecordedAgainstTheirSource() {
        val tracker = ProgressTracker(ProgressKind.SAVING, clock = { 0L })
        fetcher.offline = true

        refresher(sites = setOf(hubble), progress = tracker).refresh()

        val error = tracker.snapshot().error!!
        assertEquals(ProgressErrorKind.DISCOVERY, error.kind)
        assertEquals(hubble.label, error.source)
    }

    @Test
    fun streamingReportsToItsOwnSlotWithoutCountingSavedPictures() {
        publish(hubble, "heic9901a")
        val saving = ProgressTracker(ProgressKind.SAVING, clock = { 0L })
        val streaming = ProgressTracker(ProgressKind.STREAMING, clock = { 0L })
        fetcher.observer = streaming.observer
        fetcher.snapshotSource = streaming::snapshot

        refresher(selection = personal, progress = streaming).streamNext(StreamQueue(), OrderMode.FAIR, ::normal)!!

        assertEquals(ProgressPhase.IDLE, streaming.snapshot().phase)
        assertEquals(ProgressKind.STREAMING, streaming.snapshot().kind)
        assertEquals(0, streaming.snapshot().admitted)
        assertEquals(1, fetcher.phasesDuringDownload.size)
        assertEquals(ProgressPhase.DOWNLOADING, fetcher.phasesDuringDownload.single().phase)
        assertEquals(0L, saving.snapshot().runId)
        assertTrue(store.load().entries.isEmpty())
    }

    @Test
    fun streamNextCyclesThroughImagesAndStartsAgainWhenAllWereShown() {
        publish(hubble, "heic9901a")
        publish(hubble, "heic9902a")
        val refresher = refresher(selection = personal)
        val queue = StreamQueue()

        val first = refresher.streamNext(queue, OrderMode.FAIR, ::normal)!!.also(::show)
        val second = refresher.streamNext(queue, OrderMode.FAIR, ::normal)!!.also(::show)
        val third = refresher.streamNext(queue, OrderMode.FAIR, ::normal)!!

        assertEquals(setOf("ESA_HUBBLE:heic9901a", "ESA_HUBBLE:heic9902a"), setOf(first.entry.key, second.entry.key))
        assertTrue(third.entry.key in setOf(first.entry.key, second.entry.key))
    }

    @Test
    fun streamNextDoesNotAdvanceTheOrderUntilAFrameIsAccepted() {
        publish(hubble, "heic9901a")
        publish(hubble, "heic9902a")
        publish(hubble, "heic9903a")
        val refresher = refresher(selection = personal)
        val queue = StreamQueue()

        refresher.streamNext(queue, OrderMode.SEQUENTIAL, ::normal)!!

        assertNull(userState.load().streamOrder.lastAcceptedLead)
        assertTrue(userState.load().streamOrder.leadHashes.isEmpty())
    }

    @Test
    fun streamNextInSequentialModeFollowsProviderAndLeadOrderAcrossRestarts() {
        listOf("heic9903a", "heic9901a", "heic9902a").forEach { publish(hubble, it) }
        val refresher = refresher(selection = personal)

        val keys = (1..3).map {
            // A new queue each time stands in for a restart: only the persisted cursor carries over.
            refresher.streamNext(StreamQueue(), OrderMode.SEQUENTIAL, ::normal)!!.also(::show).leadKey
        }

        assertEquals(keys.sorted(), keys)
        assertEquals(3, keys.toSet().size)
    }

    @Test
    fun streamNextSkipsLeadsThatFailAndCoolsThemDown() {
        publish(hubble, "heic9901a", type = "Collage")
        publish(hubble, "heic9902a")
        val refresher = refresher(selection = personal)

        val streamed = refresher.streamNext(StreamQueue(), OrderMode.SEQUENTIAL, ::normal)!!

        assertEquals("ESA_HUBBLE:heic9902a", streamed.leadKey)
        assertTrue(userState.load().streamOrder.attempts.any { it.leadKey == "ESA_HUBBLE:heic9901a" && it.retryAfterEpochMillis > now })
    }

    @Test
    fun streamNextStopsWhenEveryLeadFailsOrIsBlocked() {
        publish(hubble, "heic9901a", type = "Collage")
        publish(hubble, "heic9902a")
        userState.block(listOf(CatalogueStoreTest.entry("heic9902a")), now)

        assertNull(refresher(selection = personal).streamNext(StreamQueue(), OrderMode.FAIR, ::normal))
    }

    @Test
    fun streamNextDiscardsAPictureIdenticalToTheOneJustShown() {
        publish(hubble, "heic9901a")
        val refresher = refresher(selection = personal)
        val first = refresher.streamNext(StreamQueue(), OrderMode.SEQUENTIAL, ::normal)!!
        userState.update { it.copy(lastShownHash = first.entry.sha256) }

        val again = refresher.streamNext(StreamQueue(), OrderMode.RANDOM, ::normal)

        assertNull(again)
        assertEquals(
            listOf("ESA_HUBBLE:heic9901a" to first.entry.sha256),
            userState.load().streamOrder.leadHashes
        )
    }

    @Test
    fun streamNextHonoursCancellation() {
        publish(hubble, "heic9901a")

        assertNull(refresher(selection = personal).streamNext(StreamQueue(), OrderMode.FAIR, ::normal) { true })
    }

    @Test
    fun streamNextReturnsNullWhenNothingIsAvailable() {
        fetcher.offline = true

        assertNull(refresher(selection = personal).streamNext(StreamQueue(), OrderMode.FAIR, ::normal))
    }

    private fun normal(provider: AssetProvider) = SourceWeight.NORMAL

    private fun show(streamed: Streamed) {
        userState.commitDisplay(streamed.entry.sha256, now, emptySet()) { state ->
            state.copy(
                streamOrder = StreamQueue.withAccepted(
                    state.streamOrder, streamed.next, streamed.leadKey, streamed.entry.sha256
                )
            )
        }
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
    fun fillsUpToTheBudgetInOnePassThenReplacesOnlyWhatHasBeenSeen() {
        val mib = 1024L * 1024
        listOf("heic9901a", "heic9902a", "heic9903a").forEach { id ->
            publish(hubble, id)
            fetcher.sizes[hubble.renditionUrl(Rendition.PUBLICATION, id)] = 40 * mib
        }

        refresher(selection = personal, budgetFor = { 100 * mib }).refresh()
        assertEquals(2, store.load().entries.size)
        now += 1000
        assertEquals(0, refresher(selection = personal, budgetFor = { 100 * mib }).refresh().admitted)

        watchAll()
        val result = refresher(selection = personal, budgetFor = { 100 * mib }).refresh()

        val entries = store.load().entries
        assertEquals(1, result.admitted)
        assertEquals(2, entries.size)
        assertEquals(2, store.imagesDirectory.list()!!.size)
        assertEquals(setOf("heic9901a", "heic9902a", "heic9903a"), savedKeys() + store.load().rejections.keys.map { it.substringAfter(':') })
    }

    @Test
    fun savesEverythingAvailableInOnePass() {
        (1..9).forEach { publish(hubble, "heic990${it}a") }

        val result = refresher().refresh()

        assertEquals(9, result.admitted)
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
        assertEquals(2, SelectionSnapshot.build(store.load().entries, selection, setOf(AssetProvider.ESA_HUBBLE), UserState()).eligible.size)
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

        repeat(4) {
            watchAll()
            refresher(selection = personal, budgetFor = { 100 * mib }).refresh()
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
    fun aFullLibraryNeverReplacesAPictureNotYetSeen() {
        val mib = 1024L * 1024
        (1..6).forEach { id ->
            publish(hubble, "heic990${id}a")
            fetcher.sizes[hubble.renditionUrl(Rendition.PUBLICATION, "heic990${id}a")] = 40 * mib
        }
        refresher(selection = personal, budgetFor = { 90 * mib }).refresh()
        val first = store.load().entries
        assertEquals(2, first.size)
        now += 1000
        val seen = first.first()
        userState.commitDisplay(seen.sha256, now, first.map { it.sha256 }.toSet())
        now += 1000

        val result = refresher(selection = personal, budgetFor = { 90 * mib }).refresh()

        assertEquals(1, result.admitted)
        assertTrue(result.space.userLimitFull)
        assertEquals(2, store.load().entries.size)
        assertFalse(seen.providerId in savedKeys())
        assertTrue(first.last().providerId in savedKeys())
    }

    private val mib = 1024L * 1024

    /** Every saved picture has been shown once, after it was saved. */
    private fun watchAll() {
        now += 1000
        val hashes = store.load().entries.map { it.sha256 }.toSet()
        hashes.forEach { userState.commitDisplay(it, now, hashes) }
        now += 1000
    }

    private fun savedKeys() = store.load().entries.map { it.providerId }.toSet()

    private fun evictByKey(providerId: String) {
        val entry = store.load().entries.single { it.providerId == providerId }
        assertTrue(LibraryMaintenance.evict(store, userState, entry.sha256, now) > 0)
    }

    @Test
    fun anEvictedPictureIsRememberedAndReturnsWhenThereIsSpareRoom() {
        publish(hubble, "heic9901a")
        publish(hubble, "heic9902a")
        refresher(selection = personal).refresh()
        assertEquals(setOf("heic9901a", "heic9902a"), savedKeys())

        evictByKey("heic9901a")
        assertEquals(LibraryMaintenance.EVICTED, store.load().rejections.getValue("ESA_HUBBLE:heic9901a").reason)
        assertEquals(setOf("heic9902a"), savedKeys())

        now += 1000
        val result = refresher(selection = personal).refresh()

        assertEquals(1, result.admitted)
        assertEquals(setOf("heic9901a", "heic9902a"), savedKeys())
        assertTrue(store.load().rejections.isEmpty())
    }

    @Test
    fun retiredPicturesReturnBeforeNewOnesWhenThereIsRoomForOnlyOne() {
        listOf("heic9901a", "heic9902a").forEach { publish(hubble, it); sizeOf(hubble, it, 40 * mib) }
        refresher(selection = personal, budgetFor = { 100 * mib }).refresh()
        evictByKey("heic9901a")
        publish(hubble, "heic9903a")
        sizeOf(hubble, "heic9903a", 40 * mib)
        now += 1000

        refresher(selection = personal, budgetFor = { 100 * mib }).refresh()

        assertEquals(setOf("heic9901a", "heic9902a"), savedKeys())
    }

    @Test
    fun aFullLibraryPrefersNewPicturesToRetiredOnes() {
        listOf("heic9901a", "heic9902a", "heic9903a").forEach { publish(hubble, it); sizeOf(hubble, it, 40 * mib) }
        refresher(selection = personal, budgetFor = { 90 * mib }).refresh()
        watchAll()
        refresher(selection = personal, budgetFor = { 90 * mib }).refresh()
        assertEquals(2, store.load().entries.size)
        val retiredId = (setOf("heic9901a", "heic9902a", "heic9903a") - savedKeys()).single()
        publish(hubble, "heic9904a")
        sizeOf(hubble, "heic9904a", 40 * mib)
        watchAll()

        refresher(selection = personal, budgetFor = { 90 * mib }).refresh()

        assertTrue("heic9904a" in savedKeys())
        assertFalse(retiredId in savedKeys())
    }

    @Test
    fun noPictureIsEvictedAndReturnedWithinOnePass() {
        (1..6).forEach { publish(hubble, "heic990${it}a"); sizeOf(hubble, "heic990${it}a", 40 * mib) }
        refresher(selection = personal, budgetFor = { 90 * mib }).refresh()
        val first = savedKeys()
        watchAll()
        fetcher.requests.clear()

        refresher(selection = personal, budgetFor = { 90 * mib }).refresh()

        assertEquals(2, savedKeys().size)
        assertTrue(savedKeys().none { it in first })
        assertTrue(first.none { id -> fetcher.requests.any { it.contains(id) } })
    }

    private fun sizeOf(site: DjangoplicitySite, id: String, bytes: Long) {
        fetcher.sizes[site.renditionUrl(Rendition.PUBLICATION, id)] = bytes
    }

    @Test
    fun thereIsNoCapOnTheNumberOfSavedPictures() {
        val ids = (1..160).map { "heic9%03da".format(it) }
        ids.forEach { publish(hubble, it) }

        refresher().refresh()

        assertEquals(160, store.load().entries.size)
    }

    @Test
    fun onlyFavouritesLeftIsReportedAsFavouritesNotAsDevicePressure() {
        (1..3).forEach { id ->
            publish(hubble, "heic990${id}a")
            sizeOf(hubble, "heic990${id}a", 40 * mib)
        }
        refresher(selection = personal, budgetFor = { 100 * mib }).refresh()
        store.load().entries.forEach { userState.setFavourite(it.sha256, true) }
        watchAll()

        val result = refresher(selection = personal, budgetFor = { 100 * mib }).refresh()

        assertTrue(result.space.favouritesPreventSaving)
        assertFalse(result.space.devicePressure)
        assertFalse(result.lowSpace)
        assertEquals(2, store.load().entries.size)
    }

    @Test
    fun aPictureBiggerThanTheChosenSizeIsAUserLimitNotDevicePressure() {
        publish(hubble, "heic9901a")
        sizeOf(hubble, "heic9901a", 70 * mib)

        val result = refresher(selection = personal, budgetFor = { 60 * mib }).refresh()

        assertTrue(result.space.userLimitFull)
        assertFalse(result.space.devicePressure)
        assertEquals(0, result.admitted)
        assertTrue(store.load().entries.isEmpty())
        assertEquals(0, store.imagesDirectory.list()!!.size)
    }

    @Test
    fun anUnknownMeasurementDownloadsAndDeletesNothing() {
        publish(hubble, "heic9901a")
        publish(hubble, "heic9902a")
        refresher().refresh()
        val before = store.load().entries
        fetcher.requests.clear()
        now += 1000

        val result = refresher(cacheBudget = { CacheBudget.UNKNOWN }).refresh()

        assertTrue(result.space.measurementUnavailable)
        assertEquals(0, result.admitted)
        assertEquals(before, store.load().entries)
        assertTrue(fetcher.requests.none { it.contains("cdn.") })
    }

    @Test
    fun theDownloadSizeIsCappedByThePhysicalHeadroom() {
        publish(hubble, "heic9901a")
        val limits = CatalogueRefresher.Limits(headroomFloorBytes = 90 * mib)

        val result = refresher(cacheBudget = { CacheBudget.fixed(Long.MAX_VALUE, 95 * mib) }, limits = limits).refresh()

        assertEquals(1, result.admitted)
        assertEquals(listOf(5 * mib), fetcher.maxBytesSeen)
    }

    @Test
    fun noDownloadStartsWhenFreeSpaceIsAtTheHeadroomFloor() {
        publish(hubble, "heic9901a")

        val result = refresher(cacheBudget = { CacheBudget.fixed(Long.MAX_VALUE, 90 * mib) }).refresh()

        assertEquals(0, result.admitted)
        assertTrue(result.space.devicePressure)
        assertTrue(fetcher.maxBytesSeen.isEmpty())
    }

    @Test
    fun sourcesTakeTurnsSoEachGetsAFairShare() {
        (1..4).forEach { id ->
            publish(hubble, "heic990${id}a")
            sizeOf(hubble, "heic990${id}a", 40 * mib)
            publish(webb, "weic990${id}a")
            sizeOf(webb, "weic990${id}a", 40 * mib)
        }

        refresher(selection = personal, budgetFor = { 170 * mib }).refresh()

        val byProvider = store.load().entries.groupingBy { it.provider }.eachCount()
        assertEquals(mapOf(AssetProvider.ESA_HUBBLE to 2, AssetProvider.ESA_WEBB to 2), byProvider)
    }

    @Test
    fun aLongPassKeepsItsRejectionsAsItGoes() {
        var resolved = 0
        var savedPartWay = -1
        val skipper = object : SourceAdapter {
            override val provider = AssetProvider.NASA
            override val label = "Skipper"
            override fun discover(fetcher: Fetcher, random: Random) = Discovery(
                (1..25).map { n ->
                    Lead("s$n") {
                        if (++resolved == 22) savedPartWay = store.load().rejections.size
                        CandidateResult.Skip("UNSUITABLE_TYPE")
                    }
                },
                emptyList(),
                reached = true
            )
        }

        refresher(sites = emptySet(), extraAdapters = listOf(skipper)).refresh()

        assertEquals(20, savedPartWay)
        assertEquals(25, store.load().rejections.size)
    }

    @Test
    fun theOnlineIndexRecordsQualifyingPicturesWithoutDownloadingThem() {
        publish(hubble, "heic9901a")
        publish(hubble, "heic9902a", width = 3000, height = 4000)

        val result = refresher(selection = personal, indexOnly = true).refresh()
        val entry = store.load().entries.singleOrNull()
            ?: throw AssertionError("no entry: ${result.summary} rejections=${store.load().rejections}")
        // Nothing was downloaded: only pages were read.
        assertTrue(fetcher.requests.none { it.contains("cdn.") })
        assertEquals("heic9901a", entry.providerId)
        assertEquals("", entry.fileName)
        assertEquals("", entry.sha256)
        assertTrue(entry.assetUrl.isNotBlank())
        assertTrue(entry.widthPx >= 3840)

        now += 1000
        refresher(selection = personal, indexOnly = true).refresh()

        // Kept, though it has no file, and not looked up again.
        assertEquals(listOf("heic9901a"), store.load().entries.map { it.providerId })
    }

    @Test
    fun aPassCutShortByTimeoutsWithRoomLeftAsksToBeRetriedSoon() {
        publish(hubble, "heic9901a")
        fetcher.offline = true

        refresher(selection = personal).refresh()
        assertTrue(store.load().moreToFetch)

        fetcher.offline = false
        now += 1000
        refresher(selection = personal).refresh()

        assertFalse(store.load().moreToFetch)
        assertEquals(setOf("heic9901a"), savedKeys())
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

    @Test
    fun aBlockedKeyIsNeverResolvedOrDownloaded() {
        publish(hubble, "heic9901a")
        userState.block(listOf(CatalogueStoreTest.entry("heic9901a")), now)

        val result = refresher().refresh()

        assertEquals(0, result.admitted)
        assertTrue(fetcher.requests.none { it.contains("heic9901a") })
        assertTrue(store.load().entries.isEmpty())
    }

    @Test
    fun aBlockedPictureDownloadedUnderANewKeyIsDiscardedAndItsKeyRecorded() {
        publish(hubble, "heic9901a")
        refresher().refresh()
        val saved = store.load().entries.single()
        store.remove(saved.key)
        userState.block(listOf(saved.copy(providerId = "ghost")), now)

        val result = refresher().refresh()

        assertEquals(0, result.admitted)
        assertTrue(store.load().entries.isEmpty())
        assertEquals(emptyList<String>(), store.imagesDirectory.list()!!.toList())
        assertEquals(LibraryMaintenance.BLOCKED, store.load().rejections.getValue("ESA_HUBBLE:heic9901a").reason)
        assertTrue("ESA_HUBBLE:heic9901a" in userState.load().blocked.single().keys)
    }

    @Test
    fun unblockingClearsTheCachedDecisionSoThePictureCanReturn() {
        publish(hubble, "heic9901a")
        refresher().refresh()
        val saved = store.load().entries.single()
        store.remove(saved.key)
        userState.block(listOf(saved), now)
        // The key is excluded up front, so record the decision a download-time block would have made.
        store.update { it.copy(rejections = mapOf(saved.key to com.cosmos.screensaver.catalogue.RejectionRecord(LibraryMaintenance.BLOCKED, now))) }

        LibraryMaintenance.unblock(store, userState, userState.load().blocked.single().id)
        val result = refresher().refresh()

        assertEquals(1, result.admitted)
        assertTrue(store.load().rejections.isEmpty())
    }

    @Test
    fun aPictureHeldForUndoIsNeverResolvedAndNothingIsRemembered() {
        publish(hubble, "heic9901a")

        val result = refresher(isStashed = { key, _ -> key == "ESA_HUBBLE:heic9901a" }).refresh()

        assertEquals(0, result.admitted)
        assertTrue(fetcher.requests.none { it.contains("heic9901a") })
        assertTrue(store.load().rejections.isEmpty())
    }

    @Test
    fun aPictureWhoseHashIsHeldForUndoIsDiscardedWithoutARememberedRejection() {
        publish(hubble, "heic9901a")
        refresher().refresh()
        val saved = store.load().entries.single()
        store.remove(saved.key)

        val result = refresher(isStashed = { _, sha -> sha == saved.sha256 }).refresh()

        assertEquals(0, result.admitted)
        assertTrue(store.load().entries.isEmpty())
        assertEquals(emptyList<String>(), store.imagesDirectory.list()!!.toList())
        assertTrue(store.load().rejections.isEmpty())
    }

    @Test
    fun savingWaitsWhileARemovalCanStillBeUndone() {
        publish(hubble, "heic9901a")

        val result = refresher(holdAdmissions = { true }).refresh()

        assertEquals(0, result.admitted)
        assertTrue(fetcher.requests.none { it.contains("heic9901a") })
    }

    @Test
    fun aPictureBlockedWhileItDownloadsStaysBlocked() {
        publish(hubble, "heic9902a")
        publish(hubble, "heic9901a")
        val blockingInspector = ImageInspector { file ->
            val id = String(file.readBytes(), Charsets.ISO_8859_1).substring(3)
            if (id == "heic9901a") userState.block(listOf(CatalogueStoreTest.entry("heic9901a")), now)
            dimensions[id]
        }

        refresher(inspector = blockingInspector).refresh()

        assertEquals(listOf("ESA_HUBBLE:heic9902a"), store.load().entries.map { it.key })
        assertEquals(1, store.imagesDirectory.list()!!.size)
    }

    @Test
    fun aFavouriteIsNeverEvictedToMakeRoom() {
        val mib = 1024L * 1024
        (1..4).forEach { id -> publish(hubble, "heic990${id}a") }
        (1..4).forEach { id -> fetcher.sizes[hubble.renditionUrl(Rendition.PUBLICATION, "heic990${id}a")] = 40 * mib }
        refresher(selection = personal, budgetFor = { 100 * mib }).refresh()
        val favourite = store.load().entries.first()
        userState.setFavourite(favourite.sha256, true)

        repeat(4) {
            watchAll()
            refresher(selection = personal, budgetFor = { 100 * mib }).refresh()
        }

        assertTrue(store.load().entries.any { it.key == favourite.key })
        assertTrue(store.imageFile(favourite).isFile)
    }

    @Test
    fun aSecondSourceServingTheSameBytesSharesOneFileAndShowsOnce() {
        publish(hubble, "heic9901a")
        publish(webb, "weic9901a")
        fetcher.images[webb.renditionUrl(Rendition.PUBLICATION, "weic9901a")] =
            fetcher.images.getValue(hubble.renditionUrl(Rendition.PUBLICATION, "heic9901a"))

        val result = refresher().refresh()

        assertEquals(2, result.admitted)
        val entries = store.load().entries
        assertEquals(1, store.imagesDirectory.list()!!.size)
        assertEquals(1, entries.map { it.fileName }.toSet().size)
        fun shown(providers: Set<AssetProvider>) =
            SelectionSnapshot.build(entries, licensed, providers, UserState()).eligible.size
        assertEquals(1, shown(setOf(AssetProvider.ESA_HUBBLE, AssetProvider.ESA_WEBB)))
        assertEquals(1, shown(setOf(AssetProvider.ESA_WEBB)))
        assertEquals(1, shown(setOf(AssetProvider.ESA_HUBBLE)))
        assertEquals(0, shown(emptySet()))
        assertEquals(1, result.total)
    }

    @Test
    fun removingOneAliasKeepsTheSharedFileAndAFavouriteIsNeverLost() {
        publish(hubble, "heic9901a")
        publish(webb, "weic9901a")
        fetcher.images[webb.renditionUrl(Rendition.PUBLICATION, "weic9901a")] =
            fetcher.images.getValue(hubble.renditionUrl(Rendition.PUBLICATION, "heic9901a"))
        refresher().refresh()
        val entries = store.load().entries
        userState.setFavourite(entries.first().sha256, true)

        assertTrue(LibraryMaintenance.removeKey(store, userState, "ESA_HUBBLE:heic9901a"))
        assertTrue(store.imageFile(entries.first()).isFile)
        assertFalse(LibraryMaintenance.removeKey(store, userState, "ESA_WEBB:weic9901a"))
        assertEquals(1, store.load().entries.size)
        assertTrue(store.imageFile(entries.first()).isFile)
    }

    @Test
    fun anEntryAddedConcurrentlySurvivesTheFinalMergeAndCleanup() {
        publish(hubble, "heic9901a")
        val concurrent = CatalogueStoreTest.entry("zzzz9999")
        val keepFile = File(store.imagesDirectory, concurrent.fileName)
        val orphan = File(store.imagesDirectory, "${"a".repeat(64)}.jpg")
        val notes = File(store.imagesDirectory, "notes.txt")
        val addingInspector = ImageInspector { file ->
            store.imagesDirectory.mkdirs()
            keepFile.writeBytes(byteArrayOf(1, 2, 3))
            orphan.writeBytes(byteArrayOf(4))
            notes.writeText("keep")
            store.update { it.copy(entries = it.entries + concurrent) }
            dimensions[String(file.readBytes(), Charsets.ISO_8859_1).substring(3)]
        }

        refresher(inspector = addingInspector).refresh()

        assertEquals(setOf("ESA_HUBBLE:heic9901a", concurrent.key), store.load().entries.map { it.key }.toSet())
        assertTrue(keepFile.isFile)
        assertFalse(orphan.exists())
        assertTrue(notes.isFile)
    }

    @Test
    fun anUnreadableCatalogueStopsTheRefreshWithoutDeletingAnything() {
        publish(hubble, "heic9901a")
        refresher().refresh()
        val files = store.imagesDirectory.list()!!.toSet()
        File(directory, "catalogue.json").writeText("not json")
        File(directory, "catalogue.json.bak").delete()
        File(directory, "catalogue.json.tmp").delete()

        val failed = try {
            refresher().refresh()
            false
        } catch (error: IOException) {
            true
        }

        assertTrue(failed)
        assertEquals(files, store.imagesDirectory.list()!!.toSet())
    }

    @Test
    fun aFavouriteIsKeptWhenASourceNowServesDifferentBytes() {
        publish(hubble, "heic9901a")
        refresher(selection = personal).refresh()
        val fakeHash = "ab".repeat(32)
        store.update { current ->
            current.copy(entries = current.entries.map { it.copy(sha256 = fakeHash, assetUrl = "https://old.example/x.jpg") })
        }
        userState.setFavourite(fakeHash, true)

        val result = refresher(selection = licensed).refresh()

        assertEquals(0, result.admitted)
        assertEquals(fakeHash, store.load().entries.single().sha256)
        assertEquals(CatalogueRefresher.FAVOURITE_KEPT, store.load().rejections.values.single().reason)
    }

    @Test
    fun skipsAndRemembersAChartWhoseCaptionGivesItAway() {
        publish(hubble, "heic9901a", caption = "Echelle spectrum with wavelength increasing from left to right.")

        val result = refresher().refresh()

        assertEquals(0, result.admitted)
        assertEquals("NOT_A_PHOTOGRAPH", store.load().rejections.values.single().reason)
        assertTrue(fetcher.requests.none { it.contains("cdn.") })
    }

    @Test
    fun rechecksSavedPicturesAgainstTheCurrentContentRules() {
        val nasa = StubAdapter(AssetProvider.NASA, "n1" to false, "n2" to false, "n3" to false)
        nasa.recheckReasons["n1"] = SourceRejections.NOT_A_PHOTOGRAPH
        nasa.recheckFails += "n3"
        listOf("n1", "n2", "n3").forEach {
            fetcher.images[nasa.url(it)] = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), it.last().code.toByte())
            dimensions[it.last().code.toChar().toString()] = ImageDimensions(4000, 2250)
        }
        refresher(sites = emptySet(), selection = personal, extraAdapters = listOf(nasa)).refresh()
        assertEquals(3, store.load().entries.size)
        store.update { current -> current.copy(entries = current.entries.map { it.copy(contentPolicy = "") }) }

        refresher(sites = emptySet(), selection = personal, extraAdapters = listOf(nasa)).refresh()

        val remaining = store.load().entries.associateBy { it.providerId }
        assertEquals(setOf("n2", "n3"), remaining.keys)
        assertEquals(ContentFilter.POLICY_VERSION, remaining.getValue("n2").contentPolicy)
        assertEquals("", remaining.getValue("n3").contentPolicy)
        assertEquals(SourceRejections.NOT_A_PHOTOGRAPH, store.load().rejections.getValue("NASA:n1").reason)
    }

    @Test
    fun aSavedFavouriteThatNowFailsTheContentRulesIsHiddenNotDeleted() {
        val nasa = StubAdapter(AssetProvider.NASA, "n1" to false)
        nasa.recheckReasons["n1"] = SourceRejections.NOT_A_PHOTOGRAPH
        fetcher.images[nasa.url("n1")] = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1)
        dimensions["\u0001"] = ImageDimensions(4000, 2250)
        refresher(sites = emptySet(), selection = personal, extraAdapters = listOf(nasa)).refresh()
        val saved = store.load().entries.single()
        userState.setFavourite(saved.sha256, true)
        store.update { current -> current.copy(entries = current.entries.map { it.copy(contentPolicy = "") }) }

        refresher(sites = emptySet(), selection = personal, extraAdapters = listOf(nasa)).refresh()

        val hidden = store.load().entries.single()
        assertEquals(SourceRejections.NOT_A_PHOTOGRAPH, hidden.hiddenReason)
        assertTrue(store.imageFile(hidden).isFile)
        assertEquals(0, SelectionSnapshot.build(listOf(hidden), personal, setOf(AssetProvider.NASA), userState.load()).eligible.size)
    }

    private class StubAdapter(
        override val provider: AssetProvider,
        vararg items: Pair<String, Boolean>
    ) : SourceAdapter {
        override val label = "Stub"
        private val items = items.toList()
        val recheckReasons = mutableMapOf<String, String>()
        val recheckFails = mutableSetOf<String>()

        fun url(id: String) = "https://images-assets.nasa.gov/image/$id/$id~orig.jpg"

        override fun recheck(fetcher: Fetcher, providerId: String): String? {
            if (providerId in recheckFails) throw IOException("offline")
            return recheckReasons[providerId]
        }

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
        cacheBudget: ((Long) -> CacheBudget)? = null,
        startIndex: Int = 0,
        extraAdapters: List<SourceAdapter> = emptyList(),
        isStashed: (String, String) -> Boolean = { _, _ -> false },
        holdAdmissions: () -> Boolean = { false },
        progress: ProgressTracker? = null,
        indexOnly: Boolean = false
    ) = CatalogueRefresher(
        fetcher = fetcher,
        store = store,
        userState = userState,
        inspector = inspector,
        territory = territory,
        adapters = sites.map { DjangoplicityAdapter(it) { now } } + extraAdapters,
        selection = selection,
        clock = { now },
        random = Random(7),
        limits = limits,
        budgetFor = cacheBudget ?: { used -> CacheBudget.fixed(budgetFor(used)) },
        startIndex = startIndex,
        isStashed = isStashed,
        holdAdmissions = holdAdmissions,
        progress = progress,
        indexOnly = indexOnly
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
        val maxBytesSeen = mutableListOf<Long>()
        var offline = false
        var observer: DownloadObserver? = null
        val phasesDuringDownload = mutableListOf<ProgressSnapshot>()
        var snapshotSource: (() -> ProgressSnapshot)? = null

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
            maxBytesSeen += maxBytes
            if (offline) throw IOException("offline")
            if (url in tooLarge) throw ResourceTooLargeException("too large")
            val bytes = images[url] ?: throw IOException("HTTP 404 from $url")
            destination.writeBytes(bytes)
            val size = sizes[url] ?: bytes.size.toLong()
            observer?.onProgress(0L, size, false)
            snapshotSource?.let { phasesDuringDownload += it() }
            observer?.onProgress(size, size, true)
            return Download(sha256Of(destination), size)
        }
    }
}
