package eu.kanade.tachiyomi.extension.vi.yurigarden

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream

/**
 * Algorithm overview:
 * 1. Decode the scramble key (Base58 with checksum) into a permutation
 * 2. Compute strip positions (image split into [PARTS] horizontal strips with 4px gaps)
 * 3. Reassemble strips in the correct order using the inverse permutation
 */
class ImageDescrambler : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val fragment = response.request.url.fragment ?: return response
        if (!fragment.contains("KEY=")) return response

        val key = fragment.substringAfter("KEY=")
        val bitmap = BitmapFactory.decodeStream(response.body.byteStream())
            ?: return response

        val descrambled = unscrambleImage(bitmap, key)

        val output = ByteArrayOutputStream()
        descrambled.compress(Bitmap.CompressFormat.JPEG, 90, output)

        return response.newBuilder()
            .body(output.toByteArray().toResponseBody(MEDIA_TYPE))
            .build()
    }

    /**
     * Reassembles a scrambled image by drawing strips in the correct order.
     */
    private fun unscrambleImage(bitmap: Bitmap, key: String): Bitmap {
        val strips = computeStrips(key, bitmap.height, PARTS)

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
     * Computes the ordered list of strips to reconstruct the original image.
     *
     * Corresponds to JS function `_X(K, H, P)` in the YuriGarden frontend:
     * 1. Decode the key into a permutation (how strips were shuffled)
     * 2. Compute each strip's height (total height minus 4px gaps, distributed evenly)
     * 3. Calculate each strip's Y position in the scrambled image (including 4px gaps)
     * 4. Return strips ordered by the inverse permutation (unscrambled order)
     */
    private fun computeStrips(key: String, height: Int, parts: Int): List<Strip> {
        val permutation = decodePermutation(key.substring(4), parts)
        val inverse = invertPermutation(permutation)
        val stripHeights = distributeHeight(height - 4 * (parts - 1), parts)

        // Map permutation indices to their corresponding strip heights
        val mappedHeights = permutation.map { stripHeights[it] }

        // Build strip positions: each strip starts after the previous one + 4px gap
        var cumulative = 0
        val strips = mappedHeights.mapIndexed { i, h ->
            val y = if (i == 0) 0 else cumulative + 4 * i
            cumulative += h
            Strip(y, h)
        }

        // Reorder strips using the inverse permutation to get the original order
        return inverse.map { strips[it] }
    }

    /**
     * Decodes a Base58-encoded permutation string with checksum verification.
     *
     * Format: `H<base58_data><checksum_char>`
     * - First char 'H' is a version marker (stripped by caller via substring(4))
     * - Middle chars are Base58-encoded factoradic number
     * - Last char is a checksum: `ALPHABET[value % 58]`
     *
     * Corresponds to JS function `_U(enc, p)`.
     */
    private fun decodePermutation(encoded: String, parts: Int): List<Int> {
        val data = encoded.substring(1, encoded.length - 1)
        val checkChar = encoded.last()
        val value = base58Decode(data)

        require(ALPHABET[(value % 58).toInt()] == checkChar) { "Checksum mismatch" }

        return lehmerDecode(value, parts)
    }

    /**
     * Decodes a Base58 string into a numeric value using the custom alphabet.
     *
     * Corresponds to JS function `_S(str)`.
     */
    private fun base58Decode(str: String): Long {
        var result = 0L
        for (ch in str) {
            val index = ALPHABET.indexOf(ch)
            result = result * 58 + index
        }
        return result
    }

    /**
     * Converts a factoradic-encoded number into a permutation using Lehmer code.
     *
     * The factoradic number system represents each digit as an index into a
     * shrinking list of available elements, producing a unique permutation.
     *
     * Corresponds to JS function `_I(E, P)`.
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
     * Inverts a permutation: if `perm[i] = v`, then `inverse[v] = i`.
     *
     * Corresponds to JS function `_D(e)`.
     */
    private fun invertPermutation(perm: List<Int>): List<Int> {
        val inverse = IntArray(perm.size)
        perm.forEachIndexed { i, v -> inverse[v] = i }
        return inverse.toList()
    }

    /**
     * Distributes total height evenly across [parts], with any remainder
     * distributed one extra pixel to the first few strips.
     *
     * Corresponds to JS function `_P(h, p)`.
     */
    private fun distributeHeight(height: Int, parts: Int): List<Int> {
        val base = height / parts
        val remainder = height % parts
        return List(parts) { i -> base + if (i < remainder) 1 else 0 }
    }

    private data class Strip(val y: Int, val h: Int)

    companion object {
        /** Number of horizontal strips the image is divided into. */
        private const val PARTS = 10

        private val MEDIA_TYPE = "image/jpeg".toMediaType()

        /**
         * Custom Base58 alphabet used by YuriGarden's scramble key encoding.
         * Characters: 1-9, A-N, P-Z, a-k, m-z (excludes 0, O, l, I to avoid ambiguity).
         */
        private val ALPHABET: String = intArrayOf(
            49, 50, 51, 52, 53, 54, 55, 56, 57,
            65, 66, 67, 68, 69, 70, 71, 72, 74, 75, 76, 77, 78,
            80, 81, 82, 83, 84, 85, 86, 87, 88, 89, 90,
            97, 98, 99, 100, 101, 102, 103, 104, 105, 106, 107,
            109, 110, 111, 112, 113, 114, 115, 116, 117, 118, 119,
            120, 121, 122,
        ).map { it.toChar() }.joinToString("")

        /** Pre-computed factorials 0! through 10! for Lehmer code decoding. */
        private val FACTORIALS = LongArray(11).also { f ->
            f[0] = 1L
            for (i in 1..10) {
                f[i] = f[i - 1] * i
            }
        }
    }
}
