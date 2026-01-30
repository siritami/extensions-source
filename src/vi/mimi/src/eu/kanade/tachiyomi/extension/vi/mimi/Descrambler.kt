package eu.kanade.tachiyomi.extension.vi.mimi

import android.annotation.SuppressLint
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.Headers
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.CountDownLatch
import uy.kohesive.injekt.api.get
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

// Based on the CgImvNOL.js file from the site
private const val SHIM_JS = """
// Mock import.meta.url
const importMetaUrl = "https://mimimoe.moe/_nuxt/";

const v = "https://mimimoe.moe/_nuxt/aSdLPkDj.CaSpbFQb.wasm";

const D = async (e = {}, t) => {
    let n;
    const _ = await fetch(t);
    const r = _.headers.get("Content-Type") || "";
    if ("instantiateStreaming" in WebAssembly && r.startsWith("application/wasm")) {
        n = await WebAssembly.instantiateStreaming(_, e);
    } else {
        const a = await _.arrayBuffer();
        n = await WebAssembly.instantiate(a, e);
    }
    return n.instance.exports;
};

let s;
function R(e) { s = e; }

let b = new Array(128).fill(void 0);
b.push(void 0, null, !0, !1);

function o(e) { return b[e]; }
let l = b.length;
function f(e) {
    l === b.length && b.push(b.length + 1);
    const t = l;
    return l = b[t], b[t] = e, t;
}

function A(e, t) {
    try {
        return e.apply(this, t);
    } catch (n) {
        s.__wbindgen_export_0(f(n));
    }
}

function u(e) { return e == null; }
let y = null;

function p() {
    return (y === null || y.byteLength === 0) && (y = new Uint8Array(s.memory.buffer)), y;
}

let x = new TextDecoder("utf-8", { ignoreBOM: !0, fatal: !0 });
x.decode();
const M = 2146435072;
let L = 0;

function k(e, t) {
    return L += t, L >= M && (x = new TextDecoder("utf-8", { ignoreBOM: !0, fatal: !0 }), x.decode(), L = t), x.decode(p().subarray(e, e + t));
}

function h(e, t) {
    return e = e >>> 0, k(e, t);
}

function U(e) {
    e < 132 || (b[e] = l, l = e);
}

function W(e) {
    const t = o(e);
    return U(e), t;
}

let C = 128;

function F(e) {
    if (C == 1) throw new Error("out of js stack");
    return b[--C] = e, C;
}

let I = 0;
const m = new TextEncoder();
if (!("encodeInto" in m)) {
    m.encodeInto = function(e, t) {
        const n = m.encode(e);
        return t.set(n), { read: e.length, written: n.length };
    };
}

function O(e, t, n) {
    if (n === void 0) {
        const i = m.encode(e), d = t(i.length, 1) >>> 0;
        return p().subarray(d, d + i.length).set(i), I = i.length, d;
    }
    let _ = e.length, r = t(_, 1) >>> 0;
    const a = p();
    let c = 0;
    for (; c < _; c++) {
        const i = e.charCodeAt(c);
        if (i > 127) break;
        a[r + c] = i;
    }
    if (c !== _) {
        c !== 0 && (e = e.slice(c)), r = n(r, _, _ = c + e.length * 3, 1) >>> 0;
        const i = p().subarray(r + c, r + _), d = m.encodeInto(e, i);
        c += d.written, r = n(r, _, c, 1) >>> 0;
    }
    return I = c, r;
}

let g = null;

function T() {
    return (g === null || g.buffer.detached === !0 || (g.buffer.detached === void 0 && g.buffer !== s.memory.buffer)) && (g = new DataView(s.memory.buffer)), g;
}

function le(e, t, n, _, r) {
    try {
        const d = s.__wbindgen_add_to_stack_pointer(-16),
            E = O(_, s.__wbindgen_export_1, s.__wbindgen_export_2),
            B = I,
            S = O(r, s.__wbindgen_export_1, s.__wbindgen_export_2),
            j = I;
        s.descramble_image(d, F(e), t, n, E, B, S, j);
        var a = T().getInt32(d + 0, !0),
            c = T().getInt32(d + 4, !0),
            i = T().getInt32(d + 8, !0);
        if (i) throw W(c);
        return a !== 0;
    } finally {
        s.__wbindgen_add_to_stack_pointer(16), b[C++] = void 0;
    }
}

function H() {
    return A(function(e, t) {
        const n = o(e).call(o(t));
        return f(n);
    }, arguments);
}

function G(e, t, n, _, r) {
    o(e).clearRect(t, n, _, r);
}

function N(e) {
    const t = o(e).document;
    return u(t) ? 0 : f(t);
}

function V() {
    return A(function(e, t, n, _, r, a, c, i, d, E) {
        o(e).drawImage(o(t), n, _, r, a, c, i, d, E);
    }, arguments);
}

function P() {
    return A(function(e, t, n, _, r, a) {
        o(e).drawImage(o(t), n, _, r, a);
    }, arguments);
}

function ${'$'}() {
    return A(function(e, t, n) {
        const _ = o(e).getContext(h(t, n));
        return u(_) ? 0 : f(_);
    }, arguments);
}

function z(e, t, n) {
    const _ = o(e).getElementById(h(t, n));
    return u(_) ? 0 : f(_);
}

function Q(e) {
    let t;
    try {
        t = o(e) instanceof CanvasRenderingContext2D;
    } catch { t = !1; }
    return t;
}

function X(e) {
    let t;
    try {
        t = o(e) instanceof HTMLCanvasElement;
    } catch { t = !1; }
    return t;
}

function Y(e) {
    let t;
    try {
        t = o(e) instanceof Window;
    } catch { t = !1; }
    return t;
}

function q(e, t) {
    const n = new Function(h(e, t));
    return f(n);
}

function J(e, t) {
    o(e).height = t >>> 0;
}

function K(e, t) {
    o(e).width = t >>> 0;
}

function Z() {
    const e = typeof global > "u" ? null : global;
    return u(e) ? 0 : f(e);
}

function ee() {
    const e = typeof globalThis > "u" ? null : globalThis;
    return u(e) ? 0 : f(e);
}

function te() {
    const e = typeof self > "u" ? null : self;
    return u(e) ? 0 : f(e);
}

function ne() {
    const e = typeof window > "u" ? null : window;
    return u(e) ? 0 : f(e);
}

function _e(e) {
    return o(e) === void 0;
}

function re(e, t) {
    throw new Error(h(e, t));
}

function ce(e, t) {
    const n = h(e, t);
    return f(n);
}

function oe(e) {
    const t = o(e);
    return f(t);
}

function ae(e) {
    W(e);
}

// Global initialization
var descramble_image_impl = le;

async function initWasm() {
     const w = await D({
        "./descramble_wasm_bg.js": {
            __wbindgen_object_drop_ref: ae,
            __wbg_instanceof_CanvasRenderingContext2d_8c616198ec03b12f: Q,
            __wbg_drawImage_ac6a1d25efb31742: P,
            __wbg_drawImage_8d002fb55b838415: V,
            __wbg_clearRect_448c93ecc652d129: G,
            __wbg_getElementById_3c3d00d9a16a01dd: z,
            __wbg_instanceof_HtmlCanvasElement_299c60950dbb3428: X,
            __wbg_setwidth_40a6ed203b92839d: K,
            __wbg_setheight_4fce583024b2d088: J,
            __wbg_getContext_15e158d04230a6f6: ${'$'},
            __wbg_instanceof_Window_12d20d558ef92592: Y,
            __wbg_document_7d29d139bd619045: N,
            __wbg_newnoargs_254190557c45b4ec: q,
            __wbg_call_13410aac570ffff7: H,
            __wbindgen_object_clone_ref: oe,
            __wbg_static_accessor_GLOBAL_THIS_f0a4409105898184: ee,
            __wbg_static_accessor_SELF_995b214ae681ff99: te,
            __wbg_static_accessor_WINDOW_cde3890479c675ea: ne,
            __wbg_static_accessor_GLOBAL_8921f820c2ce3f12: Z,
            __wbg_wbindgenisundefined_c4b71d073b92f3c5: _e,
            __wbg_wbindgenthrow_451ec1a8469d7eb6: re,
            __wbindgen_cast_2241b6af4c4b2941: ce
        }
    }, v);
    R({
        __wbindgen_add_to_stack_pointer: w.__wbindgen_add_to_stack_pointer,
        __wbindgen_export_0: w.__wbindgen_export_0,
        __wbindgen_export_1: w.__wbindgen_export_1,
        __wbindgen_export_2: w.__wbindgen_export_2,
        descramble_image: w.descramble_image,
        memory: w.memory
    });
    console.log("WASM Initialized");
}

// Main descramble function exposed to Android
window.processImage = async (url, drm) => {
    try {
        if (!s) await initWasm();

        const img = new Image();
        img.crossOrigin = "anonymous";
        img.src = url;
        await new Promise((resolve, reject) => {
            img.onload = resolve;
            img.onerror = reject;
        });

        const canvas = document.createElement("canvas");
        canvas.id = "descramble-canvas-temp";
        canvas.width = img.width;
        canvas.height = img.height;
        // The WASM looks for an element with this ID. We must append it to DOM.
        document.body.appendChild(canvas);

        await descramble_image_impl(img, img.width, img.height, canvas.id, drm);
        
        const dataUrl = canvas.toDataURL("image/jpeg", 0.9);
        document.body.removeChild(canvas);
        
        const base64 = dataUrl.split(",")[1];
        window.android.onResult(base64);

    } catch (e) {
        console.error(e);
        window.android.onError(e.toString());
    }
};
"""

