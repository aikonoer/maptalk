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
import app.maptalk.data.model.MessageReply
import app.maptalk.data.model.PreparedAudio
import app.maptalk.data.model.PreparedImage
import app.maptalk.data.model.PreparedVideo
import app.maptalk.data.model.ReportReason
import app.maptalk.data.model.ReportTargetType
import java.io.File
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
    Uploading,
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

    private val _videoSendPhase = MutableStateFlow(VideoSendPhase.Idle)
    val videoSendPhase: StateFlow<VideoSendPhase> = _videoSendPhase.asStateFlow()

    val isPreparingVideo: StateFlow<Boolean> = _videoSendPhase
        .map { it != VideoSendPhase.Idle }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

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
    ) { thread, messages, reply, blocked, dismiss ->
        val filtered = messages.filter { it.authorId !in blocked }
        val replyVisible = reply?.takeUnless { it.authorId in blocked }
        val authorBlocked = thread != null && thread.authorId in blocked
        ThreadUiState(
            thread = thread,
            messages = filtered,
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
        viewModelScope.launch {
            _videoSendPhase.value = VideoSendPhase.Compressing
            // Transformer must hop to Main internally; keep IO for file IO around it.
            when (val result = VideoCompressor.prepare(appContext, uri)) {
                is VideoCompressor.Result.Err -> {
                    _errors.emit(IllegalStateException(result.message))
                    _videoSendPhase.value = VideoSendPhase.Idle
                }
                is VideoCompressor.Result.Ok -> {
                    _videoSendPhase.value = VideoSendPhase.Uploading
                    send(
                        text = "",
                        author = author,
                        video = result.video,
                        onFinished = { _videoSendPhase.value = VideoSendPhase.Idle },
                    )
                }
            }
        }
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
