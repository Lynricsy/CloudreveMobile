package plus.ling.opencloudreve.core.crypto

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals

/** 以 javax.crypto 对整个文件做一次 CTR 加密作为基准，校验按偏移分片加密的结果 */
class AesCtrJceTest {
    private fun wholeJce(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance("AES/CTR/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(data)
        }

    @Test
    fun randomChunksMatchWholeFileJceEncryption() {
        val random = Random(20261020)
        repeat(20) {
            val key = random.nextBytes(32)
            // 最后几轮用接近溢出的 IV，覆盖跨字节进位
            val iv = if (it < 15) random.nextBytes(16) else ByteArray(16) { i -> if (i < 8) 0x11 else 0xff.toByte() }
            val data = random.nextBytes(random.nextInt(1, 200_000))
            val expected = wholeJce(key, iv, data)

            var offset = 0
            val parts = ArrayList<ByteArray>()
            while (offset < data.size) {
                val len = random.nextInt(1, 40_000).coerceAtMost(data.size - offset)
                parts += AesCtr.encryptAt(key, iv, offset.toLong(), data.copyOfRange(offset, offset + len))
                offset += len
            }
            assertContentEquals(expected, parts.reduce(ByteArray::plus))
        }
    }

    @Test
    fun offset16VectorMatchesJceSlice() {
        val key = ByteArray(32) { it.toByte() }
        val iv = ByteArray(16) { (0xA0 + it).toByte() }
        val data = ByteArray(4096) { (it * 13).toByte() }
        val whole = wholeJce(key, iv, data)
        assertContentEquals(whole.copyOfRange(16, 4096), AesCtr.encryptAt(key, iv, 16, data.copyOfRange(16, 4096)))
        assertContentEquals(whole.copyOfRange(23, 1000), AesCtr.encryptAt(key, iv, 23, data.copyOfRange(23, 1000)))
    }
}
