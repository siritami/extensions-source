package eu.kanade.tachiyomi.extension.vi.truyenmm

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import java.io.IOException

class FirstPageInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.fragment != firstPageFragment) return chain.proceed(request)

        val response = chain.proceed(request)
        if (!response.isSuccessful) return response

        val source = response.body.byteStream().use(BitmapFactory::decodeStream)
            ?: throw IOException("Failed to decode first page")
        val targetHeight = source.height * targetWidth / source.width
        val resized = try {
            Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
        } catch (error: Throwable) {
            source.recycle()
            throw error
        }

        return try {
            val output = Buffer()
            if (!resized.compress(Bitmap.CompressFormat.JPEG, 95, output.outputStream())) {
                throw IOException("Failed to encode first page")
            }

            response.newBuilder()
                .body(output.asResponseBody("image/jpeg".toMediaType()))
                .build()
        } finally {
            resized.recycle()
            source.recycle()
        }
    }

    private val targetWidth = 720
}

internal const val firstPageFragment = "truyenmm-first-page"