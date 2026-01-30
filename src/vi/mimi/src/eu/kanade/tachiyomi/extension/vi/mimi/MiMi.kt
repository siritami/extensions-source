package eu.kanade.tachiyomi.extension.vi.mimi

import android.annotation.SuppressLint
import android.app.Application
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Base64
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

    private val context by lazy { Injekt.get<Application>() }

    override val client = network.cloudflareClient.newBuilder()
        .rateLimit(3)
        .addInterceptor(::imageInterceptor)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ============================== Image Descrambling ======================================

    private fun imageInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        // Check if this is a descramble request (marked with fragment)
        val fragment = request.url.fragment ?: return response

        if (!fragment.startsWith("mimi_drm:")) {
            return response
        }

        // Parse the DRM key from fragment
        val drmHex = fragment.removePrefix("mimi_drm:")

        // Read the original image bytes
        val imageBytes = response.body.bytes()

        // Descramble using WebView + WASM
        val descrambledBytes = descrambleWithWebView(imageBytes, drmHex)
            ?: return response.newBuilder()
                .body(imageBytes.toResponseBody("image/jpeg".toMediaType()))
                .build()

        return response.newBuilder()
            .body(descrambledBytes.toResponseBody("image/jpeg".toMediaType()))
            .build()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun descrambleWithWebView(imageBytes: ByteArray, drmHex: String): ByteArray? {
        val latch = CountDownLatch(1)
        var result: ByteArray? = null

        val handler = Handler(Looper.getMainLooper())

        // Convert image to base64 for embedding in HTML
        val imageBase64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)

        // Create HTML that loads the WASM and descrambles
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <script src="https://mimimoe.moe/_nuxt/CgImvNOL.js"></script>
            </head>
            <body>
                <img id="scrambled" style="display:none">
                <canvas id="canvas"></canvas>
                <script>
                    (async function() {
                        try {
                            // Load the scrambled image
                            const img = document.getElementById('scrambled');
                            img.src = 'data:image/jpeg;base64,$imageBase64';
                            
                            await new Promise((resolve, reject) => {
                                img.onload = resolve;
                                img.onerror = reject;
                            });
                            
                            const canvas = document.getElementById('canvas');
                            canvas.width = img.naturalWidth;
                            canvas.height = img.naturalHeight;
                            const ctx = canvas.getContext('2d');
                            
                            // Draw original scrambled image first
                            ctx.drawImage(img, 0, 0);
                            
                            // Wait for WASM to be ready
                            let attempts = 0;
                            while (!window.wasmDescrambler && attempts < 50) {
                                await new Promise(r => setTimeout(r, 100));
                                attempts++;
                            }
                            
                            if (window.wasmDescrambler && window.wasmDescrambler.descramble_image) {
                                // Call the WASM descramble function
                                window.wasmDescrambler.descramble_image(ctx, '$drmHex', img.naturalWidth, img.naturalHeight);
                            } else {
                                // Fallback: try to find the descrambler in other locations
                                const scripts = document.querySelectorAll('script');
                                // The WASM might auto-initialize from the script
                            }
                            
                            // Get the result
                            const dataUrl = canvas.toDataURL('image/jpeg', 0.9);
                            const base64 = dataUrl.split(',')[1];
                            Android.onResult(base64);
                        } catch (e) {
                            Android.onError(e.message || 'Unknown error');
                        }
                    })();
                </script>
            </body>
            </html>
        """.trimIndent()

        handler.post {
            val webView = WebView(context)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.allowFileAccess = false

            webView.addJavascriptInterface(
                object : Any() {
                    @JavascriptInterface
                    @Suppress("unused")
                    fun onResult(base64: String) {
                        try {
                            result = Base64.decode(base64, Base64.DEFAULT)
                        } catch (e: Exception) {
                            // Decode failed
                        }
                        latch.countDown()
                    }

                    @JavascriptInterface
                    @Suppress("unused")
                    fun onError(@Suppress("UNUSED_PARAMETER") message: String) {
                        latch.countDown()
                    }
                },
                "Android",
            )

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    // Wait a bit for WASM to load and execute
                    handler.postDelayed({
                        if (latch.count > 0) {
                            // Timeout - try to get whatever is on canvas
                            webView.evaluateJavascript(
                                """
                                (function() {
                                    try {
                                        const canvas = document.getElementById('canvas');
                                        if (canvas) {
                                            const dataUrl = canvas.toDataURL('image/jpeg', 0.9);
                                            const base64 = dataUrl.split(',')[1];
                                            Android.onResult(base64);
                                        } else {
                                            Android.onError('No canvas');
                                        }
                                    } catch(e) {
                                        Android.onError(e.message);
                                    }
                                })();
                                """.trimIndent(),
                                null,
                            )
                        }
                    }, 3000,)
                }
            }

            webView.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
        }

        // Wait for result with timeout
        latch.await(10, TimeUnit.SECONDS)

        return result
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
        val result = response.parseAs<ChapterPages>()

        return result.pages.mapIndexed { index, page ->
            val imageUrl = if (page.drm != null && page.imageUrl.contains("scrambled")) {
                // Add DRM key as URL fragment for the interceptor
                "${page.imageUrl}#mimi_drm:${page.drm}"
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
