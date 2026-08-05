import FirebaseAuth
import FirebaseCore
import FirebaseFirestore
import FirebaseStorage
import Foundation

/// Hand rolled dependency container. The app has three collaborators, so a DI framework would
/// be more ceremony than the wiring it replaces.
@MainActor
final class AppEnvironment {

    let authRepository: AuthRepository
    let threadRepository: ThreadRepository
    let locationProvider: LocationProvider

    /// Only set for Firebase-backed environments; local demo has nothing to flush.
    private let firestore: Firestore?

    /// Live Firebase + Cloudflare R2 photo uploads.
    init(auth: Auth = .auth(), firestore: Firestore = .firestore()) {
        self.firestore = firestore
        authRepository = AuthRepository(auth: auth, firestore: firestore)
        let endpoint = URL(string: "https://maptalk-media.hhypkfpshg.workers.dev/v1/images")!
        threadRepository = ThreadRepository(
            firestore: firestore,
            mediaUploader: R2MediaUploader(auth: auth, endpoint: endpoint)
        )
        locationProvider = LocationProvider()
    }

    private init(auth: Auth, firestore: Firestore, mediaUploader: MediaUploading) {
        self.firestore = firestore
        authRepository = AuthRepository(auth: auth, firestore: firestore)
        threadRepository = ThreadRepository(firestore: firestore, mediaUploader: mediaUploader)
        locationProvider = LocationProvider()
    }

    private init(local store: LocalDemoStore) {
        firestore = nil
        authRepository = AuthRepository(local: store)
        threadRepository = ThreadRepository(local: store)
        locationProvider = LocationProvider()
    }

    /// On-device demo: seeded Cebu chats, no Firebase, no Mac. Ideal for trying the app on a
    /// phone over Personal Hotspot.
    static func localDemo() -> AppEnvironment {
        AppEnvironment(local: LocalDemoStore())
    }

    /// Waits until the server has acknowledged every local write. The app never needs this —
    /// its writes are deliberately fire and forget — but the cross-device suite does, because
    /// Firestore serves a write back to its own listeners long before the backend has seen it.
    func waitForPendingWrites() async throws {
        try await firestore?.waitForPendingWrites()
    }

    /// The same wiring against the local Firebase emulators, under fake credentials in a
    /// secondary app so no real project is needed. Used by the cross-device suite
    /// (`scripts/cross-device-qa.sh`) and handy for running the app with no backend of your own.
    static func emulator(
        host: String = "localhost",
        authPort: Int = 9099,
        firestorePort: Int = 8080,
        storagePort: Int = 9199,
        projectId: String = "maptalk-qa"
    ) -> AppEnvironment {
        let name = "maptalk-emulator"
        if FirebaseApp.app(name: name) == nil {
            let options = FirebaseOptions(
                googleAppID: "1:000000000000:ios:0000000000000000",
                gcmSenderID: "000000000000"
            )
            options.projectID = projectId
            options.apiKey = "emulator-only-key"
            options.bundleID = Bundle.main.bundleIdentifier ?? "app.maptalk"
            options.storageBucket = "\(projectId).appspot.com"
            FirebaseApp.configure(name: name, options: options)
        }

        let app = FirebaseApp.app(name: name)!
        let auth = Auth.auth(app: app)
        auth.useEmulator(withHost: host, port: authPort)

        let firestore = Firestore.firestore(app: app)
        let settings = firestore.settings
        settings.host = "\(host):\(firestorePort)"
        settings.isSSLEnabled = false
        // Reads must come from the emulator, not a cache left by an earlier run.
        settings.cacheSettings = MemoryCacheSettings()
        firestore.settings = settings

        let storage = Storage.storage(app: app)
        storage.useEmulator(withHost: host, port: storagePort)

        return AppEnvironment(
            auth: auth,
            firestore: firestore,
            mediaUploader: FirebaseStorageUploader(storage: storage)
        )
    }
}
