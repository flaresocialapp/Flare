package dev.dimension.flare.data.datasource.vvo

import dev.dimension.flare.common.JSON
import dev.dimension.flare.common.UploadMedia
import dev.dimension.flare.data.datasource.microblog.loader.DirectMessageDelta
import dev.dimension.flare.data.datasource.microblog.loader.DirectMessageLoader
import dev.dimension.flare.data.datasource.microblog.paging.PagingRequest
import dev.dimension.flare.data.datasource.microblog.paging.PagingResult
import dev.dimension.flare.data.network.appendMedia
import dev.dimension.flare.data.network.vvo.VVOService
import dev.dimension.flare.data.network.vvo.model.DirectMessageAttachment
import dev.dimension.flare.data.network.vvo.model.DirectMessageData
import dev.dimension.flare.data.network.vvo.model.DirectMessageEntry
import dev.dimension.flare.data.network.vvo.model.DirectMessageRoomData
import dev.dimension.flare.data.network.vvo.model.User
import dev.dimension.flare.data.network.vvo.model.VVODateSerializer
import dev.dimension.flare.data.network.vvo.model.VVOResponse
import dev.dimension.flare.data.platform.VVO_PLATFORM_ID
import dev.dimension.flare.data.repository.LoginExpiredException
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.model.vvoHost
import dev.dimension.flare.ui.model.UiDMItem
import dev.dimension.flare.ui.model.UiDMRoom
import dev.dimension.flare.ui.model.UiMedia
import dev.dimension.flare.ui.model.UiState
import dev.dimension.flare.ui.model.mapper.render
import dev.dimension.flare.ui.model.mapper.renderVVOText
import dev.dimension.flare.ui.render.toUi
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

