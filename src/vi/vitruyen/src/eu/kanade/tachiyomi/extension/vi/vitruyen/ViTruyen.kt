package eu.kanade.tachiyomi.extension.vi.vitruyen

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
import okhttp3.OkHttpClient
import kotlin.time.Instant

@Source
abstract class ViTruyen : KeiSource() {

    private val apiUrl get() = "https://api.${baseUrl.toHttpUrl().host}/api/next"

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(3)

    // ============================== Popular ===============================

    override suspend fun getPopularManga(page: Int): MangasPage = getListing(page, sort = "view")

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): MangasPage = getListing(page, sort = "latest")

    private suspend fun getListing(
        page: Int,
        sort: String,
        status: String? = null,
        genre: String? = null,
        translator: String? = null,
        schedule: String? = null,
    ): MangasPage {
        val url = "$apiUrl/the-loai/dang-hot".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("sort", sort)
            .apply {
                status?.let { addQueryParameter("status", it) }
                genre?.let { addQueryParameter("category", it) }
                translator?.let { addQueryParameter("translator", it) }
                schedule?.let { addQueryParameter("schedule", it) }
            }
            .build()

        val result = client.get(url).parseAs<ListingResponse>()

        return MangasPage(
            mangas = result.items.map { it.toSManga() },
            hasNextPage = result.page < result.totalPages,
        )
    }

    // =============================== Search ===============================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isNotBlank()) {
            val url = "$apiUrl/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("page", page.toString())
                .build()

            val result = client.get(url).parseAs<SearchResponse>()

            return MangasPage(
                mangas = result.items.map { it.toSManga() },
                hasNextPage = result.page < result.totalPages,
            )
        }

        return getListing(
            page = page,
            sort = filters.firstInstanceOrNull<SortFilter>()?.toUriPart() ?: "latest",
            status = filters.firstInstanceOrNull<StatusFilter>()?.toUriPart(),
            genre = filters.firstInstanceOrNull<GenreFilter>()?.toUriPart(),
            translator = filters.firstInstanceOrNull<TranslatorFilter>()?.toUriPart(),
            schedule = filters.firstInstanceOrNull<ScheduleFilter>()?.toUriPart(),
        )
    }

    // =============================== Details ==============================

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/${chapter.url}"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null

        val slug = url.pathSegments.firstOrNull()
            ?.takeIf { it.isNotEmpty() && it !in RESERVED_PATHS }
            ?: return null

        return getMangaDetails(slug).toSManga()
    }

    private suspend fun getMangaDetails(slug: String): MangaDetails = client.get("$apiUrl/manga/$slug").parseAs()

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val details = getMangaDetails(manga.url)

        return SMangaUpdate(
            manga = details.toSManga(),
            chapters = details.chapters.map { chapter ->
                SChapter.create().apply {
                    url = chapter.readUrl.trim('/')
                    name = chapter.name
                    date_upload = chapter.publishedAt?.let { Instant.parseOrNull(it)?.toEpochMilliseconds() } ?: 0L
                }
            },
        )
    }

    // ================================ Pages ===============================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()

        if (document.selectFirst(".v2-reader-lock-panel") != null) {
            throw Exception(LOCKED_CHAPTER_MESSAGE)
        }

        return document.select("img.v2-reader-page-image[src]")
            .mapIndexed { index, image -> Page(index, imageUrl = image.absUrl("src")) }
    }

    // =============================== Filters ==============================

    override val supportsFilterFetching get() = true

    override suspend fun fetchFilterData(): JsonElement {
        val url = "$apiUrl/the-loai/dang-hot".toHttpUrl().newBuilder()
            .addQueryParameter("page", "1")
            .addQueryParameter("sort", "latest")
            .build()

        return client.get(url).parseAs<ListingResponse>().filterOptions.toJsonElement()
    }

    override fun getFilterList(data: JsonElement?): FilterList = getFilters(data?.parseAs<FilterOptions>() ?: FilterOptions())

    // =============================== Related ==============================

    override val supportsRelatedMangas get() = true

    override suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> = getMangaDetails(manga.url)
        .related
        .map { it.toSManga() }

    // ============================== Utilities =============================

    companion object {
        private const val LOCKED_CHAPTER_MESSAGE =
            "Vui lòng đăng nhập vào tài khoản phù hợp bằng Webview để xem chương này"

        private val RESERVED_PATHS = setOf(
            "the-loai",
            "tim-kiem",
            "bang-xep-hang",
            "lich-phat-hanh",
            "nhom-dich",
            "bookmark",
        )
    }
}
