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
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class LuvEvaLand : HttpSource(), ConfigurableSource {
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

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/truyen-tranh", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("#total-tab-content .comic-item").map { element ->
            SManga.create().apply {
                element.selectFirst("a.comic-name")!!.let {
                    title = it.text()
                    setUrlWithoutDomain(it.absUrl("href"))
                }
                thumbnail_url = element.selectFirst(".comic-img img")
                    ?.absDataSrc()
            }
        }
        return MangasPage(mangas, false)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int) =
        GET("$baseUrl/danh-sach-chuong-moi-cap-nhat?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(".comic-box-container .comic-image-card-item")
            .mapNotNull { element ->
                val link = element.selectFirst(".book__lg-title a")
                    ?: element.selectFirst(".book__lg-image a")
                    ?: return@mapNotNull null
                val url = link.absUrl("href")
                if ("/truyen-tranh/" !in url) return@mapNotNull null
                SManga.create().apply {
                    setUrlWithoutDomain(url)
                    title = element.selectFirst(".book__lg-title a")?.text()
                        ?: link.selectFirst("img")?.attr("alt")
                        ?: throw Exception("Title not found")
                    thumbnail_url = element.selectFirst(".book__lg-image img")
                        ?.absDataSrc()
                }
            }
        val hasNextPage = document.selectFirst(".pagination .page-item.active + .page-item a") != null
        return MangasPage(mangas, hasNextPage)
    }

    // =============================== Search ===============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/tim-kiem".toHttpUrl().newBuilder().apply {
            addQueryParameter("s", query)
            addQueryParameter("comic_type", "1")
            if (query.isBlank()) {
                filters.firstInstanceOrNull<GenreFilter>()?.selectedValues()?.forEach {
                    addQueryParameter("genres[]", it)
                }
                filters.firstInstanceOrNull<StatusFilter>()?.let {
                    addQueryParameter("status", it.toUriPart())
                }
                filters.firstInstanceOrNull<SortByFilter>()?.let {
                    addQueryParameter("sort-by", it.toUriPart())
                }
                filters.firstInstanceOrNull<SortOrderFilter>()?.let {
                    addQueryParameter("sort-desc", it.toUriPart())
                }
            }
            addQueryParameter("page", page.toString())
        }.build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(".comic-wrap-grid .book__list-item")
            .mapNotNull { element ->
                val link = element.selectFirst(".book__list-name a")
                    ?: return@mapNotNull null
                val url = link.absUrl("href")
                if ("/truyen-tranh/" !in url) return@mapNotNull null
                SManga.create().apply {
                    setUrlWithoutDomain(url)
                    title = link.text()
                    thumbnail_url = element.selectFirst(".book__list-image img")
                        ?.absDataSrc()
                        ?.replace(THUMBNAIL_SIZE_REGEX, ".")
                }
            }
        val hasNextPage = document.selectFirst(".pagination .page-item.active + .page-item a") != null
        return MangasPage(mangas, hasNextPage)
    }

    override fun getFilterList() = getFilters()

    // =============================== Details ==============================

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst(".book__detail-name")?.text()
                ?: throw Exception("Title not found")
            thumbnail_url = document.selectFirst(".book__detail-image img")
                ?.absUrl("src")
            author = document.selectFirst(".book__detail-text:contains(Tác giả) a")?.text()
            genre = document.select(".book__detail-text:contains(Tag) a").joinToString { it.text() }
                .ifEmpty { null }
            status = document.selectFirst(".book__detail-text:contains(Tình trạng)")?.text()
                .parseStatus()
            description = document.selectFirst("#home")?.text()
        }
    }

    private fun String?.parseStatus(): Int = when {
        this == null -> SManga.UNKNOWN
        contains("Hoàn thành") -> SManga.COMPLETED
        contains("Đang tiến hành") -> SManga.ONGOING
        contains("Drop") -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    // ============================== Chapters ==============================

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select("table tr.sort-item").map { element ->
            SChapter.create().apply {
                val nameCell = element.selectFirst("td.list-chapter__name a")!!
                val costCell = element.selectFirst("td.list-chapter__cost")

                val isLocked = costCell?.selectFirst("img[src*=lock-chapter]") != null
                val chapterUrl = if (isLocked) {
                    costCell!!.selectFirst("a")!!.absUrl("href")
                } else {
                    nameCell.absUrl("href")
                }
                setUrlWithoutDomain(chapterUrl)

                name = buildString {
                    if (isLocked) append(LOCK_PREFIX)
                    append(nameCell.text())
                }

                date_upload = element.selectFirst("td.list-chapter__date")?.text()
                    .let { DATE_FORMAT.tryParse(it ?: "") }
            }
        }.reversed()
    }

    // =============================== Pages ================================

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()

        val images = document.select(".chapter__content img[data-src]")
        if (images.isNotEmpty()) {
            return images.mapIndexed { idx, img ->
                Page(idx, imageUrl = img.absUrl("data-src"))
            }
        }

        val hasPasswordForm = document.select("form:has(input[type=password])")
            .any { !it.hasClass("login-form") && !it.hasClass("register-form") }

        if (hasPasswordForm) {
            throw Exception("Vui lòng nhập mật khẩu cho chương này bằng webview")
        }

        throw Exception("Vui lòng đăng nhập bằng webview để xem chương này")
    }

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException()

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

    // ============================== Helpers ===============================

    private fun Element.absDataSrc(): String? =
        attr("abs:data-src").ifEmpty { absUrl("src") }.ifEmpty { null }

    companion object {
        private const val DEFAULT_BASE_URL_PREF = "defaultBaseUrl"
        private const val BASE_URL_PREF = "overrideBaseUrl"
        private const val BASE_URL_PREF_TITLE = "Ghi đè URL cơ sở"
        private const val BASE_URL_PREF_SUMMARY = "Dành cho sử dụng tạm thời, cập nhật tiện ích sẽ xóa cài đặt."
        private const val LOCK_PREFIX = "\uD83D\uDD12 "

        private val WEBVIEW_TOKEN_REGEX = Regex("""\;\s*wv\)""")

        private val THUMBNAIL_SIZE_REGEX = Regex("""-\d+x\d+\.""")

        private val DATE_FORMAT = SimpleDateFormat("dd/MM/yyyy", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
        }
    }
}
