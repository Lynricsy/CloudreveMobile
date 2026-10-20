package plus.ling.opencloudreve.core.presentation.admin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import plus.ling.opencloudreve.core.admin.AdminRepository
import plus.ling.opencloudreve.core.api.apiPath
import plus.ling.opencloudreve.core.api.bodyText
import plus.ling.opencloudreve.core.files.ACCOUNT
import plus.ling.opencloudreve.core.files.DataEnv
import plus.ling.opencloudreve.core.files.err
import plus.ling.opencloudreve.core.files.okData
import plus.ling.opencloudreve.core.model.Pagination
import plus.ling.opencloudreve.core.presentation.handoff.HandoffAvailability
import plus.ling.opencloudreve.core.presentation.handoff.WebHandoff
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdminListViewModelTest {

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun eventually(block: () -> Boolean) = withContext(Dispatchers.Default) {
        withTimeout(5_000) { while (!block()) delay(10) }
    }

    @Test
    fun pagingHelpersConvertOneBasedRequestsToZeroBasedResponses() {
        assertEquals(1, AdminPaging.nextRequestPage(0))
        // 响应 page=0 表示已加载第 1 页，下次请求 page=2
        val first = Pagination(page = 0, pageSize = 50, totalItems = 120)
        assertEquals(1, AdminPaging.loadedPages(first))
        assertEquals(2, AdminPaging.nextRequestPage(AdminPaging.loadedPages(first)))
        assertTrue(AdminPaging.hasMore(first, 50))
        assertTrue(AdminPaging.hasMore(Pagination(page = 1, pageSize = 50, totalItems = 120), 50))
        assertFalse(AdminPaging.hasMore(Pagination(page = 2, pageSize = 50, totalItems = 120), 50))
        // 总数为 0 时服务端省略 total_items
        assertFalse(AdminPaging.hasMore(Pagination(page = 0, pageSize = 50), 50))
        // 响应缺 page_size 时用请求的页大小
        assertTrue(AdminPaging.hasMore(Pagination(page = 0, totalItems = 30), 20))
    }

    private fun userJson(id: Int) = """{"id":$id,"email":"u$id@x.com","nick":"u$id","status":"active","group_users":2,"hash_id":"h$id"}"""

    @Test
    fun usersListAccumulatesPagesAndFilters() = runTest {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val total = 120
        val env = DataEnv { req ->
            when (req.apiPath) {
                "/admin/user" -> {
                    val body = Json.parseToJsonElement(req.bodyText()).jsonObject
                    val page = body["page"]!!.jsonPrimitive.int
                    val size = body["page_size"]!!.jsonPrimitive.int
                    val from = (page - 1) * size
                    val ids = (from until minOf(from + size, total)).map { it + 1 }
                    okData(
                        """{"users":[${ids.joinToString(",") { userJson(it) }}],""" +
                            """"pagination":{"page":${page - 1},"page_size":$size,"total_items":$total}}""",
                    )
                }
                "/admin/group" -> okData("""{"groups":[{"id":1,"name":"Admin"},{"id":2,"name":"User"}],"pagination":{"page":0,"page_size":100,"total_items":2}}""")
                "/admin/user/batch/delete" -> okData()
                else -> err(404)
            }
        }
        val handoff = WebHandoff({ "https://cloud.example.com" }, { "4.19.11" }, { "code" })
        val vm = AdminUsersViewModel(ACCOUNT, AdminRepository(ACCOUNT, env.clients), handoff, pageSize = 50)
        val effects = mutableListOf<AdminListEffect>()
        backgroundScope.launch(Dispatchers.Unconfined) { vm.effects.collect { effects += it } }

        eventually { vm.state.value.items.size == 50 && !vm.state.value.loading }
        assertEquals(1, vm.state.value.loadedPages)
        assertEquals(120, vm.state.value.totalItems)
        assertTrue(vm.state.value.hasMore)
        eventually { vm.groups.value.size == 2 && vm.state.value.handoff == HandoffAvailability.Available }

        vm.on(AdminListIntent.LoadMore)
        eventually { vm.state.value.items.size == 100 && !vm.state.value.loadingMore }
        vm.on(AdminListIntent.LoadMore)
        eventually { vm.state.value.items.size == 120 && !vm.state.value.loadingMore }
        assertFalse(vm.state.value.hasMore)
        assertEquals((1..120).toList(), vm.state.value.items.map { it.id })
        // 没有更多时不再请求
        vm.on(AdminListIntent.LoadMore)
        val pages = env.server.requests.filter { it.apiPath == "/admin/user" }.map {
            Json.parseToJsonElement(it.bodyText()).jsonObject["page"]!!.jsonPrimitive.int
        }
        assertEquals(listOf(1, 2, 3), pages)

        // 过滤：从第 1 页重新加载并带上条件
        vm.on(AdminListIntent.SetCondition("user_email", " u1 "))
        eventually { env.server.requests.count { it.apiPath == "/admin/user" } == 4 && !vm.state.value.loading }
        val filtered = env.server.requests.last { it.apiPath == "/admin/user" }.bodyText()
        assertTrue(filtered.contains("\"page\":1"))
        assertTrue(filtered.contains("\"conditions\":{\"user_email\":\"u1\"}"))

        // 多选删除
        vm.on(AdminListIntent.ToggleSelection(3))
        vm.on(AdminListIntent.ToggleSelection(4))
        vm.on(AdminListIntent.DeleteSelected)
        eventually { effects.any { it is AdminListEffect.Deleted } }
        assertTrue(env.server.requests.last { it.apiPath == "/admin/user/batch/delete" }.bodyText().contains("[3,4]"))
        assertTrue(vm.state.value.items.none { it.id == 3 || it.id == 4 })
        assertTrue(vm.state.value.selection.isEmpty())

        // 在网页后台打开
        vm.on(AdminListIntent.OpenInWeb)
        eventually { effects.any { it is AdminListEffect.OpenUrl } }
        assertEquals(
            "https://cloud.example.com/session/handoff?code=code&redirect=%2Fadmin%2Fuser",
            effects.filterIsInstance<AdminListEffect.OpenUrl>().single().url,
        )
        assertEquals("cloudreve://h5@my", vm.filesUri(vm.state.value.items.first { it.id == 5 }))
        vm.dispose()
    }
}
