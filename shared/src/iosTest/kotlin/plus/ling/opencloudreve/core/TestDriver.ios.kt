package plus.ling.opencloudreve.core

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.inMemoryDriver
import plus.ling.opencloudreve.core.db.OpenCloudreveDatabase

/** inMemoryDriver 会自动执行 Schema.create */
actual fun createTestDriver(): SqlDriver = inMemoryDriver(OpenCloudreveDatabase.Schema)
