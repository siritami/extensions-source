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
import keiyoushi.utils.asJsoup
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

@Source
abstract class MangaMori : KeiSource() {
    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        addInterceptor(ImageInterceptor())
        rateLimit(3)
    }

    // ============================== Popular ===============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)

        val document = client.get(baseUrl).asJsoup()
        val mangas = document.select("#truyen-moi a[href^=/truyen/]").mapNotNull { card ->
            val titleElement = card.selectFirst("h3") ?: return@mapNotNull null
            val href = card.attr("href")
            val slug = href.substringAfter("/truyen/").substringBefore('/').takeIf(String::isNotBlank)
                ?: return@mapNotNull null

            SManga.create().apply {
                url = slug
                title = titleElement.text()
                thumbnail_url = card.selectFirst("img")?.absUrl("src")
                author = titleElement.nextElementSibling()?.text()
            }
        }
        return MangasPage(mangas, false)
    }

    // ============================== Latest ================================

    override val supportsLatest = false

    override suspend fun getLatestUpdates(page: Int): MangasPage = MangasPage(emptyList(), false)

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
        val document = client.get("$baseUrl/truyen/$slug").asJsoup()
        val titleElement = document.selectFirst("h1") ?: return SMangaUpdate(manga, chapters)
        val detailsContainer = titleElement.parent()
        val statusText = detailsContainer?.select("span")
            ?.firstOrNull { element ->
                element.text().lowercase() in detailStatuses
            }
            ?.text()
            ?.lowercase()
        val chapterPayload = document.extractNextJs<ChapterPayload>()

        return SMangaUpdate(
            manga = SManga.create().apply {
                url = slug
                title = titleElement.text()
                thumbnail_url = detailsContainer?.parent()?.selectFirst("img")?.absUrl("src")
                author = titleElement.nextElementSibling()?.select("span")?.last()?.text()
                description = document.selectFirst("h2:containsOwn(Nội dung) + p")?.text()
                status = when (statusText) {
                    "đang tiến hành" -> SManga.ONGOING
                    "hoàn thành" -> SManga.COMPLETED
                    else -> SManga.UNKNOWN
                }
            },
            chapters = chapterPayload?.toSChapterList(slug) ?: chapters,
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

    private val detailStatuses = setOf("đang tiến hành", "hoàn thành")
}
