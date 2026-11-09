package com.cosmos.screensaver.catalogue

import com.cosmos.screensaver.rights.AssetProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
            lastSummary = "2 images available.",
            selectionFingerprint = "licence=ANY_AVAILABLE;quality=UHD_ONLY"
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
    fun keepsPersonalUseEntriesWithoutRightsEvidence() {
        val personal = entry("heic9901a").copy(
            rightsBasis = CatalogueEntry.RIGHTS_NOT_CHECKED,
            licenceName = "",
            licenceUrl = "",
            territory = "",
            policyVersion = "",
            evidence = emptyList()
        )
        val catalogue = Catalogue(entries = listOf(personal))

        assertEquals(catalogue, CatalogueJson.decode(CatalogueJson.encode(catalogue)))
    }

    @Test
    fun rejectsUnknownFormatVersionsInsteadOfTreatingThemAsEmpty() {
        assertThrows(UnsupportedFormatException::class.java) {
            CatalogueJson.decode("""{"formatVersion": 99, "entries": []}""")
        }
        val directory = Files.createTempDirectory("cosmos-store").toFile()
        try {
            val text = """{"formatVersion": 99, "entries": []}"""
            directory.resolve("catalogue.json").writeText(text)
            val store = CatalogueStore(directory)
            assertTrue((store.read() as StoreRead.Failed).unsupported)
            assertThrows(LibraryUnavailableException::class.java) { store.update { it } }
            assertFalse(store.quarantineCorrupt())
            assertEquals(text, directory.resolve("catalogue.json").readText())
        } finally {
            directory.deleteRecursively()
        }
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
    fun removingAnAliasKeepsAFileAnotherAliasStillUses() {
        val directory = Files.createTempDirectory("cosmos-store").toFile()
        try {
            val store = CatalogueStore(directory)
            val first = entry("heic9901a")
            val alias = first.copy(provider = AssetProvider.NASA, providerId = "PIA1")
            store.imagesDirectory.mkdirs()
            store.imageFile(first).writeText("x")
            store.save(Catalogue(entries = listOf(first, alias)))

            store.remove(first.key)

            assertTrue(store.imageFile(first).exists())
            assertEquals(listOf(alias.key), store.load().entries.map { it.key })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun corruptFileIsNotAnEmptySuccess() {
        val directory = Files.createTempDirectory("cosmos-store").toFile()
        try {
            directory.resolve("catalogue.json").writeText("{not json")
            val store = CatalogueStore(directory)

            assertEquals(Catalogue(), store.load())
            assertThrows(LibraryUnavailableException::class.java) { store.loadStrict() }
            assertThrows(LibraryUnavailableException::class.java) { store.save(Catalogue()) }
            assertThrows(LibraryUnavailableException::class.java) { store.clear() }
            assertEquals("{not json", directory.resolve("catalogue.json").readText())

            assertTrue(store.quarantineCorrupt(7L))
            assertEquals(Catalogue(), store.loadStrict())
            assertTrue(directory.resolve("catalogue.json.corrupt-7").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun recoversTheLastGoodCopyWhenTheCurrentFileIsDamaged() {
        val directory = Files.createTempDirectory("cosmos-store").toFile()
        try {
            val store = CatalogueStore(directory)
            store.save(Catalogue(entries = listOf(entry("heic9901a"))))
            store.save(Catalogue(entries = listOf(entry("heic9901a"), entry("heic9902a"))))
            directory.resolve("catalogue.json").writeText("{torn")

            val read = store.read() as StoreRead.Ok
            assertTrue(read.recovered)
            assertEquals(listOf("heic9901a"), read.catalogue.entries.map { it.providerId })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun aFailedWriteLeavesThePreviousCatalogueIntact() {
        val directory = Files.createTempDirectory("cosmos-store").toFile()
        try {
            CatalogueStore(directory).save(Catalogue(entries = listOf(entry("heic9901a"))))
            var failedOnce = false
            val failing = CatalogueStore(directory, ops = object : FileOps by FileOps.Real {
                override fun rename(from: java.io.File, to: java.io.File): Boolean {
                    if (to.name == "catalogue.json" && !failedOnce) {
                        failedOnce = true
                        return false
                    }
                    return from.renameTo(to)
                }
            })

            assertThrows(java.io.IOException::class.java) { failing.save(Catalogue(entries = listOf(entry("heic9902a")))) }

            assertEquals(listOf("heic9901a"), CatalogueStore(directory).loadStrict().entries.map { it.providerId })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun aMigrationFailureBlocksMutationsAndLeavesReadsEmpty() {
        val directory = Files.createTempDirectory("cosmos-store").toFile()
        try {
            val store = CatalogueStore(directory, unavailableReason = "boom")
            assertEquals(Catalogue(), store.load())
            assertThrows(LibraryUnavailableException::class.java) { store.update { it } }
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
                policyVersion = com.cosmos.screensaver.rights.LicenceValidator.POLICY_VERSION,
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
