package eu.kanade.tachiyomi.extension.vi.lxhentai

import android.annotation.SuppressLint
import android.app.Application
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservable
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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class LxHentai :
    HttpSource(),
    ConfigurableSource {

    override val name = "LXManga"

    override val id = 6495630445796108150

    override val lang = "vi"

    override val supportsLatest = true

    private val defaultBaseUrl = "https://lxmanga.space"

    override val baseUrl get() = getPrefBaseUrl()

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

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    override fun popularMangaRequest(page: Int): Request = browseMangaRequest(page, "-views")

    override fun popularMangaParse(response: Response): MangasPage = parseMangaPage(response)

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request = browseMangaRequest(page, "-updated_at")

    override fun latestUpdatesParse(response: Response): MangasPage = parseMangaPage(response)

    // ============================== Search ================================

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = when {
        query.startsWith(PREFIX_ID_SEARCH) -> {
            val slug = query.substringAfter(PREFIX_ID_SEARCH)
            val mangaUrl = "/truyen/$slug"
            fetchMangaDetails(SManga.create().apply { url = mangaUrl })
                .map {
                    it.url = mangaUrl
                    it.initialized = true
                    MangasPage(listOf(it), false)
                }
        }
        else -> super.fetchSearchManga(page, query, filters)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val sort = filters.firstInstanceOrNull<SortFilter>()?.toUriPart() ?: "-updated_at"
        val searchType = filters.firstInstanceOrNull<SearchTypeFilter>()?.toUriPart() ?: "name"
        val statuses = filters.firstInstanceOrNull<StatusFilter>()?.selectedValues().orEmpty()
        val includedGenres = filters.firstInstanceOrNull<GenreFilter>()?.includedValues().orEmpty()
        val excludedGenres = filters.firstInstanceOrNull<GenreFilter>()?.excludedValues().orEmpty()

        val url = "$baseUrl/tim-kiem".toHttpUrl().newBuilder()
            .addQueryParameter("sort", sort)
            .addQueryParameter("page", page.toString())
            .apply {
                if (query.isNotBlank()) {
                    addQueryParameter("filter[$searchType]", query)
                }
                if (statuses.isNotEmpty()) {
                    addQueryParameter("filter[status]", statuses.joinToString(","))
                }
                if (includedGenres.isNotEmpty()) {
                    addQueryParameter("filter[accept_genres]", includedGenres.joinToString(","))
                }
                if (excludedGenres.isNotEmpty()) {
                    addQueryParameter("filter[reject_genres]", excludedGenres.joinToString(","))
                }
            }
            .build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseMangaPage(response)

    override fun getFilterList(): FilterList = getFilters()

    private fun browseMangaRequest(page: Int, sortBy: String): Request {
        val url = "$baseUrl/tim-kiem".toHttpUrl().newBuilder()
            .addQueryParameter("sort", sortBy)
            .addQueryParameter("page", page.toString())
            .addQueryParameter("filter[status]", "ongoing,completed,paused")
            .build()
        return GET(url, headers)
    }

    private fun parseMangaPage(response: Response): MangasPage {
        val document = response.asJsoup()
        val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val mangaList = document.select("div.manga-vertical")
            .map { element: Element -> mangaFromElement(element) }
        val hasNextPage = document.select("a#pagination[data-page]")
            .asSequence()
            .mapNotNull { element: Element -> element.attr("data-page").toIntOrNull() }
            .any { page: Int -> page > currentPage }

        return MangasPage(mangaList, hasNextPage)
    }

    private fun mangaFromElement(element: Element): SManga {
        val titleElement = element.selectFirst("a.text-ellipsis[href^=/truyen/]")!!
        val coverElement = element.selectFirst("div.cover")

        return SManga.create().apply {
            title = titleElement.text()
            setUrlWithoutDomain(titleElement.absUrl("href"))
            thumbnail_url = coverElement?.let { it: Element -> getThumbnailUrl(it) }
        }
    }

    private fun getThumbnailUrl(element: Element): String? = element.absUrl("data-bg")
        .ifEmpty { parseBackgroundUrl(element.attr("style")).orEmpty() }
        .ifBlank { null }

    // ============================== Details ===============================

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()

        return SManga.create().apply {
            title = document.selectFirst("div.flex.flex-row.truncate.mb-4 span.grow.text-lg.ml-1.text-ellipsis.font-semibold")!!.text()
            thumbnail_url = document.selectFirst("div.md\\:col-span-2 div.cover-frame > div.cover")
                ?.let { element: Element -> getThumbnailUrl(element) }
            author = document.infoRow("Tác giả:")
                ?.select("a[href*=/tac-gia/]")
                ?.joinToString { it: Element -> it.text() }
                ?.ifEmpty { null }

            genre = document.infoRow("Thể loại:")
                ?.select("a[href*=/the-loai/]")
                ?.joinToString { it: Element -> it.text() }
                ?.ifEmpty { null }

            val altNames = document.infoRow("Tên khác:")
                ?.select("a, span:not(.font-semibold)")
                ?.joinToString { it.text().trim() }
                ?.takeIf { it.isNotBlank() }

            val summary = document.select("p:contains(Tóm tắt) ~ p").joinToString("\n") { it.wholeText() }.trim()

            description = buildString {
                if (altNames != null) {
                    append("Tên khác: ", altNames, "\n\n")
                }
                append(summary)
            }.trim()

            status = parseStatus(document.infoRow("Tình trạng:")?.text())
        }
    }

    private fun Document.infoRow(label: String): Element? = select("div")
        .firstOrNull { row: Element -> row.selectFirst("> span.font-semibold")?.text() == label }

    private fun parseStatus(rawStatus: String?): Int = when {
        rawStatus.isNullOrBlank() -> SManga.UNKNOWN
        rawStatus.contains("đang tiến hành", ignoreCase = true) -> SManga.ONGOING
        rawStatus.contains("hoàn thành", ignoreCase = true) -> SManga.COMPLETED
        rawStatus.contains("completed", ignoreCase = true) -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    // ============================= Chapters ===============================

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val chapterElements = document.select("ul.overflow-y-auto a[href^=/truyen/]:has(span.timeago)")
            .ifEmpty { document.select("a[href^=/truyen/]:has(span.timeago)") }

        return chapterElements.map { element: Element ->
            SChapter.create().apply {
                setUrlWithoutDomain(element.absUrl("href"))
                name = element.selectFirst("span.text-ellipsis")?.text() ?: "Chapter"
                date_upload = parseChapterDate(element.selectFirst("span.timeago"))
            }
        }
    }

    private fun parseChapterDate(timeElement: Element?): Long = timeElement?.attr("datetime")
        ?.ifEmpty { null }
        ?.let { value: String -> DATE_TIME_FORMAT.tryParse(value).takeIf { parsed -> parsed != 0L } }
        ?: 0L

    // ============================== Pages =================================

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = client.newCall(pageListRequest(chapter))
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
        val document = response.asJsoup()
        val html = document.outerHtml()
        val chapterUrl = response.request.url.toString()
        val actionToken = ACTION_TOKEN_REGEX.find(html)?.groupValues?.get(1)
            ?: throw Exception("Không tìm thấy action token")
        val encryptedPayload = ENCRYPTED_IMAGES_REGEX.find(html)?.groupValues?.get(1)
            ?: throw Exception("Không tìm thấy dữ liệu ảnh")
        val imageToken = resolveImageToken(chapterUrl, actionToken)

        val encryptedRows = ENCRYPTED_IMAGE_ROW_REGEX.findAll(encryptedPayload)
            .mapNotNull { row: MatchResult ->
                row.groupValues.getOrNull(1)
                    ?.split(',')
                    ?.mapNotNull { it.toIntOrNull() }
                    ?.takeIf { it.isNotEmpty() }
            }
            .toList()

        val imageUrls = document.select("#image-container[data-idx]")
            .asSequence()
            .mapNotNull { element: Element -> element.attr("data-idx").toIntOrNull() }
            .distinct()
            .sorted()
            .mapNotNull { idx: Int -> encryptedRows.getOrNull(idx) }
            .map { codes: List<Int> -> decodeImageUrl(codes, actionToken) }
            .filter { it.isNotBlank() }
            .toList()

        return imageUrls.mapIndexed { index: Int, imageUrl: String ->
            Page(index, imageToken, imageUrl)
        }
    }

    override fun imageRequest(page: Page): Request {
        val imageUrl = page.imageUrl ?: throw Exception("Không tìm thấy URL ảnh")
        val imageToken = page.url
        return GET(imageUrl, imageHeaders(imageToken))
    }

    private fun decodeImageUrl(codes: List<Int>, actionToken: String): String {
        val result = StringBuilder(codes.size)
        codes.forEachIndexed { index, code ->
            val keyCode = actionToken[index % actionToken.length].code
            result.append((code xor keyCode).toChar())
        }
        return result.toString()
    }

    private fun imageHeaders(imageToken: String) = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)
        .add("Token", imageToken)
        .build()

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    private fun hasTurnstileChallenge(chapter: SChapter): Boolean {
        val chapterUrl = getChapterUrl(chapter)

        return runCatching {
            client.newCall(GET(chapterUrl, headers)).execute().use { response ->
                val body = runCatching { response.body.string() }.getOrDefault("")
                isTurnstileChallenge(response, body)
            }
        }.getOrDefault(false)
    }

    private fun isTurnstileChallenge(response: Response, body: String): Boolean = response.header("cf-mitigated")?.equals("challenge", ignoreCase = true) == true ||
        hasTurnstileElement(body) ||
        body.contains("<title>Just a moment", ignoreCase = true)

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
        ) != null
    }

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${chapter.url}"

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

    private fun parseBackgroundUrl(styleValue: String?): String? {
        if (styleValue.isNullOrBlank()) return null

        val rawUrl = BACKGROUND_URL_REGEX.find(styleValue)?.groupValues?.get(1) ?: return null
        return rawUrl.toHttpUrlOrNull()?.toString()
            ?: "$baseUrl${rawUrl.takeIf { it.startsWith("/") } ?: "/$rawUrl"}"
    }

    private var cachedImageToken: String? = null

    private fun resolveImageToken(chapterUrl: String, latestToken: String): String {
        if (IMAGE_TOKEN_REGEX.matches(latestToken)) {
            cachedImageToken = latestToken
            return latestToken
        }

        val webViewTokens = fetchWebViewTokens(chapterUrl)
        webViewTokens.actionToken?.takeIf { IMAGE_TOKEN_REGEX.matches(it) }?.let { actionToken ->
            cachedImageToken = actionToken
            return actionToken
        }

        val refreshed = fetchImageTokenFromSession(chapterUrl, latestToken, webViewTokens.turnstileToken)
        if (refreshed != null && IMAGE_TOKEN_REGEX.matches(refreshed)) {
            cachedImageToken = refreshed
            return refreshed
        }

        return cachedImageToken?.takeIf { IMAGE_TOKEN_REGEX.matches(it) }
            ?: throw Exception(CLOUDFLARE_VERIFY_MESSAGE)
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Synchronized
    private fun fetchWebViewTokens(chapterUrl: String): WebViewTokens {
        val handler = Handler(Looper.getMainLooper())
        val latch = CountDownLatch(1)
        val webViewRef = arrayOfNulls<WebView>(1)
        var tokens = WebViewTokens()

        handler.post {
            val webView = WebView(Injekt.get<Application>())
            webViewRef[0] = webView

            with(webView.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                blockNetworkImage = true
            }

            fun complete() {
                latch.countDown()
            }

            fun evaluateTokens(attempt: Int) {
                webView.evaluateJavascript(WEBVIEW_TOKEN_EXTRACT_SCRIPT) { rawValue ->
                    val payload = rawValue
                        ?.takeUnless { it == "null" }
                        ?.removeSurrounding("\"")
                        ?.replace("\\\\", "\\")
                        .orEmpty()

                    val actionToken = payload.substringAfter("ACTION::", "").substringBefore("::TURN::", "")
                        .takeIf { IMAGE_TOKEN_REGEX.matches(it) }
                    val turnstileToken = payload.substringAfter("::TURN::", "")
                        .takeIf { it.isNotBlank() }

                    if (actionToken != null || turnstileToken != null) {
                        tokens = WebViewTokens(actionToken, turnstileToken)
                    }

                    if (tokens.actionToken != null || attempt + 1 >= WEBVIEW_TOKEN_MAX_RETRIES) {
                        complete()
                    } else {
                        handler.postDelayed({ evaluateTokens(attempt + 1) }, WEBVIEW_TOKEN_RETRY_DELAY_MS)
                    }
                }
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    evaluateTokens(0)
                }
            }
            webView.loadUrl(chapterUrl)
        }

        latch.await(WEBVIEW_TOKEN_WAIT_SECONDS, TimeUnit.SECONDS)
        handler.post {
            webViewRef[0]?.apply {
                stopLoading()
                destroy()
            }
            webViewRef[0] = null
        }

        return tokens
    }

    private fun fetchImageTokenFromSession(chapterUrl: String, csrfToken: String, turnstileToken: String?): String? {
        val baseHttpUrl = baseUrl.toHttpUrl()
        val jarCookies = client.cookieJar.loadForRequest(baseHttpUrl)
        val jarCookieHeader = jarCookies.joinToString("; ") { cookie -> "${cookie.name}=${cookie.value}" }
        val webViewCookieHeader = runCatching { CookieManager.getInstance().getCookie(baseUrl) }.getOrNull().orEmpty()
        val mergedCookieHeader = sequenceOf(jarCookieHeader, webViewCookieHeader)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("; ")

        val hasCfClearance = mergedCookieHeader.contains("cf_clearance=", ignoreCase = true)
        if (!hasCfClearance) return null

        val xsrfToken = extractCookieValue(mergedCookieHeader, "XSRF-TOKEN")
            ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }
            ?: jarCookies.firstOrNull { it.name.equals("XSRF-TOKEN", ignoreCase = true) }
            ?.value
            ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }

        val escapedTurnstile = turnstileToken.orEmpty()
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        val payload = """{"cf-turnstile-response":"$escapedTurnstile"}"""
        val request = Request.Builder()
            .url("$baseUrl/get_token")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .headers(
                headersBuilder()
                    .set("Referer", chapterUrl)
                    .set("Origin", baseUrl)
                    .set("X-CSRF-TOKEN", csrfToken)
                    .set("X-Requested-With", "XMLHttpRequest")
                    .set("Accept", "application/json, text/plain, */*")
                    .apply {
                        if (mergedCookieHeader.isNotBlank()) {
                            set("Cookie", mergedCookieHeader)
                        }
                        if (!xsrfToken.isNullOrBlank()) {
                            set("X-XSRF-TOKEN", xsrfToken)
                        }
                    }
                    .build(),
            )
            .build()

        return runCatching {
            client.newCall(request).execute().use { tokenResponse ->
                if (!tokenResponse.isSuccessful) return@use null
                val responseBody = tokenResponse.body.string()
                if (!IS_BOT_FALSE_REGEX.containsMatchIn(responseBody)) return@use null
                IMAGE_TOKEN_RESPONSE_REGEX.find(responseBody)?.groupValues?.get(1)
            }
        }.getOrNull()
    }

    private fun extractCookieValue(cookieHeader: String, cookieName: String): String? {
        if (cookieHeader.isBlank()) return null
        return cookieHeader.split(';')
            .asSequence()
            .map { it.trim() }
            .firstOrNull { cookie -> cookie.startsWith("$cookieName=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
            ?.ifBlank { null }
    }

    companion object {
        const val PREFIX_ID_SEARCH = "id:"
        private const val DEFAULT_BASE_URL_PREF = "defaultBaseUrl"
        private const val BASE_URL_PREF = "overrideBaseUrl"
        private const val BASE_URL_PREF_TITLE = "Ghi đè URL cơ sở"
        private const val BASE_URL_PREF_SUMMARY = "Dành cho sử dụng tạm thời, cập nhật tiện ích sẽ xóa cài đặt."
        private const val CLOUDFLARE_VERIFY_MESSAGE = "Mở webview để xác minh cloudflare cho chương này"
        private const val WEBVIEW_TOKEN_WAIT_SECONDS = 15L
        private const val WEBVIEW_TOKEN_MAX_RETRIES = 40
        private const val WEBVIEW_TOKEN_RETRY_DELAY_MS = 250L
        private val BACKGROUND_URL_REGEX = Regex("""background-image:\s*url\(['"]?([^'")]+)""", RegexOption.IGNORE_CASE)
        private val IMAGE_TOKEN_REGEX = Regex("""^[a-f0-9]{64}$""")
        private val IS_BOT_FALSE_REGEX = Regex("""["']is_bot["']\s*:\s*false""", RegexOption.IGNORE_CASE)
        private val IMAGE_TOKEN_RESPONSE_REGEX = Regex("""["']action_token["']\s*:\s*["']([a-f0-9]{64})["']""")
        private val ACTION_TOKEN_REGEX = Regex("""<meta\s+name=["']action_token["']\s+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val ENCRYPTED_IMAGES_REGEX = Regex("""var\s+_u\s*=\s*(\[\[.*?]]);""", RegexOption.DOT_MATCHES_ALL)
        private val ENCRYPTED_IMAGE_ROW_REGEX = Regex("""\[(\d+(?:,\d+)*)]""")
        private val WEBVIEW_TOKEN_EXTRACT_SCRIPT = """
            (function() {
                var action = '';
                var candidates = [window.actionToken, window.__actionToken, window.imageToken, window.__imageToken];
                for (var i = 0; i < candidates.length; i++) {
                    var v = candidates[i];
                    if (typeof v === 'string' && /^[a-f0-9]{64}$/.test(v)) {
                        action = v;
                        break;
                    }
                }
                var turn = '';
                var selectors = [
                    'input[name="cf-turnstile-response"]',
                    'textarea[name="cf-turnstile-response"]',
                    '#cf-turnstile-response',
                    '#report-cf-turnstile-response'
                ];
                for (var j = 0; j < selectors.length; j++) {
                    var el = document.querySelector(selectors[j]);
                    if (!el) continue;
                    var val = (el.value || el.getAttribute('value') || '').trim();
                    if (val.length > 0) {
                        turn = val;
                        break;
                    }
                }
                if (!turn && typeof window.turnstile !== 'undefined') {
                    try {
                        var maybe = window.turnstile.getResponse();
                        if (typeof maybe === 'string' && maybe.length > 0) {
                            turn = maybe;
                        }
                    } catch (e) {}
                }
                return 'ACTION::' + action + '::TURN::' + turn;
            })();
        """.trimIndent()

        private val DATE_TIME_FORMAT by lazy {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX", Locale.ROOT).apply {
                timeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
            }
        }
    }

    private data class WebViewTokens(
        val actionToken: String? = null,
        val turnstileToken: String? = null,
    )
}
