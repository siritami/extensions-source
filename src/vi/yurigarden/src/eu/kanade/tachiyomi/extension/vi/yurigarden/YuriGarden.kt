package eu.kanade.tachiyomi.extension.vi.yurigarden

import eu.kanade.tachiyomi.lib.cryptoaes.CryptoAES
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.TimeUnit

class YuriGarden : HttpSource() {

    override val name = "YuriGarden"

    override val lang = "vi"

    override val baseUrl = "https://yurigarden.com"

    override val supportsLatest = true

    private val apiUrl = baseUrl.replace("://", "://api.") + "/api"

    private val dbUrl = baseUrl.replace("://", "://db.")

    private val json: Json by injectLazy()

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

    // Page API needs Kotatsu UA to get scramble keys compatible with ImageDescrambler.
    // The encrypted API (browser UA) returns keys in a different format.
    private fun pageApiHeaders() = headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("User-Agent", "Kotatsu/9.0 (Android 16;;; en)")
        .add("x-app-origin", baseUrl)
        .add("x-custom-lang", "vi")
        .add("Accept", "application/json")
        .build()

    override fun pageListRequest(chapter: SChapter): Request =
        GET("$apiUrl/chapters/${chapterId(chapter)}", pageApiHeaders())

    override fun pageListParse(response: Response): List<Page> {
        val result = decryptIfNeeded(response)

        return result.pages.mapIndexed { index, page ->
            val rawUrl = page.url.replace("_credit", "")

            if (rawUrl.startsWith("comics")) {
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

    private fun decryptIfNeeded(response: Response): ChapterDetail {
        val body = response.body.string()

        // Check if the response is encrypted
        return if (body.contains("\"encrypted\"" )) {
            val encrypted = json.decodeFromString<EncryptedResponse>(body)
            if (encrypted.encrypted && !encrypted.data.isNullOrEmpty()) {
                val decrypted = CryptoAES.decrypt(encrypted.data, AES_PASSWORD)
                json.decodeFromString<ChapterDetail>(decrypted)
            } else {
                json.decodeFromString<ChapterDetail>(body)
            }
        } else {
            json.decodeFromString<ChapterDetail>(body)
        }
    }

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

    private inline fun <reified T> Response.parseAs(): T =
        json.decodeFromString<T>(body.string())

    companion object {
        private const val LIMIT = 15
        private const val AES_PASSWORD = "V48Ue34jrwRsJSRA"
    }
}
