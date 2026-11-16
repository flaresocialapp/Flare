import XCTest
import UIKit
import SwiftUI
import KotlinSharedUI
import FlareAppleCore
import FlareAppleUI
@testable import Flare

@MainActor
final class StatusPostPresentationTests: XCTestCase {
    func testLongRepostNameLeavesRoomForTheLocalizedLabel() throws {
        let name = "サムライ忍者GREENTEA / 原田くくま🥷🇯🇵"
        let style = RenderTextStyle(link: nil, bold: false, italic: false, strikethrough: false,
                                    monospace: false, code: false, underline: false, small: false, time: false, imageLink: nil)
        let rich = UiRichText(renderRuns: [RenderContent.Text(runs: [RenderRun.Text(text: name, style: style)], block: RenderBlockStyle())],
                              isRtl: false, raw: name, innerText: name, imageUrls: [])
        let user = UiProfile(key: MicroBlogKey(id: "retweeter", host: "x.com"),
                             handle: UiHandle(raw: "retweeter", host: "x.com", displayOverride: "@retweeter"),
                             avatar: nil as UiMediaImage?, nameInternal: rich, platformId: "xqt",
                             platformIcon: IconType.Material(icon: .x), clickEvent: ClickEventNoop.shared,
                             banner: nil, description: nil, sourceLanguages: [], translationDisplayState: .hidden,
                             matrices: UiProfile.Matrices(fansCount: 0, followsCount: 0, statusesCount: 0, platformFansCount: nil),
                             mark: [], bottomContent: nil, pluginId: nil, clearedMarks: [])
        let message = UiTimelineV2.Message(user: user, statusKey: MicroBlogKey(id: "repost", host: "x.com"), icon: .retweet,
                                          type: UiTimelineV2.MessageTypeLocalized(data: .repost, args: []),
                                          createdAt: replyPost("repost").createdAt, clickEvent: ClickEventNoop.shared,
                                          accountType: AccountType.Guest.shared, itemKey: nil)
        for width: CGFloat in [280, 348] {
            let view = StatusTopMessageUIView()
            view.configure(message: message, topMessageOnly: false, postTextContentSizeCategory: .medium,
                           isAbovePost: true)
            view.frame = CGRect(x: 0, y: 0, width: width, height: try XCTUnwrap(view.timelineHeight(for: width)))
            view.layoutIfNeeded()
            let label = try XCTUnwrap(view.subviews.compactMap { $0 as? UILabel }.first)
            let nameView = try XCTUnwrap(view.subviews.compactMap { $0 as? RichTextUIView }.first)
            let naturalWidth = label.sizeThatFits(CGSize(width: CGFloat.greatestFiniteMagnitude,
                                                       height: CGFloat.greatestFiniteMagnitude)).width
            XCTAssertGreaterThanOrEqual(label.bounds.width, naturalWidth)
            XCTAssertLessThanOrEqual(label.frame.maxX, width + 1)
            XCTAssertGreaterThan(nameView.bounds.width, 1)
            XCTAssertLessThan(nameView.frame.maxX, label.frame.minX)
            let icon = try XCTUnwrap(view.subviews.compactMap { $0 as? UIImageView }.first)
            XCTAssertEqual(icon.frame.midY, label.frame.minY + label.font.ascender - label.font.capHeight / 2, accuracy: 1)
        }
    }

    func testReplyContextIsHiddenForTheWholeInlineChainAndRestoredOnReuse() throws {
        let post = replyPost("reply")
        let parent = UiTimelineV2.TimelinePostItem(
            post: replyPost("parent"),
            presentation: UiTimelineV2.PostPresentation(message: nil, inlineParents: [], quotes: [], repost: nil, notificationKey: nil),
            itemKey: "parent"
        )
        let view = StatusUIKitView()
        let appearance = StatusUIKitAppearance(timeline: TimelineAppearance.companion.Default)
        let replyText = String(localized: "Reply to \("@context")")
        func replyCount() -> Int {
            descendants(view).compactMap { $0 as? UILabel }.filter { $0.text == replyText }.count
        }

        view.configure(data: post, appearance: appearance)
        XCTAssertEqual(replyCount(), 1, "A standalone reply needs its context")
        view.configure(data: post, appearance: appearance, withLeadingPadding: true)
        XCTAssertEqual(replyCount(), 0, "A parent inside the conversation already has a connection line")
        view.configure(data: post, appearance: appearance, inlineParents: [parent])
        XCTAssertEqual(replyCount(), 0, "Both the main post and its inline parents must hide the redundant row")
        view.configure(data: post, appearance: appearance, showParents: false, inlineParents: [parent])
        XCTAssertEqual(replyCount(), 1, "Hiding the parent chain restores standalone context")
        view.configure(data: post, appearance: appearance)
        XCTAssertEqual(replyCount(), 1, "Reused cells must restore the reply row")
    }

