import Foundation

/// On-device stand-in for Auth + Firestore. Seeds a Cebu City neighbourhood of chats so the
/// phone can be tried with no Mac, no hotspot, and no Firebase project.
///
/// Everything lives in memory (plus a UserDefaults display name). Kill the app and the replies
/// you typed are gone; the seed comes back on the next launch.
@MainActor
final class LocalDemoStore {

    static let cebu = GeoPoint(lat: 10.3157, lng: 123.8854)

    private(set) var uid = "local-demo-user"
    private(set) var displayName: String?

    private var threads: [String: ChatThread] = [:]
    private var messages: [String: [Message]] = [:]
    /// uid → display name
    private var blocked: [String: String] = [:]

    private var threadListeners: [UUID: ThreadListener] = [:]
    private var singleThreadListeners: [UUID: SingleThreadListener] = [:]
    private var messageListeners: [UUID: MessageListener] = [:]
    private var displayNameListeners: [UUID: AsyncStream<String?>.Continuation] = [:]
    private var profileListeners: [UUID: AsyncStream<(displayName: String?, photoURL: String?)>.Continuation] = [:]
    private var blockListeners: [UUID: AsyncStream<[BlockedPerson]>.Continuation] = [:]

    private static let displayNameKey = "maptalk.localDemo.displayName"
    private static let photoURLKey = "maptalk.localDemo.photoURL"
    private static let blocksKey = "maptalk.localDemo.blocks"

    private var photoURL: String? {
        UserDefaults.standard.string(forKey: Self.photoURLKey)
    }

    init(seedCebu: Bool = true) {
        displayName = UserDefaults.standard.string(forKey: Self.displayNameKey)
        if let stored = UserDefaults.standard.dictionary(forKey: Self.blocksKey) as? [String: String] {
            blocked = stored
        } else if let legacy = UserDefaults.standard.stringArray(forKey: Self.blocksKey) {
            blocked = Dictionary(uniqueKeysWithValues: legacy.map { ($0, "Blocked user") })
        }
        if seedCebu {
            for pack in Self.cebuSeed() {
                threads[pack.thread.id] = pack.thread
                messages[pack.thread.id] = pack.messages
            }
        }
    }

    // MARK: - Auth

    func ensureSignedIn() {}

    func displayNameStream() -> AsyncStream<String?> {
        let id = UUID()
        return AsyncStream { continuation in
            displayNameListeners[id] = continuation
            continuation.yield(displayName)
            continuation.onTermination = { [weak self] _ in
                Task { @MainActor in self?.displayNameListeners[id] = nil }
            }
        }
    }

    func saveDisplayName(_ name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        displayName = trimmed
        UserDefaults.standard.set(trimmed, forKey: Self.displayNameKey)
        for continuation in displayNameListeners.values {
            continuation.yield(displayName)
        }
        publishProfile()
    }

    func profileStream() -> AsyncStream<(displayName: String?, photoURL: String?)> {
        let id = UUID()
        return AsyncStream { continuation in
            profileListeners[id] = continuation
            continuation.yield((displayName, photoURL))
            continuation.onTermination = { [weak self] _ in
                Task { @MainActor in self?.profileListeners[id] = nil }
            }
        }
    }

