import UIKit
import SwiftUI
import KotlinSharedUI
import FlareAppleCore
import FlareAppleUI

/// UIKit port of the SwiftUI `StatusView`.
///
/// Layout is fully manual (frame-based) for performance — no UIStackView or
/// Auto Layout constraints. The view hierarchy is flat: all visible children
/// are direct subviews of this view, positioned in `layoutSubviews()`.
///
/// `rebuild()` determines which children should be visible and updates the
/// managed arrays via `syncManagedSubviews`. `layoutSubviews()` then assigns
/// frames based on the current `bounds.width`.
final class StatusUIKitView: UIView, UIGestureRecognizerDelegate, ManualLayoutMeasurable, TimelineHeightProviding {
    // MARK: - Environment mirrors (inputs that were `@Environment` in SwiftUI)

    /// Forwarded to all URL-clicking machinery.
    var openURL: ((URL) -> Void)?
    var onLocalHeightInvalidated: (() -> Void)?

    // MARK: - SwiftUI `var` parameters

    private var data: UiTimelineV2.Post?
    private var boundStatusKey: MicroBlogKey?
    private var isDetail: Bool = false
    private var isQuote: Bool = false
    private var withLeadingPadding: Bool = false
    private var showMediaInput: Bool = true
    private var maxLine: Int = 5
    private var showExpandTextButton: Bool = true
    private var forceHideActions: Bool = false
    private var showTranslate: Bool = true
    private var aiTldrEnabled: Bool = false
    private var showParents: Bool = true
    private var inlineParents: [UiTimelineV2.TimelinePostItem] = []
    private var quotes: [UiTimelineV2.Post] = []
    private var allowsMediaCarousel: Bool = false
    private var carouselOuterHorizontalPadding: CGFloat = 0
    private var appearance = StatusUIKitAppearance(timeline: TimelineAppearance.companion.Default)
    private var lastConfigureSignature: ConfigureSignature?
    private var lastPreparedFittingWidthKey: Int?

    private struct ConfigureSignature: Equatable {
        let statusKey: MicroBlogKey
        let renderHash: Int32
        let isDetail: Bool
        let isQuote: Bool
        let withLeadingPadding: Bool
        let showMedia: Bool
        let maxLine: Int
        let showExpandTextButton: Bool
        let forceHideActions: Bool
        let showTranslate: Bool
        let aiTldrEnabled: Bool
        let showParents: Bool
        let parentKeys: [MicroBlogKey]
        let parentRenderHashes: [Int32]
        let quoteKeys: [MicroBlogKey]
        let quoteRenderHashes: [Int32]
        let allowsMediaCarousel: Bool
        let carouselOuterHorizontalPadding: CGFloat
        let appearance: StatusUIKitAppearance

        init(
            data: UiTimelineV2.Post,
            inlineParents: [UiTimelineV2.TimelinePostItem],
            quotes: [UiTimelineV2.Post],
            appearance: StatusUIKitAppearance,
            isDetail: Bool,
            isQuote: Bool,
            withLeadingPadding: Bool,
            showMedia: Bool,
            maxLine: Int,
            showExpandTextButton: Bool,
            forceHideActions: Bool,
            showTranslate: Bool,
            aiTldrEnabled: Bool,
            showParents: Bool,
            allowsMediaCarousel: Bool,
            carouselOuterHorizontalPadding: CGFloat
        ) {
            statusKey = data.statusKey
            renderHash = data.renderHash
            self.isDetail = isDetail
            self.isQuote = isQuote
            self.withLeadingPadding = withLeadingPadding
            self.showMedia = showMedia
            self.maxLine = maxLine
            self.showExpandTextButton = showExpandTextButton
            self.forceHideActions = forceHideActions
            self.showTranslate = showTranslate
            self.aiTldrEnabled = aiTldrEnabled
            self.showParents = showParents
            self.parentKeys = inlineParents.map(\.statusKey)
            self.parentRenderHashes = inlineParents.map { $0.renderHash }
            self.quoteKeys = quotes.map(\.statusKey)
            self.quoteRenderHashes = quotes.map { $0.renderHash }
            self.allowsMediaCarousel = allowsMediaCarousel
            self.carouselOuterHorizontalPadding = carouselOuterHorizontalPadding
            self.appearance = appearance
        }
    }

    // MARK: - @State

    private var expand: Bool = false

    // MARK: - Managed child arrays (replaces UIStackViews)

    /// Parents displayed before the main row (vertical, spacing 0)
    private var activeParents: [UIView] = []
    /// The current header view (one of userHeaderFull/userHeaderQuote/userHeaderCompat), or nil
    private var currentHeaderView: UIView?
    /// Content column children; layout applies text, attachment, and action gaps.
    private var contentColumnChildren: [UIView] = []
    /// Whether avatar is currently displayed
    private var wantsAvatar: Bool = false

    // MARK: - Layout constants

    private static let mainRowSpacing: CGFloat = 8
    private static let avatarSize: CGFloat = 48
    private static let replyIconSize: CGFloat = 12
    private static let sourceIconSize: CGFloat = 12

    // MARK: - Cached leaves (created on first use, reconfigured per `rebuild`)

    private var avatarViewStorage: AvatarUIView?
    private var userHeaderFullStorage: UserOnelineUIView?
    private var userHeaderQuoteStorage: UserOnelineUIView?
    private var userHeaderCompatStorage: UserCompatUIView?
    private var topEndViewStorage: StatusTopEndView?

    private var replyToContainerStorage: ReplyToRowView?

    private var contentWarningTextStorage: RichTextUIView?
    private var contentWarningTranslationTextStorage: RichTextUIView?
    private var contentWarningToggleStorage: UIButton?

    private var bodyTextStorage: RichTextUIView?
    private var bodyTranslationTextStorage: RichTextUIView?
    private var expandMoreButtonStorage: UIButton?

    private var translateViewStorage: StatusTranslateUIView?
    private var pollViewStorage: StatusPollUIView?
    private var mediaViewStorage: StatusMediaContentUIView?
    private var normalCardViewStorage: StatusCardUIView?
    private var compatCardViewStorage: StatusCompatCardUIView?

    private var quotesContainerStorage: QuotesContainerView?
    private var quoteChildren: [StatusUIKitView] = []
    private var quoteDividers: [UIView] = []

    private var sourceChannelContainerStorage: SourceChannelRowView?

    private var reactionViewStorage: StatusReactionUIView?
    private var detailTimestampViewStorage: DateTimeUILabel?

    private var actionsViewStorage: StatusActionsUIView?
    private var actionsContainerStorage: ActionsContainerView?

    private var parentContainers: [ParentContainerView] = []

    // MARK: - Init

    override init(frame: CGRect) {
        super.init(frame: frame)
        commonInit()
    }
    required init?(coder: NSCoder) {
        super.init(coder: coder)
        commonInit()
    }

