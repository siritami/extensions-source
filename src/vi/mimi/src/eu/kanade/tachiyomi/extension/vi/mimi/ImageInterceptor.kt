package eu.kanade.tachiyomi.extension.vi.mimi

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
 * Interceptor to descramble DRM-protected images from MiMi.
 *
 * MiMi scrambles images using a 3x3 tile grid. The DRM string (204 bytes hex)
 * contains marker bytes at specific offsets that determine the tile permutation.
 */
class ImageInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val fragment = request.url.fragment

        // Check if this request has DRM data
        if (fragment.isNullOrEmpty() || !fragment.startsWith("drm=")) {
            return chain.proceed(request)
        }

        val drmHex = fragment.substringAfter("drm=")
        val response = chain.proceed(request)

        // Only process successful image responses
        if (!response.isSuccessful) {
            return response
        }

        val descrambledBody = descrambleImage(response, drmHex)
        return response.newBuilder()
            .body(descrambledBody)
            .build()
    }

    /**
     * Descramble the image by rearranging tiles according to the DRM permutation.
     */
    private fun descrambleImage(response: Response, drmHex: String): okhttp3.ResponseBody {
        val drmBytes = hexToBytes(drmHex)
        val permutation = computePermutation(drmBytes)

        val originalBitmap = BitmapFactory.decodeStream(response.body.byteStream())
            ?: throw Exception("Failed to decode scrambled image")

        val width = originalBitmap.width
        val height = originalBitmap.height

        // Calculate tile dimensions (3x3 grid)
        val tileWidth = width / GRID_SIZE
        val tileHeight = height / GRID_SIZE

        val descrambledBitmap = Bitmap.createBitmap(width, height, originalBitmap.config)
        val canvas = Canvas(descrambledBitmap)

        // Rearrange tiles according to the permutation
        // permutation[i] = destination index for tile at source index i
        for (srcIndex in 0 until TILE_COUNT) {
            val dstIndex = permutation[srcIndex]

            val srcX = (srcIndex % GRID_SIZE) * tileWidth
            val srcY = (srcIndex / GRID_SIZE) * tileHeight
            val dstX = (dstIndex % GRID_SIZE) * tileWidth
            val dstY = (dstIndex / GRID_SIZE) * tileHeight

            val srcRect = Rect(srcX, srcY, srcX + tileWidth, srcY + tileHeight)
            val dstRect = Rect(dstX, dstY, dstX + tileWidth, dstY + tileHeight)

            canvas.drawBitmap(originalBitmap, srcRect, dstRect, null)
        }

        originalBitmap.recycle()

        val outputStream = ByteArrayOutputStream()
        descrambledBitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
        descrambledBitmap.recycle()

        return outputStream.toByteArray().toResponseBody("image/jpeg".toMediaType())
    }

    /**
     * Compute the tile permutation from DRM bytes.
     *
     * The DRM string has marker bytes at specific offsets. Each marker is used
     * to determine the destination tile index via modulo 9.
     */
    private fun computePermutation(drmBytes: ByteArray): IntArray {
        val permutation = IntArray(TILE_COUNT)
        val used = BooleanArray(TILE_COUNT)

        for (i in 0 until TILE_COUNT) {
            val offset = MARKER_OFFSETS[i]
            if (offset >= drmBytes.size) {
                // Fallback: use identity mapping for this tile
                permutation[i] = findNextAvailable(used)
                continue
            }

            val marker = drmBytes[offset].toInt() and 0xFF
            var destIndex = marker % TILE_COUNT

            // Handle collisions: find next available slot
            while (used[destIndex]) {
                destIndex = (destIndex + 1) % TILE_COUNT
            }

            permutation[i] = destIndex
            used[destIndex] = true
        }

        return permutation
    }

    /**
     * Find the next available (unused) tile index.
     */
    private fun findNextAvailable(used: BooleanArray): Int {
        for (i in used.indices) {
            if (!used[i]) {
                used[i] = true
                return i
            }
        }
        return 0 // Should never reach here
    }

    /**
     * Convert hex string to byte array.
     */
    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    companion object {
        private const val GRID_SIZE = 3
        private const val TILE_COUNT = 9

        /**
         * Byte offsets in the DRM string where marker bytes are located.
         * These markers determine the destination index for each tile.
         */
        private val MARKER_OFFSETS = intArrayOf(20, 38, 58, 78, 98, 120, 142, 162, 184)
    }
}
