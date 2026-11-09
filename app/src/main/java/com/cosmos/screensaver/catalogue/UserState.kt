package com.cosmos.screensaver.catalogue

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * A picture the owner blocked. Identity is by source key OR content hash (C1); a record made
 * before the bytes were known (a seeded one) carries only keys. Holds no thumbnail.
 */
internal data class BlockedRecord(
    val id: String,
    val keys: Set<String>,
    val sha256: String?,
    val title: String,
    val sourceLabel: String,
    val blockedAtEpochMillis: Long
) {
    fun matches(key: String?, hash: String?): Boolean =
        (key != null && key in keys) || (!hash.isNullOrBlank() && hash == sha256)
}

internal data class PictureStats(
    val shownCount: Long = 0L,
    val lastShownAtEpochMillis: Long = 0L,
    /** Position in the global display sequence; ordering uses this, never the wall clock. */
    val lastShownOrdinal: Long = 0L
)

/**
 * Weighted-service state shared by the saved-picture order (keyed by hash) and the streaming
 * order (keyed by lead key). See the ordering steps for the exact meaning of each field.
 */
internal data class FairState(
    val initialized: Boolean = false,
    val scores: Map<String, Int> = emptyMap(),
    val known: Set<String> = emptySet(),
    val pendingNew: List<String> = emptyList(),
    val regularSinceNew: Int = 0
)

/** In-order cursor: a structured tuple, never an index. */
internal data class SequentialCursor(val provider: String, val title: String, val id: String)

internal data class CachedOrder(
    val fair: FairState = FairState(),
    val cursor: SequentialCursor? = null
)

internal data class StreamAttempt(
    val leadKey: String,
    val failures: Int,
    val lastAttemptAtEpochMillis: Long,
    val retryAfterEpochMillis: Long
)

internal data class StreamOrder(
    val fair: FairState = FairState(),
    val cursor: SequentialCursor? = null,
    val lastAcceptedLead: String? = null,
    /** Bounded and ordered oldest first. */
    val attempts: List<StreamAttempt> = emptyList(),
    /** Known lead key to picture hash, bounded and ordered oldest first. */
    val leadHashes: List<Pair<String, String>> = emptyList()
)

internal data class UserState(
    val favourites: Set<String> = emptySet(),
    val blocked: List<BlockedRecord> = emptyList(),
    val stats: Map<String, PictureStats> = emptyMap(),
    val displayOrdinal: Long = 0L,
    val lastShownHash: String? = null,
    val cachedOrder: CachedOrder = CachedOrder(),
    val streamOrder: StreamOrder = StreamOrder(),
    val seedVersion: Int = 0
) {
    fun isFavourite(hash: String?) = !hash.isNullOrBlank() && hash in favourites

    fun isBlocked(key: String?, hash: String?) = blocked.any { it.matches(key, hash) }

    fun blockedRecordFor(key: String?, hash: String?) = blocked.firstOrNull { it.matches(key, hash) }

    /**
     * Keeps stats for every saved, favourite and blocked picture, plus the [HISTORY_LIMIT] most
     * recently shown others (streamed-only history). [savedHashes] must be every hash in the library.
     */
    fun pruned(savedHashes: Set<String>): UserState {
        val kept = favourites + savedHashes + blocked.mapNotNull { it.sha256 }
        val (protectedStats, other) = stats.entries.partition { it.key in kept }
        if (other.size <= HISTORY_LIMIT) return this
        val recent = other.sortedByDescending { it.value.lastShownOrdinal }.take(HISTORY_LIMIT)
        return copy(stats = (protectedStats + recent).associate { it.key to it.value })
    }

    companion object {
        const val HISTORY_LIMIT = 3000
        const val SEED_VERSION = 1

        /** The multi-epoch composite comet the owner found confusing: NASA "Comet C/2018 Y1 Iwamoto". */
        const val SEEDED_COMET_KEY = "NASA:PIA23165"
        const val SEEDED_COMET_TITLE = "Comet C/2018 Y1 Iwamoto"
    }
}

internal object UserStateJson {
    const val FORMAT_VERSION = 1

