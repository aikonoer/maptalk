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

    var errorMessage: String?

    private let repository: ThreadRepository
    private let safety: SafetyRepository
    private var blockedUids: Set<String> = []
    private var queried: (center: GeoPoint, radiusKm: Double)?
    private var streamTask: Task<Void, Never>?
    private var blockTask: Task<Void, Never>?

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

    private func subscribe(center: GeoPoint, radiusKm: Double) {
        streamTask?.cancel()
        let query = Viewport.query(center: center, radiusKm: radiusKm)
        let prefixLength = Viewport.clusterPrefixLength(radiusKm: radiusKm)
        isGlobalView = query == .globalRecent

        streamTask = Task { [repository] in
            for await threads in repository.threads(for: query) {
                if Task.isCancelled { return }
                let visible = threads.filter { !blockedUids.contains($0.authorId) }
                bubbles = clusterByGeohash(
                    visible,
                    prefixLength: prefixLength,
                    geohash: \.geohash,
                    position: \.position,
                    id: \.id
                )
                isLoading = false
            }
        }
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

    func createThread(title: String, kind: ThreadKind, position: GeoPoint, author: Author) -> String {
        repository.createThread(title: title, kind: kind, position: position, author: author)
    }

    /// Latest messages for the long-press bubble peek (caller uses `.last`).
    func peekMessages(threadId: String) async -> [Message] {
        for await messages in repository.messages(threadId: threadId) {
            return Array(messages.suffix(1))
        }
        return []
    }
}