    func testIconOnlyPostMessageStaysInsideItsHeader() throws {
        let post = replyPost("icon-only")
        let message = UiTimelineV2.Message(user: nil, statusKey: post.statusKey, icon: .retweet,
                                          type: UiTimelineV2.MessageTypeUnknown(rawType: ""),
                                          createdAt: post.createdAt, clickEvent: ClickEventNoop.shared,
                                          accountType: post.accountType, itemKey: nil)
        let view = StatusTopMessageUIView()
        view.configure(message: message, topMessageOnly: false, postTextContentSizeCategory: .medium)
        view.frame = CGRect(x: 0, y: 0, width: 280, height: try XCTUnwrap(view.timelineHeight(for: 280)))
        view.layoutIfNeeded()
        let icon = try XCTUnwrap(view.subviews.compactMap { $0 as? UIImageView }.first)
        XCTAssertTrue(view.bounds.contains(icon.frame))
        XCTAssertEqual(icon.frame.midY, view.bounds.midY, accuracy: 0.5)
    }

    func testOnlyStandaloneMessagesUseBodyColorAndKeepTheirSpacingOnReuse() throws {
        let row = try XCTUnwrap(makeTimelineTestRow(1, mediaCount: 0, textRepeatCount: 0) as? UiTimelineV2.TimelinePostItem)
        let profile = try XCTUnwrap(row.post.user)
        let message = UiTimelineV2.Message(user: profile, statusKey: row.post.statusKey, icon: .retweet,
                                          type: UiTimelineV2.MessageTypeLocalized(data: .repost, args: []),
                                          createdAt: row.post.createdAt, clickEvent: ClickEventNoop.shared,
                                          accountType: row.post.accountType, itemKey: nil)
        let post = UiTimelineV2.TimelinePostItem(post: row.post,
            presentation: UiTimelineV2.PostPresentation(message: message, inlineParents: [], quotes: [], repost: nil, notificationKey: nil),
            itemKey: "repost")
        let user = UiTimelineV2.User(message: message, value: profile, createdAt: row.post.createdAt,
                                     statusKey: row.post.statusKey, button: [], accountType: row.post.accountType, itemKey: "user")
        let view = TimelineUIView()
        let cases: [(UiTimelineV2, CGFloat?)] = [(post, 2), (message, nil), (user, 8), (post, 2)]
        for (data, spacing) in cases {
            view.configure(data: data)
            view.prepareForFitting(width: 370)
            view.frame = CGRect(x: 0, y: 0, width: 370, height: try XCTUnwrap(view.timelineHeight(for: 370)))
            view.layoutIfNeeded()
            let messageView = try XCTUnwrap(descendants(view).compactMap { $0 as? StatusTopMessageUIView }.first)
            if let spacing {
                let container = try XCTUnwrap(messageView.superview)
                let content = try XCTUnwrap(view.subviews.first { $0 is StatusUIKitView || $0 is TimelineUserUIView })
                XCTAssertEqual(content.frame.minY - container.frame.maxY, spacing)
            }
            let suffix = try XCTUnwrap(messageView.subviews.compactMap { $0 as? UILabel }.first)
            let name = try XCTUnwrap(messageView.subviews.compactMap { $0 as? RichTextUIView }.first)
            let icon = try XCTUnwrap(messageView.subviews.compactMap { $0 as? UIImageView }.first)
            let bodyFont = UIFont.preferredFont(forTextStyle: .body, compatibleWith: UITraitCollection(preferredContentSizeCategory: .medium))
            XCTAssertEqual(suffix.font.pointSize, bodyFont.pointSize - (data is UiTimelineV2.TimelinePostItem ? 2 : 0))
            let nameText = try XCTUnwrap(descendants(name).compactMap { ($0 as? UILabel)?.attributedText }.first)
            let nameFont = try XCTUnwrap(nameText.attribute(.font, at: 0, effectiveRange: nil) as? UIFont)
            XCTAssertEqual(nameFont.pointSize, suffix.font.pointSize)
            XCTAssertTrue(nameFont.fontDescriptor.symbolicTraits.contains(.traitBold))
            let expectedColor: UIColor = data is UiTimelineV2.Message ? .flarePostLabel : .flarePostSecondaryLabel
            XCTAssertEqual(suffix.textColor, expectedColor)
            XCTAssertEqual(name.baseTextColor, expectedColor)
            XCTAssertEqual(icon.tintColor, .flarePostSecondaryLabel)
        }
    }

