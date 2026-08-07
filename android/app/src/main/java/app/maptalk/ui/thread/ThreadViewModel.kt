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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ThreadUiState(
    val thread: ChatThread? = null,
    val messages: List<Message> = emptyList(),
    val isLoading: Boolean = true,
    val isLoadingOlder: Boolean = false,
    /** False once a scroll-up page comes back empty or short — stop asking. */
    val hasMoreHistory: Boolean = true,
    val replyTarget: Message? = null,
    val shouldDismiss: Boolean = false,
)

enum class VideoSendPhase {
    Idle,
    Compressing,
    NeedsTrim,
    Uploading,
    Failed,
}

data class VideoTrimRequest(
    val uri: Uri,
    val durationMs: Int,
)

/** A compressed clip waiting on the composer so a caption can be typed. */
data class PendingVideo(
    val video: PreparedVideo,
    val preview: android.graphics.Bitmap?,
)

data class VideoSendUi(
    val phase: VideoSendPhase = VideoSendPhase.Idle,
    /** Human reason when [phase] is Failed. */
    val detail: String? = null,
    /** First-frame preview while preparing / sending. */
    val preview: android.graphics.Bitmap? = null,
) {
    val isBusy: Boolean
        get() = phase == VideoSendPhase.Compressing || phase == VideoSendPhase.Uploading

    val title: String?
        get() = when (phase) {
            VideoSendPhase.Compressing -> "Getting your video ready…"
            VideoSendPhase.Uploading -> "Sending…"
            VideoSendPhase.Failed -> detail ?: "Couldn’t send that video"
            VideoSendPhase.NeedsTrim, VideoSendPhase.Idle -> null
        }

    val subtitle: String?
        get() = when (phase) {
            VideoSendPhase.Compressing -> "Almost there"
            VideoSendPhase.Failed -> "Try again, or pick a different one"
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

    private val _videoTrim = MutableStateFlow<VideoTrimRequest?>(null)
    val videoTrim: StateFlow<VideoTrimRequest?> = _videoTrim.asStateFlow()

    private val _pendingVideo = MutableStateFlow<PendingVideo?>(null)
    val pendingVideo: StateFlow<PendingVideo?> = _pendingVideo.asStateFlow()

    private var videoJob: Job? = null
    private var retryVideoUri: Uri? = null
    private var retryVideoAuthor: Author? = null
    private var trimAuthor: Author? = null
    /** Set on retry so the re-prepared clip skips the composer and its caption survives. */
    private var resendCaption: String? = null
    private var failedCaption: String? = null

    private val _pendingOutgoing = MutableStateFlow<Message?>(null)
    private val _replyTarget = MutableStateFlow<Message?>(null)
    private val _shouldDismiss = MutableStateFlow(false)
    /** Live tip + pages loaded by scrolling up. Grows; live updates overwrite by id. */
    private val _retainedMessages = MutableStateFlow<List<Message>>(emptyList())
    private val _isLoadingOlder = MutableStateFlow(false)
    private val _hasMoreHistory = MutableStateFlow(true)
    private val _tipReady = MutableStateFlow(false)
    private val _historyPrepended = MutableSharedFlow<Int>(extraBufferCapacity = 1)

    /** How many messages were just prepended — UI uses this to keep the scroll anchor. */
    val historyPrepended = _historyPrepended

    private val blockedUids: StateFlow<Set<String>> = safetyRepository.blockedPeople()
        .map { people -> people.map { it.uid }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val historyFlags: StateFlow<Pair<Boolean, Boolean>> = combine(
        _isLoadingOlder,
        _hasMoreHistory,
    ) { loadingOlder, hasMore -> loadingOlder to hasMore }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false to true)

    val state: StateFlow<ThreadUiState> = combine(
        threadRepository.thread(threadId),
        _retainedMessages,
        _pendingOutgoing,
        blockedUids,
        combine(_replyTarget, _shouldDismiss, _tipReady, historyFlags) { reply, dismiss, tipReady, history ->
            HistoryChrome(reply, dismiss, tipReady, history.first, history.second)
        },
    ) { thread, retained, pending, blocked, chrome ->
        val messages = mergeMessages(older = retained, live = emptyList(), pending = pending)
            .filter { it.authorId !in blocked }
        val replyVisible = chrome.reply?.takeUnless { it.authorId in blocked }
        val authorBlocked = thread != null && thread.authorId in blocked
        ThreadUiState(
            thread = thread,
            messages = messages,
            isLoading = !chrome.tipReady,
            isLoadingOlder = chrome.loadingOlder,
            hasMoreHistory = chrome.hasMore,
            replyTarget = replyVisible,
            shouldDismiss = chrome.dismiss || authorBlocked,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThreadUiState())

    init {
        pushRepository.subscribe(toThreadId = threadId)
        viewModelScope.launch {
            threadRepository.messages(threadId).collect { live ->
                _retainedMessages.update { previous ->
                    mergeMessages(older = previous, live = live, pending = null)
                }
                // A short tip means the whole thread fit in one page.
                if (live.size < ThreadRepository.MESSAGE_PAGE) {
                    _hasMoreHistory.value = false
                }
                _tipReady.value = true
            }
        }
    }

    /**
     * Fetch the next older page when the user scrolls near the top. No-ops while a fetch is
     * already running, when history is exhausted, or when the tip has not arrived yet.
     */
    fun loadOlder() {
        if (_isLoadingOlder.value || !_hasMoreHistory.value || !_tipReady.value) return
        val oldest = _retainedMessages.value.firstOrNull { !it.isLocalPending } ?: return
        viewModelScope.launch {
            _isLoadingOlder.value = true
            try {
                val page = threadRepository.olderMessages(threadId, beforeMessageId = oldest.id)
                if (page.reachedEnd) _hasMoreHistory.value = false
                if (page.messages.isEmpty()) return@launch
                val known = _retainedMessages.value.map { it.id }.toSet()
                val fresh = page.messages.filter { it.id !in known }
                if (fresh.isEmpty()) {
                    _hasMoreHistory.value = false
                    return@launch
                }
                _retainedMessages.update { previous ->
                    mergeMessages(older = fresh, live = previous, pending = null)
                }
                _historyPrepended.emit(fresh.size)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _errors.tryEmit(error)
            } finally {
                _isLoadingOlder.value = false
            }
        }
    }

    private fun mergeMessages(
        older: List<Message>,
        live: List<Message>,
        pending: Message?,
    ): List<Message> {
        val byId = LinkedHashMap<String, Message>()
        // Retained / older first; the live tip overwrites so reactions on recent messages stay fresh.
        older.forEach { byId[it.id] = it }
        live.forEach { byId[it.id] = it }
        if (pending != null) byId[pending.id] = pending
        return byId.values.sortedWith(
            compareBy<Message> { it.createdAt ?: Instant.MAX }
                .thenBy { it.id },
        )
    }

    private data class HistoryChrome(
        val reply: Message?,
        val dismiss: Boolean,
        val tipReady: Boolean,
        val loadingOlder: Boolean,
        val hasMore: Boolean,
    )

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
                    it.hasVideo && it.text.isEmpty() -> "Video"
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
        startVideoPrepare(uri, author, resend = null)
    }

    private fun startVideoPrepare(uri: Uri, author: Author, resend: String?) {
        cancelVideoSend()
        resendCaption = resend
        retryVideoUri = uri
        retryVideoAuthor = author
        videoJob = viewModelScope.launch {
            val preview = withContext(Dispatchers.IO) { loadUriPoster(uri) }
            _videoSend.value = VideoSendUi(phase = VideoSendPhase.Compressing, preview = preview)
            try {
                when (val result = VideoCompressor.prepare(appContext, uri)) {
                    is VideoCompressor.Result.NeedsTrim -> {
                        trimAuthor = author
                        _videoTrim.value = VideoTrimRequest(result.uri, result.durationMs)
                        _videoSend.value = VideoSendUi(
                            phase = VideoSendPhase.NeedsTrim,
                            preview = preview,
                        )
                    }
                    is VideoCompressor.Result.Err -> {
                        _pendingOutgoing.value = null
                        _videoSend.value = VideoSendUi(
                            phase = VideoSendPhase.Failed,
                            detail = result.message,
                            preview = preview,
                        )
                    }
                    is VideoCompressor.Result.Ok -> {
                        continueAfterPrepare(result.video, author, preview)
                    }
                }
            } catch (_: CancellationException) {
                _pendingOutgoing.value = null
                _videoSend.value = VideoSendUi()
            }
        }
    }

    fun confirmVideoTrim(startMs: Int) {
        val request = _videoTrim.value ?: return
        val author = trimAuthor ?: retryVideoAuthor ?: return
        _videoTrim.value = null
        videoJob = viewModelScope.launch {
            val preview = _videoSend.value.preview
                ?: withContext(Dispatchers.IO) { loadUriPoster(request.uri) }
            _videoSend.value = VideoSendUi(phase = VideoSendPhase.Compressing, preview = preview)
            try {
                when (val result = VideoCompressor.prepare(appContext, request.uri, clipStartMs = startMs)) {
                    is VideoCompressor.Result.Ok -> continueAfterPrepare(result.video, author, preview)
                    is VideoCompressor.Result.NeedsTrim -> {
                        _videoSend.value = VideoSendUi(
                            phase = VideoSendPhase.Failed,
                            detail = "Pick up to 15 seconds of your video",
                            preview = preview,
                        )
                    }
                    is VideoCompressor.Result.Err -> {
                        _videoSend.value = VideoSendUi(
                            phase = VideoSendPhase.Failed,
                            detail = result.message,
                            preview = preview,
                        )
                    }
                }
            } catch (_: CancellationException) {
                _pendingOutgoing.value = null
                _videoSend.value = VideoSendUi()
            }
        }
    }

    fun declineVideoTrim() {
        _videoTrim.value = null
        trimAuthor = null
        _videoSend.value = VideoSendUi()
    }

    private suspend fun continueAfterPrepare(
        video: PreparedVideo,
        author: Author,
        preview: android.graphics.Bitmap?,
    ) {
        val filePreview = withContext(Dispatchers.IO) {
            loadFilePoster(video.file) ?: preview
        }
        val resend = resendCaption
        if (resend != null) {
            resendCaption = null
            uploadPreparedVideo(video, author, filePreview, resend)
            return
        }
        // Park it on the composer instead of sending, so there's a chance to write
        // a caption first — same as a photo.
        _pendingVideo.value = PendingVideo(video, filePreview)
        _videoSend.value = VideoSendUi()
    }

    /** Sends the clip sitting on the composer, using [caption] as its text. */
    fun sendPendingVideo(caption: String, author: Author) {
        val pending = _pendingVideo.value ?: return
        _pendingVideo.value = null
        videoJob = viewModelScope.launch {
            uploadPreparedVideo(pending.video, author, pending.preview, caption)
        }
    }

    fun removePendingVideo() {
        val pending = _pendingVideo.value ?: return
        _pendingVideo.value = null
        pending.video.file.delete()
        _videoSend.value = VideoSendUi()
    }

    private suspend fun uploadPreparedVideo(
        video: PreparedVideo,
        author: Author,
        preview: android.graphics.Bitmap?,
        caption: String,
    ) {
        _pendingOutgoing.value = Message(
            id = "local:video-pending",
            kind = MessageKind.VIDEO,
            text = caption.trim(),
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
            preview = preview,
        )
        val outcome = threadRepository.postVideoAwaiting(
            threadId = threadId,
            author = author,
            video = video,
            text = caption,
            reply = _replyTarget.value?.let {
                MessageReply(
                    id = it.id,
                    authorName = it.authorName,
                    text = when {
                        it.isSticker -> it.text
                        it.hasVoice -> "Voice note"
                        it.hasVideo && it.text.isEmpty() -> "Video"
                        it.hasImage && it.text.isEmpty() -> "Photo"
                        else -> it.text
                    },
                )
            },
        )
        _replyTarget.value = null
        _pendingOutgoing.value = null
        if (outcome.isSuccess) {
            failedCaption = null
            video.file.delete()
            _videoSend.value = VideoSendUi()
        } else {
            val detail = outcome.exceptionOrNull()?.message ?: "Couldn’t send that video"
            failedCaption = caption
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
        startVideoPrepare(uri, author, resend = failedCaption)
    }

    fun cancelVideoSend() {
        videoJob?.cancel()
        videoJob = null
        _videoTrim.value = null
        trimAuthor = null
        resendCaption = null
        _pendingVideo.value?.video?.file?.delete()
        _pendingVideo.value = null
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

    fun editMessage(message: Message, text: String) {
        threadRepository.editMessage(threadId, message.id, text)
    }

    fun deleteMessage(message: Message) {
        if (_replyTarget.value?.id == message.id) clearReply()
        threadRepository.deleteMessage(threadId, message.id)
    }

    fun deleteThread() {
        threadRepository.deleteThread(threadId)
        _shouldDismiss.value = true
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
