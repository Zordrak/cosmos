package com.cosmos.screensaver.ui

import java.util.concurrent.Executor

/**
 * Loads pictures off the main thread for the Saved pictures page and keeps a small, byte-bounded
 * memory cache. It is generic over the image type and takes its executor, main-thread poster and
 * size function, so tests run it deterministically without Android.
 *
 * Everything except the loader itself must be called on the main thread. A new request never
 * waits for older ones: past [maxJobs] outstanding requests the OLDEST is cancelled (a fast
 * scroll leaves the rows the owner has already passed). Failures (including out of memory) are
 * remembered so a broken picture shows its placeholder instead of being retried forever. Images
 * are never recycled by this class: a view may still be drawing one.
 */
internal class AsyncImageCache<T : Any>(
    private val executor: Executor,
    private val post: (Runnable) -> Unit,
    private val sizeOf: (T) -> Int,
    private val maxBytes: Int,
    private val maxJobs: Int = 32
) {
    private class Job { @Volatile var cancelled = false }

    private val cache = LinkedHashMap<String, T>(16, 0.75f, true)
    private val jobs = LinkedHashMap<String, Job>()
    private val failed = LinkedHashSet<String>()
    private var cacheBytes = 0
    private var closed = false

    /** Called on the main thread when [key] finished loading (or failed); redraw the views that show it. */
    var onLoaded: (String) -> Unit = {}

    val cachedBytes: Int get() = cacheBytes
    val pendingCount: Int get() = jobs.size

    fun cached(key: String): T? = cache[key]

    fun hasFailed(key: String): Boolean = key in failed

    /** Starts loading [key] unless it is cached, already loading or known to fail. */
    fun request(key: String, loader: () -> T?) {
        if (closed || key in cache || key in jobs || key in failed) return
        while (jobs.size >= maxJobs) {
            val oldest = jobs.entries.first()
            oldest.value.cancelled = true
            jobs.remove(oldest.key)
        }
        val job = Job()
        jobs[key] = job
        executor.execute {
            val image = if (job.cancelled) null else try {
                loader()
            } catch (error: Exception) {
                null
            } catch (error: OutOfMemoryError) {
                null
            }
            post(Runnable { finish(key, job, image) })
        }
    }

    private fun finish(key: String, job: Job, image: T?) {
        if (closed || job.cancelled) return
        if (jobs[key] === job) jobs.remove(key)
        if (image == null) {
            failed += key
            while (failed.size > MAX_FAILED) failed.remove(failed.first())
        } else {
            cache.put(key, image)?.let { cacheBytes -= sizeOf(it) }
            cacheBytes += sizeOf(image)
            trim()
        }
        onLoaded(key)
    }

    private fun trim() {
        val iterator = cache.entries.iterator()
        while (cacheBytes > maxBytes && cache.size > 1 && iterator.hasNext()) {
            val oldest = iterator.next()
            cacheBytes -= sizeOf(oldest.value)
            iterator.remove()
        }
    }

    /** Forgets everything for [key], e.g. after the picture was removed. */
    fun forget(key: String) {
        jobs.remove(key)?.cancelled = true
        failed.remove(key)
        cache.remove(key)?.let { cacheBytes -= sizeOf(it) }
    }

    fun close() {
        closed = true
        jobs.values.forEach { it.cancelled = true }
        jobs.clear()
        cache.clear()
        cacheBytes = 0
    }

    private companion object {
        const val MAX_FAILED = 256
    }
}
