package dev.dimension.flare.ui.presenter.login

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoginCookieProbeTest {
    @Test
    fun twoArgConstructorDefaultsCaptureAllToFalse() {
        val probe = LoginCookieProbe(url = "https://m.weibo.cn/", names = listOf("SUB"))
        assertFalse(probe.captureAll)
        assertEquals("https://m.weibo.cn/", probe.url)
        assertEquals(listOf("SUB"), probe.names)
    }

    @Test
    fun parseRawCookieHeaderCapturesAllNonBlankPairsPreservingBase64PaddingWhenCaptureAllTrue() {
        val probe = LoginCookieProbe(url = "https://passport.weibo.com/", captureAll = true)
        assertTrue(probe.captureAll)
        val parsed =
            parseRawCookieHeaderForProbe(
                rawHeader = "SUB=val1; X-CSRF-TOKEN=abc==; ; =bad; ALC=alc_token=",
                probe = probe,
            )
        assertEquals(
            listOf(
                LoginCookieValue(
                    sourceUrl = "https://passport.weibo.com/",
                    name = "SUB",
                    value = "val1",
                ),
                LoginCookieValue(
                    sourceUrl = "https://passport.weibo.com/",
                    name = "X-CSRF-TOKEN",
                    value = "abc==",
                ),
                LoginCookieValue(
                    sourceUrl = "https://passport.weibo.com/",
                    name = "ALC",
                    value = "alc_token=",
                ),
            ),
            parsed,
        )
    }

    @Test
    fun parseRawCookieHeaderFiltersByNamesWhenCaptureAllFalse() {
        val probe = LoginCookieProbe(url = "https://m.weibo.cn/", names = listOf("SUB", "MLOGIN"))
        val parsed =
            parseRawCookieHeaderForProbe(
                rawHeader = "MLOGIN=1; SUB=sub_val; _T_WM=visitor; XSRF-TOKEN=csrf",
                probe = probe,
            )
        assertEquals(
            listOf(
                LoginCookieValue(sourceUrl = "https://m.weibo.cn/", name = "MLOGIN", value = "1"),
                LoginCookieValue(sourceUrl = "https://m.weibo.cn/", name = "SUB", value = "sub_val"),
            ),
            parsed,
        )
        assertEquals(emptyList(), parseRawCookieHeaderForProbe(rawHeader = null, probe = probe))
        assertEquals(emptyList(), parseRawCookieHeaderForProbe(rawHeader = "   ", probe = probe))
    }
}
