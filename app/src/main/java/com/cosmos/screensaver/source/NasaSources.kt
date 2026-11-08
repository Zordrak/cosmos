package com.cosmos.screensaver.source

import com.cosmos.screensaver.rights.AssetProvider
import org.json.JSONArray
import org.json.JSONException
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import kotlin.random.Random

internal object NasaHosts {
    val ALL = setOf(
        "science.nasa.gov",
        "assets.science.nasa.gov",
        "images-api.nasa.gov",
        "images-assets.nasa.gov",
        "images.nasa.gov"
    )
}

/** Reads a (width, height) pair from the start of an image file. */
private fun probeDimensions(fetcher: Fetcher, url: String): Pair<Int, Int>? =
    ImageHeader.dimensions(fetcher.fetchPrefix(url))

internal data class ApodItem(
    val slug: String,
    val pageUrl: String,
    val title: String,
    val credit: String,
    val imageUrl: String,
    val copyrightMarked: Boolean
)

internal object ApodParser {
    private val IMAGE_LINK = Regex(
        """href="(https://assets\.science\.nasa\.gov/content/dam/science/cds/apod/[^"]+?\.(?:jpe?g|png))"""",
        RegexOption.IGNORE_CASE
    )
    private val CREDIT = Regex("""Credit(?:\s*&\s*Copyright)?\s*:\s*(.+?)\s+Authors\b""")
    private val TITLE_PREFIX = Regex("""^APOD:\s*\d{4}\s+\w+\s+\d{1,2}\s*[\u2013\u2014-]\s*""")

    fun items(json: String): List<ApodItem> {
        val array = try {
            JSONArray(json)
        } catch (_: JSONException) {
            return emptyList()
        }
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val slug = item.optString("slug").takeIf { it.startsWith("apod-") && it.length in 6..120 }
                ?: return@mapNotNull null
            val link = item.optString("link").takeIf { it.startsWith("https://science.nasa.gov/") }
                ?: return@mapNotNull null
            val content = item.optJSONObject("content")?.optString("rendered").orEmpty()
            val imageUrl = IMAGE_LINK.find(content)?.groupValues?.get(1) ?: return@mapNotNull null
            val text = HtmlText.toPlainText(content)
            val credit = CREDIT.find(text)?.groupValues?.get(1)?.trim().orEmpty()
            val rawTitle = HtmlText.toPlainText(item.optJSONObject("title")?.optString("rendered").orEmpty())
            ApodItem(
                slug = slug,
                pageUrl = link,
                title = rawTitle.replace(TITLE_PREFIX, "").ifBlank { rawTitle },
                credit = credit,
                imageUrl = imageUrl,
                copyrightMarked = text.contains("Copyright", ignoreCase = true) || text.contains("\u00a9")
            )
        }.distinctBy { it.slug }
    }
}

/** NASA's Astronomy Picture of the Day, read from the science.nasa.gov article feed. */
internal class ApodAdapter(private val pagesPerRefresh: Int = 2) : SourceAdapter {
    override val provider get() = AssetProvider.APOD
    override val label get() = "NASA APOD"

    override fun discover(fetcher: Fetcher, random: Random): Discovery {
        val errors = mutableListOf<String>()
        var reached = false
        val pages = (1..MAX_PAGE).shuffled(random).take(pagesPerRefresh).toMutableList()
        // Page 1 always holds the newest pictures, which suit "Image of the Day".
        if (1 !in pages) pages[0] = 1
        val items = pages.flatMap { page ->
            try {
                ApodParser.items(fetcher.fetchText(listingUrl(page))).also { reached = true }
            } catch (error: IOException) {
                errors += "NASA APOD listing unavailable (${error.describe()})"
                emptyList()
            }
        }.distinctBy { it.slug }
        val leads = items.map { item -> Lead(item.slug) { resolve(fetcher, item) } }
        return Discovery(leads, errors, reached)
    }

    private fun resolve(fetcher: Fetcher, item: ApodItem): CandidateResult {
        val (width, height) = try {
            probeDimensions(fetcher, item.imageUrl)
        } catch (error: IOException) {
            return CandidateResult.Failed("image unavailable (${error.describe()})")
        } ?: return CandidateResult.Skip("UNDECODABLE_IMAGE")
        return CandidateResult.Ready(
            Candidate(
                provider = AssetProvider.APOD,
                id = item.slug,
                title = item.title,
                pageUrl = item.pageUrl,
                credit = item.credit,
                options = listOf(RenditionOption(item.imageUrl, imageMaxBytes(null), width, height)),
                declaredWidth = width,
                declaredHeight = height,
                copyrightMarked = item.copyrightMarked
            )
        )
    }

    companion object {
        private const val MAX_PAGE = 40

        fun listingUrl(page: Int) =
            "https://science.nasa.gov/wp-json/wp/v2/image-article?categories=22766&per_page=20&page=$page" +
                "&_fields=slug,link,title,content"
    }
}

internal data class NasaLibraryItem(
    val nasaId: String,
    val title: String,
    val credit: String,
    val originalUrl: String,
    val previewWidth: Int,
    val previewHeight: Int,
    val copyrightMarked: Boolean
)

internal object NasaLibraryParser {
    private val ID = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{2,100}$")

    /** Titles and keywords that indicate art, charts, people or hardware rather than an observation. */
    private val UNWANTED = Regex(
        """\b(illustrations?|artists?|concepts?|renderings?|rendered|animations?|diagrams?|charts?|graphics?|infographics?|""" +
            """astronauts?|crew|portraits?|ceremony|briefing|interview|employees?|team|launch ?pad|rockets?|""" +
            """rovers?|landers?|facility|laboratory|simulation|simulated|logo|poster|patch)\b""",
        RegexOption.IGNORE_CASE
    )

