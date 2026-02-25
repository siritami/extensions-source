package eu.kanade.tachiyomi.extension.vi.yurigarden

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer

class ImageDescrambler : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val fragment = request.url.fragment ?: return response
        if (!fragment.contains("KEY=")) return response

        val key = fragment.substringAfter("KEY=")
        val image = response.body.use { BitmapFactory.decodeStream(it.byteStream()) }
            ?: return response

        val descrambled = unscrambleImage(image, key)

        val responseBody = Buffer().run {
            descrambled.compress(Bitmap.CompressFormat.JPEG, 90, outputStream())
            asResponseBody(MEDIA_TYPE)
        }
        return response.newBuilder().body(responseBody).build()
    }

    private fun unscrambleImage(bitmap: Bitmap, key: String): Bitmap {
        val parts = PARTS
        val strips = computeStrips(key, bitmap.height, parts)

        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        var dy = 0
        for (strip in strips) {
            val src = Rect(0, strip.y, bitmap.width, strip.y + strip.h)
            val dst = Rect(0, dy, bitmap.width, dy + strip.h)
            canvas.drawBitmap(bitmap, src, dst, null)
            dy += strip.h
        }
        return result
    }

    /**
     * Port of the JS descrambling algorithm from YuriGarden.
     * Computes the strip positions for unscrambling the image.
     *
     * @param key The scramble key (Base58 encoded permutation)
     * @param height The image height in pixels
     * @param parts Number of strips the image is divided into
     * @return List of Strip objects with y-offset and height for each strip in correct order
     */
    private fun computeStrips(key: String, height: Int, parts: Int): List<Strip> {
        // Decode the permutation from the key (skip first 4 chars of key)
        val encoded = key.substring(4)
        val permutation = decodePermutation(encoded, parts)

        // Invert the permutation to get unscramble order
        val inverse = invertPermutation(permutation)

        // Calculate strip heights, distributing remainder pixels
        // The total usable height is: height - 4 * (parts - 1) because there are 4px gaps
        val usableHeight = height - 4 * (parts - 1)
        val stripHeights = distributeHeight(usableHeight, parts)

        // Map each permutation index to its strip height
        val mappedHeights = permutation.map { stripHeights[it] }

        // Calculate cumulative y-positions (accounting for 4px gaps between strips)
        val yPositions = IntArray(parts)
        for (i in 0 until parts) {
            yPositions[i] = if (i == 0) 0 else yPositions[i - 1] + mappedHeights[i - 1] + 4
        }

        // Build strip descriptors
        val strips = List(parts) { i ->
            Strip(y = yPositions[i], h = mappedHeights[i])
        }

        // Return strips in unscrambled order
        return inverse.map { strips[it] }
    }

    /**
     * Decode a Base58-encoded permutation with checksum validation.
     * Format: "H" + base58_encoded_data + checksum_char
     */
    private fun decodePermutation(encoded: String, parts: Int): List<Int> {
        require(encoded.matches(BASE58_PATTERN)) { "Bad Base58" }

        val data = encoded.substring(1, encoded.length - 1)
        val checkChar = encoded.last()
        val value = base58Decode(data)

        require(ALPHABET[value % 58] == checkChar) { "Checksum mismatch" }

        return lehmerDecode(value, parts)
    }

    /**
     * Decode a Base58 string to a number.
     */
    private fun base58Decode(str: String): Long {
        var result = 0L
        for (ch in str) {
            val index = ALPHABET.indexOf(ch)
            require(index >= 0) { "Invalid Base58 char" }
            result = result * 58 + index
        }
        return result
    }

    /**
     * Convert a Lehmer code (factoradic number) to a permutation.
     */
    private fun lehmerDecode(encoding: Long, size: Int): List<Int> {
        var remaining = encoding
        val available = (0 until size).toMutableList()
        val result = mutableListOf<Int>()

        for (i in size - 1 downTo 0) {
            val factorial = FACTORIALS[i]
            val index = (remaining / factorial).toInt()
            remaining %= factorial
            result.add(available.removeAt(index))
        }
        return result
    }

    /**
     * Invert a permutation: if perm[i] = j, then inverse[j] = i.
     */
    private fun invertPermutation(perm: List<Int>): List<Int> {
        val inverse = IntArray(perm.size)
        perm.forEachIndexed { i, v -> inverse[v] = i }
        return inverse.toList()
    }

    /**
     * Distribute height evenly among parts, with remainder pixels
     * going to the first few strips.
     */
    private fun distributeHeight(height: Int, parts: Int): List<Int> {
        val base = height / parts
        val remainder = height % parts
        return List(parts) { i -> base + if (i < remainder) 1 else 0 }
    }

    private data class Strip(val y: Int, val h: Int)

    companion object {
        private const val PARTS = 10

        private val MEDIA_TYPE = "image/jpeg".toMediaType()

        private val BASE58_PATTERN = Regex("^H[1-9A-HJ-NP-Za-km-z]+$")

        /**
         * Custom Base58 alphabet used by YuriGarden.
         * Generated from char codes: 49-57, 65-90 (skip 73,79), 97-122 (skip 108)
         */
        private val ALPHABET: String = buildString {
            val codes = intArrayOf(
                49, 50, 51, 52, 53, 54, 55, 56, 57,
                65, 66, 67, 68, 69, 70, 71, 72, 74, 75, 76, 77, 78,
                80, 81, 82, 83, 84, 85, 86, 87, 88, 89, 90,
                97, 98, 99, 100, 101, 102, 103, 104, 105, 106, 107,
                109, 110, 111, 112, 113, 114, 115, 116, 117, 118, 119,
                120, 121, 122,
            )
            for (code in codes) {
                append(code.toChar())
            }
        }

        /**
         * Pre-computed factorials for Lehmer code decoding (0! to 10!).
         */
        private val FACTORIALS = LongArray(11).also { f ->
            f[0] = 1L
            for (i in 1..10) {
                f[i] = f[i - 1] * i
            }
        }
    }
}
