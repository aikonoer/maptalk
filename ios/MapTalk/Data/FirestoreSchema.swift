import FirebaseFirestore
import Foundation

/// Collection and field names, mirrored in
/// `android/app/src/main/java/app/maptalk/data/FirestoreSchema.kt` and enforced by
/// `firebase/firestore.rules`.
enum Fs {
    static let threads = "threads"
    static let messages = "messages"
    static let users = "users"

    static let title = "title"
    static let kind = "kind"
    static let lat = "lat"
    static let lng = "lng"
    static let geohash = "geohash"
    static let authorId = "authorId"
    static let authorName = "authorName"
    static let createdAt = "createdAt"
    static let lastMessageAt = "lastMessageAt"
    static let messageCount = "messageCount"
    static let lastMediaPath = "lastMediaPath"
    static let lastMediaKind = "lastMediaKind"
    static let text = "text"
    static let displayName = "displayName"
    static let photoURL = "photoURL"
    static let photoPath = "photoPath"
    static let updatedAt = "updatedAt"
    static let kindMessage = "messageKind"
    static let imagePath = "imagePath"
    static let imageWidth = "imageWidth"
    static let imageHeight = "imageHeight"
    static let audioPath = "audioPath"
    static let audioDurationMs = "audioDurationMs"
    static let videoPath = "videoPath"
    static let videoDurationMs = "videoDurationMs"
    static let videoWidth = "videoWidth"
    static let videoHeight = "videoHeight"
    static let replyToId = "replyToId"
    static let replyToText = "replyToText"
    static let replyToAuthorName = "replyToAuthorName"
    static let reactions = "reactions"

    static let blocks = "blocks"
    static let blockedUid = "blockedUid"
    static let reports = "reports"
    static let targetType = "targetType"
    static let targetId = "targetId"
    static let threadId = "threadId"
    static let targetAuthorId = "targetAuthorId"
    static let reason = "reason"

    static let devices = "devices"
    static let token = "token"
    static let platform = "platform"
    static let subscribers = "subscribers"
    static let subscribedAt = "subscribedAt"
}

extension DocumentSnapshot {

    func chatThread() -> ChatThread? {
        guard let title = self[Fs.title] as? String,
              let lat = self[Fs.lat] as? Double,
              let lng = self[Fs.lng] as? Double
        else { return nil }

        return ChatThread(
            id: documentID,
            title: title,
            kind: ThreadKind(id: self[Fs.kind] as? String),
            position: GeoPoint(lat: lat, lng: lng),
            geohash: self[Fs.geohash] as? String ?? "",
            authorId: self[Fs.authorId] as? String ?? "",
            authorName: self[Fs.authorName] as? String ?? "",
            createdAt: estimatedDate(Fs.createdAt),
            lastMessageAt: estimatedDate(Fs.lastMessageAt),
            messageCount: (self[Fs.messageCount] as? NSNumber)?.intValue ?? 0,
            lastMediaPath: self[Fs.lastMediaPath] as? String,
            lastMediaKind: (self[Fs.lastMediaKind] as? String).flatMap(MessageKind.init(rawValue:))
        )
    }

    func message() -> Message? {
        let text = self[Fs.text] as? String ?? ""
        let kind = MessageKind(rawValue: self[Fs.kindMessage] as? String ?? "") ?? .text
        switch kind {
        case .text where text.isEmpty: return nil
        case .image where self[Fs.imagePath] as? String == nil: return nil
        case .voice where self[Fs.audioPath] as? String == nil: return nil
        case .video where self[Fs.videoPath] as? String == nil: return nil
        case .sticker where text.isEmpty: return nil
        default: break
        }

        var reply: MessageReply?
        if let replyId = self[Fs.replyToId] as? String,
           let replyName = self[Fs.replyToAuthorName] as? String,
           let replyText = self[Fs.replyToText] as? String {
            reply = MessageReply(id: replyId, authorName: replyName, text: replyText)
        }

        var reactions: [String: [String]] = [:]
        if let raw = self[Fs.reactions] as? [String: Any] {
            for (emoji, value) in raw {
                if let uids = value as? [String] {
                    reactions[emoji] = uids
                } else if let uids = value as? [Any] {
                    reactions[emoji] = uids.compactMap { $0 as? String }
                }
            }
        }

        return Message(
            id: documentID,
            kind: kind,
            text: text,
            authorId: self[Fs.authorId] as? String ?? "",
            authorName: self[Fs.authorName] as? String ?? "",
            createdAt: estimatedDate(Fs.createdAt),
            imagePath: self[Fs.imagePath] as? String,
            imageWidth: (self[Fs.imageWidth] as? NSNumber)?.intValue,
            imageHeight: (self[Fs.imageHeight] as? NSNumber)?.intValue,
            audioPath: self[Fs.audioPath] as? String,
            audioDurationMs: (self[Fs.audioDurationMs] as? NSNumber)?.intValue,
            videoPath: self[Fs.videoPath] as? String,
            videoDurationMs: (self[Fs.videoDurationMs] as? NSNumber)?.intValue,
            videoWidth: (self[Fs.videoWidth] as? NSNumber)?.intValue,
            videoHeight: (self[Fs.videoHeight] as? NSNumber)?.intValue,
            reply: reply,
            reactions: reactions
        )
    }

    private func estimatedDate(_ field: String) -> Date? {
        (get(field, serverTimestampBehavior: .estimate) as? Timestamp)?.dateValue()
    }
}
