import Foundation

/// Shareable / inbound links: `maptalk://thread/{id}`.
enum ThreadLink {
    static let scheme = "maptalk"
    static let host = "thread"

    static func url(threadId: String) -> URL {
        var components = URLComponents()
        components.scheme = scheme
        components.host = host
        components.path = "/\(threadId)"
        return components.url!
    }

    static func threadId(from url: URL) -> String? {
        guard url.scheme?.lowercased() == scheme else { return nil }
        if url.host?.lowercased() == host {
            let id = url.path.split(separator: "/").map(String.init).first ?? ""
            return id.isEmpty ? nil : id
        }
        // maptalk:///thread/id path-only variants
        let parts = url.path.split(separator: "/").map(String.init)
        if parts.count >= 2, parts[0].lowercased() == host {
            return parts[1]
        }
        return nil
    }
}

/// Recently active threads get a "Live" pulse on the map — no backend field required.
enum LiveNow {
    /// Activity within this window counts as live.
    static let window: TimeInterval = 20 * 60

    static func isLive(_ date: Date?, now: Date = Date()) -> Bool {
        guard let date else { return false }
        return now.timeIntervalSince(date) <= window && now.timeIntervalSince(date) >= 0
    }
}

/// Holds a thread id from a deep link or notification tap until MapScreen can open the sheet.
@MainActor
@Observable
final class DeepLinkBus {
    static let shared = DeepLinkBus()

    private(set) var pendingThreadId: String?

    private init() {}

    func offer(_ threadId: String?) {
        let trimmed = threadId?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        pendingThreadId = trimmed.isEmpty ? nil : trimmed
    }

    func consume() -> String? {
        let id = pendingThreadId
        pendingThreadId = nil
        return id
    }
}
