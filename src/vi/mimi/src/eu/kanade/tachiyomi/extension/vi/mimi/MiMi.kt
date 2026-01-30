package eu.kanade.tachiyomi.extension.vi.mimi

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.util.Calendar

class MiMi : HttpSource() {

    override val name = "MiMi"

    override val baseUrl = "https://mimimoe.moe"

    override val lang = "vi"

    override val supportsLatest = true

    override val client = network.cloudflareClient

    private val json: Json by injectLazy()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ============================== Popular ======================================

    override fun popularMangaRequest(page: Int): Request {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("advance-search")
            .addQueryParameter("sortType", "views")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val nuxtData = extractNuxtData(response.body.string())
        return parseMangaList(nuxtData)
    }

    // ============================== Latest ======================================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("advance-search")
            .addQueryParameter("sortType", "updated_at")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // ============================== Search ======================================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("advance-search")
            .addQueryParameter("sortType", "updated_at")
            .addQueryParameter("page", page.toString())

        if (query.isNotBlank()) {
            url.addQueryParameter("name", query)
        }

        filters.filterIsInstance<GenreFilter>().firstOrNull()?.let { filter ->
            val genreId = filter.toUriPart()
            if (genreId.isNotEmpty()) {
                url.addQueryParameter("genre", genreId)
            }
        }

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // ============================== Filters ======================================

    override fun getFilterList(): FilterList = getFilters()

    // ============================== Details ======================================

    override fun getMangaUrl(manga: SManga) = baseUrl + manga.url

    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET(getMangaUrl(manga), headers)
    }

    override fun mangaDetailsParse(response: Response): SManga = SManga.create().apply {
        val nuxtData = extractNuxtData(response.body.string())
        val mangaId = response.request.url.pathSegments.last()
        val mangaInfo = nuxtData["manga-info-$mangaId"]?.jsonObject
            ?: throw Exception("Manga info not found")

        title = mangaInfo["title"]!!.jsonPrimitive.content
        thumbnail_url = mangaInfo["coverUrl"]?.jsonPrimitive?.content

        author = mangaInfo["authors"]?.jsonArray?.joinToString { it.jsonObject["name"]!!.jsonPrimitive.content }

        genre = mangaInfo["genres"]?.jsonArray?.joinToString { it.jsonObject["name"]!!.jsonPrimitive.content }

        description = buildString {
            mangaInfo["description"]?.jsonPrimitive?.content?.let { append(it) }
            mangaInfo["differentNames"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let {
                append("\n\nTên khác: $it")
            }
            mangaInfo["parody"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let {
                append("\n\nParody: $it")
            }
        }.ifEmpty { null }

        status = SManga.UNKNOWN
    }

    // ============================== Chapters ======================================

    override fun getChapterUrl(chapter: SChapter) = baseUrl + chapter.url

    override fun chapterListRequest(manga: SManga): Request {
        return GET(getMangaUrl(manga), headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val nuxtData = extractNuxtData(response.body.string())
        val mangaId = response.request.url.pathSegments.last()
        val chapterList = nuxtData["chapter-list-$mangaId"]?.jsonArray
            ?: return emptyList()

        return chapterList.mapIndexed { index, element ->
            val chapterObj = element.jsonObject
            SChapter.create().apply {
                val chapterId = chapterObj["id"]!!.jsonPrimitive.content
                val chapterTitle = chapterObj["title"]!!.jsonPrimitive.content
                url = "/g/$mangaId/chapter/$chapterTitle-$chapterId"
                name = chapterTitle
                chapter_number = (chapterList.size - index).toFloat()

                val dateStr = chapterObj["createdAt"]?.jsonPrimitive?.content
                date_upload = dateStr.toDate()
            }
        }
    }

    private fun String?.toDate(): Long {
        this ?: return 0L

        // Handle relative time format (e.g., 12s ago, 49m ago, 15h ago, 4d ago)
        if (this.contains("ago", ignoreCase = true)) {
            return try {
                val calendar = Calendar.getInstance()

                val patterns = listOf(
                    Regex("""(\d+)\s*s""", RegexOption.IGNORE_CASE) to Calendar.SECOND,
                    Regex("""(\d+)\s*m""", RegexOption.IGNORE_CASE) to Calendar.MINUTE,
                    Regex("""(\d+)\s*h""", RegexOption.IGNORE_CASE) to Calendar.HOUR_OF_DAY,
                    Regex("""(\d+)\s*d""", RegexOption.IGNORE_CASE) to Calendar.DAY_OF_MONTH,
                )

                for ((pattern, field) in patterns) {
                    pattern.find(this)?.groupValues?.get(1)?.toIntOrNull()?.let { number ->
                        calendar.add(field, -number)
                        return calendar.timeInMillis
                    }
                }

                0L
            } catch (_: Exception) {
                0L
            }
        }

        // Handle direct date format (e.g., Jan 22, 2026)
        return try {
            dateFormat.parse(this)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private val dateFormat by lazy {
        java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.ROOT)
    }

    // ============================== Pages ======================================

    override fun pageListRequest(chapter: SChapter): Request {
        return GET(getChapterUrl(chapter), headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val nuxtData = extractNuxtData(response.body.string())
        val chapterId = response.request.url.pathSegments.last().substringAfterLast("-")
        val chapterData = nuxtData["chapter-$chapterId"]?.jsonObject
            ?: throw Exception("Chapter data not found")

        val pages = chapterData["pages"]?.jsonArray
            ?: throw Exception("Pages not found")

        return pages.mapIndexed { index, element ->
            val pageObj = element.jsonObject
            val imageUrl = pageObj["imageUrl"]?.jsonPrimitive?.content
                ?: pageObj["url"]?.jsonPrimitive?.content
                ?: throw Exception("Image URL not found")
            Page(index, imageUrl = imageUrl)
        }
    }

    override fun imageUrlParse(response: Response): String {
        throw UnsupportedOperationException()
    }

    // ============================== Utilities ======================================

    private fun extractNuxtData(html: String): JsonObject {
        val nuxtDataRegex = Regex("""window\.__NUXT__\s*=\s*(\{.+?\})\s*(?:</script>|;)""", RegexOption.DOT_MATCHES_ALL)
        val match = nuxtDataRegex.find(html)
            ?: throw Exception("Nuxt data not found")

        val jsonString = match.groupValues[1]
        val nuxtObj = json.parseToJsonElement(jsonString).jsonObject
        return nuxtObj["data"]?.jsonObject ?: throw Exception("Data not found in Nuxt object")
    }

    private fun parseMangaList(nuxtData: JsonObject): MangasPage {
        val searchResults = nuxtData.entries.find { it.key.startsWith("search-results") }?.value?.jsonObject
            ?: nuxtData.entries.find { it.key.startsWith("manga-list") }?.value?.jsonObject

        val mangas = (searchResults?.get("data") as? JsonArray)?.map { element ->
            val mangaObj = element.jsonObject
            SManga.create().apply {
                val id = mangaObj["id"]!!.jsonPrimitive.content
                url = "/g/$id"
                title = mangaObj["title"]!!.jsonPrimitive.content
                thumbnail_url = mangaObj["coverUrl"]?.jsonPrimitive?.content
            }
        } ?: emptyList()

        val hasNextPage = searchResults?.get("hasNextPage")?.jsonPrimitive?.content?.toBoolean() ?: false

        return MangasPage(mangas, hasNextPage)
    }
}
