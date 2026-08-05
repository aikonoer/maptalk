import SwiftUI

struct SettingsScreen: View {
    private let author: Author
    @State private var model: SettingsModel
    @Environment(\.dismiss) private var dismiss

    init(environment: AppEnvironment, author: Author) {
        self.author = author
        _model = State(
            initialValue: SettingsModel(safety: environment.safetyRepository, author: author)
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
                        Text("Signed in anonymously")
                            .font(.meta)
                            .foregroundStyle(Theme.faint)
                    }
                }
                .listRowBackground(Theme.raised)
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

    private let safety: SafetyRepository
    private let author: Author
    private var task: Task<Void, Never>?

    init(safety: SafetyRepository, author: Author) {
        self.safety = safety
        self.author = author
    }

    func start() {
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
}
