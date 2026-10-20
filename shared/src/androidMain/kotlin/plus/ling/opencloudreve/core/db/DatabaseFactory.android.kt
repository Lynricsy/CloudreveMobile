package plus.ling.opencloudreve.core.db

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

/** Android：数据库放在应用私有目录（`getDatabasePath`） */
actual class DatabaseDriverFactory(private val context: Context) {
    actual fun create(): SqlDriver =
        AndroidSqliteDriver(OpenCloudreveDatabase.Schema, context.applicationContext, DATABASE_NAME)
}
