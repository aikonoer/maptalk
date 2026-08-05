package app.maptalk.ui.thread

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.maptalk.AppContainer
import app.maptalk.data.ImageCompressor
import app.maptalk.data.PushRepository
import app.maptalk.data.SafetyRepository
import app.maptalk.data.ThreadRepository
import app.maptalk.data.VideoCompressor
import app.maptalk.data.model.Author
import app.maptalk.data.model.ChatThread
import app.maptalk.data.model.Message
import app.maptalk.data.model.MessageKind
import app.maptalk.data.model.MessageReply
import app.maptalk.data.model.PreparedAudio
import app.maptalk.data.model.PreparedImage
import app.maptalk.data.model.PreparedVideo
import app.maptalk.data.model.ReportReason
import app.maptalk.data.model.ReportTargetType
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ThreadUiState(
    val thread: ChatThread? = null,
    val messages: List<Message> = emptyList(),
    val isLoading: Boolean = true,
    val replyTarget: Message? = null,
    val shouldDismiss: Boolean = false,
)

enum class VideoSendPhase {
    Idle,
    Compressing,
    ConfirmUpload,
    Uploading,
    Failed,
}

data class VideoSendUi(
    val phase: VideoSendPhase = VideoSendPhase.Idle,
    /** Human reason when [phase] is Failed. */
    val detail: String? = null,
    /** Rough size shown while confirming / uploading. */
    val megabytes: Int? = null,
    /** First-frame preview while preparing / sending. */
    val preview: android.graphics.Bitmap? = null,
) {
    val isBusy: Boolean
        get() = phase == VideoSendPhase.Compressing || phase == VideoSendPhase.Uploading

    val title: String?
        get() = when (phase) {
            VideoSendPhase.Compressing -> "Preparing video…"
            VideoSendPhase.Uploading -> "Sending video…"
            VideoSendPhase.Failed -> detail ?: "Video could not be sent"
            VideoSendPhase.ConfirmUpload, VideoSendPhase.Idle -> null
        }

    val subtitle: String?
        get() = when (phase) {
            VideoSendPhase.Compressing -> "Compressing to under 30 seconds"
            VideoSendPhase.Uploading -> megabytes?.let { "About $it MB" }
            VideoSendPhase.Failed -> "Retry or pick another clip"
            else -> null
        }
}