    private func commonInit() {
        let tap = UITapGestureRecognizer(target: self, action: #selector(onRootTapped))
        tap.cancelsTouchesInView = false
        tap.delegate = self
        addGestureRecognizer(tap)
    }

    private func setupAvatar(_ avatarView: AvatarUIView) {
        guard avatarView.gestureRecognizers?.isEmpty ?? true else { return }
        avatarView.isUserInteractionEnabled = true
        avatarView.isAccessibilityElement = true
        let tap = UITapGestureRecognizer(target: self, action: #selector(onAvatarTapped))
        avatarView.addGestureRecognizer(tap)
    }

    private func setupContentWarningToggle(_ contentWarningToggle: UIButton) {
        guard contentWarningToggle.actions(forTarget: nil, forControlEvent: .touchUpInside)?.isEmpty ?? true else { return }
        contentWarningToggle.contentHorizontalAlignment = .leading
        contentWarningToggle.addAction(
            UIAction { [weak self] _ in
                guard let self else { return }
                self.setExpanded(!self.expand)
            },
            for: .touchUpInside
        )
    }

    private func setupExpandMoreButton(_ expandMoreButton: UIButton) {
        guard expandMoreButton.actions(forTarget: nil, forControlEvent: .touchUpInside)?.isEmpty ?? true else { return }
        expandMoreButton.contentHorizontalAlignment = .leading
        expandMoreButton.setTitle(String(localized: "mastodon_item_show_more"), for: .normal)
        expandMoreButton.addAction(
            UIAction { [weak self] _ in self?.setExpanded(true) },
            for: .touchUpInside
        )
    }

    private func resolvedAvatarView() -> AvatarUIView {
        if let avatarViewStorage { return avatarViewStorage }
        let view = AvatarUIView()
        setupAvatar(view)
        avatarViewStorage = view
        return view
    }

    private func resolvedUserHeaderFull() -> UserOnelineUIView {
        if let userHeaderFullStorage { return userHeaderFullStorage }
        let view = UserOnelineUIView(showAvatar: false)
        userHeaderFullStorage = view
        return view
    }

    private func resolvedUserHeaderQuote() -> UserOnelineUIView {
        if let userHeaderQuoteStorage { return userHeaderQuoteStorage }
        let view = UserOnelineUIView(showAvatar: true)
        userHeaderQuoteStorage = view
        return view
    }

    private func resolvedUserHeaderCompat() -> UserCompatUIView {
        if let userHeaderCompatStorage { return userHeaderCompatStorage }
        let view = UserCompatUIView()
        userHeaderCompatStorage = view
        return view
    }

    private func resolvedTopEndView() -> StatusTopEndView {
        if let topEndViewStorage { return topEndViewStorage }
        let view = StatusTopEndView()
        topEndViewStorage = view
        return view
    }

    private func resolvedReplyToContainer() -> ReplyToRowView {
        if let replyToContainerStorage { return replyToContainerStorage }
        let view = ReplyToRowView()
        replyToContainerStorage = view
        return view
    }

    private func resolvedContentWarningText() -> RichTextUIView {
        if let contentWarningTextStorage { return contentWarningTextStorage }
        let view = RichTextUIView()
        contentWarningTextStorage = view
        return view
    }

    private func resolvedContentWarningTranslationText() -> RichTextUIView {
        if let contentWarningTranslationTextStorage { return contentWarningTranslationTextStorage }
        let view = RichTextUIView()
        contentWarningTranslationTextStorage = view
        return view
    }

    private func resolvedContentWarningToggle() -> UIButton {
        if let contentWarningToggleStorage { return contentWarningToggleStorage }
        let button = UIButton(type: .system)
        setupContentWarningToggle(button)
        contentWarningToggleStorage = button
        return button
    }

    private func resolvedBodyText() -> RichTextUIView {
        if let bodyTextStorage { return bodyTextStorage }
        let view = RichTextUIView()
        bodyTextStorage = view
        return view
    }

    private func resolvedBodyTranslationText() -> RichTextUIView {
        if let bodyTranslationTextStorage { return bodyTranslationTextStorage }
        let view = RichTextUIView()
        bodyTranslationTextStorage = view
        return view
    }

    private func resolvedExpandMoreButton() -> UIButton {
        if let expandMoreButtonStorage { return expandMoreButtonStorage }
        let button = UIButton(type: .system)
        setupExpandMoreButton(button)
        expandMoreButtonStorage = button
        return button
    }

    private func resolvedTranslateView() -> StatusTranslateUIView {
        if let translateViewStorage { return translateViewStorage }
        let view = StatusTranslateUIView()
        translateViewStorage = view
        return view
    }

    private func resolvedPollView() -> StatusPollUIView {
        if let pollViewStorage { return pollViewStorage }
        let view = StatusPollUIView()
        pollViewStorage = view
        return view
    }

    private func resolvedMediaView() -> StatusMediaContentUIView {
        if let mediaViewStorage { return mediaViewStorage }
        let view = StatusMediaContentUIView()
        mediaViewStorage = view
        return view
    }

    private func resolvedNormalCardView(cornerRadius: CGFloat) -> StatusCardUIView {
        if let normalCardViewStorage { return normalCardViewStorage }
        let view = StatusCardUIView(cornerRadius: cornerRadius)
        normalCardViewStorage = view
        return view
    }

    private func resolvedCompatCardView(cornerRadius: CGFloat) -> StatusCompatCardUIView {
        if let compatCardViewStorage { return compatCardViewStorage }
        let view = StatusCompatCardUIView(cornerRadius: cornerRadius)
        compatCardViewStorage = view
        return view
    }

    private func resolvedQuotesContainer() -> QuotesContainerView {
        if let quotesContainerStorage { return quotesContainerStorage }
        let view = QuotesContainerView()
        quotesContainerStorage = view
        return view
    }

    private func resolvedSourceChannelContainer() -> SourceChannelRowView {
        if let sourceChannelContainerStorage { return sourceChannelContainerStorage }
        let view = SourceChannelRowView()
        sourceChannelContainerStorage = view
        return view
    }

    private func resolvedReactionView() -> StatusReactionUIView {
        if let reactionViewStorage { return reactionViewStorage }
        let view = StatusReactionUIView()
        view.onLocalHeightInvalidated = { [weak self] in
            self?.notifyLocalHeightInvalidated()
        }
        reactionViewStorage = view
        return view
    }

    private func resolvedDetailTimestampView() -> DateTimeUILabel {
        if let detailTimestampViewStorage { return detailTimestampViewStorage }
        let view = DateTimeUILabel()
        view.fullTime = true
        detailTimestampViewStorage = view
        return view
    }

    private func resolvedActionsView() -> StatusActionsUIView {
        if let actionsViewStorage { return actionsViewStorage }
        let view = StatusActionsUIView()
        actionsViewStorage = view
        return view
    }

    private func resolvedActionsContainer() -> ActionsContainerView {
        if let actionsContainerStorage { return actionsContainerStorage }
        let container = ActionsContainerView()
        container.content = resolvedActionsView()
        actionsContainerStorage = container
        return container
    }

    // MARK: - Public API

    func configure(
        data: UiTimelineV2.Post,
        appearance: StatusUIKitAppearance,
        isDetail: Bool = false,
        isQuote: Bool = false,
        withLeadingPadding: Bool = false,
        showMedia: Bool = true,
        maxLine: Int = 5,
        showExpandTextButton: Bool = true,
        forceHideActions: Bool = false,
        showTranslate: Bool = true,
        aiTldrEnabled: Bool = false,
        showParents: Bool = true,
        inlineParents: [UiTimelineV2.TimelinePostItem] = [],
        quotes: [UiTimelineV2.Post] = [],
        allowsMediaCarousel: Bool = false,
        carouselOuterHorizontalPadding: CGFloat = 0
    ) {
        let newStatusKey = data.statusKey
        if boundStatusKey != newStatusKey {
            expand = false
            reactionViewStorage?.resetExpansion()
            boundStatusKey = newStatusKey
        }
        let signature = ConfigureSignature(
            data: data,
            inlineParents: inlineParents,
            quotes: quotes,
            appearance: appearance,
            isDetail: isDetail,
            isQuote: isQuote,
            withLeadingPadding: withLeadingPadding,
            showMedia: showMedia,
            maxLine: maxLine,
            showExpandTextButton: showExpandTextButton,
            forceHideActions: forceHideActions,
            showTranslate: showTranslate,
            aiTldrEnabled: aiTldrEnabled,
            showParents: showParents,
            allowsMediaCarousel: allowsMediaCarousel,
            carouselOuterHorizontalPadding: carouselOuterHorizontalPadding
        )
        let signatureUnchanged = lastConfigureSignature == signature
        self.data = data
        if signatureUnchanged {
            forwardOpenURL()
            return
        }
        self.isDetail = isDetail
        self.isQuote = isQuote
        self.withLeadingPadding = withLeadingPadding
        self.showMediaInput = showMedia
        self.maxLine = maxLine
        self.showExpandTextButton = showExpandTextButton
        self.forceHideActions = forceHideActions
        self.showTranslate = showTranslate
        self.aiTldrEnabled = aiTldrEnabled
        self.showParents = showParents
        self.inlineParents = inlineParents
        self.quotes = quotes
        self.allowsMediaCarousel = allowsMediaCarousel
        self.carouselOuterHorizontalPadding = carouselOuterHorizontalPadding
        self.appearance = appearance
        lastConfigureSignature = signature
        lastPreparedFittingWidthKey = nil
        rebuild()
    }

    func prepareForPoolRemoval() {
        data = nil
        lastConfigureSignature = nil
        lastPreparedFittingWidthKey = nil
        boundStatusKey = nil
        expand = false
        inlineParents = []
        quotes = []

        mediaViewStorage?.prepareForPoolRemoval()
        actionsViewStorage?.prepareForPoolRemoval()
        translateViewStorage?.prepareForPoolRemoval()
        reactionViewStorage?.resetExpansion()

        // Remove all managed children
        for view in contentColumnChildren { view.removeFromSuperview() }
        contentColumnChildren = []
        currentHeaderView?.removeFromSuperview()
        currentHeaderView = nil
        avatarViewStorage?.removeFromSuperview()
        avatarViewStorage?.accessibilityLabel = nil
        wantsAvatar = false

        for parent in activeParents { parent.removeFromSuperview() }
        activeParents = []

        for container in parentContainers {
            container.child.prepareForPoolRemoval()
            container.removeFromSuperview()
        }
        parentContainers.removeAll()

        for child in quoteChildren {
            child.prepareForPoolRemoval()
            child.removeFromSuperview()
        }
        quoteChildren.removeAll()

        for divider in quoteDividers {
            divider.removeFromSuperview()
        }
        quoteDividers.removeAll()

        invalidateIntrinsicContentSize()
        setNeedsLayout()
    }

    func prepareForFitting(width: CGFloat) {
        guard width.isFinite, width > 0 else { return }
        updateSingleMediaHeightLimit()
        let widthKey = Self.fittingWidthKey(width)
        guard lastPreparedFittingWidthKey != widthKey else { return }
        lastPreparedFittingWidthKey = widthKey
        bounds = CGRect(x: bounds.minX, y: bounds.minY, width: width, height: bounds.height)

        let contentWidth = fittingContentColumnWidth(for: width)
        contentWarningTextStorage?.prepareForFitting(width: contentWidth)
        contentWarningTranslationTextStorage?.prepareForFitting(width: contentWidth)
        bodyTextStorage?.prepareForFitting(width: contentWidth)
        bodyTranslationTextStorage?.prepareForFitting(width: contentWidth)
        if let mediaViewStorage {
            let mediaWidth = mediaViewStorage.isShowingCarousel
                ? width + carouselEdgePadding * 2
                : mediaViewStorage.fittingWidth(for: contentWidth)
            mediaViewStorage.bounds = CGRect(x: 0, y: 0, width: mediaWidth, height: mediaViewStorage.bounds.height)
        }
        if let normalCardViewStorage {
            normalCardViewStorage.bounds = CGRect(x: 0, y: 0, width: contentWidth, height: normalCardViewStorage.bounds.height)
        }
        if let compatCardViewStorage {
            compatCardViewStorage.bounds = CGRect(x: 0, y: 0, width: contentWidth, height: compatCardViewStorage.bounds.height)
        }

        for container in parentContainers where container.superview != nil {
            container.prepareForFitting(width: width)
        }

        let quoteWidth = max(contentWidth - 24, 1)
        if let quotesContainerStorage {
            quotesContainerStorage.bounds = CGRect(x: 0, y: 0, width: contentWidth, height: quotesContainerStorage.bounds.height)
        }
        for child in quoteChildren where child.superview != nil {
            child.prepareForFitting(width: quoteWidth)
        }

        invalidateIntrinsicContentSize()
        setNeedsLayout()
    }

    private static func fittingWidthKey(_ width: CGFloat) -> Int {
        Int((width * UIScreen.main.scale).rounded(.toNearestOrAwayFromZero))
    }

    // MARK: - Layout

    override func layoutSubviews() {
        super.layoutSubviews()
        performLayout(width: bounds.width, assignFrames: true)
    }

    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        hitTestActions(at: point, with: event) ?? super.hitTest(point, with: event)
    }

    func hitTestActions(at point: CGPoint, with event: UIEvent?) -> UIView? {
        guard isUserInteractionEnabled, !isHidden, alpha > 0.01 else { return nil }
        // Action slots extend 4pt into the post padding and must remain tappable there.
        if let container = actionsContainerStorage, container.superview === self,
           !container.isHidden, container.alpha > 0.01, container.isUserInteractionEnabled,
           let actions = actionsViewStorage,
           let control = actions.hitTest(convert(point, to: actions), with: event) as? UIControl {
            return control
        }
        for case let parent as ParentContainerView in activeParents.reversed()
            where !parent.isHidden && parent.alpha > 0.01 && parent.isUserInteractionEnabled {
            if let control = parent.child.hitTestActions(at: convert(point, to: parent.child), with: event) {
                return control
            }
        }
        return nil
    }

    override func sizeThatFits(_ size: CGSize) -> CGSize {
        CGSize(width: size.width, height: timelineHeight(for: size.width) ?? 0)
    }

    func timelineHeight(for width: CGFloat) -> CGFloat? {
        guard width > 0, width.isFinite else { return nil }
        return ceil(performLayout(width: width, assignFrames: false))
    }

    override func systemLayoutSizeFitting(
        _ targetSize: CGSize,
        withHorizontalFittingPriority horizontalFittingPriority: UILayoutPriority,
        verticalFittingPriority: UILayoutPriority
    ) -> CGSize {
        let w: CGFloat
        if horizontalFittingPriority == .required, targetSize.width.isFinite, targetSize.width > 0 {
            w = targetSize.width
        } else if bounds.width > 0 {
            w = bounds.width
        } else {
            return super.systemLayoutSizeFitting(
                targetSize,
                withHorizontalFittingPriority: horizontalFittingPriority,
                verticalFittingPriority: verticalFittingPriority
            )
        }
        prepareForFitting(width: w)
        let h = performLayout(width: w, assignFrames: false)
        return CGSize(width: w, height: ceil(h))
    }

    override var intrinsicContentSize: CGSize {
        guard bounds.width > 0 else { return CGSize(width: UIView.noIntrinsicMetric, height: UIView.noIntrinsicMetric) }
        return sizeThatFits(CGSize(width: bounds.width, height: .greatestFiniteMagnitude))
    }

    /// Core layout routine. When `assignFrames` is true, sets child frames.
    /// Always returns the total height.
    @discardableResult
    private func performLayout(width: CGFloat, assignFrames: Bool) -> CGFloat {
        guard width > 0 else { return 0 }
        updateSingleMediaHeightLimit()
        var y: CGFloat = 0

        // Parents (vertical, spacing 0)
        for parent in activeParents {
            let h = childHeight(of: parent, for: width)
            if assignFrames { parent.frame = CGRect(x: 0, y: y, width: width, height: h) }
            y += h // spacing 0
        }

        // Main row
        let contentX: CGFloat = wantsAvatar ? Self.avatarSize + Self.mainRowSpacing : 0
        let contentWidth = max(width - contentX, 1)

        let avatarBottom = y + (sideAvatarBottom ?? 0)
        if wantsAvatar, let avatarView = avatarViewStorage, assignFrames {
            avatarView.frame = CGRect(x: 0, y: avatarBottom - Self.avatarSize, width: Self.avatarSize, height: Self.avatarSize)
        }

        var innerY = y

        // Header
        if let header = currentHeaderView {
            let h = childHeight(of: header, for: contentWidth)
            if assignFrames { header.frame = CGRect(x: contentX, y: innerY, width: contentWidth, height: h) }
            innerY += h
        }

        updateExpandMoreButtonVisibility(contentWidth: contentWidth)
        var previousChild = currentHeaderView
        for child in contentColumnChildren where !child.isHidden {
            if let previousChild {
                innerY += contentSpacing(before: child, after: previousChild)
            }
            let isCarousel = child === mediaViewStorage && mediaViewStorage?.isShowingCarousel == true
            if isCarousel, wantsAvatar {
                innerY = max(innerY, avatarBottom)
            }
            let childWidth = isCarousel ? width + carouselEdgePadding * 2
                : child === mediaViewStorage ? mediaViewStorage!.fittingWidth(for: contentWidth) : contentWidth
            let childX = isCarousel ? -carouselEdgePadding : contentX
            let h = childHeight(of: child, for: childWidth)
            if assignFrames { child.frame = CGRect(x: childX, y: innerY, width: childWidth, height: h) }
            innerY += h
            previousChild = child
        }

        return max(innerY, avatarBottom)
    }

    private func contentSpacing(before child: UIView, after previousChild: UIView) -> CGFloat {
        if child === replyToContainerStorage || previousChild === replyToContainerStorage { return 0 }
        let followsHeader = previousChild === currentHeaderView
        let isText = child is RichTextUIView || child === translateViewStorage
        let isAttachment = child === mediaViewStorage || child === normalCardViewStorage ||
            child === compatCardViewStorage || child === quotesContainerStorage
        if followsHeader && !usesSideAvatar && !isQuote { return 8 }
        if followsHeader && isText { return isQuote ? 4 : 2 }
        if child === actionsContainerStorage { return 2 }
        if isAttachment && !(child === quotesContainerStorage && previousChild === mediaViewStorage) { return 10 }
        return 8
    }

    private func updateExpandMoreButtonVisibility(contentWidth: CGFloat) {
        guard let button = expandMoreButtonStorage,
              contentColumnChildren.contains(where: { $0 === button }) else {
            return
        }
        let bodyViews = [bodyTextStorage, bodyTranslationTextStorage].compactMap { $0 }
        button.isHidden = !bodyViews.contains { bodyView in
            contentColumnChildren.contains(where: { $0 === bodyView }) &&
                bodyView.hasCollapsedOverflow(for: contentWidth)
        }
    }

    // MARK: - SwiftUI-equivalent computed

    private var postBodyFont: UIFont {
        .preferredFont(forTextStyle: .body, compatibleWith: UITraitCollection(
            preferredContentSizeCategory: appearance.preferredContentSizeCategory
        ))
    }

    fileprivate var sideAvatarBottom: CGFloat? {
        guard wantsAvatar else { return nil }
        let font = postBodyFont
        return Self.avatarSize + ceil(font.ascender - font.capHeight)
    }

    private var usesSideAvatar: Bool {
        (!appearance.fullWidthPost || withLeadingPadding) && !isQuote && !isDetail
    }

    private var carouselEdgePadding: CGFloat {
        isQuote ? 12 : carouselOuterHorizontalPadding
    }

    private var showsReplyContext: Bool {
        data?.replyToHandle != nil && !withLeadingPadding && (!showParents || inlineParents.isEmpty)
    }

    private func updateSingleMediaHeightLimit() {
        guard let mediaViewStorage else { return }
        let maximumHeight: CGFloat? = appearance.expandMediaSize && appearance.limitSingleMediaHeight
            ? appearance.singleMediaMaxHeight ?? CGFloat(TimelineSingleMediaLayout.shared.maximumHeight(
            viewportHeight: Float(window.map { $0.bounds.inset(by: $0.safeAreaInsets).height } ?? 0)
        )) : nil
        if mediaViewStorage.singleMediaMaxHeight != maximumHeight {
            mediaViewStorage.singleMediaMaxHeight = maximumHeight
            lastPreparedFittingWidthKey = nil
        }
    }

    private func fittingContentColumnWidth(for width: CGFloat) -> CGFloat {
        let wantsAvatar = usesSideAvatar && data?.user != nil
        let avatarWidth = wantsAvatar ? Self.avatarSize + Self.mainRowSpacing : 0
        return max(width - avatarWidth, 1)
    }

    // MARK: - Rebuild (incremental diff)

    private func rebuild() {
        guard let data = data else {
            removeAllManaged()
            return
        }

        // Avatar
        let newWantsAvatar = usesSideAvatar && data.user != nil
        if newWantsAvatar, let user = data.user {
            let avatarView = resolvedAvatarView()
            avatarView.avatarShape = appearance.avatarShape
            avatarView.set(media: user.avatar)
            avatarView.accessibilityLabel = openProfileAccessibilityLabel(handle: user.handle.display)
            if avatarView.superview !== self { addSubview(avatarView) }
        } else if avatarViewStorage?.superview === self {
            avatarViewStorage?.removeFromSuperview()
        }
        wantsAvatar = newWantsAvatar

        // Header
        let newHeader = selectAndConfigureHeader(data: data)
        if currentHeaderView !== newHeader {
            currentHeaderView?.removeFromSuperview()
            currentHeaderView = newHeader
            if let h = newHeader, h.superview !== self { addSubview(h) }
        }

        // Content column children
        let contentItems = buildContentColumnList(data: data)
        syncManagedSubviews(parent: self, current: &contentColumnChildren, desired: contentItems)

        // Parents
        let parents = resolveActiveParentContainers(data: data)
        syncManagedSubviews(parent: self, current: &activeParents, desired: parents)

        lastPreparedFittingWidthKey = nil
        invalidateIntrinsicContentSize()
        setNeedsLayout()
    }

    private func removeAllManaged() {
        for view in contentColumnChildren { view.removeFromSuperview() }
        contentColumnChildren = []
        currentHeaderView?.removeFromSuperview()
        currentHeaderView = nil
        avatarViewStorage?.removeFromSuperview()
        wantsAvatar = false
        for parent in activeParents { parent.removeFromSuperview() }
        activeParents = []
        lastPreparedFittingWidthKey = nil
        invalidateIntrinsicContentSize()
        setNeedsLayout()
    }

    private func selectAndConfigureHeader(data: UiTimelineV2.Post) -> UIView? {
        guard let user = data.user else { return nil }
        let topEndView = resolvedTopEndView()
        topEndView.configure(
            post: data,
            showPlatformLogo: appearance.showPlatformLogo,
            absoluteTimestamp: appearance.absoluteTimestamp,
            isDetail: isDetail,
            showAgentInsight: appearance.aiAgentEnabled && !isQuote,
            onInsightTapped: { [weak self] in
                guard let self else { return }
                let route = DeeplinkRoute.StatusInsight(
                    accountType: data.accountType,
                    statusKey: data.statusKey
                )
                if let url = URL(string: route.toUri()) {
                    self.openURL?(url)
                }
            },
            preferredContentSizeCategory: appearance.preferredContentSizeCategory
        )
        let onClicked: () -> Void = { [weak self] in self?.onUserTapped() }

        if usesSideAvatar {
            let userHeaderFull = resolvedUserHeaderFull()
            userHeaderFull.avatar.avatarShape = appearance.avatarShape
            userHeaderFull.configure(data: user, subtitle: data.authorSubtitle, trailing: topEndView, onClicked: onClicked,
                                     preferredContentSizeCategory: appearance.preferredContentSizeCategory)
            return userHeaderFull
        } else if isQuote {
            let userHeaderQuote = resolvedUserHeaderQuote()
            userHeaderQuote.avatar.avatarShape = appearance.avatarShape
            userHeaderQuote.configure(data: user, subtitle: data.authorSubtitle, trailing: topEndView, onClicked: onClicked,
                                      preferredContentSizeCategory: appearance.preferredContentSizeCategory)
            return userHeaderQuote
        } else {
            let userHeaderCompat = resolvedUserHeaderCompat()
            userHeaderCompat.avatar.avatarShape = appearance.avatarShape
            userHeaderCompat.configure(data: user, subtitle: data.authorSubtitle, trailing: topEndView, onClicked: onClicked,
                                       postTextContentSizeCategory: appearance.preferredContentSizeCategory)
            return userHeaderCompat
        }
    }

    private func buildContentColumnList(data: UiTimelineV2.Post) -> [UIView] {
        var items: [UIView] = []
        let translationDisplayed = data.translationDisplayState == .translated
        let contentWarnings: [StatusTextContent<UiRichText>] = if let warning = data.contentWarning {
            StatusTextContent.visible(
                original: warning.original,
                translation: warning.translation,
                translationDisplayed: translationDisplayed,
                showOriginalWithTranslation: appearance.showOriginalWithTranslation
            )
        } else {
            []
        }
        let contents = StatusTextContent.visible(
            original: data.content.original,
            translation: data.content.translation,
            translationDisplayed: translationDisplayed,
            showOriginalWithTranslation: appearance.showOriginalWithTranslation
        )
        let hasCW = contentWarnings.contains { !$0.text.isEmpty }

        // reply-to
        if showsReplyContext, let replyToHandle = data.replyToHandle {
            let replyToContainer = resolvedReplyToContainer()
            replyToContainer.configure(text: String(localized: "Reply to \(replyToHandle)"),
                                       preferredContentSizeCategory: appearance.preferredContentSizeCategory)
            items.append(replyToContainer)
        }

        // content warning
        if hasCW {
            for contentWarning in contentWarnings where !contentWarning.text.isEmpty {
                let contentWarningText = contentWarning.cacheKeyOffset == 0
                    ? resolvedContentWarningText()
                    : resolvedContentWarningTranslationText()
                contentWarningText.configure(
                    text: contentWarning.text,
                    lineLimit: nil,
                    isTextSelectionEnabled: isDetail,
                    onOpenURL: openURL,
                    lineSpacing: 1,
                    baseTextColor: .flarePostLabel,
                    preferredContentSizeCategory: appearance.preferredContentSizeCategory
                )
                items.append(contentWarningText)
            }
            if !appearance.expandContentWarning {
                let contentWarningToggle = resolvedContentWarningToggle()
                contentWarningToggle.titleLabel?.font = postBodyFont
                contentWarningToggle.setTitle(
                    expand
                        ? String(localized: "mastodon_item_show_less")
                        : String(localized: "mastodon_item_show_more"),
                    for: .normal
                )
                items.append(contentWarningToggle)
            }
        }

        // main body
        if expand || appearance.expandContentWarning || !hasCW {
            let bodyLineLimit: Int?
            let collapseAboveLineCount: Int?
            let bodySelectionEnabled: Bool
            if isDetail {
                bodySelectionEnabled = true
                bodyLineLimit = nil
                collapseAboveLineCount = nil
            } else if expand {
                bodySelectionEnabled = false
                bodyLineLimit = nil
                collapseAboveLineCount = nil
            } else {
                bodySelectionEnabled = false
                bodyLineLimit = max(maxLine, 1)
                collapseAboveLineCount = maxLine >= 5 ? max(15, maxLine) : max(maxLine, 1)
            }
            for content in contents where !content.text.isEmpty {
                let bodyText = content.cacheKeyOffset == 0 ? resolvedBodyText() : resolvedBodyTranslationText()
                bodyText.clipsToVisibleHeight = expand
                bodyText.configure(
                    text: content.text,
                    lineLimit: bodyLineLimit,
                    isTextSelectionEnabled: bodySelectionEnabled,
                    onOpenURL: openURL,
                    lineSpacing: 1,
                    baseTextColor: .flarePostLabel,
                    preferredContentSizeCategory: appearance.preferredContentSizeCategory,
                    collapseAboveLineCount: collapseAboveLineCount
                )
                items.append(bodyText)
            }
            if contents.contains(where: { !$0.text.isEmpty }),
               !isDetail,
               !expand,
                showExpandTextButton {
                let expandMoreButton = resolvedExpandMoreButton()
                expandMoreButton.titleLabel?.font = postBodyFont
                items.append(expandMoreButton)
            }
        }

        // poll
        if let poll = data.poll, showMediaInput {
            let pollView = resolvedPollView()
            pollView.configure(data: poll, absoluteTimestamp: appearance.absoluteTimestamp,
                               preferredContentSizeCategory: appearance.preferredContentSizeCategory)
            pollView.onVote = { [weak self] indices in
                guard let self = self else { return }
                poll.onVote(
                    ClickContext(launcher: self.makeLauncher()),
                    indices.map { KotlinInt(value: Int32($0)) }
                )
            }
            items.append(pollView)
        }

        // translate (detail-only), after StatusContent (including its poll)
        if isDetail, showTranslate {
            let translateView = resolvedTranslateView()
            translateView.preferredContentSizeCategory = appearance.preferredContentSizeCategory
            translateView.content = data.content.original
            translateView.contentWarning = data.contentWarning?.original
            translateView.isSummaryAvailable = aiTldrEnabled
            translateView.onLocalHeightInvalidated = { [weak self] in
                self?.notifyLocalHeightInvalidated()
            }
            items.append(translateView)
        }

        // media grid
        if !data.images.isEmpty, showMediaInput {
            let corner: CGFloat = isQuote ? 12 : 16
            let mediaView = resolvedMediaView()
            updateSingleMediaHeightLimit()
            mediaView.configure(
                data: data.images,
                sensitive: data.sensitive,
                cornerRadius: corner,
                appearanceShowMedia: appearance.showMedia,
                appearanceShowSensitive: appearance.showSensitiveContent,
                appearanceExpandMediaSize: appearance.expandMediaSize,
                appearanceLimitMediaGridToNine: appearance.limitMediaGridToNine,
                appearanceMediaLayout: allowsMediaCarousel ? appearance.mediaLayout : .grid,
                carouselLeadingPadding: carouselEdgePadding + (wantsAvatar ? Self.avatarSize + Self.mainRowSpacing : 0),
                carouselTrailingPadding: carouselEdgePadding
            )
            mediaView.onMediaClicked = { [weak self] media, index in
                guard let self else { return }
                let preview: String? = switch onEnum(of: media) {
                case .image(let image): image.previewUrl
                case .video(let video): video.thumbnailUrl
                case .ugoira(let animation): animation.previewUrl
                case .gif(let gif): gif.previewUrl
                case .audio: nil
                }
                IOSTimelineMediaActions.open(
                    post: data,
                    statusKey: data.statusKey,
                    index: Int32(index),
                    preview: preview,
                    openURL: { [weak self] url in self?.openURL?(url) }
                )
            }
            mediaView.onMediaMenuAction = { media, action in
                IOSTimelineMediaActions.handler(data, media, action)
            }
            mediaView.onLocalHeightInvalidated = { [weak self] in
                self?.notifyLocalHeightInvalidated()
            }
            items.append(mediaView)
        }

        // card (only when no images & no quote & enabled)
        if let card = data.card,
           showMediaInput,
           data.images.isEmpty,
           quotes.isEmpty,
           appearance.showLinkPreview {
            let corner: CGFloat = isQuote ? 12 : 16
            if appearance.compatLinkPreview {
                let compatCardView = resolvedCompatCardView(cornerRadius: corner)
                compatCardView.layer.cornerRadius = corner
                compatCardView.configure(data: card, preferredContentSizeCategory: appearance.preferredContentSizeCategory)
                compatCardView.onOpenURL = { [weak self] in self?.openURL?($0) }
                items.append(compatCardView)
            } else {
                let normalCardView = resolvedNormalCardView(cornerRadius: corner)
                normalCardView.layer.cornerRadius = corner
                normalCardView.configure(data: card, preferredContentSizeCategory: appearance.preferredContentSizeCategory)
                normalCardView.onOpenURL = { [weak self] in self?.openURL?($0) }
                items.append(normalCardView)
            }
        }

        // quotes (not when self is quote)
        if !quotes.isEmpty, !isQuote {
            updateQuotes(quotes: quotes)
            items.append(resolvedQuotesContainer())
        } else {
            updateQuotes(quotes: [])
        }

        // source channel + reactions
        if showMediaInput, !isQuote {
            if let channel = data.sourceChannel {
                let sourceChannelContainer = resolvedSourceChannelContainer()
                sourceChannelContainer.configure(text: channel.name, preferredContentSizeCategory: appearance.preferredContentSizeCategory)
                items.append(sourceChannelContainer)
            }
            if appearance.showEmojiReactions, !data.emojiReactions.isEmpty {
                let reactionView = resolvedReactionView()
                reactionView.configure(data: Array(data.emojiReactions), isDetail: isDetail,
                                       preferredContentSizeCategory: appearance.preferredContentSizeCategory)
                reactionView.onReactionTapped = { [weak self] reaction in
                    guard let self = self else { return }
                    reaction.onClicked(ClickContext(launcher: self.makeLauncher()))
                }
                items.append(reactionView)
            }
        }

        // detail-only full timestamp
        if isDetail {
            let detailTimestampView = resolvedDetailTimestampView()
            detailTimestampView.postTextContentSizeCategory = appearance.preferredContentSizeCategory
            detailTimestampView.absoluteTimestamp = appearance.absoluteTimestamp
            detailTimestampView.set(data: data.createdAt)
            items.append(detailTimestampView)
        }

        // actions
        let hideActions = (appearance.postActionStyle == .hidden && !isDetail) || forceHideActions
        if !hideActions {
            let actionsView = resolvedActionsView()
            actionsView.onOpenURL = { [weak self] in self?.openURL?($0) }
            actionsView.configure(
                data: Array(data.actions),
                useText: false,
                allowSpacer: true,
                postActionStyle: appearance.postActionStyle,
                postActionLayout: appearance.postActionLayout,
                postActionFixedWidth: appearance.postActionFixedWidth,
                showNumbers: appearance.showNumbers,
                isDetail: isDetail,
                postKey: String(describing: data.statusKey),
                isPostContent: true,
                preferredContentSizeCategory: appearance.preferredContentSizeCategory
            )
            if actionsView.hasVisibleActions {
                let actionsContainer = resolvedActionsContainer()
                actionsContainer.content = actionsView
                items.append(actionsContainer)
            }
        }

        return items
    }

    // MARK: - Pools

    private func resolveActiveParentContainers(data: UiTimelineV2.Post) -> [ParentContainerView] {
        let parentData = showParents ? inlineParents : []
        while parentContainers.count < parentData.count {
            parentContainers.append(ParentContainerView())
        }
        for (i, parent) in parentData.enumerated() {
            let container = parentContainers[i]
            container.child.openURL = openURL
            container.child.onLocalHeightInvalidated = { [weak self] in
                self?.notifyLocalHeightInvalidated()
            }
            container.child.configure(
                data: parent.displayPost,
                appearance: appearance,
                withLeadingPadding: true,
                quotes: Array(parent.presentation.quotes),
                allowsMediaCarousel: allowsMediaCarousel,
                carouselOuterHorizontalPadding: carouselOuterHorizontalPadding
            )
        }
        return Array(parentContainers.prefix(parentData.count))
    }

    private func updateQuotes(quotes: [UiTimelineV2.Post]) {
        while quoteChildren.count < quotes.count {
            quoteChildren.append(StatusUIKitView())
        }
        while quoteDividers.count < max(0, quotes.count - 1) {
            let d = UIView()
            d.backgroundColor = .separator
            quoteDividers.append(d)
        }
        var desired: [UIView] = []
        for (i, quote) in quotes.enumerated() {
            let child = quoteChildren[i]
            child.openURL = openURL
            child.onLocalHeightInvalidated = { [weak self] in
                self?.notifyLocalHeightInvalidated()
            }
            child.configure(
                data: quote,
                appearance: appearance,
                isQuote: true,
                forceHideActions: true,
                allowsMediaCarousel: allowsMediaCarousel
            )
            desired.append(child)
            if i != quotes.count - 1 {
                desired.append(quoteDividers[i])
            }
        }
        if !desired.isEmpty || quotesContainerStorage != nil {
            resolvedQuotesContainer().setChildren(desired)
        }
    }

    func performLightweightPoolCleanup() {
        let activeParentCount: Int
        let activeQuoteCount: Int
        if let data {
            activeParentCount = showParents ? inlineParents.count : 0
            activeQuoteCount = !isQuote ? quotes.count : 0
        } else {
            activeParentCount = 0
            activeQuoteCount = 0
        }

        if let data, !data.images.isEmpty, showMediaInput {
            mediaViewStorage?.performLightweightPoolCleanup()
        } else {
            mediaViewStorage?.prepareForPoolRemoval()
        }

        let hideActions = (appearance.postActionStyle == .hidden && !isDetail) || forceHideActions
        if data != nil, !hideActions {
            actionsViewStorage?.performLightweightPoolCleanup()
        } else {
            actionsViewStorage?.prepareForPoolRemoval()
        }
        if !(data != nil && isDetail && showTranslate) {
            translateViewStorage?.prepareForPoolRemoval()
        }
        trimParentContainers(activeCount: activeParentCount)
        trimQuoteChildren(activeCount: activeQuoteCount)
        trimQuoteDividers(activeCount: max(0, activeQuoteCount - 1))
    }

    func performDeferredPoolCleanup() {
        let activeParentCount: Int
        let activeQuoteCount: Int
        if let data {
            activeParentCount = showParents ? inlineParents.count : 0
            activeQuoteCount = !isQuote ? quotes.count : 0
        } else {
            activeParentCount = 0
            activeQuoteCount = 0
        }

        for container in parentContainers.prefix(activeParentCount) {
            container.child.performDeferredPoolCleanup()
        }
        for child in quoteChildren.prefix(activeQuoteCount) {
            child.performDeferredPoolCleanup()
        }
        if let data, !data.images.isEmpty, showMediaInput {
            mediaViewStorage?.performDeferredPoolCleanup()
        } else {
            mediaViewStorage?.prepareForPoolRemoval()
        }

        let hideActions = (appearance.postActionStyle == .hidden && !isDetail) || forceHideActions
        if data != nil, !hideActions {
            actionsViewStorage?.performDeferredPoolCleanup()
        } else {
            actionsViewStorage?.prepareForPoolRemoval()
        }
        if !(data != nil && isDetail && showTranslate) {
            translateViewStorage?.prepareForPoolRemoval()
        }
        trimParentContainers(activeCount: activeParentCount)
        trimQuoteChildren(activeCount: activeQuoteCount)
        trimQuoteDividers(activeCount: max(0, activeQuoteCount - 1))
    }

    private func trimParentContainers(activeCount: Int) {
        guard parentContainers.count > activeCount else { return }
        for container in parentContainers[activeCount...] {
            container.child.prepareForPoolRemoval()
            container.removeFromSuperview()
        }
        parentContainers.removeLast(parentContainers.count - activeCount)
    }

    private func trimQuoteChildren(activeCount: Int) {
        guard quoteChildren.count > activeCount else { return }
        for child in quoteChildren[activeCount...] {
            child.prepareForPoolRemoval()
            child.removeFromSuperview()
        }
        quoteChildren.removeLast(quoteChildren.count - activeCount)
    }

    private func trimQuoteDividers(activeCount: Int) {
        guard quoteDividers.count > activeCount else { return }
        for divider in quoteDividers[activeCount...] {
            divider.removeFromSuperview()
        }
        quoteDividers.removeLast(quoteDividers.count - activeCount)
    }

    private func forwardOpenURL() {
        contentWarningTextStorage?.onOpenURL = openURL
        contentWarningTranslationTextStorage?.onOpenURL = openURL
        bodyTextStorage?.onOpenURL = openURL
        bodyTranslationTextStorage?.onOpenURL = openURL
        normalCardViewStorage?.onOpenURL = { [weak self] in self?.openURL?($0) }
        compatCardViewStorage?.onOpenURL = { [weak self] in self?.openURL?($0) }
        actionsViewStorage?.onOpenURL = { [weak self] in self?.openURL?($0) }
        for container in parentContainers {
            container.child.openURL = openURL
            container.child.forwardOpenURL()
        }
        for child in quoteChildren {
            child.openURL = openURL
            child.forwardOpenURL()
        }
    }

    func autoplayCandidates(prefix: String = "status") -> [TimelineVideoAutoplayCandidate] {
        guard let data, !isHidden, window != nil else { return [] }

        let statusPrefix = "\(prefix):\(String(describing: data.statusKey))"
        var candidates: [TimelineVideoAutoplayCandidate] = []

        if showMediaInput {
            candidates.append(contentsOf: mediaViewStorage?.autoplayCandidates(prefix: statusPrefix) ?? [])
        }

        if showParents {
            for container in parentContainers where container.superview != nil {
                candidates.append(contentsOf: container.child.autoplayCandidates(prefix: "\(statusPrefix):parent"))
            }
        }

        if !isQuote {
            for child in quoteChildren where child.superview != nil {
                candidates.append(contentsOf: child.autoplayCandidates(prefix: "\(statusPrefix):quote"))
            }
        }

        return candidates
    }

    // MARK: - Actions & state transitions

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
        guard gestureRecognizer.view === self,
              let touchedView = touch.view else {
            return true
        }

        if touchedView.firstSuperview(of: UIControl.self) != nil {
            return false
        }

        if let nestedStatus = touchedView.firstSuperview(of: StatusUIKitView.self),
           nestedStatus !== self {
            return false
        }

        return true
    }

