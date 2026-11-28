package dev.dimension.flare.data.datasource.vvo

import dev.dimension.flare.common.UploadMedia
import dev.dimension.flare.data.datasource.microblog.capabilities
import dev.dimension.flare.data.datasource.microblog.paging.PagingRequest
import dev.dimension.flare.data.model.tab.ShortcutSpec
import dev.dimension.flare.data.network.vvo.VVOService
import dev.dimension.flare.data.platform.VVoCredential
import dev.dimension.flare.data.repository.LoginExpiredException
import dev.dimension.flare.data.repository.SilentCookieRenewRequest
import dev.dimension.flare.data.repository.SilentCookieRenewResult
import dev.dimension.flare.data.repository.SilentSeedCookie
import dev.dimension.flare.data.repository.SilentWebCookieRenewer
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.UiDMItem
import dev.dimension.flare.ui.model.UiIcon
import dev.dimension.flare.ui.model.UiMedia
import dev.dimension.flare.ui.model.UiStrings
import dev.dimension.flare.ui.model.mapper.VvoRenderTestModule
import dev.dimension.flare.ui.route.DeeplinkRoute
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.readByteArray
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.plugin.module.dsl.modules
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class VVODirectMessageTest {
    private val account = MicroBlogKey("42", "m.weibo.cn")
    private val room = MicroBlogKey("vvo-dm:42:77", account.host)

    @BeforeTest
    fun setup() {
        startKoin { modules(VvoRenderTestModule::class) }
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun weiboAccountExposesAnInboxShortcut() {
        val source = VVODataSource(account, flowOf(VVoCredential("MLOGIN=1; SUB=test")))

        val directMessage = assertNotNull(source.capabilities.directMessage)
        assertFalse(directMessage.supportsLeavingDirectMessage)
        assertTrue(directMessage.supportsSendingDirectMessageImage)

        val shortcuts = assertNotNull(source.capabilities.tabCatalog?.configuration).shortcuts
        assertEquals(
            ShortcutSpec(
                title = UiStrings.DirectMessage,
                icon = UiIcon.Messages,
                target = ShortcutSpec.Target.Route(DeeplinkRoute.AllDirectMessages(account)),
            ),
            shortcuts.singleOrNull { it.title == UiStrings.DirectMessage },
        )
    }

    @Test
    fun roomsLinkToTheSamePeerWithoutSharingCacheKeysAcrossAccounts() =
        runTest {
            val service =
                service { request ->
                    assertEquals("/message/msglist", request.url.encodedPath)
                    assertEquals("1", request.url.parameters["page"])
                    """{"ok":1,"data":[{"user":{"id":77,"screen_name":"Friend"},"created_at":1700000000,"unread":2,"text":"Hi"}]}"""
                }
            val first = VVODirectMessageLoader(service, account).loadRooms(20, PagingRequest.Refresh)
            val other = VVODirectMessageLoader(service, account.copy(id = "43")).loadRooms(20, PagingRequest.Refresh)
            val summary = first.data.single()
            assertEquals(room, summary.key)
            assertEquals(MicroBlogKey("77", account.host), summary.users.single().key)
            assertEquals(2L, summary.unreadCount)
            assertEquals("Hi", summary.lastMessageText)
            assertEquals("2", first.nextKey)
            assertNotEquals(summary.key, other.data.single().key)
            assertFailsWith<IllegalStateException> {
                VVODirectMessageLoader(service, account.copy(id = "43")).loadMessages(room, 20, PagingRequest.Refresh)
            }
        }

    @Test
    fun roomsKeepEachPeersAvatar() =
        runTest {
            val service =
                service {
                    """
                    {"ok":1,"data":[
                      {"user":{"id":77,"screen_name":"First","avatar_large":"https://example.com/avatars/77.jpg"}},
                      {"user":{
                        "id":78,"screen_name":"Second","avatar_hd":"",
                        "profile_image_url":"https://example.com/avatars/78.jpg",
                        "avatar_large":"https://example.com/avatars/78-large.jpg"
                      }}
                    ]}
                    """.trimIndent()
                }
            val rooms = VVODirectMessageLoader(service, account).loadRooms(20, PagingRequest.Refresh).data
            assertEquals(
                listOf(
                    MicroBlogKey("77", account.host) to "https://example.com/avatars/77.jpg",
                    MicroBlogKey("78", account.host) to "https://example.com/avatars/78.jpg",
                ),
                rooms.map { room ->
                    val peer = room.users.single()
                    peer.key to peer.avatar?.url
                },
            )
        }

    @Test
    fun roomsParseDateOnlyPreviewsAndKeepUnknownTimes() =
        runTest {
            val service =
                service {
                    """
                    {"ok":1,"data":[
                      {"user":{"id":77,"screen_name":"First"},"created_at":"2023-12-25","text":"Hi"},
                      {"user":{"id":78,"screen_name":"Second"},"created_at":"01-01","text":"Hi"},
                      {"user":{"id":79,"screen_name":"Third"},"text":"Hi"}
                    ]}
                    """.trimIndent()
                }
            val rooms = VVODirectMessageLoader(service, account).loadRooms(20, PagingRequest.Refresh).data
            val currentYear =
                Clock.System
                    .now()
                    .toLocalDateTime(TimeZone.of("Asia/Shanghai"))
                    .year
            assertEquals(
                listOf(
                    Instant.parse("2023-12-24T16:00:00Z"),
                    Instant.parse("${currentYear - 1}-12-31T16:00:00Z"),
                    Instant.DISTANT_PAST,
                ),
                rooms.map { assertNotNull(it.lastMessage).timestamp.value },
            )
        }

    @Test
    fun historySkipsAnInclusiveBoundaryAndKeepsTheRemoteIdAsAString() =
        runTest {
            val service =
                service { request ->
                    assertEquals("/api/chat/list", request.url.encodedPath)
                    assertEquals("77", request.url.parameters["uid"])
                    when (request.url.parameters["max_id"]) {
                        null -> history(message("9999999999999999998"), message("9999999999999999997"))
                        "9999999999999999997" -> history(message("9999999999999999997"), message("9999999999999999996"))
                        "9999999999999999996" -> history()
                        else -> error("Unexpected history cursor")
                    }
                }
            val loader = VVODirectMessageLoader(service, account)
            val first = loader.loadMessages(room, 2, PagingRequest.Refresh)
            assertEquals("9999999999999999997", first.nextKey)
            val second = loader.loadMessages(room, 2, PagingRequest.Append(assertNotNull(first.nextKey)))
            val older = second.data.single()
            assertEquals("9999999999999999996", older.remoteCursor)
            assertEquals("vvo-dm:42:77:9999999999999999996", older.key.id)
            assertFalse(older.isFromMe)
            assertNull(loader.loadMessages(room, 2, PagingRequest.Append(assertNotNull(second.nextKey))).nextKey)
        }

    @Test
    fun incrementalSyncFiltersOlderMessagesEvenIfTheServerIgnoresSinceId() =
        runTest {
            val service =
                service { request ->
                    assertEquals("/api/chat/list", request.url.encodedPath)
                    assertEquals("101", request.url.parameters["since_id"])
                    history(message("102"), message("101"), message("100"))
                }
            val delta = VVODirectMessageLoader(service, account).fetchNewMessages(room, "101")
            assertEquals("102", delta.messages.single().remoteCursor)
        }

    @Test
    fun imageAttachmentsUseThePrivateMessageImageBubble() =
        runTest {
            val service = service { history(imageMessage(id = "108", sender = 77, originalUrl = "http://wx1.sinaimg.cn/large/dm.jpg")) }
            val message = VVODirectMessageLoader(service, account).loadMessages(room, 20, PagingRequest.Refresh).data.single()
            val image = assertIs<UiMedia.Image>(assertIs<UiDMItem.Message.Media>(message.content).media)
            assertEquals("https://wx1.sinaimg.cn/large/dm.jpg", image.url)
            assertEquals("https://wx1.sinaimg.cn/thumb/dm.jpg", image.previewUrl)
            assertEquals(800f, image.width)
        }

    @Test
    fun imagePreviewFallsBackToTheThumbnailWhenTheOriginalIsOutsideWeibo() =
        runTest {
            val service = service { history(imageMessage(id = "108", sender = 77, originalUrl = "https://sinaimg.cn.example.org/dm.jpg")) }
            val message = VVODirectMessageLoader(service, account).loadMessages(room, 20, PagingRequest.Refresh).data.single()
            val image = assertIs<UiMedia.Image>(assertIs<UiDMItem.Message.Media>(message.content).media)
            assertEquals("https://wx1.sinaimg.cn/thumb/dm.jpg", image.url)
            assertEquals(image.url, image.previewUrl)
            assertEquals(160f, image.width)
        }

    @Test
    fun unexpectedAttachmentShapeKeepsTheTextMessage() =
        runTest {
            val service = service { history("""{"id":"108","sender_id":77,"recipient_id":42,"text":"Hi","attachment":[]}""") }
            val message = VVODirectMessageLoader(service, account).loadMessages(room, 20, PagingRequest.Refresh).data.single()
            assertEquals("Hi", assertIs<UiDMItem.Message.Text>(message.content).text.raw)
        }

    @Test
    fun attachmentIdWithoutAnInlineUrlUsesTheWeiboImageEndpoint() =
        runTest {
            val fixture = """{"id":"108","sender_id":77,"text":"分享图片","attachment":{"fidstr":"903"}}"""
            val loader = VVODirectMessageLoader(service { history(fixture) }, account)
            val message = loader.loadMessages(room, 20, PagingRequest.Refresh).data.single()
            val image = assertIs<UiMedia.Image>(assertIs<UiDMItem.Message.Media>(message.content).media)
            assertEquals("https://attachment.api.weibo.com/2/mss/msget?fid=903&imageType=origin&source=2390714039", image.url)
            assertEquals(mapOf(HttpHeaders.Referrer to "https://m.weibo.cn/"), image.customHeaders.orEmpty().toMap())
        }

    @Test
    fun imageCookieIsAvailableOnlyForTheWeiboAttachmentEndpoints() =
        runTest {
            val service = service { error("Should use the existing credential") }
            for (path in listOf("msget", "msget_thumbnail")) {
                val headers = service.directMessageImageHeaders("https://attachment.api.weibo.com/2/mss/$path?fid=903")
                assertEquals(mapOf(HttpHeaders.Cookie to "MLOGIN=1; SUB=test"), headers)
            }
            for (url in listOf(
                "https://attachment.api.weibo.com.example.test/2/mss/msget?fid=903",
                "https://attachment.api.weibo.com:8443/2/mss/msget?fid=903",
                "http://attachment.api.weibo.com/2/mss/msget?fid=903",
                "https://attachment.api.weibo.com/other",
            )) {
                assertFailsWith<IllegalArgumentException>(url) { service.directMessageImageHeaders(url) }
            }
        }

    @Test
    fun imageSendUploadsToThePeerAndConfirmsTheMatchingAttachmentFromHistory() =
        runTest {
            val requests = mutableListOf<String>()
            val matched = imageMessage("102").replace("\"recipient_id\":77,", "")
            val wrongRecipient = imageMessage("104").replace("\"recipient_id\":77,", "\"recipient_id\":99,")
            val loader = imageSendLoader(requests, readback = history(matched, imageMessage("103", fid = 904), wrongRecipient))

            val sent = loader.sendImage(room, UploadMedia.fromBytes("picture.png", pngHeader, "image/png"))

            assertTrue(sent.isFromMe)
            assertEquals("vvo-dm:42:77:102", sent.key.id)
            assertNull(sent.remoteCursor)
            assertIs<UiDMItem.Message.Media>(sent.content)
            assertEquals(listOf("upload", "baseline", "send", "readback"), requests)
        }

    @Test
    fun imageSendRejectsTextOnlyAcknowledgmentsAndUnrelatedAttachments() =
        runTest {
            val requests = mutableListOf<String>()
            val loader =
                imageSendLoader(
                    requests,
                    readback = history(message("102", sender = 42, text = "分享图片"), imageMessage("103", fid = 904)),
                )

            assertFailsWith<IllegalStateException> {
                loader.sendImage(room, UploadMedia.fromBytes("picture.png", pngHeader, "image/png"))
            }
            assertEquals(listOf("upload", "baseline", "send", "readback"), requests)
        }

    @Test
    fun invalidUploadIdDoesNotPostAnImageMessage() =
        runTest {
            val requests = mutableListOf<String>()
            val loader = imageSendLoader(requests, readback = history(), uploadReply = """{"ok":1,"data":{"fids":0}}""")

            assertFailsWith<IllegalStateException> {
                loader.sendImage(room, UploadMedia.fromBytes("picture.png", pngHeader, "image/png"))
            }
            assertEquals(listOf("upload"), requests)
        }

    @Test
    fun ambiguousSendRecoversTheNewMessageWithoutPostingTwice() =
        runTest {
            var posts = 0
            val service =
                service { request ->
                    when (request.url.encodedPath) {
                        "/api/config" -> {
                            """{"ok":1,"data":{"login":true,"uid":"42","st":"token"}}"""
                        }

                        "/api/chat/list" -> {
                            if (posts == 0) {
                                history(message("101", sender = 42, text = "你好"))
                            } else {
                                assertEquals("101", request.url.parameters["since_id"])
                                history(message("102", sender = 42, text = "你好"), message("101", sender = 42, text = "你好"))
                            }
                        }

                        "/api/chat/send" -> {
                            assertEquals(HttpMethod.Post, request.method)
                            val form =
                                parseQueryString(assertIs<OutgoingContent.ByteArrayContent>(request.body).bytes().decodeToString())
                            assertEquals("77", form["uid"])
                            assertEquals("你好", form["content"])
                            assertEquals("token", form["st"])
                            assertNull(form["fids"])
                            assertNull(form["media_type"])
                            posts++
                            """{"ok":1,"data":{}}"""
                        }

                        else -> {
                            error("Unexpected request")
                        }
                    }
                }
            val sent = VVODirectMessageLoader(service, account).sendMessage(room, "你好")
            assertEquals(1, posts)
            assertNull(sent.remoteCursor)
            assertEquals("vvo-dm:42:77:102", sent.key.id)
            assertNull(sent.sendState)
        }

    @Test
    fun totalBadgeUsesOnlyThePrivateMessageCount() =
        runTest {
            val service =
                service { request ->
                    when (request.url.encodedPath) {
                        "/api/config" -> """{"ok":1,"data":{"login":true,"uid":"42","st":"token"}}"""
                        "/api/remind/unread" -> """{"ok":1,"data":{"dm":3,"mention_status":1,"cmt":4}}"""
                        else -> error("Unexpected request")
                    }
                }
            assertEquals(3, VVODirectMessageLoader(service, account).loadBadgeCount())
        }

    @Test
    fun expiredInboxDoesNotLookLikeAnEmptyInbox() =
        runTest {
            val loader = VVODirectMessageLoader(service { """{"ok":-100}""" }, account)
            val error = assertFailsWith<LoginExpiredException> { loader.loadRooms(20, PagingRequest.Refresh) }
            assertEquals(account, error.accountKey)
        }

    @Test
    fun anUnrecognizedHistoryPageDoesNotClearCachedMessages() =
        runTest {
            val loader =
                VVODirectMessageLoader(
                    service { """{"ok":1,"data":{"msgs":[{"unexpected":"shape"}]}}""" },
                    account,
                )
            assertFailsWith<IllegalStateException> { loader.loadMessages(room, 20, PagingRequest.Refresh) }
        }

    private fun imageSendLoader(
        requests: MutableList<String>,
        readback: String,
        uploadReply: String = """{"ok":1,"data":{"fids":903}}""",
    ): VVODirectMessageLoader {
        val service =
            service { request ->
                when (request.url.encodedPath) {
                    "/api/config" -> {
                        """{"ok":1,"data":{"login":true,"uid":"42","st":"token"}}"""
                    }

                    "/api/chat/list" -> {
                        if (request.url.parameters["since_id"] == null) {
                            requests += "baseline"
                            history(message("101"))
                        } else {
                            requests += "readback"
                            assertEquals("101", request.url.parameters["since_id"])
                            readback
                        }
                    }

                    "/api/chat/send" -> {
                        assertEquals(HttpMethod.Post, request.method)
                        assertEquals("token", request.headers["X-Xsrf-Token"])
                        val body = request.body
                        if (body is MultiPartFormDataContent) {
                            requests += "upload"
                            assertEquals(
                                listOf("https://m.weibo.cn/message/chat?uid=77&name=msgbox"),
                                request.headers.getAll(HttpHeaders.Referrer),
                            )
                            val multipart = body.readBody().decodeToString()
                            val partName = Regex(""";\s*name="?([^";\r\n]+)""")
                            val fields =
                                multipart
                                    .split("--${body.boundary}")
                                    .filter { "\r\n\r\n" in it }
                                    .associate { part ->
                                        val name = partName.find(part.substringBefore("\r\n\r\n"))?.groupValues?.get(1)
                                        assertNotNull(name) to part.substringAfter("\r\n\r\n").removeSuffix("\r\n")
                                    }
                            assertEquals(mapOf("tuid" to "77", "st" to "token", "file" to pngHeader.decodeToString()), fields)
                            assertTrue(multipart.contains("filename=\"picture.png\""))
                            assertTrue(multipart.contains("image/png"))
                            uploadReply
                        } else {
                            requests += "send"
                            val form = parseQueryString(assertIs<OutgoingContent.ByteArrayContent>(body).bytes().decodeToString())
                            assertEquals("77", form["uid"])
                            assertEquals("token", form["st"])
                            assertEquals("分享图片", form["content"])
                            assertEquals("903", form["fids"])
                            assertEquals("1", form["media_type"])
                            history(message("102", sender = 42, text = "分享图片"))
                        }
                    }

                    else -> {
                        error("Unexpected request")
                    }
                }
            }
        return VVODirectMessageLoader(service, account)
    }

    @Test
    fun loadRoomsRecoversExpiredSessionOnceBeforeFailing() =
        runTest {
            val credentialFlow = kotlinx.coroutines.flow.MutableStateFlow(VVoCredential("MLOGIN=1; SUB=stale"))
            val renewer =
                object : SilentWebCookieRenewer {
                    override suspend fun renew(request: SilentCookieRenewRequest): SilentCookieRenewResult =
                        SilentCookieRenewResult.Renewed(
                            cookies =
                                listOf(
                                    SilentSeedCookie("MLOGIN", "1", "weibo.cn", hostOnly = false, path = "/"),
                                    SilentSeedCookie("SUB", "renewed", "weibo.cn", hostOnly = false, path = "/"),
                                ),
                            userAgent = null,
                        )
                }
            val dmService =
                VVOService(
                    credentialFlow = credentialFlow,
                    accountKey = account,
                    silentCookieRenewer = renewer,
                    onCredentialRefreshed = { credentialFlow.value = it },
                    httpClientFactory = { config ->
                        HttpClient(
                            MockEngine { request ->
                                val fresh = "SUB=renewed" in request.headers[HttpHeaders.Cookie].orEmpty()
                                val body =
                                    when (request.url.encodedPath) {
                                        "/" -> ""
                                        "/api/config" -> """{"ok":1,"data":{"login":$fresh,"st":"token","uid":"42"}}"""
                                        "/message/msglist" -> {
                                            if (!fresh) {
                                                """{"ok":-100}"""
                                            } else {
                                                """{"ok":1,"data":[{"user":{"id":77,"screen_name":"Friend"},"text":"Recovered","created_at":"刚刚"}]}"""
                                            }
                                        }
                                        else -> error("Unexpected path: ${request.url.encodedPath}")
                                    }
                                respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                            },
                        ) { config(this) }
                    },
                )

            val rooms = VVODirectMessageLoader(dmService, account).loadRooms(20, PagingRequest.Refresh).data
            assertEquals(1, rooms.size)
            assertEquals("vvo-dm:42:77", rooms.single().key.id)
        }

    private suspend fun MultiPartFormDataContent.readBody(): ByteArray =
        coroutineScope {
            val channel = ByteChannel(autoFlush = true)
            launch {
                try {
                    writeTo(channel)
                } finally {
                    channel.flushAndClose()
                }
            }
            channel.readBuffer().readByteArray()
        }

    private fun service(body: suspend (HttpRequestData) -> String) =
        VVOService(
            credentialFlow = flowOf(VVoCredential("MLOGIN=1; SUB=test")),
            httpClientFactory = { config ->
                HttpClient(
                    MockEngine { request ->
                        assertEquals("m.weibo.cn", request.url.host)
                        assertEquals("MLOGIN=1; SUB=test", request.headers[HttpHeaders.Cookie])
                        respond(body(request), headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) { config(this) }
            },
        )

    private fun history(vararg messages: String): String =
        """{"ok":1,"data":{"msgs":[${messages.joinToString(",")}],"users":{"77":{"id":77,"screen_name":"Friend"}}}}"""

    private fun message(
        id: String,
        sender: Int = 77,
        text: String = "Hi",
    ): String = """{"id":"$id","sender_id":$sender,"recipient_id":${if (sender == 77) 42 else 77},"created_at":1700000000,"text":"$text"}"""

    private fun imageMessage(
        id: String,
        sender: Int = 42,
        fid: Int = 903,
        originalUrl: String = "https://wx1.sinaimg.cn/large/dm.jpg",
    ): String =
        """{"id":"$id","sender_id":$sender,"recipient_id":${if (sender == 77) 42 else 77},"text":"分享图片","media_type":1,"attachment":{
            "fid":$fid,
            "original_image":{"url":"$originalUrl","width":800,"height":600},
            "thumbnail":{"url":"//wx1.sinaimg.cn/thumb/dm.jpg","width":160,"height":120}
        }}"""

    private val pngHeader = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 13, 10, 0x1A, 10)
}
