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
    private(set) var currentTimeMs: Int = 0
    private(set) var durationMs: Int = 0

    private var endObserver: NSObjectProtocol?
    private var timeControlObservation: NSKeyValueObservation?
    private var timeObserver: Any?
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
        currentTimeMs = 0
        durationMs = 0

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

        let interval = CMTime(seconds: 0.25, preferredTimescale: 600)
        timeObserver = av.addPeriodicTimeObserver(forInterval: interval, queue: .main) { [weak self] time in
            Task { @MainActor in
                guard let self else { return }
                self.currentTimeMs = Int((CMTimeGetSeconds(time) * 1_000).rounded())
                if let item = self.player?.currentItem {
                    let duration = CMTimeGetSeconds(item.duration)
                    if duration.isFinite, duration > 0 {
                        self.durationMs = Int((duration * 1_000).rounded())
                    }
                }
            }
        }

        av.play()
    }

    func pause() {
        player?.pause()
        isPlaying = false
        isBuffering = false
    }

    func togglePlayPause() {
        guard let player else { return }
        if isPlaying {
            pause()
        } else {
            if currentTimeMs > 0, durationMs > 0, currentTimeMs >= durationMs - 200 {
                seek(toMs: 0)
            }
            isPlaying = true
            player.isMuted = isMuted
            player.play()
        }
    }

    func seek(toMs: Int) {
        let ms = max(0, toMs)
        let time = CMTime(value: CMTimeValue(ms), timescale: 1_000)
        player?.seek(to: time, toleranceBefore: .zero, toleranceAfter: .zero)
        currentTimeMs = ms
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
        currentTimeMs = 0
        callback?()
    }

    private func stopInternal(notifyEnded: Bool) {
        if let endObserver {
            NotificationCenter.default.removeObserver(endObserver)
            self.endObserver = nil
        }
        timeControlObservation?.invalidate()
        timeControlObservation = nil
        if let timeObserver, let player {
            player.removeTimeObserver(timeObserver)
        }
        timeObserver = nil

        player?.pause()
        player = nil
        currentURL = nil
        isPlaying = false
        isBuffering = false
        currentTimeMs = 0
        durationMs = 0

        let callback = onEnded
        onEnded = nil
        if notifyEnded {
            callback?()
        }
    }
}
