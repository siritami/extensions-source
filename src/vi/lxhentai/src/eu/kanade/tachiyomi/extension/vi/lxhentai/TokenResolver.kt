package eu.kanade.tachiyomi.extension.vi.lxhentai

import keiyoushi.utils.WebViewTimeoutException
import keiyoushi.utils.runWebView
import kotlin.time.Duration.Companion.seconds

/**
 * Loads chapter in WebView, handles Cloudflare Turnstile verification,
 * decodes image URLs from the 3-layer obfuscated inline script,
 * and fetches the action token.
 *
 * Flow:
 *   1. WebView loads chapter page → Cloudflare Turnstile dialog appears
 *   2. Poll auto-clicks "OK, tiếp tục" when Turnstile completes
 *   3. POST /get_token succeeds → actionToken is set
 *   4. JS decode extracts image URLs from obfuscated inline script
 *   5. Returns image URLs + token for the extension to fetch
 */
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
        var latestToken = ""

        return runWebView(timeout = 45.seconds) {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            blockImages = false
            userAgent = userAgent.replace(webViewTokenRegex, ")")

            poll(1.seconds) {
                // 1. Dismiss Cloudflare Turnstile dialog when ready
                evaluateJs(
                    """(function(){
                        var b=document.querySelector('.swal2-confirm');
                        if(b && !b.disabled && b.textContent.includes('tiếp tục')) b.click();
                    })()""",
                )

                // 2. Check if actionToken is available (Turnstile solved + /get_token called)
                val tokenResult = evaluateJs(
                    """(function(){
                        var t = window.actionToken;
                        return (t && typeof t === 'string' && t.length > 0) ? t : '';
                    })()""",
                ) as? String

                if (!tokenResult.isNullOrEmpty()) {
                    latestToken = tokenResult

                    // 3. Decode image URLs from the obfuscated inline script
                    val urlsJson = evaluateJs(INTERDecodeScript) as? String
                    val urls = parseUrlList(urlsJson)

                    if (urls.isNotEmpty()) {
                        resolve(Result(latestToken, urls))
                    }
                }
            }

            loadUrl(chapterUrl)
        }
    }

    /** Parses a JSON string array of URLs, filtering for valid HTTP(S) URLs. */
    private fun parseUrlList(json: String?): List<String> {
        if (json.isNullOrEmpty() || json == "[]") return emptyList()
        return try {
            json.removeSurrounding("[", "]")
                .split("\",\"")
                .map { it.trim().removeSurrounding("\"") }
                .filter { it.startsWith("http") }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private val webViewTokenRegex = Regex("""\;\s*wv\)""")

    /**
     * JavaScript that decodes the 3-layer obfuscated inline script to extract image URLs.
     * Searches all inline scripts for the obfuscation pattern.
     *
     * Layer 1: Base64-decode the concatenated _b93f0c4 string array
     * Layer 2: XOR-decode combined numeric arrays with key → produces inner script
     * Layer 3: Parse inner base64 JSON, XOR each element → final image URLs
     */
    private const val INTERDecodeScript = """(function(){
        try {
            var scripts = document.querySelectorAll('script:not([src])');
            var target = null;
            for (var i = 0; i < scripts.length; i++) {
                var text = scripts[i].textContent;
                if (text.indexOf('_b93f0c4') >= 0 || text.indexOf('_bfe4cfc') >= 0 || text.indexOf('_b5bac40') >= 0) {
                    target = text;
                    break;
                }
            }
            if (!target) return '[]';

            // Layer 1: base64 array → string
            var b64Match = target.match(/_b(?:93f0c4|fe4cfc|5bac40)=\[((?:"[^"]+",?\s*)+)\]/);
            if (!b64Match) return '[]';
            var parts = b64Match[1].match(/"([^"]+)"/g).map(function(s){return s.replace(/"/g,'');});
            var layer1 = decodeURIComponent(escape(atob(parts.join(''))));

            // Layer 2: extract numeric arrays + XOR key
            var keyMatch = layer1.match(/var _kfbbae8='([0-9a-f]+)'/);
            if (!keyMatch) return '[]';
            var key = keyMatch[1];
            var arrRe = /var _[a-f0-9]+=\[((?:\d+,?)*)\]/g;
            var combined = [];
            var m;
            while ((m = arrRe.exec(layer1)) !== null) {
                combined = combined.concat(m[1].split(',').filter(function(s){return s.length>0;}).map(Number));
            }
            var decoded = '';
            for (var i = 0; i < combined.length; i++) {
                decoded += String.fromCharCode(combined[i] ^ key.charCodeAt(i % key.length));
            }

            // Layer 3: extract inner base64 JSON + XOR key
            var key3Match = decoded.match(/var _x141d8b="([0-9a-f]+)"/);
            if (!key3Match) return '[]';
            var key3 = key3Match[1];
            var jsonMatch = decoded.match(/var _x474d97="(A[A-Za-z0-9+/=]+)"/);
            if (!jsonMatch) return '[]';
            var jsonArr = JSON.parse(atob(jsonMatch[1]));
            var urls = [];
            for (var j = 0; j < jsonArr.length; j++) {
                var item = decodeURIComponent(escape(atob(jsonArr[j])));
                var url = '';
                for (var k = 0; k < item.length; k++) {
                    url += String.fromCharCode(item.charCodeAt(k) ^ key3.charCodeAt(k % key3.length));
                }
                if (url.indexOf('http') === 0 && urls.indexOf(url) < 0) urls.push(url);
            }
            return JSON.stringify(urls);
        } catch(e) { return '[]'; }
    })()"""
}
