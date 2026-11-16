package dev.dimension.flare.ui.component.status

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Solid
import compose.icons.fontawesomeicons.solid.At
import compose.icons.fontawesomeicons.solid.Ellipsis
import compose.icons.fontawesomeicons.solid.Globe
import compose.icons.fontawesomeicons.solid.Heart
import compose.icons.fontawesomeicons.solid.Image
import compose.icons.fontawesomeicons.solid.Language
import compose.icons.fontawesomeicons.solid.Lock
import compose.icons.fontawesomeicons.solid.LockOpen
import compose.icons.fontawesomeicons.solid.Reply
import compose.icons.fontawesomeicons.solid.Robot
import compose.icons.fontawesomeicons.solid.TriangleExclamation
import compose.icons.fontawesomeicons.solid.Tv
import dev.dimension.flare.common.SystemUtils
import dev.dimension.flare.compose.ui.Res
import dev.dimension.flare.compose.ui.bookmark_add
import dev.dimension.flare.compose.ui.bookmark_remove
import dev.dimension.flare.compose.ui.channel_title
import dev.dimension.flare.compose.ui.comment
import dev.dimension.flare.compose.ui.delete
import dev.dimension.flare.compose.ui.favourite
import dev.dimension.flare.compose.ui.fx_share
import dev.dimension.flare.compose.ui.hide_media
import dev.dimension.flare.compose.ui.like
import dev.dimension.flare.compose.ui.mastodon_item_show_less
import dev.dimension.flare.compose.ui.mastodon_item_show_more
import dev.dimension.flare.compose.ui.mastodon_visibility_direct
import dev.dimension.flare.compose.ui.mastodon_visibility_private
import dev.dimension.flare.compose.ui.mastodon_visibility_public
import dev.dimension.flare.compose.ui.mastodon_visibility_unlisted
import dev.dimension.flare.compose.ui.more
import dev.dimension.flare.compose.ui.poll_expired
import dev.dimension.flare.compose.ui.poll_expired_at
import dev.dimension.flare.compose.ui.post_show_full_text
import dev.dimension.flare.compose.ui.profile_open_user
import dev.dimension.flare.compose.ui.quote
import dev.dimension.flare.compose.ui.reaction_add
import dev.dimension.flare.compose.ui.reaction_remove
import dev.dimension.flare.compose.ui.reply
import dev.dimension.flare.compose.ui.reply_to
import dev.dimension.flare.compose.ui.report
import dev.dimension.flare.compose.ui.retweet
import dev.dimension.flare.compose.ui.retweet_remove
import dev.dimension.flare.compose.ui.share
import dev.dimension.flare.compose.ui.show_media
import dev.dimension.flare.compose.ui.status_detail_tldr
import dev.dimension.flare.compose.ui.status_detail_translate
import dev.dimension.flare.compose.ui.status_open_post
import dev.dimension.flare.compose.ui.status_platform
import dev.dimension.flare.compose.ui.status_view_insight
import dev.dimension.flare.compose.ui.translation_badge_failed
import dev.dimension.flare.compose.ui.translation_badge_translated
import dev.dimension.flare.compose.ui.translation_badge_translating
import dev.dimension.flare.compose.ui.translation_retry
import dev.dimension.flare.compose.ui.translation_show_original
import dev.dimension.flare.compose.ui.unfavourite
import dev.dimension.flare.compose.ui.unlike
import dev.dimension.flare.compose.ui.user_block
import dev.dimension.flare.compose.ui.user_block_with_parameter
import dev.dimension.flare.compose.ui.user_follow_edit_list
import dev.dimension.flare.compose.ui.user_mute
import dev.dimension.flare.compose.ui.user_mute_with_parameter
import dev.dimension.flare.compose.ui.user_send_message
import dev.dimension.flare.compose.ui.user_unblock
import dev.dimension.flare.compose.ui.user_unmute
import dev.dimension.flare.compose.ui.visibility_friends
import dev.dimension.flare.compose.ui.visibility_self
import dev.dimension.flare.compose.ui.vote
import dev.dimension.flare.data.datasource.microblog.ActionMenu
import dev.dimension.flare.data.datasource.microblog.applyPostActionLayout
import dev.dimension.flare.data.model.PostActionStyle
import dev.dimension.flare.data.model.TimelineDisplayMode
import dev.dimension.flare.data.model.TimelineMediaLayout
import dev.dimension.flare.ui.component.AdaptiveGrid
import dev.dimension.flare.ui.component.AvatarComponent
import dev.dimension.flare.ui.component.DateTimeText
import dev.dimension.flare.ui.component.EmojiImage
import dev.dimension.flare.ui.component.FAIcon
import dev.dimension.flare.ui.component.FlareDividerDefaults
import dev.dimension.flare.ui.component.HorizontalDivider
import dev.dimension.flare.ui.component.LocalTimelineAppearance
import dev.dimension.flare.ui.component.RichText
import dev.dimension.flare.ui.component.TabIcon
import dev.dimension.flare.ui.component.placeholder
import dev.dimension.flare.ui.component.platform.PlatformCard
import dev.dimension.flare.ui.component.platform.PlatformCheckbox
import dev.dimension.flare.ui.component.platform.PlatformCircularProgressIndicator
import dev.dimension.flare.ui.component.platform.PlatformDropdownMenu
import dev.dimension.flare.ui.component.platform.PlatformDropdownMenuDivider
import dev.dimension.flare.ui.component.platform.PlatformDropdownMenuItem
import dev.dimension.flare.ui.component.platform.PlatformDropdownMenuScope
import dev.dimension.flare.ui.component.platform.PlatformFilledTonalButton
import dev.dimension.flare.ui.component.platform.PlatformIconButton
import dev.dimension.flare.ui.component.platform.PlatformRadioButton
import dev.dimension.flare.ui.component.platform.PlatformText
import dev.dimension.flare.ui.component.platform.PlatformTextButton
import dev.dimension.flare.ui.component.platform.PlatformTextStyle
import dev.dimension.flare.ui.component.toImageVector
import dev.dimension.flare.ui.model.ClickContext
import dev.dimension.flare.ui.model.TranslationDisplayState
import dev.dimension.flare.ui.model.UiCard
import dev.dimension.flare.ui.model.UiPoll
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.UiTranslatableText
import dev.dimension.flare.ui.model.onError
import dev.dimension.flare.ui.model.onLoading
import dev.dimension.flare.ui.model.onSuccess
import dev.dimension.flare.ui.render.RenderContent
import dev.dimension.flare.ui.render.UiRichText
import dev.dimension.flare.ui.route.DeeplinkRoute
import dev.dimension.flare.ui.route.toUri
import dev.dimension.flare.ui.theme.PlatformContentColor
import dev.dimension.flare.ui.theme.PlatformTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import moe.tlaster.precompose.molecule.producePresenter
import org.jetbrains.compose.resources.stringResource
import kotlin.math.ceil

