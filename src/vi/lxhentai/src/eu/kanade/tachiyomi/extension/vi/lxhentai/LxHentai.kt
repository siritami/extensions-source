package eu.kanade.tachiyomi.extension.vi.lxhentai

import android.util.Base64
import android.util.Log
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.get
import keiyoushi.utils.getTurnstileToken
import keiyoushi.utils.parseAs
import keiyoushi.utils.stringOrNull
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.toJsonRequestBody
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.time.Instant

@Source
abstract class LxHentai : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        rateLimit(3)
        addInterceptor(::refreshTokenOnImage403)
    }

    // ============================== Popular ===============================

    override suspend fun getPopularManga(page: Int): MangasPage = parseMangaPage(client.get(browseMangaUrl(page, "-views"), Fetch.DOCUMENT))

    // ============================== Latest ================================

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseMangaPage(client.get(browseMangaUrl(page, "-updated_at"), Fetch.DOCUMENT))

    // ============================== Search ================================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
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
        return parseMangaPage(client.get(url, Fetch.DOCUMENT))
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.firstOrNull() != "truyen") return null

        val slug = url.pathSegments.getOrNull(1) ?: return null
        val manga = SManga.create().apply {
            setUrlWithoutDomain("/truyen/$slug")
        }
        return fetchMangaUpdate(manga, emptyList(), true, false).manga
    }

    private fun browseMangaUrl(page: Int, sortBy: String): HttpUrl = "$baseUrl/tim-kiem".toHttpUrl().newBuilder()
        .addQueryParameter("sort", sortBy)
        .addQueryParameter("page", page.toString())
        .addQueryParameter("filter[status]", "ongoing,completed,paused")
        .build()

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

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get("$baseUrl${manga.url}", Fetch.DOCUMENT).asJsoup()
        return SMangaUpdate(
            manga = parseMangaDetails(document, manga),
            chapters = parseChapterList(document),
        )
    }

    private fun parseMangaDetails(document: Document, manga: SManga): SManga = SManga.create().apply {
        setUrlWithoutDomain(manga.url)
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
            ?.joinToString { it.text() }
            ?.takeIf { it.isNotEmpty() }

        val summary = document.select("p:contains(Tóm tắt) ~ p").joinToString("\n") { it.wholeText() }.trim()

        description = buildString {
            if (altNames != null) {
                append("Tên khác: ", altNames, "\n\n")
            }
            append(summary)
        }.trim()

        status = parseStatus(document.infoRow("Tình trạng:")?.text())
    }

    private fun Document.infoRow(label: String): Element? = select("div")
        .firstOrNull { row: Element -> row.selectFirst("> span.font-semibold")?.text() == label }

    private fun parseStatus(rawStatus: String?): Int {
        val status = rawStatus?.lowercase() ?: return SManga.UNKNOWN
        return when {
            "đang tiến hành" in status -> SManga.ONGOING
            "hoàn thành" in status || "completed" in status -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }

    // ============================= Chapters ===============================

    private fun parseChapterList(document: Document): List<SChapter> {
        val chapterElements = document.select("ul.overflow-y-auto a[href^=/truyen/]:has(span.timeago)")
            .ifEmpty { document.select("a[href^=/truyen/]:has(span.timeago)") }

        return chapterElements.mapNotNull { element: Element ->
            val chapterName = element.selectFirst("span.text-ellipsis")?.text()
                ?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null

            SChapter.create().apply {
                setUrlWithoutDomain(element.absUrl("href"))
                name = chapterName
                date_upload = parseChapterDate(element.selectFirst("span.timeago"))
            }
        }
    }

    private fun parseChapterDate(timeElement: Element?): Long = Instant.parseOrNull(timeElement?.attr("datetime").orEmpty())?.toEpochMilliseconds() ?: 0L

    // ============================== Pages =================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = "$baseUrl${chapter.url}"

        var lastError: Exception? = null
        // The XSRF-TOKEN cookie can rotate between requests (e.g. after /get_token),
        // which makes a stale page token fail with HTTP 419. Retry once with a fresh page.
        repeat(2) {
            try {
                val t0 = System.nanoTime()
                val chapterBody = client.get(chapterUrl, Fetch.DOCUMENT).body.string()
                val t1 = System.nanoTime()
                val chapterDocument = chapterBody.asJsoup(chapterUrl)
                val t2 = System.nanoTime()
                val chapterHtml = chapterBody
                Log.e(LOG_TAG, "timings: fetch=${(t1 - t0) / 1_000_000}ms parse=${(t2 - t1) / 1_000_000}ms body=${chapterBody.length}")

                val t3 = System.nanoTime()
                val mfKey = extractMfKey(chapterHtml)
                    ?: throw Exception("Không giải mã được viewer")
                val t4 = System.nanoTime()
                val component = findViewerComponent(chapterDocument)
                    ?: throw Exception("Không tìm thấy component ảnh")
                val t5 = System.nanoTime()
                Log.e(LOG_TAG, "timings: mfKey=${(t4 - t3) / 1_000_000}ms component=${(t5 - t4) / 1_000_000}ms")
                val mfKid = component.serverMemo["data"]["manifestKid"]?.stringOrNull

                val csrfToken = csrfTokenRegex.find(chapterHtml)?.groupValues?.get(1).orEmpty()
                val actionToken = getActionToken(chapterUrl, csrfToken, chapterHtml, mfKey, mfKid)
                val imageUrls = fetchImageManifest(chapterUrl, component, csrfToken, actionToken, mfKey)
                if (imageUrls.isEmpty()) return@repeat

                val pageMetadata = encodePageMetadata(chapterUrl, actionToken)

                return imageUrls.mapIndexed { index: Int, imageUrl: String ->
                    Page(index, url = pageMetadata, imageUrl = imageUrl)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }

        throw lastError ?: Exception("Không tải được danh sách trang")
    }

    private fun findViewerComponent(document: Document): LivewireInitialData? = document.body().allElements
        .asSequence()
        .filter { element: Element -> element.hasAttr("wire:initial-data") }
        .mapNotNull { element: Element ->
            try {
                element.attr("wire:initial-data").parseAs<LivewireInitialData>()
            } catch (_: Exception) {
                null
            }
        }
        .firstOrNull { component: LivewireInitialData ->
            component.fingerprint["name"]?.stringOrNull == "chapter-viewer-images"
        }

    /**
     * Extracts the `__lxMfkey` XOR key used to decrypt the image manifest.
     *
     * The key is buried inside hex-encoded string arrays
     * (`var _<name> = ["hex", ...]`) nested in obfuscated scripts; each decoded
     * layer may contain another payload or the key itself. Walks up to 4
     * layers (currently 2 are used) to tolerate extra nesting.
     *
     * @return the key with `\/` unescaped, or `null` if not found.
     */
    private fun extractMfKey(html: String): String? {
        var candidates = listOf(html)
        repeat(4) {
            val next = mutableListOf<String>()
            for (text in candidates) {
                mfKeyRegex.find(text)?.let { return it.groupValues[1].replace("\\/", "/") }
                next.addAll(decodeHexPayloads(text))
            }
            if (next.isEmpty()) return null
            candidates = next
        }
        return null
    }

    /**
     * Finds `var _<name> = ["hex", ...]` arrays and decodes each to a JS layer.
     *
     * Uses indexOf scanning instead of a greedy regex over array contents:
     * arrays of non-hex strings (e.g. the base64 decoy) are rejected at their
     * first non-hex character, which keeps this fast on ~600 KB pages.
     */
    private fun decodeHexPayloads(text: String): List<String> {
        val layers = mutableListOf<String>()
        var offset = 0
        while (true) {
            val start = arrayStartRegex.find(text, offset)?.let { it.range.last + 1 } ?: break
            val end = text.indexOf(']', start)
            if (end < 0) break
            offset = end + 1
            val hex = extractHex(text, start, end) ?: continue
            decodeHexPayload(hex)?.let(layers::add)
        }
        return layers
    }

    /**
     * Reads the `"hex", "hex", ...` elements between [start] and [end] as one
     * concatenated hex string. Returns `null` as soon as any other character
     * appears, so non-hex arrays are rejected without scanning them fully.
     */
    private fun extractHex(text: String, start: Int, end: Int): String? {
        // Reject non-hex arrays fast: hex entries are short fixed-length strings,
        // so if the first quoted span is much longer than a typical hex entry
        // (e.g. a base64 decoy), skip the scan entirely.
        val firstQuote = text.indexOf('"', start)
        if (firstQuote < 0 || firstQuote >= end) return null
        val secondQuote = text.indexOf('"', firstQuote + 1)
        if (secondQuote < 0 || secondQuote >= end) return null
        // Hex entries are short (~50 chars); decoy arrays pack a single long
        // base64 blob. Bail on entries far longer than a hex entry can be.
        if (secondQuote - firstQuote - 1 > 512) return null

        val hex = StringBuilder(end - start)
        for (i in start until end) {
            when (text[i]) {
                in '0'..'9', in 'a'..'f', in 'A'..'F' -> hex.append(text[i])
                '"', ',', ' ', '\t', '\n', '\r' -> Unit
                else -> return null
            }
        }
        return hex.toString().takeIf { it.length >= 66 }
    }

    private fun decodeHexPayload(hex: String): String? {
        if (hex.length < 66 || hex.length % 2 != 0) return null
        val bytes = ByteArray(hex.length / 2)
        for (i in bytes.indices) {
            val hi = hexVal(hex[2 * i])
            val lo = hexVal(hex[2 * i + 1])
            if (hi < 0 || lo < 0) return null
            bytes[i] = (hi shl 4 or lo).toByte()
        }
        val out = ByteArray(bytes.size - 32)
        for (i in out.indices) {
            out[i] = (bytes[i + 32].toInt() xor bytes[i % 32].toInt()).toByte()
        }
        return String(out, Charsets.UTF_8).takeIf { it.isNotBlank() }
    }

    private fun hexVal(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    private fun xorB64Decode(payload: String, key: String): String? {
        val bytes = try {
            Base64.decode(payload, Base64.DEFAULT)
        } catch (_: Exception) {
            return null
        }
        val keyBytes = key.toByteArray(Charsets.UTF_8)
        for (i in bytes.indices) {
            bytes[i] = (bytes[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
        }
        return String(bytes, Charsets.UTF_8)
    }

    private suspend fun getActionToken(chapterUrl: String, csrfToken: String, chapterHtml: String, mfKey: String, mfKid: String?, forceRefresh: Boolean = false): String {
        // The token is bound to the chapter's manifestKid, so it must not be
        // reused across chapters.
        if (!forceRefresh) {
            cachedActionToken?.takeIf { cachedActionTokenChapter == chapterUrl }?.let { return it }
        }

        val siteKey = recaptchaRegex.find(chapterHtml)?.groupValues?.get(1).orEmpty()
            .ifEmpty { cachedActionTokenSiteKey.orEmpty() }
            .also { if (it.isNotEmpty()) cachedActionTokenSiteKey = it }

        val tokenHeaders = headersBuilder()
            .set("Accept", "application/json")
            .set("Content-Type", "application/json")
            .set("X-CSRF-TOKEN", csrfToken)
            .set("Referer", chapterUrl.toHttpUrlOrNull()?.toString() ?: chapterUrl)
            .set("Origin", baseUrl)
            .build()

        val tokenUrl = buildString {
            append("$baseUrl/get_token")
            if (!mfKid.isNullOrEmpty()) {
                append("?kid=", mfKid)
            }
        }

        fun TokenResponse.actionToken(): String? {
            action_token?.let { return it }
            val encrypted = e ?: return null
            return xorB64Decode(encrypted, mfKey)
                ?.parseAs<EncryptedToken>()
                ?.at
        }

        fun TokenResponse.store(): String? = actionToken()?.also { token ->
            cachedActionToken = token
            cachedActionTokenChapter = chapterUrl
            cachedActionTokenCsrf = csrfToken
            cachedActionTokenMfKey = mfKey
        }

        val getResponse = client.get(tokenUrl, tokenHeaders, Fetch.XHR, ensureSuccess = false)
        if (getResponse.isSuccessful) {
            val data = getResponse.parseAs<TokenResponse>()
            if (!data.is_bot) {
                data.store()?.let { return it }
            }
        } else {
            getResponse.close()
        }

        if (siteKey.isNotEmpty()) {
            val turnstileToken = getTurnstileToken(
                url = chapterUrl,
                siteKey = siteKey,
            )

            // The WebView session can rotate cookies the page's CSRF token was issued
            // for. Re-fetch the page so the token matches the current session.
            val freshCsrf = client.get(chapterUrl, Fetch.DOCUMENT).asJsoup().html()
                .let { csrfTokenRegex.find(it)?.groupValues?.get(1) }
                ?: csrfToken
            val postHeaders = headersBuilder()
                .set("Accept", "application/json")
                .set("Content-Type", "application/json")
                .set("X-CSRF-TOKEN", freshCsrf)
                .set("Referer", chapterUrl.toHttpUrlOrNull()?.toString() ?: chapterUrl)
                .set("Origin", baseUrl)
                .build()

            val payload = TurnstilePayload(turnstileResponse = turnstileToken).toJsonRequestBody()
            val postResponse = client.post(tokenUrl, postHeaders, Fetch.XHR, payload, ensureSuccess = false)
            if (postResponse.isSuccessful) {
                val data = postResponse.parseAs<TokenResponse>()
                if (!data.is_bot) {
                    data.actionToken()?.also { token ->
                        cachedActionToken = token
                        cachedActionTokenChapter = chapterUrl
                        cachedActionTokenCsrf = csrfToken
                        cachedActionTokenMfKey = mfKey
                        return token
                    }
                }
            } else {
                postResponse.close()
            }
        }

        return ""
    }

    private suspend fun fetchImageManifest(chapterUrl: String, component: LivewireInitialData, csrfToken: String, actionToken: String, mfKey: String): List<String> {
        val message = LivewireMessage(
            fingerprint = component.fingerprint,
            serverMemo = component.serverMemo,
            updates = listOf(
                LivewireUpdate(
                    type = "fireEvent",
                    payload = LivewireUpdatePayload(
                        id = (1..7).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString(""),
                        event = "lxViewerImagesUnlock",
                        params = listOf(actionToken),
                    ),
                ),
            ),
        )

        val manifestHeaders = headersBuilder()
            .set("Accept", "text/html, application/xhtml+xml")
            .set("Content-Type", "application/json")
            .set("X-Livewire", "true")
            .set("X-CSRF-TOKEN", csrfToken)
            .set("Referer", chapterUrl.toHttpUrlOrNull()?.toString() ?: chapterUrl)
            .set("Origin", baseUrl)
            .build()

        val response = client.post(
            "$baseUrl/livewire/message/chapter-viewer-images",
            manifestHeaders,
            Fetch.XHR,
            message.toJsonRequestBody(),
        )

        val manifest = response.parseAs<LivewireResponse>()
            .effects
            ?.dispatches
            ?.firstOrNull { it.event == "lx:srcs" }
            ?.data
            ?.data
            ?.let { xorB64Decode(it, mfKey) }
            ?.parseAs<ImageManifest>()
            ?: return emptyList()

        return manifest.u.filterIndexed { index, _ -> (manifest.f.getOrNull(index) ?: 0) and 1 == 0 }
    }

    override fun getImageRequest(page: Page): Request {
        val (chapterUrl, actionToken) = decodePageMetadata(page.url)
        val imageUrl = page.imageUrl ?: throw Exception("Không tìm thấy URL ảnh")
        return GET(imageUrl, imageHeaders(chapterUrl, actionToken))
    }

    /**
     * Refreshes the action token when an image fetch is rejected with 403, then
     * retries the request once with the new token. Mirrors the viewer engine's
     * `__refreshImageToken` behaviour.
     */
    private fun refreshTokenOnImage403(chain: okhttp3.Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (response.code != 403) {
            return response
        }
        Log.e(LOG_TAG, "image 403: ${request.url} (token=${request.header("Token")?.take(8)}..., host=${request.url.host})")
        if (request.header("Token") == null) {
            return response
        }
        if (!request.url.host.endsWith("lxmanga.xyz")) {
            return response
        }
        response.close()

        val newToken = runBlocking {
            val csrf = cachedActionTokenCsrf ?: run {
                Log.e(LOG_TAG, "image 403: no cached csrf")
                return@runBlocking null
            }
            val mfKey = cachedActionTokenMfKey ?: run {
                Log.e(LOG_TAG, "image 403: no cached mfKey")
                return@runBlocking null
            }
            // Reuse the standard token path: /get_token without `kid` returns a
            // fresh generic token, which is what the engine's refresh does too.
            getActionToken(
                chapterUrl = request.header("Referer") ?: "$baseUrl/",
                csrfToken = csrf,
                chapterHtml = "",
                mfKey = mfKey,
                mfKid = null,
                forceRefresh = true,
            ).takeIf { it.isNotEmpty() }
        } ?: run {
            Log.e(LOG_TAG, "image 403: refresh returned empty; retrying old token")
            return chain.proceed(request)
        }

        Log.e(LOG_TAG, "image 403: refreshed token=${newToken.take(8)}..., retrying")
        val retried = request.newBuilder()
            .header("Token", newToken)
            .build()
        val retry = chain.proceed(retried)
        Log.e(LOG_TAG, "image 403: retry status=${retry.code}")
        return retry
    }

    private fun imageHeaders(chapterUrl: String, actionToken: String) = super.headersBuilder()
        // The CDN expects the site root Referer (what the browser actually sends
        // for cross-site image requests), not the chapter URL.
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)
        // The CDN only requires the Token header to be present; any non-empty
        // value passes. Falling back to a placeholder keeps image loads working
        // even if the page-level token was lost or stripped.
        .add("Token", actionToken.ifEmpty { "x" })
        .build()

    // ============================== Filters ===============================

    override val supportsFilterFetching get() = true

    override suspend fun fetchFilterData(): JsonElement = client.get("$baseUrl/tim-kiem", Fetch.DOCUMENT).asJsoup()
        .select("label")
        .mapNotNull { element ->
            val slug = genreSlugRegex.matchEntire(element.attr("@click"))
                ?.groupValues
                ?.get(1)
                ?: return@mapNotNull null
            val genreName = element.text().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            GenreOption(genreName, slug)
        }
        .distinctBy { it.slug }
        .toJsonElement()

    override fun getFilterList(data: JsonElement?): FilterList = getFilters(data?.parseAs<List<GenreOption>>())

    // ============================== Helpers ===============================

    private fun parseBackgroundUrl(styleValue: String?): String? {
        if (styleValue.isNullOrBlank()) return null

        val rawUrl = backgroundUrlRegex.find(styleValue)?.groupValues?.get(1) ?: return null
        return rawUrl.toHttpUrlOrNull()?.toString()
            ?: "$baseUrl${rawUrl.takeIf { it.startsWith("/") } ?: "/$rawUrl"}"
    }

    private fun encodePageMetadata(chapterUrl: String, actionToken: String): String = "$chapterUrl\n$actionToken"

    private fun decodePageMetadata(rawMetadata: String): Pair<String, String> {
        val separatorIndex = rawMetadata.lastIndexOf('\n')
        if (separatorIndex < 0) {
            return rawMetadata to ""
        }

        val chapterUrl = rawMetadata.substring(0, separatorIndex)
        val actionToken = rawMetadata.substring(separatorIndex + 1)
        return chapterUrl to actionToken
    }

    private var cachedActionToken: String? = null
    private var cachedActionTokenChapter: String? = null
    private var cachedActionTokenCsrf: String? = null
    private var cachedActionTokenMfKey: String? = null
    private var cachedActionTokenSiteKey: String? = null

    private val csrfTokenRegex = Regex("""var\s+csrf_token\s*=\s*'([^']+)'""")
    private val recaptchaRegex = Regex("""var\s+recaptcha\s*=\s*'([^']+)'""")
    private val mfKeyRegex = Regex("""__lxMfkey\s*=\s*"([^"]+)"""")
    private val arrayStartRegex = Regex("""var\s+_\w+\s*=\s*\[""")
    private val backgroundUrlRegex = Regex("""background-image:\s*url\(['"]?([^'")]+)""", RegexOption.IGNORE_CASE)
    private val genreSlugRegex = Regex("""toggleGenre\('([^']+)'\)""")

    private companion object {
        const val LOG_TAG = "LxHentai"
    }
}
