import FirebaseCore
import SwiftUI

@main
struct MapTalkApp: App {

    @State private var environment: AppEnvironment

    init() {
        // Mode selection (first match wins):
        //   MAPTALK_MODE env (Xcode scheme / devicectl)
        //   MapTalkMode Info.plist (baked build setting — survives home-screen launch)
        //   unset + Debug sim      → emulator on localhost
        //   unset + Debug device   → local demo
        //   unset + Release        → live Firebase
        let mode = ProcessInfo.processInfo.environment["MAPTALK_MODE"]
            ?? (Bundle.main.object(forInfoDictionaryKey: "MapTalkMode") as? String)
            ?? Self.defaultMode

        switch mode {
        case "local":
            _environment = State(initialValue: .localDemo())
        case "emulator":
            let host = ProcessInfo.processInfo.environment["MAPTALK_EMULATOR_HOST"]
                ?? Self.defaultEmulatorHost
            _environment = State(initialValue: .emulator(host: host))
        default:
            FirebaseApp.configure()
            _environment = State(initialValue: AppEnvironment())
        }
    }

    private static var defaultMode: String {
        #if DEBUG
        #if targetEnvironment(simulator)
        "emulator"
        #else
        "local"
        #endif
        #else
        "live"
        #endif
    }

    /// Simulator can use loopback. A plugged-in phone talks to the Mac over USB (usually
    /// 192.0.0.2) or Wi‑Fi — override with MAPTALK_EMULATOR_HOST when that address changes.
    private static var defaultEmulatorHost: String {
        #if targetEnvironment(simulator)
        "localhost"
        #else
        "192.0.0.2"
        #endif
    }

    var body: some Scene {
        WindowGroup {
            RootView(environment: environment)
                .preferredColorScheme(.dark)
                .tint(Theme.accent)
                .onOpenURL { url in
                    DeepLinkBus.shared.offer(ThreadLink.threadId(from: url))
                }
        }
    }
}