@Composable
public fun CommonStatusComponent(
    item: UiTimelineV2.Post,
    modifier: Modifier = Modifier,
    isDetail: Boolean = false,
    isQuote: Boolean = false,
    isClickable: Boolean = true,
    showMedia: Boolean = true,
    maxLines: Int? = null,
    showExpandButton: Boolean = true,
    quotes: ImmutableList<UiTimelineV2.Post> = persistentListOf(),
    allowMediaCarousel: Boolean = false,
    carouselOuterHorizontalPadding: Dp = 0.dp,
    nameModifier: Modifier = Modifier,
    handleModifier: Modifier = Modifier,
    avatarModifier: Modifier = Modifier,
    actionsModifier: Modifier = Modifier,
    forceHideActions: Boolean = false,
    showReplyTo: Boolean = true,
): Unit =
    CompositionLocalProvider(
        PlatformTextStyle provides PlatformTheme.typography.postBody,
        PlatformContentColor provides StatusComponentDefaults.contentColor,
    ) {
        val uriHandler = LocalUriHandler.current
        val openPostLabel = stringResource(Res.string.status_open_post)
        val appearanceSettings = LocalTimelineAppearance.current
        val isGalleryFallback = LocalEffectiveTimelineDisplayMode.current == TimelineDisplayMode.Gallery
        val usesSideAvatar = !appearanceSettings.fullWidthPost && !isQuote && !isDetail
        val hasFullWidthHeader = !usesSideAvatar && !isQuote && item.user != null
        val sideAvatarTopInset = with(LocalDensity.current) { (PlatformTheme.typography.postBody.fontSize * 0.25f).toDp() }
        val replyToHandle = item.replyToHandle?.takeIf { showReplyTo }
        val translationDisplayed = item.translationDisplayState == TranslationDisplayState.Translated
        val showOriginal = appearanceSettings.aiConfig.showOriginalWithTranslation
        val visibleContent =
            remember(item.content, translationDisplayed, showOriginal) {
                item.content.visiblePostText(translationDisplayed, showOriginal)
            }
        val visibleContentWarning =
            remember(item.contentWarning, translationDisplayed, showOriginal) {
                item.contentWarning?.visiblePostText(translationDisplayed, showOriginal).orEmpty()
            }
        val showsDetailTranslation = isDetail && !item.content.original.isEmpty && appearanceSettings.showTranslateButton
        val hasTextContent = visibleContent.any { !it.isEmpty } || visibleContentWarning.any { !it.isEmpty }
        val hasBodyContent = hasTextContent || item.poll != null
        val headerSpacing =
            when {
                hasFullWidthHeader -> 8.dp
                isQuote && !isGalleryFallback && hasTextContent -> 4.dp
                else -> 2.dp
            }
        val hasBodyAfterReply = hasBodyContent || showsDetailTranslation
        val hasPreMediaBody = replyToHandle != null || hasBodyAfterReply
        val showsImages = item.images.isNotEmpty() && showMedia
        val showsCard = item.card != null && appearanceSettings.showLinkPreview && item.images.isEmpty() && quotes.isEmpty()
        val showsQuotes = quotes.isNotEmpty() && !isQuote
        val showsAttachments = showsImages || showsCard || showsQuotes
        val hasVisibleEmojiReactions = appearanceSettings.showEmojiReactions && item.emojiReactions.isNotEmpty()
        val showsReactions = !isQuote && (hasVisibleEmojiReactions || item.sourceChannel != null)
        val showsActions = (appearanceSettings.postActionStyle != PostActionStyle.Hidden || isDetail) && !forceHideActions
        val replyBeforeAttachments = replyToHandle != null && !isGalleryFallback && !hasBodyAfterReply
        val replyBeforeFooter = replyBeforeAttachments && !showsAttachments
        val replyBeforeTimestampOrActions = replyBeforeFooter && !showsReactions
        val firstAttachmentSpacing =
            when {
                replyBeforeAttachments -> 0.dp
                hasFullWidthHeader && !hasPreMediaBody -> 8.dp
                else -> StatusComponentDefaults.attachmentSpacing
            }
        val carouselEdgePadding = if (isQuote) StatusComponentDefaults.quotePadding else carouselOuterHorizontalPadding
        val carouselLeadingPadding =
            carouselEdgePadding +
                if (usesSideAvatar && item.user != null) {
                    StatusComponentDefaults.avatarSize + 8.dp
                } else {
                    0.dp
                }
        val usesSideAvatarCarousel =
            usesSideAvatar &&
                item.user != null &&
                showMedia &&
                allowMediaCarousel &&
                appearanceSettings.mediaLayout == TimelineMediaLayout.Carousel &&
                item.images.size > 1
        Row(
            modifier =
                Modifier
                    .let {
                        if (isDetail || !isClickable) {
                            it
                        } else {
                            it.clickable(onClickLabel = openPostLabel) {
                                item.onClicked.invoke(
                                    ClickContext(
                                        launcher = { url ->
                                            uriHandler.openUri(url)
                                        },
                                    ),
                                )
                            }
                        }
                    }.then(modifier),
        ) {
            if (usesSideAvatar) {
                item.user?.let {
                    Box(Modifier.padding(top = sideAvatarTopInset)) {
                        AvatarComponent(
                            it.avatar,
                            size = StatusComponentDefaults.avatarSize,
                            contentDescription =
                                stringResource(
                                    Res.string.profile_open_user,
                                    it.handle.display,
                                ),
                            modifier =
                                Modifier.clickable {
                                    it.onClicked.invoke(
                                        ClickContext(
                                            launcher = {
                                                uriHandler.openUri(it)
                                            },
                                        ),
                                    )
                                },
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
            }
            Column {
                Column(
                    modifier =
                        if (usesSideAvatarCarousel) {
                            Modifier.heightIn(
                                min = StatusComponentDefaults.avatarSize + sideAvatarTopInset - firstAttachmentSpacing,
                            )
                        } else {
                            Modifier
                        },
                ) {
                    item.user?.let { user ->
                        val dateContent = @Composable {
                            val iconSize =
                                with(LocalDensity.current) {
                                    PlatformTheme.typography.postBody.fontSize
                                        .toDp()
                                }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                item.visibility?.let {
                                    StatusVisibilityComponent(
                                        visibility = it,
                                        modifier =
                                            Modifier
                                                .size(iconSize),
                                        tint = StatusComponentDefaults.secondaryColor,
                                    )
                                }
                                AnimatedVisibility(
                                    visible = item.translationDisplayState != TranslationDisplayState.Hidden,
                                ) {
                                    TranslationDisplayBadge(
                                        state = item.translationDisplayState,
                                        iconSize = iconSize,
                                    )
                                }
                                if (appearanceSettings.showPlatformLogo) {
                                    TabIcon(
                                        icon = item.platformIcon,
                                        contentDescription =
                                            stringResource(
                                                Res.string.status_platform,
                                                item.platformId,
                                            ),
                                        size = iconSize,
                                        color = StatusComponentDefaults.secondaryColor,
                                    )
                                }
                                if (!isDetail) {
                                    DateTimeText(
                                        item.createdAt,
                                        style = PlatformTheme.typography.postBody,
                                        color = StatusComponentDefaults.secondaryColor,
                                    )
                                }
                                if (appearanceSettings.aiConfig.agent) {
                                    PlatformIconButton(
                                        onClick = {
                                            uriHandler.openUri(
                                                DeeplinkRoute
                                                    .Status
                                                    .Insight(
                                                        accountType = item.accountType,
                                                        statusKey = item.statusKey,
                                                    ).toUri(),
                                            )
                                        },
                                        modifier =
                                            Modifier
                                                .size(maxOf(24.dp, iconSize + 4.dp)),
                                    ) {
                                        FAIcon(
                                            imageVector = FontAwesomeIcons.Solid.Robot,
                                            contentDescription = stringResource(Res.string.status_view_insight),
                                            modifier =
                                                Modifier
                                                    .size(iconSize),
                                            tint = StatusComponentDefaults.secondaryColor,
                                        )
                                    }
                                }
                            }
                        }
                        if (usesSideAvatar) {
                            UserCompat(
                                user = user,
                                isPost = true,
                                subtitle = item.authorSubtitle,
                                onUserClick = {
                                    user.onClicked.invoke(
                                        ClickContext(
                                            launcher = {
                                                uriHandler.openUri(it)
                                            },
                                        ),
                                    )
                                },
                                leading = null,
                            ) {
                                dateContent.invoke()
                            }
                        } else if (isQuote) {
                            UserCompat(
                                user,
                                isPost = true,
                                subtitle = item.authorSubtitle,
                                onUserClick = {
                                    user.onClicked.invoke(
                                        ClickContext(
                                            launcher = {
                                                uriHandler.openUri(it)
                                            },
                                        ),
                                    )
                                },
                            ) {
                                dateContent.invoke()
                            }
                        } else {
                            CommonStatusHeaderComponent(
                                data = user,
                                isPost = true,
                                subtitle = item.authorSubtitle,
                                nameModifier = nameModifier,
                                handleModifier = handleModifier,
                                avatarModifier = avatarModifier,
                                onUserClick = {
                                    user.onClicked.invoke(
                                        ClickContext(
                                            launcher = {
                                                uriHandler.openUri(it)
                                            },
                                        ),
                                    )
                                },
                            ) {
                                dateContent.invoke()
                            }
                        }
                    }
                    replyToHandle?.let { replyHandle ->
                        val topSpacing =
                            when {
                                !isGalleryFallback -> 4.dp
                                !hasBodyAfterReply && showsAttachments -> firstAttachmentSpacing
                                else -> 8.dp
                            }
                        val bottomSpacing =
                            when {
                                !isGalleryFallback -> 4.dp
                                hasBodyContent -> 8.dp
                                else -> 0.dp
                            }
                        StatusReplyComponent(
                            replyHandle = replyHandle,
                            modifier = Modifier.padding(top = topSpacing, bottom = bottomSpacing),
                        )
                    }
                    val body = @Composable {
                        StatusContentComponent(
                            visibleContent = visibleContent,
                            visibleContentWarning = visibleContentWarning,
                            poll = item.poll,
                            maxLines = if (isDetail) Int.MAX_VALUE else maxLines,
                            showExpandButton = showExpandButton,
                            topSpacing = if (replyToHandle == null && item.user != null) headerSpacing else 0.dp,
                        )
                    }
                    if (isDetail) {
                        SelectionContainer(content = body)
                    } else {
                        body()
                    }

                    if (showsDetailTranslation) {
                        Spacer(
                            modifier =
                                Modifier.height(
                                    if (replyToHandle != null && !isGalleryFallback &&
                                        !hasBodyContent
                                    ) {
                                        0.dp
                                    } else {
                                        8.dp
                                    },
                                ),
                        )
                        TranslationComponent(
                            item = item,
                            statusKey = item.stableItemKey,
                            contentWarning = item.contentWarning?.original,
                            rawContent = item.content.original.innerText,
                            content = item.content.original,
                        )
                    }
                }

                if (showsImages) {
                    Spacer(modifier = Modifier.height(firstAttachmentSpacing))
                    StatusMediasComponent(
                        item,
                        isQuote = isQuote,
                        allowMediaCarousel = allowMediaCarousel,
                        carouselLeadingPadding = carouselLeadingPadding,
                        carouselTrailingPadding = carouselEdgePadding,
                        onMediaClick = { index ->
                            item.openMedia(
                                index = index,
                                launcher = uriHandler::openUri,
                            )
                        },
                    )
                }
                item.card?.let { card ->
                    if (showsCard) {
                        Spacer(modifier = Modifier.height(firstAttachmentSpacing))
                        StatusCardComponent(
                            card = card,
                            modifier =
                                Modifier
                                    .border(
                                        FlareDividerDefaults.thickness,
                                        color = FlareDividerDefaults.color,
                                        shape =
                                            if (isQuote) {
                                                PlatformTheme.shapes.small
                                            } else {
                                                PlatformTheme.shapes.medium
                                            },
                                    ).clip(
                                        shape =
                                            if (isQuote) {
                                                PlatformTheme.shapes.small
                                            } else {
                                                PlatformTheme.shapes.medium
                                            },
                                    ).pointerHoverIcon(PointerIcon.Hand)
                                    .clickable {
                                        uriHandler.openUri(card.url)
                                    }.fillMaxWidth(),
                        )
                    }
                }
                if (showsQuotes) {
                    Spacer(
                        modifier = Modifier.height(if (showsImages) 8.dp else firstAttachmentSpacing),
                    )
                    StatusQuoteComponent(
                        quotes = quotes,
                        allowMediaCarousel = allowMediaCarousel,
                    )
                }

                if (showsReactions) {
                    Spacer(modifier = Modifier.height(if (replyBeforeFooter) 0.dp else 8.dp))
                    StatusReactionComponent(
                        data = item,
                        isDetail = isDetail,
                        showEmojiReactions = hasVisibleEmojiReactions,
                    )
                }

                if (isDetail) {
                    Spacer(modifier = Modifier.height(if (replyBeforeTimestampOrActions) 0.dp else 8.dp))
                    DateTimeText(
                        item.createdAt,
                        style = PlatformTheme.typography.postBody,
                        color = StatusComponentDefaults.secondaryColor,
                        fullTime = true,
                    )
                }
                if (showsActions) {
                    val actionsTopSpacing =
                        when {
                            replyBeforeTimestampOrActions && !isDetail -> 0.dp
                            hasFullWidthHeader && !isDetail && !hasPreMediaBody && !showsAttachments && !showsReactions -> 8.dp
                            else -> StatusComponentDefaults.actionsSpacing
                        }
                    StatusActions(
                        item.actions,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(top = actionsTopSpacing)
                                .expandHorizontally(leading = 4.dp, trailing = 4.dp)
                                .then(actionsModifier),
                    )
                }
            }
        }
    }

@Composable
internal fun StatusMediasComponent(
    item: UiTimelineV2.Post,
    isQuote: Boolean,
    allowMediaCarousel: Boolean,
    carouselLeadingPadding: Dp,
    carouselTrailingPadding: Dp,
    onMediaClick: (Int) -> Unit,
) {
    val appearanceSettings = LocalTimelineAppearance.current
    var showMedia by remember { mutableStateOf(false) }
    if (appearanceSettings.showMedia || showMedia) {
        if (!appearanceSettings.showMedia) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            showMedia = false
                        },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FAIcon(
                    imageVector = FontAwesomeIcons.Solid.Image,
                    contentDescription = stringResource(resource = Res.string.hide_media),
                    modifier =
                        Modifier
                            .size(12.dp),
                    tint = StatusComponentDefaults.secondaryColor,
                )
                Spacer(modifier = Modifier.width(4.dp))
                PlatformText(
                    text = stringResource(resource = Res.string.hide_media),
                    style = PlatformTheme.typography.postBody,
                    color = StatusComponentDefaults.secondaryColor,
                )
            }
        }
        StatusMediaComponent(
            post = item,
            data = item.images,
            onMediaClick = onMediaClick,
            sensitive = item.sensitive,
            shape =
                if (isQuote) {
                    PlatformTheme.shapes.small
                } else {
                    PlatformTheme.shapes.medium
                },
            allowCarousel = allowMediaCarousel,
            carouselLeadingPadding = carouselLeadingPadding,
            carouselTrailingPadding = carouselTrailingPadding,
        )
    } else {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        showMedia = true
                    },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FAIcon(
                imageVector = FontAwesomeIcons.Solid.Image,
                contentDescription = stringResource(resource = Res.string.show_media),
                modifier =
                    Modifier
                        .size(12.dp),
                tint = StatusComponentDefaults.secondaryColor,
            )
            Spacer(modifier = Modifier.width(4.dp))
            PlatformText(
                text = stringResource(resource = Res.string.show_media),
                style = PlatformTheme.typography.postBody,
                color = StatusComponentDefaults.secondaryColor,
            )
        }
    }
}

