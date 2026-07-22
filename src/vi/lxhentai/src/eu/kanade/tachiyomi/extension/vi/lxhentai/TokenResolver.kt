package eu.kanade.tachiyomi.extension.vi.lxhentai

import keiyoushi.utils.WebViewTimeoutException
import keiyoushi.utils.runWebView
import kotlin.time.Duration.Companion.seconds

/** Loads chapter in WebView, intercepts CDN image requests to extract URLs + token. */
object TokenResolver {

    class Result(val token: String = "", val srcs: List<String> = emptyList())

    private const val MAX_ATTEMPTS = 3
    suspend fun resolve(chapterUrl: String): Result {
        repeat(MAX_ATTEMPTS) {
            try {
                return resolveOnce(chapterUrl)
            } catch (_: WebViewTimeoutException) {
                // Retry transient reader load failures.
            }
        }
        return Result()
    }

    private suspend fun resolveOnce(chapterUrl: String): Result {
        val payloadLock = Any()
        val imageUrls = mutableListOf<String>()
        var latestToken = ""

        return runWebView(timeout = 20.seconds) {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            blockImages = false
            userAgent = userAgent.replace(webViewTokenRegex, ")")

            interceptRequest { request ->
                val imageUrl = request.url.toString()
                val token = request.requestHeaders["Token"]
                if (imageCdnRegex.containsMatchIn(imageUrl) && !token.isNullOrEmpty()) {
                    synchronized(payloadLock) {
                        if (imageUrl !in imageUrls) imageUrls += imageUrl
                        latestToken = token
                    }
                }
                null
            }

            poll(1.seconds) {
                val result = synchronized(payloadLock) {
                    Result(latestToken, imageUrls.toList())
                }
                if (result.token.isNotEmpty() && result.srcs.isNotEmpty()) {
                    resolve(result)
                }
            }

            loadUrl(chapterUrl)
        }
    }

    private val webViewTokenRegex = Regex("""\;\s*wv\)""")
    private val imageCdnRegex = Regex("""lxmanga\.(xyz|space)/""")
}
