package eu.kanade.tachiyomi.extension.vi.moetruyen

import android.util.Log
import keiyoushi.network.post
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import java.net.URLDecoder

internal class ImgxAccessClient(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val chapterUrl: String,
    private val document: Document,
) {
    private val keyPair = ImgxCrypto.generateEcdhP256()
    private var sequence = 0L

    suspend fun fetchPages(
        media: List<ReaderMediaEntry>,
        totalPages: Int = 0,
    ): List<ImgxPageAccess> {
        val config = parseBootstrapConfig(document)
        Log.e("MoeTruyen", "access: bootstrap=${config.bootstrapUrl} path=${config.requestPath} chapterId=${config.chapterId} media=${media.size} totalPages=$totalPages")

        // When media JSON is empty, build indexes from totalPages
        val pageIndexes = if (media.isNotEmpty()) {
            media.map { it.pageIndex }
        } else {
            (0 until totalPages).toList()
        }
        Log.e("MoeTruyen", "access: requesting ${pageIndexes.size} pages")

        if (config.bootstrapUrl.isBlank()) {
            throw IllegalStateException(
                "IMGX document capability required — site withheld reader-instance token " +
                    "(path=${config.requestPath} chapterId=${config.chapterId} pages=${pageIndexes.size})",
            )
        }

        val bootstrapProof = ImgxCrypto.base64UrlEncode(ImgxCrypto.randomBytes(32))
        val bootstrap = client.post(
            "$baseUrl${config.bootstrapUrl}",
            jsonHeaders(),
            BootstrapRequest(
                readerPublicKey = keyPair.publicKey,
                bootstrapProof = bootstrapProof,
                initialPageIndexes = config.initialIndexes,
            ).toJsonRequestBody(),
        ).parseAs<BootstrapResponse>()
        Log.e("MoeTruyen", "access: bootstrap ok=${bootstrap.ok} code=${bootstrap.code} chapterId=${bootstrap.chapterId}")

        require(bootstrap.ok && bootstrap.sealedCapability != null) {
            "IMGX bootstrap failed: ${bootstrap.code ?: "unknown"}"
        }

        val capability = ImgxCrypto.openSealedCapability(keyPair, bootstrap.sealedCapability, bootstrapProof)
        require(capability.readerInstanceId == bootstrap.readerInstanceId) {
            "IMGX reader instance mismatch"
        }

        val granted = mutableMapOf<Int, ImgxPageAccess>()
        if (bootstrap.sealedInitialPages != null) {
            ImgxCrypto.openSealedPages(keyPair, bootstrap.sealedInitialPages, bootstrapProof).forEach { page ->
                granted[page.pageIndex] = page
            }
        }

        val remaining = pageIndexes.filterNot { it in granted }
        remaining.chunked(10).forEach { indexes ->
            sequence += 1
            val material = buildProofMaterial(
                readerInstanceId = capability.readerInstanceId,
                chapterId = config.chapterId,
                requestPath = config.requestPath,
                pageIndexes = indexes,
                issuedAt = bootstrap.serverTime,
                sequence = sequence,
                publicKeyHash = ImgxCrypto.publicKeyHash(keyPair.publicKey),
            )
            val signature = ImgxCrypto.base64UrlEncode(
                ImgxCrypto.hmacSha256(ImgxCrypto.base64UrlDecode(capability.secret), material),
            )
            val response = client.post(
                "$baseUrl${config.requestPath}",
                jsonHeaders(),
                PageAccessRequest(
                    pageIndexes = indexes,
                    pageAccessProof = PageAccessProof(
                        version = PROOF_VERSION,
                        readerInstanceId = capability.readerInstanceId,
                        issuedAt = bootstrap.serverTime,
                        sequence = sequence,
                        proof = signature,
                    ),
                    readerPublicKey = keyPair.publicKey,
                ).toJsonRequestBody(),
            ).parseAs<PageAccessResponse>()
            Log.e("MoeTruyen", "access: page-access indexes=$indexes ok=${response.ok} code=${response.code}")

            require(response.ok && response.sealedPages != null) {
                "IMGX page access failed: ${response.code ?: "unknown"}"
            }

            ImgxCrypto.openSealedPages(keyPair, response.sealedPages, signature).forEach { page ->
                granted[page.pageIndex] = page
            }
        }

        return if (media.isNotEmpty()) {
            media.mapNotNull { entry -> granted[entry.pageIndex] }
        } else {
            pageIndexes.mapNotNull { idx -> granted[idx] }
        }
    }

    // POSTs need cors-style Sec-Fetch + cookies from the HTML page load.
    // OkHttp cookie jar sends imgx_document + bfang.sid automatically.
    private fun jsonHeaders(): Headers = Headers.Builder()
        .set("Accept", "application/json")
        .set("Content-Type", "application/json")
        .set("Origin", baseUrl)
        .set("Referer", chapterUrl)
        .set("Sec-Fetch-Dest", "empty")
        .set("Sec-Fetch-Mode", "cors")
        .set("Sec-Fetch-Site", "same-origin")
        .build()

    companion object {
        private const val PROOF_VERSION = "imgx-page-access-proof-v3"

        fun parseBootstrapConfig(document: Document): ReaderBootstrapConfig {
            val script = document.select("script")
                .map { it.data() }
                .firstOrNull { it.contains("createImgxReaderAccess") }

            if (script != null) {
                val requestPath = Regex("""requestPath:\s*"([^"]+)"""").find(script)?.groupValues?.get(1)
                    ?: throw IllegalStateException("IMGX request path missing from script")
                val chapterId = Regex("""chapterId:\s*(\d+)""").find(script)?.groupValues?.get(1)?.toLongOrNull()
                    ?: throw IllegalStateException("IMGX chapter id missing from script")
                val bootstrapUrl = Regex("""bootstrapUrl:\s*"([^"]*)"""").find(script)?.groupValues?.get(1)
                    .orEmpty()
                val initialIndexes = Regex("""initialIndexes:\s*\[([^\]]*)\]""").find(script)
                    ?.groupValues
                    ?.get(1)
                    ?.split(',')
                    ?.mapNotNull { it.trim().takeIf { v -> v.isNotEmpty() }?.toInt() }
                    .orEmpty()
                Log.e("MoeTruyen", "access: config from script bootstrap=$bootstrapUrl path=$requestPath chapterId=$chapterId")
                return ReaderBootstrapConfig(requestPath, chapterId, bootstrapUrl, initialIndexes)
            }

            // Script withheld — construct from data attributes
            Log.e("MoeTruyen", "access: createImgxReaderAccess script not found, using data attributes")
            val root = document.selectFirst("[data-reader-lazy-pages]")
                ?: throw IllegalStateException("IMGX reader metadata missing")
            val accessUrl = root.attr("data-reader-imgx-access-url")
            if (accessUrl.isBlank()) throw IllegalStateException("IMGX access URL missing")
            val trackToken = root.attr("data-reader-view-track-token")
            val chapterId = trackToken.substringBefore('.').toLongOrNull()
                ?: throw IllegalStateException("IMGX chapter id missing from track token")
            val totalPages = root.attr("data-reader-total-pages").toIntOrNull() ?: 0
            Log.e("MoeTruyen", "access: from attrs path=$accessUrl chapterId=$chapterId totalPages=$totalPages")
            // bootstrapUrl is not in HTML for non-browser clients — we'll try the page-access endpoint directly
            return ReaderBootstrapConfig(
                requestPath = accessUrl,
                chapterId = chapterId,
                bootstrapUrl = "",
                initialIndexes = if (totalPages > 0) listOf(totalPages - 1) else emptyList(),
            )
        }

        fun buildProofMaterial(
            readerInstanceId: String,
            chapterId: Long,
            requestPath: String,
            pageIndexes: List<Int>,
            issuedAt: Long,
            sequence: Long,
            publicKeyHash: String,
        ): ByteArray {
            val indexes = pageIndexes.joinToString(",", prefix = "[", postfix = "]")
            return """["$PROOF_VERSION","$readerInstanceId",$chapterId,"$requestPath","",$indexes,$issuedAt,$sequence,"$publicKeyHash"]"""
                .toByteArray(Charsets.UTF_8)
        }

        fun encryptedMedia(document: Document): List<ReaderMediaEntry> {
            val root = document.selectFirst("[data-reader-lazy-pages]") ?: return emptyList()
            val mediaJson = root.attr("data-reader-imgx-media")
            Log.e("MoeTruyen", "access: raw media attr len=${mediaJson.length} value=${mediaJson.take(300)}")
            if (mediaJson.isBlank()) return emptyList()
            val media = runCatching {
                URLDecoder.decode(mediaJson, Charsets.UTF_8.name()).parseAs<List<ReaderMediaEntry>>()
            }.getOrDefault(emptyList())
            Log.e("MoeTruyen", "access: parsed media count=${media.size} keys=${media.take(5).map { it.storageKey }}")
            return media.filter { entry ->
                entry.storageKey.startsWith("chapters/") &&
                    !entry.storageKey.endsWith("/0.js") &&
                    !entry.downloadUrl.endsWith("/0.js")
            }
        }
    }
}

@kotlinx.serialization.Serializable
private class BootstrapRequest(
    val readerPublicKey: String,
    val bootstrapProof: String,
    val initialPageIndexes: List<Int>,
)
