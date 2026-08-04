import FirebaseFirestore
import Foundation

/// Holds snapshot listeners so an `AsyncStream` can detach all of them when it ends.
///
/// A listener registration is not `Sendable`, and an `AsyncStream`'s termination handler is, so
/// the registrations are kept behind a lock rather than captured directly.
final class ListenerBag: @unchecked Sendable {

    private let lock = NSLock()
    private var registrations: [ListenerRegistration] = []

    func add(_ registration: ListenerRegistration) {
        lock.lock()
        defer { lock.unlock() }
        registrations.append(registration)
    }

    func removeAll() {
        lock.lock()
        let current = registrations
        registrations = []
        lock.unlock()
        current.forEach { $0.remove() }
    }
}

/// Collects the latest page from each geohash bound and hands back the merged result. Snapshot
/// callbacks arrive on the main queue today, but a lock keeps that from being a load-bearing
/// assumption.
final class PageBuffer<Element>: @unchecked Sendable {

    private let lock = NSLock()
    private var pages: [[Element]]

    init(count: Int) {
        pages = Array(repeating: [], count: count)
    }

    func replace(_ page: [Element], at index: Int) -> [Element] {
        lock.lock()
        defer { lock.unlock() }
        guard pages.indices.contains(index) else { return pages.flatMap { $0 } }
        pages[index] = page
        return pages.flatMap { $0 }
    }
}
