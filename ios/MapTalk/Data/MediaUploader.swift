import FirebaseAuth
import FirebaseStorage
import Foundation

/// Uploads a compressed JPEG and returns the public URL stored on `Message.imagePath`.
///
/// Live builds POST to the Cloudflare Worker (R2). Emulator builds use Firebase Storage.
protocol MediaUploading: AnyObject {
    func upload(threadId: String, messageId: String, image: PreparedImage) async throws -> String
}

final class R2MediaUploader: MediaUploading {
    private let auth: Auth
    private let endpoint: URL

    init(auth: Auth, endpoint: URL) {
        self.auth = auth
        self.endpoint = endpoint
    }

    func upload(threadId: String, messageId: String, image: PreparedImage) async throws -> String {
        guard let user = auth.currentUser else {
            throw URLError(.userAuthenticationRequired)
        }
        let token = try await user.getIDToken()
        var components = URLComponents(url: endpoint, resolvingAgainstBaseURL: false)!
        components.queryItems = [
            URLQueryItem(name: "threadId", value: threadId),
            URLQueryItem(name: "messageId", value: messageId),
        ]
        var request = URLRequest(url: components.url!)
        request.httpMethod = "POST"
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("image/jpeg", forHTTPHeaderField: "Content-Type")
        request.httpBody = image.jpegData

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw URLError(.badServerResponse)
        }
        guard (200...299).contains(http.statusCode) else {
            let detail = String(data: data, encoding: .utf8) ?? ""
            throw NSError(
                domain: "MapTalk.MediaUploader",
                code: http.statusCode,
                userInfo: [NSLocalizedDescriptionKey: "Photo upload failed (\(http.statusCode)): \(detail)"]
            )
        }
        let payload = try JSONDecoder().decode(UploadResponse.self, from: data)
        return payload.url
    }

    private struct UploadResponse: Decodable {
        let url: String
    }
}

final class FirebaseStorageUploader: MediaUploading {
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