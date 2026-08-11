package eu.kanade.tachiyomi.extension.vi.hv2tcomics

import com.github.penfeizhou.animation.avif.decode.AVIFDecoder
import com.github.penfeizhou.animation.awebpencoder.WebPEncoder
import com.github.penfeizhou.animation.io.ByteBufferReader
import com.github.penfeizhou.animation.loader.Loader
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.time.Duration.Companion.seconds

@Source
abstract class HV2TComics : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        addInterceptor(authInterceptor())
        addInterceptor(avifToWebpInterceptor())
        rateLimit(3)
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

    // ============================== AVIF to Animated WebP ==============================

    /**
     * Intercepts AVIF image requests and converts them to animated WebP format.
     * Uses penfeizhou's APNG4Android library for decoding AVIF and encoding animated WebP.
     */
    private fun avifToWebpInterceptor() = Interceptor { chain ->
        val request = chain.request()
        val url = request.url.toString()

        if (!url.endsWith(".avif", ignoreCase = true)) {
            return@Interceptor chain.proceed(request)
        }

        val response = chain.proceed(request)
        if (!response.isSuccessful) {
            return@Interceptor response
        }

        response.body.use { body ->
            val bytes = body.bytes()
            try {
                val webpBytes = convertAvifToAnimatedWebp(bytes)
                response.newBuilder()
                    .body(webpBytes.toResponseBody("image/webp".toMediaType()))
                    .build()
            } catch (_: Exception) {
                response.newBuilder()
                    .body(bytes.toResponseBody("image/webp".toMediaType()))
                    .build()
            }
        }
    }

    private fun convertAvifToAnimatedWebp(avifBytes: ByteArray): ByteArray {
        val loader = ByteArrayLoader(avifBytes)
        val avifDecoder = AVIFDecoder(loader, null)

        // Initialize the decoder by getting bounds (triggers native decoder creation)
        avifDecoder.getBounds()

        val frameCount = avifDecoder.getFrameCount()
        if (frameCount <= 0) {
            throw IOException("No frames found in AVIF")
        }

        val webpEncoder = WebPEncoder()
        webpEncoder.loopCount(0) // 0 = infinite loop

        for (i in 0 until frameCount) {
            val bitmap = avifDecoder.getFrameBitmap(i)
            val frame = avifDecoder.getFrame(i)
            webpEncoder.addFrame(bitmap, 0, 0, frame.frameDuration.toInt())
        }

        avifDecoder.release()
        return webpEncoder.build()
    }

    private class ByteArrayLoader(private val bytes: ByteArray) : Loader {
        override fun obtain(): com.github.penfeizhou.animation.io.Reader = ByteBufferReader(ByteBuffer.wrap(bytes))
    }

    // ============================== Popular ===============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$baseUrl/api/comics".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("sort", "popular")
            .build()
        return client.get(url).parseAs<ComicListResponse>().toMangasPage()
    }

    // ============================== Latest ================================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
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
            runWebView<List<String>>(timeout = 30.seconds) {
                loadWithOverviewMode = true
                useWideViewPort = true
                userAgent = headers["User-Agent"]!!

                val capturedUrls = java.util.concurrent.CopyOnWriteArrayList<String>()
                var lastCount = 0
                var stablePolls = 0

                interceptRequest { request ->
                    val url = request.url.toString()
                    if (url.contains("/media/") && (url.endsWith(".webp") || url.endsWith(".jpg") || url.endsWith(".png"))) {
                        capturedUrls.add(url)
                    }
                    null
                }

                poll(1.seconds) {
                    evaluateJs(
                        """
                        (function() {
                            var images = document.querySelectorAll('img[alt^="Page"]');
                            images.forEach(function(img) {
                                img.loading = 'eager';
                                img.removeAttribute('loading');
                            });
                            window.scrollTo(0, document.body.scrollHeight);
                        })()
                        """.trimIndent(),
                    )
                    val currentCount = capturedUrls.size
                    if (currentCount > 0) {
                        if (currentCount == lastCount) {
                            stablePolls++
                        } else {
                            lastCount = currentCount
                            stablePolls = 0
                        }
                        if (stablePolls >= 3) {
                            resolve(capturedUrls.distinct())
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
