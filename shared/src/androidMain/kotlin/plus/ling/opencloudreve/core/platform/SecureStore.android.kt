@file:Suppress("DEPRECATION") // security-crypto 1.1 起标记弃用，但仍是 Keystore 支持的加密偏好存储的标准实现

package plus.ling.opencloudreve.core.platform

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Android：EncryptedSharedPreferences，主密钥 AES256_GCM 存于 Android Keystore；值以 Base64 字符串保存 */
actual class SecureStore(context: Context) : SecretStore {
    private val prefs: SharedPreferences by lazy {
        val appContext = context.applicationContext
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    actual override fun put(key: String, value: ByteArray) {
        val ok = try {
            prefs.edit().putString(key, Base64.encodeToString(value, Base64.NO_WRAP)).commit()
        } catch (e: Exception) {
            throw SecureStoreException("SecureStore write failed: $key", e)
        }
        if (!ok) throw SecureStoreException("SecureStore write failed: $key")
    }

    actual override fun get(key: String): ByteArray? = try {
        prefs.getString(key, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
    } catch (e: Exception) {
        throw SecureStoreException("SecureStore read failed: $key", e)
    }

    actual override fun remove(key: String) {
        try {
            prefs.edit().remove(key).commit()
        } catch (e: Exception) {
            throw SecureStoreException("SecureStore remove failed: $key", e)
        }
    }

    private companion object {
        const val PREFS_NAME = "opencloudreve_secure"
    }
}
