import Foundation
import Observation

/// Anonymous bootstrap → optional welcome (Apple or guest) → display name → map.
@MainActor
@Observable
final class SessionStore {

    enum State: Equatable {
        case starting
        case needsAuthChoice
        case needsDisplayName
        case ready(Author)
        case failed(String)
    }

    private(set) var state: State = .starting
    private(set) var isSavingName = false
    private(set) var isAuthBusy = false
    private(set) var authError: String?

    private let repository: AuthRepository
    private let apple = AppleSignInCoordinator()
    private var displayNameTask: Task<Void, Never>?
    private let authChoiceKey = "maptalk.didChooseAuthPath"

    init(repository: AuthRepository) {
        self.repository = repository
    }

    var allowsAppleSignIn: Bool { repository.allowsAppleSignIn }

    func start() async {
        do {
            try await repository.signInAnonymouslyIfNeeded()
        } catch {
            #if DEBUG
            let detail = (error as NSError).localizedDescription
            state = .failed(
                "Could not reach Firebase (\(detail)). Is the Mac's emulator running, and can this phone reach it?"
            )
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
                    if UserDefaults.standard.bool(forKey: authChoiceKey) {
                        state = .needsDisplayName
                    } else {
                        state = .needsAuthChoice
                    }
                    continue
                }
                state = .ready(Author(uid: uid, displayName: name))
            }
        }
    }

    /// After sign-out or delete — tear down listeners and bootstrap again.
    func restart() async {
        displayNameTask?.cancel()
        displayNameTask = nil
        state = .starting
        authError = nil
        isAuthBusy = false
        await start()
    }

    func continueAsGuest() {
        authError = nil
        markAuthPathChosen()
        state = .needsDisplayName
    }

    func continueWithApple() async {
        guard repository.allowsAppleSignIn else {
            authError = "Apple Sign In isn’t available in local demo. Explore without an account, or switch to Live."
            return
        }
        guard !isAuthBusy else { return }
        isAuthBusy = true
        authError = nil
        defer { isAuthBusy = false }
        do {
            let result = try await apple.signIn()
            try await repository.linkWithApple(idToken: result.idToken, rawNonce: result.rawNonce)
            markAuthPathChosen()
            state = .needsDisplayName
        } catch let error as AuthRepository.LinkError where error == .cancelled {
            // User dismissed the sheet.
        } catch {
            authError = error.localizedDescription
        }
    }

    func saveDisplayName(_ name: String) async {
        isSavingName = true
        try? await repository.saveDisplayName(name)
        isSavingName = false
    }

    private func markAuthPathChosen() {
        UserDefaults.standard.set(true, forKey: authChoiceKey)
    }
}
