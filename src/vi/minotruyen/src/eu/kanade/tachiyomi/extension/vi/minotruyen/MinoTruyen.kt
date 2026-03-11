package eu.kanade.tachiyomi.extension.vi.minotruyen

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
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class MinoTruyen(
    override val name: String,
    private val category: String,
) : HttpSource() {

    override val baseUrl = "https://minotruyenv5.xyz"

    private val apiUrl = "https://api.cloudkk.art/api"

    override val lang = "vi"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    override val client = network.cloudflareClient.newBuilder()
        .rateLimitHost(apiUrl.toHttpUrl(), 3)
        .build()

    // ============================== Popular ===============================

    override fun popularMangaRequest(page: Int): Request {
        val url = "$apiUrl/books/side-home".toHttpUrl().newBuilder()
            .addQueryParameter("category", category)
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val result = response.parseAs<SideHomeResponse>()
        val mangaList = result.topBooksView.map { book ->
            SManga.create().apply {
                url = "/books/${book.bookId}"
                title = book.title.trim()
                thumbnail_url = book.covers.firstOrNull()?.url
                status = parseStatus(book.status)
            }
        }
        return MangasPage(mangaList, false)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiUrl/books".toHttpUrl().newBuilder()
            .addQueryParameter("take", "24")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("category", category)
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val result = response.parseAs<BooksResponse>()
        val mangaList = result.books.map { it.toSManga() }
        val hasNextPage = result.countBook?.let { mangaList.size < it } ?: false
        return MangasPage(mangaList, hasNextPage)
    }

    // =============================== Search ===============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$apiUrl/books".toHttpUrl().newBuilder()
            .addQueryParameter("take", "24")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("category", category)

        if (query.isNotBlank()) {
            url.addQueryParameter("q", query)
        }

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = latestUpdatesParse(response)

    // =============================== Details ==============================

    override fun mangaDetailsRequest(manga: SManga): Request {
        val bookId = manga.url.substringAfterLast("/")
        return GET("$apiUrl/books/$bookId", headers)
    }

    override fun getMangaUrl(manga: SManga): String {
        return "$baseUrl/$category${manga.url}"
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val result = response.parseAs<BookDetailResponse>()
        val book = result.book
        return SManga.create().apply {
            url = "/books/${book.bookId}"
            title = book.title.trim()
            thumbnail_url = book.covers.firstOrNull()?.url
            author = book.author
            description = book.description
            genre = book.tags.joinToString { it.tag.name }
            status = parseStatus(book.status)
        }
    }

    // ============================== Chapters ==============================

    override fun chapterListRequest(manga: SManga): Request {
        val bookId = manga.url.substringAfterLast("/")
        val url = "$apiUrl/chapters/$bookId".toHttpUrl().newBuilder()
            .addQueryParameter("order", "desc")
            .addQueryParameter("take", "5000")
            .build()
        return GET(url, headers)
    }

    override fun getChapterUrl(chapter: SChapter): String {
        return "$baseUrl/$category${chapter.url}"
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val result = response.parseAs<ChaptersResponse>()
        return result.chapters.map { chapter ->
            SChapter.create().apply {
                val bookId = chapter.bookId
                url = "/books/$bookId/chapters/${chapter.chapterNumber}"
                name = chapter.num
                date_upload = parseDate(chapter.createdAt)
            }
        }
    }

    // =============================== Pages ================================

    override fun pageListParse(response: Response): List<Page> {
        throw UnsupportedOperationException()
    }

    override fun imageUrlParse(response: Response): String {
        throw UnsupportedOperationException()
    }

    // ============================= Utilities ==============================

    private fun Book.toSManga() = SManga.create().apply {
        url = "/books/$bookId"
        title = this@toSManga.title.trim()
        thumbnail_url = covers.firstOrNull()?.url
        status = parseStatus(this@toSManga.status)
    }

    private fun parseStatus(status: Int?): Int = when (status) {
        1 -> SManga.ONGOING
        2 -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    private fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        return try {
            dateFormat.parse(dateStr)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private inline fun <reified T> Response.parseAs(): T {
        return json.decodeFromString<T>(body.string())
    }

    companion object {
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
}
