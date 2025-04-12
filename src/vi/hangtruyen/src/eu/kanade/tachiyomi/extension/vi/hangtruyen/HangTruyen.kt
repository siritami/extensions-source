package eu.kanade.tachiyomi.extension.vi.hangtruyen

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class HangTruyen : ParsedHttpSource() {

    override val name = "HangTruyen"

    override val baseUrl = "https://hangtruyen.net"

    override val lang = "vi"

    override val supportsLatest = true

    override fun imageUrlParse(document: Document) =
        throw UnsupportedOperationException()

    override val client = super.client.newBuilder()
        .rateLimit(5)
        .build()

    // Popular
    override fun popularMangaRequest(page: Int) =
        GET("$baseUrl/tim-kiem?r=newly-updated&page=$page&orderBy=view_desc")

    override fun popularMangaNextPageSelector() = ".next-page"

    override fun popularMangaSelector() = "div.search-result .m-post"

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val entries = document.select(popularMangaSelector()).map(::popularMangaFromElement)
        val hasNextPage = popularMangaNextPageSelector()?.let { document.selectFirst(it) } != null
        return MangasPage(entries, hasNextPage)
    }

    override fun popularMangaFromElement(element: Element) = SManga.create().apply {
        val a = element.selectFirst("a")!!
        setUrlWithoutDomain(a.attr("abs:href"))
        title = a.attr("title")
        thumbnail_url = element.selectFirst("img")?.attr("abs:data-src")
    }

    // Latest
    override fun latestUpdatesRequest(page: Int) =
        GET("$baseUrl/tim-kiem?r=newly-updated&page=$page")

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    override fun latestUpdatesParse(response: Response): MangasPage {
        return popularMangaParse(response)
    }

    override fun latestUpdatesFromElement(element: Element): SManga {
        return popularMangaFromElement(element)
    }

    // Search
    private val searchPath = "tim-kiem"

    override fun searchMangaSelector() = "div.search-result"

    override fun searchMangaParse(response: Response): MangasPage {
        return popularMangaParse(response)
    }

    override fun searchMangaFromElement(element: Element): SManga {
        return popularMangaFromElement(element)
    }

    // Details
    override fun mangaDetailsParse(document: Document) = SManga.create().apply {
        title = document.selectFirst("h1.title-detail a")!!.text().trim()
        author = document.selectFirst("div.author p")?.text()?.trim()
        description = document.selectFirst("div.sort-des div.line-clamp")?.text()?.trim()
        genre = document.select("div.kind a, div.m-tags a").joinToString(", ") { it.text().trim() }
        status = when (document.selectFirst("div.status p")?.text()?.trim()) {
            "Đang tiến hành" -> SManga.ONGOING
            "Hoàn thành" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
        thumbnail_url = document.selectFirst("div.col-image img")?.attr("abs:src")
    }

    // Chapters
    override fun chapterListSelector() = "div.list-chapters div.l-chapter"

    override fun chapterFromElement(element: Element) = SChapter.create().apply {
        val a = element.selectFirst("a.ll-chap")!!
        setUrlWithoutDomain(a.attr("href"))
        name = a.text().trim()
        date_upload = element.select("span.ll-update")[0].text().toDate()
    }

    private fun String?.toDate(): Long {
        this ?: return 0L

        val hourWords = listOf("giờ")
        val dayWords = listOf("ngày")
        val monthWords = listOf("tháng")
        val yearWords = listOf("năm")
        val agoWords = listOf("trước")

        return try {
            if (agoWords.any { this.contains(it, ignoreCase = true) }) {
                val parts = this.removeSuffix(" trước").trim().split(" ")
                if (parts.size < 2) return 0L
                val amount = parts[0].toIntOrNull() ?: return 0L

                val calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"), Locale.ROOT)

                when {
                    yearWords.contains(parts[1]) -> calendar.add(Calendar.YEAR, -amount)
                    monthWords.contains(parts[1]) -> calendar.add(Calendar.MONTH, -amount)
                    dayWords.contains(parts[1]) -> calendar.add(Calendar.DAY_OF_MONTH, -amount)
                    hourWords.contains(parts[1]) -> calendar.add(Calendar.HOUR_OF_DAY, -amount)
                }

                val formatted = dateFormat.format(calendar.time)
                dateFormat.parse(formatted)?.time ?: calendar.timeInMillis
            } else {
                0L
            }
        } catch (_: Exception) {
            0L
        }
    }

    // Pages
    override fun pageListParse(document: Document): List<Page> {
        return document.select("#read-chaps .mi-item img.reading-img").mapIndexed { index, element ->
            val img = when {
                element.hasAttr("data-src") -> element.attr("abs:data-src")
                else -> element.attr("abs:src")
            }
            Page(index, imageUrl = img)
        }.distinctBy { it.imageUrl }
    }
}
