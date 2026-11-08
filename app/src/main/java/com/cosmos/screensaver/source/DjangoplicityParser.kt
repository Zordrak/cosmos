package com.cosmos.screensaver.source

import java.util.Locale

internal data class AssetPage(
    val id: String,
    val title: String,
    val type: String,
    val widthPx: Int?,
    val heightPx: Int?,
    val credit: String,
    /** Download candidates in order of preference; all are on the site's CDN. */
    val imageUrls: List<String>,
    val linksUsagePolicy: Boolean,
    val statesMandatoryCredit: Boolean,
    /** Wording in the title, caption or credit suggesting the general licence may not apply. */
    val exceptionMarkers: List<String>
)

internal object DjangoplicityParser {
    const val MANDATORY_CREDIT_NOTICE =
        "Crediting this image with the full credit line, in a visible way is MANDATORY, " +
            "if you want to use it without paying a fee."

    private val ID_PATTERN = Regex("^[a-z0-9][a-z0-9_-]{2,63}$")
    private val NON_ASSET_PATHS = setOf(
        "archive", "viewall", "search", "potw", "potm", "zip", "feed", "top100", "page", "list"
    )
    private val EXCEPTION_MARKERS = listOf(
        "\u00a9", "copyright", "all rights reserved", "permission", "courtesy", "not licensed",
        "cc by-nc", "cc by-sa", "cc by-nd", "non-commercial", "noncommercial"
    )

    fun listingIds(html: String, site: DjangoplicitySite): List<String> =
        Regex("""href="(?:https://(?:www\.)?${Regex.escape(site.host)})?/images/([a-z0-9_-]+)/"""")
            .findAll(html)
            .map { it.groupValues[1] }
            .filter(::isAssetId)
            .distinct()
            .toList()

    fun feedIds(xml: String, site: DjangoplicitySite): List<String> =
        Regex("""<link>\s*https://(?:www\.)?${Regex.escape(site.host)}/images/([a-z0-9_-]+)/\s*</link>""")
            .findAll(xml)
            .map { it.groupValues[1] }
            .filter(::isAssetId)
            .distinct()
            .toList()

    fun discoveredIds(document: String, site: DjangoplicitySite): List<String> =
        (feedIds(document, site) + listingIds(document, site)).distinct()

    fun isAssetId(id: String) = ID_PATTERN.matches(id) && id !in NON_ASSET_PATHS

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
        val creditMatch = Regex("""<div class="credit">([\s\S]*?)</div>""").find(html) ?: return null
        val credit = HtmlText.toPlainText(creditMatch.groupValues[1]).takeIf { it.isNotBlank() } ?: return null

        val size = field(html, "Size")?.let { Regex("""(\d+)\s*x\s*(\d+)\s*px""").find(it) }
        val width = size?.groupValues?.get(1)?.toIntOrNull()
        val height = size?.groupValues?.get(2)?.toIntOrNull()

        val cdn = Regex.escape(site.cdnHost)
        val escapedId = Regex.escape(id)
        val imageUrls = listOf("publicationjpg", "large").mapNotNull { rendition ->
            Regex("""https://$cdn/archives/images/$rendition/$escapedId\.jpg""").find(html)?.value
        }

        val titleStart = titleMatch.range.first
        val captionAndCredit = if (titleStart < creditMatch.range.first) {
            html.substring(titleStart, creditMatch.range.last + 1)
        } else {
            titleMatch.value + creditMatch.value
        }
        val scanned = HtmlText.toPlainText(captionAndCredit).lowercase(Locale.ROOT)
        val markers = EXCEPTION_MARKERS.filter { scanned.contains(it) }

        return AssetPage(
            id = id,
            title = title,
            type = type,
            widthPx = width,
            heightPx = height,
            credit = credit,
            imageUrls = imageUrls,
            linksUsagePolicy = html.contains("href=\"/copyright/\"") ||
                html.contains("href=\"https://${site.host}/copyright/\""),
            statesMandatoryCredit = HtmlText.normalizeWhitespace(HtmlText.decodeEntities(html))
                .contains(MANDATORY_CREDIT_NOTICE),
            exceptionMarkers = markers
        )
    }

    private fun field(html: String, label: String): String? =
        Regex("""<th[^>]*>\s*${Regex.escape(label)}:\s*</th>\s*<td[^>]*>([\s\S]*?)</td>""")
            .find(html)
            ?.groupValues
            ?.get(1)
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

    fun toPlainText(html: String): String {
        val withoutScripts = html.replace(Regex("""<(script|style)[\s\S]*?</\1>""", RegexOption.IGNORE_CASE), " ")
        val withoutTags = withoutScripts.replace(Regex("""<[^>]*>"""), " ")
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
