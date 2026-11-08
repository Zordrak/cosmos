package com.cosmos.screensaver.settings

import android.content.Context
import android.content.SharedPreferences
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

    var displayMode: DisplayMode
        get() = preferences.enumValue(KEY_DISPLAY_MODE, DisplayMode.FILL)
        set(value) = preferences.edit().putString(KEY_DISPLAY_MODE, value.name).apply()

    var captionMode: CaptionMode
        get() = preferences.enumValue(KEY_CAPTION_MODE, CaptionMode.SUBTLE)
        set(value) = preferences.edit().putString(KEY_CAPTION_MODE, value.name).apply()

    var quality: QualityPreference
        get() = preferences.enumValue(KEY_QUALITY, QualityPreference.UHD_ONLY)
        set(value) = preferences.edit().putString(KEY_QUALITY, value.name).apply()

    var licenceMode: LicenceMode
        get() = preferences.enumValue(KEY_LICENCE_MODE, LicenceMode.ANY_AVAILABLE)
        set(value) = preferences.edit().putString(KEY_LICENCE_MODE, value.name).apply()

    val selection: ImageSelection get() = ImageSelection(licenceMode, quality)

    fun isEnabled(site: DjangoplicitySite) = preferences.getBoolean(siteKey(site), true)

    fun setEnabled(site: DjangoplicitySite, enabled: Boolean) {
        preferences.edit().putBoolean(siteKey(site), enabled).apply()
    }

    val enabledSites: Set<DjangoplicitySite>
        get() = DjangoplicitySite.entries.filter(::isEnabled).toSet()

    private fun siteKey(site: DjangoplicitySite) = "source_${site.name.lowercase()}"

    companion object {
        private const val KEY_INTERVAL = "interval_seconds"
        private const val KEY_DISPLAY_MODE = "display_mode"
        private const val KEY_CAPTION_MODE = "caption_mode"
        private const val KEY_QUALITY = "quality"
        private const val KEY_LICENCE_MODE = "licence_mode"
        const val DEFAULT_INTERVAL_SECONDS = 60
        val INTERVAL_CHOICES_SECONDS = listOf(15, 30, 60, 120, 300, 600, 1800)
    }
}

private inline fun <reified T : Enum<T>> SharedPreferences.enumValue(key: String, default: T): T =
    getString(key, null)?.let { stored -> enumValues<T>().firstOrNull { it.name == stored } } ?: default

/** Returns the item [step] places after [current], wrapping around. */
internal fun <T> List<T>.cycle(current: T, step: Int): T {
    val index = indexOf(current).coerceAtLeast(0)
    return this[Math.floorMod(index + step, size)]
}