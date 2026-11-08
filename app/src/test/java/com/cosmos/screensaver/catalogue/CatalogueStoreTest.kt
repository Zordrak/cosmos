package com.cosmos.screensaver.catalogue

import com.cosmos.screensaver.rights.AssetProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class CatalogueStoreTest {
    @Test
    fun roundTripsTheCatalogue() {
        val catalogue = Catalogue(
            entries = listOf(entry("heic9901a"), entry("heic9902a")),
            rejections = mapOf("ESA_HUBBLE:heic9903a" to RejectionRecord("NOT_AN_OBSERVATION", 5L)),
            lastAttemptAtEpochMillis = 10L,
            lastSuccessAtEpochMillis = 9L,
            lastSummary = "2 images available."
        )

        assertEquals(catalogue, CatalogueJson.decode(CatalogueJson.encode(catalogue)))
    }

    @Test
    fun dropsEntriesWithUnsafeFileNamesOrMissingProvenance() {
        val catalogue = Catalogue(
            entries = listOf(
                entry("heic9901a"),
                entry("heic9902a").copy(fileName = "../catalogue.json"),
                entry("heic9903a").copy(credit = " "),
                entry("heic9904a").copy(evidence = emptyList())
            )
        )

        assertEquals(listOf("heic9901a"), CatalogueJson.decode(CatalogueJson.encode(catalogue)).entries.map { it.providerId })
    }

    @Test
    fun ignoresUnknownFormatVersions() {
        assertEquals(Catalogue(), CatalogueJson.decode("""{"formatVersion": 99, "entries": []}"""))
    }

    @Test
    fun persistsRemovesAndClears() {
        val directory = Files.createTempDirectory("cosmos-store").toFile()
        try {
            val store = CatalogueStore(directory)
            val entries = listOf(entry("heic9901a"), entry("heic9902a"))
            store.imagesDirectory.mkdirs()
            entries.forEach { store.imageFile(it).writeText("x") }
            store.save(Catalogue(entries = entries))

            store.remove("ESA_HUBBLE:heic9901a")

            assertEquals(listOf("heic9902a"), store.load().entries.map { it.providerId })
            assertEquals(listOf(entries[1].fileName), store.imagesDirectory.list()!!.toList())

            store.clear()
            assertEquals(Catalogue(), store.load())
            assertEquals(0, store.imagesDirectory.list()!!.size)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun startsEmptyWhenTheFileIsCorrupt() {
        val directory = Files.createTempDirectory("cosmos-store").toFile()
        try {
            directory.resolve("catalogue.json").writeText("{not json")
            assertEquals(Catalogue(), CatalogueStore(directory).load())
        } finally {
            directory.deleteRecursively()
        }
    }

    companion object {
        internal fun entry(id: String): CatalogueEntry {
            val sha = id.hashCode().toUInt().toString(16).padStart(64, '0')
            return CatalogueEntry(
                provider = AssetProvider.ESA_HUBBLE,
                providerId = id,
                title = "Synthetic $id",
                sourceLabel = "ESA/Hubble",
                sourcePageUrl = "https://esahubble.org/images/$id/",
                assetUrl = "https://cdn.esahubble.org/archives/images/publicationjpg/$id.jpg",
                credit = "ESA/Synthetic",
                rightsBasis = "CC_BY_4_0",
                licenceName = "CC BY 4.0",
                licenceUrl = "https://creativecommons.org/licenses/by/4.0/",
                territory = "GB",
                evidence = listOf(EvidenceRecord("https://esahubble.org/copyright/", "statement", "PROVIDER_OR_COLLECTION", 1L)),
                validatedAtEpochMillis = 2L,
                policyVersion = "2",
                admittedAtEpochMillis = 3L,
                sha256 = sha,
                byteCount = 4L,
                widthPx = 4000,
                heightPx = 2250,
                fileName = "$sha.jpg"
            )
        }
    }
}
