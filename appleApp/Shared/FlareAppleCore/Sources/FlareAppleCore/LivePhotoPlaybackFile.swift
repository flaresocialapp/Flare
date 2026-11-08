import Foundation
@preconcurrency import KotlinSharedUI

public final class LivePhotoPlaybackFile: Sendable {
    public nonisolated let url: URL

    nonisolated init(url: URL) { self.url = url }

    deinit { try? FileManager.default.removeItem(at: url) }

    public static func prepare(_ media: any UiMedia) async throws -> LivePhotoPlaybackFile? {
        guard isRequired(media.url) else { return nil }
        // shortcut: short Live Photos download before playback; use a range-aware loader if large clips need streaming.
        let (download, _) = try await MediaTransfer.download(media)
        defer { try? FileManager.default.removeItem(at: download) }
        let file = LivePhotoPlaybackFile(url: FileManager.default.temporaryDirectory.appendingPathComponent("live-photo-\(UUID()).mov"))
        try FileManager.default.moveItem(at: download, to: file.url)
        try await Task.detached(priority: .userInitiated) { try normalize(file.url) }.value
        try Task.checkCancellation()
        return file
    }

    nonisolated static func isRequired(_ value: String) -> Bool {
        guard let url = URLComponents(string: value), let host = url.host?.lowercased(),
              ["http", "https"].contains(url.scheme?.lowercased() ?? "") else { return false }
        if host == "video.weibo.com", url.path == "/media/play" {
            return url.queryItems?.contains { $0.name == "livephoto" && !($0.value ?? "").isEmpty } == true
        }
        return host.hasPrefix("livephoto.") && host.hasSuffix(".sinaimg.cn") && url.path.lowercased().hasSuffix(".mov")
    }

    nonisolated static func normalize(_ url: URL) throws {
        let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        guard size > 0, size <= 64 * 1024 * 1024 else { throw URLError(.dataLengthExceedsMaximum) }
        let offsets = try sampleEntryOffsets(in: Data(contentsOf: url, options: .mappedIfSafe))
        guard !offsets.isEmpty else { return }
        let file = try FileHandle(forUpdating: url)
        defer { try? file.close() }
        for offset in offsets {
            try file.seek(toOffset: UInt64(offset))
            try file.write(contentsOf: Data("hvc1".utf8))
        }
    }

    private nonisolated static func sampleEntryOffsets(in data: Data) throws -> [Int] {
        func integer(_ offset: Int, bytes: Int, end: Int) throws -> UInt64 {
            guard offset >= 0, bytes <= end - offset else { throw CocoaError(.fileReadCorruptFile) }
            return data[offset..<(offset + bytes)].reduce(0) { ($0 << 8) | UInt64($1) }
        }
        func box(_ start: Int, end: Int) throws -> (type: String, payload: Int, end: Int) {
            let size = try integer(start, bytes: 4, end: end)
            guard end - start >= 8 else { throw CocoaError(.fileReadCorruptFile) }
            let header = size == 1 ? 16 : 8
            let length = try size == 1 ? integer(start + 8, bytes: 8, end: end) : size == 0 ? UInt64(end - start) : size
            guard length >= header, length <= end - start else { throw CocoaError(.fileReadCorruptFile) }
            return (String(decoding: data[(start + 4)..<(start + 8)], as: UTF8.self), start + header, start + Int(length))
        }
        func hasCompleteParameterSets(_ start: Int, end: Int) throws -> Bool {
            guard end - start >= 23, data[start] == 1 else { throw CocoaError(.fileReadCorruptFile) }
            var offset = start + 23
            var complete: Set<UInt8> = []
            for _ in 0..<data[start + 22] {
                let count = try integer(offset + 1, bytes: 2, end: end)
                let type = data[offset] & 63
                if data[offset] & 128 != 0, count > 0 { complete.insert(type) }
                offset += 3
                for _ in 0..<count {
                    let length = try integer(offset, bytes: 2, end: end)
                    offset += 2
                    guard length > 0, length <= end - offset else { throw CocoaError(.fileReadCorruptFile) }
                    offset += Int(length)
                }
            }
            guard offset == end else { throw CocoaError(.fileReadCorruptFile) }
            return complete.isSuperset(of: [32, 33, 34])
        }
        var offsets: [Int] = []
        func walk(_ start: Int, end: Int, path: ArraySlice<String>) throws {
            var offset = start
            while offset < end {
                let atom = try box(offset, end: end)
                if atom.type == path.first {
                    if path.count > 1 {
                        try walk(atom.payload, end: atom.end, path: path.dropFirst())
                    } else {
                        let count = try integer(atom.payload + 4, bytes: 4, end: atom.end)
                        var entry = atom.payload + 8
                        guard count <= (atom.end - entry) / 8 else { throw CocoaError(.fileReadCorruptFile) }
                        for _ in 0..<count {
                            let sample = try box(entry, end: atom.end)
                            if sample.type == "hev1" {
                                guard sample.end - sample.payload >= 78 else { throw CocoaError(.fileReadCorruptFile) }
                                var child = sample.payload + 78
                                var compatible = false
                                while child < sample.end {
                                    let configuration = try box(child, end: sample.end)
                                    if configuration.type == "hvcC" {
                                        compatible = try hasCompleteParameterSets(configuration.payload, end: configuration.end)
                                    }
                                    child = configuration.end
                                }
                                // Retag only self-contained parameter sets; never rewrite media payload bytes.
                                guard compatible else { throw URLError(.cannotDecodeContentData) }
                                offsets.append(entry + 4)
                            }
                            entry = sample.end
                        }
                        guard entry == atom.end else { throw CocoaError(.fileReadCorruptFile) }
                    }
                }
                offset = atom.end
            }
        }
        try walk(0, end: data.count, path: ["moov", "trak", "mdia", "minf", "stbl", "stsd"][...])
        return offsets
    }
}
