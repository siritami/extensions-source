package eu.kanade.tachiyomi.extension.vi.damconuong

import android.webkit.CookieManager
import eu.kanade.tachiyomi.source.model.Filter
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
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.stringOrNull
import keiyoushi.utils.toJsonElement
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Source
abstract class DamCoNuong : KeiSource() {
    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        rateLimit(5)
        addInterceptor(ScrambleInterceptor())
        addInterceptor(authInterceptor())
    }

    private val preferences by getPreferencesLazy()

    private val cookieManager by lazy { CookieManager.getInstance() }

    private val api = "https://api.damconuong.pw/api/v1"
    private val apiHost = api.toHttpUrl().host

    private fun authInterceptor() = Interceptor { chain ->
        val request = chain.request()
        val host = request.url.host
        val baseHost = baseUrl.toHttpUrl().host
        val builder = request.newBuilder()
        var modified = false

        if (host == baseHost || host == apiHost) {
            val cookies = cookieManager.getCookie(baseUrl)
            if (!cookies.isNullOrBlank()) {
                builder.header("Cookie", cookies)
                modified = true
            }
        }

        if (request.url.encodedPath.startsWith("/_c/")) {
            builder.header("X-Requested-With", "XMLHttpRequest")
            builder.header("Accept", "application/json")
            modified = true
        }

        if (modified) {
            chain.proceed(builder.build())
        } else {
            chain.proceed(request)
        }
    }

    private fun isLoginRequired(text: String): Boolean =
        text.contains("\"code\":\"login_required\"") ||
        text.contains("Login required", ignoreCase = true) ||
        text.contains("Yêu cầu đăng nhập", ignoreCase = true)

    private suspend fun fetchJson(url: String): String {
        val text = client.get(url, ensureSuccess = false).use { it.body.string() }
        if (isLoginRequired(text)) {
            throw Exception("Truyện này cần đăng nhập webview bằng tài khoản phù hợp để xem")
        }
        return text
    }

    // ============================== Popular ===============================

    override suspend fun getPopularManga(page: Int): MangasPage = fetchMangaList(page, query = "", filters = FilterList(SortFilter().apply { state = 3 }))

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): MangasPage = fetchMangaList(page, query = "", filters = FilterList(SortFilter().apply { state = 0 }))

    // =============================== Search ===============================

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage = fetchMangaList(page, query, filters)

    private suspend fun fetchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val sort = filters.firstInstanceOrNull<SortFilter>()?.toUriPart() ?: "-updated_at"
        val status = filters.firstInstanceOrNull<StatusFilter>()?.toUriPart().orEmpty()
        val searchType = filters.firstInstanceOrNull<SearchTypeFilter>()?.toUriPart() ?: "name"
        val minRating = filters.firstInstanceOrNull<MinRatingFilter>()?.toUriPart().orEmpty()
        val genres = filters.firstInstanceOrNull<GenreFilter>()?.state.orEmpty()

        val acceptGenres = genres
            .filter { it.state == Filter.TriState.STATE_INCLUDE }
            .joinToString(",") { it.id.toString() }
        val rejectGenres = genres
            .filter { it.state == Filter.TriState.STATE_EXCLUDE }
            .joinToString(",") { it.id.toString() }

        val url = "$api/mangas".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", "24")
            .addQueryParameter("sort", sort)
            .addQueryParameter("include", "genres,artist,latest_chapter")
            .apply {
                if (query.isNotBlank()) {
                    addQueryParameter("filter[$searchType]", query)
                }
                if (status.isNotEmpty()) {
                    addQueryParameter("filter[status]", status)
                }
                if (acceptGenres.isNotEmpty()) {
                    addQueryParameter("filter[accept_genres]", acceptGenres)
                }
                if (rejectGenres.isNotEmpty()) {
                    addQueryParameter("filter[reject_genres]", rejectGenres)
                }
                if (minRating.isNotEmpty()) {
                    addQueryParameter("filter[min_rating]", minRating)
                }
            }
            .build()

        return client.get(url).parseAs<ListResponse>().toMangasPage()
    }

    private fun ListResponse.toMangasPage(): MangasPage {
        val pagination = meta?.pagination
        val hasNextPage = pagination != null && pagination.currentPage < pagination.lastPage
        return MangasPage(data.map { it.toSManga() }, hasNextPage)
    }

    // =============================== Details ==============================

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        if (url.pathSegments.firstOrNull() != "truyen") return null
        val slug = url.pathSegments.getOrNull(1) ?: return null

        return fetchMangaDetails(slug)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val slug = manga.url.trimStart('/').substringAfterLast('/')

        return coroutineScope {
            val detailsDeferred = async {
                if (!fetchDetails) return@async manga
                fetchMangaDetails(slug).apply {
                    this.url = manga.url
                }
            }
            val chaptersDeferred = async {
                if (fetchChapters) fetchChapterList(slug) else chapters
            }

            SMangaUpdate(
                manga = detailsDeferred.await(),
                chapters = chaptersDeferred.await(),
            )
        }
    }

    private suspend fun fetchMangaDetails(slug: String): SManga {
        val apiUrl = "$api/mangas/$slug?include=artist,author,group,genres"
        val responseText = client.get(apiUrl, ensureSuccess = false).use { it.body.string() }
        if (!isLoginRequired(responseText)) {
            val dto = responseText.parseAs<DetailResponse>().data
            return dto.toSMangaDetails().apply {
                memo = buildJsonObject {
                    dto.group?.slug?.let { put("group_slug", it) }
                    dto.author?.slug?.let { put("author_slug", it) }
                    dto.artist?.slug?.let { put("artist_slug", it) }
                    dto.genres.firstOrNull()?.slug?.let { put("genre_slug", it) }
                }
            }
        }
        return fetchMangaDetailsFromHtml(slug)
    }

    private suspend fun fetchMangaDetailsFromHtml(slug: String): SManga {
        val url = "$baseUrl/truyen/$slug"
        val response = client.get(url, ensureSuccess = false)
        val text = response.use { it.body.string() }
        if (response.code == 403 || isLoginRequired(text)) {
            throw Exception("Truyện này cần đăng nhập webview bằng tài khoản phù hợp để xem")
        }
        val document = Jsoup.parse(text, url)
        return SManga.create().apply {
            this.url = "/truyen/$slug"
            title = document.selectFirst("h1.md-title")?.text()?.trim()
                ?: document.selectFirst("h1")?.text()?.trim()
                ?: slug
            thumbnail_url = document.selectFirst(".md-cover img")?.absUrl("src")?.ifEmpty { null }
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.ifEmpty { null }
            val synopsisEl = document.selectFirst(".md-synopsis")
            synopsisEl?.select("button, dialog")?.remove()
            description = synopsisEl?.text()?.trim()?.ifEmpty { null }
            author = document.select(".md-rail dt:contains(Tác giả) + dd a, .md-rail dt:contains(Tác giả) + dd span")
                .joinToString { it.text().trim() }.ifEmpty { null }
            artist = document.select(".md-rail dt:contains(Họa sĩ) + dd a, .md-rail dt:contains(Họa sĩ) + dd span")
                .joinToString { it.text().trim() }.ifEmpty { null }
            genre = document.select(".md-rail-genres a.md-chip")
                .joinToString { it.text().trim() }.ifEmpty { null }
            status = when {
                document.selectFirst(".md-badge-done") != null -> SManga.COMPLETED
                document.selectFirst(".md-badge")?.text()?.contains("hoàn thành", ignoreCase = true) == true -> SManga.COMPLETED
                else -> SManga.ONGOING
            }
        }
    }

    private suspend fun fetchChapterList(mangaSlug: String): List<SChapter> {
        val apiUrl = "$api/mangas/$mangaSlug/chapters".toHttpUrl().newBuilder()
            .addQueryParameter("page", "1")
            .addQueryParameter("per_page", "2000")
            .addQueryParameter("sort", "desc")
            .build()
            .toString()

        val firstText = client.get(apiUrl, ensureSuccess = false).use { it.body.string() }
        if (isLoginRequired(firstText)) {
            return fetchChapterListFromHtml(mangaSlug)
        }

        val result = mutableListOf<SChapter>()
        var page = 1
        var lastPage = 1
        var text = firstText

        do {
            if (page > 1) {
                text = fetchJson(
                    "$api/mangas/$mangaSlug/chapters".toHttpUrl().newBuilder()
                        .addQueryParameter("page", page.toString())
                        .addQueryParameter("per_page", "2000")
                        .addQueryParameter("sort", "desc")
                        .build()
                        .toString(),
                )
            }
            val response = text.parseAs<ChapterListResponse>()
            result += response.data.map { it.toSChapter(mangaSlug) }
            lastPage = response.meta?.pagination?.lastPage ?: 1
            page++
        } while (page <= lastPage)

        return result
    }

    private suspend fun fetchChapterListFromHtml(mangaSlug: String): List<SChapter> {
        val url = "$baseUrl/truyen/$mangaSlug"
        val response = client.get(url, ensureSuccess = false)
        val text = response.use { it.body.string() }
        if (response.code == 403 || isLoginRequired(text)) {
            throw Exception("Truyện này cần đăng nhập webview bằng tài khoản phù hợp để xem")
        }
        val document = Jsoup.parse(text, url)
        val chapterLinks = document.select("a.md-ch")
        if (chapterLinks.isEmpty() && isLoginRequired(document.text())) {
            throw Exception("Truyện này cần đăng nhập webview bằng tài khoản phù hợp để xem")
        }
        return chapterLinks.map { a ->
            SChapter.create().apply {
                this.url = a.absUrl("href").toHttpUrl().encodedPath
                name = a.selectFirst(".md-ch-title")?.text()?.trim() ?: a.text().trim()
                date_upload = parseRelativeDate(a.selectFirst(".md-ch-meta span")?.text()?.trim())
            }
        }
    }

    private val dateNumberRegex = Regex("""\d+""")

    private fun parseRelativeDate(dateText: String?): Long {
        if (dateText.isNullOrBlank()) return 0L
        val lower = dateText.lowercase()
        if ("vừa xong" in lower || "vừa đăng" in lower) return Clock.System.now().toEpochMilliseconds()

        val amount = dateNumberRegex.find(lower)?.value?.toLongOrNull() ?: return 0L
        val duration = when {
            "giây" in lower -> amount.seconds
            "phút" in lower -> amount.minutes
            "giờ" in lower -> amount.hours
            "ngày" in lower -> amount.days
            "tuần" in lower -> (amount * 7).days
            "tháng" in lower -> (amount * 30).days
            "năm" in lower -> (amount * 365).days
            else -> return 0L
        }

        return (Clock.System.now() - duration).toEpochMilliseconds()
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${chapter.url}"

    // =============================== Related ==============================

    override val supportsRelatedMangas get() = true

    override suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> {
        val slug = manga.url.trimStart('/').substringAfterLast('/')
        val sources = listOfNotNull(
            manga.memo["group_slug"]?.stringOrNull?.let { "groups" to it },
            manga.memo["author_slug"]?.stringOrNull?.let { "authors" to it },
            manga.memo["artist_slug"]?.stringOrNull?.let { "artists" to it },
            manga.memo["genre_slug"]?.stringOrNull?.let { "genres" to it },
        ).ifEmpty {
            val detail = fetchJson("$api/mangas/$slug?include=artist,author,group,genres")
                .parseAs<DetailResponse>()
                .data
            listOfNotNull(
                detail.group?.slug?.let { "groups" to it },
                detail.author?.slug?.let { "authors" to it },
                detail.artist?.slug?.let { "artists" to it },
                detail.genres.firstOrNull()?.slug?.let { "genres" to it },
            )
        }

        for ((type, taxonomySlug) in sources) {
            val list = client.get("$api/$type/$taxonomySlug/mangas?per_page=12")
                .parseAs<ListResponse>()
                .data
            val related = list.filter { it.slug != slug }.map { it.toSManga() }
            if (related.isNotEmpty()) return related.take(12)
        }
        return emptyList()
    }

    // =============================== Pages ================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val segments = chapter.url.trimStart('/').split('/')
        val mangaSlug = segments.getOrNull(1)
        val chapterSlug = segments.getOrNull(2)
        if (mangaSlug.isNullOrEmpty() || chapterSlug.isNullOrEmpty()) {
            throw Exception("Invalid chapter url: ${chapter.url}")
        }

        val path = "$mangaSlug/$chapterSlug"
        PagesCrypto.ensureLoaded(client, baseUrl, preferences)
        val token = PagesCrypto.token(mangaSlug, chapterSlug)
        val response = fetchPagesJson(mangaSlug, chapterSlug, token)
            .parseAs<PagesResponse>()

        val payload = PagesCrypto.decryptPages(response.encrypted, token, path)
        return payload.pages.mapIndexedNotNull { index, src ->
            if (src.isBlank()) return@mapIndexedNotNull null
            val key = payload.scrambleKeys?.getOrNull(index)?.takeIf { it.isNotEmpty() }
            val imageUrl = if (key != null) "$src#$key" else src
            Page(index, url = imageUrl, imageUrl = imageUrl)
        }
    }

    private suspend fun fetchPagesJson(mangaSlug: String, chapterSlug: String, token: String): String {
        val cUrl = "$baseUrl/_c/mangas/$mangaSlug/chapters/$chapterSlug/pages?_=$token"
        val text = client.get(cUrl, ensureSuccess = false).use { it.body.string() }
        if (text.contains("\"e\":")) {
            return text
        }
        if (isLoginRequired(text)) {
            throw Exception("Truyện này cần đăng nhập webview bằng tài khoản phù hợp để xem")
        }
        val fallbackUrl = "$api/mangas/$mangaSlug/chapters/$chapterSlug/pages?_=$token"
        val fallbackText = client.get(fallbackUrl, ensureSuccess = false).use { it.body.string() }
        if (isLoginRequired(fallbackText)) {
            throw Exception("Truyện này cần đăng nhập webview bằng tài khoản phù hợp để xem")
        }
        return fallbackText
    }
            val key = payload.scrambleKeys?.getOrNull(index)?.takeIf { it.isNotEmpty() }
            val imageUrl = if (key != null) "$src#$key" else src
            Page(index, url = imageUrl, imageUrl = imageUrl)
        }
    }

    // ============================== Filters ===============================

    override val supportsFilterFetching get() = true

    override suspend fun fetchFilterData(): JsonElement {
        val genres = mutableListOf<GenreOption>()
        var page = 1
        var lastPage = 1

        do {
            val response = client.get("$api/genres?per_page=100&page=$page")
                .parseAs<GenreListResponse>()
            genres += response.data
            lastPage = response.meta?.pagination?.lastPage ?: 1
            page++
        } while (page <= lastPage)

        return genres.toJsonElement()
    }

    override fun getFilterList(data: JsonElement?): FilterList = getFilters(data?.parseAs<List<GenreOption>>())
}
