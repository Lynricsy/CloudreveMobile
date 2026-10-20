package plus.ling.opencloudreve.core.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreCrypto.CCCryptorCreateWithMode
import platform.CoreCrypto.CCCryptorRefVar
import platform.CoreCrypto.CCCryptorRelease
import platform.CoreCrypto.CCCryptorUpdate
import platform.CoreCrypto.ccNoPadding
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCEncrypt
import platform.CoreCrypto.kCCModeCTR
import platform.CoreCrypto.kCCModeOptionCTR_BE
import platform.CoreCrypto.kCCSuccess
import platform.posix.size_tVar

/** CommonCrypto CTR（`kCCModeOptionCTR_BE`：整个 16 字节计数器大端递增） */
@OptIn(ExperimentalForeignApi::class)
internal actual fun aesCtrTransform(key: ByteArray, counter: ByteArray, input: ByteArray): ByteArray {
    val output = ByteArray(input.size)
    if (input.isEmpty()) return output
    memScoped {
        val ref = alloc<CCCryptorRefVar>()
        val created = key.usePinned { k ->
            counter.usePinned { iv ->
                CCCryptorCreateWithMode(
                    kCCEncrypt.convert(),
                    kCCModeCTR.convert(),
                    kCCAlgorithmAES.convert(),
                    ccNoPadding.convert(),
                    iv.addressOf(0),
                    k.addressOf(0),
                    key.size.convert(),
                    null,
                    0.convert(),
                    0,
                    kCCModeOptionCTR_BE.convert(),
                    ref.ptr,
                )
            }
        }
        check(created == kCCSuccess) { "CCCryptorCreateWithMode failed: $created" }
        try {
            val moved = alloc<size_tVar>()
            val status = input.usePinned { inp ->
                output.usePinned { out ->
                    CCCryptorUpdate(
                        ref.value,
                        inp.addressOf(0),
                        input.size.convert(),
                        out.addressOf(0),
                        output.size.convert(),
                        moved.ptr,
                    )
                }
            }
            check(status == kCCSuccess) { "CCCryptorUpdate failed: $status" }
            check(moved.value.toInt() == input.size) { "CCCryptorUpdate wrote ${moved.value} of ${input.size} bytes" }
        } finally {
            CCCryptorRelease(ref.value)
        }
    }
    return output
}
