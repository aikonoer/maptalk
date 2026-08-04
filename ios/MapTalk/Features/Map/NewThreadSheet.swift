import SwiftUI

private let maxTitleLength = 80

/// Starts a conversation at the point the map is centred on. The title is the whole thread for
/// now; the first reply comes right after in the chat screen.
struct NewThreadSheet: View {

    let position: GeoPoint
    let onCreate: (String, ThreadKind) -> Void

    @State private var title = ""
    @State private var kind: ThreadKind = .general
    @FocusState private var isTyping: Bool

    private var trimmed: String {
        title.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Start a chat here")
                .font(.screenTitle)
                .foregroundStyle(Theme.text)

            HStack(spacing: 6) {
                Image(systemName: "mappin.and.ellipse")
                    .font(.system(size: 11, weight: .semibold))
                Text(String(format: "%.4f, %.4f", position.lat, position.lng))
                Text("\u{00b7} anyone looking here can join in")
                    .foregroundStyle(Theme.faint)
            }
            .font(.meta)
            .foregroundStyle(Theme.subtle)
            .padding(.top, 6)

            TextField(
                "",
                text: $title,
                prompt: Text("What is going on?").foregroundStyle(Theme.faint)
            )
            .font(.body)
            .foregroundStyle(Theme.text)
            .focused($isTyping)
            .submitLabel(.done)
            .onSubmit(create)
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .background(Theme.raised, in: RoundedRectangle(cornerRadius: Theme.Radius.field))
            .overlay {
                RoundedRectangle(cornerRadius: Theme.Radius.field)
                    .strokeBorder(isTyping ? Theme.accent : Theme.hairline, lineWidth: 1)
            }
            .animation(.easeOut(duration: 0.15), value: isTyping)
            .onChange(of: title) { _, newValue in
                if newValue.count > maxTitleLength {
                    title = String(newValue.prefix(maxTitleLength))
                }
            }
            .padding(.top, 22)

            Text("\(trimmed.count)/\(maxTitleLength)")
                .font(.meta)
                .foregroundStyle(Theme.faint)
                .opacity(trimmed.count > maxTitleLength - 20 ? 1 : 0)
                .padding(.top, 6)

            Text("What kind of thing is it?")
                .font(.meta)
                .foregroundStyle(Theme.subtle)
                .padding(.top, 14)

            HStack(spacing: 8) {
                ForEach(ThreadKind.allCases, id: \.rawValue) { option in
                    KindChip(kind: option, isSelected: option == kind) {
                        withAnimation(.spring(duration: 0.25)) { kind = option }
                    }
                }
            }
            .padding(.top, 8)

            Button(action: create) {
                Text("Pin it and open the chat")
                    .font(.control)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 15)
                    .background(trimmed.isEmpty ? Theme.raised : Theme.accent, in: Capsule())
                    .foregroundStyle(trimmed.isEmpty ? Theme.faint : .white)
            }
            .buttonStyle(.pressable)
            .disabled(trimmed.isEmpty)
            .animation(.easeOut(duration: 0.15), value: trimmed.isEmpty)
            .padding(.top, 22)

            Spacer(minLength: 0)
        }
        .padding(24)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.surface)
        .onAppear { isTyping = true }
    }

    private func create() {
        guard !trimmed.isEmpty else { return }
        onCreate(trimmed, kind)
    }
}

private struct KindChip: View {

    let kind: ThreadKind
    let isSelected: Bool
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            VStack(spacing: 4) {
                Text(kind.glyph).font(.system(size: 16))
                Text(kind.label)
                    .font(.meta)
                    .foregroundStyle(isSelected ? kind.tint : Theme.subtle)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 10)
            .background(
                isSelected ? kind.tint.opacity(0.16) : Theme.raised,
                in: RoundedRectangle(cornerRadius: Theme.Radius.field)
            )
            .overlay {
                RoundedRectangle(cornerRadius: Theme.Radius.field)
                    .strokeBorder(isSelected ? kind.tint.opacity(0.5) : .clear, lineWidth: 1)
            }
        }
        .buttonStyle(.pressable)
    }
}
