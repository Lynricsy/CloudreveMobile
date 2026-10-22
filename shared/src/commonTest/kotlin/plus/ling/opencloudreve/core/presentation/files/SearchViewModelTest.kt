package plus.ling.opencloudreve.core.presentation.files

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import plus.ling.opencloudreve.core.api.apiPath
import plus.ling.opencloudreve.core.files.ACCOUNT
import plus.ling.opencloudreve.core.files.DataEnv
import plus.ling.opencloudreve.core.files.err
import plus.ling.opencloudreve.core.files.fileJson
import plus.ling.opencloudreve.core.files.okData
import plus.ling.opencloudreve.core.model.CrUri
import plus.ling.opencloudreve.core.presentation.eventually
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SearchViewModelTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun hitJson(name: String) = """{"file":${fileJson(name)},"content":"…"}"""

    private fun listing(names: List<String>, nextToken: String?) =
        """{"files":[${names.joinToString(",") { fileJson(it) }}],"pagination":{"page":0,"page_size":100,"is_cursor":true""" +
            (if (nextToken != null) ""","next_token":"$nextToken"""" else "") + """},"props":{}}"""

    /** 搜索 URI 中的关键词 */
    private fun searchedName(uri: String?): String? = uri?.let { CrUri.parse(it).searchParams()?.names?.firstOrNull() }

    @Test
    fun newSearchDuringLoadMoreKeepsPaginationUsable() = runTest {
        // 关键词 a 的第二页永远挂起；提交新关键词 b 后，b 的结果仍能继续翻页
        val hang = CompletableDeferred<Unit>()
        val env = DataEnv { req ->
            when (req.apiPath) {
                "/file" -> {
                    val name = searchedName(req.url.parameters["uri"])
                    when (req.url.parameters["next_page_token"]) {
                        null -> okData(listing(listOf("$name-1"), nextToken = "$name-p2"))
                        "a-p2" -> {
                            hang.await()
                            okData(listing(listOf("a-2"), nextToken = null))
                        }
                        else -> okData(listing(listOf("$name-2"), nextToken = null))
                    }
                }
                else -> err(404)
            }
        }
        val vm = SearchViewModel(ACCOUNT, "cloudreve://my", env.files, env.site)
        vm.on(SearchIntent.SetQuery("a"))
        vm.on(SearchIntent.Submit)
        eventually { vm.state.value.results.map { it.name } == listOf("a-1") }
        vm.on(SearchIntent.LoadMore)
        eventually { env.server.requests.any { it.url.parameters["next_page_token"] == "a-p2" } }
        vm.on(SearchIntent.SetQuery("b"))
        vm.on(SearchIntent.Submit)
        eventually { vm.state.value.results.map { it.name } == listOf("b-1") }
        assertFalse(vm.state.value.loadingMore)
        vm.on(SearchIntent.LoadMore)
        eventually { vm.state.value.results.map { it.name } == listOf("b-1", "b-2") }
        assertFalse(vm.state.value.loadingMore)
        vm.dispose()
        // 放开被取消的旧请求并等它收尾，避免其续体在 resetMain 之后才回到主调度器
        hang.complete(Unit)
        withContext(Dispatchers.Default) { delay(200) }
    }

    @Test
    fun fullTextLoadMoreUsesSubmittedQueryNotDraft() = runTest {
        val env = DataEnv { req ->
            when (req.apiPath) {
                "/site/config/explorer" -> okData("""{"full_text_search":true}""")
                "/file/search" -> {
                    val q = req.url.parameters["query"]
                    val offset = req.url.parameters["offset"]
                    okData("""{"hits":[${hitJson("$q@$offset")}],"total":2}""")
                }
                else -> err(404)
            }
        }
        val vm = SearchViewModel(ACCOUNT, "cloudreve://my", env.files, env.site)
        eventually { vm.state.value.fullTextAvailable }
        vm.on(SearchIntent.SetFullText(true))
        vm.on(SearchIntent.SetQuery("alpha"))
        vm.on(SearchIntent.Submit)
        eventually { vm.state.value.fullTextHits.size == 1 }
        // 只改输入框、不提交，再翻页：仍应请求已提交的 alpha
        vm.on(SearchIntent.SetQuery("beta"))
        vm.on(SearchIntent.LoadMore)
        eventually { vm.state.value.fullTextHits.size == 2 }
        assertEquals(listOf("alpha@0", "alpha@1"), vm.state.value.fullTextHits.map { it.file.name })
        assertEquals("beta", vm.state.value.query)
        vm.dispose()
    }
}
