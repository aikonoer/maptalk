package app.maptalk.ui.settings

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
import app.maptalk.data.SafetyRepository
import app.maptalk.data.model.Author
import app.maptalk.data.model.BlockedPerson
import app.maptalk.ui.theme.MapTalkColors
import app.maptalk.ui.theme.avatarTint
import app.maptalk.ui.theme.initialsOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    author: Author,
    onBack: () -> Unit,
) {
    val container = LocalContext.current.appContainer
    val viewModel: SettingsViewModel =
        viewModel(factory = SettingsViewModel.factory(container, author))
    val blocked by viewModel.blocked.collectAsStateWithLifecycle()

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
                            text = "Signed in anonymously",
                            style = MaterialTheme.typography.labelSmall,
                            color = MapTalkColors.Faint,
                        )
                    }
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
    private val author: Author,
) : ViewModel() {

    val blocked: StateFlow<List<BlockedPerson>> = safetyRepository.blockedPeople()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun unblock(person: BlockedPerson) {
        safetyRepository.unblock(person.uid, author)
    }

    companion object {
        fun factory(container: AppContainer, author: Author): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    SettingsViewModel(
                        safetyRepository = container.safetyRepository,
                        author = author,
                    )
                }
            }
    }
}
