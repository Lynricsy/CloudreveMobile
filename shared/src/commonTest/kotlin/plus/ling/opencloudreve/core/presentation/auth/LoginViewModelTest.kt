package plus.ling.opencloudreve.core.presentation.auth

import app.cash.turbine.test
import io.ktor.http.HttpMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import plus.ling.opencloudreve.core.auth.AuthHarness
import plus.ling.opencloudreve.core.auth.PasskeyFlow
import plus.ling.opencloudreve.core.auth.SITE
import plus.ling.opencloudreve.core.auth.bodyText
import plus.ling.opencloudreve.core.auth.envelope
import plus.ling.opencloudreve.core.auth.token
import plus.ling.opencloudreve.core.auth.tokenJson
import plus.ling.opencloudreve.core.auth.userJson
import plus.ling.opencloudreve.core.presentation.ErrorKind
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class LoginViewModelTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** 可配置的假服务器：记录请求路径，按路径返回 */
    private class FakeServer(
        val captchaType: String = "normal",
        val loginCaptcha: Boolean = false,
        val version: String = "4.19.11",
    ) {
        val requests = mutableListOf<String>()
        val loginBodies = mutableListOf<String>()
        var loginResponses = ArrayDeque<Pair<Int, String>>()
        var captchaCount = 0

        val harness: AuthHarness = AuthHarness { req ->
            val path = req.url.encodedPath.removePrefix("/api/v4")
            requests += "${req.method.value} $path"
            when {
                path == "/site/ping" -> envelope(0, "\"$version\"")
                path == "/site/config/basic" ->
                    envelope(0, """{"title":"Test Cloud","logo":"/static/img/logo.svg","captcha_type":"$captchaType"}""")
                path == "/site/config/login" ->
                    envelope(0, """{"login_captcha":$loginCaptcha,"authn":true,"register_enabled":true,"tos_url":"https://t.example/tos"}""")
                path == "/site/captcha" -> {
                    captchaCount++
                    envelope(0, """{"image":"data:image/png;base64,IMG$captchaCount","ticket":"ticket-$captchaCount"}""")
                }
                path == "/session/prepare" -> envelope(0, """{"password_enabled":true,"webauthn_enabled":false}""")
                path == "/session/token" && req.method == HttpMethod.Post -> {
                    loginBodies += req.bodyText()
                    val (code, data) = loginResponses.removeFirst()
                    envelope(code, data)
                }
                path == "/session/token/2fa" -> {
                    loginBodies += req.bodyText()
                    val t = token("a", 1.hours, clockNow())
                    envelope(0, """{"user":${userJson("u1", "alice@example.com")},"token":${tokenJson(t)}}""")
                }
                else -> envelope(404, msg = "unexpected $path")
            }
        }

        fun clockNow() = harness.clock.now

        fun viewModel() = LoginViewModel(
            LoginParams(SITE),
            harness.registry,
            harness.login,
            PasskeyFlow(harness.registry, harness.accounts),
            harness.pkce,
        )
    }

    @Test
    fun passwordThenTwoFactorThenDone() = runTest {
        val server = FakeServer()
        server.loginResponses.addLast(203 to "\"sess-2fa\"")
        val vm = server.viewModel()

        val loaded = vm.awaitState { !it.loadingConfig }
        assertFalse(loaded.loadingConfig)
        assertEquals("Test Cloud", loaded.siteTitle)
        assertEquals("$SITE/static/img/logo.svg", loaded.logoUrl)
        assertTrue(loaded.enhanced)
        assertTrue(loaded.sitePasskeyEnabled)
        assertTrue(loaded.registerEnabled)
        assertEquals(LoginStage.Email, loaded.stage)

        vm.on(LoginIntent.EmailChanged("alice@example.com"))
        vm.on(LoginIntent.ContinueWithEmail)
        vm.awaitState { it.stage == LoginStage.Password && !it.submitting }
        // login_captcha 关闭：不需要验证码
        assertEquals(CaptchaKind.None, vm.state.value.captcha.kind)

        vm.on(LoginIntent.PasswordChanged("secret"))
        vm.on(LoginIntent.SubmitPassword)
        vm.awaitState { it.stage == LoginStage.TwoFactor }
        assertFalse(vm.state.value.submitting)

        vm.effects.test {
            // 输入 5 位不提交，第 6 位自动提交
            vm.on(LoginIntent.OtpChanged("12345"))
            assertEquals(1, server.loginBodies.size)
            vm.on(LoginIntent.OtpChanged("12 3456"))
            val effect = awaitItem()
            assertTrue(effect is LoginEffect.SignedIn)
            assertEquals(server.harness.accounts.activeAccount.value?.id, effect.accountId)
        }
        assertTrue(server.loginBodies[1].contains("\"otp\":\"123456\""))
        assertTrue(server.loginBodies[1].contains("\"session_id\":\"sess-2fa\""))
        assertEquals("alice@example.com", server.harness.accounts.activeAccount.value?.email)
        assertNull(vm.state.value.error)
    }

    @Test
    fun captchaErrorRefreshesImage() = runTest {
        val server = FakeServer(loginCaptcha = true)
        server.loginResponses.addLast(40026 to "null")
        val vm = server.viewModel()
        vm.awaitState { !it.loadingConfig }

        vm.on(LoginIntent.EmailChanged("alice@example.com"))
        vm.on(LoginIntent.ContinueWithEmail)
        val withCaptcha = vm.awaitState { it.stage == LoginStage.Password && it.captcha.ticket != null }
        assertEquals(LoginStage.Password, withCaptcha.stage)
        assertEquals(CaptchaKind.Image, withCaptcha.captcha.kind)
        assertEquals("IMG1", withCaptcha.captcha.image)
        assertEquals("ticket-1", withCaptcha.captcha.ticket)

        vm.on(LoginIntent.PasswordChanged("secret"))
        // 未填验证码不能提交
        assertFalse(vm.state.value.canSubmitPassword)
        vm.on(LoginIntent.CaptchaAnswerChanged("abcd"))
        vm.on(LoginIntent.SubmitPassword)

        val after = vm.awaitState { it.error != null && it.captcha.ticket == "ticket-2" }
        assertEquals(ErrorKind.Captcha, after.error?.kind)
        assertEquals(40026, after.error?.code)
        assertEquals(LoginStage.Password, after.stage)
        assertFalse(after.submitting)
        // 验证码已刷新、输入清空
        assertEquals(2, server.captchaCount)
        assertEquals("ticket-2", after.captcha.ticket)
        assertEquals("", after.captcha.answer)
        assertTrue(server.loginBodies[0].contains("\"captcha\":\"abcd\""))
        assertTrue(server.loginBodies[0].contains("\"ticket\":\"ticket-1\""))
    }

    @Test
    fun webCaptchaNeedsEnhancedServer() = runTest {
        val modern = FakeServer(captchaType = "turnstile", loginCaptcha = true).viewModel()
        modern.awaitState { !it.loadingConfig }
        modern.on(LoginIntent.EmailChanged("a@example.com"))
        modern.on(LoginIntent.ContinueWithEmail)
        modern.awaitState { it.stage == LoginStage.Password }
        assertEquals(CaptchaKind.Web, modern.state.value.captcha.kind)
        assertFalse(modern.state.value.captcha.isReady)
        modern.on(LoginIntent.WebCaptchaSolved("""{"captcha":"tok"}"""))
        assertTrue(modern.state.value.captcha.isReady)
        assertEquals(
            "$SITE/captcha?theme=dark&lng=zh-CN",
            modern.state.value.captchaPageUrl(dark = true, language = "zh-CN"),
        )

        val legacy = FakeServer(captchaType = "turnstile", loginCaptcha = true, version = "4.19.10").viewModel()
        legacy.awaitState { !it.loadingConfig }
        legacy.on(LoginIntent.EmailChanged("a@example.com"))
        legacy.on(LoginIntent.ContinueWithEmail)
        legacy.awaitState { it.stage == LoginStage.Password }
        assertFalse(legacy.state.value.enhanced)
        assertEquals(CaptchaKind.Unsupported, legacy.state.value.captcha.kind)
    }

    @Test
    fun versionComparison() {
        assertTrue(ServerVersion.isAtLeast("4.19.11", "4.19.11"))
        assertTrue(ServerVersion.isAtLeast("4.20.0", "4.19.11"))
        assertTrue(ServerVersion.isAtLeast("4.19.11-pro", "4.19.11"))
        assertFalse(ServerVersion.isAtLeast("4.19.10", "4.19.11"))
        assertFalse(ServerVersion.isAtLeast("3.8.3", "4.19.11"))
    }

    /** HTTP 在 Ktor 的 IO 线程完成，等待状态满足条件 */
    private suspend fun LoginViewModel.awaitState(predicate: (LoginUiState) -> Boolean): LoginUiState =
        state.first(predicate)
}
