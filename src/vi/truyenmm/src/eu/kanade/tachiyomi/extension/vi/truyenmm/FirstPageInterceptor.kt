package eu.kanade.tachiyomi.extension.vi.truyenmm

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody

class FirstPageInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.fragment != FIRST_PAGE_FRAGMENT) return chain.proceed(request)

        val response = chain.proceed(request)
        if (!response.isSuccessful) return response

        val mediaType = response.body.contentType()
        val image = response.body.bytes()
        normalizeWidth(image)

        return response.newBuilder()
            .body(image.asResponseBody(mediaType))
            .build()
    }

    private fun normalizeWidth(image: ByteArray) {
        if (image.size < JPEG_HEADER_SIZE || image[0] != MARKER_START || image[1] != START_OF_IMAGE) return

        var offset = 2
        while (offset + JPEG_HEADER_SIZE <= image.size) {
            if (image[offset] != MARKER_START) {
                offset++
                continue
            }

            while (offset < image.size && image[offset] == MARKER_START) offset++
            if (offset >= image.size) return

            val marker = image[offset].toInt() and 0xFF
            offset++
            if (marker == START_OF_SCAN || marker == END_OF_IMAGE) return
            if (marker == TEM || marker in RESTART_MARKER_RANGE) continue
            if (offset + 1 >= image.size) return

            val segmentLength = image.readUnsignedShort(offset)
            if (segmentLength < 2 || offset + segmentLength > image.size) return

            if (marker in START_OF_FRAME_MARKERS && segmentLength >= START_OF_FRAME_SIZE) {
                val widthOffset = offset + 5
                if (image.readUnsignedShort(widthOffset) == SOURCE_WIDTH) {
                    image[widthOffset] = (NORMALIZED_WIDTH shr 8).toByte()
                    image[widthOffset + 1] = NORMALIZED_WIDTH.toByte()
                }
                return
            }

            offset += segmentLength
        }
    }

    private fun ByteArray.readUnsignedShort(offset: Int): Int =
        ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)

    private companion object {
        const val JPEG_HEADER_SIZE = 10
        const val START_OF_FRAME_SIZE = 8
        const val SOURCE_WIDTH = 729
        const val NORMALIZED_WIDTH = 728
        const val MARKER_START = 0xFF.toByte()
        const val START_OF_IMAGE = 0xD8.toByte()
        const val START_OF_SCAN = 0xDA
        const val END_OF_IMAGE = 0xD9
        const val TEM = 0x01
        val RESTART_MARKER_RANGE = 0xD0..0xD7
        val START_OF_FRAME_MARKERS = setOf(0xC0, 0xC1, 0xC2)
    }
}

internal const val FIRST_PAGE_FRAGMENT = "truyenmm-first-page"