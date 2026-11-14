import XCTest
import UIKit

final class MediaViewerDismissGesturePolicyTests: XCTestCase {
    @MainActor
    func testDismissGestureIncludesEmbeddedHostingViewsButExcludesPresentedSheet() {
        let viewer = UIViewController()
        let pager = UIViewController()
        let image = UIViewController()
        viewer.addChild(pager)
        viewer.view.addSubview(pager.view)
        // LazyPager adds the hosting view without adding its controller as a child.
        pager.view.addSubview(image.view)
        let sheet = UIViewController()
        let sheetContent = UIViewController()
        sheet.addChild(sheetContent)
        sheet.view.addSubview(sheetContent.view)

        XCTAssertNil(image.parent)
        XCTAssertTrue(MediaViewerDismissGesturePolicy.belongsToViewer(viewer.view, viewer: viewer.view))
        XCTAssertTrue(MediaViewerDismissGesturePolicy.belongsToViewer(image.view, viewer: viewer.view))
        XCTAssertFalse(MediaViewerDismissGesturePolicy.belongsToViewer(sheetContent.view, viewer: viewer.view))
    }

    func testFastVerticalSwipesDismissInBothDirections() {
        XCTAssertTrue(
            MediaViewerDismissGesturePolicy.shouldDismiss(
                translationY: 40,
                velocityY: 1_301,
                containerHeight: 800
            )
        )
        XCTAssertTrue(
            MediaViewerDismissGesturePolicy.shouldDismiss(
                translationY: -40,
                velocityY: -1_301,
                containerHeight: 800
            )
        )
    }

    func testLongVerticalDragsDismissInBothDirections() {
        XCTAssertTrue(
            MediaViewerDismissGesturePolicy.shouldDismiss(
                translationY: 401,
                velocityY: 0,
                containerHeight: 800
            )
        )
        XCTAssertTrue(
            MediaViewerDismissGesturePolicy.shouldDismiss(
                translationY: -401,
                velocityY: 0,
                containerHeight: 800
            )
        )
    }

    func testVelocityTriggeredDismissUsesReleaseDirection() {
        XCTAssertEqual(
            MediaViewerDismissGesturePolicy.dismissDirection(
                translationY: 40,
                velocityY: -1_301
            ),
            -1
        )
        XCTAssertEqual(
            MediaViewerDismissGesturePolicy.dismissDirection(
                translationY: -40,
                velocityY: 1_301
            ),
            1
        )
    }

    func testDistanceTriggeredDismissUsesTranslationDirection() {
        XCTAssertEqual(
            MediaViewerDismissGesturePolicy.dismissDirection(
                translationY: 401,
                velocityY: -500
            ),
            1
        )
        XCTAssertEqual(
            MediaViewerDismissGesturePolicy.dismissDirection(
                translationY: -401,
                velocityY: 500
            ),
            -1
        )
    }

    func testShortSlowDragDoesNotDismiss() {
        XCTAssertFalse(
            MediaViewerDismissGesturePolicy.shouldDismiss(
                translationY: 100,
                velocityY: 100,
                containerHeight: 800
            )
        )
        XCTAssertFalse(
            MediaViewerDismissGesturePolicy.shouldDismiss(
                translationY: -100,
                velocityY: -100,
                containerHeight: 800
            )
        )
    }

    func testSlowDragPastThresholdDismissesWithoutAFlick() {
        XCTAssertTrue(
            MediaViewerDismissGesturePolicy.shouldDismiss(
                translationY: 220,
                velocityY: 0,
                containerHeight: 800
            )
        )
    }

    func testPushingBackBeforeReleaseCancelsDismiss() {
        XCTAssertFalse(
            MediaViewerDismissGesturePolicy.shouldDismiss(
                translationY: 300,
                velocityY: -800,
                containerHeight: 800
            )
        )
    }

