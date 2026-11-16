import SwiftUI
import KotlinSharedUI
import SwiftUIBackports
import FlareAppleCore

#if os(macOS)
import AppKit
#endif

public typealias TimelineMediaOpenAction = (UiTimelineV2.Post, any UiMedia, Int) -> Void
public enum TimelineMediaMenuAction {
    case download
    case downloadAll
    case shareImage
    case copyLink
}
public typealias TimelineMediaActionHandler = (UiTimelineV2.Post, any UiMedia, TimelineMediaMenuAction) -> Void

public extension EnvironmentValues {
    @Entry var timelineMediaOpenAction: TimelineMediaOpenAction? = nil
    @Entry var timelineMediaActionHandler: TimelineMediaActionHandler? = nil
}

public struct StatusView: View {
    @Environment(\.timelineAppearance.fullWidthPost) private var fullWidthPost
    @Environment(\.timelineAppearance.showLinkPreview) private var showLinkPreview
    @Environment(\.timelineAppearance.compatLinkPreview) private var compatLinkPreview
    @Environment(\.timelineAppearance.showEmojiReactions) private var showEmojiReactions
    @Environment(\.timelineAppearance.postActionStyle) private var postActionStyle
    @Environment(\.timelineAppearance.showPlatformLogo) private var showPlatformLogo
    @Environment(\.timelineAppearance.expandContentWarning) private var expandContentWarning
    @Environment(\.timelineAppearance.lineLimit) private var appearanceLineLimit
    @Environment(\.timelineAppearance.mediaLayout) private var mediaLayout
    @Environment(\.timelineAppearance.aiConfig.agent) private var agentEnabled
    @Environment(\.timelineGalleryFallback) private var isGalleryFallback
    @Environment(\.translateConfig) private var translateConfig
    @Environment(\.openURL) private var openURL
    @Environment(\.timelineMediaOpenAction) private var timelineMediaOpenAction
    #if os(macOS)
    @ScaledMetric(relativeTo: .body) private var topEndIconSize = NSFont.preferredFont(forTextStyle: .body).pointSize
    #else
    @ScaledMetric(relativeTo: .body) private var topEndIconSize: CGFloat = 17
    #endif
    @ScaledMetric(relativeTo: .body) private var sideAvatarTopInset: CGFloat = 4
    private let data: UiTimelineV2.Post
    private let isDetail: Bool
    private let isQuote: Bool
    private let isClickable: Bool
    private let withLeadingPadding: Bool
    private let showMedia: Bool
    private let showAttachments: Bool
    private let maxLine: Int?
    private let showExpandTextButton: Bool
    private let forceHideActions: Bool
    private let showTranslate: Bool
    private let showParents: Bool
    private let inlineParents: [UiTimelineV2.TimelinePostItem]
    private let quotes: [UiTimelineV2.Post]
    private let allowsMediaCarousel: Bool
    private let carouselOuterHorizontalPadding: CGFloat
    @State private var contentWarningExpanded = false
    @State private var textExpanded = false
    @State private var overflowingTextIndexes: Set<Int> = []

    public init(
        data: UiTimelineV2.Post,
        isDetail: Bool = false,
        isQuote: Bool = false,
        isClickable: Bool = true,
        withLeadingPadding: Bool = false,
        showMedia: Bool = true,
        showAttachments: Bool = true,
        maxLine: Int? = nil,
        showExpandTextButton: Bool = true,
        forceHideActions: Bool = false,
        showTranslate: Bool = true,
        showParents: Bool = true,
        inlineParents: [UiTimelineV2.TimelinePostItem] = [],
        quotes: [UiTimelineV2.Post] = [],
        allowsMediaCarousel: Bool = false,
        carouselOuterHorizontalPadding: CGFloat = 0
    ) {
        self.data = data
        self.isDetail = isDetail
        self.isQuote = isQuote
        self.isClickable = isClickable
        self.withLeadingPadding = withLeadingPadding
        self.showMedia = showMedia
        self.showAttachments = showAttachments
        self.maxLine = maxLine
        self.showExpandTextButton = showExpandTextButton
        self.forceHideActions = forceHideActions
        self.showTranslate = showTranslate
        self.showParents = showParents
        self.inlineParents = inlineParents
        self.quotes = quotes
        self.allowsMediaCarousel = allowsMediaCarousel
        self.carouselOuterHorizontalPadding = carouselOuterHorizontalPadding
    }

