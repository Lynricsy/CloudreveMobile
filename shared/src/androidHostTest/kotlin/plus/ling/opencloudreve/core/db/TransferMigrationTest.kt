package plus.ling.opencloudreve.core.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 已安装旧版本（schema 1）的设备升级后，迁移补齐新列，传输任务仍可查询 */
class TransferMigrationTest {
    @Test
    fun oldSchemaDatabaseIsMigratedAndTasksAreQueryable() {
        // 已发布的 schema 1 快照（verifyMigrations 同样以它为基准）
        val db = File.createTempFile("ocr-v1", ".db").apply { deleteOnExit() }
        File("src/commonMain/sqldelight/databases/1.db").copyTo(db, overwrite = true)
        val driver = JdbcSqliteDriver("jdbc:sqlite:${db.path}")
        driver.execute(
            null,
            """INSERT INTO TransferTask(id, account_id, kind, state, display_name, size, target_uri, created_at, updated_at)
               VALUES ('t1', 'acc', 'download', 'paused', 'a.bin', 10, 'cloudreve://my/a.bin', 1, 2)""",
            0,
        )

        OpenCloudreveDatabase.Schema.migrate(driver, 1, OpenCloudreveDatabase.Schema.version)

        val row = OpenCloudreveDatabase(driver).transferQueries.selectTasksByAccount("acc").executeAsList().single()
        assertEquals("t1", row.id)
        assertEquals("paused", row.state)
        assertEquals(1L, row.created_at)
        assertEquals(2L, row.updated_at)
        assertNull(row.request_id)
        assertNull(row.upload_phase)
        assertNull(row.entity_id)
        driver.close()
    }
}
