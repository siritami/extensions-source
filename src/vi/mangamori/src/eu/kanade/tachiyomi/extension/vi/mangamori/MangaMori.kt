package eu.kanade.tachiyomi.extension.vi.mangamori

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.getStringOrNull
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

@Source
abstract class MangaMori : KeiSource() {
    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        addInterceptor(MangaMoriImageInterceptor())
        rateLimit(3)
    }

    // ============================== Popular ===============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)

        val comics = client.get(baseUrl).extractNextJs<List<HomeComic>> { element ->
            element is JsonArray && element.firstOrNull().let { comic ->
                comic is JsonObject &&
                    comic.getStringOrNull("slug") != null &&
                    comic.getStringOrNull("thumbnail") != null &&
                    "chapters" in comic &&
                    "rawChapters" in comic
            }
        }
            ?: return MangasPage(emptyList(), false)
        return MangasPage(comics.map(HomeComic::toSManga), false)
    }

    // ============================== Latest ================================

    override val supportsLatest = false

    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    // ============================== Search ================================

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        if (page > 1 || query.isBlank()) return MangasPage(emptyList(), false)

        val url = "$baseUrl/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .build()
        val result = client.get(url).parseAs<SearchResponse>()
        return MangasPage(result.data.map(SearchComic::toSManga), false)
    }

    // ============================== Details ===============================

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/truyen/${manga.url.substringAfterLast('/')}"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/truyen/${chapter.url}"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.firstOrNull() != "truyen") return null
        val slug = url.pathSegments.getOrNull(1)?.takeIf(String::isNotBlank) ?: return null
        val manga = SManga.create().apply { this.url = slug }
        return fetchMangaUpdate(manga, emptyList(), true, false).manga
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val slug = manga.url.substringAfterLast('/')
        val payload = client.get("$baseUrl/truyen/$slug").extractNextJs<ComicPayload>()
            ?: return SMangaUpdate(manga, chapters)

        return SMangaUpdate(
            manga = payload.toSManga(slug),
            chapters = payload.toSChapterList(slug),
        )
    }

    // =============================== Pages ================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val payload = client.get(getChapterUrl(chapter)).extractNextJs<ReaderPayload>()
            ?: return emptyList()
        val mapData = decodeMap(
            encrypted = payload.encryptedMap,
            nonce = payload.mapNonce,
            count = payload.rows * payload.cols,
        ).joinToString(",")

        return payload.chapterData.pages
            .sortedBy(ReaderPage::pageOrder)
            .mapIndexed { index, page ->
                val imageUrl = page.imageUrl.toHttpUrl().newBuilder()
                    .fragment("mm:${payload.rows}:${payload.cols}:$mapData")
                    .build()
                    .toString()
                Page(index, imageUrl = imageUrl)
            }
    }
}
