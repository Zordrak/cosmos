package com.cosmos.screensaver.settings

/** The screensaver service this app registers; only this exact component counts as Cosmos being selected. */
internal const val DREAM_SERVICE_CLASS = "com.cosmos.screensaver.screensaver.CosmosDreamService"

/** Whether Cosmos is the TV's selected screensaver, as far as this app can tell. */
internal enum class ScreensaverSelection {
    COSMOS,
    OTHER,
    NONE,

    /** Cosmos is selected but the TV's screensavers are switched off, so it will never start. */
    DISABLED,

    /** The setting could not be read; this is neither "not selected" nor a verified selection. */
    UNKNOWN;

    companion object {
        /**
         * [components] is the raw `screensaver_components` value: a comma-separated list of
         * `package/class`, where the class may be abbreviated with a leading dot. [enabled] is the raw
         * `screensaver_enabled` value (0 off, 1 on) or null if it cannot be read, which adds no claim.
         */
        fun parse(components: String?, packageName: String, enabled: Int? = null): ScreensaverSelection {
            if (components == null) return UNKNOWN
            val entries = components.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (entries.isEmpty()) return NONE
            if (entries.none { isCosmos(it, packageName) }) return OTHER
            return if (enabled == 0) DISABLED else COSMOS
        }

        private fun isCosmos(entry: String, packageName: String): Boolean {
            val separator = entry.indexOf('/')
            if (separator <= 0) return false
            val entryPackage = entry.substring(0, separator)
            val rawClass = entry.substring(separator + 1)
            val className = if (rawClass.startsWith('.')) entryPackage + rawClass else rawClass
            return entryPackage == packageName && className == DREAM_SERVICE_CLASS
        }
    }
}
