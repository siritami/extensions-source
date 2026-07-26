package eu.kanade.tachiyomi.extension.vi.truyenmm

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
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Source
abstract class TruyenMM : KeiSource() {

    override fun OkHttpClient.Builder.configureClient() = rateLimit(3)
        .addInterceptor(FirstPageInterceptor())

    // ============================== Popular ===============================

    override suspend fun getPopularManga(page: Int): MangasPage = parseMangaListPage(client.get("$baseUrl/danh-sach-truyen/$page"))

    // ============================== Latest ================================

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseMangaListPage(client.get("$baseUrl/truyen-moi-cap-nhat/$page"))

    // ============================== Search ================================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = if (query.isNotBlank()) {
            "$baseUrl/tim-kiem".toHttpUrl().newBuilder()
                .addQueryParameter("key", query)
                .addQueryParameter("page", page.toString())
                .build()
        } else {
            val genreSlug = filters.firstInstanceOrNull<GenreFilter>()?.toUriPart()
                ?: return getPopularManga(page)
            "$baseUrl/the-loai/$genreSlug/$page".toHttpUrl()
        }

        return parseMangaListPage(client.get(url))
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        if (url.pathSegments.firstOrNull() != "truyen") return null

        val slug = url.pathSegments.getOrNull(1) ?: return null
        val chapterSegment = url.pathSegments.getOrNull(2)
        if (chapterSegment != null && !chapterSegment.startsWith("chapter-")) return null

        val manga = SManga.create().apply {
            setUrlWithoutDomain("/truyen/$slug")
        }
        return fetchMangaUpdate(manga, emptyList(), true, false).manga
    }

    private fun parseMangaListPage(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangaList = document.select("article:has(a[href^=/truyen/])")
            .map(::mangaFromElement)

        return MangasPage(mangaList, hasNextPage(document, response.request.url))
    }

    private fun mangaFromElement(element: Element): SManga = SManga.create().apply {
        val titleElement = element.selectFirst("h2, h3")!!
        val mangaLink = titleElement.closest("a[href^=/truyen/]")
            ?: element.selectFirst("a[href^=/truyen/]")!!
        title = titleElement.text()
        setUrlWithoutDomain(mangaLink.absUrl("href"))
        thumbnail_url = element.selectFirst("img")?.extractImageUrl()
    }

    private fun hasNextPage(document: Document, requestUrl: HttpUrl): Boolean {
        if (document.selectFirst("link[rel=next]") != null) return true

        val currentPage = requestUrl.queryParameter("page")?.toIntOrNull()
            ?: requestUrl.pathSegments.lastOrNull()?.toIntOrNull()
            ?: 1
        val nextPage = currentPage + 1

        return document.select("a[href]").any { anchor ->
            val pageUrl = anchor.absUrl("href").toHttpUrlOrNull() ?: return@any false
            val pageValue = pageUrl.queryParameter("page")?.toIntOrNull()
                ?: pageUrl.pathSegments.lastOrNull()?.toIntOrNull()
            pageValue == nextPage
        }
    }

    // ============================== Details ===============================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        return SMangaUpdate(
            manga = parseMangaDetails(document, manga),
            chapters = if (fetchChapters) parseChapterList(document) else chapters,
        )
    }

    private fun parseMangaDetails(document: Document, manga: SManga): SManga = SManga.create().apply {
        setUrlWithoutDomain(manga.url)
        title = document.selectFirst("h1")!!.text()
        thumbnail_url = document.selectFirst("img[alt*=Bìa], img[alt*=bìa]")?.extractImageUrl()
        author = findInfoValue(document, "Tác giả")
        status = parseStatus(findInfoValue(document, "Loại Truyện"))
        genre = document.select("dd a[href*='/the-loai/']")
            .map(Element::text)
            .distinct()
            .joinToString()
            .ifEmpty { null }
    }

    private fun findInfoValue(document: Document, label: String): String? = document.select("dl > div").firstOrNull {
        it.selectFirst("dt")?.text()?.startsWith(label, ignoreCase = true) == true
    }?.selectFirst("dd")?.text()?.ifEmpty { null }

    private fun parseStatus(statusText: String?): Int {
        val normalizedStatus = statusText?.lowercase(Locale.ROOT)
        return when {
            normalizedStatus == null -> SManga.UNKNOWN
            "hoàn thành" in normalizedStatus -> SManga.COMPLETED
            "đang tiến hành" in normalizedStatus || "đang cập nhật" in normalizedStatus -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }
    }

    private suspend fun parseChapterList(document: Document): List<SChapter> {
        val topicId = document.selectFirst("script#script-chapter")?.attr("data-id")
        val topic = topicId?.let { fetchTopic(it) }
        if (topic != null) {
            return topic.chapters.orEmpty().map { chapter ->
                SChapter.create().apply {
                    setUrlWithoutDomain(buildChapterUrl(chapter.id))
                    name = chapter.name
                    date_upload = chapter.updateTime ?: 0L
                }
            }
        }

        return document.select("#chapter-list a[href*='/chapter-']").map { chapterElement ->
            SChapter.create().apply {
                setUrlWithoutDomain(chapterElement.absUrl("href"))
                name = chapterElement.selectFirst("span")?.text() ?: chapterElement.text()
                date_upload = parseChapterDate(chapterElement.selectFirst("time")?.text())
            }
        }
    }

    private suspend fun fetchTopic(topicId: String): TruyenMMTopic? {
        val url = "$baseUrl/api/get-topic".toHttpUrl().newBuilder()
            .addQueryParameter("id", topicId)
            .build()

        return runCatching {
            client.get(url).parseAs<TruyenMMGetTopicResponse>().topic
        }.getOrNull()
    }

    private fun buildChapterUrl(rawChapterId: String): String {
        val normalizedChapterId = rawChapterId.replace("-chapter-", "/chapter-")
        val splitIndex = normalizedChapterId.indexOf("/chapter-")
        if (splitIndex == -1) return "$baseUrl/truyen/$normalizedChapterId"

        val mangaId = normalizedChapterId.substring(0, splitIndex)
        val chapterId = normalizedChapterId.substring(splitIndex + 1)
        return "$baseUrl/truyen/$mangaId/$chapterId"
    }

    private fun parseChapterDate(dateText: String?): Long {
        if (dateText == null) return 0L
        val normalizedDate = dateText.replace("🗓", "").trim()
        val relativeDate = parseRelativeDate(normalizedDate.lowercase(Locale.ROOT))
        if (relativeDate != 0L) return relativeDate

        return runCatching {
            LocalDate.parse(normalizedDate, chapterDateFormat)
                .atStartOfDay(vietnamZone)
                .toInstant()
                .toEpochMilli()
        }.getOrDefault(0L)
    }

    private fun parseRelativeDate(dateText: String): Long {
        if ("vừa xong" in dateText) return Clock.System.now().toEpochMilliseconds()

        val amount = relativeDateNumberRegex.find(dateText)?.value?.toIntOrNull() ?: return 0L
        val duration = when {
            "giây" in dateText -> amount.seconds
            "phút" in dateText -> amount.minutes
            "giờ" in dateText -> amount.hours
            "ngày" in dateText -> amount.days
            "tuần" in dateText -> (amount * 7).days
            "tháng" in dateText -> (amount * 30).days
            "năm" in dateText -> (amount * 365).days
            else -> return 0L
        }

        return (Clock.System.now() - duration).toEpochMilliseconds()
    }

    // ============================== Pages =================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()
        val imageUrls = document.select("img[src^=/imgs/], img[data-src^=/imgs/]").mapNotNull { imageElement ->
            imageElement.extractImageUrl()
                ?.takeIf { it.isNotEmpty() }
        }.distinct()

        return imageUrls.mapIndexed { index, imageUrl ->
            val readerUrl = if (imageUrl.substringBefore('#').endsWith("/0.jpg")) {
                "$imageUrl#$FIRST_PAGE_FRAGMENT"
            } else {
                imageUrl
            }
            Page(index, imageUrl = readerUrl)
        }
    }

    // ============================== Filters ===============================

    override val supportsFilterFetching get() = true

    override suspend fun fetchFilterData(): JsonElement = client.get(baseUrl).asJsoup()
        .select("#h-genre a[href^=/the-loai/]")
        .mapNotNull { link ->
            val name = link.text().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val slug = link.absUrl("href").toHttpUrlOrNull()?.pathSegments?.getOrNull(1)
                ?: return@mapNotNull null
            GenreOption(name, slug)
        }
        .distinctBy { it.slug }
        .toJsonElement()

    override fun getFilterList(data: JsonElement?): FilterList = getFilters(data?.parseAs<List<GenreOption>>())

    // =============================== Related ==============================

    override val supportsRelatedMangas get() = true

    override suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        val relatedSection = document.select("section:has(> h2)")
            .firstOrNull { it.selectFirst("> h2")?.text() == "Có thể bạn sẽ thích" }
            ?: return emptyList()

        return relatedSection.select("li:has(a[href^=/truyen/])")
            .map(::mangaFromElement)
            .filterNot { it.url == manga.url }
            .distinctBy { it.url }
    }

    private fun Element.extractImageUrl(): String? = absUrl("data-src")
        .ifEmpty { absUrl("src") }
        .ifEmpty { null }

    private val relativeDateNumberRegex = Regex("""\d+""")
    private val chapterDateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT)
    private val vietnamZone = ZoneId.of("Asia/Ho_Chi_Minh")
}
