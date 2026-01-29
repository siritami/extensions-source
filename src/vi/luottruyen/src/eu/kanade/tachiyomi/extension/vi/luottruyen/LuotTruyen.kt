package eu.kanade.tachiyomi.extension.vi.luottruyen

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.util.Calendar

class LuotTruyen : HttpSource() {

    override val name = "LuotTruyen"

    override val lang = "vi"

    override val baseUrl = "https://luottruyen1.com"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/tim-truyen?status=-1&sort=10&page=$page", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()

        val mangaList = document.select("div.item, div.item-manga, .items .item").map { element ->
            SManga.create().apply {
                val linkElement = element.selectFirst("a[href*='/truyen-tranh/']")!!
                setUrlWithoutDomain(linkElement.attr("href"))
                title = element.selectFirst("h3 a, .title a, figcaption h3")!!.text()
                thumbnail_url = element.selectFirst("img")?.let {
                    it.absUrl("data-src")
                        .ifEmpty { it.absUrl("src") }
                        .ifEmpty { it.attr("data-original") }
                }
            }
        }

        val hasNextPage = document.selectFirst("a.next-page, a[rel=next], .pagination a:contains(»)") != null

        return MangasPage(mangaList, hasNextPage)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/?page=$page&typegroup=0", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        return popularMangaParse(response)
    }

    // =============================== Search ===============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotBlank()) {
            val url = "$baseUrl/tim-truyen".toHttpUrl().newBuilder().apply {
                addQueryParameter("keyword", query)
                addQueryParameter("page", page.toString())
            }.build()
            return GET(url, headers)
        }

        var genreSlug: String? = null
        var sortValue: String? = null
        var statusValue: String? = null

        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> {
                    if (filter.state != 0) {
                        genreSlug = filter.toUriPart()
                    }
                }
                is SortFilter -> {
                    if (filter.state != 0) {
                        sortValue = filter.toUriPart()
                    }
                }
                is StatusFilter -> {
                    statusValue = filter.toUriPart()
                }
                else -> {}
            }
        }

        if (!genreSlug.isNullOrBlank()) {
            val url = "$baseUrl/tim-truyen/$genreSlug".toHttpUrl().newBuilder().apply {
                addQueryParameter("page", page.toString())
            }.build()
            return GET(url, headers)
        }

        val url = "$baseUrl/tim-truyen".toHttpUrl().newBuilder().apply {
            if (!sortValue.isNullOrBlank()) {
                addQueryParameter("sort", sortValue)
            }
            if (!statusValue.isNullOrBlank()) {
                addQueryParameter("status", statusValue)
            }
            addQueryParameter("page", page.toString())
        }.build()

        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        return popularMangaParse(response)
    }

    // =============================== Details ==============================

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()

        return SManga.create().apply {
            title = document.selectFirst("h1.title-detail, h1.info-title, .title-manga")!!.text()
            thumbnail_url = document.selectFirst("div.col-image img, .info-cover img")?.let {
                it.absUrl("data-src")
                    .ifEmpty { it.absUrl("src") }
            }
            author = document.selectFirst("li.author p.col-xs-8, .info-item:contains(Tác giả) a")?.text()
            genre = document.select("li.kind p.col-xs-8 a, .info-item:contains(Thể loại) a").joinToString { it.text() }

            val statusText = document.selectFirst("li.status p.col-xs-8, .info-item:contains(Trạng thái)")?.text() ?: ""
            status = when {
                statusText.contains("Hoàn thành", ignoreCase = true) -> SManga.COMPLETED
                statusText.contains("Đang tiến hành", ignoreCase = true) -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }

            description = document.selectFirst("div.detail-content p, .story-detail-info")?.text()
        }
    }

    // ============================== Chapters ==============================

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()

        return document.select("div.list-chapter li, ul.list-chapters li, .chapter-list li").map { element ->
            SChapter.create().apply {
                val linkElement = element.selectFirst("a")!!
                setUrlWithoutDomain(linkElement.attr("href"))
                name = linkElement.text().trim()

                val dateText = element.selectFirst("div.col-xs-4, .chapter-time, time")?.text()
                date_upload = parseRelativeDate(dateText)
            }
        }
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

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()

        // Check for login requirement
        if (document.selectFirst("div.login-page-wrapper, .login-card") != null) {
            throw Exception("Nguồn này cần đăng nhập để xem. Vui lòng đăng nhập qua Webview trước")
        }

        val images = document.select("div.reading-detail img, div.page-chapter img, #view-chapter img")
            .ifEmpty { document.select(".chapter-content img, .reading-content img, .content-chapter img") }

        return images.mapIndexed { index, element ->
            val imageUrl = element.attr("data-src")
                .ifEmpty { element.attr("src") }
                .ifEmpty { element.attr("data-original") }
            Page(index, imageUrl = imageUrl)
        }
    }

    override fun imageUrlParse(response: Response): String {
        throw UnsupportedOperationException()
    }

    // ============================== Filters ===============================

    override fun getFilterList(): FilterList = getFilters()
}
