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
     * XorShift32 pseudo-random number generator.
     * Used to generate deterministic permutations from a seed.
     */
    private fun xorshift32(seed: UInt): UInt {
        var n = seed
        n = n xor (n shl 13)
        n = n xor (n shr 17)
        n = n xor (n shl 5)
        return n
    }

    /**
     * Generate tile permutation from seed.
     * Uses xorshift32 to create a deterministic shuffle.
     * Returns array where result[srcIdx] = destIdx
     */
    private fun generatePermutation(seed: Long, gridSize: Int = 3): IntArray {
        val totalTiles = gridSize * gridSize
        var seed32 = seed.toUInt()
        val pairs = mutableListOf<Pair<UInt, Int>>()

        for (i in 0 until totalTiles) {
            seed32 = xorshift32(seed32)
            pairs.add(seed32 to i)
        }

        pairs.sortBy { it.first }
        
        // Create permutation: permutation[srcIdx] = destIdx
        val permutation = IntArray(totalTiles)
        pairs.forEachIndexed { destIdx, (_, srcIdx) ->
            permutation[srcIdx] = destIdx
        }
        
        return permutation
    }

    /**
     * Extract a seed from the DRM key bytes and page index.
     * The algorithm combines the DRM key with the page index to produce unique seeds per page.
     */
    private fun extractSeed(drmKey: ByteArray, pageIndex: Int): Long {
        if (drmKey.size < 8) return pageIndex.toLong()

        // Combine DRM bytes with page index
        // Use first 4 bytes as base, XOR with page index, then use next 4 bytes
        var seed = 0L
        
        // First 4 bytes as lower 32 bits
        for (i in 0 until 4) {
            seed = seed or ((drmKey[i].toLong() and 0xFF) shl (i * 8))
        }
        
        // XOR with page index
        seed = seed xor (pageIndex.toLong() shl 16)
        
        // Next 4 bytes as upper bits, also mixed with page index
        for (i in 4 until 8) {
            seed = seed or (((drmKey[i].toLong() and 0xFF) xor pageIndex.toLong()) shl (i * 8))
        }
        
        return seed
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

        // Generate permutation from seed
        val seed = extractSeed(drmKey, pageIndex)
        val permutation = generatePermutation(seed, cols)

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
