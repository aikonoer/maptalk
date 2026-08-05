import FirebaseFirestore
import Foundation

/// Viewer-private blocks and append-only reports. Blocks filter the map and open threads
/// on the client; reports are write-and-forget for later admin review.
@MainActor
final class SafetyRepository {

    private enum Backend {
        case firestore(Firestore, uid: () -> String?)
        case local(LocalDemoStore)
    }

    private let backend: Backend
    var onError: ((Error) -> Void)?

    init(firestore: Firestore, currentUid: @escaping () -> String?) {
        backend = .firestore(firestore, uid: currentUid)
    }

    init(local: LocalDemoStore) {
        backend = .local(local)
    }

    /// Live set of blocked author uids for the signed-in viewer.
    func blockedUids() -> AsyncStream<Set<String>> {
        switch backend {
        case let .firestore(firestore, uidProvider):
            let listeners = ListenerBag()
            let report = onError
            return AsyncStream { continuation in
                guard let uid = uidProvider() else {
                    continuation.yield([])
                    return
                }
                let registration = firestore.collection(Fs.users).document(uid)
                    .collection(Fs.blocks)
                    .addSnapshotListener { snapshot, error in
                        if let error {
                            report?(error)
                            return
                        }
                        let ids = Set(snapshot?.documents.map(\.documentID) ?? [])
                        continuation.yield(ids)
                    }
                listeners.add(registration)
                continuation.onTermination = { _ in listeners.removeAll() }
            }
        case let .local(store):
            return store.blockedUids()
        }
    }

    func block(uid blockedUid: String, as author: Author) {
        guard blockedUid != author.uid, !blockedUid.isEmpty else { return }
        switch backend {
        case let .firestore(firestore, _):
            let ref = firestore.collection(Fs.users).document(author.uid)
                .collection(Fs.blocks).document(blockedUid)
            ref.setData([
                Fs.blockedUid: blockedUid,
                Fs.createdAt: FieldValue.serverTimestamp(),
            ]) { [weak self] error in
                guard let error else { return }
                MainActor.assumeIsolated { self?.onError?(error) }
            }
        case let .local(store):
            store.block(uid: blockedUid)
        }
    }

    func unblock(uid blockedUid: String, as author: Author) {
        switch backend {
        case let .firestore(firestore, _):
            firestore.collection(Fs.users).document(author.uid)
                .collection(Fs.blocks).document(blockedUid)
                .delete { [weak self] error in
                    guard let error else { return }
                    MainActor.assumeIsolated { self?.onError?(error) }
                }
        case let .local(store):
            store.unblock(uid: blockedUid)
        }
    }

    func report(
        type: ReportTargetType,
        targetId: String,
        threadId: String,
        targetAuthorId: String,
        reason: ReportReason,
        as author: Author
    ) {
        guard targetAuthorId != author.uid else { return }
        switch backend {
        case let .firestore(firestore, _):
            let ref = firestore.collection(Fs.users).document(author.uid)
                .collection(Fs.reports).document()
            let fields: [String: Any] = [
                Fs.targetType: type.rawValue,
                Fs.targetId: targetId,
                Fs.threadId: type == .message ? threadId : "",
                Fs.targetAuthorId: targetAuthorId,
                Fs.reason: reason.rawValue,
                Fs.createdAt: FieldValue.serverTimestamp(),
            ]
            ref.setData(fields) { [weak self] error in
                guard let error else { return }
                MainActor.assumeIsolated { self?.onError?(error) }
            }
        case let .local(store):
            store.report(
                type: type,
                targetId: targetId,
                threadId: threadId,
                targetAuthorId: targetAuthorId,
                reason: reason
            )
        }
    }
}
