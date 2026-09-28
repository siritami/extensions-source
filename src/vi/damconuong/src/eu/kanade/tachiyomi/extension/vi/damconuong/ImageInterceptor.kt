package eu.kanade.tachiyomi.extension.vi.damconuong

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Plain OkHttp GET (keiyoushi.network.get requires an HttpSource context). */
private fun OkHttpClient.getString(url: String): String {
    val response = newCall(Request.Builder().url(url).build()).execute()
    return response.use { it.body?.string().orEmpty() }
}

// ================================ Pages DTOs ================================

@Serializable
class PagesResponse(
    val e: String,
)

@Serializable
class PagesPayload(
    val p: List<String> = emptyList(),
    val s: List<String?>? = null,
)

// =============================== Site cache ================================

/** SharedPreferences cache for scraped site config (API base + decoder material). */
object SiteCache {
    private const val KEY_API = "api_base"
    private const val KEY_SECRET = "decoder_secret"
    private const val KEY_ALPHABET = "decoder_alphabet"

    fun apiBase(prefs: SharedPreferences): String? = prefs.getString(KEY_API, null)

    fun saveApiBase(prefs: SharedPreferences, value: String) {
        prefs.edit().putString(KEY_API, value).apply()
    }

    fun decoderSecret(prefs: SharedPreferences): String? = prefs.getString(KEY_SECRET, null)

    fun decoderAlphabet(prefs: SharedPreferences): String? = prefs.getString(KEY_ALPHABET, null)

    fun saveDecoder(prefs: SharedPreferences, secret: String, alphabet: String) {
        prefs.edit()
            .putString(KEY_SECRET, secret)
            .putString(KEY_ALPHABET, alphabet)
            .apply()
    }

    fun invalidate(prefs: SharedPreferences) {
        prefs.edit()
            .remove(KEY_API)
            .remove(KEY_SECRET)
            .remove(KEY_ALPHABET)
            .apply()
    }
}

// ============================== API discovery =============================

/** Resolves `https://…/api/v1` from site HTML/JS; cached in [SiteCache] and reused. */
object ApiBase {
    @Volatile private var memory: String? = null

    suspend fun get(client: OkHttpClient, baseUrl: String, prefs: SharedPreferences): String {
        memory?.let { return it }
        SiteCache.apiBase(prefs)?.let {
            memory = it
            return it
        }
        return resolve(client, baseUrl, prefs)
    }

    suspend fun resolve(client: OkHttpClient, baseUrl: String, prefs: SharedPreferences): String {
        val resolved = try {
            discover(client, baseUrl)
        } catch (e: IOException) {
            // Connection error: drop cache and try discovery again.
            invalidate(prefs)
            discover(client, baseUrl)
        }
        memory = resolved
        SiteCache.saveApiBase(prefs, resolved)
        return resolved
    }

    fun invalidate(prefs: SharedPreferences) {
        memory = null
        SiteCache.invalidate(prefs)
    }

    private suspend fun discover(client: OkHttpClient, baseUrl: String): String {
        val html = client.getString(baseUrl)

        val fromJs = API_V1_RE.find(html)?.value
        val fromPreconnect = PRECONNECT_RE.find(html)?.groupValues?.get(1)
        val resolved = when {
            fromJs != null -> fromJs
            fromPreconnect != null -> "${fromPreconnect.trimEnd('/')}/api/v1"
            else -> {
                val chunkBody = DecoderScraper.CHUNK_RE.findAll(html)
                    .map { it.groupValues[1] }
                    .distinct()
                    .mapNotNull { ref ->
                        val url = if (ref.startsWith("http")) ref else "$baseUrl/${ref.trimStart('/')}"
                        runCatching { client.getString(url) }.getOrNull()
                    }
                    .firstOrNull { it.contains("/api/v1") }
                    .orEmpty()
                API_V1_RE.find(chunkBody)?.value ?: error("api base not found")
            }
        }
        return resolved.trimEnd('/')
    }

    private val API_V1_RE = Regex("https://[A-Za-z0-9.\\-]+/api/v1")
    private val PRECONNECT_RE = Regex("rel=\"(?:preconnect|dns-prefetch)\"\\s+href=\"(https://[^\"]+)\"")
}

