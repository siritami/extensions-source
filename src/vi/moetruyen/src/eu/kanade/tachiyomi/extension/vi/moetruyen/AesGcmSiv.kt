package eu.kanade.tachiyomi.extension.vi.moetruyen

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-GCM-SIV for IMGX v4 profile p07.
 * Based on RFC 8452 + site's noble-ciphers gcmsiv behavior.
 * Ciphertext layout: encrypted_data || tag(16 bytes)
 * Nonce: 12 bytes. Key: 32 bytes (AES-256).
 */
internal object AesGcmSiv {

    fun decrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == 32) { "AES-GCM-SIV key must be 32 bytes" }
        require(nonce.size == 12) { "AES-GCM-SIV nonce must be 12 bytes" }
        require(ciphertext.size >= 16) { "AES-GCM-SIV ciphertext too short" }

        val tag = ciphertext.copyOfRange(ciphertext.size - 16, ciphertext.size)
        val ct = ciphertext.copyOfRange(0, ciphertext.size - 16)

        val (authKey, encKey) = deriveKeys(key, nonce)
        try {
            // IV = tag with bit 7 of byte 15 set
            val iv = tag.clone()
            iv[15] = (iv[15].toInt() or 0x80).toByte()

            val plaintext = aesCtrDecrypt(encKey, iv, ct)

            val expectedTag = computeTag(authKey, encKey, plaintext, nonce, aad)
            var diff = 0
            for (i in 0 until 16) {
                diff = diff or (tag[i].toInt() xor expectedTag[i].toInt())
            }
            require(diff == 0) { "AES-GCM-SIV authentication failed" }

            return plaintext
        } finally {
            authKey.fill(0)
            encKey.fill(0)
        }
    }

    /**
     * Derives authKey (16 bytes) and encKey (32 bytes) from master key + nonce
     * using AES-ECB counter mode, matching site's gcmsiv key derivation.
     */
    private fun deriveKeys(masterKey: ByteArray, nonce: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(masterKey, "AES"))

        val nbuf = ByteBuffer.wrap(nonce).order(ByteOrder.LITTLE_ENDIAN)
        val n0 = nbuf.getInt(0)
        val n1 = nbuf.getInt(4)
        val n2 = nbuf.getInt(8)

        // 6 AES calls × 2 uint32 each = 12 uint32
        // First 4 uint32 → authKey (16 bytes)
        // Next 8 uint32 → encKey (32 bytes)
        val outputs = IntArray(12)
        var counter = 0
        for (call in 0 until 6) {
            val block = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            block.putInt(counter)
            block.putInt(n0)
            block.putInt(n1)
            block.putInt(n2)
            val encrypted = cipher.doFinal(block.array())
            val ebuf = ByteBuffer.wrap(encrypted).order(ByteOrder.LITTLE_ENDIAN)
            outputs[call * 2] = ebuf.getInt(0)
            outputs[call * 2 + 1] = ebuf.getInt(4)
            counter++
        }

        val authKey = ByteArray(16)
        ByteBuffer.wrap(authKey).order(ByteOrder.LITTLE_ENDIAN).apply {
            for (i in 0 until 4) putInt(outputs[i])
        }

        val encKey = ByteArray(32)
        ByteBuffer.wrap(encKey).order(ByteOrder.LITTLE_ENDIAN).apply {
            for (i in 4 until 12) putInt(outputs[i])
        }

        return Pair(authKey, encKey)
    }

    private fun aesCtrDecrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(ciphertext)
    }

    private fun computeTag(
        authKey: ByteArray,
        encKey: ByteArray,
        plaintext: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        val polyvalResult = polyval(authKey, aad, plaintext)

        // XOR first 12 bytes with nonce
        for (i in 0 until 12) {
            polyvalResult[i] = (polyvalResult[i].toInt() xor nonce[i].toInt()).toByte()
        }
        // Clear bit 7 of byte 15
        polyvalResult[15] = (polyvalResult[15].toInt() and 0x7F).toByte()

        // AES-ECB encrypt to produce final tag
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encKey, "AES"))
        return cipher.doFinal(polyvalResult)
    }

    /**
     * POLYVAL over GF(2^128): processes AAD || plaintext || length_block.
     * Length block = aad_size_bytes || plaintext_size_bytes as LE64.
     */
    private fun polyval(authKey: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        var y = ByteArray(16)
        y = polyvalBlocks(y, authKey, aad)
        y = polyvalBlocks(y, authKey, plaintext)

        val lenBlock = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        lenBlock.putLong(0, aad.size.toLong())
        lenBlock.putLong(8, plaintext.size.toLong())
        y = polyvalBlock(y, authKey, lenBlock.array())

        return y
    }

    private fun polyvalBlocks(acc: ByteArray, h: ByteArray, data: ByteArray): ByteArray {
        var y = acc
        var i = 0
        while (i < data.size) {
            val block = ByteArray(16)
            val len = minOf(16, data.size - i)
            data.copyInto(block, 0, i, i + len)
            y = polyvalBlock(y, h, block)
            i += 16
        }
        return y
    }

    private fun polyvalBlock(y: ByteArray, h: ByteArray, block: ByteArray): ByteArray {
        val xorResult = ByteArray(16)
        for (i in 0 until 16) {
            xorResult[i] = (y[i].toInt() xor block[i].toInt()).toByte()
        }
        return gf128Multiply(xorResult, h)
    }

    /**
     * GF(2^128) multiply for POLYVAL.
     * Little-endian bit order. Poly: x^128 + x^127 + x^126 + x^121 + 1
     */
    private fun gf128Multiply(a: ByteArray, b: ByteArray): ByteArray {
        val z = ByteArray(16)
        val v = b.copyOf()

        for (i in 0 until 128) {
            val byteIdx = i shr 3
            val bitIdx = i and 7
            if (((a[byteIdx].toInt() ushr bitIdx) and 1) == 1) {
                for (j in 0 until 16) {
                    z[j] = (z[j].toInt() xor v[j].toInt()).toByte()
                }
            }

            val lsb = v[0].toInt() and 1
            for (j in 0 until 15) {
                v[j] = ((v[j].toInt() ushr 1) or ((v[j + 1].toInt() and 1) shl 7)).toByte()
            }
            v[15] = (v[15].toInt() ushr 1).toByte()

            if (lsb == 1) {
                // x^0 → byte[0] bit 0; x^121,126,127 → byte[15] bits 1,6,7 = 0xC2
                v[0] = (v[0].toInt() xor 0x01).toByte()
                v[15] = (v[15].toInt() xor 0xC2).toByte()
            }
        }

        return z
    }
}