    fun items(json: String): List<NasaLibraryItem> = try {
        val root = org.json.JSONObject(json)
        val array = root.getJSONObject("collection").getJSONArray("items")
        (0 until array.length()).mapNotNull { parse(array.getJSONObject(it)) }
    } catch (_: JSONException) {
        emptyList()
    }

    private fun parse(item: org.json.JSONObject): NasaLibraryItem? {
        val data = item.optJSONArray("data")?.optJSONObject(0) ?: return null
        if (data.optString("media_type") != "image") return null
        val id = data.optString("nasa_id").takeIf { ID.matches(it) } ?: return null
        val title = HtmlText.toPlainText(data.optString("title")).ifBlank { return null }
        val keywords = data.optJSONArray("keywords")?.let { array ->
            (0 until array.length()).joinToString(" ") { array.optString(it) }
        }.orEmpty()
        if (UNWANTED.containsMatchIn("$title $keywords")) return null
        val description = HtmlText.toPlainText(data.optString("description"))
        if (Regex("""artist'?s? (concept|rendering|illustration)|illustration|animation""", RegexOption.IGNORE_CASE)
                .containsMatchIn(description)
        ) {
            return null
        }
        val links = item.optJSONArray("links") ?: return null
        var original: String? = null
        var previewWidth = 0
        var previewHeight = 0
        for (index in 0 until links.length()) {
            val link = links.optJSONObject(index) ?: continue
            val href = link.optString("href")
            if (link.optString("rel") == "canonical" && Regex("""\.(jpe?g|png)$""", RegexOption.IGNORE_CASE).containsMatchIn(href)) {
                original = href
            }
            if (link.optString("rel") == "preview" || link.optString("rel") == "alternate") {
                val width = link.optInt("width")
                val height = link.optInt("height")
                if (width > previewWidth) {
                    previewWidth = width
                    previewHeight = height
                }
            }
        }
        val url = original?.takeIf { it.startsWith("https://images-assets.nasa.gov/") } ?: return null
        val credit = listOf("photographer", "secondary_creator")
            .map { data.optString(it).trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: "NASA"
        val text = "$credit $description"
        return NasaLibraryItem(
            nasaId = id,
            title = title,
            credit = credit,
            originalUrl = url,
            previewWidth = previewWidth,
            previewHeight = previewHeight,
            copyrightMarked = text.contains("\u00a9") || text.contains("copyright", ignoreCase = true)
        )
    }
}

/** The NASA Image and Video Library, searched for astronomy and planetary observations. */
internal class NasaLibraryAdapter(private val queriesPerRefresh: Int = 3) : SourceAdapter {
    override val provider get() = AssetProvider.NASA
    override val label get() = "NASA Image Library"

    override fun discover(fetcher: Fetcher, random: Random): Discovery {
        val errors = mutableListOf<String>()
        var reached = false
        val items = QUERIES.shuffled(random).take(queriesPerRefresh).flatMap { query ->
            try {
                NasaLibraryParser.items(fetcher.fetchText(searchUrl(query))).also { reached = true }
            } catch (error: IOException) {
                errors += "NASA Image Library search unavailable (${error.describe()})"
                emptyList()
            }
        }.distinctBy { it.nasaId }
            .filter { it.previewWidth <= 0 || it.previewHeight <= 0 || com.cosmos.screensaver.settings.ImageSelection.suitableAspect(it.previewWidth, it.previewHeight) }
        return Discovery(items.map { item -> Lead(item.nasaId) { resolve(fetcher, item) } }, errors, reached)
    }

    private fun resolve(fetcher: Fetcher, item: NasaLibraryItem): CandidateResult {
        val (width, height) = try {
            probeDimensions(fetcher, item.originalUrl)
        } catch (error: IOException) {
            return CandidateResult.Failed("image unavailable (${error.describe()})")
        } ?: return CandidateResult.Skip("UNDECODABLE_IMAGE")
        return CandidateResult.Ready(
            Candidate(
                provider = AssetProvider.NASA,
                id = item.nasaId,
                title = item.title,
                pageUrl = "https://images.nasa.gov/details/${item.nasaId}",
                credit = item.credit,
                options = listOf(RenditionOption(item.originalUrl, imageMaxBytes(null), width, height)),
                declaredWidth = width,
                declaredHeight = height,
                copyrightMarked = item.copyrightMarked
            )
        )
    }

    companion object {
        val QUERIES = listOf(
            "nebula", "galaxy", "hubble deep field", "webb telescope", "supernova remnant", "star cluster",
            "planetary nebula", "milky way", "jupiter", "saturn", "mars surface", "moon surface", "comet",
            "solar flare", "aurora", "earth from space", "spiral galaxy", "star forming region"
        )

        fun searchUrl(query: String) =
            "https://images-api.nasa.gov/search?q=${URLEncoder.encode(query, "UTF-8")}" +
                "&media_type=image&year_start=2005&page_size=100"
    }
}

internal object Sources {
    fun adapters(providers: Set<AssetProvider>, clock: () -> Long = System::currentTimeMillis): List<SourceAdapter> =
        providers.sortedBy { it.ordinal }.mapNotNull { provider ->
            when (provider) {
                AssetProvider.NASA -> NasaLibraryAdapter()
                AssetProvider.APOD -> ApodAdapter()
                else -> DjangoplicitySite.forProvider(provider)?.let { DjangoplicityAdapter(it, clock) }
            }
        }

    fun label(provider: AssetProvider): String = when (provider) {
        AssetProvider.NASA -> "NASA Image Library"
        AssetProvider.APOD -> "NASA APOD"
        else -> DjangoplicitySite.forProvider(provider)?.label ?: provider.name.lowercase(Locale.ROOT)
    }
}
