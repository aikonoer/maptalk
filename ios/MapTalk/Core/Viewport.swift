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
