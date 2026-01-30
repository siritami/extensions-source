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

    override val client = network.cloudflareClient.newBuilder()
        .rateLimit(3)
        .addInterceptor(::imageInterceptor)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ============================== Image Descrambling ======================================

    // MiMi uses fixed block size of 426x240
    private val blockWidth = 426
    private val blockHeight = 240

    private fun imageInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        // Check if this is a descramble request (marked with fragment)
        val fragment = request.url.fragment ?: return response

        if (!fragment.startsWith("mimi_drm:")) {
            return response
        }

        // Parse the page index and DRM key from fragment
        // Format: mimi_drm:pageIndex:drmHex
        val params = fragment.removePrefix("mimi_drm:").split(":", limit = 2)
        if (params.size < 2) return response

        val pageIndex = params[0].toIntOrNull() ?: 0
        val drmHex = params[1]

        // Read the image bytes
        val imageBytes = response.body.bytes()
        val scrambledBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: return response.newBuilder()
                .body(imageBytes.toResponseBody("image/jpeg".toMediaType()))
                .build()

        // Descramble the image
        val descrambledBitmap = descrambleImage(scrambledBitmap, drmHex, pageIndex)

        // Convert back to bytes
        val outputStream = ByteArrayOutputStream()
        descrambledBitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
        val descrambledBytes = outputStream.toByteArray()
        descrambledBitmap.recycle()
        scrambledBitmap.recycle()

        return response.newBuilder()
            .body(descrambledBytes.toResponseBody("image/jpeg".toMediaType()))
            .build()
    }

    /**
     * Descramble an image using the DRM key and page index.
     * The image is divided into a grid of 426x240 blocks which are then rearranged.
     */
    private fun descrambleImage(scrambled: Bitmap, drmHex: String, pageIndex: Int): Bitmap {
        val width = scrambled.width
        val height = scrambled.height

        // Calculate grid dimensions
        val cols = (width + blockWidth - 1) / blockWidth
        val rows = (height + blockHeight - 1) / blockHeight
        val totalBlocks = cols * rows

        // Generate permutation from DRM key and page index
        val permutation = generatePermutation(drmHex, pageIndex, totalBlocks)

        // Create output bitmap
        val descrambled = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(descrambled)

        // The permutation maps: source block srcIdx goes to destination permutation[srcIdx]
        // We need the inverse: for each destination, which source block?
        val inversePermutation = IntArray(totalBlocks)
        for (srcIdx in 0 until totalBlocks) {
            val destIdx = permutation[srcIdx]
            if (destIdx in 0 until totalBlocks) {
                inversePermutation[destIdx] = srcIdx
            }
        }

        // Rearrange blocks - destination gets content from source
        for (destIdx in 0 until totalBlocks) {
            val srcIdx = inversePermutation[destIdx]

            val srcCol = srcIdx % cols
            val srcRow = srcIdx / cols
            val destCol = destIdx % cols
            val destRow = destIdx / cols

            // Calculate actual block dimensions (handle edge blocks)
            val srcX = srcCol * blockWidth
            val srcY = srcRow * blockHeight
            val destX = destCol * blockWidth
            val destY = destRow * blockHeight

            val actualWidth = minOf(blockWidth, width - srcX)
            val actualHeight = minOf(blockHeight, height - srcY)

            val srcRect = Rect(srcX, srcY, srcX + actualWidth, srcY + actualHeight)
            val destRect = Rect(destX, destY, destX + actualWidth, destY + actualHeight)

            canvas.drawBitmap(scrambled, srcRect, destRect, null)
        }

        return descrambled
    }

    /**
     * Generate a permutation array from the DRM hex string and page index.
     * Returns an array where result[srcIdx] = destIdx (source block goes to destination)
     *
     * The algorithm incorporates the page index into the seed since the same DRM key
     * produces different permutations on different pages.
     */
    private fun generatePermutation(drmHex: String, pageIndex: Int, totalBlocks: Int): IntArray {
        // Convert hex to bytes
        val drmBytes = drmHex.chunked(2).mapNotNull {
            try {
                it.toInt(16).toByte()
            } catch (e: Exception) {
                null
            }
        }.toByteArray()

        if (drmBytes.isEmpty()) {
            return IntArray(totalBlocks) { it }
        }

        // Seed derivation: combine DRM bytes with page index
        // Try using first 8 bytes XOR'ed with page index
        var seed = 0L
        for (i in 0 until minOf(8, drmBytes.size)) {
            seed = seed xor ((drmBytes[i].toLong() and 0xFFL) shl (i * 8))
        }
        // Incorporate page index into seed
        seed = seed xor ((pageIndex.toLong() and 0xFFFFL) shl 48)
        seed = seed xor (pageIndex.toLong() * 0x5851F42DL)

        // Use Fisher-Yates shuffle with xorshift64 PRNG (common in Rust)
        val indices = (0 until totalBlocks).toMutableList()
        var state = if (seed != 0L) seed else 0x748FEA9BL

        for (i in (totalBlocks - 1) downTo 1) {
            // xorshift64
            state = state xor (state shl 13)
            state = state xor (state ushr 7)
            state = state xor (state shl 17)

            val j = ((state and Long.MAX_VALUE) % (i + 1)).toInt()
            val temp = indices[i]
            indices[i] = indices[j]
            indices[j] = temp
        }

        return indices.toIntArray()
    }

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
        val result = response.parseAs<ChapterResponse>()

        return result.pages.mapIndexed { index, page ->
            val imageUrl = if (page.drm != null && page.imageUrl.contains("scrambled")) {
                // Add page index and DRM key as URL fragment for the interceptor
                "${page.imageUrl}#mimi_drm:$index:${page.drm}"
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
