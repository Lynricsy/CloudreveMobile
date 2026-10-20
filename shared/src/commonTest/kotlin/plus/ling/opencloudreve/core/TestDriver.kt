package plus.ling.opencloudreve.core

import app.cash.sqldelight.db.SqlDriver

/** 测试用内存 SQLite 驱动，已执行 `OpenCloudreveDatabase.Schema.create`，每次调用得到独立的新库 */
expect fun createTestDriver(): SqlDriver
