import SwiftUI
import UIKit

/// Curated chat wallpapers — local to this device, applied behind the message list.
enum ChatBackground: String, CaseIterable, Identifiable, Sendable {
    case standard
    case midnight
    case harbor
    case ember
    case dusk
    case grain

    var id: String { rawValue }

    var title: String {
        switch self {
        case .standard: "Default"
        case .midnight: "Midnight"
        case .harbor: "Harbor"
        case .ember: "Ember"
        case .dusk: "Dusk"
        case .grain: "Grain"
        }
    }

    static func from(id: String?) -> ChatBackground {
        ChatBackground(rawValue: id ?? "") ?? .standard
    }
}

enum ChatBackgroundStore {
    static let key = "maptalk.chatBackground"

    static var current: ChatBackground {
        get { ChatBackground.from(id: UserDefaults.standard.string(forKey: key)) }
        set { UserDefaults.standard.set(newValue.rawValue, forKey: key) }
    }
}

/// Fills the message area. Header and composer stay on `Theme.surface`.
struct ChatBackgroundView: View {
    let style: ChatBackground

    var body: some View {
        Group {
            switch style {
            case .standard:
                Theme.base
            case .midnight:
                LinearGradient(
                    colors: [Color(hex: 0x070B14), Color(hex: 0x121A2E)],
                    startPoint: .top,
                    endPoint: .bottom
                )
            case .harbor:
                LinearGradient(
                    colors: [Color(hex: 0x071210), Color(hex: 0x0E1F24)],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
            case .ember:
                LinearGradient(
                    colors: [Color(hex: 0x120C0A), Color(hex: 0x1C1410)],
                    startPoint: .top,
                    endPoint: .bottom
                )
            case .dusk:
                LinearGradient(
                    colors: [Color(hex: 0x0B0D18), Color(hex: 0x15182A)],
                    startPoint: .top,
                    endPoint: .bottom
                )
            case .grain:
                ZStack {
                    Theme.base
                    Canvas { context, size in
                        for y in stride(from: 0.0, through: size.height, by: 14) {
                            for x in stride(from: 0.0, through: size.width, by: 14) {
                                let ox = (Int(y / 14) % 2 == 0) ? 0.0 : 7.0
                                var path = Path()
                                path.addEllipse(in: CGRect(x: x + ox, y: y, width: 1.4, height: 1.4))
                                context.fill(path, with: .color(Theme.hairline.opacity(0.55)))
                            }
                        }
                    }
                    .allowsHitTesting(false)
                }
            }
        }
    }
}

/// Compact swatch used in the picker grid.
struct ChatBackgroundSwatch: View {
    let style: ChatBackground
    let isSelected: Bool

    var body: some View {
        VStack(spacing: 8) {
            ChatBackgroundView(style: style)
                .frame(height: 88)
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .strokeBorder(
                            isSelected ? Theme.accent : Theme.hairline,
                            lineWidth: isSelected ? 2 : 1
                        )
                }
                .overlay(alignment: .bottomTrailing) {
                    if isSelected {
                        Image(systemName: "checkmark.circle.fill")
                            .font(.system(size: 18))
                            .foregroundStyle(Theme.accent)
                            .padding(8)
                            .symbolRenderingMode(.palette)
                            .foregroundStyle(Theme.accent, Theme.surface)
                    }
                }

            Text(style.title)
                .font(.meta)
                .foregroundStyle(isSelected ? Theme.text : Theme.subtle)
        }
    }
}

struct ChatBackgroundPicker: View {
    @Binding var selection: ChatBackground
    @Environment(\.dismiss) private var dismiss

    private let columns = [
        GridItem(.flexible(), spacing: 12),
        GridItem(.flexible(), spacing: 12),
        GridItem(.flexible(), spacing: 12),
    ]

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVGrid(columns: columns, spacing: 14) {
                    ForEach(ChatBackground.allCases) { style in
                        Button {
                            selection = style
                            ChatBackgroundStore.current = style
                            UIImpactFeedbackGenerator(style: .light).impactOccurred()
                            dismiss()
                        } label: {
                            ChatBackgroundSwatch(
                                style: style,
                                isSelected: selection == style
                            )
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(16)
            }
            .background(Theme.base)
            .navigationTitle("Chat background")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                        .foregroundStyle(Theme.accent)
                }
            }
        }
    }
}
