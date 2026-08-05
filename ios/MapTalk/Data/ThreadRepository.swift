import FirebaseFirestore
import Foundation

/// Reads and writes threads. Every read is a snapshot listener exposed as an `AsyncStream`, so
/// the map and the open conversation update themselves.
///
/// Writes are not awaited on purpose. Firestore applies them locally straight away, so the
/// bubble or the reply shows up instantly and still syncs after a tunnel or a lift. Failures
/// (a rejected write, say) are reported through `onError` instead of blocking the UI.
///
/// Local demo mode uses the same API against an in-memory store — no network required.
@MainActor
final class ThreadRepository {

    private enum Backend {
        case firestore(Firestore, MediaUploading)
        case local(LocalDemoStore)
    }

    private let backend: Backend
    var onError: ((Error) -> Void)?

    private let messagePageSize = 200

    init(firestore: Firestore, mediaUploader: MediaUploading) {
        backend = .firestore(firestore, mediaUploader)
    }

    init(local: LocalDemoStore) {
        backend = .local(local)
    }

    func threads(for query: ViewportQuery) -> AsyncStream<[ChatThread]> {
        switch backend {
        case let .firestore(firestore, _):
            switch query {
            case let .nearby(center, radiusKm):
                return nearbyThreads(firestore: firestore, center: center, radiusKm: radiusKm)
            case .globalRecent:
                return globalThreads(firestore: firestore)
            }
        case let .local(store):
            return store.threads(for: query)
        }
    }

    /// Standard Firestore geohash recipe: one range query per bound (up to nine), merged and
    /// then filtered by real distance to drop the cells that overlap the circle only partly.
    private func nearbyThreads(firestore: Firestore, center: GeoPoint, radiusKm: Double) -> AsyncStream<[ChatThread]> {
        let radiusMeters = radiusKm * 1_000
        let bounds = GeoHash.queryBounds(for: center, radiusMeters: radiusMeters)
        let pages = PageBuffer<ChatThread>(count: bounds.count)
        let listeners = ListenerBag()
        let report = onError

        return AsyncStream { continuation in
            for (index, bound) in bounds.enumerated() {
                let registration = firestore.collection(Fs.threads)
                    .order(by: Fs.geohash)
                    .start(at: [bound.startHash])
                    .end(at: [bound.endHash])
                    .limit(to: Viewport.perBoundLimit)
                    .addSnapshotListener { snapshot, error in
                        if let error {
                            report?(error)
                            return
                        }
                        let threads = snapshot?.documents.compactMap { $0.chatThread() } ?? []
                        let merged = pages.replace(threads, at: index)
                        continuation.yield(merged.within(radiusMeters, of: center))
                    }
                listeners.add(registration)
            }
            continuation.yield([])
            continuation.onTermination = { _ in listeners.removeAll() }
        }
    }

    private func globalThreads(firestore: Firestore) -> AsyncStream<[ChatThread]> {
        let listeners = ListenerBag()
        let report = onError

        return AsyncStream { continuation in
            let registration = firestore.collection(Fs.threads)
                .order(by: Fs.lastMessageAt, descending: true)
                .limit(to: Viewport.globalLimit)
                .addSnapshotListener { snapshot, error in
                    if let error {
                        report?(error)
                        return
                    }
                    continuation.yield(snapshot?.documents.compactMap { $0.chatThread() } ?? [])
                }
            listeners.add(registration)
            continuation.onTermination = { _ in listeners.removeAll() }
        }
    }

    func thread(id threadId: String) -> AsyncStream<ChatThread?> {
        switch backend {
        case let .firestore(firestore, _):
            let listeners = ListenerBag()
            let report = onError

            return AsyncStream { continuation in
                let registration = firestore.collection(Fs.threads).document(threadId)
                    .addSnapshotListener { snapshot, error in
                        if let error {
                            report?(error)
                            return
                        }
                        guard let snapshot, snapshot.exists else {
                            continuation.yield(nil)
                            return
                        }
                        continuation.yield(snapshot.chatThread())
                    }
                listeners.add(registration)
                continuation.onTermination = { _ in listeners.removeAll() }
            }
        case let .local(store):
            return store.thread(id: threadId)
        }
    }

