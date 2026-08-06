import Foundation
import MapKit

/// A place the user can jump the map to.
struct PlaceSearchHit: Identifiable, Sendable {
    let id: String
    let title: String
    let subtitle: String?
    let latitude: Double
    let longitude: Double
    let latitudeDelta: Double
    let longitudeDelta: Double

    var coordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }

    var span: MKCoordinateSpan {
        MKCoordinateSpan(latitudeDelta: latitudeDelta, longitudeDelta: longitudeDelta)
    }

    init(item: MKMapItem) {
        let placemark = item.placemark
        let coord = placemark.coordinate
        id = [
            item.name,
            placemark.locality,
            String(format: "%.5f,%.5f", coord.latitude, coord.longitude),
        ]
        .compactMap { $0 }
        .joined(separator: "|")

        let resolvedTitle = item.name
            ?? placemark.name
            ?? placemark.locality
            ?? "Place"
        title = resolvedTitle

        let parts = [
            placemark.subLocality,
            placemark.locality,
            placemark.administrativeArea,
        ]
        .compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }
        .filter { !$0.isEmpty && $0.caseInsensitiveCompare(resolvedTitle) != .orderedSame }
        var seen = Set<String>()
        subtitle = parts.filter { seen.insert($0.lowercased()).inserted }.joined(separator: ", ")
            .nilIfEmpty

        latitude = coord.latitude
        longitude = coord.longitude

        if let region = placemark.region as? CLCircularRegion {
            let delta = max(0.012, (region.radius * 2) / 111_000)
            latitudeDelta = delta
            longitudeDelta = delta
        } else if placemark.locality != nil, placemark.thoroughfare == nil {
            latitudeDelta = 0.08
            longitudeDelta = 0.08
        } else {
            latitudeDelta = 0.035
            longitudeDelta = 0.035
        }
    }
}

enum PlaceSearch {
    /// Natural-language place lookup, biased toward the current map centre when provided.
    static func search(_ query: String, near: GeoPoint?) async -> [PlaceSearchHit] {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.count >= 2 else { return [] }

        let request = MKLocalSearch.Request()
        request.naturalLanguageQuery = trimmed
        request.resultTypes = [.address, .pointOfInterest]
        if let near {
            request.region = MKCoordinateRegion(
                center: near.coordinate,
                span: MKCoordinateSpan(latitudeDelta: 1.2, longitudeDelta: 1.2)
            )
        }

        do {
            let response = try await MKLocalSearch(request: request).start()
            return response.mapItems.prefix(8).map { PlaceSearchHit(item: $0) }
        } catch {
            return []
        }
    }
}

private extension String {
    var nilIfEmpty: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}
