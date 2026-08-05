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
    private let uploadGate = UploadGate(limit: 2)

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
        await uploadGate.acquire()
        do {
            let url = try await post(
                endpoint: imageEndpoint,
                threadId: threadId,
                messageId: messageId,
                body: image.jpegData,
                contentType: "image/jpeg"
            )
            await uploadGate.release()
            return url
        } catch {
            await uploadGate.release()
            throw error
        }
    }

    func upload(threadId: String, messageId: String, audio: PreparedAudio) async throws -> String {
        await uploadGate.acquire()
        do {
            let url = try await post(
                endpoint: audioEndpoint,
                threadId: threadId,
                messageId: messageId,
                body: audio.data,
                contentType: audio.contentType
            )
            await uploadGate.release()
            return url
        } catch {
            await uploadGate.release()
            throw error
        }
    }

    func upload(threadId: String, messageId: String, video: PreparedVideo) async throws -> String {
        await uploadGate.acquire()
        do {
            let url = try await putFile(
                endpoint: videoEndpoint,
                threadId: threadId,
                messageId: messageId,
                fileURL: video.fileURL,
                contentType: video.contentType,
                extraHeaders: ["X-MapTalk-Duration-Ms": "\(video.durationMs)"]
            )
            await uploadGate.release()
            return url
        } catch {
            await uploadGate.release()
            throw error
        }
    }

    private func post(
        endpoint: URL,
        threadId: String,
        messageId: String,
        body: Data,
        contentType: String,
        extraHeaders: [String: String] = [:],
        maxAttempts: Int = 3
    ) async throws -> String {
        var lastError: Error?
        for attempt in 0..<maxAttempts {
            try Task.checkCancellation()
            do {
                return try await postOnce(
                    endpoint: endpoint,
                    threadId: threadId,
                    messageId: messageId,
                    body: body,
                    contentType: contentType,
                    extraHeaders: extraHeaders
                )
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                lastError = error
                guard Self.isTransient(error), attempt < maxAttempts - 1 else { throw error }
                try await Task.sleep(nanoseconds: UInt64(400_000_000) << attempt)
            }
        }
        throw lastError ?? URLError(.unknown)
    }

    private func putFile(
        endpoint: URL,
        threadId: String,
        messageId: String,
        fileURL: URL,
        contentType: String,
        extraHeaders: [String: String] = [:],
        maxAttempts: Int = 3
    ) async throws -> String {
        var lastError: Error?
        for attempt in 0..<maxAttempts {
            try Task.checkCancellation()
            do {
                return try await putFileOnce(
                    endpoint: endpoint,
                    threadId: threadId,
                    messageId: messageId,
                    fileURL: fileURL,
                    contentType: contentType,
                    extraHeaders: extraHeaders
                )
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                lastError = error
                guard Self.isTransient(error), attempt < maxAttempts - 1 else { throw error }
                try await Task.sleep(nanoseconds: UInt64(400_000_000) << attempt)
            }
        }
        throw lastError ?? URLError(.unknown)
    }

    private func postOnce(
        endpoint: URL,
        threadId: String,
        messageId: String,
        body: Data,
        contentType: String,
        extraHeaders: [String: String]
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
        for (key, value) in extraHeaders {
            request.setValue(value, forHTTPHeaderField: key)
        }
        request.httpBody = body

        let (data, response) = try await URLSession.shared.data(for: request)
        return try Self.parseUploadURL(data: data, response: response)
    }

    private func putFileOnce(
        endpoint: URL,
        threadId: String,
        messageId: String,
        fileURL: URL,
        contentType: String,
        extraHeaders: [String: String]
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
        request.httpMethod = "PUT"
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        let length = (try? FileManager.default.attributesOfItem(atPath: fileURL.path)[.size] as? NSNumber)?.intValue ?? 0
        request.setValue("\(length)", forHTTPHeaderField: "Content-Length")
        for (key, value) in extraHeaders {
            request.setValue(value, forHTTPHeaderField: key)
        }

        let (data, response) = try await URLSession.shared.upload(for: request, fromFile: fileURL)
        return try Self.parseUploadURL(data: data, response: response)
    }

    private static func parseUploadURL(data: Data, response: URLResponse) throws -> String {
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

    private static func isTransient(_ error: Error) -> Bool {
        if let urlError = error as? URLError {
            switch urlError.code {
            case .timedOut, .networkConnectionLost, .notConnectedToInternet, .cannotConnectToHost:
                return true
            default:
                break
            }
        }
        let ns = error as NSError
        return (500...599).contains(ns.code)
    }

    private struct UploadResponse: Decodable {
        let url: String
    }
}

/// Limits parallel R2 uploads so a busy thread doesn't stampede the Worker.
private actor UploadGate {
    private let limit: Int
    private var inFlight = 0
    private var waiters: [CheckedContinuation<Void, Never>] = []

    init(limit: Int) {
        self.limit = limit
    }

    func acquire() async {
        while inFlight >= limit {
            await withCheckedContinuation { cont in
                waiters.append(cont)
            }
        }
        inFlight += 1
    }

    func release() {
        inFlight = max(0, inFlight - 1)
        guard !waiters.isEmpty else { return }
        waiters.removeFirst().resume()
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
        _ = try await ref.putFileAsync(from: video.fileURL, metadata: metadata)
        return try await ref.downloadURL().absoluteString
    }
}
