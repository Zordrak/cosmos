package com.cosmos.screensaver.settings

import android.content.Context
import android.content.SharedPreferences
import com.cosmos.screensaver.rights.AssetProvider
import java.util.Locale

/** Local-only preferences. Nothing here leaves the device. */
internal class CosmosSettings(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    )

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

    var captionSize: CaptionSize
        get() = preferences.enumValue(KEY_CAPTION_SIZE, DEFAULT_CAPTION_SIZE)
        set(value) = preferences.edit().putString(KEY_CAPTION_SIZE, value.name).apply()

    var quality: QualityPreference
        get() = preferences.enumValue(KEY_QUALITY, DEFAULT_QUALITY)
        set(value) = preferences.edit().putString(KEY_QUALITY, value.name).apply()

    var licenceMode: LicenceMode
        get() = preferences.enumValue(KEY_LICENCE_MODE, DEFAULT_LICENCE_MODE)
        set(value) = preferences.edit().putString(KEY_LICENCE_MODE, value.name).apply()

    var orderMode: OrderMode
        get() = preferences.enumValue(KEY_ORDER_MODE, DEFAULT_ORDER_MODE)
        set(value) = preferences.edit().putString(KEY_ORDER_MODE, value.name).apply()

    fun sourceWeight(provider: AssetProvider): SourceWeight =
        parseEnumPreference(preferences.getString(weightKey(provider), null), SourceWeight.NORMAL)

    fun setSourceWeight(provider: AssetProvider, weight: SourceWeight) {
        val editor = preferences.edit()
        if (weight == SourceWeight.NORMAL) editor.remove(weightKey(provider)) else editor.putString(weightKey(provider), weight.name)
        editor.apply()
    }

    /** Streams every image instead of using the cache; the cache is left untouched. */
    var streamOnly: Boolean
        get() = preferences.getBoolean(KEY_STREAM_ONLY, false)
        set(value) = preferences.edit().putBoolean(KEY_STREAM_ONLY, value).apply()

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

    /**
     * Puts every viewing choice back to its default: what is shown, how it is shown and Play
     * without saving. Deliberately leaves anything that is not a viewing choice (the space limit,
     * download mode and similar) alone, and never touches saved pictures, favourites or blocks.
     */
    fun resetViewingSettings() {
        val editor = preferences.edit()
        VIEWING_KEYS.forEach { editor.remove(it) }
        AssetProvider.entries.forEach {
            editor.remove(sourceKey(it))
            editor.remove(weightKey(it))
        }
        editor.apply()
    }

    private fun sourceKey(provider: AssetProvider) = "source_${provider.name.lowercase(Locale.ROOT)}"

    private fun weightKey(provider: AssetProvider) = "weight_${provider.name.lowercase(Locale.ROOT)}"

    companion object {
        const val PREFERENCES_NAME = "cosmos_settings"
        private const val KEY_INTERVAL = "interval_seconds"
        private const val KEY_DISPLAY_MODE = "display_mode"
        private const val KEY_CAPTION_MODE = "caption_mode"
        private const val KEY_CAPTION_SIZE = "caption_size"
        val DEFAULT_CAPTION_SIZE = CaptionSize.STANDARD
        private const val KEY_QUALITY = "quality"
        private const val KEY_LICENCE_MODE = "licence_mode"
        private const val KEY_STREAM_ONLY = "stream_only"
        private const val KEY_ORDER_MODE = "order_mode"
        val DEFAULT_ORDER_MODE = OrderMode.FAIR
        const val DEFAULT_INTERVAL_SECONDS = 60
        val DEFAULT_DISPLAY_MODE = DisplayMode.FILL
        val DEFAULT_CAPTION_MODE = CaptionMode.SUBTLE
        val DEFAULT_QUALITY = QualityPreference.UHD_ONLY
        val DEFAULT_LICENCE_MODE = LicenceMode.ANY_AVAILABLE
        val DEFAULT_COLLECTION = CollectionPreset.EVERYTHING
        val INTERVAL_CHOICES_SECONDS = listOf(15, 30, 60, 120, 300, 600, 1800)

        /**
         * Every preference key that is a viewing choice. Later steps that add a viewing choice
         * (order, source weights, credit size) MUST add its key here so Reset covers it.
         */
        private val VIEWING_KEYS = listOf(
            KEY_INTERVAL, KEY_DISPLAY_MODE, KEY_CAPTION_MODE, KEY_CAPTION_SIZE, KEY_QUALITY, KEY_LICENCE_MODE,
            KEY_STREAM_ONLY, KEY_ORDER_MODE
        )

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