// =========================== Decoder string scrape =========================

/**
 * Pulls token secret + base64 alphabet out of the site's obfuscated decoder bundle
 * at runtime, so they are not hardcoded in the extension.
 *
 * The bundle uses a javascript-obfuscator string table: RC4(key) over a custom
 * base64 alphabet, with the array rotated until a checksum matches.
 */
object DecoderScraper {
    data class Config(
        val secret: String,
        val alphabet: String,
    )

    private val stringCache = ConcurrentHashMap<String, String>()

    suspend fun scrape(client: OkHttpClient, baseUrl: String, prefs: SharedPreferences? = null): Config {
        prefs?.let { p ->
            val cachedSecret = SiteCache.decoderSecret(p)
            val cachedAlphabet = SiteCache.decoderAlphabet(p)
            if (cachedSecret != null) {
                return Config(cachedSecret, cachedAlphabet ?: DEFAULT_B64)
            }
        }

        val config = try {
            scrapeFromSite(client, baseUrl)
        } catch (e: IOException) {
            prefs?.let { SiteCache.invalidate(it) }
            scrapeFromSite(client, baseUrl)
        }
        prefs?.let { SiteCache.saveDecoder(it, config.secret, config.alphabet) }
        return config
    }

    private suspend fun scrapeFromSite(client: OkHttpClient, baseUrl: String): Config {
        val js = fetchDecoderJs(client, baseUrl)
        val strings = decodeStringTable(js)
        val secret = strings.values.firstOrNull { it.matches(SECRET_RE) }
            ?: error("decoder secret not found")
        val alphabet = strings.values.firstOrNull { it.length == 64 && ALPHABET_RE.matches(it) }
            ?: DEFAULT_B64
        return Config(secret, alphabet)
    }

    private suspend fun fetchDecoderJs(client: OkHttpClient, baseUrl: String): String {
        val home = client.getString(baseUrl)
        val chunkRefs = LinkedHashSet<String>()
        CHUNK_RE.findAll(home).forEach { chunkRefs += it.groupValues[1] }
        NESTED_CHUNK_RE.findAll(home).forEach { chunkRefs += it.groupValues[1] }

        for (ref in chunkRefs) {
            val url = if (ref.startsWith("http")) ref else "$baseUrl/${ref.trimStart('/')}"
            val body = runCatching { client.getString(url) }.getOrDefault("")
            if (body.contains("aLRCVy") && body.contains("function S(){let W=[")) {
                return body
            }
            NESTED_CHUNK_RE.findAll(body).forEach { chunkRefs += it.groupValues[1] }
        }
        error("decoder bundle not found")
    }

    private fun decodeStringTable(js: String): Map<String, String> {
        val arrayMatch = STRING_ARRAY_RE.find(js) ?: error("decoder string table not found")
        val rawStrings = parseJsStringArray(arrayMatch.groupValues[1])
        val pairs = PAIR_RE.findAll(js)
            .map { it.groupValues[1].toInt() to it.groupValues[2] }
            .distinct()
            .toList()

        val table = ArrayList(rawStrings)
        // Rotate until the bundle's integer checksum matches (same as its for(;;) loop).
        repeat(table.size) {
            val decoder = StringDecoder(table)
            if (checksum(decoder)) {
                val out = HashMap<String, String>()
                for ((index, key) in pairs) {
                    runCatching { out["$index|$key"] = decoder.decode(index, key) }
                }
                return out
            }
            table.add(table.removeAt(0))
        }
        error("decoder string table rotation failed")
    }

