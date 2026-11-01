package eu.kanade.tachiyomi.extension.vi.daomeoden

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.tryParseDateTime
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class DaoMeoDen : KeiSource() {
    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(3)

    private val apiUrl
        get() = baseUrl.toHttpUrl().host.let { host ->
            val parts = host.split('.')
            if (parts.size >= 2) "https://api.${parts.takeLast(2).joinToString(".")}" else "https://api.$host"
        }

    private fun searchUrl(page: Int): HttpUrl.Builder = "$apiUrl/api/search/advanced".toHttpUrl().newBuilder()
        .addQueryParameter("page", page.toString())
        .addQueryParameter("limit", "24")

    // ============================== Popular ===============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = searchUrl(page)
            .addQueryParameter("sort", "views")
            .build()
        return parseSearchPage(client.get(url, Fetch.XHR.from(baseUrl)))
    }

    // ============================== Latest ================================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = searchUrl(page)
            .addQueryParameter("sort", "latest")
            .build()
        return parseSearchPage(client.get(url, Fetch.XHR.from(baseUrl)))
    }

    // ============================== Search ================================

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val builder = searchUrl(page)

        if (query.isNotEmpty()) {
            builder.addQueryParameter("q", query)
        }

        filters.firstInstanceOrNull<StatusFilter>()?.toUriPart()
            ?.takeIf { it.isNotEmpty() }
            ?.let { builder.addQueryParameter("status", it) }

        filters.firstInstanceOrNull<AdultFilter>()?.toUriPart()
            ?.takeIf { it.isNotEmpty() }
            ?.let { builder.addQueryParameter("adult", it) }

        filters.firstInstanceOrNull<SortFilter>()?.toUriPart()
            ?.takeIf { it.isNotEmpty() }
            ?.let { builder.addQueryParameter("sort", it) }

        filters.firstInstanceOrNull<GenreFilter>()?.included()
            ?.takeIf { it.isNotEmpty() }
            ?.let { builder.addQueryParameter("genres", it.joinToString(",")) }

        filters.firstInstanceOrNull<TagFilter>()?.included()
            ?.takeIf { it.isNotEmpty() }
            ?.let { builder.addQueryParameter("tags", it.joinToString(",")) }

        return parseSearchPage(client.get(builder.build(), Fetch.XHR.from(baseUrl)))
    }

    private fun parseSearchPage(response: Response): MangasPage {
        val payload = response.parseAs<SearchResponse>()
        val mangas = payload.data.map { it.toSManga() }
        return MangasPage(mangas, payload.pagination.hasNext)
    }

    // ============================== Details ===============================

    private fun parseMangaDetails(document: Document, manga: SManga): SManga = SManga.create().apply {
        setUrlWithoutDomain(manga.url)
        title = document.selectFirst("main h1")!!.text()
        thumbnail_url = document.selectFirst("main img[src*=cover_]")?.absUrl("src")
        genre = document.select("""main a[href*="advanced-search?genre="], main a[href*="advanced-search?tag="]""")
            .mapNotNull { it.ownText().ifEmpty { it.text() }.takeIf { t -> t.isNotEmpty() && t != "Xem thêm" } }
            .distinct()
            .joinToString()
            .ifEmpty { null }
        status = document.select("main span")
            .firstOrNull { it.text().trim().lowercase() in knownStatuses }
            ?.text()
            .let { parseStatus(it) }
        description = document.select("main h3")
            .firstOrNull { it.text().contains("GIỚI THIỆU", ignoreCase = true) }
            ?.nextElementSibling()
            ?.text()
            ?.let { Jsoup.parseBodyFragment(it).text() }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null

        val mangaPath = when (url.pathSegments.firstOrNull()) {
            "truyen-tranh" -> url.encodedPath
            "doc-truyen-tranh" -> {
                val mangaSlug = url.pathSegments.getOrNull(1) ?: return null
                "/truyen-tranh/$mangaSlug"
            }
            else -> return null
        }
        val manga = SManga.create().apply { setUrlWithoutDomain(mangaPath) }

        return fetchMangaUpdate(manga, emptyList(), true, false).manga.apply {
            initialized = true
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga), Fetch.DOCUMENT).asJsoup()
        return SMangaUpdate(
            manga = parseMangaDetails(document, manga),
            chapters = parseChapterList(document),
        )
    }

    // ============================== Chapters ==============================

    private fun parseChapterList(document: Document): List<SChapter> = document.select("main a[data-chapter-number]")
        .mapNotNull { element -> chapterFromElement(element) }
        .distinctBy { it.url }

    private fun chapterFromElement(element: Element): SChapter? {
        val href = element.absUrl("href").takeIf { it.isNotEmpty() } ?: return null
        val number = element.attr("data-chapter-number").toFloatOrNull()
        val name = element.selectFirst("span.truncate")?.text()
            ?: element.text().let { text ->
                chapterLabelRegex.find(text)?.value ?: return null
            }

        return SChapter.create().apply {
            setUrlWithoutDomain(href)
            this.name = name
            date_upload = element.select("span")
                .mapNotNull { chapterDateRegex.find(it.text())?.value }
                .firstOrNull()
                .let { parseChapterDate(it) }
            number?.let { chapter_number = it }
        }
    }

    private fun parseChapterDate(date: String?): Long = chapterDateFormat.tryParseDateTime(date, chapterDateZone)

    // ============================== Pages =================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val response = client.get(getChapterUrl(chapter), Fetch.DOCUMENT)
        val chapterUrl = response.request.url.toString()
        val document = response.asJsoup()

        return document.select("""main img[src*="imggo.net"], main img[data-src*="imggo.net"]""")
            .mapIndexedNotNull { index, element ->
                val imageUrl = element.absUrl("data-src")
                    .ifEmpty { element.absUrl("src") }
                    .takeIf { it.isNotEmpty() }
                    ?: return@mapIndexedNotNull null
                Page(index, url = chapterUrl, imageUrl = imageUrl)
            }
            .distinctBy { it.imageUrl }
    }

    override fun getImageRequest(page: Page): Request {
        val imageHeaders = headers.newBuilder()
            .set("Referer", page.url.ifEmpty { "$baseUrl/" })
            .build()
        return GET(page.imageUrl!!, imageHeaders)
    }

    // ============================== Filters ===============================

    override val supportsFilterFetching get() = true

    override suspend fun fetchFilterData(): JsonElement = client.get("$apiUrl/api/search/filters", Fetch.XHR.from(baseUrl))
        .parseAs<FiltersResponse>()
        .toJsonElement()

    override fun getFilterList(data: JsonElement?): FilterList {
        val parsed = data?.parseAs<FiltersResponse>()?.data
        return getFilters(
            genres = parsed?.categories?.map { FilterOption(it.name, it.slug) },
            tags = parsed?.tags?.map { FilterOption(it.name, it.slug) },
        )
    }

    // ============================== Related ===============================

    override val supportsRelatedMangas get() = true

    override suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> {
        val document = client.get(getMangaUrl(manga), Fetch.DOCUMENT).asJsoup()

        val relatedSection = document.select("main h3")
            .firstOrNull { it.text().equals("Truyện đề xuất cùng loại", ignoreCase = true) }
            ?.closest("section")
            ?: return emptyList()

        return relatedSection.select("""a[href^="/truyen-tranh/"]:has(img)""")
            .mapNotNull { element ->
                val titleEl = element.selectFirst("img") ?: return@mapNotNull null
                SManga.create().apply {
                    setUrlWithoutDomain(element.absUrl("href"))
                    title = titleEl.attr("alt").ifEmpty { element.text() }
                    thumbnail_url = titleEl.absUrl("src")
                }
            }
            .distinctBy { it.url }
            .filterNot { it.url == manga.url }
    }

    private val knownStatuses = setOf("ongoing", "completed", "hiatus", "cancelled", "dropped")
    private val chapterDateRegex = Regex("""\d{2}\.\d{2}\.\d{4}\s*-\s*\d{2}:\d{2}""")
    private val chapterLabelRegex = Regex("""CHAP\s*\S+""", RegexOption.IGNORE_CASE)
    private val chapterDateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy - HH:mm", Locale.ROOT)
    private val chapterDateZone = ZoneId.of("Asia/Ho_Chi_Minh")
}
