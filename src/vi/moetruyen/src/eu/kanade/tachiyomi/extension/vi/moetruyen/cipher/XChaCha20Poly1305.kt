package eu.kanade.tachiyomi.extension.vi.moetruyen.cipher

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * XChaCha20-Poly1305 (IETF) for IMGX v4 profile p03.
 * Body: nonce(24) || ciphertext || tag(16).
 * libsodium crypto_aead_xchacha20poly1305_ietf_decrypt.
 */
internal object XChaCha20Poly1305 {

    private fun rotl(x: Int, n: Int): Int = (x shl n) or (x ushr (32 - n))

    private fun quarterRound(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
        s[a] += s[b]; s[d] = rotl(s[d] xor s[a], 16)
        s[c] += s[d]; s[b] = rotl(s[b] xor s[c], 12)
        s[a] += s[b]; s[d] = rotl(s[d] xor s[a], 8)
        s[c] += s[d]; s[b] = rotl(s[b] xor s[c], 7)
    }

    /** HChaCha20(key, nonce[0..16]) → 32-byte subkey. */
    fun hchacha20(key: ByteArray, nonce16: ByteArray): ByteArray {
        require(key.size == 32) { "HChaCha20 key must be 32 bytes" }
        require(nonce16.size == 16) { "HChaCha20 nonce must be 16 bytes" }
        val state = IntArray(16)
        state[0] = 0x61707865
        state[1] = 0x3320646e
        state[2] = 0x79622d32
        state[3] = 0x6b206574
        val kb = ByteBuffer.wrap(key).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 8) state[4 + i] = kb.getInt(i * 4)
        val nb = ByteBuffer.wrap(nonce16).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 4) state[12 + i] = nb.getInt(i * 4)
        val x = state.copyOf()
        repeat(10) {
            quarterRound(x, 0, 4, 8, 12)
            quarterRound(x, 1, 5, 9, 13)
            quarterRound(x, 2, 6, 10, 14)
            quarterRound(x, 3, 7, 11, 15)
            quarterRound(x, 0, 5, 10, 15)
            quarterRound(x, 1, 6, 11, 12)
            quarterRound(x, 2, 7, 8, 13)
            quarterRound(x, 3, 4, 9, 14)
        }
        val out = ByteArray(32)
        val ob = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 4) ob.putInt(i * 4, x[i])
        for (i in 0 until 4) ob.putInt(16 + i * 4, x[12 + i])
        return out
    }

    fun decrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == 32) { "XChaCha20-Poly1305 key must be 32 bytes" }
        require(nonce.size == 24) { "XChaCha20-Poly1305 nonce must be 24 bytes" }
        val subkey = hchacha20(key, nonce.copyOfRange(0, 16))
        try {
            // IETF nonce = 0^32 || nonce[16..24]
            val ietfNonce = ByteArray(12)
            nonce.copyInto(ietfNonce, 4, 16, 24)
            return ChaCha20Poly1305.decrypt(subkey, ietfNonce, ciphertext, aad)
        } finally {
            subkey.fill(0)
        }
    }
}
