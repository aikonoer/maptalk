import Foundation

/// What a thread is about. Purely presentational: it picks the bubble icon and is never used
/// to filter queries, which is why no composite index is needed.
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
}

enum MessageKind: String, Sendable {
    case text
    case image
}

/// One reply inside a thread. Text messages carry only `text`; image messages also carry a
/// local or remote path to the compressed bytes (and optional caption in `text`).
struct Message: Identifiable, Equatable, Sendable {
    let id: String
    let kind: MessageKind
    let text: String
    let authorId: String
    let authorName: String
    let createdAt: Date?
    /// Relative path under the app's media directory, or a remote URL once Storage is wired.
    let imagePath: String?
    let imageWidth: Int?
    let imageHeight: Int?

    var hasImage: Bool { kind == .image && imagePath != nil }

    init(
        id: String,
        kind: MessageKind = .text,
        text: String,
        authorId: String,
        authorName: String,
        createdAt: Date?,
        imagePath: String? = nil,
        imageWidth: Int? = nil,
        imageHeight: Int? = nil
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
    }
}

/// Who is writing, denormalised onto every thread and message.
struct Author: Equatable, Sendable {
    let uid: String
    let displayName: String
}

/// A photo that has already been resized and JPEG-encoded on the device, ready to store.
struct PreparedImage: Sendable {
    let jpegData: Data
    let width: Int
    let height: Int
}
