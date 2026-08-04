import Foundation
import Testing

@testable import MapTalk

/// The fixtures the two platforms hand off through, duplicated in
/// `android/app/src/androidTest/java/app/maptalk/qa/CrossDevice.kt`. A change here needs the
/// same change there.
private enum QA {
    static let projectId = "maptalk-qa"
    static let host = "localhost"
    static let firestorePort = 8080
    static let authPort = 9099

    static let center = GeoPoint(lat: -33.8688, lng: 151.2093)
    static let radiusKm = 2.0

    static let androidTitle = "Bubble from Android"
    static let androidMessage = "Posted on Android"
    static let iosAuthor = "iOS QA"
    static let iosTitle = "Bubble from iOS"
    static let iosReply = "Replying from iOS"

    /// Long enough for a listener to make the round trip, short enough to fail a stuck test.
    static let timeout = Duration.seconds(30)

    /// A plain TCP connect to the Firestore emulator's port, which is enough to tell whether
    /// the cross-device suite has a backend to talk to.
    static var emulatorIsListening: Bool {
        let descriptor = socket(AF_INET, SOCK_STREAM, 0)
        guard descriptor >= 0 else { return false }
        defer { close(descriptor) }

        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = UInt16(firestorePort).bigEndian
        address.sin_addr.s_addr = inet_addr("127.0.0.1")

        let connected = withUnsafePointer(to: address) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                connect(descriptor, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        return connected == 0
    }

    /// The app's own wiring, pointed at the emulators.
    @MainActor
    static func environment() -> AppEnvironment {
        AppEnvironment.emulator(
            host: host,
            authPort: authPort,
            firestorePort: firestorePort,
            projectId: projectId
        )
    }

    /// The first value from a listener stream that satisfies `predicate`, or a failure if the
    /// wait runs out.
    static func first<Value: Sendable>(
        of stream: AsyncStream<Value>,
        matching predicate: @escaping @Sendable (Value) -> Bool
    ) async throws -> Value {
        try await withThrowingTaskGroup(of: Value?.self) { group in
            group.addTask {
                for await value in stream where predicate(value) { return value }
                return nil
            }
            group.addTask {
                try await Task.sleep(for: timeout)
                return nil
            }
            let winner = try await group.next()
            group.cancelAll()
            guard let value = winner ?? nil else { throw Timeout() }
            return value
        }
    }

    struct Timeout: Error, CustomStringConvertible {
        var description: String {
            "Timed out waiting for the other platform's data. Is the Firebase emulator running, and did the Android step run first?"
        }
    }
}

/// Errors the repository reported while the test was running.
@MainActor
private final class Failures {
    private(set) var all: [String] = []
    func record(_ error: Error) { all.append(error.localizedDescription) }
}

/// The iOS half of the cross-device check. `scripts/cross-device-qa.sh` boots the Firebase
/// emulators and runs the Android `CrossDeviceWriteTest`, then this, then the Android
/// `CrossDeviceVerifyTest`: Android pins a bubble, iOS finds it through its own geohash query
/// and replies, and Android sees the reply.
///
/// The suite skips itself when the emulator is not listening, so a plain `xcodebuild test`
/// stays green without it. The script greps for this test by name, so a skip cannot pass for
/// a pass.
@Suite(.enabled(if: QA.emulatorIsListening))
@MainActor
struct CrossDeviceSyncTests {

    @Test
    func iosSeesTheAndroidBubbleRepliesToItAndPinsItsOwn() async throws {
        let environment = QA.environment()
        let auth = environment.authRepository
        let threads = environment.threadRepository

        // A write rejected by the rules is still visible to local listeners for a moment, so
        // failures are collected and checked rather than trusted to surface as a wrong value.
        let failures = Failures()
        threads.onError = { failures.record($0) }

        try await auth.signInAnonymouslyIfNeeded()
        try await auth.saveDisplayName(QA.iosAuthor)
        let uid = try #require(auth.currentUid)
        let name = try await QA.first(of: auth.displayName(uid: uid)) { $0 == QA.iosAuthor }
        #expect(name == QA.iosAuthor)
        let author = Author(uid: uid, displayName: QA.iosAuthor)

        // The Android thread has to arrive through the same viewport query the map uses, which
        // means the Swift geohash port has to agree with the library that wrote the document.
        let query = Viewport.query(center: QA.center, radiusKm: QA.radiusKm)
        let visible = try await QA.first(of: threads.threads(for: query)) { threads in
            threads.contains { $0.title == QA.androidTitle }
        }
        let androidThread = try #require(visible.first { $0.title == QA.androidTitle })
        #expect(androidThread.authorId != uid)
        #expect(androidThread.geohash == GeoHash.hash(for: QA.center))

        // Its message list reads correctly here too, then we reply.
        let existing = try await QA.first(of: threads.messages(threadId: androidThread.id)) {
            !$0.isEmpty
        }
        #expect(existing.map(\.text) == [QA.androidMessage])

        threads.postMessage(threadId: androidThread.id, text: QA.iosReply, author: author)
        let afterReply = try await QA.first(of: threads.messages(threadId: androidThread.id)) {
            $0.count >= 2
        }
        #expect(afterReply.map(\.text) == [QA.androidMessage, QA.iosReply])

        // The batch also bumps the thread's activity, which the rules allow and nothing else.
        let bumped = try await QA.first(of: threads.thread(id: androidThread.id)) {
            ($0?.messageCount ?? 0) >= 2
        }
        #expect(bumped?.messageCount == 2)

        // A bubble of our own, for the Android side to find.
        _ = threads.createThread(
            title: QA.iosTitle,
            kind: .notice,
            position: QA.center,
            author: author
        )
        let mine = try await QA.first(of: threads.threads(for: query)) { threads in
            threads.contains { $0.title == QA.iosTitle }
        }
        #expect(mine.contains { $0.title == QA.iosTitle && $0.authorName == QA.iosAuthor })

        // Everything above could have been served from this device's own cache, so the run only
        // counts once the backend has taken the writes.
        try await environment.waitForPendingWrites()
        #expect(failures.all.isEmpty, "\(failures.all)")
    }
}