    private fun checksum(d: StringDecoder): Boolean = runCatching {
        val a = d.decode(194, "TzA0").toInt()
        val b = d.decode(358, "R*ME").toInt()
        val c = d.decode(380, "TzA0").toInt()
        val e = d.decode(299, "lB]H").toInt()
        val f = d.decode(136, "29CC").toInt()
        val g = d.decode(294, "1^^5").toInt()
        val h = d.decode(192, "3cvp").toInt()
        val i = d.decode(311, "ZnCW").toInt()
        val j = d.decode(385, "EG)e").toInt()
        val k = d.decode(206, "qiSG").toInt()
        val l = d.decode(290, "TzA0").toInt()
        val m = d.decode(313, "J%(R").toInt()
        val total = a / 1.0 * (b / 2.0) + c / 3.0 * (-e / 4.0) +
            -f / 5.0 + -g / 6.0 + -h / 7.0 * (i / 8.0) +
            -j / 9.0 * (k / 10.0) + -l / 11.0 * (-m / 12.0)
        total == 436543.0
    }.getOrDefault(false)

    private fun parseJsStringArray(body: String): List<String> {
        val out = ArrayList<String>()
        val re = Regex("\"((?:\\\\.|[^\"\\\\])*)\"")
        for (m in re.findAll(body)) {
            out += m.groupValues[1]
                .replace("\\\\", "\\")
                .replace("\\\"", "\"")
                .replace("\\'", "'")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
        }
        return out
    }

    private class StringDecoder(private val table: List<String>) {
        fun decode(index: Int, key: String): String {
            val cacheKey = "$index|$key"
            stringCache[cacheKey]?.let { return it }
            val adjusted = index - 127
            if (adjusted !in table.indices) error("bad index")
            val plain = rc4(customB64Decode(table[adjusted]), key)
            stringCache[cacheKey] = plain
            return plain
        }

        /** Mirrors the bundle's atob-style decoder (lowercase-first alphabet + percent-decode). */
        private fun customB64Decode(input: String): String {
            val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789+/="
            val bytes = ArrayList<Byte>()
            var acc = 0
            var count = 0
            for (ch in input) {
                val r = alphabet.indexOf(ch)
                if (r < 0) continue
                acc = if (count % 4 != 0) 64 * acc + r else r
                count++
                if (count % 4 != 0) {
                    bytes.add(((acc shr (-2 * count and 6)) and 0xff).toByte())
                }
            }
            // decodeURIComponent("%xx...") of those bytes
            return String(bytes.toByteArray(), StandardCharsets.UTF_8)
        }

        private fun rc4(input: String, key: String): String {
            val s = IntArray(256) { it }
            var j = 0
            for (i in 0 until 256) {
                j = (j + s[i] + key[i % key.length].code) % 256
                val tmp = s[i]
                s[i] = s[j]
                s[j] = tmp
            }
            val out = CharArray(input.length)
            var i = 0
            j = 0
            for (n in input.indices) {
                i = (i + 1) % 256
                j = (j + s[i]) % 256
                val tmp = s[i]
                s[i] = s[j]
                s[j] = tmp
                out[n] = (input[n].code xor s[(s[i] + s[j]) % 256]).toChar()
            }
            return out.concatToString()
        }
    }

    private val SECRET_RE = Regex("^[A-Za-z0-9_-]{43}$")
    private val ALPHABET_RE = Regex("^[A-Za-z0-9+/_-]{64}$")
    private val STRING_ARRAY_RE = Regex("function S\\(\\)\\{let W=(\\[.*?\\]);return", RegexOption.DOT_MATCHES_ALL)
    private val PAIR_RE = Regex("[kfC]\\((\\d+),\\s*\"([^\"]*)\"\\)")
    internal val CHUNK_RE = Regex("(?:src|href)=\"(/_next/static/chunks/[^\"]+\\.js)")
    private val NESTED_CHUNK_RE = Regex("static/chunks/([A-Za-z0-9_\\-\\.]+\\.js)")

    const val DEFAULT_B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
}

// ============================== Pages crypto ===============================

/**
 * Pages request token + AES-GCM payload decrypt.
 *
 * secret is scraped from the site decoder at runtime (see [DecoderScraper]).
 * tokKey = HMAC-SHA256(secret, "tok")
 * encKey = HMAC-SHA256(secret, "enc")
 * token  = base64url(0x01 || HMAC-SHA256(tokKey, path)[0..16])
 * aesKey = HMAC-SHA256(encKey, token)
 * payload.e = base64url(iv[12] || ciphertext || tag[16])
 * AAD = utf8(path) where path is "mangaSlug/chapterSlug"
 */
