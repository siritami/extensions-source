package eu.kanade.tachiyomi.extension.vi.lxhentai

import android.util.Base64
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.get
import keiyoushi.utils.getTurnstileToken
import keiyoushi.utils.parseAs
import keiyoushi.utils.stringOrNull
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.toJsonRequestBody
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.time.Instant

@Source
abstract class LxHentai : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        rateLimit(3)
    }

    // ============================== Popular ===============================

    override suspend fun getPopularManga(page: Int): MangasPage = parseMangaPage(client.get(browseMangaUrl(page, "-views"), Fetch.DOCUMENT))

    // ============================== Latest ================================

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseMangaPage(client.get(browseMangaUrl(page, "-updated_at"), Fetch.DOCUMENT))

    // ============================== Search ================================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val sort = filters.firstInstanceOrNull<SortFilter>()?.toUriPart() ?: "-updated_at"
        val searchType = filters.firstInstanceOrNull<SearchTypeFilter>()?.toUriPart() ?: "name"
        val statuses = filters.firstInstanceOrNull<StatusFilter>()?.selectedValues().orEmpty()
        val includedGenres = filters.firstInstanceOrNull<GenreFilter>()?.includedValues().orEmpty()
        val excludedGenres = filters.firstInstanceOrNull<GenreFilter>()?.excludedValues().orEmpty()

        val url = "$baseUrl/tim-kiem".toHttpUrl().newBuilder()
            .addQueryParameter("sort", sort)
            .addQueryParameter("page", page.toString())
            .apply {
                if (query.isNotBlank()) {
                    addQueryParameter("filter[$searchType]", query)
                }
                if (statuses.isNotEmpty()) {
                    addQueryParameter("filter[status]", statuses.joinToString(","))
                }
                if (includedGenres.isNotEmpty()) {
                    addQueryParameter("filter[accept_genres]", includedGenres.joinToString(","))
                }
                if (excludedGenres.isNotEmpty()) {
                    addQueryParameter("filter[reject_genres]", excludedGenres.joinToString(","))
                }
            }
            .build()
        return parseMangaPage(client.get(url, Fetch.DOCUMENT))
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.firstOrNull() != "truyen") return null

        val slug = url.pathSegments.getOrNull(1) ?: return null
        val manga = SManga.create().apply {
            setUrlWithoutDomain("/truyen/$slug")
        }
        return fetchMangaUpdate(manga, emptyList(), true, false).manga
    }

    private fun browseMangaUrl(page: Int, sortBy: String): HttpUrl = "$baseUrl/tim-kiem".toHttpUrl().newBuilder()
        .addQueryParameter("sort", sortBy)
        .addQueryParameter("page", page.toString())
        .addQueryParameter("filter[status]", "ongoing,completed,paused")
        .build()

    private fun parseMangaPage(response: Response): MangasPage {
        val document = response.asJsoup()
        val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val mangaList = document.select("div.manga-vertical")
            .map { element: Element -> mangaFromElement(element) }
        val hasNextPage = document.select("a#pagination[data-page]")
            .asSequence()
            .mapNotNull { element: Element -> element.attr("data-page").toIntOrNull() }
            .any { page: Int -> page > currentPage }

        return MangasPage(mangaList, hasNextPage)
    }

    private fun mangaFromElement(element: Element): SManga {
        val titleElement = element.selectFirst("a.text-ellipsis[href^=/truyen/]")!!
        val coverElement = element.selectFirst("div.cover")

        return SManga.create().apply {
            title = titleElement.text()
            setUrlWithoutDomain(titleElement.absUrl("href"))
            thumbnail_url = coverElement?.let { it: Element -> getThumbnailUrl(it) }
        }
    }

    private fun getThumbnailUrl(element: Element): String? = element.absUrl("data-bg")
        .ifEmpty { parseBackgroundUrl(element.attr("style")).orEmpty() }
        .ifBlank { null }

    // ============================== Details ===============================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get("$baseUrl${manga.url}", Fetch.DOCUMENT).asJsoup()
        return SMangaUpdate(
            manga = parseMangaDetails(document, manga),
            chapters = parseChapterList(document),
        )
    }

    private fun parseMangaDetails(document: Document, manga: SManga): SManga = SManga.create().apply {
        setUrlWithoutDomain(manga.url)
        title = document.selectFirst("div.flex.flex-row.truncate.mb-4 span.grow.text-lg.ml-1.text-ellipsis.font-semibold")!!.text()
        thumbnail_url = document.selectFirst("div.md\\:col-span-2 div.cover-frame > div.cover")
            ?.let { element: Element -> getThumbnailUrl(element) }
        author = document.infoRow("Tác giả:")
            ?.select("a[href*=/tac-gia/]")
            ?.joinToString { it: Element -> it.text() }
            ?.ifEmpty { null }

        genre = document.infoRow("Thể loại:")
            ?.select("a[href*=/the-loai/]")
            ?.joinToString { it: Element -> it.text() }
            ?.ifEmpty { null }

        val altNames = document.infoRow("Tên khác:")
            ?.select("a, span:not(.font-semibold)")
            ?.joinToString { it.text() }
            ?.takeIf { it.isNotEmpty() }

        val summary = document.select("p:contains(Tóm tắt) ~ p").joinToString("\n") { it.wholeText() }.trim()

        description = buildString {
            if (altNames != null) {
                append("Tên khác: ", altNames, "\n\n")
            }
            append(summary)
        }.trim()

        status = parseStatus(document.infoRow("Tình trạng:")?.text())
    }

    private fun Document.infoRow(label: String): Element? = select("div")
        .firstOrNull { row: Element -> row.selectFirst("> span.font-semibold")?.text() == label }

    private fun parseStatus(rawStatus: String?): Int {
        val status = rawStatus?.lowercase() ?: return SManga.UNKNOWN
        return when {
            "đang tiến hành" in status -> SManga.ONGOING
            "hoàn thành" in status || "completed" in status -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }

    // ============================= Chapters ===============================

    private fun parseChapterList(document: Document): List<SChapter> {
        val chapterElements = document.select("ul.overflow-y-auto a[href^=/truyen/]:has(span.timeago)")
            .ifEmpty { document.select("a[href^=/truyen/]:has(span.timeago)") }

        return chapterElements.mapNotNull { element: Element ->
            val chapterName = element.selectFirst("span.text-ellipsis")?.text()
                ?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null

            SChapter.create().apply {
                setUrlWithoutDomain(element.absUrl("href"))
                name = chapterName
                date_upload = parseChapterDate(element.selectFirst("span.timeago"))
            }
        }
    }

    private fun parseChapterDate(timeElement: Element?): Long = Instant.parseOrNull(timeElement?.attr("datetime").orEmpty())?.toEpochMilliseconds() ?: 0L

    // ============================== Pages =================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = "$baseUrl${chapter.url}"
        val chapterDocument = client.get(chapterUrl, Fetch.DOCUMENT).asJsoup()
        val chapterHtml = chapterDocument.html()

        val mfKey = extractMfKey(chapterHtml)
            ?: throw Exception("Không giải mã được viewer")
        val component = findViewerComponent(chapterDocument)
            ?: throw Exception("Không tìm thấy component ảnh")
        val mfKid = component.serverMemo["data"]["manifestKid"]?.stringOrNull

        val csrfToken = csrfTokenRegex.find(chapterHtml)?.groupValues?.get(1).orEmpty()
        val actionToken = getActionToken(chapterUrl, csrfToken, chapterHtml, mfKey, mfKid)
        val imageUrls = fetchImageManifest(chapterUrl, component, csrfToken, actionToken, mfKey)
        if (imageUrls.isEmpty()) return emptyList()

        val pageMetadata = encodePageMetadata(chapterUrl, actionToken)

        return imageUrls.mapIndexed { index: Int, imageUrl: String ->
            Page(index, url = pageMetadata, imageUrl = imageUrl)
        }
    }

    private fun findViewerComponent(document: Document): LivewireInitialData? = document.body().allElements
        .asSequence()
        .filter { element: Element -> element.hasAttr("wire:initial-data") }
        .mapNotNull { element: Element ->
            try {
                element.attr("wire:initial-data").parseAs<LivewireInitialData>()
            } catch (_: Exception) {
                null
            }
        }
        .firstOrNull { component: LivewireInitialData ->
            component.fingerprint["name"]?.stringOrNull == "chapter-viewer-images"
        }

    private fun extractMfKey(html: String): String? {
        // The viewer engine is nested in hex-encoded payloads: each blob is an array of
        // hex strings whose first 32 bytes are the XOR key for the rest. Walk the layers
        // until the decoded engine script exposes __lxMfkey / __lxMfkid.
        var candidates = listOf(html)
        repeat(4) {
            val next = mutableListOf<String>()
            for (text in candidates) {
                mfKeyRegex.find(text)?.let { return it.groupValues[1].replace("\\/", "/") }
                for (match in hexPayloadRegex.findAll(text)) {
                    val hex = quotedStringRegex.findAll(match.groupValues[1])
                        .joinToString("") { it.groupValues[1] }
                    next.add(decodeHexPayload(hex) ?: continue)
                }
            }
            if (next.isEmpty()) return null
            candidates = next
        }
        return null
    }

    private fun decodeHexPayload(hex: String): String? {
        if (hex.length < 66 || hex.length % 2 != 0) return null
        val bytes = try {
            hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } catch (_: Exception) {
            return null
        }
        val key = bytes.copyOfRange(0, 32)
        val out = ByteArray(bytes.size - 32)
        for (i in out.indices) {
            out[i] = (bytes[i + 32].toInt() xor key[i % key.size].toInt()).toByte()
        }
        return String(out, Charsets.UTF_8).takeIf { it.isNotBlank() }
    }

    private fun xorB64Decode(payload: String, key: String): String? {
        val bytes = try {
            Base64.decode(payload, Base64.DEFAULT)
        } catch (_: Exception) {
            return null
        }
        val keyBytes = key.toByteArray(Charsets.UTF_8)
        for (i in bytes.indices) {
            bytes[i] = (bytes[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
        }
        return String(bytes, Charsets.UTF_8)
    }

    private suspend fun getActionToken(chapterUrl: String, csrfToken: String, chapterHtml: String, mfKey: String, mfKid: String?): String {
        cachedActionToken?.let { return it }

        val siteKey = recaptchaRegex.find(chapterHtml)?.groupValues?.get(1).orEmpty()

        val tokenHeaders = headersBuilder()
            .set("Accept", "application/json")
            .set("Content-Type", "application/json")
            .set("X-CSRF-TOKEN", csrfToken)
            .set("Referer", chapterUrl.toHttpUrlOrNull()?.toString() ?: chapterUrl)
            .set("Origin", baseUrl)
            .build()

        val tokenUrl = buildString {
            append("$baseUrl/get_token")
            if (!mfKid.isNullOrEmpty()) {
                append("?kid=", mfKid)
            }
        }

        fun TokenResponse.actionToken(): String? {
            action_token?.let { return it }
            val encrypted = e ?: return null
            return xorB64Decode(encrypted, mfKey)
                ?.parseAs<EncryptedToken>()
                ?.at
        }

        val getResponse = client.get(tokenUrl, tokenHeaders, Fetch.XHR, ensureSuccess = false)
        if (getResponse.isSuccessful) {
            val data = getResponse.parseAs<TokenResponse>()
            if (!data.is_bot) {
                data.actionToken()?.let { return it.also { token -> cachedActionToken = token } }
            }
        } else {
            getResponse.close()
        }

        if (siteKey.isNotEmpty()) {
            val turnstileToken = getTurnstileToken(
                url = chapterUrl,
                siteKey = siteKey,
            )

            val payload = TurnstilePayload(turnstileResponse = turnstileToken).toJsonRequestBody()
            val postResponse = client.post(tokenUrl, tokenHeaders, Fetch.XHR, payload, ensureSuccess = false)
            if (postResponse.isSuccessful) {
                val data = postResponse.parseAs<TokenResponse>()
                if (!data.is_bot) {
                    data.actionToken()?.let { return it.also { token -> cachedActionToken = token } }
                }
            } else {
                postResponse.close()
            }
        }

        return ""
    }

    private suspend fun fetchImageManifest(chapterUrl: String, component: LivewireInitialData, csrfToken: String, actionToken: String, mfKey: String): List<String> {
        val message = LivewireMessage(
            fingerprint = component.fingerprint,
            serverMemo = component.serverMemo,
            updates = listOf(
                LivewireUpdate(
                    type = "fireEvent",
                    payload = LivewireUpdatePayload(
                        id = (1..7).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString(""),
                        event = "lxViewerImagesUnlock",
                        params = listOf(actionToken),
                    ),
                ),
            ),
        )

        val manifestHeaders = headersBuilder()
            .set("Accept", "text/html, application/xhtml+xml")
            .set("Content-Type", "application/json")
            .set("X-Livewire", "true")
            .set("X-CSRF-TOKEN", csrfToken)
            .set("Referer", chapterUrl.toHttpUrlOrNull()?.toString() ?: chapterUrl)
            .set("Origin", baseUrl)
            .build()

        val response = client.post(
            "$baseUrl/livewire/message/chapter-viewer-images",
            manifestHeaders,
            Fetch.XHR,
            message.toJsonRequestBody(),
        )

        val payload = response.parseAs<LivewireResponse>()
            .effects
            ?.dispatches
            ?.firstOrNull { it.event == "lx:srcs" }
            ?.data
            ?.data
            ?: return emptyList()

        val manifest = xorB64Decode(payload, mfKey)?.parseAs<ImageManifest>() ?: return emptyList()
        return manifest.u.filterIndexed { index, _ -> (manifest.f.getOrNull(index) ?: 0) and 1 == 0 }
    }

    override fun getImageRequest(page: Page): Request {
        val (chapterUrl, actionToken) = decodePageMetadata(page.url)
        val imageUrl = page.imageUrl ?: throw Exception("Không tìm thấy URL ảnh")
        return GET(imageUrl, imageHeaders(chapterUrl, actionToken))
    }

    private fun imageHeaders(chapterUrl: String, actionToken: String) = super.headersBuilder()
        .add("Referer", chapterUrl.toHttpUrlOrNull()?.toString() ?: chapterUrl)
        .add("Origin", baseUrl)
        .apply {
            if (actionToken.isNotEmpty()) {
                add("Token", actionToken)
            }
        }
        .build()

    // ============================== Filters ===============================

    override val supportsFilterFetching get() = true

    override suspend fun fetchFilterData(): JsonElement = client.get("$baseUrl/tim-kiem", Fetch.DOCUMENT).asJsoup()
        .select("label")
        .mapNotNull { element ->
            val slug = genreSlugRegex.matchEntire(element.attr("@click"))
                ?.groupValues
                ?.get(1)
                ?: return@mapNotNull null
            val genreName = element.text().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            GenreOption(genreName, slug)
        }
        .distinctBy { it.slug }
        .toJsonElement()

    override fun getFilterList(data: JsonElement?): FilterList = getFilters(data?.parseAs<List<GenreOption>>())

    // ============================== Helpers ===============================

    private fun parseBackgroundUrl(styleValue: String?): String? {
        if (styleValue.isNullOrBlank()) return null

        val rawUrl = backgroundUrlRegex.find(styleValue)?.groupValues?.get(1) ?: return null
        return rawUrl.toHttpUrlOrNull()?.toString()
            ?: "$baseUrl${rawUrl.takeIf { it.startsWith("/") } ?: "/$rawUrl"}"
    }

    private fun encodePageMetadata(chapterUrl: String, actionToken: String): String = "$chapterUrl\n$actionToken"

    private fun decodePageMetadata(rawMetadata: String): Pair<String, String> {
        val separatorIndex = rawMetadata.lastIndexOf('\n')
        if (separatorIndex < 0) {
            return rawMetadata to ""
        }

        val chapterUrl = rawMetadata.substring(0, separatorIndex)
        val actionToken = rawMetadata.substring(separatorIndex + 1)
        return chapterUrl to actionToken
    }

    private var cachedActionToken: String? = null

    private val csrfTokenRegex = Regex("""var\s+csrf_token\s*=\s*'([^']+)'""")
    private val recaptchaRegex = Regex("""var\s+recaptcha\s*=\s*'([^']+)'""")
    private val mfKeyRegex = Regex("""__lxMfkey\s*=\s*"([^"]+)"""")
    private val hexPayloadRegex = Regex("""var\s+_\w+\s*=\s*\[((?:"[0-9a-fA-F]+"\s*,?\s*)+)\]""")
    private val quotedStringRegex = Regex(""""([^"]+)"""")
    private val backgroundUrlRegex = Regex("""background-image:\s*url\(['"]?([^'")]+)""", RegexOption.IGNORE_CASE)
    private val genreSlugRegex = Regex("""toggleGenre\('([^']+)'\)""")
}