    @objc private func onRootTapped() {
        guard let data = data else { return }
        data.onClicked(ClickContext(launcher: makeLauncher()))
    }

    @objc private func onAvatarTapped() {
        guard let user = data?.user else { return }
        user.onClicked(ClickContext(launcher: makeLauncher()))
    }

    private func onUserTapped() {
        guard let user = data?.user else { return }
        user.onClicked(ClickContext(launcher: makeLauncher()))
    }

    private func setExpanded(_ expanded: Bool) {
        guard expand != expanded else { return }
        layoutIfNeeded()
        let hiddenBodyTop = contentColumnChildren.first { $0 === contentWarningToggleStorage }
            .map { $0.frame.maxY + 8 }
        expand = expanded
        UIView.performWithoutAnimation {
            rebuild()
            if expanded {
                let width = fittingContentColumnWidth(for: bounds.width)
                // Lay out the full text before animating its clipped viewport.
                for case let body as RichTextUIView in contentColumnChildren
                    where body === bodyTextStorage || body === bodyTranslationTextStorage {
                    if let hiddenBodyTop {
                        body.frame = CGRect(x: bounds.width - width, y: hiddenBodyTop, width: width, height: 0)
                    }
                    body.prepareForFitting(width: width)
                    body.layoutIfNeeded()
                }
            }
        }

        let cell = onLocalHeightInvalidated == nil ? nil : firstSuperview(of: UICollectionViewCell.self)
        let timeline = cell?.firstSuperview(of: TimelineCollectionView.self)
        UIView.animate(withDuration: UIAccessibility.isReduceMotionEnabled ? 0 : 0.25,
                       delay: 0, options: [.beginFromCurrentState, .allowUserInteraction]) {
            self.notifyLocalHeightInvalidated()
            self.layoutIfNeeded()
            if let cell { timeline?.layoutAfterLocalHeightChange(in: cell) }
        } completion: { [weak self] _ in
            if timeline == nil { self?.invalidateContainingCollectionLayout() }
        }
    }