    func testDismissTransformFollowsFingerAndShrinksWithDistance() {
        let transform = MediaViewerDismissGesturePolicy.transform(
            translation: CGPoint(x: -30, y: 200),
            startLocation: CGPoint(x: 100, y: 300),
            containerSize: CGSize(width: 400, height: 800)
        )

        XCTAssertEqual(transform.offset, CGSize(width: -30, height: 200))
        XCTAssertEqual(transform.scale, 0.9, accuracy: 0.0001)
        XCTAssertEqual(transform.anchor, CGPoint(x: 0.25, y: 0.375))
    }

    func testDismissTransformScaleBottomsOutAtMinimum() {
        let transform = MediaViewerDismissGesturePolicy.transform(
            translation: CGPoint(x: 0, y: -1_200),
            startLocation: CGPoint(x: 200, y: 400),
            containerSize: CGSize(width: 400, height: 800)
        )

        XCTAssertEqual(transform.scale, MediaViewerDismissGesturePolicy.minimumScale, accuracy: 0.0001)
    }

    func testProgressIsDirectionIndependent() {
        XCTAssertEqual(
            MediaViewerDismissGesturePolicy.progress(translationY: 200, containerHeight: 800),
            0.25
        )
        XCTAssertEqual(
            MediaViewerDismissGesturePolicy.progress(translationY: -200, containerHeight: 800),
            0.25
        )
    }

    func testOnlyMediaMovesWithDismissGesture() {
        XCTAssertEqual(
            MediaViewerDismissGesturePolicy.verticalOffset(
                for: .media,
                translationY: 160
            ),
            160
        )
        XCTAssertEqual(
            MediaViewerDismissGesturePolicy.verticalOffset(
                for: .bottomOverlay,
                translationY: 160
            ),
            0
        )
    }

    func testActiveDismissHoldsTheTouchedScrollPosition() {
        XCTAssertTrue(
            MediaViewerDismissGesturePolicy.shouldHoldTouchedScrollPosition(
                didDismiss: false
            )
        )
        XCTAssertFalse(
            MediaViewerDismissGesturePolicy.shouldHoldTouchedScrollPosition(
                didDismiss: true
            )
        )
    }

    func testDismissUsesTranslationWhenVelocityIsNotReady() {
        XCTAssertTrue(
            MediaViewerDismissGesturePolicy.shouldBegin(
                velocityX: 0,
                velocityY: 0,
                zoomScale: 1,
                minimumZoomScale: 1,
                translationX: 2,
                translationY: 14
            )
        )
        XCTAssertFalse(
            MediaViewerDismissGesturePolicy.shouldBegin(
                velocityX: 0,
                velocityY: 0,
                zoomScale: 1,
                minimumZoomScale: 1,
                translationX: 14,
                translationY: 2
            )
        )
        XCTAssertFalse(
            MediaViewerDismissGesturePolicy.shouldBegin(
                velocityX: 0,
                velocityY: 0,
                zoomScale: 2,
                minimumZoomScale: 1,
                translationX: 1,
                translationY: 16
            )
        )
    }

    func testHorizontalPanYieldsBeforeAVelocitySample() {
        XCTAssertTrue(
            MediaViewerDismissGesturePolicy.shouldYieldToHorizontalPan(
                translationX: 16,
                translationY: 4
            )
        )
        XCTAssertFalse(
            MediaViewerDismissGesturePolicy.shouldYieldToHorizontalPan(
                translationX: 6,
                translationY: 2
            )
        )
        XCTAssertFalse(
            MediaViewerDismissGesturePolicy.shouldYieldToHorizontalPan(
                translationX: 4,
                translationY: 16
            )
        )
    }

