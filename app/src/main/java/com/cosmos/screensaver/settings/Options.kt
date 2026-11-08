package com.cosmos.screensaver.settings

import com.cosmos.screensaver.catalogue.CatalogueEntry
import com.cosmos.screensaver.rights.LicenceValidator
import com.cosmos.screensaver.rights.RightsBasis

/** How an image is placed on a screen whose aspect ratio differs from the image's. */
internal enum class DisplayMode {
    /** Covers the whole screen without distortion, cropping whichever edges overflow. */
    FILL,

    /** Shows the whole image without distortion, leaving black bars. */
    FIT,

    /** Fills when that crops little of the image, otherwise fits. */
    AUTO,

    /** One image pixel per physical screen pixel, centred and cropped to the screen. */
    ORIGINAL,

    /** Covers the whole screen and distorts the aspect ratio. Only ever used when chosen. */
    STRETCH
}

internal enum class CaptionMode { SUBTLE, DETAILED, OFF }

internal enum class LicenceMode {
    /** Personal use: any publicly available image from an enabled source. */
    ANY_AVAILABLE,

    /** Only images individually verified as CC BY 4.0, with fail-closed rights validation. */
    OPEN_LICENCE_ONLY
}

/** A minimum resolution. Images within [TOLERANCE] of it still qualify. */
internal data class Resolution(val width: Int, val height: Int) {
    fun accepts(imageWidth: Int, imageHeight: Int): Boolean =
        imageWidth >= width * (1 - TOLERANCE) && imageHeight >= height * (1 - TOLERANCE)

    companion object {
        const val TOLERANCE = 0.05
        val UHD = Resolution(3840, 2160)
        val FHD = Resolution(1920, 1080)
        val HD = Resolution(1280, 720)
        val SD = Resolution(854, 480)
    }
}

/**
 * The minimum image resolution. Both dimensions must meet it, which is what is needed for the
 * image to cover a screen of that resolution in [DisplayMode.FILL] without upscaling.
 */
internal enum class QualityPreference(val minimum: Resolution?, val fallback: Resolution? = null) {
    UHD_ONLY(Resolution.UHD),

    /** 4K images, plus 1080p images only while the library holds too few 4K ones. */
    UHD_PREFERRED(Resolution.UHD, Resolution.FHD),
    FHD_OR_BETTER(Resolution.FHD),
    HD_OR_BETTER(Resolution.HD),
    SD_OR_BETTER(Resolution.SD),
    ANY(null)
}

/** The settings that decide which images are downloaded and shown. Pure, so it is unit-tested. */
internal data class ImageSelection(
    val licenceMode: LicenceMode = LicenceMode.ANY_AVAILABLE,
    val quality: QualityPreference = QualityPreference.UHD_ONLY
) {
    /** Changes whenever a different set of images could qualify, so earlier rejections are retried. */
    val fingerprint: String
        get() = buildString {
            append("licence=").append(licenceMode.name)
            if (licenceMode == LicenceMode.OPEN_LICENCE_ONLY) append("/policy").append(LicenceValidator.POLICY_VERSION)
            append(";quality=").append(quality.name)
        }

    fun licenceAccepts(entry: CatalogueEntry): Boolean = when (licenceMode) {
        LicenceMode.ANY_AVAILABLE -> true
        LicenceMode.OPEN_LICENCE_ONLY ->
            entry.rightsBasis == RightsBasis.CC_BY_4_0.name && entry.policyVersion == LicenceValidator.POLICY_VERSION
    }

    fun meetsMinimum(entry: CatalogueEntry) = quality.minimum?.accepts(entry.widthPx, entry.heightPx) ?: true

    /** True while fallback-quality images should be admitted and shown. */
    fun fallbackActive(entries: List<CatalogueEntry>): Boolean =
        quality.fallback != null && entries.count { licenceAccepts(it) && meetsMinimum(it) } < FALLBACK_TARGET

    /** The entries that qualify under these settings. */
    fun eligible(entries: List<CatalogueEntry>): List<CatalogueEntry> {
        val licensed = entries.filter(::licenceAccepts)
        val useFallback = fallbackActive(entries)
        return licensed.filter { entry ->
            meetsMinimum(entry) || (useFallback && quality.fallback?.accepts(entry.widthPx, entry.heightPx) == true)
        }
    }

    companion object {
        /** Below this many qualifying images, [QualityPreference.UHD_PREFERRED] also uses its fallback. */
        const val FALLBACK_TARGET = 20
    }
}
