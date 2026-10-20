package plus.ling.opencloudreve.core.presentation.settings

import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import plus.ling.opencloudreve.core.api.ApiClient
import plus.ling.opencloudreve.core.auth.AuthEvent
import plus.ling.opencloudreve.core.auth.AuthHarness
import plus.ling.opencloudreve.core.auth.PasskeyFlow
import plus.ling.opencloudreve.core.auth.SITE
import plus.ling.opencloudreve.core.auth.bodyText
import plus.ling.opencloudreve.core.auth.envelope
import plus.ling.opencloudreve.core.auth.token
import plus.ling.opencloudreve.core.model.User
import plus.ling.opencloudreve.core.platform.PlatformContext
import plus.ling.opencloudreve.core.settings.UserSettingsRepository
import plus.ling.opencloudreve.core.site.SiteConfigRepository
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class SecurityViewModelTest {

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun eventually(block: () -> Boolean) = withContext(Dispatchers.Default) {
        withTimeout(5_000) { while (!block()) delay(10) }
    }

    private val apiPath: HttpRequestData.() -> String = { url.encodedPath.removePrefix(ApiClient.API_PREFIX) }

    private class Env(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) {
        val h = AuthHarness(handler)
        val account = h.accounts.addOrUpdate(SITE, User(id = "u1", email = "me@example.com", nickname = "Me"), token("t", 2.hours, h.clock.now))
        val userSettings = UserSettingsRepository(account.id, h.registry) { _, _ -> }
        val site = SiteConfigRepository(h.registry, h.registry::anonymous, h.database, Dispatchers.Unconfined) { h.clock.now }

        fun vm() = SecurityViewModel(
            account.id, userSettings, h.accounts, PasskeyFlow(h.registry, h.accounts), site, PlatformContext.PLATFORM_ANDROID,
        )
    }

    @Test
    fun changePasswordSignsOutAndEmitsReloginEffect() = runTest {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val requests = mutableListOf<HttpRequestData>()
        val env = Env { req ->
            requests += req
            when (req.apiPath()) {
                "/user/setting" -> if (req.method == HttpMethod.Get) envelope(0, """{"two_fa_enabled":false,"passwordless":false}""") else envelope(0)
                "/session/token" -> envelope(0)
                "/user/me" -> envelope(0, """{"id":"u1","email":"me@example.com","nickname":"Me"}""")
                else -> envelope(404)
            }
        }
        val events = mutableListOf<AuthEvent>()
        backgroundScope.launch(Dispatchers.Unconfined) { env.h.accounts.authEvents.collect { events += it } }
        val vm = env.vm()
        val effects = mutableListOf<SecurityEffect>()
        backgroundScope.launch(Dispatchers.Unconfined) { vm.effects.collect { effects += it } }
        eventually { vm.state.value.settings != null }
        assertTrue(vm.state.value.canChangePassword)

        // 新密码太短：本地校验，不发请求
        vm.on(SecurityIntent.ChangePassword("oldpass", "123"))
        eventually { effects.isNotEmpty() }
        assertIs<SecurityEffect.Failed>(effects.removeAt(0))

        vm.on(SecurityIntent.ChangePassword("oldpass", "newpass1"))
        eventually { effects.any { it is SecurityEffect.PasswordChanged } }
        val patch = requests.first { it.apiPath() == "/user/setting" && it.method == HttpMethod.Patch }
        assertTrue(patch.bodyText().contains("\"current_password\":\"oldpass\""))
        assertTrue(patch.bodyText().contains("\"new_password\":\"newpass1\""))
        // 登出信号：AuthEvent.SignedOut（主动登出），账户已删除，令牌已清
        eventually { events.any { it is AuthEvent.SignedOut } }
        val signedOut = events.filterIsInstance<AuthEvent.SignedOut>().single()
        assertEquals(env.account.id, signedOut.accountId)
        assertNull(env.h.accounts.account(env.account.id))
        assertNull(env.h.sessions.token(env.account.id))
        val effect = effects.filterIsInstance<SecurityEffect.PasswordChanged>().single()
        assertEquals(SITE, effect.siteUrl)
        assertEquals("me@example.com", effect.email)
        assertNull(vm.state.value.busy)
        vm.dispose()
    }

    @Test
    fun enableTwoFactorFlow() = runTest {
        Dispatchers.setMain(Dispatchers.Unconfined)
        var enabled = false
        val requests = mutableListOf<HttpRequestData>()
        val env = Env { req ->
            requests += req
            when (req.apiPath()) {
                "/user/setting" -> when (req.method) {
                    HttpMethod.Get -> envelope(0, """{"two_fa_enabled":$enabled}""")
                    else -> if (req.bodyText().contains("\"two_fa_code\":\"123456\"")) {
                        enabled = true
                        envelope(0)
                    } else {
                        envelope(40022, msg = "Incorrect 2FA code")
                    }
                }
                "/user/setting/2fa" -> envelope(0, "\"JBSWY3DPEHPK3PXP\"")
                "/user/me" -> envelope(0, """{"id":"u1","email":"me@example.com","nickname":"Me"}""")
                "/site/config/basic" -> envelope(0, """{"title":"My Cloud"}""")
                else -> envelope(404)
            }
        }
        val vm = env.vm()
        val effects = mutableListOf<SecurityEffect>()
        backgroundScope.launch(Dispatchers.Unconfined) { vm.effects.collect { effects += it } }
        eventually { vm.state.value.settings != null }
        assertEquals(false, vm.state.value.twoFactorEnabled)

        vm.on(SecurityIntent.BeginEnableTwoFactor)
        eventually { vm.state.value.twoFactorSetup != null }
        val setup = vm.state.value.twoFactorSetup!!
        assertEquals("JBSWY3DPEHPK3PXP", setup.secret)
        assertEquals("otpauth://totp/My%20Cloud:me%40example.com?secret=JBSWY3DPEHPK3PXP&issuer=My%20Cloud", setup.otpauthUrl)

        // 错误验证码：保持设置流程，提示 TwoFactor 错误
        vm.on(SecurityIntent.ConfirmEnableTwoFactor("000000"))
        eventually { effects.isNotEmpty() }
        val failed = assertIs<SecurityEffect.Failed>(effects.removeAt(0))
        assertEquals(plus.ling.opencloudreve.core.presentation.ErrorKind.TwoFactor, failed.error.kind)
        assertTrue(vm.state.value.twoFactorSetup != null)

        vm.on(SecurityIntent.ConfirmEnableTwoFactor(" 123456 "))
        eventually { effects.any { it is SecurityEffect.TwoFactorEnabled } }
        assertNull(vm.state.value.twoFactorSetup)
        assertTrue(vm.state.value.twoFactorEnabled)
        val ok = requests.last { it.apiPath() == "/user/setting" && it.method == HttpMethod.Patch }
        assertTrue(ok.bodyText().contains("\"two_fa_enabled\":true"))
        vm.dispose()
    }
}
