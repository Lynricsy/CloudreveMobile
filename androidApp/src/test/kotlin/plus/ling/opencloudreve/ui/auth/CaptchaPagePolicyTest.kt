package plus.ling.opencloudreve.ui.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptchaPagePolicyTest {
    private val captcha = "http://10.0.2.2:5212/captcha?type=turnstile&dark=0"

    @Test
    fun originIsNormalized() {
        assertEquals("https://cloud.example.com", CaptchaPagePolicy.origin("HTTPS://Cloud.Example.com:443/captcha"))
        assertEquals("http://10.0.2.2:5212", CaptchaPagePolicy.origin(captcha))
        assertNull(CaptchaPagePolicy.origin("javascript:alert(1)"))
        assertNull(CaptchaPagePolicy.origin("file:///sdcard/x.html"))
    }

    @Test
    fun tokenFromTheCaptchaPageMainFrameIsAccepted() {
        assertTrue(CaptchaPagePolicy.acceptsToken("http://10.0.2.2:5212/captcha?type=turnstile", captcha, true, "http://10.0.2.2:5212"))
    }

    @Test
    fun tokenFromAnIframeIsRejected() {
        assertFalse(CaptchaPagePolicy.acceptsToken("http://10.0.2.2:5212/captcha", captcha, false, "http://10.0.2.2:5212"))
    }

    @Test
    fun tokenFromAnotherOriginIsRejected() {
        assertFalse(CaptchaPagePolicy.acceptsToken("http://10.0.2.2:5212/captcha", captcha, true, "https://evil.example"))
        assertFalse(CaptchaPagePolicy.acceptsToken("https://10.0.2.2:5212/captcha", captcha, true, "https://10.0.2.2:5212"))
        assertFalse(CaptchaPagePolicy.acceptsToken("http://10.0.2.2:5213/captcha", captcha, true, "http://10.0.2.2:5213"))
    }

    @Test
    fun tokenFromAnotherPageOfTheSiteIsRejected() {
        assertFalse(CaptchaPagePolicy.acceptsToken("http://10.0.2.2:5212/s/abc", captcha, true, "http://10.0.2.2:5212"))
        assertFalse(CaptchaPagePolicy.acceptsToken("http://10.0.2.2:5212/captcha/../s/abc", captcha, true, "http://10.0.2.2:5212"))
        assertFalse(CaptchaPagePolicy.acceptsToken(null, captcha, true, "http://10.0.2.2:5212"))
    }

    @Test
    fun mainFrameMayOnlyNavigateWithinTheSite() {
        assertTrue(CaptchaPagePolicy.allowsMainFrame("http://10.0.2.2:5212/captcha?x=1", captcha))
        assertFalse(CaptchaPagePolicy.allowsMainFrame("https://evil.example/captcha", captcha))
        assertFalse(CaptchaPagePolicy.allowsMainFrame("intent://scan#Intent;end", captcha))
    }
}
