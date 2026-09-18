package eu.kanade.tachiyomi.extension.vi.moetruyen

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
        bootstrapUrlOverride: String? = null,
    ): List<ImgxPageAccess> {
        val parsed = parseBootstrapConfig(document)
        val bootstrapUrl = bootstrapUrlOverride?.takeIf { it.isNotBlank() }
            ?: parsed.bootstrapUrl
        require(bootstrapUrl.isNotBlank()) {
            "IMGX document capability required"
        }
        val config = parsed.copy(bootstrapUrl = bootstrapUrl)
        val bootstrapProof = ImgxCrypto.base64UrlEncode(ImgxCrypto.randomBytes(32))
        val bootstrap = client.post(
            "$baseUrl${config.bootstrapUrl}",
            headers(),
            BootstrapRequest(
                readerPublicKey = keyPair.publicKey,
                bootstrapProof = bootstrapProof,
                initialPageIndexes = config.initialIndexes,
            ).toJsonRequestBody(),
        ).parseAs<BootstrapResponse>()

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

        val remaining = media
            .map { it.pageIndex }
            .filterNot { it in granted }
            .distinct()

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
                headers(),
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

            require(response.ok && response.sealedPages != null) {
                "IMGX page access failed: ${response.code ?: "unknown"}"
            }

            ImgxCrypto.openSealedPages(keyPair, response.sealedPages, signature).forEach { page ->
                granted[page.pageIndex] = page
            }
        }

        return media.mapNotNull { entry -> granted[entry.pageIndex] }
    }

    private fun headers(): Headers = Headers.Builder()
        .set("Accept", "application/json")
        .set("Content-Type", "application/json")
        .set("Referer", chapterUrl)
        .set("Origin", baseUrl)
        .build()

    companion object {
        private const val PROOF_VERSION = "imgx-page-access-proof-v3"

        fun parseBootstrapConfig(document: Document): ReaderBootstrapConfig {
            val script = document.select("script")
                .map { it.data() }
                .firstOrNull { it.contains("createImgxReaderAccess") }
                ?: throw IllegalStateException("IMGX reader bootstrap missing")
            val requestPath = Regex("""requestPath:\s*"([^"]+)"""").find(script)?.groupValues?.get(1)
                ?: throw IllegalStateException("IMGX request path missing")
            val chapterId = Regex("""chapterId:\s*(\d+)""").find(script)?.groupValues?.get(1)?.toLongOrNull()
                ?: throw IllegalStateException("IMGX chapter id missing")
            val bootstrapUrl = Regex("""bootstrapUrl:\s*"([^"]*)"""").find(script)?.groupValues?.get(1)
                .orEmpty()
            val initialIndexes = Regex("""initialIndexes:\s*\[([^\]]*)\]""").find(script)
                ?.groupValues
                ?.get(1)
                ?.split(',')
                ?.mapNotNull { it.trim().takeIf { value -> value.isNotEmpty() }?.toInt() }
                .orEmpty()
            return ReaderBootstrapConfig(
                requestPath = requestPath,
                chapterId = chapterId,
                bootstrapUrl = bootstrapUrl,
                initialIndexes = initialIndexes,
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
            val mediaJson = root.attr("data-reader-imgx-media").ifBlank { return emptyList() }
            val media = runCatching {
                URLDecoder.decode(mediaJson, Charsets.UTF_8.name()).parseAs<List<ReaderMediaEntry>>()
            }.getOrDefault(emptyList())
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
