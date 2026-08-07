import AVFoundation
import SwiftUI
import UIKit

/// Peek card for a single map bubble: thread stats (instant) + last few messages (loaded).
/// Tap or swipe up to open · swipe down to dismiss.
struct BubblePreviewCard: View {
    let thread: ChatThread
    /// Oldest → newest tip lines (up to 3). Older rows fade.
    let latest: [Message]
    /// Preloaded thumb for the newest tip media so it rides in with the card.
    var mediaThumb: UIImage? = nil
    let isLoading: Bool
    let onOpen: () -> Void
    let onDismiss: () -> Void

    @State private var dragOffset: CGFloat = 0

    private var live: Bool { LiveNow.isLive(thread.lastMessageAt) }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            PreviewGrabber()

            header
            stats
            latestBlock
        }
        .padding(.horizontal, 18)
        .padding(.top, 10)
        .padding(.bottom, 18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
        .shadow(color: .black.opacity(0.45), radius: 16, y: 8)
        .offset(y: dragOffset)
        .opacity(Double(1 - abs(dragOffset) / 280).clamped(to: 0.55...1))
        .contentShape(Rectangle())
        .onTapGesture(perform: onOpen)
        .gesture(verticalSwipe)
        .accessibilityHint("Swipe up or double tap to open. Swipe down to dismiss.")
    }

    private var verticalSwipe: some Gesture {
        DragGesture(minimumDistance: 8, coordinateSpace: .local)
            .onChanged { value in
                dragOffset = value.translation.height
            }
            .onEnded { value in
                let predicted = value.predictedEndTranslation.height
                let pulled = value.translation.height
                let open = predicted < -140 || pulled < -64
                let dismiss = predicted > 140 || pulled > 64
                if open {
                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                    onOpen()
                } else if dismiss {
                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                    onDismiss()
                } else {
                    withAnimation(.spring(duration: 0.28, bounce: 0.18)) {
                        dragOffset = 0
                    }
                }
            }
    }

    private var header: some View {
        HStack(alignment: .top, spacing: 12) {
            Text(thread.kind.glyph)
                .font(.system(size: 22))
                .frame(width: 40, height: 40)
                .background(
                    thread.kind.tint.opacity(0.18),
                    in: Theme.bubble(radius: 12, tail: .bottomLeading)
                )

            VStack(alignment: .leading, spacing: 5) {
                Text(thread.title)
                    .font(.cardTitle)
                    .foregroundStyle(Theme.text)
                    .lineLimit(2)
                    .truncationMode(.tail)
                    .fixedSize(horizontal: false, vertical: true)

                HStack(spacing: 5) {
                    if live {
                        LiveDot(size: 6)
                        Text("Live")
                            .foregroundStyle(Theme.accent)
                    }
                    Text(thread.kind.label)
                        .foregroundStyle(thread.kind.tint)
                    Text("\u{00b7} \(thread.authorName)")
                        .foregroundStyle(Theme.faint)
                }
                .font(.meta)
                .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var stats: some View {
        HStack(spacing: 0) {
            statChip(
                value: thread.messageCount == 0 ? "—" : "\(thread.messageCount)",
                label: thread.messageCount == 1 ? "message" : "messages"
            )
            Rectangle()
                .fill(Theme.hairline)
                .frame(width: 1, height: 28)
            statChip(
                value: relativeTime(thread.lastMessageAt),
                label: live ? "live now" : "last active"
            )
            Rectangle()
                .fill(Theme.hairline)
                .frame(width: 1, height: 28)
            statChip(
                value: relativeTime(thread.createdAt),
                label: "started"
            )
        }
        .padding(.vertical, 4)
    }

    private func statChip(value: String, label: String) -> some View {
        VStack(spacing: 3) {
            Text(value)
                .font(.control)
                .foregroundStyle(Theme.text)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
            Text(label)
                .font(.meta)
                .foregroundStyle(Theme.faint)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity)
    }

    @ViewBuilder
    private var latestBlock: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Latest")
                .font(.meta)
                .foregroundStyle(Theme.faint)

            if isLoading {
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .fill(Theme.raised)
                    .frame(height: 56)
                    .redacted(reason: .placeholder)
            } else if latest.isEmpty {
                Text("Nobody has said anything yet")
                    .font(.subheadline)
                    .foregroundStyle(Theme.faint)
            } else {
                VStack(alignment: .leading, spacing: 10) {
                    ForEach(Array(latest.enumerated()), id: \.element.id) { index, message in
                        PeekLatestRow(
                            message: message,
                            showMedia: index == latest.count - 1,
                            mediaThumb: index == latest.count - 1 ? mediaThumb : nil,
                            opacity: Self.fadeOpacity(index: index, count: latest.count)
                        )
                    }
                }
                .mask {
                    if latest.count > 1 {
                        LinearGradient(
                            stops: [
                                .init(color: .black.opacity(0.25), location: 0),
                                .init(color: .black.opacity(0.65), location: 0.28),
                                .init(color: .black, location: 0.62),
                                .init(color: .black, location: 1),
                            ],
                            startPoint: .top,
                            endPoint: .bottom
                        )
                    } else {
                        Rectangle()
                    }
                }
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.raised.opacity(0.85), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    /// Oldest tip rows fade; the newest stays full strength.
    private static func fadeOpacity(index: Int, count: Int) -> Double {
        guard count > 1 else { return 1 }
        let t = Double(index) / Double(count - 1)
        return 0.38 + t * 0.62
    }
}

private struct PeekLatestRow: View {
    let message: Message
    let showMedia: Bool
    var mediaThumb: UIImage? = nil
    let opacity: Double

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            InitialAvatar(name: message.authorName, seed: message.authorId, size: 26)

            VStack(alignment: .leading, spacing: 2) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(message.authorName)
                        .font(.meta)
                        .foregroundStyle(Theme.subtle)
                        .lineLimit(1)
                    Spacer(minLength: 4)
                    Text(relativeTime(message.createdAt))
                        .font(.meta)
                        .foregroundStyle(Theme.faint)
                }
                Text(messagePreviewLine(message))
                    .font(.subheadline)
                    .foregroundStyle(Theme.text)
                    .lineLimit(2)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }

            if showMedia, message.hasImage || message.hasVideo {
                PeekMediaThumb(message: message, preloaded: mediaThumb)
            }
        }
        .opacity(opacity)
    }
}

