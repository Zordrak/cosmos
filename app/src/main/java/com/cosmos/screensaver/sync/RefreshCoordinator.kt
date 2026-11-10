package com.cosmos.screensaver.sync

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.util.Log
import com.cosmos.screensaver.catalogue.Catalogue
import com.cosmos.screensaver.catalogue.CatalogueStore
import com.cosmos.screensaver.catalogue.UserState
import com.cosmos.screensaver.catalogue.UserStateStore
import com.cosmos.screensaver.rights.AssetProvider
import com.cosmos.screensaver.settings.CosmosSettings
import com.cosmos.screensaver.settings.DownloadMode
import com.cosmos.screensaver.settings.ImageSelection
import com.cosmos.screensaver.settings.SelectionSnapshot
import com.cosmos.screensaver.source.HttpsFetcher
import com.cosmos.screensaver.source.ListingCache
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
    /** A pass that stopped with room left because a source could not be read is retried this soon. */
    private const val INTERRUPTED_RETRY_AFTER_MILLIS = 30L * 60 * 1000
    private const val MIN_USEFUL_BUDGET_BYTES = 50L * 1024 * 1024

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "cosmos-refresh").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }
    private val gate = RefreshGate()
    private val deferLock = Any()
    private var deferredReason: RefreshReason? = null
    private val undoListenerRegistered = java.util.concurrent.atomic.AtomicBoolean(false)
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** How connection limits are read; replaced in tests. */
    @Volatile
    internal var probeFor: (Context) -> NetworkProbe = { AndroidNetworkProbe(it) }

    val isRunning: Boolean get() = gate.isRunning

    /** What the TV says about the connection now. Reads a system service, so call off the main thread when it matters. */
    fun meteredness(context: Context): Meteredness = probeFor(context.applicationContext).meteredness()

    /**
     * Whether a refresh for [reason] may save pictures under the current download mode. Only the
     * mode that needs it consults the connection, so Automatically never touches the network service.
     */
    fun policyAllows(context: Context, reason: RefreshReason): Boolean {
        val mode = CosmosSettings(context.applicationContext).downloadMode
        val checkConnection = mode == DownloadMode.UNMETERED_ONLY && reason != RefreshReason.EXPLICIT_SEARCH
        val meteredness = if (checkConnection) meteredness(context) else Meteredness.UNKNOWN_NOW
        return savingAllowed(mode, meteredness, reason)
    }

    /**
     * The saved pictures live in app data (not the cache directory): they are user data -
     * favourites, the blocklist and a multi-GB download - and "Clear cache" or system cache
     * reclaim must not remove them. Waits for the one-time migration from the old cache location.
     */
    fun store(context: Context): CatalogueStore = LibraryStorage.store(context)

    /** What the device reports for the storage holding the saved pictures, or Unknown if the probe fails. */
    fun measure(context: Context): StorageMeasurement = try {
        val stat = StatFs(context.applicationContext.filesDir.path)
        StorageMeasurement.Known(stat.totalBytes, stat.availableBytes)
    } catch (error: IllegalArgumentException) {
        Log.w(TAG, "Unable to read free space", error)
        StorageMeasurement.Unknown
    }

    /**
     * The budget for the saved pictures given the bytes Cosmos could give back ([ownedBytes]): the
     * owner's chosen size (or Automatic), never so much that free space drops below the reserve.
     */
    fun budget(context: Context, ownedBytes: Long): CacheBudget =
        CacheBudget.compute(
            measure(context), CosmosSettings(context).cacheLimitBytes,
            ownedBytes + TrashBin.of(context).bytesOnDisk()
        )

    /** True when there is too little free space to keep a useful library. Unknown is not "low". */
    fun lowOnSpace(context: Context, store: CatalogueStore): Boolean =
        lowOnSpace(context, SelectionSnapshot.physicalBytes(store.load().entries))

    fun lowOnSpace(context: Context, usedBytes: Long): Boolean {
        val budget = budget(context, usedBytes)
        return budget.known && budget.budgetBytes < MIN_USEFUL_BUDGET_BYTES
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
        enabledProviders: Set<AssetProvider>,
        state: UserState = UserState()
    ): Boolean {
        val sinceAttempt = now - catalogue.lastAttemptAtEpochMillis
        if (sinceAttempt < 0) return true
        if (catalogue.selectionFingerprint != CatalogueRefresher.fingerprintOf(selection, enabledProviders)) return true
        if (catalogue.moreToFetch && sinceAttempt >= INTERRUPTED_RETRY_AFTER_MILLIS) return true
        val showable = SelectionSnapshot.build(catalogue.entries, selection, enabledProviders, state).eligible
        return if (showable.isEmpty()) {
            sinceAttempt >= EMPTY_RETRY_AFTER_MILLIS
        } else {
            sinceAttempt >= STALE_AFTER_MILLIS
        }
    }

    /**
     * Starts a refresh for [reason] if one is due and allowed. Returns false if nothing was started.
     * Only [RefreshReason.AUTO] waits for staleness; every reason waits for Undo and respects the
     * download mode, except an explicit search, which overrides the mode for its one pass. A settings
     * change or search made while a refresh runs queues one more pass, re-checked when it starts.
     */
    fun requestRefresh(
        context: Context,
        reason: RefreshReason = RefreshReason.AUTO,
        onFinished: ((RefreshResult?) -> Unit)? = null
    ): Boolean {
        val appContext = context.applicationContext
        val store = store(appContext)
        val trash = TrashBin.of(appContext)
        trash.recoverAndExpire()
        if (trash.pending() != null) {
            deferForUndo(appContext, trash, reason)
            return false
        }
        val settings = CosmosSettings(appContext)
        val staleSource = if (live(appContext, store)) ImageStreamer.onlineStore(appContext) else store
        if (reason == RefreshReason.AUTO && !isStale(
                staleSource.load(), System.currentTimeMillis(), settings.selection, settings.enabledProviders,
                LibraryStorage.userState(appContext).load()
            )
        ) {
            return false
        }
        if (!policyAllows(appContext, reason)) return false
        if (!gate.request(reason)) return false

        try {
            executor.execute {
                var result: RefreshResult?
                try {
                    result = gate.drive(reason, { policyAllows(appContext, it) }) { pass -> runOnce(appContext, store, pass) }
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

    /**
     * Saving waits while a removal can still be undone: the removed files are still on disk and
     * must not be refilled. The strongest request made meanwhile is remembered (so a search is not
     * lost) and resumed, once, when the change settles; it is then re-checked against the policy.
     */
    private fun deferForUndo(appContext: Context, trash: TrashBin, reason: RefreshReason) {
        synchronized(deferLock) { deferredReason = deferredReason?.let { maxOf(it, reason) } ?: reason }
        if (!undoListenerRegistered.compareAndSet(false, true)) return
        trash.addSettledListener {
            val resume = synchronized(deferLock) { deferredReason.also { deferredReason = null } }
            if (resume != null) requestRefresh(appContext, resume)
        }
    }

    internal fun createRefresher(
        context: Context,
        store: CatalogueStore,
        userState: UserStateStore,
        budgetFor: (usedBytes: Long) -> CacheBudget,
        startIndex: Int = 0,
        progress: ProgressTracker = RefreshProgress.saving,
        allowSaving: () -> Boolean = { true },
        indexOnly: Boolean = false
    ): CatalogueRefresher {
        val settings = CosmosSettings(context)
        val trash = TrashBin.of(context)
        val startedAt = ElapsedRealtimeClock.elapsedMillis()
        return CatalogueRefresher(
            fetcher = HttpsFetcher(userAgent(context), progress.observer),
            store = store,
            userState = userState,
            inspector = ::inspectImage,
            territory = deviceTerritory(),
            adapters = Sources.adapters(
                settings.enabledProviders,
                listings = ListingCache(java.io.File(context.cacheDir, "listings.json"))
            ),
            selection = settings.selection,
            budgetFor = budgetFor,
            startIndex = startIndex,
            isStashed = { key, sha -> trash.isStashed(key, sha) || RemovalFence.removedSince(key, sha, startedAt) },
            holdAdmissions = { trash.pending() != null },
            progress = progress,
            allowSaving = allowSaving,
            indexOnly = indexOnly
        )
    }

    /**
     * True when pictures are played without saving: chosen in settings, or forced by too little
     * free space. Refreshes then build the online index instead of the library.
     */
    fun live(context: Context, store: CatalogueStore): Boolean =
        CosmosSettings(context).streamOnly || lowOnSpace(context, store)

    /** Bytes available on the device's storage right now, or null when they cannot be read. */
    fun freeBytes(context: Context): Long? =
        (measure(context) as? StorageMeasurement.Known)?.availableBytes

    private fun runOnce(appContext: Context, store: CatalogueStore, reason: RefreshReason): RefreshResult? {
        val settings = CosmosSettings(appContext)
        val cursor = settings.refreshCursor
        val indexing = live(appContext, store)
        val target = if (indexing) ImageStreamer.onlineStore(appContext) else store
        return try {
            createRefresher(
                appContext, target, LibraryStorage.userState(appContext),
                { used -> if (indexing) CacheBudget.fixed(Long.MAX_VALUE) else budget(appContext, used) }, cursor,
                allowSaving = { policyAllows(appContext, reason) },
                indexOnly = indexing
            ).refresh().also {
                    settings.refreshCursor = cursor + 1
                    Log.i(TAG, (if (indexing) "Online index: " else "") + it.summary)
                }
        } catch (error: Exception) {
            Log.e(TAG, "Refresh failed", error)
            runCatching {
                target.update {
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
