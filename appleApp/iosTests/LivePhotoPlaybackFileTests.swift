@testable import FlareAppleCore
import AVFoundation
import Foundation
import KotlinSharedUI
import XCTest

final class LivePhotoPlaybackFileTests: XCTestCase {
    func testRecognizesOnlyWeiboLivePhotoSources() {
        for url in [
            "https://video.weibo.com/media/play?livephoto=https%3A%2F%2Flivephoto.us.sinaimg.cn%2Fclip.mov",
            "https://livephoto.us.sinaimg.cn/clip.mov?Expires=123&ssig=signature"
        ] {
            XCTAssertTrue(LivePhotoPlaybackFile.isRequired(url), url)
        }
        for url in [
            "https://video.weibo.com/media/play?video=clip.mp4",
            "https://video.weibo.com/media/play?livephoto=",
            "https://video.weibo.com.evil.test/media/play?livephoto=clip.mov",
            "https://livephoto.us.sinaimg.cn.evil.test/clip.mov",
            "https://example.test/clip.mov"
        ] {
            XCTAssertFalse(LivePhotoPlaybackFile.isRequired(url), url)
        }
    }

    func testRewritesOnlyHEVCSampleEntriesAndKeepsMediaBytes() throws {
        let bytes = movie(sampleType: "hev1")
        let url = try temporaryFile(bytes)
        defer { try? FileManager.default.removeItem(at: url) }
        try LivePhotoPlaybackFile.normalize(url)
        let normalized = try Data(contentsOf: url)
        XCTAssertEqual(normalized.count, bytes.count)
        XCTAssertEqual(zip(bytes, normalized).filter { $0 != $1 }.count, 2)
        XCTAssertTrue(normalized.suffix(12).elementsEqual(Data("hev1-in-mdat".utf8)))
        try LivePhotoPlaybackFile.normalize(url)
        XCTAssertEqual(try Data(contentsOf: url), normalized, "Normalization must be idempotent")
    }

    func testOtherCodecsAreUnchanged() throws {
        for type in ["avc1", "hvc1"] {
            let bytes = movie(sampleType: type)
            let url = try temporaryFile(bytes)
            defer { try? FileManager.default.removeItem(at: url) }
            try LivePhotoPlaybackFile.normalize(url)
            XCTAssertEqual(try Data(contentsOf: url), bytes)
        }
    }

    func testRejectsMalformedBoxesAndIncompleteParameterSetsWithoutWriting() throws {
        let samples = [
            Data([0, 0, 0, 7]) + Data("moov".utf8),
            Data([0, 0, 0, 1]) + Data("moov".utf8) + Data(repeating: 255, count: 8),
            movie(sampleType: "hev1", complete: false),
            movie(sampleType: "hev1", entryCount: 2),
            movie(sampleType: "hev1").dropLast(1)
        ]
        for bytes in samples {
            let url = try temporaryFile(Data(bytes))
            defer { try? FileManager.default.removeItem(at: url) }
            XCTAssertThrowsError(try LivePhotoPlaybackFile.normalize(url))
            XCTAssertEqual(try Data(contentsOf: url), Data(bytes), "Validate the entire container before writing")
        }
    }

    func testSupportsExtendedAndZeroLengthContainerBoxes() throws {
        let bytes = movie(sampleType: "hev1")
        let moovSize = bytes.prefix(4).reduce(0) { ($0 << 8) | Int($1) }
        var extended = Data([0, 0, 0, 1]) + Data("moov".utf8)
        var size = UInt64(moovSize + 8).bigEndian
        withUnsafeBytes(of: &size) { extended.append(contentsOf: $0) }
        extended.append(bytes[8...])
        var zeroLength = bytes
        zeroLength.replaceSubrange(0..<4, with: [0, 0, 0, 0])
        for bytes in [extended, zeroLength] {
            let url = try temporaryFile(bytes)
            defer { try? FileManager.default.removeItem(at: url) }
            try LivePhotoPlaybackFile.normalize(url)
            XCTAssertEqual(zip(bytes, try Data(contentsOf: url)).filter { $0 != $1 }.count, 2)
        }
    }

