package com.hippo.ehviewer.ios.crypto

/**
 * AES-256 解密（ECB / NoPadding）。
 *
 * Kotlin/Native 上没有 javax.crypto，禁漫的接口返回又是 AES 加密的，所以手写一份。
 * 只实现解密路径需要的部分：密钥扩展、逆轮变换。
 * 轮数固定 14（AES-256），密钥必须 32 字节。
 */
object Aes {

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

    private val INV_SBOX = IntArray(256).also { inv ->
        for (i in 0 until 256) inv[SBOX[i]] = i
    }

    private const val NR = 14
    private const val NK = 8

    /** 解一段 ECB 数据，长度必须是 16 的倍数。 */
    fun decryptEcb(key: ByteArray, data: ByteArray): ByteArray {
        require(key.size == 32) { "AES-256 需要 32 字节密钥，实际 ${key.size}" }
        require(data.size % 16 == 0) { "密文长度必须是 16 的倍数，实际 ${data.size}" }
        val w = expandKey(key)
        val out = ByteArray(data.size)
        var off = 0
        while (off < data.size) {
            val block = IntArray(16)
            for (i in 0 until 16) block[i] = data[off + i].toInt() and 0xFF
            decryptBlock(block, w)
            for (i in 0 until 16) out[off + i] = block[i].toByte()
            off += 16
        }
        return out
    }

    private fun expandKey(key: ByteArray): IntArray {
        val w = IntArray(4 * (NR + 1))
        for (i in 0 until NK) {
            w[i] = ((key[4 * i].toInt() and 0xFF) shl 24) or
                ((key[4 * i + 1].toInt() and 0xFF) shl 16) or
                ((key[4 * i + 2].toInt() and 0xFF) shl 8) or
                (key[4 * i + 3].toInt() and 0xFF)
        }
        var rcon = 1
        for (i in NK until w.size) {
            var temp = w[i - 1]
            if (i % NK == 0) {
                temp = subWord(rotWord(temp)) xor (rcon shl 24)
                rcon = xtime(rcon)
            } else if (i % NK == 4) {
                temp = subWord(temp)
            }
            w[i] = w[i - NK] xor temp
        }
        return w
    }

    private fun decryptBlock(s: IntArray, w: IntArray) {
        addRoundKey(s, w, NR)
        for (round in NR - 1 downTo 1) {
            invShiftRows(s)
            for (i in 0 until 16) s[i] = INV_SBOX[s[i]]
            addRoundKey(s, w, round)
            invMixColumns(s)
        }
        invShiftRows(s)
        for (i in 0 until 16) s[i] = INV_SBOX[s[i]]
        addRoundKey(s, w, 0)
    }

    /** 状态用 s[4*c + r]，c 是列、r 是行，和标准列主序一致。 */
    private fun addRoundKey(s: IntArray, w: IntArray, round: Int) {
        for (c in 0 until 4) {
            val word = w[round * 4 + c]
            s[4 * c] = s[4 * c] xor ((word ushr 24) and 0xFF)
            s[4 * c + 1] = s[4 * c + 1] xor ((word ushr 16) and 0xFF)
            s[4 * c + 2] = s[4 * c + 2] xor ((word ushr 8) and 0xFF)
            s[4 * c + 3] = s[4 * c + 3] xor (word and 0xFF)
        }
    }

    /** 第 r 行循环右移 r 位。 */
    private fun invShiftRows(s: IntArray) {
        for (r in 1 until 4) {
            val tmp = IntArray(4)
            for (c in 0 until 4) tmp[(c + r) % 4] = s[4 * c + r]
            for (c in 0 until 4) s[4 * c + r] = tmp[c]
        }
    }

    private fun invMixColumns(s: IntArray) {
        for (c in 0 until 4) {
            val a0 = s[4 * c]
            val a1 = s[4 * c + 1]
            val a2 = s[4 * c + 2]
            val a3 = s[4 * c + 3]
            s[4 * c] = gmul(a0, 14) xor gmul(a1, 11) xor gmul(a2, 13) xor gmul(a3, 9)
            s[4 * c + 1] = gmul(a0, 9) xor gmul(a1, 14) xor gmul(a2, 11) xor gmul(a3, 13)
            s[4 * c + 2] = gmul(a0, 13) xor gmul(a1, 9) xor gmul(a2, 14) xor gmul(a3, 11)
            s[4 * c + 3] = gmul(a0, 11) xor gmul(a1, 13) xor gmul(a2, 9) xor gmul(a3, 14)
        }
    }

    /** GF(2^8) 乘法。 */
    private fun gmul(a: Int, b: Int): Int {
        var x = a
        var y = b
        var r = 0
        while (y != 0) {
            if (y and 1 != 0) r = r xor x
            x = xtime(x)
            y = y ushr 1
        }
        return r and 0xFF
    }

    private fun xtime(a: Int): Int {
        val r = a shl 1
        return if (a and 0x80 != 0) (r xor 0x1B) and 0xFF else r and 0xFF
    }

    private fun rotWord(word: Int): Int =
        ((word shl 8) or (word ushr 24)) and 0xFFFFFFFF.toInt()

    private fun subWord(word: Int): Int =
        (SBOX[(word ushr 24) and 0xFF] shl 24) or
            (SBOX[(word ushr 16) and 0xFF] shl 16) or
            (SBOX[(word ushr 8) and 0xFF] shl 8) or
            SBOX[word and 0xFF]
}
