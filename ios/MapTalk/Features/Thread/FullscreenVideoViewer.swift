import AVFoundation
import AVKit
import SwiftUI
import UIKit

/// Full-screen video with transport controls.
struct FullscreenVideoViewer: View {
    let path: String
    let durationMs: Int
    let onDismiss: () -> Void

    @State private var controlsVisible = true
    @State private var hideTask: Task<Void, Never>?
    @State private var isScrubbing = false
    @State private var scrubMs: Double = 0
    @State private var dragOffset: CGFloat = 0

    private let playback = VideoPlaybackController.shared

    private var mediaURL: URL? {
        if path.hasPrefix("http://") || path.hasPrefix("https://") {
            return URL(string: path)
        }
        if path.hasPrefix("file://") {
            return URL(string: path)
        }
        if path.hasPrefix("/") {
            return URL(fileURLWithPath: path)
        }
        return LocalMediaStore.url(forRelativePath: path)
    }

    private var resolvedDuration: Int {
        max(durationMs, playback.durationMs, 1)
    }

    /// 0 at rest, 1 once the drag is far enough that releasing dismisses.
    private var dragProgress: CGFloat {
        min(1, dragOffset / Self.dragTravel)
    }

    var body: some View {
        ZStack {
            // Deliberately opaque: a translucent backdrop shows the thread (and
            // this video's own poster bubble) through the moving video, and makes
            // the whole stack recomposite every frame of the drag.
            Color.black.ignoresSafeArea()

            // Only the video moves, and it is grouped so the drag transform and
            // its safe-area-ignoring layout resolve together. Transforming a stack
            // that mixed this with the safe-area-respecting chrome let the two
            // land on different frames, which read as a flicker between positions.
            if let player = playback.player {
                PlayerLayerView(player: player)
                    .ignoresSafeArea()
                    .scaleEffect(1 - dragProgress * 0.14)
                    .offset(y: dragOffset)
                    .geometryGroup()
            }

            Color.clear
                .contentShape(Rectangle())
                .onTapGesture { toggleControls() }
                .gesture(swipeToDismiss)

            if controlsVisible {
                VStack {
                    HStack {
                        Spacer()
                        Button(action: onDismiss) {
                            Image(systemName: "xmark")
                                .font(.system(size: 14, weight: .bold))
                                .foregroundStyle(.white)
                                .frame(width: 36, height: 36)
                                .background(.white.opacity(0.18), in: Circle())
                        }
                        .accessibilityLabel("Close")
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 12)

                    Spacer()

                    controls
                        .padding(.horizontal, 16)
                        .padding(.bottom, 28)
                }
                .transition(.opacity)
                .opacity(1 - Double(min(1, dragOffset / 70)))
            }

            if playback.isBuffering {
                ProgressView()
                    .controlSize(.large)
                    .tint(.white)
            }
        }
        .statusBarHidden(true)
        .task { await start() }
        .onDisappear {
            hideTask?.cancel()
            playback.pause()
        }
    }

    private var swipeToDismiss: some Gesture {
        DragGesture(minimumDistance: 14)
            .onChanged { value in
                dragOffset = max(0, value.translation.height)
                if dragOffset > 0 { hideTask?.cancel() }
            }
            .onEnded { value in
                let flung = value.predictedEndTranslation.height > Self.dragTravel
                if dragOffset > Self.dismissThreshold || flung {
                    onDismiss()
                } else {
                    withAnimation(.interactiveSpring(response: 0.32, dampingFraction: 0.82)) {
                        dragOffset = 0
                    }
                    scheduleHide()
                }
            }
    }

    private static let dragTravel: CGFloat = 240
    private static let dismissThreshold: CGFloat = 110

