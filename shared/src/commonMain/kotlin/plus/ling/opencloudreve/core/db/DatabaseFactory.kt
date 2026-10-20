package plus.ling.opencloudreve.core.db

import app.cash.sqldelight.db.SqlDriver

/** 数据库文件名（Android 在应用私有目录，iOS 在 App Group 容器） */
const val DATABASE_NAME = "opencloudreve.db"

/**
 * 平台 SQLite 驱动工厂。构造参数由平台决定：
 * - Android：`DatabaseDriverFactory(context)`
 * - iOS：`DatabaseDriverFactory(appGroupId)`（默认 `group.plus.ling.opencloudreve`）
 */
expect class DatabaseDriverFactory {
    /** 打开（必要时创建）[DATABASE_NAME] 并返回驱动 */
    fun create(): SqlDriver
}
