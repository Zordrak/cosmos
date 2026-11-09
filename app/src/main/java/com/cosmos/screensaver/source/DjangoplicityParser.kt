package com.cosmos.screensaver.source

import java.util.Locale

internal data class AssetPage(
    val id: String,
    val title: String,
    val type: String,
    val widthPx: Int?,
    val heightPx: Int?,
    /** The mandatory credit line, or blank if the page has none. */
    val credit: String,
    /** Renditions offered by the page; all are on the site's CDN. */
    val renditions: Map<Rendition, String>,
    val linksUsagePolicy: Boolean,
    val statesMandatoryCredit: Boolean,
    /** Wording in the title, caption or credit suggesting the general licence may not apply. */
    val exceptionMarkers: List<String>,
    /** Caption text from the page, used only for content screening. */
    val description: String = ""
) {
    val imageUrls: List<String> get() = Rendition.entries.mapNotNull(renditions::get)
}

internal object DjangoplicityParser {
    const val MANDATORY_CREDIT_NOTICE =
        "Crediting this image with the full credit line, in a visible way is MANDATORY, " +
            "if you want to use it without paying a fee."

    private val ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{2,63}$")
    private val NON_ASSET_PATHS = setOf(
        "archive", "viewall", "search", "potw", "potm", "iotw", "zip", "feed", "top100", "page", "list",
        "comparisons", "news", "category"
    )
    private val EXCEPTION_MARKERS = listOf(
        "\u00a9", "copyright", "all rights reserved", "permission", "courtesy", "not licensed",
        "cc by-nc", "cc by-sa", "cc by-nd", "non-commercial", "noncommercial"
    )

