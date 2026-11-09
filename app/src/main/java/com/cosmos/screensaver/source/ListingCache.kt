package com.cosmos.screensaver.source

import org.json.JSONObject
import java.io.File
import java.io.IOException

/** One picture named on a category listing page, with the size the listing declares for it. */
internal data class ListingItem(val id: String, val width: Int, val height: Int)

/** What one category listing page said when it was last read. */
internal data class ListingPage(
    val fetchedAtEpochMillis: Long,
    /** How many pages the category had when this page was read; only trusted from page 1. */
    val lastPage: Int,
    val items: List<ListingItem>
)

/**
 * Remembers which pictures each category listing page named, so the crawl can walk pages it has
 * already read without touching the network. It is a pure cache of public listings: losing it
 * costs a few page downloads and nothing else, so it lives in the app's cache directory and
 * every failure here is swallowed.
 */
internal class ListingCache(private val file: File?) {
    private var pages: MutableMap<String, ListingPage>? = null
    private var dirty = false

    @Synchronized
    fun get(key: String): ListingPage? = loaded()[key]

    @Synchronized
    fun put(key: String, page: ListingPage) {
        loaded()[key] = page
        dirty = true
    }

    /** Writes the cache if anything changed. Never throws. */
    @Synchronized
    fun flush() {
        val target = file ?: return
        val current = pages ?: return
        if (!dirty) return
        try {
            val root = JSONObject()
            current.forEach { (key, page) ->
                root.put(
                    key,
                    JSONObject()
                        .put("t", page.fetchedAtEpochMillis)
                        .put("n", page.lastPage)
                        .put("i", page.items.joinToString(";") { "${it.id},${it.width},${it.height}" })
                )
            }
            target.parentFile?.mkdirs()
            val temporary = File(target.parentFile, "${target.name}.tmp")
            temporary.writeText(root.toString(), Charsets.UTF_8)
            if (target.exists()) target.delete()
            if (temporary.renameTo(target)) dirty = false else temporary.delete()
        } catch (error: IOException) {
            // The cache is rebuilt from the network next time.
        }
    }

    private fun loaded(): MutableMap<String, ListingPage> {
        pages?.let { return it }
        val result = mutableMapOf<String, ListingPage>()
        val source = file
        if (source != null && source.isFile) {
            try {
                val root = JSONObject(source.readText(Charsets.UTF_8))
                for (key in root.keys()) {
                    val value = root.optJSONObject(key) ?: continue
                    val items = value.optString("i").split(';').mapNotNull { part ->
                        val fields = part.split(',')
                        val width = fields.getOrNull(1)?.toIntOrNull()
                        val height = fields.getOrNull(2)?.toIntOrNull()
                        if (fields.size == 3 && fields[0].isNotEmpty() && width != null && height != null) {
                            ListingItem(fields[0], width, height)
                        } else {
                            null
                        }
                    }
                    result[key] = ListingPage(value.optLong("t"), value.optInt("n", 1).coerceAtLeast(1), items)
                }
            } catch (error: Exception) {
                result.clear()
            }
        }
        pages = result
        return result
    }

    companion object {
        /** No category is crawled deeper than this, whatever it advertises. */
        const val MAX_PAGES = 120
    }
}