    func testDismissPanDoesNotShareTheDrag() {
        XCTAssertFalse(
            MediaViewerDismissGesturePolicy.allowsSimultaneousRecognition(
                chromeTap: false,
                otherIsTap: false
            )
        )
        XCTAssertFalse(
            MediaViewerDismissGesturePolicy.allowsSimultaneousRecognition(
                chromeTap: false,
                otherIsTap: true
            )
        )
        XCTAssertTrue(
            MediaViewerDismissGesturePolicy.allowsSimultaneousRecognition(
                chromeTap: true,
                otherIsTap: true
            )
        )
    }

    func testAnimatedZoomContentInsetIsAppliedImmediately() {
        XCTAssertTrue(
            MediaViewerDismissGesturePolicy.shouldDiscardAnimatedContentInset(
                viewClassName: "ZoomableView<UiMediaImage, Image>",
                inheritedAnimationDuration: 0.3
            )
        )
        XCTAssertTrue(
            MediaViewerDismissGesturePolicy.shouldDiscardAnimatedContentInset(
                viewClassName: "UIScrollView",
                inheritedAnimationDuration: 0.3,
                runtimeClassName: "_TtGC9LazyPager12ZoomableView"
            )
        )
        XCTAssertFalse(
            MediaViewerDismissGesturePolicy.shouldDiscardAnimatedContentInset(
                viewClassName: "ZoomableView<UiMediaImage, Image>",
                inheritedAnimationDuration: 0
            )
        )
        XCTAssertFalse(
            MediaViewerDismissGesturePolicy.shouldDiscardAnimatedContentInset(
                viewClassName: "UIScrollView",
                inheritedAnimationDuration: 0.3
            )
        )
    }

    @MainActor
    func testDismissPanDelaysPansOwnedByTheSheet() {
        let viewer = UIView()
        let sheet = UIView()
        let sheetPan = UIPanGestureRecognizer()
        sheet.addGestureRecognizer(sheetPan)
        XCTAssertTrue(
            MediaViewerDismissGesturePolicy.delaysPanOutsideViewer(sheetPan, viewer: viewer)
        )

        let controls = UIView()
        viewer.addSubview(controls)
        let controlPan = UIPanGestureRecognizer()
        controls.addGestureRecognizer(controlPan)
        XCTAssertFalse(
            MediaViewerDismissGesturePolicy.delaysPanOutsideViewer(controlPan, viewer: viewer)
        )

        let scrollView = UIScrollView()
        XCTAssertFalse(
            MediaViewerDismissGesturePolicy.delaysPanOutsideViewer(
                scrollView.panGestureRecognizer,
                viewer: viewer
            )
        )
    }

    @MainActor
    func testChromeTapTargetsThePager() {
        let scrollView = UIScrollView()
        let image = UIView()
        scrollView.addSubview(image)
        XCTAssertTrue(MediaViewerDismissGesturePolicy.touchesPager(image))
        XCTAssertTrue(MediaViewerDismissGesturePolicy.touchesPager(scrollView))
        XCTAssertFalse(MediaViewerDismissGesturePolicy.touchesPager(UIButton()))
    }

    func testDismissGestureBeginsOnlyForUnzoomedVerticalPans() {
        XCTAssertTrue(
            MediaViewerDismissGesturePolicy.shouldBegin(
                velocityX: 20,
                velocityY: -200,
                zoomScale: 1,
                minimumZoomScale: 1
            )
        )
        XCTAssertFalse(
            MediaViewerDismissGesturePolicy.shouldBegin(
                velocityX: 200,
                velocityY: 20,
                zoomScale: 1,
                minimumZoomScale: 1
            )
        )
        XCTAssertFalse(
            MediaViewerDismissGesturePolicy.shouldBegin(
                velocityX: 20,
                velocityY: 200,
                zoomScale: 2,
                minimumZoomScale: 1
            )
        )
    }

