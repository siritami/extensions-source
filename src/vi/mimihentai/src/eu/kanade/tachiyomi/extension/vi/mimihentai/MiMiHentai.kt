package eu.kanade.tachiyomi.extension.vi.mimihentai

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Calendar

class MiMiHentai : ParsedHttpSource() {
    override val name = "MiMiHentai"
    override val lang = "vi"
    override val baseUrl = "https://mimihentai.net"
    override val supportsLatest = true

    // ============================== Popular ===============================
    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/danh-sach?sort=-views&page=$page", headers)
    }

    override fun popularMangaSelector(): String = "a.group"

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        title = element.selectFirst("h1")?.text() ?: ""
        thumbnail_url = element.selectFirst("img")?.absUrl("src")
    }

    override fun popularMangaNextPageSelector(): String = "a[href*='page=']:contains(>)"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/danh-sach?page=$page", headers)
    }

    override fun latestUpdatesSelector(): String = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularMangaNextPageSelector()

    // =============================== Search ===============================
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/tim-kiem".toHttpUrl().newBuilder().apply {
            addQueryParameter("keyword", query)
            addQueryParameter("page", page.toString())

            filters.forEach { filter ->
                when (filter) {
                    is GenreFilter -> {
                        filter.state.filter { it.state }.forEach { genre ->
                            addQueryParameter("genres[${genre.id}]", "1")
                        }
                    }
                    is StatusFilter -> {
                        if (filter.state > 0) {
                            addQueryParameter("filter[status]", filter.toUriPart())
                        }
                    }
                    is SortFilter -> {
                        addQueryParameter("sort", filter.toUriPart())
                    }
                    else -> {}
                }
            }
        }.build()

        return GET(url, headers)
    }

    override fun searchMangaSelector(): String = popularMangaSelector()

    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector(): String = popularMangaNextPageSelector()

    // =============================== Details ==============================
    override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        val infoContainer = document.selectFirst("div.title")?.parent()

        title = document.selectFirst("div.title p")?.text() ?: ""
        thumbnail_url = document.selectFirst("img.rounded.shadow-md.w-full")?.absUrl("src")
        author = document.selectFirst("a[href*='/tac-gia/']")?.text()
        genre = document.select("a[href*='/the-loai/']").joinToString { it.text() }

        val bodyText = document.body().text()
        status = when {
            bodyText.contains("Đã hoàn thành") -> SManga.COMPLETED
            bodyText.contains("Đang tiến hành") -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }

        description = infoContainer?.selectFirst("div.mt-4")?.ownText()
            ?: document.selectFirst("div.mt-\\[4rem\\]")?.text()
    }

    // ============================== Chapters ==============================
    override fun chapterListSelector(): String = "a[href*='/chap-']"

    override fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        name = element.selectFirst("h1")?.text() ?: element.text()

        val dateText = element.parent()?.selectFirst("span")?.text()
        date_upload = parseRelativeDate(dateText)
    }

    private fun parseRelativeDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L

        val calendar = Calendar.getInstance()
        val number = Regex("\\d+").find(dateStr)?.value?.toIntOrNull() ?: return 0L

        when {
            dateStr.contains("giây") -> calendar.add(Calendar.SECOND, -number)
            dateStr.contains("phút") -> calendar.add(Calendar.MINUTE, -number)
            dateStr.contains("giờ") -> calendar.add(Calendar.HOUR_OF_DAY, -number)
            dateStr.contains("ngày") -> calendar.add(Calendar.DAY_OF_MONTH, -number)
            dateStr.contains("tuần") -> calendar.add(Calendar.WEEK_OF_YEAR, -number)
            dateStr.contains("tháng") -> calendar.add(Calendar.MONTH, -number)
            dateStr.contains("năm") -> calendar.add(Calendar.YEAR, -number)
            else -> return 0L
        }

        return calendar.timeInMillis
    }

    // =============================== Pages ================================
    override fun pageListParse(document: Document): List<Page> {
        return document.select("img.lazy").mapIndexed { index, img ->
            Page(index, imageUrl = img.absUrl("src"))
        }
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ============================== Filters ===============================
    override fun getFilterList(): FilterList = getFilters()
}