    private var controls: some View {
        VStack(spacing: 14) {
            HStack(spacing: 12) {
                Text(format(isScrubbing ? Int(scrubMs) : playback.currentTimeMs))
                    .font(.meta.monospacedDigit())
                    .foregroundStyle(.white.opacity(0.9))
                    .frame(width: 44, alignment: .leading)

                Slider(
                    value: Binding(
                        get: { isScrubbing ? scrubMs : Double(playback.currentTimeMs) },
                        set: { scrubMs = $0 }
                    ),
                    in: 0...Double(resolvedDuration),
                    onEditingChanged: { editing in
                        isScrubbing = editing
                        if editing {
                            hideTask?.cancel()
                        } else {
                            playback.seek(toMs: Int(scrubMs))
                            scheduleHide()
                        }
                    }
                )
                .tint(.white)

                Text(format(resolvedDuration))
                    .font(.meta.monospacedDigit())
                    .foregroundStyle(.white.opacity(0.9))
                    .frame(width: 44, alignment: .trailing)
            }

            HStack(spacing: 28) {
                Button {
                    playback.toggleMute()
                    bumpControls()
                } label: {
                    Image(systemName: playback.isMuted ? "speaker.slash.fill" : "speaker.wave.2.fill")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(width: 44, height: 44)
                }

                Button {
                    playback.togglePlayPause()
                    bumpControls()
                } label: {
                    Image(systemName: playback.isPlaying ? "pause.circle.fill" : "play.circle.fill")
                        .font(.system(size: 52))
                        .foregroundStyle(.white)
                }

                Button {
                    playback.seek(toMs: min(resolvedDuration, playback.currentTimeMs + 10_000))
                    bumpControls()
                } label: {
                    Image(systemName: "goforward.10")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(width: 44, height: 44)
                }
            }
        }
        .padding(16)
        .background(.black.opacity(0.45), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
    }

    private func start() async {
        guard let url = mediaURL else { return }
        let playURL: URL
        if let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https" {
            playURL = await VideoCache.localURL(for: url)
        } else {
            playURL = url
        }
        playback.play(url: playURL)
        scheduleHide()
    }

    private func toggleControls() {
        withAnimation(.easeOut(duration: 0.15)) {
            controlsVisible.toggle()
        }
        if controlsVisible { scheduleHide() }
    }

    private func bumpControls() {
        controlsVisible = true
        scheduleHide()
    }

    private func scheduleHide() {
        hideTask?.cancel()
        hideTask = Task {
            try? await Task.sleep(for: .seconds(3))
            guard !Task.isCancelled, !isScrubbing, playback.isPlaying else { return }
            withAnimation(.easeOut(duration: 0.2)) {
                controlsVisible = false
            }
        }
    }

    private func format(_ ms: Int) -> String {
        let total = max(0, ms / 1_000)
        return String(format: "%d:%02d", total / 60, total % 60)
    }
}

/// Bare `AVPlayerLayer`. `VideoPlayer` carries a whole `AVPlayerViewController`
/// that re-lays out on every frame of a transform, which makes the swipe stutter.
private struct PlayerLayerView: UIViewRepresentable {
    let player: AVPlayer

    func makeUIView(context: Context) -> PlayerLayerHost {
        let view = PlayerLayerHost()
        view.backgroundColor = .black
        view.isUserInteractionEnabled = false
        view.playerLayer.player = player
        return view
    }

    func updateUIView(_ view: PlayerLayerHost, context: Context) {
        if view.playerLayer.player !== player {
            view.playerLayer.player = player
        }
    }
}

private final class PlayerLayerHost: UIView {
    let playerLayer = AVPlayerLayer()

    override init(frame: CGRect) {
        super.init(frame: frame)
        playerLayer.videoGravity = .resizeAspect
        layer.addSublayer(playerLayer)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is unavailable") }

    override func layoutSubviews() {
        super.layoutSubviews()
        // No implicit animation: the drag resizes this every frame, and an
        // animated frame change would trail the transform by a frame.
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        playerLayer.frame = bounds
        CATransaction.commit()
    }
}