    private func invalidateContainingCollectionLayout() {
        invalidateIntrinsicContentSize()
        setNeedsLayout()
        var responder: UIResponder? = self
        var cellRef: UICollectionViewCell?
        while let current = responder {
            if cellRef == nil, let cell = current as? UICollectionViewCell {
                cellRef = cell
            }
            if let collectionView = current as? UICollectionView {
                if let cell = cellRef, let indexPath = collectionView.indexPath(for: cell) {
                    let context = UICollectionViewLayoutInvalidationContext()
                    context.invalidateItems(at: [indexPath])
                    collectionView.collectionViewLayout.invalidateLayout(with: context)
                }
                collectionView.performBatchUpdates(nil)
                return
            }
            responder = current.next
        }
    }

    private func notifyLocalHeightInvalidated() {
        lastPreparedFittingWidthKey = nil
        invalidateIntrinsicContentSize()
        setNeedsLayout()
        onLocalHeightInvalidated?()
    }

    fileprivate func makeLauncher() -> AppleUriLauncher {
        AppleUriLauncher(openUrl: OpenURLAction { [weak self] url in
            self?.openURL?(url)
            return .handled
        })
    }
}

// MARK: - ReplyToRowView (replaces replyToRow UIStackView)

private final class ReplyToRowView: UIView, ManualLayoutMeasurable, TimelineHeightProviding {
    private let icon = UIImageView(image: UIImage(fontAwesome: .reply))
    private let label = UILabel()
    private static let iconSize: CGFloat = 12
    private static let spacing: CGFloat = 4
    private static let verticalPadding: CGFloat = 4

