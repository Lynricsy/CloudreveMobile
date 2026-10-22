package plus.ling.opencloudreve.transfer

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import plus.ling.opencloudreve.core.transfer.TransferState
import plus.ling.opencloudreve.transfer.SourceGrants.SourceReference

class SourceGrantsTest {
    private val uri = "content://com.android.providers.media.documents/document/video%3A42"
    private var now = 1_000L
    private val tasks = mutableListOf<SourceReference>()
    private val released = mutableListOf<String>()
    private val grants = SourceGrants(
        references = { ref -> if (ref == uri) tasks.toList() else emptyList() },
        releaseGrant = { released += it },
        clock = { now },
    )

    @Test
    fun grantSharedWithPausedTaskIsKeptUntilLastTaskEnds() = runTest {
        grants.claim(uri)
        tasks += SourceReference("A", TransferState.QUEUED, createdAt = 1_001)
        now = 5_000
        tasks[0] = tasks[0].copy(state = TransferState.PAUSED)
        grants.claim(uri)
        tasks += SourceReference("B", TransferState.QUEUED, createdAt = 5_001)
        now = 9_000
        tasks[1] = tasks[1].copy(state = TransferState.COMPLETED)

        // B 完成：A 仍暂停，需要同一授权继续
        grants.release(uri, "B", taskCreatedAt = 5_001)
        assertEquals(emptyList<String>(), released)

        // A 也结束（取消后已被移除出表）：最后一个引用，释放
        tasks.removeAt(0)
        grants.release(uri, "A", taskCreatedAt = 1_001)
        assertEquals(listOf(uri), released)
    }

    @Test
    fun claimNotYetEnqueuedBlocksReleaseUntilTaskArrivesOrExpires() = runTest {
        tasks += SourceReference("A", TransferState.CANCELED, createdAt = 1_000)
        now = 2_000
        // 选择器刚为新上传取得授权，任务尚未入库
        grants.claim(uri)
        grants.release(uri, "A", taskCreatedAt = 1_000)
        assertEquals(emptyList<String>(), released)

        // 认领过期仍未入库：不再阻止释放
        now = 2_000 + SourceGrants.CLAIM_TTL_MS + 1
        grants.release(uri, "A", taskCreatedAt = 1_000)
        assertEquals(listOf(uri), released)
    }

    @Test
    fun ownClaimDoesNotKeepGrantAfterSingleTaskCompletes() = runTest {
        grants.claim(uri)
        tasks += SourceReference("A", TransferState.COMPLETED, createdAt = 1_000)
        now = 1_500
        grants.release(uri, "A", taskCreatedAt = 1_000)
        assertEquals(listOf(uri), released)
    }
}
