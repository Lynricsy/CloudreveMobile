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

/**
 * iOS：Keychain 通用密码项。
 * [accessGroup] 为完整访问组字符串（`$(AppIdentifierPrefix)plus.ling.opencloudreve.shared`，由 Swift 读取后传入），
 * 主 App 与扩展共用；为空时使用进程默认访问组（单元测试宿主）。
 */
@OptIn(ExperimentalForeignApi::class)
actual class SecureStore(private val accessGroup: String? = null) : SecretStore {

    actual override fun put(key: String, value: ByteArray) {
        val arena = CfArena()
        try {
            val query = baseQuery(arena, key)
            val attributes = arena.dict()
            CFDictionaryAddValue(attributes, kSecValueData, arena.obj(value.toNSData()))
            CFDictionaryAddValue(attributes, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlock)
            var status = SecItemUpdate(query, attributes)
            if (status == errSecItemNotFound) {
                CFDictionaryAddValue(query, kSecValueData, arena.obj(value.toNSData()))
                CFDictionaryAddValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlock)
                status = SecItemAdd(query, null)
            }
            if (status != errSecSuccess) throw SecureStoreException("Keychain write failed (OSStatus $status): $key")
        } finally {
            arena.release()
        }
    }

    actual override fun get(key: String): ByteArray? {
        val arena = CfArena()
        try {
            val query = baseQuery(arena, key)
            CFDictionaryAddValue(query, kSecReturnData, kCFBooleanTrue)
            CFDictionaryAddValue(query, kSecMatchLimit, kSecMatchLimitOne)
            return memScoped {
                val out = alloc<CFTypeRefVar>()
                when (val status = SecItemCopyMatching(query, out.ptr)) {
                    errSecSuccess -> (CFBridgingRelease(out.value) as? NSData)?.toByteArray()
                    errSecItemNotFound -> null
                    else -> throw SecureStoreException("Keychain read failed (OSStatus $status): $key")
                }
            }
        } finally {
            arena.release()
        }
    }

    actual override fun remove(key: String) {
        val arena = CfArena()
        try {
            val status = SecItemDelete(baseQuery(arena, key))
            if (status != errSecSuccess && status != errSecItemNotFound) {
                throw SecureStoreException("Keychain delete failed (OSStatus $status): $key")
            }
        } finally {
            arena.release()
        }
    }

    /** 定位单个条目的查询：class + service + account（+ access group） */
    private fun baseQuery(arena: CfArena, key: String): CFMutableDictionaryRef? {
        val query = arena.dict()
        CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(query, kSecAttrService, arena.obj(SERVICE))
        CFDictionaryAddValue(query, kSecAttrAccount, arena.obj(key))
        accessGroup?.let { CFDictionaryAddValue(query, kSecAttrAccessGroup, arena.obj(it)) }
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
