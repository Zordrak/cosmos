package com.cosmos.screensaver.settings

import com.cosmos.screensaver.catalogue.CatalogueEntry
import com.cosmos.screensaver.rights.AssetProvider
import com.cosmos.screensaver.rights.LicenceValidator
import com.cosmos.screensaver.rights.RightsBasis

/** How an image is placed on a screen whose aspect ratio differs from the image's. */
internal enum class DisplayMode {
    /** Covers the whole screen without distortion, cropping whichever edges overflow. */
    FILL,

    /** Shows the whole image without distortion, leaving black bars. */
    FIT,

    /** Covers the whole screen and distorts the aspect ratio. Only ever used when chosen. */
    STRETCH,

    /** One image pixel per physical screen pixel, centred and cropped to the screen. */
    ORIGINAL,

    /** Fills when that crops little of the image, otherwise fits. */
    AUTO
}

internal enum class CaptionMode { SUBTLE, DETAILED, OFF }

/** How the next picture is chosen. */
internal enum class OrderMode {
    /** Recency-aware: varies pictures, favourites and "More" sources recur sooner, new pictures arrive soon. */
    FAIR,

    /** Independent uniform choice; only an immediate repeat is avoided. */
    RANDOM,

    /** The same fixed order every time (source, then title). */
    SEQUENTIAL
}

/** A relative per-picture weight for a source; applies to [OrderMode.FAIR] only. */
internal enum class SourceWeight { LESS, NORMAL, MORE }

internal enum class LicenceMode {
    /** Any publicly available image from an enabled source. */
    ANY_AVAILABLE,

    /** Only images verified as CC BY 4.0 or as NASA public-domain works. */
    OPEN_LICENCE_ONLY
}

/** Which sources are switched on, as a named group. */
internal enum class CollectionPreset(val providers: Set<AssetProvider>?) {
    EVERYTHING(AssetProvider.entries.toSet()),
    DEEP_SPACE(setOf(AssetProvider.ESA_HUBBLE, AssetProvider.ESA_WEBB, AssetProvider.ESO, AssetProvider.NOIRLAB)),
    PICTURE_OF_THE_DAY(setOf(AssetProvider.APOD)),
    NASA_ONLY(setOf(AssetProvider.NASA, AssetProvider.APOD)),

    /** The sources were toggled individually. */
    CUSTOM(null)
}

/** A minimum resolution, applied to both dimensions without tolerance. */
internal data class Resolution(val width: Int, val height: Int) {
    fun accepts(imageWidth: Int, imageHeight: Int): Boolean =
        imageWidth >= width && imageHeight >= height

    companion object {
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
            append(";maxEdge=").append(MAX_IMAGE_LONG_EDGE)
            append(";aspect=").append(MIN_ASPECT_RATIO).append('-').append(MAX_ASPECT_RATIO)
            append(";content=").append(com.cosmos.screensaver.source.ContentFilter.POLICY_VERSION)
        }

    fun licenceAccepts(entry: CatalogueEntry): Boolean = when (licenceMode) {
        LicenceMode.ANY_AVAILABLE -> true
        LicenceMode.OPEN_LICENCE_ONLY ->
            entry.policyVersion == LicenceValidator.POLICY_VERSION &&
                (entry.rightsBasis == RightsBasis.CC_BY_4_0.name || entry.rightsBasis == RightsBasis.NASA_PUBLIC_DOMAIN.name)
    }

    fun meetsMinimum(entry: CatalogueEntry) =
        withinMaximum(entry.widthPx, entry.heightPx) &&
            (quality.minimum?.accepts(entry.widthPx, entry.heightPx) ?: true)

    /** True while fallback-quality images should be admitted and shown, given how many distinct pictures meet the minimum. */
    fun fallbackActive(distinctQualifying: Int): Boolean =
        quality.fallback != null && distinctQualifying < FALLBACK_TARGET

    /** Whether a picture that passed the licence check qualifies, given whether the fallback is in use. */
    fun qualifies(entry: CatalogueEntry, useFallback: Boolean): Boolean =
        licenceAccepts(entry) && (
            meetsMinimum(entry) ||
                (useFallback && withinMaximum(entry.widthPx, entry.heightPx) &&
                    quality.fallback?.accepts(entry.widthPx, entry.heightPx) == true)
            )
    companion object {
        /** Below this many qualifying images, [QualityPreference.UHD_PREFERRED] also uses its fallback. */
        const val FALLBACK_TARGET = 20

        /** No commercial TV exceeds 8K, so larger images only waste bandwidth. */
        const val MAX_IMAGE_LONG_EDGE = 7680

        /** Landscape only: 4:3 up to 21:9. */
        const val MIN_ASPECT_RATIO = 4.0 / 3.0
        const val MAX_ASPECT_RATIO = 21.0 / 9.0
        const val ASPECT_TOLERANCE = 0.02

        fun withinMaximum(width: Int, height: Int): Boolean =
            width > 0 && height > 0 && maxOf(width, height) <= MAX_IMAGE_LONG_EDGE

        fun suitableAspect(width: Int, height: Int): Boolean {
            if (width <= 0 || height <= 0) return false
            val ratio = width.toDouble() / height
            return ratio >= MIN_ASPECT_RATIO * (1 - ASPECT_TOLERANCE) && ratio <= MAX_ASPECT_RATIO * (1 + ASPECT_TOLERANCE)
        }
    }
}
