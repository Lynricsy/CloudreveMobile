package plus.ling.opencloudreve.documents

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import plus.ling.opencloudreve.documents.PendingWriteAction.DROP_CONTENT
import plus.ling.opencloudreve.documents.PendingWriteAction.DROP_RECORD
import plus.ling.opencloudreve.documents.PendingWriteAction.SUBMIT
import plus.ling.opencloudreve.documents.PendingWriteAction.SUBMIT_PAUSED
import plus.ling.opencloudreve.documents.PendingWriteAction.WAIT
import java.nio.file.Files

class PendingWritesTest {
    private val dir = Files.createTempDirectory("pending-writes").toFile()

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun record(closed: Boolean, existingSize: Long?, process: String = "old-process") = PendingWrite(
        id = "w1",
        accountId = "1@cloud.example.com",
        documentId = "1@cloud.example.com|id:lpUx",
        parentUri = "cloudreve://my/A",
        name = "report.txt",
        existingSize = existingSize,
        temp = "/data/user/0/app/files/transfer-inbox/u1/report.txt",
        process = process,
        closed = closed,
    )

    @Test
    fun journalEntryWrittenBeforeHandoffSurvivesProcessRestart() {
        // 进程 1：交出描述符前记录，关闭时标记已提交，随后进程被杀（尚未建上传任务）
        PendingWriteJournal(dir).put(record(closed = false, existingSize = 12))
        PendingWriteJournal(dir).put(record(closed = true, existingSize = 12))

        // 进程 2：重新读取日志
        val recovered = PendingWriteJournal(dir).all()
        assertEquals(listOf(record(closed = true, existingSize = 12)), recovered)
        assertEquals(SUBMIT, PendingWrites.decide(recovered.single(), "new-process", tempLength = 30, alreadyQueued = false))
    }

    @Test
    fun emptySaveOverNonEmptyFileIsQueuedLikeAnyOtherSave() {
        assertEquals(SUBMIT, PendingWrites.decide(record(closed = true, existingSize = 12), "p", tempLength = 0, alreadyQueued = false))
        // 新建后什么都没写：服务端已是空文件
        assertEquals(DROP_CONTENT, PendingWrites.decide(record(closed = true, existingSize = 0), "p", tempLength = 0, alreadyQueued = false))
        // 原本不存在：空内容也要建出文件
        assertEquals(SUBMIT, PendingWrites.decide(record(closed = true, existingSize = null), "p", tempLength = 0, alreadyQueued = false))
    }

    @Test
    fun unclosedWritesAreNotCommittedAsComplete() {
        // 本进程仍打开着：等关闭回调
        assertEquals(WAIT, PendingWrites.decide(record(closed = false, existingSize = 12, process = "p"), "p", tempLength = 5, alreadyQueued = false))
        // 打开它的进程已不在：内容可能只写了一半，建暂停任务由用户确认
        assertEquals(SUBMIT_PAUSED, PendingWrites.decide(record(closed = false, existingSize = 12), "p", tempLength = 5, alreadyQueued = false))
        assertEquals(DROP_CONTENT, PendingWrites.decide(record(closed = false, existingSize = 12), "p", tempLength = 0, alreadyQueued = false))
    }

    @Test
    fun handedOffOrMissingContentOnlyDropsJournal() {
        assertEquals(DROP_RECORD, PendingWrites.decide(record(closed = true, existingSize = 12), "p", tempLength = 30, alreadyQueued = true))
        assertEquals(DROP_RECORD, PendingWrites.decide(record(closed = true, existingSize = 12), "p", tempLength = null, alreadyQueued = false))
    }

    @Test
    fun corruptJournalEntryIsSkipped() {
        PendingWriteJournal(dir).put(record(closed = true, existingSize = 1))
        dir.resolve("broken.json").writeText("{not json")
        assertEquals(1, PendingWriteJournal(dir).all().size)
    }
}
