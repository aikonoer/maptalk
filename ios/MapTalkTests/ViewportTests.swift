import Foundation
import Testing

@testable import MapTalk

/// Mirrors `android/app/src/test/java/app/maptalk/geo/ViewportTest.kt`. Both apps have to pick
/// the same query and the same cluster size for the same camera, or the maps disagree.
struct ViewportTests {

    private let sydney = GeoPoint(lat: -33.8688, lng: 151.2093)

    @Test
    func closeInViewsUseAGeohashBoundsQuery() {
        #expect(Viewport.query(center: sydney, radiusKm: 2) == .nearby(center: sydney, radiusKm: 2))
    }

    @Test
    func theBoundaryRadiusStillCountsAsNearby() {
        let query = Viewport.query(center: sydney, radiusKm: Viewport.nearbyMaxRadiusKm)
        #expect(query == .nearby(center: sydney, radiusKm: Viewport.nearbyMaxRadiusKm))
    }

    @Test
    func widerViewsFallBackToTheWorldwideActivityQuery() {
        #expect(Viewport.query(center: sydney, radiusKm: 51) == .globalRecent)
        #expect(Viewport.query(center: sydney, radiusKm: 8_000) == .globalRecent)
    }

    @Test
    func clusterPrefixShortensAsTheCameraPullsBack() {
        #expect(Viewport.clusterPrefixLength(radiusKm: 0.4) == nil)
        #expect(Viewport.clusterPrefixLength(radiusKm: 3) == 6)
        #expect(Viewport.clusterPrefixLength(radiusKm: 20) == 5)
        #expect(Viewport.clusterPrefixLength(radiusKm: 80) == 4)
        #expect(Viewport.clusterPrefixLength(radiusKm: 400) == 3)
        #expect(Viewport.clusterPrefixLength(radiusKm: 5_000) == 2)
    }
}

struct ClusterTests {

    private struct Pin {
        let id: String
        let geohash: String
        let position: GeoPoint
    }

    private func cluster(_ pins: [Pin], prefixLength: Int?) -> [GeoCluster<Pin>] {
        clusterByGeohash(
            pins,
            prefixLength: prefixLength,
            geohash: \.geohash,
            position: \.position,
            id: \.id
        )
    }

    @Test
    func aNilPrefixLeavesEveryPinOnItsOwn() {
        let pins = [
            Pin(id: "a", geohash: "r3gx2f303j", position: GeoPoint(lat: -33.86, lng: 151.20)),
            Pin(id: "b", geohash: "r3gx2f303k", position: GeoPoint(lat: -33.87, lng: 151.21)),
        ]
        let clusters = cluster(pins, prefixLength: nil)
        #expect(clusters.count == 2)
        #expect(clusters.allSatisfy { $0.size == 1 })
        #expect(Set(clusters.map(\.id)) == ["a", "b"])
    }

    @Test
    func pinsSharingAPrefixMergeAndSitAtTheMeanPosition() throws {
        let pins = [
            Pin(id: "a", geohash: "r3gx11", position: GeoPoint(lat: -34, lng: 151.0)),
            Pin(id: "b", geohash: "r3gx22", position: GeoPoint(lat: -34, lng: 151.4)),
            Pin(id: "c", geohash: "r3gy00", position: GeoPoint(lat: -30, lng: 150.0)),
        ]
        let clusters = cluster(pins, prefixLength: 4)

        #expect(clusters.count == 2)
        let merged = try #require(clusters.first { $0.size == 2 })
        #expect(merged.id == "cluster:r3gx")
        #expect(abs(merged.position.lat - -34) < 1e-9)
        #expect(abs(merged.position.lng - 151.2) < 1e-9)
        #expect(merged.single == nil)

        let alone = try #require(clusters.first { $0.size == 1 })
        #expect(alone.id == "c")
        #expect(alone.single?.id == "c")
    }

    @Test
    func aLonePinInItsCellKeepsItsOwnIdentityRatherThanBecomingACluster() {
        let pins = [Pin(id: "a", geohash: "r3gx11", position: GeoPoint(lat: -34, lng: 151))]
        #expect(cluster(pins, prefixLength: 2).first?.id == "a")
    }
}

struct RelativeTimeTests {

    @Test
    func formatsTheUsualBuckets() {
        let now = Date(timeIntervalSince1970: 1_700_000_000)
        #expect(relativeTime(nil, now: now) == "sending\u{2026}")
        #expect(relativeTime(now.addingTimeInterval(-30), now: now) == "now")
        #expect(relativeTime(now.addingTimeInterval(-4 * 60), now: now) == "4m")
        #expect(relativeTime(now.addingTimeInterval(-3 * 3_600), now: now) == "3h")
        #expect(relativeTime(now.addingTimeInterval(-2 * 86_400), now: now) == "2d")
        #expect(relativeTime(now.addingTimeInterval(-14 * 86_400), now: now) == "2w")
    }
}
