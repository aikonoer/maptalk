import CryptoKit
import FirebaseAuth
import FirebaseFirestore
import FirebaseStorage
import Foundation
import UIKit

/// Anonymous bootstrap, then optional link to Apple (or later Google) so the uid stays put.
///
/// In local demo mode there is no Firebase at all — the display name is kept on device.
@MainActor
final class AuthRepository {

    static let maxDisplayNameLength = 24
    /// Avatar JPEG edge length — enough for chat + account, small for Storage.
    static let avatarMaxEdge: CGFloat = 512

    enum LinkError: LocalizedError {
        case notSignedIn
        case alreadyLinked
        case credentialInUse
        case cancelled
        case requiresRecentLogin
        case failed(String)

        var errorDescription: String? {
            switch self {
            case .notSignedIn: "You’re not signed in yet."
            case .alreadyLinked: "This account is already saved."
            case .credentialInUse:
                "That Apple ID is already linked to another MapTalk account."
            case .cancelled: "Sign in was cancelled."
            case .requiresRecentLogin:
                "Confirm with Apple once more to delete this account."
            case let .failed(message): message
            }
        }
    }

    private enum Backend {
        case firebase(Auth, Firestore, Storage)
        case local(LocalDemoStore)
    }

    private let backend: Backend

    init(auth: Auth, firestore: Firestore, storage: Storage = .storage()) {
        backend = .firebase(auth, firestore, storage)
    }

    init(local: LocalDemoStore) {
        backend = .local(local)
    }

    var currentUid: String? {
        switch backend {
        case let .firebase(auth, _, _): auth.currentUser?.uid
        case let .local(store): store.uid
        }
    }

    var isAnonymous: Bool {
        switch backend {
        case let .firebase(auth, _, _):
            auth.currentUser?.isAnonymous ?? true
        case .local:
            true
        }
    }

