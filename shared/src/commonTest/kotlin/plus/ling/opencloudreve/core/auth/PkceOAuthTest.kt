package plus.ling.opencloudreve.core.auth

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parseQueryString
import kotlinx.coroutines.test.runTest
import plus.ling.opencloudreve.core.api.ApiException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class PkceOAuthTest {
    @Test
    fun verifierLengthAndCharset() {
        val seen = HashSet<String>()
        repeat(50) {
            val v = PkceOAuth.generateCodeVerifier()
            assertTrue(v.length in 43..128, "length ${v.length}")
            assertTrue(v.all { it.isLetterOrDigit() && it.code < 128 || it in "-._~" }, v)
            seen += v
        }
        assertEquals(50, seen.size)
    }

    @Test
    fun s256ChallengeMatchesRfc7636AppendixB() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            PkceOAuth.codeChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun authorizeUrlCarriesAllParameters() {
        val h = AuthHarness { envelope(404) }
        val auth = h.pkce.begin("cloud.example.com/")
        assertTrue(auth.authorizeUrl.startsWith("https://cloud.example.com/session/authorize?"))
        assertTrue(auth.authorizeUrl.contains("redirect_uri=opencloudreve%3A%2F%2Foauth%2Fcallback"))
        val q = parseQueryString(auth.authorizeUrl.substringAfter('?'))
        assertEquals(PkceOAuth.CLIENT_ID, q["client_id"])
        assertEquals("code", q["response_type"])
        assertEquals(auth.state, q["state"])
        assertEquals(PkceOAuth.SCOPES.joinToString(" "), q["scope"])
        assertEquals(PkceOAuth.codeChallenge(auth.codeVerifier), q["code_challenge"])
        assertEquals("S256", q["code_challenge_method"])
    }

    @Test
    fun callbackStateIsChecked() {
        val h = AuthHarness { envelope(404) }
        assertEquals("abc", h.pkce.parseCallback("opencloudreve://oauth/callback?code=abc&state=s1", "s1"))
        assertFailsWith<OAuthCallbackException> { h.pkce.parseCallback("opencloudreve://oauth/callback?code=abc&state=s2", "s1") }
        assertFailsWith<OAuthCallbackException> {
            h.pkce.parseCallback("opencloudreve://oauth/callback?error=access_denied&state=s1", "s1")
        }
    }

    @Test
    fun exchangeSavesAccount() = runTest {
        val h = AuthHarness { req ->
            when {
                req.url.encodedPath.endsWith("/session/oauth/token") -> {
                    val form = parseQueryString(req.bodyText())
                    assertEquals(PkceOAuth.CLIENT_SECRET, form["client_secret"])
                    assertEquals("authorization_code", form["grant_type"])
                    assertEquals("the-code", form["code"])
                    assertEquals("the-verifier", form["code_verifier"])
                    assertEquals(PkceOAuth.REDIRECT_URI, form["redirect_uri"])
                    respondJson(
                        """{"access_token":"at","token_type":"Bearer","expires_in":3600,
                           "refresh_token_expires_in":7776000,"refresh_token":"rt","scope":"openid"}""",
                    )
                }
                req.url.encodedPath.endsWith("/user/me") -> {
                    assertEquals("Bearer at", req.headers[HttpHeaders.Authorization])
                    envelope(0, userJson("u9"))
                }
                else -> envelope(404)
            }
        }
        val acc = h.pkce.exchange(SITE, "the-code", "the-verifier")
        assertEquals("u9@cloud.example.com", acc.id)
        assertTrue(acc.isActive)
        val t = h.sessions.token(acc.id)!!
        assertEquals("rt", t.refreshToken)
        assertEquals(h.clock.now + 3600.seconds, t.accessExpires)
        assertEquals(h.clock.now + 7776000.seconds, t.refreshExpires)
    }

    @Test
    fun exchangeFailureMapsHttp400() = runTest {
        val h = AuthHarness {
            respondJson(
                """{"error":"invalid_grant","error_description":"Invalid code verifier","error_codes":[40020]}""",
                HttpStatusCode.BadRequest,
            )
        }
        val e = assertFailsWith<ApiException> { h.pkce.exchange(SITE, "c", "v") }
        assertEquals(40020, e.code)
        assertNotEquals("", e.serverMsg)
    }
}
