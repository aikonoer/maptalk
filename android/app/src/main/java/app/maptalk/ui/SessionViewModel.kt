package app.maptalk.ui

import android.app.Activity
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.maptalk.AppContainer
import app.maptalk.auth.GoogleSignInHelper
import app.maptalk.data.AuthRepository
import app.maptalk.data.LinkException
import app.maptalk.data.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SessionViewModel(private val authRepository: AuthRepository) : ViewModel() {

    val session: StateFlow<Session?> = authRepository.session()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _signInError = MutableStateFlow<String?>(null)
    val signInError: StateFlow<String?> = _signInError.asStateFlow()

    private val _isSavingName = MutableStateFlow(false)
    val isSavingName: StateFlow<Boolean> = _isSavingName.asStateFlow()

    private val _authBusy = MutableStateFlow(false)
    val authBusy: StateFlow<Boolean> = _authBusy.asStateFlow()

    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    val allowsGoogleSignIn: Boolean get() = authRepository.allowsGoogleSignIn

    init {
        signIn()
        viewModelScope.launch {
            session.collect { current ->
                // Already-linked Google accounts may never have written photoURL — seed once.
                if (current is Session.Ready && current.author.photoURL.isNullOrBlank()) {
                    runCatching { authRepository.seedProviderPhotoIfNeeded() }
                }
            }
        }
    }

    private fun signIn() {
        viewModelScope.launch {
            runCatching { authRepository.signInAnonymously() }
                .onFailure { cause ->
                    Log.w("MapTalk", "Anonymous sign-in failed", cause)
                    _signInError.value =
                        "Could not reach Firebase. Check your connection and configuration, then reopen the app."
                }
        }
    }

    fun continueAsGuest() {
        _authError.value = null
        authRepository.markAuthPathChosen()
    }

    fun continueWithGoogle(activity: Activity) {
        if (!authRepository.allowsGoogleSignIn) {
            _authError.value =
                "Google Sign-In isn’t available in local demo. Explore without an account, or switch to Live."
            return
        }
        if (_authBusy.value) return
        viewModelScope.launch {
            _authBusy.value = true
            _authError.value = null
            try {
                val webClientId = GoogleSignInHelper.webClientId(activity)
                val token = GoogleSignInHelper.idToken(activity, webClientId)
                authRepository.linkWithGoogle(token)
                authRepository.markAuthPathChosen()
            } catch (_: LinkException.Cancelled) {
                _authError.value = null
            } catch (e: Exception) {
                _authError.value = e.message ?: "Google Sign-In failed."
            }
            _authBusy.value = false
        }
    }

    fun saveDisplayName(name: String) {
        viewModelScope.launch {
            _isSavingName.value = true
            runCatching { authRepository.saveDisplayName(name) }
            _isSavingName.value = false
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { SessionViewModel(container.authRepository) }
        }
    }
}
