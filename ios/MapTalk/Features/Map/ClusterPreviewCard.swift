import SwiftUI

/// Peek card for a clustered map bubble — lists the chats in that cell.
/// Tap a row / swipe up for latest · swipe down to dismiss.
struct ClusterPreviewCard: View {
    let threads: [ChatThread]
    let onOpen: (ChatThread) -> Void
    let onDismiss: () -> Void

    @State private var dragOffset: CGFloat = 0

    private var sorted: [ChatThread] {
        threads.sorted {
            ($0.lastMessageAt ?? .distantPast) > ($1.lastMessageAt ?? .distantPast)
        }
    }

    private var visible: [ChatThread] { Array(sorted.prefix(5)) }
    private var latest: ChatThread? { sorted.first }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            PreviewGrabber()

            HStack(spacing: 8) {
                Image(systemName: "bubble.left.and.bubble.right.fill")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(Theme.accent)

                Text(sorted.count == 1 ? "1 chat here" : "\(sorted.count) chats here")
                    .font(.cardTitle)
                    .foregroundStyle(Theme.text)

                Spacer(minLength: 0)
            }

            VStack(spacing: 0) {
                ForEach(Array(visible.enumerated()), id: \.element.id) { index, thread in
                    if index > 0 {
                        Rectangle()
                            .fill(Theme.hairline)
                            .frame(height: 1)
                    }
                    Button {
                        onOpen(thread)
                    } label: {
                        ClusterPreviewRow(thread: thread)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
        .shadow(color: .black.opacity(0.45), radius: 16, y: 8)
        .offset(y: dragOffset)
        .opacity(Double(1 - abs(dragOffset) / 280).clamped(to: 0.55...1))
        .gesture(verticalSwipe)
        .accessibilityHint("Swipe up to open the latest chat. Swipe down to dismiss.")
    }

    private var verticalSwipe: some Gesture {
        DragGesture(minimumDistance: 12, coordinateSpace: .local)
            .onChanged { value in
                dragOffset = value.translation.height
            }
            .onEnded { value in
                let predicted = value.predictedEndTranslation.height
                let pulled = value.translation.height
                let open = predicted < -140 || pulled < -64
                let dismiss = predicted > 140 || pulled > 64
                if open, let latest {
                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                    onOpen(latest)
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
}

private struct ClusterPreviewRow: View {
    let thread: ChatThread

    private var heat: ActivityHeat { ActivityHeat.of(thread.lastMessageAt) }

    var body: some View {
        HStack(spacing: 10) {
            Text(thread.kind.glyph)
                .font(.body)

            VStack(alignment: .leading, spacing: 2) {
                Text(thread.title)
                    .font(.control)
                    .foregroundStyle(Theme.text)
                    .lineLimit(1)

                Text("\(thread.kind.label) · \(thread.authorName)")
                    .font(.meta)
                    .foregroundStyle(Theme.faint)
                    .lineLimit(1)
            }

            Spacer(minLength: 8)

            Text(relativeTime(thread.lastMessageAt))
                .font(.meta)
                .foregroundStyle(heat == .cool ? Theme.faint : Theme.accent.opacity(heat == .hot ? 0.95 : 0.7))
        }
        .padding(.vertical, 10)
        .contentShape(Rectangle())
    }
}
