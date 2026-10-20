package plus.ling.opencloudreve.core.crypto

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** JCE 的 CTR 模式把整个 16 字节 IV 当作大端计数器递增，与规格一致 */
internal actual fun aesCtrTransform(key: ByteArray, counter: ByteArray, input: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("AES/CTR/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(counter))
    return cipher.doFinal(input)
}
