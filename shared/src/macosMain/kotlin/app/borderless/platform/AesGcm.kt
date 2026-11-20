package app.borderless.platform

/**
 * AES-256-GCM, pure Kotlin — the format the whole store layer is written in (Android's `Seal`, NIST SP 800-38D).
 *
 * Why not the system's: the CommonCrypto functions that do GCM (`CCCryptorGCMOneshot*`) exist in
 * libcommonCrypto.dylib since macOS 10.13 but are absent from the Command Line Tools' CommonCryptor.h —
 * the platform.CoreCrypto klib the shared module binds against has no GCM symbols at all (checked the
 * built linkdata). CryptoKit has no C interface the interop can see, and Security.framework's
 * `SecKeyCreateEncryptedData` only wraps small payloads asymmetrically. Until a cinterop .def for the
 * missing prototypes is wired into the build this is the documented equivalent: a small, fixed set of
 * operations over NIST-published algorithms, checked byte-for-byte against the published test vectors.
 *
 * GCM gives authentication: [open] returns null for data that was changed, truncated, belongs to another
 * key or another AAD (Android's AEADBadTagException → here a null, callers map it to their own exception).
 */
object AesGcm {
    const val NONCE_BYTES = 12
    const val TAG_BYTES = 16
    const val KEY_BYTES = 32

    /**
     * Encrypts [plain] under [key] (32 bytes) and [nonce] (12 bytes, fresh random per message — GCM loses
     * all security on a nonce reuse), with [aad] authenticated but not encrypted. Returns ciphertext || tag.
     */
    fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray {
        require(key.size == KEY_BYTES) { "AES-256 key must be 32 bytes" }
        require(nonce.size == NONCE_BYTES) { "GCM nonce must be 12 bytes" }
        val aes = RoundKeys(key)
        val h = ByteArray(16).also { aes.encryptBlock(ByteArray(16), it) }
        val j0 = ByteArray(16).also { nonce.copyInto(it); it[15] = 1 }
        val out = ByteArray(plain.size + TAG_BYTES)
        ctr(aes, j0, plain, out)
        val tag = tag(h, j0, aes, aad, out, plain.size)
        tag.copyInto(out, plain.size)
        return out
    }

    /** Inverse of [seal]; null when the tag does not verify (wrong key, aad, or tampered bytes). */
    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray? {
        if (sealed.size < TAG_BYTES) return null
        require(key.size == KEY_BYTES) { "AES-256 key must be 32 bytes" }
        require(nonce.size == NONCE_BYTES) { "GCM nonce must be 12 bytes" }
        val ct = sealed.size - TAG_BYTES
        val aes = RoundKeys(key)
        val h = ByteArray(16).also { aes.encryptBlock(ByteArray(16), it) }
        val j0 = ByteArray(16).also { nonce.copyInto(it); it[15] = 1 }
        val want = tag(h, j0, aes, aad, sealed, ct)
        // Constant-time-ish compare: the tag check is not a timing oracle worth defending beyond this.
        var diff = 0
        for (i in 0 until TAG_BYTES) diff = diff or (want[i].toInt() xor sealed[ct + i].toInt())
        if (diff != 0) return null
        return ByteArray(ct).also { ctr(aes, j0, sealed, it) }
    }

    // ------------------------------------------------------------------ the pieces

    /** GCTR: counter blocks J0+1, J0+2, … (the counter is the low 32 bits, big-endian, mod 2^32). */
    private fun ctr(aes: RoundKeys, j0: ByteArray, input: ByteArray, out: ByteArray) {
        val block = ByteArray(16)
        val ks = ByteArray(16)
        var counter = j0.copyOf()
        var off = 0
        while (off < input.size && off < out.size) {
            inc32(counter)
            aes.encryptBlock(counter, ks)
            val n = minOf(16, minOf(input.size - off, out.size - off))
            for (i in 0 until n) out[off + i] = (input[off + i].toInt() xor ks[i].toInt()).toByte()
            off += n
        }
    }

    private fun inc32(b: ByteArray) {
        for (i in 15 downTo 12) {
            b[i] = (b[i] + 1).toByte()
            if (b[i].toInt() != 0) return
        }
    }

