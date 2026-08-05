import Foundation

@MainActor
@Observable
final class ThreadModel {

    static let maxMessageLength = 1_000

    private(set) var thread: ChatThread?
    private(set) var messages: [Message] = []
    private(set) var isLoading = true
    private(set) var blockedUids: Set<String> = []
    var errorMessage: String?
    var replyTarget: Message?
    /// Set when the thread author is blocked so the screen can dismiss.
    var shouldDismiss = false

    private let repository: ThreadRepository
    private let safety: SafetyRepository
    private let threadId: String
    private var allMessages: [Message] = []
    private var tasks: [Task<Void, Never>] = []

    init(repository: ThreadRepository, safety: SafetyRepository, threadId: String) {
        self.repository = repository
        self.safety = safety
        self.threadId = threadId
        repository.onError = { [weak self] error in
            self?.errorMessage = error.localizedDescription
        }
        safety.onError = { [weak self] error in
            self?.errorMessage = error.localizedDescription
        }
    }

    func start() {
        guard tasks.isEmpty else { return }
        tasks.append(
            Task { [repository, threadId] in
                for await thread in repository.thread(id: threadId) {
                    self.thread = thread
                    if let thread, blockedUids.contains(thread.authorId) {
                        shouldDismiss = true
                    }
                }
            }
        )
        tasks.append(
            Task { [repository, threadId] in
                for await messages in repository.messages(threadId: threadId) {
                    allMessages = messages
                    applyMessageFilter()
                    isLoading = false
                }
            }
        )
        tasks.append(
            Task { [safety] in
                for await blocked in safety.blockedUids() {
                    blockedUids = blocked
                    applyMessageFilter()
                    if let thread, blocked.contains(thread.authorId) {
                        shouldDismiss = true
                    }
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

    func block(uid: String, as author: Author) {
        safety.block(uid: uid, as: author)
        shouldDismiss = true
    }

    func report(
        type: ReportTargetType,
        targetId: String,
        targetAuthorId: String,
        reason: ReportReason,
        as author: Author
    ) {
        safety.report(
            type: type,
            targetId: targetId,
            threadId: threadId,
            targetAuthorId: targetAuthorId,
            reason: reason,
            as: author
        )
    }

    private func applyMessageFilter() {
        messages = allMessages.filter { !blockedUids.contains($0.authorId) }
        if let reply = replyTarget, blockedUids.contains(reply.authorId) {
            replyTarget = nil
        }
    }
}
