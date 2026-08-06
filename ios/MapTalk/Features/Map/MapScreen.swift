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
    @State private var isPlacingPin = false
    @State private var previewThread: ChatThread?
    @State private var previewLatest: Message?
    @State private var previewLoading = false
    @State private var previewCluster: [ChatThread]?
    @State private var previewTask: Task<Void, Never>?
    /// Consumed by the next fix that arrives, so the map centres on you once at launch and again
    /// whenever you ask, but a late fix never yanks the camera away while you are panning.
    @State private var wantsToCenterOnUser = !Self.startsInDemo
    @State private var nearbyCountFlash = false
    @State private var lastNearbyCount = -1
    @State private var kindFilterExpanded = false
    @State private var profilePhotoURL: String?
    @State private var profileTask: Task<Void, Never>?

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
            if showCrosshair {
                crosshair
                    .transition(.opacity.combined(with: .scale(scale: 0.7)))
            }
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
        .animation(.spring(duration: 0.32, bounce: 0.2), value: showCrosshair)
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
                AccountScreen(environment: environment, author: author)
            }
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
            .presentationCornerRadius(28)
            .presentationBackground(Theme.base)
        }
        .sheet(isPresented: $isComposing) {
            NewThreadSheet(position: model.visibleCenter) { title, body, kind in
                isComposing = false
                isPlacingPin = false
                openThreadId = model.createThread(
                    title: title,
                    kind: kind,
                    position: model.visibleCenter,
                    author: author,
                    openingText: body
                )
            }
            .presentationDetents([.height(520), .large])
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
            startProfileListener()
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
        .onDisappear {
            model.stop()
            profileTask?.cancel()
            profileTask = nil
        }
        .onChange(of: location.lastLocation) { _, _ in centerOnUserIfWanted() }
        .onChange(of: pendingDeepLink) { _, _ in
            openPendingDeepLink()
        }
        .onChange(of: nearbyChatCount) { _, count in
            guard !model.isGlobalView, !model.isLoading else {
                lastNearbyCount = count
                return
            }
            if lastNearbyCount >= 0, count != lastNearbyCount {
                flashNearbyCount()
            }
            lastNearbyCount = count
        }
    }

    private var nearbyChatCount: Int {
        model.bubbles.reduce(0) { $0 + $1.size }
    }

    private func flashNearbyCount() {
        withAnimation(.easeOut(duration: 0.2)) { nearbyCountFlash = true }
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(500))
            withAnimation(.easeOut(duration: 0.35)) { nearbyCountFlash = false }
        }
    }

    private func startProfileListener() {
        guard profileTask == nil, let uid = environment.authRepository.currentUid else { return }
        profileTask = Task {
            for await profile in environment.authRepository.profile(uid: uid) {
                profilePhotoURL = profile.photoURL
            }
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
                Annotation("", coordinate: bubble.position.coordinate, anchor: .bottomLeading) {
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
                        NearbyStatusIcon(isGlobal: model.isGlobalView, isActive: !model.isGlobalView && !model.bubbles.isEmpty)
                    }
                    Text(statusText)
                        .font(.control)
                        .foregroundStyle(Theme.text)
                        .contentTransition(.numericText())
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 9)
                .background(Theme.surface.opacity(0.92), in: Capsule())
                .overlay {
                    Capsule().strokeBorder(
                        nearbyCountFlash ? Theme.accent.opacity(0.55) : Theme.hairline,
                        lineWidth: 1
                    )
                }

                Button {
                    showSettings = true
                } label: {
                    MapAccountAvatar(
                        name: author.displayName,
                        uid: author.uid,
                        photoURL: profilePhotoURL
                    )
                }
                .buttonStyle(.pressable)
                .accessibilityLabel("Account")
            }
            .shadow(color: .black.opacity(0.35), radius: 10, y: 3)
            .animation(.spring(duration: 0.3), value: statusText)
            .padding(.top, 10)

            kindFilterRow
                .padding(.top, 10)

            if showEmptyNearbyCTA {
                VStack(spacing: 10) {
                    Text("Nothing pinned near here")
                        .font(.cardTitle)
                        .foregroundStyle(Theme.text)
                    Text("Be the first — pick a spot and start a chat.")
                        .font(.subheadline)
                        .foregroundStyle(Theme.subtle)
                        .multilineTextAlignment(.center)
                    Button {
                        beginPlacingPin()
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
            } else if showFilterEmptyHint {
                VStack(spacing: 10) {
                    Text("Nothing in this filter")
                        .font(.cardTitle)
                        .foregroundStyle(Theme.text)
                    Text("Other chats are nearby — clear the filter to see them.")
                        .font(.subheadline)
                        .foregroundStyle(Theme.subtle)
                        .multilineTextAlignment(.center)
                    Button {
                        withAnimation(.spring(duration: 0.25)) { model.clearKindFilter() }
                    } label: {
                        Text("Show all kinds")
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

                if isPlacingPin, !isComposing {
                    Button {
                        withAnimation(.spring(duration: 0.32, bounce: 0.2)) {
                            isPlacingPin = false
                        }
                    } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundStyle(Theme.text)
                            .frame(width: 46, height: 46)
                            .background(Theme.surface.opacity(0.92), in: Circle())
                            .overlay { Circle().strokeBorder(Theme.hairline, lineWidth: 1) }
                    }
                    .accessibilityLabel("Cancel placing chat")
                    .transition(.scale.combined(with: .opacity))
                }

                Button {
                    if isPlacingPin {
                        isComposing = true
                    } else {
                        beginPlacingPin()
                    }
                } label: {
                    Label(
                        isPlacingPin ? "Pin chat here" : "Start a chat here",
                        systemImage: isPlacingPin ? "mappin.and.ellipse" : "plus"
                    )
                    .font(.control)
                    .foregroundStyle(.white)
                    .padding(.horizontal, 20)
                    .padding(.vertical, 15)
                    .background(Theme.accent, in: Capsule())
                }
                .buttonStyle(.pressable)
                .disabled(isComposing)
            }
            .shadow(color: .black.opacity(0.35), radius: 12, y: 4)
            .padding(.horizontal, 16)
            .padding(.bottom, 14)
            .animation(.spring(duration: 0.32, bounce: 0.2), value: isPlacingPin)
        }
        .animation(.spring(duration: 0.35), value: showEmptyNearbyCTA)
        .animation(.spring(duration: 0.35), value: showFilterEmptyHint)
        .animation(.spring(duration: 0.25), value: model.kindFilter)
    }

    private var showCrosshair: Bool {
        isPlacingPin || isComposing
    }

    private func beginPlacingPin() {
        withAnimation(.spring(duration: 0.32, bounce: 0.2)) {
            isPlacingPin = true
        }
    }

    private var kindFilterRow: some View {
        HStack {
            KindFilterStack(
                expanded: $kindFilterExpanded,
                kindFilter: model.kindFilter,
                onToggle: { kind in
                    withAnimation(.spring(duration: 0.25)) { model.toggleKindFilter(kind) }
                },
                onClear: {
                    withAnimation(.spring(duration: 0.25)) { model.clearKindFilter() }
                }
            )
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16)
    }

    private var showEmptyNearbyCTA: Bool {
        !isPlacingPin && !isComposing && !model.isLoading && !model.isGlobalView
            && model.bubbles.isEmpty && !model.isFilterHidingAll
    }

    private var showFilterEmptyHint: Bool {
        !model.isLoading && model.bubbles.isEmpty && model.isFilterHidingAll
    }

    private var statusText: String {
        if model.isLoading { return "Looking around\u{2026}" }
        if model.isFilterHidingAll {
            return model.isGlobalView ? "No matches worldwide" : "No matches nearby"
        }
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
        previewTask?.cancel()
        previewCluster = nil
        previewLatest = nil
        previewLoading = true
        // Load the latest line first so the "Latest" card is already in the
        // peek when it slides — it used to pop in after, looking stuck.
        previewTask = Task {
            let messages = await model.peekMessages(threadId: thread.id)
            guard !Task.isCancelled else { return }
            previewLatest = messages.last
            previewLoading = false
            withAnimation(.spring(duration: 0.34, bounce: 0.12)) {
                previewThread = thread
            }
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

/// MapKit eats SwiftUI `LongPressGesture` on annotations, and an exclusive
/// `DragGesture(minimumDistance: 0)` steals a finger during pinch-zoom so the
/// map can get stuck. UIKit recognizers with simultaneous recognition + giving
/// up when a second finger lands keep pan/zoom working.
private struct BubblePressModifier: ViewModifier {
    let onTap: () -> Void
    let onLongPress: () -> Void

    @State private var pressed = false

    func body(content: Content) -> some View {
        content
            .contentShape(Rectangle())
            .scaleEffect(pressed ? 0.96 : 1, anchor: .bottomLeading)
            .animation(.easeOut(duration: 0.12), value: pressed)
            .overlay {
                BubbleGestureCatcher(
                    onTap: onTap,
                    onLongPress: {
                        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                        onLongPress()
                    },
                    onPressedChanged: { pressed = $0 }
                )
            }
    }
}

/// Clear hit target over a bubble. Yields to the map as soon as a second finger appears.
private struct BubbleGestureCatcher: UIViewRepresentable {
    var onTap: () -> Void
    var onLongPress: () -> Void
    var onPressedChanged: (Bool) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    func makeUIView(context: Context) -> BubbleGestureView {
        let view = BubbleGestureView()
        view.coordinator = context.coordinator
        context.coordinator.onTap = onTap
        context.coordinator.onLongPress = onLongPress
        context.coordinator.onPressedChanged = onPressedChanged
        return view
    }

    func updateUIView(_ uiView: BubbleGestureView, context: Context) {
        context.coordinator.onTap = onTap
        context.coordinator.onLongPress = onLongPress
        context.coordinator.onPressedChanged = onPressedChanged
        uiView.coordinator = context.coordinator
    }

    final class Coordinator {
        var onTap: (() -> Void)?
        var onLongPress: (() -> Void)?
        var onPressedChanged: ((Bool) -> Void)?
    }
}

private final class BubbleGestureView: UIView, UIGestureRecognizerDelegate {
    weak var coordinator: BubbleGestureCatcher.Coordinator?

    private let tap = UITapGestureRecognizer()
    private let longPress = UILongPressGestureRecognizer()
    private let press = UILongPressGestureRecognizer()

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        isAccessibilityElement = false
        // One finger only on this view — the other finger should land on the map.
        isMultipleTouchEnabled = false

        longPress.minimumPressDuration = 0.38
        longPress.allowableMovement = 14
        longPress.delegate = self
        longPress.cancelsTouchesInView = false
        longPress.addTarget(self, action: #selector(handleLongPress))

        // Short recognizer only for press scale feedback; never cancels map touches.
        press.minimumPressDuration = 0.01
        press.allowableMovement = 14
        press.delegate = self
        press.cancelsTouchesInView = false
        press.addTarget(self, action: #selector(handlePressFeedback))

        tap.delegate = self
        tap.cancelsTouchesInView = false
        tap.require(toFail: longPress)
        tap.addTarget(self, action: #selector(handleTap))

        addGestureRecognizer(press)
        addGestureRecognizer(longPress)
        addGestureRecognizer(tap)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    /// While a pinch/pan already has multiple fingers, don't claim the hit — let MapKit win.
    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        if activeTouchCount(in: event) > 1 { return nil }
        return super.hitTest(point, with: event)
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        super.touchesBegan(touches, with: event)
        // Second finger appeared elsewhere (or here): drop our claim so the map can zoom.
        if activeTouchCount(in: event) > 1 {
            cancelBubbleGestures()
            coordinator?.onPressedChanged?(false)
        }
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        super.touchesCancelled(touches, with: event)
        coordinator?.onPressedChanged?(false)
    }

    private func activeTouchCount(in event: UIEvent?) -> Int {
        event?.allTouches?.filter {
            $0.phase == .began || $0.phase == .moved || $0.phase == .stationary
        }.count ?? 0
    }

    private func cancelBubbleGestures() {
        for recognizer in [tap, longPress, press] as [UIGestureRecognizer] {
            recognizer.isEnabled = false
            recognizer.isEnabled = true
        }
    }

    @objc private func handleTap() {
        coordinator?.onPressedChanged?(false)
        coordinator?.onTap?()
    }

    @objc private func handleLongPress(_ gesture: UILongPressGestureRecognizer) {
        switch gesture.state {
        case .began:
            coordinator?.onPressedChanged?(false)
            coordinator?.onLongPress?()
        case .cancelled, .failed, .ended:
            coordinator?.onPressedChanged?(false)
        default:
            break
        }
    }

    @objc private func handlePressFeedback(_ gesture: UILongPressGestureRecognizer) {
        switch gesture.state {
        case .began:
            coordinator?.onPressedChanged?(true)
        case .ended, .cancelled, .failed:
            coordinator?.onPressedChanged?(false)
        default:
            break
        }
    }

    func gestureRecognizer(
        _ gestureRecognizer: UIGestureRecognizer,
        shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
    ) -> Bool {
        true
    }
}

/// Soft radar breath on the nearby icon so the status pill feels connected to the map.
private struct NearbyStatusIcon: View {
    let isGlobal: Bool
    let isActive: Bool
    @State private var pulse = false

    var body: some View {
        Image(systemName: isGlobal ? "globe" : "dot.radiowaves.left.and.right")
            .font(.system(size: 12, weight: .bold))
            .foregroundStyle(Theme.accent)
            .scaleEffect(isActive && pulse ? 1.12 : 1)
            .opacity(isActive && pulse ? 0.7 : 1)
            .animation(
                isActive
                    ? .easeInOut(duration: 1.4).repeatForever(autoreverses: true)
                    : .default,
                value: pulse
            )
            .onAppear { pulse = isActive }
            .onChange(of: isActive) { _, active in
                pulse = active
            }
    }
}

/// Map account button — photo/initials with a quiet pad, no neon.
private struct MapAccountAvatar: View {
    let name: String
    let uid: String
    let photoURL: String?

    var body: some View {
        InitialAvatar(name: name, seed: uid, size: 32, photoURL: photoURL)
            .padding(2)
            .background(Theme.surface.opacity(0.94), in: Circle())
            .overlay {
                Circle().strokeBorder(Theme.hairline, lineWidth: 1)
            }
            .frame(width: 36, height: 36)
    }
}

/// A marker drawn as a chat bubble: title when alone, stacked kind glyphs when clustered.
private struct BubbleMarker: View {
    let bubble: GeoCluster<ChatThread>

    var body: some View {
        // Re-evaluate Live every 30s so badges age off without a pan.
        TimelineView(.periodic(from: .now, by: 30)) { context in
            if let thread = bubble.single {
                LiveThreadBubble(thread: thread, now: context.date)
            } else {
                ClusterBubbleMarker(threads: bubble.items, now: context.date)
            }
        }
    }
}

private struct LiveThreadBubble: View {
    let thread: ChatThread
    let now: Date

    @State private var flash = false
    @State private var seenMessageAt: Date?

    private var live: Bool { LiveNow.isLive(thread.lastMessageAt, now: now) }
    /// Sharper lower-left point — that corner is the map anchor (`.bottomLeading`).
    private var shape: UnevenRoundedRectangle { Theme.bubble(radius: 14, tailRadius: 2) }

    var body: some View {
        bubbleChip
            .scaleEffect(flash ? 1.08 : 1, anchor: .bottomLeading)
            .brightness(flash ? 0.08 : 0)
            .onAppear { seenMessageAt = thread.lastMessageAt }
            .onChange(of: thread.lastMessageAt) { _, newValue in
                guard let newValue else { return }
                if let seen = seenMessageAt, newValue > seen {
                    triggerFlash()
                }
                seenMessageAt = newValue
            }
    }

    private var bubbleChip: some View {
        HStack(spacing: 7) {
            if live {
                Circle()
                    .fill(Theme.accent)
                    .frame(width: 8, height: 8)
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
        .background(Theme.surface, in: shape)
        .overlay {
            shape.strokeBorder(
                live ? Theme.accent.opacity(flash ? 0.95 : 0.7) : Theme.hairline,
                lineWidth: live ? (flash ? 2 : 1.5) : 1
            )
        }
        .shadow(
            color: live ? Theme.accent.opacity(flash ? 0.55 : 0.3) : .black.opacity(0.45),
            radius: flash ? 12 : (live ? 8 : 6),
            y: 3
        )
    }

    private func triggerFlash() {
        withAnimation(.spring(duration: 0.28, bounce: 0.35)) { flash = true }
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(420))
            withAnimation(.easeOut(duration: 0.35)) { flash = false }
        }
    }
}

/// Cluster pin: stack of kind glyphs (hottest first), +N if more than three.
/// Glyphs are interim — custom icon set later (docs/PARKING.md).
private struct ClusterBubbleMarker: View {
    let threads: [ChatThread]
    let now: Date

    private static let maxVisible = 3
    private var shape: UnevenRoundedRectangle { Theme.bubble(radius: 14, tailRadius: 2) }

    private var sorted: [ChatThread] {
        threads.sorted {
            ($0.lastMessageAt ?? .distantPast) > ($1.lastMessageAt ?? .distantPast)
        }
    }

    private var visible: [ChatThread] {
        Array(sorted.prefix(Self.maxVisible))
    }

    private var overflow: Int {
        max(0, sorted.count - visible.count)
    }

    private var anyLive: Bool {
        sorted.contains { LiveNow.isLive($0.lastMessageAt, now: now) }
    }

    var body: some View {
        HStack(spacing: 0) {
            HStack(spacing: -8) {
                ForEach(Array(visible.enumerated()), id: \.element.id) { index, thread in
                    Text(thread.kind.glyph)
                        .font(.system(size: 13))
                        .frame(width: 28, height: 28)
                        .background(
                            thread.kind.tint.opacity(0.22),
                            in: Circle()
                        )
                        .overlay {
                            Circle()
                                .strokeBorder(Theme.surface, lineWidth: 1.5)
                        }
                        .zIndex(Double(visible.count - index))
                }
            }

            if overflow > 0 {
                Text("+\(overflow)")
                    .font(.markerTitle)
                    .foregroundStyle(Theme.text)
                    .padding(.leading, 8)
            } else if anyLive {
                Circle()
                    .fill(Theme.accent)
                    .frame(width: 7, height: 7)
                    .padding(.leading, 8)
            }
        }
        .padding(.leading, 8)
        .padding(.trailing, 10)
        .padding(.vertical, 7)
        .background(Theme.surface, in: shape)
        .overlay {
            shape.strokeBorder(
                anyLive ? Theme.accent.opacity(0.7) : Theme.hairline,
                lineWidth: anyLive ? 1.5 : 1
            )
        }
        .shadow(
            color: anyLive ? Theme.accent.opacity(0.3) : .black.opacity(0.45),
            radius: anyLive ? 8 : 6,
            y: 3
        )
        .accessibilityLabel(accessibilityLabel)
    }

    private var accessibilityLabel: String {
        let n = sorted.count
        let base = n == 1 ? "1 chat" : "\(n) chats"
        return anyLive ? "\(base), live" : base
    }
}

/// Kind filter: stacked glyphs like cluster pins; tap spreads them out to toggle.
/// Map stays interactive while open — collapse with the trailing ×.
private struct KindFilterStack: View {
    @Binding var expanded: Bool
    let kindFilter: Set<ThreadKind>
    let onToggle: (ThreadKind) -> Void
    let onClear: () -> Void

    private let kinds = Array(ThreadKind.allCases)
    private var filterActive: Bool { !kindFilter.isEmpty }

    var body: some View {
        HStack(spacing: expanded ? 10 : -8) {
            ForEach(Array(kinds.enumerated()), id: \.element.rawValue) { index, kind in
                let selected = kindFilter.contains(kind)
                Button {
                    if expanded {
                        onToggle(kind)
                    } else {
                        withAnimation(.spring(duration: 0.38, bounce: 0.2)) {
                            expanded = true
                        }
                    }
                } label: {
                    Text(kind.glyph)
                        .font(.system(size: 13))
                        .frame(width: 28, height: 28)
                        .background(
                            kind.tint.opacity(filterActive ? (selected ? 0.28 : 0.12) : 0.22),
                            in: Circle()
                        )
                        .overlay {
                            Circle().strokeBorder(
                                filterActive && selected ? kind.tint.opacity(0.75) : Theme.surface,
                                lineWidth: 1.5
                            )
                        }
                        .opacity(expanded && filterActive && !selected ? 0.45 : 1)
                }
                .buttonStyle(.plain)
                .zIndex(Double(kinds.count - index))
                .accessibilityLabel(kind.label)
                .accessibilityAddTraits(selected ? .isSelected : [])
            }

            if expanded {
                Button {
                    if filterActive { onClear() }
                    withAnimation(.spring(duration: 0.38, bounce: 0.2)) {
                        expanded = false
                    }
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(Theme.subtle)
                        .frame(width: 28, height: 28)
                        .background(Theme.raised, in: Circle())
                        .overlay { Circle().strokeBorder(Theme.hairline, lineWidth: 1) }
                }
                .buttonStyle(.plain)
                .accessibilityLabel(filterActive ? "Clear filter and collapse" : "Collapse")
                .transition(.opacity)
            }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 7)
        .background(Theme.surface.opacity(0.92), in: Capsule())
        .overlay {
            Capsule().strokeBorder(
                filterActive ? Theme.accent.opacity(0.55) : Theme.hairline,
                lineWidth: filterActive ? 1.5 : 1
            )
        }
        .shadow(color: .black.opacity(0.35), radius: 10, y: 3)
        // Only the stack spacing should spring; glyph chrome stays put.
        .animation(.spring(duration: 0.38, bounce: 0.2), value: expanded)
        .accessibilityElement(children: expanded ? .contain : .combine)
        .accessibilityLabel(collapsedAccessibilityLabel)
        .accessibilityHint(expanded ? "" : "Double tap to expand kind filters")
    }

    private var collapsedAccessibilityLabel: String {
        if filterActive {
            let names = kinds.filter { kindFilter.contains($0) }.map(\.label)
            return "Filtering: \(names.joined(separator: ", "))"
        }
        return "Filter by kind"
    }
}

private struct ThreadRoute: Identifiable {
    let id: String
}
