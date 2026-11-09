package com.cosmos.screensaver.sync

import android.content.Context
import android.util.Log
import com.cosmos.screensaver.catalogue.CatalogueStore
import com.cosmos.screensaver.catalogue.StoreRead
import com.cosmos.screensaver.catalogue.UserStateStore
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Owns the library's storage lifecycle: the serial storage executor and the one-time, shared
 * migration into app data. Every caller of [store] waits for the SAME initialization result;
 * only a successful result is remembered, so a failed migration is retried and stays visible.
 */
internal object LibraryStorage {
    private const val TAG = "CosmosStorage"

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "cosmos-storage").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }
    private val onStorageThread = ThreadLocal<Boolean>()
    private val lock = Any()

    @Volatile
    private var ready: LibraryMigration.Result? = null

    @Volatile
    var lastResult: LibraryMigration.Result? = null
        private set

    /** Runs disk work off the main thread, in order. Safe to call from the storage thread itself. */
    fun <T> run(task: () -> T): T {
        if (onStorageThread.get() == true) return task()
        return executor.submit(Callable { onStorageThread.set(true); task() }).get()
    }

    fun post(task: () -> Unit) {
        executor.execute { onStorageThread.set(true); runCatching(task).onFailure { Log.e(TAG, "Storage task failed", it) } }
    }

    /** Waits for (or performs) the single migration and returns its result. All callers share one barrier. */
    fun initialize(context: Context): LibraryMigration.Result {
        ready?.let { return it }
        synchronized(lock) {
            ready?.let { return it }
            val appContext = context.applicationContext
            val result = initializeOnStorageThread(appContext.filesDir, appContext.cacheDir)
            lastResult = result
            if (result !is LibraryMigration.Result.Failed) ready = result
            return result
        }
    }

    internal fun initializeOnStorageThread(filesDir: File, cacheDir: File): LibraryMigration.Result {
        val result = LibraryMigration.migrate(oldDirectory = cacheDir, newDirectory = filesDir)
        when (result) {
            is LibraryMigration.Result.Failed -> Log.e(TAG, "Library migration failed: ${result.reason}")
            is LibraryMigration.Result.Ready -> Log.i(TAG, "Library migration: $result")
            LibraryMigration.Result.NotNeeded -> Unit
        }
        if (result !is LibraryMigration.Result.Failed) {
            val store = CatalogueStore(filesDir)
            if (store.quarantineCorrupt()) Log.w(TAG, "A damaged library index was set aside")
            (store.read() as? StoreRead.Ok)?.let { UserStateStore(filesDir).ensureSeeded(it.catalogue, System.currentTimeMillis()) }
        }
        return result
    }

    /** The saved library, in app data. Blocks (briefly) until the shared migration has finished. */
    fun store(context: Context): CatalogueStore {
        val appContext = context.applicationContext
        val result = initialize(appContext)
        val reason = (result as? LibraryMigration.Result.Failed)?.reason?.let { "Library migration failed: $it" }
        return CatalogueStore(appContext.filesDir, unavailableReason = reason)
    }
}
