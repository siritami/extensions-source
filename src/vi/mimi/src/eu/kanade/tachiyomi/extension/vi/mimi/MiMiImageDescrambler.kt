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

    // Precomputed key strings from JavaScript: (Math.PI * baseKey).toString()
    // These MUST match JavaScript output exactly — Android JVM's Double.toString()
    // produces different precision, causing XOR decryption to fail.
    private fun getEncryptionKey(strategy: Int): String = when (strategy) {
        0 -> "3.8915823378295684"
        1 -> "4.045363298867129"
        2 -> "4.371143507295955"
        3 -> "7.512736161514067"
        4 -> "10.654328811114038"
        5 -> "13.795905818738952"
        6 -> "8.945031150395478"
        7 -> "16.31381112633418"
        8 -> "12.401069174473331"
        9 -> "5.803755797346398"
        10 -> "19.77270441488375"
        11 -> "15.228216457575064"
        12 -> "7.523572150205324"
        13 -> "24.652994418344445"
        14 -> "12.024115472131163"
        15 -> "6.117883961224305"
        16 -> "26.055889722063334"
        17 -> "18.36980911116486"
        18 -> "8.603166465561035"
        19 -> "30.93617972552403"
        20 -> "13.489519107704165"
        21 -> "21.511401764754652"
        22 -> "10.97310339217877"
        23 -> "5.4647461922328375"
        24 -> "22.914297068473545"
        25 -> "16.948350110974705"
        26 -> "8.945345309660837"
        27 -> "27.794587071934238"
        28 -> "14.886351772740623"
        29 -> "19.772704729043014"
        30 -> "12.08662380398527"
        31 -> "4.689918084999185"
        32 -> "29.197482375653127"
        33 -> "18.36980911116486"
        34 -> "6.661373465011714"
        35 -> "23.217881647756816"
        36 -> "13.486896413706253"
        37 -> "19.796471858658787"
        38 -> "11.744759119150828"
        39 -> "5.803438496805685"
        40 -> "25.804731919028555"
        41 -> "16.948350110974705"
        42 -> "6.915842446589575"
        43 -> "29.248632968657063"
        44 -> "32.42535265426064"
        45 -> "3.552236637624503"
        else -> "3.8672468480107685"
    }

    private fun hexToBytes(hex: String): ByteArray {
        val bytes = ByteArray(hex.length / 2)
        for (i in hex.indices step 2) {
            bytes[i / 2] = hex.substring(i, i + 2).toInt(16).toByte()
        }
        return bytes
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
