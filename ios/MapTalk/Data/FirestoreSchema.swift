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
    static let text = "text"
    static let displayName = "displayName"
    static let kindMessage = "messageKind"
    static let imagePath = "imagePath"
    static let imageWidth = "imageWidth"
    static let imageHeight = "imageHeight"
}

/// Documents are mapped by hand rather than through Codable, so a schema change is a compile
/// error instead of a silent nil, and both apps read the data the same way.
///
/// A locally created document has no server timestamp yet, so timestamps are read with
/// `.estimate`. That gives a just-sent message a usable sort key instead of nil.
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
            messageCount: (self[Fs.messageCount] as? NSNumber)?.intValue ?? 0
        )
    }

    func message() -> Message? {
        // Image-only messages may have an empty caption; text messages still need a body.
        let text = self[Fs.text] as? String ?? ""
        let kind = MessageKind(rawValue: self[Fs.kindMessage] as? String ?? "") ?? .text
        if kind == .text, text.isEmpty { return nil }
        if kind == .image, self[Fs.imagePath] as? String == nil { return nil }
        return Message(
            id: documentID,
            kind: kind,
            text: text,
            authorId: self[Fs.authorId] as? String ?? "",
            authorName: self[Fs.authorName] as? String ?? "",
            createdAt: estimatedDate(Fs.createdAt),
            imagePath: self[Fs.imagePath] as? String,
            imageWidth: (self[Fs.imageWidth] as? NSNumber)?.intValue,
            imageHeight: (self[Fs.imageHeight] as? NSNumber)?.intValue
        )
    }

    private func estimatedDate(_ field: String) -> Date? {
        (get(field, serverTimestampBehavior: .estimate) as? Timestamp)?.dateValue()
    }
}
