import PhotosUI
import SwiftUI
import UIKit

/// Headline for the map pin + chat header. Stay short so bubbles stay scannable.
private let maxTitleLength = 100
/// Optional opening post (text and/or photo). Becomes the first message in the chat.
private let maxBodyLength = 1_000

/// Starts a conversation at the point the map is centred on.
/// Title = pin headline; optional text and/or photo = first message (equal weight).
struct NewThreadSheet: View {

    let position: GeoPoint
    var onCancel: (() -> Void)? = nil
    let onCreate: (_ title: String, _ body: String, _ kind: ThreadKind, _ image: UIImage?) -> Void

    @State private var title = ""
    @State private var bodyText = ""
    @State private var kind: ThreadKind = .general
    @State private var pendingImage: UIImage?
    @State private var pickerItem: PhotosPickerItem?
    @State private var isPreparingImage = false
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
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                compactHeader

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
                .padding(.vertical, 11)
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
                .padding(.top, 14)

                Text("\(trimmedTitle.count)/\(maxTitleLength)")
                    .font(.meta)
                    .foregroundStyle(Theme.faint)
                    .opacity(trimmedTitle.count > maxTitleLength - 20 ? 1 : 0)
                    .padding(.top, 4)

                TextEditor(text: $bodyText)
                    .font(.body)
                    .foregroundStyle(Theme.text)
                    .scrollContentBackground(.hidden)
                    .focused($focusedField, equals: .body)
                    .frame(minHeight: 56, maxHeight: 88)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(Theme.raised, in: RoundedRectangle(cornerRadius: Theme.Radius.field))
                    .overlay(alignment: .topLeading) {
                        if trimmedBody.isEmpty && focusedField != .body {
                            Text("Say what’s here (optional)")
                                .font(.body)
                                .foregroundStyle(Theme.faint)
                                .padding(.horizontal, 14)
                                .padding(.vertical, 14)
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
                    .padding(.top, 10)

                mediaRow
                    .padding(.top, 10)

                Text("What kind of thing is it?")
                    .font(.meta)
                    .foregroundStyle(Theme.subtle)
                    .padding(.top, 12)

                HStack(spacing: 8) {
                    ForEach(ThreadKind.allCases, id: \.rawValue) { option in
                        KindChip(kind: option, isSelected: option == kind) {
                            withAnimation(.spring(duration: 0.25)) { kind = option }
                        }
                    }
                }
                .padding(.top, 6)

                Button(action: create) {
                    Text("Pin it and open the chat")
                        .font(.control)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .background(trimmedTitle.isEmpty ? Theme.raised : Theme.accent, in: Capsule())
                        .foregroundStyle(trimmedTitle.isEmpty ? Theme.faint : .white)
                }
                .buttonStyle(.pressable)
                .disabled(trimmedTitle.isEmpty || isPreparingImage)
                .animation(.easeOut(duration: 0.15), value: trimmedTitle.isEmpty)
                .padding(.top, 16)
                .padding(.bottom, 8)
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
            .padding(.bottom, 12)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(Theme.surface)
        // Don't auto-focus — keyboard would shove the sheet up and hide the map pin.
        .onChange(of: pickerItem) { _, item in
            guard let item else { return }
            Task { await loadPickerItem(item) }
        }
    }

    /// Title + place on two tight lines so the pin stays visible above a short sheet.
    private var compactHeader: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline) {
                Text("Start a chat")
                    .font(.cardTitle)
                    .foregroundStyle(Theme.text)
                Spacer(minLength: 8)
                if let onCancel {
                    Button("Cancel", action: onCancel)
                        .font(.control)
                        .foregroundStyle(Theme.subtle)
                }
            }
            PlaceLabelLine(
                point: position,
                trailing: "anyone nearby can join"
            )
        }
    }

    private var mediaRow: some View {
        HStack(alignment: .center, spacing: 12) {
            if let pendingImage {
                ZStack(alignment: .topTrailing) {
                    Image(uiImage: pendingImage)
                        .resizable()
                        .scaledToFill()
                        .frame(width: 72, height: 72)
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    Button {
                        self.pendingImage = nil
                        pickerItem = nil
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.system(size: 20))
                            .symbolRenderingMode(.palette)
                            .foregroundStyle(.white, Theme.faint)
                    }
                    .offset(x: 6, y: -6)
                    .accessibilityLabel("Remove photo")
                }
            } else {
                PhotosPicker(selection: $pickerItem, matching: .images, photoLibrary: .shared()) {
                    HStack(spacing: 10) {
                        if isPreparingImage {
                            ProgressView()
                                .tint(Theme.subtle)
                                .frame(width: 36, height: 36)
                        } else {
                            Image(systemName: "photo.on.rectangle")
                                .font(.system(size: 18, weight: .semibold))
                                .foregroundStyle(Theme.accent)
                                .frame(width: 36, height: 36)
                                .background(Theme.accent.opacity(0.14), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                        }
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Show what’s here")
                                .font(.control)
                                .foregroundStyle(Theme.text)
                            Text("Photo — same as writing a line")
                                .font(.meta)
                                .foregroundStyle(Theme.faint)
                        }
                        Spacer(minLength: 0)
                    }
                    .padding(12)
                    .background(Theme.raised, in: RoundedRectangle(cornerRadius: Theme.Radius.field))
                    .overlay {
                        RoundedRectangle(cornerRadius: Theme.Radius.field)
                            .strokeBorder(Theme.hairline, lineWidth: 1)
                    }
                }
                .disabled(isPreparingImage)
                .buttonStyle(.plain)
            }

            Spacer(minLength: 0)
        }
    }

    private func create() {
        guard !trimmedTitle.isEmpty else { return }
        onCreate(trimmedTitle, trimmedBody, kind, pendingImage)
    }

    private func loadPickerItem(_ item: PhotosPickerItem) async {
        isPreparingImage = true
        defer { isPreparingImage = false }
        guard let data = try? await item.loadTransferable(type: Data.self),
              let image = UIImage(data: data)
        else { return }
        pendingImage = image
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
            .padding(.vertical, 8)
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