@Composable
private fun StatusQuoteComponent(
    quotes: ImmutableList<UiTimelineV2.Post>,
    allowMediaCarousel: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .border(
                    FlareDividerDefaults.thickness,
                    color = FlareDividerDefaults.color,
                    shape = PlatformTheme.shapes.medium,
                ).clip(
                    shape = PlatformTheme.shapes.medium,
                ),
    ) {
        Column {
            CompositionLocalProvider(
                LocalTimelineAppearance provides
                    LocalTimelineAppearance.current.copy(
                        postActionStyle = PostActionStyle.Hidden,
                    ),
            ) {
                quotes.fastForEachIndexed { index, quote ->
                    CommonStatusComponent(
                        quote,
                        isQuote = true,
                        allowMediaCarousel = allowMediaCarousel,
                        modifier =
                            Modifier
                                .padding(StatusComponentDefaults.quotePadding),
                    )
                    if (index != quotes.lastIndex && quotes.size > 1) {
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusReactionComponent(
    data: UiTimelineV2.Post,
    isDetail: Boolean,
    showEmojiReactions: Boolean,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        data.sourceChannel?.let { channel ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FAIcon(
                    imageVector = FontAwesomeIcons.Solid.Tv,
                    contentDescription = channel.name,
                    tint = StatusComponentDefaults.secondaryColor,
                    modifier =
                        Modifier
                            .size(12.dp),
                )
                PlatformText(
                    text = channel.name,
                    style = PlatformTheme.typography.postBody,
                    color = StatusComponentDefaults.secondaryColor,
                    maxLines = 1,
                )
            }
        }
        if (showEmojiReactions) {
            StatusReactionRow(
                itemCount = data.emojiReactions.size,
                isDetail = isDetail,
            ) { index ->
                val reaction = data.emojiReactions[index]
                val color =
                    if (reaction.me) {
                        PlatformTheme.colorScheme.primaryContainer
                    } else {
                        PlatformTheme.colorScheme.cardAlt
                    }
                val borderColor =
                    if (reaction.me) {
                        PlatformTheme.colorScheme.primary
                    } else {
                        Color.Transparent
                    }
                PlatformCard(
                    shape = RoundedCornerShape(100),
                    containerColor = color,
                    modifier =
                        Modifier
                            .border(
                                FlareDividerDefaults.thickness,
                                color = borderColor,
                                shape = RoundedCornerShape(100),
                            ),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .clickable {
                                    reaction.onClicked.invoke(
                                        ClickContext(uriHandler::openUri),
                                    )
                                }.padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        if (reaction.isUnicode) {
                            PlatformText(reaction.name)
                        } else {
                            EmojiImage(
                                media = reaction.media,
                                modifier = Modifier.height(16.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        PlatformText(
                            text = reaction.count.humanized,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun TranslationDisplayBadge(
    state: TranslationDisplayState,
    modifier: Modifier = Modifier,
    iconSize: Dp? = null,
) {
    val color = if (iconSize == null) PlatformTheme.colorScheme.caption else StatusComponentDefaults.secondaryColor
    val description =
        when (state) {
            TranslationDisplayState.Translating -> {
                stringResource(Res.string.translation_badge_translating)
            }

            TranslationDisplayState.Translated -> {
                stringResource(Res.string.translation_badge_translated)
            }

            TranslationDisplayState.Failed -> {
                stringResource(Res.string.translation_badge_failed)
            }

            TranslationDisplayState.Hidden -> {
                null
            }
        }
    Row(
        modifier =
            modifier.semantics {
                description?.let { contentDescription = it }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FAIcon(
            FontAwesomeIcons.Solid.Language,
            contentDescription = null,
            modifier = Modifier.size(iconSize ?: 20.dp),
            tint = color,
        )
        AnimatedContent(state) { state ->
            when (state) {
                TranslationDisplayState.Translating -> {
                    PlatformCircularProgressIndicator(
                        modifier = Modifier.size(iconSize ?: 12.dp),
                        color = color,
                    )
                }

                TranslationDisplayState.Translated -> {}

                TranslationDisplayState.Failed -> {
                    FAIcon(
                        FontAwesomeIcons.Solid.TriangleExclamation,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(iconSize ?: 12.dp),
                    )
                }

                TranslationDisplayState.Hidden -> {}
            }
        }
    }
}

@Composable
private fun TranslationComponent(
    item: UiTimelineV2.Post,
    statusKey: String,
    contentWarning: UiRichText?,
    rawContent: String,
    content: UiRichText,
) {
    val timelineAppearance = LocalTimelineAppearance.current
    var enabledTranslate by rememberSaveable("translate-$statusKey") {
        mutableStateOf(false)
    }
    var enabledTldr by rememberSaveable("tldr-$statusKey") {
        mutableStateOf(false)
    }
    Row {
        PlatformTextButton(
            onClick = {
                if (!enabledTranslate) {
                    enabledTranslate = true
                }
            },
        ) {
            PlatformText(
                style = PlatformTheme.typography.postBody,
                text =
                    stringResource(
                        resource = Res.string.status_detail_translate,
                    ),
            )
        }
        if (timelineAppearance.aiConfig.tldr && content.isLongText) {
            PlatformTextButton(
                onClick = {
                    if (!enabledTldr) {
                        enabledTldr = true
                    }
                },
            ) {
                PlatformText(
                    style = PlatformTheme.typography.postBody,
                    text =
                        stringResource(
                            resource = Res.string.status_detail_tldr,
                        ),
                )
            }
        }
    }
    if (enabledTldr) {
        Spacer(modifier = Modifier.height(8.dp))
        val state by producePresenter(
            "tldr_${contentWarning}_${rawContent}_${Locale.current.language}",
        ) {
            statusTldrPresenter(
                contentWarning = contentWarning,
                content = content,
                targetLanguage = Locale.current.language,
            )
        }
        state
            .onSuccess {
                PlatformText(text = it)
            }.onLoading {
                PlatformText(
                    text = "Lores ipsum dolor sit amet",
                    modifier = Modifier.placeholder(true),
                )
            }.onError {
                PlatformText(text = it.message ?: "Error")
            }
    }
    if (enabledTranslate) {
        Spacer(modifier = Modifier.height(8.dp))
        val state by producePresenter(
            "translate_${contentWarning}_${rawContent}_${Locale.current.language}_${timelineAppearance.aiConfig.translation}",
        ) {
            statusTranslatePresenter(
                item = item,
                contentWarning = contentWarning,
                content = content,
                targetLanguage = Locale.current.language,
            )
        }
        state.contentWarning
            ?.onSuccess {
                RichText(
                    text = it,
                    modifier = Modifier.fillMaxWidth(),
                )
            }?.onLoading {
                PlatformText(
                    text = "Lores ipsum dolor sit amet",
                    modifier = Modifier.placeholder(true),
                )
            }?.onError {
                PlatformText(text = it.message ?: "Error")
            }
        state.text
            .onSuccess {
                RichText(
                    text = it,
                    modifier = Modifier.fillMaxWidth(),
                )
            }.onLoading {
                PlatformText(
                    text = "Lores ipsum dolor sit amet",
                    modifier = Modifier.placeholder(true),
                )
            }.onError {
                PlatformText(text = it.message ?: "Error")
            }
    }
}

@Composable
public fun StatusVisibilityComponent(
    visibility: UiTimelineV2.Post.Visibility,
    tint: Color = PlatformContentColor.current,
    modifier: Modifier = Modifier,
) {
    when (visibility) {
        UiTimelineV2.Post.Visibility.Public -> {
            FAIcon(
                imageVector = FontAwesomeIcons.Solid.Globe,
                contentDescription = stringResource(resource = Res.string.mastodon_visibility_public),
                modifier = modifier,
                tint = tint,
            )
        }

        UiTimelineV2.Post.Visibility.Home -> {
            FAIcon(
                imageVector = FontAwesomeIcons.Solid.LockOpen,
                contentDescription = stringResource(resource = Res.string.mastodon_visibility_unlisted),
                modifier = modifier,
                tint = tint,
            )
        }

        UiTimelineV2.Post.Visibility.Followers -> {
            FAIcon(
                imageVector = FontAwesomeIcons.Solid.Lock,
                contentDescription = stringResource(resource = Res.string.mastodon_visibility_private),
                modifier = modifier,
                tint = tint,
            )
        }

        UiTimelineV2.Post.Visibility.Specified -> {
            FAIcon(
                imageVector = FontAwesomeIcons.Solid.At,
                contentDescription = stringResource(resource = Res.string.mastodon_visibility_direct),
                modifier = modifier,
                tint = tint,
            )
        }

        UiTimelineV2.Post.Visibility.Channel -> {
            FAIcon(
                imageVector = FontAwesomeIcons.Solid.Tv,
                contentDescription = stringResource(resource = Res.string.channel_title),
                modifier = modifier,
                tint = tint,
            )
        }

        UiTimelineV2.Post.Visibility.Friends -> {
            FAIcon(
                imageVector = FontAwesomeIcons.Solid.Heart,
                contentDescription = stringResource(Res.string.visibility_friends),
                modifier = modifier,
                tint = tint,
            )
        }

        UiTimelineV2.Post.Visibility.SelfOnly -> {
            FAIcon(
                imageVector = FontAwesomeIcons.Solid.Lock,
                contentDescription = stringResource(Res.string.visibility_self),
                modifier = modifier,
                tint = tint,
            )
        }
    }
}

@Composable
internal fun StatusActions(
    items: ImmutableList<ActionMenu>,
    modifier: Modifier = Modifier,
    isPost: Boolean = true,
) = CompositionLocalProvider(
    PlatformTextStyle provides
        PlatformTheme.typography.postBody.let {
            val reduction = if (isPost) StatusComponentDefaults.postActionTextSizeReduction else 2
            it.copy(fontSize = (it.fontSize.value - reduction).sp)
        },
    PlatformContentColor provides StatusComponentDefaults.secondaryColor,
) {
    val appearanceSettings = LocalTimelineAppearance.current
    val displayItems =
        remember(items, appearanceSettings.postActionLayout) {
            items.applyPostActionLayout(appearanceSettings.postActionLayout).filterNot { it == ActionMenu.Divider }
        }
    if (displayItems.isEmpty()) return@CompositionLocalProvider
    val haptics = LocalHapticFeedback.current
    val launcher = LocalUriHandler.current
    val density = LocalDensity.current
    val isStretched = appearanceSettings.postActionStyle == PostActionStyle.Stretch
    val isAndroidPost = isPost && SystemUtils.isAndroid
    val iconSize = if (isPost) StatusComponentDefaults.postActionIconSize else StatusComponentDefaults.actionIconSize
    val trailingNumberWidth =
        with(density) { PlatformTextStyle.current.fontSize.toDp() } * 2.5f + StatusComponentDefaults.actionLabelSpacing
    Row(
        modifier = modifier,
        verticalAlignment = if (isPost) Alignment.CenterVertically else Alignment.Bottom,
        horizontalArrangement =
            when (appearanceSettings.postActionStyle) {
                PostActionStyle.Hidden -> Arrangement.spacedBy(4.dp, Alignment.Start)
                PostActionStyle.LeftAligned -> Arrangement.spacedBy(4.dp, Alignment.Start)
                PostActionStyle.RightAligned -> Arrangement.spacedBy(4.dp, Alignment.End)
                PostActionStyle.Stretch -> Arrangement.Start
            },
    ) {
        displayItems.fastForEachIndexed { index, action ->
            val item =
                when (action) {
                    is ActionMenu.Item -> action
                    is ActionMenu.Group -> action.displayItem
                    ActionMenu.Divider -> return@fastForEachIndexed
                }
            val number = item.count
            val icon = item.icon?.toImageVector() ?: FontAwesomeIcons.Solid.Ellipsis
            val iconWidth =
                if (isAndroidPost) {
                    with(density) {
                        // Reserve enough pixels for aspect-fit to use the full height.
                        ceil(iconSize.roundToPx() * (icon.defaultWidth / icon.defaultHeight)).toDp()
                    }
                } else {
                    iconSize
                }
            val actionModifier =
                when {
                    !isStretched || displayItems.size == 1 -> {
                        Modifier
                    }

                    index != displayItems.lastIndex -> {
                        Modifier.weight(1f)
                    }

                    else -> {
                        val iconSlotWidth = maxOf(StatusComponentDefaults.actionIconSlotWidth, iconWidth)
                        val padding = StatusComponentDefaults.actionButtonPadding
                        val actionWidth =
                            if (isAndroidPost) {
                                with(density) { (iconSlotWidth.roundToPx() + padding.roundToPx() * 2).toDp() }
                            } else {
                                iconSlotWidth + padding * 2
                            }
                        val trailingNumber = if (isAndroidPost) number.visibleActionNumber(appearanceSettings.showNumbers) else number
                        Modifier.width(actionWidth + if (trailingNumber != null) trailingNumberWidth else 0.dp)
                    }
                }
            if (index == displayItems.lastIndex && appearanceSettings.postActionStyle == PostActionStyle.LeftAligned) {
                Spacer(modifier = Modifier.weight(1f))
            }
            when (action) {
                is ActionMenu.Group -> {
                    StatusActionGroup(
                        modifier = actionModifier,
                        icon = icon,
                        number = number,
                        iconSize = iconSize,
                        iconWidth = iconWidth,
                        color =
                            action.displayItem.color?.toComposeColor()
                                ?: PlatformContentColor.current,
                        contentDescription =
                            action.displayItem.text?.asString()
                                ?: stringResource(Res.string.more),
                        withTextMinWidth =
                            !isStretched && appearanceSettings.postActionFixedWidth &&
                                action.displayItem.count != null &&
                                index != displayItems.lastIndex,
                        isStretched = isStretched,
                    ) { closeMenu, isMenuShown ->
                        action.actions.fastForEach { subActions ->
                            when (subActions) {
                                is ActionMenu.Item -> {
                                    StatusActionItemMenu(subActions, closeMenu, launcher)
                                }

                                // nested group is not supported
                                is ActionMenu.Group -> {}

                                ActionMenu.Divider -> {
                                    PlatformDropdownMenuDivider()
                                }
                            }
                        }
                    }
                }

                is ActionMenu.Item -> {
                    StatusActionButton(
                        modifier = actionModifier,
                        icon = icon,
                        number = number,
                        color = action.color?.toComposeColor() ?: PlatformContentColor.current,
                        contentDescription =
                            action.text?.asString()
                                ?: stringResource(Res.string.more),
                        enabled = action.enabled,
                        iconSize = iconSize,
                        iconWidth = iconWidth,
                        withTextMinWidth =
                            !isStretched && appearanceSettings.postActionFixedWidth &&
                                action.count != null &&
                                index != displayItems.lastIndex,
                        isStretched = isStretched,
                        onClicked = {
                            action.onClicked.let { onClick ->
                                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                onClick.invoke(
                                    ClickContext(
                                        launcher = {
                                            launcher.openUri(it)
                                        },
                                    ),
                                )
                            }
                        },
                    )
                }

                // divider is only supported in group
                ActionMenu.Divider -> {}
            }
        }
    }
}

@Composable
internal fun StatusActionsDropdownMenu(
    items: ImmutableList<ActionMenu>,
    expanded: Boolean,
    onDismissRequest: () -> Unit,
) {
    val appearanceSettings = LocalTimelineAppearance.current
    val postActionLayout = appearanceSettings.postActionLayout
    val displayItems =
        remember(items, postActionLayout) {
            lazy(LazyThreadSafetyMode.NONE) {
                items
                    .applyPostActionLayout(postActionLayout)
                    .flattenStatusActionMenuItems()
            }
        }
    val launcher = LocalUriHandler.current
    PlatformDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
    ) {
        CompositionLocalProvider(
            PlatformContentColor provides PlatformTheme.colorScheme.text,
            PlatformTextStyle provides PlatformTheme.typography.body,
        ) {
            StatusActionMenuItems(displayItems.value, onDismissRequest, launcher)
        }
    }
}

@Composable
private fun PlatformDropdownMenuScope.StatusActionMenuItems(
    items: List<ActionMenu>,
    closeMenu: () -> Unit,
    launcher: UriHandler,
) {
    items.fastForEach { action ->
        when (action) {
            is ActionMenu.Item -> {
                StatusActionItemMenu(action, closeMenu, launcher)
            }

            is ActionMenu.Group -> {
                StatusActionMenuItems(action.actions, closeMenu, launcher)
            }

            ActionMenu.Divider -> {
                PlatformDropdownMenuDivider()
            }
        }
    }
}

private fun List<ActionMenu>.flattenStatusActionMenuItems(): List<ActionMenu> {
    val flattened =
        buildList {
            this@flattenStatusActionMenuItems.forEach { action ->
                when (action) {
                    is ActionMenu.Item -> {
                        add(action)
                    }

                    is ActionMenu.Group -> {
                        val children = action.actions.flattenStatusActionMenuItems()
                        if (children.isNotEmpty()) {
                            add(ActionMenu.Divider)
                            addAll(children)
                            add(ActionMenu.Divider)
                        }
                    }

                    ActionMenu.Divider -> {
                        add(action)
                    }
                }
            }
        }
    val result = mutableListOf<ActionMenu>()
    flattened.forEach { action ->
        if (action != ActionMenu.Divider || (result.isNotEmpty() && result.last() != ActionMenu.Divider)) {
            result += action
        }
    }
    if (result.lastOrNull() == ActionMenu.Divider) {
        result.removeAt(result.lastIndex)
    }
    return result
}

@Composable
private fun PlatformDropdownMenuScope.StatusActionItemMenu(
    subActions: ActionMenu.Item,
    closeMenu: () -> Unit,
    launcher: UriHandler,
) {
    val color = subActions.color?.toComposeColor() ?: PlatformContentColor.current
    PlatformDropdownMenuItem(
        enabled = subActions.enabled,
        leadingIcon = {
            subActions.icon?.let {
                FAIcon(
                    imageVector = it.toImageVector(),
                    contentDescription = null,
                    tint = color,
                )
            }
        },
        text = {
            subActions.text?.let {
                PlatformText(
                    text = it.asString(),
                    color = color,
                )
            }
        },
        onClick = {
            closeMenu.invoke()
            subActions.onClicked.invoke(
                ClickContext(
                    launcher = {
                        launcher.openUri(it)
                    },
                ),
            )
        },
    )
}

@Composable
private fun ActionMenu.Item.Text.asString(): String =
    when (this) {
        is ActionMenu.Item.Text.Raw -> {
            text
        }

        is ActionMenu.Item.Text.Localized -> {
            val resource =
                when (type) {
                    ActionMenu.Item.Text.Localized.Type.Like -> Res.string.like
                    ActionMenu.Item.Text.Localized.Type.Unlike -> Res.string.unlike
                    ActionMenu.Item.Text.Localized.Type.Retweet -> Res.string.retweet
                    ActionMenu.Item.Text.Localized.Type.Unretweet -> Res.string.retweet_remove
                    ActionMenu.Item.Text.Localized.Type.Reply -> Res.string.reply
                    ActionMenu.Item.Text.Localized.Type.Comment -> Res.string.comment
                    ActionMenu.Item.Text.Localized.Type.Quote -> Res.string.quote
                    ActionMenu.Item.Text.Localized.Type.Bookmark -> Res.string.bookmark_add
                    ActionMenu.Item.Text.Localized.Type.Unbookmark -> Res.string.bookmark_remove
                    ActionMenu.Item.Text.Localized.Type.More -> Res.string.more
                    ActionMenu.Item.Text.Localized.Type.Delete -> Res.string.delete
                    ActionMenu.Item.Text.Localized.Type.Report -> Res.string.report
                    ActionMenu.Item.Text.Localized.Type.React -> Res.string.reaction_add
                    ActionMenu.Item.Text.Localized.Type.UnReact -> Res.string.reaction_remove
                    ActionMenu.Item.Text.Localized.Type.Share -> Res.string.share
                    ActionMenu.Item.Text.Localized.Type.FxShare -> Res.string.fx_share
                    ActionMenu.Item.Text.Localized.Type.EditUserList -> Res.string.user_follow_edit_list
                    ActionMenu.Item.Text.Localized.Type.SendMessage -> Res.string.user_send_message
                    ActionMenu.Item.Text.Localized.Type.Mute -> Res.string.user_mute
                    ActionMenu.Item.Text.Localized.Type.UnMute -> Res.string.user_unmute
                    ActionMenu.Item.Text.Localized.Type.Block -> Res.string.user_block
                    ActionMenu.Item.Text.Localized.Type.UnBlock -> Res.string.user_unblock
                    ActionMenu.Item.Text.Localized.Type.BlockWithHandleParameter -> Res.string.user_block_with_parameter
                    ActionMenu.Item.Text.Localized.Type.MuteWithHandleParameter -> Res.string.user_mute_with_parameter
                    ActionMenu.Item.Text.Localized.Type.AcceptFollowRequest -> Res.string.more
                    ActionMenu.Item.Text.Localized.Type.RejectFollowRequest -> Res.string.more
                    ActionMenu.Item.Text.Localized.Type.RetryTranslation -> Res.string.translation_retry
                    ActionMenu.Item.Text.Localized.Type.Translate -> Res.string.status_detail_translate
                    ActionMenu.Item.Text.Localized.Type.ShowOriginal -> Res.string.translation_show_original
                    ActionMenu.Item.Text.Localized.Type.Favorite -> Res.string.favourite
                    ActionMenu.Item.Text.Localized.Type.UnFavorite -> Res.string.unfavourite
                }
            stringResource(resource, *parameters.toTypedArray())
        }
    }

@Composable
private fun ActionMenu.Item.Color.toComposeColor(): Color =
    when (this) {
        ActionMenu.Item.Color.Red -> PlatformTheme.colorScheme.error
        ActionMenu.Item.Color.ContentColor -> PlatformContentColor.current
        ActionMenu.Item.Color.PrimaryColor -> PlatformTheme.colorScheme.retweetColor
    }

@Composable
private fun StatusReplyComponent(
    replyHandle: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FAIcon(
            imageVector = FontAwesomeIcons.Solid.Reply,
            contentDescription = stringResource(resource = Res.string.reply_to),
            tint = StatusComponentDefaults.secondaryColor,
            modifier =
                Modifier
                    .size(12.dp),
        )
        PlatformText(
            text = stringResource(resource = Res.string.reply_to, replyHandle),
            style = PlatformTheme.typography.postBody,
            color = StatusComponentDefaults.secondaryColor,
            maxLines = 1,
        )
    }
}

private fun UiTranslatableText.visiblePostText(
    translated: Boolean,
    showOriginal: Boolean,
): List<UiRichText> {
    val translatedText = translation
    return when {
        !translated || translatedText == null -> listOf(original)
        showOriginal -> listOf(original, translatedText)
        else -> listOf(translatedText)
    }
}

@Composable
private fun StatusContentComponent(
    visibleContent: List<UiRichText>,
    visibleContentWarning: List<UiRichText>,
    poll: UiPoll?,
    maxLines: Int?,
    showExpandButton: Boolean,
    topSpacing: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val appearanceSettings = LocalTimelineAppearance.current
    val effectiveMaxLines = (maxLines ?: appearanceSettings.lineLimit).coerceAtLeast(1)
    val collapseThresholdLines = maxLines?.coerceAtLeast(1) ?: 15
    var expanded by rememberSaveable {
        mutableStateOf(false)
    }
    var overflowingTextIndexes by remember(visibleContent, effectiveMaxLines, expanded) {
        mutableStateOf(emptySet<Int>())
    }
    Column(
        modifier = modifier,
    ) {
        if (topSpacing > 0.dp && (visibleContentWarning.any { !it.isEmpty } || visibleContent.any { !it.isEmpty } || poll != null)) {
            Spacer(modifier = Modifier.height(topSpacing))
        }
        val expandContentWarning = appearanceSettings.expandContentWarning
        if (visibleContentWarning.any { !it.isEmpty }) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                visibleContentWarning.forEach { warning ->
                    if (!warning.isEmpty) {
                        RichText(text = warning)
                    }
                }
                if (!expandContentWarning) {
                    PlatformFilledTonalButton(
                        modifier =
                            Modifier
                                .fillMaxWidth(),
                        onClick = {
                            expanded = !expanded
                        },
                    ) {
                        if (expanded) {
                            PlatformText(stringResource(Res.string.mastodon_item_show_less), style = PlatformTheme.typography.postBody)
                        } else {
                            PlatformText(stringResource(Res.string.mastodon_item_show_more), style = PlatformTheme.typography.postBody)
                        }
                    }
                }
            }
        }
        AnimatedVisibility(visible = expanded || expandContentWarning || visibleContentWarning.all { it.isEmpty }) {
            Column(
                modifier =
                    Modifier.padding(
                        top =
                            if (visibleContentWarning.any { !it.isEmpty } && (visibleContent.any { !it.isEmpty } || poll != null)) {
                                8.dp
                            } else {
                                0.dp
                            },
                    ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                key(visibleContent, effectiveMaxLines) {
                    visibleContent.forEachIndexed { index, visibleText ->
                        if (!visibleText.isEmpty) {
                            if (expanded || effectiveMaxLines == Int.MAX_VALUE) {
                                RichText(
                                    text = visibleText,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                CollapsibleRichText(
                                    text = visibleText,
                                    lineLimit = effectiveMaxLines,
                                    collapseThresholdLines = collapseThresholdLines,
                                    onOverflowChanged = { overflows ->
                                        overflowingTextIndexes =
                                            if (overflows) {
                                                overflowingTextIndexes + index
                                            } else {
                                                overflowingTextIndexes - index
                                            }
                                    },
                                )
                            }
                        }
                    }
                }
                if (visibleContent.any { !it.isEmpty }) {
                    if (overflowingTextIndexes.isNotEmpty() && showExpandButton) {
                        PlatformTextButton(
                            onClick = {
                                expanded = true
                            },
                        ) {
                            PlatformText(
                                stringResource(Res.string.post_show_full_text),
                                style = PlatformTheme.typography.postBody,
                                color = PlatformTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                poll?.let {
                    StatusPollComponent(
                        poll = it,
                    )
                }
            }
        }
    }
}

@Composable
private fun CollapsibleRichText(
    text: UiRichText,
    lineLimit: Int,
    collapseThresholdLines: Int,
    onOverflowChanged: (Boolean) -> Unit,
) {
    val lineHeight =
        with(LocalDensity.current) {
            PlatformTextStyle.current
                .lineHeight
                .roundToPx()
                .coerceAtLeast(1)
        }
    var fullHeight by remember(text) { mutableStateOf(0) }
    var textLayout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    val singleTextBlock = text.renderRuns.singleOrNull() is RenderContent.Text
    val threshold = maxOf(lineLimit, collapseThresholdLines)
    val overflows =
        textLayout?.let { it.lineCount > threshold }
            ?: shouldCollapseRichText(fullHeight, lineHeight, threshold)
    LaunchedEffect(overflows) {
        onOverflowChanged(overflows)
    }
    Layout(
        modifier = Modifier.fillMaxWidth().clipToBounds(),
        content = {
            RichText(
                text = text,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .onSizeChanged { fullHeight = it.height },
                onTextLayout = { if (singleTextBlock) textLayout = it },
            )
        },
    ) { measurables, constraints ->
        val placeable =
            measurables.single().measure(
                constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity),
            )
        val height =
            textLayout?.let { result ->
                if (result.lineCount > threshold) {
                    placeable.height - result.size.height + ceil(result.getLineBottom(lineLimit.coerceAtLeast(1) - 1)).toInt()
                } else {
                    placeable.height
                }
            } ?: collapsedRichTextHeight(
                // shortcut: multi-block rich text keeps height-based folding until block layout metrics are available.
                fullHeight = placeable.height,
                lineHeight = lineHeight,
                lineLimit = lineLimit,
                collapseThresholdLines = collapseThresholdLines,
            )
        layout(placeable.width, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            placeable.placeRelative(0, 0)
        }
    }
}

internal fun shouldCollapseRichText(
    fullHeight: Int,
    lineHeight: Int,
    collapseThresholdLines: Int,
): Boolean =
    fullHeight.toLong() >
        lineHeight.coerceAtLeast(1).toLong() * collapseThresholdLines.coerceAtLeast(1)

internal fun collapsedRichTextHeight(
    fullHeight: Int,
    lineHeight: Int,
    lineLimit: Int,
    collapseThresholdLines: Int,
): Int =
    if (shouldCollapseRichText(fullHeight, lineHeight, maxOf(lineLimit, collapseThresholdLines))) {
        minOf(fullHeight.toLong(), lineHeight.coerceAtLeast(1).toLong() * lineLimit.coerceAtLeast(1)).toInt()
    } else {
        fullHeight
    }

@Composable
private fun StatusPollComponent(
    poll: UiPoll,
    modifier: Modifier = Modifier,
) {
    val selectedOptions =
        remember {
            mutableStateListOf(*poll.ownVotes.toTypedArray())
        }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        poll.options.fastForEachIndexed { index, option ->
            PollOption(
                option = option,
                modifier = Modifier.fillMaxWidth(),
                canVote = poll.canVote,
                multiple = poll.multiple,
                selected = selectedOptions.contains(index),
                onClick = {
                    if (poll.multiple) {
                        if (selectedOptions.contains(index)) {
                            selectedOptions.remove(index)
                        } else {
                            selectedOptions.add(index)
                        }
                    } else {
                        selectedOptions.clear()
                        selectedOptions.add(index)
                    }
                },
            )
        }
        if (poll.expired) {
            PlatformText(
                text = stringResource(resource = Res.string.poll_expired),
                modifier =
                    Modifier
                        .align(Alignment.End),
                style = PlatformTheme.typography.postBody,
                color = StatusComponentDefaults.secondaryColor,
            )
        } else {
            poll.expiredAt?.let { expiredAt ->
                PlatformText(
                    text =
                        stringResource(
                            resource = Res.string.poll_expired_at,
                            expiredAt.full,
                        ),
                    modifier =
                        Modifier
                            .align(Alignment.End),
                    style = PlatformTheme.typography.postBody,
                    color = StatusComponentDefaults.secondaryColor,
                )
            }
        }
        if (poll.canVote) {
            val uriHandler = LocalUriHandler.current
            PlatformFilledTonalButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    poll.onVote.invoke(
                        ClickContext(launcher = uriHandler::openUri),
                        selectedOptions.toImmutableList(),
                    )
                },
            ) {
                PlatformText(
                    text = stringResource(resource = Res.string.vote),
                    style = PlatformTheme.typography.postBody,
                )
            }
        }
    }
}

@Composable
private fun PollOption(
    canVote: Boolean,
    multiple: Boolean,
    option: UiPoll.Option,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .height(IntrinsicSize.Min)
                .border(
                    width = FlareDividerDefaults.thickness,
                    color = FlareDividerDefaults.color,
                    shape = PlatformTheme.shapes.small,
                )
//            .background(
//                color = PlatformTheme.colorScheme.secondaryContainer,
//                shape = PlatformTheme.shapes.small,
//            )
                .clip(
                    shape = PlatformTheme.shapes.small,
                ),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(option.percentage)
                    .background(
                        color = PlatformTheme.colorScheme.cardAlt,
                        shape = PlatformTheme.shapes.small,
                    ),
        )
        val mutableInteractionSource =
            remember {
                MutableInteractionSource()
            }
        ListComponent(
            modifier =
                Modifier.clickable(
                    onClick = onClick,
                    interactionSource = mutableInteractionSource,
                    indication = LocalIndication.current,
                    enabled = canVote,
                ),
            headlineContent = {
                PlatformText(
                    text = option.title,
                    color = StatusComponentDefaults.secondaryColor,
                    modifier =
                        Modifier
                            .padding(8.dp),
                )
            },
            trailingContent = {
                if (canVote || selected) {
                    if (multiple) {
                        PlatformCheckbox(
                            checked = selected,
                            onCheckedChange = {
                                onClick.invoke()
                            },
                            interactionSource = mutableInteractionSource,
                            enabled = canVote,
                        )
                    } else {
                        PlatformRadioButton(
                            selected = selected,
                            onClick = onClick,
                            interactionSource = mutableInteractionSource,
                            enabled = canVote,
                        )
                    }
                } else {
                    // keep the height consist
//                    PlatformRadioButton(
//                        selected = false,
//                        onClick = {},
//                        enabled = false,
//                        modifier = Modifier.alpha(0f),
//                    )
                }
            },
        )
    }
}

@Composable
private fun StatusCardComponent(
    card: UiCard,
    modifier: Modifier = Modifier,
) {
    val appearanceSettings = LocalTimelineAppearance.current
    if (appearanceSettings.compatLinkPreview) {
        CompatCard(
            card = card,
            modifier = modifier,
        )
    } else {
        ExpandedCard(
            card = card,
            modifier = modifier,
        )
    }
}

@Composable
private fun ExpandedCard(
    card: UiCard,
    modifier: Modifier = Modifier,
) {
    val appearanceSettings = LocalTimelineAppearance.current
    Column(
        modifier = modifier,
    ) {
        card.media?.let { media ->
            AdaptiveGrid(
                itemCount = 1,
                itemContent = {
                    MediaItem(
                        media = media,
                        keepAspectRatio = appearanceSettings.expandMediaSize,
                        modifier =
                            Modifier
                                .clipToBounds(),
                    )
                },
                expandedSize = appearanceSettings.expandMediaSize,
                modifier =
                    Modifier
                        .clipToBounds(),
            )
        }
        Column(
            modifier =
                Modifier
                    .padding(8.dp),
        ) {
            PlatformText(
                text = card.title,
                maxLines = 2,
            )
            card.description?.let {
                PlatformText(
                    text = it,
                    style = PlatformTheme.typography.postBody,
                    color = StatusComponentDefaults.secondaryColor,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun CompatCard(
    card: UiCard,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
    ) {
        card.media?.let {
            MediaItem(
                media = it,
                modifier =
                    Modifier
                        .size(72.dp)
                        .clipToBounds(),
            )
        }
        Column(
            modifier =
                Modifier
                    .padding(8.dp),
        ) {
            PlatformText(
                text = card.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            card.description?.let {
                PlatformText(
                    text = it,
                    style = PlatformTheme.typography.postBody,
                    color = StatusComponentDefaults.secondaryColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
