package plus.ling.opencloudreve.core

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import plus.ling.opencloudreve.core.db.OpenCloudreveDatabase

actual fun createTestDriver(): SqlDriver =
    JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { OpenCloudreveDatabase.Schema.create(it) }
