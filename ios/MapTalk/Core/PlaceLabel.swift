import CoreLocation
import Foundation
import SwiftUI

/// Short area label for a map pin — neighborhood / city, not a full street address.
/// Resolved on-device via Apple’s geocoder; cached so peek/open doesn’t spam the service.
enum PlaceLabel {

    /// ~100 m bucket so nearby opens share one lookup.
    static func cacheKey(for point: GeoPoint) -> String {
        String(format: "%.3f,%.3f", point.lat, point.lng)
    }

    static func resolve(_ point: GeoPoint) async -> String? {
        let key = cacheKey(for: point)
        if let cached = await PlaceLabelCache.shared.value(for: key) {
            return cached
        }

        let location = CLLocation(latitude: point.lat, longitude: point.lng)
        do {
            let marks = try await CLGeocoder().reverseGeocodeLocation(location)
            guard let label = marks.first.flatMap(format) else { return nil }
            await PlaceLabelCache.shared.store(label, for: key)
            return label
        } catch {
            return nil
        }
    }

    /// Prefer area names over house numbers / precise street addresses.
    static func format(_ placemark: CLPlacemark) -> String? {
        if let sub = cleaned(placemark.subLocality) { return sub }
        if let city = cleaned(placemark.locality) {
            if let area = cleaned(placemark.administrativeArea), area != city {
                return "\(city), \(area)"
            }
            return city
        }
        // POI / landmark name only when it doesn’t look like "123 Main St".
        if let name = cleaned(placemark.name), !looksLikeStreetAddress(name) {
            return name
        }
        if let street = cleaned(placemark.thoroughfare) { return street }
        return cleaned(placemark.administrativeArea)
    }

    private static func cleaned(_ value: String?) -> String? {
        guard let value else { return nil }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    private static func looksLikeStreetAddress(_ name: String) -> Bool {
        name.split(whereSeparator: \.isWhitespace).contains { token in
            token.contains(where: \.isNumber)
        }
    }
}

private actor PlaceLabelCache {
    static let shared = PlaceLabelCache()
    private var values: [String: String] = [:]

    func value(for key: String) -> String? { values[key] }

    func store(_ label: String, for key: String) {
        values[key] = label
    }
}

/// Location glyph + resolved area name (or a quiet loading / fallback state).
/// Optional `onTap` makes the line open that place on the map (label passed when known).
struct PlaceLabelLine: View {
    let point: GeoPoint
    var trailing: String? = nil
    var onTap: ((String?) -> Void)? = nil

    @State private var label: String?
    @State private var didFail = false

    var body: some View {
        Group {
            if let onTap {
                Button {
                    onTap(label)
                } label: {
                    labelContent(interactive: true)
                }
                .buttonStyle(.plain)
                .accessibilityHint("Shows this place on the map")
            } else {
                labelContent(interactive: false)
            }
        }
        .task(id: PlaceLabel.cacheKey(for: point)) {
            label = nil
            didFail = false
            let resolved = await PlaceLabel.resolve(point)
            label = resolved
            didFail = resolved == nil
        }
    }

    private func labelContent(interactive: Bool) -> some View {
        HStack(spacing: 5) {
            Image(systemName: "location.fill")
                .font(.system(size: 10, weight: .semibold))
                .foregroundStyle(interactive ? Theme.accent : Theme.subtle)
            Text(displayText)
                .lineLimit(1)
                .foregroundStyle(interactive ? Theme.accent : Theme.subtle)
            if let trailing {
                Text("\u{00b7} \(trailing)")
                    .foregroundStyle(Theme.faint)
                    .lineLimit(1)
            }
        }
        .font(.meta)
        .contentShape(Rectangle())
    }

    private var displayText: String {
        if let label { return label }
        if didFail { return "Somewhere nearby" }
        return "Finding area\u{2026}"
    }
}
