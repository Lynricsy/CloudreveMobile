package plus.ling.opencloudreve.core.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.dataWithBytes
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecItemNotFound
import platform.Security.errSecMissingEntitlement
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessGroup
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlock
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.posix.memcpy
import kotlin.concurrent.Volatile

/**
 * iOS：Keychain 通用密码项。
 * [accessGroup] 为完整访问组字符串（`$(AppIdentifierPrefix)plus.ling.opencloudreve.shared`，由 Swift 读取后传入），
 * 主 App 与扩展共用；为空时使用进程默认访问组（单元测试宿主）。
 *
 * 进程没有该访问组的 entitlement（未签名/模拟器 ad-hoc 构建）时 Keychain 返回 `errSecMissingEntitlement`（-34018），
 * 此时本进程后续操作改用默认访问组（不带 `kSecAttrAccessGroup`），主 App 与扩展将无法共享凭证。
 */
@OptIn(ExperimentalForeignApi::class)
actual class SecureStore(private val accessGroup: String? = null) : SecretStore {

    /** 当前使用的访问组；首次遇到 -34018 后置空（各线程收敛到同一结果，竞争无害） */
    @Volatile
    private var effectiveGroup: String? = accessGroup

    actual override fun put(key: String, value: ByteArray) {
        val status = withGroupFallback { group -> putOnce(key, value, group) }
        if (status != errSecSuccess) throw SecureStoreException("Keychain write failed (OSStatus $status): $key")
    }

    actual override fun get(key: String): ByteArray? {
        var result: ByteArray? = null
        val status = withGroupFallback { group ->
            val (s, bytes) = getOnce(key, group)
            result = bytes
            s
        }
        return when (status) {
            errSecSuccess -> result
            errSecItemNotFound -> null
            else -> throw SecureStoreException("Keychain read failed (OSStatus $status): $key")
        }
    }

    actual override fun remove(key: String) {
        val status = withGroupFallback { group -> removeOnce(key, group) }
        if (status != errSecSuccess && status != errSecItemNotFound) {
            throw SecureStoreException("Keychain delete failed (OSStatus $status): $key")
        }
    }

    /** 以当前访问组执行 [op]；缺少访问组 entitlement 时改用默认访问组重试一次 */
    private inline fun withGroupFallback(op: (group: String?) -> Int): Int {
        val group = effectiveGroup
        val status = op(group)
        if (status != errSecMissingEntitlement || group == null) return status
        effectiveGroup = null
        return op(null)
    }

    private fun putOnce(key: String, value: ByteArray, group: String?): Int {
        val arena = CfArena()
        try {
            val query = baseQuery(arena, key, group)
            val attributes = arena.dict()
            CFDictionaryAddValue(attributes, kSecValueData, arena.obj(value.toNSData()))
            CFDictionaryAddValue(attributes, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlock)
            var status = SecItemUpdate(query, attributes)
            if (status == errSecItemNotFound) {
                CFDictionaryAddValue(query, kSecValueData, arena.obj(value.toNSData()))
                CFDictionaryAddValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlock)
                status = SecItemAdd(query, null)
            }
            return status
        } finally {
            arena.release()
        }
    }

    private fun getOnce(key: String, group: String?): Pair<Int, ByteArray?> {
        val arena = CfArena()
        try {
            val query = baseQuery(arena, key, group)
            CFDictionaryAddValue(query, kSecReturnData, kCFBooleanTrue)
            CFDictionaryAddValue(query, kSecMatchLimit, kSecMatchLimitOne)
            return memScoped {
                val out = alloc<CFTypeRefVar>()
                val status = SecItemCopyMatching(query, out.ptr)
                val bytes = if (status == errSecSuccess) (CFBridgingRelease(out.value) as? NSData)?.toByteArray() else null
                status to bytes
            }
        } finally {
            arena.release()
        }
    }

    private fun removeOnce(key: String, group: String?): Int {
        val arena = CfArena()
        try {
            return SecItemDelete(baseQuery(arena, key, group))
        } finally {
            arena.release()
        }
    }

    /** 定位单个条目的查询：class + service + account（+ access group） */
    private fun baseQuery(arena: CfArena, key: String, group: String?): CFMutableDictionaryRef? {
        val query = arena.dict()
        CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(query, kSecAttrService, arena.obj(SERVICE))
        CFDictionaryAddValue(query, kSecAttrAccount, arena.obj(key))
        group?.let { CFDictionaryAddValue(query, kSecAttrAccessGroup, arena.obj(it)) }
        return query
    }

    private companion object {
        const val SERVICE = "plus.ling.opencloudreve"
    }
}

/** 统一释放本次调用中创建/桥接的 CF 对象（字典会自行 retain 其键值） */
@OptIn(ExperimentalForeignApi::class)
private class CfArena {
    private val owned = ArrayList<CFTypeRef?>()

    fun obj(value: Any): CFTypeRef? = CFBridgingRetain(value).also { owned += it }

    fun dict(): CFMutableDictionaryRef? = CFDictionaryCreateMutable(
        kCFAllocatorDefault,
        0,
        kCFTypeDictionaryKeyCallBacks.ptr,
        kCFTypeDictionaryValueCallBacks.ptr,
    ).also { owned += it }

    fun release() {
        owned.forEach { if (it != null) CFRelease(it) }
        owned.clear()
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.dataWithBytes(it.addressOf(0), size.convert()) }

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val n = length.toInt()
    if (n == 0) return ByteArray(0)
    return ByteArray(n).also { out -> out.usePinned { memcpy(it.addressOf(0), bytes, length) } }
}
