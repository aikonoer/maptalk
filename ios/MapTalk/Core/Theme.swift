import SwiftUI
import UIKit

/// Dark is the only theme. Everything sits on one near-black ramp so the accent and the four
/// thread colours are the only things that pull the eye — which matters on a screen where the
/// map is already busy.
enum Theme {

    static let base = Color(hex: 0x0B0D12)
    static let surface = Color(hex: 0x14171F)
    static let raised = Color(hex: 0x1E222D)
    static let hairline = Color(hex: 0x2A2F3D)

    static let text = Color(hex: 0xEDEFF5)
    static let subtle = Color(hex: 0x9AA1B5)
    static let faint = Color(hex: 0x6B7285)

    static let accent = Color(hex: 0x6366F1)
    static let danger = Color(hex: 0xFB7185)

    /// Three radii, one per kind of thing: a speech bubble, a card, an input.
    enum Radius {
        static let bubble: CGFloat = 18
        static let card: CGFloat = 16
        static let field: CGFloat = 12
    }

    /// A rounded rectangle with one corner pulled in, so whatever wears it reads as something
    /// being said: the map markers, the app mark, the chat rows.
    /// A `nil` tail gives a plain rounded rectangle, which is what the middle of a run of
    /// messages from one person wants. Smaller `tailRadius` = sharper point.
    static func bubble(
        radius: CGFloat = Radius.card,
        tail: Tail? = .bottomLeading,
        tailRadius: CGFloat = 4
    ) -> UnevenRoundedRectangle {
        UnevenRoundedRectangle(
            cornerRadii: RectangleCornerRadii(
                topLeading: radius,
                bottomLeading: tail == .bottomLeading ? tailRadius : radius,
                bottomTrailing: tail == .bottomTrailing ? tailRadius : radius,
                topTrailing: radius
            ),
            style: .continuous
        )
    }

    enum Tail {
        case bottomLeading
        case bottomTrailing
    }
}

/// Rounded type for chrome and labels, the default face for message text where readability at
/// small sizes matters more than character.
extension Font {
    static let screenTitle = Font.system(.title2, design: .rounded).weight(.bold)
    static let cardTitle = Font.system(.headline, design: .rounded)
    static let control = Font.system(.subheadline, design: .rounded).weight(.semibold)
    static let markerTitle = Font.system(.footnote, design: .rounded).weight(.semibold)
    static let meta = Font.system(.caption2, design: .rounded).weight(.medium)
}

extension ThreadKind {
    /// One colour per kind, used for the marker's dot and its chip. The glyph carries the
    /// meaning; the colour just makes a busy map scannable.
    var tint: Color {
        switch self {
        case .event: Color(hex: 0xC084FC)
        case .notice: Color(hex: 0xFBBF24)
        case .traffic: Color(hex: 0xFB7185)
        case .general: Color(hex: 0x38BDF8)
        }
    }
}

/// A name reduced to its initials on a coloured disc, so a stranger's replies are easy to tell
/// apart in a public thread. Optional `photoURL` replaces initials when set.
struct InitialAvatar: View {

    let name: String
    let seed: String
    var size: CGFloat = 32
    var photoURL: String? = nil

    private static let tints = [
        0xF87171, 0xFB923C, 0xFBBF24, 0x4ADE80,
        0x2DD4BF, 0x38BDF8, 0x818CF8, 0xF472B6,
    ].map { Color(hex: $0) }

    /// Exposed so a name can be written in the same colour as the face beside it.
    static func tint(for seed: String) -> Color {
        tints[slot(for: seed, count: tints.count)]
    }

    var body: some View {
        let tint = Self.tint(for: seed)
        ZStack {
            Circle()
                .fill(tint.opacity(0.18))
            if let photoURL, let image = resolvedLocalPhoto(photoURL) {
                image
                    .resizable()
                    .scaledToFill()
            } else if let photoURL, photoURL.hasPrefix("http"), let remote = URL(string: photoURL) {
                AsyncImage(url: remote) { phase in
                    switch phase {
                    case let .success(image):
                        image.resizable().scaledToFill()
                    default:
                        initialsLabel(tint: tint)
                    }
                }
            } else {
                initialsLabel(tint: tint)
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
        .overlay { Circle().strokeBorder(tint.opacity(0.35), lineWidth: 1) }
    }

    private func initialsLabel(tint: Color) -> some View {
        Text(initials)
            .font(.system(size: size * 0.4, weight: .bold, design: .rounded))
            .foregroundStyle(tint)
    }

    private func resolvedLocalPhoto(_ path: String) -> Image? {
        guard !path.hasPrefix("http") else { return nil }
        let url = LocalMediaStore.url(forRelativePath: path)
        guard let ui = UIImage(contentsOfFile: url.path) else { return nil }
        return Image(uiImage: ui)
    }

    private var initials: String {
        let words = name.split(separator: " ").prefix(2)
        let letters = words.compactMap { $0.first }.map(String.init).joined()
        return letters.isEmpty ? "?" : letters.uppercased()
    }

    /// Deliberately not `hashValue`, which is seeded per process: the same person should keep
    /// the same colour across launches.
    private static func slot(for seed: String, count: Int) -> Int {
        seed.utf8.reduce(0) { ($0 &* 31 &+ Int($1)) % 1_000_003 } % count
    }
}

/// A little give when pressed, for the actions worth making feel physical.
struct PressableButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.96 : 1)
            .opacity(configuration.isPressed ? 0.92 : 1)
            .animation(.spring(duration: 0.2), value: configuration.isPressed)
    }
}

extension ButtonStyle where Self == PressableButtonStyle {
    static var pressable: PressableButtonStyle { PressableButtonStyle() }
}

extension Color {
    init(hex: UInt32) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255
        )
    }
}
