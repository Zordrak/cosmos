package com.cosmos.screensaver.catalogue

import android.util.Log
import com.cosmos.screensaver.rights.AssetProvider
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Stores the catalogue as a single JSON document in app-private storage. Writes are atomic
 * (temporary file then rename), and all access in the process is serialised.
 */
internal class CatalogueStore(directory: File) {
    val imagesDirectory = File(directory, "images")
    private val file = File(directory, "catalogue.json")

    fun imageFile(entry: CatalogueEntry) = File(imagesDirectory, entry.fileName)

    fun load(): Catalogue {
        synchronized(LOCK) {
            if (!file.exists()) return Catalogue()
            return try {
                CatalogueJson.decode(file.readText(Charsets.UTF_8))
            } catch (error: JSONException) {
                Log.w(TAG, "Discarding unreadable catalogue", error)
                Catalogue()
            } catch (error: IOException) {
                Log.w(TAG, "Unable to read catalogue", error)
                Catalogue()
            }
        }
    }

    fun save(catalogue: Catalogue) = synchronized(LOCK) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(CatalogueJson.encode(catalogue), Charsets.UTF_8)
        if (!temporary.renameTo(file)) {
            file.delete()
            if (!temporary.renameTo(file)) throw IOException("Unable to replace catalogue")
        }
    }

    fun update(transform: (Catalogue) -> Catalogue): Catalogue = synchronized(LOCK) {
        val updated = transform(load())
        save(updated)
        updated
    }

    /** Removes an entry, e.g. after an integrity failure, and deletes its file. */
    fun remove(key: String) = synchronized(LOCK) {
        update { catalogue ->
            catalogue.entries.filter { it.key == key }.forEach { imageFile(it).delete() }
            catalogue.copy(entries = catalogue.entries.filterNot { it.key == key })
        }
    }

    /** Deletes every downloaded image and cached decision. */
    fun clear() = synchronized(LOCK) {
        imagesDirectory.listFiles()?.forEach { it.delete() }
        save(Catalogue())
    }

    companion object {
        private const val TAG = "CosmosCatalogue"
        private val LOCK = Any()
    }
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
        if (root.optInt("formatVersion") != FORMAT_VERSION) return Catalogue()
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
