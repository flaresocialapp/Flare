import AVFoundation
import Combine
import FlareAppleUI
import SwiftUI
import UIKit
import XCTest

@MainActor
final class VideoControlViewTests: XCTestCase {
    func testLoadingAfterSeekPreservesProgressAndDuration() async throws {
        let fixture = try await Fixture()
        defer { fixture.close() }
        fixture.state.time = CMTime(seconds: 45, preferredTimescale: 600)
        try await fixture.settle()
        // SwiftUI maps 45 of 90 seconds to the native slider's normalized midpoint.
        XCTAssertEqual(try fixture.slider().value, 0.5, accuracy: 0.001)

        fixture.state.isPlaying = true
        fixture.state.videoState = .loading
        try await fixture.settle()
        XCTAssertEqual(try fixture.slider().value, 0.5, accuracy: 0.001)

        fixture.state.videoState = .playing(90)
        try await fixture.settle()
        XCTAssertGreaterThanOrEqual(try fixture.slider().value, 0.5)
    }

    func testActualDurationChangesStillClampProgress() async throws {
        let fixture = try await Fixture()
        defer { fixture.close() }
        fixture.state.time = CMTime(seconds: 45, preferredTimescale: 600)
        try await fixture.settle()

        fixture.state.videoState = .paused(30)
        try await fixture.settle()
        XCTAssertEqual(try fixture.slider().value, 1, accuracy: 0.001)
        fixture.state.videoState = .loading
        try await fixture.settle()
        XCTAssertEqual(try fixture.slider().value, 1, accuracy: 0.001)

        fixture.state.videoState = .paused(60)
        try await fixture.settle()
        XCTAssertEqual(try fixture.slider().value, 0.5, accuracy: 0.001)
    }

    func testNewMediaStartsWithoutThePreviousDurationAndLearnsItsOwn() async throws {
        let fixture = try await Fixture(videoState: .loading)
        defer { fixture.close() }
        let initialLoadingProgress = try fixture.slider().value

        fixture.state.videoState = .paused(90)
        try await fixture.settle()
        fixture.state.videoState = .loading
        try await fixture.settle()
        XCTAssertEqual(try fixture.slider().value, 0.111_111, accuracy: 0.001)

        fixture.state.mediaID += 1
        try await fixture.settle()
        XCTAssertEqual(try fixture.slider().value, initialLoadingProgress, accuracy: 0.001)
        fixture.state.videoState = .paused(20)
        try await fixture.settle()
        XCTAssertEqual(try fixture.slider().value, 0.5, accuracy: 0.001)
    }

    @MainActor
    private final class Fixture {
        let state: ControlState
        let host: UIHostingController<ControlPage>
        let window: UIWindow
        private let previousKeyWindow: UIWindow?

        init(videoState: VideoState = .paused(90)) async throws {
            state = ControlState(videoState: videoState)
            host = UIHostingController(rootView: ControlPage(state: state))
            let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
            previousKeyWindow = scene.windows.first(where: \.isKeyWindow)
            window = UIWindow(windowScene: scene)
            window.rootViewController = host
            window.makeKeyAndVisible()
            try await settle()
        }

        func close() {
            window.isHidden = true
            window.rootViewController = nil
            previousKeyWindow?.makeKey()
        }

        func settle() async throws {
            window.layoutIfNeeded()
            try await Task.sleep(for: .milliseconds(50))
            window.layoutIfNeeded()
        }

        func slider() throws -> UISlider {
            func find(in view: UIView) -> UISlider? {
                if let slider = view as? UISlider { return slider }
                return view.subviews.lazy.compactMap { find(in: $0) }.first
            }
            return try XCTUnwrap(find(in: host.view))
        }
    }

    @MainActor
    private final class ControlState: ObservableObject {
        @Published var isPlaying = false
        @Published var time = CMTime(seconds: 10, preferredTimescale: 600)
        @Published var videoState: VideoState
        @Published var mediaID = 0

        init(videoState: VideoState) { self.videoState = videoState }
    }

    private struct ControlPage: View {
        @ObservedObject var state: ControlState

        var body: some View {
            VideoControlView(isPlaying: $state.isPlaying, currentTime: $state.time,
                             videoState: state.videoState, playbackRate: 1)
                .id(state.mediaID)
                .padding()
        }
    }
}
