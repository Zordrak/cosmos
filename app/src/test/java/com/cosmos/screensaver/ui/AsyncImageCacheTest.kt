package com.cosmos.screensaver.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

class AsyncImageCacheTest {
    private class Manual : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun runAll() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    private val posted = ArrayDeque<Runnable>()
    private fun flush() { while (posted.isNotEmpty()) posted.removeFirst().run() }

    private fun cache(executor: Executor, maxBytes: Int = 100, maxJobs: Int = 32) =
        AsyncImageCache<ByteArray>(executor, { posted.addLast(it) }, { it.size }, maxBytes, maxJobs)

    @Test
    fun loadsOnceAndServesFromMemoryAfterwards() {
        val executor = Manual()
        val cache = cache(executor)
        val loaded = mutableListOf<String>()
        cache.onLoaded = { loaded += it }
        var calls = 0

        cache.request("a") { calls++; ByteArray(10) }
        cache.request("a") { calls++; ByteArray(10) }
        assertEquals(1, cache.pendingCount)
        assertNull(cache.cached("a"))
        executor.runAll(); flush()

        assertEquals(1, calls)
        assertEquals(listOf("a"), loaded)
        assertEquals(10, cache.cached("a")!!.size)
        cache.request("a") { calls++; ByteArray(10) }
        executor.runAll(); flush()
        assertEquals(1, calls)
    }

    @Test
    fun evictsLeastRecentlyUsedWhenOverTheByteBudgetButKeepsTheNewest() {
        val executor = Manual()
        val cache = cache(executor, maxBytes = 25)
        listOf("a", "b", "c").forEach { key -> cache.request(key) { ByteArray(10) } }
        executor.runAll(); flush()

        assertNull(cache.cached("a"))
        assertTrue(cache.cached("b") != null && cache.cached("c") != null)
        assertTrue(cache.cachedBytes <= 25)

        val huge = cache(executor, maxBytes = 5)
        huge.request("x") { ByteArray(10) }
        executor.runAll(); flush()
        assertEquals(10, huge.cached("x")!!.size)
    }

    @Test
    fun aFastScrollCancelsTheOldestRequestsRatherThanQueueingForever() {
        val executor = Manual()
        val cache = cache(executor, maxJobs = 2)
        val ran = mutableListOf<String>()
        listOf("a", "b", "c").forEach { key -> cache.request(key) { ran += key; ByteArray(1) } }

        assertEquals(2, cache.pendingCount)
        executor.runAll(); flush()

        assertEquals(listOf("b", "c"), ran)
        assertNull(cache.cached("a"))
        assertTrue(cache.cached("b") != null)
    }

    @Test
    fun failuresAndOutOfMemoryAreRememberedAndNeverRetried() {
        val executor = Manual()
        val cache = cache(executor)
        var calls = 0
        cache.request("bad") { calls++; throw OutOfMemoryError() }
        cache.request("null") { calls++; null }
        cache.request("io") { calls++; throw java.io.IOException("x") }
        executor.runAll(); flush()

        assertTrue(cache.hasFailed("bad") && cache.hasFailed("null") && cache.hasFailed("io"))
        cache.request("bad") { calls++; ByteArray(1) }
        executor.runAll(); flush()
        assertEquals(3, calls)
        assertNull(cache.cached("bad"))
    }

    @Test
    fun forgetCancelsAndAllowsAFreshLoad() {
        val executor = Manual()
        val cache = cache(executor)
        val loaded = mutableListOf<String>()
        cache.onLoaded = { loaded += it }
        cache.request("a") { ByteArray(1) }
        cache.forget("a")
        executor.runAll(); flush()
        assertTrue(loaded.isEmpty())

        val replacement = ByteArray(2)
        cache.request("a") { replacement }
        executor.runAll(); flush()
        assertSame(replacement, cache.cached("a"))
    }

    @Test
    fun closeStopsEverythingAndLateResultsAreIgnored() {
        val executor = Manual()
        val cache = cache(executor)
        val loaded = mutableListOf<String>()
        cache.onLoaded = { loaded += it }
        cache.request("a") { ByteArray(1) }
        executor.runAll()
        cache.close()
        flush()

        assertTrue(loaded.isEmpty())
        assertNull(cache.cached("a"))
        cache.request("b") { ByteArray(1) }
        assertFalse(executor.tasks.isNotEmpty())
        assertEquals(0, cache.pendingCount)
    }
}
