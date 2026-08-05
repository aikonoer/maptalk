import SwiftUI

struct SettingsScreen: View {
    private let author: Author
    @State private var model: SettingsModel
    @Environment(\.dismiss) private var dismiss

    init(environment: AppEnvironment, author: Author) {
        self.author = author
        _model = State(
            initialValue: SettingsModel(
                safety: environment.safetyRepository,
                auth: environment.authRepository,
                author: author
            )
        )
    }

    var body: some View {
        List {
            Section {
                HStack(spacing: 12) {
                    InitialAvatar(name: author.displayName, seed: author.uid, size: 40)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(author.displayName)
                            .font(.cardTitle)
                            .foregroundStyle(Theme.text)
                        Text(model.providerLabel)
                            .font(.meta)
                            .foregroundStyle(Theme.faint)
                    }
                }
                .listRowBackground(Theme.raised)

                if model.isAnonymous {
                    Button {
                        Task { await model.linkWithApple() }
                    } label: {
                        HStack {
                            Image(systemName: "apple.logo")
                            Text(model.isLinking ? "Connecting…" : "Continue with Apple")
                            Spacer()
                        }
                        .font(.body)
                        .foregroundStyle(Theme.text)
                    }
                    .disabled(model.isLinking)
                    .listRowBackground(Theme.raised)
                }
            } footer: {
                if model.isAnonymous {
                    Text("Save this account with Apple so your chats stick if you reinstall.")
                        .foregroundStyle(Theme.faint)
                }
            }

            if let message = model.linkMessage {
                Section {
                    Text(message)
                        .font(.subheadline)
                        .foregroundStyle(
                            model.linkIsError ? Theme.danger : Theme.subtle
                        )
                        .listRowBackground(Theme.raised)
                }
            }

            Section {
                if model.isLoading {
                    HStack {
                        Spacer()
                        ProgressView().tint(Theme.subtle)
                        Spacer()
                    }
                    .listRowBackground(Theme.raised)
                } else if model.blocked.isEmpty {
                    Text("Nobody blocked yet. Long-press a message to block someone.")
                        .font(.subheadline)
                        .foregroundStyle(Theme.subtle)
                        .listRowBackground(Theme.raised)
                } else {
                    ForEach(model.blocked) { person in
                        HStack {
                            InitialAvatar(name: person.displayName, seed: person.uid, size: 32)
                            Text(person.displayName)
                                .font(.body)
                                .foregroundStyle(Theme.text)
                            Spacer()
                            Button("Unblock") {
                                model.unblock(person)
                            }
                            .font(.meta)
                            .foregroundStyle(Theme.accent)
                        }
                        .listRowBackground(Theme.raised)
                    }
                }
            } header: {
                Text("Blocked people")
                    .foregroundStyle(Theme.subtle)
            } footer: {
                Text("Blocked authors’ chats and messages stay hidden for you only.")
                    .foregroundStyle(Theme.faint)
            }
        }
        .scrollContentBackground(.hidden)
        .background(Theme.base)
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(Theme.surface, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Done") { dismiss() }
                    .foregroundStyle(Theme.accent)
            }
        }
        .onAppear { model.start() }
        .onDisappear { model.stop() }
    }
}

@MainActor
@Observable
final class SettingsModel {
    private(set) var blocked: [BlockedPerson] = []
    private(set) var isLoading = true
    private(set) var providerLabel: String
    private(set) var isAnonymous: Bool
    private(set) var isLinking = false
    private(set) var linkMessage: String?
    private(set) var linkIsError = false

    private let safety: SafetyRepository
    private let auth: AuthRepository
    private let author: Author
    private let apple = AppleSignInCoordinator()
    private var task: Task<Void, Never>?

    init(safety: SafetyRepository, auth: AuthRepository, author: Author) {
        self.safety = safety
        self.auth = auth
        self.author = author
        providerLabel = auth.providerLabel
        isAnonymous = auth.isAnonymous
    }

    func start() {
        refreshIdentity()
        guard task == nil else { return }
        task = Task {
            for await people in safety.blockedPeople() {
                blocked = people
                isLoading = false
            }
        }
    }

    func stop() {
        task?.cancel()
        task = nil
    }

    func unblock(_ person: BlockedPerson) {
        safety.unblock(uid: person.uid, as: author)
    }

    func linkWithApple() async {
        guard !isLinking else { return }
        isLinking = true
        linkMessage = nil
        do {
            let result = try await apple.signIn()
            try await auth.linkWithApple(idToken: result.idToken, rawNonce: result.rawNonce)
            refreshIdentity()
            linkIsError = false
            linkMessage = "Account saved with Apple."
        } catch let error as AuthRepository.LinkError where error == .cancelled {
            linkMessage = nil
        } catch {
            linkIsError = true
            linkMessage = error.localizedDescription
        }
        isLinking = false
    }

    private func refreshIdentity() {
        providerLabel = auth.providerLabel
        isAnonymous = auth.isAnonymous
    }
}

extension AuthRepository.LinkError: Equatable {
    static func == (lhs: Self, rhs: Self) -> Bool {
        switch (lhs, rhs) {
        case (.notSignedIn, .notSignedIn),
             (.alreadyLinked, .alreadyLinked),
             (.credentialInUse, .credentialInUse),
             (.cancelled, .cancelled):
            true
        case let (.failed(a), .failed(b)):
            a == b
        default:
            false
        }
    }
}
