package eu.kanade.tachiyomi.extension.vi.toptruyen

import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.multisrc.wpcomics.WPComics
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.getPreferences
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

class TopTruyen :
    WPComics(
        "Top Truyen",
        "https://www.toptruyentv.pro",
        "vi",
        dateFormat = SimpleDateFormat("dd-MM-yyyy", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
        },
        gmtOffset = null,
    ),
    ConfigurableSource {

    private val preferences: SharedPreferences = getPreferences()

    // Standard client with rate limiting
    override val client = super.client.newBuilder()
        .rateLimit(3)
        .build()

    init {
        // Check for domain updates only when the app starts (once per session)
        if (preferences.getBoolean(AUTO_CHANGE_DOMAIN_PREF, false)) {
            val currentSessionId = UUID.randomUUID().toString()
            val lastSessionId = preferences.getString(LAST_SESSION_ID, "")
            if (currentSessionId != lastSessionId) {
                runBlocking {
                    try {
                        val testRequest = Request.Builder()
                            .url(super.baseUrl)
                            .build()

                        val response = client.newCall(testRequest).execute()

                        val originalHost = super.baseUrl.toHttpUrl().host

                        val newHost = response.request.url.host

                        if (newHost != originalHost) {
                            val newBaseUrl = "${response.request.url.scheme}://$newHost"
                            preferences.edit()
                                .putString(BASE_URL_PREF, newBaseUrl)
                                .putString(DEFAULT_BASE_URL_PREF, newBaseUrl)
                                .apply()
                        }

                        preferences.edit()
                            .putString(LAST_SESSION_ID, currentSessionId)
                            .apply()

                        response.close()
                    } catch (e: Exception) {
                    }
                }
            }
        } else {
            preferences.getString(DEFAULT_BASE_URL_PREF, null).let { prefDefaultBaseUrl ->
                if (prefDefaultBaseUrl != super.baseUrl) {
                    preferences.edit()
                        .putString(BASE_URL_PREF, super.baseUrl)
                        .putString(DEFAULT_BASE_URL_PREF, super.baseUrl)
                        .apply()
                }
            }
        }
    }

    override fun pageListParse(document: Document): List<Page> {
        return document.select("div[id^=page_].page-chapter img").mapIndexed { index, element ->
            val img = element.attr("abs:src")
            Page(index, imageUrl = img)
        }.distinctBy { it.imageUrl }
    }

    override fun popularMangaSelector() = "div.item-manga div.item"

    override fun popularMangaFromElement(element: Element) = SManga.create().apply {
        element.select("h3 a").let {
            title = it.text()
            setUrlWithoutDomain(it.attr("abs:href"))
        }
        thumbnail_url = imageOrNull(element.selectFirst("img")!!)
    }

    override fun searchMangaSelector() = popularMangaSelector()

    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/$searchPath".toHttpUrl().newBuilder()

        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> filter.toUriPart()?.let { url.addPathSegment(it) }
                is StatusFilter -> filter.toUriPart()?.let { url.addQueryParameter("status", it) }
                else -> {}
            }
        }

        when {
            query.isNotBlank() -> url.addQueryParameter(queryParam, query)
            else -> url.addQueryParameter("page", page.toString())
        }

        return GET(url.toString(), headers)
    }

    override fun mangaDetailsParse(document: Document) = SManga.create().apply {
        title = document.selectFirst("h1.title-manga")!!.text()
        description = document.selectFirst("p.detail-summary")?.text()
        status = document.selectFirst("li.status p.detail-info span")?.text().toStatus()
        genre = document.select("li.category p.detail-info a")?.joinToString { it.text() }
        thumbnail_url = imageOrNull(document.selectFirst("img.image-comic")!!)
    }

    override fun chapterListSelector() = "div.list-chapter li.row:not(.heading):not([style])"

    override fun chapterFromElement(element: Element): SChapter {
        return super.chapterFromElement(element).apply {
            date_upload = element.select(".chapters + div").text().toDate()
        }
    }

    override val genresSelector = ".categories-detail ul.nav li:not(.active) a"

    override val baseUrl by lazy { getPrefBaseUrl() }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val defaultUrl = super.baseUrl
        // Manual override preference for base URL
        val baseUrlPref = androidx.preference.EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF
            title = BASE_URL_PREF_TITLE
            summary = BASE_URL_PREF_SUMMARY
            setDefaultValue(defaultUrl)
            dialogTitle = BASE_URL_PREF_TITLE
            dialogMessage = "Default: $defaultUrl"

            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(screen.context, RESTART_APP, Toast.LENGTH_LONG).show()
                true
            }
        }
        screen.addPreference(baseUrlPref)

        // Switch preference to enable automatic update on domain redirect
        val autoDomainPref = androidx.preference.SwitchPreferenceCompat(screen.context).apply {
            key = AUTO_CHANGE_DOMAIN_PREF
            title = "Tự động cập nhật domain"
            summary = "Khi bật, ứng dụng sẽ tự động cập nhật domain mới mỗi khi khởi động (Mặc định tắt)"
            setDefaultValue(false)
            
            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(screen.context, RESTART_APP, Toast.LENGTH_LONG).show()
                true
            }
        }
        screen.addPreference(autoDomainPref)
    }

    private fun getPrefBaseUrl(): String = preferences.getString(BASE_URL_PREF, super.baseUrl)!!

    companion object {
        // Bottom of code: manual change domain and automatic change domain constants
        private const val DEFAULT_BASE_URL_PREF = "defaultBaseUrl"
        private const val RESTART_APP = "Khởi chạy lại ứng dụng để áp dụng thay đổi."
        private const val BASE_URL_PREF_TITLE = "Ghi đè URL cơ sở"
        private const val BASE_URL_PREF = "overrideBaseUrl"
        private const val BASE_URL_PREF_SUMMARY =
            "Dành cho sử dụng tạm thời, cập nhật tiện ích sẽ xóa cài đặt."
        private const val AUTO_CHANGE_DOMAIN_PREF = "autoChangeDomain"
        private const val LAST_SESSION_ID = "lastSessionId"
    }
}