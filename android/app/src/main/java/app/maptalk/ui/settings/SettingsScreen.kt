package app.maptalk.ui.settings

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.maptalk.AppContainer
import app.maptalk.R
import app.maptalk.appContainer
import app.maptalk.auth.GoogleSignInHelper
import app.maptalk.data.AuthRepository
import app.maptalk.data.LinkException
import app.maptalk.data.SafetyRepository
import app.maptalk.data.model.Author
import app.maptalk.data.model.BlockedPerson
import app.maptalk.ui.theme.MapTalkColors
import app.maptalk.ui.theme.avatarTint
import app.maptalk.ui.theme.initialsOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    author: Author,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val container = context.appContainer
    val viewModel: SettingsViewModel =
        viewModel(factory = SettingsViewModel.factory(container, author))
    val blocked by viewModel.blocked.collectAsStateWithLifecycle()
    val providerLabel by viewModel.providerLabel.collectAsStateWithLifecycle()
    val isAnonymous by viewModel.isAnonymous.collectAsStateWithLifecycle()
    val isLinking by viewModel.isLinking.collectAsStateWithLifecycle()
    val linkMessage by viewModel.linkMessage.collectAsStateWithLifecycle()
    val linkIsError by viewModel.linkIsError.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MapTalkColors.Base,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MapTalkColors.Surface,
                    titleContentColor = MapTalkColors.Text,
                    navigationIconContentColor = MapTalkColors.Text,
                ),
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SettingsAvatar(name = author.displayName, seed = author.uid)
                    Column {
                        Text(
                            text = author.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            color = MapTalkColors.Text,
                        )
                        Text(
                            text = providerLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MapTalkColors.Faint,
                        )
                    }
                }
            }

            if (isAnonymous && !container.isLocalDemo) {
                item {
                    Button(
                        onClick = {
                            val act = activity ?: return@Button
                            viewModel.linkWithGoogle(act)
                        },
                        enabled = !isLinking,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MapTalkColors.Raised,
                            contentColor = MapTalkColors.Text,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (isLinking) "Connecting…" else "Continue with Google")
                    }
                    Text(
                        text = "Save this account with Google so your chats stick if you reinstall.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MapTalkColors.Faint,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            linkMessage?.let { message ->
                item {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (linkIsError) MapTalkColors.Danger else MapTalkColors.Subtle,
                    )
                }
            }

            item {
                Text(
                    text = "Blocked people",
                    style = MaterialTheme.typography.titleSmall,
                    color = MapTalkColors.Subtle,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            if (blocked.isEmpty()) {
                item {
                    Text(
                        text = "Nobody blocked yet. Long-press a message to block someone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MapTalkColors.Subtle,
                    )
                }
            } else {
                items(blocked, key = { it.uid }) { person ->
                    Surface(
                        color = MapTalkColors.Raised,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            SettingsAvatar(name = person.displayName, seed = person.uid, size = 32)
                            Text(
                                text = person.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MapTalkColors.Text,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { viewModel.unblock(person) }) {
                                Text("Unblock", color = MapTalkColors.Accent)
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    text = "Blocked authors’ chats and messages stay hidden for you only.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MapTalkColors.Faint,
                )
            }
        }
    }
}

@Composable
private fun SettingsAvatar(name: String, seed: String, size: Int = 40) {
    val tint = avatarTint(seed)
    Surface(
        modifier = Modifier.size(size.dp),
        shape = CircleShape,
        color = tint.copy(alpha = 0.18f),
        contentColor = tint,
    ) {
        androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
            Text(text = initialsOf(name), style = MaterialTheme.typography.labelSmall)
        }
    }
}

class SettingsViewModel(
    private val safetyRepository: SafetyRepository,
    private val authRepository: AuthRepository,
    private val author: Author,
) : ViewModel() {

    val blocked: StateFlow<List<BlockedPerson>> = safetyRepository.blockedPeople()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val providerLabel: StateFlow<String> = authRepository.providerLabelFlow()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            authRepository.providerLabel,
        )

    private val _isAnonymous = MutableStateFlow(authRepository.isAnonymous)
    val isAnonymous: StateFlow<Boolean> = _isAnonymous.asStateFlow()

    private val _isLinking = MutableStateFlow(false)
    val isLinking: StateFlow<Boolean> = _isLinking.asStateFlow()

    private val _linkMessage = MutableStateFlow<String?>(null)
    val linkMessage: StateFlow<String?> = _linkMessage.asStateFlow()

    private val _linkIsError = MutableStateFlow(false)
    val linkIsError: StateFlow<Boolean> = _linkIsError.asStateFlow()

    init {
        viewModelScope.launch {
            authRepository.providerLabelFlow().collect {
                _isAnonymous.value = authRepository.isAnonymous
            }
        }
    }

    fun unblock(person: BlockedPerson) {
        safetyRepository.unblock(person.uid, author)
    }

    fun linkWithGoogle(activity: Activity) {
        if (_isLinking.value) return
        viewModelScope.launch {
            _isLinking.value = true
            _linkMessage.value = null
            try {
                val webClientId = GoogleSignInHelper.webClientId(activity)
                val token = GoogleSignInHelper.idToken(activity, webClientId)
                authRepository.linkWithGoogle(token)
                _isAnonymous.value = authRepository.isAnonymous
                _linkIsError.value = false
                _linkMessage.value = "Account saved with Google."
            } catch (_: LinkException.Cancelled) {
                _linkMessage.value = null
            } catch (e: LinkException) {
                _linkIsError.value = true
                _linkMessage.value = e.message
            } catch (e: Exception) {
                _linkIsError.value = true
                _linkMessage.value = e.message ?: "Google Sign-In failed."
            }
            _isLinking.value = false
        }
    }

    companion object {
        fun factory(container: AppContainer, author: Author): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    SettingsViewModel(
                        safetyRepository = container.safetyRepository,
                        authRepository = container.authRepository,
                        author = author,
                    )
                }
            }
    }
}