    override init(frame: CGRect) {
        super.init(frame: frame)
        icon.tintColor = .flarePostSecondaryLabel
        icon.contentMode = .scaleAspectFit
        addSubview(icon)

        label.font = .preferredFont(forTextStyle: .caption1)
        label.textColor = .flarePostSecondaryLabel
        label.adjustsFontForContentSizeCategory = true
        label.numberOfLines = 0
        addSubview(label)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) not supported") }

    func configure(text: String, preferredContentSizeCategory: UIContentSizeCategory) {
        label.setPostText(text, preferredContentSizeCategory: preferredContentSizeCategory)
        invalidateIntrinsicContentSize()
        setNeedsLayout()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let s = Self.iconSize
        let labelX = s + Self.spacing
        let labelW = max(bounds.width - labelX, 0)
        let labelHeight = ceil(label.textRect(
            forBounds: CGRect(x: 0, y: 0, width: labelW, height: .greatestFiniteMagnitude),
            limitedToNumberOfLines: 0
        ).height)
        let contentCenterY = Self.verticalPadding + max(labelHeight, s) / 2
        icon.frame = CGRect(x: 0, y: contentCenterY - s / 2, width: s, height: s)
        label.frame = CGRect(x: labelX, y: contentCenterY - labelHeight / 2, width: labelW, height: labelHeight)
    }

