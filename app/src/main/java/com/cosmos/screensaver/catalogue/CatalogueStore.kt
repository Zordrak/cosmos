package com.cosmos.screensaver.catalogue

import android.util.Log
import com.cosmos.screensaver.rights.AssetProvider
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** The library cannot be read or written safely; nothing has been changed on disk. */
internal class LibraryUnavailableException(message: String) : IOException(message)

/** A catalogue written by a newer format; never treated as empty. */
internal class UnsupportedFormatException(message: String) : JSONException(message)

/**
 * Stores the catalogue as a single JSON document in app-private storage ([directory] is the
 * app data directory for the saved library). Writes are crash-safe with a retained last-good
 * copy ([AtomicJsonFile]); all access in the process shares one short lock ([withLock]).
 *
 * [load] is for display and playback: an unreadable library looks empty there. Every mutation
 * ([update], [save], [remove], [clear]) and [loadStrict] refuses to run on an unreadable or
 * unsupported library, so damaged state is never overwritten or cleaned up as if it were empty.
 * [unavailableReason] (a failed migration) blocks all mutations the same way.
 */
internal class CatalogueStore(
    val directory: File,
    private val unavailableReason: String? = null,
    private val ops: FileOps = FileOps.Real
) {
    val imagesDirectory = File(directory, "images")
    private val file = File(directory, "catalogue.json")

    fun imageFile(entry: CatalogueEntry) = File(imagesDirectory, entry.fileName)

    /** Runs [block] under the one library lock shared by the catalogue, user state and Undo. */
    fun <T> withLock(block: () -> T): T = synchronized(LOCK) { block() }

    /** Catalogue for display and playback; an unreadable library yields an empty one. */
    fun load(): Catalogue = (read() as? StoreRead.Ok)?.catalogue ?: Catalogue()

    fun read(): StoreRead = synchronized(LOCK) {
        if (unavailableReason != null) return StoreRead.Failed(unavailableReason)
        var unsupported: String? = null
        val result = AtomicJsonFile.read(file) { text ->
            try {
                CatalogueJson.decode(text)
                true
            } catch (error: UnsupportedFormatException) {
                unsupported = error.message
                false
            } catch (error: JSONException) {
                false
            }
        }
        when (result) {
            AtomicJsonFile.Read.Missing -> StoreRead.Ok(Catalogue())
            is AtomicJsonFile.Read.Ok -> StoreRead.Ok(CatalogueJson.decode(result.text), result.recovered)
            is AtomicJsonFile.Read.Unreadable -> {
                Log.w(TAG, "Catalogue unreadable: ${result.reason}")
                StoreRead.Failed(unsupported ?: result.reason, unsupported != null)
            }
        }
    }

    /** Like [load] but throws [LibraryUnavailableException] instead of returning an empty catalogue. */
    @Throws(LibraryUnavailableException::class)
    fun loadStrict(): Catalogue = when (val result = read()) {
        is StoreRead.Ok -> result.catalogue
        is StoreRead.Failed -> throw LibraryUnavailableException(result.reason)
    }

    fun save(catalogue: Catalogue) = synchronized(LOCK) {
        loadStrict()
        write(catalogue)
    }

    private fun write(catalogue: Catalogue) = AtomicJsonFile.write(file, CatalogueJson.encode(catalogue), ops)

    fun update(transform: (Catalogue) -> Catalogue): Catalogue = synchronized(LOCK) {
        val updated = transform(loadStrict())
        write(updated)
        updated
    }

    /**
     * Removes an entry, e.g. after an integrity failure. Its file is deleted only when no
     * other entry (an alias of identical bytes) still uses it.
     */
    fun remove(key: String) = synchronized(LOCK) {
        update { catalogue ->
            val remaining = catalogue.entries.filterNot { it.key == key }
            val stillUsed = remaining.map { it.fileName }.toSet()
            catalogue.entries.filter { it.key == key && it.fileName !in stillUsed }.forEach { imageFile(it).delete() }
            catalogue.copy(entries = remaining)
        }
    }

    /** Deletes every downloaded image and cached decision. */
    fun clear() = synchronized(LOCK) {
        loadStrict()
        imagesDirectory.listFiles()?.forEach { it.delete() }
        write(Catalogue())
    }

    /**
     * Sets aside an unreadable (corrupt, not merely newer-format) catalogue so a fresh one can
     * start. The damaged file is kept next to it for recovery. Returns true when it did so.
     */
    fun quarantineCorrupt(now: Long = System.currentTimeMillis()): Boolean = synchronized(LOCK) {
        val failed = read() as? StoreRead.Failed ?: return false
        if (failed.unsupported || unavailableReason != null) return false
        listOf(file, AtomicJsonFile.temporary(file), AtomicJsonFile.backup(file)).forEach { damaged ->
            if (damaged.exists()) damaged.renameTo(File(damaged.parentFile, "${damaged.name}.corrupt-$now"))
        }
        true
    }

    companion object {
        private const val TAG = "CosmosCatalogue"
        private val LOCK = Any()

        /** The one library lock, for state that must change atomically with the catalogue (user state, Undo). */
        fun <T> locked(block: () -> T): T = synchronized(LOCK) { block() }
    }
}

internal sealed class StoreRead {
    data class Ok(val catalogue: Catalogue, val recovered: Boolean = false) : StoreRead()
    data class Failed(val reason: String, val unsupported: Boolean = false) : StoreRead()
}

