package eu.kanade.tachiyomi.extension.vi.mimi

import android.annotation.SuppressLint
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Interceptor to descramble images that are split into a 3x3 grid.
 *
 * The MiMi website uses a WASM-based descrambling algorithm that cannot be
 * easily replicated. This interceptor uses WebView to execute the site's
 * JavaScript/WASM to get the correct tile permutation.
 */
class ImageInterceptor : Interceptor {
    companion object {
        const val DRM_PARAM = "mimi_drm"
        private const val GRID_SIZE = 3
        private const val TILE_COUNT = GRID_SIZE * GRID_SIZE
        private val JPEG_MEDIA_TYPE = "image/jpeg".toMediaType()
        private const val WEBVIEW_TIMEOUT = 15L // seconds
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url

        // Check if this is a scrambled image with DRM data
        val drmHex = url.queryParameter(DRM_PARAM) ?: return chain.proceed(request)

        // Remove the DRM param and proceed with the real request
        val cleanUrl = url.newBuilder()
            .removeAllQueryParameters(DRM_PARAM)
            .build()
        val cleanRequest = request.newBuilder().url(cleanUrl).build()
        val response = chain.proceed(cleanRequest)

        if (!response.isSuccessful) return response

        // Descramble the image
        val body = response.body ?: return response
        val imageBytes = body.bytes()

        // Get the tile mapping from WebView (or use fallback)
        val mapping = getTileMapping(drmHex)

        // Descramble the image using the mapping
        val descrambledBytes = descrambleImage(imageBytes, mapping)

        return response.newBuilder()
            .body(descrambledBytes.toResponseBody(JPEG_MEDIA_TYPE))
            .build()
    }

    /**
     * Get tile mapping by executing the site's WASM via WebView.
     * Falls back to identity mapping (no change) if WebView fails.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun getTileMapping(drmHex: String): IntArray {
        val latch = CountDownLatch(1)
        val result = intArrayOf(-1, -1, -1, -1, -1, -1, -1, -1, -1)
        val handler = Handler(Looper.getMainLooper())
        var webView: WebView? = null

        handler.post {
            try {
                val wv = WebView(Injekt.get<Application>())
                webView = wv
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                wv.addJavascriptInterface(JsInterface(latch, result), "MiMiExt")

                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        // Execute JavaScript to get the tile mapping
                        view?.evaluateJavascript(getDescrambleScript(drmHex), null)
                    }
                }

                // Load the MiMi page to get access to the WASM module
                wv.loadUrl("https://mimimoe.moe/")
            } catch (e: Exception) {
                latch.countDown()
            }
        }

        latch.await(WEBVIEW_TIMEOUT, TimeUnit.SECONDS)
        handler.post { webView?.destroy() }

        // If we got valid mapping, return it
        if (result[0] != -1) {
            return result
        }

        // Fallback: identity mapping (no descrambling)
        return intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8)
    }

    private fun getDescrambleScript(drmHex: String): String {
        return """
            (function() {
                try {
                    // Wait for the DRM module to be available
                    if (!window.drm || !window.drm.descramble_image) {
                        // DRM not loaded, retry after a delay
                        setTimeout(function() {
                            if (window.drm && window.drm.descramble_image) {
                                runDescramble();
                            } else {
                                window.MiMiExt.onMappingResult('');
                            }
                        }, 2000);
                        return;
                    }
                    runDescramble();
                    
                    function runDescramble() {
                        // Create a test canvas
                        var canvas = document.createElement('canvas');
                        canvas.width = 300;
                        canvas.height = 300;
                        canvas.id = 'mimi_test_canvas';
                        var ctx = canvas.getContext('2d');
                        
                        // Draw numbered tiles so we can track the permutation
                        for (var i = 0; i < 9; i++) {
                            ctx.fillStyle = 'rgb(' + (i * 28) + ',' + (i * 28) + ',' + (i * 28) + ')';
                            var x = (i % 3) * 100;
                            var y = Math.floor(i / 3) * 100;
                            ctx.fillRect(x, y, 100, 100);
                        }
                        
                        // Capture the draw calls to get the mapping
                        var mapping = [];
                        var originalDrawImage = ctx.drawImage;
                        ctx.drawImage = function() {
                            if (arguments.length === 9) {
                                var sx = arguments[1], sy = arguments[2];
                                var dx = arguments[5], dy = arguments[6];
                                var srcIdx = Math.round(sy / 100) * 3 + Math.round(sx / 100);
                                var dstIdx = Math.round(dy / 100) * 3 + Math.round(dx / 100);
                                mapping.push({src: srcIdx, dst: dstIdx});
                            }
                            return originalDrawImage.apply(this, arguments);
                        };
                        
                        // Create a source image for descrambling
                        var img = new Image();
                        img.width = 300;
                        img.height = 300;
                        canvas.getContext('2d').drawImage = originalDrawImage;
                        
                        // Try to call descramble
                        try {
                            var drmStr = '$drmHex';
                            // The WASM descramble function
                            window.drm.descramble_image(canvas, 300, 300, 'mimi_test_canvas', drmStr);
                            
                            // Build result array: result[dstIdx] = srcIdx
                            var result = new Array(9).fill(-1);
                            mapping.forEach(function(m) {
                                result[m.dst] = m.src;
                            });
                            
                            window.MiMiExt.onMappingResult(result.join(','));
                        } catch (e) {
                            window.MiMiExt.onMappingResult('');
                        }
                    }
                } catch (e) {
                    window.MiMiExt.onMappingResult('');
                }
            })();
        """.trimIndent()
    }

    /**
     * Descramble the image by rearranging tiles according to the mapping.
     * mapping[dstIndex] = srcIndex means the tile at dstIndex in the result
     * comes from srcIndex in the scrambled source.
     */
    private fun descrambleImage(imageBytes: ByteArray, mapping: IntArray): ByteArray {
        val srcBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        val width = srcBitmap.width
        val height = srcBitmap.height

        val tileWidth = width / GRID_SIZE
        val tileHeight = height / GRID_SIZE

        val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)

