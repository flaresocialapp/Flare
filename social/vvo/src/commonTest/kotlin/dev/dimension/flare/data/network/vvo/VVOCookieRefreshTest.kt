package dev.dimension.flare.data.network.vvo

import dev.dimension.flare.data.platform.VVoCredential
import dev.dimension.flare.data.platform.VVoStoredCookie
import dev.dimension.flare.data.repository.SilentCookieRenewRequest
import dev.dimension.flare.data.repository.SilentCookieRenewResult
import dev.dimension.flare.data.repository.SilentSeedCookie
import dev.dimension.flare.data.repository.SilentWebCookieRenewer
import dev.dimension.flare.data.repository.isAllowedSilentRenewNavigationUrl
import dev.dimension.flare.data.repository.isWeiboInteractiveLoginUrl
import dev.dimension.flare.model.MicroBlogKey
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class VVOCookieRefreshTest {
    @Test
    fun extensionAuthenticationRejectsForeignOriginsAndUsesRefreshedCookie() =
        runTest {
            val current = MutableStateFlow(VVoCredential("MLOGIN=1; SUB=old; XSRF-TOKEN=csrf"))
            var requests = 0
            val service =
                VVOService(
                    credentialFlow = current,
                    refreshCookieWhenStale = true,
                    onCredentialRefreshed = { current.value = it },
                    httpClientFactory =
                        mockHttpClientFactory {
                            requests++
                            setCookieResponse("SUB=new; Path=/; Domain=.weibo.cn; HttpOnly")
                        },
                )
            for (url in listOf(
                "https://other.example/path",
                "http://m.weibo.cn/path",
                "https://m.weibo.cn:444/path",
                "https://user@m.weibo.cn/path",
            )) {
                kotlin.test.assertFailsWith<IllegalArgumentException> { service.extensionAuthHeaders(url) }
            }
            assertEquals(0, requests)
            val headers = service.extensionAuthHeaders("https://m.weibo.cn/api/config")
            assertEquals("MLOGIN=1; SUB=new; XSRF-TOKEN=csrf", headers[HttpHeaders.Cookie])
            assertEquals("csrf", headers["X-XSRF-TOKEN"])
            assertEquals("https://m.weibo.cn/", headers[HttpHeaders.Referrer])
            assertEquals(1, requests)
        }

    @Test
    fun configRefreshesStaleCookieBeforeRequestAndPersistsCredential() =
        runTest {
            val oldCookie = "MLOGIN=1; SUB=old-sub; XSRF-TOKEN=old-xsrf"
            val refreshedCookie = "MLOGIN=1; SUB=new-sub; XSRF-TOKEN=old-xsrf; NEW_COOKIE=new-value"
            val credentialFlow =
                MutableStateFlow(
                    VVoCredential(
                        chocolate = oldCookie,
                        lastCookieRefreshEpochMillis = null,
                    ),
                )
            val refreshCookies = mutableListOf<String?>()
            val configCookies = mutableListOf<String?>()

            val service =
                VVOService(
                    credentialFlow = credentialFlow,
                    refreshCookieWhenStale = true,
                    onCredentialRefreshed = { credential ->
                        credentialFlow.value = credential
                    },
                    httpClientFactory =
                        mockHttpClientFactory { request ->
                            when (request.url.encodedPath) {
                                "/" -> {
                                    refreshCookies += request.headers[HttpHeaders.Cookie]
                                    setCookieResponse(
                                        "SUB=new-sub; Path=/; Domain=.weibo.cn; HttpOnly",
                                        "NEW_COOKIE=new-value; Path=/; Domain=.weibo.cn; HttpOnly",
                                    )
                                }

                                "/api/config" -> {
                                    configCookies += request.headers[HttpHeaders.Cookie]
                                    configResponse(login = request.headers[HttpHeaders.Cookie] == refreshedCookie)
                                }

                                else -> {
                                    error("Unexpected request path: ${request.url.encodedPath}")
                                }
                            }
                        },
                )

            val response = service.config()

            assertEquals(true, response.data?.login)
            assertEquals(listOf<String?>(oldCookie), refreshCookies)
            assertEquals(listOf<String?>(refreshedCookie), configCookies)
            assertEquals(refreshedCookie, credentialFlow.value.chocolate)
            assertTrue(credentialFlow.value.lastCookieRefreshEpochMillis != null)
        }

    @Test
    fun configRefreshesAndRetriesWithReturnedCredentialWhenLoggedOut() =
        runTest {
            val oldCookie = "MLOGIN=1; SUB=old-sub"
            val refreshedCookie = "MLOGIN=1; SUB=new-sub"
            val credentialFlow = MutableStateFlow(VVoCredential(chocolate = oldCookie))
            var refreshedCredential: VVoCredential? = null
            val refreshCookies = mutableListOf<String?>()
            val configCookies = mutableListOf<String?>()

            val service =
                VVOService(
                    credentialFlow = credentialFlow,
                    refreshCookieWhenStale = false,
                    onCredentialRefreshed = { credential ->
                        refreshedCredential = credential
                    },
                    httpClientFactory =
                        mockHttpClientFactory { request ->
                            when (request.url.encodedPath) {
                                "/" -> {
                                    refreshCookies += request.headers[HttpHeaders.Cookie]
                                    setCookieResponse("SUB=new-sub; Path=/; Domain=.weibo.cn; HttpOnly")
                                }

                                "/api/config" -> {
                                    val cookie = request.headers[HttpHeaders.Cookie]
                                    configCookies += cookie
                                    configResponse(login = cookie == refreshedCookie)
                                }

                                else -> {
                                    error("Unexpected request path: ${request.url.encodedPath}")
                                }
                            }
                        },
                )

            val response = service.config()

            assertEquals(true, response.data?.login)
            assertEquals(listOf<String?>(oldCookie), refreshCookies)
            assertEquals(listOf<String?>(oldCookie, refreshedCookie), configCookies)
            assertEquals(oldCookie, credentialFlow.value.chocolate)
            assertEquals(refreshedCookie, refreshedCredential?.chocolate)
            assertTrue(refreshedCredential?.lastCookieRefreshEpochMillis != null)
        }

    @Test
    fun configKeepsRefreshingUntilLoginRecoversWithinRetryLimit() =
        runTest {
            val oldCookie = "MLOGIN=1; SUB=old-sub"
            val recoveredCookie = "MLOGIN=1; SUB=retry-3"
            val credentialFlow = MutableStateFlow(VVoCredential(chocolate = oldCookie))
            var refreshCalls = 0
            val configCookies = mutableListOf<String?>()

            val service =
                VVOService(
                    credentialFlow = credentialFlow,
                    refreshCookieWhenStale = false,
                    onCredentialRefreshed = { credential ->
                        credentialFlow.value = credential
                    },
                    httpClientFactory =
                        mockHttpClientFactory { request ->
                            when (request.url.encodedPath) {
                                "/" -> {
                                    refreshCalls += 1
                                    setCookieResponse("SUB=retry-$refreshCalls; Path=/; Domain=.weibo.cn; HttpOnly")
                                }

                                "/api/config" -> {
                                    val cookie = request.headers[HttpHeaders.Cookie]
                                    configCookies += cookie
                                    configResponse(login = cookie == recoveredCookie)
                                }

                                else -> {
                                    error("Unexpected request path: ${request.url.encodedPath}")
                                }
                            }
                        },
                )

            val response = service.config()

            assertEquals(true, response.data?.login)
            assertEquals(3, refreshCalls)
            assertEquals(
                listOf<String?>(
                    oldCookie,
                    "MLOGIN=1; SUB=retry-1",
                    "MLOGIN=1; SUB=retry-2",
                    recoveredCookie,
                ),
                configCookies,
            )
            assertEquals(recoveredCookie, credentialFlow.value.chocolate)
        }

    @Test
    fun refreshFollowsPassportSsoRedirectAndAdoptsNewSub() =
        runTest {
            val oldCookie = "MLOGIN=1; SUB=old-sub; SCF=old-scf; ALF=9999999999"
            val passportCookie = "MLOGIN=1; SUB=old-sub; SCF=new-scf; ALF=9999999999"
            val refreshedCookie = "MLOGIN=1; SUB=new-sub; SCF=new-scf; ALF=9999999999"
            val credentialFlow = MutableStateFlow(VVoCredential(chocolate = oldCookie))
            val homeHops = mutableListOf<String?>()

            val service =
                VVOService(
                    credentialFlow = credentialFlow,
                    refreshCookieWhenStale = false,
                    onCredentialRefreshed = { credentialFlow.value = it },
                    httpClientFactory =
                        mockHttpClientFactory { request ->
                            when {
                                request.url.host == "m.weibo.cn" && request.url.encodedPath == "/" -> {
                                    homeHops += request.headers[HttpHeaders.Cookie]
                                    redirectResponse(
                                        "https://passport.weibo.cn/sso/login?entry=mweibo&ticket=old",
                                        "SCF=new-scf; Path=/; Domain=.weibo.cn; HttpOnly",
                                    )
                                }

                                request.url.host == "passport.weibo.cn" && request.url.encodedPath == "/sso/login" -> {
                                    assertEquals(passportCookie, request.headers[HttpHeaders.Cookie])
                                    redirectResponse(
                                        "https://m.weibo.cn/sso/complete?ticket=new",
                                        "SUB=new-sub; Path=/; Domain=.weibo.cn; HttpOnly",
                                    )
                                }

                                request.url.encodedPath == "/sso/complete" -> {
                                    assertEquals("https://m.weibo.cn/sso/complete?ticket=new", request.url.toString())
                                    assertEquals(refreshedCookie, request.headers[HttpHeaders.Cookie])
                                    setCookieResponse()
                                }

                                request.url.encodedPath == "/api/config" -> {
                                    val cookie = request.headers[HttpHeaders.Cookie]
                                    configResponse(login = cookie?.contains("SUB=new-sub") == true)
                                }

                                else -> {
                                    error("Unexpected request: ${request.url}")
                                }
                            }
                        },
                )

            val response = service.config()

            assertEquals(true, response.data?.login)
            assertEquals(refreshedCookie, credentialFlow.value.chocolate)
            // The home hop is attempted with the stale cookie before the SSO leg re-signs it.
            assertEquals(listOf<String?>(oldCookie), homeHops)
        }

    @Test
    fun refreshStopsBeforeUntrustedRedirectTargets() =
        runTest {
            val oldCookie = "MLOGIN=1; SUB=old-sub; SCF=scf; ALF=9999999999"
            val refreshedCookie = "MLOGIN=1; SUB=new-sub; SCF=scf; ALF=9999999999"
            val passportUrl = "https://passport.weibo.cn/sso/login"
            val untrustedLocations =
                listOf(
                    "https://untrusted.example/collect",
                    "//untrusted.example/collect",
                    "https://passport.weibo.cn.untrusted.example/collect",
                    "https://passport.weibo.cn@untrusted.example/collect",
                    "http://passport.weibo.cn/collect",
                    "http://m.weibo.cn/collect",
                    "https://passport.weibo.cn:8443/collect",
                    "https://weibo.com.untrusted.example/collect",
                    "https://untrustedweibo.cn/collect",
                    "https://untrustedweibo.com/collect",
                    "https://login.sina.com.cn.untrusted.example/collect",
                    "https://news.sina.com.cn/collect",
                    "https://untrusted@passport.weibo.com/collect",
                    "http://passport.weibo.com/collect",
                    "https://passport.weibo.com:8443/collect",
                )

            for (location in untrustedLocations) {
                val credentialFlow = MutableStateFlow(VVoCredential(chocolate = oldCookie))
                val refreshHops = mutableListOf<String>()
                val service =
                    VVOService(
                        credentialFlow = credentialFlow,
                        refreshCookieWhenStale = true,
                        onCredentialRefreshed = { credentialFlow.value = it },
                        httpClientFactory =
                            mockHttpClientFactory { request ->
                                if (request.url.encodedPath == "/api/config") {
                                    return@mockHttpClientFactory configResponse(login = true)
                                }
                                refreshHops += request.url.toString()
                                when (request.url.toString()) {
                                    "https://m.weibo.cn/" -> {
                                        redirectResponse(passportUrl)
                                    }

                                    passportUrl -> {
                                        redirectResponse(
                                            location,
                                            "SUB=new-sub; Path=/; Domain=.weibo.cn; HttpOnly",
                                        )
                                    }

                                    else -> {
                                        setCookieResponse("SUB=untrusted-sub; Path=/")
                                    }
                                }
                            },
                    )

                assertEquals(true, service.config().data?.login)
                assertEquals(listOf("https://m.weibo.cn/", passportUrl), refreshHops, location)
                assertEquals(refreshedCookie, credentialFlow.value.chocolate, location)
            }
        }

    @Test
    fun refreshIsolatesCookiesPerDomainAcrossWeiboAndSinaSsoHops() =
        runTest {
            val initialCookies =
                listOf(
                    VVoStoredCookie(name = "MLOGIN", value = "1", domain = "weibo.cn", hostOnly = false),
                    VVoStoredCookie(name = "SUB", value = "old-sub", domain = "weibo.cn", hostOnly = false),
                    VVoStoredCookie(name = "ALF", value = "9999999999", domain = "weibo.cn", hostOnly = false),
                    VVoStoredCookie(name = "SUB", value = "initial-com-sub", domain = "weibo.com", hostOnly = false),
                    VVoStoredCookie(name = "ALC", value = "sina-alc", domain = "sina.com.cn", hostOnly = false),
                )
            val oldCnCookie = "MLOGIN=1; SUB=old-sub; ALF=9999999999"
            val afterHomeCn = "$oldCnCookie; SCF=shared-scf"
            val refreshedCnCookie = "MLOGIN=1; SUB=cn-new-sub; ALF=9999999999; SCF=shared-scf"
            val hops =
                listOf(
                    Triple("https://m.weibo.cn/", oldCnCookie, listOf("SCF=shared-scf; Domain=.weibo.cn; Path=/; Secure")),
                    Triple("https://visitor.passport.weibo.cn/visitor/visitor?entry=sinawap", afterHomeCn, emptyList()),
                    Triple(
                        "https://passport.weibo.com/sso/signin?entry=wapsso",
                        "SUB=initial-com-sub",
                        listOf("SUB=com-sub; Domain=.weibo.com; Path=/; Secure"),
                    ),
                    Triple(
                        "https://login.sina.com.cn/sso/login.php?entry=weibo",
                        "ALC=sina-alc",
                        listOf("SSO=sina-sso; Domain=.sina.com.cn; Path=/; Secure"),
                    ),
                    Triple("https://weibo.com/sso/crossdomain?ticket=one", "SUB=com-sub", emptyList()),
                    Triple(
                        "https://passport.weibo.cn/sso/complete?ticket=two",
                        afterHomeCn,
                        listOf("SUB=cn-new-sub; Domain=.weibo.cn; Path=/; Secure"),
                    ),
                )
            val credentialFlow =
                MutableStateFlow(
                    VVoCredential(
                        chocolate = oldCnCookie,
                        cookies = initialCookies,
                    ),
                )
            var hopIndex = 0
            val service =
                VVOService(
                    credentialFlow = credentialFlow,
                    refreshCookieWhenStale = true,
                    onCredentialRefreshed = { credentialFlow.value = it },
                    httpClientFactory =
                        mockHttpClientFactory { request ->
                            if (request.url.encodedPath == "/api/config") {
                                assertEquals(refreshedCnCookie, request.headers[HttpHeaders.Cookie])
                                return@mockHttpClientFactory configResponse(login = true)
                            }
                            val (url, cookie, setCookies) = hops[hopIndex++]
                            assertEquals(url, request.url.toString())
                            assertEquals(cookie, request.headers[HttpHeaders.Cookie])
                            if (hopIndex < hops.size) {
                                redirectResponse(hops[hopIndex].first, *setCookies.toTypedArray())
                            } else {
                                setCookieResponse(*setCookies.toTypedArray())
                            }
                        },
                )

            assertEquals(true, service.config().data?.login)
            assertEquals(hops.size, hopIndex)
            assertEquals(refreshedCnCookie, credentialFlow.value.chocolate)
            val jar = VVoCookieJar.fromCredential(credentialFlow.value)
            assertEquals("SUB=com-sub", jar.cookieHeaderFor(io.ktor.http.Url("https://passport.weibo.com/")))
            assertEquals("ALC=sina-alc; SSO=sina-sso", jar.cookieHeaderFor(io.ktor.http.Url("https://login.sina.com.cn/")))
        }

    @Test
    fun refreshStopsOnceTheAlfSessionWindowHasClosed() =
        runTest {
            val expiredCookie = "MLOGIN=1; SUB=old-sub; ALF=1000"
            val credentialFlow = MutableStateFlow(VVoCredential(chocolate = expiredCookie))
            var refreshHops = 0

            val service =
                VVOService(
                    credentialFlow = credentialFlow,
                    refreshCookieWhenStale = false,
                    onCredentialRefreshed = { credentialFlow.value = it },
                    httpClientFactory =
                        mockHttpClientFactory { request ->
                            when (request.url.encodedPath) {
                                "/" -> {
                                    refreshHops += 1
                                    setCookieResponse("SUB=new-sub; Path=/; Domain=.weibo.cn; HttpOnly")
                                }

                                "/api/config" -> {
                                    configResponse(login = false)
                                }

                                else -> {
                                    error("Unexpected request: ${request.url}")
                                }
                            }
                        },
                )

            val response = service.config()

            assertEquals(false, response.data?.login)
            assertEquals(0, refreshHops)
            assertEquals(expiredCookie, credentialFlow.value.chocolate)
        }

    @Test
    fun configReturnsLoginFalseAfterRetryLimitIsExhausted() =
        runTest {
            val oldCookie = "MLOGIN=1; SUB=old-sub"
            val credentialFlow = MutableStateFlow(VVoCredential(chocolate = oldCookie))
            var refreshCalls = 0
            val configCookies = mutableListOf<String?>()

            val service =
                VVOService(
                    credentialFlow = credentialFlow,
                    refreshCookieWhenStale = false,
                    onCredentialRefreshed = { credential ->
                        credentialFlow.value = credential
                    },
                    httpClientFactory =
                        mockHttpClientFactory { request ->
                            when (request.url.encodedPath) {
                                "/" -> {
                                    refreshCalls += 1
                                    setCookieResponse("SUB=retry-$refreshCalls; Path=/; Domain=.weibo.cn; HttpOnly")
                                }

                                "/api/config" -> {
                                    configCookies += request.headers[HttpHeaders.Cookie]
                                    configResponse(login = false)
                                }

                                else -> {
                                    error("Unexpected request path: ${request.url.encodedPath}")
                                }
                            }
                        },
                )

            val response = service.config()

            assertEquals(false, response.data?.login)
            assertEquals(5, refreshCalls)
            assertEquals(
                listOf<String?>(
                    oldCookie,
                    "MLOGIN=1; SUB=retry-1",
                    "MLOGIN=1; SUB=retry-2",
                    "MLOGIN=1; SUB=retry-3",
                    "MLOGIN=1; SUB=retry-4",
                    "MLOGIN=1; SUB=retry-5",
                ),
                configCookies,
            )
            assertEquals(oldCookie, credentialFlow.value.chocolate)
        }

    @Test
    fun configDelaysBeforeEachLoginRetry() =
        runTest {
            val oldCookie = "MLOGIN=1; SUB=old-sub"
            val credentialFlow = MutableStateFlow(VVoCredential(chocolate = oldCookie))
            var refreshCalls = 0
            val configRequestTimes = mutableListOf<Long>()

            val service =
                VVOService(
                    credentialFlow = credentialFlow,
                    refreshCookieWhenStale = false,
                    configLoginRetryDelay = 500.milliseconds,
                    onCredentialRefreshed = { credential ->
                        credentialFlow.value = credential
                    },
                    httpClientFactory =
                        mockHttpClientFactory { request ->
                            when (request.url.encodedPath) {
                                "/" -> {
                                    refreshCalls += 1
                                    setCookieResponse("SUB=retry-$refreshCalls; Path=/; Domain=.weibo.cn; HttpOnly")
                                }

                                "/api/config" -> {
                                    configRequestTimes += testScheduler.currentTime
                                    configResponse(login = false)
                                }

                                else -> {
                                    error("Unexpected request path: ${request.url.encodedPath}")
                                }
                            }
                        },
                )

            val response = service.config()

            assertEquals(false, response.data?.login)
            assertEquals(
                listOf(
                    0L,
                    500L,
                    1_000L,
                    1_500L,
                    2_000L,
                    2_500L,
                ),
                configRequestTimes,
            )
        }

    @Test
    fun concurrentLoggedOutConfigRequestsReuseSingleRefresh() =
        runTest {
            val oldCookie = "MLOGIN=1; SUB=old-sub"
            val refreshedCookie = "MLOGIN=1; SUB=new-sub"
            val credentialFlow = MutableStateFlow(VVoCredential(chocolate = oldCookie))
            val bothLoggedOutReached = CompletableDeferred<Unit>()
            val requestStateMutex = Mutex()
            var oldConfigCalls = 0
            var refreshCalls = 0
            val refreshCookies = mutableListOf<String?>()
            val configCookies = mutableListOf<String?>()

            val service =
                VVOService(
                    credentialFlow = credentialFlow,
                    refreshCookieWhenStale = false,
                    onCredentialRefreshed = { credential ->
                        credentialFlow.value = credential
                    },
                    httpClientFactory =
                        mockHttpClientFactory { request ->
                            when (request.url.encodedPath) {
                                "/" -> {
                                    val refreshCallIndex =
                                        requestStateMutex.withLock {
                                            refreshCookies += request.headers[HttpHeaders.Cookie]
                                            ++refreshCalls
                                        }
                                    when (refreshCallIndex) {
                                        1 -> {
                                            setCookieResponse("SUB=new-sub; Path=/; Domain=.weibo.cn; HttpOnly")
                                        }

                                        else -> {
                                            error("Unexpected refresh call count: $refreshCallIndex")
                                        }
                                    }
                                }

                                "/api/config" -> {
                                    val cookie = request.headers[HttpHeaders.Cookie]
                                    val oldConfigCallIndex =
                                        requestStateMutex.withLock {
                                            configCookies += cookie
                                            if (cookie == oldCookie) {
                                                ++oldConfigCalls
                                            } else {
                                                null
                                            }
                                        }
                                    when (cookie) {
                                        oldCookie -> {
                                            when (oldConfigCallIndex) {
                                                1 -> {
                                                    withTimeout(5_000) {
                                                        bothLoggedOutReached.await()
                                                    }
                                                }

                                                2 -> {
                                                    bothLoggedOutReached.complete(Unit)
                                                }

                                                else -> {
                                                    error("Unexpected old config call count: $oldConfigCallIndex")
                                                }
                                            }
                                            configResponse(login = false)
                                        }

                                        refreshedCookie -> {
                                            configResponse(login = true)
                                        }

                                        else -> {
                                            error("Unexpected config cookie: $cookie")
                                        }
                                    }
                                }

                                else -> {
                                    error("Unexpected request path: ${request.url.encodedPath}")
                                }
                            }
                        },
                )

            val results =
                supervisorScope {
                    val requestOne =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            service.config().data?.login
                        }
                    val requestTwo =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            service.config().data?.login
                        }
                    awaitAll(requestOne, requestTwo)
                }

            assertEquals(listOf(true, true), results)
            assertEquals(1, refreshCalls)
            assertEquals(listOf<String?>(oldCookie), refreshCookies)
            assertEquals(
                listOf<String?>(
                    oldCookie,
                    oldCookie,
                    refreshedCookie,
                    refreshedCookie,
                ),
                configCookies,
            )
            assertEquals(refreshedCookie, credentialFlow.value.chocolate)
        }

    @Test
    fun configDoesNotRefreshFreshCookieBeforeSuccessfulRequest() =
        runTest {
            val cookie = "MLOGIN=1; SUB=current-sub"
            val credentialFlow =
                MutableStateFlow(
                    VVoCredential(
                        chocolate = cookie,
                        lastCookieRefreshEpochMillis = Clock.System.now().toEpochMilliseconds(),
                    ),
                )
            var refreshCalls = 0
            val configCookies = mutableListOf<String?>()

            val service =
                VVOService(
                    credentialFlow = credentialFlow,
                    refreshCookieWhenStale = true,
                    onCredentialRefreshed = { credential ->
                        credentialFlow.value = credential
                    },
                    httpClientFactory =
                        mockHttpClientFactory { request ->
                            when (request.url.encodedPath) {
                                "/" -> {
                                    refreshCalls += 1
                                    setCookieResponse("SUB=new-sub; Path=/; Domain=.weibo.cn; HttpOnly")
                                }

                                "/api/config" -> {
                                    configCookies += request.headers[HttpHeaders.Cookie]
                                    configResponse(login = true)
                                }

                                else -> {
                                    error("Unexpected request path: ${request.url.encodedPath}")
                                }
                            }
                        },
                )

            val response = service.config()

            assertEquals(true, response.data?.login)
            assertEquals(0, refreshCalls)
            assertEquals(listOf<String?>(cookie), configCookies)
            assertEquals(cookie, credentialFlow.value.chocolate)
        }

    @Test
    fun vvoCredentialDefaultsLastCookieRefreshToNull() {
        val credential =
            Json.decodeFromString(
                VVoCredential.serializer(),
                """{"chocolate":"MLOGIN=1"}""",
            )

        assertEquals("MLOGIN=1", credential.chocolate)
        assertNull(credential.lastCookieRefreshEpochMillis)
    }

    @Test
    fun shouldRefreshVvoCookieWhenMissingOrOlderThanInterval() {
        val now = 1.days.inWholeMilliseconds
        val interval = 6.hours.inWholeMilliseconds

        assertTrue(shouldRefreshVvoCookie(lastRefreshEpochMillis = null, nowEpochMillis = now))
        assertFalse(shouldRefreshVvoCookie(lastRefreshEpochMillis = now - interval + 1, nowEpochMillis = now))
        assertTrue(shouldRefreshVvoCookie(lastRefreshEpochMillis = now - interval - 1, nowEpochMillis = now))
    }

    @Test
    fun isRefreshRedirectRequiresRedirectStatusAndLocation() {
        assertTrue(isRefreshRedirect(302, "https://passport.weibo.cn/sso/login"))
        assertTrue(isRefreshRedirect(301, "https://passport.weibo.cn/"))
        assertFalse(isRefreshRedirect(200, "https://passport.weibo.cn/"))
        assertFalse(isRefreshRedirect(302, null))
        assertFalse(isRefreshRedirect(302, ""))
    }

    @Test
    fun resolveRefreshRedirectUrlResolvesAbsoluteAndRelativeLocations() {
        assertEquals(
            "https://passport.weibo.cn/sso/login?entry=mweibo",
            resolveRefreshRedirectUrl(
                current = "https://m.weibo.cn/?entry=old",
                location = "https://passport.weibo.cn/sso/login?entry=mweibo",
            ),
        )
        assertEquals(
            "https://m.weibo.cn/sso/crossdomain?action=login",
            resolveRefreshRedirectUrl(
                current = "https://m.weibo.cn/home?action=old",
                location = "/sso/crossdomain?action=login",
            ),
        )
        assertEquals(
            "https://m.weibo.cn/home?action=login",
            resolveRefreshRedirectUrl(
                current = "https://m.weibo.cn/home?action=old",
                location = "?action=login",
            ),
        )
        assertEquals(
            "https://m.weibo.cn/",
            resolveRefreshRedirectUrl(
                current = "https://passport.weibo.cn/sso/login?ticket=old",
                location = "https://m.weibo.cn/",
            ),
        )
    }

    @Test
    fun resolveRefreshRedirectUrlAcceptsWeiboSitesAndSinaSso() {
        val locations =
            listOf(
                "https://weibo.cn/",
                "https://weibo.com/",
                "https://www.weibo.com/",
                "https://visitor.passport.weibo.cn/visitor/visitor",
                "https://visitor.passport.weibo.com/visitor/visitor",
                "https://passport.weibo.com/sso/signin",
                "https://login.sina.com.cn/sso/login.php",
                "https://PASSPORT.WEIBO.COM:443/sso/signin",
                "//visitor.passport.weibo.com/visitor/visitor",
            )
        for (location in locations) {
            assertNotNull(resolveRefreshRedirectUrl(current = "https://m.weibo.cn/", location = location), location)
        }
    }

    @Test
    fun vvoSessionExpiryReadsAlfTimestampAsEpochMillis() {
        assertEquals(1_794_367_522_000L, vvoSessionExpiryEpochMillis("MLOGIN=1; ALF=1794367522; SUB=value"))
        assertNull(vvoSessionExpiryEpochMillis("MLOGIN=1; SUB=value"))
        assertNull(vvoSessionExpiryEpochMillis("MLOGIN=1; ALF=0; SUB=value"))
    }

    @Test
    fun isVvoSessionExpiredComparesAgainstAlf() {
        val cookie = "MLOGIN=1; ALF=1000; SUB=value"
        assertTrue(isVvoSessionExpired(cookie, nowEpochMillis = 2_000_000L))
        assertFalse(isVvoSessionExpired(cookie, nowEpochMillis = 500_000L))
        // Without ALF there is nothing to say the session is over.
        assertFalse(isVvoSessionExpired("MLOGIN=1; SUB=value", nowEpochMillis = Long.MAX_VALUE))
    }

    @Test
    fun mergeVvoCookieHeaderKeepsExistingCookiesMissingFromSetCookie() {
        val merged =
            mergeVvoCookieHeader(
                currentCookieHeader = "MLOGIN=1; SUB=old-sub; XSRF-TOKEN=old-xsrf",
                setCookieHeaders =
                    listOf(
                        "SUB=new-sub; Path=/; Domain=.weibo.cn; HttpOnly",
                    ),
            )

        assertEquals("MLOGIN=1; SUB=new-sub; XSRF-TOKEN=old-xsrf", merged)
    }

    @Test
    fun mergeVvoCookieHeaderAddsNewCookies() {
        val merged =
            mergeVvoCookieHeader(
                currentCookieHeader = "MLOGIN=1",
                setCookieHeaders =
                    listOf(
                        "SUB=new-sub; Path=/; Domain=.weibo.cn; HttpOnly",
                    ),
            )

        assertEquals("MLOGIN=1; SUB=new-sub", merged)
    }

    @Test
    fun mergeVvoCookieHeaderSkipsMaxAgeExpiredCookies() {
        val merged =
            mergeVvoCookieHeader(
                currentCookieHeader = "MLOGIN=1; SUB=old-sub; XSRF-TOKEN=old-xsrf",
                setCookieHeaders =
                    listOf(
                        "SUB=; Max-Age=0; Path=/; Domain=.weibo.cn",
                    ),
            )

        assertNull(merged)
    }

    @Test
    fun mergeVvoCookieHeaderSkipsExpiresExpiredCookies() {
        val merged =
            mergeVvoCookieHeader(
                currentCookieHeader = "MLOGIN=1; SUB=old-sub; XSRF-TOKEN=old-xsrf",
                setCookieHeaders =
                    listOf(
                        "SUB=new-sub; Expires=Thu, 01-Jan-1970 00:00:00 GMT; Path=/; Domain=.weibo.cn",
                    ),
            )

        assertNull(merged)
    }

    @Test
    fun mergeVvoCookieHeaderKeepsExistingCookiesWhenExpiredCookieIsMixedWithFreshCookie() {
        val merged =
            mergeVvoCookieHeader(
                currentCookieHeader = "MLOGIN=1; SUB=old-sub; XSRF-TOKEN=old-xsrf",
                setCookieHeaders =
                    listOf(
                        "SUB=new-sub; Expires=Thu, 01-Jan-1970 00:00:00 GMT; Path=/; Domain=.weibo.cn",
                        "MLOGIN=1; Expires=Fri, 31 Dec 9999 23:59:59 GMT; Path=/; Domain=.weibo.cn",
                        "NEW_COOKIE=new-value; Expires=Fri, 31 Dec 9999 23:59:59 GMT; Path=/; Domain=.weibo.cn",
                    ),
            )

        assertEquals("MLOGIN=1; SUB=old-sub; XSRF-TOKEN=old-xsrf; NEW_COOKIE=new-value", merged)
    }

    @Test
    fun mergeVvoCookieHeaderReturnsNullWhenNothingChanges() {
        val merged =
            mergeVvoCookieHeader(
                currentCookieHeader = "MLOGIN=1; SUB=old-sub",
                setCookieHeaders =
                    listOf(
                        "SUB=old-sub; Path=/; Domain=.weibo.cn; HttpOnly",
                    ),
            )

        assertNull(merged)
    }

    @Test
    fun requestsCarryPersistedCredentialUserAgentOrDefaultMobileSafariUserAgent() =
        runTest {
            val customUa = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_2 like Mac OS X) CustomWebKit/605.1.15"
            val seenUas = mutableListOf<String?>()
            val credentialFlow =
                MutableStateFlow(
                    VVoCredential(
                        chocolate = "MLOGIN=1; SUB=current; XSRF-TOKEN=csrf",
                        userAgent = customUa,
                        lastCookieRefreshEpochMillis = Clock.System.now().toEpochMilliseconds(),
                    ),
                )
            val service =
                VVOService(
                    credentialFlow = credentialFlow,
                    refreshCookieWhenStale = true,
                    httpClientFactory =
                        mockHttpClientFactory { request ->
                            seenUas += request.headers[HttpHeaders.UserAgent]
                            configResponse(login = true)
                        },
                )
            service.config()
            val extHeaders = service.extensionAuthHeaders("https://weibo.com/ajax/statuses/buildComments")
            assertEquals(customUa, extHeaders[HttpHeaders.UserAgent])
            assertEquals(listOf<String?>(customUa), seenUas)

            credentialFlow.value = credentialFlow.value.copy(userAgent = null)
            service.config()
            assertEquals(DEFAULT_VVO_USER_AGENT, seenUas.last())
        }

    @Test
    fun stage1RestoresViaVisitorJsonpAndPassportSsoV2LoginWhenHomeLandsOn200VisitorPage() =
        runTest {
            val initialCookies =
                listOf(
                    VVoStoredCookie("MLOGIN", "1", "weibo.cn", hostOnly = false),
                    VVoStoredCookie("SUB", "expired-cn-sub", "weibo.cn", hostOnly = false),
                    VVoStoredCookie("SRF", "visitor-srf-token", "visitor.passport.weibo.cn", hostOnly = true),
                    VVoStoredCookie("ALC", "com-alc-token", "weibo.com", hostOnly = false),
                )
            val credentialFlow =
                MutableStateFlow(
                    VVoCredential(
                        chocolate = "MLOGIN=1; SUB=expired-cn-sub",
                        cookies = initialCookies,
                    ),
                )
            val visitedUrls = mutableListOf<String>()
            val service =
                VVOService(
                    credentialFlow = credentialFlow,
                    refreshCookieWhenStale = false,
                    onCredentialRefreshed = { credentialFlow.value = it },
                    httpClientFactory =
                        mockHttpClientFactory { request ->
                            if (request.url.encodedPath == "/api/config") {
                                val cookie = request.headers[HttpHeaders.Cookie].orEmpty()
                                return@mockHttpClientFactory configResponse(login = "SUB=restored-cn-sub" in cookie)
                            }
                            visitedUrls += "${request.url.host}${request.url.encodedPath}?${request.url.encodedQuery}"
                            when {
                                request.url.host == "m.weibo.cn" && request.url.encodedPath == "/" -> {
                                    redirectResponse(
                                        "https://visitor.passport.weibo.cn/visitor/visitor?entry=sinawap&a=enter&url=https%3A%2F%2Fm.weibo.cn%2F",
                                    )
                                }

                                request.url.host == "visitor.passport.weibo.cn" && request.url.parameters["a"] == "enter" -> {
                                    respond(
                                        content = "<html><title>Sina Visitor System</title></html>",
                                        status = HttpStatusCode.OK,
                                        headers = Headers.build { append(HttpHeaders.ContentType, "text/html") },
                                    )
                                }

                                request.url.host == "visitor.passport.weibo.cn" && request.url.parameters["a"] == "restore" -> {
                                    assertTrue(request.headers[HttpHeaders.Cookie].orEmpty().contains("SRF=visitor-srf-token"))
                                    respond(
                                        content = """window.restore_back && restore_back({"retcode":20000000,"msg":"succ","data":{"alt":"ALT-TOKEN-1"}});""",
                                        status = HttpStatusCode.OK,
                                        headers = Headers.build { append(HttpHeaders.ContentType, "application/javascript") },
                                    )
                                }

                                request.url.host == "passport.weibo.com" && request.url.encodedPath == "/sso/v2/login" -> {
                                    assertEquals("ALT-TOKEN-1", request.url.parameters["alt"])
                                    assertTrue(request.headers[HttpHeaders.Cookie].orEmpty().contains("ALC=com-alc-token"))
                                    jsonResponse(
                                        """{"retcode":20000000,"data":{"crossdomain":["https://evil.example/steal","https://passport.weibo.cn/sso/crossdomain?ticket=ST-OK"]}}""",
                                    )
                                }

                                request.url.host == "passport.weibo.cn" && request.url.encodedPath == "/sso/crossdomain" -> {
                                    setCookieResponse(
                                        "SUB=restored-cn-sub; Path=/; Domain=.weibo.cn; Secure; HttpOnly",
                                        "MLOGIN=1; Path=/; Domain=.weibo.cn; Secure",
                                    )
                                }

                                else -> error("Unexpected request: ${request.url}")
                            }
                        },
                )

            val response = service.config()
            assertEquals(true, response.data?.login)
            assertTrue(credentialFlow.value.chocolate.contains("SUB=restored-cn-sub"))
            assertFalse(visitedUrls.any { "evil.example" in it })
        }

    @Test
    fun visitorRestoreHandlesNullDataAndMalformedJsonpSafely() {
        val nullData = parseVisitorRestoreJsonp("""window.restore_back && restore_back({"retcode":20000000,"msg":"succ","data":null});""")
        assertNotNull(nullData)
        assertEquals(20_000_000L, nullData.retcode)
        assertNull(nullData.alt)

        assertNull(parseVisitorRestoreJsonp("<html>not jsonp</html>"))
        assertNull(parseVisitorRestoreJsonp("restore_back(not-json);"))

        val locations =
            extractTrustedSsoLocations(
                body = """{"retcode":20000000,"data":{"location":"https://passport.weibo.cn/sso/login?ticket=1","arrURL":["https://evil.example/collect","https://weibo.com/sso/crossdomain?ticket=2"]}}""",
                baseUrl = "https://passport.weibo.com/sso/v2/login",
            )
        assertEquals(
            listOf(
                "https://passport.weibo.cn/sso/login?ticket=1",
                "https://weibo.com/sso/crossdomain?ticket=2",
            ),
            locations,
        )
    }

    @Test
    fun renewalDoesNotPersistGuestCookieWhenConfigReportsLoggedOutOrWrongUid() =
        runTest {
            val original =
                VVoCredential(
                    chocolate = "MLOGIN=1; SUB=expired-sub",
                    cookies =
                        listOf(
                            VVoStoredCookie("MLOGIN", "1", "weibo.cn", hostOnly = false),
                            VVoStoredCookie("SUB", "expired-sub", "weibo.cn", hostOnly = false),
                            VVoStoredCookie("ALC", "precious-alc", "sina.com.cn", hostOnly = false),
                        ),
                )
            val credentialFlow = MutableStateFlow(original)
            val service =
                VVOService(
                    accountKey = MicroBlogKey("42", "m.weibo.cn"),
                    credentialFlow = credentialFlow,
                    refreshCookieWhenStale = false,
                    onCredentialRefreshed = { credentialFlow.value = it },
                    httpClientFactory =
                        mockHttpClientFactory { request ->
                            when (request.url.encodedPath) {
                                "/" -> setCookieResponse(
                                    "MLOGIN=0; Path=/; Domain=.weibo.cn",
                                    "SUB=guest-visitor-sub; Path=/; Domain=.weibo.cn",
                                )
                                "/api/config" -> jsonResponse("""{"ok":1,"data":{"login":true,"st":"st","uid":"999"}}""")
                                else -> error("Unexpected: ${request.url}")
                            }
                        },
                )

            val response = service.config()
            assertEquals("999", response.data?.uid)
            assertEquals(false, response.data?.login)
            assertNull(response.data?.st)
            assertEquals(original, credentialFlow.value)
        }

    @Test
    fun expiredWeiboCnAlfDoesNotBlockRenewalWhenCrossDomainAlcExists() =
        runTest {
            val credentialFlow =
                MutableStateFlow(
                    VVoCredential(
                        chocolate = "MLOGIN=1; SUB=old-sub; ALF=1000",
                        cookies =
                            listOf(
                                VVoStoredCookie("MLOGIN", "1", "weibo.cn", hostOnly = false),
                                VVoStoredCookie("SUB", "old-sub", "weibo.cn", hostOnly = false),
                                VVoStoredCookie("ALF", "1000", "weibo.cn", hostOnly = false),
                                VVoStoredCookie("ALC", "still-valid-alc", "sina.com.cn", hostOnly = false),
                            ),
                    ),
                )
            val service =
                VVOService(
                    credentialFlow = credentialFlow,
                    refreshCookieWhenStale = false,
                    onCredentialRefreshed = { credentialFlow.value = it },
                    httpClientFactory =
                        mockHttpClientFactory { request ->
                            when (request.url.encodedPath) {
                                "/" -> setCookieResponse("SUB=renewed-sub; Path=/; Domain=.weibo.cn; Secure; HttpOnly")
                                "/api/config" -> configResponse(login = request.headers[HttpHeaders.Cookie].orEmpty().contains("SUB=renewed-sub"))
                                else -> error("Unexpected: ${request.url}")
                            }
                        },
                )

            assertEquals(true, service.config().data?.login)
            assertTrue(credentialFlow.value.chocolate.contains("SUB=renewed-sub"))
        }

    @Test
    fun businessRequestsToWeiboComAndDmAttachmentsWorkForLegacyChocolateOnlyAccount() =
        runTest {
            val credentialFlow =
                MutableStateFlow(
                    VVoCredential(
                        chocolate = "MLOGIN=1; SUB=cn-sub; XSRF-TOKEN=cn-xsrf",
                        lastCookieRefreshEpochMillis = Clock.System.now().toEpochMilliseconds(),
                    ),
                )
            val service =
                VVOService(
                    credentialFlow = credentialFlow,
                    refreshCookieWhenStale = true,
                    httpClientFactory = mockHttpClientFactory { configResponse(login = true) },
                )

            val ext = service.extensionAuthHeaders("https://weibo.com/ajax/statuses/buildComments")
            assertEquals("MLOGIN=1; SUB=cn-sub; XSRF-TOKEN=cn-xsrf", ext[HttpHeaders.Cookie])
            assertEquals("cn-xsrf", ext["X-XSRF-TOKEN"])

            val dm = service.directMessageImageHeaders("https://attachment.api.weibo.com/2/mss/msget?fid=123")
            assertEquals("MLOGIN=1; SUB=cn-sub; XSRF-TOKEN=cn-xsrf", dm[HttpHeaders.Cookie])
        }

    @Test
    fun stage2SilentWebViewFallbackRecoversSessionWhenStage1StopsAtJsPage() =
        runTest {
            val account = MicroBlogKey("42", "m.weibo.cn")
            val initial =
                VVoCredential(
                    chocolate = "MLOGIN=1; SUB=expired-sub",
                    cookies =
                        listOf(
                            VVoStoredCookie("MLOGIN", "1", "weibo.cn", hostOnly = false),
                            VVoStoredCookie("SUB", "expired-sub", "weibo.cn", hostOnly = false),
                            VVoStoredCookie("ALC", "alc-1", "sina.com.cn", hostOnly = false),
                        ),
                    userAgent = "Mozilla/5.0 CustomSafari",
                )
            val credentialFlow = MutableStateFlow(initial)
            val capturedRequests = mutableListOf<SilentCookieRenewRequest>()
            val renewer =
                object : SilentWebCookieRenewer {
                    override suspend fun renew(request: SilentCookieRenewRequest): SilentCookieRenewResult {
                        capturedRequests += request
                        return SilentCookieRenewResult.Renewed(
                            cookies =
                                listOf(
                                    SilentSeedCookie("MLOGIN", "1", "weibo.cn", hostOnly = false, path = "/"),
                                    SilentSeedCookie("SUB", "webview-fresh-sub", "weibo.cn", hostOnly = false, path = "/", httpOnly = true),
                                    SilentSeedCookie("ALC", "alc-2", "sina.com.cn", hostOnly = false, path = "/"),
                                ),
                            userAgent = "Mozilla/5.0 CustomSafari",
                        )
                    }
                }

            val service =
                VVOService(
                    credentialFlow = credentialFlow,
                    accountKey = account,
                    silentCookieRenewer = renewer,
                    refreshCookieWhenStale = false,
                    onCredentialRefreshed = { credentialFlow.value = it },
                    httpClientFactory =
                        mockHttpClientFactory { request ->
                            when (request.url.encodedPath) {
                                "/" -> respond(
                                    content = "<html><title>Sina Visitor System</title></html>",
                                    status = HttpStatusCode.OK,
                                    headers = Headers.build { append(HttpHeaders.ContentType, "text/html") },
                                )
                                "/api/config" -> {
                                    val cookie = request.headers[HttpHeaders.Cookie].orEmpty()
                                    val loggedIn = "SUB=webview-fresh-sub" in cookie
                                    jsonResponse("""{"ok":1,"data":{"login":$loggedIn,"st":"st-val","uid":"42"}}""")
                                }
                                else -> error("Unexpected: ${request.url}")
                            }
                        },
                )

            val response = service.config()
            assertEquals(true, response.data?.login)
            assertEquals(1, capturedRequests.size)
            assertEquals("expired-sub", capturedRequests.single().previousSubCookie)
            assertEquals("Mozilla/5.0 CustomSafari", capturedRequests.single().userAgent)
            assertTrue(credentialFlow.value.chocolate.contains("SUB=webview-fresh-sub"))
            assertEquals(
                "ALC=alc-2",
                VVoCookieJar.fromCredential(credentialFlow.value).cookieHeaderFor(io.ktor.http.Url("https://login.sina.com.cn/")),
            )
        }

    @Test
    fun interactiveLoginRequiredMemoizesCredentialAndPreventsRepeatedWebViewStorms() =
        runTest {
            val account = MicroBlogKey("42", "m.weibo.cn")
            val expired = VVoCredential(chocolate = "MLOGIN=1; SUB=dead-sub")
            val credentialFlow = MutableStateFlow(expired)
            var renewCalls = 0
            val renewer =
                object : SilentWebCookieRenewer {
                    override suspend fun renew(request: SilentCookieRenewRequest): SilentCookieRenewResult {
                        renewCalls++
                        return if (request.previousSubCookie == "dead-sub") {
                            SilentCookieRenewResult.InteractiveLoginRequired
                        } else {
                            SilentCookieRenewResult.Renewed(
                                cookies =
                                    listOf(
                                        SilentSeedCookie("MLOGIN", "1", "weibo.cn", hostOnly = false, path = "/"),
                                        SilentSeedCookie("SUB", "relogged-renewed-sub", "weibo.cn", hostOnly = false, path = "/"),
                                    ),
                                userAgent = null,
                            )
                        }
                    }
                }

            val service =
                VVOService(
                    credentialFlow = credentialFlow,
                    accountKey = account,
                    silentCookieRenewer = renewer,
                    refreshCookieWhenStale = false,
                    configLoginRetryDelay = 0.milliseconds,
                    onCredentialRefreshed = { credentialFlow.value = it },
                    httpClientFactory =
                        mockHttpClientFactory { request ->
                            when (request.url.encodedPath) {
                                "/" -> setCookieResponse()
                                "/api/config" -> {
                                    val loggedIn = "SUB=relogged-renewed-sub" in request.headers[HttpHeaders.Cookie].orEmpty()
                                    jsonResponse("""{"ok":1,"data":{"login":$loggedIn,"st":"st","uid":"42"}}""")
                                }
                                else -> error("Unexpected: ${request.url}")
                            }
                        },
                )

            val parallel =
                supervisorScope {
                    listOf(
                        async { service.config().data?.login },
                        async { service.config().data?.login },
                    ).awaitAll()
                }
            assertEquals(listOf(false, false), parallel)
            assertEquals(false, service.config().data?.login)
            assertEquals(1, renewCalls)

            // User re-logs in with a new credential; Stage 2 becomes eligible again when that new SUB later expires.
            credentialFlow.value = VVoCredential(chocolate = "MLOGIN=1; SUB=new-login-sub")
            assertEquals(true, service.config().data?.login)
            assertEquals(2, renewCalls)
        }

    @Test
    fun skippedWebViewResultAppliesTwoMinuteCooldownBeforeRetrying() =
        runTest {
            var now = 1_000L
            var renewCalls = 0
            val credentialFlow = MutableStateFlow(VVoCredential(chocolate = "MLOGIN=1; SUB=stale"))
            val renewer =
                object : SilentWebCookieRenewer {
                    override suspend fun renew(request: SilentCookieRenewRequest): SilentCookieRenewResult {
                        renewCalls++
                        return SilentCookieRenewResult.Skipped("interactive-webview-active")
                    }
                }
            val service =
                VVOService(
                    credentialFlow = credentialFlow,
                    silentCookieRenewer = renewer,
                    nowEpochMillis = { now },
                    configLoginRetryDelay = 0.milliseconds,
                    httpClientFactory =
                        mockHttpClientFactory { request ->
                            when (request.url.encodedPath) {
                                "/" -> setCookieResponse()
                                "/api/config" -> configResponse(login = false)
                                else -> error("Unexpected: ${request.url}")
                            }
                        },
                )

            assertEquals(false, service.config().data?.login)
            assertEquals(1, renewCalls)

            now = 60_000L
            assertEquals(false, service.config().data?.login)
            assertEquals(1, renewCalls)

            now = 130_000L
            assertEquals(false, service.config().data?.login)
            assertEquals(2, renewCalls)
        }

    @Test
    fun getWebCommentsAndGetStatusLikersRecoverViaStage2OnMinus100BeforeThrowing() =
        runTest {
            val account = MicroBlogKey("42", "m.weibo.cn")
            val credentialFlow = MutableStateFlow(VVoCredential(chocolate = "MLOGIN=1; SUB=stale-sub"))
            val renewer =
                object : SilentWebCookieRenewer {
                    override suspend fun renew(request: SilentCookieRenewRequest): SilentCookieRenewResult =
                        SilentCookieRenewResult.Renewed(
                            cookies =
                                listOf(
                                    SilentSeedCookie("MLOGIN", "1", "weibo.cn", hostOnly = false, path = "/"),
                                    SilentSeedCookie("SUB", "fresh-sub", "weibo.cn", hostOnly = false, path = "/"),
                                ),
                            userAgent = null,
                        )
                }
            val service =
                VVOService(
                    credentialFlow = credentialFlow,
                    accountKey = account,
                    silentCookieRenewer = renewer,
                    configLoginRetryDelay = 0.milliseconds,
                    onCredentialRefreshed = { credentialFlow.value = it },
                    httpClientFactory =
                        mockHttpClientFactory { request ->
                            val hasFresh = "SUB=fresh-sub" in request.headers[HttpHeaders.Cookie].orEmpty()
                            when (request.url.encodedPath) {
                                "/" -> setCookieResponse()
                                "/api/config" -> jsonResponse("""{"ok":1,"data":{"login":$hasFresh,"st":"st","uid":"42"}}""")
                                "/ajax/statuses/buildComments" -> {
                                    if (!hasFresh) {
                                        jsonResponse("""{"ok":-100,"msg":"login required"}""")
                                    } else {
                                        jsonResponse("""{"ok":1,"data":[]}""")
                                    }
                                }
                                "/api/attitudes/show" -> {
                                    if (!hasFresh) {
                                        jsonResponse("""{"ok":-100,"msg":"login required"}""")
                                    } else {
                                        jsonResponse("""{"ok":1,"data":{"data":[]}}""")
                                    }
                                }
                                else -> error("Unexpected: ${request.url}")
                            }
                        },
                )

            assertEquals(emptyList(), service.getWebComments(id = "100", flow = 0, maxId = null, accountKey = account).data.orEmpty())
            credentialFlow.value = VVoCredential(chocolate = "MLOGIN=1; SUB=stale-sub-2")
            assertEquals(emptyList(), service.getStatusLikers(id = "100", page = 1, accountKey = account))
        }

    @Test
    fun containerIndexAndProactiveRefreshVerifyUidAndRecoverFromHttp401() =
        runTest {
            val account = MicroBlogKey("42", "m.weibo.cn")
            val initial = VVoCredential(chocolate = "MLOGIN=1; SUB=stale-sub", lastCookieRefreshEpochMillis = null)
            val credentialFlow = MutableStateFlow(initial)
            val renewer =
                object : SilentWebCookieRenewer {
                    override suspend fun renew(request: SilentCookieRenewRequest): SilentCookieRenewResult =
                        SilentCookieRenewResult.Renewed(
                            cookies =
                                listOf(
                                    SilentSeedCookie("MLOGIN", "1", "weibo.cn", hostOnly = false, path = "/"),
                                    SilentSeedCookie("SUB", "fresh-401-sub", "weibo.cn", hostOnly = false, path = "/"),
                                ),
                            userAgent = null,
                        )
                }
            val service =
                VVOService(
                    credentialFlow = credentialFlow,
                    accountKey = account,
                    silentCookieRenewer = renewer,
                    refreshCookieWhenStale = true,
                    configLoginRetryDelay = 0.milliseconds,
                    onCredentialRefreshed = { credentialFlow.value = it },
                    httpClientFactory =
                        mockHttpClientFactory { request ->
                            val cookie = request.headers[HttpHeaders.Cookie].orEmpty()
                            val hasFresh = "SUB=fresh-401-sub" in cookie
                            when (request.url.encodedPath) {
                                "/" -> setCookieResponse("SUB=unverified-guest-sub; Path=/; Domain=.weibo.cn; Secure")
                                "/api/config" -> {
                                    if (hasFresh) {
                                        jsonResponse("""{"ok":1,"data":{"login":true,"st":"st","uid":"42"}}""")
                                    } else {
                                        jsonResponse("""{"ok":1,"data":{"login":false,"st":"st"}}""")
                                    }
                                }
                                "/api/container/getIndex" -> {
                                    if (!hasFresh) {
                                        respond(
                                            content = """{"ok":-100}""",
                                            status = HttpStatusCode.Unauthorized,
                                            headers = Headers.build { append(HttpHeaders.ContentType, "application/json") },
                                        )
                                    } else {
                                        jsonResponse("""{"ok":1,"data":{"cards":[]}}""")
                                    }
                                }
                                else -> error("Unexpected: ${request.url}")
                            }
                        },
                )

            val container = service.getContainerIndexRecovering(accountKey = account, containerId = "231093_-_selffollowed", page = 1)
            assertEquals(1L, container.ok)
            assertTrue(credentialFlow.value.chocolate.contains("SUB=fresh-401-sub"))
            assertFalse(credentialFlow.value.chocolate.contains("unverified-guest-sub"))
        }

    @Test
    fun silentRenewUrlHelpersValidateTrustedHttpsOriginsAndInteractiveLoginPaths() {
        val allowedHosts = setOf("weibo.cn", "weibo.com", "login.sina.com.cn")
        val allowedSuffixes = setOf("weibo.cn", "weibo.com")
        assertTrue(isAllowedSilentRenewNavigationUrl("https://m.weibo.cn/", allowedHosts, allowedSuffixes))
        assertTrue(isAllowedSilentRenewNavigationUrl("https://passport.weibo.com/sso/signin?entry=wapsso", allowedHosts, allowedSuffixes))
        assertTrue(isAllowedSilentRenewNavigationUrl("https://login.sina.com.cn/sso/login.php", allowedHosts, allowedSuffixes))
        assertFalse(isAllowedSilentRenewNavigationUrl("http://m.weibo.cn/", allowedHosts, allowedSuffixes))
        assertFalse(isAllowedSilentRenewNavigationUrl("https://m.weibo.cn:8443/", allowedHosts, allowedSuffixes))
        assertFalse(isAllowedSilentRenewNavigationUrl("https://user@m.weibo.cn/", allowedHosts, allowedSuffixes))
        assertFalse(isAllowedSilentRenewNavigationUrl("https://news.sina.com.cn/", allowedHosts, allowedSuffixes))
        assertFalse(isAllowedSilentRenewNavigationUrl("https://evil.example/", allowedHosts, allowedSuffixes))

        assertTrue(isWeiboInteractiveLoginUrl("https://passport.weibo.com/sso/signin?entry=wapsso"))
        assertTrue(isWeiboInteractiveLoginUrl("https://passport.weibo.cn/signin/login?entry=mweibo"))
        assertTrue(isWeiboInteractiveLoginUrl("https://login.sina.com.cn/signup/signin.php?entry=sso"))
        assertTrue(isWeiboInteractiveLoginUrl("https://weibo.com/newlogin?tabtype=weibo"))
        assertFalse(isWeiboInteractiveLoginUrl("https://m.weibo.cn/"))
        assertFalse(isWeiboInteractiveLoginUrl("https://visitor.passport.weibo.cn/visitor/visitor?a=enter"))
    }

    @Test
    fun checkChocolatesRequiresSubAndMissingUidConfigSanitizesLoginFalseWhenAccountKeyExpected() =
        runTest {
            assertFalse(VVOService.checkChocolates("MLOGIN=1"))
            assertFalse(VVOService.checkChocolates("MLOGIN=0; SUB=abc"))
            assertTrue(VVOService.checkChocolates("MLOGIN=1; SUB=abc"))

            val credentialFlow = MutableStateFlow(VVoCredential(chocolate = "MLOGIN=1; SUB=sub-1"))
            val service =
                VVOService(
                    accountKey = MicroBlogKey("42", "m.weibo.cn"),
                    credentialFlow = credentialFlow,
                    configLoginRetryDelay = 0.milliseconds,
                    onCredentialRefreshed = { credentialFlow.value = it },
                    httpClientFactory =
                        mockHttpClientFactory { request ->
                            val rotated = "SUB=rotated-no-uid" in request.headers[HttpHeaders.Cookie].orEmpty()
                            when (request.url.encodedPath) {
                                "/" -> setCookieResponse("SUB=rotated-no-uid; Path=/; Domain=.weibo.cn; Secure")
                                "/api/config" -> {
                                    if (!rotated) {
                                        configResponse(login = false)
                                    } else {
                                        jsonResponse("""{"ok":1,"data":{"login":true,"st":"leaked-st"}}""")
                                    }
                                }
                                else -> error("Unexpected: ${request.url}")
                            }
                        },
                )
            val response = service.config()
            assertEquals(false, response.data?.login)
            assertNull(response.data?.st)
            assertEquals("MLOGIN=1; SUB=sub-1", credentialFlow.value.chocolate)

            val wrapped =
                parseVisitorRestoreJsonp(
                    """try { window.restore_back && restore_back({"retcode":20000000,"msg":"ok}","data":{"alt":"ALT-WRAPPED"}}); } catch (e) { console.log(e); }""",
                )
            assertNotNull(wrapped)
            assertEquals("ALT-WRAPPED", wrapped.alt)
        }

    @Test
    fun proactiveStaleRefreshDoesNotClearTerminalExpiredMemoizationAcrossSixHoursAndStage1StopsEarlyWhenUnchanged() =
        runTest {
            var now = 1_000_000L
            var stage1HomeHits = 0
            var renewCalls = 0
            val credentialFlow =
                MutableStateFlow(
                    VVoCredential(
                        chocolate = "MLOGIN=1; SUB=dead-sub",
                        lastCookieRefreshEpochMillis = now,
                    ),
                )
            val renewer =
                object : SilentWebCookieRenewer {
                    override suspend fun renew(request: SilentCookieRenewRequest): SilentCookieRenewResult {
                        renewCalls++
                        return SilentCookieRenewResult.InteractiveLoginRequired
                    }
                }
            val service =
                VVOService(
                    accountKey = MicroBlogKey("42", "m.weibo.cn"),
                    credentialFlow = credentialFlow,
                    silentCookieRenewer = renewer,
                    nowEpochMillis = { now },
                    refreshCookieWhenStale = true,
                    configLoginRetryDelay = 0.milliseconds,
                    onCredentialRefreshed = { credentialFlow.value = it },
                    httpClientFactory =
                        mockHttpClientFactory { request ->
                            when (request.url.encodedPath) {
                                "/" -> {
                                    stage1HomeHits++
                                    setCookieResponse()
                                }
                                "/api/config" -> configResponse(login = false)
                                else -> error("Unexpected: ${request.url}")
                            }
                        },
                )

            assertEquals(false, service.config().data?.login)
            assertEquals(1, stage1HomeHits)
            assertEquals(1, renewCalls)

            // Advance 7 hours (> 6h proactive refresh interval): neither proactive refresh nor Stage 2 should re-trigger InteractiveLoginRequired storm.
            now += 7 * 3_600_000L
            assertEquals(false, service.config().data?.login)
            assertEquals(1, renewCalls)
        }

    @Test
    fun concurrentGetWebCommentsSucceedsEvenWhenPeerRotatedCredentialWhileFirstAttemptWasInFlight() =
        runTest {
            val account = MicroBlogKey("42", "m.weibo.cn")
            val credentialFlow = MutableStateFlow(VVoCredential(chocolate = "MLOGIN=1; SUB=stale-sub"))
            val bothInitialCommentsSeen = kotlinx.coroutines.CompletableDeferred<Unit>()
            var initialStaleCommentHits = 0
            var renewCalls = 0

            val renewer =
                object : SilentWebCookieRenewer {
                    override suspend fun renew(request: SilentCookieRenewRequest): SilentCookieRenewResult {
                        renewCalls++
                        return SilentCookieRenewResult.Renewed(
                            cookies =
                                listOf(
                                    SilentSeedCookie("MLOGIN", "1", "weibo.cn", hostOnly = false, path = "/"),
                                    SilentSeedCookie("SUB", "fresh-sub", "weibo.cn", hostOnly = false, path = "/"),
                                ),
                            userAgent = null,
                        )
                    }
                }

            val service =
                VVOService(
                    credentialFlow = credentialFlow,
                    accountKey = account,
                    silentCookieRenewer = renewer,
                    configLoginRetryDelay = 0.milliseconds,
                    onCredentialRefreshed = { credentialFlow.value = it },
                    httpClientFactory =
                        mockHttpClientFactory { request ->
                            val hasFresh = "SUB=fresh-sub" in request.headers[HttpHeaders.Cookie].orEmpty()
                            when (request.url.encodedPath) {
                                "/" -> setCookieResponse()
                                "/api/config" -> jsonResponse("""{"ok":1,"data":{"login":$hasFresh,"st":"st","uid":"42"}}""")
                                "/ajax/statuses/buildComments" -> {
                                    if (!hasFresh) {
                                        initialStaleCommentHits++
                                        if (initialStaleCommentHits >= 2) {
                                            bothInitialCommentsSeen.complete(Unit)
                                        }
                                        bothInitialCommentsSeen.await()
                                        jsonResponse("""{"ok":-100,"msg":"login required"}""")
                                    } else {
                                        jsonResponse("""{"ok":1,"data":[]}""")
                                    }
                                }
                                else -> error("Unexpected: ${request.url}")
                            }
                        },
                )

            val results =
                supervisorScope {
                    listOf(
                        async { service.getWebComments(id = "100", flow = 0, maxId = null, accountKey = account).data.orEmpty() },
                        async { service.getWebComments(id = "101", flow = 0, maxId = null, accountKey = account).data.orEmpty() },
                    ).awaitAll()
                }
            assertEquals(listOf(emptyList(), emptyList()), results)
            assertEquals(1, renewCalls)
        }

    private fun mockHttpClientFactory(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): (HttpClientConfig<*>.() -> Unit) -> HttpClient =
        { config ->
            HttpClient(
                MockEngine { request ->
                    handler(request)
                },
            ) {
                config.invoke(this)
            }
        }

    private fun MockRequestHandleScope.configResponse(login: Boolean): HttpResponseData =
        jsonResponse(
            """
            {
              "ok": 1,
              "data": {
                "login": $login,
                "st": "st-value",
                "uid": "uid-value"
              }
            }
            """.trimIndent(),
        )

    private fun MockRequestHandleScope.jsonResponse(body: String): HttpResponseData =
        respond(
            content = body,
            status = HttpStatusCode.OK,
            headers =
                Headers.build {
                    append(HttpHeaders.ContentType, "application/json")
                },
        )

    private fun MockRequestHandleScope.setCookieResponse(vararg setCookieHeaders: String): HttpResponseData =
        respond(
            content = "",
            status = HttpStatusCode.OK,
            headers =
                Headers.build {
                    setCookieHeaders.forEach { setCookie ->
                        append(HttpHeaders.SetCookie, setCookie)
                    }
                },
        )

    private fun MockRequestHandleScope.redirectResponse(
        location: String,
        vararg setCookieHeaders: String,
    ): HttpResponseData =
        respond(
            content = "",
            status = HttpStatusCode.Found,
            headers =
                Headers.build {
                    append(HttpHeaders.Location, location)
                    setCookieHeaders.forEach { setCookie ->
                        append(HttpHeaders.SetCookie, setCookie)
                    }
                },
        )
}
