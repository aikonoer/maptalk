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
    /// Image messages upload to Storage first, then the Firestore batch carries the download URL.
    func postMessage(
        threadId: String,
        text: String,
        author: Author,
        image: PreparedImage? = nil
    ) {
        switch backend {
        case let .firestore(firestore, uploader):
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            guard image != nil || !trimmed.isEmpty else { return }
            let threadRef = firestore.collection(Fs.threads).document(threadId)
            let messageRef = threadRef.collection(Fs.messages).document()

            Task { [weak self] in
                do {
                    var fields: [String: Any] = [
                        Fs.text: trimmed,
                        Fs.authorId: author.uid,
                        Fs.authorName: author.displayName,
                        Fs.createdAt: FieldValue.serverTimestamp(),
                    ]
                    if let image {
                        let url = try await uploader.upload(
                            threadId: threadId,
                            messageId: messageRef.documentID,
                            image: image
                        )
                        fields[Fs.kindMessage] = MessageKind.image.rawValue
                        fields[Fs.imagePath] = url
                        fields[Fs.imageWidth] = image.width
                        fields[Fs.imageHeight] = image.height
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
                    self?.onError?(error)
                }
            }
        case let .local(store):
            store.postMessage(threadId: threadId, text: text, author: author, image: image)
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
