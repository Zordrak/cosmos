package com.cosmos.screensaver.source

/**
 * Keeps charts, diagrams and multi-panel graphics out of the picture library. It is deliberately
 * high-precision: only explicit wording counts. Words such as "spectrum", "composite", "mosaic" or
 * "exposures taken over several nights" describe real photographs too, so they are NOT enough.
 * One bad asset is handled by the owner's blocklist, not by widening these rules.
 */
internal object ContentFilter {
    /** Changes whenever the rules do, so earlier decisions are re-examined. */
    const val POLICY_VERSION = "content-1"

    private val TITLE_TERMS = Regex(
        """\b(infographics?|diagrams?|charts?|graphs?|schematics?|timelines?|collages?|montages?|annotated|labell?ed)\b""",
        RegexOption.IGNORE_CASE
    )

    private val DESCRIPTION_TERMS = listOf(
        Regex("""\bechelle\b""", RegexOption.IGNORE_CASE),
        Regex("""\bwavelength increasing\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:spectral|spectrum|flux) atlas\b""", RegexOption.IGNORE_CASE),
        Regex("""\binfographic\b""", RegexOption.IGNORE_CASE),
        Regex("""\bmulti-?panel\b""", RegexOption.IGNORE_CASE),
        Regex("""\bside[- ]by[- ]side (?:comparison|images|panels)\b""", RegexOption.IGNORE_CASE),
        Regex("""\btime[- ](?:lapse )?sequence\b""", RegexOption.IGNORE_CASE),
        Regex(
            """\b(?:top|bottom|left|right|upper|lower|center|centre)(?:\s+(?:left|right|center|centre))?\s+(?:images?|panels?|frames?)\s*:""",
            RegexOption.IGNORE_CASE
        )
    )

    /** The reason to skip an asset, or null when nothing indicates it is not a photograph. */
    fun check(title: String, description: String = ""): String? {
        if (TITLE_TERMS.containsMatchIn(title)) return SourceRejections.NOT_A_PHOTOGRAPH
        if (DESCRIPTION_TERMS.any { it.containsMatchIn(description) }) return SourceRejections.NOT_A_PHOTOGRAPH
        return null
    }

    /** Limits stored/scanned text; descriptions can be long article bodies. */
    fun clip(text: String): String = if (text.length <= MAX_DESCRIPTION) text else text.substring(0, MAX_DESCRIPTION)

    private const val MAX_DESCRIPTION = 4000
}
