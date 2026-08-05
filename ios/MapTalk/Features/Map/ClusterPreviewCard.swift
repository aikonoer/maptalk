import SwiftUI

/// Peek card for a clustered map bubble — lists the chats in that cell.
/// Tap a row to open it, or swipe up to open the most recent.
struct ClusterPreviewCard: View {
    let threads: [ChatThread]
    let onOpen: (ChatThread) -> Void

    @State private var dragOffset: CGFloat = 0

    private var sorted: [ChatThread] {
        threads.sorted {
            ($0.lastMessageAt ?? .distantPast) > ($1.lastMessageAt ?? .distantPast)
        }
    }

    private var visible: [ChatThread] { Array(sorted.prefix(5)) }
    private var overflow: Int { max(0, sorted.count - visible.count) }
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

            if overflow > 0 {
                Text("+\(overflow) more — tap bubble to zoom · swipe up for latest")
                    .font(.meta)
                    .foregroundStyle(Theme.faint)
            } else {
                Text("Tap a chat · swipe up for latest")
                    .font(.meta)
                    .foregroundStyle(Theme.faint)
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
        .opacity(Double(1 + dragOffset / 280).clamped(to: 0.55...1))
        .gesture(swipeUpToOpenLatest)
    }

    private var swipeUpToOpenLatest: some Gesture {
        DragGesture(minimumDistance: 12, coordinateSpace: .local)
            .onChanged { value in
                dragOffset = min(0, value.translation.height)
            }
            .onEnded { value in
                let flung = value.predictedEndTranslation.height < -140
                let pulled = value.translation.height < -64
                if (flung || pulled), let latest {
                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                    onOpen(latest)
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

    private var live: Bool { LiveNow.isLive(thread.lastMessageAt) }

    var body: some View {
        HStack(spacing: 10) {
            Text(thread.kind.glyph)
                .font(.body)

            VStack(alignment: .leading, spacing: 2) {
                Text(thread.title)
                    .font(.control)
                    .foregroundStyle(Theme.text)
                    .lineLimit(1)

                Text(
                    [
                        live ? "Live" : nil,
                        thread.kind.label,
                        thread.authorName,
                    ]
                    .compactMap { $0 }
                    .joined(separator: " · ")
                )
                .font(.meta)
                .foregroundStyle(live ? Theme.accent : Theme.faint)
                .lineLimit(1)
            }

            Spacer(minLength: 8)

            Text(live ? "Live" : relativeTime(thread.lastMessageAt))
                .font(.meta)
                .foregroundStyle(live ? Theme.accent : Theme.faint)
        }
        .padding(.vertical, 10)
        .contentShape(Rectangle())
    }
}