    fun listingIds(html: String, site: DjangoplicitySite): List<String> =
        Regex("""href="(?:https?://(?:www\.)?${Regex.escape(site.domain)})?${Regex.escape(site.pagePath)}([A-Za-z0-9_-]+)/"""")
            .findAll(html)
            .map { it.groupValues[1] }
            .filter(::isAssetId)
            .distinct()
            .toList()

    fun feedIds(xml: String, site: DjangoplicitySite): List<String> =
        Regex("""<link>\s*https?://(?:www\.)?${Regex.escape(site.domain)}${Regex.escape(site.pagePath)}([A-Za-z0-9_-]+)/\s*</link>""")
            .findAll(xml)
            .map { it.groupValues[1] }
            .filter(::isAssetId)
            .distinct()
            .toList()

    fun discoveredIds(document: String, site: DjangoplicitySite): List<String> =
        (feedIds(document, site) + listingIds(document, site)).distinct()

    fun isAssetId(id: String) = ID_PATTERN.matches(id) && id.lowercase(Locale.ROOT) !in NON_ASSET_PATHS

    fun policyIsCurrent(html: String, site: DjangoplicitySite): Boolean {
        val text = HtmlText.toPlainText(html)
        return text.contains(site.policyStatement) &&
            html.contains("creativecommons.org/licenses/by/4.0")
    }

    fun assetPage(html: String, site: DjangoplicitySite): AssetPage? {
        val id = field(html, "Id") ?: return null
        if (!isAssetId(id)) return null
        val type = field(html, "Type") ?: return null
        val titleMatch = Regex("""<h1[^>]*>([\s\S]*?)</h1>""").find(html) ?: return null
        val titleHtml = titleMatch.groupValues[1]
        val title = HtmlText.toPlainText(titleHtml).takeIf { it.isNotBlank() } ?: return null
        val creditMatch = Regex("""<div class="credit">([\s\S]*?)</div>""").find(html)
        val credit = creditMatch?.let { HtmlText.toPlainText(it.groupValues[1]) }.orEmpty()

        val size = field(html, "Size")?.let { Regex("""(\d+)\s*x\s*(\d+)\s*px""").find(it) }
        val width = size?.groupValues?.get(1)?.toIntOrNull()
        val height = size?.groupValues?.get(2)?.toIntOrNull()

        val renditions = Rendition.entries.mapNotNull { rendition ->
            val url = site.renditionUrl(rendition, id)
            if (html.contains(url)) rendition to url else null
        }.toMap()

        val titleStart = titleMatch.range.first
        val captionAndCredit = when {
            creditMatch == null -> titleMatch.value
            titleStart < creditMatch.range.first -> html.substring(titleStart, creditMatch.range.last + 1)
            else -> titleMatch.value + creditMatch.value
        }
        val captionText = HtmlText.toPlainText(captionAndCredit)
        val scanned = captionText.lowercase(Locale.ROOT)
        val markers = EXCEPTION_MARKERS.filter { scanned.contains(it) }

        return AssetPage(
            id = id,
            title = title,
            type = type,
            widthPx = width,
            heightPx = height,
            credit = credit,
            renditions = renditions,
            linksUsagePolicy = html.contains("href=\"${site.policyPath}\"") ||
                html.contains("href=\"${site.policyUrl}\""),
            statesMandatoryCredit = HtmlText.normalizeWhitespace(HtmlText.decodeEntities(html))
                .contains(MANDATORY_CREDIT_NOTICE),
            exceptionMarkers = markers,
            description = ContentFilter.clip(captionText)
        )
    }

    private fun field(html: String, label: String): String? =
        Regex("""<t([hd])[^>]*>\s*${Regex.escape(label)}:\s*</t\1>\s*<td[^>]*>([\s\S]*?)</td>""")
            .find(html)
            ?.groupValues
            ?.get(2)
            ?.let(HtmlText::toPlainText)
            ?.takeIf { it.isNotBlank() }
}

internal object HtmlText {
    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "ndash" to "\u2013", "mdash" to "\u2014", "rsquo" to "\u2019", "lsquo" to "\u2018",
        "rdquo" to "\u201d", "ldquo" to "\u201c", "hellip" to "\u2026", "copy" to "\u00a9",
        "deg" to "\u00b0", "times" to "\u00d7", "eacute" to "\u00e9", "uuml" to "\u00fc",
        "ouml" to "\u00f6", "auml" to "\u00e4", "aacute" to "\u00e1", "oacute" to "\u00f3",
        "iacute" to "\u00ed", "ntilde" to "\u00f1", "ccedil" to "\u00e7"
    )
    private val ENTITY = Regex("""&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);""")
    private val INLINE_TAG = Regex(
        """</?(a|span|em|strong|b|i|u|sup|sub|abbr|small|cite|code)\b[^>]*>""",
        RegexOption.IGNORE_CASE
    )

    fun toPlainText(html: String): String {
        val withoutScripts = html.replace(Regex("""<(script|style)[\s\S]*?</\1>""", RegexOption.IGNORE_CASE), " ")
        // Inline elements must not introduce spaces, or credits such as "NASA, ESA" gain stray gaps.
        val withoutInline = withoutScripts.replace(INLINE_TAG, "")
        val withoutTags = withoutInline.replace(Regex("""<[^>]*>"""), " ")
        return normalizeWhitespace(decodeEntities(withoutTags))
    }

    fun decodeEntities(text: String): String = ENTITY.replace(text) { match ->
        val body = match.groupValues[1]
        when {
            body.startsWith("#x") || body.startsWith("#X") ->
                body.substring(2).toIntOrNull(16)?.let(::codePoint) ?: match.value
            body.startsWith("#") -> body.substring(1).toIntOrNull()?.let(::codePoint) ?: match.value
            else -> NAMED[body] ?: match.value
        }
    }

    fun normalizeWhitespace(text: String): String =
        text.replace('\u00a0', ' ').replace(Regex("""\s+"""), " ").trim()

    private fun codePoint(value: Int): String? =
        if (Character.isValidCodePoint(value)) String(Character.toChars(value)) else null
}
