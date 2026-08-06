import SwiftUI

/// Headline for the map pin + chat header. Stay short so bubbles stay scannable.
private let maxTitleLength = 100
/// Optional opening post (Reddit-style body). Becomes the first message in the chat.
private let maxBodyLength = 1_000

/// Starts a conversation at the point the map is centred on.
/// Title = pin headline; optional body = first message in the thread.
struct NewThreadSheet: View {

    let position: GeoPoint
    let onCreate: (_ title: String, _ body: String, _ kind: ThreadKind) -> Void

    @State private var title = ""
    @State private var bodyText = ""
    @State private var kind: ThreadKind = .general
    @FocusState private var focusedField: Field?

    private enum Field {
        case title
        case body
    }

    private var trimmedTitle: String {
        title.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var trimmedBody: String {
        bodyText.trimmingCharacters(in: .whitespacesAndNewlines)
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
                prompt: Text("Title — what is going on?").foregroundStyle(Theme.faint)
            )
            .font(.body)
            .foregroundStyle(Theme.text)
            .focused($focusedField, equals: .title)
            .submitLabel(.next)
            .onSubmit { focusedField = .body }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .background(Theme.raised, in: RoundedRectangle(cornerRadius: Theme.Radius.field))
            .overlay {
                RoundedRectangle(cornerRadius: Theme.Radius.field)
                    .strokeBorder(focusedField == .title ? Theme.accent : Theme.hairline, lineWidth: 1)
            }
            .onChange(of: title) { _, newValue in
                if newValue.count > maxTitleLength {
                    title = String(newValue.prefix(maxTitleLength))
                }
            }
            .padding(.top, 22)

            Text("\(trimmedTitle.count)/\(maxTitleLength)")
                .font(.meta)
                .foregroundStyle(Theme.faint)
                .opacity(trimmedTitle.count > maxTitleLength - 20 ? 1 : 0)
                .padding(.top, 6)

            TextEditor(text: $bodyText)
                .font(.body)
                .foregroundStyle(Theme.text)
                .scrollContentBackground(.hidden)
                .focused($focusedField, equals: .body)
                .frame(minHeight: 72, maxHeight: 110)
                .padding(.horizontal, 10)
                .padding(.vertical, 8)
                .background(Theme.raised, in: RoundedRectangle(cornerRadius: Theme.Radius.field))
                .overlay(alignment: .topLeading) {
                    if trimmedBody.isEmpty && focusedField != .body {
                        Text("Add more if you want (optional)")
                            .font(.body)
                            .foregroundStyle(Theme.faint)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 16)
                            .allowsHitTesting(false)
                    }
                }
                .overlay {
                    RoundedRectangle(cornerRadius: Theme.Radius.field)
                        .strokeBorder(focusedField == .body ? Theme.accent : Theme.hairline, lineWidth: 1)
                }
                .onChange(of: bodyText) { _, newValue in
                    if newValue.count > maxBodyLength {
                        bodyText = String(newValue.prefix(maxBodyLength))
                    }
                }
                .padding(.top, 12)

            Text("\(trimmedBody.count)/\(maxBodyLength)")
                .font(.meta)
                .foregroundStyle(Theme.faint)
                .opacity(trimmedBody.count > maxBodyLength - 80 ? 1 : 0)
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
                    .background(trimmedTitle.isEmpty ? Theme.raised : Theme.accent, in: Capsule())
                    .foregroundStyle(trimmedTitle.isEmpty ? Theme.faint : .white)
            }
            .buttonStyle(.pressable)
            .disabled(trimmedTitle.isEmpty)
            .animation(.easeOut(duration: 0.15), value: trimmedTitle.isEmpty)
            .padding(.top, 22)

            Spacer(minLength: 0)
        }
        .padding(24)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.surface)
        .onAppear { focusedField = .title }
    }

    private func create() {
        guard !trimmedTitle.isEmpty else { return }
        onCreate(trimmedTitle, trimmedBody, kind)
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
