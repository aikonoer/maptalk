package app.maptalk.ui.thread

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.maptalk.AppContainer
import app.maptalk.data.ImageCompressor
import app.maptalk.data.ThreadRepository
import app.maptalk.data.model.Author
import app.maptalk.data.model.ChatThread
import app.maptalk.data.model.Message
import app.maptalk.data.model.MessageReply
import app.maptalk.data.model.PreparedAudio
import app.maptalk.data.model.PreparedImage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ThreadUiState(
    val thread: ChatThread? = null,
    val messages: List<Message> = emptyList(),
    val isLoading: Boolean = true,
    val replyTarget: Message? = null,
)

class ThreadViewModel(
    private val threadId: String,
    private val threadRepository: ThreadRepository,
    private val readBytes: suspend (Uri) -> ByteArray?,
    private val resolveMedia: (String) -> File?,
) : ViewModel() {

    private val _errors = MutableSharedFlow<Throwable>(extraBufferCapacity = 1)
    val errors = _errors.asSharedFlow()

    private val _isPreparingImage = MutableStateFlow(false)
    val isPreparingImage: StateFlow<Boolean> = _isPreparingImage.asStateFlow()

    private val _replyTarget = MutableStateFlow<Message?>(null)

    val state: StateFlow<ThreadUiState> = combine(
        threadRepository.thread(threadId),
        threadRepository.messages(threadId),
        _replyTarget,
    ) { thread, messages, reply ->
        ThreadUiState(
            thread = thread,
            messages = messages,
            isLoading = false,
            replyTarget = reply,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThreadUiState())

    init {
        viewModelScope.launch {
            threadRepository.errors.collect { _errors.emit(it) }
        }
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
        sticker: String? = null,
    ) {
        if (text.isBlank() && image == null && audio == null && sticker == null) return
        val target = _replyTarget.value
        val reply = target?.let {
            MessageReply(
                id = it.id,
                authorName = it.authorName,
                text = when {
                    it.isSticker -> it.text
                    it.hasVoice -> "Voice note"
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
            sticker = sticker,
            reply = reply,
        )
        _replyTarget.value = null
    }

    fun toggleReaction(emoji: String, message: Message, author: Author) {
        threadRepository.toggleReaction(threadId, message.id, emoji, author)
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

    companion object {
        const val MAX_MESSAGE_LENGTH = 1000

        fun factory(container: AppContainer, threadId: String): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    val app = container.context
                    ThreadViewModel(
                        threadId = threadId,
                        threadRepository = container.threadRepository,
                        readBytes = { uri ->
                            app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        },
                        resolveMedia = { path -> container.resolveLocalMedia(path) },
                    )
                }
            }
    }
}
