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

/// How fresh a chat feels — map bubbles use glow intensity instead of a "Live" label.
/// Ordered hot → cool so `min()` picks the most active in a cluster.
enum ActivityHeat: Equatable, Sendable, Comparable {
    /// Active in the last 20 minutes — strong accent glow.
    case hot
    /// Active in the last 2 hours — soft accent glow.
    case warm
    /// Quieter — hairline only.
    case cool

    static let hotWindow: TimeInterval = 20 * 60
    static let warmWindow: TimeInterval = 2 * 60 * 60

    static func of(_ date: Date?, now: Date = Date()) -> ActivityHeat {
        guard let date else { return .cool }
        let age = now.timeIntervalSince(date)
        guard age >= 0 else { return .cool }
        if age <= hotWindow { return .hot }
        if age <= warmWindow { return .warm }
        return .cool
    }
}

/// Recently active threads get a glow on the map — no backend field required.
enum LiveNow {
    /// Activity within this window counts as live (hot).
    static let window: TimeInterval = ActivityHeat.hotWindow

    static func isLive(_ date: Date?, now: Date = Date()) -> Bool {
        ActivityHeat.of(date, now: now) == .hot
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
