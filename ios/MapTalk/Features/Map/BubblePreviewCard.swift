import SwiftUI

/// Peek card for a single map bubble: thread stats (instant) + latest message (loaded).
/// Tap or swipe up to open · swipe down to dismiss.
struct BubblePreviewCard: View {
    let thread: ChatThread
    let latest: Message?
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
                        Circle()
                            .fill(Theme.accent)
                            .frame(width: 6, height: 6)
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
                    .frame(height: 40)
                    .redacted(reason: .placeholder)
            } else if let latest {
                HStack(alignment: .top, spacing: 10) {
                    InitialAvatar(name: latest.authorName, seed: latest.authorId, size: 28)

                    VStack(alignment: .leading, spacing: 2) {
                        HStack(alignment: .firstTextBaseline, spacing: 8) {
                            Text(latest.authorName)
                                .font(.meta)
                                .foregroundStyle(Theme.subtle)
                                .lineLimit(1)
                            Spacer(minLength: 4)
                            Text(relativeTime(latest.createdAt))
                                .font(.meta)
                                .foregroundStyle(Theme.faint)
                        }
                        Text(messagePreviewLine(latest))
                            .font(.subheadline)
                            .foregroundStyle(Theme.text)
                            .lineLimit(2)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            } else {
                Text("Nobody has said anything yet")
                    .font(.subheadline)
                    .foregroundStyle(Theme.faint)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.raised.opacity(0.85), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
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
