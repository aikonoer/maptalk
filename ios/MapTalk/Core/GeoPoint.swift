import CoreLocation
import Foundation

/// A plain latitude/longitude pair, deliberately free of any MapKit or Firestore type so the
/// geo rules can be unit tested and stay comparable with the Android implementation.
struct GeoPoint: Equatable, Sendable {
    let lat: Double
    let lng: Double

    init(lat: Double, lng: Double) {
        self.lat = lat
        self.lng = lng
    }

    init(_ coordinate: CLLocationCoordinate2D) {
        self.lat = coordinate.latitude
        self.lng = coordinate.longitude
    }

    var coordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: lat, longitude: lng)
    }

    /// Great-circle distance in metres. Android filters geohash false positives with the same
    /// formula, so both apps draw exactly the same set of bubbles.
    func distance(to other: GeoPoint) -> Double {
        let earthRadiusMeters = 6_371_008.8
        let dLat = (other.lat - lat) * .pi / 180
        let dLng = (other.lng - lng) * .pi / 180
        let a = sin(dLat / 2) * sin(dLat / 2)
            + cos(lat * .pi / 180) * cos(other.lat * .pi / 180)
            * sin(dLng / 2) * sin(dLng / 2)
        return 2 * earthRadiusMeters * asin(min(1, a.squareRoot()))
    }

    /// Move south by `meters` (pin stays put while the camera centre shifts under a bottom sheet).
    func shiftedSouth(byMeters meters: Double) -> GeoPoint {
        let degrees = meters / 111_320.0
        return GeoPoint(lat: lat - degrees, lng: lng)
    }
}
