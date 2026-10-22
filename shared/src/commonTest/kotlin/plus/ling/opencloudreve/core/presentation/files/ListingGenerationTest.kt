package plus.ling.opencloudreve.core.presentation.files

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import plus.ling.opencloudreve.core.api.apiPath
import plus.ling.opencloudreve.core.events.EventHub
import plus.ling.opencloudreve.core.files.ACCOUNT
import plus.ling.opencloudreve.core.files.DataEnv
import plus.ling.opencloudreve.core.files.err
import plus.ling.opencloudreve.core.files.fileJson
import plus.ling.opencloudreve.core.files.okData
import plus.ling.opencloudreve.core.settings.UserSettingsRepository
import plus.ling.opencloudreve.core.presentation.eventually
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.milliseconds

/** 替换 pager（换目录/换排序）后，旧 pager 上在途的追加页不得回写当前状态 */
class ListingGenerationTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun settle() = withContext(Dispatchers.Default) { delay(300) }

    private fun listing(dir: String, folders: List<String>, nextToken: String?) =
        """{"files":[${folders.joinToString(",") { fileJson(it, dir = dir, folder = true) }}],""" +
            """"pagination":{"page":0,"page_size":100,"is_cursor":true""" +
            (if (nextToken != null) ""","next_token":"$nextToken"""" else "") + "}," +
            """"props":{"capability":"/f+3"}}"""

    @Test
    fun folderPickerDropsOldDirectoryPageAfterOpeningAnother() = runTest {
        val oldPage = CompletableDeferred<Unit>()
        val env = DataEnv { req ->
            when {
                req.apiPath != "/file" -> err(404)
                req.url.parameters["uri"] == "cloudreve://my/A" -> if (req.url.parameters["next_page_token"] == null) {
                    okData(listing("cloudreve://my/A", listOf("a1"), nextToken = "A2"))
                } else {
                    oldPage.await()
                    okData(listing("cloudreve://my/A", listOf("a2"), nextToken = null))
                }
                req.url.parameters["uri"] == "cloudreve://my/B" -> okData(listing("cloudreve://my/B", listOf("b1"), nextToken = null))
                else -> err(404)
            }
        }
        val vm = FolderPickerViewModel(ACCOUNT, "cloudreve://my/A", emptyList(), env.files)
        eventually { vm.state.value.folders.map { it.name } == listOf("a1") }
        vm.on(FolderPickerIntent.LoadMore)
        eventually { env.server.requests.any { it.url.parameters["next_page_token"] == "A2" } }
        vm.on(FolderPickerIntent.Open("cloudreve://my/B"))
        eventually { vm.state.value.folders.map { it.name } == listOf("b1") }
        // A 的追加页最后返回
        oldPage.complete(Unit)
        settle()
        assertEquals("cloudreve://my/B", vm.state.value.uri)
        assertEquals(listOf("b1"), vm.state.value.folders.map { it.name })
        assertFalse(vm.state.value.hasMore)
        assertFalse(vm.state.value.loadingMore)
        vm.dispose()
    }

    @Test
    fun fileListDropsOldSortPageAfterSortChange() = runTest {
        val oldPage = CompletableDeferred<Unit>()
        val env = DataEnv { req ->
            when (req.apiPath) {
                "/file" -> when {
                    req.url.parameters["order_by"] == "created_at" -> okData(listing("cloudreve://my", listOf("c1", "c2"), nextToken = null))
                    req.url.parameters["next_page_token"] == null -> okData(listing("cloudreve://my", listOf("n1"), nextToken = "N2"))
                    else -> {
                        oldPage.await()
                        okData(listing("cloudreve://my", listOf("n2"), nextToken = null))
                    }
                }
                else -> err(404)
            }
        }
        val user = UserSettingsRepository(ACCOUNT, env.clients) { _, _ -> }
        val hub = EventHub(ACCOUNT, env.clients, initialBackoff = 10_000.milliseconds)
        val vm = FileListViewModel(ACCOUNT, "cloudreve://my", env.files, user, env.site, hub)
        eventually { vm.state.value.files.map { it.name } == listOf("n1") && vm.state.value.hasMore }
        vm.on(FileListIntent.LoadMore)
        eventually { env.server.requests.any { it.url.parameters["next_page_token"] == "N2" } }
        vm.on(FileListIntent.SetSort("created_at", "desc"))
        eventually { vm.state.value.files.map { it.name } == listOf("c1", "c2") }
        // 旧排序的追加页最后返回
        oldPage.complete(Unit)
        settle()
        assertEquals(listOf("c1", "c2"), vm.state.value.files.map { it.name })
        assertFalse(vm.state.value.hasMore)
        assertFalse(vm.state.value.loadingMore)
        vm.dispose()
    }
}
