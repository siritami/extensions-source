package eu.kanade.tachiyomi.extension.vi.truyenmm

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream

class FirstPageInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.queryParameter(FIRST_PAGE_QUERY_PARAMETER) != FIRST_PAGE_QUERY_VALUE) {
            return chain.proceed(request)
        }

        val imageUrl = request.url.newBuilder()
            .removeAllQueryParameters(FIRST_PAGE_QUERY_PARAMETER)
            .build()
        val response = chain.proceed(request.newBuilder().url(imageUrl).build())
        if (!response.isSuccessful) return response

        val mediaType = response.body.contentType()
        val image = response.body.bytes()
        val bitmap = BitmapFactory.decodeByteArray(
            image,
            0,
            image.size,
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                inSampleSize = FIRST_PAGE_SAMPLE_SIZE
            },
        ) ?: return response.newBuilder()
            .body(image.toResponseBody(mediaType))
            .build()

        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
        bitmap.recycle()

        return response.newBuilder()
            .body(output.toByteArray().toResponseBody(mediaType))
            .build()
    }

    private companion object {
        const val FIRST_PAGE_SAMPLE_SIZE = 2
        const val JPEG_QUALITY = 90
    }
}

internal const val FIRST_PAGE_QUERY_PARAMETER = "truyenmm_first_page"
internal const val FIRST_PAGE_QUERY_VALUE = "1"
