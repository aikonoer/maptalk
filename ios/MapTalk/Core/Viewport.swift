import Foundation

/// What the map should query, decided by how much ground the camera can see.
enum ViewportQuery: Equatable, Sendable {
    /// Geohash bounds query around the visible centre.
    case nearby(center: GeoPoint, radiusKm: Double)
    /// Most recently active threads worldwide.
    case globalRecent
}

/// How the map turns a camera position into a query and into markers.
///
/// These thresholds are a contract shared with Android
/// (`android/app/src/main/java/app/maptalk/geo/Viewport.kt`) and documented in
/// `docs/data-model.md`. Change them in all three places or the two apps stop showing the
/// same map.
enum Viewport {

    /// Beyond this the geohash bounds cover so much ground that we switch strategies.
    static let nearbyMaxRadiusKm = 50.0

    /// Documents fetched per geohash bound; there can be up to nine bounds.
    static let perBoundLimit = 40

    /// Threads fetched when zoomed out past `nearbyMaxRadiusKm`.
    static let globalLimit = 200

    static func query(center: GeoPoint, radiusKm: Double) -> ViewportQuery {
        radiusKm <= nearbyMaxRadiusKm ? .nearby(center: center, radiusKm: radiusKm) : .globalRecent
    }

    /// Geohash prefix length used to group markers, or nil when the map is zoomed in far
    /// enough to show every thread on its own.
    static func clusterPrefixLength(radiusKm: Double) -> Int? {
        switch radiusKm {
        case let radius where radius > 500: 2
        case let radius where radius > 100: 3
        case let radius where radius > 25: 4
        case let radius where radius > 5: 5
        case let radius where radius > 1: 6
        default: nil
        }
    }

    /// Chats packed tighter than this still draw on top of each other at the deepest zoom, so
    /// moving the camera cannot pull them apart.
    static let minDrillSpreadKm = 0.03

    /// How much wider the camera ends up than the members' bare box: the room left for the bubbles
    /// themselves plus the screen padding around them. Predicting the fitted view too tightly would
    /// promise a group can be spread out when the camera actually lands back on the same marker.
    private static let fitSlack = GeoBounds.fitSlack

    /// Where to put the camera when a grouped marker is tapped, or nil when the group cannot be
    /// opened up by moving the camera and the caller should list its chats instead.
    ///
    /// Fitting the members' own bounds is what keeps the group findable: every chat that was under
    /// the marker is still on screen afterwards, rather than scattered outside it by a blind zoom
    /// step. Nil comes back when the members sit almost on the same spot, or when the fitted view
    /// would still be wide enough to group them under one geohash prefix — in both cases the camera
    /// would land on the same single marker and the chats would look lost.
    static func drillFit<Item>(
        _ items: [Item],
        geohash: (Item) -> String,
        position: (Item) -> GeoPoint
    ) -> GeoBounds? {
        guard items.count > 1 else { return nil }
        let bounds = GeoBounds(containing: items.map(position))
        guard bounds.radiusKm >= minDrillSpreadKm else { return nil }
        if let prefix = clusterPrefixLength(radiusKm: bounds.radiusKm * fitSlack) {
            let cells = Set(items.map { String(geohash($0).prefix(prefix)) })
            guard cells.count > 1 else { return nil }
        }
        return bounds
    }
}

/// The corners of the smallest box holding every given point. Local to a cluster, so it does not
/// try to reason about the antimeridian.
struct GeoBounds: Equatable {
    let southwest: GeoPoint
    let northeast: GeoPoint

    init(southwest: GeoPoint, northeast: GeoPoint) {
        self.southwest = southwest
        self.northeast = northeast
    }

    init(containing points: [GeoPoint]) {
        southwest = GeoPoint(
            lat: points.map(\.lat).min() ?? 0,
            lng: points.map(\.lng).min() ?? 0
        )
        northeast = GeoPoint(
            lat: points.map(\.lat).max() ?? 0,
            lng: points.map(\.lng).max() ?? 0
        )
    }

    var center: GeoPoint {
        GeoPoint(
            lat: (southwest.lat + northeast.lat) / 2,
            lng: (southwest.lng + northeast.lng) / 2
        )
    }

    /// Centre to corner, the same measure the map reports for the visible region.
    var radiusKm: Double { center.distance(to: northeast) / 1_000 }

    var latitudeSpan: Double { northeast.lat - southwest.lat }
    var longitudeSpan: Double { northeast.lng - southwest.lng }

    /// A bubble is up to about half a screen wide and hangs up and right of the point it marks, so
    /// the outermost chat needs close to another box width on that side to stay whole.
    static let bubbleRoom = 0.9

    /// Enough to keep the trailing edge of the group off the very edge of the screen.
    static let edgeRoom = 0.08

    /// The margin the map itself leaves around a fitted box, as a share of the box.
    static let screenPaddingSlack = 1.1

    /// A group in a straight line has no width on one axis; about 90 m keeps the box usable.
    private static let minRoomBasisDegrees = 0.0008

    /// The same box with room added up and to the right, where a bubble hangs from the corner that
    /// sits on its coordinate. Fitting the bare box puts every chat on screen but runs the
    /// outermost label off the edge, which is half of what makes a group feel lost.
    func withRoomForBubbles() -> GeoBounds {
        let basis = max(latitudeSpan, longitudeSpan, Self.minRoomBasisDegrees)
        return GeoBounds(
            southwest: GeoPoint(
                lat: southwest.lat - basis * Self.edgeRoom,
                lng: southwest.lng - basis * Self.edgeRoom
            ),
            northeast: GeoPoint(
                lat: northeast.lat + basis * Self.bubbleRoom,
                lng: northeast.lng + basis * Self.bubbleRoom
            )
        )
    }

    /// The camera lands this much wider than the bare box once bubble room and padding are added.
    static let fitSlack = (1 + bubbleRoom + edgeRoom) * screenPaddingSlack
}

/// A group of items drawn as one marker.
struct GeoCluster<Item>: Identifiable {
    let id: String
    let position: GeoPoint
    let items: [Item]

    var size: Int { items.count }
    var single: Item? { items.count == 1 ? items[0] : nil }
}

/// Groups items sharing a geohash prefix and places the marker at the mean position of the
/// group. A nil `prefixLength` leaves every item on its own.
func clusterByGeohash<Item>(
    _ items: [Item],
    prefixLength: Int?,
    geohash: (Item) -> String,
    position: (Item) -> GeoPoint,
    id: (Item) -> String
) -> [GeoCluster<Item>] {
    guard let prefixLength else {
        return items.map { GeoCluster(id: id($0), position: position($0), items: [$0]) }
    }
    let grouped = Dictionary(grouping: items) { String(geohash($0).prefix(prefixLength)) }
    return grouped.map { prefix, members in
        if members.count == 1 {
            return GeoCluster(id: id(members[0]), position: position(members[0]), items: members)
        }
        let mean = GeoPoint(
            lat: members.reduce(0) { $0 + position($1).lat } / Double(members.count),
            lng: members.reduce(0) { $0 + position($1).lng } / Double(members.count)
        )
        return GeoCluster(id: "cluster:\(prefix)", position: mean, items: members)
    }
    .sorted { $0.id < $1.id }
}
