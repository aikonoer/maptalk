import SwiftUI

/// First launch. There are no accounts in v1, so all we need is the name that will sit on the
/// threads and replies this person writes.
struct DisplayNameView: View {

    let isSaving: Bool
    let onSubmit: (String) async -> Void

    @State private var name = ""
    @FocusState private var isFocused: Bool

    private var trimmed: String {
        name.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var canSubmit: Bool { !trimmed.isEmpty && !isSaving }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Spacer(minLength: 0)

            AppMark(size: 56)
                .padding(.bottom, 24)

            Text("What should people call you?")
                .font(.screenTitle)
                .foregroundStyle(Theme.text)

            Text("This sits next to everything you post. Be a person, a shop, or a restaurant.")
                .font(.subheadline)
                .foregroundStyle(Theme.subtle)
                .padding(.top, 8)

            HStack(spacing: 10) {
                if trimmed.isEmpty {
                    Image(systemName: "person")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(Theme.faint)
                        .frame(width: 32, height: 32)
                } else {
                    InitialAvatar(name: trimmed, seed: trimmed)
                        .transition(.scale.combined(with: .opacity))
                }

                TextField("", text: $name, prompt: Text("Display name").foregroundStyle(Theme.faint))
                    .font(.body)
                    .foregroundStyle(Theme.text)
                    .textInputAutocapitalization(.words)
                    .submitLabel(.done)
                    .focused($isFocused)
                    .onSubmit(submit)
                    .onChange(of: name) { _, newValue in
                        if newValue.count > AuthRepository.maxDisplayNameLength {
                            name = String(newValue.prefix(AuthRepository.maxDisplayNameLength))
                        }
                    }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(Theme.raised, in: RoundedRectangle(cornerRadius: Theme.Radius.field))
            .overlay {
                RoundedRectangle(cornerRadius: Theme.Radius.field)
                    .strokeBorder(isFocused ? Theme.accent : Theme.hairline, lineWidth: 1)
            }
            .animation(.easeOut(duration: 0.15), value: isFocused)
            .animation(.spring(duration: 0.25), value: trimmed.isEmpty)
            .padding(.top, 28)

            // Only worth showing once the limit is in sight.
            Text("\(trimmed.count)/\(AuthRepository.maxDisplayNameLength)")
                .font(.meta)
                .foregroundStyle(Theme.faint)
                .opacity(trimmed.count > AuthRepository.maxDisplayNameLength - 8 ? 1 : 0)
                .padding(.top, 6)

            Button(action: submit) {
                HStack(spacing: 8) {
                    if isSaving {
                        ProgressView().controlSize(.small).tint(.white)
                    }
                    Text(isSaving ? "Getting you in\u{2026}" : "Start talking")
                }
                .font(.control)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 15)
                .background(canSubmit ? Theme.accent : Theme.raised, in: Capsule())
                .foregroundStyle(canSubmit ? .white : Theme.faint)
            }
            .disabled(!canSubmit)
            .animation(.easeOut(duration: 0.15), value: canSubmit)
            .padding(.top, 20)

            Spacer(minLength: 0)
        }
        .padding(28)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Theme.base)
        .onAppear { isFocused = true }
    }

    private func submit() {
        guard canSubmit else { return }
        let value = trimmed
        Task { await onSubmit(value) }
    }
}
