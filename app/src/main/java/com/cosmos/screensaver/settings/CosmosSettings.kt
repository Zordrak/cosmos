package com.cosmos.screensaver.settings

import android.content.Context
import com.cosmos.screensaver.source.DjangoplicitySite

/** Local-only preferences. Nothing here leaves the device. */
internal class CosmosSettings(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("cosmos_settings", Context.MODE_PRIVATE)

    var intervalSeconds: Int
        get() = preferences.getInt(KEY_INTERVAL, DEFAULT_INTERVAL_SECONDS)
            .takeIf { it in INTERVAL_CHOICES_SECONDS } ?: DEFAULT_INTERVAL_SECONDS
        set(value) {
            require(value in INTERVAL_CHOICES_SECONDS)
            preferences.edit().putInt(KEY_INTERVAL, value).apply()
        }

    fun isEnabled(site: DjangoplicitySite) = preferences.getBoolean(siteKey(site), true)

    fun setEnabled(site: DjangoplicitySite, enabled: Boolean) {
        preferences.edit().putBoolean(siteKey(site), enabled).apply()
    }

    val enabledSites: Set<DjangoplicitySite>
        get() = DjangoplicitySite.entries.filter(::isEnabled).toSet()

    private fun siteKey(site: DjangoplicitySite) = "source_${site.name.lowercase()}"

    companion object {
        private const val KEY_INTERVAL = "interval_seconds"
        const val DEFAULT_INTERVAL_SECONDS = 60
        val INTERVAL_CHOICES_SECONDS = listOf(30, 60, 120, 300, 600)

        fun nextInterval(current: Int): Int {
            val index = INTERVAL_CHOICES_SECONDS.indexOf(current)
            return INTERVAL_CHOICES_SECONDS[(index + 1) % INTERVAL_CHOICES_SECONDS.size]
        }
    }
}
