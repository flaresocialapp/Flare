package dev.dimension.flare.feature.plugin.host

import dev.dimension.flare.feature.plugin.wire.HttpAuthorizationV1
import dev.dimension.flare.feature.plugin.wire.HttpRequestV1
import dev.dimension.flare.model.MicroBlogKey
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class PluginExtensionHttpTest {
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

                    override suspend fun headers(url: String) = mapOf("Cookie" to "private=value", "X-XSRF-TOKEN" to "native-token")
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
