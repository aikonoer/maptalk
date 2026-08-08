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

/// Soft pulse ring for hot bubbles.
struct LiveBubbleAura<S: Shape>: View {
    let shape: S
    var intensity: Double = 1

    @State private var pulse = false

    var body: some View {
        shape
            .stroke(Theme.accent.opacity(0.45 * intensity), lineWidth: 2)
            .scaleEffect(pulse ? 1.14 : 1.0, anchor: .center)
            .opacity(pulse ? 0 : 0.65 * intensity)
            .allowsHitTesting(false)
            .onAppear {
                withAnimation(.easeOut(duration: 2.0).repeatForever(autoreverses: false)) {
                    pulse = true
                }
            }
    }
}

/// Accent halo behind warm/hot bubbles (fill + scale — reads along the pill).
struct ActivityRingGlow<S: Shape>: View {
    let shape: S
    let heat: ActivityHeat

    var body: some View {
        if heat != .cool {
            shape
                .fill(Theme.accent.opacity(heat == .hot ? 0.22 : 0.12))
                .blur(radius: heat == .hot ? 10 : 7)
                .scaleEffect(heat == .hot ? 1.08 : 1.05, anchor: .center)
                .allowsHitTesting(false)
        }
    }
}
