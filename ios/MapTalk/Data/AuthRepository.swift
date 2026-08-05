import CryptoKit
import FirebaseAuth
import FirebaseFirestore
import Foundation

/// Anonymous bootstrap, then optional link to Apple (or later Google) so the uid stays put.
///
/// In local demo mode there is no Firebase at all — the display name is kept on device.
@MainActor
final class AuthRepository {

    static let maxDisplayNameLength = 24

    enum LinkError: LocalizedError {
        case notSignedIn
        case alreadyLinked
        case credentialInUse
        case cancelled
        case failed(String)

        var errorDescription: String? {
            switch self {
            case .notSignedIn: "You’re not signed in yet."
            case .alreadyLinked: "This account is already saved."
            case .credentialInUse:
                "That Apple ID is already linked to another MapTalk account."
            case .cancelled: "Sign in was cancelled."
            case let .failed(message): message
            }
        }
    }

    private enum Backend {
        case firebase(Auth, Firestore)
        case local(LocalDemoStore)
    }

    private let backend: Backend

    init(auth: Auth, firestore: Firestore) {
        backend = .firebase(auth, firestore)
    }

    init(local: LocalDemoStore) {
        backend = .local(local)
    }

    var currentUid: String? {
        switch backend {
        case let .firebase(auth, _): auth.currentUser?.uid
        case let .local(store): store.uid
        }
    }

    var isAnonymous: Bool {
        switch backend {
        case let .firebase(auth, _):
            auth.currentUser?.isAnonymous ?? true
        case .local:
            true
        }
    }

    /// Short label for Settings: "Signed in anonymously" / "Signed in with Apple" / …
    var providerLabel: String {
        switch backend {
        case let .firebase(auth, _):
            guard let user = auth.currentUser else { return "Signed out" }
            if user.isAnonymous { return "Signed in anonymously" }
            let ids = Set(user.providerData.map(\.providerID))
            if ids.contains("apple.com") { return "Signed in with Apple" }
            if ids.contains("google.com") { return "Signed in with Google" }
            return "Signed in"
        case .local:
            return "Local demo"
        }
    }

    func signInAnonymouslyIfNeeded() async throws {
        switch backend {
        case let .firebase(auth, _):
            guard auth.currentUser == nil else { return }
            _ = try await auth.signInAnonymously()
        case let .local(store):
            store.ensureSignedIn()
        }
    }

    /// Emits the stored display name, and nil while the user still has to choose one.
    func displayName(uid: String) -> AsyncStream<String?> {
        switch backend {
        case let .firebase(_, firestore):
            let reference = firestore.collection(Fs.users).document(uid)
            let listeners = ListenerBag()
            return AsyncStream { continuation in
                listeners.add(
                    reference.addSnapshotListener { snapshot, _ in
                        continuation.yield(snapshot?[Fs.displayName] as? String)
                    }
                )
                continuation.onTermination = { _ in listeners.removeAll() }
            }
        case let .local(store):
            return store.displayNameStream()
        }
    }

    func saveDisplayName(_ name: String) async throws {
        switch backend {
        case let .firebase(auth, firestore):
            guard let uid = auth.currentUser?.uid else { return }
            try await firestore.collection(Fs.users).document(uid).setData(
                [
                    Fs.displayName: name.trimmingCharacters(in: .whitespacesAndNewlines),
                    Fs.createdAt: FieldValue.serverTimestamp(),
                ],
                merge: true
            )
        case let .local(store):
            store.saveDisplayName(name)
        }
    }

    /// Links the current anonymous user to Apple. Same uid afterwards.
    func linkWithApple(idToken: String, rawNonce: String) async throws {
        switch backend {
        case let .firebase(auth, _):
            guard let user = auth.currentUser else { throw LinkError.notSignedIn }
            if !user.isAnonymous { throw LinkError.alreadyLinked }
            let credential = OAuthProvider.appleCredential(
                withIDToken: idToken,
                rawNonce: rawNonce,
                fullName: nil
            )
            do {
                _ = try await user.link(with: credential)
            } catch let error as NSError {
                throw mapLinkError(error)
            }
        case .local:
            throw LinkError.failed("Account linking isn’t available in local demo.")
        }
    }

    private func mapLinkError(_ error: NSError) -> LinkError {
        let code = AuthErrorCode(rawValue: error.code)
        switch code {
        case .credentialAlreadyInUse, .emailAlreadyInUse:
            return .credentialInUse
        case .providerAlreadyLinked:
            return .alreadyLinked
        default:
            return .failed(error.localizedDescription)
        }
    }
}

enum AppleNonce {
    static func random() -> String {
        let length = 32
        var bytes = [UInt8](repeating: 0, count: length)
        _ = SecRandomCopyBytes(kSecRandomDefault, length, &bytes)
        return Data(bytes).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    static func sha256(_ input: String) -> String {
        let data = Data(input.utf8)
        let hash = SHA256.hash(data: data)
        return hash.map { String(format: "%02x", $0) }.joined()
    }
}
