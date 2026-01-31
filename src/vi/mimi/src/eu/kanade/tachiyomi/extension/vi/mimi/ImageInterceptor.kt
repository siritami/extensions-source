package eu.kanade.tachiyomi.extension.vi.mimi

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import eu.kanade.tachiyomi.lib.seedrandom.SeedRandom
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream

/**
 * Interceptor to descramble images that are split into a 3x3 grid.
 *
 * The MiMi website scrambles images by shuffling tiles in a 3x3 grid.
 * The DRM hex string is used as a seed for a SeedRandom PRNG to generate
 * the shuffle permutation.
 */
class ImageInterceptor : Interceptor {
    companion object {
        const val DRM_PARAM = "mimi_drm"
        private const val GRID_SIZE = 3
        private const val TILE_COUNT = GRID_SIZE * GRID_SIZE
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

        // Descramble the image using DRM as seed
        val body = response.body ?: return response
        val descrambledBytes = body.byteStream().use { inputStream ->
            descrambleImage(inputStream, drmHex)
        }

        return response.newBuilder()
            .body(descrambledBytes.toResponseBody(JPEG_MEDIA_TYPE))
            .build()
    }

    /**
     * Descramble the image by rearranging tiles according to the shuffle permutation.
     *
     * The website uses a seeded PRNG to shuffle tile positions.
     * We use the same algorithm to generate the same permutation and reverse it.
     */
    private fun descrambleImage(inputStream: java.io.InputStream, drmHex: String): ByteArray {
        val srcBitmap = BitmapFactory.decodeStream(inputStream)
        val width = srcBitmap.width
        val height = srcBitmap.height

        val tileWidth = width / GRID_SIZE
        val tileHeight = height / GRID_SIZE

        val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)

        // Generate the shuffle permutation using DRM as seed
        val rng = SeedRandom(drmHex)

        // Create list of original indices [0, 1, 2, 3, 4, 5, 6, 7, 8]
        val originalIndices = (0 until TILE_COUNT).toMutableList()

        // Shuffle to get the mapping: shuffledIndices[dstIndex] = srcIndex
        // This means: final tile at dstIndex comes from scrambled tile at srcIndex
        val shuffledIndices = rng.shuffle(originalIndices)

        // For each destination position, read from the source position indicated by the shuffle
        for (dstIndex in 0 until TILE_COUNT) {
            val srcIndex = shuffledIndices[dstIndex]

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
}
