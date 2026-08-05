import SwiftUI

struct RootView: View {

    private let environment: AppEnvironment
    @State private var session: SessionStore

    init(environment: AppEnvironment) {
        self.environment = environment
        _session = State(initialValue: SessionStore(repository: environment.authRepository))
    }

    var body: some View {
        content
            .task { await session.start() }
    }

    @ViewBuilder
    private var content: some View {
        switch session.state {
        case .starting:
            StartupView(message: nil)
        case let .failed(message):
            StartupView(message: message)
        case .needsDisplayName:
            DisplayNameView(isSaving: session.isSavingName) { name in
                await session.saveDisplayName(name)
            }
        case let .ready(author):
            MapScreen(environment: environment, author: author)
                .task {
                    await PushRegistrar.start(push: environment.pushRepository)
                }
        }
    }
}

/// Shown for the moment it takes to get an anonymous account, or if that fails.
private struct StartupView: View {
    let message: String?

    var body: some View {
        VStack(spacing: 16) {
            AppMark()
            Text("MapTalk")
                .font(.screenTitle)
                .foregroundStyle(Theme.text)

            if let message {
                Text(message)
                    .font(.subheadline)
                    .foregroundStyle(Theme.danger)
                    .multilineTextAlignment(.center)
            } else {
                Text("Finding conversations near you\u{2026}")
                    .font(.subheadline)
                    .foregroundStyle(Theme.subtle)
            }
        }
        .padding(40)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Theme.base)
    }
}

/// The logo, such as it is: the app's own speech bubble with a map pin inside it.
struct AppMark: View {
    var size: CGFloat = 64

    var body: some View {
        Image(systemName: "mappin")
            .font(.system(size: size * 0.42, weight: .bold))
            .foregroundStyle(Theme.accent)
            .frame(width: size, height: size)
            .background(Theme.accent.opacity(0.16), in: Theme.bubble(radius: size * 0.32))
            .overlay {
                Theme.bubble(radius: size * 0.32)
                    .strokeBorder(Theme.accent.opacity(0.35), lineWidth: 1)
            }
    }
}
