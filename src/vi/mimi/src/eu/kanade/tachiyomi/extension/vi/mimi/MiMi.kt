package eu.kanade.tachiyomi.extension.vi.mimi

import android.annotation.SuppressLint
import android.app.Application
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.ByteArrayOutputStream
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

    // Cache for descrambled images from WebView
    @Volatile
    private var descrambledImagesCache: Map<Int, ByteArray> = emptyMap()

    override val client = network.cloudflareClient.newBuilder()
        .rateLimit(3)
        .addInterceptor(::imageInterceptor)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ============================== Image Interceptor ======================================

    private fun imageInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // Check if this is a descrambled image request (data URL)
        val fragment = request.url.fragment
        if (fragment != null && fragment.startsWith("mimi_cache:")) {
            val pageIndex = fragment.removePrefix("mimi_cache:").toIntOrNull() ?: 0
            val cachedData = descrambledImagesCache[pageIndex]

            if (cachedData != null) {
                return Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(cachedData.toResponseBody("image/jpeg".toMediaType()))
                    .build()
            }
        }

        return chain.proceed(request)
    }

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

    @SuppressLint("SetJavaScriptEnabled")
    override fun pageListParse(response: Response): List<Page> {
        val result = response.parseAs<ChapterPages>()
        val chapterId = response.request.url.queryParameter("id") ?: ""

        // Check if any page needs descrambling
        val needsDescrambling = result.pages.any { it.imageUrl.contains("scrambled") }

        if (!needsDescrambling) {
            // No descrambling needed, return direct URLs
            return result.pages.mapIndexed { index, page ->
                Page(index, imageUrl = page.imageUrl)
            }
        }

        // Need to use WebView to descramble images
        val chapterUrl = "$baseUrl/g/0/chapter/Chap-0-$chapterId"
        val descrambledImages = descrambleWithWebView(chapterUrl, result.pages.size)

        // Clear previous cache and set new one
        descrambledImagesCache = descrambledImages

        return result.pages.mapIndexed { index, page ->
            if (descrambledImages.containsKey(index)) {
                // Use cached descrambled image
                Page(index, imageUrl = "$baseUrl/descrambled/$chapterId/$index.jpg#mimi_cache:$index")
            } else {
                // Fallback to original URL
                Page(index, imageUrl = page.imageUrl)
            }
        }
    }

    /**
     * Load the chapter page in WebView and extract descrambled images.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun descrambleWithWebView(chapterUrl: String, pageCount: Int): Map<Int, ByteArray> {
        val handler = Handler(Looper.getMainLooper())
        val latch = CountDownLatch(1)
        val jsInterface = DescrambleInterface(latch, pageCount)
        var webView: WebView? = null

        handler.post {
            val wv = WebView(Injekt.get<Application>())
            webView = wv
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.settings.loadsImagesAutomatically = true
            wv.settings.blockNetworkImage = false
            wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            wv.addJavascriptInterface(jsInterface, "MiMiInterface")

            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)

                    // Wait a bit for WASM to initialize and process images
                    handler.postDelayed({
                        view?.evaluateJavascript(extractImagesScript, null)
                    }, 5000)
                }
            }

            wv.loadUrl(chapterUrl)
        }

        // Wait up to 60 seconds for all images
        latch.await(60L, TimeUnit.SECONDS)
        handler.post { webView?.destroy() }

        return jsInterface.getImages()
    }

    private val extractImagesScript = """
        (function() {
            try {
                // Find all canvas elements that contain the descrambled images
                const canvases = document.querySelectorAll('canvas');
                const results = [];

                canvases.forEach((canvas, index) => {
                    try {
                        if (canvas.width > 100 && canvas.height > 100) {
                            const dataUrl = canvas.toDataURL('image/jpeg', 0.92);
                            results.push({
                                index: index,
                                data: dataUrl.split(',')[1]
                            });
                        }
                    } catch (e) {
                        console.error('Canvas export error:', e);
                    }
                });

                // Also check for any img elements that might have been processed
                const imgs = document.querySelectorAll('img[src*="scrambled"]');
                imgs.forEach((img, index) => {
                    // These are the source images, we need the canvas versions
                });

                if (results.length > 0) {
                    window.MiMiInterface.onImagesReady(JSON.stringify(results));
                } else {
                    // Try again after more delay
                    setTimeout(function() {
                        const canvases2 = document.querySelectorAll('canvas');
                        const results2 = [];
                        canvases2.forEach((canvas, index) => {
                            try {
                                if (canvas.width > 100 && canvas.height > 100) {
                                    const dataUrl = canvas.toDataURL('image/jpeg', 0.92);
                                    results2.push({
                                        index: index,
                                        data: dataUrl.split(',')[1]
                                    });
                                }
                            } catch (e) {}
                        });
                        window.MiMiInterface.onImagesReady(JSON.stringify(results2));
                    }, 5000);
                }
            } catch (e) {
                window.MiMiInterface.onError(e.toString());
            }
        })();
    """.trimIndent()

    @Suppress("UNUSED")
    private class DescrambleInterface(
        private val latch: CountDownLatch,
        private val expectedCount: Int,
    ) {
        private val images = mutableMapOf<Int, ByteArray>()
        private var error: String? = null

        fun getImages(): Map<Int, ByteArray> = images.toMap()

        @JavascriptInterface
        fun onImagesReady(jsonData: String) {
            try {
                val results = Json.decodeFromString<List<ImageResult>>(jsonData)
                results.forEach { result ->
                    try {
                        val bytes = Base64.decode(result.data, Base64.DEFAULT)
                        images[result.index] = bytes
                    } catch (e: Exception) {
                        // Skip this image
                    }
                }
            } catch (e: Exception) {
                error = e.message
            }
            latch.countDown()
        }

        @JavascriptInterface
        fun onError(errorMessage: String) {
            error = errorMessage
            latch.countDown()
        }

        @Serializable
        data class ImageResult(val index: Int, val data: String)
    }

    override fun imageUrlParse(response: Response): String {
        throw UnsupportedOperationException()
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
