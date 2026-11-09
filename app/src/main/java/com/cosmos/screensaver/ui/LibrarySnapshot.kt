package com.cosmos.screensaver.ui

import android.content.Context
import com.cosmos.screensaver.catalogue.StoreRead
import com.cosmos.screensaver.settings.CosmosSettings
import com.cosmos.screensaver.settings.SavedPicturesState
import com.cosmos.screensaver.settings.SelectionSnapshot
import com.cosmos.screensaver.sync.LibraryStorage
import com.cosmos.screensaver.sync.RefreshCoordinator
import com.cosmos.screensaver.sync.StorageMeasurement
import com.cosmos.screensaver.sync.TrashBin

/**
 * An immutable view of the saved pictures for the settings screens. It is built on the storage
 * executor and published to the main thread, so the UI never parses the catalogue itself. [revision]
 * lets the UI discard an older asynchronous result.
 */
internal data class LibrarySnapshot(
    val revision: Long = 0L,
    val loaded: Boolean = false,
    val unreadable: Boolean = false,
    val files: Int = 0,
    val bytes: Long = 0L,
    val eligible: Int = 0,
    val lowOnSpace: Boolean = false,
    val lastAttemptAtEpochMillis: Long = 0L,
    val lastSummary: String = "",
    /** Distinct pictures, counting aliases of one picture once. */
    val pictures: Int = 0,
    val favouritePictures: Int = 0,
    val favouriteBytes: Long = 0L,
    val blockedCount: Int = 0,
    /** Files set aside for a change that can still be undone. */
    val trashBytes: Long = 0L,
    val measurement: StorageMeasurement = StorageMeasurement.Unknown,
    val pending: TrashBin.Pending? = null,
    val composition: CacheComposition = CacheComposition()
) {
    val saved: SavedPicturesState
        get() = when {
            unreadable -> SavedPicturesState.Unreadable
            !loaded -> SavedPicturesState.Loading
            else -> SavedPicturesState.Loaded(files, bytes)
        }

    /** The bar for [limitBytes] (0 = Automatic) from this snapshot's measurement and holdings. */
    fun barLayout(limitBytes: Long): StorageBarLayout =
        if (!loaded || unreadable) {
            StorageBarLayout.UNKNOWN
        } else {
            StorageBarModel.layout(
                measurement, limitBytes, favouriteBytes, (bytes - favouriteBytes).coerceAtLeast(0L), trashBytes
            )
        }

    companion object {
        /** Reads the library and current settings. Call on the storage executor, never on the main thread. */
        fun load(context: Context, settings: CosmosSettings, revision: Long): LibrarySnapshot {
            val appContext = context.applicationContext
            return when (val read = RefreshCoordinator.store(appContext).read()) {
                is StoreRead.Failed -> LibrarySnapshot(revision = revision, loaded = true, unreadable = true)
                is StoreRead.Ok -> {
                    val entries = read.catalogue.entries
                    val bytes = SelectionSnapshot.physicalBytes(entries)
                    val userState = LibraryStorage.userState(appContext).load()
                    val trash = TrashBin.of(appContext)
                    if (trash.pending() != null) trash.recoverAndExpire()
                    val selection = SelectionSnapshot.build(entries, settings.selection, settings.enabledProviders, userState)
                    LibrarySnapshot(
                        revision = revision,
                        loaded = true,
                        files = SelectionSnapshot.physicalFiles(entries),
                        bytes = bytes,
                        eligible = selection.eligible.size,
                        lowOnSpace = RefreshCoordinator.lowOnSpace(appContext, bytes),
                        lastAttemptAtEpochMillis = read.catalogue.lastAttemptAtEpochMillis,
                        lastSummary = read.catalogue.lastSummary,
                        pictures = SelectionSnapshot.uniquePictures(entries),
                        favouritePictures = SelectionSnapshot.favouritePictures(entries, userState),
                        favouriteBytes = SelectionSnapshot.favouriteBytes(entries, userState),
                        blockedCount = userState.blocked.size,
                        trashBytes = trash.bytesOnDisk(),
                        measurement = RefreshCoordinator.measure(appContext),
                        pending = trash.pending(),
                        composition = CacheComposition.of(entries, selection.eligibleIdentities, userState)
                    )
                }
            }
        }
    }
}
