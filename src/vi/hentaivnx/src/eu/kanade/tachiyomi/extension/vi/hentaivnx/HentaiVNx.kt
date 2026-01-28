package eu.kanade.tachiyomi.extension.vi.hentaivnx

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import rx.Observable

class HentaiVNx : HttpSource() {

    override val name = "HentaiVNx"

    override val baseUrl = "https://www.hentaivnx.com"

    override val lang = "vi"

    override val supportsLatest = true

    // ============================== Popular ===============================

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/tim-truyen-nang-cao?genres=&notgenres=&minchapter=0&sort=10&contain=&page=$page", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()

        val mangaList = document.select(".item").map { element ->
            SManga.create().apply {
                val linkElement = element.selectFirst("a.jtip, .image a")!!
                setUrlWithoutDomain(linkElement.absUrl("href"))
                title = linkElement.attr("title").ifEmpty { 
                    element.selectFirst("h3 a")?.text() ?: linkElement.text()
                }
                thumbnail_url = element.selectFirst(".image img")?.let {
                    it.absUrl("data-src").ifEmpty { it.absUrl("src") }
                }
            }
        }

        val hasNextPage = document.selectFirst("ul.pagination li:last-child:not(.disabled) a") != null

        return MangasPage(mangaList, hasNextPage)
    }

    // ============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/?page=$page", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        return popularMangaParse(response)
    }

    // ============================== Search ===============================

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return when {
            query.startsWith(PREFIX_ID_SEARCH) -> {
                val slug = query.removePrefix(PREFIX_ID_SEARCH).trim()
                fetchMangaDetails(
                    SManga.create().apply {
                        url = "/truyen-hentai/$slug"
                    },
                ).map {
                    it.url = "/truyen-hentai/$slug"
                    MangasPage(listOf(it), false)
                }
            }
            else -> super.fetchSearchManga(page, query, filters)
        }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = if (query.isNotBlank()) {
            baseUrl.toHttpUrl().newBuilder()
                .addQueryParameter("keyword", query)
                .addQueryParameter("page", page.toString())
                .build()
        } else {
            var genreSlug = ""
            filters.forEach { filter ->
                if (filter is GenreFilter) {
                    val genres = getGenreList()
                    genreSlug = genres[filter.state].second
                }
            }
            if (genreSlug.isNotEmpty()) {
                "$baseUrl/tim-truyen/$genreSlug?page=$page".toHttpUrl()
            } else {
                "$baseUrl/tim-truyen?page=$page".toHttpUrl()
            }
        }
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        return popularMangaParse(response)
    }

    override fun getFilterList(): FilterList = FilterList(
        GenreFilter(getGenreList()),
    )

    // ============================== Details ===============================

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()

        return SManga.create().apply {
            title = document.selectFirst("h1.title-detail")!!.text()
            author = document.selectFirst("li.author .col-xs-8")?.text()?.trim()
            description = document.selectFirst(".detail-content")?.text()?.trim()
            genre = document.select("li.kind .col-xs-8 a").joinToString { it.text().trim() }
            thumbnail_url = document.selectFirst(".detail-info .col-image img")?.let {
                it.absUrl("data-src").ifEmpty { it.absUrl("src") }
            }

            val statusText = document.selectFirst("li.status .col-xs-8")?.text()
            status = when {
                statusText?.contains("Đang tiến hành", ignoreCase = true) == true -> SManga.ONGOING
                statusText?.contains("Hoàn thành", ignoreCase = true) == true -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
    }

    // ============================== Chapters ===============================

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()

        return document.select(".list-chapter ul li a, #nt_listchapter ul li a").mapNotNull { element ->
            parseChapterElement(element)
        }
    }

    private fun parseChapterElement(element: Element): SChapter? {
        val url = element.attr("href")
        if (url.isBlank()) return null

        return SChapter.create().apply {
            setUrlWithoutDomain(url)
            name = element.text().trim()
        }
    }

    // ============================== Pages ===============================

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()

        val images = document.select(".reading-detail img, .page-chapter img")
            .ifEmpty { document.select(".chapter-content img") }

        return images.mapIndexed { idx, element ->
            val imageUrl = element.absUrl("data-src")
                .ifEmpty { element.absUrl("data-original") }
                .ifEmpty { element.absUrl("src") }
            Page(idx, imageUrl = imageUrl)
        }
    }

    override fun imageUrlParse(response: Response): String {
        throw UnsupportedOperationException()
    }

    companion object {
        const val PREFIX_ID_SEARCH = "id:"
    }
}
