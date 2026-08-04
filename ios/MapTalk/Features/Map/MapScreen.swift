import MapKit
import SwiftUI

private let worldRegion = MKCoordinateRegion(
    center: CLLocationCoordinate2D(latitude: 20, longitude: 10),
    span: MKCoordinateSpan(latitudeDelta: 120, longitudeDelta: 120)
)
private let nearbySpan = MKCoordinateSpan(latitudeDelta: 0.03, longitudeDelta: 0.03)

/// Cebu City — where the mock-data seed clusters when you run against local emulators.
private let cebuRegion = MKCoordinateRegion(
    center: CLLocationCoordinate2D(latitude: 10.3157, longitude: 123.8854),
    span: nearbySpan
)

struct MapScreen: View {

    private let environment: AppEnvironment
    private let author: Author

    @State private var model: MapModel
    @State private var camera: MapCameraPosition = Self.startsInDemo
        ? .region(cebuRegion)
        : .region(worldRegion)
    @State private var openThreadId: String?
    @State private var isComposing = false
    /// Consumed by the next fix that arrives, so the map centres on you once at launch and again
    /// whenever you ask, but a late fix never yanks the camera away while you are panning.
    @State private var wantsToCenterOnUser = !Self.startsInDemo

    /// Local demo and emulator runs open on Cebu (where the seed lives) instead of the world.
    private static var startsInDemo: Bool {
        let mode = ProcessInfo.processInfo.environment["MAPTALK_MODE"]
        if mode == "local" || mode == "emulator" { return true }
        #if DEBUG && !targetEnvironment(simulator)
        return true
        #else
        return false
        #endif
    }

    init(environment: AppEnvironment, author: Author) {
        self.environment = environment
        self.author = author
        _model = State(initialValue: MapModel(repository: environment.threadRepository))
    }

    private var location: LocationProvider { environment.locationProvider }

    var body: some View {
        NavigationStack {
            ZStack {
                map
                crosshair
                overlay
            }
            .navigationBarHidden(true)
            .navigationDestination(item: $openThreadId) { threadId in
                ThreadScreen(
                    environment: environment,
                    author: author,
                    threadId: threadId
                )
            }
            .sheet(isPresented: $isComposing) {
                NewThreadSheet(position: model.visibleCenter) { title, kind in
                    isComposing = false
                    openThreadId = model.createThread(
                        title: title,
                        kind: kind,
                        position: model.visibleCenter,
                        author: author
                    )
                }
                .presentationDetents([.height(380)])
                .presentationDragIndicator(.visible)
                .presentationBackground(Theme.surface)
            }
            .alert(
                "Something went wrong",
                isPresented: Binding(
                    get: { model.errorMessage != nil },
                    set: { if !$0 { model.errorMessage = nil } }
                )
            ) {
                Button("OK") { model.errorMessage = nil }
            } message: {
                Text(model.errorMessage ?? "")
            }
            .onAppear {
                if Self.startsInDemo {
                    // Don't wait for MapKit's first camera callback — pin the query on Cebu now.
                    let center = GeoPoint(lat: 10.3157, lng: 123.8854)
                    model.cameraChanged(center: center, radiusKm: 3)
                } else if location.isAuthorized {
                    location.locateMe()
                }
                centerOnUserIfWanted()
            }
            .onChange(of: location.lastLocation) { _, _ in centerOnUserIfWanted() }
        }
    }

    private var map: some View {
        Map(position: $camera) {
            UserAnnotation()
            ForEach(model.bubbles) { bubble in
                Annotation("", coordinate: bubble.position.coordinate, anchor: .bottom) {
                    BubbleMarker(bubble: bubble)
                        .onTapGesture { open(bubble) }
                }
            }
        }
        .mapStyle(.standard(elevation: .flat, pointsOfInterest: .excludingAll))
        .mapControls { MapCompass() }
        .onMapCameraChange(frequency: .onEnd) { context in
            let region = context.region
            let center = GeoPoint(region.center)
            let corner = GeoPoint(
                lat: region.center.latitude + region.span.latitudeDelta / 2,
                lng: region.center.longitude + region.span.longitudeDelta / 2
            )
            model.cameraChanged(center: center, radiusKm: center.distance(to: corner) / 1_000)
        }
    }

    /// Marks the spot a new chat would be pinned to.
    private var crosshair: some View {
        Circle()
            .strokeBorder(Theme.accent, lineWidth: 2)
            .frame(width: 22, height: 22)
            .background(Circle().fill(Theme.accent.opacity(0.15)))
            .overlay { Circle().fill(Theme.accent).frame(width: 5, height: 5) }
            .shadow(color: .black.opacity(0.4), radius: 6, y: 2)
            .allowsHitTesting(false)
    }