    override func sizeThatFits(_ size: CGSize) -> CGSize {
        CGSize(width: size.width, height: timelineHeight(for: size.width) ?? 0)
    }

    func timelineHeight(for width: CGFloat) -> CGFloat? {
        guard width > 0, width.isFinite else { return nil }
        let labelW = max(width - Self.iconSize - Self.spacing, 0)
        let labelH = label.textRect(
            forBounds: CGRect(x: 0, y: 0, width: labelW, height: .greatestFiniteMagnitude),
            limitedToNumberOfLines: 0
        ).height
        return ceil(max(labelH, Self.iconSize)) + Self.verticalPadding * 2
    }

    override func systemLayoutSizeFitting(
        _ targetSize: CGSize,
        withHorizontalFittingPriority horizontalFittingPriority: UILayoutPriority,
        verticalFittingPriority: UILayoutPriority
    ) -> CGSize {
        sizeThatFits(CGSize(width: targetSize.width, height: .greatestFiniteMagnitude))
    }
}

// MARK: - SourceChannelRowView (replaces sourceChannelRow UIStackView)

private final class SourceChannelRowView: UIView, ManualLayoutMeasurable, TimelineHeightProviding {
    private let icon = UIImageView(image: UIImage(fontAwesome: .tv))
    private let label = UILabel()
    private static let iconSize: CGFloat = 12
    private static let spacing: CGFloat = 4

