package eu.kanade.tachiyomi.extension.vi.luottruyen

import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferences
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.Calendar

class LuotTruyen :
    HttpSource(),
    ConfigurableSource {

    override val name = "LuotTruyen"

    override val lang = "vi"

    private val defaultBaseUrl = "https://luottruyen1.com"

    override val baseUrl by lazy { getPrefBaseUrl() }

    override val supportsLatest = true

    private val preferences: SharedPreferences = getPreferences()

    private var hasCheckedRedirect = false

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .addInterceptor { chain ->
            val originalRequest = chain.request()
            val response = chain.proceed(originalRequest)
            if (!hasCheckedRedirect && preferences.getBoolean(AUTO_CHANGE_DOMAIN_PREF, false)) {
                hasCheckedRedirect = true
                val originalHost = defaultBaseUrl.toHttpUrl().host
                val newHost = response.request.url.host
                if (newHost != originalHost) {
                    val newBaseUrl = "${response.request.url.scheme}://$newHost"
                    preferences.edit()
                        .putString(BASE_URL_PREF, newBaseUrl)
                        .apply()
                }
            }
            response
        }
        .cookieJar(
            object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    // Do nothing - we don't need to save cookies
                }

                override fun loadForRequest(url: HttpUrl): List<Cookie> {
                    val authCookie = preferences.getString(AUTH_COOKIE_PREF, "")
                    if (authCookie.isNullOrBlank()) {
                        return emptyList()
                    }
                    return listOf(
                        Cookie.Builder()
                            .domain(url.host)
                            .path("/")
                            .name(".truyen_AUTH")
                            .value(authCookie)
                            .build(),
                    )
                }
            },
        )
        .build()

    init {
        preferences.getString(DEFAULT_BASE_URL_PREF, null).let { prefDefaultBaseUrl ->
            if (prefDefaultBaseUrl != defaultBaseUrl) {
                preferences.edit()
                    .putString(BASE_URL_PREF, defaultBaseUrl)
                    .putString(DEFAULT_BASE_URL_PREF, defaultBaseUrl)
                    .apply()
            }
        }
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/tim-truyen?status=-1&sort=10" + if (page > 1) "&page=$page" else "", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()

        val mangaList = document.select("div.item").map { element ->
            SManga.create().apply {
                element.selectFirst("figcaption h3 a, a.jtip")!!.let {
                    title = it.text()
                    setUrlWithoutDomain(it.attr("abs:href"))
                }
                thumbnail_url = element.selectFirst("div.image a img")?.absUrl("src")
            }
        }

        val hasNextPage = document.selectFirst("li.next:not(.disabled) a, li:not(.disabled).next a") != null

        return MangasPage(mangaList, hasNextPage)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/?page=$page&typegroup=0", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()

        // Use #ctl00_divCenter to exclude slider items from owl-carousel
        val mangaList = document.select("#ctl00_divCenter .row > .item").map { element ->
            SManga.create().apply {
                element.selectFirst("figcaption h3 a, a.jtip")!!.let {
                    title = it.text()
                    setUrlWithoutDomain(it.attr("abs:href"))
                }
                thumbnail_url = element.selectFirst("div.image a img")?.absUrl("src")
            }
        }

        val hasNextPage = document.selectFirst("li.next:not(.disabled) a, li:not(.disabled).next a") != null

        return MangasPage(mangaList, hasNextPage)
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
                thumbnail_url = info.selectFirst("div.col-image img")?.absUrl("src")
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

    override fun fetchChapterList(manga: SManga): rx.Observable<List<SChapter>> {
        return rx.Observable.fromCallable {
            // Fetch chapters
            val storyId = manga.url.substringAfterLast("-")
            val formBody = FormBody.Builder()
                .add("StoryID", storyId)
                .build()

            val chapterHeaders = headersBuilder()
                .add("X-Requested-With", "XMLHttpRequest")
                .add("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .build()

            val chapterResponse = client.newCall(POST("$baseUrl/Story/ListChapterByStoryID", chapterHeaders, formBody)).execute()
            val chapterDocument = chapterResponse.asJsoup()

            chapterDocument.select("li.row:not(.heading)").map { element ->
                SChapter.create().apply {
                    element.selectFirst("div.chapter a, a")?.let {
                        name = it.text()
                        setUrlWithoutDomain(it.attr("href"))
                    }
                    date_upload = parseRelativeDate(element.selectFirst("div.col-xs-4")?.text())
                }
            }
        }
    }

    override fun chapterListRequest(manga: SManga): Request {
        // Extract story ID from manga URL (e.g., /truyen-tranh/manga-name-12345 -> 12345)
        val storyId = manga.url.substringAfterLast("-")

        val formBody = FormBody.Builder()
            .add("StoryID", storyId)
            .build()

        val chapterHeaders = headersBuilder()
            .add("X-Requested-With", "XMLHttpRequest")
            .add("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .build()

        return POST("$baseUrl/Story/ListChapterByStoryID", chapterHeaders, formBody)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()

        return document.select("li.row:not(.heading)").map { element ->
            SChapter.create().apply {
                element.selectFirst("div.chapter a, a")?.let {
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

        val images = document.select(".reading-detail .page-chapter img[data-index]")

        return images.mapIndexed { i, img -> Page(i, imageUrl = img.absUrl("src")) }
    }

    override fun imageUrlParse(response: Response): String {
        throw UnsupportedOperationException()
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF
            title = BASE_URL_PREF_TITLE
            summary = BASE_URL_PREF_SUMMARY
            setDefaultValue(defaultBaseUrl)
            dialogTitle = BASE_URL_PREF_TITLE
            dialogMessage = "Default: $defaultBaseUrl"

            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(screen.context, RESTART_APP, Toast.LENGTH_LONG).show()
                true
            }
        }.let(screen::addPreference)

        androidx.preference.SwitchPreferenceCompat(screen.context).apply {
            key = AUTO_CHANGE_DOMAIN_PREF
            title = AUTO_CHANGE_DOMAIN_TITLE
            summary = AUTO_CHANGE_DOMAIN_SUMMARY
            setDefaultValue(false)
        }.let(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = AUTH_COOKIE_PREF
            title = AUTH_COOKIE_TITLE
            summary = AUTH_COOKIE_SUMMARY
            setDefaultValue("")
            dialogTitle = AUTH_COOKIE_TITLE
            dialogMessage = """
                |Hướng dẫn lấy cookie:
                |1. Mở trình duyệt (Chrome/Edge) trên máy tính
                |2. Truy cập $baseUrl và đăng nhập bằng Google
                |3. Nhấn F12 để mở DevTools
                |4. Chọn tab Application (hoặc Storage)
                |5. Ở menu bên trái, chọn Cookies → $baseUrl
                |6. Tìm cookie tên ".truyen_AUTH"
                |7. Sao chép giá trị (Value) và dán vào ô bên dưới
                |
                |Lưu ý: Cookie có thể hết hạn, cần cập nhật lại khi không đọc được.
            """.trimMargin()

            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(screen.context, RESTART_APP, Toast.LENGTH_LONG).show()
                true
            }
        }.let(screen::addPreference)
    }

    private fun getPrefBaseUrl(): String = preferences.getString(BASE_URL_PREF, defaultBaseUrl)!!

    companion object {
        private const val DEFAULT_BASE_URL_PREF = "defaultBaseUrl"
        private const val BASE_URL_PREF = "overrideBaseUrl"
        private const val BASE_URL_PREF_TITLE = "Ghi đè URL cơ sở"
        private const val BASE_URL_PREF_SUMMARY = "Dành cho sử dụng tạm thời, cập nhật tiện ích sẽ xóa cài đặt."

        private const val AUTO_CHANGE_DOMAIN_PREF = "autoChangeDomain"
        private const val AUTO_CHANGE_DOMAIN_TITLE = "Tự động cập nhật domain"
        private const val AUTO_CHANGE_DOMAIN_SUMMARY = "Khi mở ứng dụng, ứng dụng sẽ tự động cập nhật domain mới nếu website chuyển hướng."

        private const val AUTH_COOKIE_PREF = "authCookie"
        private const val AUTH_COOKIE_TITLE = "Cookie xác thực"
        private const val AUTH_COOKIE_SUMMARY = "Nhập cookie để đọc truyện cần đăng nhập"
        private const val RESTART_APP = "Khởi chạy lại ứng dụng để áp dụng thay đổi."
    }

    // ============================== Filters ===============================

    override fun getFilterList(): FilterList = getFilters()
}