    private var usesSideAvatar: Bool {
        (!fullWidthPost || withLeadingPadding) && !isQuote && !isDetail
    }
    public var body: some View {
        let parents = inlineParents
        let user = data.user
        let hasFullWidthHeader = !usesSideAvatar && !isQuote && user != nil
        let carouselEdgePadding: CGFloat = isQuote ? 12 : carouselOuterHorizontalPadding
        let carouselLeadingPadding = carouselEdgePadding + (usesSideAvatar && user != nil ? 56 : 0)
        let replyToHandle = withLeadingPadding || (showParents && !parents.isEmpty) ? nil : data.replyToHandle
        let translationDisplayed = data.translationDisplayState == .translated
        let contentWarnings: [UiRichText] = if let warning = data.contentWarning {
            if translationDisplayed, let translation = warning.translation {
                translateConfig.showOriginalWithTranslation ? [warning.original, translation] : [translation]
            } else {
                [warning.original]
            }
        } else {
            []
        }
        let contentWarningIsEmpty = contentWarnings.allSatisfy(\.isEmpty)
        let contents: [UiRichText] = if translationDisplayed, let translation = data.content.translation {
            translateConfig.showOriginalWithTranslation ? [data.content.original, translation] : [translation]
        } else {
            [data.content.original]
        }
        let poll = data.poll
        let images = Array(data.images)
        let hasImages = !images.isEmpty
        let showsImages = hasImages && showMedia && showAttachments
        let usesSideAvatarCarousel =
            usesSideAvatar &&
            user != nil &&
            showMedia &&
            showAttachments &&
            allowsMediaCarousel &&
            mediaLayout == .carousel &&
            images.count > 1
        let sensitive = data.sensitive
        let card = data.card
        let quoteItems = self.quotes
        let hasQuotes = !quoteItems.isEmpty
        let showsQuotes = hasQuotes && !isQuote
        let showsCard = card != nil && showMedia && !hasImages && !hasQuotes && showLinkPreview
        let showsAttachments = showsImages || showsCard || showsQuotes
        let sourceChannelName = data.sourceChannel?.name
        let emojiReactions = Array(data.emojiReactions)
        let hasEmojiReactions = showEmojiReactions && !emojiReactions.isEmpty
        let showsReactions = showMedia && !isQuote && (sourceChannelName != nil || hasEmojiReactions)
        let visibility = data.visibility
        let translationDisplayState = data.translationDisplayState
        let createdAt = data.createdAt
        let actions = Array(data.actions)
        let accountType = data.accountType
        let statusKey = data.statusKey
        let effectiveLineLimit = max(maxLine ?? Int(appearanceLineLimit), 1)
        let contentLineLimit: Int? = isDetail || textExpanded || effectiveLineLimit == Int(Int32.max) ? nil : effectiveLineLimit
        let collapseThreshold = maxLine == nil ? max(15, effectiveLineLimit) : effectiveLineLimit
        let canExpandLineLimitedContent = contentLineLimit != nil && !isDetail && !textExpanded && showExpandTextButton
        let showsDetailTranslation = isDetail && showTranslate && !data.content.original.isEmpty
        let hasBodyText = contents.contains { !$0.isEmpty }
        let showsPoll = poll != nil && showMedia
        let hasPreMediaText = !contentWarningIsEmpty || hasBodyText ||
            (showsDetailTranslation && !showsPoll)
        let hasBodyAfterReply = !contentWarningIsEmpty || hasBodyText || showsDetailTranslation || showsPoll
        let hasPreMediaBody = replyToHandle != nil || hasBodyAfterReply
        let replyBeforeAttachments = replyToHandle != nil && !isGalleryFallback && !hasBodyAfterReply
        let replyBeforeFooter = replyBeforeAttachments && !showsAttachments
        let replyBeforeTimestampOrActions = replyBeforeFooter && !showsReactions
        let firstAttachmentSpacing: CGFloat = if replyBeforeAttachments {
            0
        } else {
            hasFullWidthHeader && !hasPreMediaBody ? 8 : 10
        }
        let replyTopSpacing: CGFloat = if !isGalleryFallback {
            4
        } else if user == nil {
            0
        } else {
            !hasBodyAfterReply && showsAttachments ? firstAttachmentSpacing : 8
        }
        let replyBottomSpacing: CGFloat = isGalleryFallback ? (hasBodyAfterReply ? 8 : 0) : 4
        let showsActions = (postActionStyle != .hidden || isDetail) && !forceHideActions
        let actionsFollowHeader = hasFullWidthHeader && !isDetail && !hasPreMediaBody && !showsAttachments && !showsReactions
        let actionSpacing: CGFloat = if replyBeforeTimestampOrActions && !isDetail {
            0
        } else {
            actionsFollowHeader ? 8 : 2
        }
        let bodyTopSpacing: CGFloat = if user == nil || replyToHandle != nil {
            0
        } else if hasFullWidthHeader || !hasPreMediaText {
            8
        } else {
            isQuote && !isGalleryFallback ? 4 : 2
        }

        VStack(
            alignment: .leading,
            spacing: 0
        ) {
            if !parents.isEmpty, showParents {
                ForEach(parents, id: \.itemKey) { parent in
                    VStack(
                        spacing: 0
                    ) {
                        StatusView(
                            data: parent.displayPost,
                            withLeadingPadding: true,
                            quotes: Array(parent.presentation.quotes),
                            allowsMediaCarousel: allowsMediaCarousel,
                            carouselOuterHorizontalPadding: carouselOuterHorizontalPadding
                        )
                        Spacer()
                            .frame(height: timelinePostBottomPadding + 14)
                    }
                    .overlay(alignment: .leading) {
                        Rectangle()
                            .fill(Color.flareSeparator)
                            .frame(minWidth: 1, maxWidth: 1, alignment: .leading)
                            .padding(.leading, 24)
                            .padding(.top, 48 + sideAvatarTopInset)
                    }
                }
            }
            HStack(
                alignment: .top,
                spacing: 8,
            ) {
                if usesSideAvatar, let user {
                    AvatarView(data: user.avatar)
                        .frame(width: 48, height: 48)
                        .accessibilityLabel(
                            Text(
                                verbatim: openProfileAccessibilityLabel(handle: user.handle.display)
                            )
                        )
                        .onTapGesture {
                            user.onClicked(ClickContext(launcher: AppleUriLauncher(openUrl: openURL)))
                        }
                        .padding(.top, sideAvatarTopInset)
                }
                VStack(
                    alignment: .leading,
                    spacing: 0,
                ) {
                    if user != nil || hasPreMediaBody {
                        VStack(
                            alignment: .leading,
                            spacing: 0,
                        ) {
                            if let user {
                                if usesSideAvatar {
                                    UserOnelineView(data: user, showAvatar: false, subtitle: data.authorSubtitle) {
                                        topEndContent(
                                            visibility: visibility,
                                            translationDisplayState: translationDisplayState,
                                            platformIcon: data.platformIcon,
                                            platformId: data.platformId,
                                            createdAt: createdAt,
                                            accountType: accountType,
                                            statusKey: statusKey
                                        )
                                    } onClicked: {
                                        user.onClicked(ClickContext(launcher: AppleUriLauncher(openUrl: openURL)))
                                    }
                                } else if isQuote {
                                    UserOnelineView(data: user, showAvatar: true, subtitle: data.authorSubtitle) {
                                        topEndContent(
                                            visibility: visibility,
                                            translationDisplayState: translationDisplayState,
                                            platformIcon: data.platformIcon,
                                            platformId: data.platformId,
                                            createdAt: createdAt,
                                            accountType: accountType,
                                            statusKey: statusKey
                                        )
                                    } onClicked: {
                                        user.onClicked(ClickContext(launcher: AppleUriLauncher(openUrl: openURL)))
                                    }
                                } else {
                                    UserCompatView(data: user, subtitle: data.authorSubtitle) {
                                        topEndContent(
                                            visibility: visibility,
                                            translationDisplayState: translationDisplayState,
                                            platformIcon: data.platformIcon,
                                            platformId: data.platformId,
                                            createdAt: createdAt,
                                            accountType: accountType,
                                            statusKey: statusKey
                                        )
                                    } onClicked: {
                                        user.onClicked(ClickContext(launcher: AppleUriLauncher(openUrl: openURL)))
                                    }
                                }
                            }
                            if let replyToHandle {
                                HStack(spacing: 4) {
                                    Image(fontAwesome: .reply)
                                    Text("Reply to \(replyToHandle)", bundle: FlareAppleUILocalization.bundle)
                                }
                                .flareSecondaryText()
                                .padding(.top, replyTopSpacing)
                                .padding(.bottom, replyBottomSpacing)
                            }
                            if hasBodyAfterReply {
                                VStack(
                                    alignment: .leading,
                                    spacing: 8,
                                ) {
                                    if !contentWarningIsEmpty {
                                        VStack(alignment: .leading, spacing: 8) {
                                            ForEach(Array(contentWarnings.enumerated()), id: \.offset) { _, contentWarning in
                                                if !contentWarning.isEmpty {
                                                    RichText(text: contentWarning)
                                                        .fixedSize(horizontal: false, vertical: true)
                                                        .if(isDetail) { view in
                                                            view.textSelection(.enabled)
                                                        }
                                                    }
                                            }

                                            if !expandContentWarning {
                                                Button {
                                                    withAnimation {
                                                        contentWarningExpanded.toggle()
                                                        if !contentWarningExpanded {
                                                            textExpanded = false
                                                            overflowingTextIndexes.removeAll()
                                                        }
                                                    }
                                                } label: {
                                                    if contentWarningExpanded {
                                                        Text("mastodon_item_show_less", bundle: FlareAppleUILocalization.bundle)
                                                    } else {
                                                        Text("mastodon_item_show_more", bundle: FlareAppleUILocalization.bundle)
                                                    }
                                                }
                                                .backport
                                                .glassProminentButtonStyle()
                                            }
                                        }
                                    }

                                    if (hasBodyText || showsPoll) && (contentWarningExpanded || expandContentWarning || contentWarningIsEmpty) {
                                        VStack(alignment: .leading, spacing: 8) {
                                            ForEach(Array(contents.enumerated()), id: \.offset) { index, content in
                                                if !content.isEmpty {
                                                    CollapsibleRichText(
                                                        text: content,
                                                        lineLimit: contentLineLimit,
                                                        collapseThreshold: collapseThreshold,
                                                        isExpanded: textExpanded,
                                                        isTextSelectionEnabled: isDetail
                                                    ) { overflows in
                                                        if overflows {
                                                            overflowingTextIndexes.insert(index)
                                                        } else {
                                                            overflowingTextIndexes.remove(index)
                                                        }
                                                    }
                                                }
                                            }
                                            if !overflowingTextIndexes.isEmpty, canExpandLineLimitedContent {
                                                Button {
                                                    withAnimation {
                                                        textExpanded = true
                                                    }
                                                } label: {
                                                    Text("mastodon_item_show_more", bundle: FlareAppleUILocalization.bundle)
                                                }
                                                .buttonStyle(.borderless)
                                            }

                                            if let poll, showMedia {
                                                StatusPollView(data: poll)
                                            }
                                        }
                                    }

                                    if showsDetailTranslation {
                                        StatusTranslateView(
                                            content: data.content.original,
                                            contentWarning: data.contentWarning?.original
                                        )
                                    }
                                }
                                .padding(.top, bodyTopSpacing)
                            }
                        }
                        .frame(
                            minHeight: usesSideAvatarCarousel ? 48 + sideAvatarTopInset - firstAttachmentSpacing : nil,
                            alignment: .topLeading
                        )
                    }
                    VStack(
                        alignment: .leading,
                        spacing: 0,
                    ) {
                        if showsImages {
                            StatusMediaContent(
                                post: data,
                                data: images,
                                sensitive: sensitive,
                                cornerRadius: isQuote ? 12 : 16,
                                allowsCarousel: allowsMediaCarousel,
                                carouselLeadingPadding: carouselLeadingPadding,
                                carouselTrailingPadding: carouselEdgePadding
                            ) { media, index in
                                if data.pluginId != nil, data.mediaClickPolicy == .openPostClickEvent {
                                    data.onClicked(ClickContext(launcher: AppleUriLauncher(openUrl: openURL)))
                                } else if let timelineMediaOpenAction {
                                    timelineMediaOpenAction(data, media, index)
                                } else {
                                    let preview: String? = switch onEnum(of: media) {
                                    case .image(let image):
                                        image.previewUrl
                                    case .video(let video):
                                        video.thumbnailUrl
                                    case .ugoira(let animation):
                                        animation.previewUrl
                                    case .gif(let gif):
                                        gif.previewUrl
                                    case .audio:
                                        nil
                                    }
                                    let previewIsImage: Bool
                                    if case .image = onEnum(of: media) {
                                        previewIsImage = true
                                    } else {
                                        previewIsImage = false
                                    }
                                    let route = DeeplinkRoute.MediaStatusMedia(
                                        statusKey: statusKey,
                                        accountType: accountType,
                                        index: Int32(index),
                                        preview: preview,
                                        aspectRatio: Float(media.aspectRatio ?? 0),
                                        previewIsImage: previewIsImage
                                    )
                                    if let url = URL(string: route.toUri()) {
                                        openURL(url)
                                    }
                                }
                            }
                            .padding(.top, firstAttachmentSpacing)
                        }

                        if let card, showsCard {
                            if compatLinkPreview {
                                StatusCompatCardView(data: card, cornerRadius: isQuote ? 12 : 16)
                                    .padding(.top, firstAttachmentSpacing)
                            } else {
                                StatusCardView(data: card, cornerRadius: isQuote ? 12 : 16)
                                    .padding(.top, firstAttachmentSpacing)
                            }
                        }

                        if showsQuotes {
                            VStack(alignment: .leading, spacing: 8) {
                                ForEach(Array(quoteItems.enumerated()), id: \.offset) { index, quote in
                                    StatusView(
                                        data: quote,
                                        isQuote: true,
                                        forceHideActions: true,
                                        allowsMediaCarousel: allowsMediaCarousel
                                    )
                                    if index < quoteItems.count - 1 {
                                        Divider()
                                    }
                                }
                            }
                            .padding(12)
                            .overlay(
                                RoundedRectangle(cornerRadius: 16)
                                    .stroke(Color.flareSeparator, lineWidth: 1)
                            )
                            .padding(.top, showsImages ? 8 : firstAttachmentSpacing)
                        }

                        if showMedia, !isQuote {
                            if sourceChannelName != nil || hasEmojiReactions {
                                VStack(alignment: .leading, spacing: 8) {
                                    if let sourceChannelName {
                                        HStack(spacing: 4) {
                                            Image(fontAwesome: .tv)
                                            Text(sourceChannelName)
                                        }
                                        .flareSecondaryText()
                                    }
                                    if hasEmojiReactions {
                                        StatusReactionView(data: emojiReactions, isDetail: isDetail)
                                    }
                                }
                                .padding(.top, replyBeforeFooter ? 0 : 8)
                            }
                        }

                        if isDetail {
                            DateTimeText(data: createdAt, fullTime: true)
                                .flareSecondaryText()
                                .padding(.top, replyBeforeTimestampOrActions ? 0 : 8)
                        }

                        if showsActions {
                            StatusActionsView(data: actions, useText: false, topPadding: actionSpacing,
                                              isDetail: isDetail, postKey: String(describing: data.statusKey))
                                .padding(.horizontal, -4)
                        }
                    }
                }
                #if os(macOS)
                .contextMenu {
                    StatusActionsView(data: data.actions, useText: true, allowSpacer: false)
                }
                #endif
//                .if(!isDetail) { view in
//                    view
//                        .contextMenu {
//                            StatusActionsView(data: data.actions, useText: true, allowSpacer: false)
//                        }
//                }
            }
        }
        .font(.body)
        .lineSpacing(1)
        .environment(\.isPostContent, true)
        .contentShape(.rect)
        .if(isClickable) { view in
            view.onTapGesture {
                data.onClicked(ClickContext(launcher: AppleUriLauncher(openUrl: openURL)))
            }
        }
        .onChange(of: data.renderHash) { _, _ in
            contentWarningExpanded = false
            textExpanded = false
            overflowingTextIndexes.removeAll()
        }
        .onChange(of: effectiveLineLimit) { _, _ in
            textExpanded = false
            overflowingTextIndexes.removeAll()
        }
        .onChange(of: translateConfig.showOriginalWithTranslation) { _, _ in
            textExpanded = false
            overflowingTextIndexes.removeAll()
        }
    }
    
