import Foundation

@MainActor
@Observable
final class ThreadModel {

    static let maxMessageLength = 1_000

    private(set) var thread: ChatThread?
    private(set) var messages: [Message] = []
    private(set) var isLoading = true
    var errorMessage: String?

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

    func send(_ text: String, as author: Author, image: PreparedImage? = nil) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard image != nil || !trimmed.isEmpty else { return }
        repository.postMessage(threadId: threadId, text: trimmed, author: author, image: image)
    }
}
