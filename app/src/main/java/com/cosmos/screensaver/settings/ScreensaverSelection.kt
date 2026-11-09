package com.cosmos.screensaver.settings

/** Whether Cosmos is the TV's selected screensaver, as far as this app can tell. */
internal enum class ScreensaverSelection {
    COSMOS,
    OTHER,
    NONE,

    /** The setting could not be read; this is neither "not selected" nor a verified selection. */
    UNKNOWN;

    companion object {
        /** [components] is the raw `screensaver_components` value: a comma-separated list of `package/class`. */
        fun parse(components: String?, packageName: String): ScreensaverSelection {
            if (components == null) return UNKNOWN
            val entries = components.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (entries.isEmpty()) return NONE
            return if (entries.any { it.substringBefore('/') == packageName }) COSMOS else OTHER
        }
    }
}
