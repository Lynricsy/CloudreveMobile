package plus.ling.opencloudreve.core.presentation.viewer

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import plus.ling.opencloudreve.core.api.apiPath
import plus.ling.opencloudreve.core.api.bodyText
import plus.ling.opencloudreve.core.files.ACCOUNT
import plus.ling.opencloudreve.core.files.DataEnv
import plus.ling.opencloudreve.core.files.err
import plus.ling.opencloudreve.core.files.okData
import plus.ling.opencloudreve.core.model.ArchivedFile
import plus.ling.opencloudreve.core.model.FileItem
import plus.ling.opencloudreve.core.model.FileType
import plus.ling.opencloudreve.core.presentation.ErrorKind
import plus.ling.opencloudreve.core.settings.UserSettingsRepository
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ViewerViewModelsTest {

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun eventually(block: () -> Boolean) = withContext(Dispatchers.Default) {
        withTimeout(5_000) { while (!block()) delay(10) }
    }

    private fun file(name: String, metadata: Map<String, String> = emptyMap(), folder: Boolean = false) = FileItem(
        type = if (folder) FileType.FOLDER else FileType.FILE,
        id = "id-$name",
        name = name,
        path = "cloudreve://my/videos/$name",
        owned = true,
        primaryEntity = "v1",
        metadata = metadata,
    )

    @Test
    fun archiveTreeSynthesizesParentsAndSortsDirectoriesFirst() {
        val tree = ViewerArchiveViewModel.buildTree(
            listOf(
                ArchivedFile(name = "/b.txt", size = 1),
                ArchivedFile(name = "a/x/deep.txt", size = 2),
                ArchivedFile(name = "z/", isDirectory = true),
                ArchivedFile(name = "a/c.txt", size = 3),
                ArchivedFile(name = "a", isDirectory = true),
            ),
        )
        assertEquals(listOf("a", "a/x", "z", "a/c.txt", "a/x/deep.txt", "b.txt"), tree.map { it.path })
        assertEquals(listOf(true, true, true, false, false, false), tree.map { it.isDirectory })
        val state = ViewerArchiveState(file("x.zip"), entries = tree, currentPath = "a")
        assertEquals(listOf("x", "c.txt"), state.children.map { it.name })
        assertEquals(listOf("a", "z", "b.txt"), state.copy(currentPath = "").children.map { it.path })
    }

    @Test
    fun subtitlesMatchVideoNameFirstAndChaptersParse() {
        val video = file("Movie.2024.mkv")
        val siblings = listOf(
            file("other.srt"),
            video,
            file("Movie.2024.zh.ass"),
            file("notes.txt"),
            file("Movie.2024.vtt"),
            file("subs.srt", folder = true),
            file("Movie.ssa"),
        )
        val subs = ViewerVideoViewModel.subtitlesFor(video, siblings)
        assertEquals(listOf("Movie.2024.zh.ass", "Movie.2024.vtt", "other.srt", "Movie.ssa"), subs.map { it.file.name })
        assertEquals(listOf(true, true, false, false), subs.map { it.matchesVideo })
        assertEquals("ass", subs.first().format)

        val chapters = ViewerVideoViewModel.chapters(
            file(
                "a.mp4",
                mapOf(
                    "stream:chapter_1_start_time" to "60.5",
                    "stream:chapter_1_end_time" to "120",
                    "stream:chapter_1_name" to "Two",
                    "stream:chapter_0_start_time" to "0",
                    "stream:chapter_0_end_time" to "60.5",
                    "stream:chapter_0_name" to "One",
                    "stream:duration" to "120",
                ),
            ),
        )
        assertEquals(listOf("One", "Two"), chapters.map { it.title })
        assertEquals(60.5, chapters[1].startSeconds)
        assertEquals(120.0, chapters[1].endSeconds)
    }

    @Test
    fun customViewerTemplateAndTargetClassification() {
        val url = ViewerWebViewModel.fillTemplate(
            "https://v.example/?src={\$src}&n={\$name}&t={\$theme}",
            mapOf("src" to "a%2Fb", "name" to "x", "theme" to "dark"),
        )
        assertEquals("https://v.example/?src=a%2Fb&n=x&t=dark", url)
        assertIs<WebViewerTarget.ExternalApp>(ViewerWebViewModel.classify("iina://open?url=x", null))
        assertIs<WebViewerTarget.ExternalApp>(ViewerWebViewModel.classify("nplayer://x", "true"))
        assertIs<WebViewerTarget.ExternalBrowser>(ViewerWebViewModel.classify("https://x", "yes"))
        val embedded = assertIs<WebViewerTarget.Embedded>(ViewerWebViewModel.classify("http://x", "false"))
        assertFalse(embedded.viaHandoff)
    }

    @Test
    fun textCodecKeepsBom() {
        val utf16 = TextCodec.encode("héllo", TextEncoding.Utf16Le)
        val decoded = TextCodec.decode(utf16)
        assertEquals(TextEncoding.Utf16Le, decoded.encoding)
        assertEquals("héllo", decoded.text)
        val bom = TextCodec.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "x".encodeToByteArray())
        assertEquals(TextEncoding.Utf8Bom, bom.encoding)
        assertEquals("x", bom.text)
    }

    @Test
    fun textSaveHandlesStaleVersionLockAndTooLarge() = runTest {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val savedJson = { entity: String, name: String ->
            """{"type":0,"id":"id-$name","name":"$name","path":"cloudreve://my/docs/$name","owned":true,"primary_entity":"$entity"}"""
        }
        // 依次返回的 PUT /file/content 响应
        val putResponses = ArrayDeque(listOf("stale", "ok:v2", "stale", "saveas", "lock", "ok:v3", "large"))
        val env = DataEnv { req ->
            when {
                req.apiPath == "/file/url" -> okData("""{"urls":[{"url":"/api/v4/file/content/abc/0/a.txt?sign=x"}],"expires":"2099-01-01T00:00:00Z"}""")
                req.apiPath.startsWith("/file/content/abc") -> respond("hello", HttpStatusCode.OK)
                req.apiPath == "/file/content" && req.method == HttpMethod.Put -> when (val r = putResponses.removeFirst()) {
                    "stale" -> err(40076, "File is updated during your edit")
                    "lock" -> err(40073, "Lock conflict", """[{"path":"/docs/a.txt","token":"tk1","owner":{"application":{"type":"webdav"}}}]""")
                    "large" -> err(40049, "File too large")
                    "saveas" -> okData(savedJson("n1", "a (1).txt"))
                    else -> okData(savedJson(r.removePrefix("ok:"), "a.txt"))
                }
                req.apiPath == "/file/lock" -> okData()
                else -> err(404)
            }
        }
        val user = UserSettingsRepository(ACCOUNT, env.clients) { _, _ -> }
        val viewerEnv = ViewerEnvironment(env.files, env.site, user, env.clients, env.clock)
        val doc = FileItem(id = "f1", name = "a.txt", path = "cloudreve://my/docs/a.txt", owned = true, primaryEntity = "v1")
        val vm = ViewerTextViewModel(ViewerParams(ACCOUNT, doc, viewerId = BuiltinViewerId.MONACO), viewerEnv)
        val effects = mutableListOf<ViewerTextEffect>()
        backgroundScope.launch(Dispatchers.Unconfined) { vm.effects.collect { effects += it } }

        eventually { vm.state.value.original == "hello" }
        assertFalse(vm.state.value.readOnly)
        assertEquals("v1", vm.state.value.baseVersion)
        assertFalse(vm.state.value.canSave)
        val puts = { env.server.requests.filter { it.apiPath == "/file/content" && it.method == HttpMethod.Put } }

        // 1. 版本过期 → 覆盖（不带 previous）
        vm.on(ViewerTextIntent.Edit("hello world"))
        vm.on(ViewerTextIntent.Save)
        eventually { vm.state.value.staleVersion }
        assertEquals("v1", puts().last().url.parameters["previous"])
        assertEquals("cloudreve://my/docs/a.txt", puts().last().url.parameters["uri"])
        assertTrue(vm.state.value.dirty)
        vm.on(ViewerTextIntent.Overwrite)
        eventually { effects.any { it is ViewerTextEffect.Saved } }
        assertNull(puts().last().url.parameters["previous"])
        assertEquals("hello world", puts().last().bodyText())
        assertFalse(vm.state.value.staleVersion)
        assertFalse(vm.state.value.dirty)
        assertEquals("v2", vm.state.value.baseVersion)

        // 2. 版本过期 → 另存为（带原基线版本）
        vm.on(ViewerTextIntent.Edit("v3 text"))
        vm.on(ViewerTextIntent.Save)
        eventually { vm.state.value.staleVersion }
        vm.on(ViewerTextIntent.SaveAs("cloudreve://my/docs", "a (1).txt"))
        eventually { effects.any { it is ViewerTextEffect.SavedAs } }
        assertEquals("cloudreve://my/docs/a%20(1).txt", puts().last().url.parameters["uri"])
        assertEquals("v2", puts().last().url.parameters["previous"])
        assertEquals("v2", vm.state.value.baseVersion)

        // 3. 锁冲突 → 强制解锁并重放
        vm.on(ViewerTextIntent.Edit("locked text"))
        vm.on(ViewerTextIntent.Save)
        eventually { vm.state.value.lockConflicts.isNotEmpty() }
        assertEquals("tk1", vm.state.value.lockConflicts.single().token)
        vm.on(ViewerTextIntent.ForceUnlockAndSave)
        eventually { vm.state.value.baseVersion == "v3" }
        assertTrue(env.server.requests.single { it.apiPath == "/file/lock" }.bodyText().contains("tk1"))
        assertTrue(vm.state.value.lockConflicts.isEmpty())

        // 4. 超限 → 失败事件
        vm.on(ViewerTextIntent.Edit("big"))
        vm.on(ViewerTextIntent.Save)
        eventually { effects.any { it is ViewerTextEffect.Failed } }
        assertEquals(ErrorKind.FileTooLarge, effects.filterIsInstance<ViewerTextEffect.Failed>().single().error.kind)
        assertFalse(vm.state.value.saving)
        assertTrue(vm.state.value.dirty)
        vm.dispose()
    }
}
