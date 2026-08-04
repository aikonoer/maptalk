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
    private var queried: (center: GeoPoint, radiusKm: Double)?
    private var streamTask: Task<Void, Never>?

    init(repository: ThreadRepository) {
        self.repository = repository
        repository.onError = { [weak self] error in
            self?.errorMessage = error.localizedDescription
        }
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
                bubbles = clusterByGeohash(
                    threads,
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
}