    private func topEndContent(
        visibility: UiTimelineV2.PostVisibility?,
        translationDisplayState: TranslationDisplayState,
        platformIcon: IconType,
        platformId: String,
        createdAt: UiDateTime,
        accountType: AccountType,
        statusKey: MicroBlogKey
    ) -> some View {
        HStack(spacing: 4) {
            if let visibility {
                StatusVisibilityView(data: visibility)
                    .font(.system(size: 20))
                    .frame(width: 20, height: 20)
                    .scaleEffect(topEndIconSize / 20)
                    .frame(width: topEndIconSize, height: topEndIconSize)
                    .flareSecondaryText()
            }
            if translationDisplayState != .hidden {
                TranslateStatusComponent(data: translationDisplayState, iconSize: topEndIconSize)
                    .font(.system(size: topEndIconSize))
                    .flareSecondaryText()
            }
            if showPlatformLogo {
                TabIcon(icon: platformIcon, size: topEndIconSize)
                    .font(.system(size: topEndIconSize))
                    .flareSecondaryText()
                    .accessibilityLabel(
                        Text(
                            verbatim: String(
                                format: FlareAppleUILocalization.string(
                                    "status_platform",
                                    fallback: "Platform: %@"
                                ),
                                platformId
                            )
                        )
                    )
            }
            if !isDetail {
                DateTimeText(data: createdAt)
                    .flareSecondaryText()
            }
            if agentEnabled, !isQuote {
                Button {
                    let route = DeeplinkRoute.StatusInsight(
                        accountType: accountType,
                        statusKey: statusKey
                    )
                    if let url = URL(string: route.toUri()) {
                        openURL(url)
                    }
                } label: {
                    Image(fontAwesome: .robot)
                        .resizable()
                        .scaledToFit()
                        .frame(width: topEndIconSize, height: topEndIconSize)
                        .frame(width: max(24, topEndIconSize + 4), height: max(24, topEndIconSize + 4))
                        .flareSecondaryText()
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("status_insight_title", bundle: FlareAppleUILocalization.bundle))
            }
        }
    }
}