object PagesCrypto {
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var tokKey: ByteArray? = null

    @Volatile private var encKey: ByteArray? = null

    @Volatile private var alphabet: String = DecoderScraper.DEFAULT_B64

    suspend fun ensureLoaded(client: OkHttpClient, baseUrl: String, prefs: SharedPreferences? = null) {
        if (tokKey != null) return
        val config = DecoderScraper.scrape(client, baseUrl, prefs)
        val secretBytes = decodeBase64Url(config.secret, config.alphabet)
            ?: error("bad decoder secret")
        alphabet = config.alphabet
        tokKey = hmac(secretBytes, "tok".toByteArray(StandardCharsets.UTF_8))
        encKey = hmac(secretBytes, "enc".toByteArray(StandardCharsets.UTF_8))
    }

    fun token(mangaSlug: String, chapterSlug: String): String {
        val tok = checkNotNull(tokKey) { "PagesCrypto not loaded" }
        val path = "$mangaSlug/$chapterSlug"
        val mac = hmac(tok, path.toByteArray(StandardCharsets.UTF_8)).copyOf(16)
        val bytes = ByteArray(17)
        bytes[0] = 1
        mac.copyInto(bytes, 1)
        return encodeBase64Url(bytes)
    }

    fun decryptPages(encrypted: String, token: String, path: String): PagesPayload {
        val enc = checkNotNull(encKey) { "PagesCrypto not loaded" }
        val raw = decodeBase64Url(encrypted, alphabet) ?: error("bad payload")
        if (raw.size < 12 + 16) error("bad payload")
        val iv = raw.copyOfRange(0, 12)
        val cipherBytes = raw.copyOfRange(12, raw.size)

        val aesKey = hmac(enc, token.toByteArray(StandardCharsets.UTF_8))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(aesKey, "AES"),
            GCMParameterSpec(128, iv),
        )
        cipher.updateAAD(path.toByteArray(StandardCharsets.UTF_8))
        val plain = cipher.doFinal(cipherBytes)
        return json.decodeFromString(PagesPayload.serializer(), String(plain, StandardCharsets.UTF_8))
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun encodeBase64Url(input: ByteArray): String {
        val b64 = alphabet
        val out = StringBuilder()
        var i = 0
        while (i < input.size) {
            val b0 = input[i].toInt() and 0xff
            val b1 = if (i + 1 < input.size) input[i + 1].toInt() and 0xff else 0
            val b2 = if (i + 2 < input.size) input[i + 2].toInt() and 0xff else 0
            val n = (b0 shl 16) or (b1 shl 8) or b2
            val remain = input.size - i
            val chars = minOf(4, (remain * 8 + 5) / 6)
            for (c in 0 until chars) {
                out.append(b64[(n shr (18 - 6 * c)) and 63])
            }
            i += 3
        }
        return out.toString()
    }

    private fun decodeBase64Url(input: String, b64: String = alphabet): ByteArray? {
        val out = ArrayList<Byte>()
        var bits = 0
        var value = 0
        for (ch in input) {
            val d = b64.indexOf(ch)
            if (d < 0) return null
            value = (value shl 6) or d
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.add(((value shr bits) and 0xff).toByte())
            }
        }
        return out.toByteArray()
    }
}

// ================================= Scramble ================================

/**
 * Vertical tile shuffle used by the site's reader.
 *
 * Scramble key format: "x1." + base64url(32-byte seed).
 * The tile grid comes from an HMAC-SHA256 PRNG seeded with those 32 bytes.
 */
object Scramble {
    private const val PREFIX = "x1."
    private const val KEY_LENGTH = 46
    private const val SEED_LENGTH = 32
    private val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun shouldDescramble(urlFragment: String?): String? {
        if (urlFragment.isNullOrEmpty()) return null
        if (!urlFragment.startsWith(PREFIX) || urlFragment.length != KEY_LENGTH) return null
        return urlFragment
    }

