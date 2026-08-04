import Foundation

/// A geohash range to feed one Firestore query: `order(by:).start(at:).end(at:)`.
struct GeoHashQueryBound: Equatable, Sendable {
    let startHash: String
    let endHash: String
}

/// Geohashing and geohash query bounds.
///
/// Android gets these from `com.firebase:geofire-android-common`, which is not published for
/// Swift Package Manager, so this is a port of the same algorithm. It has to agree with the
/// library character for character or a thread would be visible on one platform and not the
/// other; `MapTalkTests/GeoHashTests.swift` pins the shared fixtures, and
/// `android/app/src/test/java/app/maptalk/geo/GeoHashParityTest.kt` asserts the same values
/// against the real library.
enum GeoHash {

    static let precision = 10

    private static let base32 = Array("0123456789bcdefghjkmnpqrstuvwxyz")
    private static let bitsPerChar = 5
    private static let maximumBitsPrecision = 22 * 5
    private static let metersPerDegreeLatitude = 110_574.0
    private static let earthMeridionalCircumference = 40_007_860.0
    private static let earthEquatorialRadius = 6_378_137.0
    /// Square of the Earth's eccentricity.
    private static let e2 = 0.006_694_478_197_99
    private static let epsilon = 1e-12

    /// The geohash for a point. Note that a coordinate sitting exactly on a cell boundary
    /// takes the lower cell, which is why (0, 0) hashes to "7zzz..." rather than "s000...".
    static func hash(for point: GeoPoint, precision: Int = precision) -> String {
        var latitudeRange = (min: -90.0, max: 90.0)
        var longitudeRange = (min: -180.0, max: 180.0)
        var hash = ""
        var hashValue = 0
        var bits = 0
        var even = true

        while hash.count < precision {
            let value = even ? point.lng : point.lat
            let mid: Double
            if even {
                mid = (longitudeRange.min + longitudeRange.max) / 2
                if value > mid {
                    hashValue = (hashValue << 1) + 1
                    longitudeRange.min = mid
                } else {
                    hashValue = hashValue << 1
                    longitudeRange.max = mid
                }
            } else {
                mid = (latitudeRange.min + latitudeRange.max) / 2
                if value > mid {
                    hashValue = (hashValue << 1) + 1
                    latitudeRange.min = mid
                } else {
                    hashValue = hashValue << 1
                    latitudeRange.max = mid
                }
            }
            even.toggle()

            if bits < 4 {
                bits += 1
            } else {
                bits = 0
                hash.append(base32[hashValue])
                hashValue = 0
            }
        }
        return hash
    }

    /// Ranges that together cover every cell touching the circle: nine candidates, deduplicated
    /// and then merged where they are adjacent or nested, which usually leaves four. The library
    /// returns these in an unspecified order; here they come back sorted by start hash.
    static func queryBounds(for center: GeoPoint, radiusMeters: Double) -> [GeoHashQueryBound] {
        let queryBits = max(1, boundingBoxBits(center, radiusMeters))
        let hashPrecision = Int(ceil(Double(queryBits) / Double(bitsPerChar)))
        var bounds: [GeoHashQueryBound] = []
        for coordinate in boundingBoxCoordinates(center, radiusMeters) {
            let bound = query(hash: hash(for: coordinate, precision: hashPrecision), bits: queryBits)
            if !bounds.contains(bound) {
                bounds.append(bound)
            }
        }
        return join(bounds).sorted { $0.startHash < $1.startHash }
    }

    /// Collapses bounds that touch or contain each other, so the map makes four queries instead
    /// of nine for the same coverage.
    private static func join(_ bounds: [GeoHashQueryBound]) -> [GeoHashQueryBound] {
        var bounds = bounds
        while true {
            var joined = false
            outer: for i in bounds.indices {
                for j in bounds.indices where j != i {
                    guard let merged = merge(bounds[i], bounds[j]) else { continue }
                    bounds.remove(at: Swift.max(i, j))
                    bounds.remove(at: Swift.min(i, j))
                    if !bounds.contains(merged) {
                        bounds.append(merged)
                    }
                    joined = true
                    break outer
                }
            }
            if !joined { return bounds }
        }
    }

    private static func merge(
        _ a: GeoHashQueryBound,
        _ b: GeoHashQueryBound
    ) -> GeoHashQueryBound? {
        /// True when `other` starts before `bound` and ends inside it, so the two are adjacent.
        func isPrefix(_ bound: GeoHashQueryBound, _ other: GeoHashQueryBound) -> Bool {
            other.endHash >= bound.startHash
                && other.startHash < bound.startHash
                && other.endHash < bound.endHash
        }
        /// True when `other` fully contains `bound`.
        func contains(_ bound: GeoHashQueryBound, _ other: GeoHashQueryBound) -> Bool {
            other.startHash <= bound.startHash && other.endHash >= bound.endHash
        }

        if isPrefix(a, b) {
            return GeoHashQueryBound(startHash: b.startHash, endHash: a.endHash)
        }
        if isPrefix(b, a) {
            return GeoHashQueryBound(startHash: a.startHash, endHash: b.endHash)
        }
        if contains(a, b) { return b }
        if contains(b, a) { return a }
        return nil
    }

