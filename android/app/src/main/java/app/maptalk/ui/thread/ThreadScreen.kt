package app.maptalk.ui.thread

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.maptalk.R
import app.maptalk.appContainer
import app.maptalk.data.model.Author
import app.maptalk.data.model.Message
import app.maptalk.data.model.PreparedAudio
import app.maptalk.data.model.PreparedImage
import app.maptalk.data.model.ReactionEmoji
import app.maptalk.data.model.ReportReason
import app.maptalk.data.model.ReportTargetType
import app.maptalk.data.model.StickerPack
import app.maptalk.data.model.ChatThread
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
            onDismiss = { longPressTarget = null },
            onReact = { emoji ->
                viewModel.toggleReaction(emoji, message, author)
                longPressTarget = null
            },
            onReply = {
                viewModel.setReply(message)
                longPressTarget = null
            },
            onReport = {
                reportTarget = ReportTarget.Message(message)
                longPressTarget = null
            },
            onBlock = {
                blockConfirm = message.authorId to message.authorName
                longPressTarget = null
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
                    "Their chats and messages will disappear for you.",
                    color = MapTalkColors.Subtle,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.block(uid, author)
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
    resolveMedia: (String) -> File?,
    onImageClick: (String) -> Unit,
    onReply: () -> Unit,
    onLongPress: () -> Unit,
    onToggleReaction: (String) -> Unit,
) {
    val imageOnly = message.hasImage && message.text.isEmpty() && message.reply == null
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
                            horizontal = if (imageOnly) 4.dp else 14.dp,
                            vertical = if (imageOnly) 4.dp else 9.dp,
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
    isRecording: Boolean,
    recordElapsedMs: Int,
    onClearPending: () -> Unit,
    onClearReply: () -> Unit,
    onToggleStickers: () -> Unit,
    onPickSticker: (String) -> Unit,
    onPickPhoto: () -> Unit,
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
    onDismiss: () -> Unit,
    onReact: (String) -> Unit,
    onReply: () -> Unit,
    onReport: () -> Unit,
    onBlock: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = if (isMine) Alignment.End else Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = MapTalkColors.Surface.copy(alpha = 0.95f),
                    border = BorderStroke(1.dp, MapTalkColors.Hairline),
                    shadowElevation = 12.dp,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ReactionEmoji.ALL.forEach { emoji ->
                            val mine = message.reacted(by = myUid, emoji = emoji)
                            Text(
                                text = emoji,
                                fontSize = 28.sp,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(
                                        if (mine) MapTalkColors.Accent.copy(alpha = 0.25f)
                                        else Color.Transparent,
                                    )
                                    .clickable { onReact(emoji) }
                                    .padding(6.dp),
                            )
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = if (isMine) MapTalkColors.Accent else MapTalkColors.Raised,
                    contentColor = if (isMine) Color.White else MapTalkColors.Text,
                    shadowElevation = 10.dp,
                    modifier = Modifier.widthIn(max = 280.dp),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        when {
                            message.isSticker -> Text(message.text, fontSize = 48.sp)
                            message.hasVoice -> Text("Voice note", style = MaterialTheme.typography.bodyMedium)
                            message.hasImage && message.text.isEmpty() ->
                                Text("Photo", style = MaterialTheme.typography.bodyMedium)
                            else -> Text(
                                text = message.text.ifEmpty { "Photo" },
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MapTalkColors.Surface,
                    border = BorderStroke(1.dp, MapTalkColors.Hairline),
                    shadowElevation = 14.dp,
                    modifier = Modifier.widthIn(max = 260.dp),
                ) {
                    Column {
                        LongPressMenuRow("Reply", onClick = onReply)
                        if (!isMine) {
                            HorizontalDivider(color = MapTalkColors.Hairline)
                            LongPressMenuRow("Report", onClick = onReport)
                            HorizontalDivider(color = MapTalkColors.Hairline)
                            LongPressMenuRow(
                                title = "Block ${message.authorName}",
                                tint = MapTalkColors.Danger,
                                onClick = onBlock,
                            )
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