    /// Newest messages first from Firestore, oldest first for the UI. Sorting locally keeps a
    /// reply that has not reached the server yet (its timestamp is still an estimate) at the
    /// bottom where the sender expects it.
    func messages(threadId: String) -> AsyncStream<[Message]> {
        switch backend {
        case let .firestore(firestore, _):
            let listeners = ListenerBag()
            let report = onError
            let pageSize = messagePageSize

            return AsyncStream { continuation in
                let registration = firestore.collection(Fs.threads).document(threadId)
                    .collection(Fs.messages)
                    .order(by: Fs.createdAt, descending: true)
                    .limit(to: pageSize)
                    .addSnapshotListener { snapshot, error in
                        if let error {
                            report?(error)
                            return
                        }
                        let messages = snapshot?.documents.compactMap { $0.message() } ?? []
                        continuation.yield(
                            messages.sorted { ($0.createdAt ?? .distantFuture) < ($1.createdAt ?? .distantFuture) }
                        )
                    }
                listeners.add(registration)
                continuation.onTermination = { _ in listeners.removeAll() }
            }
        case let .local(store):
            return store.messages(threadId: threadId)
        }
    }

    /// Returns the id of the new thread straight away; the write completes in the background.
    func createThread(
        title: String,
        kind: ThreadKind,
        position: GeoPoint,
        author: Author
    ) -> String {
        switch backend {
        case let .firestore(firestore, _):
            let document = firestore.collection(Fs.threads).document()
            let data: [String: Any] = [
                Fs.title: title.trimmingCharacters(in: .whitespacesAndNewlines),
                Fs.kind: kind.rawValue,
                Fs.lat: position.lat,
                Fs.lng: position.lng,
                Fs.geohash: GeoHash.hash(for: position),
                Fs.authorId: author.uid,
                Fs.authorName: author.displayName,
                Fs.createdAt: FieldValue.serverTimestamp(),
                Fs.lastMessageAt: FieldValue.serverTimestamp(),
                Fs.messageCount: 0,
            ]
            document.setData(data) { [weak self] error in
                guard let error else { return }
                MainActor.assumeIsolated { self?.onError?(error) }
            }
            return document.documentID
        case let .local(store):
            return store.createThread(title: title, kind: kind, position: position, author: author)
        }
    }

    /// The message and the thread's activity fields move together or not at all.
    /// Rich media uploads first, then the Firestore batch carries the download URL.
    func postMessage(
        threadId: String,
        text: String,
        author: Author,
        image: PreparedImage? = nil,
        audio: PreparedAudio? = nil,
        video: PreparedVideo? = nil,
        sticker: String? = nil,
        reply: MessageReply? = nil,
        onFinished: (() -> Void)? = nil
    ) {
        switch backend {
        case let .firestore(firestore, uploader):
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            guard image != nil || audio != nil || video != nil || sticker != nil || !trimmed.isEmpty else {
                onFinished?()
                return
            }
            let threadRef = firestore.collection(Fs.threads).document(threadId)
            let messageRef = threadRef.collection(Fs.messages).document()

            Task { [weak self] in
                defer { onFinished?() }
                do {
                    var fields: [String: Any] = [
                        Fs.text: sticker ?? trimmed,
                        Fs.authorId: author.uid,
                        Fs.authorName: author.displayName,
                        Fs.createdAt: FieldValue.serverTimestamp(),
                    ]
                    if let reply {
                        fields[Fs.replyToId] = reply.id
                        fields[Fs.replyToText] = String(reply.text.prefix(200))
                        fields[Fs.replyToAuthorName] = String(reply.authorName.prefix(64))
                    }
                    if let sticker {
                        fields[Fs.kindMessage] = MessageKind.sticker.rawValue
                        fields[Fs.text] = sticker
                    } else if let image {
                        let url = try await uploader.upload(
                            threadId: threadId,
                            messageId: messageRef.documentID,
                            image: image
                        )
                        fields[Fs.kindMessage] = MessageKind.image.rawValue
                        fields[Fs.imagePath] = url
                        fields[Fs.imageWidth] = image.width
                        fields[Fs.imageHeight] = image.height
                    } else if let video {
                        let url = try await uploader.upload(
                            threadId: threadId,
                            messageId: messageRef.documentID,
                            video: video
                        )
                        fields[Fs.kindMessage] = MessageKind.video.rawValue
                        fields[Fs.text] = ""
                        fields[Fs.videoPath] = url
                        fields[Fs.videoDurationMs] = video.durationMs
                        fields[Fs.videoWidth] = video.width
                        fields[Fs.videoHeight] = video.height
                    } else if let audio {
                        let url = try await uploader.upload(
                            threadId: threadId,
                            messageId: messageRef.documentID,
                            audio: audio
                        )
                        fields[Fs.kindMessage] = MessageKind.voice.rawValue
                        fields[Fs.text] = ""
                        fields[Fs.audioPath] = url
                        fields[Fs.audioDurationMs] = audio.durationMs
                    } else {
                        fields[Fs.kindMessage] = MessageKind.text.rawValue
                    }

                    let batch = firestore.batch()
                    batch.setData(fields, forDocument: messageRef)
                    batch.updateData(
                        [
                            Fs.lastMessageAt: FieldValue.serverTimestamp(),
                            Fs.messageCount: FieldValue.increment(Int64(1)),
                        ],
                        forDocument: threadRef
                    )
                    try await batch.commit()
                } catch {
                    self?.onError?(Self.mapMediaUploadError(error))
                }
            }
        case let .local(store):
            store.postMessage(
                threadId: threadId,
                text: text,
                author: author,
                image: image,
                audio: audio,
                video: video,
                sticker: sticker,
                reply: reply
            )
            onFinished?()
        }
    }

