package com.cosmos.screensaver.ui

import android.content.Context
import com.cosmos.screensaver.catalogue.StoreRead
import com.cosmos.screensaver.settings.CosmosSettings
import com.cosmos.screensaver.settings.SavedPicturesState
import com.cosmos.screensaver.settings.SelectionSnapshot
import com.cosmos.screensaver.sync.LibraryStorage
import com.cosmos.screensaver.sync.RefreshCoordinator

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
    val lastSummary: String = ""
) {
    val saved: SavedPicturesState
        get() = when {
            unreadable -> SavedPicturesState.Unreadable
            !loaded -> SavedPicturesState.Loading
            else -> SavedPicturesState.Loaded(files, bytes)
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
                    LibrarySnapshot(
                        revision = revision,
                        loaded = true,
                        files = SelectionSnapshot.physicalFiles(entries),
                        bytes = bytes,
                        eligible = SelectionSnapshot.build(
                            entries, settings.selection, settings.enabledProviders,
                            LibraryStorage.userState(appContext).load()
                        ).eligible.size,
                        lowOnSpace = RefreshCoordinator.lowOnSpace(appContext, bytes),
                        lastAttemptAtEpochMillis = read.catalogue.lastAttemptAtEpochMillis,
                        lastSummary = read.catalogue.lastSummary
                    )
                }
            }
        }
    }
}