    func saveAvatarJPEG(_ data: Data) throws -> String {
        let relative = "avatars/\(uid).jpg"
        let url = LocalMediaStore.url(forRelativePath: relative)
        try FileManager.default.createDirectory(
            at: url.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        try data.write(to: url, options: .atomic)
        UserDefaults.standard.set(relative, forKey: Self.photoURLKey)
        publishProfile()
        return relative
    }

    func removeAvatar() {
        if let relative = photoURL {
            try? FileManager.default.removeItem(at: LocalMediaStore.url(forRelativePath: relative))
        }
        UserDefaults.standard.removeObject(forKey: Self.photoURLKey)
        publishProfile()
    }

    private func publishProfile() {
        let snapshot = (displayName, photoURL)
        for continuation in profileListeners.values {
            continuation.yield(snapshot)
        }
    }

    // MARK: - Safety

    func blockedPeople() -> AsyncStream<[BlockedPerson]> {
        let id = UUID()
        return AsyncStream { continuation in
            blockListeners[id] = continuation
            continuation.yield(peopleSnapshot())
            continuation.onTermination = { [weak self] _ in
                Task { @MainActor in self?.blockListeners[id] = nil }
            }
        }
    }

    func block(uid blockedUid: String, displayName: String) {
        guard blockedUid != uid else { return }
        blocked[blockedUid] = displayName
        UserDefaults.standard.set(blocked, forKey: Self.blocksKey)
        publishBlocks()
    }

    func unblock(uid blockedUid: String) {
        blocked.removeValue(forKey: blockedUid)
        UserDefaults.standard.set(blocked, forKey: Self.blocksKey)
        publishBlocks()
    }

    func report(
        type: ReportTargetType,
        targetId: String,
        threadId: String,
        targetAuthorId: String,
        reason: ReportReason
    ) {
        // Local demo: accept and forget — nothing to review offline.
        _ = (type, targetId, threadId, targetAuthorId, reason)
    }

    private func peopleSnapshot() -> [BlockedPerson] {
        blocked.map { BlockedPerson(uid: $0.key, displayName: $0.value) }
            .sorted { $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending }
    }

    private func publishBlocks() {
        let people = peopleSnapshot()
        for continuation in blockListeners.values {
            continuation.yield(people)
        }
    }

    // MARK: - Threads

    func threads(for query: ViewportQuery) -> AsyncStream<[ChatThread]> {
        let id = UUID()
        return AsyncStream { continuation in
            threadListeners[id] = ThreadListener(query: query, continuation: continuation)
            continuation.yield(snapshot(for: query))
            continuation.onTermination = { [weak self] _ in
                Task { @MainActor in self?.threadListeners[id] = nil }
            }
        }
    }

    func thread(id threadId: String) -> AsyncStream<ChatThread?> {
        let id = UUID()
        return AsyncStream { continuation in
            singleThreadListeners[id] = SingleThreadListener(threadId: threadId, continuation: continuation)
            continuation.yield(threads[threadId])
            continuation.onTermination = { [weak self] _ in
                Task { @MainActor in self?.singleThreadListeners[id] = nil }
            }
        }
    }

    func messages(threadId: String) -> AsyncStream<[Message]> {
        let id = UUID()
        return AsyncStream { continuation in
            messageListeners[id] = MessageListener(threadId: threadId, continuation: continuation)
            continuation.yield(messages[threadId] ?? [])
            continuation.onTermination = { [weak self] _ in
                Task { @MainActor in self?.messageListeners[id] = nil }
            }
        }
    }

    func createThread(title: String, kind: ThreadKind, position: GeoPoint, author: Author) -> String {
        let id = "local-\(UUID().uuidString.prefix(8))"
        let now = Date()
        let thread = ChatThread(
            id: id,
            title: title.trimmingCharacters(in: .whitespacesAndNewlines),
            kind: kind,
            position: position,
            geohash: GeoHash.hash(for: position),
            authorId: author.uid,
            authorName: author.displayName,
            createdAt: now,
            lastMessageAt: now,
            messageCount: 0
        )
        threads[id] = thread
        messages[id] = []
        publishAllThreadLists()
        publishThread(id)
        return id
    }

    func postMessage(
        threadId: String,
        text: String,
        author: Author,
        image: PreparedImage? = nil,
        audio: PreparedAudio? = nil,
        video: PreparedVideo? = nil,
        sticker: String? = nil,
        reply: MessageReply? = nil
    ) {
        guard let existing = threads[threadId] else { return }
        let now = Date()
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)

        let kind: MessageKind
        var imagePath: String?
        var imageWidth: Int?
        var imageHeight: Int?
        var audioPath: String?
        var audioDurationMs: Int?
        var videoPath: String?
        var videoDurationMs: Int?
        var videoWidth: Int?
        var videoHeight: Int?
        var body = trimmed

        if let sticker {
            kind = .sticker
            body = sticker
        } else if let image {
            kind = .image
            imagePath = try? LocalMediaStore.save(jpeg: image.jpegData)
            imageWidth = image.width
            imageHeight = image.height
            if imagePath == nil { return }
        } else if let video {
            kind = .video
            if let data = try? Data(contentsOf: video.fileURL) {
                videoPath = try? LocalMediaStore.save(video: data)
            }
            video.deleteTempFile()
            videoDurationMs = video.durationMs
            videoWidth = video.width
            videoHeight = video.height
            if videoPath == nil { return }
        } else if let audio {
            kind = .voice
            body = ""
            audioPath = try? LocalMediaStore.save(audio: audio.data, ext: "m4a")
            audioDurationMs = audio.durationMs
            if audioPath == nil { return }
        } else {
            kind = .text
            if trimmed.isEmpty { return }
        }

        let message = Message(
            id: "local-msg-\(UUID().uuidString.prefix(8))",
            kind: kind,
            text: body,
            authorId: author.uid,
            authorName: author.displayName,
            createdAt: now,
            imagePath: imagePath,
            imageWidth: imageWidth,
            imageHeight: imageHeight,
            audioPath: audioPath,
            audioDurationMs: audioDurationMs,
            videoPath: videoPath,
            videoDurationMs: videoDurationMs,
            videoWidth: videoWidth,
            videoHeight: videoHeight,
            reply: reply
        )
        messages[threadId, default: []].append(message)
        threads[threadId] = ChatThread(
            id: existing.id,
            title: existing.title,
            kind: existing.kind,
            position: existing.position,
            geohash: existing.geohash,
            authorId: existing.authorId,
            authorName: existing.authorName,
            createdAt: existing.createdAt,
            lastMessageAt: now,
            messageCount: existing.messageCount + 1
        )
        publishAllThreadLists()
        publishThread(threadId)
        publishMessages(threadId)
    }

