package eu.kanade.tachiyomi.extension.vi.kirakira

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.head
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class KiraKira :
    KeiSource(),
    ConfigurableSource {
    private val apiUrl = "https://api.${baseUrl.toHttpUrl().host}"

    private val apiHeaders: Headers
        get() = headersBuilder()
            .set("Accept", "application/json")
            .set("X-Requested-With", "XMLHttpRequest")
            .build()

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        rateLimit(3)
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$apiUrl/top".toHttpUrl().newBuilder()
            .addQueryParameter("status", "all")
            .addQueryParameter("page", page.toString())
            .build()

        return client.get(url, apiHeaders).parseAs<ComicListDto>().toMangasPage()
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$apiUrl/recent-update-comics".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .build()

        return client.get(url, apiHeaders).parseAs<ComicListDto>().toMangasPage()
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isNotBlank()) {
            val url = "$apiUrl/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("page", page.toString())
                .build()

            return client.get(url, apiHeaders).parseAs<ComicListDto>().toMangasPage()
        }

        val genreId = filters.firstInstanceOrNull<GenreFilter>()?.selected?.id
        if (genreId != null) {
            val url = "$apiUrl/genres/$genreId".toHttpUrl().newBuilder()
                .addQueryParameter("type", genreId)
                .addQueryParameter("page", page.toString())
                .build()

            return client.get(url, apiHeaders).parseAs<ComicListDto>().toMangasPage()
        }

        return getLatestUpdates(page)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val slug = when (url.pathSegments.firstOrNull()) {
            "comics", "chapters" -> url.pathSegments.getOrNull(1)
            else -> null
        } ?: return null
        val manga = SManga.create().apply {
            title = slug
            setUrlWithoutDomain("/comics/$slug")
        }
        return fetchMangaUpdate(manga, emptyList(), true, false).manga
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${chapter.url}"

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val slug = extractComicSlug(manga.url) ?: throw Exception("Không tìm thấy mã truyện")
        val payload = client.get("$apiUrl/comics/$slug", apiHeaders).parseAs<ComicDetailsDto>()

        val updatedManga = SManga.create().apply {
            setUrlWithoutDomain("/comics/$slug")
            title = payload.title
            thumbnail_url = payload.thumbnail?.ifBlank { null } ?: payload.banner_image_url?.ifBlank { null }
            author = "Unknown"
            status = parseStatus(payload.status)
            genre = payload.genres.mapNotNull { it.name }.joinToString().ifEmpty { null }
            description = payload.description
        }

        return SMangaUpdate(
            manga = updatedManga,
            chapters = parseChapters(payload, slug),
        )
    }

    private fun parseStatus(statusText: String?): Int = when {
        statusText == null -> SManga.UNKNOWN
        statusText.equals("updating", true) -> SManga.ONGOING
        statusText.equals("ongoing", true) -> SManga.ONGOING
        statusText.equals("completed", true) -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    private fun extractComicSlug(url: String): String? = comicSlugRegex.find(url)?.groupValues?.getOrNull(1) ?: url.substringBefore('/').takeIf { it.isNotBlank() }

    private fun parseChapters(payload: ComicDetailsDto, slug: String): List<SChapter> {
        val autoUnlock = isAutoUnlockEnabled

        return payload.chapters.mapNotNull { chapter ->
            val chapterId = chapter.id ?: return@mapNotNull null
            val chapterTitle = chapter.name ?: return@mapNotNull null
            val isLocked = (chapter.coinPrice ?: 0) > 0
            val unlockDate = chapter.unlockAt?.let(::formatUnlockDate)
            val chapterDate = chapter.unlockAt?.let(::parseDate) ?: 0L

            SChapter.create().apply {
                name = if (autoUnlock) chapterTitle else buildChapterName(chapterTitle, isLocked, unlockDate)
                val chapterUrl = buildString {
                    append("/chapters/$slug/$chapterId")
                    if (isLocked && !autoUnlock) {
                        append("?is_locked=1")
                    }
                }
                setUrlWithoutDomain(chapterUrl)
                date_upload = chapterDate
            }
        }
    }

    private fun buildChapterName(chapterName: String, isLocked: Boolean, unlockDate: String?): String {
        if (!isLocked) return chapterName

        return buildString {
            append("\uD83D\uDD12")
            append(" ")
            append(chapterName)
            if (unlockDate != null) {
                append(" [Mở khóa: ")
                append(unlockDate)
                append("]")
            }
        }
    }

    private fun formatUnlockDate(dateText: String): String? = runCatching {
        unlockLabelDateFormat.format(Instant.parse(dateText).atZone(dateZone))
    }.getOrNull()

    private fun parseDate(dateText: String): Long = runCatching {
        Instant.parse(dateText).toEpochMilli()
    }.getOrDefault(0L)

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = "$baseUrl${chapter.url}".toHttpUrl()
        if (chapterUrl.queryParameter("is_locked") == "1") {
            throw Exception(lockedChapterMessage)
        }

        val chapterInfo = extractChapterInfo(chapter.url)
            ?: throw Exception("Không tìm thấy thông tin chương")

        val url = "$apiUrl/comics/${chapterInfo.first}/chapters/${chapterInfo.second}"
            .toHttpUrl()
            .newBuilder()
            .build()

        val response = client.get(url, apiHeaders, ensureSuccess = false)

        if (!response.isSuccessful) {
            if (response.code == 401 && isAutoUnlockEnabled) {
                response.close()
                return buildPageListFromPattern(chapterInfo.first, chapterInfo.second)
            }
            val error = runCatching { response.parseAs<ApiErrorDto>() }.getOrNull()
            throw Exception(error?.message ?: "Không thể tải dữ liệu chương")
        }

        val payload = response.parseAs<ChapterPagesDto>()
        val imageUrls = payload.images.mapNotNull { it.src?.ifBlank { null } }

        if (imageUrls.isEmpty()) {
            if (isAutoUnlockEnabled) {
                return buildPageListFromPattern(chapterInfo.first, chapterInfo.second)
            }
            if ((payload.coinPrice ?: 0) > 0 && payload.isPurchased == false) {
                throw Exception(lockedChapterMessage)
            }
            throw Exception("Không tìm thấy hình ảnh")
        }

        return imageUrls.mapIndexed { index, imageUrl ->
            Page(index, imageUrl = imageUrl)
        }
    }

    /**
     * Build page list by constructing predictable image URLs.
     * Images are hosted at: {baseUrl}/manga/{imageSlug}/chapter-{id}/page-{i}.jpg
     * Probes pages with HEAD requests until the response Content-Type is not an image.
     */
    private suspend fun buildPageListFromPattern(comicSlug: String, chapterId: String): List<Page> {
        val imageSlug = fetchImageSlug(comicSlug) ?: comicSlug
        val pages = mutableListOf<Page>()
        var index = 1

        while (index <= maxPageProbe) {
            val imageUrl = "$baseUrl/manga/$imageSlug/chapter-$chapterId/page-$index.jpg"
            val isImage = client.head(imageUrl, headers, ensureSuccess = false).use {
                it.isSuccessful && it.header("Content-Type")?.startsWith("image/") == true
            }

            if (!isImage) break

            pages.add(Page(index - 1, imageUrl = imageUrl))
            index++
        }

        if (pages.isEmpty()) {
            throw Exception("Không tìm thấy hình ảnh")
        }

        return pages
    }

    private suspend fun fetchImageSlug(comicSlug: String): String? {
        val details = client.get("$apiUrl/comics/$comicSlug", apiHeaders).parseAs<ComicDetailsDto>()
        return details.thumbnail?.let { imageSlugRegex.find(it)?.groupValues?.getOrNull(1) }
    }

    private fun extractChapterInfo(url: String): Pair<String, String>? {
        val match = (chapterInfoRegex.find(url) ?: apiChapterRegex.find(url)) ?: return null
        val comicSlug = match.groupValues.getOrNull(1)
        val chapterId = match.groupValues.getOrNull(2)
        if (comicSlug.isNullOrBlank() || chapterId.isNullOrBlank()) return null
        return comicSlug to chapterId
    }

    override val supportsFilterFetching get() = true

    override suspend fun fetchFilterData(): JsonElement = client.get("$apiUrl/genres", apiHeaders).parseAs()

    override fun getFilterList(data: JsonElement?): FilterList {
        val genres = data?.parseAs<GenreListDto>()?.data?.genres.orEmpty().mapNotNull { genre ->
            val id = genre.id ?: return@mapNotNull null
            val name = genre.name ?: return@mapNotNull null
            GenreOption(name, id)
        }
        return getFilters(genres)
    }

    private val preferences: SharedPreferences = getPreferences()

    private val isAutoUnlockEnabled: Boolean
        get() = preferences.getBoolean(keyAutoUnlockChapters, false)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = keyAutoUnlockChapters
            title = "Tự động mở khóa chương"
            summary = "Có thể gây chậm hoặc crash cân nhắc khi sử dụng."
            setDefaultValue(false)
        }.let(screen::addPreference)
    }

    private fun ComicListDto.toMangasPage(): MangasPage {
        val mangas = comics.mapNotNull { comic ->
            val slug = comic.id ?: return@mapNotNull null

            SManga.create().apply {
                title = comic.title
                setUrlWithoutDomain("/comics/$slug")
                thumbnail_url = comic.thumbnail?.ifBlank { null } ?: comic.banner_image_url?.ifBlank { null }
            }
        }

        return MangasPage(mangas, current_page < total_pages)
    }

    private val maxPageProbe = 200
    private val lockedChapterMessage = "Vui lòng đăng nhập bằng tài khoản phù hợp qua webview để xem chương này"
    private val keyAutoUnlockChapters = "autoUnlockChapters"
    private val comicSlugRegex = Regex("/comics/([^/?#]+)")
    private val chapterInfoRegex = Regex("(?:/chapters/)?([^/?#]+)/([^/?#]+)")
    private val apiChapterRegex = Regex("/comics/([^/?#]+)/chapters/([^/?#]+)")
    private val imageSlugRegex = Regex("/manga/([^/]+)/thumbnail")
    private val dateZone = ZoneId.of("Asia/Ho_Chi_Minh")
    private val unlockLabelDateFormat = DateTimeFormatter.ofPattern("dd/MM", Locale.ROOT)
}