internal object CatalogueJson {
    private const val FORMAT_VERSION = 2

    fun encode(catalogue: Catalogue): String {
        val root = JSONObject()
        root.put("formatVersion", FORMAT_VERSION)
        root.put("lastAttemptAt", catalogue.lastAttemptAtEpochMillis)
        root.put("lastSuccessAt", catalogue.lastSuccessAtEpochMillis)
        root.put("lastSummary", catalogue.lastSummary)
        root.put("selectionFingerprint", catalogue.selectionFingerprint)
        root.put("entries", JSONArray().apply { catalogue.entries.forEach { put(encodeEntry(it)) } })
        root.put("rejections", JSONObject().apply {
            catalogue.rejections.forEach { (key, value) ->
                put(key, JSONObject().put("reason", value.reason).put("at", value.atEpochMillis))
            }
        })
        return root.toString(2)
    }

    fun decode(text: String): Catalogue {
        val root = JSONObject(text)
        val version = root.optInt("formatVersion", -1)
        if (version != FORMAT_VERSION) throw UnsupportedFormatException("Catalogue format $version is not supported")
        val entriesJson = root.optJSONArray("entries") ?: JSONArray()
        val entries = (0 until entriesJson.length()).mapNotNull { index ->
            try {
                decodeEntry(entriesJson.getJSONObject(index))
            } catch (_: JSONException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        val rejectionsJson = root.optJSONObject("rejections") ?: JSONObject()
        val rejections = rejectionsJson.keys().asSequence().associateWith { key ->
            val value = rejectionsJson.getJSONObject(key)
            RejectionRecord(value.getString("reason"), value.getLong("at"))
        }
        return Catalogue(
            entries = entries.distinctBy { it.key },
            rejections = rejections,
            lastAttemptAtEpochMillis = root.optLong("lastAttemptAt"),
            lastSuccessAtEpochMillis = root.optLong("lastSuccessAt"),
            lastSummary = root.optString("lastSummary"),
            selectionFingerprint = root.optString("selectionFingerprint")
        )
    }

    private fun encodeEntry(entry: CatalogueEntry) = JSONObject().apply {
        put("provider", entry.provider.name)
        put("providerId", entry.providerId)
        put("title", entry.title)
        put("sourceLabel", entry.sourceLabel)
        put("sourcePageUrl", entry.sourcePageUrl)
        put("assetUrl", entry.assetUrl)
        put("credit", entry.credit)
        put("rightsBasis", entry.rightsBasis)
        put("licenceName", entry.licenceName)
        put("licenceUrl", entry.licenceUrl)
        put("territory", entry.territory)
        put("evidence", JSONArray().apply {
            entry.evidence.forEach {
                put(
                    JSONObject()
                        .put("url", it.url)
                        .put("statement", it.statement)
                        .put("scope", it.scope)
                        .put("retrievedAt", it.retrievedAtEpochMillis)
                )
            }
        })
        put("validatedAt", entry.validatedAtEpochMillis)
        put("policyVersion", entry.policyVersion)
        put("admittedAt", entry.admittedAtEpochMillis)
        put("sha256", entry.sha256)
        put("byteCount", entry.byteCount)
        put("widthPx", entry.widthPx)
        put("heightPx", entry.heightPx)
        put("fileName", entry.fileName)
        put("alteration", entry.alteration)
    }

    private fun decodeEntry(json: JSONObject): CatalogueEntry {
        val evidenceJson = json.getJSONArray("evidence")
        val evidence = (0 until evidenceJson.length()).map { index ->
            val item = evidenceJson.getJSONObject(index)
            EvidenceRecord(
                url = item.getString("url"),
                statement = item.getString("statement"),
                scope = item.getString("scope"),
                retrievedAtEpochMillis = item.getLong("retrievedAt")
            )
        }
        val fileName = json.getString("fileName")
        require(fileName.matches(Regex("^[0-9a-f]{64}\\.(jpg|png)$"))) { "Unexpected file name" }
        val credit = json.getString("credit")
        val rightsBasis = json.getString("rightsBasis")
        require(credit.isNotBlank())
        require(evidence.isNotEmpty() || rightsBasis == CatalogueEntry.RIGHTS_NOT_CHECKED)
        return CatalogueEntry(
            provider = AssetProvider.valueOf(json.getString("provider")),
            providerId = json.getString("providerId"),
            title = json.getString("title"),
            sourceLabel = json.getString("sourceLabel"),
            sourcePageUrl = json.getString("sourcePageUrl"),
            assetUrl = json.getString("assetUrl"),
            credit = credit,
            rightsBasis = rightsBasis,
            licenceName = json.getString("licenceName"),
            licenceUrl = json.getString("licenceUrl"),
            territory = json.getString("territory"),
            evidence = evidence,
            validatedAtEpochMillis = json.getLong("validatedAt"),
            policyVersion = json.getString("policyVersion"),
            admittedAtEpochMillis = json.getLong("admittedAt"),
            sha256 = json.getString("sha256"),
            byteCount = json.getLong("byteCount"),
            widthPx = json.getInt("widthPx"),
            heightPx = json.getInt("heightPx"),
            fileName = fileName,
            alteration = json.getString("alteration")
        )
    }
}
