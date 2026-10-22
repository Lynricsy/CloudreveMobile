package plus.ling.opencloudreve.core.presentation.workflow

import app.cash.turbine.test
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import plus.ling.opencloudreve.core.api.apiPath
import plus.ling.opencloudreve.core.api.bodyText
import plus.ling.opencloudreve.core.api.json
import plus.ling.opencloudreve.core.files.ACCOUNT
import plus.ling.opencloudreve.core.files.err
import plus.ling.opencloudreve.core.files.okData
import plus.ling.opencloudreve.core.model.BoolSet
import plus.ling.opencloudreve.core.model.DownloadFileSelection
import plus.ling.opencloudreve.core.model.GroupPermission
import plus.ling.opencloudreve.core.model.TaskCategory
import plus.ling.opencloudreve.core.presentation.ErrorKind
import plus.ling.opencloudreve.core.presentation.VmEnv
import plus.ling.opencloudreve.core.presentation.eventually
import plus.ling.opencloudreve.core.presentation.userJson
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class WorkflowViewModelsTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun downloadTask(id: String, phase: String = "monitor", status: String = "processing") =
        """{"id":"$id","type":"remote_download","status":"$status","summary":{"phase":"$phase","props":{"src_str":"magnet:?x","dst":"cloudreve://my",""" +
            """"download":{"name":"n","state":"downloading","total":200,"downloaded":50,"files":[{"index":0,"name":"a","size":100,"selected":true},{"index":1,"name":"b","size":100}]}}}}"""

    private suspend fun realDelay(ms: Long) = withContext(Dispatchers.Default) { delay(ms) }

    private fun downloadingCount(env: VmEnv) =
        env.server.requests.count { it.apiPath == "/workflow" && it.url.parameters["category"] == TaskCategory.DOWNLOADING }

    @Test
    fun downloadingPollStopsWhenListBecomesEmpty() = runTest {
        var calls = 0
        val env = VmEnv { req ->
            when (req.apiPath) {
                "/workflow" -> {
                    calls++
                    if (calls <= 2) okData("""{"tasks":[${downloadTask("t1")}],"pagination":{}}""") else okData("""{"tasks":[],"pagination":{}}""")
                }
                else -> err(404)
            }
        }
        val vm = WorkflowListViewModel(ACCOUNT, TaskCategory.DOWNLOADING, env.workflows, pollInterval = 30.milliseconds)
        eventually { vm.state.value.rows.size == 1 }
        val row = vm.state.value.rows.single()
        assertTrue(row.canCancel)
        assertTrue(row.canSelectFiles)
        assertEquals(0.25, row.downloadProgress)
        assertEquals(listOf("magnet:?x"), row.sources)
        eventually { calls >= 3 && !vm.state.value.polling }
        assertTrue(vm.state.value.rows.isEmpty())
        realDelay(150)
        assertEquals(3, downloadingCount(env))
        vm.dispose()
    }

    @Test
    fun downloadingPollStopsInBackgroundAndResumes() = runTest {
        val env = VmEnv { req ->
            when (req.apiPath) {
                "/workflow" -> okData("""{"tasks":[${downloadTask("t1")}],"pagination":{}}""")
                else -> err(404)
            }
        }
        val vm = WorkflowListViewModel(ACCOUNT, TaskCategory.DOWNLOADING, env.workflows, pollInterval = 20.milliseconds)
        eventually { downloadingCount(env) >= 3 }
        vm.on(WorkflowListIntent.SetForeground(false))
        assertFalse(vm.state.value.polling)
        val stopped = downloadingCount(env)
        realDelay(120)
        assertTrue(downloadingCount(env) <= stopped + 1)
        val afterStop = downloadingCount(env)
        vm.on(WorkflowListIntent.SetForeground(true))
        eventually { downloadingCount(env) > afterStop }
        // 切换到其它分类同样停止轮询
        vm.on(WorkflowListIntent.SetCategory(TaskCategory.GENERAL))
        eventually { !vm.state.value.polling }
        val switched = downloadingCount(env)
        realDelay(120)
        assertTrue(downloadingCount(env) <= switched + 1)
        vm.dispose()
    }

    @Test
    fun detailPollsProgressUntilTaskFinishes() = runTest {
        var progressCalls = 0
        val env = VmEnv { req ->
            when {
                req.apiPath == "/workflow" -> if (progressCalls < 2) {
                    okData("""{"tasks":[{"id":"g1","type":"create_archive","status":"processing","summary":{"props":{"src_multiple":["cloudreve://my/a"],"dst":"cloudreve://my/a.zip"}}}],"pagination":{}}""")
                } else {
                    okData("""{"tasks":[{"id":"g1","type":"create_archive","status":"completed"}],"pagination":{}}""")
                }
                req.apiPath == "/workflow/progress/g1" -> {
                    progressCalls++
                    if (progressCalls == 1) okData("""{"archive_count":{"total":10,"current":3,"identifier":"archive_count"}}""") else okData("{}")
                }
                else -> err(404)
            }
        }
        val vm = WorkflowListViewModel(ACCOUNT, TaskCategory.GENERAL, env.workflows, pollInterval = 20.milliseconds)
        eventually { vm.state.value.rows.size == 1 }
        assertEquals("cloudreve://my/a.zip", vm.state.value.rows.single().destination)
        vm.on(WorkflowListIntent.OpenDetail("g1"))
        eventually { vm.state.value.detail?.progress?.get("archive_count")?.current == 3L }
        eventually { vm.state.value.detail?.row?.status == "completed" && vm.state.value.detail?.polling == false }
        val calls = progressCalls
        realDelay(100)
        assertEquals(calls, progressCalls)
        vm.dispose()
    }

    private fun generalTask(id: String, status: String = "processing") =
        """{"id":"$id","type":"create_archive","status":"$status"}"""

    @Test
    fun refreshDuringLoadMoreKeepsPaginationUsable() = runTest {
        val hang = CompletableDeferred<Unit>()
        var moreCalls = 0
        val env = VmEnv { req ->
            when {
                req.apiPath != "/workflow" -> err(404)
                req.url.parameters["next_page_token"] == null ->
                    okData("""{"tasks":[${generalTask("g1")}],"pagination":{"is_cursor":true,"next_token":"p2"}}""")
                else -> {
                    if (moreCalls++ == 0) hang.await()
                    okData("""{"tasks":[${generalTask("g2")}],"pagination":{"is_cursor":true}}""")
                }
            }
        }
        val vm = WorkflowListViewModel(ACCOUNT, TaskCategory.GENERAL, env.workflows, pollInterval = 20.milliseconds)
        eventually { vm.state.value.rows.size == 1 }
        vm.on(WorkflowListIntent.LoadMore)
        eventually { vm.state.value.loadingMore && moreCalls == 1 }
        vm.on(WorkflowListIntent.Refresh)
        eventually { !vm.state.value.refreshing && env.server.requests.size == 3 }
        assertFalse(vm.state.value.loadingMore)
        vm.on(WorkflowListIntent.LoadMore)
        eventually { vm.state.value.rows.map { it.id } == listOf("g1", "g2") }
        assertFalse(vm.state.value.loadingMore)
        vm.dispose()
        // 放开被取消的旧请求并等它收尾，避免其续体在 resetMain 之后才回到主调度器
        hang.complete(Unit)
        realDelay(200)
    }

    @Test
    fun detailFindsTaskOnLaterPageWhenItFinishes() = runTest {
        // 目标任务 g51 位于第二页；进度为空后需沿游标找到它并识别为已完成
        var finished = false
        val env = VmEnv { req ->
            when {
                req.apiPath == "/workflow/progress/g51" -> {
                    finished = true
                    okData("{}")
                }
                req.apiPath != "/workflow" -> err(404)
                req.url.parameters["next_page_token"] == null ->
                    okData("""{"tasks":[${generalTask("g99")}],"pagination":{"is_cursor":true,"next_token":"p2"}}""")
                else -> okData(
                    """{"tasks":[${generalTask("g51", if (finished) "completed" else "processing")}],"pagination":{"is_cursor":true}}""",
                )
            }
        }
        val vm = WorkflowListViewModel(ACCOUNT, TaskCategory.GENERAL, env.workflows, pollInterval = 20.milliseconds)
        eventually { vm.state.value.rows.size == 1 }
        vm.on(WorkflowListIntent.LoadMore)
        eventually { vm.state.value.rows.size == 2 }
        vm.on(WorkflowListIntent.OpenDetail("g51"))
        eventually { vm.state.value.detail?.row?.status == "completed" && vm.state.value.detail?.polling == false }
        assertEquals("completed", vm.state.value.rows.single { it.id == "g51" }.status)
        assertFalse(vm.state.value.detail!!.missing)
        vm.dispose()
    }

    @Test
    fun detailStopsPollingWhenTaskNoLongerExists() = runTest {
        var listed = true
        val env = VmEnv { req ->
            when {
                req.apiPath == "/workflow/progress/g1" -> okData("{}")
                req.apiPath != "/workflow" -> err(404)
                req.url.parameters["next_page_token"] == null -> if (listed) {
                    okData("""{"tasks":[${generalTask("g1")}],"pagination":{"is_cursor":true,"next_token":"p2"}}""")
                } else {
                    okData("""{"tasks":[${generalTask("g9")}],"pagination":{"is_cursor":true,"next_token":"p2"}}""")
                }
                else -> okData("""{"tasks":[${generalTask("g0")}],"pagination":{"is_cursor":true}}""")
            }
        }
        val vm = WorkflowListViewModel(ACCOUNT, TaskCategory.GENERAL, env.workflows, pollInterval = 20.milliseconds)
        eventually { vm.state.value.rows.size == 1 }
        // 打开详情前任务已被删除：所有分页都找不到
        listed = false
        vm.on(WorkflowListIntent.OpenDetail("g1"))
        eventually { vm.state.value.detail?.missing == true && vm.state.value.detail?.polling == false }
        val progressCalls = env.server.requests.count { it.apiPath == "/workflow/progress/g1" }
        realDelay(100)
        assertEquals(progressCalls, env.server.requests.count { it.apiPath == "/workflow/progress/g1" })
        vm.dispose()
    }

    @Test
    fun selectFilesAndCancel() = runTest {
        val env = VmEnv { req ->
            when {
                req.apiPath == "/workflow" -> okData("""{"tasks":[${downloadTask("t1")}],"pagination":{}}""")
                req.apiPath == "/workflow/download/t1" -> okData("{}")
                else -> err(404)
            }
        }
        val vm = WorkflowListViewModel(ACCOUNT, TaskCategory.DOWNLOADED, env.workflows, pollInterval = 10_000.milliseconds)
        eventually { vm.state.value.rows.size == 1 }
        vm.effects.test {
            vm.on(WorkflowListIntent.SelectFiles("t1", listOf(DownloadFileSelection(1, true))))
            assertEquals(WorkflowListEffect.FilesSelected("t1"), awaitItem())
            vm.on(WorkflowListIntent.Cancel("t1"))
            assertEquals(WorkflowListEffect.Canceled("t1"), awaitItem())
        }
        val patch = env.server.requests.first { it.method == HttpMethod.Patch }
        assertEquals("""{"files":[{"index":1,"download":true}]}""", patch.bodyText())
        assertTrue(env.server.requests.any { it.method == HttpMethod.Delete && it.apiPath == "/workflow/download/t1" })
        vm.dispose()
    }

    @Test
    fun remoteDownloadPartialFailureKeepsFailedSources() = runTest {
        val perms = BoolSet.of(GroupPermission.REMOTE_DOWNLOAD, GroupPermission.SELECT_NODE).toBase64()
        val env = VmEnv { req ->
            when (req.apiPath) {
                "/user/me" -> okData(userJson(permissionB64 = perms))
                "/workflow/nodes" -> okData("""[{"id":"n1","name":"Node","type":"slave"}]""")
                "/workflow/download" -> json(
                    """{"code":40081,"msg":"partial","aggregated_error":{"http://bad":{"code":40001,"msg":"bad"}}}""",
                )
                else -> err(404)
            }
        }
        val vm = RemoteDownloadCreateViewModel(ACCOUNT, "cloudreve://my/dl", null, env.workflows, env.userSettings)
        eventually { vm.state.value.nodes.size == 1 }
        assertTrue(vm.state.value.canSelectNode)
        assertEquals("3", env.server.requests.first { it.apiPath == "/workflow/nodes" }.url.parameters["capability"])
        vm.on(RemoteDownloadCreateIntent.SetUrls("http://ok\n\n  http://bad  \n"))
        vm.on(RemoteDownloadCreateIntent.SelectNode("n1"))
        assertEquals(listOf("http://ok", "http://bad"), vm.state.value.sources)
        vm.effects.test {
            vm.on(RemoteDownloadCreateIntent.Submit)
            val created = assertIs<RemoteDownloadCreateEffect.Created>(awaitItem())
            assertEquals(1, created.failedCount)
        }
        val body = env.server.requests.last().bodyText()
        assertEquals("""{"src":["http://ok","http://bad"],"dst":"cloudreve://my/dl","node_id":"n1"}""", body)
        assertEquals("http://bad", vm.state.value.urlsText)
        assertEquals(ErrorKind.Validation, vm.state.value.failures.single().error.kind)
        vm.dispose()
    }

    @Test
    fun compressExtractAndRelocateRequests() = runTest {
        val perms = BoolSet.of(GroupPermission.ARCHIVE_TASK, GroupPermission.RELOCATE).toBase64()
        val task = """{"id":"x","type":"create_archive","status":"queued"}"""
        val env = VmEnv { req ->
            when (req.apiPath) {
                "/user/me" -> okData(userJson(permissionB64 = perms))
                "/workflow/archive", "/workflow/extract", "/workflow/relocate" -> okData(task)
                "/user/setting/policies" -> okData(
                    """[{"id":"lb","name":"LB","type":"load_balance"},{"id":"enc","name":"E","type":"local","encryption":true},{"id":"s3","name":"S3","type":"s3"}]""",
                )
                else -> err(404)
            }
        }
        val compress = ArchiveTaskViewModel(
            ACCOUNT, ArchiveTaskMode.COMPRESS, listOf("cloudreve://my/a", "cloudreve://my/b"), "cloudreve://my", emptyList(), env.workflows, env.userSettings,
        )
        eventually { env.server.requests.any { it.apiPath == "/user/me" } }
        assertFalse(compress.state.value.canSelectNode)
        compress.on(ArchiveTaskIntent.SetArchiveName("pack.zip"))
        compress.effects.test {
            compress.on(ArchiveTaskIntent.Submit)
            assertIs<ArchiveTaskEffect.Created>(awaitItem())
        }
        assertEquals(
            """{"src":["cloudreve://my/a","cloudreve://my/b"],"dst":"cloudreve://my/pack.zip"}""",
            env.server.requests.last { it.apiPath == "/workflow/archive" }.bodyText(),
        )
        compress.dispose()

        val extract = ArchiveTaskViewModel(
            ACCOUNT, ArchiveTaskMode.EXTRACT, listOf("cloudreve://my/x.zip"), "cloudreve://my/out", emptyList(), env.workflows, env.userSettings,
        )
        assertTrue(extract.state.value.showEncoding)
        assertTrue(extract.state.value.showPassword)
        extract.on(ArchiveTaskIntent.SetEncoding("gbk"))
        extract.on(ArchiveTaskIntent.SetPassword("pw"))
        extract.effects.test {
            extract.on(ArchiveTaskIntent.Submit)
            assertIs<ArchiveTaskEffect.Created>(awaitItem())
        }
        assertEquals(
            """{"src":["cloudreve://my/x.zip"],"dst":"cloudreve://my/out","encoding":"gbk","password":"pw"}""",
            env.server.requests.last { it.apiPath == "/workflow/extract" }.bodyText(),
        )
        extract.dispose()

        val relocate = ArchiveTaskViewModel(
            ACCOUNT, ArchiveTaskMode.RELOCATE, listOf("cloudreve://my/a"), "", emptyList(), env.workflows, env.userSettings,
        )
        eventually { relocate.state.value.policies.size == 3 }
        assertEquals(listOf(false, false, true), relocate.state.value.policies.map { it.selectable })
        relocate.on(ArchiveTaskIntent.SelectPolicy("lb"))
        assertFalse(relocate.state.value.canSubmit)
        relocate.on(ArchiveTaskIntent.SelectPolicy("s3"))
        relocate.effects.test {
            relocate.on(ArchiveTaskIntent.Submit)
            assertIs<ArchiveTaskEffect.Created>(awaitItem())
        }
        assertEquals(
            """{"src":["cloudreve://my/a"],"dst_policy_id":"s3"}""",
            env.server.requests.last { it.apiPath == "/workflow/relocate" }.bodyText(),
        )
        relocate.dispose()
    }
}