        for (dstIndex in 0 until TILE_COUNT) {
            val srcIndex = mapping[dstIndex]

            // Source coordinates (from scrambled image)
            val srcCol = srcIndex % GRID_SIZE
            val srcRow = srcIndex / GRID_SIZE
            val srcX = srcCol * tileWidth
            val srcY = srcRow * tileHeight

            // Destination coordinates (in result image)
            val dstCol = dstIndex % GRID_SIZE
            val dstRow = dstIndex / GRID_SIZE
            val dstX = dstCol * tileWidth
            val dstY = dstRow * tileHeight

            // Handle edge tiles that might have different sizes
            val actualWidth = if (srcCol == GRID_SIZE - 1) width - srcX else tileWidth
            val actualHeight = if (srcRow == GRID_SIZE - 1) height - srcY else tileHeight

            val srcRect = Rect(srcX, srcY, srcX + actualWidth, srcY + actualHeight)
            val dstRect = Rect(dstX, dstY, dstX + actualWidth, dstY + actualHeight)

            canvas.drawBitmap(srcBitmap, srcRect, dstRect, null)
        }

        srcBitmap.recycle()

        val output = ByteArrayOutputStream()
        resultBitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
        resultBitmap.recycle()

        return output.toByteArray()
    }

    @Suppress("UNUSED")
    private class JsInterface(
        private val latch: CountDownLatch,
        private val result: IntArray,
    ) {
        @JavascriptInterface
        fun onMappingResult(mappingStr: String) {
            try {
                if (mappingStr.isNotEmpty()) {
                    val parts = mappingStr.split(",")
                    if (parts.size == 9) {
                        parts.forEachIndexed { index, value ->
                            result[index] = value.toIntOrNull() ?: index
                        }
                    }
                }
            } finally {
                latch.countDown()
            }
        }
    }
}
