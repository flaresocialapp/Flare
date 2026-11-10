package dev.dimension.flare.feature.plugin.host

import dev.dimension.flare.data.datasource.microblog.AccountHttpRequest
import dev.dimension.flare.feature.plugin.wire.HttpBodyV1
import dev.dimension.flare.feature.plugin.wire.HttpMultipartPartV1
import dev.dimension.flare.feature.plugin.wire.HttpRequestV1
import dev.dimension.flare.model.MicroBlogKey
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.core.readText
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KtorPluginHttpTransportTest {
    @Test
    fun authenticationRejectsBodiesThatExceedTheLimitAfterEncoding() =
        runBlocking {
            val bodies =
                listOf(
                    PluginTransportBodyV1.Form((1..4).associate { "field$it" to "你".repeat(64_000) }),
                    PluginTransportBodyV1.Multipart(
                        (1..9).map { PluginTransportMultipartPartV1.Text("field$it", "x".repeat(256_000), null) },
                    ),
                )
            for (body in bodies) assertFailsWith<IllegalArgumentException> { body.materializeForAuthentication() }
        }

    @Test
    fun authenticatedBodiesReachTheEngineWithoutReencoding() =
        runBlocking {
            for (body in listOf(
                HttpBodyV1.Text("你好", "text/plain"),
                HttpBodyV1.Form(mapOf("value" to "a b+你好")),
                HttpBodyV1.Multipart(listOf(HttpMultipartPartV1.Text("value", "你好"))),
            )) {
                var signed: AccountHttpRequest? = null
                val access =
                    object : PluginAccountHttpAccess {
                        override val origins = setOf("https://instance.example")

                        override suspend fun ensureActive() = Unit

                        override suspend fun authenticate(request: AccountHttpRequest): Map<String, String> {
                            signed = request
                            return emptyMap()
                        }
                    }
                val context =
                    PluginInvocationContextV1.extension(
                        "org.example.extension",
                        "a".repeat(64),
                        "",
                        "Provider",
                        "grant",
                        MicroBlogKey("user", "instance.example"),
                        emptySet(),
                        "en",
                        access,
                    )
                val transport =
                    KtorPluginHttpTransport(
                        MockEngine { request ->
                            val content = request.body as OutgoingContent.ByteArrayContent
                            assertEquals(requireNotNull(signed).bodyBase64, content.bytes().toByteString().base64())
                            assertEquals(requireNotNull(signed).headers[HttpHeaders.ContentType], content.contentType.toString())
                            respond("ok")
                        },
                    )
                try {
                    val headers =
                        if (body is HttpBodyV1.Text) {
                            mapOf(
                                HttpHeaders.ContentType to "application/custom;charset=utf-8",
                            )
                        } else {
                            emptyMap()
                        }
                    PluginHttpHost(transport).execute(
                        HttpRequestV1(
                            method = "POST",
                            url = "https://instance.example/signed",
                            headers = headers,
                            body = body,
                        ),
                        context,
                        30_000,
                        2,
                        access,
                    )
                    if (body is HttpBodyV1.Text) {
                        assertEquals(
                            "application/custom; charset=utf-8",
                            requireNotNull(signed).headers[HttpHeaders.ContentType],
                        )
                    }
                } finally {
                    transport.close()
                }
            }
        }

    @Test
    fun rejectsDeclaredOversizedResponseBeforeReadingIt() =
        runBlocking {
            val engine =
                MockEngine {
                    respond(
                        content = "ignored",
                        headers = headersOf(HttpHeaders.ContentLength, (MAX_RESPONSE_BYTES + 1).toString()),
                    )
                }
            val transport = KtorPluginHttpTransport(engine)
            try {
                assertFailsWith<IllegalArgumentException> {
                    transport.execute(
                        PluginTransportRequestV1(
                            method = "GET",
                            url = "https://instance.example/large",
                            headers = emptyMap(),
                            body = null,
                            timeoutMillis = 30_000,
                        ),
                    )
                }
                Unit
            } finally {
                transport.close()
            }
        }

    @Test
    fun rejectsOversizedResponseWithoutContentLengthWhileStreaming() =
        runBlocking {
            val engine = MockEngine { respond(content = ByteArray(MAX_RESPONSE_BYTES + 1), headers = headersOf()) }
            val transport = KtorPluginHttpTransport(engine)
            try {
                assertFailsWith<IllegalArgumentException> {
                    transport.execute(
                        PluginTransportRequestV1(
                            method = "GET",
                            url = "https://instance.example/chunked-large",
                            headers = emptyMap(),
                            body = null,
                            timeoutMillis = 30_000,
                        ),
                    )
                }
                Unit
            } finally {
                transport.close()
            }
        }

    @Test
    fun streamsMultipartAssetIntoRequestBody() =
        runBlocking {
            val asset = BufferAsset("streamed-image".encodeToByteArray())
            var captured = ""
            val engine =
                MockEngine { request ->
                    val content = request.body as OutgoingContent.WriteChannelContent
                    captured =
                        coroutineScope {
                            val channel = ByteChannel(autoFlush = true)
                            val writer =
                                async {
                                    content.writeTo(channel)
                                    channel.close()
                                }
                            val value = channel.readBuffer().readText()
                            writer.await()
                            value
                        }
                    respond("ok")
                }
            val transport = KtorPluginHttpTransport(engine)
            try {
                val response =
                    transport.execute(
                        PluginTransportRequestV1(
                            method = "POST",
                            url = "https://instance.example/upload",
                            headers = emptyMap(),
                            body =
                                PluginTransportBodyV1.Multipart(
                                    listOf(
                                        PluginTransportMultipartPartV1.Text("description", "alt", null),
                                        PluginTransportMultipartPartV1.Asset("file", asset, "image.png", "image/png"),
                                    ),
                                ),
                            timeoutMillis = 30_000,
                        ),
                    )
                assertEquals("ok", response.body.decodeToString())
                assertEquals(1, asset.openCount)
                assertContains(captured, "name=\"description\"")
                assertContains(captured, "name=\"file\"; filename=\"image.png\"")
                assertContains(captured, "streamed-image")
            } finally {
                transport.close()
            }
        }
}
