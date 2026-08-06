package app.maptalk.ui.account

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.maptalk.R
import app.maptalk.appContainer
import app.maptalk.data.AuthRepository
import app.maptalk.data.model.Author
import app.maptalk.data.model.BlockedPerson
import app.maptalk.ui.InitialAvatar
import app.maptalk.ui.theme.MapTalkColors
import app.maptalk.ui.theme.MapTalkShapes

/**
 * Standard account settings — profile, sign-in, posting prefs, safety.
 * Spec: `docs/ACCOUNT.md`; mirrors `ios/MapTalk/Features/Account/AccountScreen.swift`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    author: Author,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val container = context.appContainer
    val viewModel: AccountViewModel =
        viewModel(factory = AccountViewModel.factory(container, author))

    val state by viewModel.state.collectAsStateWithLifecycle()
    val blocked by viewModel.blocked.collectAsStateWithLifecycle()

    var showBlocked by remember { mutableStateOf(false) }
    var showPhotoActions by remember { mutableStateOf(false) }
    var editingName by remember { mutableStateOf<String?>(null) }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri != null) viewModel.setPhoto(uri)
    }

    if (showBlocked) BackHandler { showBlocked = false }

    Scaffold(
        modifier = modifier,
        containerColor = MapTalkColors.Base,
        topBar = {
            AccountTopBar(
                title = if (showBlocked) "Blocked people" else "Account",
                showsBack = showBlocked,
                onNavigate = { if (showBlocked) showBlocked = false else onBack() },
            )
        },
    ) { padding ->
        if (showBlocked) {
            BlockedPeopleList(
                blocked = blocked,
                isLoading = state.isLoadingBlocked,
                onUnblock = viewModel::unblock,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                ProfileHeader(
                    state = state,
                    seed = author.uid,
                    onEditPhoto = { showPhotoActions = true },
                )
            }

            sectionHeader("Profile")
            item {
                SettingsRow(
                    title = "Display name",
                    value = state.displayName,
                    onClick = { editingName = state.displayName },
                )
            }
            item {
                SettingsRow(
                    title = "Profile photo",
                    value = if (state.photoURL == null) "None" else "Set",
                    onClick = { showPhotoActions = true },
                )
            }

            sectionHeader("Sign-in")
            if (state.isAnonymous && !container.isLocalDemo) {
                item {
                    Button(
                        onClick = { activity?.let(viewModel::linkWithGoogle) },
                        enabled = !state.isLinking,
                        shape = RoundedCornerShape(MapTalkShapes.Field),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MapTalkColors.Raised,
                            contentColor = MapTalkColors.Text,
                            disabledContainerColor = MapTalkColors.Raised,
                            disabledContentColor = MapTalkColors.Faint,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = if (state.isLinking) "Connecting…" else "Continue with Google",
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
            } else if (state.linkedProviders.isNotEmpty()) {
                items(state.linkedProviders, key = { it }) { provider ->
                    RaisedRow {
                        Text(
                            text = provider,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MapTalkColors.Text,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "Linked",
                            style = MaterialTheme.typography.labelSmall,
                            color = MapTalkColors.Faint,
                        )
                    }
                }
            }
            sectionFooter(
                if (state.isAnonymous) {
                    "You’re on an anonymous account. Save it with Google so chats stick across " +
                        "reinstalls. Apple sign-in is on iOS."
                } else {
                    "Your account stays the same when you reinstall and sign in again."
                },
            )

            sectionHeader("Posting")
            item {
                RaisedRow {
                    Text(
                        text = "Post anonymously by default",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MapTalkColors.Text,
                        modifier = Modifier.weight(1f),
                    )
                    SoonPill()
                }
            }
            sectionFooter(
                "Later you’ll choose for each chat or reply whether to show your name or stay " +
                    "anonymous — while keeping one account.",
            )

            sectionHeader("Safety")
            item {
                SettingsRow(
                    title = "Blocked people",
                    value = if (blocked.isEmpty()) "None" else blocked.size.toString(),
                    onClick = { showBlocked = true },
                )
            }

            sectionHeader("Account")
            item { SoonRow(title = "Sign out") }
            item { SoonRow(title = "Delete account", destructive = true) }
            sectionFooter(
                "Sign out and delete are coming next. See docs/ACCOUNT.md for the full field list.",
            )
        }
    }

    if (showPhotoActions) {
        PhotoActionsDialog(
            canRemove = state.photoURL != null,
            onChoose = {
                showPhotoActions = false
                photoPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            onRemove = {
                showPhotoActions = false
                viewModel.removePhoto()
            },
            onDismiss = { showPhotoActions = false },
        )
    }

    editingName?.let { draft ->
        EditDisplayNameDialog(
            initial = draft,
            isSaving = state.isSavingName,
            onSave = {
                viewModel.saveDisplayName(it)
                editingName = null
            },
            onDismiss = { editingName = null },
        )
    }
}

@Composable
private fun AccountTopBar(title: String, showsBack: Boolean, onNavigate: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MapTalkColors.Surface),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .width(36.dp)
                    .height(4.dp)
                    .background(MapTalkColors.Hairline, RoundedCornerShape(2.dp)),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onNavigate) {
                Icon(
                    painter = painterResource(
                        if (showsBack) R.drawable.ic_arrow_back else R.drawable.ic_close,
                    ),
                    contentDescription = if (showsBack) "Back" else "Close",
                    tint = MapTalkColors.Subtle,
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MapTalkColors.Text,
                modifier = Modifier.weight(1f),
            )
        }
        HorizontalDivider(color = MapTalkColors.Hairline)
    }
}

@Composable
private fun ProfileHeader(state: AccountUiState, seed: String, onEditPhoto: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(MapTalkShapes.Card),
        color = MapTalkColors.Raised,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 18.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(contentAlignment = Alignment.BottomEnd) {
                InitialAvatar(
                    name = state.displayName,
                    seed = seed,
                    size = 88.dp,
                    photoURL = state.photoURL,
                )
                Surface(
                    onClick = onEditPhoto,
                    modifier = Modifier.size(28.dp),
                    shape = CircleShape,
                    color = MapTalkColors.Accent,
                    contentColor = MapTalkColors.Text,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(R.drawable.ic_photo),
                            contentDescription = "Change profile photo",
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = state.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    color = MapTalkColors.Text,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = state.providerLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MapTalkColors.Faint,
                )
            }

            if (state.isSavingPhoto) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = MapTalkColors.Subtle,
                    modifier = Modifier.size(20.dp),
                )
            }
            state.statusMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.statusIsError) MapTalkColors.Danger else MapTalkColors.Subtle,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun SettingsRow(title: String, value: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(MapTalkShapes.Field),
        color = MapTalkColors.Raised,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MapTalkColors.Text,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MapTalkColors.Faint,
                maxLines = 1,
            )
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MapTalkColors.Faint,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun RaisedRow(content: @Composable RowScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(MapTalkShapes.Field),
        color = MapTalkColors.Raised,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
private fun SoonPill() {
    Surface(shape = CircleShape, color = MapTalkColors.Base.copy(alpha = 0.8f)) {
        Text(
            text = "Soon",
            style = MaterialTheme.typography.labelSmall,
            color = MapTalkColors.Faint,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun SoonRow(title: String, destructive: Boolean = false) {
    RaisedRow {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (destructive) MapTalkColors.Danger else MapTalkColors.Text,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "Soon",
            style = MaterialTheme.typography.labelSmall,
            color = MapTalkColors.Faint,
        )
    }
}

@Composable
private fun BlockedPeopleList(
    blocked: List<BlockedPerson>,
    isLoading: Boolean,
    onUnblock: (BlockedPerson) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            isLoading -> item {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = MapTalkColors.Subtle,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            blocked.isEmpty() -> item {
                Text(
                    text = "Nobody blocked yet. Long-press a message to block someone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MapTalkColors.Subtle,
                )
            }
            else -> items(blocked, key = { it.uid }) { person ->
                RaisedRow {
                    InitialAvatar(name = person.displayName, seed = person.uid, size = 32.dp)
                    Text(
                        text = person.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MapTalkColors.Text,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onUnblock(person) }) {
                        Text("Unblock", color = MapTalkColors.Accent)
                    }
                }
            }
        }

        item {
            Text(
                text = "Blocked authors’ chats and messages stay hidden for you only.",
                style = MaterialTheme.typography.labelSmall,
                color = MapTalkColors.Faint,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun PhotoActionsDialog(
    canRemove: Boolean,
    onChoose: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MapTalkColors.Surface,
        titleContentColor = MapTalkColors.Text,
        textContentColor = MapTalkColors.Subtle,
        title = { Text("Profile photo") },
        text = { Text("Show a face on your chats, or keep your initials.") },
        confirmButton = {
            TextButton(onClick = onChoose) {
                Text("Choose photo", color = MapTalkColors.Accent)
            }
        },
        dismissButton = {
            if (canRemove) {
                TextButton(onClick = onRemove) {
                    Text("Remove photo", color = MapTalkColors.Danger)
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = MapTalkColors.Subtle)
                }
            }
        },
    )
}

@Composable
private fun EditDisplayNameDialog(
    initial: String,
    isSaving: Boolean,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    val trimmed = draft.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MapTalkColors.Surface,
        titleContentColor = MapTalkColors.Text,
        title = { Text("What should we call you?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = {
                        if (it.length <= AuthRepository.MAX_DISPLAY_NAME_LENGTH) draft = it
                    },
                    placeholder = { Text("Display name", color = MapTalkColors.Faint) },
                    singleLine = true,
                    shape = RoundedCornerShape(MapTalkShapes.Field),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MapTalkColors.Raised,
                        unfocusedContainerColor = MapTalkColors.Raised,
                        focusedBorderColor = MapTalkColors.Accent,
                        unfocusedBorderColor = MapTalkColors.Hairline,
                        cursorColor = MapTalkColors.Accent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "${trimmed.length}/${AuthRepository.MAX_DISPLAY_NAME_LENGTH}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MapTalkColors.Faint,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(trimmed) },
                enabled = trimmed.isNotEmpty() && !isSaving,
            ) {
                Text("Save", color = MapTalkColors.Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = MapTalkColors.Subtle)
            }
        },
    )
}

private fun LazyListScope.sectionHeader(title: String) {
    item {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MapTalkColors.Subtle,
            modifier = Modifier.padding(top = 14.dp, start = 4.dp, bottom = 2.dp),
        )
    }
}

private fun LazyListScope.sectionFooter(text: String) {
    item {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MapTalkColors.Faint,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 2.dp),
        )
    }
}
