package eu.kanade.tachiyomi.extension.vi.hentaicube

import android.widget.Toast
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.getPreferences
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import java.text.SimpleDateFormat
import java.util.Locale

class HentaiCB :
    Madara(
        "CBHentai",
        "https://hentaicube.xyz",
        "vi",
        SimpleDateFormat("dd/MM/yyyy", Locale("vi")),
    ),
    ConfigurableSource {

    private val defaultBaseUrl = super.baseUrl
    override val baseUrl by lazy { getPrefBaseUrl() }
    private val preferences = getPreferences()

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

    override val id: Long = 823638192569572166

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .rateLimit(9)
        .build()


    override val filterNonMangaItems = false

    override val mangaSubString = "read"

    override val altNameSelector = ".post-content_item:contains(Tên khác) .summary-content"

    private val thumbnailOriginalUrlRegex = Regex("-\\d+x\\d+(\\.[a-zA-Z]+)$")

    override fun popularMangaFromElement(element: Element): SManga = super.popularMangaFromElement(element).apply {
        val img = element.selectFirst("img")
        thumbnail_url = imageFromElement(img!!)?.replace(thumbnailOriginalUrlRegex, "$1")
    }

    override fun processThumbnail(url: String?, fromSearch: Boolean): String? =
        url?.replace(thumbnailOriginalUrlRegex, "$1")

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        if (query.startsWith(URL_SEARCH_PREFIX)) {
            val mangaUrl = baseUrl.toHttpUrl().newBuilder().apply {
                addPathSegment(mangaSubString)
                addPathSegment(query.substringAfter(URL_SEARCH_PREFIX))
                addPathSegment("")
            }.build()
            return client.newCall(GET(mangaUrl, headers))
                .asObservableSuccess().map { response ->
                    val manga = mangaDetailsParse(response).apply {
                        setUrlWithoutDomain(mangaUrl.toString())
                        initialized = true
                    }

                    MangasPage(listOf(manga), false)
                }
        }

        // Special characters causing search to fail
        val queryFixed = query
            .replace("–", "-")
            .replace("’", "'")
            .replace("“", "\"")
            .replace("”", "\"")
            .replace("…", "...")

        return super.fetchSearchManga(page, queryFixed, filters)
    }

    private val oldMangaUrlRegex = Regex("^$baseUrl/\\w+/")

    // Change old entries from mangaSubString
    override fun getMangaUrl(manga: SManga): String = super.getMangaUrl(manga)
        .replace(oldMangaUrlRegex, "$baseUrl/$mangaSubString/")

    override fun pageListParse(document: Document): List<Page> = super.pageListParse(document).distinctBy { it.imageUrl }

    // ============================== Preferences ===========================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val baseUrlPref = androidx.preference.EditTextPreference(screen.context).apply {
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
        }
        screen.addPreference(baseUrlPref)
    }

    private fun getPrefBaseUrl(): String = preferences.getString(BASE_URL_PREF, defaultBaseUrl)!!

    companion object {
        private const val DEFAULT_BASE_URL_PREF = "defaultBaseUrl"
        private const val BASE_URL_PREF = "overrideBaseUrl"
        private const val BASE_URL_PREF_TITLE = "Ghi đè URL cơ sở"
        private const val BASE_URL_PREF_SUMMARY =
            "Dành cho sử dụng tạm thời, cập nhật tiện ích sẽ xóa cài đặt."
        private const val RESTART_APP = "Khởi chạy lại ứng dụng để áp dụng thay đổi."
    }
}
