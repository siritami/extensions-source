package eu.kanade.tachiyomi.extension.vi.minotruyen

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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
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

    private val apiHeaders by lazy { headersBuilder().add("Origin", baseUrl).build() }

    override val client = network.cloudflareClient.newBuilder()
        .rateLimitHost(apiUrl.toHttpUrl(), 3)
        .build()

    // ============================== Popular ===============================

    override fun popularMangaRequest(page: Int): Request {
        val url = "$apiUrl/books/side-home".toHttpUrl().newBuilder()
            .addQueryParameter("category", category)
            .build()
        return GET(url, apiHeaders)
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

    private inline fun <reified T> Response.parseAs(): T {
        return json.decodeFromString<T>(body.string())
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiUrl/books".toHttpUrl().newBuilder()
            .addQueryParameter("take", "24")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("category", category)
            .build()
        return GET(url, apiHeaders)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val result = response.parseAs<BooksResponse>()
        val mangaList = result.books.map { it.toSManga() }
        val hasNextPage = result.countBook?.let { mangaList.size < it } ?: false
        return MangasPage(mangaList, hasNextPage)
    }

    private fun Book.toSManga() = SManga.create().apply {
        url = "/books/$bookId"
        title = this@toSManga.title.trim()
        thumbnail_url = covers.firstOrNull()?.url
        status = parseStatus(this@toSManga.status)
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

        return GET(url.build(), apiHeaders)
    }

    override fun searchMangaParse(response: Response): MangasPage = latestUpdatesParse(response)

    // =============================== Details ==============================

    override fun mangaDetailsRequest(manga: SManga): Request {
        val bookId = manga.url.substringAfterLast("/")
        return GET("$apiUrl/books/$bookId", apiHeaders)
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

    private fun parseStatus(status: Int?): Int = when (status) {
        1 -> SManga.ONGOING
        2 -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    // ============================== Chapters ==============================

    override fun chapterListRequest(manga: SManga): Request {
        val bookId = manga.url.substringAfterLast("/")
        val url = "$apiUrl/chapters/$bookId".toHttpUrl().newBuilder()
            .addQueryParameter("order", "desc")
            .addQueryParameter("take", "5000")
            .build()
        return GET(url, apiHeaders)
    }

    override fun getChapterUrl(chapter: SChapter): String {
        return "$baseUrl/$category${chapter.url}"
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val result = response.parseAs<ChaptersResponse>()
        return result.chapters.map { chapter ->
            SChapter.create().apply {
                val bookId = chapter.bookId
                url = "/books/$bookId/${chapter.chapterNumber}"
                name = chapter.num
                date_upload = parseDate(chapter.createdAt)
            }
        }
    }

    private fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        return try {
            dateFormat.parse(dateStr)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    // =============================== Pages ================================

    override fun pageListRequest(chapter: SChapter): Request {
        return GET("$baseUrl/$category${chapter.url}", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val html = response.body.string()

        val encrypted = ENCRYPTED_DATA_REGEX.find(html)?.groupValues?.get(1)
            ?: throw Exception("Could not find encrypted chapter data")

        val encData = encrypted.substringAfter(":")

        val decrypted = CryptoAES.decrypt(encData, AES_KEY)
        if (decrypted.isBlank()) {
            throw Exception("Failed to decrypt chapter data")
        }

        val root = json.parseToJsonElement(decrypted)

        val imageUrls = when {
            // {"NV1": ["url1", "url2", ...], "NV2": [...]}
            root is JsonObject -> {
                root.values.first().jsonArray
                    .map { it.jsonPrimitive.content }
            }
            // ["url1", "url2", ...] or [{"NV1": "url1"}, ...]
            root is kotlinx.serialization.json.JsonArray -> {
                root.mapNotNull { element ->
                    when {
                        element is JsonPrimitive && element.content.startsWith("http") ->
                            element.content
                        element is JsonObject ->
                            element.values.firstNotNullOfOrNull {
                                (it as? JsonPrimitive)?.content?.takeIf { url -> url.startsWith("http") }
                            }
                        else -> null
                    }
                }
            }
            else -> throw Exception("Unexpected data format")
        }

        return imageUrls.mapIndexed { index, imageUrl ->
            Page(index, imageUrl = imageUrl)
        }
    }

    override fun imageUrlParse(response: Response): String {
        throw UnsupportedOperationException()
    }

    companion object {
        private const val AES_KEY = "GCERKSmf28E6nWwrnR8Lz4f7TacKpzMy7aK0rxSB"
        private val ENCRYPTED_DATA_REGEX = Regex("""([a-f0-9]{32}:U2FsdGVk[A-Za-z0-9+/=]+)""")

        private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
}
