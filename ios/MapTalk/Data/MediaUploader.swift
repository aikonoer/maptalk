import FirebaseStorage
import Foundation

/// Uploads a compressed JPEG to Firebase Storage and returns a download URL for
/// `Message.imagePath`. Local demo never uses this — it writes files on device instead.
///
/// Object layout: `threads/{threadId}/{messageId}.jpg`. The same URL field can later point at
/// Cloudflare R2 without changing the Firestore schema.
struct MediaUploader {
    private let storage: Storage

    init(storage: Storage) {
        self.storage = storage
    }

    func upload(threadId: String, messageId: String, image: PreparedImage) async throws -> String {
        let ref = storage.reference()
            .child("threads")
            .child(threadId)
            .child("\(messageId).jpg")
        let metadata = StorageMetadata()
        metadata.contentType = "image/jpeg"
        _ = try await ref.putDataAsync(image.jpegData, metadata: metadata)
        return try await ref.downloadURL().absoluteString
    }
}
