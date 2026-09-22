package eu.kanade.tachiyomi.extension.vi.moetruyen

import android.util.Base64
import android.webkit.WebResourceResponse
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.runWebView
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.toJsonString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
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
import java.net.URLDecoder
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Source
abstract class MoeTruyen : KeiSource() {
    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        addInterceptor(webViewImageInterceptor())
        rateLimit(3)
    }

    // ============================== Popular ===============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$baseUrl/manga".toHttpUrl().newBuilder()
            .addQueryParameter("sort", "views_desc")
            .addQueryParameter("page", page.toString())
            .build()

        return parseMangaList(client.get(url).asJsoup())
    }

    // ============================== Latest ================================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$baseUrl/manga".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .build()

        return parseMangaList(client.get(url).asJsoup())
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

        return parseMangaList(client.get(url).asJsoup())
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
        val document = client.get("$baseUrl${manga.url}").asJsoup()
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
            currentDocument = client.get(currentPageUrl).asJsoup()
        }

        return chapters
    }

    private fun parseChapterList(document: Document): List<SChapter> = document.select("ul.chapter-list li.chapter a.chapter-link").map { element ->
        SChapter.create().apply {
            setUrlWithoutDomain(element.absUrl("href"))
            name = element.selectFirst(".chapter-num")!!.text()

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

    private fun parseAbsoluteDate(date: String?): Long {
        if (date == null) return 0L
        return runCatching {
            LocalDate.parse(date, dateFormat)
                .atStartOfDay(dateZone)
                .toInstant()
                .toEpochMilli()
        }.getOrDefault(0L)
    }

    // ============================== Pages =================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = "$baseUrl${chapter.url}"
        val document = client.get(chapterUrl).asJsoup()
        val readerPages = document.selectFirst("[data-reader-lazy-pages]")
        val media = parseReaderMedia(document, readerPages)
        val protectedPages = media
            .filter(::isRealProtectedPage)
            .sortedBy { it.pageIndex }
        val initialIndexes = parseInitialIndexes(document, readerPages)

        android.util.Log.e("MoeTruyenDbg", "url=$chapterUrl media=${media.size} protected=${protectedPages.size}")
        protectedPages.forEach { p ->
            android.util.Log.e("MoeTruyenDbg", "entry idx=${p.pageIndex} primary=${p.primaryUrl} download=${p.downloadUrl}")
        }

        if (protectedPages.isNotEmpty()) {
            try {
                val pages = fetchV4Pages(chapterUrl, protectedPages, initialIndexes)
                android.util.Log.e("MoeTruyenDbg", "webview ok count=${pages.size}")
                return pages
            } catch (e: Exception) {
                android.util.Log.e("MoeTruyenDbg", "webview fail: ${e.message}")
            }
        }

        // primaryUrl PNGs are scrambled by IMGX — do not use them for protected pages.
        if (protectedPages.isNotEmpty()) {
            throw IllegalStateException(
                "IMGX decrypt failed; refusing scrambled primaryUrl noise for ${protectedPages.size} pages",
            )
        }

        return readerImages(document)
            .mapNotNull(::resolvePlainImageUrl)
            .distinct()
            .mapIndexed { index, imageUrl ->
                Page(index, imageUrl = imageUrl)
            }
    }

    private fun resolvePlainImageUrl(element: Element): String? = sequenceOf(
        element.absUrl("data-src"),
        element.absUrl("src"),
        element.absUrl("data-lazy-original-src"),
    ).firstOrNull { url ->
        (url.startsWith("http://") || url.startsWith("https://")) &&
            !url.startsWith("data:") &&
            !isImgxPayloadUrl(url)
    }

    private fun isImgxPayloadUrl(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#')
        return path.endsWith("/0.js") ||
            path.endsWith(".js") ||
            path.contains("/i.truyen.moe/") ||
            path.contains("i.truyen.moe")
    }

    private fun parseReaderMedia(document: Document, readerPages: Element?): List<ReaderMediaEntry> {
        val attributeJson = readerPages?.attr("data-reader-imgx-media")
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { URLDecoder.decode(it, Charsets.UTF_8.name()) }.getOrNull() }

        val scriptJson = document.select("script:not([src])")
            .map { it.data() }
            .firstOrNull { it.contains(mediaScriptMarker) && it.contains("\"storageKey\"") }
            ?.let { extractJsonArrayAfter(it, mediaScriptMarker) }

        val json = attributeJson ?: scriptJson ?: return emptyList()
        return runCatching { json.parseAs<List<ReaderMediaEntry>>() }.getOrDefault(emptyList())
    }

    private fun parseInitialIndexes(document: Document, readerPages: Element?): List<Int> {
        val attributeJson = readerPages?.attr("data-reader-imgx-initial-pages")
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { URLDecoder.decode(it, Charsets.UTF_8.name()) }.getOrNull() }

        val scriptJson = document.select("script:not([src])")
            .map { it.data() }
            .firstOrNull { it.contains(initialIndexesScriptMarker) }
            ?.let { extractJsonArrayAfter(it, initialIndexesScriptMarker) }

        val json = attributeJson ?: scriptJson ?: return emptyList()
        runCatching { json.parseAs<List<Int>>() }.getOrNull()?.let { return it }
        return runCatching { json.parseAs<List<ReaderInitialPage>>() }
            .getOrDefault(emptyList())
            .mapNotNull { it.pageIndex }
    }

    private fun extractJsonArrayAfter(script: String, marker: String): String? {
        val markerIndex = script.indexOf(marker)
        if (markerIndex < 0) return null
        val arrayStart = script.indexOf('[', markerIndex + marker.length)
        if (arrayStart < 0) return null

        var depth = 0
        var inString = false
        var escaped = false
        for (index in arrayStart until script.length) {
            val char = script[index]
            if (escaped) {
                escaped = false
                continue
            }
            when {
                char == '\\' && inString -> escaped = true
                char == '"' -> inString = !inString
                !inString && char == '[' -> depth++
                !inString && char == ']' -> {
                    depth--
                    if (depth == 0) return script.substring(arrayStart, index + 1)
                }
            }
        }
        return null
    }

    private fun isRealProtectedPage(entry: ReaderMediaEntry): Boolean {
        val storageKey = entry.storageKey
        val downloadUrl = entry.downloadUrl
        return entry.pageIndex >= 0 &&
            storageKey.startsWith("chapters/") &&
            !storageKey.endsWith("/0.js") &&
            !downloadUrl.endsWith("/0.js")
    }

    private suspend fun fetchV4Pages(
        chapterUrl: String,
        protectedPages: List<ReaderMediaEntry>,
        initialIndexes: List<Int>,
    ): List<Page> {
        if (protectedPages.isEmpty()) {
            throw IllegalStateException("IMGX protected pages missing")
        }

        val readerScript = client.get("$baseUrl/reader.js").body.string()
        val decoderPath = v4DecoderRegex.find(readerScript)?.groupValues?.get(1)
            ?: throw IllegalStateException(
                "IMGX decoder missing and primaryUrl unavailable — chapter uses the protected IMGX worker",
            )
        val decoderUrl = "$baseUrl/chunks/$decoderPath"
        val readerScriptForWebView = readerScript.replace(runtimeClaimRegex, "null")
        val script = javaClass.getResource("/assets/imgx-v4-reader.js")?.readText()
            ?: throw IllegalStateException("imgx-v4-reader.js not found")
        val pool = ('a'..'z') + ('A'..'Z')
        val bridgeName = (1..(10..20).random())
            .map { pool.random() }
            .joinToString("")
        val mediaJson = protectedPages.toJsonString()
        val initialIndexesJson = initialIndexes.toJsonString()
        val webViewScript = script
            .replace("__IMGX_DECODER_URL__", decoderUrl)
            .replace("__IMGX_BRIDGE__", bridgeName)
            .replace("__IMGX_MEDIA_JSON__", mediaJson)
            .replace("__IMGX_INITIAL_INDEXES_JSON__", initialIndexesJson)
        val pages = arrayOfNulls<ByteArray>(protectedPages.size)
        val downloadUrls = arrayOfNulls<String>(protectedPages.size)

        runWebView<Unit>(timeout = 90.seconds) {
            interceptRequest { request ->
                if (request.url.toString().substringBefore('?') == "$baseUrl/reader.js") {
                    WebResourceResponse("application/javascript", "UTF-8", readerScriptForWebView.byteInputStream())
                } else {
                    null
                }
            }
            jsBridge(bridgeName) { message ->
                val payload = message.parseAs<JsonObject>()
                when (payload["type"]?.jsonPrimitive?.content) {
                    "page" -> {
                        val index = payload["index"]!!.jsonPrimitive.int
                        val data = payload["data"]!!.jsonPrimitive.content
                        val downloadUrl = payload["downloadUrl"]?.jsonPrimitive?.content
                        if (index in pages.indices) {
                            pages[index] = Base64.decode(data, Base64.DEFAULT)
                            downloadUrls[index] = downloadUrl
                        }
                    }
                    "log" -> android.util.Log.e("MoeTruyenDbg", "js ${payload["message"]?.jsonPrimitive?.content}")
                    "done" -> resolve(Unit)
                    "error" -> {
                        val message = payload["message"]?.jsonPrimitive?.content ?: "IMGX reader failed"
                        android.util.Log.e("MoeTruyenDbg", "js error $message")
                        reject(Exception(message))
                    }
                }
            }
            onPageStarted { url ->
                if (url.startsWith(chapterUrl)) {
                    evaluateJs(webViewScript)
                }
            }
            loadUrl(chapterUrl)
        }

        return pages.mapIndexed { index, data ->
            val bytes = data ?: throw IllegalStateException("IMGX page ${index + 1} missing")
            if (!isDecodedImage(bytes)) {
                throw IllegalStateException("IMGX page ${index + 1} is not a decoded image")
            }
            val imageUrl = decodedImageUrl(index)
            webViewImages[imageUrl] = bytes
            Page(index, imageUrl = imageUrl)
        }
    }

    private fun decodedImageUrl(index: Int): String = "https://moetruyen.local/decoded/$index"

    private fun isDecodedImage(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        if (bytes[0] == 'I'.code.toByte() && bytes[1] == 'M'.code.toByte() &&
            bytes[2] == 'G'.code.toByte() && bytes[3] == 'X'.code.toByte()
        ) {
            return false
        }
        val isPng = bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        val isJpeg = bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
        val isGif = bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte()
        val isWebp = bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
        return isPng || isJpeg || isGif || isWebp
    }

    private fun webViewImageInterceptor() = Interceptor { chain ->
        val request = chain.request()
        val requestUrl = request.url.toString()
        val isDecodedHost = request.url.host == "moetruyen.local"
        val data = webViewImages[requestUrl]
        if (data == null) {
            if (isDecodedHost) {
                throw IllegalStateException("Decoded page missing from cache: $requestUrl")
            }
            return@Interceptor chain.proceed(request)
        }
        if (!isDecodedImage(data)) {
            throw IllegalStateException("Refusing to serve IMGX payload as image: $requestUrl")
        }

        val mediaType = detectImageMediaType(data)
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Content-Type", mediaType)
            .header("Content-Length", data.size.toString())
            .header("Cache-Control", "no-store")
            .body(data.toResponseBody(mediaType.toMediaType()))
            .build()
    }

    private fun detectImageMediaType(bytes: ByteArray): String = when {
        bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> "image/png"
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte() -> "image/jpeg"
        bytes.size >= 3 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() -> "image/gif"
        else -> "image/webp"
    }

    private fun readerImages(document: Document): List<Element> = document.select("img.page-media")
        .filterNot { element ->
            element.parents().any { parent -> parent.tagName().equals("noscript", ignoreCase = true) }
        }

    private val webViewImages = Collections.synchronizedMap(
        object : LinkedHashMap<String, ByteArray>(100, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean = size > 100
        },
    )

    // ============================== Filters ===============================

    override val supportsFilterFetching get() = true

    override suspend fun fetchFilterData(): JsonElement = client.get("$baseUrl/manga").asJsoup()
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
        val document = client.get("$baseUrl${manga.url}").asJsoup()
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
    private val mediaScriptMarker = "media: "
    private val initialIndexesScriptMarker = "initialIndexes: "
    private val v4DecoderRegex = Regex("""\.\./chunks/(v4-[A-Za-z0-9_-]+\.js)""")
    private val runtimeClaimRegex = Regex("""window\.__IMGX_RUNTIME__\?\.take\(\)\|\|null""")

    @Serializable
    private class ReaderMediaEntry(
        val pageIndex: Int,
        val storageKey: String,
        val downloadUrl: String,
        val primaryUrl: String? = null,
    )

    @Serializable
    private class ReaderInitialPage(
        val pageIndex: Int? = null,
    )
}
