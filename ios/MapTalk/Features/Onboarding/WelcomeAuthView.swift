import SwiftUI

/// First-impression gate before display name: brand + curiosity, then Apple or guest.
/// Anonymous Firebase already exists underneath; Apple is optional “save this account.”
struct WelcomeAuthView: View {

    let allowsApple: Bool
    let isBusy: Bool
    let errorMessage: String?
    let onContinueAsGuest: () -> Void
    let onContinueWithApple: () -> Void

    @State private var appeared = false
    @State private var pulse = false

    var body: some View {
        ZStack {
            atmosphere

            VStack(spacing: 0) {
                Spacer(minLength: 0)

                VStack(spacing: 18) {
                    AppMark(size: 76)
                        .scaleEffect(appeared ? 1 : 0.84)
                        .opacity(appeared ? 1 : 0)
                        .scaleEffect(pulse ? 1.04 : 1)
                        .animation(
                            .easeInOut(duration: 2.4).repeatForever(autoreverses: true),
                            value: pulse
                        )

                    Text("MapTalk")
                        .font(.system(size: 46, weight: .bold, design: .rounded))
                        .foregroundStyle(Theme.text)
                        .opacity(appeared ? 1 : 0)
                        .offset(y: appeared ? 0 : 12)

                    Text("Chats pinned to places")
                        .font(.system(.title3, design: .rounded).weight(.semibold))
                        .foregroundStyle(Theme.subtle)
                        .multilineTextAlignment(.center)
                        .opacity(appeared ? 1 : 0)
                        .offset(y: appeared ? 0 : 14)

                    Text("What’s happening on this corner right now?")
                        .font(.subheadline)
                        .foregroundStyle(Theme.faint)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 12)
                        .opacity(appeared ? 1 : 0)
                        .offset(y: appeared ? 0 : 16)
                }
                .padding(.horizontal, 28)

                Spacer(minLength: 0)

                VStack(spacing: 12) {
                    if let errorMessage {
                        Text(errorMessage)
                            .font(.meta)
                            .foregroundStyle(Theme.danger)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 8)
                    }

                    if allowsApple {
                        Button(action: onContinueWithApple) {
                            HStack(spacing: 10) {
                                if isBusy {
                                    ProgressView().tint(.black)
                                } else {
                                    Image(systemName: "apple.logo")
                                        .font(.system(size: 17, weight: .semibold))
                                }
                                Text(isBusy ? "Connecting\u{2026}" : "Continue with Apple")
                                    .font(.control)
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 15)
                            .foregroundStyle(.black)
                            .background(Color.white, in: Capsule())
                        }
                        .buttonStyle(.pressable)
                        .disabled(isBusy)
                        .accessibilityLabel("Continue with Apple")
                    }

                    Button(action: onContinueAsGuest) {
                        Text(allowsApple ? "Explore without an account" : "Start exploring")
                            .font(.control)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 15)
                            .foregroundStyle(Theme.text)
                            .background(Theme.raised, in: Capsule())
                            .overlay {
                                Capsule().strokeBorder(Theme.hairline, lineWidth: 1)
                            }
                    }
                    .buttonStyle(.pressable)
                    .disabled(isBusy)

                    Text(
                        allowsApple
                            ? "Save your place on the map — or peek first. You can link Apple later."
                            : "Local demo — Apple Sign In shows up on a Live build."
                    )
                    .font(.meta)
                    .foregroundStyle(Theme.faint)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 12)
                    .padding(.top, 4)
                }
                .padding(.horizontal, 24)
                .padding(.bottom, 36)
                .opacity(appeared ? 1 : 0)
                .offset(y: appeared ? 0 : 20)
            }
        }
        .onAppear {
            withAnimation(.spring(duration: 0.72, bounce: 0.2)) {
                appeared = true
            }
            pulse = true
        }
    }

    private var atmosphere: some View {
        ZStack {
            Theme.base
            RadialGradient(
                colors: [
                    Theme.accent.opacity(0.24),
                    Theme.accent.opacity(0.07),
                    .clear,
                ],
                center: .init(x: 0.5, y: 0.26),
                startRadius: 16,
                endRadius: 360
            )
            RadialGradient(
                colors: [
                    Color(hex: 0x38BDF8).opacity(0.12),
                    .clear,
                ],
                center: .init(x: 0.12, y: 0.78),
                startRadius: 8,
                endRadius: 280
            )
            // Soft grid hint — place, not a dashboard.
            Canvas { context, size in
                let step: CGFloat = 28
                var path = Path()
                stride(from: 0, through: size.width, by: step).forEach { x in
                    path.move(to: CGPoint(x: x, y: 0))
                    path.addLine(to: CGPoint(x: x, y: size.height))
                }
                stride(from: 0, through: size.height, by: step).forEach { y in
                    path.move(to: CGPoint(x: 0, y: y))
                    path.addLine(to: CGPoint(x: size.width, y: y))
                }
                context.stroke(path, with: .color(Theme.hairline.opacity(0.35)), lineWidth: 0.5)
            }
            .opacity(appeared ? 0.5 : 0)
            .animation(.easeOut(duration: 1.1), value: appeared)
            .allowsHitTesting(false)
        }
        .ignoresSafeArea()
    }
}
