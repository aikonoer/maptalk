import MapKit
import SwiftUI
import UIKit

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
    @State private var showSettings = false
    @State private var isComposing = false
    @State private var previewThread: ChatThread?
    @State private var previewLatest: Message?
    @State private var previewLoading = false
    @State private var previewCluster: [ChatThread]?
    @State private var previewTask: Task<Void, Never>?
    /// Consumed by the next fix that arrives, so the map centres on you once at launch and again
    /// whenever you ask, but a late fix never yanks the camera away while you are panning.
    @State private var wantsToCenterOnUser = !Self.startsInDemo

    private var openThreadBinding: Binding<ThreadRoute?> {
        Binding(
            get: { openThreadId.map(ThreadRoute.init(id:)) },
            set: { openThreadId = $0?.id }
        )
    }
    /// Local / emulator / live Debug all open on Cebu (seeded neighbourhood). Release live
    /// centres on the user once location arrives.
    private static var startsInDemo: Bool {
        let mode = ProcessInfo.processInfo.environment["MAPTALK_MODE"]
            ?? (Bundle.main.object(forInfoDictionaryKey: "MapTalkMode") as? String)
        if mode == "local" || mode == "emulator" || mode == "live" { return true }
        #if DEBUG && !targetEnvironment(simulator)
        return true
        #else
        return false
        #endif
    }

    init(environment: AppEnvironment, author: Author) {
        self.environment = environment
        self.author = author
        _model = State(
            initialValue: MapModel(
                repository: environment.threadRepository,
                safety: environment.safetyRepository
            )
        )
    }

    private var location: LocationProvider { environment.locationProvider }

    var body: some View {
        let pendingDeepLink = DeepLinkBus.shared.pendingThreadId
        return ZStack {
            map
            crosshair
            overlay
            if previewThread != nil || previewCluster != nil {
                Color.black.opacity(0.4)
                    .ignoresSafeArea()
                    .onTapGesture { dismissPreview() }
                    .transition(.opacity)

                VStack(spacing: 0) {
                    Spacer(minLength: 0)
                    Group {
                        if let thread = previewThread {
                            BubblePreviewCard(
                                thread: thread,
                                latest: previewLatest,
                                isLoading: previewLoading,
                                onOpen: { openFromPreview(thread.id) }
                            )
                        } else if let cluster = previewCluster {
                            ClusterPreviewCard(threads: cluster) { thread in
                                openFromPreview(thread.id)
                            }
                        }
                    }
                    .padding(.horizontal, 14)
                    .padding(.bottom, 14)
                }
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.spring(duration: 0.34, bounce: 0.12), value: previewThread?.id)
        .animation(.spring(duration: 0.34, bounce: 0.12), value: previewCluster?.count)
        .sheet(item: openThreadBinding) { route in
            ThreadScreen(
                environment: environment,
                author: author,
                threadId: route.id
            )
            .presentationDetents([.fraction(0.94), .large])
            .presentationDragIndicator(.hidden)
            .presentationCornerRadius(28)
            .presentationBackground(Theme.base)
            .presentationContentInteraction(.scrolls)
        }
        .sheet(isPresented: $showSettings) {
            NavigationStack {
                SettingsScreen(environment: environment, author: author)
            }
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
            .presentationCornerRadius(28)
            .presentationBackground(Theme.base)
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
            .presentationCornerRadius(24)
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
            model.start()
            if Self.startsInDemo {
                // Don't wait for MapKit's first camera callback — pin the query on Cebu now.
                let center = GeoPoint(lat: 10.3157, lng: 123.8854)
                model.cameraChanged(center: center, radiusKm: 3)
            } else if location.isAuthorized {
                location.locateMe()
            }
            centerOnUserIfWanted()
            openPendingDeepLink()
        }
        .onDisappear { model.stop() }
        .onChange(of: location.lastLocation) { _, _ in centerOnUserIfWanted() }
        .onChange(of: pendingDeepLink) { _, _ in
            openPendingDeepLink()
        }
    }

    private func openPendingDeepLink() {
        if let pushId = PushTokenBridge.shared.pendingThreadId {
            PushTokenBridge.shared.pendingThreadId = nil
            openThreadId = pushId
            return
        }
        if let id = DeepLinkBus.shared.consume() {
            openThreadId = id
        }
    }

    private var map: some View {
        Map(position: $camera) {
            UserAnnotation()
            ForEach(model.bubbles) { bubble in
                Annotation("", coordinate: bubble.position.coordinate, anchor: .bottom) {
                    BubbleMarker(bubble: bubble)
                        .modifier(
                            BubblePressModifier(
                                onTap: { open(bubble) },
                                onLongPress: {
                                    if let thread = bubble.single {
                                        presentPreview(thread)
                                    } else {
                                        presentClusterPreview(bubble.items)
                                    }
                                }
                            )
                        )
                }
            }
        }
        .mapStyle(
            .standard(
                elevation: .flat,
                emphasis: .muted,
                pointsOfInterest: .excludingAll
            )
        )
        .environment(\.colorScheme, .dark)
        .background(Theme.base)
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
            HStack(spacing: 10) {
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

                Button {
                    showSettings = true
                } label: {
                    Image(systemName: "gearshape.fill")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(Theme.text)
                        .frame(width: 36, height: 36)
                        .background(Theme.surface.opacity(0.92), in: Circle())
                        .overlay { Circle().strokeBorder(Theme.hairline, lineWidth: 1) }
                }
                .accessibilityLabel("Settings")
            }
            .shadow(color: .black.opacity(0.35), radius: 10, y: 3)
            .animation(.spring(duration: 0.3), value: statusText)
            .padding(.top, 10)

            if showEmptyNearbyCTA {
                VStack(spacing: 10) {
                    Text("Nothing pinned near here")
                        .font(.cardTitle)
                        .foregroundStyle(Theme.text)
                    Text("Be the first — drop a chat at the crosshair.")
                        .font(.subheadline)
                        .foregroundStyle(Theme.subtle)
                        .multilineTextAlignment(.center)
                    Button {
                        isComposing = true
                    } label: {
                        Text("Start a chat here")
                            .font(.control)
                            .foregroundStyle(.white)
                            .padding(.horizontal, 18)
                            .padding(.vertical, 12)
                            .background(Theme.accent, in: Capsule())
                    }
                    .buttonStyle(.pressable)
                }
                .padding(20)
                .frame(maxWidth: 300)
                .background(Theme.surface.opacity(0.94), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .strokeBorder(Theme.hairline, lineWidth: 1)
                }
                .shadow(color: .black.opacity(0.4), radius: 16, y: 8)
                .padding(.top, 28)
                .transition(.opacity.combined(with: .scale(scale: 0.96)))
            }

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
        .animation(.spring(duration: 0.35), value: showEmptyNearbyCTA)
    }

    private var showEmptyNearbyCTA: Bool {
        !model.isLoading && !model.isGlobalView && model.bubbles.isEmpty
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

    private func presentPreview(_ thread: ChatThread) {
        previewCluster = nil
        previewLatest = nil
        previewLoading = true
        previewTask?.cancel()
        withAnimation(.spring(duration: 0.34, bounce: 0.12)) {
            previewThread = thread
        }
        previewTask = Task {
            let messages = await model.peekMessages(threadId: thread.id)
            guard !Task.isCancelled else { return }
            previewLatest = messages.last
            previewLoading = false
        }
    }

    private func presentClusterPreview(_ threads: [ChatThread]) {
        previewTask?.cancel()
        previewTask = nil
        previewLatest = nil
        previewLoading = false
        previewThread = nil
        withAnimation(.spring(duration: 0.34, bounce: 0.12)) {
            previewCluster = threads
        }
    }

    /// Clear the peek first so it doesn't float over the opening thread sheet.
    private func openFromPreview(_ threadId: String) {
        var instant = Transaction()
        instant.disablesAnimations = true
        withTransaction(instant) {
            previewTask?.cancel()
            previewTask = nil
            previewThread = nil
            previewCluster = nil
            previewLatest = nil
            previewLoading = false
        }
        openThreadId = threadId
    }

    private func dismissPreview() {
        previewTask?.cancel()
        previewTask = nil
        withAnimation(.easeOut(duration: 0.2)) {
            previewThread = nil
            previewCluster = nil
        }
        previewLatest = nil
        previewLoading = false
    }
}

/// MapKit eats SwiftUI `LongPressGesture` on annotations. A zero-distance drag that
/// times out into a long-press still receives touches on the bubble view itself.
private struct BubblePressModifier: ViewModifier {
    let onTap: () -> Void
    let onLongPress: () -> Void

    @State private var holdTask: Task<Void, Never>?
    @State private var didFireLongPress = false

    func body(content: Content) -> some View {
        content
            .contentShape(Rectangle())
            .scaleEffect(holdTask != nil && !didFireLongPress ? 0.96 : 1)
            .animation(.easeOut(duration: 0.12), value: holdTask != nil)
            .gesture(
                DragGesture(minimumDistance: 0, coordinateSpace: .local)
                    .onChanged { value in
                        if holdTask == nil && !didFireLongPress {
                            holdTask = Task { @MainActor in
                                try? await Task.sleep(for: .milliseconds(380))
                                guard !Task.isCancelled else { return }
                                didFireLongPress = true
                                holdTask = nil
                                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                                onLongPress()
                            }
                        }
                        let moved = hypot(value.translation.width, value.translation.height) > 12
                        if moved {
                            holdTask?.cancel()
                            holdTask = nil
                        }
                    }
                    .onEnded { value in
                        let moved = hypot(value.translation.width, value.translation.height) > 12
                        let wasHolding = holdTask != nil
                        holdTask?.cancel()
                        holdTask = nil
                        if !didFireLongPress && !moved && wasHolding {
                            onTap()
                        }
                        didFireLongPress = false
                    }
            )
    }
}

/// A marker drawn as a chat bubble: the thread's title when it stands alone, a count when
/// several threads share a geohash cell at this zoom.
private struct BubbleMarker: View {
    let bubble: GeoCluster<ChatThread>
    @State private var pulse = false

    var body: some View {
        if let thread = bubble.single {
            let live = LiveNow.isLive(thread.lastMessageAt)
            HStack(spacing: 7) {
                if live {
                    Circle()
                        .fill(Theme.accent)
                        .frame(width: 8, height: 8)
                        .scaleEffect(pulse ? 1.35 : 1)
                        .opacity(pulse ? 0.55 : 1)
                        .animation(
                            .easeInOut(duration: 0.9).repeatForever(autoreverses: true),
                            value: pulse
                        )
                        .onAppear { pulse = true }
                }

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

                Text(live ? "Live" : relativeTime(thread.lastMessageAt))
                    .font(.meta)
                    .foregroundStyle(live ? Theme.accent : Theme.faint)
            }
            .padding(.leading, 10)
            .padding(.trailing, 9)
            .padding(.vertical, 8)
            .frame(maxWidth: 200)
            .background(Theme.surface, in: Theme.bubble(radius: 14))
            .overlay {
                Theme.bubble(radius: 14).strokeBorder(
                    live ? Theme.accent.opacity(0.55) : Theme.hairline,
                    lineWidth: 1
                )
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

private struct ThreadRoute: Identifiable {
    let id: String
}
