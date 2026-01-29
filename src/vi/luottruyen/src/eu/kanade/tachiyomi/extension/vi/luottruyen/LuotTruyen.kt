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
import org.jsoup.nodes.Element
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
        return GET("$baseUrl/tim-truyen?status=-1&sort=10" + if (page > 1) "&page=$page" else "", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()

        val mangaList = document.select("div.item").map { element ->
            SManga.create().apply {
                element.selectFirst("figcaption h3 a, a.jtip")!!.let {
                    title = it.text()
                    setUrlWithoutDomain(it.attr("abs:href"))
                }
                thumbnail_url = imageOrNull(element.selectFirst("div.image a img"))
            }
        }

        val hasNextPage = document.selectFirst("li.next:not(.disabled) a, li:not(.disabled).next a") != null

        return MangasPage(mangaList, hasNextPage)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        return GET(baseUrl + if (page > 1) "?page=$page" else "", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        return popularMangaParse(response)
    }

    // =============================== Search ===============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotBlank()) {
            val url = "$baseUrl/tim-truyen".toHttpUrl().newBuilder().apply {
                addQueryParameter("keyword", query)
                if (page > 1) addQueryParameter("page", page.toString())
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
                if (page > 1) addQueryParameter("page", page.toString())
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
            if (page > 1) addQueryParameter("page", page.toString())
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
            document.selectFirst("article#item-detail")?.let { info ->
                author = info.selectFirst("li.author p.col-xs-8")?.text()
                status = info.selectFirst("li.status p.col-xs-8")?.text().toStatus()
                genre = info.select("li.kind p.col-xs-8 a").joinToString { it.text() }
                description = info.select("div.detail-content p").joinToString("\n") { it.text() }
                thumbnail_url = imageOrNull(info.selectFirst("div.col-image img"))
            }
        }
    }

    private fun String?.toStatus(): Int {
        return when {
            this == null -> SManga.UNKNOWN
            this.contains("Đang tiến hành", ignoreCase = true) -> SManga.ONGOING
            this.contains("Đang cập nhật", ignoreCase = true) -> SManga.ONGOING
            this.contains("Hoàn thành", ignoreCase = true) -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }

    // ============================== Chapters ==============================

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()

        return document.select("div.list-chapter li.row:not(.heading)").map { element ->
            SChapter.create().apply {
                element.selectFirst("div.chapter a")?.let {
                    name = it.text()
                    setUrlWithoutDomain(it.attr("href"))
                }
                date_upload = parseRelativeDate(element.selectFirst("div.col-xs-4")?.text())
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

        return document.select("div.page-chapter > img, li.blocks-gallery-item img")
            .mapNotNull { img -> imageOrNull(img) }
            .distinct()
            .mapIndexed { i, image -> Page(i, imageUrl = image) }
    }

    override fun imageUrlParse(response: Response): String {
        throw UnsupportedOperationException()
    }

    // ============================== Utilities =============================

    private fun imageOrNull(element: Element?): String? {
        if (element == null) return null
        return when {
            element.hasAttr("data-original") && element.attr("data-original").isNotBlank() ->
                element.absUrl("data-original")
            element.hasAttr("data-src") && element.attr("data-src").isNotBlank() ->
                element.absUrl("data-src")
            element.hasAttr("src") && element.attr("src").isNotBlank() ->
                element.absUrl("src")
            else -> null
        }
    }

    // ============================== Filters ===============================

    override fun getFilterList(): FilterList = getFilters()
}
