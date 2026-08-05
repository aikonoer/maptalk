import AVKit
import SwiftUI
import UIKit

/// Pick a 30-second window from a longer clip before upload.
struct VideoTrimSheet: View {
    let sourceURL: URL
    let durationMs: Int
    let onTrim: (Int) -> Void
    let onCancel: () -> Void

    @State private var startMs: Double = 0
    @State private var player: AVPlayer?
    @State private var thumbs: [UIImage] = []
    @State private var loopObserver: NSObjectProtocol?

    private var maxStartMs: Double {
        Double(max(0, durationMs - VideoCompressor.maxDurationMs))
    }

    private var endMs: Int {
        min(durationMs, Int(startMs) + VideoCompressor.maxDurationMs)
    }

    private var windowSeconds: Int {
        max(1, (endMs - Int(startMs) + 500) / 1_000)
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                ZStack {
                    Color.black
                    if let player {
                        VideoPlayer(player: player)
                            .disabled(true)
                    }
                }
                .frame(maxWidth: .infinity)
                .frame(height: 260)
                .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                .overlay(alignment: .topTrailing) {
                    Text("\(windowSeconds) sec")
                        .font(.meta.weight(.semibold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(.black.opacity(0.55), in: Capsule())
                        .padding(12)
                }

                VStack(alignment: .leading, spacing: 6) {
                    Text("Pick your best 15 seconds")
                        .font(.cardTitle)
                        .foregroundStyle(Theme.text)
                    Text("Short & clear looks better in chat — slide to choose the fun part.")
                        .font(.subheadline)
                        .foregroundStyle(Theme.subtle)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                filmstripTrack

                HStack {
                    Text("\(format(Int(startMs))) – \(format(endMs))")
                        .font(.meta.monospacedDigit().weight(.semibold))
                        .foregroundStyle(Theme.text)
                    Spacer()
                    Text("Video is \(format(durationMs)) long")
                        .font(.meta)
                        .foregroundStyle(Theme.faint)
                }

                Spacer(minLength: 0)

                Button {
                    player?.pause()
                    onTrim(Int(startMs))
                } label: {
                    Text("Looks good!")
                        .font(.control)
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 15)
                        .background(Theme.accent, in: Capsule())
                }
                .buttonStyle(.pressable)

                Button("Never mind") {
                    player?.pause()
                    onCancel()
                }
                .font(.control)
                .foregroundStyle(Theme.subtle)
            }
            .padding(16)
            .background(Theme.base)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) {
                    Text("Make it short")
                        .font(.control.weight(.semibold))
                        .foregroundStyle(Theme.text)
                }
            }
        }
        .presentationDetents([.large])
        .onAppear {
            let av = AVPlayer(url: sourceURL)
            av.isMuted = true
            player = av
            installLoop(for: av)
            av.play()
            Task {
                thumbs = await VideoCompressor.filmstrip(from: sourceURL)
            }
        }
        .onDisappear {
            if let loopObserver {
                NotificationCenter.default.removeObserver(loopObserver)
            }
            player?.pause()
            player = nil
        }
        .onChange(of: startMs) { _, newValue in
            seekAndLoop(toMs: Int(newValue))
        }
    }

    private var filmstripTrack: some View {
        GeometryReader { geo in
            let trackW = geo.size.width
            let windowFrac = min(1, Double(VideoCompressor.maxDurationMs) / Double(max(1, durationMs)))
            let windowW = max(48, trackW * windowFrac)
            let travel = max(0, trackW - windowW)
            let x = maxStartMs > 0 ? (startMs / maxStartMs) * travel : 0

            ZStack(alignment: .leading) {
                HStack(spacing: 0) {
                    ForEach(Array(thumbs.enumerated()), id: \.offset) { _, img in
                        Image(uiImage: img)
                            .resizable()
                            .scaledToFill()
                            .frame(maxWidth: .infinity)
                            .frame(height: 64)
                            .clipped()
                    }
                    if thumbs.isEmpty {
                        ForEach(0..<8, id: \.self) { _ in
                            Theme.raised
                                .frame(maxWidth: .infinity)
                                .frame(height: 64)
                        }
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .stroke(Theme.hairline, lineWidth: 1)
                }

                HStack(spacing: 0) {
                    Color.black.opacity(0.45)
                        .frame(width: max(0, x))
                    Color.clear
                        .frame(width: windowW)
                    Color.black.opacity(0.45)
                        .frame(maxWidth: .infinity)
                }
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                .allowsHitTesting(false)

                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .stroke(Theme.accent, lineWidth: 3)
                    .frame(width: windowW, height: 64)
                    .offset(x: x)
                    .allowsHitTesting(false)
            }
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        guard maxStartMs > 0, travel > 0 else { return }
                        let nx = min(max(0, value.location.x - windowW / 2), travel)
                        startMs = (nx / travel) * maxStartMs
                    }
            )
        }
        .frame(height: 64)
    }

    private func installLoop(for av: AVPlayer) {
        if let loopObserver {
            NotificationCenter.default.removeObserver(loopObserver)
        }
        loopObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: av.currentItem,
            queue: .main
        ) { _ in
            seekAndLoop(toMs: Int(startMs))
        }
    }

    private func seekAndLoop(toMs: Int) {
        let start = CMTime(value: CMTimeValue(toMs), timescale: 1_000)
        let end = CMTime(value: CMTimeValue(endMs), timescale: 1_000)
        player?.currentItem?.forwardPlaybackEndTime = end
        player?.seek(to: start, toleranceBefore: .zero, toleranceAfter: .zero)
        player?.play()
    }

    private func format(_ ms: Int) -> String {
        let total = max(0, ms / 1_000)
        return String(format: "%d:%02d", total / 60, total % 60)
    }
}
