package eu.kanade.tachiyomi.extension.vi.tulinhtruyen

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
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
import keiyoushi.utils.toJsonRequestBody
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.concurrent.thread

@Source
class TuLinhTruyen :
    KeiSource(),
    ConfigurableSource {

    private val preferences by getPreferencesLazy()

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        rateLimit(3)
        addInterceptor { chain ->
            val request = chain.request()
            val userToken = runCatching {
                preferences.getString(PREF_USER_TOKEN, "")?.trim()
            }.getOrNull()
            val token = if (userToken.isNullOrEmpty()) DEFAULT_MANGA_TOKEN else userToken

            val newRequest = request.newBuilder()
                .header("User-Agent", USER_AGENT)
                .header("X-Manga-Token", token)
                .build()

            chain.proceed(newRequest)
        }
    }

    override fun Headers.Builder.configureHeaders(): Headers.Builder = apply {
        set("User-Agent", USER_AGENT)
        set("X-Manga-Token", DEFAULT_MANGA_TOKEN)
    }

    // ============================== Popular ===============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val offset = (page - 1) * PAGE_LIMIT
        val url = "$baseUrl/manga".toHttpUrl().newBuilder()
            .addQueryParameter("limit", PAGE_LIMIT.toString())
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("includes[]", "cover_art")
            .build()

        val response = client.get(url).parseAs<MangaListResponseDto>()
        val mangaList = response.data.map { it.toSManga(baseUrl) }
        val hasNextPage = response.offset + response.limit < response.total

        return MangasPage(mangaList, hasNextPage)
    }

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    // =============================== Search ===============================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val offset = (page - 1) * PAGE_LIMIT
        val urlBuilder = "$baseUrl/manga".toHttpUrl().newBuilder()
            .addQueryParameter("limit", PAGE_LIMIT.toString())
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("includes[]", "cover_art")

        if (query.isNotEmpty()) {
            urlBuilder.addQueryParameter("title", query)
        }

        val statusFilter = filters.firstInstanceOrNull<StatusFilter>()
        val status = statusFilter?.selectedValue()
        if (!status.isNullOrEmpty()) {
            urlBuilder.addQueryParameter("status[]", status)
        }

        val demographicFilter = filters.firstInstanceOrNull<DemographicFilter>()
        val demographic = demographicFilter?.selectedValue()
        if (!demographic.isNullOrEmpty()) {
            urlBuilder.addQueryParameter("publicationDemographic[]", demographic)
        }

        val contentRatingFilter = filters.firstInstanceOrNull<ContentRatingFilter>()
        val contentRating = contentRatingFilter?.selectedValue()
        if (!contentRating.isNullOrEmpty()) {
            urlBuilder.addQueryParameter("contentRating[]", contentRating)
        }

        val tagFilter = filters.firstInstanceOrNull<TagFilter>()
        val selectedTags = tagFilter?.selectedTagIds().orEmpty()
        for (tag in selectedTags) {
            urlBuilder.addQueryParameter("includedTags[]", tag)
        }

        val response = client.get(urlBuilder.build()).parseAs<MangaListResponseDto>()

        val filteredData = response.data.filter { manga ->
            if (!status.isNullOrEmpty() && !manga.attributes.status.equals(status, ignoreCase = true)) {
                return@filter false
            }
            if (!demographic.isNullOrEmpty() && !manga.attributes.publicationDemographic.equals(demographic, ignoreCase = true)) {
                return@filter false
            }
            if (!contentRating.isNullOrEmpty() && !manga.attributes.contentRating.equals(contentRating, ignoreCase = true)) {
                return@filter false
            }
            if (selectedTags.isNotEmpty()) {
                val mangaTagIds = manga.attributes.tags.map { it.id.lowercase() }
                if (!selectedTags.all { it.lowercase() in mangaTagIds }) {
                    return@filter false
                }
            }
            true
        }

        val mangaList = filteredData.map { it.toSManga(baseUrl) }
        val hasNextPage = response.offset + response.limit < response.total

        return MangasPage(mangaList, hasNextPage)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val mangaId = url.pathSegments.lastOrNull { it.isNotEmpty() } ?: return null
        val targetUrl = "$baseUrl/manga/$mangaId"
        val response = client.get(targetUrl).parseAs<MangaDetailsResponseDto>()

        return response.data.toDetailedSManga(baseUrl).apply {
            this.url = "/manga/$mangaId"
        }
    }

    // =========================== Details & Chapters =======================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val mangaId = manga.url.substringAfterLast('/')

        return coroutineScope {
            val detailsDeferred = if (fetchDetails) {
                async {
                    val response = client.get("$baseUrl/manga/$mangaId").parseAs<MangaDetailsResponseDto>()
                    response.data.toDetailedSManga(baseUrl)
                }
            } else {
                null
            }

            val chaptersDeferred = if (fetchChapters) {
                async {
                    val feedUrl = "$baseUrl/manga/$mangaId/feed".toHttpUrl().newBuilder()
                        .addQueryParameter("limit", "500")
                        .addQueryParameter("order[chapter]", "desc")
                        .build()
                    val response = client.get(feedUrl).parseAs<ChapterListResponseDto>()
                    response.data.map { it.toSChapter() }
                }
            } else {
                null
            }

            SMangaUpdate(
                manga = detailsDeferred?.await() ?: manga,
                chapters = chaptersDeferred?.await() ?: chapters,
            )
        }
    }

    // =============================== Pages ================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = if (chapter.url.startsWith("http")) chapter.url else baseUrl + chapter.url
        val response = client.get(chapterUrl).parseAs<ChapterPagesResponseDto>()
        val cdnBaseUrl = response.baseUrl ?: "$baseUrl/media"
        val chapterPayload = response.chapter ?: return emptyList()
        val hash = chapterPayload.hash
        val data = chapterPayload.data.ifEmpty { chapterPayload.dataSaver }

        val quality = preferences.getString(PREF_IMAGE_QUALITY, DEFAULT_IMAGE_QUALITY) ?: DEFAULT_IMAGE_QUALITY

        return data.mapIndexed { index, fileName ->
            Page(index, imageUrl = "$cdnBaseUrl/$quality/$hash/$fileName")
        }
    }

    // ============================== Filters ===============================

    override val supportsFilterFetching get() = true

    override suspend fun fetchFilterData(): JsonElement {
        val url = "$baseUrl/manga".toHttpUrl().newBuilder()
            .addQueryParameter("limit", "100")
            .addQueryParameter("includes[]", "cover_art")
            .build()
        return client.get(url).parseAs<JsonElement>()
    }

    override fun getFilterList(data: JsonElement?): FilterList = getFilters(data)

    // ============================= Preferences ============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val qualityPref = ListPreference(screen.context).apply {
            key = PREF_IMAGE_QUALITY
            title = "Chất lượng ảnh chương"
            summary = "%s"
            entries = arrayOf(
                "Gốc WebP (Khuyên dùng - Nhanh & Nhẹ)",
                "Gốc nguyên bản (Original PNG/JPG)",
                "Tiết kiệm dữ liệu (1600px WebP)",
                "Tiết kiệm tối đa (1200px WebP)",
            )
            entryValues = arrayOf("s0-rw", "s0", "s1600-rw", "s1200-rw")
            setDefaultValue(DEFAULT_IMAGE_QUALITY)
        }
        screen.addPreference(qualityPref)

        val userToken = preferences.getString(PREF_USER_TOKEN, "").orEmpty()
        val tokenPref = EditTextPreference(screen.context).apply {
            key = PREF_USER_TOKEN
            title = "Token cá nhân / Mã kết nối (Pairing Code)"
            summary = when {
                userToken.isEmpty() -> "Chạm để nhập Token VIP (tl_usr_...) hoặc Mã kết nối 6 ký tự (TL-XXXX)"
                userToken.length > 20 -> userToken.substring(0, 10) + "..." + userToken.takeLast(6)
                else -> userToken
            }
            dialogTitle = "Nhập Token hoặc Mã kết nối"
            dialogMessage = "Nếu bạn có mã kết nối tạm thời 6 chữ cái (ví dụ TL-7942), hệ thống sẽ tự động kích hoạt thiết bị của bạn."
            setDefaultValue("")
            setOnPreferenceChangeListener { _, newValue ->
                val trimmed = (newValue as String).trim()
                val isPairingCode = trimmed.startsWith("TL", ignoreCase = true) ||
                    (trimmed.length == 6 && !trimmed.startsWith("tl_"))

                if (isPairingCode) {
                    thread {
                        try {
                            val body = PairingRequestDto(trimmed).toJsonRequestBody()
                            val request = Request.Builder()
                                .url("$baseUrl/auth/pair")
                                .post(body)
                                .build()
                            val response = client.newCall(request).execute()

                            if (response.isSuccessful) {
                                val pairingDto = response.parseAs<PairingResponseDto>()
                                val token = pairingDto.token
                                if (!token.isNullOrEmpty()) {
                                    preferences.edit().putString(PREF_USER_TOKEN, token).apply()
                                    Handler(Looper.getMainLooper()).post {
                                        summary = token.substring(0, 10) + "..." + token.takeLast(6)
                                        Toast.makeText(screen.context, "Kích hoạt thiết bị thành công!", Toast.LENGTH_LONG).show()
                                    }
                                }
                            } else {
                                response.close()
                                Handler(Looper.getMainLooper()).post {
                                    Toast.makeText(screen.context, "Mã kết nối không hợp lệ hoặc đã hết hạn!", Toast.LENGTH_LONG).show()
                                }
                            }
                        } catch (e: Exception) {
                            Handler(Looper.getMainLooper()).post {
                                Toast.makeText(screen.context, "Lỗi kết nối máy chủ: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    return@setOnPreferenceChangeListener true
                }

                if (trimmed.isNotEmpty()) {
                    summary = if (trimmed.length > 20) {
                        trimmed.substring(0, 10) + "..." + trimmed.takeLast(6)
                    } else {
                        trimmed
                    }
                }
                true
            }
        }
        screen.addPreference(tokenPref)
    }

    companion object {
        private const val USER_AGENT = "TuLinhExtension/1.4.7 (Android; OkHttp)"
        private const val DEFAULT_MANGA_TOKEN = "tl_live_74b68abdc9db00654620cc3e0f0e2414667b0308a09bfc05"
        private const val PAGE_LIMIT = 20
        private const val PREF_IMAGE_QUALITY = "tulinh_image_quality"
        private const val DEFAULT_IMAGE_QUALITY = "s0-rw"
        private const val PREF_USER_TOKEN = "tulinh_user_token"
    }
}