    fun descramble(bitmap: Bitmap, key: String): Bitmap {
        val seed = decodeBase64Url(key.removePrefix(PREFIX)) ?: return bitmap
        if (seed.size != SEED_LENGTH) return bitmap

        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return bitmap

        val layout = buildLayout(seed, height) ?: return bitmap
        val out = Bitmap.createBitmap(width, height, bitmap.config ?: Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        for ((dstY, srcY, tileH) in layout) {
            canvas.drawBitmap(
                bitmap,
                Rect(0, srcY, width, srcY + tileH),
                Rect(0, dstY, width, dstY + tileH),
                null,
            )
        }
        return out
    }

    /** @return (dstY, srcY, tileH) triples, matching the site's paint() layout. */
    private fun buildLayout(seed: ByteArray, height: Int): List<Triple<Int, Int, Int>>? {
        val rng = HmacPrng(seed)
        val rows = 8 + rng.nextInt(13)
        val tileH = 16 * (height / (16 * rows))
        if (tileH < 16) return null

        val perm = IntArray(rows) { it }
        for (i in rows - 1 downTo 1) {
            val j = rng.nextInt(i + 1)
            val tmp = perm[i]
            perm[i] = perm[j]
            perm[j] = tmp
        }

        val total = rows * tileH
        if (total > height) return null

        val layout = ArrayList<Triple<Int, Int, Int>>(rows + 1)
        for (i in 0 until rows) {
            // paint(): drawImage(img, 0, i*tileH, w, tileH, 0, perm[i]*tileH, w, tileH)
            layout.add(Triple(perm[i] * tileH, i * tileH, tileH))
        }
        if (height > total) {
            layout.add(Triple(total, total, height - total))
        }
        return layout
    }

    private fun decodeBase64Url(input: String): ByteArray? {
        val out = ArrayList<Byte>()
        var bits = 0
        var value = 0
        for (ch in input) {
            val d = B64.indexOf(ch)
            if (d < 0) return null
            value = (value shl 6) or d
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.add(((value shr bits) and 0xff).toByte())
            }
        }
        return out.toByteArray()
    }
}

private class HmacPrng(seed: ByteArray) {
    private val mac = Mac.getInstance("HmacSHA256").apply {
        init(SecretKeySpec(seed, "HmacSHA256"))
    }
    private var counter = 0
    private var buf = ByteArray(0)
    private var pos = 0

    private fun nextUint32(): Int {
        if (pos >= buf.size) {
            val msg = byteArrayOf(
                (counter ushr 24).toByte(),
                (counter ushr 16).toByte(),
                (counter ushr 8).toByte(),
                counter.toByte(),
            )
            counter++
            buf = mac.doFinal(msg)
            pos = 0
        }
        val v = ((buf[pos].toInt() and 0xff) shl 24) or
            ((buf[pos + 1].toInt() and 0xff) shl 16) or
            ((buf[pos + 2].toInt() and 0xff) shl 8) or
            (buf[pos + 3].toInt() and 0xff)
        pos += 4
        return v
    }

    fun nextInt(n: Int): Int {
        val limit = 0x100000000L / n * n
        var r = nextUint32().toLong() and 0xffffffffL
        while (r >= limit) {
            r = nextUint32().toLong() and 0xffffffffL
        }
        return (r % n).toInt()
    }
}

// ============================ Image interceptor ============================

class ScrambleInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val key = Scramble.shouldDescramble(request.url.fragment)
            ?: return chain.proceed(request)

        val cleanUrl = request.url.newBuilder().fragment(null).build()
        val response = chain.proceed(request.newBuilder().url(cleanUrl).build())
        if (!response.isSuccessful) return response

        val bytes = response.body?.bytes() ?: return response
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: return response.withBody(bytes)

        val out = try {
            Scramble.descramble(decoded, key)
        } catch (_: Exception) {
            decoded
        }
        if (out != decoded) {
            decoded.recycle()
        }

        val bos = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.PNG, 100, bos)
        out.recycle()
        return response.withBody(bos.toByteArray(), "image/png")
    }

    private fun Response.withBody(bytes: ByteArray, mime: String = "image/*"): Response = newBuilder()
        .body(bytes.toResponseBody(mime.toMediaType()))
        .build()
}