internal class VVODirectMessageLoader(
    private val service: VVOService,
    private val accountKey: MicroBlogKey,
) : DirectMessageLoader {
    override val platformId: String = VVO_PLATFORM_ID
    override val persistRoomLastMessage: Boolean = false
    override val useLatestNonNullRemoteCursor: Boolean = true

    private val roomPrefix = "vvo-dm:${accountKey.id}:"
    private val sendMutex = Mutex()

    override suspend fun loadRooms(
        pageSize: Int,
        request: PagingRequest,
    ): PagingResult<UiDMRoom> =
        messageRequest {
            val page =
                when (request) {
                    PagingRequest.Refresh -> 1
                    is PagingRequest.Append -> request.nextKey.toInt()
                    is PagingRequest.Prepend -> return@messageRequest PagingResult()
                }
            val body = service.directMessageApi().getDirectMessageRooms(page).requireData()
            val rows = (body as? JsonArray) ?: ((body as? JsonObject)?.get("data") as? JsonArray)
            val rooms =
                JSON
                    .decodeFromJsonElement<List<DirectMessageRoomData>>(checkNotNull(rows))
                    .mapNotNull { it.toRoom() }
                    .distinctBy { it.key }
            check(rows.isEmpty() || rooms.isNotEmpty())
            PagingResult(data = rooms, nextKey = (page + 1).toString().takeIf { rows.isNotEmpty() })
        }

    override suspend fun loadMessages(
        roomKey: MicroBlogKey,
        pageSize: Int,
        request: PagingRequest,
    ): PagingResult<UiDMItem> =
        messageRequest {
            val maxId =
                when (request) {
                    PagingRequest.Refresh -> null
                    is PagingRequest.Append -> request.nextKey
                    is PagingRequest.Prepend -> return@messageRequest PagingResult()
                }
            val boundary = maxId?.toULong()
            val count = pageSize.coerceIn(2, CHAT_PAGE_SIZE)
            val chat = history(roomKey.peerUid(), count, maxId = maxId)
            val entries = chat.entries()
            val messages = chat.items(roomKey).filter { boundary == null || it.remoteCursor!!.toULong() < boundary }
            val oldest = entries.oldestId()
            check(boundary == null || oldest == null || oldest <= boundary)
            PagingResult(
                data = messages,
                nextKey = oldest?.toString()?.takeIf { entries.size >= count && (boundary == null || oldest < boundary) },
            )
        }

    override suspend fun fetchNewMessages(
        roomKey: MicroBlogKey,
        cursor: String?,
    ): DirectMessageDelta =
        messageRequest {
            val peerUid = roomKey.peerUid()
            val boundary = cursor?.toULong()
            val updates = linkedMapOf<MicroBlogKey, UiDMItem>()
            var maxId: ULong? = null
            repeat(5) {
                val chat = history(peerUid, CHAT_PAGE_SIZE, maxId = maxId?.toString(), sinceId = cursor)
                val entries = chat.entries()
                for (item in chat.items(roomKey)) {
                    if (boundary == null || item.remoteCursor!!.toULong() > boundary) {
                        updates[item.key] = item
                    }
                }
                val oldest = entries.oldestId()
                if (boundary == null || entries.size < CHAT_PAGE_SIZE || oldest == null || oldest <= boundary) {
                    return@messageRequest DirectMessageDelta(messages = updates.values.toList())
                }
                check(maxId == null || oldest < maxId)
                maxId = oldest
            }
            // shortcut: Keep the previous cursor if the gap exceeds five pages; refresh the conversation to reload history.
            error("Weibo direct message sync did not finish")
        }

    override suspend fun sendMessage(
        roomKey: MicroBlogKey,
        message: String,
    ): UiDMItem =
        sendMutex.withLock {
            submitMessage(roomKey, message)
        }

    override suspend fun sendImage(
        roomKey: MicroBlogKey,
        image: UploadMedia,
    ): UiDMItem = sendMutex.withLock { submitMessage(roomKey, "分享图片", image) }

    private suspend fun submitMessage(
        roomKey: MicroBlogKey,
        message: String,
        image: UploadMedia? = null,
    ): UiDMItem =
        messageRequest {
            val peerUid = roomKey.peerUid()
            require(message.isNotBlank())
            val st = sessionToken()
            val fileId = image?.let { uploadImage(peerUid, it, st) }
            val baseline = history(peerUid, count = 1).entries()
            val before = baseline.mapNotNull { it.messageId?.toULong() }.maxOrNull()
            val response =
                try {
                    service.directMessageApi().postDirectMessage(
                        uid = peerUid,
                        content = message,
                        st = st,
                        fileId = fileId,
                        mediaType = if (fileId == null) null else 1,
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: ClientRequestException) {
                    if (error.response.status != HttpStatusCode.RequestTimeout) throw error
                    null
                } catch (_: Exception) {
                    null
                }
            response?.takeIf { it.ok != null }?.requireSuccess()
            val sent =
                response
                    ?.data
                    ?.let {
                        runCatching { JSON.decodeFromJsonElement<DirectMessageData>(it) }.getOrNull()
                    }?.confirmedMessage(roomKey, peerUid, message, before, fileId)
            val confirmed =
                sent ?: checkNotNull(
                    history(peerUid, CHAT_PAGE_SIZE, sinceId = before?.toString())
                        .confirmedMessage(roomKey, peerUid, message, before, fileId),
                ) { "Weibo did not confirm the sent message" }
            // An outbound receipt can be newer than an incoming message that has not been fetched yet.
            confirmed.copy(remoteCursor = null)
        }

    private suspend fun uploadImage(
        peerUid: String,
        image: UploadMedia,
        st: String,
    ): String =
        coroutineScope {
            image.validate("Weibo")
            require(image.mimeType.startsWith("image/"))
            val data =
                service
                    .directMessageApi()
                    .uploadDirectMessageImage(
                        body =
                            MultiPartFormDataContent(
                                formData {
                                    append("tuid", peerUid)
                                    append("st", st)
                                    appendMedia("file", image, this@coroutineScope)
                                },
                            ),
                        referer = "https://$vvoHost/message/chat?uid=$peerUid&name=msgbox",
                        xsrfToken = st,
                    ).requireData() as? JsonObject
            val fileId = (data?.get("fids") as? JsonPrimitive)?.contentOrNull
            checkNotNull(fileId?.takeIf { (it.toULongOrNull() ?: 0u) > 0u })
        }

    override suspend fun loadBadgeCount(): Int =
        messageRequest {
            val result = service.remindUnread(time = Clock.System.now().epochSeconds, st = sessionToken())
            result
                .requireData()
                .dm
                ?.coerceIn(0, Int.MAX_VALUE.toLong())
                ?.toInt() ?: 0
        }

    override suspend fun fetchRoomInfo(roomKey: MicroBlogKey): UiDMRoom? = null

    override fun createRoom(userKey: MicroBlogKey): Flow<UiState<UiDMRoom>> = flowOf(UiState.Error(UnsupportedOperationException()))

    override suspend fun canSend(userKey: MicroBlogKey): Boolean = false

    override suspend fun deleteMessage(
        roomKey: MicroBlogKey,
        messageKey: MicroBlogKey,
    ): Unit = throw UnsupportedOperationException()

    override suspend fun leaveRoom(roomKey: MicroBlogKey): Unit = throw UnsupportedOperationException()

    private suspend fun sessionToken(): String {
        val config = service.config().data
        if (config?.login != true || (config.uid != null && config.uid != accountKey.id)) throw loginExpired()
        return checkNotNull(config.st?.takeIf(String::isNotBlank))
    }

    private suspend fun history(
        peerUid: String,
        count: Int,
        maxId: String? = null,
        sinceId: String? = null,
    ): DirectMessageData =
        JSON.decodeFromJsonElement(service.directMessageApi().getDirectMessages(peerUid, count, maxId, sinceId).requireData())

    private fun DirectMessageRoomData.toRoom(): UiDMRoom? {
        val uid =
            user?.id?.takeIf { it > 0 } ?: scheme?.let {
                ROOM_UID
                    .find(it)
                    ?.groupValues
                    ?.get(1)
                    ?.toLongOrNull()
            } ?: return null
        val profile = (user ?: User()).copy(id = uid, screenName = user?.screenName ?: uid.toString()).render(accountKey)
        val key = MicroBlogKey(roomPrefix + uid, accountKey.host)
        return UiDMRoom(
            key = key,
            users = persistentListOf(profile),
            lastMessage =
                UiDMItem(
                    key = key.copy(id = "${key.id}:preview"),
                    user = profile,
                    content = UiDMItem.Message.Text(renderVVOText(text.orEmpty(), accountKey)),
                    timestamp = createdAt.toMessageTime().toUi(),
                    isFromMe = false,
                    sendState = null,
                    showSender = false,
                ),
            unreadCount = unread?.coerceAtLeast(0) ?: 0,
        )
    }

    private fun DirectMessageData.items(roomKey: MicroBlogKey): List<UiDMItem> =
        entries()
            .mapNotNull { item(roomKey, it) }
            .distinctBy { it.key }

    private fun DirectMessageData.item(
        roomKey: MicroBlogKey,
        entry: DirectMessageEntry,
    ): UiDMItem? {
        val id = entry.messageId ?: return null
        val sender =
            entry.senderId
                ?.contentOrNull
                ?.toLongOrNull()
                ?.takeIf { it > 0 } ?: return null
        val user = users?.get(sender.toString()) ?: User()
        return UiDMItem(
            key = roomKey.copy(id = "${roomKey.id}:$id"),
            user = user.copy(id = sender, screenName = user.screenName ?: entry.senderName ?: sender.toString()).render(accountKey),
            content =
                entry
                    .imageAttachment()
                    ?.toImage(allowIdFallback = entry.mediaType?.intOrNull == 1 || entry.text == "分享图片")
                    ?.let { UiDMItem.Message.Media(it) }
                    ?: UiDMItem.Message.Text(renderVVOText(entry.text.orEmpty(), accountKey)),
            timestamp = entry.createdAt.toMessageTime().toUi(),
            isFromMe = sender.toString() == accountKey.id,
            sendState = null,
            showSender = false,
            remoteCursor = id,
        )
    }

    private fun DirectMessageData.confirmedMessage(
        roomKey: MicroBlogKey,
        peerUid: String,
        text: String,
        before: ULong?,
        fileId: String?,
    ): UiDMItem? =
        msgs
            .orEmpty()
            .filter { entry ->
                val id = entry.messageId?.toULong()
                // The endpoint is scoped to the peer even when the mobile response omits recipient_id.
                val recipient = entry.recipientId?.contentOrNull
                id != null && (before == null || id > before) &&
                    entry.senderId?.contentOrNull == accountKey.id &&
                    (recipient == null || recipient == peerUid) &&
                    (fileId == null || entry.imageAttachment()?.fileId == fileId)
            }.distinctBy { it.messageId }
            .mapNotNull { item(roomKey, it) }
            .singleOrNull {
                if (fileId == null) {
                    (it.content as? UiDMItem.Message.Text)?.text?.raw == text
                } else {
                    it.content is UiDMItem.Message.Media
                }
            }

    private fun MicroBlogKey.peerUid(): String {
        require(host == accountKey.host && id.startsWith(roomPrefix))
        return id.removePrefix(roomPrefix).also { require((it.toLongOrNull() ?: 0) > 0) }
    }

    private fun loginExpired() = LoginExpiredException(accountKey = accountKey, platformId = platformId)

    private fun VVOResponse<*>.requireSuccess() {
        if (ok == -100L || httpCode == 401L) throw loginExpired()
        check(ok == 1L)
    }

    private fun <T : Any> VVOResponse<T>.requireData(): T {
        requireSuccess()
        return checkNotNull(data)
    }

    // Response exceptions and decoding failures may include private message bodies.
    private suspend inline fun <T> messageRequest(request: () -> T): T {
        val attemptedCredential = service.activeCredential()
        return try {
            request()
        } catch (error: CancellationException) {
            throw error
        } catch (error: LoginExpiredException) {
            if (service.recoverSession(accountKey, attemptedCredential)) {
                return retryRecoveredMessageRequest(request)
            }
            throw error
        } catch (error: ClientRequestException) {
            if (error.response.status == HttpStatusCode.Unauthorized) {
                if (service.recoverSession(accountKey, attemptedCredential)) {
                    return retryRecoveredMessageRequest(request)
                }
                throw loginExpired()
            }
            error("Weibo direct message request failed (${error.response.status.value})")
        } catch (_: Exception) {
            error("Weibo direct message request failed")
        }
    }

    private inline fun <T> retryRecoveredMessageRequest(request: () -> T): T =
        try {
            request()
        } catch (error: CancellationException) {
            throw error
        } catch (error: LoginExpiredException) {
            throw error
        } catch (error: ClientRequestException) {
            if (error.response.status == HttpStatusCode.Unauthorized) throw loginExpired()
            error("Weibo direct message request failed (${error.response.status.value})")
        } catch (_: Exception) {
            error("Weibo direct message request failed")
        }
}

private const val CHAT_PAGE_SIZE = 20
private val ROOM_UID = Regex("[?&]uid=(\\d+)(?:&|$)")
private val AGE = Regex("(\\d{1,6})\\s*(秒|分钟|小时|天)前")
private val SHORT_MESSAGE_DATE = Regex("\\d{2}-\\d{2}")
private val MESSAGE_TIME_ZONE = TimeZone.of("Asia/Shanghai")
private val IMAGE_DOMAINS = listOf("sinaimg.cn", "weibo.cn", "weibo.com")

private val DirectMessageEntry.messageId: String?
    get() = (idstr ?: id?.contentOrNull)?.takeIf { (it.toULongOrNull() ?: 0u) > 0u }

private fun DirectMessageData.entries(): List<DirectMessageEntry> =
    checkNotNull(msgs).also { entries ->
        check(entries.isEmpty() || entries.any { it.messageId != null && (it.senderId?.contentOrNull?.toLongOrNull() ?: 0) > 0 })
    }

private fun List<DirectMessageEntry>.oldestId(): ULong? = mapNotNull { it.messageId?.toULong() }.minOrNull()

private fun DirectMessageEntry.imageAttachment(): DirectMessageAttachment? {
    val data = attachment as? JsonObject ?: return null
    if (mediaType != null && mediaType.intOrNull != 1) return null
    return runCatching { JSON.decodeFromJsonElement<DirectMessageAttachment>(data) }.getOrNull()
}

private val DirectMessageAttachment.fileId: String?
    get() = (fidstr?.contentOrNull ?: fid?.contentOrNull)?.takeIf { (it.toULongOrNull() ?: 0u) > 0u }

private fun DirectMessageAttachment.toImage(allowIdFallback: Boolean): UiMedia.Image? {
    val preview = thumbnail?.url?.weiboImageUrl()
    val original = originalImage?.url?.weiboImageUrl()
    val fid = fileId?.takeIf { allowIdFallback }
    val url =
        original ?: preview ?: fid?.let {
            "https://attachment.api.weibo.com/2/mss/msget?fid=$it&imageType=origin&source=2390714039"
        } ?: return null
    val size = if (original == null) thumbnail else originalImage
    return UiMedia.Image(
        url = url,
        previewUrl = preview ?: url,
        description = null,
        width = size?.width?.takeIf { it.isFinite() && it > 0 } ?: 0f,
        height = size?.height?.takeIf { it.isFinite() && it > 0 } ?: 0f,
        sensitive = false,
        customHeaders = persistentMapOf(HttpHeaders.Referrer to "https://$vvoHost/"),
    )
}

private fun String.weiboImageUrl(): String? {
    val normalized =
        when {
            startsWith("//") -> "https:$this"
            startsWith("http://") -> "https://${removePrefix("http://")}"
            startsWith("https://") -> this
            else -> return null
        }
    val url = runCatching { Url(normalized) }.getOrNull() ?: return null
    val host = url.host
    return url
        .takeIf {
            it.protocol == URLProtocol.HTTPS && it.port == 443 && it.user == null && it.password == null &&
                IMAGE_DOMAINS.any { domain -> host == domain || host.endsWith(".$domain") }
        }?.toString()
}

private fun JsonPrimitive?.toMessageTime(): Instant {
    val value = this?.contentOrNull?.trim() ?: return Instant.DISTANT_PAST
    value.toLongOrNull()?.takeIf { it > 0 }?.let {
        return if (it < 100_000_000_000L) Instant.fromEpochSeconds(it) else Instant.fromEpochMilliseconds(it)
    }
    val absolute = JSON.decodeFromJsonElement(VVODateSerializer, JsonPrimitive(value))
    if (absolute != Instant.DISTANT_PAST) return absolute
    if (value == "刚刚") return Clock.System.now()
    AGE.matchEntire(value)?.destructured?.let { (number, unit) ->
        val elapsed = number.toInt()
        return Clock.System.now() -
            when (unit) {
                "秒" -> elapsed.seconds
                "分钟" -> elapsed.minutes
                "小时" -> elapsed.hours
                else -> elapsed.days
            }
    }
    return runCatching { LocalDateTime.parse(value.replace(' ', 'T')).toInstant(MESSAGE_TIME_ZONE) }.getOrNull()
        ?: runCatching { value.toMessageDate()?.atStartOfDayIn(MESSAGE_TIME_ZONE) }.getOrNull()
        ?: Instant.DISTANT_PAST
}

private fun String.toMessageDate(): LocalDate? {
    runCatching { LocalDate.parse(this) }.getOrNull()?.let { return it }
    if (!SHORT_MESSAGE_DATE.matches(this)) return null

    val today =
        Clock.System
            .now()
            .toLocalDateTime(MESSAGE_TIME_ZONE)
            .date
    val thisYear = runCatching { LocalDate.parse("${today.year}-$this") }.getOrNull()
    return thisYear?.takeIf { it <= today }
        ?: runCatching { LocalDate.parse("${today.year - 1}-$this") }.getOrNull()
}