    func testDismissGestureTakesPriorityOnlyOverScrollViewPans() async {
        await MainActor.run {
            let scrollView = UIScrollView()
            XCTAssertTrue(
                MediaViewerDismissGesturePolicy.shouldTakePriority(
                    over: scrollView.panGestureRecognizer
                )
            )

            let ordinaryView = UIView()
            let ordinaryPan = UIPanGestureRecognizer()
            ordinaryView.addGestureRecognizer(ordinaryPan)
            XCTAssertFalse(
                MediaViewerDismissGesturePolicy.shouldTakePriority(
                    over: ordinaryPan
                )
            )
        }
    }

    func testDismissGestureDoesNotTakePriorityOverScrollableVerticalContent() async {
        await MainActor.run {
            let scrollView = UIScrollView(frame: CGRect(x: 0, y: 0, width: 390, height: 600))
            scrollView.contentSize = CGSize(width: 390, height: 1_200)
            scrollView.contentOffset = CGPoint(x: 0, y: 300)

            XCTAssertFalse(
                MediaViewerDismissGesturePolicy.shouldTakePriority(
                    over: scrollView.panGestureRecognizer,
                    velocityY: -500
                )
            )
        }
    }

    func testTallImageScrollAllowsDismissOnlyPastTheMatchingEdge() async {
        await MainActor.run {
            let scrollView = Self.makeTallImageScrollView()

            scrollView.contentOffset.y = 300
            XCTAssertFalse(
                MediaViewerDismissGesturePolicy.shouldBeginDismissPan(
                    in: scrollView,
                    velocityY: 500
                )
            )
            XCTAssertFalse(
                MediaViewerDismissGesturePolicy.shouldBeginDismissPan(
                    in: scrollView,
                    velocityY: -500
                )
            )

            // 30pt below the top edge is outside the relaxed tolerance.
            scrollView.contentOffset.y = 10
            XCTAssertFalse(
                MediaViewerDismissGesturePolicy.shouldBeginDismissPan(
                    in: scrollView,
                    velocityY: 500
                )
            )

            // 20pt below the top edge still counts as resting at the edge.
            scrollView.contentOffset.y = 0
            XCTAssertTrue(
                MediaViewerDismissGesturePolicy.shouldBeginDismissPan(
                    in: scrollView,
                    velocityY: 500
                )
            )
            XCTAssertFalse(
                MediaViewerDismissGesturePolicy.shouldBeginDismissPan(
                    in: scrollView,
                    velocityY: -500
                )
            )

            scrollView.contentOffset.y = -20
            XCTAssertTrue(
                MediaViewerDismissGesturePolicy.shouldBeginDismissPan(
                    in: scrollView,
                    velocityY: 500
                )
            )
            XCTAssertTrue(
                MediaViewerDismissGesturePolicy.shouldBeginDismissPan(
                    in: scrollView,
                    velocityY: 0,
                    translationY: 12
                )
            )
            XCTAssertFalse(
                MediaViewerDismissGesturePolicy.shouldBeginDismissPan(
                    in: scrollView,
                    velocityY: 0,
                    translationY: -12
                )
            )
            XCTAssertFalse(
                MediaViewerDismissGesturePolicy.shouldBeginDismissPan(
                    in: scrollView,
                    velocityY: -500
                )
            )

            scrollView.contentOffset.y = 600
            XCTAssertFalse(
                MediaViewerDismissGesturePolicy.shouldBeginDismissPan(
                    in: scrollView,
                    velocityY: -500
                )
            )

            scrollView.contentOffset.y = 630
            XCTAssertFalse(
                MediaViewerDismissGesturePolicy.shouldBeginDismissPan(
                    in: scrollView,
                    velocityY: 500
                )
            )
            XCTAssertTrue(
                MediaViewerDismissGesturePolicy.shouldBeginDismissPan(
                    in: scrollView,
                    velocityY: -500
                )
            )
        }
    }

