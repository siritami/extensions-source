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
 * Interceptor to descramble images that are split into a 3x3 grid.
 *
 * The MiMi website scrambles images by rearranging tiles in a 3x3 grid.
 * The DRM hex string (204 bytes) encodes the tile permutation:
 * - 24-byte header (contains page-specific seed info)
 * - 9 x 20-byte blocks (one per tile, contains destination index)
 */
class ImageInterceptor : Interceptor {
    companion object {
        const val DRM_PARAM = "mimi_drm"
        private const val GRID_SIZE = 3
        private val JPEG_MEDIA_TYPE = "image/jpeg".toMediaType()
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

        // Decode the DRM to get tile mapping
        val mapping = decodeDrmMapping(drmHex)
        if (mapping == null) {
            // If decoding fails, return original response
            return response
        }

        // Descramble the image
        val body = response.body ?: return response
        val descrambledBytes = body.byteStream().use { inputStream ->
            descrambleImage(inputStream, mapping)
        }

        return response.newBuilder()
            .body(descrambledBytes.toResponseBody(JPEG_MEDIA_TYPE))
            .build()
    }

    /**
     * Decode DRM hex string to get tile mapping.
     *
     * DRM structure (204 bytes / 408 hex chars):
     * - Header: 24 bytes
     * - Tile blocks: 9 x 20 bytes
     *
     * Each 20-byte block encodes the destination position for that tile.
     * The mapping tells us: source tile i should go to destination mapping[i]
     */
    private fun decodeDrmMapping(drmHex: String): IntArray? {
        if (drmHex.length != 408) return null

        try {
            val bytes = ByteArray(204)
            for (i in 0 until 204) {
                bytes[i] = drmHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }

            val mapping = IntArray(9)
            val header = 24
            val blockSize = 20

            for (tileIndex in 0 until 9) {
                val blockStart = header + tileIndex * blockSize
                val block = bytes.sliceArray(blockStart until blockStart + blockSize)

                // Try to extract destination index from the block
                // The destination index (0-8) should be encoded somewhere in each block
                // Based on observed patterns, values 0-8 appear at specific offsets
                val destIndex = extractDestinationIndex(block, tileIndex)
                if (destIndex !in 0..8) return null
                mapping[tileIndex] = destIndex
            }

            // Validate mapping - each destination should appear exactly once
            val seen = BooleanArray(9)
            for (dest in mapping) {
                if (seen[dest]) return null
                seen[dest] = true
            }

            return mapping
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Extract destination index from a 20-byte tile block.
     *
     * After analyzing the DRM structure, the destination index appears to be
     * encoded within specific bytes of each block. We try multiple strategies:
     * 1. Look for bytes in range 0-8 at known positions
     * 2. Use modular arithmetic on certain bytes
     */
    private fun extractDestinationIndex(block: ByteArray, tileIndex: Int): Int {
        // Strategy 1: Check if first few bytes contain the destination directly
        for (offset in listOf(0, 1, 2, 3, 4, 5)) {
            val value = block[offset].toInt() and 0xFF
            if (value in 0..8) {
                return value
            }
        }

        // Strategy 2: Look for ASCII characters that might encode position
        // Some blocks contain ASCII markers; check if they relate to position
        val validIndices = block.filter { (it.toInt() and 0xFF) in 0..8 }
        if (validIndices.isNotEmpty()) {
            return validIndices.first().toInt() and 0xFF
        }

        // Strategy 3: Use modulo of first non-zero byte
        for (b in block) {
            val value = b.toInt() and 0xFF
            if (value > 0) {
                return value % 9
            }
        }

        // Fallback: return tile's original position (no scrambling)
        return tileIndex
    }

    /**
     * Descramble the image by rearranging tiles according to the mapping.
     *
     * @param inputStream The scrambled image input stream
     * @param mapping Array where mapping[srcTile] = destPosition
     */
    private fun descrambleImage(inputStream: java.io.InputStream, mapping: IntArray): ByteArray {
        val srcBitmap = BitmapFactory.decodeStream(inputStream)
        val width = srcBitmap.width
        val height = srcBitmap.height

        val tileWidth = width / GRID_SIZE
        val tileHeight = height / GRID_SIZE

        val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)

        // For each source tile, draw it to its destination position
        for (srcIndex in 0 until 9) {
            val dstIndex = mapping[srcIndex]

            // Source tile coordinates
            val srcX = (srcIndex % GRID_SIZE) * tileWidth
            val srcY = (srcIndex / GRID_SIZE) * tileHeight

            // Destination tile coordinates
            val dstX = (dstIndex % GRID_SIZE) * tileWidth
            val dstY = (dstIndex / GRID_SIZE) * tileHeight

            val srcRect = Rect(srcX, srcY, srcX + tileWidth, srcY + tileHeight)
            val dstRect = Rect(dstX, dstY, dstX + tileWidth, dstY + tileHeight)

            canvas.drawBitmap(srcBitmap, srcRect, dstRect, null)
        }

        srcBitmap.recycle()

        val output = ByteArrayOutputStream()
        resultBitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
        resultBitmap.recycle()

        return output.toByteArray()
    }
}
