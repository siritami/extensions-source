package eu.kanade.tachiyomi.extension.vi.yurigarden

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Solves a Cloudflare Turnstile / challenge page by loading the target URL in a
 * hidden, fully-laid-out [WebView]. Turnstile fingerprints the rendering pipeline
 * (screen/canvas size), so the view is forced to a realistic layout to let the
 * invisible/managed widget auto-solve in most cases.
 *
 * On success the `cf_clearance` cookie is set in the shared [CookieManager], which
 * is picked up by the OkHttp cookie jar on subsequent requests.
 */
object CloudflareResolver {

    private const val TIMEOUT_SECONDS = 45L
    private const val INITIAL_POLL_DELAY_MS = 2_000L
    private const val POLL_INTERVAL_MS = 500L

    // Keep polling after onPageFinished to let the reader page's JS fire the
    // API subrequests that actually trigger the cf_clearance cookie.
    private const val POST_LOAD_GRACE_MS = 8_000L
    private const val WEBVIEW_WIDTH = 1080
    private const val WEBVIEW_HEIGHT = 1920
    private const val CLEARANCE_COOKIE = "cf_clearance"

    @Synchronized
    @SuppressLint("SetJavaScriptEnabled")
    fun resolve(url: String, userAgent: String? = null): Boolean {
        val cookieManager = CookieManager.getInstance()
        if (hasClearance(cookieManager, url)) return true

        val context = Injekt.get<Application>()
        val handler = Handler(Looper.getMainLooper())
        val latch = CountDownLatch(1)
        val pageFinishedAt = AtomicLong(0L)
        var webView: WebView? = null
        lateinit var poll: Runnable

        handler.post {
            val wv = WebView(context)
            webView = wv

            wv.layoutParams = ViewGroup.LayoutParams(WEBVIEW_WIDTH, WEBVIEW_HEIGHT)
            wv.measure(
                View.MeasureSpec.makeMeasureSpec(WEBVIEW_WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(WEBVIEW_HEIGHT, View.MeasureSpec.EXACTLY),
            )
            wv.layout(0, 0, WEBVIEW_WIDTH, WEBVIEW_HEIGHT)

            with(wv.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                blockNetworkImage = false
                mediaPlaybackRequiresUserGesture = false
                // Cloudflare binds cf_clearance to the User-Agent that solved the
                // challenge, so the cookie is only valid for OkHttp requests if both
                // sides use the same UA.
                if (!userAgent.isNullOrBlank()) userAgentString = userAgent
            }

            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(wv, true)

            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                    pageFinishedAt.compareAndSet(0L, System.currentTimeMillis())
                }
            }

            poll = Runnable {
                if (latch.count == 0L) return@Runnable
                if (hasClearance(cookieManager, url)) {
                    latch.countDown()
                    return@Runnable
                }
                // Stop waiting once the grace period after page load has elapsed.
                // This covers both "CF wasn't actually gating this URL" and
                // "managed/invisible challenge solved without a visible cookie" --
                // the caller will retry the OkHttp request once regardless.
                val finishedAt = pageFinishedAt.get()
                if (finishedAt != 0L && System.currentTimeMillis() - finishedAt > POST_LOAD_GRACE_MS) {
                    latch.countDown()
                    return@Runnable
                }
                handler.postDelayed(poll, POLL_INTERVAL_MS)
            }

            wv.loadUrl(url)
            handler.postDelayed(poll, INITIAL_POLL_DELAY_MS)
        }

        latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)

        handler.post {
            handler.removeCallbacks(poll)
            webView?.stopLoading()
            webView?.destroy()
        }

        // Report success based on the actual cookie state, not just "page loaded";
        // this prevents an infinite retry loop if Cloudflare really blocked us.
        return hasClearance(cookieManager, url)
    }

    private fun hasClearance(cookieManager: CookieManager, url: String): Boolean {
        val cookies = cookieManager.getCookie(url) ?: return false
        return cookies.split(';').any { it.trim().startsWith("$CLEARANCE_COOKIE=") }
    }
}
