package dev.dimension.flare.data.network.vvo

import dev.dimension.flare.data.platform.VVoCredential
import dev.dimension.flare.data.platform.VVoStoredCookie
import dev.dimension.flare.ui.presenter.login.LoginCookieAttributes
import dev.dimension.flare.ui.presenter.login.LoginCookieSnapshot
import dev.dimension.flare.ui.presenter.login.LoginCookieValue
import io.ktor.http.Url
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VVoCookieJarTest {
    private val now = 1_795_866_000_000L

    @Test
    fun separatesSameNamedSubAndScfCookiesAcrossWeiboComAndWeiboCn() {
        val jar =
            VVoCookieJar(
                listOf(
                    VVoStoredCookie(name = "MLOGIN", value = "1", domain = "weibo.cn", hostOnly = false),
                    VVoStoredCookie(name = "SUB", value = "cn-sub", domain = "weibo.cn", hostOnly = false),
                    VVoStoredCookie(name = "SCF", value = "cn-scf", domain = "weibo.cn", hostOnly = false),
                    VVoStoredCookie(name = "SUB", value = "com-sub", domain = "weibo.com", hostOnly = false),
                    VVoStoredCookie(name = "SCF", value = "com-scf", domain = "weibo.com", hostOnly = false),
                    VVoStoredCookie(name = "ALC", value = "sina-alc", domain = "sina.com.cn", hostOnly = false),
                    VVoStoredCookie(name = "SRF", value = "visitor-srf", domain = "visitor.passport.weibo.cn", hostOnly = true),
                ),
            )

        assertEquals(
            "MLOGIN=1; SUB=cn-sub; SCF=cn-scf",
            jar.cookieHeaderFor(Url("https://m.weibo.cn/api/config"), now),
        )
        assertEquals(
            "SUB=com-sub; SCF=com-scf",
            jar.cookieHeaderFor(Url("https://passport.weibo.com/sso/signin"), now),
        )
        assertEquals(
            "ALC=sina-alc",
            jar.cookieHeaderFor(Url("https://login.sina.com.cn/sso/login.php"), now),
        )
        assertEquals(
            "SRF=visitor-srf; MLOGIN=1; SUB=cn-sub; SCF=cn-scf",
            jar.cookieHeaderFor(Url("https://visitor.passport.weibo.cn/visitor/visitor"), now),
        )
    }

    @Test
    fun legacyCredentialWithoutStoredCookiesMigratesOntoWeiboCnDomainAndRoundTripsJson() {
        val legacy =
            Json.decodeFromString(
                VVoCredential.serializer(),
                """{"chocolate":"MLOGIN=1; SUB=legacy=="}""",
            )
        assertEquals(emptyList(), legacy.cookies)
        assertNull(legacy.userAgent)

        val jar = VVoCookieJar.fromCredential(legacy)
        assertEquals("MLOGIN=1; SUB=legacy==", jar.cookieHeaderFor(Url("https://m.weibo.cn/"), now))
        assertNull(jar.cookieHeaderFor(Url("https://passport.weibo.com/"), now))

        val upgraded = jar.toCredential(base = legacy, userAgent = "TestSafari/18.0", lastRefreshEpochMillis = now, nowEpochMillis = now)
        val encoded = Json.encodeToString(VVoCredential.serializer(), upgraded)
        val decoded = Json.decodeFromString(VVoCredential.serializer(), encoded)
        assertEquals("MLOGIN=1; SUB=legacy==", decoded.chocolate)
        assertEquals("TestSafari/18.0", decoded.userAgent)
        assertEquals(2, decoded.cookies.size)
    }

    @Test
    fun desktopBareRootDomainsAreTreatedAsSubdomainMatchingWhileAndroidSourceUrlCookiesStayPerHost() {
        val snapshot =
            LoginCookieSnapshot(
                values =
                    listOf(
                        LoginCookieValue(
                            sourceUrl = "https://weibo.com/",
                            name = "SUB",
                            value = "desktop-com-sub",
                            attributes = LoginCookieAttributes(domain = "weibo.com", path = "/", secure = true),
                        ),
                        LoginCookieValue(
                            sourceUrl = "https://login.sina.com.cn/",
                            name = "ALC",
                            value = "android-alc=",
                            attributes = null,
                        ),
                        LoginCookieValue(
                            sourceUrl = "https://m.weibo.cn/",
                            name = "MLOGIN",
                            value = "1",
                            attributes = null,
                        ),
                        LoginCookieValue(
                            sourceUrl = "https://m.weibo.cn/",
                            name = "SUB",
                            value = "android-cn-sub",
                            attributes = null,
                        ),
                        LoginCookieValue(
                            sourceUrl = "https://evil.example/",
                            name = "EVIL",
                            value = "nope",
                            attributes = LoginCookieAttributes(domain = ".evil.example", path = "/"),
                        ),
                    ),
                userAgent = "Mozilla/5.0 Mobile",
            )

        val jar = VVoCookieJar.fromLoginSnapshot(snapshot, now)
        assertEquals("SUB=desktop-com-sub", jar.cookieHeaderFor(Url("https://passport.weibo.com/sso/signin"), now))
        assertEquals("ALC=android-alc=", jar.cookieHeaderFor(Url("https://login.sina.com.cn/sso/login.php"), now))
        assertEquals("MLOGIN=1; SUB=android-cn-sub", jar.cookieHeaderFor(Url("https://m.weibo.cn/api/config"), now))
        assertNull(jar.cookieHeaderFor(Url("https://evil.example/"), now))
    }

    @Test
    fun applySetCookiesHandlesCommaExpiresEvictsMaxAgeZeroAndRejectsPublicSuffixOrForeignDomain() {
        val jar =
            VVoCookieJar(
                listOf(
                    VVoStoredCookie(name = "MLOGIN", value = "1", domain = "m.weibo.cn", hostOnly = true),
                    VVoStoredCookie(name = "SUB", value = "android-old-cn", domain = "m.weibo.cn", hostOnly = true),
                    VVoStoredCookie(name = "SUB", value = "com-sub", domain = "weibo.com", hostOnly = false),
                ),
            )

        val changed =
            jar.applySetCookies(
                requestUrl = Url("https://m.weibo.cn/"),
                setCookieHeaders =
                    listOf(
                        "SUB=rotated==; Expires=Fri, 31 Dec 2099 23:59:59 GMT; Path=/; Domain=.weibo.cn; Secure; HttpOnly",
                    ),
                nowEpochMillis = now,
            )
        assertTrue(changed)
        assertEquals("MLOGIN=1; SUB=rotated==", jar.cookieHeaderFor(Url("https://m.weibo.cn/api/config"), now))
        assertEquals("SUB=com-sub", jar.cookieHeaderFor(Url("https://passport.weibo.com/"), now))

        assertFalse(
            jar.applySetCookies(
                requestUrl = Url("https://m.weibo.cn/"),
                setCookieHeaders =
                    listOf(
                        "EVIL=1; Domain=.com.cn; Path=/; Secure",
                        "EVIL2=1; Domain=.evil.example; Path=/; Secure",
                        "CROSS=1; Domain=.weibo.com; Path=/; Secure",
                    ),
                nowEpochMillis = now,
            ),
        )

        assertTrue(
            jar.applySetCookies(
                requestUrl = Url("https://m.weibo.cn/"),
                setCookieHeaders = listOf("SUB=deleted; Max-Age=0; Path=/; Domain=.weibo.cn"),
                nowEpochMillis = now,
            ),
        )
        assertEquals("MLOGIN=1", jar.cookieHeaderFor(Url("https://m.weibo.cn/api/config"), now))
        assertEquals("SUB=com-sub", jar.cookieHeaderFor(Url("https://passport.weibo.com/"), now))
    }

    @Test
    fun hasLongLivedSsoCookiesAndProbeUrlListCoverExpectedOrigins() {
        assertEquals(
            listOf(
                "https://m.weibo.cn/",
                "https://passport.weibo.cn/",
                "https://visitor.passport.weibo.cn/",
                "https://weibo.com/",
                "https://passport.weibo.com/",
                "https://visitor.passport.weibo.com/",
                "https://login.sina.com.cn/",
            ),
            VVO_COOKIE_PROBE_URLS,
        )

        val shortOnly = VVoCookieJar(listOf(VVoStoredCookie("SUB", "cn", "weibo.cn", hostOnly = false)))
        assertFalse(shortOnly.hasLongLivedSsoCookies(now))

        val withAlc =
            VVoCookieJar(
                listOf(
                    VVoStoredCookie("SUB", "cn", "weibo.cn", hostOnly = false),
                    VVoStoredCookie("ALC", "alc", "sina.com.cn", hostOnly = false, expiresAtEpochMillis = now + 86_400_000L),
                ),
            )
        assertTrue(withAlc.hasLongLivedSsoCookies(now))

        val expiredAlc =
            VVoCookieJar(
                listOf(
                    VVoStoredCookie("ALC", "alc", "sina.com.cn", hostOnly = false, expiresAtEpochMillis = now - 1L),
                ),
            )
        assertFalse(expiredAlc.hasLongLivedSsoCookies(now))
    }

    @Test
    fun hasValidMWeiboLoginCookiesRequiresMloginOneAndNonBlankSub() {
        assertFalse(hasValidMWeiboLoginCookies(mapOf("MLOGIN" to "1")))
        assertFalse(hasValidMWeiboLoginCookies(mapOf("MLOGIN" to "0", "SUB" to "sub")))
        assertFalse(hasValidMWeiboLoginCookies(mapOf("MLOGIN" to "1", "SUB" to "  ")))
        assertTrue(hasValidMWeiboLoginCookies(mapOf("MLOGIN" to "1", "SUB" to "sub-ok")))
    }

    @Test
    fun androidMultiProbeCookiesPromoteSharedRootDomainAndAcceptPassportCrossdomainRotation() {
        val snapshot =
            LoginCookieSnapshot(
                values =
                    listOf(
                        LoginCookieValue("https://m.weibo.cn/", "MLOGIN", "1", attributes = null),
                        LoginCookieValue("https://passport.weibo.cn/", "MLOGIN", "1", attributes = null),
                        LoginCookieValue("https://m.weibo.cn/", "SUB", "stale-sub", attributes = null),
                        LoginCookieValue("https://m.weibo.cn/", "SUB", "android-cn-sub", attributes = null),
                        LoginCookieValue("https://passport.weibo.cn/", "SUB", "android-cn-sub", attributes = null),
                        LoginCookieValue("https://visitor.passport.weibo.cn/", "SUB", "android-cn-sub", attributes = null),
                        LoginCookieValue("https://visitor.passport.weibo.cn/", "SRF", "visitor-only-srf", attributes = null),
                    ),
            )
        val jar = VVoCookieJar.fromLoginSnapshot(snapshot, now)
        val storedSub = jar.toStoredCookies(now).single { it.name == "SUB" }
        assertEquals("weibo.cn", storedSub.domain)
        assertFalse(storedSub.hostOnly)
        val storedSrf = jar.toStoredCookies(now).single { it.name == "SRF" }
        assertEquals("visitor.passport.weibo.cn", storedSrf.domain)
        assertTrue(storedSrf.hostOnly)

        // Even when an explicit host-only cookie exists on m.weibo.cn, a Domain=.weibo.cn rotation from passport.weibo.cn replaces it.
        val hostShadowJar =
            VVoCookieJar(
                listOf(
                    VVoStoredCookie("MLOGIN", "1", "m.weibo.cn", hostOnly = true),
                    VVoStoredCookie("SUB", "old-host-sub", "m.weibo.cn", hostOnly = true),
                ),
            )
        assertTrue(
            hostShadowJar.applySetCookies(
                requestUrl = Url("https://passport.weibo.cn/sso/crossdomain"),
                setCookieHeaders =
                    listOf(
                        "SUB=rotated-from-passport; Max-Age=9223372036854775; Path=/; Domain=.weibo.cn; Secure; HttpOnly",
                    ),
                nowEpochMillis = now,
            ),
        )
        assertEquals(
            "MLOGIN=1; SUB=rotated-from-passport",
            hostShadowJar.toCredential(nowEpochMillis = now).chocolate,
        )

        // Merging a host-only seed from Android Stage 2 also evicts stale domain-wide SUB on the parent root domain.
        val mergedJar =
            VVoCookieJar(
                listOf(
                    VVoStoredCookie("MLOGIN", "1", "weibo.cn", hostOnly = false),
                    VVoStoredCookie("SUB", "old-domain-sub", "weibo.cn", hostOnly = false),
                ),
            )
        mergedJar.mergeSilentSeedCookies(
            listOf(
                dev.dimension.flare.data.repository.SilentSeedCookie(
                    name = "SUB",
                    value = "stage2-host-sub",
                    domain = "m.weibo.cn",
                    hostOnly = true,
                    path = "/",
                ),
            ),
            nowEpochMillis = now,
        )
        assertEquals(listOf("stage2-host-sub"), mergedJar.toStoredCookies(now).filter { it.name == "SUB" }.map { it.value })

        // Same-batch hostOnly cookies are preserved even when followed by a parent domain cookie in the same Renewed payload.
        val batchJar = VVoCookieJar()
        batchJar.mergeSilentSeedCookies(
            listOf(
                dev.dimension.flare.data.repository.SilentSeedCookie("X", "host-val", "visitor.passport.weibo.cn", hostOnly = true, path = "/"),
                dev.dimension.flare.data.repository.SilentSeedCookie("X", "domain-val", "weibo.cn", hostOnly = false, path = "/"),
            ),
            nowEpochMillis = now,
        )
        assertEquals(2, batchJar.toStoredCookies(now).size)
    }
}
