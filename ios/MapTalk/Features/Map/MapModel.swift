import Foundation

@MainActor
@Observable
final class MapModel {

    private(set) var bubbles: [GeoCluster<ChatThread>] = []
    private(set) var isGlobalView = false
    private(set) var isLoading = true

    /// Where a new chat would be pinned: the centre of what the camera can see.
    private(set) var visibleCenter = GeoPoint(lat: 20, lng: 10)
    private(set) var visibleRadiusKm = 5_000.0

    /// Empty = show every kind. Non-empty = only those kinds (client-side; no query change).
    private(set) var kindFilter: Set<ThreadKind> = []

    /// True when the filter hid every nearby chat that would otherwise show.
    private(set) var isFilterHidingAll = false

    var errorMessage: String?

    private let repository: ThreadRepository
    private let safety: SafetyRepository
    private var blockedUids: Set<String> = []
    private var queried: (center: GeoPoint, radiusKm: Double)?
    private var streamTask: Task<Void, Never>?
    private var blockTask: Task<Void, Never>?
    /// Last stream snapshot after block filtering, before kind filter + clustering.
    private var latestThreads: [ChatThread] = []
    private var clusterPrefixLength: Int?

    init(repository: ThreadRepository, safety: SafetyRepository) {
        self.repository = repository
        self.safety = safety
        repository.onError = { [weak self] error in
            self?.errorMessage = error.localizedDescription
        }
        safety.onError = { [weak self] error in
            self?.errorMessage = error.localizedDescription
        }
    }

    var isKindFilterActive: Bool { !kindFilter.isEmpty }

    func start() {
        guard blockTask == nil else { return }
        blockTask = Task { [safety] in
            for await people in safety.blockedPeople() {
                blockedUids = Set(people.map(\.uid))
                if let queried {
                    // Re-apply filter on the current stream by re-subscribing.
                    subscribe(center: queried.center, radiusKm: queried.radiusKm)
                }
            }
        }
    }

    func stop() {
        streamTask?.cancel()
        streamTask = nil
        blockTask?.cancel()
        blockTask = nil
    }

    func cameraChanged(center: GeoPoint, radiusKm: Double) {
        visibleCenter = center
        visibleRadiusKm = radiusKm
        guard isMeaningfulMove(center: center, radiusKm: radiusKm) else { return }
        queried = (center, radiusKm)
        subscribe(center: center, radiusKm: radiusKm)
    }

    func clearKindFilter() {
        guard !kindFilter.isEmpty else { return }
        kindFilter = []
        publishBubbles()
    }

    func toggleKindFilter(_ kind: ThreadKind) {
        if kindFilter.isEmpty {
            kindFilter = [kind]
        } else if kindFilter.contains(kind) {
            kindFilter.remove(kind)
        } else {
            kindFilter.insert(kind)
        }
        if kindFilter.count == ThreadKind.allCases.count {
            kindFilter = []
        }
        publishBubbles()
    }

    private func subscribe(center: GeoPoint, radiusKm: Double) {
        streamTask?.cancel()
        let query = Viewport.query(center: center, radiusKm: radiusKm)
        clusterPrefixLength = Viewport.clusterPrefixLength(radiusKm: radiusKm)
        isGlobalView = query == .globalRecent

        streamTask = Task { [repository] in
            for await threads in repository.threads(for: query) {
                if Task.isCancelled { return }
                latestThreads = threads.filter { !blockedUids.contains($0.authorId) }
                publishBubbles()
                isLoading = false
            }
        }
    }

    private func publishBubbles() {
        let visible: [ChatThread]
        if kindFilter.isEmpty {
            visible = latestThreads
            isFilterHidingAll = false
        } else {
            visible = latestThreads.filter { kindFilter.contains($0.kind) }
            isFilterHidingAll = visible.isEmpty && !latestThreads.isEmpty
        }
        bubbles = clusterByGeohash(
            visible,
            prefixLength: clusterPrefixLength,
            geohash: \.geohash,
            position: \.position,
            id: \.id
        )
    }

    /// Panning a map produces a stream of positions. Re-subscribing Firestore for every one of
    /// them would be wasteful, so a move only counts once it changes the zoom noticeably,
    /// shifts the centre by a fifth of the visible radius, or crosses the line between the
    /// nearby and the worldwide query.
    private func isMeaningfulMove(center: GeoPoint, radiusKm: Double) -> Bool {
        guard let queried else { return true }
        let wasGlobal = Viewport.query(center: queried.center, radiusKm: queried.radiusKm) == .globalRecent
        let isGlobal = Viewport.query(center: center, radiusKm: radiusKm) == .globalRecent
        if wasGlobal != isGlobal { return true }
        let zoomRatio = radiusKm / queried.radiusKm
        if zoomRatio < 0.8 || zoomRatio > 1.25 { return true }
        return center.distance(to: queried.center) > queried.radiusKm * 1_000 * 0.2
    }

    func createThread(
        title: String,
        kind: ThreadKind,
        position: GeoPoint,
        author: Author,
        openingText: String = "",
        openingImage: PreparedImage? = nil
    ) -> String {
        let id = repository.createThread(title: title, kind: kind, position: position, author: author)
        let opening = openingText.trimmingCharacters(in: .whitespacesAndNewlines)
        if openingImage != nil || !opening.isEmpty {
            repository.postMessage(
                threadId: id,
                text: opening,
                author: author,
                image: openingImage
            )
        }
        return id
    }

    /// Up to three tip messages for the long-press bubble peek (oldest → newest).
    func peekMessages(threadId: String) async -> [Message] {
        for await messages in repository.messages(threadId: threadId) {
            return Array(messages.suffix(3))
        }
        return []
    }

    private(set) var isFindingClosest = false

    /// Nearest chat to the current camera centre — caller zooms out in place to reveal it.
    func findClosestChat() async -> ChatThread? {
        guard !isFindingClosest else { return nil }
        isFindingClosest = true
        defer { isFindingClosest = false }
        return await repository.nearestThread(
            from: visibleCenter,
            excludingAuthorIds: blockedUids
        )
    }
}
