package eu.kanade.tachiyomi.extension.vi.soaicacomic

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.tryParse
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class SoaiCaComic : HttpSource() {

    override val name = "SoaiCaComic"
    override val lang = "vi"
    override val supportsLatest = true

    override val baseUrl = "https://soaicacomic2.top"

    override val client = network.cloudflareClient.newBuilder()
        .rateLimit(5)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/xem-nhieu-nhat/", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val mangas = parseMangaList(response.asJsoup())
        return MangasPage(mangas, hasNextPage = false)
    }

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int) = GET(buildPagedUrl("truyen-moi", page), headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        return MangasPage(parseMangaList(document), hasNextPage(document))
    }

    // ============================== Search ================================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): okhttp3.Request {
        if (query.isNotBlank()) {
            val url = buildPagedHttpUrl("danh-sach-truyen", page)
                .newBuilder()
                .addQueryParameter(QUERY_PARAM, query)
                .build()
            return GET(url, headers)
        }

        val uriPart = listOf(
            filters.firstInstanceOrNull<GenreFilter>()?.toUriPart(),
            filters.firstInstanceOrNull<TeamFilter>()?.toUriPart(),
            filters.firstInstanceOrNull<SeriesFilter>()?.toUriPart(),
            filters.firstInstanceOrNull<KeywordFilter>()?.toUriPart(),
        ).firstOrNull { it != null }

        if (uriPart != null) {
            return GET(buildPagedUrl(uriPart, page), headers)
        }

        return latestUpdatesRequest(page)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val query = response.request.url.queryParameter(QUERY_PARAM)

        val mangas = parseMangaList(document).let { list ->
            if (query.isNullOrBlank()) {
                list
            } else {
                list.filter { it.title.contains(query, ignoreCase = true) }
            }
        }

        return MangasPage(mangas, hasNextPage(document))
    }

    // ============================== Details ===============================

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val infoBlock = document.selectFirst(".comic-info, .comic-intro")

        return SManga.create().apply {
            title = document.selectFirst("h2.info-title, .info-title")!!.text()
            thumbnail_url = infoBlock
                ?.selectFirst("img.img-thumbnail, img")
                ?.absUrl("src")
                ?.takeIf { it.isNotBlank() }
            author = infoBlock
                ?.selectFirst("strong:containsOwn(Tác giả) + span")
                ?.text()
                ?.takeUnless(::isUnknownText)
            status = parseStatus(infoBlock?.selectFirst("span.comic-stt")?.text())
            genre = infoBlock
                ?.select(".tags a[href*=/the-loai/]")
                ?.joinToString { it.text() }
                ?.ifEmpty { null }
            description = parseDescription(document)
        }
    }

    private fun parseDescription(document: Document): String? {
        val block = document.selectFirst(".intro-container .hide-long-text, .intro-container > p")
            ?: return null

        val description = block.text()
            .substringBefore("— Xem Thêm —")
            .substringBefore("- Xem thêm -")
            .removePrefix("\"")
            .removeSuffix("\"")
            .trim()

        return description.takeUnless(::isUnknownText)
    }

    private fun parseStatus(status: String?): Int = when {
        status == null -> SManga.UNKNOWN
        status.contains("Đang tiến hành", ignoreCase = true) -> SManga.ONGOING
        status.contains("Hoàn thành", ignoreCase = true) -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    // ============================== Chapters ==============================

    override fun chapterListParse(response: Response): List<SChapter> {
        val chapters = mutableListOf<SChapter>()
        var document = response.asJsoup()

        chapters += document.select(".chapter-table table tbody tr").mapNotNull(::parseChapterElement)

        val visited = mutableSetOf(response.request.url.toString())
        var nextPage = document.selectFirst("ul.pager li.next:not(.disabled) a")
            ?.absUrl("href")
            .nullIfBlank()

        while (nextPage != null && visited.add(nextPage)) {
            client.newCall(GET(nextPage, headers)).execute().use { pageResponse ->
                document = pageResponse.asJsoup()
                chapters += document.select(".chapter-table table tbody tr").mapNotNull(::parseChapterElement)
                nextPage = document.selectFirst("ul.pager li.next:not(.disabled) a")
                    ?.absUrl("href")
                    .nullIfBlank()
            }
        }

        return chapters
    }

    private fun parseChapterElement(element: Element): SChapter? {
        val linkElement = element.selectFirst("a.text-capitalize") ?: return null
        val chapterUrl = linkElement.absUrl("href").nullIfBlank() ?: return null

        val isLocked = linkElement.selectFirst(".glyphicon-lock, .fa-lock, .icon-lock") != null

        return SChapter.create().apply {
            setUrlWithoutDomain(chapterUrl)

            val fullText = linkElement.selectFirst("span.hidden-sm.hidden-xs")?.text()
                ?: linkElement.text()
            val parsedName = parseChapterName(fullText)
            name = if (isLocked) "🔒 $parsedName" else parsedName

            date_upload = element
                .selectFirst("td.hidden-xs.hidden-sm, td:last-child")
                ?.text()
                ?.let(::parseChapterDate)
                ?: 0L
        }
    }

    private fun parseChapterName(rawName: String): String {
        val match = CHAPTER_NAME_REGEX.find(rawName)
        if (match != null) {
            return match.value
                .replace(CHAPTER_WORD_REGEX, "CHAP")
                .replace(MULTI_SPACE_REGEX, " ")
                .trim()
        }

        return rawName.substringAfterLast("–").substringAfterLast("-").trim()
    }

    private fun parseChapterDate(date: String): Long {
        return DATE_FORMAT.tryParse(date)
    }

    // ============================== Pages =================================

    override fun pageListParse(response: Response): List<Page> {
        val html = response.body.string()
        if (PASSWORD_INPUT_REGEX.containsMatchIn(html)) {
            throw Exception(PASSWORD_WEBVIEW_MESSAGE)
        }

        val imageUrls = ImageDecryptor.extractImageUrls(html)
        if (imageUrls.isEmpty()) {
            throw Exception("Không tìm thấy hình ảnh")
        }

        return imageUrls.mapIndexed { index, imageUrl ->
            Page(index, imageUrl = imageUrl)
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun getFilterList(): FilterList = getFilters()

    private fun parseMangaList(document: Document): List<SManga> = document
        .select("ul.most-views.single-list-comic li.position-relative")
        .mapNotNull { element ->
            val linkElement = element.selectFirst("p.super-title a") ?: return@mapNotNull null
            val imageElement = element.selectFirst("img.list-left-img, img")

            SManga.create().apply {
                title = linkElement.text()
                setUrlWithoutDomain(linkElement.absUrl("href"))
                thumbnail_url = imageElement
                    ?.absUrl("data-src")
                    .orEmpty()
                    .ifEmpty { imageElement?.absUrl("src").orEmpty() }
                    .ifEmpty { null }
            }
        }
        .distinctBy { it.url }

    private fun buildPagedHttpUrl(path: String, page: Int): HttpUrl = buildPagedUrl(path, page).toHttpUrl()

    private fun buildPagedUrl(path: String, page: Int): String {
        val normalizedPath = path.trim('/').ifEmpty { "danh-sach-truyen" }
        return if (page == 1) {
            "$baseUrl/$normalizedPath/"
        } else {
            "$baseUrl/$normalizedPath/page/$page/"
        }
    }

    private fun hasNextPage(document: Document): Boolean =
        document.selectFirst("ul.pager li.next:not(.disabled) a") != null

    private fun String?.nullIfBlank(): String? = this?.takeIf { it.isNotBlank() }

    private fun isUnknownText(value: String): Boolean {
        val normalized = value.trim()
        return normalized.isEmpty() ||
            normalized.equals("Đang cập nhật", ignoreCase = true) ||
            normalized.equals("Đang cập nhật...", ignoreCase = true) ||
            normalized.equals("Không có", ignoreCase = true)
    }

    companion object {
        private const val QUERY_PARAM = "keyword"
        private const val PASSWORD_WEBVIEW_MESSAGE = "Vui lòng nhập mật khẩu của chương này qua webview"

        private val PASSWORD_INPUT_REGEX = Regex("""name\s*=\s*["']post_password["']""")
        private val CHAPTER_NAME_REGEX = Regex("chap\\s*\\d+(?:\\.\\d+)?", RegexOption.IGNORE_CASE)
        private val CHAPTER_WORD_REGEX = Regex("chap", RegexOption.IGNORE_CASE)
        private val MULTI_SPACE_REGEX = Regex("\\s+")

        private val DATE_FORMAT by lazy {
            SimpleDateFormat("dd/MM/yyyy", Locale.ROOT).apply {
                timeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
            }
        }
    }
}
