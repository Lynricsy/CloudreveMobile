package plus.ling.opencloudreve.core.auth

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import plus.ling.opencloudreve.core.api.ApiClient
import plus.ling.opencloudreve.core.api.ApiException
import plus.ling.opencloudreve.core.auth.model.CaptchaParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class LoginFlowTest {
    @Test
    fun prepareReportsMethods() = runTest {
        val h = AuthHarness { req ->
            assertEquals("/api/v4/session/prepare", req.url.encodedPath)
            assertEquals("a@example.com", req.url.parameters["email"])
            envelope(0, """{"webauthn_enabled":true,"password_enabled":false}""")
        }
        assertEquals(LoginStep.Password(passwordEnabled = false, webauthnEnabled = true), h.login.prepare(SITE, " a@example.com "))
    }

    @Test
    fun code203LeadsToTwoFactorThenDone() = runTest {
        lateinit var h: AuthHarness
        h = AuthHarness { req ->
            assertNull(req.headers[HttpHeaders.Authorization])
            assertTrue(req.headers.contains(ApiClient.HEADER_CLIENT_ID))
            when (req.url.encodedPath) {
                "/api/v4/session/token" -> {
                    assertEquals(HttpMethod.Post, req.method)
                    val body = Json.parseToJsonElement(req.bodyText()).jsonObject
                    assertEquals("a@example.com", body["email"]!!.jsonPrimitive.content)
                    assertEquals("secret", body["password"]!!.jsonPrimitive.content)
                    // 验证码字段平铺在同一 JSON 中，空字段不输出
                    assertEquals("tk", body["ticket"]!!.jsonPrimitive.content)
                    assertFalse(body.containsKey("captcha"))
                    envelope(203, "\"2fa-session\"")
                }
                "/api/v4/session/token/2fa" -> {
                    val body = Json.parseToJsonElement(req.bodyText()).jsonObject
                    assertEquals("123456", body["otp"]!!.jsonPrimitive.content)
                    assertEquals("2fa-session", body["session_id"]!!.jsonPrimitive.content)
                    envelope(0, """{"user":${userJson("u1")},"token":${tokenJson(token("t", 1.hours, h.clock.now))}}""")
                }
                else -> envelope(404)
            }
        }
        val step = h.login.login(SITE, "a@example.com", "secret", CaptchaParams(ticket = "tk"))
        assertEquals(LoginStep.TwoFactor("2fa-session"), step)
        val done = h.login.verify2fa(SITE, "2fa-session", "123456")
        assertEquals("u1@cloud.example.com", done.account.id)
        assertEquals(done.account, h.accounts.activeAccount.value)
        assertEquals("access-t", h.sessions.token(done.account.id)?.accessToken)
    }

    @Test
    fun directLoginCompletes() = runTest {
        lateinit var h: AuthHarness
        h = AuthHarness {
            envelope(0, """{"user":${userJson("u2")},"token":${tokenJson(token("t", 1.hours, h.clock.now))}}""")
        }
        val step = h.login.login(SITE, "b@example.com", "pw")
        assertIs<LoginStep.Done>(step)
        assertEquals("nick-u2", step.account.nickname)
    }

    @Test
    fun wrongPasswordThrows() = runTest {
        val h = AuthHarness { envelope(40080, msg = "Incorrect password or email address") }
        val e = assertFailsWith<ApiException> { h.login.login(SITE, "a@example.com", "bad") }
        assertEquals(40080, e.code)
        assertTrue(h.accounts.accounts.value.isEmpty())
    }
}
