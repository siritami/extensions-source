package eu.kanade.tachiyomi.extension.vi.truyenmm

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import java.io.IOException

class TallImageInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.fragment != tallImageFragment) return chain.proceed(request)

        val response = chain.proceed(request)
        if (!response.isSuccessful) return response

        val source = response.body.byteStream().use(BitmapFactory::decodeStream)
            ?: throw IOException("Failed to decode tall image")
        val resized = if (source.height > maxImageHeight) {
            val width = source.width * maxImageHeight / source.height
            Bitmap.createScaledBitmap(source, width, maxImageHeight, true)
        } else {
            source
        }

        return try {
            val output = Buffer()
            if (!resized.compress(Bitmap.CompressFormat.JPEG, 95, output.outputStream())) {
                throw IOException("Failed to encode tall image")
            }

            response.newBuilder()
                .body(output.asResponseBody("image/jpeg".toMediaType()))
                .build()
        } finally {
            if (resized !== source) resized.recycle()
            source.recycle()
        }
    }

    private val maxImageHeight = 6500
}

internal const val tallImageFragment = "truyenmm-tall-image"