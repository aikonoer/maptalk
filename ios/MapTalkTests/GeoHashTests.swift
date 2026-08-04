import Foundation
import Testing

@testable import MapTalk

/// The fixtures here are the contract with the real GeoFire library on Android. The Kotlin
/// test `app.maptalk.geo.GeoHashParityTest` asserts the same inputs against
/// `com.firebase:geofire-android-common`, so if these two files agree, the Swift port and the
/// library agree, and a thread cannot show up on one platform but not the other. Both sides
/// sort bounds by start hash, since the library does not promise an order.
struct GeoHashTests {

    @Test
    func hashesMatchTheSharedFixtures() {
        // The example from the geohash literature.
        #expect(GeoHash.hash(for: GeoPoint(lat: 57.64911, lng: 10.40744), precision: 11) == "u4pruydqqvj")
        // Null island sits exactly on a cell boundary, and the algorithm rounds down.
        #expect(GeoHash.hash(for: GeoPoint(lat: 0, lng: 0), precision: 12) == "7zzzzzzzzzzz")
        #expect(GeoHash.hash(for: GeoPoint(lat: -90, lng: -180), precision: 12) == "000000000000")
        #expect(GeoHash.hash(for: GeoPoint(lat: 90, lng: 180), precision: 12) == "zzzzzzzzzzzz")
        #expect(GeoHash.hash(for: GeoPoint(lat: -33.8688, lng: 151.2093), precision: 10) == "r3gx2f77bn")
        #expect(GeoHash.hash(for: GeoPoint(lat: 51.5074, lng: -0.1278), precision: 10) == "gcpvj0duq5")
        #expect(GeoHash.hash(for: GeoPoint(lat: 37.7749, lng: -122.4194), precision: 10) == "9q8yyk8ytp")
    }

    @Test
    func defaultPrecisionIsTenCharacters() {
        #expect(GeoHash.hash(for: GeoPoint(lat: -33.8688, lng: 151.2093)).count == 10)
    }

    @Test
    func queryBoundsMatchTheSharedFixtures() {
        #expect(
            bounds(lat: -33.8688, lng: 151.2093, radius: 1_000) == [
                "r3gx28|r3gx2h", "r3gx2s|r3gx2w", "r3gx30|r3gx38", "r3gx3h|r3gx3n",
            ]
        )
        // '~' is the character just past 'z', which is how an open ended cell is expressed.
        #expect(
            bounds(lat: 51.5074, lng: -0.1278, radius: 5_000) == [
                "gcpu8|gcpuh", "gcpus|gcpu~", "gcpv0|gcpv8", "gcpvh|gcpvs",
            ]
        )
        #expect(
            bounds(lat: 0, lng: 0, radius: 50_000) == [
                "7zzh|7zz~", "ebp0|ebph", "kpbh|kpb~", "s000|s00h",
            ]
        )
    }

    /// Whatever the bounds are, they have to actually cover the circle: a point inside the
    /// radius must fall inside one of the ranges, or its thread would never be queried.
    @Test
    func boundsCoverEveryPointInsideTheRadius() {
        let center = GeoPoint(lat: -33.8688, lng: 151.2093)
        let radiusMeters = 2_000.0
        let ranges = GeoHash.queryBounds(for: center, radiusMeters: radiusMeters)

        // A ring of points just inside the radius, plus the centre.
        var samples = [center]
        for degrees in stride(from: 0.0, to: 360.0, by: 15.0) {
            let radians = degrees * .pi / 180
            let latOffset = (radiusMeters * 0.95 / 111_320) * cos(radians)
            let lngOffset = (radiusMeters * 0.95 / 111_320) * sin(radians)
                / cos(center.lat * .pi / 180)
            samples.append(GeoPoint(lat: center.lat + latOffset, lng: center.lng + lngOffset))
        }

        for sample in samples {
            let hash = GeoHash.hash(for: sample)
            let covered = ranges.contains { hash >= $0.startHash && hash <= $0.endHash }
            #expect(covered, "\(hash) at \(sample) was not covered by any bound")
        }
    }

    private func bounds(lat: Double, lng: Double, radius: Double) -> [String] {
        GeoHash.queryBounds(for: GeoPoint(lat: lat, lng: lng), radiusMeters: radius)
            .map { "\($0.startHash)|\($0.endHash)" }
    }
}

struct GeoPointTests {

    @Test
    func aDegreeOfLatitudeIsAboutOneHundredAndElevenKilometres() {
        let meters = GeoPoint(lat: 0, lng: 0).distance(to: GeoPoint(lat: 1, lng: 0))
        #expect(abs(meters - 111_195) < 200)
    }

    @Test
    func knownCityPairMatchesTheGreatCircleDistance() {
        // Sydney to Melbourne, roughly 713 km.
        let meters = GeoPoint(lat: -33.8688, lng: 151.2093)
            .distance(to: GeoPoint(lat: -37.8136, lng: 144.9631))
        #expect(abs(meters - 713_000) < 5_000)
    }

    @Test
    func distanceIsSymmetricAndZeroForTheSamePoint() {
        let a = GeoPoint(lat: 51.5074, lng: -0.1278)
        let b = GeoPoint(lat: 48.8566, lng: 2.3522)
        #expect(abs(a.distance(to: b) - b.distance(to: a)) < 1e-6)
        #expect(a.distance(to: a) == 0)
    }
}