    override init(frame: CGRect) {
        super.init(frame: frame)
        icon.tintColor = .flarePostSecondaryLabel
        icon.contentMode = .scaleAspectFit
        addSubview(icon)

        label.font = .preferredFont(forTextStyle: .footnote)
        label.textColor = .flarePostSecondaryLabel
        label.adjustsFontForContentSizeCategory = true
        label.numberOfLines = 0
        addSubview(label)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) not supported") }

    func configure(text: String, preferredContentSizeCategory: UIContentSizeCategory) {
        label.setPostText(text, preferredContentSizeCategory: preferredContentSizeCategory)
        invalidateIntrinsicContentSize()
        setNeedsLayout()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let s = Self.iconSize
        let labelX = s + Self.spacing
        let labelW = max(bounds.width - labelX, 0)
        let labelH = label.textRect(
            forBounds: CGRect(x: 0, y: 0, width: labelW, height: .greatestFiniteMagnitude),
            limitedToNumberOfLines: 0
        ).height
        icon.frame = CGRect(x: 0, y: (ceil(labelH) - s) / 2, width: s, height: s)
        label.frame = CGRect(x: labelX, y: 0, width: labelW, height: ceil(labelH))
    }

    override func sizeThatFits(_ size: CGSize) -> CGSize {
        CGSize(width: size.width, height: timelineHeight(for: size.width) ?? 0)
    }

