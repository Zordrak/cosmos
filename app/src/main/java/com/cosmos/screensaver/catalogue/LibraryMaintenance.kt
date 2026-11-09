package com.cosmos.screensaver.catalogue

import com.cosmos.screensaver.source.ContentFilter
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Local, network-free upkeep of the saved library. Every operation runs under the shared library
 * lock and refuses to act when the catalogue or the user's saved choices cannot be read, so an
 * unreadable file is never mistaken for an empty one (contract C2). Favourites are never deleted
 * here; an explicit, confirmed Remove/Block is the only exception.
 */
internal object LibraryMaintenance {
    /** Rejection reason recorded when a download turned out to be a blocked picture. */
    const val BLOCKED = "BLOCKED"

    data class Report(val removed: Int = 0, val hidden: Int = 0)

    /** Files being written or about to be committed by an in-flight admission; cleanup leaves them alone. */
    private val inFlight: MutableSet<String> = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private val LIBRARY_FILE = Regex("^[0-9a-f]{64}\\.(jpg|png)$")
    private const val STALE_PART_MILLIS = 10L * 60 * 1000

    fun claim(file: File) {
        inFlight += file.absolutePath
    }

    fun release(file: File) {
        inFlight -= file.absolutePath
    }

    /**
     * Applies explicit blocks and the content rules that need only the saved metadata, and drops
     * entries whose file has vanished. Favourites that fail are kept but hidden with the reason.
     */
    fun screenLocal(store: CatalogueStore, userState: UserStateStore): Report = CatalogueStore.locked {
        val state = (userState.read() as? UserStateRead.Ok)?.state ?: return@locked Report()
        val catalogue = (store.read() as? StoreRead.Ok)?.catalogue ?: return@locked Report()
        val kept = mutableListOf<CatalogueEntry>()
        val dropped = mutableListOf<CatalogueEntry>()
        var hidden = 0
        for (entry in catalogue.entries) {
            val favourite = state.isFavourite(entry.sha256)
            val reason = entry.hiddenReason.ifBlank { ContentFilter.check(entry.title).orEmpty() }
            when {
                state.isBlocked(entry.key, entry.sha256) -> dropped += entry
                !store.imageFile(entry).isFile && !favourite -> dropped += entry
                reason.isNotBlank() && !favourite -> dropped += entry
                reason.isNotBlank() && entry.hiddenReason != reason -> {
                    kept += entry.copy(hiddenReason = reason)
                    hidden++
                }
                else -> kept += entry
            }
        }
        if (dropped.isEmpty() && hidden == 0) return@locked Report()
        try {
            store.update { it.copy(entries = kept) }
        } catch (error: IOException) {
            return@locked Report()
        }
        deleteUnreferenced(store, dropped, kept)
        Report(removed = dropped.size, hidden = hidden)
    }

    /**
     * Withdraws every entry [matches] (for example a licence that no longer applies). Favourites,
     * and everything when the user's choices cannot be read, are kept but hidden for [reason].
     */
    fun withdraw(
        store: CatalogueStore,
        userState: UserStateStore,
        reason: String,
        matches: (CatalogueEntry) -> Boolean
    ): Int = CatalogueStore.locked {
        val state = (userState.read() as? UserStateRead.Ok)?.state
        val catalogue = (store.read() as? StoreRead.Ok)?.catalogue ?: return@locked 0
        val kept = mutableListOf<CatalogueEntry>()
        val dropped = mutableListOf<CatalogueEntry>()
        for (entry in catalogue.entries) {
            when {
                !matches(entry) -> kept += entry
                state == null || state.isFavourite(entry.sha256) -> kept += entry.copy(hiddenReason = reason)
                else -> dropped += entry
            }
        }
        if (kept.size == catalogue.entries.size && kept == catalogue.entries) return@locked 0
        try {
            store.update { it.copy(entries = kept) }
        } catch (error: IOException) {
            return@locked 0
        }
        deleteUnreferenced(store, dropped, kept)
        dropped.size
    }

