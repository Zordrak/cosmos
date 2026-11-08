package com.cosmos.screensaver.sync

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.util.Log
import com.cosmos.screensaver.catalogue.Catalogue
import com.cosmos.screensaver.catalogue.CatalogueStore
import com.cosmos.screensaver.rights.AssetProvider
import com.cosmos.screensaver.settings.CosmosSettings
import com.cosmos.screensaver.settings.ImageSelection
import com.cosmos.screensaver.source.HttpsFetcher
import com.cosmos.screensaver.source.Sources
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Runs at most one refresh at a time on a background thread. Refreshes are triggered only when
 * the app is opened, when the screensaver starts, or on request; Cosmos does not assume any
 * background scheduler runs while the TV is idle.
 */
internal object RefreshCoordinator {
    private const val TAG = "CosmosRefresh"
    private const val STALE_AFTER_MILLIS = 20L * 60 * 60 * 1000
    private const val EMPTY_RETRY_AFTER_MILLIS = 10L * 60 * 1000
    private const val MAX_LIBRARY_BYTES = 2L * 1024 * 1024 * 1024
    private const val MIN_FREE_BYTES = 3L * 1024 * 1024 * 1024
    private const val MIN_FREE_FRACTION = 0.15
    private const val MIN_USEFUL_BUDGET_BYTES = 50L * 1024 * 1024

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "cosmos-refresh").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }
    private val gate = RefreshGate()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    val isRunning: Boolean get() = gate.isRunning

    /** The library lives in the cache directory: it is re-downloadable, and the system may reclaim it. */
    fun store(context: Context): CatalogueStore {
        val appContext = context.applicationContext
        removeLegacyStorage(appContext)
        return CatalogueStore(appContext.cacheDir)
    }

    private fun removeLegacyStorage(context: Context) {
        val legacyImages = File(context.filesDir, "images")
        val legacyCatalogue = File(context.filesDir, "catalogue.json")
        if (legacyImages.exists()) legacyImages.deleteRecursively()
        if (legacyCatalogue.exists()) legacyCatalogue.delete()
    }

    /**
     * Bytes the library may occupy: up to [MAX_LIBRARY_BYTES], but never so much that free space
     * drops below the larger of [MIN_FREE_BYTES] and [MIN_FREE_FRACTION] of the storage.
     */
    fun storageBudget(context: Context, usedBytes: Long): Long = try {
        val stat = StatFs(context.applicationContext.cacheDir.path)
        val reserve = maxOf(MIN_FREE_BYTES, (stat.totalBytes * MIN_FREE_FRACTION).toLong())
        minOf(MAX_LIBRARY_BYTES, usedBytes + stat.availableBytes - reserve).coerceAtLeast(0L)
    } catch (error: IllegalArgumentException) {
        Log.w(TAG, "Unable to read free space", error)
        MAX_LIBRARY_BYTES
    }

    /** True when there is too little free space to keep a useful library. */
    fun lowOnSpace(context: Context, store: CatalogueStore): Boolean {
        val used = store.load().entries.sumOf { it.byteCount }
        return storageBudget(context, used) < MIN_USEFUL_BUDGET_BYTES
    }

    /**
     * A refresh is due when the image settings changed, after [STALE_AFTER_MILLIS], or after
     * [EMPTY_RETRY_AFTER_MILLIS] while nothing in the library can be shown with the current
     * settings and [enabledProviders].
     */
    internal fun isStale(
        catalogue: Catalogue,
        now: Long,
        selection: ImageSelection,
        enabledProviders: Set<AssetProvider>
    ): Boolean {
        val sinceAttempt = now - catalogue.lastAttemptAtEpochMillis
        if (sinceAttempt < 0) return true
        if (catalogue.selectionFingerprint != selection.fingerprint) return true
        val showable = selection.eligible(catalogue.entries.filter { it.provider in enabledProviders })
        return if (showable.isEmpty()) {
            sinceAttempt >= EMPTY_RETRY_AFTER_MILLIS
        } else {
            sinceAttempt >= STALE_AFTER_MILLIS
        }
    }

    /**
     * Starts a refresh if one is due (or [force] is set). Returns false if nothing was started.
     * A forced request made while a refresh is running queues one more run, so settings changed
     * mid-refresh are always applied.
     */
    fun requestRefresh(
        context: Context,
        force: Boolean = false,
        onFinished: ((RefreshResult?) -> Unit)? = null
    ): Boolean {
        val appContext = context.applicationContext
        val store = store(appContext)
        val settings = CosmosSettings(appContext)
        if (!force && !isStale(store.load(), System.currentTimeMillis(), settings.selection, settings.enabledProviders)) {
            return false
        }
        if (!gate.request(force)) return false

        try {
            executor.execute {
                var result: RefreshResult?
                try {
                    do {
                        result = runOnce(appContext, store)
                    } while (gate.finishPass())
                } catch (error: Throwable) {
                    gate.abort()
                    Log.e(TAG, "Refresh worker terminated", error)
                    throw error
                }
                if (onFinished != null) mainHandler.post { onFinished(result) }
            }
        } catch (error: RejectedExecutionException) {
            gate.abort()
            throw error
        }
        return true
    }

    private fun runOnce(appContext: Context, store: CatalogueStore): RefreshResult? {
        val settings = CosmosSettings(appContext)
        return try {
            CatalogueRefresher(
                fetcher = HttpsFetcher(userAgent(appContext)),
                store = store,
                inspector = ::inspectImage,
                territory = deviceTerritory(),
                adapters = Sources.adapters(settings.enabledProviders),
                selection = settings.selection,
                budgetFor = { used -> storageBudget(appContext, used) }
            ).refresh().also { Log.i(TAG, it.summary) }
        } catch (error: Exception) {
            Log.e(TAG, "Refresh failed", error)
            runCatching {
                store.update {
                    it.copy(
                        lastAttemptAtEpochMillis = System.currentTimeMillis(),
                        lastSummary = "Refresh failed: ${error.message ?: error.javaClass.simpleName}"
                    )
                }
            }
            null
        }
    }

    private fun deviceTerritory(): String =
        Locale.getDefault().country.uppercase(Locale.ROOT).takeIf { it.length == 2 } ?: "US"

    private fun userAgent(context: Context): String {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
        return "Cosmos/$version (Android TV screensaver; +https://github.com/Zordrak/cosmos)"
    }

    private fun inspectImage(file: File): ImageDimensions? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        if (options.outMimeType != "image/jpeg" && options.outMimeType != "image/png") return null
        return ImageDimensions(options.outWidth, options.outHeight)
    }
}
