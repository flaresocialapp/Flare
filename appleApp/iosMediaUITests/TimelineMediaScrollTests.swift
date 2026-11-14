import XCTest

final class TimelineMediaScrollTests: XCTestCase {
    @MainActor
    func testMediaStaysStableWhenNavigationBarReappearsWhileScrollingBack() throws {
        try assertReverseScrolling(fast: false)
    }

    @MainActor
    func testMediaStaysStableDuringFastReverseScrolling() throws {
        try assertReverseScrolling(fast: true)
    }

    @MainActor
    func testMediaLimitUpdatesAfterRotation() throws {
        let app = openTimeline()
        defer { XCUIDevice.shared.orientation = .portrait; app.terminate() }
        let portraitHeight = try XCTUnwrap(measurements(in: app)["mediaHeight"])
        XCTAssertGreaterThan(portraitHeight, 250)
        XCUIDevice.shared.orientation = .landscapeLeft
        let smaller = NSPredicate { _, _ in
            guard let height = try? self.measurements(in: app)["mediaHeight"] else { return false }
            return height > 0 && height < portraitHeight - 50
        }
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: smaller, object: nil)], timeout: 5), .completed)
        XCUIDevice.shared.orientation = .portrait
        let restored = NSPredicate { _, _ in
            guard let height = try? self.measurements(in: app)["mediaHeight"] else { return false }
            return abs(height - portraitHeight) <= 1
        }
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: restored, object: nil)], timeout: 5), .completed)
    }

    @MainActor
    func testCarouselContinuesScrollingDuringDeceleration() throws {
        let app = openTimeline(carousel: true)
        defer { app.terminate() }

        let carousel = app.collectionViews["timeline-media-carousel"].firstMatch
        XCTAssertTrue(carousel.waitForExistence(timeout: 10))
        let initialTimelineOffsetY = try XCTUnwrap(measurements(in: app)["timelineOffsetY"])

        // Vertical drag starting on an idle carousel must still scroll the outer timeline.
        let center = carousel.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        let above = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.2))
        center.press(forDuration: 0.05, thenDragTo: above, withVelocity: .slow, thenHoldForDuration: 0.2)
        let verticalMetrics = try measurements(in: app)
        XCTAssertGreaterThan(try XCTUnwrap(verticalMetrics["timelineOffsetY"]) - initialTimelineOffsetY, 60)
        XCTAssertEqual(try XCTUnwrap(verticalMetrics["carouselOffsetX"]), 0, accuracy: 2)

        let list = app.collectionViews["timeline-list"]
        list.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.25)).press(
            forDuration: 0.05,
            thenDragTo: list.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.85)),
            withVelocity: .slow,
            thenHoldForDuration: 0.2
        )
        app.buttons["timeline-scroll-reset"].tap()
        XCTAssertTrue(carousel.waitForExistence(timeout: 5))
        let restingTimelineOffsetY = try XCTUnwrap(measurements(in: app)["timelineOffsetY"])

        let frame = carousel.frame
        let origin = app.coordinate(withNormalizedOffset: .zero)
        let right = origin.withOffset(CGVector(dx: frame.minX + frame.width * 0.85, dy: frame.midY))
        let left = origin.withOffset(CGVector(dx: frame.minX + frame.width * 0.15, dy: frame.midY))

        // 1. Continue scrolling forward in the same direction during inertia.
        try withQuiescenceSkipped(in: app) {
            right.press(forDuration: 0.05, thenDragTo: left, withVelocity: .fast, thenHoldForDuration: 0)
            try waitForDeceleration(in: app, minimumOffsetX: 60)
            right.press(forDuration: 0.02, thenDragTo: left, withVelocity: .slow, thenHoldForDuration: 0.25)
        }

        let forwardRaw = app.staticTexts["timeline-scroll-metrics"].label
        let afterContinue = try measurements(in: app)
        XCTAssertEqual(try XCTUnwrap(afterContinue["touchesDuringDeceleration"]), 1, forwardRaw)
        XCTAssertEqual(try XCTUnwrap(afterContinue["decelerationCatchDrags"]), 1, forwardRaw)
        let forwardTouchOffset = try XCTUnwrap(afterContinue["decelerationTouchOffsetX"])
        let forwardFinalOffset = try XCTUnwrap(afterContinue["carouselOffsetX"])
        XCTAssertGreaterThan(
            forwardFinalOffset - forwardTouchOffset, 120,
            "Carousel must continue scrolling forward when dragged during inertia: \(forwardRaw)"
        )
        XCTAssertEqual(try XCTUnwrap(afterContinue["timelineOffsetY"]), restingTimelineOffsetY, accuracy: 2, forwardRaw)

        // 2. Catch and reverse scrolling direction during inertia.
        try withQuiescenceSkipped(in: app) {
            right.press(forDuration: 0.05, thenDragTo: left, withVelocity: .fast, thenHoldForDuration: 0)
            try waitForDeceleration(in: app, minimumOffsetX: forwardFinalOffset + 60)
            left.press(forDuration: 0.02, thenDragTo: right, withVelocity: .slow, thenHoldForDuration: 0.25)
        }

        let reverseRaw = app.staticTexts["timeline-scroll-metrics"].label
        let afterReverse = try measurements(in: app)
        XCTAssertEqual(try XCTUnwrap(afterReverse["touchesDuringDeceleration"]), 2, reverseRaw)
        XCTAssertEqual(try XCTUnwrap(afterReverse["decelerationCatchDrags"]), 2, reverseRaw)
        let reverseTouchOffset = try XCTUnwrap(afterReverse["decelerationTouchOffsetX"])
        let reverseFinalOffset = try XCTUnwrap(afterReverse["carouselOffsetX"])
        XCTAssertGreaterThan(
            reverseTouchOffset - reverseFinalOffset, 120,
            "Carousel must reverse scrolling when caught and dragged back during inertia: \(reverseRaw)"
        )
        XCTAssertEqual(try XCTUnwrap(afterReverse["timelineOffsetY"]), restingTimelineOffsetY, accuracy: 2, reverseRaw)
    }

    @MainActor
    private func withQuiescenceSkipped(in app: XCUIApplication, _ body: () throws -> Void) rethrows {
        let selector = Selector(("setCurrentInteractionOptions:"))
        guard app.responds(to: selector) else {
            XCTFail("XCUIApplication does not respond to setCurrentInteractionOptions:")
            return
        }
        // 0x1 | 0x2 skips pre- and post-event quiescence so follow-up gestures land mid-deceleration.
        app.setValue(0x3, forKey: "currentInteractionOptions")
        defer { app.setValue(0, forKey: "currentInteractionOptions") }
        try body()
    }

    @MainActor
    private func waitForDeceleration(in app: XCUIApplication, minimumOffsetX: Double, timeout: TimeInterval = 3) throws {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if let values = try? measurements(in: app),
               (values["carouselDecelerating"] ?? 0) == 1,
               (values["carouselOffsetX"] ?? 0) >= minimumOffsetX {
                return
            }
            RunLoop.current.run(until: Date().addingTimeInterval(0.03))
        }
        XCTFail("Carousel did not enter momentum deceleration past \(minimumOffsetX)pt: \(app.staticTexts["timeline-scroll-metrics"].label)")
    }

    @MainActor
    private func assertReverseScrolling(fast: Bool) throws {
        guard #available(iOS 27.0, *) else { throw XCTSkip("Navigation bar minimization requires iOS 27") }
        let app = openTimeline()
        defer { app.terminate() }
        let list = app.collectionViews["timeline-list"]
        let high = list.coordinate(withNormalizedOffset: CGVector(dx: 0.7, dy: 0.25))
        let low = list.coordinate(withNormalizedOffset: CGVector(dx: 0.7, dy: 0.85))
        for _ in 0..<(fast ? 4 : 2) {
            low.press(forDuration: 0.1, thenDragTo: high, withVelocity: fast ? .fast : .slow,
                      thenHoldForDuration: fast ? 0 : 0.15)
        }
        try assertStableMeasurements(in: app)
        app.buttons["timeline-scroll-reset"].tap()
        for _ in 0..<(fast ? 3 : 1) {
            high.press(forDuration: 0.1, thenDragTo: low, withVelocity: fast ? .fast : .slow,
                       thenHoldForDuration: fast ? 0 : 0.15)
        }
        try assertStableMeasurements(in: app)
    }

    @MainActor
    private func openTimeline(carousel: Bool = false) -> XCUIApplication {
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        app.launchArguments = ["--timeline-media-scroll-test", "-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        if carousel { app.launchArguments.append("--timeline-media-carousel") }
        app.launch()
        let list = app.collectionViews["timeline-list"]
        XCTAssertTrue(list.waitForExistence(timeout: 20))
        return app
    }

    @MainActor
    private func assertStableMeasurements(in app: XCUIApplication) throws {
        let raw = app.staticTexts["timeline-scroll-metrics"].label
        let values = try measurements(in: app)
        XCTAssertGreaterThan(try XCTUnwrap(values["samples"]), 30, raw)
        XCTAssertGreaterThan(try XCTUnwrap(values["topInsetRange"]), 20, "The navigation bar must actually animate: \(raw)")
        XCTAssertLessThan(try XCTUnwrap(values["maxError"]), 20, raw)
        XCTAssertLessThanOrEqual(try XCTUnwrap(values["maxHeightChange"]), 1, raw)
    }

    @MainActor
    private func measurements(in app: XCUIApplication) throws -> [String: Double] {
        let raw = app.staticTexts["timeline-scroll-metrics"].label
        return try XCTUnwrap(JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Double])
    }
}
