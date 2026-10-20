package plus.ling.opencloudreve.core.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals

class AesCtrTest {
    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // NIST SP 800-38A F.5.5 CTR-AES256.Encrypt
    private val key = hex("603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4")
    private val iv = hex("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff")
    private val plain = hex(
        "6bc1bee22e409f96e93d7e117393172a" + "ae2d8a571e03ac9c9eb76fac45af8e51" +
            "30c81c46a35ce411e5fbc1191a0a52ef" + "f69f2445df4f9b17ad2b417be66c3710",
    )
    private val cipher = hex(
        "601ec313775789a5b7a7f504bbf3d228" + "f443e3ca4d62b59aca84e990cacaf5c5" +
            "2b0930daa23de94ce87017ba2d84988d" + "dfc9c58db67aada613c2dd08457941a6",
    )

    @Test
    fun nistVector() {
        assertContentEquals(cipher, AesCtr.encryptAt(key, iv, 0, plain))
        // CTR 加解密相同
        assertContentEquals(plain, AesCtr.encryptAt(key, iv, 0, cipher))
    }

    @Test
    fun offset16EqualsSliceOfWholeEncryption() {
        assertContentEquals(cipher.copyOfRange(16, 64), AesCtr.encryptAt(key, iv, 16, plain.copyOfRange(16, 64)))
    }

    @Test
    fun unalignedOffsetsEqualSlices() {
        val data = ByteArray(1000) { (it * 31 + 7).toByte() }
        val whole = AesCtr.encryptAt(key, iv, 0, data)
        listOf(1, 5, 15, 17, 31, 33, 100, 511, 999).forEach { start ->
            listOf(1, 3, 16, 50, 1000 - start).filter { start + it <= 1000 }.forEach { len ->
                assertContentEquals(
                    whole.copyOfRange(start, start + len),
                    AesCtr.encryptAt(key, iv, start.toLong(), data.copyOfRange(start, start + len)),
                    "offset=$start len=$len",
                )
            }
        }
    }

    @Test
    fun arbitraryChunkingMatchesStream() {
        val data = ByteArray(5000) { (it xor 0x5a).toByte() }
        val whole = AesCtr.encryptAt(key, iv, 0, data)
        val cuts = listOf(0, 7, 1024, 1031, 2048, 4093, 5000)
        val joined = cuts.zipWithNext().map { (a, b) -> AesCtr.encryptAt(key, iv, a.toLong(), data.copyOfRange(a, b)) }
            .reduce(ByteArray::plus)
        assertContentEquals(whole, joined)
    }

    @Test
    fun counterCarriesAcross128Bits() {
        val allOnes = ByteArray(16) { 0xff.toByte() }
        assertContentEquals(ByteArray(16), AesCtr.counterAt(allOnes, 1), "2^128 - 1 + 1 回绕为 0")
        val lowOnes = hex("000000000000000000000000ffffffff")
        assertContentEquals(hex("00000000000000000000000100000000"), AesCtr.counterAt(lowOnes, 1))
        assertContentEquals(hex("0000000000000000000000000000ff01"), AesCtr.counterAt(hex("0000000000000000000000000000fe02"), 0xff))
        // 计数器回绕的块与从 0 计数器开始加密的块一致
        val block = ByteArray(16) { it.toByte() }
        assertContentEquals(
            AesCtr.encryptAt(key, ByteArray(16), 0, block),
            AesCtr.encryptAt(key, allOnes, 16, block),
        )
    }
}