class Descrambler(private val headers: Headers) {

    private val context = Injekt.get<Application>()
    private val handler = Handler(Looper.getMainLooper())
    private val lock = ReentrantLock()

    @Volatile
    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        if (webView != null) return
        
        val latch = CountDownLatch(1)
        handler.post {
            try {
                webView = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // Block network images to speed up, but we unblock them for the actual fetch
                    settings.blockNetworkImage = false


                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                            // Log console messages for debugging
                            return super.onConsoleMessage(consoleMessage)
                        }
                    }
                    
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): WebResourceResponse? {
                            // Let everything load normally for now
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            view?.evaluateJavascript(SHIM_JS, null)
                        }
                    }
                    
                    addJavascriptInterface(AndroidInterface(), "android")
                }
                webView?.loadDataWithBaseURL("https://mimimoe.moe/", "<html><body></body></html>", "text/html", "UTF-8", null)
            } finally {
                latch.countDown()
            }
        }
        latch.await()
    }

    fun descramble(url: String, drm: String): Bitmap {
        lock.lock()
        try {
            if (webView == null) initWebView()
            
            val latch = CountDownLatch(1)
            var result: Bitmap? = null
            var error: String? = null

            val callback = object : AndroidInterface.Callback {
                override fun onSuccess(base64: String) {
                    try {
                        val decodedBytes = Base64.decode(base64, Base64.DEFAULT)
                        result = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                    } catch (e: Exception) {
                        error = e.message
                    } finally {
                        latch.countDown()
                    }
                }

                override fun onFailure(msg: String) {
                    error = msg
                    latch.countDown()
                }
            }

            // Set the current callback
            AndroidInterface.currentCallback = callback

            handler.post {
                webView?.evaluateJavascript("window.processImage('$url', '$drm')", null)
            }

            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw Exception("Timeout waiting for descramble")
            }

            if (error != null) throw Exception(error)
            return result ?: throw Exception("Failed to decode bitmap")
        } finally {
            lock.unlock()
        }
    }

    // JS Interface
    class AndroidInterface {
        companion object {
            var currentCallback: Callback? = null
        }

        interface Callback {
            fun onSuccess(base64: String)
            fun onFailure(msg: String)
        }

        @JavascriptInterface
        fun onResult(base64: String) {
            currentCallback?.onSuccess(base64)
        }

        @JavascriptInterface
        fun onError(msg: String) {
            currentCallback?.onFailure(msg)
        }
    }
}
