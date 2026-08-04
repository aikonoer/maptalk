import FirebaseAuth
import FirebaseFirestore
import Foundation

/// v1 has no real accounts. Everyone gets an anonymous Firebase account, and the display name
/// they pick on first launch is stamped onto the threads and messages they write.
///
/// In local demo mode there is no Firebase at all — the display name is kept on device.
@MainActor
final class AuthRepository {

    static let maxDisplayNameLength = 24

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
}
