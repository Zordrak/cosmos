package com.cosmos.screensaver.sync

import android.content.Context
import com.cosmos.screensaver.catalogue.CatalogueEntry
import com.cosmos.screensaver.catalogue.CatalogueStore
import java.io.File

/**
 * The last-resort mode for when free space is too low to keep a library: each image is downloaded
 * on demand into a scratch directory, shown, and deleted straight away. Nothing is retained.
 */
internal class ImageStreamer(context: Context) {
    private val appContext = context.applicationContext
    private val store = CatalogueStore(File(appContext.cacheDir, "stream"))
    private val queue = StreamQueue()
    private val refresher by lazy {
        RefreshCoordinator.createRefresher(appContext, store, LibraryStorage.userState(appContext)) { Long.MAX_VALUE }
    }

    init {
        discardAll()
    }

    /** True when there is enough free space to hold one image while it is being shown. */
    fun hasRoom(): Boolean = RefreshCoordinator.freeBytes(appContext) >= MIN_FREE_BYTES

    /** Downloads the next image, or returns null if none could be obtained. Delete it with [discard]. */
    fun next(): CatalogueEntry? = if (hasRoom()) refresher.streamNext(queue) else null

    fun file(entry: CatalogueEntry): File = store.imageFile(entry)

    fun discard(entry: CatalogueEntry) {
        store.imageFile(entry).delete()
    }

    fun discardAll() {
        store.imagesDirectory.listFiles()?.forEach { it.delete() }
    }

    companion object {
        /** One image of up to 60 MiB, plus room to decode it. */
        const val MIN_FREE_BYTES = 150L * 1024 * 1024

        /** While streaming, a new image is fetched no more often than this, to limit bandwidth. */
        const val MIN_INTERVAL_SECONDS = 60

        /** Streaming also tops up a library with fewer cached images than this. */
        const val SMALL_LIBRARY = 10
    }
}
