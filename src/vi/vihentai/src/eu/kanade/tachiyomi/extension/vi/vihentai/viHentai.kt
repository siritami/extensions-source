package eu.kanade.tachiyomi.extension.vi.vihentai

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.tryParse
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class viHentai : HttpSource() {

    override val name = "viHentai"

    override val baseUrl = "https://vi-hentai.moe"

    override val lang = "vi"

    override val supportsLatest = true

    override val client = network.cloudflareClient.newBuilder()
        .rateLimit(5)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/danh-sach?sort=-views&page=$page&filter[status]=2,1", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()

        val mangaList = document.select("div.manga-vertical").map { element ->
            SManga.create().apply {
                val linkElement = element.selectFirst("div.p-2 a")!!
                setUrlWithoutDomain(linkElement.absUrl("href"))
                title = linkElement.text()
                thumbnail_url = element.selectFirst("div.cover")?.extractBackgroundImage()
            }
        }

        val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val hasNextPage = document.selectFirst("a[href*='page=${currentPage + 1}']") != null

        return MangasPage(mangaList, hasNextPage)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/danh-sach?page=$page", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        return popularMangaParse(response)
    }

    // =============================== Search ===============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/tim-kiem".toHttpUrl().newBuilder().apply {
            addQueryParameter("keyword", query)
            addQueryParameter("page", page.toString())

            filters.forEach { filter ->
                when (filter) {
                    is SortFilter -> addQueryParameter("sort", filter.toUriPart())
                    is StatusFilter -> {
                        val status = filter.toUriPart()
                        if (status.isNotEmpty()) {
                            addQueryParameter("filter[status]", status)
                        }
                    }
                    is GenreFilter -> {
                        val selectedGenres = filter.state
                            .filter { it.state }
                            .joinToString(",") { it.id }
                        if (selectedGenres.isNotEmpty()) {
                            addQueryParameter("filter[accept_genres]", selectedGenres)
                        }
                    }
                    else -> {}
                }
            }
        }.build()

        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        return popularMangaParse(response)
    }

    override fun getFilterList(): FilterList = getFilters()

    // =============================== Details ==============================

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()

        return SManga.create().apply {
            title = document.selectFirst("span.grow.text-lg")!!.text()
            author = document.selectFirst("a[href*=/tac-gia/]")?.text()
            genre = document.select("div.mt-2.flex.flex-wrap.gap-1 a[href*=/the-loai/]").joinToString { it.text() }
            thumbnail_url = document.selectFirst("div.cover-frame div.cover")?.extractBackgroundImage()
            description = document.selectFirst("div.mg-plot")?.let { plot ->
                plot.select("p")
                    .drop(1)
                    .joinToString("\n") { it.text() }
                    .trim()
                    .ifEmpty { null }
            }

            status = document.selectFirst("a[href*='filter[status]'] span")?.text()?.let { statusText ->
                when {
                    statusText.contains("Đã hoàn thành") -> SManga.COMPLETED
                    statusText.contains("Đang tiến hành") -> SManga.ONGOING
                    else -> SManga.UNKNOWN
                }
            } ?: SManga.UNKNOWN
        }
    }

    // ============================== Chapters ==============================

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()

        return document.select("ul a[href*=/truyen/]").map { element ->
            SChapter.create().apply {
                setUrlWithoutDomain(element.absUrl("href"))
                name = element.selectFirst("span.text-ellipsis")!!.text()
                date_upload = element.selectFirst("span.timeago[datetime]")
                    ?.attr("datetime")
                    .let { dateFormat.tryParse(it) }
            }
        }
    }

    // =============================== Pages ================================

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()

        // Images are loaded via packed JavaScript, not in the HTML directly
        val packedScript = document.select("script").map { it.data() }
            .firstOrNull { it.contains("eval(function(h,u,n,t,e,r)") }
            ?: throw Exception("Could not find packed script with image data")

        val decoded = unpackScript(packedScript)

        return IMAGE_URL_REGEX.findAll(decoded).mapIndexed { index, match ->
            Page(index, imageUrl = match.groupValues[1].replace("\\/", "/"))
        }.toList()
    }

    override fun imageUrlParse(response: Response): String {
        throw UnsupportedOperationException()
    }

    // ============================= Utilities ==============================

    private fun Element.extractBackgroundImage(): String? {
        val style = attr("style") ?: return null
        return BACKGROUND_IMAGE_REGEX.find(style)?.groupValues?.get(1)
    }

    /**
     * Unpacks a JavaScript "HUNTO" packer script.
     * The packed format: eval(function(h,u,n,t,e,r){...}("encoded",base,charset,offset))
     */
    private fun unpackScript(script: String): String {
        val argsMatch = PACKED_ARGS_REGEX.find(script)
            ?: throw Exception("Could not parse packed script arguments")

        val h = argsMatch.groupValues[1]
        val u = argsMatch.groupValues[2].toInt()
        val n = argsMatch.groupValues[3]
        val t = argsMatch.groupValues[4].toInt()

        val result = StringBuilder()
        var i = 0
        while (i < h.length) {
            val s = StringBuilder()
            while (i < h.length && h[i] != n[u]) {
                s.append(h[i])
                i++
            }
            i++ // skip delimiter
            var charStr = s.toString()
            for (j in n.indices) {
                charStr = charStr.replace(n[j].toString(), j.toString())
            }
            result.append((baseConvert(charStr, u, 10) - t).toChar())
        }
        return result.toString()
    }

    /**
     * Converts a number string from one base to another.
     * Replicates the _0xe6c function from the site's JavaScript.
     */
    private fun baseConvert(d: String, fromBase: Int, toBase: Int): Int {
        val charset = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ+/"
        val fromChars = charset.substring(0, fromBase)
        return d.reversed().foldIndexed(0) { index, acc, char ->
            val pos = fromChars.indexOf(char)
            if (pos != -1) {
                acc + pos * Math.pow(fromBase.toDouble(), index.toDouble()).toInt()
            } else {
                acc
            }
        }
    }

    private val dateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
        }
    }

    companion object {
        private val BACKGROUND_IMAGE_REGEX = Regex("""background-image:\s*url\(['"]?(.*?)['"]?\)""")
        private val IMAGE_URL_REGEX = Regex(""""(https?:\\?/\\?/[^"]+\.\w{3,4})""")
        private val PACKED_ARGS_REGEX = Regex("""\}\("(.+?)",\s*(\d+),\s*"([^"]+)",\s*(\d+)""")
    }
}
