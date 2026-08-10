package eu.kanade.tachiyomi.extension.vi.hv2tcomics

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
import keiyoushi.utils.WebViewTimeoutException
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getLocalStorage
import keiyoushi.utils.parseAs
import keiyoushi.utils.runWebView
import keiyoushi.utils.toJsonElement
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.time.Duration.Companion.seconds

@Source
abstract class HV2TComics : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        addInterceptor(authInterceptor())
        addInterceptor(imageInterceptor())
        rateLimit(3)
    }

    // ============================== Image Interceptor ==============================

    private fun imageInterceptor() = Interceptor { chain ->
        val request = chain.request()
        val host = request.url.host
        if (host == "cdn.hv2tcomics.net" || host == "cdn.hv2t.com") {
            val newRequest = request.newBuilder().apply {
                header("Accept", "image/avif,image/jxl,image/webp,image/png,image/svg+xml,image/*;q=0.8,*/*;q=0.5")
                header("Referer", "$baseUrl/")
                header("Origin", baseUrl)
                header("Sec-Fetch-Dest", "image")
                header("Sec-Fetch-Mode", "no-cors")
                header("Sec-Fetch-Site", "same-site")
            }.build()
            chain.proceed(newRequest)
        } else {
            chain.proceed(request)
        }
    }

    // ================================ Auth ================================

    private var cachedAuthToken: String? = null
    private var authChecked = false

    private suspend fun loadAuthToken() {
        if (authChecked) return
        authChecked = true
        cachedAuthToken = readAuthToken()
    }

    private suspend fun readAuthToken(): String? = getLocalStorage(baseUrl, "auth_token")
        ?.takeIf { it.isNotBlank() }

    private fun authInterceptor() = Interceptor { chain ->
        val original = chain.request()
        val request = original.newBuilder().apply {
            if (original.url.host == baseUrl.toHttpUrl().host) {
                cachedAuthToken?.let { header("Authorization", "Bearer $it") }
            }
        }.build()
        chain.proceed(request)
    }

    // ============================== Popular ===============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        loadAuthToken()
        val url = "$baseUrl/api/comics".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("sort", "popular")
            .build()
        return client.get(url).parseAs<ComicListResponse>().toMangasPage()
    }

    // ============================== Latest ================================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        loadAuthToken()
        val url = "$baseUrl/api/comics".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("sort", "latest")
            .build()
        return client.get(url).parseAs<ComicListResponse>().toMangasPage()
    }

    // ============================== Search ================================

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        loadAuthToken()
        val genreFilter = filters.firstInstanceOrNull<GenreFilter>()
        val translatorFilter = filters.firstInstanceOrNull<TranslatorFilter>()
        val genreState = genreFilter?.selectedSlugs()
        val translatorState = translatorFilter?.selectedNames()

        val urlBuilder = "$baseUrl/api/comics".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("sort", "latest")

        if (query.isNotBlank()) {
            urlBuilder.addQueryParameter("q", query)
        }

        genreState?.include?.let { slugs ->
            if (slugs.isNotEmpty()) urlBuilder.addQueryParameter("tags_inc", slugs.joinToString(","))
        }
        genreState?.exclude?.let { slugs ->
            if (slugs.isNotEmpty()) urlBuilder.addQueryParameter("tags_exc", slugs.joinToString(","))
        }
        translatorState?.include?.let { names ->
            if (names.isNotEmpty()) urlBuilder.addQueryParameter("translator_names_inc", names.joinToString(","))
        }
        translatorState?.exclude?.let { names ->
            if (names.isNotEmpty()) urlBuilder.addQueryParameter("translator_names_exc", names.joinToString(","))
        }

        return client.get(urlBuilder.build()).parseAs<ComicListResponse>().toMangasPage()
    }

    // ============================== Details ===============================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        loadAuthToken()
        val updatedManga = async {
            if (fetchDetails) {
                val url = "$baseUrl/api/comics/${manga.url}".toHttpUrl()
                client.get(url).parseAs<ComicDetailResponse>().data.toSManga()
            } else {
                manga
            }
        }
        val updatedChapters = async {
            if (fetchChapters) {
                val url = "$baseUrl/api/comics/${manga.url}".toHttpUrl()
                client.get(url).parseAs<ComicDetailResponse>().data.chapters.map {
                    it.toSChapter(manga.url)
                }
            } else {
                chapters
            }
        }
        SMangaUpdate(updatedManga.await(), updatedChapters.await())
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/truyen/${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/truyen/${chapter.url}"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val slug = url.pathSegments.getOrNull(1) ?: return null
        return client.get("$baseUrl/api/comics/$slug").parseAs<ComicDetailResponse>().data.toSManga()
    }

    // =============================== Pages ================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        loadAuthToken()
        val pageUrl = "$baseUrl/truyen/${chapter.url}"
        val imageUrls = try {
            runWebView<List<String>>(timeout = 60.seconds) {
                loadWithOverviewMode = true
                useWideViewPort = true
                userAgent = headers["User-Agent"]!!

                onPageFinished {
                    poll(1.seconds) {
                        evaluateJs(
                            """
                            (function() {
                                return Array.from(document.querySelectorAll('img[alt^="Page"]'))
                                    .map(function(img) { return img.currentSrc || img.src || ''; })
                                    .filter(function(src) { return /^https?:\\/\\//.test(src); });
                            })()
                            """.trimIndent(),
                        ) { value ->
                            val urls = runCatching { value.parseAs<List<String>>() }
                                .getOrDefault(emptyList())
                                .distinct()
                            if (urls.isNotEmpty()) resolve(urls)
                        }
                    }
                }
                loadUrl(pageUrl)
            }
        } catch (_: WebViewTimeoutException) {
            emptyList()
        }
        return imageUrls.mapIndexed { index, imageUrl ->
            Page(index, pageUrl, imageUrl)
        }
    }

    override fun imageRequest(page: Page): Request {
        val imageUrl = page.imageUrl ?: throw Exception("Không tìm thấy URL ảnh")
        return GET(imageUrl, headersBuilder()
            .add("Referer", "$baseUrl/")
            .add("Origin", baseUrl)
            .add("Accept", "image/avif,image/jxl,image/webp,image/png,image/svg+xml,image/*;q=0.8,*/*;q=0.5")
            .add("Sec-Fetch-Dest", "image")
            .add("Sec-Fetch-Mode", "no-cors")
            .add("Sec-Fetch-Site", "same-site")
            .build())
    }

    // ============================== Filters ================================

    override val supportsFilterFetching get() = true

    override suspend fun fetchFilterData(): JsonElement = coroutineScope {
        loadAuthToken()
        val tags = async { client.get("$baseUrl/api/tags").parseAs<TagResponse>().data.map { TagOption(it.id, it.name, it.slug) } }
        val translators = async {
            client.get("$baseUrl/api/translators?view=top-by-comics-v1")
                .parseAs<TranslatorResponse>()
                .data
                .map { TranslatorOption(it) }
        }

        FilterData(
            tags = tags.await(),
            translators = translators.await(),
        ).toJsonElement()
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val filterData = data?.parseAs<FilterData>()
        return getFilters(filterData?.tags, filterData?.translators)
    }

    // ============================= Utilities ==============================

    private fun ComicListResponse.toMangasPage(): MangasPage = MangasPage(
        mangas = data.map { it.toSManga() },
        hasNextPage = meta.page < meta.totalPages,
    )
}