    func testReplyContextHasEqualSpacingAndQuoteUsesAttachmentSpacing() throws {
        let row = try XCTUnwrap(makeTimelineTestRow(1, mediaCount: 0, textRepeatCount: 0) as? UiTimelineV2.TimelinePostItem)
        let appearance = StatusUIKitAppearance(timeline: TimelineAppearance.companion.Default)
        let cases: [(String, Bool, Bool, CGFloat)] = [
            ("Reply body", true, false, 4),
            ("", true, true, 4),
            ("", true, false, 4),
            ("", false, true, 10),
        ]
        for (text, includeReply, includeQuote, spacing) in cases {
            let post = replyPost("spacing", text: text, user: row.post.user,
                                 replyToHandle: includeReply ? "@context" : nil, actions: row.post.actions)
            let view = StatusUIKitView()
            view.configure(data: post, appearance: appearance, showTranslate: false, quotes: includeQuote ? [row.post] : [])
            view.prepareForFitting(width: 370)
            view.frame = CGRect(x: 0, y: 0, width: 370, height: try XCTUnwrap(view.timelineHeight(for: 370)))
            view.layoutIfNeeded()
            let header = try XCTUnwrap(view.subviews.first { $0 is UserOnelineUIView || $0 is UserCompatUIView })
            let next: UIView
            if !text.isEmpty {
                next = try XCTUnwrap(view.subviews.first { $0 is RichTextUIView })
            } else if includeQuote {
                let quote = try XCTUnwrap(descendants(view).compactMap { $0 as? StatusUIKitView }.first { $0 !== view })
                next = try XCTUnwrap(quote.superview)
            } else {
                next = try XCTUnwrap(descendants(view).first { $0 is StatusActionsUIView })
            }
            if includeReply {
                let reply = try XCTUnwrap(descendants(view).compactMap { $0 as? UILabel }
                    .first { $0.text == String(localized: "Reply to \("@context")") })
                let replyFrame = reply.convert(reply.bounds, to: view)
                XCTAssertEqual(replyFrame.minY - header.frame.maxY, spacing)
                XCTAssertEqual(next.convert(next.bounds, to: view).minY - replyFrame.maxY, spacing)
            } else {
                XCTAssertEqual(next.convert(next.bounds, to: view).minY - header.frame.maxY, spacing)
            }
        }
    }

    func testSwiftUIQuoteOnlyReplyReplacesAttachmentGapWithPaddedReplyRow() throws {
        let row = try XCTUnwrap(makeTimelineTestRow(1, mediaCount: 0, textRepeatCount: 0) as? UiTimelineV2.TimelinePostItem)
        let profile = try XCTUnwrap(row.post.user)
        let quote = replyPost("quote", text: "Quoted text", user: profile, replyToHandle: nil)
        let helper = TimelinePresentationAppearancePatchHelper.shared
        let appearance = helper.resolve(base: TimelineAppearance.companion.Default,
            patch: helper.setFullWidthPost(patch: helper.empty, value: false))
        func height(replyTo: String?) throws -> CGFloat {
            let post = replyPost("reply", text: "", user: profile, replyToHandle: replyTo)
            let renderer = ImageRenderer(content:
                StatusView(data: post, forceHideActions: true, quotes: [quote])
                    .environment(\.timelineAppearance, appearance)
                    .frame(width: 370)
            )
            return CGFloat(try XCTUnwrap(renderer.cgImage).height)
        }

        let prompt = ImageRenderer(content: HStack(spacing: 4) {
            Image(fontAwesome: .reply)
            Text("Reply to @context")
        }.font(.body))
        let promptHeight = CGFloat(try XCTUnwrap(prompt.cgImage).height)
        XCTAssertEqual(try height(replyTo: "@context") - height(replyTo: nil), promptHeight + 8 - 10, accuracy: 1)
    }

    func testSwiftUIQuoteSeparatesUserRowAndBodyByFourPoints() throws {
        let row = try XCTUnwrap(makeTimelineTestRow(1, mediaCount: 0, textRepeatCount: 0) as? UiTimelineV2.TimelinePostItem)
        let profile = try XCTUnwrap(row.post.user)
        func height(text: String, user: UiProfile?) throws -> CGFloat {
            let quote = replyPost("quote", text: text, user: user, replyToHandle: nil)
            let renderer = ImageRenderer(content:
                StatusView(data: quote, isQuote: true, forceHideActions: true).frame(width: 346)
            )
            renderer.scale = 3
            return CGFloat(try XCTUnwrap(renderer.cgImage).height) / renderer.scale
        }
        let header = try height(text: "", user: profile)
        let body = try height(text: "Quoted text", user: nil)
        XCTAssertEqual(try height(text: "Quoted text", user: profile) - header - body, 4, accuracy: 0.5)
    }

