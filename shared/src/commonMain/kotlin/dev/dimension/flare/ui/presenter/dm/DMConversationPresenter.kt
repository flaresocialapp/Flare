package dev.dimension.flare.ui.presenter.dm

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.dimension.flare.common.FileItem
import dev.dimension.flare.common.PagingState
import dev.dimension.flare.common.collectAsPagingItems
import dev.dimension.flare.common.collectAsState
import dev.dimension.flare.common.toPagingState
import dev.dimension.flare.data.datasource.microblog.capabilities
import dev.dimension.flare.data.repository.AccountRepository
import dev.dimension.flare.data.repository.accountServiceProvider
import dev.dimension.flare.di.koinInject
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.UiDMItem
import dev.dimension.flare.ui.model.UiProfile
import dev.dimension.flare.ui.model.UiState
import dev.dimension.flare.ui.model.flatMap
import dev.dimension.flare.ui.model.map
import dev.dimension.flare.ui.model.onSuccess
import dev.dimension.flare.ui.model.toUi
import dev.dimension.flare.ui.presenter.PresenterBase
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

public class DMConversationPresenter(
    private val accountType: AccountType,
    private val roomKey: MicroBlogKey,
) : PresenterBase<DMConversationState>() {
    private val accountRepository: AccountRepository by koinInject()

    @Composable
    override fun body(): DMConversationState {
        val serviceState = accountServiceProvider(accountType = accountType, repository = accountRepository)
        val directMessageState =
            remember(serviceState) {
                serviceState.map { requireNotNull(it.capabilities.directMessage) }
            }
        val scope = rememberCoroutineScope()
        val directMessage = (directMessageState as? UiState.Success)?.data
        var image by remember(directMessage, roomKey) { mutableStateOf<FileItem?>(null) }
        var imageSending by remember(directMessage, roomKey) { mutableStateOf(false) }
        var imageFailed by remember(directMessage, roomKey) { mutableStateOf(false) }
        val items =
            directMessageState
                .map { directMessage ->
                    remember(directMessage, roomKey) {
                        directMessage.directMessageConversation(roomKey, scope = scope)
                    }.collectAsPagingItems()
                }.toPagingState()
        val users =
            directMessageState
                .flatMap { directMessage ->
                    remember(directMessage, roomKey) {
                        directMessage.getDirectMessageConversationInfo(roomKey)
                    }.collectAsState().toUi()
                }.map {
                    it.users
                }
        directMessageState.onSuccess { directMessage ->
            LaunchedEffect(directMessage, roomKey) {
                while (true) {
                    delay(10.seconds)
                    runCatching {
                        directMessage.fetchNewDirectMessageForConversation(roomKey)
                    }
                }
            }
        }
        return object : DMConversationState {
            override val items = items

            override val users = users

            override val canLeave = directMessage?.supportsLeavingDirectMessage == true

            override val canSendImage = directMessage?.supportsSendingDirectMessageImage == true

            override val selectedImage = image

            override val isSendingImage = imageSending

            override val imageSendFailed = imageFailed

            override fun selectImage(file: FileItem?) {
                if (!canSendImage || imageSending) return
                image = file
                imageFailed = false
            }

            override fun sendImage() {
                val file = image ?: return
                if (!canSendImage || directMessage == null || imageSending) return
                imageSending = true
                imageFailed = false
                scope.launch {
                    try {
                        directMessage.sendDirectMessageImage(roomKey, file)
                        image = null
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        imageFailed = true
                    } finally {
                        imageSending = false
                    }
                }
            }

            override fun send(message: String) {
                directMessageState.onSuccess {
                    it.sendDirectMessage(roomKey, message)
                }
            }

            override fun retry(key: MicroBlogKey) {
                directMessageState.onSuccess {
                    it.retrySendDirectMessage(key)
                }
            }

            override fun leave() {
                directMessageState.onSuccess {
                    if (it.supportsLeavingDirectMessage) it.leaveDirectMessage(roomKey)
                }
            }
        }
    }
}

@Immutable
public interface DMConversationState {
    public val items: PagingState<UiDMItem>
    public val users: UiState<ImmutableList<UiProfile>>
    public val canLeave: Boolean
    public val canSendImage: Boolean
    public val selectedImage: FileItem?
    public val isSendingImage: Boolean
    public val imageSendFailed: Boolean

    public fun send(message: String)

    public fun selectImage(file: FileItem?)

    public fun sendImage()

    public fun retry(key: MicroBlogKey)

    public fun leave()
}
