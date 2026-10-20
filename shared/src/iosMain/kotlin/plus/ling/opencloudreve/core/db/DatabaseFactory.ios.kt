package plus.ling.opencloudreve.core.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/**
 * iOS：数据库放在 App Group 容器（主 App、分享扩展、File Provider 扩展共用），
 * 取不到容器（未配置 entitlement，如单元测试宿主）时退回 Application Support。
 */
actual class DatabaseDriverFactory(private val appGroupId: String = DEFAULT_APP_GROUP) {
    actual fun create(): SqlDriver {
        val dir = databaseDirectory()
        return NativeSqliteDriver(
            schema = OpenCloudreveDatabase.Schema,
            name = DATABASE_NAME,
            onConfiguration = { config ->
                config.copy(extendedConfig = config.extendedConfig.copy(basePath = dir))
            },
        )
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun databaseDirectory(): String {
        val fm = NSFileManager.defaultManager
        val group = fm.containerURLForSecurityApplicationGroupIdentifier(appGroupId)?.path
        val dir = group ?: (NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String ?: error("Application Support directory unavailable"))
        fm.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        return dir
    }

    companion object {
        const val DEFAULT_APP_GROUP = "group.plus.ling.opencloudreve"
    }
}
