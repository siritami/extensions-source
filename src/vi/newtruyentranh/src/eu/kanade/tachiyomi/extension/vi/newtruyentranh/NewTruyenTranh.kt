package eu.kanade.tachiyomi.extension.vi.newtruyentranh

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

class NewTruyenTranh : HttpSource() {
    override val name = "NewTruyenTranh"
    override val lang = "vi"
    override val baseUrl = "https://newtruyenhot.4share.me"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", baseUrl)
        .add("Origin", baseUrl)

    // ============================== Popular ===============================
    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("sort", "10") // Top all
            .addQueryParameter("p", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val result = response.parseAs<MangaListResponse>()
        val mangas = result.channels.map { it.toSManga() }
        val hasNextPage = result.loadMore?.pageInfo?.let {
            it.currentPage < it.lastPage
        } ?: false
        return MangasPage(mangas, hasNextPage)
    }

    // ============================== Latest ================================
    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/page/newest".toHttpUrl().newBuilder()
            .addQueryParameter("p", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    // ============================== Search ================================
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("p", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    // ============================== Details ===============================
    override fun mangaDetailsRequest(manga: SManga): Request {
        val id = manga.url.substringAfter("/detail/").substringBefore("?")
        return GET("$baseUrl/detail/$id", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        // The API detail endpoint returns chapter list, not full manga details
        // We get the basic info from the list already
        return SManga.create().apply {
            initialized = true
        }
    }

    override fun getMangaUrl(manga: SManga): String {
        return "https://newtruyentranh5.com" + manga.url.replace("/detail/", "/truyen/")
    }

    // ============================== Chapters ==============================
    override fun chapterListRequest(manga: SManga): Request {
        val id = manga.url.substringAfter("/detail/").substringBefore("?")
        return GET("$baseUrl/detail/$id", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val result = response.parseAs<ChapterListResponse>()
        val chapters = mutableListOf<SChapter>()

        result.sources.forEach { source ->
            source.contents.forEach { content ->
                content.streams.forEach { stream ->
                    chapters.add(
                        SChapter.create().apply {
                            url = stream.remoteData.url
                            name = stream.name
                            chapter_number = stream.index.toFloat()
                        },
                    )
                }
            }
        }

        // Sort by index descending (newest first)
        return chapters.sortedByDescending { it.chapter_number }
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val chapterId = chapter.url.substringAfter("/chapter/")
        return "https://newtruyentranh5.com/truyen-tranh/$chapterId"
    }

    // ============================== Pages =================================
    override fun pageListRequest(chapter: SChapter): Request {
        val chapterUrl = chapter.url
        val url = if (chapterUrl.startsWith("http")) {
            chapterUrl
        } else {
            "$baseUrl$chapterUrl"
        }
        return GET(url, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val result = response.parseAs<PageListResponse>()
        return result.files.mapIndexed { index, file ->
            Page(index, imageUrl = file.url)
        }
    }

    override fun imageUrlParse(response: Response): String {
        throw UnsupportedOperationException()
    }

    // ============================== Utilities =============================
    private fun MangaChannel.toSManga(): SManga = SManga.create().apply {
        url = remoteData.url.replace(baseUrl, "")
        title = name
        thumbnail_url = image.url
    }

    // ============================== DTO ===================================
    @Serializable
    data class MangaListResponse(
        val channels: List<MangaChannel> = emptyList(),
        @SerialName("load_more")
        val loadMore: LoadMore? = null,
    )

    @Serializable
    data class MangaChannel(
        val id: String,
        val name: String,
        val description: String = "",
        val image: ImageData,
        @SerialName("remote_data")
        val remoteData: RemoteData,
    )

    @Serializable
    data class ImageData(
        val url: String,
    )

    @Serializable
    data class RemoteData(
        val url: String,
    )

    @Serializable
    data class LoadMore(
        @SerialName("pageInfo")
        val pageInfo: PageInfo? = null,
    )

    @Serializable
    data class PageInfo(
        @SerialName("current_page")
        val currentPage: Int = 1,
        val total: Int = 0,
        @SerialName("per_page")
        val perPage: Int = 24,
        @SerialName("last_page")
        val lastPage: Int = 1,
    )

    @Serializable
    data class ChapterListResponse(
        val sources: List<Source> = emptyList(),
    )

    @Serializable
    data class Source(
        val id: String,
        val name: String,
        val contents: List<Content> = emptyList(),
    )

    @Serializable
    data class Content(
        val id: String,
        val name: String = "",
        val streams: List<Stream> = emptyList(),
    )

    @Serializable
    data class Stream(
        val id: String,
        val index: Int,
        val name: String,
        @SerialName("remote_data")
        val remoteData: RemoteData,
    )

    @Serializable
    data class PageListResponse(
        val files: List<PageFile> = emptyList(),
    )

    @Serializable
    data class PageFile(
        val id: String,
        val name: String = "",
        val url: String,
    )
}
