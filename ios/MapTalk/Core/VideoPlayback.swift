import AVFoundation
import Foundation

/// Single shared `AVPlayer` so only one bubble plays at a time.
@MainActor
@Observable
final class VideoPlaybackController {
    static let shared = VideoPlaybackController()

    private(set) var player: AVPlayer?
    private(set) var currentURL: URL?
    private(set) var isPlaying = false
    private(set) var isBuffering = false
    private(set) var isMuted = false

    private var endObserver: NSObjectProtocol?
    private var timeControlObservation: NSKeyValueObservation?
    private var onEnded: (() -> Void)?

    private init() {}

    func play(url: URL, onEnded: (() -> Void)? = nil) {
        if currentURL == url, let player {
            self.onEnded = onEnded ?? self.onEnded
            isPlaying = true
            player.isMuted = isMuted
            player.play()
            return
        }

        stopInternal(notifyEnded: false)
        self.onEnded = onEnded
        currentURL = url

        let item = AVPlayerItem(url: url)
        let av = AVPlayer(playerItem: item)
        av.isMuted = isMuted
        player = av
        isPlaying = true
        isBuffering = true

        endObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: item,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in
                self?.handleEnded()
            }
        }

        timeControlObservation = av.observe(\.timeControlStatus, options: [.initial, .new]) { [weak self] observed, _ in
            Task { @MainActor in
                guard let self else { return }
                self.isBuffering = observed.timeControlStatus == .waitingToPlayAtSpecifiedRate
            }
        }

        av.play()
    }

    func pause() {
        player?.pause()
        isPlaying = false
        isBuffering = false
    }

    func toggleMute() {
        isMuted.toggle()
        player?.isMuted = isMuted
    }

    func stop() {
        stopInternal(notifyEnded: false)
    }

    private func handleEnded() {
        let callback = onEnded
        onEnded = nil
        player?.seek(to: .zero)
        player?.pause()
        isPlaying = false
        isBuffering = false
        callback?()
    }

    private func stopInternal(notifyEnded: Bool) {
        if let endObserver {
            NotificationCenter.default.removeObserver(endObserver)
            self.endObserver = nil
        }
        timeControlObservation?.invalidate()
        timeControlObservation = nil

        player?.pause()
        player = nil
        currentURL = nil
        isPlaying = false
        isBuffering = false

        let callback = onEnded
        onEnded = nil
        if notifyEnded {
            callback?()
        }
    }
}