    func testPostGeometryUsesTheSameInsetsForLayoutAndMeasurement() throws {
        let row = try XCTUnwrap(makeTimelineTestRow(1, mediaCount: 0, textRepeatCount: 1) as? UiTimelineV2.TimelinePostItem)
        let appearance = StatusUIKitAppearance(timeline: TimelineAppearance.companion.Default)
        for detail in [false, true] {
            let view = StatusUIKitView()
            view.configure(data: row.post, appearance: appearance, isDetail: detail,
                           withLeadingPadding: !detail, showTranslate: false, quotes: [row.post])
            view.prepareForFitting(width: 370)
            view.frame = CGRect(x: 0, y: 0, width: 370, height: try XCTUnwrap(view.timelineHeight(for: 370)))
            view.layoutIfNeeded()
            let avatar = try XCTUnwrap(view.subviews.compactMap { $0 as? AvatarUIView }.first
                ?? descendants(view).compactMap { $0 as? UserCompatUIView }.first?.avatar)
            XCTAssertEqual(avatar.bounds.size, CGSize(width: 48, height: 48))
            let quote = try XCTUnwrap(descendants(view).compactMap { $0 as? StatusUIKitView }.first { $0 !== view })
            let quoteContainer = try XCTUnwrap(quote.superview)
            let header = try XCTUnwrap(view.subviews.first { $0 is UserOnelineUIView || $0 is UserCompatUIView })
            let body = try XCTUnwrap(view.subviews.compactMap { $0 as? RichTextUIView }.first)
            XCTAssertEqual(body.frame.minY - header.frame.maxY, detail ? 8 : 2)
            if let fullWidthHeader = header as? UserCompatUIView {
                let author = try XCTUnwrap(fullWidthHeader.name.superview)
                XCTAssertEqual(author.center.y, avatar.center.y, accuracy: 1)
            } else {
                let font = UIFont.preferredFont(forTextStyle: .body,
                    compatibleWith: UITraitCollection(preferredContentSizeCategory: appearance.preferredContentSizeCategory))
                XCTAssertEqual(avatar.frame.minY - header.frame.minY, font.ascender - font.capHeight, accuracy: 1)
            }
            XCTAssertEqual(quoteContainer.frame.minY - body.frame.maxY, 10)
            XCTAssertEqual(quote.frame.minX, 12)
            XCTAssertEqual(quoteContainer.bounds.maxX - quote.frame.maxX, 12)
            XCTAssertEqual(quote.frame.minY, 12)
        }

        let cell = TimelineUIKitCollectionViewCell(frame: CGRect(x: 0, y: 0, width: 390, height: 500))
        cell.configureTimeline(data: row, index: 0, totalCount: 1,
                               appearance: TimelineUIKitAppearance(timeline: TimelineAppearance.companion.Default),
                               detailStatusKey: nil, aiTldrEnabled: false, isMultipleColumn: false, openURL: nil)
        cell.layoutIfNeeded()
        let timeline = try XCTUnwrap(descendants(cell).compactMap { $0 as? TimelineUIView }.first)
        let frame = timeline.convert(timeline.bounds, to: cell)
        XCTAssertEqual(frame.minY, 12)
        XCTAssertEqual(frame.minX, 10)
        XCTAssertEqual(frame.maxX, 380)

        let controller = UITimelineCollectionViewController(detailStatusKey: nil)
        let layout = UICollectionViewFlowLayout()
        let collection = UICollectionView(frame: .zero, collectionViewLayout: layout)
        XCTAssertEqual(controller.collectionView(collection, layout: layout, minimumInteritemSpacingForSectionAt: 0), 0)
    }

    func testListAndDetailActionsUseCompactCountsAndSixteenPointIcons() throws {
        let row = try XCTUnwrap(makeTimelineTestRow(1, mediaCount: 0, textRepeatCount: 0) as? UiTimelineV2.TimelinePostItem)
        for category in [UIContentSizeCategory.medium, .extraExtraExtraLarge] {
            let actions = StatusActionsUIView()
            let expectedFont = UIFont.preferredFont(forTextStyle: .body, compatibleWith: UITraitCollection(preferredContentSizeCategory: category))
            var listHeight: CGFloat?
            for detail in [false, true] {
                actions.configure(data: Array(row.post.actions), useText: false, postActionStyle: .stretch,
                                  showNumbers: true, isDetail: detail, isPostContent: true,
                                  preferredContentSizeCategory: category)
                let count = try XCTUnwrap(descendants(actions).compactMap { $0 as? UILabel }.first { $0.text == "1" })
                XCTAssertEqual(count.font.pointSize, expectedFont.pointSize - 2)
                XCTAssertEqual(count.textColor, .flarePostSecondaryLabel)
                actions.frame.size = actions.sizeThatFits(CGSize(width: 370, height: CGFloat.greatestFiniteMagnitude))
                actions.layoutIfNeeded()
                let icons = descendants(actions).compactMap { $0 as? UIImageView }.filter { !$0.isHidden }
                XCTAssertFalse(icons.isEmpty)
                XCTAssertTrue(icons.allSatisfy { $0.bounds.height == 16 })
                let height = try XCTUnwrap(actions.timelineHeight(for: 370))
                if let listHeight { XCTAssertEqual(height, listHeight) } else { listHeight = height }
            }
        }
    }

