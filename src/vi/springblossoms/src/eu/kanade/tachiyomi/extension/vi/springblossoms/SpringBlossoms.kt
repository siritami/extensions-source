package eu.kanade.tachiyomi.extension.vi.springblossoms

import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

@Source
abstract class SpringBlossoms :
    KeiSource(),
    ConfigurableSource {
    private val preferences by getPreferencesLazy()

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        addInterceptor(supabaseConfigInterceptor())
        rateLimit(2, 1.seconds)
    }

    override fun Headers.Builder.configureHeaders(): Headers.Builder = apply {
        add("Accept", "application/json")
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = supabaseUrlPref
            title = "Supabase URL"
            summary = "Tự động lấy lại nếu để trống"
            dialogTitle = title
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = supabaseAnonKeyPref
            title = "Supabase anon key"
            summary = "Tự động lấy lại khi API từ chối khóa đã lưu"
            dialogTitle = title
        }.also(screen::addPreference)
    }

    // ============================== Popular ===============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val config = getSupabaseConfig()
        val url = config.restUrl("manga")
            .apply { addQueryParameter("select", mangaSelect) }
            .addQueryParameter("order", "view_count.desc")
            .addQueryParameter("limit", (pageSize + 1).toString())
            .addQueryParameter("offset", ((page - 1) * pageSize).toString())
            .build()
        val data = client.get(url, config.headers()).parseAs<List<MangaDto>>()
        return MangasPage(
            mangas = data.take(pageSize).map(MangaDto::toSManga),
            hasNextPage = data.size > pageSize,
        )
    }

    // ============================== Latest ================================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val config = getSupabaseConfig()
        val url = config.restUrl("manga")
            .apply { addQueryParameter("select", mangaSelect) }
            .addQueryParameter("order", "updated_at.desc")
            .addQueryParameter("limit", (pageSize + 1).toString())
            .addQueryParameter("offset", ((page - 1) * pageSize).toString())
            .build()
        val data = client.get(url, config.headers()).parseAs<List<MangaDto>>()
        return MangasPage(
            mangas = data.take(pageSize).map(MangaDto::toSManga),
            hasNextPage = data.size > pageSize,
        )
    }

    // ============================== Search ================================

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val config = getSupabaseConfig()
        val url = config.restUrl("manga")
            .apply { addQueryParameter("select", mangaSelect) }
            .apply {
                if (query.isNotBlank()) {
                    addQueryParameter("title", "ilike.*$query*")
                }
            }
            .apply {
                filters.firstInstanceOrNull<GenreFilter>()?.selected?.let { genre ->
                    if (genre.isNotBlank()) addQueryParameter("genres", "cs.{$genre}")
                }
            }
            .apply {
                filters.firstInstanceOrNull<StatusFilter>()?.selected?.let { status ->
                    if (status.isNotBlank()) addQueryParameter("status", "eq.$status")
                }
            }
            .apply {
                filters.firstInstanceOrNull<SortFilter>()?.selected?.let { sort ->
                    addQueryParameter("order", sort)
                }
            }
            .apply {
                filters.firstInstanceOrNull<AdultFilter>()?.selected?.let { isAdult ->
                    addQueryParameter("is_adult", "eq.$isAdult")
                }
            }
            .addQueryParameter("limit", (pageSize + 1).toString())
            .addQueryParameter("offset", ((page - 1) * pageSize).toString())
            .build()
        val data = client.get(url, config.headers()).parseAs<List<MangaDto>>()
        return MangasPage(
            mangas = data.take(pageSize).map(MangaDto::toSManga),
            hasNextPage = data.size > pageSize,
        )
    }

    // ============================== Details ===============================

    private suspend fun getMangaDetails(manga: SManga): SManga {
        val serialId = manga.url.substringAfterLast("/")
        return getManga(serialId)?.toSManga() ?: manga
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val mangaIndex = url.pathSegments.indexOf("manga")
        val serialId = url.pathSegments.getOrNull(mangaIndex + 1)
            ?.takeIf { mangaIndex >= 0 && it.toIntOrNull() != null }
            ?: return null
        return getManga(serialId)?.toSManga()
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = SMangaUpdate(
        manga = if (fetchDetails) getMangaDetails(manga) else manga,
        chapters = if (fetchChapters) getChapterList(manga) else chapters,
    )

    // ============================== Chapters ==============================

    private suspend fun getChapterList(manga: SManga): List<SChapter> {
        val serialId = manga.url.substringAfterLast("/")
        val mangaId = getMangaId(serialId) ?: return emptyList()
        val config = getSupabaseConfig()
        val url = config.restUrl("chapters")
            .addQueryParameter("select", "id,chapter_number,title,created_at,publish_date")
            .addQueryParameter("manga_id", "eq.$mangaId")
            .addQueryParameter("order", "chapter_number.desc")
            .build()
        return client.get(url, config.headers()).parseAs<List<ChapterDto>>()
            .map { it.toSChapter(serialId) }
    }

    // ============================== Pages ================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterId = chapter.url.substringAfter("/read/")
        val config = getSupabaseConfig()
        val url = config.restUrl("chapters")
            .addQueryParameter("select", "pages")
            .addQueryParameter("id", "eq.$chapterId")
            .addQueryParameter("limit", "1")
            .build()
        val pages = client.get(url, config.headers()).parseAs<List<PagesDto>>()
            .firstOrNull()?.pages ?: emptyList()
        return pages.mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }
    }

    // ============================== Filters ===============================

    override val supportsFilterFetching get() = true

    override suspend fun fetchFilterData(): JsonElement {
        val config = getSupabaseConfig()
        val url = config.restUrl("manga")
            .addQueryParameter("select", "genres")
            .build()
        return client.get(url, config.headers()).parseAs<JsonElement>()
    }

    override fun getFilterList(data: JsonElement?) = getFilters(data)

    // ============================== Utilities =============================

    private suspend fun getManga(serialId: String): MangaDto? {
        val config = getSupabaseConfig()
        val url = config.restUrl("manga")
            .addQueryParameter("select", mangaSelect)
            .addQueryParameter("serial_id", "eq.$serialId")
            .addQueryParameter("limit", "1")
            .build()
        return client.get(url, config.headers()).parseAs<List<MangaDto>>().firstOrNull()
    }

    private suspend fun getMangaId(serialId: String): String? {
        val config = getSupabaseConfig()
        val url = config.restUrl("manga")
            .addQueryParameter("select", "id")
            .addQueryParameter("serial_id", "eq.$serialId")
            .addQueryParameter("limit", "1")
            .build()
        return client.get(url, config.headers()).parseAs<List<MangaIdDto>>()
            .firstOrNull()?.id
    }

    private suspend fun getSupabaseConfig(): SupabaseConfig {
        preferences.getSupabaseConfig()?.let { return it }

        return supabaseConfigMutex.withLock {
            preferences.getSupabaseConfig() ?: discoverSupabaseConfig()
        }
    }

    private fun supabaseConfigInterceptor() = Interceptor { chain ->
        val request = chain.request()
        if (!request.url.encodedPath.startsWith("/rest/v1/")) {
            return@Interceptor chain.proceed(request)
        }

        val staleKey = request.header("apikey").orEmpty()
        val response = try {
            chain.proceed(request)
        } catch (error: IOException) {
            return@Interceptor retryWithRefreshedConfig(chain, request, staleKey, error)
        }
        if (response.code !in staleConfigCodes) {
            return@Interceptor response
        }

        response.close()
        retryWithRefreshedConfig(chain, request, staleKey)
    }

    private fun retryWithRefreshedConfig(
        chain: Interceptor.Chain,
        request: Request,
        staleKey: String,
        originalError: IOException? = null,
    ): Response {
        val refreshedConfig = try {
            runBlocking { refreshSupabaseConfig(staleKey) }
        } catch (error: Exception) {
            if (originalError != null) error.addSuppressed(originalError)
            throw error
        }
        val refreshedUrl = request.url.newBuilder()
            .scheme(refreshedConfig.url.scheme)
            .host(refreshedConfig.url.host)
            .port(refreshedConfig.url.port)
            .build()
        return chain.proceed(
            request.newBuilder()
                .url(refreshedUrl)
                .header("apikey", refreshedConfig.anonKey)
                .build(),
        )
    }

    private suspend fun refreshSupabaseConfig(staleKey: String): SupabaseConfig = supabaseConfigMutex.withLock {
        preferences.getSupabaseConfig()
            ?.takeIf { it.anonKey != staleKey }
            ?: discoverSupabaseConfig()
    }

    private suspend fun discoverSupabaseConfig(): SupabaseConfig {
        val mainScriptUrl = client.get(baseUrl, headers).asJsoup()
            .selectFirst("script[type=module][src*=/assets/index-]")
            ?.absUrl("src")
            ?.takeIf(String::isNotEmpty)
            ?: throw Exception("Không tìm thấy JavaScript chính của trang web")
        val script = client.get(mainScriptUrl, headers).use { it.body.string() }
        val supabaseUrlMatch = supabaseUrlRegex.find(script)
            ?: throw Exception("Không tìm thấy Supabase URL")
        val nearbyScript = script.substring(
            supabaseUrlMatch.range.first,
            minOf(script.length, supabaseUrlMatch.range.last + configSearchRange),
        )
        val url = supabaseUrlMatch.value.toHttpUrl()
        val projectRef = url.host.substringBefore('.')
        val anonKey = jwtRegex.findAll(nearbyScript)
            .map(MatchResult::value)
            .firstOrNull { it.isSupabaseAnonKey(projectRef) }
            ?: throw Exception("Không tìm thấy Supabase anon key")

        return SupabaseConfig(url, anonKey).also { preferences.putSupabaseConfig(it) }
    }

    private fun String.isSupabaseAnonKey(projectRef: String): Boolean = runCatching {
        val parts = split('.')
        if (parts.size != 3) return@runCatching false

        val header = JSONObject(Base64.decode(parts[0], Base64.URL_SAFE).decodeToString())
        val payload = JSONObject(Base64.decode(parts[1], Base64.URL_SAFE).decodeToString())
        header.optString("typ") == "JWT" &&
            header.optString("alg").isNotBlank() &&
            payload.optString("role") == "anon" &&
            payload.optString("ref") == projectRef
    }.getOrDefault(false)

    private class SupabaseConfig(
        val url: HttpUrl,
        val anonKey: String,
    ) {
        fun restUrl(table: String): HttpUrl.Builder = url.newBuilder()
            .addPathSegments("rest/v1")
            .addPathSegment(table)
    }

    private fun SupabaseConfig.headers(): Headers = headersBuilder()
        .set("apikey", anonKey)
        .build()

    private fun SharedPreferences.getSupabaseConfig(): SupabaseConfig? {
        val url = getString(supabaseUrlPref, null)
            ?.takeIf(String::isNotBlank)
            ?.let { runCatching { it.toHttpUrl() } }
            ?.getOrNull()
            ?: return null
        val anonKey = getString(supabaseAnonKeyPref, null)
            ?.takeIf(String::isNotBlank)
            ?: return null
        return SupabaseConfig(url, anonKey)
    }

    private fun SharedPreferences.putSupabaseConfig(config: SupabaseConfig) {
        edit()
            .putString(supabaseUrlPref, config.url.toString().removeSuffix("/"))
            .putString(supabaseAnonKeyPref, config.anonKey)
            .commit()
    }

    private val supabaseConfigMutex = Mutex()
    private val supabaseUrlRegex = Regex("""https://[a-z0-9-]+\.supabase\.co""")
    private val jwtRegex = Regex("""[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+""")
    private val configSearchRange = 2_000
    private val pageSize = 30
    private val mangaSelect = "title,serial_id,author,status,cover_image_path,genres,synopsis,description"
    private val supabaseUrlPref = "supabase_url"
    private val supabaseAnonKeyPref = "supabase_anon_key"
    private val staleConfigCodes = setOf(401, 403)
}
