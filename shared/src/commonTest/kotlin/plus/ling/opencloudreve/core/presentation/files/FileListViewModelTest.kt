package plus.ling.opencloudreve.core.presentation.files

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import plus.ling.opencloudreve.core.api.apiPath
import plus.ling.opencloudreve.core.api.bodyText
import plus.ling.opencloudreve.core.events.EventHub
import plus.ling.opencloudreve.core.files.ACCOUNT
import plus.ling.opencloudreve.core.files.DataEnv
import plus.ling.opencloudreve.core.files.err
import plus.ling.opencloudreve.core.files.fileJson
import plus.ling.opencloudreve.core.files.okData
import plus.ling.opencloudreve.core.settings.UserSettingsRepository
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class FileListViewModelTest {

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** 真实时间等待条件成立（VM 运行在 Unconfined 上，延迟为真实时间） */
    private suspend fun eventually(block: () -> Boolean) = withContext(Dispatchers.Default) {
        withTimeout(5_000) {
            while (!block()) delay(10)
        }
    }

    private val listJson =
        """{"files":[${fileJson("a.txt")},${fileJson("b", folder = true)}],"parent":{"type":1,"id":"root","name":"","path":"cloudreve://my"},""" +
            """"pagination":{"page":0,"page_size":100,"is_cursor":true},""" +
            """"props":{"capability":"/f+3","max_page_size":2000,"order_by_options":["name","created_at"],"order_direction_options":["asc","desc"]},""" +
            """"view":{"page_size":100,"order":"name","order_direction":"asc","view":"list","thumbnail":true}}"""

    @Test
    fun loadsListingSyncsViewAndRefreshesOnEvents() = runTest {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val env = DataEnv { req ->
            when (req.apiPath) {
                "/file" -> okData(listJson)
                "/user/me" -> okData("""{"id":"u1","email":"u@x","group":{"id":"g","permission":"Bw=="}}""")
                "/user/capacity" -> okData("""{"total":100,"used":10}""")
                "/site/config/explorer" -> okData("""{"max_batch_size":500,"file_viewers":[{"viewers":[{"id":"monaco","type":"builtin","exts":["txt"]}]}]}""")
                "/file/view" -> okData()
                "/file/events" -> respond(
                    "event: subscribed\n\nevent: event\ndata: {\"type\":\"create\",\"file_id\":\"x\",\"from\":\"/c\"}\n\n",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "text/event-stream"),
                )
                else -> err(404)
            }
        }
        val user = UserSettingsRepository(ACCOUNT, env.clients) { _, _ -> }
        val hub = EventHub(ACCOUNT, env.clients, initialBackoff = 10_000.milliseconds)
        val vm = FileListViewModel(
            ACCOUNT, "cloudreve://my", env.files, user, env.site, hub,
            eventDebounce = 50.milliseconds, backgroundGrace = 10.milliseconds,
        )
        eventually { vm.state.value.files.size == 2 && vm.state.value.viewerResolver != null && vm.state.value.capacity != null }
        val s = vm.state.value
        assertEquals("list", s.viewMode)
        assertEquals("name", s.orderBy)
        assertTrue(s.canSort)
        assertEquals(500, s.maxBatchSize)
        assertEquals(10L, s.capacity?.used)
        assertNotNull(s.viewerResolver?.viewer("monaco"))

        // 排序变更：重新加载并同步视图
        vm.on(FileListIntent.SetSort("created_at", "desc"))
        eventually { env.server.requests.any { it.apiPath == "/file/view" } }
        val patch = env.server.requests.first { it.apiPath == "/file/view" }
        assertEquals(HttpMethod.Patch, patch.method)
        assertTrue(patch.bodyText().contains("\"order\":\"created_at\""))
        assertTrue(patch.bodyText().contains("\"order_direction\":\"desc\""))
        // 服务端 binding 要求 gallery_width ≥ 50，缺省会被 40001 拒绝
        assertTrue(patch.bodyText().contains("\"gallery_width\":110"))
        eventually { env.server.requests.any { it.apiPath == "/file" && it.url.parameters["order_by"] == "created_at" } }
        assertEquals("created_at", vm.state.value.orderBy)

        // 前台订阅事件：收到事件防抖后刷新
        val listCalls = env.server.requests.count { it.apiPath == "/file" }
        vm.on(FileListIntent.SetForeground(true))
        eventually { env.server.requests.count { it.apiPath == "/file" } > listCalls }
        assertTrue(env.server.requests.any { it.apiPath == "/file/events" })

        // 多选
        vm.on(FileListIntent.ToggleSelection("cloudreve://my/a.txt"))
        assertEquals(listOf("a.txt"), vm.state.value.selectedFiles.map { it.name })
        vm.on(FileListIntent.SelectAll)
        assertEquals(2, vm.state.value.selection.size)
        vm.on(FileListIntent.ExitSelection)
        assertTrue(vm.state.value.selection.isEmpty())
        vm.dispose()
    }
}
