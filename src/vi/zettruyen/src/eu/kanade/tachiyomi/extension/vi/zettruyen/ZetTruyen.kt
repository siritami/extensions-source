package eu.kanade.tachiyomi.extension.vi.zettruyen

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class ZetTruyen : KeiSource() {
    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        rateLimit(3)
    }

    private val apiHeaders: Headers
        get() = headersBuilder()
            .set("Accept", "application/json")
            .build()

    private val imageHeaders: Headers
        get() = headersBuilder()
            .set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .build()

    // ============================== Popular ===============================
    override suspend fun getPopularManga(page: Int): MangasPage = getSearchMangaList(
        page,
        "",
        FilterList(SortFilter().apply { state = 1 }),
    )

    private fun parseMangaPage(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("div.grid a[href*=/truyen-tranh/]").map(::mangaFromElement)
        return MangasPage(mangas, mangas.isNotEmpty())
    }

    private fun mangaFromElement(element: Element) = SManga.create().apply {
        setUrlWithoutDomain(element.absUrl("href"))
        title = element.selectFirst("span.line-clamp-2")!!.text()
        thumbnail_url = element.selectFirst("img")?.absUrl("src")
    }

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): MangasPage = getSearchMangaList(
        page,
        "",
        FilterList(SortFilter().apply { state = 0 }),
    )

    // ============================== Search ================================
    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val url = "$baseUrl/tim-kiem-nang-cao".toHttpUrl().newBuilder().apply {
            if (query.isNotEmpty()) addQueryParameter("name", query)
            addQueryParameter("page", page.toString())

            filters.forEach { filter ->
                when (filter) {
                    is SortFilter -> setQueryParameter("sort", filter.toUriPart())
                    is StatusFilter -> setQueryParameter("status", filter.toUriPart())
                    is TypeFilter -> setQueryParameter("type", filter.toUriPart())
                    is ChapterFilter -> setQueryParameter("chapterRange", filter.toUriPart())
                    is GenreFilter -> {
                        val genres = filter.state
                            .filter { it.state }
                            .joinToString(",") { it.id }
                        setQueryParameter("genres", genres)
                    }
                    else -> {}
                }
            }
        }.build()

        return parseMangaPage(client.get(url))
    }

    // ============================== Details ===============================

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.firstOrNull() != "truyen-tranh") return null

        val slug = url.pathSegments.getOrNull(1) ?: return null
        val manga = SManga.create().apply {
            setUrlWithoutDomain("/truyen-tranh/$slug")
        }
        return fetchMangaUpdate(manga, emptyList(), true, false).manga
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val detailsDeferred = if (fetchDetails) async { getMangaDetails(manga) } else null
        val chaptersDeferred = if (fetchChapters) async { getChapterList(manga) } else null

        SMangaUpdate(
            manga = detailsDeferred?.await() ?: manga,
            chapters = chaptersDeferred?.await() ?: chapters,
        )
    }

    private suspend fun getMangaDetails(manga: SManga): SManga {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        return SManga.create().apply {
            setUrlWithoutDomain(manga.url)
            title = document.selectFirst("h1")!!.text()
            thumbnail_url = document.selectFirst("img[src*=/thumb/]")?.absUrl("src")
            author = document.getInfoValue("Tác giả")
            status = parseStatus(document.getInfoValue("Trạng thái"))
            genre = document.getGenres()
            description = document.selectFirst("p.comic-content")?.wholeText()?.trim()
        }
    }

    private fun Document.getInfoValue(label: String): String? {
        val element = select("div, span, p").firstOrNull { it.ownText() == label }
            ?: return null
        return element.nextElementSibling()?.text()
    }

    private fun Document.getGenres(): String? {
        val genreLabel = select("div, span").firstOrNull {
            it.ownText() == "Thể loại" && it.closest("header") == null
        } ?: return null
        return genreLabel.nextElementSibling()
            ?.select("a")
            ?.joinToString { it.text() }
    }

    private fun parseStatus(status: String?): Int = when {
        status == null -> SManga.UNKNOWN
        "đang tiến hành" in status.lowercase() -> SManga.ONGOING
        "hoàn thành" in status.lowercase() -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    // ============================== Chapters ==============================
    private suspend fun getChapterList(manga: SManga): List<SChapter> = coroutineScope {
        val firstPage = client.get(chapterListUrl(manga, 1), apiHeaders).parseAs<ChapterListResponse>()
        val lastPage = firstPage.data?.lastPage ?: 1
        val remainingPages = (2..lastPage)
            .map { page ->
                async {
                    client.get(chapterListUrl(manga, page), apiHeaders).parseAs<ChapterListResponse>()
                }
            }
            .awaitAll()

        listOf(firstPage, *remainingPages.toTypedArray())
            .flatMap { parseChapterList(manga, it) }
    }

    private fun chapterListUrl(manga: SManga, page: Int): HttpUrl {
        val slug = getMangaUrl(manga).toHttpUrl().pathSegments.last()
        return "$baseUrl/api/comics/$slug/chapters".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", "100")
            .addQueryParameter("order", "desc")
            .build()
    }

    private fun parseChapterList(manga: SManga, result: ChapterListResponse): List<SChapter> {
        val data = result.data ?: return emptyList()
        val slug = getMangaUrl(manga).toHttpUrl().pathSegments.last()

        return data.chapters.map { chapter ->
            val chapterSlug = chapter.chapterSlug.replace("chapter-", "chuong-")
            SChapter.create().apply {
                url = "/truyen-tranh/$slug/$chapterSlug"
                name = chapter.chapterName
                date_upload = parseChapterDate(chapter.updatedAt)
            }
        }
    }

    private fun parseChapterDate(date: String?): Long {
        if (date == null) return 0L
        return runCatching {
            LocalDateTime.parse(date.substringBefore("."), apiDateFormat)
                .atZone(apiDateZone)
                .toInstant()
                .toEpochMilli()
        }.getOrDefault(0L)
    }

    // ============================== Pages =================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()
        return document.select("div.center img").mapIndexed { index, element ->
            Page(index, imageUrl = element.absUrl("src"))
        }.ifEmpty {
            document.select("div.w-full.mx-auto.center img").mapIndexed { index, element ->
                Page(index, imageUrl = element.absUrl("src"))
            }
        }
    }

    override fun imageRequest(page: Page): Request {
        return GET(page.imageUrl!!, imageHeaders)
    }

    // ============================== Filters ===============================

    override val supportsFilterFetching get() = true

    override suspend fun fetchFilterData(): JsonElement = client.get("$baseUrl/tim-kiem-nang-cao").asJsoup()
        .select("input[name='genres[]']")
        .mapNotNull { input ->
            val id = input.attr("value").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val name = input.parent()?.text()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            GenreOption(name, id)
        }
        .distinctBy { it.id }
        .toJsonElement()

    override fun getFilterList(data: JsonElement?): FilterList = getFilters(data?.parseAs<List<GenreOption>>())

    private val apiDateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)
    private val apiDateZone = ZoneId.of("Asia/Ho_Chi_Minh")
}
