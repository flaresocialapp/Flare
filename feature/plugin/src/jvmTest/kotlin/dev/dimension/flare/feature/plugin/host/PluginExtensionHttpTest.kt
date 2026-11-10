package dev.dimension.flare.feature.plugin.host

import dev.dimension.flare.data.datasource.microblog.AccountHttpRequest
import dev.dimension.flare.feature.plugin.wire.HttpAuthorizationV1
import dev.dimension.flare.feature.plugin.wire.HttpBodyV1
import dev.dimension.flare.feature.plugin.wire.HttpMultipartPartV1
import dev.dimension.flare.feature.plugin.wire.HttpRedirectV1
import dev.dimension.flare.feature.plugin.wire.HttpRequestV1
import dev.dimension.flare.model.MicroBlogKey
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class PluginExtensionHttpTest {
    @Test
    fun responseHeadersHideAuthenticationNamesFromEarlierRedirectHops() =
        runBlocking {
            val access =
                object : PluginAccountHttpAccess {
                    override val origins = setOf("https://provider.example")

                    override suspend fun ensureActive() = Unit

                    override suspend fun authenticate(request: AccountHttpRequest) =
                        mapOf((if (request.url.endsWith("/start")) "X-Initial-Token" else "X-Final-Token") to "private")
                }
            val context =
                PluginInvocationContextV1.extension(
                    "org.example.extension",
                    "a".repeat(64),
                    "",
                    "Provider",
                    "grant",
                    MicroBlogKey("user", "provider.example"),
                    emptySet(),
                    "en",
                    access,
                )
            val host =
                PluginHttpHost(
                    object : PluginHttpTransport {
                        override suspend fun execute(request: PluginTransportRequestV1): PluginTransportResponseV1 =
                            if (request.url.endsWith("/start")) {
                                PluginTransportResponseV1(302, mapOf("Location" to listOf("/final")), byteArrayOf())
                            } else {
                                PluginTransportResponseV1(
                                    200,
                                    mapOf("X-Initial-Token" to listOf("private"), "X-Final-Token" to listOf("private")),
                                    byteArrayOf(),
                                )
                            }
                    },
                )
            val response = host.execute(HttpRequestV1(url = "https://provider.example/start"), context, 30_000, 2, access)
            assertEquals(200, response.status)
            assertNull(response.headers["x-initial-token"])
            assertNull(response.headers["x-final-token"])
        }

    @Test
    fun redirectsAreAuthenticatedForEachUrlAndNeverCarryAuthenticationAcrossOrigins() =
        runBlocking {
            val signed = mutableListOf<String>()
            val sent = mutableListOf<PluginTransportRequestV1>()
            val access =
                object : PluginAccountHttpAccess {
                    override val origins = setOf("https://provider.example", "https://other.example")

                    override suspend fun ensureActive() = Unit

                    override suspend fun authenticate(request: AccountHttpRequest): Map<String, String> {
                        signed += request.url
                        return mapOf("X-Signature" to request.url)
                    }
                }
            val context =
                PluginInvocationContextV1.extension(
                    "org.example.extension",
                    "a".repeat(64),
                    "",
                    "Provider",
                    "grant",
                    MicroBlogKey("user", "provider.example"),
                    emptySet(),
                    "en",
                    access,
                )
            val host =
                PluginHttpHost(
                    object : PluginHttpTransport {
                        override suspend fun execute(request: PluginTransportRequestV1): PluginTransportResponseV1 {
                            sent += request
                            val location = if (request.url.endsWith("/start")) "/next" else "https://other.example/final"
                            return PluginTransportResponseV1(302, mapOf("Location" to listOf(location)), byteArrayOf())
                        }
                    },
                )
            assertFailsWith<IllegalArgumentException> {
                host.execute(HttpRequestV1(url = "https://provider.example/start"), context, 30_000, 2, access)
            }
            assertEquals(listOf("https://provider.example/start", "https://provider.example/next"), signed)
            assertEquals(signed, sent.map { it.headers["X-Signature"] })
            val response =
                host.execute(
                    HttpRequestV1(url = "https://provider.example/start", redirect = HttpRedirectV1.Manual),
                    context,
                    30_000,
                    2,
                    access,
                )
            assertEquals(302, response.status)
            assertEquals(3, sent.size)
        }

    @Test
    fun authenticationSignsTheExactEncodedBodyAndUsesTheFinalContentType() =
        runBlocking {
            for (body in listOf(
                HttpBodyV1.Text("你好", "text/plain; charset=utf-8"),
                HttpBodyV1.Form(mapOf("value" to "a b+你好")),
                HttpBodyV1.Multipart(listOf(HttpMultipartPartV1.Text("value", "你好"))),
            )) {
                var signed: AccountHttpRequest? = null
                var sent: PluginTransportRequestV1? = null
                val access =
                    object : PluginAccountHttpAccess {
                        override val origins = setOf("https://provider.example")

                        override suspend fun ensureActive() = Unit

                        override suspend fun authenticate(request: AccountHttpRequest): Map<String, String> {
                            signed = request
                            return mapOf("X-Provider-Secret" to "private")
                        }
                    }
                val context =
                    PluginInvocationContextV1.extension(
                        "org.example.extension",
                        "a".repeat(64),
                        "",
                        "Provider",
                        "grant",
                        MicroBlogKey("user", "provider.example"),
                        emptySet(),
                        "en",
                        access,
                    )
                val host =
                    PluginHttpHost(
                        object : PluginHttpTransport {
                            override suspend fun execute(request: PluginTransportRequestV1): PluginTransportResponseV1 {
                                sent = request
                                return PluginTransportResponseV1(
                                    200,
                                    mapOf("X-Provider-Secret" to listOf("private"), "X-Visible" to listOf("public")),
                                    "{}".encodeToByteArray(),
                                )
                            }
                        },
                    )
                val response =
                    host.execute(
                        HttpRequestV1(method = "POST", url = "https://provider.example/checkin", body = body),
                        context,
                        30_000,
                        2,
                        access,
                    )
                val bytes = requireNotNull(sent).body as PluginTransportBodyV1.Bytes
                assertEquals(bytes.value.toByteString().base64(), requireNotNull(signed).bodyBase64)
                assertEquals(bytes.contentType, requireNotNull(signed).headers["Content-Type"])
                assertEquals("POST", requireNotNull(signed).method)
                assertNull(response.headers["x-provider-secret"])
                assertEquals(listOf("public"), response.headers["x-visible"])
            }
        }

    @Test
    fun providerCannotOverrideRoutingOrInjectInvalidHeaders() =
        runBlocking {
            for (headers in listOf(
                mapOf("Host" to "attacker.example"),
                mapOf("Content-Length" to "1"),
                mapOf("Content-Type" to "text/other"),
                mapOf("Keep-Alive" to "timeout=600"),
                mapOf("Proxy-Authorization" to "secret"),
                mapOf("Sec-Fetch-Site" to "none"),
                mapOf("Authorization" to "bad\r\nInjected: value"),
                mapOf("Cookie" to "one", "cookie" to "two"),
            )) {
                var sends = 0
                val access =
                    object : PluginAccountHttpAccess {
                        override val origins = setOf("https://provider.example")

                        override suspend fun ensureActive() = Unit

                        override suspend fun authenticate(request: AccountHttpRequest) = headers
                    }
                val context =
                    PluginInvocationContextV1.extension(
                        "org.example.extension",
                        "a".repeat(64),
                        "",
                        "Provider",
                        "grant",
                        MicroBlogKey("user", "provider.example"),
                        emptySet(),
                        "en",
                        access,
                    )
                val host =
                    PluginHttpHost(
                        object : PluginHttpTransport {
                            override suspend fun execute(request: PluginTransportRequestV1): PluginTransportResponseV1 {
                                sends++
                                return PluginTransportResponseV1(200, emptyMap(), byteArrayOf())
                            }
                        },
                    )
                assertFailsWith<IllegalArgumentException> {
                    host.execute(HttpRequestV1(url = "https://provider.example/checkin"), context, 30_000, 2, access)
                }
                assertEquals(0, sends)
            }
        }

    @Test
    fun authenticationIsConfinedToTheDedicatedChannelAndSensitiveHeadersAreHidden() =
        runBlocking {
            val requests = mutableListOf<PluginTransportRequestV1>()
            val transport =
                object : PluginHttpTransport {
                    override suspend fun execute(request: PluginTransportRequestV1): PluginTransportResponseV1 {
                        requests += request
                        return PluginTransportResponseV1(
                            200,
                            mapOf("Set-Cookie" to listOf("private=value"), "Cookie" to listOf("private=value")),
                            "{}".encodeToByteArray(),
                        )
                    }
                }
            val access =
                object : PluginAccountHttpAccess {
                    override val origins = setOf("https://m.weibo.cn")

                    override suspend fun ensureActive() = Unit

                    override suspend fun authenticate(request: AccountHttpRequest) =
                        mapOf(
                            "Cookie" to "private=value",
                            "X-XSRF-TOKEN" to "native-token",
                        )
                }
            val context =
                PluginInvocationContextV1.extension(
                    "org.example.extension",
                    "a".repeat(64),
                    "",
                    "VVo",
                    "grant",
                    MicroBlogKey("123", "m.weibo.cn"),
                    setOf("https://other.example"),
                    "en",
                    access,
                )
            val host = PluginHttpHost(transport)
            val response =
                host.execute(
                    HttpRequestV1(url = "https://m.weibo.cn/path", headers = mapOf("x-xsrf-token" to "plugin-token")),
                    context,
                    30_000,
                    2,
                    access,
                )
            assertEquals("private=value", requests.single().headers["Cookie"])
            assertEquals("native-token", requests.single().headers["X-XSRF-TOKEN"])
            assertNull(response.headers["set-cookie"])
            assertNull(response.headers["cookie"])
            host.execute(HttpRequestV1(url = "https://other.example/path"), context, 30_000, 2)
            assertFalse(
                requests
                    .last()
                    .headers.keys
                    .any { it.equals("cookie", true) },
            )
            assertFailsWith<IllegalArgumentException> {
                host.execute(HttpRequestV1(url = "https://other.example/path"), context, 30_000, 2, access)
            }
            assertFailsWith<IllegalArgumentException> {
                host.execute(
                    HttpRequestV1(url = "https://m.weibo.cn/path", authorization = HttpAuthorizationV1.Bearer("override")),
                    context,
                    30_000,
                    2,
                    access,
                )
            }
            assertEquals(2, requests.size)
        }
}