    /** GHASH over aad||pad||ct||pad||len(aad)||len(ct) xor E(K,J0) — the GCM tag, SP 800-38D §6.4/6.5. */
    private fun tag(h: ByteArray, j0: ByteArray, aes: RoundKeys, aad: ByteArray, ct: ByteArray, ctLen: Int): ByteArray {
        val y = ByteArray(16)
        fun feed(block: ByteArray) {
            for (i in 0 until 16) y[i] = (y[i].toInt() xor block[i].toInt()).toByte()
            gmul(y, h)
        }
        fun feedAll(data: ByteArray, len: Int) {
            var off = 0
            while (off < len) {
                val n = minOf(16, len - off)
                val b = ByteArray(16).also { data.copyInto(it, 0, off, off + n) }
                feed(b)
                off += n
            }
        }
        feedAll(aad, aad.size)
        feedAll(ct, ctLen)
        val lens = ByteArray(16)
        writeLen(lens, 0, aad.size.toLong() * 8)
        writeLen(lens, 8, ctLen.toLong() * 8)
        feed(lens)
        val ek = ByteArray(16).also { aes.encryptBlock(j0, it) }
        return ByteArray(16) { (y[it].toInt() xor ek[it].toInt()).toByte() }
    }

    private fun writeLen(out: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) out[off + i] = (v ushr (56 - i * 8)).toByte()
    }

    /** GF(2^128) multiply, X = X·Y in place of [x] (SP 800-38D Algorithm 1, R = 0xE1 in the top byte). */
    private fun gmul(x: ByteArray, y: ByteArray) {
        val z = ByteArray(16)
        val v = x.copyOf()
        for (i in 0 until 128) {
            if ((y[i / 8].toInt() ushr (7 - i % 8)) and 1 == 1) {
                for (j in 0 until 16) z[j] = (z[j].toInt() xor v[j].toInt()).toByte()
            }
            val lsb = v[15].toInt() and 1
            for (j in 15 downTo 1) v[j] = ((v[j].toInt() and 0xFF ushr 1) or ((v[j - 1].toInt() and 1) shl 7)).toByte()
            v[0] = (v[0].toInt() and 0xFF ushr 1).toByte()
            if (lsb == 1) v[0] = (v[0].toInt() xor 0xE1).toByte()
        }
        v.fill(0)
        z.copyInto(x)
    }

    // ------------------------------------------------------------------ AES-256 (FIPS-197)

    /** The expanded AES-256 key (14 rounds); [encryptBlock] runs a forward cipher only — GCM never decrypts. */
    class RoundKeys(key: ByteArray) {
        // 15 round keys of 16 bytes in one flat array.
        private val rk = ByteArray(240)

        init {
            require(key.size == 32) { "AES-256 key must be 32 bytes" }
            // Key expansion, FIPS-197 §5.2, Nk=8 Nr=14.
            key.copyInto(rk)
            var i = 32
            var rcon = 1
            while (i < rk.size) {
                var t0 = rk[i - 4].toInt() and 0xFF
                var t1 = rk[i - 3].toInt() and 0xFF
                var t2 = rk[i - 2].toInt() and 0xFF
                var t3 = rk[i - 1].toInt() and 0xFF
                if (i % 32 == 0) {
                    // RotWord + SubWord + Rcon.
                    val u0 = SBOX[t1]; val u1 = SBOX[t2]; val u2 = SBOX[t3]; val u3 = SBOX[t0]
                    t0 = u0 xor rcon; t1 = u1; t2 = u2; t3 = u3
                    rcon = xtime(rcon)
                } else if (i % 32 == 16) {
                    t0 = SBOX[t0]; t1 = SBOX[t1]; t2 = SBOX[t2]; t3 = SBOX[t3]
                }
                rk[i] = (rk[i - 32].toInt() and 0xFF xor t0).toByte()
                rk[i + 1] = (rk[i - 31].toInt() and 0xFF xor t1).toByte()
                rk[i + 2] = (rk[i - 30].toInt() and 0xFF xor t2).toByte()
                rk[i + 3] = (rk[i - 29].toInt() and 0xFF xor t3).toByte()
                i += 4
            }
        }

        /** One 16-byte block through the 14-round AES cipher (state is the block in column-major order). */
        fun encryptBlock(input: ByteArray, out: ByteArray) {
            require(input.size >= 16 && out.size >= 16)
            val s = ByteArray(16)
            input.copyInto(s, 0, 0, 16)
            addRoundKey(s, 0)
            for (round in 1 until 14) {
                subBytes(s); shiftRows(s); mixColumns(s); addRoundKey(s, round * 16)
            }
            subBytes(s); shiftRows(s); addRoundKey(s, 14 * 16)
            s.copyInto(out, 0, 0, 16)
        }

        private fun addRoundKey(s: ByteArray, off: Int) {
            for (i in 0 until 16) s[i] = (s[i].toInt() xor rk[off + i].toInt()).toByte()
        }

        private fun subBytes(s: ByteArray) {
            for (i in 0 until 16) s[i] = SBOX[s[i].toInt() and 0xFF].toByte()
        }

        /** State column-major: s[r][c] is s[c*4+r]; row r shifts left by r. */
        private fun shiftRows(s: ByteArray) {
            val t = s.copyOf()
            for (r in 0 until 4) for (c in 0 until 4) s[c * 4 + r] = t[((c + r) % 4) * 4 + r]
        }

        private fun mixColumns(s: ByteArray) {
            for (c in 0 until 4) {
                val o = c * 4
                val a0 = s[o].toInt() and 0xFF; val a1 = s[o + 1].toInt() and 0xFF
                val a2 = s[o + 2].toInt() and 0xFF; val a3 = s[o + 3].toInt() and 0xFF
                s[o] = (xtime(a0) xor (xtime(a1) xor a1) xor a2 xor a3).toByte()
                s[o + 1] = (a0 xor xtime(a1) xor (xtime(a2) xor a2) xor a3).toByte()
                s[o + 2] = (a0 xor a1 xor xtime(a2) xor (xtime(a3) xor a3)).toByte()
                s[o + 3] = ((xtime(a0) xor a0) xor a1 xor a2 xor xtime(a3)).toByte()
            }
        }
    }

    private fun xtime(x: Int): Int = ((x shl 1) and 0xFF) xor (if (x and 0x80 != 0) 0x1B else 0)

    private val SBOX = intArrayOf(
        0x63, 0x7c, 0x77, 0x7b, 0xf2, 0x6b, 0x6f, 0xc5, 0x30, 0x01, 0x67, 0x2b, 0xfe, 0xd7, 0xab, 0x76,
        0xca, 0x82, 0xc9, 0x7d, 0xfa, 0x59, 0x47, 0xf0, 0xad, 0xd4, 0xa2, 0xaf, 0x9c, 0xa4, 0x72, 0xc0,
        0xb7, 0xfd, 0x93, 0x26, 0x36, 0x3f, 0xf7, 0xcc, 0x34, 0xa5, 0xe5, 0xf1, 0x71, 0xd8, 0x31, 0x15,
        0x04, 0xc7, 0x23, 0xc3, 0x18, 0x96, 0x05, 0x9a, 0x07, 0x12, 0x80, 0xe2, 0xeb, 0x27, 0xb2, 0x75,
        0x09, 0x83, 0x2c, 0x1a, 0x1b, 0x6e, 0x5a, 0xa0, 0x52, 0x3b, 0xd6, 0xb3, 0x29, 0xe3, 0x2f, 0x84,
        0x53, 0xd1, 0x00, 0xed, 0x20, 0xfc, 0xb1, 0x5b, 0x6a, 0xcb, 0xbe, 0x39, 0x4a, 0x4c, 0x58, 0xcf,
        0xd0, 0xef, 0xaa, 0xfb, 0x43, 0x4d, 0x33, 0x85, 0x45, 0xf9, 0x02, 0x7f, 0x50, 0x3c, 0x9f, 0xa8,
        0x51, 0xa3, 0x40, 0x8f, 0x92, 0x9d, 0x38, 0xf5, 0xbc, 0xb6, 0xda, 0x21, 0x10, 0xff, 0xf3, 0xd2,
        0xcd, 0x0c, 0x13, 0xec, 0x5f, 0x97, 0x44, 0x17, 0xc4, 0xa7, 0x7e, 0x3d, 0x64, 0x5d, 0x19, 0x73,
        0x60, 0x81, 0x4f, 0xdc, 0x22, 0x2a, 0x90, 0x88, 0x46, 0xee, 0xb8, 0x14, 0xde, 0x5e, 0x0b, 0xdb,
        0xe0, 0x32, 0x3a, 0x0a, 0x49, 0x06, 0x24, 0x5c, 0xc2, 0xd3, 0xac, 0x62, 0x91, 0x95, 0xe4, 0x79,
        0xe7, 0xc8, 0x37, 0x6d, 0x8d, 0xd5, 0x4e, 0xa9, 0x6c, 0x56, 0xf4, 0xea, 0x65, 0x7a, 0xae, 0x08,
        0xba, 0x78, 0x25, 0x2e, 0x1c, 0xa6, 0xb4, 0xc6, 0xe8, 0xdd, 0x74, 0x1f, 0x4b, 0xbd, 0x8b, 0x8a,
        0x70, 0x3e, 0xb5, 0x66, 0x48, 0x03, 0xf6, 0x0e, 0x61, 0x35, 0x57, 0xb9, 0x86, 0xc1, 0x1d, 0x9e,
        0xe1, 0xf8, 0x98, 0x11, 0x69, 0xd9, 0x8e, 0x94, 0x9b, 0x1e, 0x87, 0xe9, 0xce, 0x55, 0x28, 0xdf,
        0x8c, 0xa1, 0x89, 0x0d, 0xbf, 0xe6, 0x42, 0x68, 0x41, 0x99, 0x2d, 0x0f, 0xb0, 0x54, 0xbb, 0x16,
    )
}
