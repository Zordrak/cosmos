package com.cosmos.screensaver.catalogue

import android.util.Log
import org.json.JSONException
import java.io.File
import java.io.IOException

internal sealed class UserStateRead {
    data class Ok(val state: UserState, val recovered: Boolean = false) : UserStateRead()
    data class Failed(val reason: String, val unsupported: Boolean = false) : UserStateRead()
}

/**
 * The owner's durable choices (favourites, blocks, playback history and ordering state) in
 * `userstate.json` beside the library. It shares the catalogue's lock and recoverable writer, so
 * a favourite or block added on one thread is never overwritten by another thread's update.
 *
 * A missing file is a normal first run. An unreadable or newer-format file is NOT empty: [load]
 * shows defaults for display only, while every change and [loadStrict] refuse to run, so damaged
 * blocks or favourites are never overwritten or forgotten. Instances are cheap; the last healthy
 * snapshot is shared by the process and revalidated against the file.
 */
internal class UserStateStore(
    val directory: File,
    private val unavailableReason: String? = null,
    private val ops: FileOps = FileOps.Real
) {
    private val file = File(directory, FILE_NAME)

    fun read(): UserStateRead = CatalogueStore.locked {
        if (unavailableReason != null) return@locked UserStateRead.Failed(unavailableReason)
        val key = file.absolutePath
        val cached = cache[key]
        if (cached != null && cached.first == stamp()) return@locked UserStateRead.Ok(cached.second)
        var unsupported: String? = null
        val result = AtomicJsonFile.read(file) { text ->
            try {
                UserStateJson.decode(text)
                true
            } catch (error: UnsupportedFormatException) {
                unsupported = error.message
                false
            } catch (error: JSONException) {
                false
            }
        }
        when (result) {
            AtomicJsonFile.Read.Missing -> {
                cache.remove(key)
                UserStateRead.Ok(UserState())
            }
            is AtomicJsonFile.Read.Ok -> {
                val state = UserStateJson.decode(result.text)
                if (result.recovered) cache.remove(key) else cache[key] = stamp() to state
                UserStateRead.Ok(state, result.recovered)
            }
            is AtomicJsonFile.Read.Unreadable -> {
                cache.remove(key)
                Log.w(TAG, "User state unreadable: ${result.reason}")
                UserStateRead.Failed(unsupported ?: result.reason, unsupported != null)
            }
        }
    }

    /** State for display and selection; defaults when unreadable. Use [read] to learn which. */
    fun load(): UserState = (read() as? UserStateRead.Ok)?.state ?: UserState()

    @Throws(LibraryUnavailableException::class)
    fun loadStrict(): UserState = when (val result = read()) {
        is UserStateRead.Ok -> result.state
        is UserStateRead.Failed -> throw LibraryUnavailableException("Saved choices unavailable: ${result.reason}")
    }

    /** Applies [transform] to the LIVE state and saves it. Throws when the state is unreadable or the write fails. */
    @Throws(IOException::class)
    fun update(transform: (UserState) -> UserState): UserState = CatalogueStore.locked {
        val updated = transform(loadStrict())
        write(updated)
        updated
    }

    /** [update] that reports failure instead of throwing. */
    fun tryUpdate(transform: (UserState) -> UserState): Boolean = try {
        update(transform)
        true
    } catch (error: IOException) {
        Log.w(TAG, "User state not saved: ${error.message}")
        false
    } catch (error: JSONException) {
        Log.w(TAG, "User state not saved: ${error.message}")
        false
    }

    fun setFavourite(hash: String, favourite: Boolean): Boolean {
        if (hash.isBlank()) return false
        return tryUpdate { it.copy(favourites = if (favourite) it.favourites + hash else it.favourites - hash) }
    }

    /**
     * Blocks a picture: [aliases] are every catalogue entry sharing its bytes. The exclusion is
     * saved before any file is removed. Also drops the favourite for that identity.
     */
    fun block(aliases: List<CatalogueEntry>, nowMillis: Long): Boolean {
        val first = aliases.firstOrNull() ?: return false
        val keys = aliases.map { it.key }.toSet()
        val hash = aliases.map { it.sha256 }.firstOrNull { it.isNotBlank() }
        return tryUpdate { state ->
            val (overlapping, others) = state.blocked.partition { record -> keys.any { it in record.keys } || (hash != null && hash == record.sha256) }
            val merged = BlockedRecord(
                id = overlapping.firstOrNull()?.id ?: newId(hash, keys),
                keys = keys + overlapping.flatMap { it.keys },
                sha256 = hash ?: overlapping.firstNotNullOfOrNull { it.sha256 },
                title = first.title,
                sourceLabel = first.sourceLabel,
                blockedAtEpochMillis = overlapping.firstOrNull()?.blockedAtEpochMillis ?: nowMillis
            )
            state.copy(blocked = others + merged, favourites = state.favourites - setOfNotNull(hash))
        }
    }

    /** Removes the block record; the caller also clears matching rejection decisions in the catalogue. */
    fun unblock(recordId: String): BlockedRecord? {
        var removed: BlockedRecord? = null
        val saved = tryUpdate { state ->
            removed = state.blocked.firstOrNull { it.id == recordId }
            state.copy(blocked = state.blocked.filterNot { it.id == recordId })
        }
        return if (saved) removed else null
    }

    /**
     * Commits one actually-displayed picture: history plus any ordering change in one write. [savedHashes]
     * is every hash currently in the library so its history is never pruned. Call only for a frame that
     * was posted successfully (C5).
     */
    fun commitDisplay(
        hash: String,
        nowMillis: Long,
        savedHashes: Set<String>,
        order: (UserState) -> UserState = { it }
    ): Boolean {
        if (hash.isBlank()) return false
        return tryUpdate { state ->
            val ordinal = state.displayOrdinal + 1
            val previous = state.stats[hash] ?: PictureStats()
            val counted = state.copy(
                stats = state.stats + (hash to PictureStats(previous.shownCount + 1, nowMillis, ordinal)),
                displayOrdinal = ordinal,
                lastShownHash = hash
            )
            order(counted).pruned(savedHashes)
        }
    }

    /**
     * Applies the one-time seed (the confusing Iwamoto composite) during a successful initialization.
     * It captures the picture's hash and aliases from [catalogue] before anything removes it, and never
     * re-seeds after the owner unblocks it.
     */
    fun ensureSeeded(catalogue: Catalogue, nowMillis: Long): Boolean {
        val current = (read() as? UserStateRead.Ok)?.state ?: return false
        if (current.seedVersion >= UserState.SEED_VERSION) return true
        return tryUpdate { state ->
            if (state.seedVersion >= UserState.SEED_VERSION) return@tryUpdate state
            val aliases = catalogue.entries.filter { it.key == UserState.SEEDED_COMET_KEY }
            val hashes = aliases.map { it.sha256 }.filter { it.isNotBlank() }.toSet()
            val hashAliases = catalogue.entries.filter { it.sha256 in hashes }
            val keys = (hashAliases + aliases).map { it.key }.toSet() + UserState.SEEDED_COMET_KEY
            val hash = hashes.firstOrNull()
            val alreadyBlocked = state.blocked.any { it.matches(UserState.SEEDED_COMET_KEY, hash) }
            val blocked = if (alreadyBlocked) state.blocked else state.blocked + BlockedRecord(
                id = newId(hash, keys),
                keys = keys,
                sha256 = hash,
                title = aliases.firstOrNull()?.title ?: UserState.SEEDED_COMET_TITLE,
                sourceLabel = aliases.firstOrNull()?.sourceLabel ?: "NASA Image and Video Library",
                blockedAtEpochMillis = nowMillis
            )
            state.copy(blocked = blocked, favourites = state.favourites - hashes, seedVersion = UserState.SEED_VERSION)
        }
    }

    /** Sets a damaged (not newer-format) state file aside so choices can start afresh. Owner-initiated only. */
    fun quarantineCorrupt(nowMillis: Long = System.currentTimeMillis()): Boolean = CatalogueStore.locked {
        val failed = read() as? UserStateRead.Failed ?: return@locked false
        if (failed.unsupported || unavailableReason != null) return@locked false
        listOf(file, AtomicJsonFile.temporary(file), AtomicJsonFile.backup(file)).forEach { damaged ->
            if (damaged.exists()) damaged.renameTo(File(damaged.parentFile, "${damaged.name}.corrupt-$nowMillis"))
        }
        cache.remove(file.absolutePath)
        true
    }

    private fun write(state: UserState) {
        AtomicJsonFile.write(file, UserStateJson.encode(state), ops)
        cache[file.absolutePath] = stamp() to state
    }

    private fun stamp() = file.lastModified() to file.length()

    private fun newId(hash: String?, keys: Set<String>) = "b-" + (hash?.take(16) ?: keys.sorted().first().filter { it.isLetterOrDigit() })

    companion object {
        const val FILE_NAME = "userstate.json"
        private const val TAG = "CosmosUserState"
        private val cache = HashMap<String, Pair<Pair<Long, Long>, UserState>>()
    }
}
