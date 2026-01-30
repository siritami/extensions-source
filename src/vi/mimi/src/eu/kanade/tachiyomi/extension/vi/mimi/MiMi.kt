package eu.kanade.tachiyomi.extension.vi.mimi

import android.app.Application
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
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
import keiyoushi.utils.getPreferences
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MiMi : HttpSource(), ConfigurableSource {

    override val name = "MiMi"

    private val defaultBaseUrl = "https://mimimoe.moe"

    override val baseUrl by lazy { getPrefBaseUrl() }

    private val apiUrl get() = baseUrl.replace("://", "://api.") + "/api/v2"

    override val lang = "vi"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences = getPreferences()

    override val client = network.cloudflareClient.newBuilder()
        .rateLimit(3)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ============================== Popular ======================================

    override fun popularMangaRequest(page: Int): Request {
        val url = "$apiUrl/manga/advance-search".toHttpUrl().newBuilder()
            .addQueryParameter("sort", "views")
            .addQueryParameter("page", (page - 1).toString())
            .addQueryParameter("max", "18")
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val result = response.parseAs<SearchResponse>()
        val mangas = result.data.map { it.toSManga() }
        val hasNextPage = result.currentPage < result.totalPage - 1
        return MangasPage(mangas, hasNextPage)
    }

    // ============================== Latest ======================================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiUrl/manga/advance-search".toHttpUrl().newBuilder()
            .addQueryParameter("sort", "updated_at")
            .addQueryParameter("page", (page - 1).toString())
            .addQueryParameter("max", "18")
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    // ============================== Search ======================================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$apiUrl/manga/advance-search".toHttpUrl().newBuilder()
            .addQueryParameter("sort", "updated_at")
            .addQueryParameter("page", (page - 1).toString())
            .addQueryParameter("max", "18")

        if (query.isNotBlank()) {
            url.addQueryParameter("name", query)
        }

        filters.filterIsInstance<GenreFilter>().firstOrNull()?.let { filter ->
            val genreId = filter.toUriPart()
            if (genreId.isNotEmpty()) {
                url.addQueryParameter("genre", genreId)
            }
        }

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    // ============================== Filters ======================================

    override fun getFilterList(): FilterList = getFilters()

    // ============================== Details ======================================

    override fun mangaDetailsRequest(manga: SManga): Request {
        val mangaId = manga.url.substringAfterLast("/")
        return GET("$apiUrl/manga/info/$mangaId", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val result = response.parseAs<MangaInfo>()
        return result.toSManga()
    }

    override fun getMangaUrl(manga: SManga): String {
        return "$baseUrl${manga.url}"
    }

    // ============================== Chapters ======================================

    override fun chapterListRequest(manga: SManga): Request {
        val mangaId = manga.url.substringAfterLast("/")
        return GET("$apiUrl/manga/gallery/$mangaId", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val result = response.parseAs<List<ChapterDto>>()
        return result.mapIndexed { index, chapter ->
            SChapter.create().apply {
                url = "/chapter/${chapter.id}"
                name = chapter.title ?: "Chapter ${result.size - index}"
                chapter_number = (result.size - index).toFloat()
                date_upload = chapter.uploadDate?.let { parseDate(it) } ?: 0L
            }
        }
    }

    private fun parseDate(dateString: String): Long {
        return try {
            dateFormat.parse(dateString)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private val dateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)
    }

    override fun getChapterUrl(chapter: SChapter): String {
        return "$baseUrl${chapter.url}"
    }

    // ============================== Pages ======================================

    override fun pageListRequest(chapter: SChapter): Request {
        val chapterId = chapter.url.substringAfterLast("/")
        return GET("$apiUrl/manga/chapter?id=$chapterId", headers)
    }

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        // First, get the page list from API to check if pages have DRM
        val chapterId = chapter.url.substringAfterLast("/")
        val apiResponse = client.newCall(GET("$apiUrl/manga/chapter?id=$chapterId", headers)).execute()
        val result = apiResponse.parseAs<ChapterPages>()

        // Check if any page has DRM (scrambled)
        val hasScrambledPages = result.pages.any { !it.drm.isNullOrEmpty() }

        if (!hasScrambledPages) {
            // No DRM, return pages directly
            val pages = result.pages.mapIndexed { index, page ->
                Page(index, imageUrl = page.imageUrl)
            }
            return Observable.just(pages)
        }

        // Has scrambled pages - use WebView to descramble
        return fetchPagesViaWebView(chapter)
    }

    private fun fetchPagesViaWebView(chapter: SChapter): Observable<List<Page>> {
        val chapterUrl = "$baseUrl${chapter.url}"
        val interfaceName = "MiMiInterface"

        val handler = Handler(Looper.getMainLooper())
        val latch = CountDownLatch(1)
        val jsInterface = JsInterface(latch)
        var webView: WebView? = null

        handler.post {
            val innerWv = WebView(Injekt.get<Application>())

            webView = innerWv
            innerWv.settings.domStorageEnabled = true
            innerWv.settings.javaScriptEnabled = true
            innerWv.settings.blockNetworkImage = false
            innerWv.settings.userAgentString = headers["User-Agent"]
            innerWv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            innerWv.addJavascriptInterface(jsInterface, interfaceName)

            innerWv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)

                    // Wait a bit for WASM to finish descrambling
                    handler.postDelayed({
                        view?.evaluateJavascript(
                            """
                            (function() {
                                try {
                                    var canvases = document.querySelectorAll('canvas');
                                    var images = [];
                                    for (var i = 0; i < canvases.length; i++) {
                                        try {
                                            var canvas = canvases[i];
                                            if (canvas.width > 100 && canvas.height > 100) {
                                                var dataUrl = canvas.toDataURL('image/jpeg', 0.9);
                                                images.push(dataUrl);
                                            }
                                        } catch(e) {}
                                    }
                                    return JSON.stringify(images);
                                } catch(e) {
                                    return JSON.stringify([]);
                                }
                            })()
                            """.trimIndent(),
                        ) { result ->
                            jsInterface.passPayload(result)
                        }
                    }, 3000) // Wait 3 seconds for WASM to descramble
                }
            }

            innerWv.loadUrl(chapterUrl, headers.toMap())
        }

        // Wait up to 30 seconds for the WebView to complete
        latch.await(30, TimeUnit.SECONDS)
        handler.post { webView?.destroy() }

        if (latch.count == 1L) {
            throw Exception("Timeout waiting for page descrambling")
        }

        val pages = jsInterface.images.mapIndexed { index, imageData ->
            Page(index, imageUrl = imageData)
        }

        if (pages.isEmpty()) {
            throw Exception("No images found in chapter")
        }

        return Observable.just(pages)
    }

    override fun pageListParse(response: Response): List<Page> {
        val result = response.parseAs<ChapterPages>()
        return result.pages.mapIndexed { index, page ->
            Page(index, imageUrl = page.imageUrl)
        }
    }

    override fun imageUrlParse(response: Response): String {
        throw UnsupportedOperationException()
    }

    // ============================== WebView Helper ======================================

    internal class JsInterface(private val latch: CountDownLatch) {
        var images: List<String> = listOf()
            private set

        @JavascriptInterface
        @Suppress("UNUSED")
        fun passPayload(rawData: String) {
            try {
                // Remove quotes from JSON string result
                val jsonString = rawData.trim().removeSurrounding("\"").replace("\\\"", "\"")
                images = json.decodeFromString<List<String>>(jsonString)
                latch.countDown()
            } catch (_: Exception) {
                // Try alternative parsing
                try {
                    images = json.decodeFromString<List<String>>(rawData)
                    latch.countDown()
                } catch (_: Exception) {
                    latch.countDown()
                }
            }
        }

        companion object {
            private val json: Json by injectLazy()
        }
    }

    // ============================== Helpers ======================================

    private inline fun <reified T> Response.parseAs(): T {
        return json.decodeFromString<T>(body.string())
    }

    // ============================== Preferences ======================================

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

    private fun getPrefBaseUrl(): String = preferences.getString(BASE_URL_PREF, defaultBaseUrl)!!

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
    }

    companion object {
        private const val DEFAULT_BASE_URL_PREF = "defaultBaseUrl"
        private const val RESTART_APP = "Khởi chạy lại ứng dụng để áp dụng thay đổi."
        private const val BASE_URL_PREF_TITLE = "Ghi đè URL cơ sở"
        private const val BASE_URL_PREF = "overrideBaseUrl"
        private const val BASE_URL_PREF_SUMMARY =
            "Dành cho sử dụng tạm thời, cập nhật tiện ích sẽ xóa cài đặt."
    }
}