    /**
     * Removes the entry for [key] (for example after an integrity failure). Refuses when that would
     * delete the last copy of a favourite, or when the saved choices cannot be read.
     */
    fun removeKey(store: CatalogueStore, userState: UserStateStore, key: String): Boolean = CatalogueStore.locked {
        val state = (userState.read() as? UserStateRead.Ok)?.state ?: return@locked false
        val catalogue = (store.read() as? StoreRead.Ok)?.catalogue ?: return@locked false
        val targets = catalogue.entries.filter { it.key == key }
        if (targets.isEmpty()) return@locked false
        val remaining = catalogue.entries.filterNot { it.key == key }
        val protectsFavourite = targets.any { target ->
            state.isFavourite(target.sha256) && remaining.none { it.sha256 == target.sha256 }
        }
        if (protectsFavourite) return@locked false
        try {
            store.update { it.copy(entries = remaining) }
        } catch (error: IOException) {
            return@locked false
        }
        deleteUnreferenced(store, targets, remaining)
        true
    }

    /** Returned by [evict] when the picture is a favourite (possibly made one after the caller chose it). */
    const val PROTECTED = -1

    /**
     * Removes every alias of one picture to make room. Favourite status is re-read under the lock,
     * so a stale candidate list can never delete a picture favourited meanwhile. Returns the
     * number of aliases removed, [PROTECTED], or 0 if nothing could be removed.
     */
    fun evict(store: CatalogueStore, userState: UserStateStore, identity: String): Int = CatalogueStore.locked {
        val state = (userState.read() as? UserStateRead.Ok)?.state ?: return@locked 0
        val catalogue = (store.read() as? StoreRead.Ok)?.catalogue ?: return@locked 0
        val targets = catalogue.entries.filter { (it.sha256.ifBlank { it.key }) == identity }
        if (targets.isEmpty()) return@locked 0
        if (targets.any { state.isFavourite(it.sha256) }) return@locked PROTECTED
        val remaining = catalogue.entries - targets.toSet()
        try {
            store.update { it.copy(entries = remaining) }
        } catch (error: IOException) {
            return@locked 0
        }
        deleteUnreferenced(store, targets, remaining)
        targets.size
    }

    /** Lifts a block and forgets any cached "blocked" decisions so the picture can return at once. */
    fun unblock(store: CatalogueStore, userState: UserStateStore, recordId: String): Boolean = CatalogueStore.locked {
        val record = userState.unblock(recordId) ?: return@locked false
        try {
            store.update { catalogue ->
                catalogue.copy(
                    rejections = catalogue.rejections.filterNot { (key, rejection) ->
                        rejection.reason == BLOCKED && key in record.keys
                    }
                )
            }
        } catch (error: IOException) {
            // The catalogue is unreadable, so there are no usable cached decisions to clear.
        }
        true
    }

    /** Deletes a just-downloaded file that no catalogue entry or in-flight admission refers to. */
    fun discardIfUnreferenced(store: CatalogueStore, fileName: String) = CatalogueStore.locked {
        val catalogue = (store.read() as? StoreRead.Ok)?.catalogue ?: return@locked
        if (catalogue.entries.none { it.fileName == fileName }) {
            val file = File(store.imagesDirectory, fileName)
            if (file.absolutePath !in inFlight) file.delete()
        }
    }

    /** Removes library files nothing refers to, plus abandoned partial downloads. Needs a readable catalogue. */
    fun deleteOrphanFiles(store: CatalogueStore, nowMillis: Long = System.currentTimeMillis()) = CatalogueStore.locked {
        val catalogue = (store.read() as? StoreRead.Ok)?.catalogue ?: return@locked
        val referenced = catalogue.entries.map { it.fileName }.toSet()
        store.imagesDirectory.listFiles()?.forEach { file ->
            if (!file.isFile || file.absolutePath in inFlight) return@forEach
            val orphan = LIBRARY_FILE.matches(file.name) && file.name !in referenced
            val abandoned = file.name.endsWith(".part") && nowMillis - file.lastModified() > STALE_PART_MILLIS
            if (orphan || abandoned) file.delete()
        }
    }

    private fun deleteUnreferenced(store: CatalogueStore, dropped: List<CatalogueEntry>, remaining: List<CatalogueEntry>) {
        val used = remaining.map { it.fileName }.toSet()
        dropped.map { it.fileName }.distinct().filter { it !in used }.forEach { name ->
            val file = File(store.imagesDirectory, name)
            if (file.absolutePath !in inFlight) file.delete()
        }
    }
}
