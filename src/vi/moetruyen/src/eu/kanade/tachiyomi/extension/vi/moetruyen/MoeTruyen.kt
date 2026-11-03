package eu.kanade.tachiyomi.extension.vi.moetruyen

import android.widget.Toast
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.utils.applicationContext
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.runWebView
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.toJsonRequestBody
import keiyoushi.utils.tryParseDate
import keiyoushi.utils.ui.askInput
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Source
abstract class MoeTruyen : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        addInterceptor(imgxInterceptor())
        rateLimit(3)
    }

    // The site only embeds the reader-instance token and issues the imgx_document cookie for
    // requests that look like a real browser navigation. Any Accept-Encoding other than
    // "identity" (OkHttp adds "Accept-Encoding: gzip" via transparent gzip) makes the server
    // withhold the token — force identity. The app-level UA already names Chrome/Firefox,
    // which is what BrowserHeadersInterceptor needs to inject the Sec-Fetch nav headers.
    override fun Headers.Builder.configureHeaders(): Headers.Builder =
        set("Accept-Encoding", "identity")
    }

    // ============================== Popular ===============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$baseUrl/manga".toHttpUrl().newBuilder()
            .addQueryParameter("sort", "views_desc")
            .addQueryParameter("page", page.toString())
            .build()

        return parseMangaList(client.get(url, Fetch.DOCUMENT).asJsoup())
    }

    // ============================== Latest ================================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$baseUrl/manga".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .build()

        return parseMangaList(client.get(url, Fetch.DOCUMENT).asJsoup())
    }

    private fun mangaFromElement(element: Element): SManga = SManga.create().apply {
        val linkElement = element.selectFirst("a[href^=/manga/]")!!
        setUrlWithoutDomain(linkElement.absUrl("href"))
        title = getFullListTitle(element)
        thumbnail_url = element.selectFirst("img")?.let {
            it.absUrl("data-src").ifEmpty { it.absUrl("src") }
        }
    }

    private fun getFullListTitle(element: Element): String {
        val titleElement = element.selectFirst("h3")!!
        val titleAttr = titleElement.attr("title")
        if (titleAttr.isNotEmpty()) {
            return titleAttr
        }

        val titleText = titleElement.text()
        if (!titleText.endsWith("...")) {
            return titleText
        }

        val imageAlt = element.selectFirst("img")?.attr("alt")
            ?.removePrefix("Bìa ")
            ?.trim()
            ?.ifEmpty { null }

        return imageAlt ?: titleText
    }

    private fun parseMangaList(document: Document): MangasPage {
        val mangas = document.select("article.manga-card--list")
            .map(::mangaFromElement)

        val hasNextPage = document
            .selectFirst("nav[aria-label='Phân trang truyện'] a[aria-label='Trang sau']:not(.is-disabled)")
            ?.attr("href")
            ?.let { it != "#" }
            ?: false

        return MangasPage(mangas, hasNextPage)
    }

    // ============================== Search ================================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val status = filters.firstInstanceOrNull<StatusFilter>()?.toUriPart()?.ifEmpty { null }
        val sort = filters.firstInstanceOrNull<SortFilter>()?.toUriPart()?.ifEmpty { null }
        val genres = filters.firstInstanceOrNull<GenreFilter>()?.state.orEmpty()
        val includedGenres = genres.filter { it.isIncluded() }
        val excludedGenres = genres.filter { it.isExcluded() }
        val hasFilter = status != null || (sort != null && sort != "updated_desc") || includedGenres.isNotEmpty() || excludedGenres.isNotEmpty()

        if (query.isBlank() && !hasFilter) {
            return getLatestUpdates(page)
        }

        val url = "$baseUrl/manga".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .apply {
                if (query.isNotBlank()) {
                    addQueryParameter("q", query)
                }

                status?.let { addQueryParameter("status", it) }
                sort?.let { addQueryParameter("sort", it) }
                includedGenres.forEach { addQueryParameter("include", it.id) }
                excludedGenres.forEach { addQueryParameter("exclude", it.id) }
            }
            .build()

        return parseMangaList(client.get(url, Fetch.DOCUMENT).asJsoup())
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.firstOrNull() != "manga") return null

        val slug = url.pathSegments.getOrNull(1) ?: return null
        val manga = SManga.create().apply { setUrlWithoutDomain("/manga/$slug") }
        return fetchMangaUpdate(manga, emptyList(), true, false).manga
    }

    // ============================== Details ===============================

    private fun parseMangaDetails(document: Document, manga: SManga): SManga = SManga.create().apply {
        setUrlWithoutDomain(manga.url)
        title = document.selectFirst("h1.manga-detail-title")!!.text()
        author = document.select("p.manga-detail-meta-line")
            .firstOrNull { line ->
                line.selectFirst(".manga-detail-meta-label")
                    ?.text()
                    ?.contains("Tác giả")
                    ?: false
            }
            ?.select("a.inline-link")
            ?.joinToString { it.text() }
            ?.ifEmpty { null }
        genre = document.select(".manga-detail-genre-chips a.chip")
            .joinToString { it.text() }
            .ifEmpty { null }
        description = document.selectFirst("[data-description-content]")
            ?.text()
            ?.ifEmpty { null }
            ?: document.selectFirst(".manga-description__text")
                ?.text()
                ?.ifEmpty { null }
        status = parseStatus(document.selectFirst(".manga-status-pill")?.text())
        thumbnail_url = document.selectFirst(".detail-cover img")?.absUrl("src")
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get("$baseUrl${manga.url}", Fetch.DOCUMENT).asJsoup()
        return SMangaUpdate(
            manga = parseMangaDetails(document, manga),
            chapters = if (fetchChapters) fetchChapterList(document) else chapters,
        )
    }

    private fun parseStatus(status: String?): Int = when (status) {
        "Còn tiếp" -> SManga.ONGOING
        "Hoàn thành" -> SManga.COMPLETED
        "Tạm dừng" -> SManga.ON_HIATUS
        else -> SManga.UNKNOWN
    }

    // ============================== Chapters ==============================

    private suspend fun fetchChapterList(firstDocument: Document): List<SChapter> {
        val chapters = mutableListOf<SChapter>()
        val visitedPages = mutableSetOf<String>()
        var currentPageUrl = firstDocument.location()
        var currentDocument = firstDocument

        while (visitedPages.add(currentPageUrl)) {
            chapters += parseChapterList(currentDocument)

            val nextChapterLinkElement: Element? = currentDocument.selectFirst(
                "nav[aria-label*='Phân trang chương'] a[aria-label='Trang chương sau']:not(.is-disabled)",
            )
            val nextChapterPageUrl: String? = nextChapterLinkElement?.let { link ->
                if (link.attr("href") == "#") {
                    null
                } else {
                    link.absUrl("href").ifEmpty { null }
                }
            }

            if (nextChapterPageUrl == null || visitedPages.contains(nextChapterPageUrl)) {
                break
            }

            currentPageUrl = nextChapterPageUrl
            currentDocument = client.get(currentPageUrl, Fetch.DOCUMENT).asJsoup()
        }

        return chapters
    }

    private fun parseChapterList(document: Document): List<SChapter> = document.select("ul.chapter-list li.chapter a.chapter-link").map { element ->
        SChapter.create().apply {
            setUrlWithoutDomain(element.absUrl("href"))
            val title = element.selectFirst(".chapter-num")!!.text()
            val locked = element.selectFirst(".chapter-lock-icon") != null
            name = if (locked) "🔒 $title" else title

            val chapterTime = element.selectFirst(".chapter-time")
            val relativeDate = chapterTime?.text()
            val absoluteDate = chapterTime?.attr("title")
                ?.substringAfter("Cập nhật", missingDelimiterValue = "")
                ?.trim()
                ?.ifEmpty { null }

            date_upload = parseRelativeDate(relativeDate).takeIf { it != 0L }
                ?: parseAbsoluteDate(absoluteDate)
        }
    }

    private fun parseRelativeDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L

        val number = numberRegex.find(dateStr)?.value?.toIntOrNull() ?: return 0L
        val duration = when {
            dateStr.contains("giây") -> number.seconds
            dateStr.contains("phút") -> number.minutes
            dateStr.contains("giờ") -> number.hours
            dateStr.contains("ngày") -> number.days
            dateStr.contains("tuần") -> (number * 7).days
            dateStr.contains("tháng") -> (number * 30).days
            dateStr.contains("năm") -> (number * 365).days
            else -> return 0L
        }

        return (Clock.System.now() - duration).toEpochMilliseconds()
    }

    private fun parseAbsoluteDate(date: String?): Long = dateFormat.tryParseDate(date, dateZone)

    // ============================== Pages =================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = "$baseUrl${chapter.url}"
        var document = client.get(chapterUrl, Fetch.DOCUMENT).asJsoup()

        if (isCommentLocked(document)) {
            unlockByComment(chapter, document, chapterUrl)
            document = client.get(chapterUrl, Fetch.DOCUMENT).asJsoup()
            if (isCommentLocked(document)) {
                Toast.makeText(applicationContext, "Không thể mở khóa chương này", Toast.LENGTH_LONG).show()
                return emptyList()
            }
        }

        var readerPages = document.selectFirst("[data-reader-lazy-pages]")
        var encryptedMedia = ImgxAccessClient.encryptedMedia(document)
        val totalPages = readerPages?.attr("data-reader-total-pages")?.toIntOrNull() ?: 0
        val accessUrl = readerPages?.attr("data-reader-imgx-access-url").orEmpty()
        val isImgx = accessUrl.isNotBlank()
        val plainUrls = plainPageUrls(document)
        val hasRealPlain = plainUrls.any { isRealPageUrl(it) }
        val shouldTryImgx = isImgx && (
            encryptedMedia.any { isRealPageUrl(it.storageKey) || isRealPageUrl(it.downloadUrl) } ||
                (!hasRealPlain && totalPages > 0)
            )

        if (shouldTryImgx) {
            try {
                // The reader-instance token is only embedded for real browser TLS sessions
                // and bound to that session, so the whole channel must run inside a WebView
                // when OkHttp receives a page without it.
                val pages = if (ImgxAccessClient.parseBootstrapConfig(document).bootstrapUrl.isBlank()) {
                    fetchPagesViaWebView(chapterUrl)
                } else {
                    ImgxAccessClient(client, baseUrl, chapterUrl, document)
                        .fetchPages(encryptedMedia, totalPages)
                }.filter { isRealPageUrl(it.storageKey) && isRealPageUrl(it.downloadUrl) }
                if (pages.isNotEmpty()) {
                    pages.forEach { page ->
                        val grant = page.grant
                            ?: throw IllegalStateException("IMGX grant missing page=\${page.pageIndex + 1}")
                        imgxGrants[page.downloadUrl] = grant to page.storageKey
                    }
                    return pages
                        .sortedBy { it.pageIndex }
                        .mapIndexed { index, page -> Page(index, imageUrl = page.downloadUrl) }
                }
            } catch (e: Exception) {
                if (!hasRealPlain) throw e
            }
        }

        return plainUrls
            .filter { isRealPageUrl(it) }
            .distinct()
            .mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }
    }

    /**
     * Runs the IMGX reader channel inside a real WebView: bootstrap ECDH channel,
     * unseal capability, sign page-access proofs, unseal page grants. Returns the
     * granted pages so OkHttp can download and decode the images natively.
     */
    private suspend fun fetchPagesViaWebView(chapterUrl: String): List<ImgxPageAccess> = runWebView(timeout = 60.seconds) {
        webView.post {
            Toast.makeText(
                webView.context,
                "Mòe Truyện: dùng WebView để tải trang (trang chờ chậm hơn)",
                Toast.LENGTH_LONG,
            ).show()
        }
        webMessageBridge(BRIDGE_NAME, setOf(baseUrl)) { message ->
            val result = runCatching { message.parseAs<ImgxWebViewResult>() }.getOrNull()
            if (result == null) {
                reject(IllegalStateException("IMGX WebView result invalid"))
            } else if (!result.ok) {
                reject(IllegalStateException("IMGX WebView channel failed: ${result.error ?: "unknown"}"))
            } else {
                resolve(result.pages.orEmpty())
            }
        }
        onPageFinished {
            evaluateJs(IMGX_WEBVIEW_SCRIPT)
        }
        loadUrl(chapterUrl)
    }

    private fun isCommentLocked(document: Document): Boolean {
        if (document.selectFirst("[data-reader-lazy-pages], img.page-media") != null) return false
        val text = document.body().text()
        return text.contains("Bạn phải bình luận") || text.contains("yêu cầu bình luận")
    }

    private suspend fun isLoggedIn(): Boolean = client.get("$baseUrl/auth/session", Fetch.XHR, ensureSuccess = false).use { response ->
        response.isSuccessful && response.parseAs<AuthSession>().session != null
    }

    private suspend fun unlockByComment(chapter: SChapter, document: Document, chapterUrl: String) {
        if (!isLoggedIn()) {
            throw Exception(loginRequiredMessage)
        }

        val previousUrl = document.select("a[href*=/chapters/]")
            .firstOrNull { it.text().contains("chương trước", ignoreCase = true) }
            ?.absUrl("href")
            ?.takeIf { it.isNotBlank() }
            ?: throw Exception(loginRequiredMessage)

        val comment = askInput(
            title = chapter.name,
            message = "Chương này yêu cầu bình luận ở chương trước\n\nBình luận vô nghĩa tài khoản sẽ bị khoá",
            hint = "Bình luận",
        ) ?: throw Exception("Đã hủy bình luận")
        postChapterComment(previousUrl, chapterUrl, comment)
    }

    private suspend fun postChapterComment(previousChapterUrl: String, referer: String, content: String) {
        val requestId = UUID.randomUUID().toString()
        val body = CommentRequest(content, requestId).toJsonRequestBody()
        val requestHeaders = headers.newBuilder()
            .set("Referer", referer)
            .set("Accept", "application/json")
            .set("Idempotency-Key", requestId)
            .build()
        val response = client.post("$previousChapterUrl/comments", requestHeaders, Fetch.XHR, body, ensureSuccess = false)
        if (!response.isSuccessful) {
            val code = response.code
            val errorBody = response.body.string()
            val apiError = runCatching { errorBody.parseAs<ApiError>() }.getOrNull()
            throw Exception(
                apiError?.error?.takeIf { it.isNotBlank() }
                    ?: when (code) {
                        401, 403 -> loginRequiredMessage
                        else -> "Không thể mở khóa chương ($code)"
                    },
            )
        }
        response.close()
    }

    private fun isRealPageUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        if (url.startsWith("data:")) return false
        val path = url.substringBefore('?')
        return !path.endsWith("/0.js") && !path.endsWith("/0.js/")
    }

    private fun plainPageUrls(document: Document): List<String> = readerImages(document)
        .map { element -> element.absUrl("data-src").ifEmpty { element.absUrl("src") } }
        .filter { it.isNotBlank() && !it.startsWith("data:") }

    private fun imgxInterceptor() = Interceptor { chain ->
        val request = chain.request()
        val grantEntry = imgxGrants.remove(request.url.toString())
            ?: return@Interceptor chain.proceed(request)
        val (grant, storageKey) = grantEntry
        val response = chain.proceed(request)
        val encrypted = response.body.use { body ->
            val source = body.source()
            source.request(Long.MAX_VALUE)
            source.buffer.readByteArray()
        }
        if (
            encrypted.size <= 13 ||
            encrypted[0] != 0x49.toByte() ||
            encrypted[1] != 0x4D.toByte() ||
            encrypted[2] != 0x47.toByte() ||
            encrypted[3] != 0x58.toByte()
        ) {
            return@Interceptor response.newBuilder()
                .body(encrypted.toResponseBody(response.body.contentType()))
                .build()
        }
        val webp = try {
            ImgxCrypto.decodeProtectedPage(encrypted, grant, storageKey)
        } catch (e: Exception) {
            throw e
        }
        if (webp.size < 12 || webp[0] != 0x52.toByte() || webp[1] != 0x49.toByte()) {
        } else {
        }
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Content-Type", "image/webp")
            .header("Content-Length", webp.size.toString())
            .header("Cache-Control", "no-store")
            .body(webp.toResponseBody("image/webp".toMediaType()))
            .build()
    }

    private fun readerImages(document: Document): List<Element> {
        val all = document.select("img.page-media")
        val outsideNoscript = all.filterNot { element ->
            element.parents().any { parent -> parent.tagName().equals("noscript", ignoreCase = true) }
        }
        if (outsideNoscript.isNotEmpty()) {
            val first = outsideNoscript.first()
        }
        return outsideNoscript
    }

    private val imgxGrants = Collections.synchronizedMap(
        object : LinkedHashMap<String, Pair<ImgxGrant, String>>(100, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<ImgxGrant, String>>?): Boolean = size > 100
        },
    )

    // ============================== Filters ===============================

    override val supportsFilterFetching get() = true

    override suspend fun fetchFilterData(): JsonElement = client.get("$baseUrl/manga", Fetch.DOCUMENT).asJsoup()
        .select(".filter-option[data-genre]")
        .mapNotNull { element ->
            val id = element.attr("data-genre").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val name = element.selectFirst(".filter-name")?.text()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            GenreOption(name, id)
        }
        .distinctBy { it.id }
        .toJsonElement()

    override fun getFilterList(data: JsonElement?): FilterList = getFilters(data?.parseAs<List<GenreOption>>())

    // =============================== Related ==============================

    override val supportsRelatedMangas get() = true

    override suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> {
        val document = client.get("$baseUrl${manga.url}", Fetch.DOCUMENT).asJsoup()
        val section = document.selectFirst("section[aria-labelledby=manga-related-similar-title]")
            ?: return emptyList()

        return section.select("article.manga-related-card").mapNotNull { card ->
            val link = card.selectFirst("a.manga-related-card__link[href^=/manga/]")
                ?: return@mapNotNull null
            val title = card.selectFirst("h3")?.text()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null

            SManga.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                this.title = title
                thumbnail_url = card.selectFirst("img")?.absUrl("src")
            }
        }.distinctBy { it.url }
    }

    private val dateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT)
    private val dateZone = ZoneId.of("Asia/Ho_Chi_Minh")
    private val numberRegex = Regex("""\d+""")
    private val loginRequiredMessage = "Chương này cần đăng nhập webview bằng tài khoản phù hợp để xem"

    companion object {

        private const val BRIDGE_NAME = "__imgxKt"

        /**
         * Mirrors imgx-reader.js + imgx-worker.js channel flow:
         * bootstrap ECDH channel -> unseal capability -> HMAC page proofs ->
         * unseal pages -> open channelKeys grants. Result posted to the bridge.
         */
        private const val IMGX_WEBVIEW_SCRIPT = """
            (async () => {
                const send = (obj) => window.__imgxKt.postMessage(JSON.stringify(obj));
                try {
                    const CHAN = 'imgx-reader-channel-v1';
                    const PAGEKEY = 'IMGX-READER-PAGE-KEY-v1';
                    const PVER = 'imgx-page-access-proof-v3';
                    const enc = new TextEncoder();
                    const b64u = (a) => btoa(String.fromCharCode.apply(null, new Uint8Array(a))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+${'$'}/, '');
                    const ub64 = (s) => {
                        const n = String(s).replace(/-/g, '+').replace(/_/g, '/');
                        const b = atob(n + '='.repeat((4 - n.length % 4) % 4));
                        const o = new Uint8Array(b.length);
                        for (let i = 0; i < b.length; i++) o[i] = b.charCodeAt(i);
                        return o;
                    };
                    const html = document.documentElement.innerHTML;
                    const bootUrl = /bootstrapUrl:\s*"([^"]+)"/.exec(html)[1];
                    const reqPath = /requestPath:\s*"([^"]+)"/.exec(html)[1];
                    const chapterId = Number(/chapterId:\s*(\d+)/.exec(html)[1]);
                    const initIdx = JSON.parse('[' + (/initialIndexes:\s*\[([^\]]*)\]/.exec(html) || [null, ''])[1] + ']');
                    const mediaMatch = /media:\s*(\[[\s\S]*?\])\s*,\s*\n\s*connection:/.exec(html);
                    const media = mediaMatch ? JSON.parse(mediaMatch[1]).filter((e) =>
                        e.storageKey && e.storageKey.startsWith('chapters/') &&
                        !e.storageKey.endsWith('/0.js') && !String(e.downloadUrl || '').endsWith('/0.js'),
                    ) : [];
                    const pageIndexes = media.length ? media.map((e) => e.pageIndex) : initIdx;
                    const kp = await crypto.subtle.generateKey({ name: 'ECDH', namedCurve: 'P-256' }, true, ['deriveBits']);
                    const pubB64 = b64u(await crypto.subtle.exportKey('raw', kp.publicKey));
                    const proof = b64u(crypto.getRandomValues(32));
                    const jh = { Accept: 'application/json', 'Content-Type': 'application/json' };
                    const post = async (u, b) => (await fetch(u, {
                        method: 'POST', credentials: 'same-origin', cache: 'no-store',
                        headers: jh, body: JSON.stringify(b),
                    })).json();
                    let shared;
                    const open = async (sealed, prf) => {
                        const peer = await crypto.subtle.importKey('raw', ub64(sealed.publicKey), { name: 'ECDH', namedCurve: 'P-256' }, false, []);
                        const bits = await crypto.subtle.deriveBits({ name: 'ECDH', public: peer }, kp.privateKey, 256);
                        shared = bits;
                        const material = await crypto.subtle.importKey('raw', bits, 'HKDF', false, ['deriveKey']);
                        const key = await crypto.subtle.deriveKey({ name: 'HKDF', hash: 'SHA-256', salt: enc.encode(prf), info: enc.encode(CHAN) }, material, { name: 'AES-GCM', length: 256 }, false, ['decrypt']);
                        const aad = enc.encode(JSON.stringify([CHAN, pubB64, sealed.publicKey, prf]));
                        const plain = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: ub64(sealed.iv), additionalData: aad, tagLength: 128 }, key, ub64(sealed.ciphertext));
                        const list = JSON.parse(new TextDecoder().decode(plain));
                        return { list, material, sealed, prf };
                    };
                    const openGrantKeys = async (page, opened) => {
                        const ck = page.grant && page.grant.channelKeys;
                        if (!ck) return;
                        const material = await crypto.subtle.importKey('raw', shared, 'HKDF', false, ['deriveKey']);
                        const key = await crypto.subtle.deriveKey({ name: 'HKDF', hash: 'SHA-256', salt: enc.encode(opened.prf), info: enc.encode(PAGEKEY) }, material, { name: 'AES-GCM', length: 256 }, false, ['decrypt']);
                        const g = page.grant;
                        const aad = enc.encode(JSON.stringify([PAGEKEY, [pubB64, opened.sealed.publicKey, opened.prf], page.pageIndex, page.storageKey, g.imageId, g.issuedAt, g.expiresAt, g.nonce, g.signature]));
                        const plain = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: ub64(ck.iv), additionalData: aad, tagLength: 128 }, key, ub64(ck.ciphertext));
                        const extra = JSON.parse(new TextDecoder().decode(plain));
                        const merged = Object.assign({}, g);
                        delete merged.channelKeys;
                        for (const k of ['decodeKey', 'wrappedDecodeKey', 'wrappedContentKey', 'wrappedV4Key']) {
                            if (Object.prototype.hasOwnProperty.call(extra, k)) merged[k] = extra[k];
                        }
                        page.grant = merged;
                    };
                    const boot = await post(location.origin + bootUrl, {
                        readerPublicKey: pubB64, bootstrapProof: proof, initialPageIndexes: initIdx,
                    });
                    if (!boot.ok) throw new Error('bootstrap ' + (boot.code || boot.status || 'failed'));
                    const capOpened = await open(boot.sealedCapability, proof);
                    const cap = capOpened.list[0];
                    const granted = new Map();
                    if (boot.sealedInitialPages) {
                        const opened = await open(boot.sealedInitialPages, proof);
                        for (const p of opened.list) {
                            await openGrantKeys(p, opened);
                            granted.set(p.pageIndex, p);
                        }
                    }
                    const pubHashBytes = new Uint8Array(await crypto.subtle.digest('SHA-256', ub64(pubB64)));
                    let pubHash = '';
                    for (const b of pubHashBytes) pubHash += b.toString(16).padStart(2, '0');
                    const secretKey = await crypto.subtle.importKey('raw', ub64(cap.secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
                    let seq = 0;
                    const remaining = pageIndexes.filter((i) => !granted.has(i));
                    for (let s = 0; s < remaining.length; s += 10) {
                        const idx = remaining.slice(s, s + 10);
                        seq += 1;
                        const material = JSON.stringify([PVER, cap.readerInstanceId, chapterId, reqPath, '', idx, boot.serverTime, seq, pubHash]);
                        const sig = b64u(await crypto.subtle.sign({ name: 'HMAC', hash: 'SHA-256' }, secretKey, enc.encode(material)));
                        const acc = await post(location.origin + reqPath, {
                            pageIndexes: idx,
                            pageAccessProof: {
                                version: PVER, readerInstanceId: cap.readerInstanceId,
                                issuedAt: boot.serverTime, sequence: seq, proof: sig,
                            },
                            readerPublicKey: pubB64,
                        });
                        if (!acc.ok) throw new Error('page-access ' + (acc.code || acc.status || 'failed'));
                        const opened = await open(acc.sealedPages, sig);
                        for (const p of opened.list) {
                            await openGrantKeys(p, opened);
                            granted.set(p.pageIndex, p);
                        }
                    }
                    const pages = pageIndexes.map((i) => granted.get(i)).filter(Boolean);
                    send({ ok: true, pages: pages });
                } catch (e) {
                    send({ ok: false, error: String((e && e.message) || e) });
                }
            })();
        """
    }
}
