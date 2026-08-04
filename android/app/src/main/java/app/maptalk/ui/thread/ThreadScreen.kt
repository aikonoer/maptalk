package app.maptalk.ui.thread

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.maptalk.R
import app.maptalk.appContainer
import app.maptalk.data.model.Author
import app.maptalk.data.model.Message
import app.maptalk.data.model.PreparedImage
import app.maptalk.ui.relativeTime
import app.maptalk.ui.theme.MapTalkColors
import app.maptalk.ui.theme.MapTalkShapes
import app.maptalk.ui.theme.avatarTint
import app.maptalk.ui.theme.initialsOf
import app.maptalk.ui.theme.tint
import java.io.File
import java.time.Duration

/** Messages from one person, close enough in time, are drawn as one run. */
private val RUN_GAP: Duration = Duration.ofMinutes(5)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(
    threadId: String,
    author: Author,
    onBack: () -> Unit,
) {
    val container = LocalContext.current.appContainer
    val viewModel: ThreadViewModel =
        viewModel(factory = ThreadViewModel.factory(container, threadId))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isPreparingImage by viewModel.isPreparingImage.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var pendingImage by remember { mutableStateOf<PreparedImage?>(null) }
    var fullscreenPath by remember { mutableStateOf<String?>(null) }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            viewModel.prepareImage(uri) { pendingImage = it }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.errors.collect { error ->
            snackbarHostState.showSnackbar(error.message ?: "Message could not be sent")
        }
    }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    fullscreenPath?.let { path ->
        FullscreenImage(
            file = viewModel.mediaFile(path),
            onDismiss = { fullscreenPath = null },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MapTalkColors.Base,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MapTalkColors.Surface,
                    titleContentColor = MapTalkColors.Text,
                    navigationIconContentColor = MapTalkColors.Text,
                ),
                title = {
                    Column {
                        Text(
                            text = state.thread?.title ?: "Chat",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        state.thread?.let { thread ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Text(
                                    text = "${thread.kind.glyph} ${thread.kind.label}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = thread.kind.tint,
                                    maxLines = 1,
                                )
                                Text(
                                    text = "\u00b7 ${thread.authorName} \u00b7 " +
                                        relativeTime(thread.createdAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MapTalkColors.Faint,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                when {
                    state.isLoading -> CircularProgressIndicator(
                        color = MapTalkColors.Subtle,
                        modifier = Modifier.align(Alignment.Center),
                    )

                    state.messages.isEmpty() -> EmptyThread(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(40.dp),
                    )

                    else -> LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 16.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        itemsIndexed(
                            items = state.messages,
                            key = { _, message -> message.id },
                        ) { index, message ->
                            MessageRow(
                                message = message,
                                isMine = message.authorId == author.uid,
                                startsRun = startsRun(state.messages, index),
                                endsRun = endsRun(state.messages, index),
                                resolveMedia = viewModel::mediaFile,
                                onImageClick = { path -> fullscreenPath = path },
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = MapTalkColors.Hairline)
            Composer(
                pendingImage = pendingImage,
                isPreparingImage = isPreparingImage,
                onClearPending = { pendingImage = null },
                onPickPhoto = {
                    photoPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                onSend = { text ->
                    viewModel.send(text, author, pendingImage)
                    pendingImage = null
                },
                modifier = Modifier
                    .background(MapTalkColors.Surface)
                    .navigationBarsPadding(),
            )
        }
    }
}

@Composable
private fun EmptyThread(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = "\uD83D\uDCAC", style = MaterialTheme.typography.titleLarge)
        Text(
            text = "Nobody has said anything yet",
            style = MaterialTheme.typography.titleMedium,
            color = MapTalkColors.Subtle,
        )
        Text(
            text = "Go first \u2014 everyone looking at this spot will see it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MapTalkColors.Faint,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun MessageRow(
    message: Message,
    isMine: Boolean,
    startsRun: Boolean,
    endsRun: Boolean,
    resolveMedia: (String) -> File?,
    onImageClick: (String) -> Unit,
) {
    val imageOnly = message.hasImage && message.text.isEmpty()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (startsRun) 12.dp else 2.dp),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
    ) {
        if (!isMine) {
            if (startsRun) {
                InitialAvatar(name = message.authorName, seed = message.authorId)
            } else {
                Spacer(modifier = Modifier.size(30.dp))
            }
            Spacer(modifier = Modifier.size(8.dp))
        }

        Column(
            horizontalAlignment = if (isMine) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            if (!isMine && startsRun) {
                Text(
                    text = message.authorName,
                    style = MaterialTheme.typography.labelSmall,
                    color = avatarTint(message.authorId),
                    modifier = Modifier.padding(start = 2.dp, bottom = 3.dp),
                )
            }

            Surface(
                shape = MapTalkShapes.bubble(
                    radius = MapTalkShapes.Bubble,
                    tail = when {
                        !endsRun -> null
                        isMine -> MapTalkShapes.Tail.BottomEnd
                        else -> MapTalkShapes.Tail.BottomStart
                    },
                ),
                color = if (isMine) MapTalkColors.Accent else MapTalkColors.Raised,
                contentColor = if (isMine) Color.White else MapTalkColors.Text,
            ) {
                Column(
                    modifier = Modifier.padding(
                        horizontal = if (imageOnly) 4.dp else 14.dp,
                        vertical = if (imageOnly) 4.dp else 9.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (message.hasImage) {
                        message.imagePath?.let { path ->
                            MessageImage(
                                file = resolveMedia(path),
                                width = message.imageWidth,
                                height = message.imageHeight,
                                onClick = { onImageClick(path) },
                            )
                        }
                    }
                    if (message.text.isNotEmpty()) {
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }

            if (endsRun) {
                Text(
                    text = relativeTime(message.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MapTalkColors.Faint,
                    modifier = Modifier.padding(horizontal = 2.dp, vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun MessageImage(
    file: File?,
    width: Int?,
    height: Int?,
    onClick: () -> Unit,
) {
    val displayWidth = 220.dp
    val displayHeight = remember(width, height) {
        if (width != null && height != null && width > 0) {
            minOf(280f, 220f * height / width).dp
        } else {
            160.dp
        }
    }
    val bitmap = remember(file?.absolutePath) {
        file?.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }
    }

    Box(
        modifier = Modifier
            .width(displayWidth)
            .height(displayHeight)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .background(Color.Black.copy(alpha = 0.2f)),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_photo),
                contentDescription = null,
                tint = MapTalkColors.Faint,
            )
        }
    }
}

@Composable
private fun FullscreenImage(file: File?, onDismiss: () -> Unit) {
    val bitmap = remember(file?.absolutePath) {
        file?.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Photo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun InitialAvatar(name: String, seed: String, size: Int = 30) {
    val tint = avatarTint(seed)
    Surface(
        modifier = Modifier.size(size.dp),
        shape = CircleShape,
        color = tint.copy(alpha = 0.18f),
        contentColor = tint,
        border = BorderStroke(1.dp, tint.copy(alpha = 0.35f)),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text = initialsOf(name), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun Composer(
    pendingImage: PreparedImage?,
    isPreparingImage: Boolean,
    onClearPending: () -> Unit,
    onPickPhoto: () -> Unit,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember { mutableStateOf("") }
    val canSend = pendingImage != null || draft.isNotBlank()
    val sendContainer by animateColorAsState(
        if (canSend) MapTalkColors.Accent else MapTalkColors.Raised,
        label = "sendContainer",
    )
    val previewBitmap = remember(pendingImage) {
        pendingImage?.let {
            BitmapFactory.decodeByteArray(it.jpegBytes, 0, it.jpegBytes.size)
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (previewBitmap != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Image(
                    bitmap = previewBitmap.asImageBitmap(),
                    contentDescription = "Selected photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onClearPending) {
                    Text(
                        text = "\u2715",
                        color = MapTalkColors.Subtle,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            IconButton(
                onClick = onPickPhoto,
                enabled = !isPreparingImage,
                modifier = Modifier.size(40.dp),
            ) {
                if (isPreparingImage) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = MapTalkColors.Subtle,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_photo),
                        contentDescription = "Add a photo",
                        tint = MapTalkColors.Subtle,
                    )
                }
            }

            OutlinedTextField(
                value = draft,
                onValueChange = {
                    if (it.length <= ThreadViewModel.MAX_MESSAGE_LENGTH) draft = it
                },
                placeholder = {
                    Text(
                        if (pendingImage == null) "Say something" else "Add a caption",
                        color = MapTalkColors.Faint,
                    )
                },
                maxLines = 5,
                shape = CircleShape,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = true),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MapTalkColors.Raised,
                    unfocusedContainerColor = MapTalkColors.Raised,
                    focusedBorderColor = MapTalkColors.Accent,
                    unfocusedBorderColor = MapTalkColors.Hairline,
                    cursorColor = MapTalkColors.Accent,
                ),
                modifier = Modifier.weight(1f),
            )

            Surface(
                onClick = {
                    onSend(draft)
                    draft = ""
                },
                enabled = canSend && !isPreparingImage,
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                color = sendContainer,
                contentColor = if (canSend) Color.White else MapTalkColors.Faint,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_send),
                        contentDescription = "Send",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

private fun startsRun(messages: List<Message>, index: Int): Boolean =
    index == 0 || !messages[index].follows(messages[index - 1])

private fun endsRun(messages: List<Message>, index: Int): Boolean =
    index == messages.lastIndex || !messages[index + 1].follows(messages[index])

/** Same person, close enough in time to read as one thought. */
private fun Message.follows(previous: Message): Boolean {
    if (authorId != previous.authorId) return false
    val mine = createdAt ?: return true
    val theirs = previous.createdAt ?: return true
    return Duration.between(theirs, mine) < RUN_GAP
}