    func toggleReaction(threadId: String, messageId: String, emoji: String, author: Author) {
        guard var list = messages[threadId],
              let index = list.firstIndex(where: { $0.id == messageId })
        else { return }
        var reactions = list[index].reactions
        for key in reactions.keys {
            reactions[key] = reactions[key]?.filter { $0 != author.uid }
            if reactions[key]?.isEmpty == true { reactions[key] = nil }
        }
        var uids = reactions[emoji] ?? []
        if uids.contains(author.uid) {
            uids.removeAll { $0 == author.uid }
        } else {
            uids.append(author.uid)
        }
        if uids.isEmpty {
            reactions[emoji] = nil
        } else {
            reactions[emoji] = uids
        }
        let old = list[index]
        list[index] = Message(
            id: old.id,
            kind: old.kind,
            text: old.text,
            authorId: old.authorId,
            authorName: old.authorName,
            createdAt: old.createdAt,
            imagePath: old.imagePath,
            imageWidth: old.imageWidth,
            imageHeight: old.imageHeight,
            audioPath: old.audioPath,
            audioDurationMs: old.audioDurationMs,
            videoPath: old.videoPath,
            videoDurationMs: old.videoDurationMs,
            videoWidth: old.videoWidth,
            videoHeight: old.videoHeight,
            reply: old.reply,
            reactions: reactions
        )
        messages[threadId] = list
        publishMessages(threadId)
    }

    // MARK: - Internals

    private func snapshot(for query: ViewportQuery) -> [ChatThread] {
        let all = Array(threads.values)
        switch query {
        case let .nearby(center, radiusKm):
            let radiusMeters = radiusKm * 1_000
            return all
                .filter { center.distance(to: $0.position) <= radiusMeters }
                .sorted { ($0.lastMessageAt ?? .distantPast) > ($1.lastMessageAt ?? .distantPast) }
        case .globalRecent:
            return Array(
                all
                    .sorted { ($0.lastMessageAt ?? .distantPast) > ($1.lastMessageAt ?? .distantPast) }
                    .prefix(Viewport.globalLimit)
            )
        }
    }

    private func publishAllThreadLists() {
        for listener in threadListeners.values {
            listener.continuation.yield(snapshot(for: listener.query))
        }
    }

    private func publishThread(_ threadId: String) {
        for listener in singleThreadListeners.values where listener.threadId == threadId {
            listener.continuation.yield(threads[threadId])
        }
    }

    private func publishMessages(_ threadId: String) {
        for listener in messageListeners.values where listener.threadId == threadId {
            listener.continuation.yield(messages[threadId] ?? [])
        }
    }

    private struct ThreadListener {
        let query: ViewportQuery
        let continuation: AsyncStream<[ChatThread]>.Continuation
    }

    private struct SingleThreadListener {
        let threadId: String
        let continuation: AsyncStream<ChatThread?>.Continuation
    }

    private struct MessageListener {
        let threadId: String
        let continuation: AsyncStream<[Message]>.Continuation
    }
}

// MARK: - Cebu seed

private extension LocalDemoStore {

    struct Pack {
        let thread: ChatThread
        let messages: [Message]
    }

