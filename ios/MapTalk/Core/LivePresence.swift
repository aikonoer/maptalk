import SwiftUI

/// Breathing Live marker — activity as presence, not a count or ellipsis.
struct LiveDot: View {
    var size: CGFloat = 8

    @State private var breathe = false

    var body: some View {
        ZStack {
            Circle()
                .stroke(Theme.accent.opacity(0.55), lineWidth: 1.25)
                .frame(width: size, height: size)
                .scaleEffect(breathe ? 2.35 : 1)
                .opacity(breathe ? 0 : 0.9)

            Circle()
                .fill(Theme.accent)
                .frame(width: size, height: size)
                .scaleEffect(breathe ? 1.06 : 0.9)
                .opacity(breathe ? 1 : 0.7)
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
        .onAppear {
            withAnimation(.easeInOut(duration: 1.55).repeatForever(autoreverses: true)) {
                breathe = true
            }
        }
    }
}

/// Soft expanding ring behind a live map bubble. Motion = something’s happening here.
struct LiveBubbleAura<S: Shape>: View {
    let shape: S

    @State private var pulse = false

    var body: some View {
        shape
            .stroke(Theme.accent.opacity(0.5), lineWidth: 2)
            .scaleEffect(pulse ? 1.12 : 1.0, anchor: .bottomLeading)
            .opacity(pulse ? 0 : 0.7)
            .allowsHitTesting(false)
            .onAppear {
                withAnimation(.easeOut(duration: 2.0).repeatForever(autoreverses: false)) {
                    pulse = true
                }
            }
    }
}