    @MainActor
    func testPlaybackFileSurvivesHandoffAndIsRemovedWhenTheQueueIsReleased() async throws {
        let fixture = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "live-photo-hev1", withExtension: "mov"))
        let url = try temporaryFile(Data(contentsOf: fixture))
        try LivePhotoPlaybackFile.normalize(url)
        let first = VideoPlaybackSession()
        let second = VideoPlaybackSession()
        let source = MediaPlaybackSource(url: "https://video.weibo.com/media/play?livephoto=fixture.mov",
                                         key: UUID().uuidString, originalUrl: "live-photo-fixture", snapshot: nil)
        defer {
            SharedVideoPlayer.shared.detach(second)
            VideoPlaybackSession.releaseIdleBuffer()
        }
        let player: AVQueuePlayer
        do {
            let file = LivePhotoPlaybackFile(url: url)
            player = try SharedVideoPlayer.shared.attach(first, source: source, position: 0, allowsAudio: false,
                                                         playbackFile: file).0
        }
        let item = try XCTUnwrap(player.currentItem)
        XCTAssertEqual((item.asset as? AVURLAsset)?.url, url)
        XCTAssertTrue(FileManager.default.fileExists(atPath: url.path))
        let media = UiMediaVideo(url: source.url, thumbnailUrl: "", description: nil, height: 0, width: 0,
                                customHeaders: nil, proxy: ProxyRef.System.shared, downloadUrl: nil)
        do {
            let reused = try await SharedVideoPlayer.shared.prepareLivePhoto(media, source: source)
            XCTAssertEqual(reused?.url, url, "Handoff must reuse the prepared file without another download")
            let handedOff = try SharedVideoPlayer.shared.attach(second, source: source, position: nil, allowsAudio: false,
                                                              playbackFile: reused).0
            XCTAssertTrue(handedOff === player)
            XCTAssertTrue(player.currentItem === item)
        }
        SharedVideoPlayer.shared.detach(second)
        VideoPlaybackSession.releaseIdleBuffer()
        XCTAssertTrue(player.items().isEmpty)
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))
    }

    @MainActor
    func testNormalizedHEVCProducesChangingVideoFrames() async throws {
        let fixture = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "live-photo-hev1", withExtension: "mov"))
        let url = try temporaryFile(Data(contentsOf: fixture))
        defer { try? FileManager.default.removeItem(at: url) }
        try LivePhotoPlaybackFile.normalize(url)
        let item = AVPlayerItem(url: url)
        let output = AVPlayerItemVideoOutput(pixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA
        ])
        item.add(output)
        let player = AVPlayer(playerItem: item)
        player.isMuted = true
        defer { player.replaceCurrentItem(with: nil) }
        player.play()
        var shades: Set<Bool> = []
        let deadline = Date().addingTimeInterval(5)
        while shades.count < 2, Date() < deadline {
            if let frame = output.copyPixelBuffer(forItemTime: player.currentTime(), itemTimeForDisplay: nil) {
                CVPixelBufferLockBaseAddress(frame, .readOnly)
                if let pixel = CVPixelBufferGetBaseAddress(frame)?.assumingMemoryBound(to: UInt8.self) {
                    shades.insert(pixel.pointee > 128)
                }
                CVPixelBufferUnlockBaseAddress(frame, .readOnly)
            }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertEqual(shades.count, 2, "Playback time alone must not pass a video decoding regression")
    }

    private func temporaryFile(_ data: Data) throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("live-photo-test-\(UUID()).mov")
        try data.write(to: url)
        return url
    }

    private func movie(sampleType: String, complete: Bool = true, entryCount: UInt32 = 1) -> Data {
        func uint32(_ number: UInt32) -> Data {
            var value = number.bigEndian
            return withUnsafeBytes(of: &value) { Data($0) }
        }
        func box(_ type: String, _ payload: Data) -> Data {
            uint32(UInt32(payload.count + 8)) + Data(type.utf8) + payload
        }
        var configuration = Data(repeating: 0, count: 23)
        configuration[0] = 1
        configuration[22] = 3
        for type: UInt8 in [32, 33, 34] {
            configuration.append(contentsOf: [(complete ? 128 : 0) | type, 0, 1, 0, 1, type])
        }
        let sample = box(sampleType, Data(repeating: 0, count: 78) + box("hvcC", configuration))
        var container = box("stsd", Data(repeating: 0, count: 4) + uint32(entryCount) + sample)
        for type in ["stbl", "minf", "mdia", "trak", "moov"] { container = box(type, container) }
        return container + box("mdat", Data("hev1-in-mdat".utf8))
    }
}
