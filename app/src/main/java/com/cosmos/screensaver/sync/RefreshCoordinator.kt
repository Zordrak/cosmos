package com.cosmos.screensaver.sync

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.cosmos.screensaver.catalogue.Catalogue
import com.cosmos.screensaver.catalogue.CatalogueStore
import com.cosmos.screensaver.rights.AssetProvider
import com.cosmos.screensaver.settings.CosmosSettings
import com.cosmos.screensaver.settings.ImageSelection
import com.cosmos.screensaver.source.HttpsFetcher
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs at most one refresh at a time on a background thread. Refreshes are triggered only when
 * the app is opened, when the screensaver starts, or on request; Cosmos does not assume any
 * background scheduler runs while the TV is idle.
 */
internal object RefreshCoordinator {
    private const val TAG = "CosmosRefresh"
    private const val STALE_AFTER_MILLIS = 20L * 60 * 60 * 1000
    private const val EMPTY_RETRY_AFTER_MILLIS = 10L * 60 * 1000

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "cosmos-refresh").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }
    private val running = AtomicBoolean(false)
    private val rerunRequested = AtomicBoolean(false)
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    val isRunning: Boolean get() = running.get()

    fun store(context: Context) = CatalogueStore(context.applicationContext.filesDir)

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
        val enabledProviders = settings.enabledSites.map { it.provider }.toSet()
        if (!force && !isStale(store.load(), System.currentTimeMillis(), settings.selection, enabledProviders)) return false
        if (!running.compareAndSet(false, true)) {
            if (!force) return false
            rerunRequested.set(true)
            // The running refresh may have finished before seeing the request; if so, run it here.
            if (!running.compareAndSet(false, true)) return false
        }

        executor.execute {
            var result: RefreshResult?
            while (true) {
                do {
                    rerunRequested.set(false)
                    result = runOnce(appContext, store)
                } while (rerunRequested.get())
                running.set(false)
                // A request may have arrived between the last check and releasing the flag.
                if (!rerunRequested.get() || !running.compareAndSet(false, true)) break
            }
            if (onFinished != null) mainHandler.post { onFinished(result) }
        }
        return true
    }

    private fun runOnce(appContext: Context, store: CatalogueStore): RefreshResult? {
        val settings = CosmosSettings(appContext)
        return try {
            CatalogueRefresher(
                fetcher = HttpsFetcher(userAgent(appContext)),
                store = store,
                inspector = ::inspectJpeg,
                territory = deviceTerritory(),
                sites = settings.enabledSites,
                selection = settings.selection
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

    private fun deviceTerritory(): String? =
        Locale.getDefault().country.uppercase(Locale.ROOT).takeIf { it.length == 2 }

    private fun userAgent(context: Context): String {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
        return "Cosmos/$version (Android TV screensaver; +https://github.com/Zordrak/cosmos)"
    }

    private fun inspectJpeg(file: java.io.File): ImageDimensions? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        if (options.outMimeType != "image/jpeg") return null
        return ImageDimensions(options.outWidth, options.outHeight)
    }
}
