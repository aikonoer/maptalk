import CryptoKit
import Foundation

/// Disk cache for remote thread videos under Caches/MapTalkVideos (~100 MB LRU).
enum VideoCache {
    private static let maxBytes = 100 * 1024 * 1024
    private static let folderName = "MapTalkVideos"

    /// Returns a local file URL for `remote`, downloading into the cache when needed.
    /// On download failure, returns the original remote URL so playback can still stream.
    static func localURL(for remote: URL) async -> URL {
        let fileURL = cacheFileURL(for: remote)
        let fm = FileManager.default

        if fm.fileExists(atPath: fileURL.path) {
            try? fm.setAttributes([.modificationDate: Date()], ofItemAtPath: fileURL.path)
            return fileURL
        }

        do {
            try fm.createDirectory(at: cacheDirectory(), withIntermediateDirectories: true)
            let (tempURL, response) = try await URLSession.shared.download(from: remote)
            if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
                try? fm.removeItem(at: tempURL)
                return remote
            }
            if fm.fileExists(atPath: fileURL.path) {
                try? fm.removeItem(at: fileURL)
            }
            try fm.moveItem(at: tempURL, to: fileURL)
            try? fm.setAttributes([.modificationDate: Date()], ofItemAtPath: fileURL.path)
            enforceCap()
            return fileURL
        } catch {
            return remote
        }
    }

    // MARK: - Internals

    private static func cacheDirectory() -> URL {
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        return caches.appendingPathComponent(folderName, isDirectory: true)
    }

    private static func cacheFileURL(for remote: URL) -> URL {
        let key = sha256(remote.absoluteString)
        let ext = remote.pathExtension.isEmpty ? "mp4" : remote.pathExtension
        return cacheDirectory().appendingPathComponent("\(key).\(ext)", isDirectory: false)
    }

    private static func sha256(_ input: String) -> String {
        let hash = SHA256.hash(data: Data(input.utf8))
        return hash.map { String(format: "%02x", $0) }.joined()
    }

    private static func enforceCap() {
        let fm = FileManager.default
        let dir = cacheDirectory()
        guard let urls = try? fm.contentsOfDirectory(
            at: dir,
            includingPropertiesForKeys: [.contentModificationDateKey, .fileSizeKey],
            options: [.skipsHiddenFiles]
        ) else { return }

        struct Entry {
            let url: URL
            let modified: Date
            let size: Int
        }

        var entries: [Entry] = urls.compactMap { url in
            let values = try? url.resourceValues(forKeys: [.contentModificationDateKey, .fileSizeKey])
            let size = values?.fileSize ?? 0
            guard size > 0 else { return nil }
            return Entry(
                url: url,
                modified: values?.contentModificationDate ?? .distantPast,
                size: size
            )
        }

        var total = entries.reduce(0) { $0 + $1.size }
        guard total > maxBytes else { return }

        entries.sort { $0.modified < $1.modified }
        for entry in entries {
            guard total > maxBytes else { break }
            try? fm.removeItem(at: entry.url)
            total -= entry.size
        }
    }
}