class ThreadViewModel(
    private val threadId: String,
    private val threadRepository: ThreadRepository,
    private val safetyRepository: SafetyRepository,
    private val pushRepository: PushRepository,
    private val readBytes: suspend (Uri) -> ByteArray?,
    private val resolveMedia: (String) -> File?,
    private val appContext: Context,
) : ViewModel() {

    private val _errors = MutableSharedFlow<Throwable>(extraBufferCapacity = 1)
    val errors = merge(_errors, threadRepository.errors, safetyRepository.errors)

    private val _isPreparingImage = MutableStateFlow(false)
    val isPreparingImage: StateFlow<Boolean> = _isPreparingImage.asStateFlow()

    private val _videoSend = MutableStateFlow(VideoSendUi())
    val videoSend: StateFlow<VideoSendUi> = _videoSend.asStateFlow()

    val isPreparingVideo: StateFlow<Boolean> = _videoSend
        .map { it.isBusy }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _videoConfirmMb = MutableStateFlow<Int?>(null)
    val videoConfirmMb: StateFlow<Int?> = _videoConfirmMb.asStateFlow()

    private var videoJob: Job? = null
    private var retryVideoUri: Uri? = null
    private var retryVideoAuthor: Author? = null
    private var pendingConfirmVideo: PreparedVideo? = null
    private var pendingConfirmAuthor: Author? = null

    private val _pendingOutgoing = MutableStateFlow<Message?>(null)
    private val _replyTarget = MutableStateFlow<Message?>(null)
    private val _shouldDismiss = MutableStateFlow(false)

    private val blockedUids: StateFlow<Set<String>> = safetyRepository.blockedPeople()
        .map { people -> people.map { it.uid }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val state: StateFlow<ThreadUiState> = combine(
        threadRepository.thread(threadId),
        threadRepository.messages(threadId),
        _replyTarget,
        blockedUids,
        _shouldDismiss,
        _pendingOutgoing,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val thread = values[0] as ChatThread?
        @Suppress("UNCHECKED_CAST")
        val messages = values[1] as List<Message>
        @Suppress("UNCHECKED_CAST")
        val reply = values[2] as Message?
        @Suppress("UNCHECKED_CAST")
        val blocked = values[3] as Set<String>
        val dismiss = values[4] as Boolean
        @Suppress("UNCHECKED_CAST")
        val pending = values[5] as Message?
        val filtered = messages.filter { it.authorId !in blocked }
        val withPending = if (pending != null) filtered + pending else filtered
        val replyVisible = reply?.takeUnless { it.authorId in blocked }
        val authorBlocked = thread != null && thread.authorId in blocked
        ThreadUiState(
            thread = thread,
            messages = withPending,
            isLoading = false,
            replyTarget = replyVisible,
            shouldDismiss = dismiss || authorBlocked,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThreadUiState())

    init {
        pushRepository.subscribe(toThreadId = threadId)
    }

    fun mediaFile(relativePath: String): File? = resolveMedia(relativePath)

    fun setReply(message: Message) {
        _replyTarget.value = message
    }

    fun clearReply() {
        _replyTarget.value = null
    }

    fun send(
        text: String,
        author: Author,
        image: PreparedImage? = null,
        audio: PreparedAudio? = null,
        video: PreparedVideo? = null,
        sticker: String? = null,
        onFinished: (() -> Unit)? = null,
    ) {
        if (text.isBlank() && image == null && audio == null && video == null && sticker == null) {
            onFinished?.invoke()
            return
        }
        val target = _replyTarget.value
        val reply = target?.let {
            MessageReply(
                id = it.id,
                authorName = it.authorName,
                text = when {
                    it.isSticker -> it.text
                    it.hasVoice -> "Voice note"
                    it.hasVideo -> "Video"
                    it.hasImage && it.text.isEmpty() -> "Photo"
                    else -> it.text
                },
            )
        }
        threadRepository.postMessage(
            threadId = threadId,
            text = text,
            author = author,
            image = image,
            audio = audio,
            video = video,
            sticker = sticker,
            reply = reply,
            onFinished = onFinished,
        )
        _replyTarget.value = null
    }

    fun prepareAndSendVideo(uri: Uri, author: Author) {
        cancelVideoSend()
        retryVideoUri = uri
        retryVideoAuthor = author
        videoJob = viewModelScope.launch {
            val preview = withContext(Dispatchers.IO) { loadUriPoster(uri) }
            _videoSend.value = VideoSendUi(phase = VideoSendPhase.Compressing, preview = preview)
            try {
                when (val result = VideoCompressor.prepare(appContext, uri)) {
                    is VideoCompressor.Result.Err -> {
                        _pendingOutgoing.value = null
                        _videoSend.value = VideoSendUi(
                            phase = VideoSendPhase.Failed,
                            detail = result.message,
                            preview = preview,
                        )
                    }
                    is VideoCompressor.Result.Ok -> {
                        val mb = ((result.video.byteLength + 512 * 1024) / (1024 * 1024))
                            .toInt()
                            .coerceAtLeast(1)
                        val filePreview = withContext(Dispatchers.IO) {
                            loadFilePoster(result.video.file) ?: preview
                        }
                        val needsConfirm = isMeteredNetwork(appContext) ||
                            result.video.byteLength >= LARGE_VIDEO_WARN_BYTES
                        if (needsConfirm) {
                            pendingConfirmVideo = result.video
                            pendingConfirmAuthor = author
                            _videoConfirmMb.value = mb
                            _videoSend.value = VideoSendUi(
                                phase = VideoSendPhase.ConfirmUpload,
                                megabytes = mb,
                                preview = filePreview,
                            )
                        } else {
                            uploadPreparedVideo(result.video, author, mb, filePreview)
                        }
                    }
                }
            } catch (_: CancellationException) {
                _pendingOutgoing.value = null
                _videoSend.value = VideoSendUi()
            }
        }
    }

    fun confirmVideoUpload() {
        val video = pendingConfirmVideo ?: return
        val author = pendingConfirmAuthor ?: return
        val mb = _videoConfirmMb.value
        val preview = _videoSend.value.preview
        pendingConfirmVideo = null
        pendingConfirmAuthor = null
        _videoConfirmMb.value = null
        videoJob = viewModelScope.launch {
            try {
                uploadPreparedVideo(video, author, mb, preview)
            } catch (_: CancellationException) {
                video.file.delete()
                _pendingOutgoing.value = null
                _videoSend.value = VideoSendUi()
            }
        }
    }

    fun declineVideoUpload() {
        pendingConfirmVideo?.file?.delete()
        pendingConfirmVideo = null
        pendingConfirmAuthor = null
        _videoConfirmMb.value = null
        _pendingOutgoing.value = null
        _videoSend.value = VideoSendUi()
    }

    private suspend fun uploadPreparedVideo(
        video: PreparedVideo,
        author: Author,
        megabytes: Int?,
        preview: android.graphics.Bitmap?,
    ) {
        val mb = megabytes
            ?: ((video.byteLength + 512 * 1024) / (1024 * 1024)).toInt().coerceAtLeast(1)
        _pendingOutgoing.value = Message(
            id = "local:video-pending",
            kind = MessageKind.VIDEO,
            text = "",
            authorId = author.uid,
            authorName = author.displayName,
            createdAt = Instant.now(),
            videoPath = video.file.absolutePath,
            videoDurationMs = video.durationMs,
            videoWidth = video.width,
            videoHeight = video.height,
        )
        _videoSend.value = VideoSendUi(
            phase = VideoSendPhase.Uploading,
            megabytes = mb,
            preview = preview,
        )
        val outcome = threadRepository.postVideoAwaiting(
            threadId = threadId,
            author = author,
            video = video,
            reply = _replyTarget.value?.let {
                MessageReply(
                    id = it.id,
                    authorName = it.authorName,
                    text = when {
                        it.isSticker -> it.text
                        it.hasVoice -> "Voice note"
                        it.hasVideo -> "Video"
                        it.hasImage && it.text.isEmpty() -> "Photo"
                        else -> it.text
                    },
                )
            },
        )
        _replyTarget.value = null
        _pendingOutgoing.value = null
        if (outcome.isSuccess) {
            video.file.delete()
            _videoSend.value = VideoSendUi()
        } else {
            val detail = outcome.exceptionOrNull()?.message ?: "Video could not be sent"
            video.file.delete()
            _videoSend.value = VideoSendUi(
                phase = VideoSendPhase.Failed,
                detail = detail,
                preview = preview,
            )
        }
    }

    fun retryVideoSend() {
        val uri = retryVideoUri ?: return
        val author = retryVideoAuthor ?: return
        prepareAndSendVideo(uri, author)
    }

    fun cancelVideoSend() {
        videoJob?.cancel()
        videoJob = null
        pendingConfirmVideo?.file?.delete()
        pendingConfirmVideo = null
        pendingConfirmAuthor = null
        _videoConfirmMb.value = null
        _pendingOutgoing.value = null
        _videoSend.value = VideoSendUi()
    }

    private fun loadUriPoster(uri: Uri): android.graphics.Bitmap? {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, uri)
            retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun loadFilePoster(file: java.io.File): android.graphics.Bitmap? {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    override fun onCleared() {
        cancelVideoSend()
        super.onCleared()
    }

    fun prepareImage(uri: Uri, onReady: (PreparedImage) -> Unit) {
        viewModelScope.launch {
            _isPreparingImage.value = true
            val prepared = withContext(Dispatchers.IO) {
                val bytes = readBytes(uri) ?: return@withContext null
                ImageCompressor.prepare(bytes)
            }
            _isPreparingImage.value = false
            if (prepared == null) {
                _errors.emit(IllegalStateException("That photo could not be prepared"))
            } else {
                onReady(prepared)
            }
        }
    }

    fun toggleReaction(emoji: String, message: Message, author: Author) {
        threadRepository.toggleReaction(threadId, message.id, emoji, author)
    }

    fun block(blockedUid: String, displayName: String, author: Author) {
        safetyRepository.block(blockedUid, displayName, author)
        _shouldDismiss.value = true
    }

    fun report(
        type: ReportTargetType,
        targetId: String,
        targetAuthorId: String,
        reason: ReportReason,
        author: Author,
    ) {
        safetyRepository.report(
            type = type,
            targetId = targetId,
            threadId = threadId,
            targetAuthorId = targetAuthorId,
            reason = reason,
            author = author,
        )
    }

    companion object {
        const val MAX_MESSAGE_LENGTH = 1000
        private const val LARGE_VIDEO_WARN_BYTES = 5 * 1024 * 1024

        private fun isMeteredNetwork(context: Context): Boolean {
            val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
                ?: return false
            return cm.isActiveNetworkMetered
        }

        fun factory(container: AppContainer, threadId: String): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    val app = container.context
                    ThreadViewModel(
                        threadId = threadId,
                        threadRepository = container.threadRepository,
                        safetyRepository = container.safetyRepository,
                        pushRepository = container.pushRepository,
                        readBytes = { uri ->
                            app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        },
                        resolveMedia = { path -> container.resolveLocalMedia(path) },
                        appContext = app,
                    )
                }
            }
    }
}
