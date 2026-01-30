package eu.kanade.tachiyomi.extension.vi.mimi

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.util.Calendar

class MiMi : ParsedHttpSource() {

    override val name = "MiMi"

    override val baseUrl = "https://mimimoe.moe"

    override val lang = "vi"

    override val supportsLatest = true

    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor(::authCheckInterceptor)
        .rateLimit(3)
        .build()

    private fun authCheckInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url

        // Only check auth for requests to this domain
        if (url.host != baseUrl.toHttpUrl().host) {
            return chain.proceed(request)
        }

        val cookie = client.cookieJar.loadForRequest(url).find { it.name == "authState" }
        if (cookie == null) {
            throw IOException("Nguồn này cần đăng nhập qua WebView để sử dụng")
        }

        return chain.proceed(request)
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ============================== Popular ======================================

    override fun popularMangaRequest(page: Int): Request {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("advance-search")
            .addQueryParameter("sortType", "views")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun popularMangaSelector() = "a.btn-popcl"

    override fun popularMangaFromElement(element: Element) = SManga.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        title = element.selectFirst("h2")?.text() ?: ""
        thumbnail_url = element.selectFirst("img")?.absUrl("src")
    }

    override fun popularMangaNextPageSelector() = "button[aria-label='Next Page']:not([disabled])"

    // ============================== Latest ======================================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("advance-search")
            .addQueryParameter("sortType", "updated_at")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    // ============================== Search ======================================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("advance-search")
            .addQueryParameter("sortType", "updated_at")
            .addQueryParameter("page", page.toString())

        if (query.isNotBlank()) {
            url.addQueryParameter("name", query)
        }

        filters.filterIsInstance<GenreFilter>().firstOrNull()?.let { filter ->
            val genreId = filter.toUriPart()
            if (genreId.isNotEmpty()) {
                url.addQueryParameter("genre", genreId)
            }
        }

        return GET(url.build(), headers)
    }

    override fun searchMangaSelector() = popularMangaSelector()

    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    // ============================== Filters ======================================

    override fun getFilterList(): FilterList = getFilters()

    // ============================== Details ======================================

    override fun mangaDetailsParse(document: Document) = SManga.create().apply {
        title = document.selectFirst("h1")?.text() ?: ""
        thumbnail_url = document.selectFirst("img.object-cover")?.absUrl("src")
        author = document.select("a[href*='/artist/']").joinToString { it.text() }
        genre = document.select("a[href*='/genres/']").joinToString { it.text() }
        description = document.selectFirst("div.prose, div.description")?.text()
        status = SManga.UNKNOWN
    }

    // ============================== Chapters ======================================

    override fun chapterListSelector() = "a[href*='/chapter/']"

    override fun chapterFromElement(element: Element) = SChapter.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        name = element.selectFirst("span, div")?.text() ?: element.text()
        val dateText = element.selectFirst("span:contains(ago), span:contains(trước)")?.text()
        date_upload = dateText.toDate()
    }

    private fun String?.toDate(): Long {
        this ?: return 0L

        // Handle relative time format (e.g., 12s ago, 49m ago, 15h ago, 4d ago)
        if (this.contains("ago", ignoreCase = true) || this.contains("trước", ignoreCase = true)) {
            return try {
                val calendar = Calendar.getInstance()

                val patterns = listOf(
                    Regex("""(\d+)\s*s""", RegexOption.IGNORE_CASE) to Calendar.SECOND,
                    Regex("""(\d+)\s*m""", RegexOption.IGNORE_CASE) to Calendar.MINUTE,
                    Regex("""(\d+)\s*h""", RegexOption.IGNORE_CASE) to Calendar.HOUR_OF_DAY,
                    Regex("""(\d+)\s*d""", RegexOption.IGNORE_CASE) to Calendar.DAY_OF_MONTH,
                    Regex("""(\d+)\s*giờ""", RegexOption.IGNORE_CASE) to Calendar.HOUR_OF_DAY,
                    Regex("""(\d+)\s*ngày""", RegexOption.IGNORE_CASE) to Calendar.DAY_OF_MONTH,
                    Regex("""(\d+)\s*phút""", RegexOption.IGNORE_CASE) to Calendar.MINUTE,
                )

                for ((pattern, field) in patterns) {
                    pattern.find(this)?.groupValues?.get(1)?.toIntOrNull()?.let { number ->
                        calendar.add(field, -number)
                        return calendar.timeInMillis
                    }
                }

                0L
            } catch (_: Exception) {
                0L
            }
        }

        // Handle direct date format (e.g., Jan 22, 2026)
        return try {
            dateFormat.parse(this)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private val dateFormat by lazy {
        java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.ROOT)
    }

    // ============================== Pages ======================================

    override fun pageListParse(document: Document): List<Page> {
        return document.select("img.w-full, .image-container img").mapIndexed { index, element ->
            val imageUrl = element.absUrl("src").ifEmpty { element.absUrl("data-src") }
            Page(index, imageUrl = imageUrl)
        }
    }

    override fun imageUrlParse(document: Document): String {
        throw UnsupportedOperationException()
    }
}
