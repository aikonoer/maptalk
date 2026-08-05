import Foundation
import Network

/// Cheap snapshot of whether the active path is cellular / expensive.
enum NetworkExpense {
    private static let monitor: NWPathMonitor = {
        let m = NWPathMonitor()
        m.start(queue: DispatchQueue(label: "app.maptalk.network"))
        return m
    }()

    static var isExpensiveOrConstrained: Bool {
        let path = monitor.currentPath
        return path.isExpensive || path.isConstrained || path.usesInterfaceType(.cellular)
    }
}
