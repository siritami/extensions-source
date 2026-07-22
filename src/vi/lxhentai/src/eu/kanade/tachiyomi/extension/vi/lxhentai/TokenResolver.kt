package eu.kanade.tachiyomi.extension.vi.lxhentai

import keiyoushi.utils.WebViewTimeoutException
import keiyoushi.utils.runWebView
import kotlin.time.Duration.Companion.seconds

/** Loads chapter in WebView, solves Cloudflare Turnstile, decodes obfuscated image URLs. */
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
        var latestToken = ""
        var latestUrls = emptyList<String>()

        return runWebView(timeout = 45.seconds) {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            blockImages = false
            userAgent = userAgent.replace(webViewTokenRegex, ")")

            poll(1.seconds) {
                // Dismiss Cloudflare Turnstile dialog
                evaluateJs(
                    """(function(){
                        var b=document.querySelector('.swal2-confirm');
                        if(b && !b.disabled && b.textContent.includes('tiếp tục')) b.click();
                    })()""",
                )

                // Check actionToken via callback
                evaluateJs(
                    """(function(){
                        var t = window.actionToken;
                        return (t && typeof t === 'string' && t.length > 0) ? t : '';
                    })()""",
                ) { value ->
                    val token = value.removeSurrounding("\"")
                    if (token.isNotEmpty() && token != "null") {
                        synchronized(payloadLock) { latestToken = token }
                    }
                }

                val token = synchronized(payloadLock) { latestToken }
                if (token.isEmpty()) return@poll

                // Decode image URLs via callback
                evaluateJs(INTER_DECODE_SCRIPT) { value ->
                    val json = value.removeSurrounding("\"")
                    val urls = parseUrlList(json)
                    if (urls.isNotEmpty()) {
                        synchronized(payloadLock) { latestUrls = urls }
                    }
                }

                val urls = synchronized(payloadLock) { latestUrls }
                if (urls.isNotEmpty()) {
                    resolve(Result(token, urls))
                }
            }

            loadUrl(chapterUrl)
        }
    }

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

    /** Rotation-proof JS: decodes 3-layer obfuscated inline script → image URLs. */
    private const val INTER_DECODE_SCRIPT = """(function(){
        try {
            var scripts = document.querySelectorAll('script:not([src])');
            var target = null;
            for (var i = 0; i < scripts.length; i++) {
                var t = scripts[i].textContent;
                if (t.indexOf('["KGZ1') >= 0 || t.indexOf('=\["KGZ1') >= 0) {
                    target = t;
                    break;
                }
            }
            if (!target) return '[]';
            var b64Match = target.match(/=\[((?:"[A-Za-z0-9+/=]{20,}",?\s*)+)\]/);
            if (!b64Match) return '[]';
            var parts = b64Match[1].match(/"([^"]+)"/g);
            if (!parts) return '[]';
            var joined = parts.map(function(s){return s.replace(/"/g,'');}).join('');
            var raw = atob(joined);
            var layer1;
            try { layer1 = decodeURIComponent(escape(raw)); } catch(e2) { layer1 = raw; }
            var key2Match = layer1.match(/var _\w+='([0-9a-f]{20,})'/);
            if (!key2Match) return '[]';
            var key2 = key2Match[1];
            var arrRe = /var _\w+=\[((?:-?\d+,?)*)\]/g;
            var combined = [];
            var m;
            while ((m = arrRe.exec(layer1)) !== null) {
                var nums = m[1].split(',').filter(function(s){return s.length>0;}).map(Number);
                combined = combined.concat(nums);
            }
            if (combined.length === 0) return '[]';
            var decoded = '';
            for (var i = 0; i < combined.length; i++) {
                decoded += String.fromCharCode((combined[i] ^ key2.charCodeAt(i % key2.length)) & 0xFF);
            }
            var key3Match = decoded.match(/var _\w+="([0-9a-f]{20,})"/);
            if (!key3Match) return '[]';
            var key3 = key3Match[1];
            var jsonB64Match = decoded.match(/var _\w+="([A-Za-z0-9+/=]{50,})"/);
            if (!jsonB64Match) return '[]';
            var jsonArr = JSON.parse(atob(jsonB64Match[1]));
            var urls = [];
            for (var j = 0; j < jsonArr.length; j++) {
                var item;
                try { item = decodeURIComponent(escape(atob(jsonArr[j]))); }
                catch(e3) { item = atob(jsonArr[j]); }
                var url = '';
                for (var k = 0; k < item.length; k++) {
                    url += String.fromCharCode((item.charCodeAt(k) ^ key3.charCodeAt(k % key3.length)) & 0xFF);
                }
                if (url.indexOf('http') === 0 && urls.indexOf(url) < 0) urls.push(url);
            }
            return JSON.stringify(urls);
        } catch(e) { return '[]'; }
    })()"""
}
