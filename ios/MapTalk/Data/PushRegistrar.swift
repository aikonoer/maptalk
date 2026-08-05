import FirebaseCore
import FirebaseMessaging
import Foundation
import UIKit
import UserNotifications

/// Asks for notification permission, keeps the FCM token in Firestore, and
/// auto-subscribes the user when they open a thread.
@MainActor
enum PushRegistrar {

    private static let deviceIdKey = "maptalk.deviceId"

    static var deviceId: String {
        if let existing = UserDefaults.standard.string(forKey: deviceIdKey) {
            return existing
        }
        let id = UIDevice.current.identifierForVendor?.uuidString ?? UUID().uuidString
        UserDefaults.standard.set(id, forKey: deviceIdKey)
        return id
    }

    static func start(push: PushRepository) async {
        // Local demo has no Firebase; skip permission / token work.
        guard FirebaseApp.app() != nil else { return }

        let bridge = PushTokenBridge.shared
        bridge.push = push

        let center = UNUserNotificationCenter.current()
        center.delegate = bridge
        _ = try? await center.requestAuthorization(options: [.alert, .sound, .badge])

        UIApplication.shared.registerForRemoteNotifications()
        Messaging.messaging().delegate = bridge

        if let token = try? await Messaging.messaging().token() {
            push.registerDevice(deviceId: deviceId, token: token, platform: "ios")
        }
    }
}

/// Bridges UIKit / FirebaseMessaging callbacks into SwiftUI's PushRepository.
@MainActor
final class PushTokenBridge: NSObject, UNUserNotificationCenterDelegate, MessagingDelegate {
    static let shared = PushTokenBridge()

    var push: PushRepository?
    var pendingThreadId: String?

    nonisolated func messaging(_ messaging: Messaging, didReceiveRegistrationToken fcmToken: String?) {
        guard let fcmToken else { return }
        Task { @MainActor in
            push?.registerDevice(
                deviceId: PushRegistrar.deviceId,
                token: fcmToken,
                platform: "ios"
            )
        }
    }

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        [.banner, .sound]
    }

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse
    ) async {
        let threadId = response.notification.request.content.userInfo["threadId"] as? String
        await MainActor.run {
            pendingThreadId = threadId
        }
    }
}