    private var overlay: some View {
        VStack(spacing: 0) {
            HStack(spacing: 7) {
                if model.isLoading {
                    ProgressView().controlSize(.mini).tint(Theme.subtle)
                } else {
                    Image(systemName: model.isGlobalView ? "globe" : "dot.radiowaves.left.and.right")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(Theme.accent)
                }
                Text(statusText)
                    .font(.control)
                    .foregroundStyle(Theme.text)
                    .contentTransition(.numericText())
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 9)
            .background(Theme.surface.opacity(0.92), in: Capsule())
            .overlay { Capsule().strokeBorder(Theme.hairline, lineWidth: 1) }
            .shadow(color: .black.opacity(0.35), radius: 10, y: 3)
            .animation(.spring(duration: 0.3), value: statusText)
            .padding(.top, 10)

            Spacer()

            HStack(spacing: 12) {
                Button {
                    wantsToCenterOnUser = true
                    location.locateMe()
                    centerOnUserIfWanted()
                } label: {
                    Image(systemName: "location.fill")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(Theme.text)
                        .frame(width: 46, height: 46)
                        .background(Theme.surface.opacity(0.92), in: Circle())
                        .overlay { Circle().strokeBorder(Theme.hairline, lineWidth: 1) }
                }
                .accessibilityLabel("Centre on my location")

                Spacer()

                Button {
                    isComposing = true
                } label: {
                    Label("Start a chat here", systemImage: "plus")
                        .font(.control)
                        .foregroundStyle(.white)
                        .padding(.horizontal, 20)
                        .padding(.vertical, 15)
                        .background(Theme.accent, in: Capsule())
                }
                .buttonStyle(.pressable)
            }
            .shadow(color: .black.opacity(0.35), radius: 12, y: 4)
            .padding(.horizontal, 16)
            .padding(.bottom, 14)
        }
    }

    private var statusText: String {
        if model.isLoading { return "Looking around\u{2026}" }
        if model.isGlobalView { return "Busiest chats worldwide" }
        let count = model.bubbles.reduce(0) { $0 + $1.size }
        switch count {
        case 0: return "No chats here yet"
        case 1: return "1 chat nearby"
        default: return "\(count) chats nearby"
        }
    }

    private func centerOnUserIfWanted() {
        guard wantsToCenterOnUser, let point = location.lastLocation else { return }
        wantsToCenterOnUser = false
        withAnimation {
            camera = .region(MKCoordinateRegion(center: point.coordinate, span: nearbySpan))
        }
    }

    private func open(_ bubble: GeoCluster<ChatThread>) {
        if let thread = bubble.single {
            openThreadId = thread.id
            return
        }
        // Tapping a cluster drills into it.
        withAnimation {
            camera = .region(
                MKCoordinateRegion(
                    center: bubble.position.coordinate,
                    span: MKCoordinateSpan(
                        latitudeDelta: max(0.005, model.visibleRadiusKm / 111 / 4),
                        longitudeDelta: max(0.005, model.visibleRadiusKm / 111 / 4)
                    )
                )
            )
        }
    }
}

/// A marker drawn as a chat bubble: the thread's title when it stands alone, a count when
/// several threads share a geohash cell at this zoom.
private struct BubbleMarker: View {
    let bubble: GeoCluster<ChatThread>

    var body: some View {
        if let thread = bubble.single {
            HStack(spacing: 7) {
                Text(thread.kind.glyph)
                    .font(.system(size: 13))

                Text(thread.title)
                    .font(.markerTitle)
                    .foregroundStyle(Theme.text)
                    .lineLimit(1)

                if thread.messageCount > 0 {
                    Text("\(thread.messageCount)")
                        .font(.meta)
                        .foregroundStyle(thread.kind.tint)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(thread.kind.tint.opacity(0.16), in: Capsule())
                }
            }
            .padding(.leading, 10)
            .padding(.trailing, 9)
            .padding(.vertical, 8)
            .frame(maxWidth: 200)
            .background(Theme.surface, in: Theme.bubble(radius: 14))
            .overlay {
                Theme.bubble(radius: 14).strokeBorder(Theme.hairline, lineWidth: 1)
            }
            .shadow(color: .black.opacity(0.45), radius: 8, y: 3)
        } else {
            HStack(spacing: 5) {
                Image(systemName: "bubble.left.and.bubble.right.fill")
                    .font(.system(size: 11, weight: .bold))
                Text("\(bubble.size)")
                    .font(.markerTitle)
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(Theme.accent, in: Theme.bubble(radius: 14))
            .overlay {
                Theme.bubble(radius: 14).strokeBorder(.white.opacity(0.25), lineWidth: 1)
            }
            .shadow(color: .black.opacity(0.45), radius: 8, y: 3)
        }
    }
}
