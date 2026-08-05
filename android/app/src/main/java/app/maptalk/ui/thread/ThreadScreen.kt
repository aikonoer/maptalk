package app.maptalk.ui.thread

import android.Manifest
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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
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
import app.maptalk.data.model.PreparedAudio
import app.maptalk.data.model.PreparedImage
import app.maptalk.data.model.ReactionEmoji
import app.maptalk.data.model.ReportReason
import app.maptalk.data.model.ReportTargetType
import app.maptalk.data.model.StickerPack
import app.maptalk.ui.relativeTime
import app.maptalk.ui.theme.MapTalkColors
import app.maptalk.ui.theme.MapTalkShapes
import app.maptalk.ui.theme.avatarTint
import app.maptalk.ui.theme.initialsOf
import app.maptalk.ui.theme.tint
import coil.compose.AsyncImage
import java.io.File
import java.time.Duration
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Messages from one person, close enough in time, are drawn as one run. */
private val RUN_GAP: Duration = Duration.ofMinutes(5)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ThreadScreen(
    threadId: String,
    author: Author,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val container = context.appContainer
    val viewModel: ThreadViewModel =
        viewModel(factory = ThreadViewModel.factory(container, threadId))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isPreparingImage by viewModel.isPreparingImage.collectAsStateWithLifecycle()
    val isPreparingVideo by viewModel.isPreparingVideo.collectAsStateWithLifecycle()
    val videoSend by viewModel.videoSend.collectAsStateWithLifecycle()
    val videoConfirmMb by viewModel.videoConfirmMb.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var pendingImage by remember { mutableStateOf<PreparedImage?>(null) }
    var fullscreenPath by remember { mutableStateOf<String?>(null) }
    var showStickers by remember { mutableStateOf(false) }
    var longPressTarget by remember { mutableStateOf<Message?>(null) }
    var reportTarget by remember { mutableStateOf<ReportTarget?>(null) }
    var blockConfirm by remember { mutableStateOf<Pair<String, String>?>(null) }
    var reportThanks by remember { mutableStateOf(false) }
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
            snackbarHostState.showSnackbar(error.message ?: "Message could not be sent")
        }
    }

    LaunchedEffect(state.shouldDismiss) {
        if (state.shouldDismiss) onBack()
    }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    fullscreenPath?.let { path ->
        FullscreenImage(
            path = path,
            file = viewModel.mediaFile(path),
            onDismiss = { fullscreenPath = null },
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

    if (videoSend.phase == VideoSendPhase.ConfirmUpload) {
        val mb = videoConfirmMb ?: videoSend.megabytes ?: 1
        AlertDialog(
            onDismissRequest = { viewModel.declineVideoUpload() },
            containerColor = MapTalkColors.Surface,
            title = { Text("Send this video?", color = MapTalkColors.Text) },
            text = {
                Text(
                    "About $mb MB · up to 30 seconds. On cellular this uses mobile data.",
                    color = MapTalkColors.Subtle,
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmVideoUpload() }) {
                    Text("Send", color = MapTalkColors.Accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.declineVideoUpload() }) {
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
                actions = {
                    val thread = state.thread
                    if (thread != null && thread.authorId != author.uid) {
                        IconButton(
                            onClick = {
                                reportTarget = ReportTarget.Thread(thread)
                            },
                        ) {
                            Text("⚑", color = MapTalkColors.Subtle, fontSize = 16.sp)
                        }
                        IconButton(
                            onClick = {
                                blockConfirm = thread.authorId to thread.authorName
                            },
                        ) {
                            Text("⊘", color = MapTalkColors.Subtle, fontSize = 16.sp)
                        }
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
                                myUid = author.uid,
                                startsRun = startsRun(state.messages, index),
                                endsRun = endsRun(state.messages, index),
                                isLifted = longPressTarget?.id == message.id,
                                resolveMedia = viewModel::mediaFile,
                                onImageClick = { path -> fullscreenPath = path },
                                onReply = { viewModel.setReply(message) },
                                onLongPress = { longPressTarget = message },
                                onToggleReaction = { emoji ->
                                    viewModel.toggleReaction(emoji, message, author)
                                },
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = MapTalkColors.Hairline)
            Composer(
                pendingImage = pendingImage,
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
                    viewModel.send(text, author, pendingImage)
                    pendingImage = null
                    showStickers = false
                },
                modifier = Modifier
                    .background(MapTalkColors.Surface)
                    .navigationBarsPadding(),
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
    resolveMedia: (String) -> File?,
    onImageClick: (String) -> Unit,
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
                                VideoBubble(
                                    path = path,
                                    file = resolveMedia(path),
                                    durationMs = message.videoDurationMs ?: 0,
                                    width = message.videoWidth,
                                    height = message.videoHeight,
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

                        if (message.text.isNotEmpty() && !message.hasVoice && !message.hasVideo) {
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
                    Text(
                        text = relativeTime(message.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MapTalkColors.Faint,
                        modifier = Modifier.padding(horizontal = 2.dp, vertical = 3.dp),
                    )
                }
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
    val playUri = remember(path, file?.absolutePath) {
        when {
            path.startsWith("http://") || path.startsWith("https://") -> path
            file != null && file.exists() -> Uri.fromFile(file).toString()
            else -> null
        }
    }
    var isPlaying by remember(path) { mutableStateOf(false) }
    var isBuffering by remember(path) { mutableStateOf(false) }
    val poster = remember(path, file?.absolutePath) {
        loadVideoPoster(context, path, file)
    }

    DisposableEffect(path) {
        val exo = ThreadVideoPlayer.player(context)
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                if (ThreadVideoPlayer.activePath == playUri) {
                    isPlaying = playing
                } else if (playing.not() && ThreadVideoPlayer.activePath != playUri) {
                    isPlaying = false
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (ThreadVideoPlayer.activePath != playUri) return
                isBuffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_ENDED) {
                    isPlaying = false
                }
            }
        }
        exo.addListener(listener)
        onDispose {
            exo.removeListener(listener)
            playUri?.let { ThreadVideoPlayer.pauseIfPlaying(it) }
        }
    }

    Box(
        modifier = Modifier
            .width(displayWidth)
            .height(displayHeight)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable {
                val uri = playUri ?: return@clickable
                ThreadVideoPlayer.play(context, uri)
                isPlaying = ThreadVideoPlayer.activePath == uri
            },
        contentAlignment = Alignment.Center,
    ) {
        if (poster != null && (!isPlaying || isBuffering)) {
            Image(
                bitmap = poster.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (isPlaying && playUri != null) {
            AndroidView(
                factory = { ctx ->
                    FrameLayout(ctx).apply {
                        val texture = TextureView(ctx)
                        addView(
                            texture,
                            FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT,
                            ),
                        )
                        ThreadVideoPlayer.player(ctx).setVideoTextureView(texture)
                    }
                },
                update = { frame ->
                    val texture = frame.getChildAt(0) as? TextureView
                    if (texture != null && ThreadVideoPlayer.activePath == playUri) {
                        ThreadVideoPlayer.player(frame.context).setVideoTextureView(texture)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        when {
            isBuffering && isPlaying -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(28.dp),
                    )
                    Text(
                        text = "Loading…",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            !isPlaying -> Icon(
                painter = painterResource(R.drawable.ic_play),
                contentDescription = "Play video, ${formatDuration(durationMs)}",
                tint = Color.White,
                modifier = Modifier
                    .size(44.dp)
                    .background(Color.Black.copy(alpha = 0.35f), CircleShape)
                    .padding(12.dp),
            )
        }
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
            when {
                bitmap != null -> Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Photo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                )
                remote != null -> AsyncImage(
                    model = remote,
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
    onClearReply: () -> Unit,
    onToggleStickers: () -> Unit,
    onPickSticker: (String) -> Unit,
    onPickPhoto: () -> Unit,
    onPickVideo: () -> Unit,
    onStartRecord: () -> Unit,
    onCancelRecord: () -> Unit,
    onSendRecord: () -> Unit,
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
        videoSend.title?.let { title ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (videoSend.isBusy) {
                        CircularProgressIndicator(
                            color = MapTalkColors.Accent,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp),
                        )
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
                            Text("Retry", color = MapTalkColors.Accent)
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
                            .padding(top = 8.dp),
                        color = MapTalkColors.Accent,
                        trackColor = MapTalkColors.Raised,
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
                            reply.hasVideo -> "Video"
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
                IconButton(
                    onClick = onToggleStickers,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_smile),
                        contentDescription = "Stickers",
                        tint = MapTalkColors.Subtle,
                    )
                }

                IconButton(
                    onClick = onPickPhoto,
                    enabled = !isPreparingImage && !isPreparingVideo,
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

                IconButton(
                    onClick = onPickVideo,
                    enabled = !isPreparingImage && !isPreparingVideo,
                    modifier = Modifier.size(40.dp),
                ) {
                    if (isPreparingVideo) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = MapTalkColors.Subtle,
                            modifier = Modifier.size(18.dp),
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_video),
                            contentDescription = "Add a video",
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
                    .padding(horizontal = 28.dp)
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
                            if (message.text.isNotEmpty() && !message.hasVoice && !message.hasVideo) {
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
                        if (!isMine) {
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
