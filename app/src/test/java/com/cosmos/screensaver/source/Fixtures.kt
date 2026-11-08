package com.cosmos.screensaver.source

/** Synthetic pages modelled on the structure of ESA/Hubble and ESA/Webb pages. No real content. */
internal object Fixtures {
    fun policyPage(site: DjangoplicitySite, statement: String = site.policyStatement) = """
        <html><body><h1>Usage of images</h1>
        <p>${statement.replace("'", "&#39;")}</p>
        <a href="https://creativecommons.org/licenses/by/4.0/">CC BY 4.0</a>
        </body></html>
    """.trimIndent()

    fun feed(site: DjangoplicitySite, vararg ids: String) = buildString {
        append("<rss><channel><link>https://${site.host}/images/</link>")
        ids.forEach { append("<item><title>x</title><link>https://${site.host}/images/$it/</link></item>") }
        append("</channel></rss>")
    }

    fun listing(vararg ids: String) = ids.joinToString("\n") { """<a href="/images/$it/">$it</a>""" } +
        """<a href="/images/archive/top100/">Top</a><a href="/images/potw/">POTW</a>"""

    fun assetPage(
        site: DjangoplicitySite,
        id: String,
        type: String = "Observation",
        width: Int = 4000,
        height: Int = 2250,
        title: String = "Synthetic nebula &amp; friends",
        caption: String = "A project-created description of a synthetic object.",
        credit: String = "ESA/Synthetic &amp; Example Team",
        renditions: List<String> = listOf("publicationjpg", "large"),
        creditNotice: Boolean = true,
        policyLink: Boolean = true,
        webbStyle: Boolean = site == DjangoplicitySite.WEBB
    ): String {
        fun row(label: String, value: String) = if (webbStyle) {
            "<tr>\n <th scope=\"row\"> $label: </th>\n <td colspan=\"2\"> $value </td>\n</tr>"
        } else {
            "<tr><th class=\"title\" scope=\"row\">$label:</th><td>$value</td></tr>"
        }
        val notice = if (creditNotice) {
            "<span data-bs-content=\"${DjangoplicityParser.MANDATORY_CREDIT_NOTICE}\">i</span>"
        } else {
            ""
        }
        val links = renditions.joinToString("\n") {
            "<a href=\"https://${site.cdnHost}/archives/images/$it/$id.jpg\">$it</a>"
        }
        val policy = if (policyLink) "<a href=\"/copyright/\">Usage of images</a>" else ""
        return """
            <html><body>
            <h1>$title</h1>
            <p>$caption</p>
            <div class="credit"><p>$credit</p></div>
            $notice
            $links
            <table>${row("Id", id)}${row("Type", type)}${row("Size", "$width x $height px")}</table>
            $policy
            </body></html>
        """.trimIndent()
    }
}
