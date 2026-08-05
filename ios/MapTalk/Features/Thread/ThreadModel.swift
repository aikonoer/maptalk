import Foundation

@MainActor
@Observable
final class ThreadModel {

    static let maxMessageLength = 1_000

    private(set) var thread: ChatThread?
    private(set) var messages: [Message] = []
    private(set) var isLoading = true
    var errorMessage: String?
    var replyTarget: Message?

    private let repository: ThreadRepository
    private let threadId: String
    private var tasks: [Task<Void, Never>] = []

    init(repository: ThreadRepository, threadId: String) {
        self.repository = repository
        self.threadId = threadId
        repository.onError = { [weak self] error in
            self?.errorMessage = error.localizedDescription
        }
    }

    func start() {
        guard tasks.isEmpty else { return }
        tasks.append(
            Task { [repository, threadId] in
                for await thread in repository.thread(id: threadId) {
                    self.thread = thread
                }
            }
        )
        tasks.append(
            Task { [repository, threadId] in
                for await messages in repository.messages(threadId: threadId) {
                    self.messages = messages
                    isLoading = false
                }
            }
        )
    }

    func stop() {
        tasks.forEach { $0.cancel() }
        tasks = []
    }

    func send(
        _ text: String,
        as author: Author,
        image: PreparedImage? = nil,
        audio: PreparedAudio? = nil,
        sticker: String? = nil
    ) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard image != nil || audio != nil || sticker != nil || !trimmed.isEmpty else { return }
        let reply = replyTarget.map {
            MessageReply(
                id: $0.id,
                authorName: $0.authorName,
                text: $0.isSticker ? $0.text : ($0.hasVoice ? "Voice note" : $0.text)
            )
        }
        repository.postMessage(
            threadId: threadId,
            text: trimmed,
            author: author,
            image: image,
            audio: audio,
            sticker: sticker,
            reply: reply
        )
        replyTarget = nil
    }

    func setReply(to message: Message) {
        replyTarget = message
    }

    func clearReply() {
        replyTarget = nil
    }

    func toggleReaction(_ emoji: String, on message: Message, as author: Author) {
        repository.toggleReaction(
            threadId: threadId,
            messageId: message.id,
            emoji: emoji,
            author: author
        )
    }
}
