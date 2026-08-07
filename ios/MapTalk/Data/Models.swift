import Foundation

/// What a thread is about. One required main kind per thread (closed set). Used for
/// marker styling and client-side map filters — never for Firestore queries, so no
/// composite index is needed. Free-form / secondary tags stay parked.
enum ThreadKind: String, CaseIterable, Sendable {
    case event
    case notice
    case traffic
    case general

    var label: String {
        switch self {
        case .event: "Happening now"
        case .notice: "Notice"
        case .traffic: "Traffic"
        case .general: "Just talking"
        }
    }

    var glyph: String {
        switch self {
        case .event: "\u{1F389}"
        case .notice: "\u{1F4E3}"
        case .traffic: "\u{1F6A7}"
        case .general: "\u{1F4AC}"
        }
    }

    init(id: String?) {
        self = ThreadKind(rawValue: id ?? "") ?? .general
    }
}

/// A conversation pinned to one point on the map.
struct ChatThread: Identifiable, Equatable, Sendable {
    let id: String
    let title: String
    let kind: ThreadKind
    let position: GeoPoint
    let geohash: String
    let authorId: String
    let authorName: String
    let createdAt: Date?
    let lastMessageAt: Date?
    let messageCount: Int
    /// Latest message media for the map bubble thumb — image/video path, or nil.
    let lastMediaPath: String?
    /// `.image` or `.video` when `lastMediaPath` is set; cleared when the tip is text/voice/sticker.
    let lastMediaKind: MessageKind?

    var hasMapMediaPreview: Bool {
        guard let lastMediaPath, !lastMediaPath.isEmpty else { return false }
        return lastMediaKind == .image || lastMediaKind == .video
    }
}

enum MessageKind: String, Sendable {
    case text
    case image
    case voice
    case video
    case sticker
}

/// Quick reactions available under every bubble.
enum ReactionEmoji: String, CaseIterable, Sendable {
    case thumb = "👍"
    case heart = "❤️"
    case laugh = "😂"
    case wow = "😮"
    case sad = "😢"
    case fire = "🔥"
}

/// Curated sticker glyphs (no network pack — just expressive emoji sent as sticker messages).
enum StickerPack {
    static let all: [String] = [
        "👋", "🔥", "💯", "✨", "🎉", "🙌",
        "😅", "🫡", "🫶", "📍", "🚦", "☕",
    ]
}

/// Snapshot of the message being replied to, denormalised onto the new message.
struct MessageReply: Equatable, Sendable {
    let id: String
    let authorName: String
    let text: String
}

/// One reply inside a thread.
struct Message: Identifiable, Equatable, Sendable {
    let id: String
    let kind: MessageKind
    let text: String
    let authorId: String
    let authorName: String
    let createdAt: Date?
    let imagePath: String?
    let imageWidth: Int?
    let imageHeight: Int?
    let audioPath: String?
    let audioDurationMs: Int?
    let videoPath: String?
    let videoDurationMs: Int?
    let videoWidth: Int?
    let videoHeight: Int?
    let reply: MessageReply?
    /// emoji → uids who reacted with it
    let reactions: [String: [String]]

    var hasImage: Bool { kind == .image && imagePath != nil }
    var hasVoice: Bool { kind == .voice && audioPath != nil }
    var hasVideo: Bool { kind == .video && videoPath != nil }
    var isSticker: Bool { kind == .sticker }
    /// Optimistic local send — not yet on the server.
    var isLocalPending: Bool { id.hasPrefix("local:") }

    init(
        id: String,
        kind: MessageKind = .text,
        text: String,
        authorId: String,
        authorName: String,
        createdAt: Date?,
        imagePath: String? = nil,
        imageWidth: Int? = nil,
        imageHeight: Int? = nil,
        audioPath: String? = nil,
        audioDurationMs: Int? = nil,
        videoPath: String? = nil,
        videoDurationMs: Int? = nil,
        videoWidth: Int? = nil,
        videoHeight: Int? = nil,
        reply: MessageReply? = nil,
        reactions: [String: [String]] = [:]
    ) {
        self.id = id
        self.kind = kind
        self.text = text
        self.authorId = authorId
        self.authorName = authorName
        self.createdAt = createdAt
        self.imagePath = imagePath
        self.imageWidth = imageWidth
        self.imageHeight = imageHeight
        self.audioPath = audioPath
        self.audioDurationMs = audioDurationMs
        self.videoPath = videoPath
        self.videoDurationMs = videoDurationMs
        self.videoWidth = videoWidth
        self.videoHeight = videoHeight
        self.reply = reply
        self.reactions = reactions
    }

    func reactionCount(for emoji: String) -> Int {
        reactions[emoji]?.count ?? 0
    }

    func reacted(by uid: String, emoji: String) -> Bool {
        reactions[emoji]?.contains(uid) == true
    }
}

struct Author: Equatable, Sendable {
    let uid: String
    let displayName: String
}

/// Someone this viewer has blocked.
struct BlockedPerson: Identifiable, Equatable, Sendable {
    var id: String { uid }
    let uid: String
    let displayName: String
}

enum ReportTargetType: String, Sendable {
    case message
    case thread
    case user
}

enum ReportReason: String, CaseIterable, Sendable {
    case spam
    case harassment
    case inappropriate
    case other

    var label: String {
        switch self {
        case .spam: "Spam"
        case .harassment: "Harassment"
        case .inappropriate: "Inappropriate"
        case .other: "Something else"
        }
    }
}

struct PreparedImage: Sendable {
    let jpegData: Data
    let width: Int
    let height: Int
}

struct PreparedAudio: Sendable {
    let data: Data
    let durationMs: Int
    let contentType: String
}

struct PreparedVideo: Sendable {
    let fileURL: URL
    let durationMs: Int
    let width: Int
    let height: Int
    let contentType: String

    var byteCount: Int {
        (try? FileManager.default.attributesOfItem(atPath: fileURL.path)[.size] as? NSNumber)?.intValue ?? 0
    }

    init(
        fileURL: URL,
        durationMs: Int,
        width: Int,
        height: Int,
        contentType: String = "video/mp4"
    ) {
        self.fileURL = fileURL
        self.durationMs = durationMs
        self.width = width
        self.height = height
        self.contentType = contentType
    }

    func deleteTempFile() {
        try? FileManager.default.removeItem(at: fileURL)
    }
}