    func testTimelineRowsSharePostInsetsAndMessageStyle() async throws {
        let fixture = try XCTUnwrap(makeTimelineTestRow(1, mediaCount: 0) as? UiTimelineV2.TimelinePostItem)
        let fixtureUser = try XCTUnwrap(fixture.post.user)
        let avatarURL = FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID().uuidString).png")
        defer { try? FileManager.default.removeItem(at: avatarURL) }
        let avatarImage = UIGraphicsImageRenderer(size: CGSize(width: 48, height: 48)).image { context in
            UIColor(red: 91 / 255, green: 128 / 255, blue: 165 / 255, alpha: 1).setFill()
            context.fill(CGRect(x: 0, y: 0, width: 48, height: 48))
        }
        try XCTUnwrap(avatarImage.pngData()).write(to: avatarURL)
        let avatar = UiMediaImage(url: avatarURL.absoluteString, previewUrl: avatarURL.absoluteString, description: nil,
                                  height: 48, width: 48, sensitive: false, customHeaders: nil, proxy: ProxyRef.Unbound.shared)
        func profile(_ handle: String, _ name: String) -> UiProfile {
            UiProfile(key: MicroBlogKey(id: handle, host: "example.org"),
                handle: UiHandle(raw: handle, host: "example.org", displayOverride: "@\(handle)"),
                avatar: avatar, nameInternal: replyPost(handle, text: name).content.original,
                platformId: fixtureUser.platformId, platformIcon: fixtureUser.platformIcon, clickEvent: ClickEventNoop.shared,
                banner: nil, description: nil, sourceLanguages: [], translationDisplayState: .hidden,
                matrices: fixtureUser.matrices, mark: [], bottomContent: nil, pluginId: nil, clearedMarks: [])
        }
        let createdAt = KotlinInstant.companion.fromEpochMilliseconds(
            epochMilliseconds: Int64((Date().timeIntervalSince1970 - 300) * 1_000)).toUi()
        let post = replyPost("alex", text: "A quiet afternoon at the coast.", user: profile("alex", "Alex Rivera"),
                            replyToHandle: nil, actions: Array(fixture.post.actions), createdAt: createdAt)
        func message(_ id: String, _ name: String, _ icon: UiIcon, _ text: String) -> UiTimelineV2.Message {
            UiTimelineV2.Message(user: profile(id, name), statusKey: post.statusKey, icon: icon,
                type: UiTimelineV2.MessageTypeRaw(content: text), createdAt: fixture.post.createdAt,
                clickEvent: ClickEventNoop.shared, accountType: post.accountType, itemKey: id)
        }
        let repost = UiTimelineV2.TimelinePostItem(post: post, presentation: UiTimelineV2.PostPresentation(
            message: message("sam", "Sam Lee", .retweet, "reposted"), inlineParents: [], quotes: [], repost: nil, notificationKey: nil),
            itemKey: "repost")
        let user = UiTimelineV2.User(message: message("jordan", "Jordan Kim", .follow, "followed you"), value: profile("mei", "Mei Chen"),
            createdAt: post.createdAt, statusKey: post.statusKey, button: [], accountType: post.accountType, itemKey: "user")
        var input = TimelineContent()
        input.state = .loaded
        input.items = [.post(repost), .post(user), .post(message("flare", "Flare Team", .pin, "pinned a post"))]

