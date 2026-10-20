package plus.ling.opencloudreve.core.platform

import plus.ling.opencloudreve.core.db.DatabaseDriverFactory
import plus.ling.opencloudreve.core.transfer.FileSystemBridge

/**
 * 平台在启动共享核心时提供的上下文（Android 由 Application 构建，iOS 由 Swift 构建）。
 * 主 App 与各扩展进程各自构建一份，但数据库与安全存储指向同一共享位置。
 */
interface PlatformContext {
    /** SQLite 驱动工厂（数据库文件位置由平台决定） */
    val databaseDriverFactory: DatabaseDriverFactory

    /** 机密存储（通常为 [SecureStore]） */
    val secureStore: SecretStore

    /** 本地文件系统桥（读取上传源区间、写临时文件） */
    val fileSystem: FileSystemBridge

    /** App 版本号（如 `1.0.0`） */
    val appVersion: String

    /** 平台名：`android` 或 `ios`（用于 viewer 平台过滤等） */
    val platformName: String

    companion object {
        const val PLATFORM_ANDROID = "android"
        const val PLATFORM_IOS = "ios"
    }
}
