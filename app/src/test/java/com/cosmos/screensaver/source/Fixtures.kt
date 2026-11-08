package com.cosmos.screensaver.source

/** Synthetic pages modelled on the structure of Djangoplicity (ESA/Hubble, ESA/Webb, ESO, NOIRLab) pages. No real content. */
internal object Fixtures {
    fun policyPage(site: DjangoplicitySite, statement: String = site.policyStatement) = """
        <html><body><h1>Usage of images</h1>
        <p>${statement.replace("'", "&#39;")}</p>
        <a href="https://creativecommons.org/licenses/by/4.0/">CC BY 4.0</a>
        </body></html>
    """.trimIndent()

    fun feed(site: DjangoplicitySite, vararg ids: String) = buildString {
        // NOIRLab's feed links use plain http; the page itself is still fetched over https.
        val scheme = if (site == DjangoplicitySite.NOIRLAB) "http" else "https"
        append("<rss><channel><link>$scheme://${site.webHost}${site.pagePath}</link>")
        ids.forEach { append("<item><title>x</title><link>$scheme://${site.webHost}${site.pagePath}$it/</link></item>") }
        append("</channel></rss>")
    }

    fun listing(vararg ids: String) = listing(DjangoplicitySite.HUBBLE, *ids)

    fun listing(site: DjangoplicitySite, vararg ids: String) =
        ids.joinToString("\n") { """<a href="${site.pagePath}$it/">$it</a>""" } +
            """<a href="${site.pagePath}archive/top100/">Top</a><a href="${site.pagePath}potw/">POTW</a>""" +
            """<a href="${site.pagePath}comparisons/">Compare</a>"""

    fun assetPage(
        site: DjangoplicitySite,
        id: String,
        type: String = "Observation",
        width: Int = 4000,
        height: Int = 2250,
        title: String = "Synthetic nebula &amp; friends",
        caption: String = "A project-created description of a synthetic object.",
        credit: String? = "ESA/Synthetic &amp; Example Team",
        renditions: List<String> = listOf("publicationjpg", "large"),
        creditNotice: Boolean = true,
        policyLink: Boolean = true,
        webbStyle: Boolean = site == DjangoplicitySite.WEBB
    ): String {
        val tdStyle = site == DjangoplicitySite.ESO || site == DjangoplicitySite.NOIRLAB
        fun row(label: String, value: String) = when {
            webbStyle -> "<tr>\n <th scope=\"row\"> $label: </th>\n <td colspan=\"2\"> $value </td>\n</tr>"
            tdStyle -> "<tr><td class=\"title\">$label:</td><td>$value</td></tr>"
            else -> "<tr><th class=\"title\" scope=\"row\">$label:</th><td>$value</td></tr>"
        }
        val notice = if (creditNotice) {
            "<span data-bs-content=\"${DjangoplicityParser.MANDATORY_CREDIT_NOTICE}\">i</span>"
        } else {
            ""
        }
        val links = renditions.joinToString("\n") { path ->
            val rendition = Rendition.entries.firstOrNull { it.path == path }
            val url = rendition?.let { site.renditionUrl(it, id) } ?: "https://${site.cdnHost}${site.cdnPath}$path/$id.jpg"
            "<a href=\"$url\">$path</a>"
        }
        val policy = if (policyLink) "<a href=\"${site.policyPath}\">Usage of images</a>" else ""
        val creditBlock = credit?.let { "<div class=\"credit\"><p>$it</p></div>" }.orEmpty()
        return """
            <html><body>
            <h1>$title</h1>
            <p>$caption</p>
            $creditBlock
            $notice
            $links
            <table>${row("Id", id)}${row("Type", type)}${row("Size", "$width x $height px")}</table>
            $policy
            </body></html>
        """.trimIndent()
    }
}
