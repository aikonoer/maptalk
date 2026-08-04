import Foundation

/// Where the user is in the two step sign-in: anonymous account, then a display name.
@MainActor
@Observable
final class SessionStore {

    enum State: Equatable {
        case starting
        case needsDisplayName
        case ready(Author)
        case failed(String)
    }

    private(set) var state: State = .starting
    private(set) var isSavingName = false

    private let repository: AuthRepository
    private var displayNameTask: Task<Void, Never>?

    init(repository: AuthRepository) {
        self.repository = repository
    }

    func start() async {
        do {
            try await repository.signInAnonymouslyIfNeeded()
        } catch {
            // Surface the underlying cause in Debug so a bad host/ATS/firewall is obvious.
            #if DEBUG
            let detail = (error as NSError).localizedDescription
            state = .failed("Could not reach Firebase (\(detail)). Is the Mac's emulator running, and can this phone reach it?")
            #else
            state = .failed(
                "Could not reach Firebase. Check the connection and the app's Firebase configuration, then reopen MapTalk."
            )
            #endif
            return
        }
        guard let uid = repository.currentUid else {
            state = .failed("Signed in but no account came back. Try reopening MapTalk.")
            return
        }
        guard displayNameTask == nil else { return }
        displayNameTask = Task { [repository] in
            for await name in repository.displayName(uid: uid) {
                guard let name, !name.isEmpty else {
                    state = .needsDisplayName
                    continue
                }
                state = .ready(Author(uid: uid, displayName: name))
            }
        }
    }

    func saveDisplayName(_ name: String) async {
        isSavingName = true
        try? await repository.saveDisplayName(name)
        isSavingName = false
    }
}
