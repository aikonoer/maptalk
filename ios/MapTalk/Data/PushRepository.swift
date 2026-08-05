import FirebaseFirestore
import Foundation

/// Registers FCM tokens and thread subscriptions for push.
@MainActor
final class PushRepository {

    var onError: ((Error) -> Void)?

    private let firestore: Firestore?
    private let currentUid: () -> String?

    init(firestore: Firestore, currentUid: @escaping () -> String?) {
        self.firestore = firestore
        self.currentUid = currentUid
    }

    init(local _: LocalDemoStore) {
        firestore = nil
        currentUid = { nil }
    }

    func registerDevice(deviceId: String, token: String, platform: String) {
        guard let firestore, let uid = currentUid(), !token.isEmpty else { return }
        firestore
            .collection(Fs.users).document(uid)
            .collection(Fs.devices).document(deviceId)
            .setData([
                Fs.token: token,
                Fs.platform: platform,
                Fs.updatedAt: FieldValue.serverTimestamp(),
            ]) { [weak self] error in
                if let error { self?.onError?(error) }
            }
    }

    func subscribe(toThreadId threadId: String) {
        guard let firestore, let uid = currentUid(), !threadId.isEmpty else { return }
        firestore
            .collection(Fs.threads).document(threadId)
            .collection(Fs.subscribers).document(uid)
            .setData([Fs.subscribedAt: FieldValue.serverTimestamp()]) { [weak self] error in
                if let error { self?.onError?(error) }
            }
    }
}