    private static func mapMediaUploadError(_ error: Error) -> Error {
        let raw = error.localizedDescription
        let message: String
        if raw.contains("413") || raw.contains("bad_size") {
            message = "Video is too large to upload"
        } else if raw.contains("415") || raw.contains("bad_magic") || raw.contains("unsupported_type") {
            message = "That video format is not supported"
        } else if raw.contains("429") || raw.contains("rate_limited") {
            message = "Slow down — try again in a minute"
        } else if raw.contains("401") || raw.contains("invalid_token") {
            message = "Sign in again to send video"
        } else {
            return error
        }
        return NSError(
            domain: "MapTalk.MediaUploader",
            code: (error as NSError).code,
            userInfo: [NSLocalizedDescriptionKey: message]
        )
    }

    /// Toggle one emoji reaction from this user. One emoji per user (switching replaces).
    func toggleReaction(threadId: String, messageId: String, emoji: String, author: Author) {
        switch backend {
        case let .firestore(firestore, _):
            let ref = firestore.collection(Fs.threads).document(threadId)
                .collection(Fs.messages).document(messageId)
            firestore.runTransaction({ transaction, errorPointer -> Any? in
                let snap: DocumentSnapshot
                do {
                    snap = try transaction.getDocument(ref)
                } catch let error as NSError {
                    errorPointer?.pointee = error
                    return nil
                }
                var reactions = snap.data()?[Fs.reactions] as? [String: [String]] ?? [:]
                // Remove this user from every emoji first.
                for key in reactions.keys {
                    reactions[key] = reactions[key]?.filter { $0 != author.uid }
                    if reactions[key]?.isEmpty == true { reactions[key] = nil }
                }
                var list = reactions[emoji] ?? []
                if list.contains(author.uid) {
                    list.removeAll { $0 == author.uid }
                } else {
                    list.append(author.uid)
                }
                if list.isEmpty {
                    reactions[emoji] = nil
                } else {
                    reactions[emoji] = list
                }
                transaction.updateData([Fs.reactions: reactions], forDocument: ref)
                return nil
            }, completion: { [weak self] _, error in
                if let error {
                    MainActor.assumeIsolated { self?.onError?(error) }
                }
            })
        case let .local(store):
            store.toggleReaction(threadId: threadId, messageId: messageId, emoji: emoji, author: author)
        }
    }
}

private extension [ChatThread] {
    func within(_ radiusMeters: Double, of center: GeoPoint) -> [ChatThread] {
        var seen = Set<String>()
        return filter { thread in
            guard seen.insert(thread.id).inserted else { return false }
            return center.distance(to: thread.position) <= radiusMeters
        }
        .sorted { ($0.lastMessageAt ?? .distantPast) > ($1.lastMessageAt ?? .distantPast) }
    }
}
