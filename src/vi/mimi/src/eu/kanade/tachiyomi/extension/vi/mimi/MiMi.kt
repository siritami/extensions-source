package eu.kanade.tachiyomi.extension.vi.mimi

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.getPreferences
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import uy.kohesive.injekt.injectLazy
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Locale

class MiMi : HttpSource(), ConfigurableSource {

    override val name = "MiMi"

    private val defaultBaseUrl = "https://mimimoe.moe"

    override val baseUrl by lazy { getPrefBaseUrl() }

    private val apiUrl get() = baseUrl.replace("://", "://api.") + "/api/v2"

    override val lang = "vi"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences = getPreferences()

    private val imageDescrambler: Interceptor = Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)

        val fragment = request.url.fragment
        if (fragment.isNullOrEmpty()) {
            return@Interceptor response
        }

        val urlString = request.url.toString()
        if (!urlString.contains("/scrambled/")) {
            return@Interceptor response
        }

        try {
            // Fragment format: "pageIndex:drmHexString"
            val parts = fragment.split(":", limit = 2)
            if (parts.size != 2) {
                return@Interceptor response
            }

            val pageIndex = parts[0].toIntOrNull() ?: 0
            val drmHex = parts[1]
            val hashBytes = drmHex.decodeHex()

            val scrambledImg = BitmapFactory.decodeStream(response.body.byteStream())
                ?: return@Interceptor response

            val descrambledImg = descrambleImage(scrambledImg, hashBytes, pageIndex)

            val output = ByteArrayOutputStream()
            descrambledImg.compress(Bitmap.CompressFormat.JPEG, 90, output)
            scrambledImg.recycle()
            descrambledImg.recycle()

            val body = output.toByteArray().toResponseBody("image/jpeg".toMediaType())
            response.newBuilder()
                .body(body)
                .build()
        } catch (e: Exception) {
            response
        }
    }

    override val client = network.cloudflareClient.newBuilder()
        .rateLimit(3)
        .addInterceptor(imageDescrambler)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ============================== Popular ======================================

    override fun popularMangaRequest(page: Int): Request {
        val url = "$apiUrl/manga/advance-search".toHttpUrl().newBuilder()
            .addQueryParameter("sort", "views")
            .addQueryParameter("page", (page - 1).toString())
            .addQueryParameter("max", "18")
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val result = response.parseAs<SearchResponse>()
        val mangas = result.data.map { it.toSManga() }
        val hasNextPage = result.currentPage < result.totalPage - 1
        return MangasPage(mangas, hasNextPage)
    }

    // ============================== Latest ======================================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiUrl/manga/advance-search".toHttpUrl().newBuilder()
            .addQueryParameter("sort", "updated_at")
            .addQueryParameter("page", (page - 1).toString())
            .addQueryParameter("max", "18")
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    // ============================== Search ======================================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$apiUrl/manga/advance-search".toHttpUrl().newBuilder()
            .addQueryParameter("sort", "updated_at")
            .addQueryParameter("page", (page - 1).toString())
            .addQueryParameter("max", "18")

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

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    // ============================== Filters ======================================

    override fun getFilterList(): FilterList = getFilters()

    // ============================== Details ======================================

    override fun mangaDetailsRequest(manga: SManga): Request {
        val mangaId = manga.url.substringAfterLast("/")
        return GET("$apiUrl/manga/info/$mangaId", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val result = response.parseAs<MangaInfo>()
        return result.toSManga()
    }

    override fun getMangaUrl(manga: SManga): String {
        return "$baseUrl${manga.url}"
    }

    // ============================== Chapters ======================================

    override fun chapterListRequest(manga: SManga): Request {
        val mangaId = manga.url.substringAfterLast("/")
        return GET("$apiUrl/manga/gallery/$mangaId", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val result = response.parseAs<List<ChapterDto>>()
        return result.mapIndexed { index, chapter ->
            SChapter.create().apply {
                url = "/chapter/${chapter.id}"
                name = chapter.title ?: "Chapter ${result.size - index}"
                chapter_number = (result.size - index).toFloat()
                date_upload = chapter.uploadDate?.let { parseDate(it) } ?: 0L
            }
        }
    }

    private fun parseDate(dateString: String): Long {
        return try {
            dateFormat.parse(dateString)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private val dateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)
    }

    override fun getChapterUrl(chapter: SChapter): String {
        return "$baseUrl${chapter.url}"
    }

    // ============================== Pages ======================================

    override fun pageListRequest(chapter: SChapter): Request {
        val chapterId = chapter.url.substringAfterLast("/")
        return GET("$apiUrl/manga/chapter?id=$chapterId", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val result = response.parseAs<ChapterPages>()
        return result.pages.mapIndexed { index, page ->
            val imageUrl = if (!page.drm.isNullOrEmpty()) {
                // Include page index in fragment for descrambling: "pageIndex:drmHex"
                "${page.imageUrl}#$index:${page.drm}"
            } else {
                page.imageUrl
            }
            Page(index, imageUrl = imageUrl)
        }
    }

    override fun imageUrlParse(response: Response): String {
        throw UnsupportedOperationException()
    }

    // ============================== Helpers ======================================

    private inline fun <reified T> Response.parseAs(): T {
        return json.decodeFromString<T>(body.string())
    }

    private fun String.decodeHex(): ByteArray {
        check(length % 2 == 0) { "Must have an even length" }
        return chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }

    /**
     * Extract permutation using Fisher-Yates shuffle seeded by DRM key bytes.
     * The DRM key bytes are used as a sequence of random values to drive the shuffle.
     *
     * Analysis shows:
     * - DRM byte 0: version/type indicator (0x41, 0x42, 0x44, 0x47)
     * - Remaining bytes: used to seed/drive the permutation algorithm
     * - Page index may also affect the permutation
     */
    private fun extractPermutation(drmKey: ByteArray, pageIndex: Int): IntArray {
        // Start with identity permutation [0, 1, 2, 3, 4, 5, 6, 7, 8]
        val permutation = IntArray(9) { it }

        if (drmKey.size < 20) {
            return permutation
        }

        // Use Fisher-Yates shuffle with DRM bytes as the "random" sequence
        // The DRM key combined with page index determines the shuffle
        val shuffleBytes = ByteArray(9)
        for (i in 0 until 9) {
            // Use bytes from position 5+i, XOR with page index for variation
            val byte = drmKey[5 + i].toInt() and 0xFF
            shuffleBytes[i] = ((byte xor pageIndex) and 0xFF).toByte()
        }

        // Fisher-Yates shuffle using shuffleBytes as the random source
        for (i in 8 downTo 1) {
            // Use the shuffle byte to determine swap index
            val j = (shuffleBytes[8 - i].toInt() and 0xFF) % (i + 1)
            // Swap permutation[i] and permutation[j]
            val temp = permutation[i]
            permutation[i] = permutation[j]
            permutation[j] = temp
        }

        return permutation
    }

    /**
     * Descramble an image that has been split into a 3x3 grid of tiles.
     * The DRM key combined with page index determines the tile permutation.
     */
    private fun descrambleImage(scrambledImg: Bitmap, drmKey: ByteArray, pageIndex: Int): Bitmap {
        val width = scrambledImg.width
        val height = scrambledImg.height

        // Grid is always 3x3 (9 tiles)
        val cols = 3
        val rows = 3
        val tileWidth = width / cols
        val tileHeight = height / rows

        val descrambledImg = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(descrambledImg)

        // Extract permutation directly from DRM key and page index
        val permutation = extractPermutation(drmKey, pageIndex)

        // permutation[srcIdx] = destIdx means source tile srcIdx goes to destination destIdx
        // To descramble, we need to reverse this: for each destIdx, find which srcIdx goes there
        val inversePermutation = IntArray(9)
        for (srcIdx in 0 until 9) {
            val destIdx = permutation[srcIdx]
            inversePermutation[destIdx] = srcIdx
        }

        // Now draw tiles: for each destination position, get the source tile
        for (destIdx in 0 until 9) {
            val srcIdx = inversePermutation[destIdx]

            val srcCol = srcIdx % cols
            val srcRow = srcIdx / cols
            val destCol = destIdx % cols
            val destRow = destIdx / cols

            val srcRect = Rect(
                srcCol * tileWidth,
                srcRow * tileHeight,
                (srcCol + 1) * tileWidth,
                (srcRow + 1) * tileHeight,
            )
            val destRect = Rect(
                destCol * tileWidth,
                destRow * tileHeight,
                (destCol + 1) * tileWidth,
                (destRow + 1) * tileHeight,
            )
            canvas.drawBitmap(scrambledImg, srcRect, destRect, null)
        }

        return descrambledImg
    }

    // ============================== Preferences ======================================

    init {
        preferences.getString(DEFAULT_BASE_URL_PREF, null).let { prefDefaultBaseUrl ->
            if (prefDefaultBaseUrl != defaultBaseUrl) {
                preferences.edit()
                    .putString(BASE_URL_PREF, defaultBaseUrl)
                    .putString(DEFAULT_BASE_URL_PREF, defaultBaseUrl)
                    .apply()
            }
        }
    }

    private fun getPrefBaseUrl(): String = preferences.getString(BASE_URL_PREF, defaultBaseUrl)!!

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF
            title = BASE_URL_PREF_TITLE
            summary = BASE_URL_PREF_SUMMARY
            setDefaultValue(defaultBaseUrl)
            dialogTitle = BASE_URL_PREF_TITLE
            dialogMessage = "Default: $defaultBaseUrl"
            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(screen.context, RESTART_APP, Toast.LENGTH_LONG).show()
                true
            }
        }.let(screen::addPreference)
    }

    companion object {
        private const val DEFAULT_BASE_URL_PREF = "defaultBaseUrl"
        private const val RESTART_APP = "Khởi chạy lại ứng dụng để áp dụng thay đổi."
        private const val BASE_URL_PREF_TITLE = "Ghi đè URL cơ sở"
        private const val BASE_URL_PREF = "overrideBaseUrl"
        private const val BASE_URL_PREF_SUMMARY =
            "Dành cho sử dụng tạm thời, cập nhật tiện ích sẽ xóa cài đặt."
    }
}
