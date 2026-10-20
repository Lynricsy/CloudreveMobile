package plus.ling.opencloudreve.core.presentation.share

import app.cash.turbine.test
import io.ktor.http.HttpMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import plus.ling.opencloudreve.core.api.apiPath
import plus.ling.opencloudreve.core.api.bodyText
import plus.ling.opencloudreve.core.files.err
import plus.ling.opencloudreve.core.files.fileJson
import plus.ling.opencloudreve.core.files.okData
import plus.ling.opencloudreve.core.files.ACCOUNT
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ShareViewModelsTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun listing(files: String, singleFile: Boolean = false, nextToken: String? = null) =
        """{"files":[$files],"pagination":{"page":0,"page_size":100,"is_cursor":true${if (nextToken != null) ",\"next_token\":\"$nextToken\"" else ""}},""" +
            """"props":{"capability":"/f+3"},"single_file_view":$singleFile}"""

    private val ownerJson = """{"id":"owner","nickname":"O"}"""

    @Test
    fun parsesShareLinks() {
        val a = ShareLinkParser.parse("https://cloud.example.com/s/Ab12/pw9")!!
        assertEquals("Ab12", a.id)
        assertEquals("pw9", a.password)
        assertEquals("https://cloud.example.com", a.siteUrl)
        assertEquals("cloudreve://Ab12:pw9@share", a.rootUri)
        val b = ShareLinkParser.parse("http://host:5212/sub/s/XyZ?x=1")!!
        assertEquals("XyZ", b.id)
        assertNull(b.password)
        assertEquals("http://host:5212/sub", b.siteUrl)
        val c = ShareLinkParser.parse("cloudreve://XyZ:p@share/dir/a")!!
        assertEquals(listOf("dir", "a"), c.path)
        assertEquals("p", c.password)
        assertEquals("XyZ", ShareLinkParser.parse("  XyZ ")!!.id)
        assertEquals("Ab12", ShareLinkParser.parse("opencloudreve://open?url=https%3A%2F%2Fh%2Fs%2FAb12")!!.id)
        assertNull(ShareLinkParser.parse("cloudreve://my/a"))
        assertNull(ShareLinkParser.parse("https://h/files/x"))
    }

    @Test
    fun passwordRetryThenBrowseWithReadme() = runTest {
        val env = VmEnv { req ->
            when (req.apiPath) {
                "/share/info/S1" -> when (req.url.parameters["password"]) {
                    "good" -> okData(
                        """{"id":"S1","name":"Docs","unlocked":true,"password_protected":true,"source_type":1,"show_readme":true,"password":"good","owner":$ownerJson}""",
                    )
                    else -> okData("""{"id":"S1","unlocked":false,"password_protected":true,"source_type":1,"owner":$ownerJson}""")
                }
                "/file" -> okData(listing(fileJson("a.md", "cloudreve://S1:good@share") + "," + fileJson("b", "cloudreve://S1:good@share", folder = true), nextToken = "n1"))
                "/file/info" -> if (req.url.parameters["uri"]!!.endsWith("README.md")) {
                    okData(fileJson("README.md", "cloudreve://S1:good@share"))
                } else {
                    err(404)
                }
                "/user/me" -> okData(userJson("me"))
                else -> err(404)
            }
        }
        val vm = ShareVisitViewModel(ACCOUNT, "https://cloud.example.com/s/S1", "https://cloud.example.com", env.shares, env.files, env.userSettings)
        eventually { vm.state.value.needsPassword }
        assertFalse(vm.state.value.passwordIncorrect)
        assertFalse(vm.state.value.siteMismatch)
        assertEquals("true", env.server.requests.first().url.parameters["count_views"])

        vm.on(ShareVisitIntent.SubmitPassword("bad"))
        eventually { vm.state.value.passwordIncorrect && !vm.state.value.submittingPassword }
        assertTrue(vm.state.value.needsPassword)

        vm.on(ShareVisitIntent.SubmitPassword("good"))
        eventually { vm.state.value.files.size == 2 && vm.state.value.readme != null }
        val s = vm.state.value
        assertFalse(s.needsPassword)
        assertEquals("cloudreve://S1:good@share", s.currentUri)
        assertEquals(listOf("Docs"), s.crumbs.map { it.name })
        // 已加载部分没有 README 且列表未取全：按优先级取信息，README.md 存在即采用
        assertEquals("README.md", s.readme?.name)
        assertTrue(env.server.requests.none { it.apiPath == "/file/info" && it.url.parameters["uri"]!!.endsWith("README.txt") })
        assertTrue(s.canSave)
        assertEquals("cloudreve://S1:good@share", s.shortcutTarget)
        vm.dispose()
    }

    @Test
    fun singleFileShareSavesShortcutThenConflicts() = runTest {
        var creates = 0
        val env = VmEnv { req ->
            when {
                req.apiPath == "/share/info/F1" -> okData("""{"id":"F1","name":"a.bin","unlocked":true,"source_type":0,"owner":$ownerJson}""")
                req.apiPath == "/file" -> okData(listing(fileJson("a.bin", "cloudreve://F1@share"), singleFile = true))
                req.apiPath == "/file/create" && req.method == HttpMethod.Post -> {
                    creates++
                    if (creates == 1) okData(fileJson("a.bin", "cloudreve://my/dst")) else err(40004, "exists")
                }
                req.apiPath == "/user/me" -> okData(userJson("other"))
                else -> err(404)
            }
        }
        val vm = ShareVisitViewModel(ACCOUNT, "cloudreve://F1@share", null, env.shares, env.files, env.userSettings)
        eventually { vm.state.value.singleFile != null }
        assertEquals("a.bin", vm.state.value.singleFile?.name)
        assertNull(vm.state.value.readme)
        eventually { vm.state.value.canSave }
        vm.effects.test {
            vm.on(ShareVisitIntent.SaveToMyFiles("cloudreve://my/dst"))
            val saved = assertIs<ShareVisitEffect.Saved>(awaitItem())
            assertEquals("cloudreve://my/dst", saved.dstUri)
            vm.on(ShareVisitIntent.SaveToMyFiles("cloudreve://my/dst"))
            val failed = assertIs<ShareVisitEffect.Failed>(awaitItem())
            assertEquals(ErrorKind.Conflict, failed.error.kind)
        }
        val create = env.server.requests.first { it.apiPath == "/file/create" }
        assertEquals(
            """{"uri":"cloudreve://my/dst/a.bin","type":"file","metadata":{"sys:shared_redirect":"cloudreve://F1@share/a.bin",""" +
                """"sys:shared_owner":"owner"},"err_on_conflict":true}""",
            create.bodyText(),
        )
        assertFalse(vm.state.value.saving)
        vm.dispose()
    }

    @Test
    fun listing40069AsksForPasswordAndOwnerCannotSave() = runTest {
        val env = VmEnv { req ->
            when (req.apiPath) {
                "/share/info/P1" -> okData("""{"id":"P1","name":"D","unlocked":true,"source_type":1,"owner":{"id":"me"}}""")
                "/file" -> err(40069, "Incorrect share password")
                "/user/me" -> okData(userJson("me"))
                else -> err(404)
            }
        }
        val vm = ShareVisitViewModel(ACCOUNT, "P1", "https://x", env.shares, env.files, env.userSettings)
        eventually { vm.state.value.needsPassword && vm.state.value.isOwner }
        assertTrue(vm.state.value.passwordIncorrect)
        assertFalse(vm.state.value.canSave)
        vm.dispose()
    }

    @Test
    fun editKeepsRemainingExpiryAndParsesLink() = runTest {
        val now = Instant.parse("2026-10-21T00:00:00Z")
        val env = VmEnv { req ->
            when {
                req.apiPath == "/share/info/E1" -> okData(
                    """{"id":"E1","name":"a.txt","unlocked":true,"source_type":0,"is_private":true,"password":"abc","remain_downloads":3,""" +
                        """"expires":"2026-10-22T00:00:00Z","source_uri":"cloudreve://my/a.txt","owner":$ownerJson}""",
                )
                req.apiPath == "/share/E1" && req.method == HttpMethod.Post -> okData("\"https://cloud.example.com/s/E1/abc\"")
                else -> err(404)
            }
        }
        val vm = ShareEditViewModel(ACCOUNT, null, "E1", env.shares, env.files, clock = { now })
        eventually { vm.state.value.fileUri != null && !vm.state.value.loading }
        val s = vm.state.value
        assertTrue(s.isPrivate)
        assertEquals("abc", s.password)
        assertEquals(3, s.downloadLimit)
        assertEquals(86_400L, s.expireSeconds)
        assertTrue(s.canSubmit)
        vm.effects.test {
            vm.on(ShareEditIntent.Submit)
            val saved = assertIs<ShareEditEffect.Saved>(awaitItem())
            assertEquals("abc", saved.result.password)
            assertEquals("https://cloud.example.com/s/E1", saved.result.linkWithoutPassword)
        }
        val body = env.server.requests.last().bodyText()
        assertTrue(body.contains("\"uri\":\"cloudreve://my/a.txt\""), body)
        assertTrue(body.contains("\"expire\":86400"), body)
        assertTrue(body.contains("\"downloads\":3"), body)
        assertTrue(body.contains("\"password\":\"abc\""), body)

        vm.on(ShareEditIntent.SetPassword("bad pw!"))
        assertFalse(vm.state.value.canSubmit)
        vm.dispose()
    }

    @Test
    fun shareListPaginatesAndDeletes() = runTest {
        val env = VmEnv { req ->
            when {
                req.apiPath == "/share" && req.method == HttpMethod.Get -> if (req.url.parameters["next_page_token"] == null) {
                    okData("""{"shares":[{"id":"a"},{"id":"b"}],"pagination":{"is_cursor":true,"next_token":"t2"}}""")
                } else {
                    okData("""{"shares":[{"id":"c"}],"pagination":{"is_cursor":true}}""")
                }
                req.apiPath == "/share" && req.method == HttpMethod.Delete -> okData()
                else -> err(404)
            }
        }
        val vm = ShareListViewModel(ACCOUNT, env.shares)
        eventually { vm.state.value.shares.size == 2 }
        assertTrue(vm.state.value.hasMore)
        vm.on(ShareListIntent.LoadMore)
        eventually { vm.state.value.shares.size == 3 }
        assertFalse(vm.state.value.hasMore)
        vm.effects.test {
            vm.on(ShareListIntent.Delete(listOf("a", "c")))
            assertEquals(ShareListEffect.Deleted(2), awaitItem())
        }
        assertEquals(listOf("b"), vm.state.value.shares.map { it.id })
        assertEquals("""{"ids":["a","c"]}""", env.server.requests.last().bodyText())
        vm.dispose()
    }
}
