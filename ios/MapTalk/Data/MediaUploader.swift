import FirebaseAuth
import FirebaseStorage
import Foundation

/// Uploads chat media and returns the public URL stored on the message document.
protocol MediaUploading: AnyObject {
    func upload(threadId: String, messageId: String, image: PreparedImage) async throws -> String
    func upload(threadId: String, messageId: String, audio: PreparedAudio) async throws -> String
    func upload(threadId: String, messageId: String, video: PreparedVideo) async throws -> String
}

final class R2MediaUploader: MediaUploading {
    private let auth: Auth
    private let imageEndpoint: URL
    private let audioEndpoint: URL
    private let videoEndpoint: URL

    init(auth: Auth, endpoint: URL) {
        self.auth = auth
        self.imageEndpoint = endpoint
        // …/v1/images → …/v1/audio and …/v1/video
        var audio = endpoint
        var video = endpoint
        if endpoint.lastPathComponent == "images" {
            audio.deleteLastPathComponent()
            audio.appendPathComponent("audio")
            video.deleteLastPathComponent()
            video.appendPathComponent("video")
        } else {
            audio = URL(string: "https://maptalk-media.hhypkfpshg.workers.dev/v1/audio")!
            video = URL(string: "https://maptalk-media.hhypkfpshg.workers.dev/v1/video")!
        }
        self.audioEndpoint = audio
        self.videoEndpoint = video
    }

    func upload(threadId: String, messageId: String, image: PreparedImage) async throws -> String {
        try await post(
            endpoint: imageEndpoint,
            threadId: threadId,
            messageId: messageId,
            body: image.jpegData,
            contentType: "image/jpeg"
        )
    }

    func upload(threadId: String, messageId: String, audio: PreparedAudio) async throws -> String {
        try await post(
            endpoint: audioEndpoint,
            threadId: threadId,
            messageId: messageId,
            body: audio.data,
            contentType: audio.contentType
        )
    }

    func upload(threadId: String, messageId: String, video: PreparedVideo) async throws -> String {
        try await post(
            endpoint: videoEndpoint,
            threadId: threadId,
            messageId: messageId,
            body: video.data,
            contentType: video.contentType
        )
    }

    private func post(
        endpoint: URL,
        threadId: String,
        messageId: String,
        body: Data,
        contentType: String
    ) async throws -> String {
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
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        request.httpBody = body

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw URLError(.badServerResponse)
        }
        guard (200...299).contains(http.statusCode) else {
            let detail = String(data: data, encoding: .utf8) ?? ""
            throw NSError(
                domain: "MapTalk.MediaUploader",
                code: http.statusCode,
                userInfo: [NSLocalizedDescriptionKey: "Upload failed (\(http.statusCode)): \(detail)"]
            )
        }
        return try JSONDecoder().decode(UploadResponse.self, from: data).url
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

    func upload(threadId: String, messageId: String, audio: PreparedAudio) async throws -> String {
        let ref = storage.reference()
            .child("threads")
            .child(threadId)
            .child("\(messageId).m4a")
        let metadata = StorageMetadata()
        metadata.contentType = audio.contentType
        _ = try await ref.putDataAsync(audio.data, metadata: metadata)
        return try await ref.downloadURL().absoluteString
    }

    func upload(threadId: String, messageId: String, video: PreparedVideo) async throws -> String {
        let ref = storage.reference()
            .child("threads")
            .child(threadId)
            .child("\(messageId).mp4")
        let metadata = StorageMetadata()
        metadata.contentType = video.contentType
        _ = try await ref.putDataAsync(video.data, metadata: metadata)
        return try await ref.downloadURL().absoluteString
    }
}