    fun encode(state: UserState): String {
        val root = JSONObject()
        root.put("formatVersion", FORMAT_VERSION)
        root.put("seedVersion", state.seedVersion)
        root.put("displayOrdinal", state.displayOrdinal)
        root.put("lastShownHash", state.lastShownHash ?: JSONObject.NULL)
        root.put("favourites", JSONArray(state.favourites.sorted()))
        root.put("blocked", JSONArray().apply {
            state.blocked.forEach { record ->
                put(
                    JSONObject()
                        .put("id", record.id)
                        .put("keys", JSONArray(record.keys.sorted()))
                        .put("sha256", record.sha256 ?: JSONObject.NULL)
                        .put("title", record.title)
                        .put("sourceLabel", record.sourceLabel)
                        .put("blockedAt", record.blockedAtEpochMillis)
                )
            }
        })
        root.put("stats", JSONObject().apply {
            state.stats.forEach { (hash, stats) ->
                put(hash, JSONObject().put("n", stats.shownCount).put("at", stats.lastShownAtEpochMillis).put("o", stats.lastShownOrdinal))
            }
        })
        root.put("cachedOrder", JSONObject().put("fair", encodeFair(state.cachedOrder.fair)).put("cursor", encodeCursor(state.cachedOrder.cursor)))
        val stream = state.streamOrder
        root.put(
            "streamOrder",
            JSONObject()
                .put("fair", encodeFair(stream.fair))
                .put("cursor", encodeCursor(stream.cursor))
                .put("lastAcceptedLead", stream.lastAcceptedLead ?: JSONObject.NULL)
                .put("attempts", JSONArray().apply {
                    stream.attempts.forEach {
                        put(JSONObject().put("lead", it.leadKey).put("failures", it.failures).put("at", it.lastAttemptAtEpochMillis).put("retry", it.retryAfterEpochMillis))
                    }
                })
                .put("leadHashes", JSONArray().apply {
                    stream.leadHashes.forEach { (lead, hash) -> put(JSONArray().put(lead).put(hash)) }
                })
        )
        return root.toString(2)
    }

    @Throws(JSONException::class)
    fun decode(text: String): UserState {
        val root = JSONObject(text)
        val version = root.optInt("formatVersion", -1)
        if (version != FORMAT_VERSION) throw UnsupportedFormatException("User state format $version is not supported")
        val stream = root.optJSONObject("streamOrder") ?: JSONObject()
        return UserState(
            favourites = strings(root.optJSONArray("favourites")).toSet(),
            blocked = objects(root.optJSONArray("blocked")).map { json ->
                BlockedRecord(
                    id = json.getString("id"),
                    keys = strings(json.optJSONArray("keys")).toSet(),
                    sha256 = json.optString("sha256").takeIf { !json.isNull("sha256") && it.isNotBlank() },
                    title = json.optString("title"),
                    sourceLabel = json.optString("sourceLabel"),
                    blockedAtEpochMillis = json.optLong("blockedAt")
                )
            },
            stats = (root.optJSONObject("stats") ?: JSONObject()).let { json ->
                json.keys().asSequence().associateWith { hash ->
                    val item = json.getJSONObject(hash)
                    PictureStats(item.optLong("n"), item.optLong("at"), item.optLong("o"))
                }
            },
            displayOrdinal = root.optLong("displayOrdinal"),
            lastShownHash = root.optString("lastShownHash").takeIf { !root.isNull("lastShownHash") && it.isNotBlank() },
            cachedOrder = (root.optJSONObject("cachedOrder") ?: JSONObject()).let {
                CachedOrder(decodeFair(it.optJSONObject("fair")), decodeCursor(it.optJSONObject("cursor")))
            },
            streamOrder = StreamOrder(
                fair = decodeFair(stream.optJSONObject("fair")),
                cursor = decodeCursor(stream.optJSONObject("cursor")),
                lastAcceptedLead = stream.optString("lastAcceptedLead").takeIf { !stream.isNull("lastAcceptedLead") && it.isNotBlank() },
                attempts = objects(stream.optJSONArray("attempts")).map {
                    StreamAttempt(it.getString("lead"), it.optInt("failures"), it.optLong("at"), it.optLong("retry"))
                },
                leadHashes = (0 until (stream.optJSONArray("leadHashes")?.length() ?: 0)).map { index ->
                    val pair = stream.getJSONArray("leadHashes").getJSONArray(index)
                    pair.getString(0) to pair.getString(1)
                }
            ),
            seedVersion = root.optInt("seedVersion")
        )
    }

    private fun encodeFair(fair: FairState) = JSONObject()
        .put("initialized", fair.initialized)
        .put("scores", JSONObject().apply { fair.scores.forEach { (key, score) -> put(key, score) } })
        .put("known", JSONArray(fair.known.sorted()))
        .put("pendingNew", JSONArray(fair.pendingNew))
        .put("regularSinceNew", fair.regularSinceNew)

    private fun decodeFair(json: JSONObject?): FairState {
        json ?: return FairState()
        val scores = json.optJSONObject("scores") ?: JSONObject()
        return FairState(
            initialized = json.optBoolean("initialized"),
            scores = scores.keys().asSequence().associateWith { scores.getInt(it) },
            known = strings(json.optJSONArray("known")).toSet(),
            pendingNew = strings(json.optJSONArray("pendingNew")),
            regularSinceNew = json.optInt("regularSinceNew")
        )
    }

    private fun encodeCursor(cursor: SequentialCursor?): Any =
        cursor?.let { JSONObject().put("provider", it.provider).put("title", it.title).put("id", it.id) } ?: JSONObject.NULL

    private fun decodeCursor(json: JSONObject?): SequentialCursor? =
        json?.let { SequentialCursor(it.getString("provider"), it.getString("title"), it.getString("id")) }

    private fun strings(array: JSONArray?): List<String> = (0 until (array?.length() ?: 0)).map { array!!.getString(it) }

    private fun objects(array: JSONArray?): List<JSONObject> = (0 until (array?.length() ?: 0)).map { array!!.getJSONObject(it) }
}
