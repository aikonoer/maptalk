import AuthenticationServices
import Foundation
import UIKit

/// Presents the system Apple ID sheet and returns the identity token + nonce for Firebase link.
@MainActor
final class AppleSignInCoordinator: NSObject {

    private var continuation: CheckedContinuation<(idToken: String, rawNonce: String), Error>?
    private var currentNonce: String?

    func signIn() async throws -> (idToken: String, rawNonce: String) {
        try await withCheckedThrowingContinuation { continuation in
            self.continuation = continuation
            let nonce = AppleNonce.random()
            currentNonce = nonce

            let request = ASAuthorizationAppleIDProvider().createRequest()
            request.requestedScopes = [.fullName]
            request.nonce = AppleNonce.sha256(nonce)

            let controller = ASAuthorizationController(authorizationRequests: [request])
            controller.delegate = self
            controller.presentationContextProvider = self
            controller.performRequests()
        }
    }
}

extension AppleSignInCoordinator: ASAuthorizationControllerDelegate {
    func authorizationController(
        controller: ASAuthorizationController,
        didCompleteWithAuthorization authorization: ASAuthorization
    ) {
        guard
            let credential = authorization.credential as? ASAuthorizationAppleIDCredential,
            let tokenData = credential.identityToken,
            let idToken = String(data: tokenData, encoding: .utf8),
            let rawNonce = currentNonce
        else {
            continuation?.resume(throwing: AuthRepository.LinkError.failed("Apple returned no identity token."))
            continuation = nil
            return
        }
        continuation?.resume(returning: (idToken, rawNonce))
        continuation = nil
    }

    func authorizationController(
        controller: ASAuthorizationController,
        didCompleteWithError error: Error
    ) {
        let ns = error as NSError
        if ns.domain == ASAuthorizationError.errorDomain,
           ns.code == ASAuthorizationError.canceled.rawValue {
            continuation?.resume(throwing: AuthRepository.LinkError.cancelled)
        } else {
            continuation?.resume(throwing: AuthRepository.LinkError.failed(error.localizedDescription))
        }
        continuation = nil
    }
}

extension AppleSignInCoordinator: ASAuthorizationControllerPresentationContextProviding {
    func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        if let key = scenes.flatMap(\.windows).first(where: \.isKeyWindow) {
            return key
        }
        return scenes.flatMap(\.windows).first ?? ASPresentationAnchor()
    }
}