    func testNonScrollableContentAllowsDismissInBothDirections() async {
        await MainActor.run {
            let scrollView = UIScrollView(frame: CGRect(x: 0, y: 0, width: 390, height: 600))
            scrollView.contentSize = CGSize(width: 390, height: 600)

            XCTAssertTrue(
                MediaViewerDismissGesturePolicy.shouldBeginDismissPan(
                    in: scrollView,
                    velocityY: 500
                )
            )
            XCTAssertTrue(
                MediaViewerDismissGesturePolicy.shouldBeginDismissPan(
                    in: scrollView,
                    velocityY: -500
                )
            )
        }
    }

    func testOverscrollEdgeRequiresReachingTheEdgeAndPushingPastIt() {
        let edge = { (offset: CGFloat, velocity: CGFloat) in
            MediaViewerDismissGesturePolicy.overscrollEdge(
                contentOffsetY: offset,
                minimumOffsetY: -20,
                maximumOffsetY: 630,
                fingerVelocityY: velocity
            )
        }
        // Mid-image drags keep scrolling.
        XCTAssertNil(edge(300, 800))
        XCTAssertNil(edge(300, -800))
        // Close to, but not at, the edge keeps scrolling so the image can finish reaching it.
        XCTAssertNil(edge(0, 800))
        XCTAssertNil(edge(600, -800))
        // At or rubber-banding past the top while pulling down.
        XCTAssertEqual(edge(-20, 800), 1)
        XCTAssertEqual(edge(-35, 800), 1)
        XCTAssertNil(edge(-20, -800))
        // At or past the bottom while pushing up.
        XCTAssertEqual(edge(630, -800), -1)
        XCTAssertEqual(edge(650, -800), -1)
        XCTAssertNil(edge(630, 800))
        // Content that does not scroll is handled by the regular dismiss pan.
        XCTAssertNil(
            MediaViewerDismissGesturePolicy.overscrollEdge(
                contentOffsetY: 0,
                minimumOffsetY: 0,
                maximumOffsetY: 0,
                fingerVelocityY: 800
            )
        )
    }

    func testOverscrollHandoffStartsFromTheRubberBandDistance() {
        XCTAssertEqual(
            MediaViewerDismissGesturePolicy.overscrollAmount(contentOffsetY: -35, edgeOffsetY: -20, edge: 1),
            15
        )
        XCTAssertEqual(
            MediaViewerDismissGesturePolicy.overscrollAmount(contentOffsetY: 650, edgeOffsetY: 630, edge: -1),
            20
        )
        XCTAssertEqual(
            MediaViewerDismissGesturePolicy.overscrollAmount(contentOffsetY: -10, edgeOffsetY: -20, edge: 1),
            0
        )
    }

    func testOverscrollTranslationEndsWhenTheFingerReturnsPastTheEdge() {
        XCTAssertEqual(
            MediaViewerDismissGesturePolicy.overscrollTranslationY(locationY: 460, baseLocationY: 400, edge: 1),
            60
        )
        XCTAssertNil(
            MediaViewerDismissGesturePolicy.overscrollTranslationY(locationY: 400, baseLocationY: 400, edge: 1)
        )
        XCTAssertNil(
            MediaViewerDismissGesturePolicy.overscrollTranslationY(locationY: 380, baseLocationY: 400, edge: 1)
        )
        XCTAssertEqual(
            MediaViewerDismissGesturePolicy.overscrollTranslationY(locationY: 340, baseLocationY: 400, edge: -1),
            -60
        )
        XCTAssertNil(
            MediaViewerDismissGesturePolicy.overscrollTranslationY(locationY: 420, baseLocationY: 400, edge: -1)
        )
    }

