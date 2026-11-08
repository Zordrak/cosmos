package com.cosmos.screensaver.settings

import android.content.Context
import android.content.SharedPreferences
import com.cosmos.screensaver.rights.AssetProvider

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
        get() = preferences.enumValue(KEY_DISPLAY_MODE, DEFAULT_DISPLAY_MODE)
        set(value) = preferences.edit().putString(KEY_DISPLAY_MODE, value.name).apply()

    var captionMode: CaptionMode
        get() = preferences.enumValue(KEY_CAPTION_MODE, DEFAULT_CAPTION_MODE)
        set(value) = preferences.edit().putString(KEY_CAPTION_MODE, value.name).apply()

    var quality: QualityPreference
        get() = preferences.enumValue(KEY_QUALITY, QualityPreference.UHD_ONLY)
        set(value) = preferences.edit().putString(KEY_QUALITY, value.name).apply()

    var licenceMode: LicenceMode
        get() = preferences.enumValue(KEY_LICENCE_MODE, LicenceMode.ANY_AVAILABLE)
        set(value) = preferences.edit().putString(KEY_LICENCE_MODE, value.name).apply()

    val selection: ImageSelection get() = ImageSelection(licenceMode, quality)

    fun isEnabled(provider: AssetProvider) = preferences.getBoolean(sourceKey(provider), true)

    fun setEnabled(provider: AssetProvider, enabled: Boolean) {
        preferences.edit().putBoolean(sourceKey(provider), enabled).apply()
    }

    val enabledProviders: Set<AssetProvider>
        get() = AssetProvider.entries.filter(::isEnabled).toSet()

    /** The preset matching the enabled sources, or [CollectionPreset.CUSTOM]. */
    var collection: CollectionPreset
        get() = collectionFor(enabledProviders)
        set(value) {
            val providers = value.providers ?: return
            val editor = preferences.edit()
            AssetProvider.entries.forEach { editor.putBoolean(sourceKey(it), it in providers) }
            editor.apply()
        }

    private fun sourceKey(provider: AssetProvider) = "source_${provider.name.lowercase()}"

    companion object {
        private const val KEY_INTERVAL = "interval_seconds"
        private const val KEY_DISPLAY_MODE = "display_mode"
        private const val KEY_CAPTION_MODE = "caption_mode"
        private const val KEY_QUALITY = "quality"
        private const val KEY_LICENCE_MODE = "licence_mode"
        const val DEFAULT_INTERVAL_SECONDS = 60
        val DEFAULT_DISPLAY_MODE = DisplayMode.FILL
        val DEFAULT_CAPTION_MODE = CaptionMode.SUBTLE
        val INTERVAL_CHOICES_SECONDS = listOf(15, 30, 60, 120, 300, 600, 1800)

        fun collectionFor(enabled: Set<AssetProvider>): CollectionPreset =
            CollectionPreset.entries.firstOrNull { it.providers == enabled } ?: CollectionPreset.CUSTOM
    }
}
private inline fun <reified T : Enum<T>> SharedPreferences.enumValue(key: String, default: T): T =
    parseEnumPreference(getString(key, null), default)

internal inline fun <reified T : Enum<T>> parseEnumPreference(stored: String?, default: T): T =
    stored?.let { value -> enumValues<T>().firstOrNull { it.name == value } } ?: default

/** Returns the item [step] places after [current], wrapping around. */
internal fun <T> List<T>.cycle(current: T, step: Int): T {
    val index = indexOf(current).coerceAtLeast(0)
    return this[Math.floorMod(index + step, size)]
}