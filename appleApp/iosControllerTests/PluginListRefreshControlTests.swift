import XCTest
import UIKit
@testable import FlareAppleUI

@MainActor
final class PluginListRefreshControlTests: XCTestCase {
    func testBusyUsesAndFinishesTheExistingNativeRefreshControl() throws {
        let (window, list) = try hostedList()
        defer { window.isHidden = true }
        let control = UIRefreshControl()
        list.refreshControl = control
        list.layoutIfNeeded()
        list.requestedOffset = nil
        updatePluginListRefreshControl(list, isRefreshing: true)
        XCTAssertTrue(list.refreshControl === control)
        XCTAssertTrue(control.isRefreshing)
        XCTAssertEqual(list.requestedOffset?.y, -max(control.bounds.height, 60))
        list.requestedOffset = nil
        updatePluginListRefreshControl(list, isRefreshing: true)
        XCTAssertNil(list.requestedOffset)
        updatePluginListRefreshControl(list, isRefreshing: false)
        XCTAssertFalse(control.isRefreshing)
    }

    func testBusyDoesNotMoveAListThatHasScrolledAwayFromTheTop() throws {
        let (window, list) = try hostedList()
        defer { window.isHidden = true }
        list.refreshControl = UIRefreshControl()
        list.contentOffset = CGPoint(x: 0, y: 100)
        list.layoutIfNeeded()
        list.requestedOffset = nil
        updatePluginListRefreshControl(list, isRefreshing: true)
        XCTAssertTrue(list.refreshControl!.isRefreshing)
        XCTAssertEqual(list.contentOffset.y, 100)
    }

    func testIntrospectionBeforeNativeRefreshControlIsAttachedIsHarmless() throws {
        let (window, list) = try hostedList()
        defer { window.isHidden = true }
        updatePluginListRefreshControl(list, isRefreshing: true)
        updatePluginListRefreshControl(list, isRefreshing: false)
        XCTAssertNil(list.refreshControl)
        XCTAssertNil(list.requestedOffset)
    }

    func testBusyWaitsUntilTheListIsOnscreen() {
        let list = RecordingScrollView()
        list.refreshControl = UIRefreshControl()
        updatePluginListRefreshControl(list, isRefreshing: true)
        XCTAssertFalse(list.refreshControl!.isRefreshing)
        XCTAssertNil(list.requestedOffset)
    }

    private func hostedList() throws -> (UIWindow, RecordingScrollView) {
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        window.rootViewController = UIViewController()
        let list = RecordingScrollView(frame: window.bounds)
        list.contentInsetAdjustmentBehavior = .never
        list.contentSize = CGSize(width: window.bounds.width, height: 2000)
        window.rootViewController!.view.addSubview(list)
        window.makeKeyAndVisible()
        window.layoutIfNeeded()
        list.layoutIfNeeded()
        list.requestedOffset = nil
        return (window, list)
    }
}

@MainActor
private final class RecordingScrollView: UIScrollView {
    var requestedOffset: CGPoint?

    override func setContentOffset(_ contentOffset: CGPoint, animated: Bool) {
        requestedOffset = contentOffset
        super.setContentOffset(contentOffset, animated: false)
    }
}
