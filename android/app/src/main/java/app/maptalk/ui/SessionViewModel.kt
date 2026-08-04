package app.maptalk.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.maptalk.AppContainer
import app.maptalk.data.AuthRepository
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

    init {
        signIn()
    }

    private fun signIn() {
        viewModelScope.launch {
            runCatching { authRepository.signInAnonymously() }
                .onFailure { cause ->
                    // The message on screen has to stay vague; the cause is what makes a bad
                    // Firebase setup diagnosable.
                    Log.w("MapTalk", "Anonymous sign-in failed", cause)
                    _signInError.value =
                        "Could not reach Firebase. Check your connection and configuration, then reopen the app."
                }
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