    func testOverscrollDismissNeedsLessTravelThanARegularDrag() {
        // 120pt of an 800pt page: short of a regular dismiss, enough past a tall image's edge.
        XCTAssertFalse(
            MediaViewerDismissGesturePolicy.shouldDismiss(translationY: 120, velocityY: 0, containerHeight: 800)
        )
        XCTAssertTrue(
            MediaViewerDismissGesturePolicy.shouldDismissOverscroll(
                translationY: 120, velocityY: 0, containerHeight: 800, edge: 1
            )
        )
        XCTAssertTrue(
            MediaViewerDismissGesturePolicy.shouldDismissOverscroll(
                translationY: -120, velocityY: 0, containerHeight: 800, edge: -1
            )
        )
        // A short pull released slowly springs back.
        XCTAssertFalse(
            MediaViewerDismissGesturePolicy.shouldDismissOverscroll(
                translationY: 40, velocityY: 0, containerHeight: 800, edge: 1
            )
        )
        // A short but quick pull dismisses.
        XCTAssertTrue(
            MediaViewerDismissGesturePolicy.shouldDismissOverscroll(
                translationY: 40, velocityY: 300, containerHeight: 800, edge: 1
            )
        )
    }

    func testFlickThatOnlyReachesTheEdgeDoesNotDismiss() {
        // Scrolling velocity carried into the edge is not a pull to close.
        XCTAssertFalse(
            MediaViewerDismissGesturePolicy.shouldDismissOverscroll(
                translationY: 4, velocityY: 2_000, containerHeight: 800, edge: 1
            )
        )
        XCTAssertFalse(
            MediaViewerDismissGesturePolicy.shouldDismissOverscroll(
                translationY: -20, velocityY: -2_000, containerHeight: 800, edge: -1
            )
        )
        XCTAssertTrue(
            MediaViewerDismissGesturePolicy.shouldDismissOverscroll(
                translationY: 32, velocityY: 2_000, containerHeight: 800, edge: 1
            )
        )
    }

    func testReleaseKeepsAFlingBackIntoTheImage() {
        XCTAssertTrue(
            MediaViewerDismissGesturePolicy.shouldStopScrollAtEdgeOnRelease(didDismiss: true, velocityY: -500, edge: 1)
        )
        XCTAssertTrue(
            MediaViewerDismissGesturePolicy.shouldStopScrollAtEdgeOnRelease(didDismiss: false, velocityY: 200, edge: 1)
        )
        XCTAssertFalse(
            MediaViewerDismissGesturePolicy.shouldStopScrollAtEdgeOnRelease(didDismiss: false, velocityY: -500, edge: 1)
        )
        XCTAssertFalse(
            MediaViewerDismissGesturePolicy.shouldStopScrollAtEdgeOnRelease(didDismiss: false, velocityY: 500, edge: -1)
        )
    }

    func testOverscrollDismissNeverGoesTowardsTheImage() {
        // Flinging back into the image cancels instead of dismissing the other way.
        XCTAssertFalse(
            MediaViewerDismissGesturePolicy.shouldDismissOverscroll(
                translationY: 40, velocityY: -2_000, containerHeight: 800, edge: 1
            )
        )
        XCTAssertFalse(
            MediaViewerDismissGesturePolicy.shouldDismissOverscroll(
                translationY: -40, velocityY: 2_000, containerHeight: 800, edge: -1
            )
        )
    }

    func testEdgeOffsetPinsTheMatchingEdge() async {
        await MainActor.run {
            let scrollView = Self.makeTallImageScrollView()
            XCTAssertEqual(MediaViewerDismissGesturePolicy.edgeOffsetY(in: scrollView, edge: 1), -20)
            XCTAssertEqual(MediaViewerDismissGesturePolicy.edgeOffsetY(in: scrollView, edge: -1), 630)
        }
    }

    @MainActor
    private static func makeTallImageScrollView() -> UIScrollView {
        let scrollView = UIScrollView(frame: CGRect(x: 0, y: 0, width: 390, height: 600))
        scrollView.contentInsetAdjustmentBehavior = .never
        scrollView.contentInset = UIEdgeInsets(top: 20, left: 0, bottom: 30, right: 0)
        scrollView.contentSize = CGSize(width: 390, height: 1_200)
        return scrollView
    }
}
