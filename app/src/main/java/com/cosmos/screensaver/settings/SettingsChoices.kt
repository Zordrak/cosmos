package com.cosmos.screensaver.settings

/**
 * The picture-quality choices offered to the user, in order. Four levels are offered; a stored
 * HD/SD value is still honoured, so it appears as an extra, currently selected choice and is never
 * rewritten unless the user picks something else.
 */
internal fun qualityChoices(current: QualityPreference): List<QualityPreference> {
    val offered = OFFERED_QUALITY
    if (current in offered) return offered
    return QualityPreference.entries.filter { it in offered || it == current }
}

private val OFFERED_QUALITY = listOf(
    QualityPreference.UHD_ONLY,
    QualityPreference.UHD_PREFERRED,
    QualityPreference.FHD_OR_BETTER,
    QualityPreference.ANY
)

/** The collection presets listed in the picker; Custom is always last and opens the Sources page. */
internal val COLLECTION_CHOICES: List<CollectionPreset> = CollectionPreset.entries

/** Presets that Left/Right cycles through: only those that can be applied directly. */
internal val COLLECTION_CYCLE: List<CollectionPreset> = CollectionPreset.entries.filter { it.providers != null }

/**
 * Applies a collection choice. Returns true when the caller should open the Sources page instead
 * (Custom has no sources of its own, so it must never silently do nothing).
 */
internal fun applyCollection(settings: CosmosSettings, preset: CollectionPreset): Boolean {
    if (preset.providers == null) return true
    settings.collection = preset
    return false
}