        let helper = TimelinePresentationAppearancePatchHelper.shared
        let layouts: [(TimelineDisplayMode, CGFloat, String)] = [(.plain, 0, "plain"), (.card, 8, "card")]
        for (mode, outerInset, name) in layouts {
            let controller = UITimelineCollectionViewController(detailStatusKey: nil)
            let appearance = helper.resolve(base: TimelineAppearance.companion.Default,
                patch: helper.setTimelineDisplayMode(patch: helper.empty, value: mode))
            controller.appearance = TimelineUIKitAppearance(timeline: appearance)
            controller.loadViewIfNeeded()
            controller.view.frame = CGRect(x: 0, y: 0, width: 402, height: 310)
            controller.view.overrideUserInterfaceStyle = .dark
            let collection = try XCTUnwrap(descendants(controller.view).compactMap { $0 as? TimelineCollectionView }.first)
            collection.contentInsetAdjustmentBehavior = .never
            controller.submit(input, columns: 1)
            for _ in 0..<50 {
                controller.view.layoutIfNeeded()
                collection.layoutIfNeeded()
                if collection.visibleCells.count == 3 { break }
                try await Task.sleep(for: .milliseconds(10))
            }
            let cells = collection.visibleCells
            XCTAssertEqual(cells.count, 3)
            for cell in cells {
                XCTAssertEqual(cell.frame.minX, outerInset, accuracy: 0.5)
                XCTAssertEqual(cell.frame.maxX, 402 - outerInset, accuracy: 0.5)
                let item = try XCTUnwrap(descendants(cell).compactMap { $0 as? TimelineUIView }.first)
                XCTAssertEqual(item.convert(item.bounds, to: cell).minX, 10, accuracy: 0.5)
            }
            let people = try XCTUnwrap(descendants(collection).compactMap { $0 as? UserCompatUIView }.first)
            XCTAssertEqual(people.name.superview?.center.y ?? 0, people.avatar.center.y, accuracy: 0.5)
            XCTAssertEqual(people.name.baseTextWeight, .bold)
            let headers = descendants(collection).compactMap { $0 as? StatusTopMessageUIView }
            XCTAssertEqual(headers.count, 3)
            for header in headers {
                let name = try XCTUnwrap(header.subviews.compactMap { $0 as? RichTextUIView }.first)
                let text = try XCTUnwrap(header.subviews.compactMap { $0 as? UILabel }.first)
                let expectedColor: UIColor = text.text == "pinned a post" ? .flarePostLabel : .flarePostSecondaryLabel
                XCTAssertEqual(name.baseTextWeight, .bold)
                XCTAssertEqual(name.baseTextColor, expectedColor)
                XCTAssertEqual(text.textColor, expectedColor)
                XCTAssertGreaterThan(name.bounds.width, 40)
            }

            try await Task.sleep(for: .milliseconds(150))
            let image = UIGraphicsImageRenderer(size: controller.view.bounds.size).image { context in
                controller.view.layer.render(in: context.cgContext)
            }
            let attachment = XCTAttachment(image: image)
            attachment.name = "ios-\(name)"
            attachment.lifetime = .keepAlways
            add(attachment)
        }

