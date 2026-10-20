package plus.ling.opencloudreve.core.presentation.transfer

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import plus.ling.opencloudreve.core.api.apiPath
import plus.ling.opencloudreve.core.files.DataEnv
import plus.ling.opencloudreve.core.files.err
import plus.ling.opencloudreve.core.files.okData
import plus.ling.opencloudreve.core.model.StoragePolicy
import plus.ling.opencloudreve.core.presentation.eventually
import plus.ling.opencloudreve.core.transfer.ACCOUNT
import plus.ling.opencloudreve.core.transfer.TransferEnv
import plus.ling.opencloudreve.core.transfer.TransferErrorCode
import plus.ling.opencloudreve.core.transfer.TransferState
import plus.ling.opencloudreve.core.transfer.UploadRejection
import plus.ling.opencloudreve.core.transfer.UploadSource
import plus.ling.opencloudreve.core.transfer.bytes
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class TransferViewModelsTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun speedFromBytesDoneDifferenceAndDecay() = runTest {
        val env = TransferEnv()
        val item = env.upload(bytes(10_000))
        env.repository.setState(item.id, TransferState.RUNNING)
        var now = 0L
        val vm = TransferListViewModel(ACCOUNT, env.manager, clockMillis = { now }, sampleInterval = 1.hours, speedWindow = 5.seconds)
        eventually { vm.state.value.uploads.singleOrNull()?.state == TransferState.RUNNING }
        assertEquals(0, vm.state.value.uploads.single().speedBytesPerSecond)

        now = 2_000
        env.repository.setBytesDone(item.id, 4_000)
        eventually { vm.state.value.uploads.single().item.bytesDone == 4_000L }
        val row = vm.state.value.uploads.single()
        assertEquals(2_000, row.speedBytesPerSecond)
        assertEquals(3, row.etaSeconds)
        assertTrue(row.canPause)
        assertEquals(2_000, vm.state.value.totalSpeedBytesPerSecond)

        // 窗口外的旧样本被淘汰：6 秒后无新字节 → 速度回落为 0
        now = 8_000
        env.repository.setBytesDone(item.id, 4_000)
        env.repository.setState(item.id, TransferState.RUNNING)
        eventually { vm.state.value.uploads.single().speedBytesPerSecond == 0L }

        now = 9_000
        env.repository.setBytesDone(item.id, 5_000)
        eventually { vm.state.value.uploads.single().speedBytesPerSecond == 1_000L }

        // 非运行中：速度清零，可继续
        env.repository.setState(item.id, TransferState.PAUSED)
        eventually { vm.state.value.uploads.single().state == TransferState.PAUSED }
        val paused = vm.state.value.uploads.single()
        assertEquals(0, paused.speedBytesPerSecond)
        assertTrue(paused.canResume)
        assertFalse(paused.canPause)
        vm.dispose()
    }

    @Test
    fun tickerRecomputesWithoutDbChanges() = runTest {
        val env = TransferEnv()
        val item = env.upload(bytes(1_000))
        env.repository.setState(item.id, TransferState.RUNNING)
        var now = 0L
        val vm = TransferListViewModel(ACCOUNT, env.manager, clockMillis = { now }, sampleInterval = 20.milliseconds, speedWindow = 5.seconds)
        eventually { vm.state.value.loaded }
        now = 1_000
        env.repository.setBytesDone(item.id, 500)
        eventually { vm.state.value.uploads.single().speedBytesPerSecond == 500L }
        // 没有新的 DB 变化，采样器继续推进时间，旧样本出窗后速度归零
        now = 100_000
        eventually { vm.state.value.uploads.single().speedBytesPerSecond == 0L }
        vm.dispose()
    }

    @Test
    fun conflictRowOffersRenameAndRetries() = runTest {
        val env = TransferEnv()
        val item = env.upload(bytes(10), name = "a.txt")
        env.repository.setState(item.id, TransferState.FAILED, 40004, "exists")
        val vm = TransferListViewModel(ACCOUNT, env.manager)
        eventually { vm.state.value.uploads.singleOrNull()?.needsConflictResolution == true }
        assertFalse(vm.state.value.uploads.single().canRetry)
        vm.on(TransferListIntent.RetryWithRename(item.id, "b.txt"))
        eventually { vm.state.value.uploads.single().state == TransferState.QUEUED }
        assertEquals("cloudreve://my/dir/b.txt", vm.state.value.uploads.single().item.targetUri)
        vm.dispose()
    }

    @Test
    fun uploadPrecheckRejectsAndLoadsPolicyFromListing() = runTest {
        val tenv = TransferEnv()
        val denv = DataEnv { req ->
            when (req.apiPath) {
                "/file" -> okData(
                    """{"files":[],"pagination":{"is_cursor":true},"props":{"capability":"/f+3"},""" +
                        """"storage_policy":{"id":"p","type":"local","max_size":100,"denied_suffix":["exe"]}}""",
                )
                else -> err(404)
            }
        }
        val vm = UploadPickerViewModel(ACCOUNT, "cloudreve://my/dir", null, tenv.manager, denv.files)
        eventually { vm.state.value.policy != null }
        assertEquals(100L, vm.state.value.maxSize)
        assertEquals(listOf("exe"), vm.state.value.deniedSuffix)
        assertEquals(UploadRejection.TOO_LARGE, vm.check("big.bin", 101))
        assertNull(vm.check("ok.txt", 10))

        val sources = listOf(
            UploadSource("src://ok", "ok.txt", 10),
            UploadSource("src://big", "big.bin", 500),
            UploadSource("src://bad", "virus.exe", 1),
        )
        vm.effects.test {
            vm.on(UploadPickerIntent.Enqueue(sources))
            val result = assertIs<UploadPickerEffect.Enqueued>(awaitItem()).result
            assertEquals(listOf("ok.txt"), result.accepted.map { it.displayName })
            assertEquals(
                listOf("big.bin" to UploadRejection.TOO_LARGE, "virus.exe" to UploadRejection.SUFFIX_DENIED),
                result.rejected.map { it.name to it.reason },
            )
        }
        val stored = tenv.repository.unfinishedTasks()
        assertEquals(listOf("ok.txt"), stored.map { it.displayName })
        val rejected = tenv.repository.task(vm.state.value.lastResult!!.rejected.first().taskId)!!
        assertEquals(TransferState.FAILED, rejected.state)
        assertEquals(TransferErrorCode.VALIDATION, rejected.errorCode)

        // 已知策略直接使用，不再请求
        val requests = denv.server.requests.size
        vm.on(UploadPickerIntent.SetTarget("cloudreve://my/other", StoragePolicy(id = "q", type = "local")))
        assertEquals(0L, vm.state.value.maxSize)
        assertEquals(requests, denv.server.requests.size)
        vm.dispose()
    }
}
