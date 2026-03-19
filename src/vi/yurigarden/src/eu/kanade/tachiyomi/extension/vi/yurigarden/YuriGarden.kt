package eu.kanade.tachiyomi.extension.vi.yurigarden

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.lib.cryptoaes.CryptoAES
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservable
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class YuriGarden : HttpSource() {

    override val name = "YuriGarden"

    override val lang = "vi"

    override val baseUrl = "https://yurigarden.com"

    override val supportsLatest = true

    private val apiUrl = baseUrl.replace("://", "://api.") + "/api"

    private val dbUrl = baseUrl.replace("://", "://db.")
    private val json by lazy { Json { ignoreUnknownKeys = true } }
    @Volatile private var localStorageDumpCache: String? = null

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor(ImageDescrambler())
        .rateLimitHost(apiUrl.toHttpUrl(), 15, 1, TimeUnit.MINUTES)
        .build()

    private fun apiHeaders() = headersBuilder()
        .set("Referer", "$baseUrl/")
        .add("x-app-origin", baseUrl)
        .add("x-custom-lang", "vi")
        .add("Accept", "application/json")
        .build()

    // ============================== Popular ===============================

    override fun popularMangaRequest(page: Int): Request {
        val url = "$apiUrl/comics".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", LIMIT.toString())
            .addQueryParameter("full", "true")
            .build()

        return GET(url, apiHeaders())
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val result = response.parseAs<ComicsResponse>()

        val mangaList = result.comics.map { comic ->
            SManga.create().apply {
                url = "/comic/${comic.id}"
                title = comic.title
                thumbnail_url = comic.thumbnail?.toThumbnailUrl()
            }
        }

        val hasNextPage = result.totalPages > currentPage(response)

        return MangasPage(mangaList, hasNextPage)
    }

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int) = popularMangaRequest(page)

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    // ============================== Search ================================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$apiUrl/comics".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            addQueryParameter("limit", LIMIT.toString())
            addQueryParameter("full", "true")
            addQueryParameter("searchBy", "title,anotherNames")

            if (query.isNotBlank()) {
                addQueryParameter("search", query)
            }

            val filterList = filters.ifEmpty { getFilterList() }

            filterList.forEach { filter ->
                when (filter) {
                    is GenreFilter -> {
                        if (filter.slug.isNotEmpty()) {
                            addQueryParameter("genre", filter.slug)
                        }
                    }
                    is StatusFilter -> {
                        if (filter.slug.isNotEmpty()) {
                            addQueryParameter("status", filter.slug)
                        }
                    }
                    is SortFilter -> {
                        addQueryParameter("sort", filter.slug)
                    }
                    is R18Filter -> {
                        if (filter.state) {
                            addQueryParameter("allowR18", "true")
                        }
                    }
                    else -> {}
                }
            }
        }.build()

        return GET(url, apiHeaders())
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    // ============================== Filters ===============================

    override fun getFilterList() = getFilters()

    // ============================== Details ===============================

    private fun mangaId(manga: SManga): String =
        manga.url.substringAfterLast("/")

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET("$apiUrl/comics/${mangaId(manga)}", apiHeaders())

    override fun getMangaUrl(manga: SManga): String =
        "$baseUrl${manga.url}"

    override fun mangaDetailsParse(response: Response): SManga {
        val comic = response.parseAs<ComicDetail>()

        return SManga.create().apply {
            url = "/comic/${comic.id}"
            title = comic.title
            author = comic.authors.joinToString { it.name }
            description = comic.description
            genre = comic.genres.joinToString()
            status = when (comic.status) {
                "ongoing" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                "hiatus" -> SManga.ON_HIATUS
                "canceled" -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
            thumbnail_url = comic.thumbnail?.toThumbnailUrl()
            initialized = true
        }
    }

    // ============================== Chapters ==============================

    private fun chapterId(chapter: SChapter): String =
        chapter.url.substringAfterLast("/")

    override fun chapterListRequest(manga: SManga): Request =
        GET("$apiUrl/chapters/comic/${mangaId(manga)}", apiHeaders())

    override fun chapterListParse(response: Response): List<SChapter> {
        val chapters = response.parseAs<List<ChapterData>>()

        return chapters
            .sortedByDescending { it.order }
            .map { chapter ->
                SChapter.create().apply {
                    url = "/chapter/${chapter.id}"
                    name = "Chapter ${chapter.order.toBigDecimal().stripTrailingZeros().toPlainString()}"
                    date_upload = chapter.publishedAt
                }
            }
    }

    override fun getChapterUrl(chapter: SChapter): String =
        "$baseUrl${chapter.url}"

    // ============================== Pages =================================

    override fun pageListRequest(chapter: SChapter): Request =
        GET("$apiUrl/chapters/pages/${chapterId(chapter)}", apiHeaders())

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> =
        client.newCall(pageListRequest(chapter))
            .asObservable()
            .doOnNext { response ->
                if (response.code == 403) {
                    val body = runCatching { response.peekBody(1024 * 1024).string() }.getOrDefault("")
                    val hasTurnstile = isTurnstileChallenge(response, body)
                    response.close()
                    if (hasTurnstile || hasTurnstileChallenge(chapter)) {
                        throw Exception(CLOUDFLARE_VERIFY_MESSAGE)
                    }
                    throw Exception("HTTP error 403")
                }
                if (!response.isSuccessful) {
                    response.close()
                    throw Exception("HTTP error ${response.code}")
                }
            }
            .map(::pageListParse)

    override fun pageListParse(response: Response): List<Page> {
        val chapterId = response.request.url.pathSegments.lastOrNull().orEmpty()
        var result = decryptIfNeeded(response)

        if (result.isLocked && chapterId.isNotBlank()) {
            val storageDump = getLocalStorageDump()
            val cachedChapterDetail = storageDump?.let { findStoredChapterDetail(chapterId, it) }

            result = if (cachedChapterDetail?.pages?.isNotEmpty() == true) {
                cachedChapterDetail
            } else {
                val password = storageDump
                    ?.let { findStoredChapterPassword(chapterId, it) }
                    ?: throw Exception(PASSWORD_WEBVIEW_MESSAGE)
                verifyChapterPassword(chapterId, password)
            }
        }

        if (result.isLocked) {
            throw Exception(PASSWORD_WEBVIEW_MESSAGE)
        }

        return result.pages.mapIndexed { index, page ->
            val rawUrl = page.url.replace("_credit", "").trimStart('/')

            if (rawUrl.startsWith("comics/")) {
                val key = page.key
                val url = "$dbUrl/storage/v1/object/public/yuri-garden-store/$rawUrl"
                    .toHttpUrl().newBuilder().apply {
                        if (!key.isNullOrEmpty()) {
                            fragment("KEY=$key")
                        }
                    }.build().toString()

                Page(index, imageUrl = url)
            } else {
                val url = rawUrl.toHttpUrlOrNull()?.toString() ?: rawUrl
                Page(index, imageUrl = url)
            }
        }
    }

    private fun verifyChapterPassword(chapterId: String, password: String): ChapterDetail {
        val escapedPassword = password.replace("\\", "\\\\").replace("\"", "\\\"")
        val body = """{"password":"$escapedPassword"}"""
            .toRequestBody("application/json".toMediaType())

        return client.newCall(
            POST("$apiUrl/chapters/pages/$chapterId/verify", apiHeaders(), body),
        ).execute().use { response ->
            if (response.code == 403) {
                val responseBody = runCatching { response.peekBody(1024 * 1024).string() }.getOrDefault("")
                if (isTurnstileChallenge(response, responseBody)) {
                    throw Exception(CLOUDFLARE_VERIFY_MESSAGE)
                }
            }
            if (!response.isSuccessful) {
                throw Exception("HTTP error ${response.code}")
            }
            decryptIfNeeded(response)
        }
    }

    private fun decryptIfNeeded(response: Response): ChapterDetail {
        val body = response.body.string()

        // Check if the response is encrypted
        return if (body.contains("\"encrypted\"")) {
            val encrypted = body.parseAs<EncryptedResponse>()
            if (encrypted.encrypted && !encrypted.data.isNullOrEmpty()) {
                val decrypted = CryptoAES.decrypt(encrypted.data, AES_PASSWORD)
                decrypted.parseAs<ChapterDetail>()
            } else {
                body.parseAs<ChapterDetail>()
            }
        } else {
            body.parseAs<ChapterDetail>()
        }
    }

    private fun findStoredChapterDetail(chapterId: String, storageDump: String): ChapterDetail? {
        val root = runCatching { json.parseToJsonElement(storageDump) }.getOrNull() ?: return null
        return findChapterDetailInElement(chapterId, root, mutableSetOf())
            ?.takeIf { it.pages.isNotEmpty() && !it.isLocked }
    }

    private fun findStoredChapterPassword(chapterId: String, storageDump: String): String? {
        val root = runCatching { json.parseToJsonElement(storageDump) }.getOrNull()
        val searchText = buildStorageSearchText(storageDump, root)
        return extractPasswordFromText(chapterId, searchText)
    }

    private fun buildStorageSearchText(storageDump: String, root: JsonElement?): String {
        if (root == null) return storageDump

        val chunks = mutableListOf<String>()
        collectStringValues(root, chunks, mutableSetOf())

        return buildString {
            append(storageDump.take(MAX_STORAGE_TEXT_LENGTH))
            for (chunk in chunks) {
                if (length >= MAX_STORAGE_TEXT_LENGTH) break
                append('\n')
                append(chunk.take(MAX_STORAGE_CHUNK_LENGTH))
            }
        }
    }

    private fun collectStringValues(
        element: JsonElement,
        output: MutableList<String>,
        visitedJsonStrings: MutableSet<String>,
    ) {
        when (element) {
            is JsonObject -> element.values.forEach { collectStringValues(it, output, visitedJsonStrings) }
            is JsonArray -> element.forEach { collectStringValues(it, output, visitedJsonStrings) }
            is JsonPrimitive -> {
                if (!element.isString) return

                val value = element.contentOrNull?.trim().orEmpty()
                if (value.isBlank()) return
                output += value

                if (looksLikeJson(value) && visitedJsonStrings.add(value)) {
                    runCatching { json.parseToJsonElement(value) }
                        .getOrNull()
                        ?.let { collectStringValues(it, output, visitedJsonStrings) }
                }
            }
        }
    }

    private fun findChapterDetailInElement(
        chapterId: String,
        element: JsonElement,
        visitedJsonStrings: MutableSet<String>,
    ): ChapterDetail? {
        when (element) {
            is JsonObject -> {
                val queryKey = element["queryKey"] as? JsonArray
                val queryState = element["state"] as? JsonObject
                if (queryKey != null && queryState != null && isChapterPagesQuery(queryKey, chapterId)) {
                    val dataElement = queryState["data"]
                        ?.let { parseNestedJson(it, visitedJsonStrings) }
                    if (dataElement != null) {
                        decodeChapterDetail(dataElement)?.let { return it }
                    }
                }

                for (value in element.values) {
                    findChapterDetailInElement(chapterId, value, visitedJsonStrings)?.let { return it }
                }
            }
            is JsonArray -> {
                for (value in element) {
                    findChapterDetailInElement(chapterId, value, visitedJsonStrings)?.let { return it }
                }
            }
            is JsonPrimitive -> {
                parseNestedJson(element, visitedJsonStrings)
                    ?.let { findChapterDetailInElement(chapterId, it, visitedJsonStrings) }
                    ?.let { return it }
            }
        }
        return null
    }

    private fun decodeChapterDetail(element: JsonElement): ChapterDetail? =
        runCatching { json.decodeFromJsonElement<ChapterDetail>(element) }.getOrNull()

    private fun parseNestedJson(
        element: JsonElement,
        visitedJsonStrings: MutableSet<String>,
    ): JsonElement? {
        if (element !is JsonPrimitive || !element.isString) return element

        val raw = element.contentOrNull?.trim().orEmpty()
        if (!looksLikeJson(raw) || !visitedJsonStrings.add(raw)) return null
        return runCatching { json.parseToJsonElement(raw) }.getOrNull()
    }

    private fun looksLikeJson(text: String): Boolean =
        text.startsWith("{") || text.startsWith("[")

    private fun isChapterPagesQuery(queryKey: JsonArray, chapterId: String): Boolean {
        if (queryKey.size < 2) return false

        val keyName = queryKey[0].jsonPrimitive.content
        if (!keyName.equals("chapterPages", ignoreCase = true)) return false

        val keyChapterId = queryKey[1].jsonPrimitive.content
        return keyChapterId == chapterId
    }

    private fun extractPasswordFromText(chapterId: String, text: String): String? {
        val escapedChapterId = Regex.escape(chapterId)
        val objectPattern = Regex(
            "(?:chapterId|id)\"?\\s*:\\s*\"?$escapedChapterId\"?[\\s\\S]{0,180}?(?:password|pass|value)\"?\\s*:\\s*\"([^\"]+)\"",
            RegexOption.IGNORE_CASE,
        )
        val keyPattern = Regex(
            "\"(?:$escapedChapterId|chapter[_:-]?$escapedChapterId|$escapedChapterId[_:-]?password|password[_:-]?$escapedChapterId)\"\\s*:\\s*\"([^\"]+)\"",
            RegexOption.IGNORE_CASE,
        )
        val directPattern = Regex(
            "\"$escapedChapterId\"\\s*:\\s*\"([^\"]+)\"",
            RegexOption.IGNORE_CASE,
        )

        return listOf(objectPattern, keyPattern, directPattern)
            .firstNotNullOfOrNull { pattern ->
                pattern.find(text)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() && it.length <= 128 }
            }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun getLocalStorageDump(): String? {
        localStorageDumpCache?.also { return it }

        val handler = Handler(Looper.getMainLooper())
        val latch = CountDownLatch(1)
        val done = AtomicBoolean(false)
        var storageDump: String? = null
        var fallbackLocalStorageDump: String? = null

        handler.post {
            val webView = WebView(Injekt.get<Application>())
            val bridgeName = "YuriGardenBridge"

            fun finish(result: String?) {
                if (!done.compareAndSet(false, true)) return

                val finalDump = result?.takeUnless { it.isBlank() || it == "null" || it == "undefined" } ?: fallbackLocalStorageDump
                handler.post {
                    storageDump = finalDump
                    localStorageDumpCache = storageDump
                    latch.countDown()
                    webView.destroy()
                }
            }

            val bridge = object {
                @JavascriptInterface
                fun onResult(result: String?) {
                    finish(result)
                }
            }

            webView.addJavascriptInterface(bridge, bridgeName)

            with(webView.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                blockNetworkImage = true
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.evaluateJavascript("JSON.stringify(window.localStorage)") { result ->
                        fallbackLocalStorageDump = result.fromJsResult()
                    }
                    view?.evaluateJavascript(buildStorageDumpScript(bridgeName), null)
                }
            }

            handler.postDelayed({
                if (!done.get()) {
                    finish(fallbackLocalStorageDump)
                }
            }, 9_000)

            webView.loadDataWithBaseURL(baseUrl, " ", "text/html", "UTF-8", null)
        }

        latch.await(10, TimeUnit.SECONDS)
        return storageDump
    }

    private fun buildStorageDumpScript(bridgeName: String): String = """
        (function() {
            var bridge = window.$bridgeName;
            var finished = false;
            function finish(data) {
                if (finished) return;
                finished = true;
                try {
                    bridge.onResult(JSON.stringify(data));
                } catch (e) {
                    try { bridge.onResult(""); } catch (_) {}
                }
            }

            var localStorageData = {};
            try {
                for (var i = 0; i < localStorage.length; i++) {
                    var key = localStorage.key(i);
                    localStorageData[key] = localStorage.getItem(key);
                }
            } catch (e) {}

            var payload = {
                localStorage: localStorageData,
                mangaPersisted: null
            };

            if (!window.indexedDB) {
                finish(payload);
                return;
            }

            try {
                var request = indexedDB.open("localforage");
                request.onerror = function() { finish(payload); };
                request.onupgradeneeded = function() { finish(payload); };
                request.onsuccess = function() {
                    try {
                        var db = request.result;
                        var tx;
                        try {
                            tx = db.transaction("keyvaluepairs", "readonly");
                        } catch (e) {
                            db.close();
                            finish(payload);
                            return;
                        }

                        var store = tx.objectStore("keyvaluepairs");
                        var getReq = store.get("manga");
                        getReq.onerror = function() {
                            db.close();
                            finish(payload);
                        };
                        getReq.onsuccess = function() {
                            payload.mangaPersisted = getReq.result || null;
                            db.close();
                            finish(payload);
                        };
                    } catch (e) {
                        finish(payload);
                    }
                };
            } catch (e) {
                finish(payload);
            }
        })();
    """.trimIndent()

    private fun String?.fromJsResult(): String? {
        val value = this ?: return null
        if (value == "null" || value == "undefined") return null

        return value
            .removeSurrounding("\"")
            .replace("\\\\", "\\")
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\u003C", "<")
            .replace("\\u003E", ">")
            .replace("\\u0026", "&")
    }

    private fun hasTurnstileChallenge(chapter: SChapter): Boolean {
        val urls = listOfNotNull(resolveReaderUrl(chapter), getChapterUrl(chapter)).distinct()

        return urls.any { url ->
            runCatching {
                client.newCall(GET(url, headers)).execute().use { response ->
                    val body = runCatching { response.body.string() }.getOrDefault("")
                    isTurnstileChallenge(response, body)
                }
            }.getOrDefault(false)
        }
    }

    private fun isTurnstileChallenge(response: Response, body: String): Boolean =
        response.header("cf-mitigated")?.equals("challenge", ignoreCase = true) == true ||
            hasTurnstileElement(body) ||
            body.contains("/cdn-cgi/challenge-platform", ignoreCase = true) ||
            body.contains("Just a moment", ignoreCase = true)

    private fun hasTurnstileElement(html: String): Boolean {
        if (html.isBlank()) return false

        val document = Jsoup.parse(html)
        return document.selectFirst(
            "div.cf-turnstile, " +
                "input[name=cf-turnstile-response], " +
                "iframe[src*=challenges.cloudflare.com], " +
                "form#challenge-form, " +
                "#cf-challenge-running, " +
                "#challenge-stage",
        ) != null ||
            html.contains("cf-turnstile", ignoreCase = true) ||
            html.contains("challenges.cloudflare.com/turnstile", ignoreCase = true)
    }

    private fun resolveReaderUrl(chapter: SChapter): String? = runCatching {
        val chapterId = chapterId(chapter)
        client.newCall(GET("$apiUrl/chapters/$chapterId", apiHeaders())).execute().use { response ->
            if (!response.isSuccessful) return@use null

            val body = response.body.string()
            val comicId = COMIC_ID_REGEX.find(body)?.groupValues?.getOrNull(1) ?: return@use null
            "$baseUrl/comic/$comicId/$chapterId"
        }
    }.getOrNull()

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException()

    // =============================== Related ================================

    // dirty hack to disable suggested mangas on Komikku due to heavy rate limit
    // https://github.com/komikku-app/komikku/blob/4323fd5841b390213aa4c4af77e07ad42eb423fc/source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/source/CatalogueSource.kt#L176-L184
    @Suppress("Unused")
    @JvmName("getDisableRelatedMangasBySearch")
    fun disableRelatedMangasBySearch() = true

    // ============================== Helpers ================================

    private fun currentPage(response: Response): Int {
        val url = response.request.url
        return url.queryParameter("page")?.toIntOrNull() ?: 1
    }

    private fun String.toThumbnailUrl(): String =
        if (startsWith("http")) this else "$dbUrl/storage/v1/object/public/yuri-garden-store/$this"

    companion object {
        private const val LIMIT = 15
        private const val AES_PASSWORD = "OAqg95LgrfPM8r68"
        private const val CLOUDFLARE_VERIFY_MESSAGE = "Mở webview để xác minh cloudflare cho chương này"
        private const val PASSWORD_WEBVIEW_MESSAGE = "Mở webview nhập mật khẩu cho chương này rồi thử lại"
        private const val MAX_STORAGE_TEXT_LENGTH = 2_000_000
        private const val MAX_STORAGE_CHUNK_LENGTH = 100_000
        private val COMIC_ID_REGEX = """"comic"\s*:\s*\{\s*"id"\s*:\s*(\d+)""".toRegex()
    }
}