struct PreviewGrabber: View {
    var body: some View {
        Capsule()
            .fill(Theme.hairline)
            .frame(width: 36, height: 4)
            .frame(maxWidth: .infinity)
            .padding(.bottom, 2)
            .accessibilityHidden(true)
    }
}

extension Comparable {
    func clamped(to range: ClosedRange<Self>) -> Self {
        min(max(self, range.lowerBound), range.upperBound)
    }
}

func messagePreviewLine(_ message: Message) -> String {
    if message.isSticker { return message.text }
    if message.hasVoice { return "Voice note" }
    if message.hasVideo { return message.text.isEmpty ? "Video" : message.text }
    if message.hasImage && message.text.isEmpty { return "Photo" }
    if message.hasImage { return message.text }
    if message.text.isEmpty { return "Message" }
    return message.text
}

/// Compact photo / video thumb for peek — same visual weight as the text line.
struct PeekMediaThumb: View {
    let message: Message
    var preloaded: UIImage? = nil
    @State private var image: UIImage?

    private var path: String? {
        if message.hasImage { return message.imagePath }
        if message.hasVideo { return message.videoPath }
        return nil
    }

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(Theme.raised)
            if let image = image ?? preloaded {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            } else if message.hasVideo {
                Image(systemName: "video.fill")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(Theme.faint)
            } else {
                Image(systemName: "photo")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(Theme.faint)
            }
            if message.hasVideo {
                Image(systemName: "play.fill")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(.white)
                    .padding(6)
                    .background(.black.opacity(0.45), in: Circle())
            }
        }
        .frame(width: 56, height: 56)
        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        .task(id: path) {
            if preloaded != nil {
                image = preloaded
                return
            }
            image = await Self.loadThumb(path: path, isVideo: message.hasVideo)
        }
    }

    static func load(for message: Message) async -> UIImage? {
        let path = message.hasImage ? message.imagePath : message.videoPath
        return await loadThumb(path: path, isVideo: message.hasVideo)
    }

    private static func loadThumb(path: String?, isVideo: Bool) async -> UIImage? {
        guard let path else { return nil }
        if path.hasPrefix("http://") || path.hasPrefix("https://") {
            guard let url = URL(string: path) else { return nil }
            if isVideo {
                return await videoPoster(url: url)
            }
            guard let (data, _) = try? await URLSession.shared.data(from: url) else { return nil }
            return UIImage(data: data)
        }
        let fileURL: URL = {
            if path.hasPrefix("/") || path.hasPrefix("file:") {
                return URL(fileURLWithPath: path.replacingOccurrences(of: "file://", with: ""))
            }
            return LocalMediaStore.url(forRelativePath: path)
        }()
        if isVideo {
            return await videoPoster(url: fileURL)
        }
        return UIImage(contentsOfFile: fileURL.path)
    }

    private static func videoPoster(url: URL) async -> UIImage? {
        let asset = AVURLAsset(url: url)
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: 240, height: 240)
        let time = CMTime(seconds: 0.05, preferredTimescale: 600)
        guard let cg = try? await generator.image(at: time).image else { return nil }
        return UIImage(cgImage: cg)
    }
}