struct StatusMediaContent: View {
    @Environment(\.timelineAppearance.showMedia) private var showMedia
    @Environment(\.timelineAppearance.showSensitiveContent) private var showSensitiveContent
    @State private var expandMedia = false
    let post: UiTimelineV2.Post
    let data: [any UiMedia]
    let sensitive: Bool
    let cornerRadius: CGFloat
    let allowsCarousel: Bool
    let carouselLeadingPadding: CGFloat
    let carouselTrailingPadding: CGFloat
    let onMediaClicked: (any UiMedia, Int) -> Void
    var body: some View {
        if showMedia || expandMedia {
            StatusMediaView(
                post: post,
                data: data,
                sensitive: !(showSensitiveContent) && sensitive,
                cornerRadius: cornerRadius,
                allowsCarousel: allowsCarousel,
                carouselLeadingPadding: carouselLeadingPadding,
                carouselTrailingPadding: carouselTrailingPadding,
                onMediaClicked: onMediaClicked
            )
        } else {
            Button {
                withAnimation {
                    expandMedia = true
                }
            } label: {
                Label {
                    Text(
                        "appearance_show_media",
                        bundle: FlareAppleUILocalization.bundle,
                        comment: "Button to show media attachments"
                    )
                } icon: {
                    Image(fontAwesome: .image)
                }
            }
            .backport
            .glassButtonStyle(fallbackStyle: .bordered)
        }
    }
}

//extension StatusView {
//    init(
//        data: UiTimeline.ItemContentStatus, 
//        isDetail: Bool = false, 
//        isQuote: Bool = false,
//        withLeadingPadding: Bool = false,
//        showMedia: Bool = true,
//        maxLine: Int = 5, 
//        showExpandTextButton: Bool = true, 
//        forceHideActions: Bool = false
//    ) {
//        self.data = data
//        self.isDetail = isDetail
//        self.isQuote = isQuote
//        self.withLeadingPadding = withLeadingPadding
//        self.showMedia = showMedia
//        self.maxLine = maxLine
//        self.showExpandTextButton = showExpandTextButton
//        self.forceHideActions = forceHideActions
//    }
//}
