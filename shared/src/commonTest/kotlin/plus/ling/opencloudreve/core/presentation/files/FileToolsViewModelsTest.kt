package plus.ling.opencloudreve.core.presentation.files

import app.cash.turbine.test
import io.ktor.http.HttpMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import plus.ling.opencloudreve.core.api.apiPath
import plus.ling.opencloudreve.core.api.bodyText
import plus.ling.opencloudreve.core.api.json
import plus.ling.opencloudreve.core.files.ACCOUNT
import plus.ling.opencloudreve.core.files.err
import plus.ling.opencloudreve.core.files.okData
import plus.ling.opencloudreve.core.model.BoolSet
import plus.ling.opencloudreve.core.model.CustomPropDefinition
import plus.ling.opencloudreve.core.model.GroupPermission
import plus.ling.opencloudreve.core.presentation.VmEnv
import plus.ling.opencloudreve.core.presentation.eventually
import plus.ling.opencloudreve.core.presentation.userJson
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileToolsViewModelsTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun versionsListAndSetCurrent() = runTest {
        var primary = "v2"
        val env = VmEnv { req ->
            when {
                req.apiPath == "/file/info" -> okData(
                    """{"type":0,"id":"f","name":"a.txt","path":"cloudreve://my/a.txt","owned":true,"primary_entity":"$primary",""" +
                        """"extended_info":{"entities":[{"id":"v1","type":0,"size":1,"created_at":"2026-01-01T00:00:00Z"},""" +
                        """{"id":"t","type":1},{"id":"v2","type":0,"size":2,"created_at":"2026-02-01T00:00:00Z"}]}}""",
                )
                req.apiPath == "/file/version/current" -> {
                    primary = "v1"
                    okData()
                }
                req.apiPath == "/user/me" -> okData(userJson())
                else -> err(404)
            }
        }
        val vm = VersionsViewModel(ACCOUNT, "cloudreve://my/a.txt", env.files, env.userSettings)
        eventually { vm.state.value.versions.size == 2 }
        val s = vm.state.value
        assertEquals(listOf("v2", "v1"), s.versions.map { it.id })
        assertTrue(s.versions[0].isCurrent)
        assertFalse(s.versions[0].canModify)
        assertTrue(s.versions[1].canModify)
        vm.effects.test {
            vm.on(VersionsIntent.SetCurrent("v1"))
            assertEquals(VersionsEffect.Updated, awaitItem())
        }
        assertEquals("""{"uri":"cloudreve://my/a.txt","version":"v1"}""", env.server.requests.first { it.apiPath == "/file/version/current" }.bodyText())
        assertTrue(vm.state.value.versions.first { it.id == "v1" }.isCurrent)
        // 当前版本不可删除：不发请求
        vm.on(VersionsIntent.Delete("v1"))
        assertTrue(env.server.requests.none { it.apiPath == "/file/version" })
        vm.dispose()
    }

    @Test
    fun customPropValidationMirrorsServer() {
        val num = CustomPropDefinition(id = "n", type = CustomPropDefinition.TYPE_NUMBER, min = 1, max = 10)
        assertEquals(PropValidationError.TOO_SMALL, CustomPropCodec.validate(num, "0"))
        assertEquals(PropValidationError.TOO_LARGE, CustomPropCodec.validate(num, "11"))
        assertEquals(PropValidationError.NOT_A_NUMBER, CustomPropCodec.validate(num, "x"))
        assertNull(CustomPropCodec.validate(num, ""))
        val text = CustomPropDefinition(id = "t", type = CustomPropDefinition.TYPE_TEXT, min = 2, max = 3)
        assertEquals(PropValidationError.TOO_SHORT, CustomPropCodec.validate(text, "a"))
        assertEquals(PropValidationError.TOO_LONG, CustomPropCodec.validate(text, "中文"))
        val multi = CustomPropDefinition(id = "m", type = CustomPropDefinition.TYPE_MULTI_SELECT, options = listOf("a", "b"))
        assertNull(CustomPropCodec.validate(multi, CustomPropCodec.encodeMultiSelect(listOf("a", "b"))))
        assertEquals(PropValidationError.INVALID_OPTION, CustomPropCodec.validate(multi, """["c"]"""))
        val bool = CustomPropDefinition(id = "b", type = CustomPropDefinition.TYPE_BOOLEAN)
        assertEquals(PropValidationError.NOT_BOOLEAN, CustomPropCodec.validate(bool, "yes"))
        assertEquals(PropValidationError.UNKNOWN_PROP, CustomPropCodec.validate(null, "x"))
    }

    @Test
    fun tagsDiffAndPropsAndIcon() = runTest {
        var metadata = """{"tag:old":"#f44336","tag:keep":"","props:n":"5"}"""
        val env = VmEnv { req ->
            when {
                req.apiPath == "/file/info" -> okData(
                    """{"type":1,"id":"d","name":"dir","path":"cloudreve://my/dir","owned":true,"capability":"${BoolSet.of(8).toBase64()}","metadata":$metadata}""",
                )
                req.apiPath == "/file/metadata" && req.method == HttpMethod.Patch -> {
                    metadata = """{"tag:keep":"","tag:new":"#2196f3","props:n":"5"}"""
                    okData()
                }
                req.apiPath == "/site/config/explorer" -> okData(
                    """{"custom_props":[{"id":"n","name":"Num","type":"number","min":1,"max":10},{"id":"s","name":"Sel","type":"select","options":["x"]}]}""",
                )
                req.apiPath == "/site/config/emojis" -> okData("""{"emoji_preset":"{\"😀\":[\"😀\",\"🎉\"]}"}""")
                req.apiPath == "/user/me" -> okData(userJson())
                else -> err(404)
            }
        }
        val vm = MetadataEditViewModel(ACCOUNT, listOf("cloudreve://my/dir"), env.files, env.site, env.userSettings)
        eventually { vm.state.value.files.isNotEmpty() && vm.state.value.customPropDefinitions.size == 2 && vm.state.value.emojiPresets.isNotEmpty() }
        val s = vm.state.value
        assertTrue(s.canEdit)
        assertTrue(s.canChangeIconColor)
        assertEquals(listOf("old", "keep"), s.tags.map { it.name })
        assertEquals(5L, s.props.single().numberValue)
        assertEquals("Num", s.props.single().name)
        assertEquals(listOf("s"), s.availableProps.map { it.id })

        vm.on(MetadataEditIntent.RemoveTag("old"))
        vm.on(MetadataEditIntent.AddTag("new", "#2196f3"))
        vm.on(MetadataEditIntent.AddTag("keep"))
        assertTrue(vm.state.value.tagsDirty)
        vm.effects.test {
            vm.on(MetadataEditIntent.SaveTags)
            assertEquals(MetadataEditEffect.Updated, awaitItem())
        }
        assertEquals(
            """{"uris":["cloudreve://my/dir"],"patches":[{"key":"tag:keep","value":"","remove":false},{"key":"tag:new","value":"#2196f3","remove":false},{"key":"tag:old","value":"","remove":true}]}""",
            env.server.requests.last { it.apiPath == "/file/metadata" }.bodyText(),
        )
        assertFalse(vm.state.value.tagsDirty)
        assertEquals(listOf("keep", "new"), vm.state.value.tags.map { it.name })

        // 本地校验失败不提交
        val before = env.server.requests.count { it.apiPath == "/file/metadata" }
        vm.on(MetadataEditIntent.SetProp("n", "42"))
        assertEquals(PropValidationError.TOO_LARGE, vm.state.value.propErrors["n"])
        assertEquals(before, env.server.requests.count { it.apiPath == "/file/metadata" })

        vm.effects.test {
            vm.on(MetadataEditIntent.SetIconColor(null))
            assertEquals(MetadataEditEffect.Updated, awaitItem())
        }
        assertEquals(
            """{"uris":["cloudreve://my/dir"],"patches":[{"key":"customize:icon_color","value":"","remove":true}]}""",
            env.server.requests.last { it.apiPath == "/file/metadata" }.bodyText(),
        )
        vm.dispose()
    }

    @Test
    fun directLinksGenerateWithFailuresAndForceDownload() = runTest {
        val env = VmEnv { req ->
            when (req.apiPath) {
                "/user/me" -> okData(userJson(permissionB64 = BoolSet.of(GroupPermission.SHARE).toBase64(), batch = 10))
                "/file/source" -> json(
                    """{"code":40081,"msg":"partial","data":[{"link":"https://h/f/abc/a.txt","file_url":"cloudreve://my/a.txt"}],""" +
                        """"aggregated_error":{"cloudreve://my/b.txt":{"code":40044,"msg":"nf"}}}""",
                )
                else -> err(404)
            }
        }
        val vm = DirectLinksViewModel(ACCOUNT, listOf("cloudreve://my/a.txt", "cloudreve://my/b.txt"), env.files, env.userSettings)
        eventually { vm.state.value.user != null }
        assertEquals(10, vm.state.value.batchLimit)
        assertTrue(vm.state.value.canGenerate)
        vm.on(DirectLinksIntent.Generate)
        eventually { vm.state.value.generated.isNotEmpty() }
        val s = vm.state.value
        assertEquals("a.txt", s.generated.single().name)
        assertEquals("cloudreve://my/b.txt", s.failures.single().uri)
        vm.on(DirectLinksIntent.SetForceDownload(true))
        vm.on(DirectLinksIntent.SetShowFileName(true))
        assertEquals("[a.txt] https://h/f/d/abc/a.txt", vm.state.value.copyText)
        vm.dispose()
    }
}
