package app.maptalk.ui.account

import android.app.Activity
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.maptalk.AppContainer
import app.maptalk.auth.GoogleSignInHelper
import app.maptalk.data.AuthRepository
import app.maptalk.data.LinkException
import app.maptalk.data.SafetyRepository
import app.maptalk.data.model.Author
import app.maptalk.data.model.BlockedPerson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AccountUiState(
    val displayName: String,
    val photoURL: String? = null,
    val providerLabel: String,
    val isAnonymous: Boolean,
    val linkedProviders: List<String> = emptyList(),
    val isLoadingBlocked: Boolean = true,
    val isLinking: Boolean = false,
    val isSavingName: Boolean = false,
    val isSavingPhoto: Boolean = false,
    val statusMessage: String? = null,
    val statusIsError: Boolean = false,
)

class AccountViewModel(
    private val context: Context,
    private val safetyRepository: SafetyRepository,
    private val authRepository: AuthRepository,
    private val author: Author,
) : ViewModel() {

    private val _state = MutableStateFlow(
        AccountUiState(
            displayName = author.displayName,
            providerLabel = authRepository.providerLabel,
            isAnonymous = authRepository.isAnonymous,
            linkedProviders = authRepository.linkedProviderNames,
        ),
    )
    val state: StateFlow<AccountUiState> = _state.asStateFlow()

    val blocked: StateFlow<List<BlockedPerson>> = safetyRepository.blockedPeople()
        .onEach { _state.update { current -> current.copy(isLoadingBlocked = false) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        authRepository.currentUid?.let { uid ->
            viewModelScope.launch {
                authRepository.profile(uid).collect { profile ->
                    _state.update { current ->
                        current.copy(
                            displayName = profile.displayName?.takeIf { it.isNotBlank() }
                                ?: current.displayName,
                            photoURL = profile.photoURL,
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            authRepository.providerLabelFlow().collect { refreshIdentity() }
        }
    }

    fun unblock(person: BlockedPerson) {
        safetyRepository.unblock(person.uid, author)
    }

    fun saveDisplayName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(isSavingName = true, statusMessage = null) }
            try {
                authRepository.saveDisplayName(trimmed)
                _state.update { it.copy(displayName = trimmed) }
            } catch (e: Exception) {
                fail(e.message ?: "Couldn’t save that name.")
            }
            _state.update { it.copy(isSavingName = false) }
        }
    }

    fun setPhoto(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(isSavingPhoto = true, statusMessage = null) }
            try {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: throw LinkException.Failed("Couldn’t read that photo.")
                val url = authRepository.saveAvatar(bytes)
                _state.update {
                    it.copy(photoURL = url, statusIsError = false, statusMessage = "Photo updated.")
                }
            } catch (e: Exception) {
                fail(e.message ?: "Couldn’t save that photo.")
            }
            _state.update { it.copy(isSavingPhoto = false) }
        }
    }

    fun removePhoto() {
        viewModelScope.launch {
            _state.update { it.copy(isSavingPhoto = true, statusMessage = null) }
            try {
                authRepository.removeAvatar()
                _state.update {
                    it.copy(photoURL = null, statusIsError = false, statusMessage = "Photo removed.")
                }
            } catch (e: Exception) {
                fail(e.message ?: "Couldn’t remove that photo.")
            }
            _state.update { it.copy(isSavingPhoto = false) }
        }
    }

    fun linkWithGoogle(activity: Activity) {
        if (_state.value.isLinking) return
        viewModelScope.launch {
            _state.update { it.copy(isLinking = true, statusMessage = null) }
            try {
                val webClientId = GoogleSignInHelper.webClientId(activity)
                val token = GoogleSignInHelper.idToken(activity, webClientId)
                authRepository.linkWithGoogle(token)
                refreshIdentity()
                _state.update {
                    it.copy(statusIsError = false, statusMessage = "Account saved with Google.")
                }
            } catch (_: LinkException.Cancelled) {
                _state.update { it.copy(statusMessage = null) }
            } catch (e: Exception) {
                fail(e.message ?: "Google Sign-In failed.")
            }
            _state.update { it.copy(isLinking = false) }
        }
    }

    private fun refreshIdentity() {
        _state.update {
            it.copy(
                providerLabel = authRepository.providerLabel,
                isAnonymous = authRepository.isAnonymous,
                linkedProviders = authRepository.linkedProviderNames,
            )
        }
    }

    private fun fail(message: String) {
        _state.update { it.copy(statusIsError = true, statusMessage = message) }
    }

    companion object {
        fun factory(container: AppContainer, author: Author): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    AccountViewModel(
                        context = container.context,
                        safetyRepository = container.safetyRepository,
                        authRepository = container.authRepository,
                        author = author,
                    )
                }
            }
    }
}
