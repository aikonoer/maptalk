import SwiftUI

/// Peek card for a single map bubble: thread stats (instant) + latest message (loaded).
/// Tap or swipe up to open the chat.
struct BubblePreviewCard: View {
    let thread: ChatThread
    let latest: Message?
    let isLoading: Bool
    let onOpen: () -> Void

    @State private var dragOffset: CGFloat = 0

    private var live: Bool { LiveNow.isLive(thread.lastMessageAt) }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            PreviewGrabber()

            header
            stats
            latestBlock

            Text("Tap or swipe up to open")
                .font(.meta)
                .foregroundStyle(Theme.faint)
                .frame(maxWidth: .infinity)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
        .shadow(color: .black.opacity(0.45), radius: 16, y: 8)
        .offset(y: dragOffset)
        .opacity(Double(1 + dragOffset / 280).clamped(to: 0.55...1))
        .contentShape(Rectangle())
        .onTapGesture(perform: onOpen)
        .gesture(swipeUpToOpen)
        .accessibilityHint("Swipe up or double tap to open")
    }

    private var swipeUpToOpen: some Gesture {
        DragGesture(minimumDistance: 8, coordinateSpace: .local)
            .onChanged { value in
                // Only follow upward drags; ignore downward so dismiss-via-scrim stays clear.
                dragOffset = min(0, value.translation.height)
            }
            .onEnded { value in
                let flung = value.predictedEndTranslation.height < -140
                let pulled = value.translation.height < -64
                if flung || pulled {
                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                    onOpen()
                } else {
                    withAnimation(.spring(duration: 0.28, bounce: 0.18)) {
                        dragOffset = 0
                    }
                }
            }
    }

    private var header: some View {
        HStack(alignment: .top, spacing: 8) {
            Text(thread.kind.glyph)
                .font(.title3)

            VStack(alignment: .leading, spacing: 2) {
                Text(thread.title)
                    .font(.cardTitle)
                    .foregroundStyle(Theme.text)
                    .lineLimit(2)

                Text(subtitle)
                    .font(.meta)
                    .foregroundStyle(live ? Theme.accent : Theme.faint)
                    .lineLimit(1)
            }

            Spacer(minLength: 8)

            Text("Open")
                .font(.control)
                .foregroundStyle(Theme.accent)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(Theme.accent.opacity(0.16), in: Capsule())
        }
    }

    private var stats: some View {
        HStack(spacing: 0) {
            statChip(
                value: thread.messageCount == 0 ? "—" : "\(thread.messageCount)",
                label: thread.messageCount == 1 ? "message" : "messages"
            )
            Divider().frame(height: 28).overlay(Theme.hairline)
            statChip(
                value: relativeTime(thread.lastMessageAt),
                label: live ? "live now" : "last active"
            )
            Divider().frame(height: 28).overlay(Theme.hairline)
            statChip(
                value: relativeTime(thread.createdAt),
                label: "started"
            )
        }
        .padding(.vertical, 10)
        .padding(.horizontal, 4)
        .frame(maxWidth: .infinity)
        .background(Theme.raised.opacity(0.9), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    private func statChip(value: String, label: String) -> some View {
        VStack(spacing: 2) {
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
        VStack(alignment: .leading, spacing: 6) {
            Text("Latest")
                .font(.meta)
                .foregroundStyle(Theme.faint)

            if isLoading {
                RoundedRectangle(cornerRadius: 6, style: .continuous)
                    .fill(Theme.raised)
                    .frame(height: 36)
                    .redacted(reason: .placeholder)
            } else if let latest {
                HStack(alignment: .top, spacing: 8) {
                    Text(latest.authorName)
                        .font(.meta)
                        .foregroundStyle(Theme.subtle)
                        .lineLimit(1)
                    Text(messagePreviewLine(latest))
                        .font(.subheadline)
                        .foregroundStyle(Theme.text)
                        .lineLimit(2)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Text(relativeTime(latest.createdAt))
                        .font(.meta)
                        .foregroundStyle(Theme.faint)
                }
            } else {
                Text("Nobody has said anything yet")
                    .font(.subheadline)
                    .foregroundStyle(Theme.faint)
            }
        }
    }

    private var subtitle: String {
        [live ? "Live" : nil, thread.kind.label, thread.authorName]
            .compactMap { $0 }
            .joined(separator: " · ")
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
    if message.hasVideo { return "Video" }
    if message.hasImage && message.text.isEmpty { return "Photo" }
    if message.hasImage { return message.text }
    if message.text.isEmpty { return "Message" }
    return message.text
}
