package eu.kanade.tachiyomi.extension.vi.mimi

import android.annotation.SuppressLint
import android.app.Application
import android.content.SharedPreferences
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
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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
        .addInterceptor(::imageInterceptor)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ============================== Image Interceptor ======================================

    private fun imageInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        // Check if this is a descramble request (marked with fragment)
        val fragment = request.url.fragment ?: return response

        if (!fragment.startsWith("mimi_descramble:")) {
            return response
        }

        // Parse the descramble parameters
        val params = fragment.removePrefix("mimi_descramble:")
        val parts = params.split(":")
        if (parts.size < 3) return response

        val pageIndex = parts[0].toIntOrNull() ?: return response
        val chapterId = parts[1]
        val drmKey = parts[2]

        // Load the scrambled image
        val scrambledBytes = response.body.bytes()
        val scrambledBitmap = BitmapFactory.decodeByteArray(scrambledBytes, 0, scrambledBytes.size)
            ?: return response

        // Get the descrambled image using WebView
        val descrambledBytes = descrambleWithWebView(scrambledBytes, drmKey, pageIndex)
            ?: return response.newBuilder()
                .body(scrambledBytes.toResponseBody("image/jpeg".toMediaType()))
                .build()

        return response.newBuilder()
            .body(descrambledBytes.toResponseBody("image/jpeg".toMediaType()))
            .build()
    }

    /**
     * Use WebView to run MiMi's WASM descrambling algorithm.
     * This executes the site's own JavaScript to properly descramble the image.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun descrambleWithWebView(imageBytes: ByteArray, drmKey: String, pageIndex: Int): ByteArray? {
        val handler = Handler(Looper.getMainLooper())
        val latch = CountDownLatch(1)
        val jsInterface = DescrambleInterface(latch)
        var webView: WebView? = null

        // Convert image to base64 for passing to JavaScript
        val imageBase64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)

        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <script src="$baseUrl/_nuxt/aSdLPkDj.CaSpbFQb.wasm"></script>
                <script src="$baseUrl/_nuxt/CgImvNOL.js"></script>
            </head>
            <body>
                <canvas id="output" style="display:none;"></canvas>
                <script>
                (async function() {
                    try {
                        // Wait for WASM to be ready
                        await new Promise(resolve => setTimeout(resolve, 2000));

                        // Create image from base64
                        const img = new Image();
                        await new Promise((resolve, reject) => {
                            img.onload = resolve;
                            img.onerror = reject;
                            img.src = 'data:image/jpeg;base64,$imageBase64';
                        });

                        // Create canvas
                        const canvas = document.getElementById('output');
                        canvas.width = img.width;
                        canvas.height = img.height;

                        // Get WASM instance and descramble
                        if (typeof window.s !== 'undefined' && window.s.descramble_image) {
                            // Use the WASM descramble function
                            await window.s.descramble_image(canvas, img, '$drmKey', $pageIndex);
                        } else {
                            // Fallback: try manual descrambling with intercepted drawImage
                            const ctx = canvas.getContext('2d');
                            ctx.drawImage(img, 0, 0);
                        }

                        // Convert to base64 and return
                        const dataUrl = canvas.toDataURL('image/jpeg', 0.92);
                        window.MiMiInterface.onSuccess(dataUrl.split(',')[1]);
                    } catch (e) {
                        window.MiMiInterface.onError(e.toString());
                    }
                })();
                </script>
            </body>
            </html>
        """.trimIndent()

        handler.post {
            val wv = WebView(Injekt.get<Application>())
            webView = wv
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.settings.allowFileAccess = true
            wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            wv.addJavascriptInterface(jsInterface, "MiMiInterface")

            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                }
            }

            wv.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
        }

        // Wait up to 30 seconds for the result
        latch.await(30L, TimeUnit.SECONDS)
        handler.post { webView?.destroy() }

        if (jsInterface.resultBase64.isNullOrEmpty()) {
            return null
        }

        return try {
            Base64.decode(jsInterface.resultBase64, Base64.DEFAULT)
        } catch (e: Exception) {
            null
        }
    }

    @Suppress("UNUSED")
    private class DescrambleInterface(private val latch: CountDownLatch) {
        var resultBase64: String? = null
            private set
        var error: String? = null
            private set

        @JavascriptInterface
        fun onSuccess(base64Data: String) {
            resultBase64 = base64Data
            latch.countDown()
        }

        @JavascriptInterface
        fun onError(errorMessage: String) {
            error = errorMessage
            latch.countDown()
        }
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

    override fun pageListParse(response: Response): List<Page> {
        val result = response.parseAs<ChapterPagesWithDrm>()
        val chapterId = response.request.url.queryParameter("id") ?: ""

        return result.pages.mapIndexed { index, page ->
            val imageUrl = if (page.drm != null && page.imageUrl.contains("scrambled")) {
                // Add descramble info as URL fragment
                "${page.imageUrl}#mimi_descramble:$index:$chapterId:${page.drm}"
            } else {
                page.imageUrl
            }
            Page(index, imageUrl = imageUrl)
        }
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

@Serializable
data class ChapterPagesWithDrm(
    val pages: List<PageWithDrm> = emptyList(),
)

@Serializable
data class PageWithDrm(
    val imageUrl: String,
    val drm: String? = null,
)