        let longMessage = StatusTopMessageUIView()
        longMessage.configure(message: message("long", "Flare Team", .pin, "pinned a post about the next community meetup"),
                              topMessageOnly: true, postTextContentSizeCategory: .medium)
        longMessage.frame = CGRect(x: 0, y: 0, width: 240, height: try XCTUnwrap(longMessage.timelineHeight(for: 240)))
        longMessage.layoutIfNeeded()
        let label = try XCTUnwrap(longMessage.subviews.compactMap { $0 as? UILabel }.first)
        XCTAssertEqual(label.textColor, .flarePostLabel)
        XCTAssertGreaterThan(label.bounds.height, label.font.lineHeight)
        XCTAssertLessThanOrEqual(label.frame.maxX, longMessage.bounds.maxX + 1)
    }

    func testActionOutsetsRemainTappableThroughTheEntireTimelineCell() throws {
        let row = try XCTUnwrap(makeTimelineTestRow(1, mediaCount: 0, textRepeatCount: 1) as? UiTimelineV2.TimelinePostItem)
        let parent = try XCTUnwrap(makeTimelineTestRow(2, mediaCount: 0, textRepeatCount: 1) as? UiTimelineV2.TimelinePostItem)
        for detail in [false, true] {
            for withParent in [false, true] {
                let item = UiTimelineV2.TimelinePostItem(post: row.post,
                    presentation: UiTimelineV2.PostPresentation(message: nil, inlineParents: withParent ? [parent] : [],
                                                               quotes: [], repost: nil, notificationKey: nil),
                    itemKey: row.itemKey)
                let cell = TimelineUIKitCollectionViewCell(frame: CGRect(x: 0, y: 0, width: 402, height: 900))
                cell.configureTimeline(data: item, index: 0, totalCount: 1,
                    appearance: TimelineUIKitAppearance(timeline: TimelineAppearance.companion.Default),
                    detailStatusKey: detail ? row.post.statusKey : nil, aiTldrEnabled: false, isMultipleColumn: false, openURL: nil)
                cell.layoutIfNeeded()
                let rows = descendants(cell).compactMap { $0 as? StatusActionsUIView }
                XCTAssertEqual(rows.count, withParent ? 2 : 1)
                for actions in rows {
                    let controls = actions.subviews.compactMap { $0 as? UIControl }.sorted { $0.frame.minX < $1.frame.minX }
                    let first = try XCTUnwrap(controls.first)
                    let last = try XCTUnwrap(controls.last)
                    for (control, x) in [(first, CGFloat(1)), (last, last.bounds.width - 1)] {
                        let point = control.convert(CGPoint(x: x, y: control.bounds.midY), to: cell)
                        XCTAssertTrue(cell.hitTest(point, with: nil) === control,
                                      "The entire action slot must be tappable, including its horizontal outset")
                        actions.isHidden = true
                        XCTAssertFalse(cell.hitTest(point, with: nil) === control)
                        actions.isHidden = false
                    }
                }
            }
        }
    }

    func testHeaderIconsFollowBodySizeIncludingTranslationProgress() throws {
        let view = StatusTopEndView()
        for category in [UIContentSizeCategory.medium, .extraExtraExtraLarge] {
            let font = UIFont.preferredFont(forTextStyle: .body,
                compatibleWith: UITraitCollection(preferredContentSizeCategory: category))
            for state in [TranslationDisplayState.translated, .translating, .failed] {
                let post = replyPost("icons", translationDisplayState: state, visibility: .public)
                view.configure(post: post, showPlatformLogo: true, absoluteTimestamp: false, isDetail: false,
                               showAgentInsight: false, onInsightTapped: nil, preferredContentSizeCategory: category)
                view.frame = CGRect(origin: .zero, size: view.intrinsicContentSize)
                view.layoutIfNeeded()
                for icon in view.subviews.compactMap({ $0 as? UIImageView }).filter({ !$0.isHidden }) {
                    XCTAssertEqual(icon.bounds.height, font.pointSize)
                }
                let badge = try XCTUnwrap(view.subviews.compactMap { $0 as? TranslateStatusStateView }.first)
                badge.layoutIfNeeded()
                XCTAssertEqual(badge.bounds.height, font.pointSize)
                if state == .translating {
                    let spinner = try XCTUnwrap(badge.subviews.compactMap { $0 as? UIActivityIndicatorView }.first)
                    XCTAssertEqual(spinner.frame.height, font.pointSize, accuracy: 0.01)
                }
                let timestamp = try XCTUnwrap(view.subviews.compactMap { $0 as? DateTimeUILabel }.first)
                XCTAssertEqual(timestamp.font.pointSize, font.pointSize)
                let textHeight = timestamp.sizeThatFits(CGSize(width: 1_000, height: 1_000)).height
                XCTAssertLessThanOrEqual(view.bounds.height, ceil(textHeight))
            }
        }
    }

    func testMediaQuoteAndActionsUseTheSameSpacingGrid() throws {
        let row = try XCTUnwrap(makeTimelineTestRow(1, mediaCount: 1, textRepeatCount: 0) as? UiTimelineV2.TimelinePostItem)
        let post = replyPost("attachments", text: "Two lines of text.\nThe image follows below.", user: row.post.user,
                             replyToHandle: nil, images: row.post.images, actions: row.post.actions)
        let quote = replyPost("quote", text: "The quoted text.", user: row.post.user, replyToHandle: nil)
        let view = StatusUIKitView()
        view.configure(data: post, appearance: StatusUIKitAppearance(timeline: TimelineAppearance.companion.Default),
                       showTranslate: false, quotes: [quote])
        view.prepareForFitting(width: 382)
        view.frame = CGRect(x: 0, y: 0, width: 382, height: try XCTUnwrap(view.timelineHeight(for: 382)))
        view.layoutIfNeeded()
        let body = try XCTUnwrap(view.subviews.compactMap { $0 as? RichTextUIView }.first)
        let media = try XCTUnwrap(view.subviews.compactMap { $0 as? StatusMediaContentUIView }.first)
        let mediaTile = try XCTUnwrap(descendants(media).first { $0.layer.borderWidth > 0 })
        XCTAssertEqual(mediaTile.layer.borderColor, UIColor.separator.resolvedColor(with: mediaTile.traitCollection).cgColor)
        XCTAssertEqual(mediaTile.layer.borderWidth * max(mediaTile.traitCollection.displayScale, 1), 1, accuracy: 0.01)
        let quoted = try XCTUnwrap(descendants(view).compactMap { $0 as? StatusUIKitView }.first { $0 !== view })
        let quoteContainer = try XCTUnwrap(quoted.superview)
        let quoteHeader = try XCTUnwrap(quoted.subviews.first { $0 is UserOnelineUIView })
        let quoteBody = try XCTUnwrap(quoted.subviews.first { $0 is RichTextUIView })
        let actions = try XCTUnwrap(descendants(view).compactMap { $0 as? StatusActionsUIView }.first)
        let actionFrame = actions.convert(actions.bounds, to: view)
        XCTAssertEqual(media.frame.minY - body.frame.maxY, 10)
        XCTAssertEqual(quoteContainer.frame.minY - media.frame.maxY, 8)
        XCTAssertEqual(quoted.frame.minX, 12)
        XCTAssertEqual(quoted.frame.minY, 12)
        XCTAssertEqual(quoteBody.frame.minY - quoteHeader.frame.maxY, 4)
        XCTAssertEqual(actionFrame.minY - quoteContainer.frame.maxY, 2)
        XCTAssertEqual(actionFrame.minX, body.frame.minX - 4)
        XCTAssertEqual(actionFrame.width, body.frame.width + 8)

        let borderedViews = [mediaTile, quoteContainer, StatusCardUIView(cornerRadius: 16), StatusCompatCardUIView(cornerRadius: 16)]
        for style in [UIUserInterfaceStyle.light, .dark] {
            for borderedView in borderedViews {
                borderedView.traitOverrides.userInterfaceStyle = style
                borderedView.updateTraitsIfNeeded()
                XCTAssertEqual(borderedView.traitCollection.userInterfaceStyle, style)
                XCTAssertEqual(borderedView.layer.borderColor,
                               UIColor.separator.resolvedColor(with: borderedView.traitCollection).cgColor)
            }
        }
    }

    func testMediaOverflowIsInsideTheBorderAndClearsOnReuse() throws {
        let view = StatusMediaUIView()
        let image = UiMediaImage(url: URL(fileURLWithPath: "/dev/null").absoluteString, proxy: ProxyRef.Unbound.shared)

        func configure(count: Int) throws {
            view.configure(data: Array(repeating: image, count: count), sensitive: false, cornerRadius: 16,
                           singleFollowsImageAspect: true, limitMediaGridToNine: true, mediaLayout: .grid,
                           carouselLeadingPadding: 0, carouselTrailingPadding: 0)
            view.frame = CGRect(x: 0, y: 0, width: 340, height: try XCTUnwrap(view.timelineHeight(for: 340)))
            view.layoutIfNeeded()
        }

        try configure(count: 10)
        let label = try XCTUnwrap(descendants(view).compactMap { $0 as? UILabel }.first { $0.text == "+1" })
        let overlay = try XCTUnwrap(label.superview)
        let tile = try XCTUnwrap(overlay.superview)
        XCTAssertEqual(tile.layer.borderColor, UIColor.separator.resolvedColor(with: tile.traitCollection).cgColor)
        XCTAssertEqual(overlay.frame.origin, tile.bounds.origin)
        XCTAssertEqual(overlay.frame.width, tile.bounds.width, accuracy: 0.001)
        XCTAssertEqual(overlay.frame.height, tile.bounds.height, accuracy: 0.001)
        try configure(count: 2)
        XCTAssertNil(overlay.superview)
    }

    private func descendants(_ view: UIView) -> [UIView] {
        [view] + view.subviews.flatMap(descendants)
    }

    private func replyPost(_ id: String, text: String = "Reply body", user: UiProfile? = nil,
                           replyToHandle: String? = "@context", images: [UiMedia] = [], actions: [ActionMenu] = [],
                           translationDisplayState: TranslationDisplayState = .hidden,
                           visibility: UiTimelineV2.PostVisibility? = nil, createdAt: UiDateTime? = nil) -> UiTimelineV2.Post {
        let style = RenderTextStyle(link: nil, bold: false, italic: false, strikethrough: false,
                                    monospace: false, code: false, underline: false, small: false, time: false, imageLink: nil)
        let rich = UiRichText(renderRuns: text.isEmpty ? [] : [RenderContent.Text(runs: [RenderRun.Text(text: text, style: style)], block: RenderBlockStyle())],
                              isRtl: false, raw: text, innerText: text, imageUrls: [])
        return UiTimelineV2.Post(platformId: "mastodon", images: images, sensitive: false, contentWarning: nil,
                                user: user, platformIcon: IconType.Material(icon: .world), sourceLanguages: [], translationDisplayState: translationDisplayState,
                                content: UiTranslatableText(original: rich, translation: nil), actions: actions, poll: nil,
                                statusKey: MicroBlogKey(id: id, host: "example.invalid"), card: nil,
                                createdAt: createdAt ?? KotlinInstant.companion.fromEpochMilliseconds(epochMilliseconds: 0).toUi(),
                                emojiReactions: [], sourceChannel: nil, visibility: visibility, replyToHandle: replyToHandle,
                                references: [], clickEvent: ClickEventNoop.shared, mediaClickPolicy: .openStatusMedia,
                                accountType: AccountType.Guest.shared, itemKey: id, pluginId: nil, authorSubtitleInternal: nil)
    }
}
