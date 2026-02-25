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
import kotlin.math.PI

class MiMiImageDescrambler : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val fragment = request.url.fragment
        val response = chain.proceed(request)

        if (fragment == null || !fragment.startsWith(DRM_PREFIX)) {
            return response
        }

        if (!response.isSuccessful) {
            return response
        }

        val drmData = fragment.substringAfter(DRM_PREFIX)
        val metadata = decodeMetadata(drmData)

        val inputBytes = response.body.bytes()
        val input = BitmapFactory.decodeByteArray(inputBytes, 0, inputBytes.size)
            ?: return response.newBuilder()
                .body(inputBytes.toResponseBody())
                .build()

        val result = descramble(input, metadata)

        val output = ByteArrayOutputStream()
        result.compress(Bitmap.CompressFormat.JPEG, 90, output)

        input.recycle()
        result.recycle()

        return response.newBuilder()
            .body(output.toByteArray().toResponseBody(JPEG_MEDIA_TYPE))
            .build()
    }

    private fun descramble(bitmap: Bitmap, metadata: TileMetadata): Bitmap {
        val sw = metadata.sourceWidth
        val sh = metadata.sourceHeight
        if (sw <= 0 || sh <= 0) return bitmap

        val fullW = bitmap.width
        val fullH = bitmap.height

        // Create a working bitmap from the scrambled region
        val working = Bitmap.createBitmap(bitmap, 0, 0, sw.coerceAtMost(fullW), sh.coerceAtMost(fullH))

        // Calculate default 3x3 grid dimensions
        val baseW = sw / 3
        val baseH = sh / 3
        val remainderW = sw % 3
        val remainderH = sh % 3

        val defaultDims = HashMap<String, IntArray>()
        for (key in TILE_KEYS) {
            val row = key[0].digitToInt()
            val col = key[1].digitToInt()
            val w = baseW + if (col == 2) remainderW else 0
            val h = baseH + if (row == 2) remainderH else 0
            defaultDims[key] = intArrayOf(col * baseW, row * baseH, w, h)
        }

        // Merge with any custom dimensions from metadata
        val dims = HashMap<String, IntArray>()
        for (key in TILE_KEYS) {
            dims[key] = metadata.tileDims[key] ?: defaultDims.getValue(key)
        }

        // Build inverse position map: destination -> source
        val inversePos = HashMap<String, String>()
        for ((src, dst) in metadata.tilePositions) {
            inversePos[dst] = src
        }

        // Create result bitmap
        val result = Bitmap.createBitmap(fullW, fullH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        // Rearrange tiles
        for (key in TILE_KEYS) {
            val srcKey = inversePos[key] ?: continue
            val s = dims.getValue(key)
            val d = dims.getValue(srcKey)
            canvas.drawBitmap(
                working,
                Rect(s[0], s[1], s[0] + s[2], s[1] + s[3]),
                Rect(d[0], d[1], d[0] + d[2], d[1] + d[3]),
                null,
            )
        }

        // Copy any remaining area below the scrambled region
        if (sh < fullH) {
            canvas.drawBitmap(
                bitmap,
                Rect(0, sh, fullW, fullH),
                Rect(0, sh, fullW, fullH),
                null,
            )
        }
        // Copy any remaining area to the right of the scrambled region
        if (sw < fullW) {
            canvas.drawBitmap(
                bitmap,
                Rect(sw, 0, fullW, sh),
                Rect(sw, 0, fullW, sh),
                null,
            )
        }

        working.recycle()
        return result
    }

    private fun decodeMetadata(hexData: String): TileMetadata {
        val gt = decryptDrm(hexData)
        return parseMetadata(gt)
    }

    private fun decryptDrm(hexData: String): String {
        val strategyStr = hexData.takeLast(2)
        val strategy = strategyStr.toInt(10)
        val encryptionKey = getEncryptionKey(strategy)
        val encryptedHex = hexData.dropLast(2)
        val encryptedBytes = hexToBytes(encryptedHex)
        val keyBytes = encryptionKey.toByteArray(Charsets.UTF_8)
        val decrypted = ByteArray(encryptedBytes.size)

        for (i in encryptedBytes.indices) {
            decrypted[i] = (encryptedBytes[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
        }

        return decrypted.toString(Charsets.UTF_8)
    }

    private fun parseMetadata(gt: String): TileMetadata {
        var sw = 0
        var sh = 0
        val positions = HashMap<String, String>()
        val dims = HashMap<String, IntArray>()

        for (token in gt.split("|")) {
            when {
                token.startsWith("sw:") -> sw = token.substring(3).toInt()
                token.startsWith("sh:") -> sh = token.substring(3).toInt()
                token.contains("@") && token.contains(">") -> {
                    val (left, right) = token.split(">")
                    val (name, rectStr) = left.split("@")
                    val parts = rectStr.split(",").map { it.toInt() }
                    dims[name] = intArrayOf(parts[0], parts[1], parts[2], parts[3])
                    positions[name] = right
                }
            }
        }

        return TileMetadata(sw, sh, positions, dims)
    }

    private fun getEncryptionKey(strategy: Int): String {
        val baseKey = getKeyByStrategy(strategy)
        return (PI * baseKey).toString()
    }

    private fun hexToBytes(hex: String): ByteArray {
        val bytes = ByteArray(hex.length / 2)
        for (i in hex.indices step 2) {
            bytes[i / 2] = hex.substring(i, i + 2).toInt(16).toByte()
        }
        return bytes
    }

    @Suppress("MagicNumber")
    private fun getKeyByStrategy(strategy: Int): Double = when (strategy) {
        0 -> 1.23872913102938
        1 -> 1.28767913123448
        2 -> 1.391378192300391
        3 -> 2.391378192500391
        4 -> 3.391378191230391
        5 -> 4.391373210965091
        6 -> 2.847291847392847
        7 -> 5.192847362847291
        8 -> 3.947382917483921
        9 -> 1.847392847291847
        10 -> 6.293847291847382
        11 -> 4.847291847392847
        12 -> 2.394827394827394
        13 -> 7.847291847392847
        14 -> 3.827394827394827
        15 -> 1.947382947382947
        16 -> 8.293847291847382
        17 -> 5.847291847392847
        18 -> 2.738472938472938
        19 -> 9.847291847392847
        20 -> 4.293847291847382
        21 -> 6.847291847392847
        22 -> 3.492847291847392
        23 -> 1.739482738472938
        24 -> 7.293847291847382
        25 -> 5.394827394827394
        26 -> 2.847391847392847
        27 -> 8.847291847392847
        28 -> 4.738472938472938
        29 -> 6.293847391847382
        30 -> 3.847291847392847
        31 -> 1.492847291847392
        32 -> 9.293847291847382
        33 -> 5.847291847392847
        34 -> 2.120381029475602
        35 -> 7.390481264726194
        36 -> 4.293012462419412
        37 -> 6.301412704170294
        38 -> 3.738472938472938
        39 -> 1.847291847392847
        40 -> 8.213901280149210
        41 -> 5.394827394827394
        42 -> 2.201381022038956
        43 -> 9.310129031284698
        44 -> 10.32131031284698
        45 -> 1.130712039820147
        else -> 1.2309829040349309
    }

    private data class TileMetadata(
        val sourceWidth: Int,
        val sourceHeight: Int,
        val tilePositions: Map<String, String>,
        val tileDims: Map<String, IntArray>,
    )

    companion object {
        const val DRM_PREFIX = "drm="
        private val TILE_KEYS = arrayOf("00", "01", "02", "10", "11", "12", "20", "21", "22")
        private val JPEG_MEDIA_TYPE = "image/jpeg".toMediaType()
    }
}
