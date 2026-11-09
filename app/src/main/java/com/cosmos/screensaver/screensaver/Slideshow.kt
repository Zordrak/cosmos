package com.cosmos.screensaver.screensaver

import com.cosmos.screensaver.catalogue.CatalogueEntry

internal data class Attribution(
    val title: String,
    val credit: String,
    val source: String
) {
    companion object {
        /** The credit is reproduced in full and unaltered, as the source licences require. */
        fun of(entry: CatalogueEntry) = Attribution(
            title = entry.title,
            credit = "Credit: ${entry.credit}",
            source = listOf(
                entry.sourceLabel,
                entry.licenceName,
                displayUrl(entry.licenceUrl),
                displayUrl(entry.sourcePageUrl)
            )
                .filter { it.isNotBlank() }
                .joinToString("  \u00b7  ")
        )

        fun displayUrl(url: String) = url.removePrefix("https://").removeSuffix("/")
    }
}