    private static func query(hash: String, bits: Int) -> GeoHashQueryBound {
        let precision = Int(ceil(Double(bits) / Double(bitsPerChar)))
        // '~' is the character right after 'z', which leaves the range open ended.
        if hash.count < precision {
            return GeoHashQueryBound(startHash: hash, endHash: hash + "~")
        }
        let trimmed = String(hash.prefix(precision))
        let base = String(trimmed.dropLast())
        let lastValue = base32.firstIndex(of: trimmed.last!) ?? 0
        let significantBits = bits - (base.count * bitsPerChar)
        let unusedBits = bitsPerChar - significantBits
        let startValue = (lastValue >> unusedBits) << unusedBits
        let endValue = startValue + (1 << unusedBits)
        if endValue > 31 {
            return GeoHashQueryBound(startHash: base + String(base32[startValue]), endHash: base + "~")
        }
        return GeoHashQueryBound(
            startHash: base + String(base32[startValue]),
            endHash: base + String(base32[endValue])
        )
    }

    private static func boundingBoxBits(_ coordinate: GeoPoint, _ size: Double) -> Int {
        let latitudeDelta = size / metersPerDegreeLatitude
        let latitudeNorth = Swift.min(90, coordinate.lat + latitudeDelta)
        let latitudeSouth = Swift.max(-90, coordinate.lat - latitudeDelta)
        let bitsLatitude = Int(latitudeBits(for: size).rounded(.down)) * 2
        let bitsLongitudeNorth = Int(longitudeBits(for: size, at: latitudeNorth).rounded(.down)) * 2 - 1
        let bitsLongitudeSouth = Int(longitudeBits(for: size, at: latitudeSouth).rounded(.down)) * 2 - 1
        return Swift.min(bitsLatitude, bitsLongitudeNorth, bitsLongitudeSouth)
    }

    private static func latitudeBits(for resolution: Double) -> Double {
        Swift.min(
            log2(earthMeridionalCircumference / 2 / resolution),
            Double(maximumBitsPrecision)
        )
    }

    private static func longitudeBits(for resolution: Double, at latitude: Double) -> Double {
        let degrees = metersToLongitudeDegrees(resolution, latitude)
        return abs(degrees) > 0 ? Swift.max(1, log2(360 / degrees)) : 1
    }

    private static func metersToLongitudeDegrees(_ distance: Double, _ latitude: Double) -> Double {
        let radians = latitude * .pi / 180
        let numerator = cos(radians) * earthEquatorialRadius * .pi / 180
        let denominator = 1 / (1 - e2 * sin(radians) * sin(radians)).squareRoot()
        let deltaDegrees = numerator * denominator
        if deltaDegrees < epsilon {
            return distance > 0 ? 360 : distance
        }
        return Swift.min(360, distance / deltaDegrees)
    }

    private static func boundingBoxCoordinates(_ center: GeoPoint, _ radius: Double) -> [GeoPoint] {
        let latitudeDegrees = radius / metersPerDegreeLatitude
        let latitudeNorth = Swift.min(90, center.lat + latitudeDegrees)
        let latitudeSouth = Swift.max(-90, center.lat - latitudeDegrees)
        let longitudeDelta = Swift.max(
            metersToLongitudeDegrees(radius, latitudeNorth),
            metersToLongitudeDegrees(radius, latitudeSouth)
        )
        return [
            GeoPoint(lat: center.lat, lng: center.lng),
            GeoPoint(lat: center.lat, lng: wrapLongitude(center.lng - longitudeDelta)),
            GeoPoint(lat: center.lat, lng: wrapLongitude(center.lng + longitudeDelta)),
            GeoPoint(lat: latitudeNorth, lng: center.lng),
            GeoPoint(lat: latitudeNorth, lng: wrapLongitude(center.lng - longitudeDelta)),
            GeoPoint(lat: latitudeNorth, lng: wrapLongitude(center.lng + longitudeDelta)),
            GeoPoint(lat: latitudeSouth, lng: center.lng),
            GeoPoint(lat: latitudeSouth, lng: wrapLongitude(center.lng - longitudeDelta)),
            GeoPoint(lat: latitudeSouth, lng: wrapLongitude(center.lng + longitudeDelta)),
        ]
    }

    private static func wrapLongitude(_ longitude: Double) -> Double {
        if longitude <= 180 && longitude >= -180 { return longitude }
        let adjusted = longitude + 180
        if adjusted > 0 {
            return adjusted.truncatingRemainder(dividingBy: 360) - 180
        }
        return 180 - (-adjusted).truncatingRemainder(dividingBy: 360)
    }
}