    func timelineHeight(for width: CGFloat) -> CGFloat? {
        guard width > 0, width.isFinite else { return nil }
        let labelW = max(width - Self.iconSize - Self.spacing, 0)
        let labelH = label.textRect(
            forBounds: CGRect(x: 0, y: 0, width: labelW, height: .greatestFiniteMagnitude),
            limitedToNumberOfLines: 0
        ).height
        return ceil(max(labelH, Self.iconSize))
    }

    override func systemLayoutSizeFitting(
        _ targetSize: CGSize,
        withHorizontalFittingPriority horizontalFittingPriority: UILayoutPriority,
        verticalFittingPriority: UILayoutPriority
    ) -> CGSize {
        sizeThatFits(CGSize(width: targetSize.width, height: .greatestFiniteMagnitude))
    }
}

// MARK: - ActionsContainerView

private final class ActionsContainerView: UIView, ManualLayoutMeasurable, TimelineHeightProviding {
    var content: UIView? {
        didSet {
            guard oldValue !== content else { return }
            oldValue?.removeFromSuperview()
            if let c = content, c.superview !== self { addSubview(c) }
            setNeedsLayout()
        }
    }
    private static let topPadding: CGFloat = 0

    override func layoutSubviews() {
        super.layoutSubviews()
        guard let content else { return }
        content.frame = CGRect(
            x: -4,
            y: Self.topPadding,
            width: bounds.width + 8,
            height: max(bounds.height - Self.topPadding, 0)
        )
    }

    override func sizeThatFits(_ size: CGSize) -> CGSize {
        CGSize(width: size.width, height: timelineHeight(for: size.width) ?? 0)
    }

    func timelineHeight(for width: CGFloat) -> CGFloat? {
        guard width > 0, width.isFinite else { return nil }
        guard let content else { return .zero }
        let h = childHeight(of: content, for: width)
        return ceil(h + Self.topPadding)
    }

    override func systemLayoutSizeFitting(
        _ targetSize: CGSize,
        withHorizontalFittingPriority horizontalFittingPriority: UILayoutPriority,
        verticalFittingPriority: UILayoutPriority
    ) -> CGSize {
        sizeThatFits(CGSize(width: targetSize.width, height: .greatestFiniteMagnitude))
    }
}

// MARK: - QuotesContainerView (replaces quotesContainer + quotesStack)

private final class QuotesContainerView: UIView, ManualLayoutMeasurable, TimelineHeightProviding {
    private var managedChildren: [UIView] = []
    private static let padding: CGFloat = 12
    private static let spacing: CGFloat = 8
    private static let dividerHeight: CGFloat = 1.0 / UIScreen.main.scale

    override init(frame: CGRect) {
        super.init(frame: frame)
        layer.cornerRadius = 16
        layer.borderWidth = 1
        useSeparatorBorderColor()
        clipsToBounds = true
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) not supported") }

    func setChildren(_ desired: [UIView]) {
        syncManagedSubviews(parent: self, current: &managedChildren, desired: desired)
        invalidateIntrinsicContentSize()
        setNeedsLayout()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let p = Self.padding
        let contentWidth = max(bounds.width - p * 2, 0)
        var y = p
        for (i, child) in managedChildren.enumerated() {
            // Dividers get fixed height
            if child.backgroundColor == .separator && !(child is StatusUIKitView) {
                child.frame = CGRect(x: p, y: y, width: contentWidth, height: Self.dividerHeight)
                y += Self.dividerHeight
            } else {
                let h = childHeight(of: child, for: contentWidth)
                child.frame = CGRect(x: p, y: y, width: contentWidth, height: h)
                y += h
            }
            if i < managedChildren.count - 1 {
                y += Self.spacing
            }
        }
    }

    override func sizeThatFits(_ size: CGSize) -> CGSize {
        CGSize(width: size.width, height: timelineHeight(for: size.width) ?? 0)
    }

    func timelineHeight(for width: CGFloat) -> CGFloat? {
        guard width > 0, width.isFinite else { return nil }
        let p = Self.padding
        let contentWidth = max(width - p * 2, 0)
        var h = p
        for (i, child) in managedChildren.enumerated() {
            if child.backgroundColor == .separator && !(child is StatusUIKitView) {
                h += Self.dividerHeight
            } else {
                h += childHeight(of: child, for: contentWidth)
            }
            if i < managedChildren.count - 1 {
                h += Self.spacing
            }
        }
        h += p
        return ceil(h)
    }

    override func systemLayoutSizeFitting(
        _ targetSize: CGSize,
        withHorizontalFittingPriority horizontalFittingPriority: UILayoutPriority,
        verticalFittingPriority: UILayoutPriority
    ) -> CGSize {
        sizeThatFits(CGSize(width: targetSize.width, height: .greatestFiniteMagnitude))
    }
}

// MARK: - Parent container

/// Wraps a `StatusUIKitView` with SwiftUI-equivalent leading line overlay and
/// the rendered parent-to-parent/main gap (6pt bottom + 2pt connector + 12pt top).
private final class ParentContainerView: UIView, ManualLayoutMeasurable, TimelineHeightProviding {
    let child = StatusUIKitView()
    private let line = UIView()
    private static let bottomSpacing: CGFloat = 20

    override init(frame: CGRect) {
        super.init(frame: frame)
        line.backgroundColor = .separator
        addSubview(line)
        addSubview(child)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) not supported") }

    override func layoutSubviews() {
        super.layoutSubviews()
        let w = bounds.width
        let childH = max(bounds.height - Self.bottomSpacing, 0)
        child.frame = CGRect(x: 0, y: 0, width: w, height: childH)
        let avatarBottom = child.sideAvatarBottom ?? 48
        line.frame = CGRect(x: 24, y: avatarBottom, width: 1, height: max(bounds.height - avatarBottom, 0))
    }

    override func sizeThatFits(_ size: CGSize) -> CGSize {
        CGSize(width: size.width, height: timelineHeight(for: size.width) ?? 0)
    }

    func timelineHeight(for width: CGFloat) -> CGFloat? {
        guard width > 0, width.isFinite else { return nil }
        let childH = childHeight(of: child, for: width)
        return ceil(childH + Self.bottomSpacing)
    }

    override func systemLayoutSizeFitting(
        _ targetSize: CGSize,
        withHorizontalFittingPriority horizontalFittingPriority: UILayoutPriority,
        verticalFittingPriority: UILayoutPriority
    ) -> CGSize {
        sizeThatFits(CGSize(width: targetSize.width, height: .greatestFiniteMagnitude))
    }

    func prepareForFitting(width: CGFloat) {
        bounds = CGRect(x: bounds.minX, y: bounds.minY, width: width, height: bounds.height)
        child.prepareForFitting(width: width)
        invalidateIntrinsicContentSize()
        setNeedsLayout()
    }
}

// MARK: - Helpers

private extension UIView {
    func firstSuperview<T: UIView>(of type: T.Type) -> T? {
        var current: UIView? = self
        while let view = current {
            if let matched = view as? T {
                return matched
            }
            current = view.superview
        }
        return nil
    }
}