    /// Short label for Account: "Signed in anonymously" / "Signed in with Apple" / …
    var providerLabel: String {
        switch backend {
        case let .firebase(auth, _, _):
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

    var linkedProviderNames: [String] {
        switch backend {
        case let .firebase(auth, _, _):
            guard let user = auth.currentUser, !user.isAnonymous else { return [] }
            return user.providerData.compactMap { info in
                switch info.providerID {
                case "apple.com": "Apple"
                case "google.com": "Google"
                default: nil
                }
            }
        case .local:
            return []
        }
    }

    /// Local demo has no Firebase Auth — guest path only on the welcome screen.
    var allowsAppleSignIn: Bool {
        switch backend {
        case .firebase: true
        case .local: false
        }
    }

    func signInAnonymouslyIfNeeded() async throws {
        switch backend {
        case let .firebase(auth, _, _):
            guard auth.currentUser == nil else { return }
            _ = try await auth.signInAnonymously()
        case let .local(store):
            store.ensureSignedIn()
        }
    }

    /// Emits the stored display name, and nil while the user still has to choose one.
    func displayName(uid: String) -> AsyncStream<String?> {
        switch backend {
        case let .firebase(_, firestore, _):
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

    /// Profile fields for Account (name + photo).
    func profile(uid: String) -> AsyncStream<(displayName: String?, photoURL: String?)> {
        switch backend {
        case let .firebase(_, firestore, _):
            let reference = firestore.collection(Fs.users).document(uid)
            let listeners = ListenerBag()
            return AsyncStream { continuation in
                listeners.add(
                    reference.addSnapshotListener { snapshot, _ in
                        continuation.yield(
                            (
                                snapshot?[Fs.displayName] as? String,
                                snapshot?[Fs.photoURL] as? String
                            )
                        )
                    }
                )
                continuation.onTermination = { _ in listeners.removeAll() }
            }
        case let .local(store):
            return store.profileStream()
        }
    }

    func saveDisplayName(_ name: String) async throws {
        switch backend {
        case let .firebase(auth, firestore, _):
            guard let uid = auth.currentUser?.uid else { return }
            try await firestore.collection(Fs.users).document(uid).setData(
                [
                    Fs.displayName: name.trimmingCharacters(in: .whitespacesAndNewlines),
                    Fs.createdAt: FieldValue.serverTimestamp(),
                    Fs.updatedAt: FieldValue.serverTimestamp(),
                ],
                merge: true
            )
        case let .local(store):
            store.saveDisplayName(name)
        }
    }

    /// Compresses and uploads a square-ish avatar; returns the public URL.
    func saveAvatar(_ image: UIImage) async throws -> String {
        guard let jpeg = Self.avatarJPEG(from: image) else {
            throw LinkError.failed("Couldn’t get that photo ready.")
        }
        switch backend {
        case let .firebase(auth, firestore, storage):
            guard let uid = auth.currentUser?.uid else { throw LinkError.notSignedIn }
            let path = "users/\(uid)/avatar.jpg"
            let ref = storage.reference().child(path)
            let meta = StorageMetadata()
            meta.contentType = "image/jpeg"
            _ = try await ref.putDataAsync(jpeg, metadata: meta)
            let url = try await ref.downloadURL().absoluteString
            try await firestore.collection(Fs.users).document(uid).setData(
                [
                    Fs.photoURL: url,
                    Fs.photoPath: path,
                    Fs.updatedAt: FieldValue.serverTimestamp(),
                ],
                merge: true
            )
            return url
        case let .local(store):
            return try store.saveAvatarJPEG(jpeg)
        }
    }

    func removeAvatar() async throws {
        switch backend {
        case let .firebase(auth, firestore, storage):
            guard let uid = auth.currentUser?.uid else { throw LinkError.notSignedIn }
            let path = "users/\(uid)/avatar.jpg"
            try? await storage.reference().child(path).delete()
            try await firestore.collection(Fs.users).document(uid).setData(
                [
                    Fs.photoURL: FieldValue.delete(),
                    Fs.photoPath: FieldValue.delete(),
                    Fs.updatedAt: FieldValue.serverTimestamp(),
                ],
                merge: true
            )
        case let .local(store):
            store.removeAvatar()
        }
    }

    /// Links the current anonymous user to Apple. Same uid afterwards.
    func linkWithApple(idToken: String, rawNonce: String) async throws {
        switch backend {
        case let .firebase(auth, _, _):
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

    /// Signs out the durable provider (or clears local demo). Boots a fresh anonymous uid.
    func signOut() async throws {
        clearAuthPathChoice()
        switch backend {
        case let .firebase(auth, _, _):
            try auth.signOut()
            _ = try await auth.signInAnonymously()
        case let .local(store):
            store.clearAccount()
        }
    }

    /// Wipes profile + Auth user (App Store account deletion). Past chats keep denormalised names.
    /// Apple-linked accounts must pass a fresh Apple credential before wipe.
    func deleteAccount(
        appleIdToken: String? = nil,
        appleRawNonce: String? = nil
    ) async throws {
        clearAuthPathChoice()
        switch backend {
        case let .firebase(auth, firestore, storage):
            guard let user = auth.currentUser else { throw LinkError.notSignedIn }
            let uid = user.uid

            if !user.isAnonymous {
                guard let appleIdToken, let appleRawNonce else {
                    throw LinkError.requiresRecentLogin
                }
                let credential = OAuthProvider.appleCredential(
                    withIDToken: appleIdToken,
                    rawNonce: appleRawNonce,
                    fullName: nil
                )
                do {
                    _ = try await user.reauthenticate(with: credential)
                } catch let error as NSError {
                    throw mapLinkError(error)
                }
            }

            try? await removeAvatar()
            try await wipeProfileData(uid: uid, firestore: firestore)
            try await user.delete()
            _ = try await auth.signInAnonymously()
        case let .local(store):
            store.clearAccount()
        }
    }

    private func wipeProfileData(uid: String, firestore: Firestore) async throws {
        let userRef = firestore.collection(Fs.users).document(uid)
        let blocks = try await userRef.collection(Fs.blocks).getDocuments()
        for doc in blocks.documents {
            try await doc.reference.delete()
        }
        let devices = try await userRef.collection(Fs.devices).getDocuments()
        for doc in devices.documents {
            try await doc.reference.delete()
        }
        try await userRef.delete()
    }

    private func clearAuthPathChoice() {
        UserDefaults.standard.removeObject(forKey: "maptalk.didChooseAuthPath")
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

    private static func avatarJPEG(from image: UIImage) -> Data? {
        let maxEdge = avatarMaxEdge
        let size = image.size
        let scale = min(1, maxEdge / max(size.width, size.height))
        let target = CGSize(width: size.width * scale, height: size.height * scale)
        let renderer = UIGraphicsImageRenderer(size: target)
        let scaled = renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: target))
        }
        return scaled.jpegData(compressionQuality: 0.82)
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
