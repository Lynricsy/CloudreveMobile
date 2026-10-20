package plus.ling.opencloudreve.core.platform

/**
 * 机密键值存储的抽象（令牌、安装 ID）。平台实现为 [SecureStore]；测试可用内存实现。
 * 所有方法同步、线程安全；写入失败抛 [SecureStoreException]。
 */
interface SecretStore {
    fun put(key: String, value: ByteArray)

    fun get(key: String): ByteArray?

    fun remove(key: String)
}

/** 平台安全存储读写失败（Keychain 状态码 / Keystore 异常） */
class SecureStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 平台安全存储：
 * - Android：`SecureStore(context)`，EncryptedSharedPreferences（AES256_GCM 主密钥）
 * - iOS：`SecureStore(accessGroup)`，Keychain 通用密码项（`kSecAttrService = "plus.ling.opencloudreve"`，
 *   `kSecAttrAccessibleAfterFirstUnlock` 以便后台传输读取；`accessGroup` 为 Swift 传入的完整访问组，
 *   如 `"<TeamID>.plus.ling.opencloudreve.shared"`，为空则使用默认组）
 */
expect class SecureStore : SecretStore {
    override fun put(key: String, value: ByteArray)

    override fun get(key: String): ByteArray?

    override fun remove(key: String)
}
