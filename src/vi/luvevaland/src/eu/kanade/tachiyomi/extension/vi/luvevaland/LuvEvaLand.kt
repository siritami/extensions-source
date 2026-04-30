package eu.kanade.tachiyomi.extension.vi.luvevaland

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferences
import keiyoushi.utils.tryParse
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class LuvEvaLand :
    HttpSource(),
    ConfigurableSource {

    override val name = "LuvEvaLand"

    override val lang = "vi"

    private val defaultBaseUrl = "https://luvevalands2.co"

    override val baseUrl get() = getPrefBaseUrl()

    override val supportsLatest = true

    private val preferences: SharedPreferences = getPreferences {
        getString(DEFAULT_BASE_URL_PREF, null).let { prefDefaultBaseUrl ->
            if (prefDefaultBaseUrl != defaultBaseUrl) {
                edit()
                    .putString(BASE_URL_PREF, defaultBaseUrl)
                    .putString(DEFAULT_BASE_URL_PREF, defaultBaseUrl)
                    .apply()
            }
        }
    }

    override val client = network.cloudflareClient.newBuilder()
        .rateLimit(3)
        .build()

    // Strip "wv" from User-Agent so Google login works in this source.
    // Google deny login when User-Agent contains the WebView token.
    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .apply {
            build()["user-agent"]?.let { userAgent ->
                set("user-agent", removeWebViewToken(userAgent))
            }
        }

    private fun removeWebViewToken(userAgent: String): String = userAgent.replace(WEBVIEW_TOKEN_REGEX, ")")

    // ============================== Popular ===============================

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/truyen-tranh", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()

        val mangas = document.select("#total-tab-content .comic-item")
            .mapNotNull(::popularMangaFromElement)
            .distinctBy { it.url }

        return MangasPage(mangas, hasNextPage = false)
    }

    private fun popularMangaFromElement(element: Element): SManga? {
        val mangaLinkElement = element.select("a[href*=/truyen-tranh/]")
            .firstOrNull {
                val href = it.absUrl("href")
                href.isNotEmpty() && !CHAPTER_PATH_REGEX.containsMatchIn(href)
            }
            ?: return null

        val titleElement = element.selectFirst("a.comic-name") ?: mangaLinkElement
        val mangaTitle = titleElement.text().ifEmpty { mangaLinkElement.selectFirst("img")?.attr("alt").orEmpty() }
        if (mangaTitle.isEmpty()) return null

        return SManga.create().apply {
            title = mangaTitle
            setUrlWithoutDomain(mangaLinkElement.absUrl("href"))
            thumbnail_url = normalizeThumbnail(extractImageUrl(element.selectFirst(".comic-img img, img")))
        }
    }

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/danh-sach-chuong-moi-cap-nhat".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .build()

        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()

        val mangas = document.select(".home__lg-book .book-vertical__item")
            .mapNotNull(::latestMangaFromElement)
            .distinctBy { it.url }

        val hasNextPage = document.selectFirst("ul.pagination a[rel=next]") != null
        return MangasPage(mangas, hasNextPage)
    }

    private fun latestMangaFromElement(element: Element): SManga? {
        val mangaLinkElement = element.selectFirst(".book__lg-title a[href*=/truyen-tranh/], .book__lg-image a[href*=/truyen-tranh/]")
            ?: return null

        val mangaUrl = mangaLinkElement.absUrl("href")
        if (!MANGA_PATH_REGEX.containsMatchIn(mangaUrl)) return null

        val mangaTitle = mangaLinkElement.text().ifEmpty {
            element.selectFirst(".book__lg-image img")?.attr("alt").orEmpty()
        }
        if (mangaTitle.isEmpty()) return null

        return SManga.create().apply {
            title = mangaTitle
            setUrlWithoutDomain(mangaUrl)
            thumbnail_url = normalizeThumbnail(extractImageUrl(element.selectFirst(".book__lg-image img, img")))
        }
    }

    // ============================== Search ================================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val genres = filters.firstInstanceOrNull<GenreFilter>()?.selectedValues().orEmpty()
        val status = filters.firstInstanceOrNull<StatusFilter>()?.toUriPart()
        val sortBy = filters.firstInstanceOrNull<SortByFilter>()?.toUriPart() ?: "name"
        val sortOrder = filters.firstInstanceOrNull<SortOrderFilter>()?.toUriPart() ?: "desc"

        val urlBuilder = "$baseUrl/tim-kiem".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("s", query)
            .addQueryParameter("comic_type", "1")
            .addQueryParameter("status", status)
            .addQueryParameter("sort-by", sortBy)
            .addQueryParameter("sort-desc", sortOrder)
            .addQueryParameter("sort-view", null)
            .addQueryParameter("sort-number-chapter", null)
            .addQueryParameter("sort-date-update", null)
            .addQueryParameter("sort-number-word", null)

        genres.forEach { genreId ->
            urlBuilder.addQueryParameter("genres[]", genreId)
        }

        return GET(urlBuilder.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()

        val mangas = parseSearchManga(document)
            .distinctBy { it.url }

        val hasNextPage = document.selectFirst("ul.pagination a[rel=next]") != null
        return MangasPage(mangas, hasNextPage)
    }

    private fun parseSearchManga(document: Document): List<SManga> {
        val titleElement = document.select("div.title__color")
            .firstOrNull { RESULT_TITLE_REGEX.matches(it.text()) }
            ?: return emptyList()

        val table = titleElement.nextElementSibling()
            ?.takeIf { it.tagName() == "table" }
            ?: titleElement.parent()?.selectFirst("table.book__list")
            ?: return emptyList()

        return table.select("tr.book__list-item")
            .mapNotNull(::searchMangaFromRow)
    }

    private fun searchMangaFromRow(element: Element): SManga? {
        val linkElement = element.selectFirst("td.book__list-name a[href], td.book__list-image a[href]") ?: return null
        val mangaUrl = linkElement.absUrl("href")
        if (!MANGA_PATH_REGEX.containsMatchIn(mangaUrl)) return null

        val mangaTitle = linkElement.ownText()
            .ifEmpty { linkElement.text() }
            .ifEmpty { element.selectFirst("img")?.attr("alt").orEmpty() }
        if (mangaTitle.isEmpty()) return null

        return SManga.create().apply {
            title = mangaTitle
            setUrlWithoutDomain(mangaUrl)
            thumbnail_url = normalizeThumbnail(extractImageUrl(element.selectFirst("td.book__list-image img, img")))
        }
    }

    // ============================== Details ===============================

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()

        return SManga.create().apply {
            title = document.selectFirst("h1.comic-name-detail, .comic-name-detail, h1.comic-name")!!.text()
            thumbnail_url = normalizeThumbnail(
                extractImageUrl(document.selectFirst(".comic-image img, .comic-info img, .comic-image-detail img")),
            )
            author = document.selectFirst(".comic-author a")?.text()
                ?: document.selectFirst(".comic-author")?.text()?.substringAfter(": ")
            status = parseStatus(document.selectFirst(".comic-status-detail, .comic-status")?.text())
            genre = document.select("a[href*=/the-loai/]")
                .joinToString { it.text() }
                .ifEmpty { null }
            description = parseDescription(document)
        }
    }

    private fun parseDescription(document: Document): String? {
        val introPaneId = document.selectFirst("a[role=tab][href^=#]:matchesOwn((?i)GIỚI THIỆU)")
            ?.attr("href")

        val introElement = introPaneId?.let { document.selectFirst(it) }
            ?: document.selectFirst("#intro-tab-content, #comic-intro, .tab-content .tab-pane.active.in, .tab-content .tab-pane.active")

        return introElement?.text()?.ifEmpty { null }
    }

    private fun parseStatus(statusText: String?): Int = when {
        statusText == null -> SManga.UNKNOWN
        statusText.contains("đang tiến hành", ignoreCase = true) -> SManga.ONGOING
        statusText.contains("hoàn thành", ignoreCase = true) -> SManga.COMPLETED
        statusText.contains("truyện full", ignoreCase = true) -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    // ============================== Chapters ==============================

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()

        return document.select("table.list-chapter tbody tr.sort-item")
            .mapNotNull(::chapterFromRow)
            .sortedByDescending { it.first }
            .map { it.second }
    }

    private fun chapterFromRow(element: Element): Pair<Int, SChapter>? {
        val chapterNameElement = element.selectFirst("td.list-chapter__name a") ?: return null

        val chapterLinkElement = element.selectFirst("td.list-chapter__cost a[href]") ?: chapterNameElement
        val chapterUrl = chapterLinkElement.absUrl("href")
        if (!CHAPTER_PATH_REGEX.containsMatchIn(chapterUrl)) return null

        val chapterName = chapterNameElement.ownText().ifEmpty { chapterNameElement.text() }

        val isLocked =
            chapterNameElement.attr("href").startsWith("javascript") ||
                element.selectFirst("td.list-chapter__cost img[src*=lock], td.list-chapter__cost img[alt*=khóa], td.list-chapter__cost .chapter-icon") != null

        val chapterOrder = element.attr("data-order").toIntOrNull()
            ?: CHAPTER_NUMBER_REGEX.find(chapterUrl)?.groupValues?.get(1)?.toIntOrNull()
            ?: 0

        val chapterDate = element.selectFirst("td.list-chapter__date")
            ?.text()
            ?.let { DATE_FORMAT.tryParse(it) }
            ?: 0L

        return chapterOrder to SChapter.create().apply {
            name = if (isLocked) "🔒 $chapterName" else chapterName
            setUrlWithoutDomain(chapterUrl)
            date_upload = chapterDate
        }
    }

    // ============================== Pages =================================

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()

        if (isPasswordRequired(response, document)) {
            throw Exception(PASSWORD_WEBVIEW_MESSAGE)
        }

        val images = document.select("#view-chapter img, .chapter-content img, .reading-content img, .content-chapter img, .box-chapter-content img")
            .map { imageElement ->
                imageElement.absUrl("data-src").ifEmpty { imageElement.absUrl("src") }
            }
            .filter { imageUrl ->
                imageUrl.isNotBlank() && !imageUrl.startsWith("data:image")
            }

        if (images.isEmpty() && isLoginRequired(response, document)) {
            throw Exception(LOGIN_WEBVIEW_MESSAGE)
        }

        if (images.isEmpty()) {
            throw Exception("Không tìm thấy hình ảnh")
        }

        return images.mapIndexed { index, imageUrl ->
            Page(index, imageUrl = imageUrl)
        }
    }

    private fun isPasswordRequired(response: Response, document: Document): Boolean {
        val path = response.request.url.encodedPath

        return path.contains("/mo-khoa/chap/") ||
            document.selectFirst("form.unlock-chapter-form input[name=password], form.unlock-chapter-form") != null
    }

    private fun isLoginRequired(response: Response, document: Document): Boolean {
        val path = response.request.url.encodedPath

        return !CHAPTER_PATH_REGEX.containsMatchIn(path) ||
            document.selectFirst(".swal2-container .swal2-content:matchesOwn((?i)đăng nhập)") != null ||
            (
                document.selectFirst("form.login-form") != null &&
                    document.selectFirst("table.list-chapter") != null &&
                    document.selectFirst("#view-chapter img") == null
                )
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================== Helpers ===============================

    private fun extractImageUrl(element: Element?): String? {
        if (element == null) return null

        val imageUrl = element.absUrl("data-src")
            .ifEmpty { element.absUrl("src") }

        return imageUrl.ifEmpty { null }
    }

    private fun normalizeThumbnail(url: String?): String? {
        if (url == null) return null
        return url.replace(THUMBNAIL_SIZE_REGEX, "")
    }

    // ============================== Filters ===============================

    override fun getFilterList(): FilterList = getFilters()

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF
            title = BASE_URL_PREF_TITLE
            summary = BASE_URL_PREF_SUMMARY
            setDefaultValue(defaultBaseUrl)
            dialogTitle = BASE_URL_PREF_TITLE
            dialogMessage = "Default: $defaultBaseUrl"
        }.let(screen::addPreference)
    }

    private fun getPrefBaseUrl(): String = preferences.getString(BASE_URL_PREF, defaultBaseUrl)!!

    companion object {
        private const val LOGIN_WEBVIEW_MESSAGE = "Vui lòng đăng nhập bằng webview để xem chương này"
        private const val PASSWORD_WEBVIEW_MESSAGE = "Vui lòng nhập mật khẩu cho chương này bằng webview"

        private val WEBVIEW_TOKEN_REGEX = Regex(""";\s*wv\)""")
        private val MANGA_PATH_REGEX = Regex("""/truyen-tranh/""")
        private val CHAPTER_PATH_REGEX = Regex("""/chap-[^/]+\.[0-9]+/?$""")
        private val CHAPTER_NUMBER_REGEX = Regex("""/chap-([0-9]+)""")
        private val RESULT_TITLE_REGEX = Regex("""(?i)kết\s+quả\s+truyện""")
        private val THUMBNAIL_SIZE_REGEX = Regex("""-[0-9]+x[0-9]+(?=\.(?:jpe?g|png|webp)$)""")

        private val DATE_FORMAT by lazy {
            SimpleDateFormat("dd/MM/yyyy", Locale.ROOT).apply {
                timeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
            }
        }

        private const val DEFAULT_BASE_URL_PREF = "defaultBaseUrl"
        private const val BASE_URL_PREF = "overrideBaseUrl"
        private const val BASE_URL_PREF_TITLE = "Ghi đè URL cơ sở"
        private const val BASE_URL_PREF_SUMMARY = "Dành cho sử dụng tạm thời, cập nhật tiện ích sẽ xóa cài đặt."
    }
}