    /// Metres offset from Cebu City centre, matching the shape of `scripts/seed-emulator.mjs`.
    static func cebuSeed() -> [Pack] {
        let center = cebu
        func at(_ north: Double, _ east: Double) -> GeoPoint {
            GeoPoint(
                lat: center.lat + north / 111_320,
                lng: center.lng + east / (111_320 * cos(center.lat * .pi / 180))
            )
        }

        func pack(
            id: String,
            title: String,
            kind: ThreadKind,
            position: GeoPoint,
            author: String,
            ageMinutes: Double,
            replies: [(String, String, Double)]
        ) -> Pack {
            let created = Date().addingTimeInterval(-ageMinutes * 60)
            let lastReplyAge = replies.map(\.2).min() ?? ageMinutes
            let lastMessageAt = Date().addingTimeInterval(-lastReplyAge * 60)
            let authorId = "seed-\(author.lowercased().filter(\.isLetter))"
            let thread = ChatThread(
                id: id,
                title: title,
                kind: kind,
                position: position,
                geohash: GeoHash.hash(for: position),
                authorId: authorId,
                authorName: author,
                createdAt: created,
                lastMessageAt: lastMessageAt,
                messageCount: replies.count
            )
            let messages = replies.enumerated().map { index, reply in
                Message(
                    id: "\(id)-msg-\(index)",
                    text: reply.1,
                    authorId: "seed-\(reply.0.lowercased().filter(\.isLetter))",
                    authorName: reply.0,
                    createdAt: Date().addingTimeInterval(-reply.2 * 60)
                )
            }
            return Pack(thread: thread, messages: messages)
        }

        return [
            pack(
                id: "cebu-waterfront",
                title: "Anyone else at Waterfront for the concert?",
                kind: .event,
                position: at(120, 80),
                author: "Priya",
                ageMinutes: 95,
                replies: [
                    ("Priya", "Doors were quick, barely queued", 90),
                    ("Marcus", "Standing left is packed, plenty of room on the right", 74),
                    ("Tomas", "Main act on at 9 apparently", 41),
                    ("Priya", "Sound is unreal from the front", 12),
                ]
            ),
            pack(
                id: "cebu-itpark",
                title: "Closing early tonight at IT Park — family thing",
                kind: .notice,
                position: at(-260, 140),
                author: "Loretta (Bar Sesa)",
                ageMinutes: 180,
                replies: [
                    ("Loretta (Bar Sesa)", "Kitchen is off but coffee is on until then", 175),
                    ("Dan", "Thanks for the heads up, will come by tomorrow", 120),
                ]
            ),
            pack(
                id: "cebu-bridge",
                title: "Mactan–Mandaue bridge crawling, what happened?",
                kind: .traffic,
                position: at(700, -220),
                author: "Kenji",
                ageMinutes: 52,
                replies: [
                    ("Kenji", "Stopped for ten minutes now", 50),
                    ("Ava", "Two lanes closed, looks like a breakdown not a crash", 44),
                    ("Sam", "Took the old bridge instead, saved me 20 min", 21),
                ]
            ),
            pack(
                id: "cebu-carbon",
                title: "Carbon Market is packed tonight, food stalls everywhere",
                kind: .event,
                position: at(-90, -310),
                author: "Rosa",
                ageMinutes: 240,
                replies: [
                    ("Rosa", "Lechon stall near the entrance is worth the queue", 230),
                    ("Ellie", "Live band started by Fuente", 66),
                ]
            ),
            pack(
                id: "cebu-cat",
                title: "Lost a grey cat around Lahug, very friendly",
                kind: .general,
                position: at(310, 420),
                author: "Hugo",
                ageMinutes: 400,
                replies: [
                    ("Hugo", "Answers to Miso, no collar", 395),
                    ("Nadia", "Saw a grey one near JY Square an hour ago", 88),
                ]
            ),
            pack(
                id: "cebu-brownout",
                title: "Brownout in Mabolo, anyone know how long?",
                kind: .notice,
                position: at(-620, -80),
                author: "Bea",
                ageMinutes: 150,
                replies: [("Bea", "VEC notice says back by 4", 140)]
            ),
            pack(
                id: "cebu-srp",
                title: "Pickup game at SRP courts in 20 if anyone wants in",
                kind: .general,
                position: at(430, -540),
                author: "Theo",
                ageMinutes: 35,
                replies: [
                    ("Theo", "Got 6, need 2 more", 33),
                    ("Ines", "On my way from Banilad", 8),
                ]
            ),
            pack(
                id: "cebu-seaside",
                title: "Queue for SM Seaside cinema is already around the corner",
                kind: .general,
                position: at(-410, 660),
                author: "Fen",
                ageMinutes: 70,
                replies: [("Fen", "Maybe 40 minutes from where I am", 68)]
            ),
            // A couple elsewhere so the worldwide zoom still has something to show.
            pack(
                id: "world-london",
                title: "Street food festival on the South Bank today",
                kind: .event,
                position: GeoPoint(lat: 51.5074, lng: -0.1278),
                author: "Amira",
                ageMinutes: 210,
                replies: [
                    ("Amira", "Runs until 8, free entry", 205),
                    ("Joe", "Beef bowl stall is the one to beat", 130),
                ]
            ),
            pack(
                id: "world-tokyo",
                title: "Cherry blossoms are past peak in the park",
                kind: .general,
                position: GeoPoint(lat: 35.6762, lng: 139.6503),
                author: "Rin",
                ageMinutes: 500,
                replies: [("Rin", "Still worth it early morning, no crowds", 495)]
            ),
        ]
    }
}
