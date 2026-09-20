package eu.kanade.tachiyomi.extension.vi.moetruyen

import android.util.Log
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
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.tryParseDate
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

    // Add only the headers the site checks beyond KeiSource defaults.
    override fun Headers.Builder.configureHeaders(): Headers.Builder = apply {
        set("Sec-Fetch-Dest", "document")
        set("Sec-Fetch-Mode", "navigate")
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
        if (titleAttr.isNotEmpty()) return titleAttr

        val titleText = titleElement.text()
        if (!titleText.endsWith("...")) return titleText

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

            val chapterNum = element.selectFirst(".chapter-num")?.text()?.trim().orEmpty()
            val chapterTitle = element.selectFirst(".chapter-title")?.text()?.trim().orEmpty()
            // Site uses .chapter-lock-icon (comment icon) when a chapter needs a prior-chapter comment.
            val isLocked = element.selectFirst(".chapter-lock-icon") != null ||
                element.selectFirst("[title*='bình luận']") != null
            name = buildString {
                if (isLocked) append("🔒 ")
                append(chapterNum)
                if (chapterTitle.isNotBlank() && !chapterNum.contains(chapterTitle)) {
                    if (chapterNum.isNotBlank()) append(" - ")
                    append(chapterTitle)
                }
            }

            val chapterTime = element.selectFirst(".chapter-time")
            val relativeDate = chapterTime?.text()
            val absoluteDate = chapterTime?.attr("title")
                ?.substringAfter("Cập nhật", missingDelimiterValue = "")
                ?.trim()
                ?.ifEmpty { null }

            date_upload = parseRelativeDate(relativeDate).takeIf { it != 0L }
                ?: dateFormat.tryParseDate(absoluteDate, dateZone)
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

    // ============================== Pages =================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = "$baseUrl${chapter.url}"
        val document = client.get(chapterUrl).asJsoup()
        val readerPages = document.selectFirst("[data-reader-lazy-pages]")
        val encryptedMedia = ImgxAccessClient.encryptedMedia(document)

        // IMGX active but media JSON empty → site loads pages via access API only
        val totalPages = readerPages?.attr("data-reader-total-pages")?.toIntOrNull() ?: 0
        val accessUrl = readerPages?.attr("data-reader-imgx-access-url").orEmpty()
        val isImgx = accessUrl.isNotBlank()
        val plainUrls = plainPageUrls(document)
        Log.e("MoeTruyen", "pages: url=$chapterUrl encrypted=${encryptedMedia.size} isImgx=$isImgx totalPages=$totalPages plain=${plainUrls.size}")

        // Some chapters SSR real WebP URLs even when IMGX metadata is present.
        // Access API then returns 400 "No pages requested" — prefer usable plain URLs.
        val hasRealPlain = plainUrls.any { isRealPageUrl(it) }
        val shouldTryImgx = isImgx && (
            encryptedMedia.any { isRealPageUrl(it.storageKey) || isRealPageUrl(it.downloadUrl) } ||
                (!hasRealPlain && totalPages > 0)
            )

        if (shouldTryImgx) {
            try {
                val access = ImgxAccessClient(client, baseUrl, chapterUrl, document)
                val pages = access.fetchPages(encryptedMedia, totalPages)
                    .filter { isRealPageUrl(it.storageKey) && isRealPageUrl(it.downloadUrl) }
                Log.e("MoeTruyen", "pages: grants returned=${pages.size}")
                if (pages.isNotEmpty()) {
                    pages.forEach { page ->
                        val grant = page.grant
                            ?: throw IllegalStateException("IMGX grant missing page=${page.pageIndex + 1}")
                        imgxGrants[page.downloadUrl] = grant to page.storageKey
                    }
                    return pages
                        .sortedBy { it.pageIndex }
                        .mapIndexed { index, page -> Page(index, imageUrl = page.downloadUrl) }
                }
            } catch (e: Exception) {
                Log.e("MoeTruyen", "pages: imgx access failed url=$chapterUrl err=${e.message}")
                if (!hasRealPlain) throw e
            }
        }

        val result = plainUrls
            .filter { isRealPageUrl(it) }
            .distinct()
            .mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }
        Log.e("MoeTruyen", "pages: plain result=${result.size}")
        if (result.isEmpty()) {
            lockedChapterReason(document)?.let { throw IllegalStateException(it) }
        }
        return result
    }

    private fun isRealPageUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        if (url.startsWith("data:")) return false
        val path = url.substringBefore('?')
        return !path.endsWith("/0.js") && !path.endsWith("/0.js/")
    }

    private fun plainPageUrls(document: Document): List<String> {
        return readerImages(document)
            .map { element -> element.absUrl("data-src").ifEmpty { element.absUrl("src") } }
            .filter { it.isNotBlank() && !it.startsWith("data:") }
    }

    private fun lockedChapterReason(document: Document): String? {
        val note = document.selectFirst(".reader-note")?.text()?.trim().orEmpty()
        val bridge = document.selectFirst(".reader-chapter-bridge__title")?.text()?.trim().orEmpty()
        val combined = listOf(note, bridge).filter { it.isNotBlank() }.joinToString(" — ")
        if (combined.isBlank()) return null
        if (listOf("bình luận", "tăng tương tác", "đăng nhập", "mở đọc", "mở chương", "trả phí", "VIP").none { combined.contains(it, ignoreCase = true) }) {
            return null
        }
        return "Chapter locked on site: $combined"
    }

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
            Log.e("MoeTruyen", "imgx decode fail url=${request.url} err=${e.message}")
            throw e
        }
        if (webp.size < 12 || webp[0] != 0x52.toByte() || webp[1] != 0x49.toByte()) {
            Log.e("MoeTruyen", "imgx decode non-webp len=${webp.size} head=${webp.take(12).joinToString(" ") { "%02x".format(it) }}")
        } else {
            Log.e("MoeTruyen", "imgx decode ok len=${webp.size}")
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
        Log.e("MoeTruyen", "pages: img.page-media total=${all.size} outsideNoscript=${outsideNoscript.size}")
        if (outsideNoscript.isNotEmpty()) {
            val first = outsideNoscript.first()
            Log.e("MoeTruyen", "pages: first img attrs=${first.attributes().joinToString(" ") { "${it.key}=${it.value.take(80)}" }}")
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
}
