package app.maptalk.ui.thread

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.view.TextureView
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import app.maptalk.core.DeepLinkBus
import app.maptalk.core.ActivityHeat
import app.maptalk.core.ThreadLink
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import app.maptalk.R
import app.maptalk.appContainer
import app.maptalk.data.ThreadVideoPlayer
import app.maptalk.data.model.Author
import app.maptalk.data.model.ChatThread
import app.maptalk.data.model.Message
import app.maptalk.data.model.MessageKind
import app.maptalk.data.model.PreparedAudio
import app.maptalk.data.model.PreparedImage
import app.maptalk.data.model.ReactionEmoji
import app.maptalk.data.model.ReportReason
import app.maptalk.data.model.ReportTargetType
import app.maptalk.data.model.StickerPack
import app.maptalk.ui.InitialAvatar
import app.maptalk.ui.PlaceLabelLine
import app.maptalk.ui.relativeTime
import app.maptalk.ui.theme.MapTalkColors
import app.maptalk.ui.theme.MapTalkShapes
import app.maptalk.ui.theme.avatarTint
import app.maptalk.ui.theme.tint
import coil.compose.AsyncImage
import java.io.File
import java.time.Duration
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Messages from one person, close enough in time, are drawn as one run. */
private val RUN_GAP: Duration = Duration.ofMinutes(5)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ThreadScreen(
    threadId: String,
    author: Author,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onThreadDeleted: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val container = context.appContainer
    // Key by threadId so opening chat B after chat A does not reuse A's ViewModel
    // (Activity-scoped store would otherwise keep the first instance forever).
    val viewModel: ThreadViewModel =
        viewModel(key = threadId, factory = ThreadViewModel.factory(container, threadId))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val authorPhotos by viewModel.authorPhotos.collectAsStateWithLifecycle()
    val isPreparingImage by viewModel.isPreparingImage.collectAsStateWithLifecycle()
    val isPreparingVideo by viewModel.isPreparingVideo.collectAsStateWithLifecycle()
    val videoSend by viewModel.videoSend.collectAsStateWithLifecycle()
    val pendingVideo by viewModel.pendingVideo.collectAsStateWithLifecycle()
    val videoTrim by viewModel.videoTrim.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var pendingImage by remember { mutableStateOf<PreparedImage?>(null) }
    var fullscreenPath by remember { mutableStateOf<String?>(null) }
    var fullscreenVideoPath by remember { mutableStateOf<String?>(null) }
    var showStickers by remember { mutableStateOf(false) }
    var longPressTarget by remember { mutableStateOf<Message?>(null) }
    var reportTarget by remember { mutableStateOf<ReportTarget?>(null) }
    var blockConfirm by remember { mutableStateOf<Pair<String, String>?>(null) }
    var reportThanks by remember { mutableStateOf(false) }
    var showBackgroundPicker by remember { mutableStateOf(false) }
    var showThreadMenu by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<Message?>(null) }
    var editDraft by remember { mutableStateOf("") }
    var deleteMessageConfirm by remember { mutableStateOf<Message?>(null) }
    var deleteThreadConfirm by remember { mutableStateOf(false) }
    var chatBackground by remember {
        mutableStateOf(ChatBackgroundStore.current(context))
    }
    var isRecording by remember { mutableStateOf(false) }
    var recordElapsedMs by remember { mutableIntStateOf(0) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordFile by remember { mutableStateOf<File?>(null) }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            viewModel.prepareImage(uri) { pendingImage = it }
        }
    }

    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            viewModel.prepareAndSendVideo(uri, author)
        }
    }

    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { saved ->
        val uri = pendingCameraUri
        pendingCameraUri = null
        if (saved && uri != null) {
            viewModel.prepareImage(uri) { pendingImage = it }
        }
    }

    fun launchCameraCapture() {
        val dir = File(context.cacheDir, "camera").also { it.mkdirs() }
        val file = File(dir, "capture-${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        pendingCameraUri = uri
        takePicture.launch(uri)
    }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) launchCameraCapture()
    }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startRecording(context)?.let { (rec, file) ->
                recorder = rec
                recordFile = file
                isRecording = true
                recordElapsedMs = 0
            }
        }
    }

    fun stopAndSendVoice() {
        val rec = recorder ?: return
        val file = recordFile
        runCatching { rec.stop() }
        rec.release()
        recorder = null
        isRecording = false
        val duration = recordElapsedMs.coerceAtLeast(500)
        recordElapsedMs = 0
        if (file != null && file.exists() && file.length() > 0) {
            viewModel.send(
                text = "",
                author = author,
                audio = PreparedAudio(bytes = file.readBytes(), durationMs = duration),
            )
        }
        file?.delete()
        recordFile = null
    }

    fun cancelVoice() {
        recorder?.runCatching {
            stop()
            release()
        }
        recorder = null
        isRecording = false
        recordElapsedMs = 0
        recordFile?.delete()
        recordFile = null
    }

    DisposableEffect(Unit) {
        onDispose { cancelVoice() }
    }

    LaunchedEffect(isRecording) {
        if (!isRecording) return@LaunchedEffect
        while (isRecording) {
            delay(200)
            recordElapsedMs += 200
            if (recordElapsedMs >= 60_000) {
                stopAndSendVoice()
                break
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.errors.collect { error ->
            val message = error.message?.takeIf { it.isNotBlank() }
                ?: "Something went wrong"
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(state.shouldDismiss) {
        if (state.shouldDismiss) {
            state.deletedThreadId?.let(onThreadDeleted)
            onBack()
        }
    }

    // Stick to the latest bubble on first paint and when a new tip message arrives while
    // the user is already near the bottom. Loading older history must not yank the camera.
    var pinnedBottom by remember { mutableStateOf(true) }
    var lastTipId by remember { mutableStateOf<String?>(null) }
    // LazyColumn opens at index 0 — wait until we have scrolled to the tip before paging up.
    var historyLoadEnabled by remember { mutableStateOf(false) }
    LaunchedEffect(state.messages.lastOrNull()?.id) {
        val tipId = state.messages.lastOrNull()?.id ?: return@LaunchedEffect
        val firstOpen = lastTipId == null
        val tipGrew = lastTipId != null && tipId != lastTipId
        lastTipId = tipId
        if (firstOpen || (tipGrew && pinnedBottom)) {
            listState.scrollToItem(state.messages.lastIndex)
            pinnedBottom = true
            if (firstOpen) {
                delay(350)
                historyLoadEnabled = true
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= (info.totalItemsCount - 3).coerceAtLeast(0)
        }
            .distinctUntilChanged()
            .collect { nearBottom -> pinnedBottom = nearBottom }
    }
    LaunchedEffect(listState, state.hasMoreHistory, state.isLoadingOlder, historyLoadEnabled) {
        if (!historyLoadEnabled) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { index ->
                if (index <= 2 && state.hasMoreHistory && !state.isLoadingOlder) {
                    viewModel.loadOlder()
                }
            }
    }
    LaunchedEffect(Unit) {
        viewModel.historyPrepended.collect { prepended ->
            val index = listState.firstVisibleItemIndex
            val offset = listState.firstVisibleItemScrollOffset
            listState.scrollToItem(index + prepended, offset)
        }
    }

    fullscreenPath?.let { path ->
        FullscreenImage(
            path = path,
            file = viewModel.mediaFile(path),
            onDismiss = { fullscreenPath = null },
        )
    }

    fullscreenVideoPath?.let { path ->
        FullscreenVideoPlayer(
            path = path,
            onDismiss = { fullscreenVideoPath = null },
        )
    }

    videoTrim?.let { trim ->
        VideoTrimSheet(
            uri = trim.uri,
            durationMs = trim.durationMs,
            onConfirm = viewModel::confirmVideoTrim,
            onDismiss = viewModel::declineVideoTrim,
        )
    }

    longPressTarget?.let { message ->
        MessageLongPressDialog(
            message = message,
            isMine = message.authorId == author.uid,
            myUid = author.uid,
            resolveMedia = viewModel::mediaFile,
            onDismiss = { longPressTarget = null },
            onReact = { emoji ->
                viewModel.toggleReaction(emoji, message, author)
            },
            onReply = {
                viewModel.setReply(message)
            },
            onEdit = {
                editDraft = message.text
                editTarget = message
            },
            onDelete = {
                deleteMessageConfirm = message
            },
            onReport = {
                reportTarget = ReportTarget.Message(message)
            },
            onBlock = {
                blockConfirm = message.authorId to message.authorName
            },
        )
    }

    reportTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { reportTarget = null },
            containerColor = MapTalkColors.Surface,
            title = { Text("Why are you reporting this?", color = MapTalkColors.Text) },
            text = {
                Column {
                    ReportReason.ALL.forEach { reason ->
                        TextButton(
                            onClick = {
                                when (target) {
                                    is ReportTarget.Message -> viewModel.report(
                                        type = ReportTargetType.MESSAGE,
                                        targetId = target.message.id,
                                        targetAuthorId = target.message.authorId,
                                        reason = reason,
                                        author = author,
                                    )
                                    is ReportTarget.Thread -> viewModel.report(
                                        type = ReportTargetType.THREAD,
                                        targetId = target.thread.id,
                                        targetAuthorId = target.thread.authorId,
                                        reason = reason,
                                        author = author,
                                    )
                                }
                                reportTarget = null
                                reportThanks = true
                            },
                        ) {
                            Text(reason.label, color = MapTalkColors.Text)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { reportTarget = null }) {
                    Text("Cancel", color = MapTalkColors.Subtle)
                }
            },
        )
    }

    blockConfirm?.let { (uid, name) ->
        AlertDialog(
            onDismissRequest = { blockConfirm = null },
            containerColor = MapTalkColors.Surface,
            title = { Text("Block $name?", color = MapTalkColors.Text) },
            text = {
                Text(
                    "Their chats and messages will disappear for you. Unblock anytime from Settings.",
                    color = MapTalkColors.Subtle,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.block(uid, name, author)
                    blockConfirm = null
                }) {
                    Text("Block", color = MapTalkColors.Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { blockConfirm = null }) {
                    Text("Cancel", color = MapTalkColors.Subtle)
                }
            },
        )
    }

    if (reportThanks) {
        AlertDialog(
            onDismissRequest = { reportThanks = false },
            containerColor = MapTalkColors.Surface,
            title = { Text("Thanks — we’ll take a look", color = MapTalkColors.Text) },
            text = {
                Text(
                    "Your report was sent. You can also block the person if you don’t want to see them.",
                    color = MapTalkColors.Subtle,
                )
            },
            confirmButton = {
                TextButton(onClick = { reportThanks = false }) {
                    Text("OK", color = MapTalkColors.Accent)
                }
            },
        )
    }

    if (showBackgroundPicker) {
        ChatBackgroundPickerSheet(
            selected = chatBackground,
            onSelect = { style ->
                chatBackground = style
                ChatBackgroundStore.set(context, style)
                showBackgroundPicker = false
            },
            onDismiss = { showBackgroundPicker = false },
        )
    }

    editTarget?.let { message ->
        val trimmed = editDraft.trim()
        val canSave = message.kind == MessageKind.IMAGE || trimmed.isNotEmpty()
        AlertDialog(
            onDismissRequest = { editTarget = null },
            containerColor = MapTalkColors.Surface,
            title = { Text("Edit message", color = MapTalkColors.Text) },
            text = {
                OutlinedTextField(
                    value = editDraft,
                    onValueChange = { editDraft = it },
                    placeholder = {
                        Text(
                            if (message.kind == MessageKind.IMAGE) "Caption" else "Message",
                            color = MapTalkColors.Faint,
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MapTalkColors.Text,
                        unfocusedTextColor = MapTalkColors.Text,
                        focusedBorderColor = MapTalkColors.Accent,
                        unfocusedBorderColor = MapTalkColors.Hairline,
                        cursorColor = MapTalkColors.Accent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.editMessage(message, editDraft)
                        editTarget = null
                    },
                    enabled = canSave,
                ) {
                    Text("Save", color = if (canSave) MapTalkColors.Accent else MapTalkColors.Faint)
                }
            },
            dismissButton = {
                TextButton(onClick = { editTarget = null }) {
                    Text("Cancel", color = MapTalkColors.Subtle)
                }
            },
        )
    }

    deleteMessageConfirm?.let { message ->
        AlertDialog(
            onDismissRequest = { deleteMessageConfirm = null },
            containerColor = MapTalkColors.Surface,
            title = { Text("Delete message?", color = MapTalkColors.Text) },
            text = {
                Text(
                    "This message will be removed for everyone.",
                    color = MapTalkColors.Subtle,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteMessage(message)
                    deleteMessageConfirm = null
                }) {
                    Text("Delete", color = MapTalkColors.Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteMessageConfirm = null }) {
                    Text("Cancel", color = MapTalkColors.Subtle)
                }
            },
        )
    }

    if (deleteThreadConfirm) {
        AlertDialog(
            onDismissRequest = { deleteThreadConfirm = false },
            containerColor = MapTalkColors.Surface,
            title = { Text("Delete chat?", color = MapTalkColors.Text) },
            text = {
                Text(
                    "This chat and all its messages will be permanently deleted.",
                    color = MapTalkColors.Subtle,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteThread()
                    deleteThreadConfirm = false
                }) {
                    Text("Delete", color = MapTalkColors.Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteThreadConfirm = false }) {
                    Text("Cancel", color = MapTalkColors.Subtle)
                }
            },
        )
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MapTalkColors.Base,
        // Floating MapTalkBottomSheet already clears the nav/status bars.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
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
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(R.drawable.ic_close),
                            contentDescription = "Close",
                            tint = MapTalkColors.Subtle,
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.thread?.title ?: "Chat",
                            style = MaterialTheme.typography.titleMedium,
                            color = MapTalkColors.Text,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        state.thread?.let { thread ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                val heat = ActivityHeat.of(thread.lastMessageAt)
                                if (heat != ActivityHeat.COOL) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .background(
                                                MapTalkColors.Accent.copy(
                                                    alpha = if (heat == ActivityHeat.HOT) 1f else 0.55f,
                                                ),
                                                CircleShape,
                                            ),
                                    )
                                }
                                Text(
                                    text = "${thread.kind.glyph} ${thread.kind.label}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = thread.kind.tint,
                                    maxLines = 1,
                                )
                                Text(
                                    text = "\u00b7 ${thread.authorName}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MapTalkColors.Faint,
                                    maxLines = 1,
                                )
                            }

                            PlaceLabelLine(
                                point = thread.position,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { showThreadMenu = true }) {
                            Icon(
                                painterResource(R.drawable.ic_more),
                                contentDescription = "Chat options",
                                tint = MapTalkColors.Subtle,
                            )
                        }
                        DropdownMenu(
                            expanded = showThreadMenu,
                            onDismissRequest = { showThreadMenu = false },
                            containerColor = MapTalkColors.Surface,
                        ) {
                            DropdownMenuItem(
                                text = { Text("Share chat", color = MapTalkColors.Text) },
                                leadingIcon = {
                                    Icon(
                                        painterResource(R.drawable.ic_share),
                                        contentDescription = null,
                                        tint = MapTalkColors.Subtle,
                                    )
                                },
                                onClick = {
                                    showThreadMenu = false
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, ThreadLink.url(threadId))
                                    }
                                    context.startActivity(
                                        Intent.createChooser(send, "Share chat"),
                                    )
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Background", color = MapTalkColors.Text) },
                                leadingIcon = {
                                    Icon(
                                        painterResource(R.drawable.ic_wallpaper),
                                        contentDescription = null,
                                        tint = MapTalkColors.Subtle,
                                    )
                                },
                                onClick = {
                                    showThreadMenu = false
                                    showBackgroundPicker = true
                                },
                            )
                            val thread = state.thread
                            if (thread != null && thread.authorId != author.uid) {
                                HorizontalDivider(color = MapTalkColors.Hairline)
                                DropdownMenuItem(
                                    text = { Text("Report chat", color = MapTalkColors.Text) },
                                    onClick = {
                                        showThreadMenu = false
                                        reportTarget = ReportTarget.Thread(thread)
                                    },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "Block ${thread.authorName}",
                                            color = MapTalkColors.Danger,
                                        )
                                    },
                                    onClick = {
                                        showThreadMenu = false
                                        blockConfirm = thread.authorId to thread.authorName
                                    },
                                )
                            } else if (thread != null && thread.authorId == author.uid) {
                                HorizontalDivider(color = MapTalkColors.Hairline)
                                DropdownMenuItem(
                                    text = {
                                        Text("Delete chat", color = MapTalkColors.Danger)
                                    },
                                    onClick = {
                                        showThreadMenu = false
                                        deleteThreadConfirm = true
                                    },
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(color = MapTalkColors.Hairline)
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                ChatBackgroundLayer(
                    style = chatBackground,
                    modifier = Modifier.fillMaxSize(),
                )
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

                    else -> Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
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
                                    myUid = author.uid,
                                    startsRun = startsRun(state.messages, index),
                                    endsRun = endsRun(state.messages, index),
                                    isLifted = longPressTarget?.id == message.id,
                                    authorPhotoURL = message.authorPhotoURL
                                        ?: authorPhotos[message.authorId],
                                    resolveMedia = viewModel::mediaFile,
                                    onImageClick = { path -> fullscreenPath = path },
                                    onOpenVideo = { path -> fullscreenVideoPath = path },
                                    onReply = { viewModel.setReply(message) },
                                    onLongPress = { longPressTarget = message },
                                    onToggleReaction = { emoji ->
                                        viewModel.toggleReaction(emoji, message, author)
                                    },
                                )
                            }
                        }
                        if (state.isLoadingOlder) {
                            CircularProgressIndicator(
                                color = MapTalkColors.Subtle,
                                strokeWidth = 2.dp,
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 12.dp)
                                    .size(18.dp),
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = MapTalkColors.Hairline)
            Composer(
                pendingImage = pendingImage,
                pendingVideo = pendingVideo,
                replyTarget = state.replyTarget,
                showStickers = showStickers,
                isPreparingImage = isPreparingImage,
                isPreparingVideo = isPreparingVideo,
                videoSend = videoSend,
                onCancelVideo = viewModel::cancelVideoSend,
                onRetryVideo = viewModel::retryVideoSend,
                isRecording = isRecording,
                recordElapsedMs = recordElapsedMs,
                onClearPending = { pendingImage = null },
                onClearPendingVideo = viewModel::removePendingVideo,
                onClearReply = viewModel::clearReply,
                onToggleStickers = { showStickers = !showStickers },
                onPickSticker = { glyph ->
                    viewModel.send("", author, sticker = glyph)
                    showStickers = false
                },
                onPickPhoto = {
                    photoPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                onTakePhoto = {
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.CAMERA,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        launchCameraCapture()
                    } else {
                        cameraPermission.launch(Manifest.permission.CAMERA)
                    }
                },
                onPickVideo = {
                    videoPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                    )
                },
                onStartRecord = {
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        startRecording(context)?.let { (rec, file) ->
                            recorder = rec
                            recordFile = file
                            isRecording = true
                            recordElapsedMs = 0
                        }
                    } else {
                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onCancelRecord = { cancelVoice() },
                onSendRecord = { stopAndSendVoice() },
                onSend = { text ->
                    if (pendingVideo != null) {
                        viewModel.sendPendingVideo(text, author)
                    } else {
                        viewModel.send(text, author, pendingImage)
                        pendingImage = null
                    }
                    showStickers = false
                },
                // Sheet already floats above the nav bar (MapTalkBottomSheet inset).
                modifier = Modifier.background(MapTalkColors.Surface),
            )
        }
    }
}

