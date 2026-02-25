package eu.kanade.tachiyomi.extension.vi.mimi

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import kotlin.math.PI

object MiMiImageDecryptor {

    // ============================== Decryption ======================================

    /** Decrypts the hex-encoded, XOR-encrypted DRM string into the plaintext tile-map. */
    fun decryptDrm(hexData: String): String {
        val strategyStr = hexData.takeLast(2)
        val strategy = strategyStr.toInt(10)
        val encryptionKey = getFixedEncryptionKey(strategy)
        val encryptedHex = hexData.dropLast(2)
        val encryptedBytes = hexToBytes(encryptedHex)
        val keyBytes = encryptionKey.toByteArray(Charsets.UTF_8)
        val decrypted = ByteArray(encryptedBytes.size) { i ->
            (encryptedBytes[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
        }
        return decrypted.toString(Charsets.UTF_8)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val bytes = ByteArray(hex.length / 2)
        for (i in hex.indices step 2) {
            bytes[i / 2] = hex.substring(i, i + 2).toInt(16).toByte()
        }
        return bytes
    }

    private fun getFixedEncryptionKey(strategy: Int): String =
        (PI * getKeyByStrategy(strategy)).toString()

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

    // ============================== Descrambling ======================================

    private val TILE_KEYS = arrayOf("00", "01", "02", "10", "11", "12", "20", "21", "22")

    /**
     * Unscrambles [scrambled] using the decrypted [drmPlaintext] produced by [decryptDrm].
     * Returns the restored bitmap (the caller should recycle [scrambled] if no longer needed).
     */
    fun unscramble(scrambled: Bitmap, drmPlaintext: String): Bitmap {
        // Parse the plaintext: sw:W|sh:H|{tileId}@{srcX},{srcY},{w},{h}>{destTileId}|...
        var sw = 0
        var sh = 0
        val posMap = HashMap<String, String>()   // srcTileId -> destTileId
        val dimsMap = HashMap<String, IntArray>() // tileId -> [x, y, w, h]

        for (token in drmPlaintext.split("|")) {
            when {
                token.startsWith("sw:") -> sw = token.substring(3).toIntOrNull() ?: 0
                token.startsWith("sh:") -> sh = token.substring(3).toIntOrNull() ?: 0
                token.contains("@") && token.contains(">") -> {
                    val (left, destKey) = token.split(">")
                    val (srcKey, rectStr) = left.split("@")
                    val parts = rectStr.split(",")
                    if (parts.size == 4) {
                        val x = parts[0].toIntOrNull() ?: 0
                        val y = parts[1].toIntOrNull() ?: 0
                        val w = parts[2].toIntOrNull() ?: 0
                        val h = parts[3].toIntOrNull() ?: 0
                        dimsMap[srcKey] = intArrayOf(x, y, w, h)
                        posMap[srcKey] = destKey
                    }
                }
            }
        }

        val fullW = scrambled.width
        val fullH = scrambled.height

        if (sw <= 0 || sh <= 0) return scrambled

        // Build default (evenly-divided) tile dimensions for any tile missing from dimsMap
        val baseW = sw / 3
        val baseH = sh / 3
        val rw = sw % 3
        val rh = sh % 3
        val defaultDims = HashMap<String, IntArray>().apply {
            for (k in TILE_KEYS) {
                val i = k[0].digitToInt()
                val j = k[1].digitToInt()
                val w = baseW + if (j == 2) rw else 0
                val h = baseH + if (i == 2) rh else 0
                put(k, intArrayOf(j * baseW, i * baseH, w, h))
            }
        }

        // Merge: use explicit dim if present, otherwise default
        val dims = HashMap<String, IntArray>().apply {
            for (k in TILE_KEYS) {
                put(k, dimsMap[k] ?: defaultDims.getValue(k))
            }
        }

        // Build inverse map: destTileId -> srcTileId
        val inv = HashMap<String, String>()
        for ((src, dest) in posMap) {
            inv[dest] = src
        }

        // Draw reassembled result
        val result = Bitmap.createBitmap(fullW, fullH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        for (k in TILE_KEYS) {
            val srcKey = inv[k] ?: continue
            val s = dims.getValue(k)     // destination region in result
            val d = dims.getValue(srcKey) // source region in scrambled

            val srcRect = Rect(d[0], d[1], d[0] + d[2], d[1] + d[3])
            val dstRect = Rect(s[0], s[1], s[0] + s[2], s[1] + s[3])
            canvas.drawBitmap(scrambled, srcRect, dstRect, null)
        }

        // Copy any region below the scrambled height (remainder strip)
        if (sh < fullH) {
            val srcRect = Rect(0, sh, fullW, fullH)
            canvas.drawBitmap(scrambled, srcRect, srcRect, null)
        }
        // Copy any region to the right of sw (remainder strip)
        if (sw < fullW) {
            val srcRect = Rect(sw, 0, fullW, sh)
            canvas.drawBitmap(scrambled, srcRect, srcRect, null)
        }

        return result
    }
}
