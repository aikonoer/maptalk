package app.maptalk.auth

import android.app.Activity
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import app.maptalk.data.LinkException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException

/**
 * Fetches a Google ID token via Credential Manager for Firebase [AuthRepository.linkWithGoogle].
 */
object GoogleSignInHelper {

    /**
     * @param webClientId OAuth 2.0 Web client ID from `google-services.json` (`client_type: 3`).
     */
    suspend fun idToken(activity: Activity, webClientId: String): String {
        if (webClientId.isBlank()) {
            throw LinkException.Failed(
                "Google Sign-In isn’t configured. Add SHA fingerprints and redownload google-services.json.",
            )
        }
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(webClientId)
            .setAutoSelectEnabled(false)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()
        val manager = CredentialManager.create(activity)
        try {
            val result = manager.getCredential(activity, request)
            val credential = result.credential
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val google = GoogleIdTokenCredential.createFrom(credential.data)
                return google.idToken
            }
            throw LinkException.Failed("Unexpected credential type from Google.")
        } catch (_: GetCredentialCancellationException) {
            throw LinkException.Cancelled
        } catch (e: GoogleIdTokenParsingException) {
            throw LinkException.Failed(e.message ?: "Could not parse Google ID token.")
        } catch (e: GetCredentialException) {
            throw LinkException.Failed(e.message ?: "Google Sign-In failed.")
        }
    }

    /** Reads the Web client ID (`client_type` 3) from the merged google-services resources. */
    fun webClientId(context: Context): String {
        val id = context.resources.getIdentifier(
            "default_web_client_id",
            "string",
            context.packageName,
        )
        if (id == 0) return ""
        return context.getString(id)
    }
}
