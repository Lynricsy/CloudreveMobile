package plus.ling.opencloudreve.core.crypto

/**
 * AES-256-CTR 客户端加密（与 Web `uploader/encrypt/blob.ts`、服务端 `encrypt/aes256ctr.go` 等价）。
 *
 * 第 N 字节使用计数器 `iv + floor(N / 16)`（128 位大端加法），无填充、无认证标签，密文长度等于明文长度。
 * 因此任意一段明文只要按它在整个文件中的绝对偏移加密，结果就与整体流式加密的对应切片一致。
 */
object AesCtr {
    const val KEY_SIZE = 32
    const val BLOCK_SIZE = 16

    /**
     * 加密（CTR 下加解密相同）文件中从绝对偏移 [offset] 开始的 [plain]。
     * 非 16 字节对齐的起点先在前面补零到块边界，加密后再截掉补齐部分。
     */
    fun encryptAt(key: ByteArray, iv: ByteArray, offset: Long, plain: ByteArray): ByteArray {
        require(key.size == KEY_SIZE) { "AES-256 key must be 32 bytes" }
        require(iv.size == BLOCK_SIZE) { "IV must be 16 bytes" }
        require(offset >= 0) { "offset must be >= 0" }
        if (plain.isEmpty()) return plain
        val counter = counterAt(iv, offset / BLOCK_SIZE)
        val skip = (offset % BLOCK_SIZE).toInt()
        if (skip == 0) return aesCtrTransform(key, counter, plain)
        val padded = ByteArray(skip + plain.size)
        plain.copyInto(padded, destinationOffset = skip)
        val out = aesCtrTransform(key, counter, padded)
        return out.copyOfRange(skip, out.size)
    }

    /** 计数器 = [iv] + [blocks]（128 位大端加法，溢出回绕） */
    fun counterAt(iv: ByteArray, blocks: Long): ByteArray {
        require(iv.size == BLOCK_SIZE) { "IV must be 16 bytes" }
        require(blocks >= 0) { "blocks must be >= 0" }
        val result = iv.copyOf()
        // 逐字节加上 blocks 的对应字节并传播进位
        var addend = blocks.toULong()
        var carry = 0uL
        var i = BLOCK_SIZE - 1
        while (i >= 0 && (addend != 0uL || carry != 0uL)) {
            val sum = (result[i].toInt() and 0xff).toULong() + (addend and 0xffu) + carry
            result[i] = (sum and 0xffu).toByte()
            carry = sum shr 8
            addend = addend shr 8
            i--
        }
        return result
    }
}

/**
 * 平台 AES-256-CTR 原语：以 [counter] 作为第一个块的计数器（之后 128 位大端递增），对 [input] 做 CTR 变换。
 * Android 用 `javax.crypto`，iOS 用 CommonCrypto。
 */
internal expect fun aesCtrTransform(key: ByteArray, counter: ByteArray, input: ByteArray): ByteArray