private fun startRecording(context: android.content.Context): Pair<MediaRecorder, File>? {
    val file = File(context.cacheDir, "voice-${System.currentTimeMillis()}.m4a")
    val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        MediaRecorder(context)
    } else {
        @Suppress("DEPRECATION")
        MediaRecorder()
    }
    return try {
        recorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(22_050)
            setAudioChannels(1)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        recorder to file
    } catch (_: Exception) {
        runCatching { recorder.release() }
        file.delete()
        null
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
            text = "Go first \u2014 photos, stickers, voice, the lot.",
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
    myUid: String,
    startsRun: Boolean,
    endsRun: Boolean,
    isLifted: Boolean = false,
    authorPhotoURL: String? = message.authorPhotoURL,
    resolveMedia: (String) -> File?,
    onImageClick: (String) -> Unit,
    onOpenVideo: (String) -> Unit = {},
    onReply: () -> Unit,
    onLongPress: () -> Unit,
    onToggleReaction: (String) -> Unit,
) {
    val mediaOnly = (message.hasImage || message.hasVideo) &&
        message.text.isEmpty() &&
        message.reply == null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (startsRun) 12.dp else 2.dp)
            .graphicsLayer { alpha = if (isLifted) 0f else 1f },
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
    ) {
        if (!isMine) {
            if (startsRun) {
                InitialAvatar(
                    name = message.authorName,
                    seed = message.authorId,
                    size = 30.dp,
                    photoURL = authorPhotoURL,
                )
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

            if (message.isSticker) {
                Text(
                    text = message.text,
                    fontSize = 48.sp,
                    modifier = Modifier.combinedClickable(
                        onClick = {},
                        onLongClick = onLongPress,
                    ),
                )
            } else {
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
                    modifier = Modifier.combinedClickable(
                        onClick = {},
                        onLongClick = onLongPress,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(
                            horizontal = if (mediaOnly) 4.dp else 14.dp,
                            vertical = if (mediaOnly) 4.dp else 9.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        message.reply?.let { reply ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (isMine) Color.White.copy(alpha = 0.12f)
                                        else MapTalkColors.Base.copy(alpha = 0.55f),
                                        RoundedCornerShape(10.dp),
                                    )
                                    .padding(8.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height(36.dp)
                                        .background(
                                            if (isMine) Color.White.copy(alpha = 0.7f)
                                            else MapTalkColors.Accent,
                                            RoundedCornerShape(2.dp),
                                        ),
                                )
                                Column {
                                    Text(
                                        text = reply.authorName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isMine) Color.White.copy(alpha = 0.85f)
                                        else MapTalkColors.Accent,
                                    )
                                    Text(
                                        text = reply.text,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isMine) Color.White.copy(alpha = 0.75f)
                                        else MapTalkColors.Subtle,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }

                        if (message.hasImage) {
                            message.imagePath?.let { path ->
                                val remote = path.takeIf {
                                    it.startsWith("http://") || it.startsWith("https://")
                                }
                                MessageImage(
                                    file = if (remote == null) resolveMedia(path) else null,
                                    remoteUrl = remote,
                                    width = message.imageWidth,
                                    height = message.imageHeight,
                                    onClick = { onImageClick(path) },
                                )
                            }
                        }

                        if (message.hasVideo) {
                            message.videoPath?.let { path ->
                                val playUri = when {
                                    path.startsWith("http://") || path.startsWith("https://") -> path
                                    else -> resolveMedia(path)?.let { Uri.fromFile(it).toString() } ?: path
                                }
                                VideoBubble(
                                    path = path,
                                    file = resolveMedia(path),
                                    durationMs = message.videoDurationMs ?: 0,
                                    width = message.videoWidth,
                                    height = message.videoHeight,
                                    isSending = message.isLocalPending,
                                    onOpenFullscreen = { onOpenVideo(playUri) },
                                )
                            }
                        }

                        if (message.hasVoice) {
                            message.audioPath?.let { path ->
                                VoiceBubble(
                                    path = path,
                                    file = resolveMedia(path),
                                    durationMs = message.audioDurationMs ?: 0,
                                    isMine = isMine,
                                )
                            }
                        }

                        if (message.text.isNotEmpty() && !message.hasVoice) {
                            Text(
                                text = message.text,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            }

            if (message.reactions.isNotEmpty()) {
                ReactionStrip(
                    reactions = message.reactions,
                    myUid = myUid,
                    onToggle = onToggleReaction,
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (endsRun) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 2.dp, vertical = 3.dp),
                    ) {
                        Text(
                            text = if (message.isLocalPending) "Sending…" else relativeTime(message.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MapTalkColors.Faint,
                        )
                        if (message.isEdited && !message.isLocalPending) {
                            Text(
                                text = "· Edited",
                                style = MaterialTheme.typography.labelSmall,
                                color = MapTalkColors.Faint,
                            )
                        }
                    }
                }
                if (!message.isLocalPending) {
                    Text(
                        text = "Reply",
                        style = MaterialTheme.typography.labelSmall,
                        color = MapTalkColors.Subtle,
                        modifier = Modifier
                            .clickable(onClick = onReply)
                            .padding(vertical = 3.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ReactionStrip(
    reactions: Map<String, List<String>>,
    myUid: String,
    onToggle: (String) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(top = 4.dp),
    ) {
        reactions.keys.sorted().forEach { emoji ->
            val uids = reactions[emoji].orEmpty()
            val mine = uids.contains(myUid)
            Surface(
                onClick = { onToggle(emoji) },
                shape = CircleShape,
                color = MapTalkColors.Raised,
                border = BorderStroke(
                    1.dp,
                    if (mine) MapTalkColors.Accent.copy(alpha = 0.7f) else MapTalkColors.Hairline,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = emoji, fontSize = 13.sp)
                    Text(
                        text = "${uids.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (mine) MapTalkColors.Accent else MapTalkColors.Subtle,
                    )
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoBubble(
    path: String,
    file: File?,
    durationMs: Int,
    width: Int?,
    height: Int?,
    isSending: Boolean = false,
    onOpenFullscreen: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val displayWidth = 220.dp
    val displayHeight = remember(width, height) {
        if (width != null && height != null && width > 0) {
            minOf(280f, 220f * height / width).dp
        } else {
            160.dp
        }
    }
    val absoluteFile = remember(path, file?.absolutePath) {
        when {
            file != null && file.exists() -> file
            path.startsWith("/") -> File(path).takeIf { it.exists() }
            else -> null
        }
    }
    val playUri = remember(path, absoluteFile?.absolutePath) {
        when {
            path.startsWith("http://") || path.startsWith("https://") -> path
            absoluteFile != null -> Uri.fromFile(absoluteFile).toString()
            else -> null
        }
    }
    val poster = remember(path, absoluteFile?.absolutePath) {
        loadVideoPoster(context, path, absoluteFile)
    }

    Box(
        modifier = Modifier
            .width(displayWidth)
            .height(displayHeight)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.35f))
            .then(
                if (isSending) {
                    Modifier
                } else {
                    Modifier.clickable {
                        playUri?.let(onOpenFullscreen)
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (poster != null) {
            Image(
                bitmap = poster.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (isSending) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
            )
            Text(
                text = "Sending…",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_play),
                contentDescription = "Play video, ${formatDuration(durationMs)}",
                tint = Color.White,
                modifier = Modifier
                    .size(54.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                    .border(1.5.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                    .padding(15.dp),
            )
            Text(
                text = formatDuration(durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

private fun loadVideoPoster(
    context: android.content.Context,
    path: String,
    file: File?,
): android.graphics.Bitmap? {
    val retriever = android.media.MediaMetadataRetriever()
    return try {
        when {
            path.startsWith("http://") || path.startsWith("https://") ->
                retriever.setDataSource(path, HashMap())
            file != null && file.exists() ->
                retriever.setDataSource(file.absolutePath)
            else -> return null
        }
        retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

@Composable
private fun VoiceBubble(
    path: String,
    file: File?,
    durationMs: Int,
    isMine: Boolean,
) {
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(false) }

    DisposableEffect(path) {
        onDispose {
            player?.release()
            player = null
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            onClick = {
                if (isPlaying) {
                    player?.pause()
                    isPlaying = false
                    return@Surface
                }
                val source = when {
                    path.startsWith("http://") || path.startsWith("https://") -> path
                    file != null && file.exists() -> file.absolutePath
                    else -> return@Surface
                }
                runCatching {
                    player?.release()
                    val mp = MediaPlayer().apply {
                        setDataSource(source)
                        prepare()
                        setOnCompletionListener {
                            isPlaying = false
                        }
                        start()
                    }
                    player = mp
                    isPlaying = true
                }
            },
            shape = CircleShape,
            color = if (isMine) Color.White.copy(alpha = 0.2f) else MapTalkColors.Accent.copy(alpha = 0.15f),
            modifier = Modifier.size(32.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(
                        if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                    ),
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = if (isMine) Color.White else MapTalkColors.Accent,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Box(
            modifier = Modifier
                .width(110.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(
                    if (isMine) Color.White.copy(alpha = 0.35f) else MapTalkColors.Hairline,
                ),
        )
        Text(
            text = formatDuration(durationMs),
            style = MaterialTheme.typography.labelSmall,
            color = if (isMine) Color.White.copy(alpha = 0.85f) else MapTalkColors.Subtle,
        )
    }
}

private fun formatDuration(ms: Int): String {
    val total = (ms / 1000).coerceAtLeast(1)
    return "%d:%02d".format(total / 60, total % 60)
}

@Composable
private fun MessageImage(
    file: File?,
    remoteUrl: String?,
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
    val localBitmap = remember(file?.absolutePath) {
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
        when {
            localBitmap != null -> Image(
                bitmap = localBitmap.asImageBitmap(),
                contentDescription = "Photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            remoteUrl != null -> AsyncImage(
                model = remoteUrl,
                contentDescription = "Photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            else -> Icon(
                painter = painterResource(R.drawable.ic_photo),
                contentDescription = null,
                tint = MapTalkColors.Faint,
            )
        }
    }
}

@Composable
private fun FullscreenImage(path: String, file: File?, onDismiss: () -> Unit) {
    val remote = path.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    val bitmap = remember(file?.absolutePath) {
        if (remote != null) null
        else file?.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }
    }
    val density = LocalDensity.current
    val dismissThreshold = with(density) { 110.dp.toPx() }
    val dragTravel = with(density) { 240.dp.toPx() }
    val scope = rememberCoroutineScope()
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var dismissDrag by remember { mutableFloatStateOf(0f) }

    fun resetZoom() {
        scale = 1f
        offset = Offset.Zero
        dismissDrag = 0f
    }

    fun springDismissBack() {
        scope.launch {
            animate(
                initialValue = dismissDrag,
                targetValue = 0f,
                animationSpec = spring(dampingRatio = 0.86f),
            ) { value, _ -> dismissDrag = value }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > 1.1f) {
                                resetZoom()
                            } else {
                                scale = 2.5f
                                offset = Offset.Zero
                                dismissDrag = 0f
                            }
                        },
                    )
                }
                .pointerInput(Unit) {
                    // Pinch/zoom + pan when zoomed; vertical drag dismisses at 1x.
                    // detectTransformGestures returns when fingers lift — then we settle dismiss.
                    while (true) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val nextScale = (scale * zoom).coerceIn(1f, 4f)
                            if (nextScale > 1.05f) {
                                scale = nextScale
                                val maxX = (size.width * (scale - 1f)) / 2f
                                val maxY = (size.height * (scale - 1f)) / 2f
                                offset = Offset(
                                    x = (offset.x + pan.x).coerceIn(-maxX, maxX),
                                    y = (offset.y + pan.y).coerceIn(-maxY, maxY),
                                )
                                dismissDrag = 0f
                            } else {
                                scale = 1f
                                offset = Offset.Zero
                                dismissDrag = maxOf(0f, dismissDrag + pan.y)
                            }
                        }
                        if (scale <= 1.05f) {
                            if (dismissDrag > dismissThreshold) {
                                onDismiss()
                            } else if (dismissDrag > 0f) {
                                springDismissBack()
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val progress = (dismissDrag / dragTravel).coerceIn(0f, 1f)
            val shrink = if (scale <= 1.05f) 1f - progress * 0.14f else 1f
            val imageModifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
                .graphicsLayer {
                    scaleX = scale * shrink
                    scaleY = scale * shrink
                    translationX = offset.x
                    translationY = offset.y + dismissDrag
                }
            when {
                bitmap != null -> Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Photo",
                    contentScale = ContentScale.Fit,
                    modifier = imageModifier,
                )
                remote != null -> AsyncImage(
                    model = remote,
                    contentDescription = "Photo",
                    contentScale = ContentScale.Fit,
                    modifier = imageModifier,
                )
            }
        }
    }
}

@Composable
private fun Composer(
    pendingImage: PreparedImage?,
    pendingVideo: PendingVideo?,
    replyTarget: Message?,
    showStickers: Boolean,
    isPreparingImage: Boolean,
    isPreparingVideo: Boolean,
    videoSend: VideoSendUi,
    onCancelVideo: () -> Unit,
    onRetryVideo: () -> Unit,
    isRecording: Boolean,
    recordElapsedMs: Int,
    onClearPending: () -> Unit,
    onClearPendingVideo: () -> Unit,
    onClearReply: () -> Unit,
    onToggleStickers: () -> Unit,
    onPickSticker: (String) -> Unit,
    onPickPhoto: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickVideo: () -> Unit,
    onStartRecord: () -> Unit,
    onCancelRecord: () -> Unit,
    onSendRecord: () -> Unit,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember { mutableStateOf("") }
    var mediaExpanded by remember { mutableStateOf(true) }
    val draftBlank = draft.isBlank()
    val mediaTrayVisible = draftBlank || mediaExpanded
    val canSend = pendingImage != null || pendingVideo != null || draft.isNotBlank()

    LaunchedEffect(draftBlank) {
        if (draftBlank) {
            mediaExpanded = true
        }
    }
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
        videoSend.title?.let { title ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .background(MapTalkColors.Raised, RoundedCornerShape(16.dp))
                    .border(
                        width = 1.dp,
                        color = if (videoSend.isBusy) {
                            MapTalkColors.Accent.copy(alpha = 0.45f)
                        } else {
                            MapTalkColors.Hairline
                        },
                        shape = RoundedCornerShape(16.dp),
                    )
                    .padding(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MapTalkColors.Surface),
                        contentAlignment = Alignment.Center,
                    ) {
                        videoSend.preview?.let { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                filterQuality = FilterQuality.High,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (videoSend.phase == VideoSendPhase.Failed) {
                                MapTalkColors.Danger
                            } else {
                                MapTalkColors.Text
                            },
                        )
                        videoSend.subtitle?.let { sub ->
                            Text(
                                text = sub,
                                style = MaterialTheme.typography.labelMedium,
                                color = MapTalkColors.Subtle,
                            )
                        }
                    }
                    if (videoSend.isBusy) {
                        TextButton(onClick = onCancelVideo) {
                            Text("Cancel", color = MapTalkColors.Subtle)
                        }
                    } else if (videoSend.phase == VideoSendPhase.Failed) {
                        TextButton(onClick = onRetryVideo) {
                            Text("Try again", color = MapTalkColors.Accent)
                        }
                        TextButton(onClick = onCancelVideo) {
                            Text("Dismiss", color = MapTalkColors.Subtle)
                        }
                    }
                }
                if (videoSend.isBusy) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        color = MapTalkColors.Accent,
                        trackColor = MapTalkColors.Surface,
                    )
                }
            }
        }

        replyTarget?.let { reply ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(36.dp)
                        .background(MapTalkColors.Accent, RoundedCornerShape(2.dp)),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Reply to ${reply.authorName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MapTalkColors.Accent,
                    )
                    Text(
                        text = when {
                            reply.isSticker -> reply.text
                            reply.hasVoice -> "Voice note"
                            reply.hasVideo && reply.text.isEmpty() -> "Video"
                            reply.hasImage && reply.text.isEmpty() -> "Photo"
                            else -> reply.text
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MapTalkColors.Subtle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onClearReply) {
                    Text("\u2715", color = MapTalkColors.Subtle)
                }
            }
        }

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

        if (pendingVideo != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MapTalkColors.Raised),
                    contentAlignment = Alignment.Center,
                ) {
                    pendingVideo.preview?.let { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Selected video",
                            contentScale = ContentScale.Crop,
                            filterQuality = FilterQuality.High,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_play),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onClearPendingVideo) {
                    Text(
                        text = "\u2715",
                        color = MapTalkColors.Subtle,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }

        if (showStickers) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(StickerPack.ALL) { glyph ->
                    Text(
                        text = glyph,
                        fontSize = 32.sp,
                        modifier = Modifier
                            .clickable { onPickSticker(glyph) }
                            .padding(4.dp),
                    )
                }
            }
        }

        if (isRecording) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(MapTalkColors.Danger),
                )
                Text(
                    text = formatDuration(recordElapsedMs),
                    style = MaterialTheme.typography.titleMedium,
                    color = MapTalkColors.Text,
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onCancelRecord) {
                    Text("Cancel", color = MapTalkColors.Subtle)
                }
                TextButton(onClick = onSendRecord) {
                    Text("Send", color = MapTalkColors.Accent)
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ComposerMediaTray(
                    expanded = mediaTrayVisible,
                    showStickers = showStickers,
                    isPreparingImage = isPreparingImage,
                    isPreparingVideo = isPreparingVideo,
                    pendingImage = pendingImage != null,
                    pendingVideo = pendingVideo != null,
                    onToggleStickers = onToggleStickers,
                    onPickPhoto = onPickPhoto,
                    onTakePhoto = onTakePhoto,
                    onPickVideo = onPickVideo,
                    onExpand = { mediaExpanded = true },
                )

                // Compact field (~40dp) — Material OutlinedTextField forces ~56dp.
                // Placeholder is a sibling under the field (not decorationBox) so it
                // can’t paint twice on some OEMs.
                val fieldInteraction = remember { MutableInteractionSource() }
                val fieldFocused by fieldInteraction.collectIsFocusedAsState()
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp, max = 120.dp)
                        .border(
                            width = 1.dp,
                            color = if (fieldFocused) {
                                MapTalkColors.Accent
                            } else {
                                MapTalkColors.Hairline
                            },
                            shape = RoundedCornerShape(20.dp),
                        )
                        .background(MapTalkColors.Raised, RoundedCornerShape(20.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (draft.isEmpty()) {
                        Text(
                            text = if (pendingImage == null && pendingVideo == null) {
                                "Say something"
                            } else {
                                "Add a caption"
                            },
                            color = MapTalkColors.Faint,
                            fontSize = 15.sp,
                            lineHeight = 20.sp,
                            maxLines = 1,
                        )
                    }
                    BasicTextField(
                        value = draft,
                        onValueChange = {
                            val next =
                                if (it.length <= ThreadViewModel.MAX_MESSAGE_LENGTH) it else draft
                            draft = next
                            if (next.isBlank()) {
                                mediaExpanded = true
                            } else if (mediaExpanded) {
                                mediaExpanded = false
                                if (showStickers) onToggleStickers()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { focus ->
                                if (focus.isFocused && draft.isNotBlank()) {
                                    mediaExpanded = false
                                    if (showStickers) onToggleStickers()
                                }
                            },
                        textStyle = TextStyle(
                            color = MapTalkColors.Text,
                            fontSize = 15.sp,
                            lineHeight = 20.sp,
                        ),
                        cursorBrush = SolidColor(MapTalkColors.Accent),
                        maxLines = 5,
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = true),
                        interactionSource = fieldInteraction,
                    )
                }

                if (canSend || isPreparingImage) {
                    Surface(
                        onClick = {
                            onSend(draft)
                            draft = ""
                        },
                        enabled = canSend && !isPreparingImage,
                        modifier = Modifier.size(40.dp),
                        shape = CircleShape,
                        color = sendContainer,
                        contentColor = if (canSend) Color.White else MapTalkColors.Faint,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(R.drawable.ic_send),
                                contentDescription = "Send",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                } else {
                    Surface(
                        onClick = onStartRecord,
                        modifier = Modifier.size(40.dp),
                        shape = CircleShape,
                        color = MapTalkColors.Raised,
                        contentColor = MapTalkColors.Subtle,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(R.drawable.ic_mic),
                                contentDescription = "Record a voice note",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
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
/**
 * Stickers / photo / video tray that collapses into a chevron once typing starts.
 * Springs + stagger — not a plain fade.
 */
@Composable
private fun ComposerMediaTray(
    expanded: Boolean,
    showStickers: Boolean,
    isPreparingImage: Boolean,
    isPreparingVideo: Boolean,
    pendingImage: Boolean,
    pendingVideo: Boolean,
    onToggleStickers: () -> Unit,
    onPickPhoto: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickVideo: () -> Unit,
    onExpand: () -> Unit,
) {
    val springSpec = spring<Float>(
        dampingRatio = 0.72f,
        stiffness = Spring.StiffnessMediumLow,
    )
    // Width tracks tool count — collapses to a chevron so the field keeps the rest.
    val trayWidth by animateDpAsState(
        targetValue = if (expanded) 160.dp else 32.dp,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow),
        label = "composerTrayWidth",
    )

    Box(
        modifier = Modifier
            .width(trayWidth)
            .height(40.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        val chevronScale by animateFloatAsState(
            targetValue = if (expanded) 0.45f else 1f,
            animationSpec = springSpec,
            label = "chevronScale",
        )
        val chevronAlpha by animateFloatAsState(
            targetValue = if (expanded) 0f else 1f,
            animationSpec = springSpec,
            label = "chevronAlpha",
        )
        // Chevron sits underneath the tray so expanded icons receive taps.
        IconButton(
            onClick = onExpand,
            enabled = !expanded,
            modifier = Modifier
                .size(32.dp, 40.dp)
                .graphicsLayer {
                    scaleX = chevronScale
                    scaleY = chevronScale
                    alpha = chevronAlpha
                    transformOrigin = TransformOrigin(0f, 0.5f)
                },
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = "Show media options",
                tint = MapTalkColors.Subtle,
            )
        }

        val trayAlpha by animateFloatAsState(
            targetValue = if (expanded) 1f else 0f,
            animationSpec = springSpec,
            label = "trayAlpha",
        )
        val trayShift by animateFloatAsState(
            targetValue = if (expanded) 0f else -18f,
            animationSpec = springSpec,
            label = "trayShift",
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .graphicsLayer {
                    alpha = trayAlpha
                    translationX = trayShift
                }
                .then(if (expanded) Modifier else Modifier.size(0.dp)),
        ) {
            StaggeredMediaIcon(visible = expanded, delayMs = 0) {
                IconButton(onClick = onToggleStickers, modifier = Modifier.size(40.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_smile),
                        contentDescription = "Stickers",
                        tint = if (showStickers) MapTalkColors.Accent else MapTalkColors.Subtle,
                    )
                }
            }
            StaggeredMediaIcon(visible = expanded, delayMs = 30) {
                IconButton(
                    onClick = onTakePhoto,
                    enabled = !isPreparingImage && !isPreparingVideo && !pendingVideo,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_camera),
                        contentDescription = "Take a photo",
                        tint = if (isPreparingImage || isPreparingVideo || pendingVideo) {
                            MapTalkColors.Faint
                        } else {
                            MapTalkColors.Subtle
                        },
                    )
                }
            }
            StaggeredMediaIcon(visible = expanded, delayMs = 60) {
                IconButton(
                    onClick = onPickPhoto,
                    enabled = !isPreparingImage && !isPreparingVideo && !pendingVideo,
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
            }
            StaggeredMediaIcon(visible = expanded, delayMs = 90) {
                IconButton(
                    onClick = onPickVideo,
                    enabled = !isPreparingImage && !isPreparingVideo && !pendingImage && !pendingVideo,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_video),
                        contentDescription = "Add a video",
                        tint = if (isPreparingVideo || pendingVideo) {
                            MapTalkColors.Faint
                        } else {
                            MapTalkColors.Subtle
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun StaggeredMediaIcon(
    visible: Boolean,
    delayMs: Int,
    content: @Composable () -> Unit,
) {
    val scale = remember { Animatable(if (visible) 1f else 0.55f) }
    val alpha = remember { Animatable(if (visible) 1f else 0f) }
    LaunchedEffect(visible) {
        val expandDelay = if (visible) delayMs.toLong() else maxOf(0, 90 - delayMs).toLong()
        delay(expandDelay)
        launch {
            scale.animateTo(
                if (visible) 1f else 0.55f,
                spring(dampingRatio = 0.68f, stiffness = Spring.StiffnessMediumLow),
            )
        }
        alpha.animateTo(
            if (visible) 1f else 0f,
            spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium),
        )
    }
    Box(
        modifier = Modifier.graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
            this.alpha = alpha.value
            transformOrigin = TransformOrigin(0f, 0.5f)
        },
    ) {
        content()
    }
}

private fun Message.follows(previous: Message): Boolean {
    if (authorId != previous.authorId) return false
    val mine = createdAt ?: return true
    val theirs = previous.createdAt ?: return true
    return Duration.between(theirs, mine) < RUN_GAP
}

private sealed interface ReportTarget {
    data class Message(val message: app.maptalk.data.model.Message) : ReportTarget
    data class Thread(val thread: ChatThread) : ReportTarget
}

@Composable
private fun MessageLongPressDialog(
    message: Message,
    isMine: Boolean,
    myUid: String,
    resolveMedia: (String) -> File?,
    onDismiss: () -> Unit,
    onReact: (String) -> Unit,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onReport: () -> Unit,
    onBlock: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var visible by remember { mutableStateOf(false) }
    val scrim by animateFloatAsState(
        targetValue = if (visible) 0.58f else 0f,
        animationSpec = tween(280, easing = FastOutSlowInEasing),
        label = "scrim",
    )
    val reactionScale = remember { Animatable(0.55f) }
    val reactionAlpha = remember { Animatable(0f) }
    val previewScale = remember { Animatable(1f) }
    val menuScale = remember { Animatable(0.92f) }
    val menuAlpha = remember { Animatable(0f) }
    val menuOffset = remember { Animatable(-8f) }
    val emojiScales = remember {
        ReactionEmoji.ALL.map { Animatable(0.4f) }
    }
    val mediaOnly = (message.hasImage || message.hasVideo) &&
        message.text.isEmpty() &&
        message.reply == null

    fun playExit(then: () -> Unit) {
        scope.launch {
            visible = false
            launch {
                reactionScale.animateTo(0.7f, tween(180, easing = FastOutSlowInEasing))
            }
            launch {
                reactionAlpha.animateTo(0f, tween(180, easing = FastOutSlowInEasing))
            }
            launch {
                previewScale.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow))
            }
            launch {
                menuScale.animateTo(0.94f, tween(180, easing = FastOutSlowInEasing))
            }
            launch {
                menuAlpha.animateTo(0f, tween(180, easing = FastOutSlowInEasing))
            }
            launch {
                menuOffset.animateTo(-6f, tween(180, easing = FastOutSlowInEasing))
            }
            emojiScales.forEach {
                launch { it.animateTo(0.5f, tween(140, easing = FastOutSlowInEasing)) }
            }
            delay(300)
            then()
        }
    }

    LaunchedEffect(Unit) {
        visible = true
        launch {
            previewScale.animateTo(1.05f, spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMedium))
        }
        delay(40)
        launch {
            reactionScale.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMedium))
        }
        launch {
            reactionAlpha.animateTo(1f, tween(160))
        }
        delay(40)
        launch {
            menuScale.animateTo(1f, spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMedium))
        }
        launch {
            menuAlpha.animateTo(1f, tween(180))
        }
        launch {
            menuOffset.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMedium))
        }
        ReactionEmoji.ALL.indices.forEach { index ->
            launch {
                delay(60L + index * 30L)
                emojiScales[index].animateTo(
                    1f,
                    spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
                )
            }
        }
    }

    Dialog(
        onDismissRequest = { playExit(onDismiss) },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = scrim))
                .clickable { playExit(onDismiss) },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = if (isMine) Alignment.End else Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp, vertical = 24.dp)
                    .clickable(enabled = false) {},
            ) {
                Surface(
                    shape = CircleShape,
                    color = MapTalkColors.Surface.copy(alpha = 0.95f),
                    border = BorderStroke(1.dp, MapTalkColors.Hairline),
                    shadowElevation = 12.dp,
                    modifier = Modifier
                        .graphicsLayer {
                            scaleX = reactionScale.value
                            scaleY = reactionScale.value
                            alpha = reactionAlpha.value
                            translationY = (1f - reactionAlpha.value) * 18f
                        },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ReactionEmoji.ALL.forEachIndexed { index, emoji ->
                            val mine = message.reacted(by = myUid, emoji = emoji)
                            Text(
                                text = emoji,
                                fontSize = 28.sp,
                                modifier = Modifier
                                    .graphicsLayer {
                                        val s = emojiScales[index].value
                                        scaleX = s
                                        scaleY = s
                                        alpha = s.coerceIn(0f, 1f)
                                    }
                                    .clip(CircleShape)
                                    .background(
                                        if (mine) MapTalkColors.Accent.copy(alpha = 0.25f)
                                        else Color.Transparent,
                                    )
                                    .clickable { onReact(emoji); playExit(onDismiss) }
                                    .padding(6.dp),
                            )
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = if (isMine) MapTalkColors.Accent else MapTalkColors.Raised,
                    contentColor = if (isMine) Color.White else MapTalkColors.Text,
                    shadowElevation = 16.dp,
                    modifier = Modifier
                        .widthIn(max = 280.dp)
                        .graphicsLayer {
                            scaleX = previewScale.value
                            scaleY = previewScale.value
                        },
                ) {
                    when {
                        message.isSticker -> Text(
                            text = message.text,
                            fontSize = 48.sp,
                            modifier = Modifier.padding(8.dp),
                        )
                        else -> Column(
                            modifier = Modifier.padding(
                                horizontal = if (mediaOnly) 4.dp else 12.dp,
                                vertical = if (mediaOnly) 4.dp else 9.dp,
                            ),
                        ) {
                            if (message.hasImage) {
                                message.imagePath?.let { path ->
                                    val remote = path.takeIf {
                                        it.startsWith("http://") || it.startsWith("https://")
                                    }
                                    MessageImage(
                                        file = if (remote == null) resolveMedia(path) else null,
                                        remoteUrl = remote,
                                        width = message.imageWidth,
                                        height = message.imageHeight,
                                        onClick = {},
                                    )
                                }
                            }
                            if (message.hasVideo) {
                                Text("Video", style = MaterialTheme.typography.bodyMedium)
                            }
                            if (message.hasVoice) {
                                Text("Voice note", style = MaterialTheme.typography.bodyMedium)
                            }
                            if (message.text.isNotEmpty() && !message.hasVoice) {
                                Text(
                                    text = message.text,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 8,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MapTalkColors.Surface,
                    border = BorderStroke(1.dp, MapTalkColors.Hairline),
                    shadowElevation = 14.dp,
                    modifier = Modifier
                        .widthIn(max = 260.dp)
                        .offset(y = menuOffset.value.dp)
                        .graphicsLayer {
                            scaleX = menuScale.value
                            scaleY = menuScale.value
                            alpha = menuAlpha.value
                            transformOrigin = TransformOrigin(
                                pivotFractionX = if (isMine) 1f else 0f,
                                pivotFractionY = 0f,
                            )
                        },
                ) {
                    Column {
                        LongPressMenuRow("Reply") {
                            onReply()
                            playExit(onDismiss)
                        }
                        if (isMine) {
                            if (message.isEditable) {
                                HorizontalDivider(color = MapTalkColors.Hairline)
                                LongPressMenuRow("Edit") {
                                    onEdit()
                                    playExit(onDismiss)
                                }
                            }
                            HorizontalDivider(color = MapTalkColors.Hairline)
                            LongPressMenuRow(
                                title = "Delete",
                                tint = MapTalkColors.Danger,
                            ) {
                                onDelete()
                                playExit(onDismiss)
                            }
                        } else {
                            HorizontalDivider(color = MapTalkColors.Hairline)
                            LongPressMenuRow("Report") {
                                onReport()
                                playExit(onDismiss)
                            }
                            HorizontalDivider(color = MapTalkColors.Hairline)
                            LongPressMenuRow(
                                title = "Block ${message.authorName}",
                                tint = MapTalkColors.Danger,
                            ) {
                                onBlock()
                                playExit(onDismiss)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LongPressMenuRow(
    title: String,
    tint: Color = MapTalkColors.Text,
    onClick: () -> Unit,
) {
    Text(
        text = title,
        color = tint,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    )
}
