import AVFoundation
import AVKit
import PhotosUI
import SwiftUI
import UIKit

struct ThreadScreen: View {

    private let author: Author
    private let threadId: String
    /// Dismiss the sheet and show this pin’s area on the map (place-search style).
    private let onShowOnMap: ((GeoPoint, String?) -> Void)?
    /// Called after Delete chat succeeds so the map can drop the bubble immediately.
    private let onThreadDeleted: ((String) -> Void)?
    @State private var model: ThreadModel
    @State private var draft = ""
    @State private var pickerItem: PhotosPickerItem?
    @State private var videoPickerItem: PhotosPickerItem?
    @State private var pendingImage: UIImage?
    @State private var isPreparingImage = false
    @State private var videoSendPhase: VideoSendPhase = .idle
    @State private var videoFailDetail: String?
    @State private var videoPreview: UIImage?
    @State private var pendingOutgoing: Message?
    @State private var videoTask: Task<Void, Never>?
    @State private var pendingVideo: PreparedVideo?
    @State private var retryPreparedVideo: PreparedVideo?
    @State private var retryVideoCaption = ""
    @State private var showStickers = false
    /// Messenger-style collapse removed — text and media stay equally available.
    @State private var longPressTarget: Message?
    @State private var longPressFrame: CGRect = .zero
    @State private var bubbleFrames: [String: CGRect] = [:]
    @State private var editTarget: Message?
    @State private var editDraft = ""
    @State private var deleteMessageConfirm: Message?
    @State private var deleteThreadConfirm = false
    @State private var reportTarget: ReportSheetTarget?
    @State private var blockConfirm: BlockConfirm?
    @State private var reportThanks = false
    @State private var showBackgroundPicker = false
    @State private var pendingTrim: PendingVideoTrim?
    @State private var fullscreenVideo: FullscreenVideoRoute?
    @AppStorage(ChatBackgroundStore.key) private var backgroundId = ChatBackground.standard.rawValue
    @State private var recorder = VoiceRecorder()
    /// Stick to the latest bubble until the user scrolls away from the bottom.
    @State private var pinnedToBottom = true
    @State private var lastTipId: String?
    /// Top-sentinel loadOlder must wait until the first scroll-to-bottom finishes, or a long
    /// tip opens halfway up (older page prepends while the list is still at the top).
    @State private var historyLoadEnabled = false
    @FocusState private var isComposing: Bool
    @Environment(\.dismiss) private var dismiss

    private var chatBackground: ChatBackground {
        ChatBackground.from(id: backgroundId)
    }

    private var threadErrorPresented: Binding<Bool> {
        Binding(
            get: { model.errorMessage != nil && videoSendPhase != .failed },
            set: { if !$0 { model.errorMessage = nil } }
        )
    }

    init(
        environment: AppEnvironment,
        author: Author,
        threadId: String,
        onShowOnMap: ((GeoPoint, String?) -> Void)? = nil,
        onThreadDeleted: ((String) -> Void)? = nil
    ) {
        self.author = author
        self.threadId = threadId
        self.onShowOnMap = onShowOnMap
        self.onThreadDeleted = onThreadDeleted
        _model = State(
            initialValue: ThreadModel(
                repository: environment.threadRepository,
                safety: environment.safetyRepository,
                push: environment.pushRepository,
                threadId: threadId
            )
        )
    }

    private var isPreparingVideo: Bool {
        videoSendPhase == .compressing || videoSendPhase == .uploading
    }

    private var trimmedDraft: String {
        draft.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static let composerSpring = Animation.spring(response: 0.42, dampingFraction: 0.78)

    private var canSend: Bool {
        pendingImage != nil || pendingVideo != nil || !trimmedDraft.isEmpty
    }

    private var displayedMessages: [Message] {
        if let pendingOutgoing {
            return model.messages + [pendingOutgoing]
        }
        return model.messages
    }

    var body: some View {
        threadChrome
            .alert("Thanks — we’ll take a look", isPresented: $reportThanks) {
                Button("OK", role: .cancel) {}
            } message: {
                Text("Your report was sent. You can also block the person if you don’t want to see them.")
            }
            .alert("Something went wrong", isPresented: threadErrorPresented) {
                Button("OK", role: .cancel) { model.errorMessage = nil }
            } message: {
                Text(model.errorMessage ?? "")
            }
            .confirmationDialog(
                blockConfirm.map { "Block \($0.name)?" } ?? "Block?",
                isPresented: Binding(
                    get: { blockConfirm != nil },
                    set: { if !$0 { blockConfirm = nil } }
                ),
                titleVisibility: .visible
            ) {
                Button("Block", role: .destructive) {
                    if let blockConfirm {
                        model.block(
                            uid: blockConfirm.uid,
                            displayName: blockConfirm.name,
                            as: author
                        )
                    }
                    blockConfirm = nil
                }
                Button("Cancel", role: .cancel) { blockConfirm = nil }
            } message: {
                Text("Their chats and messages will disappear for you. Unblock anytime from Settings.")
            }
            .alert(
                "Delete message?",
                isPresented: Binding(
                    get: { deleteMessageConfirm != nil },
                    set: { if !$0 { deleteMessageConfirm = nil } }
                )
            ) {
                Button("Delete", role: .destructive) {
                    if let deleteMessageConfirm {
                        model.deleteMessage(deleteMessageConfirm)
                    }
                    deleteMessageConfirm = nil
                }
                Button("Cancel", role: .cancel) { deleteMessageConfirm = nil }
            } message: {
                Text("This message will be removed for everyone.")
            }
            .alert("Delete chat?", isPresented: $deleteThreadConfirm) {
                Button("Delete", role: .destructive) {
                    model.deleteThread()
                }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("This chat and all its messages will be permanently deleted.")
            }
            .sheet(item: $editTarget) { message in
                editMessageSheet(for: message)
            }
    }

    private func editMessageSheet(for message: Message) -> some View {
        let trimmed = editDraft.trimmingCharacters(in: .whitespacesAndNewlines)
        let canSave = message.kind == .image || !trimmed.isEmpty
        return NavigationStack {
            Form {
                TextField(
                    message.kind == .image ? "Caption" : "Message",
                    text: $editDraft,
                    axis: .vertical
                )
                .lineLimit(3...8)
            }
            .scrollContentBackground(.hidden)
            .background(Theme.base)
            .navigationTitle("Edit message")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { editTarget = nil }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        model.editMessage(message, text: editDraft)
                        editTarget = nil
                    }
                    .disabled(!canSave)
                    .fontWeight(.semibold)
                }
            }
        }
        .presentationDetents([.medium])
        .presentationDragIndicator(.visible)
        .presentationBackground(Theme.base)
    }

    private var threadChrome: some View {
        VStack(spacing: 0) {
            sheetHeader
            messages
        }
        .background {
            ChatBackgroundView(style: chatBackground)
                .ignoresSafeArea()
        }
        .safeAreaInset(edge: .bottom) { composer }
        .onAppear { model.start() }
        .onDisappear {
            model.stop()
            recorder.cancel()
            cancelVideoSend()
        }
        .onChange(of: model.shouldDismiss) { _, dismissNow in
            guard dismissNow else { return }
            if let deletedId = model.deletedThreadId {
                onThreadDeleted?(deletedId)
            }
            dismiss()
        }
        .onChange(of: pickerItem) { _, item in
            guard let item else { return }
            Task { await loadPickerItem(item) }
        }
        .onChange(of: videoPickerItem) { _, item in
            guard let item else { return }
            videoTask?.cancel()
            videoTask = Task { await loadVideoItem(item) }
        }
        .overlay { longPressOverlay }
        .coordinateSpace(name: "thread")
        .onPreferenceChange(BubbleFrameKey.self) { bubbleFrames = $0 }
        .sheet(item: $reportTarget) { target in
            reportSheet(for: target)
        }
        .sheet(isPresented: $showBackgroundPicker) {
            ChatBackgroundPicker(
                selection: Binding(
                    get: { chatBackground },
                    set: { backgroundId = $0.rawValue }
                )
            )
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
            .presentationBackground(Theme.base)
        }
        .sheet(item: $pendingTrim) { trim in
            VideoTrimSheet(
                sourceURL: trim.sourceURL,
                durationMs: trim.durationMs,
                onTrim: { startMs in
                    let url = trim.sourceURL
                    pendingTrim = nil
                    videoTask = Task {
                        await compressAndContinue(from: url, clipStartMs: startMs, deleteSourceWhenDone: true)
                    }
                },
                onCancel: {
                    try? FileManager.default.removeItem(at: trim.sourceURL)
                    pendingTrim = nil
                    videoSendPhase = .idle
                    videoPreview = nil
                }
            )
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
            .presentationBackground(Theme.base)
            .interactiveDismissDisabled()
        }
        .fullScreenCover(item: $fullscreenVideo) { route in
            FullscreenVideoViewer(path: route.path, durationMs: route.durationMs) {
                fullscreenVideo = nil
            }
        }
    }

    private var sheetHeader: some View {
        VStack(spacing: 0) {
            Capsule()
                .fill(Theme.hairline)
                .frame(width: 36, height: 4)
                .padding(.top, 10)
                .padding(.bottom, 8)

            HStack(alignment: .center, spacing: 10) {
                Button {
                    dismiss()
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(Theme.subtle)
                        .frame(width: 32, height: 32)
                        .background(Theme.raised, in: Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Close")

                header
                    .frame(maxWidth: .infinity, alignment: .leading)

                threadOptionsMenu
                    .frame(width: 32, height: 32)
            }
            .padding(.horizontal, 14)
            .padding(.bottom, 12)

            Rectangle()
                .fill(Theme.hairline)
                .frame(height: 1)
        }
        .background(Theme.surface)
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(model.thread?.title ?? "Chat")
                .font(.cardTitle)
                .foregroundStyle(Theme.text)
                .lineLimit(2)
                .truncationMode(.tail)
                .multilineTextAlignment(.leading)
                .frame(maxWidth: .infinity, alignment: .leading)

            if let thread = model.thread {
                HStack(spacing: 5) {
                    // Glow pip instead of a "Live" label — same heat language as map bubbles.
                    let heat = ActivityHeat.of(thread.lastMessageAt)
                    if heat != .cool {
                        Circle()
                            .fill(Theme.accent.opacity(heat == .hot ? 1 : 0.55))
                            .frame(width: 6, height: 6)
                            .shadow(
                                color: Theme.accent.opacity(heat == .hot ? 0.7 : 0.35),
                                radius: heat == .hot ? 4 : 2
                            )
                    }
                    Text(thread.kind.glyph).font(.system(size: 9))
                    Text(thread.kind.label)
                        .foregroundStyle(thread.kind.tint)
                    Text("\u{00b7} \(thread.authorName)")
                        .foregroundStyle(Theme.faint)
                }
                .font(.meta)
                .lineLimit(1)

                PlaceLabelLine(point: thread.position) { placeName in
                    if let onShowOnMap {
                        onShowOnMap(thread.position, placeName)
                    }
                }
                .padding(.top, 2)
            }
        }
    }

    @ViewBuilder
    private var threadOptionsMenu: some View {
        Menu {
            ShareLink(item: ThreadLink.url(threadId: threadId)) {
                Label("Share chat", systemImage: "square.and.arrow.up")
            }
            Button("Background", systemImage: "photo.on.rectangle") {
                showBackgroundPicker = true
            }
            if let thread = model.thread {
                if thread.authorId != author.uid {
                    Divider()
                    Button("Report chat", systemImage: "flag") {
                        reportTarget = .thread(thread)
                    }
                    Button("Block \(thread.authorName)", systemImage: "hand.raised", role: .destructive) {
                        blockConfirm = BlockConfirm(
                            uid: thread.authorId,
                            name: thread.authorName
                        )
                    }
                } else if thread.authorId == author.uid {
                    Divider()
                    Button("Delete chat", systemImage: "trash", role: .destructive) {
                        deleteThreadConfirm = true
                    }
                }
            }
        } label: {
            Image(systemName: "ellipsis")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(Theme.subtle)
                .frame(width: 32, height: 32)
                .background(Theme.raised, in: Circle())
        }
        .accessibilityLabel("Chat options")
    }

    @ViewBuilder
    private var longPressOverlay: some View {
        if let message = longPressTarget {
            MessageLongPressOverlay(
                message: message,
                isMine: message.authorId == author.uid,
                myUid: author.uid,
                anchor: bubbleFrames[message.id] ?? longPressFrame,
                onReact: { emoji in
                    model.toggleReaction(emoji, on: message, as: author)
                },
                onReply: {
                    model.setReply(to: message)
                },
                onEdit: {
                    editDraft = message.text
                    editTarget = message
                },
                onDelete: {
                    deleteMessageConfirm = message
                },
                onReport: {
                    reportTarget = .message(message)
                },
                onBlock: {
                    blockConfirm = BlockConfirm(
                        uid: message.authorId,
                        name: message.authorName
                    )
                },
                onDismiss: dismissLongPress
            )
            .zIndex(20)
        }
    }

    private func reportSheet(for target: ReportSheetTarget) -> some View {
        ReportReasonSheet { reason in
            switch target {
            case let .message(message):
                model.report(
                    type: .message,
                    targetId: message.id,
                    targetAuthorId: message.authorId,
                    reason: reason,
                    as: author
                )
            case let .thread(thread):
                model.report(
                    type: .thread,
                    targetId: thread.id,
                    targetAuthorId: thread.authorId,
                    reason: reason,
                    as: author
                )
            }
            reportTarget = nil
            reportThanks = true
        }
        .presentationDetents([.height(280)])
        .presentationDragIndicator(.visible)
        .presentationBackground(Theme.surface)
    }

    @ViewBuilder
    private var messages: some View {
        if model.isLoading {
            ProgressView()
                .tint(Theme.subtle)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if displayedMessages.isEmpty {
            VStack(spacing: 10) {
                Image(systemName: "bubble.left.and.bubble.right")
                    .font(.system(size: 30, weight: .light))
                    .foregroundStyle(Theme.faint)
                Text("Nobody has said anything yet")
                    .font(.cardTitle)
                    .foregroundStyle(Theme.subtle)
                Text("Go first — say something or show a photo.")
                    .font(.subheadline)
                    .foregroundStyle(Theme.faint)
                    .multilineTextAlignment(.center)
            }
            .padding(40)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .contentShape(Rectangle())
            .onTapGesture { isComposing = false }
        } else {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 0) {
                        Color.clear
                            .frame(height: 1)
                            .onAppear {
                                guard historyLoadEnabled,
                                      model.hasMoreHistory,
                                      !model.isLoadingOlder
                                else { return }
                                model.loadOlder()
                            }
                        if model.isLoadingOlder {
                            ProgressView()
                                .tint(Theme.subtle)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 10)
                        }
                        ForEach(Array(displayedMessages.enumerated()), id: \.element.id) { index, message in
                            MessageRow(
                                message: message,
                                isMine: message.authorId == author.uid,
                                myUid: author.uid,
                                startsRun: startsRun(at: index),
                                endsRun: endsRun(at: index),
                                isLifted: longPressTarget?.id == message.id,
                                onLongPress: {
                                    guard !message.isLocalPending else { return }
                                    longPressFrame = bubbleFrames[message.id] ?? .zero
                                    isComposing = false
                                    longPressTarget = message
                                },
                                onToggleReaction: { emoji in
                                    guard !message.isLocalPending else { return }
                                    model.toggleReaction(emoji, on: message, as: author)
                                },
                                onOpenVideo: { path, durationMs in
                                    isComposing = false
                                    fullscreenVideo = FullscreenVideoRoute(
                                        path: path,
                                        durationMs: durationMs
                                    )
                                }
                            )
                            .id(message.id)
                            .onAppear {
                                if index >= displayedMessages.count - 3 {
                                    pinnedToBottom = true
                                } else if index < displayedMessages.count - 8 {
                                    pinnedToBottom = false
                                }
                            }
                        }
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 16)
                    .frame(maxWidth: .infinity)
                    // Empty space under/around bubbles — tap dismisses the keyboard (Simulator
                    // often did this for free; real devices need an explicit gesture).
                    .background {
                        Color.clear
                            .contentShape(Rectangle())
                            .onTapGesture { isComposing = false }
                    }
                }
                .scrollDismissesKeyboard(.interactively)
                .onChange(of: displayedMessages.last?.id) { _, tipId in
                    guard let tipId else { return }
                    let firstOpen = lastTipId == nil
                    let tipGrew = lastTipId != nil && tipId != lastTipId
                    lastTipId = tipId
                    if firstOpen || (tipGrew && pinnedToBottom) {
                        scroll(proxy, animated: !firstOpen)
                        if firstOpen {
                            // Let the tip settle at the bottom before the top sentinel can page.
                            DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
                                historyLoadEnabled = true
                            }
                        }
                    }
                }
                .onChange(of: model.historyPrepended) { _, prepended in
                    guard prepended > 0 else { return }
                    // Keep the previously top-visible bubble on screen after older pages land.
                    if let anchor = displayedMessages.dropFirst(prepended).first {
                        proxy.scrollTo(anchor.id, anchor: .top)
                    }
                    model.consumeHistoryPrepend()
                }
                .onAppear {
                    scroll(proxy, animated: false)
                }
            }
        }
    }

    private var composer: some View {
        VStack(spacing: 0) {
            Rectangle()
                .fill(Theme.hairline)
                .frame(height: 1)

            if let reply = model.replyTarget {
                HStack(spacing: 10) {
                    RoundedRectangle(cornerRadius: 2)
                        .fill(Theme.accent)
                        .frame(width: 3, height: 36)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Reply to \(reply.authorName)")
                            .font(.meta)
                            .foregroundStyle(Theme.accent)
                        Text(replyPreview(reply))
                            .font(.subheadline)
                            .foregroundStyle(Theme.subtle)
                            .lineLimit(1)
                    }
                    Spacer(minLength: 0)
                    Button {
                        model.clearReply()
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundStyle(Theme.subtle)
                    }
                    .accessibilityLabel("Cancel reply")
                }
                .padding(.horizontal, 14)
                .padding(.top, 10)
            }

            if let pendingImage {
                HStack(alignment: .top, spacing: 10) {
                    Image(uiImage: pendingImage)
                        .resizable()
                        .scaledToFill()
                        .frame(width: 72, height: 72)
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    Spacer(minLength: 0)
                    Button {
                        self.pendingImage = nil
                        pickerItem = nil
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.system(size: 22))
                            .foregroundStyle(Theme.subtle)
                    }
                    .accessibilityLabel("Remove photo")
                }
                .padding(.horizontal, 14)
                .padding(.top, 10)
            }

            if pendingVideo != nil {
                HStack(alignment: .top, spacing: 10) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .fill(Theme.raised)
                            .frame(width: 72, height: 72)
                        if let videoPreview {
                            Image(uiImage: videoPreview)
                                .resizable()
                                .interpolation(.high)
                                .scaledToFill()
                                .frame(width: 72, height: 72)
                                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                        }
                        Image(systemName: "play.fill")
                            .font(.system(size: 13, weight: .bold))
                            .foregroundStyle(.white)
                            .frame(width: 28, height: 28)
                            .background(.black.opacity(0.45), in: Circle())
                    }
                    Spacer(minLength: 0)
                    Button(action: removePendingVideo) {
                        Image(systemName: "xmark.circle.fill")
                            .font(.system(size: 22))
                            .foregroundStyle(Theme.subtle)
                    }
                    .accessibilityLabel("Remove video")
                }
                .padding(.horizontal, 14)
                .padding(.top, 10)
            }

            if let status = videoSendPhase.statusCopy(detail: videoFailDetail) {
                VideoSendStatusBanner(
                    status: status,
                    phase: videoSendPhase,
                    isBusy: isPreparingVideo,
                    preview: videoPreview,
                    onCancel: cancelVideoSend,
                    onRetry: retryVideoSend
                )
            }

            if showStickers {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 12) {
                        ForEach(StickerPack.all, id: \.self) { glyph in
                            Button {
                                model.send("", as: author, sticker: glyph)
                                showStickers = false
                            } label: {
                                Text(glyph)
                                    .font(.system(size: 34))
                                    .frame(width: 48, height: 48)
                            }
                            .buttonStyle(.pressable)
                        }
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                }
            }

            if recorder.isRecording {
                HStack(spacing: 12) {
                    Circle()
                        .fill(Theme.danger)
                        .frame(width: 10, height: 10)
                    Text(recorder.elapsedLabel)
                        .font(.cardTitle)
                        .foregroundStyle(Theme.text)
                        .monospacedDigit()
                    Spacer()
                    Button("Cancel") {
                        recorder.cancel()
                    }
                    .foregroundStyle(Theme.subtle)
                    Button("Send") {
                        if let audio = recorder.stop() {
                            model.send("", as: author, audio: audio)
                        }
                    }
                    .fontWeight(.semibold)
                    .foregroundStyle(Theme.accent)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
            } else {
                HStack(alignment: .bottom, spacing: 8) {
                    ComposerMediaControls(
                        showStickers: showStickers,
                        isPreparingImage: isPreparingImage,
                        isPreparingVideo: isPreparingVideo,
                        pendingImage: pendingImage != nil,
                        pendingVideo: pendingVideo != nil,
                        pickerItem: $pickerItem,
                        videoPickerItem: $videoPickerItem,
                        onToggleStickers: { showStickers.toggle() }
                    )

                    TextField(
                        "",
                        text: $draft,
                        prompt: Text(pendingImage == nil && pendingVideo == nil ? "Say something" : "Add a caption")
                            .foregroundStyle(Theme.faint),
                        axis: .vertical
                    )
                    .font(.body)
                    .foregroundStyle(Theme.text)
                    .focused($isComposing)
                    .lineLimit(1...5)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    // Rounded rect, not Capsule — a capsule stretches into a weird
                    // sausage as soon as the field grows past one line.
                    .background(Theme.raised, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                    .overlay {
                        RoundedRectangle(cornerRadius: 20, style: .continuous)
                            .strokeBorder(isComposing ? Theme.accent : Theme.hairline, lineWidth: 1)
                    }
                    .onChange(of: draft) { _, newValue in
                        if newValue.count > ThreadModel.maxMessageLength {
                            draft = String(newValue.prefix(ThreadModel.maxMessageLength))
                        }
                    }

                    if canSend || isPreparingImage {
                        Button(action: send) {
                            Group {
                                if isPreparingImage {
                                    ProgressView().tint(.white)
                                } else {
                                    Image(systemName: "arrow.up")
                                        .font(.system(size: 16, weight: .bold))
                                }
                            }
                            .foregroundStyle(.white)
                            .frame(width: 40, height: 40)
                            .background(Theme.accent, in: Circle())
                        }
                        .buttonStyle(.pressable)
                        .disabled(!canSend || isPreparingImage)
                        .accessibilityLabel("Send")
                        .transition(.scale(scale: 0.6).combined(with: .opacity))
                    } else {
                        Button {
                            Task { await recorder.start() }
                        } label: {
                            Image(systemName: "mic.fill")
                                .font(.system(size: 16, weight: .bold))
                                .foregroundStyle(Theme.subtle)
                                .frame(width: 40, height: 40)
                                .background(Theme.raised, in: Circle())
                        }
                        .buttonStyle(.pressable)
                        .accessibilityLabel("Record a voice note")
                        .transition(.scale(scale: 0.6).combined(with: .opacity))
                    }
                }
                .animation(Self.composerSpring, value: canSend || isPreparingImage)
                .padding(.horizontal, 10)
                .padding(.vertical, 10)
            }
        }
        .background(Theme.surface)
    }

    private func replyPreview(_ message: Message) -> String {
        if message.isSticker { return message.text }
        if message.hasVoice { return "Voice note" }
        if message.hasVideo { return message.text.isEmpty ? "Video" : message.text }
        if message.hasImage { return message.text.isEmpty ? "Photo" : message.text }
        return message.text
    }

    private func send() {
        let caption = draft
        draft = ""
        showStickers = false

        if let video = pendingVideo {
            pendingVideo = nil
            videoPickerItem = nil
            videoTask?.cancel()
            videoTask = Task { await uploadPreparedVideo(video, caption: caption) }
            return
        }

        let image = pendingImage.flatMap(ImageCompressor.prepare)
        model.send(caption, as: author, image: image)
        pendingImage = nil
        pickerItem = nil
    }

    private func removePendingVideo() {
        pendingVideo?.deleteTempFile()
        pendingVideo = nil
        videoPickerItem = nil
        videoPreview = nil
    }

    private func loadPickerItem(_ item: PhotosPickerItem) async {
        isPreparingImage = true
        defer { isPreparingImage = false }
        guard let data = try? await item.loadTransferable(type: Data.self),
              let image = UIImage(data: data)
        else { return }
        pendingImage = image
    }

    private func loadVideoItem(_ item: PhotosPickerItem) async {
        videoFailDetail = nil
        videoPreview = nil
        pendingOutgoing = nil
        pendingTrim = nil
        videoSendPhase = .compressing
        defer { videoPickerItem = nil }
        guard let movie = try? await item.loadTransferable(type: PickedMovie.self) else {
            if !Task.isCancelled {
                videoFailDetail = VideoCompressor.PrepareError.unreadable.localizedDescription
                videoSendPhase = .failed
            }
            return
        }
        videoPreview = await Self.videoPoster(from: movie.url)
        if Task.isCancelled {
            try? FileManager.default.removeItem(at: movie.url)
            videoSendPhase = .idle
            return
        }
        await compressAndContinue(from: movie.url, clipStartMs: nil, deleteSourceWhenDone: true)
    }

    private func compressAndContinue(
        from sourceURL: URL,
        clipStartMs: Int?,
        deleteSourceWhenDone: Bool
    ) async {
        videoSendPhase = .compressing
        let outcome = await VideoCompressor.prepare(from: sourceURL, clipStartMs: clipStartMs)
        switch outcome {
        case .needsTrim(let url, let durationMs):
            // Keep source for the trimmer; don't delete here.
            pendingTrim = PendingVideoTrim(sourceURL: url, durationMs: durationMs)
            videoSendPhase = .idle
        case .failed(let error):
            if deleteSourceWhenDone {
                try? FileManager.default.removeItem(at: sourceURL)
            }
            guard !Task.isCancelled else {
                videoSendPhase = .idle
                return
            }
            videoFailDetail = error.localizedDescription
            videoSendPhase = .failed
        case .ready(let prepared):
            if deleteSourceWhenDone {
                try? FileManager.default.removeItem(at: sourceURL)
            }
            guard !Task.isCancelled else {
                prepared.deleteTempFile()
                videoSendPhase = .idle
                return
            }
            if videoPreview == nil {
                videoPreview = await Self.videoPoster(from: prepared.fileURL)
            }
            // Park it on the composer instead of sending, so there's a chance to
            // write a caption first — same as a photo.
            pendingVideo = prepared
            videoSendPhase = .idle
        }
    }

    private func uploadPreparedVideo(_ prepared: PreparedVideo, caption: String) async {
        retryPreparedVideo = prepared
        retryVideoCaption = caption
        pendingOutgoing = Message(
            id: "local:video-pending",
            kind: .video,
            text: caption,
            authorId: author.uid,
            authorName: author.displayName,
            createdAt: Date(),
            videoPath: prepared.fileURL.path,
            videoDurationMs: prepared.durationMs,
            videoWidth: prepared.width,
            videoHeight: prepared.height
        )
        videoSendPhase = .uploading
        model.errorMessage = nil
        videoFailDetail = nil
        await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
            model.send(caption, as: author, video: prepared) {
                cont.resume()
            }
        }
        pendingOutgoing = nil
        if Task.isCancelled {
            prepared.deleteTempFile()
            retryPreparedVideo = nil
            videoSendPhase = .idle
        } else if let error = model.errorMessage {
            videoFailDetail = error
            model.errorMessage = nil
            videoSendPhase = .failed
        } else {
            prepared.deleteTempFile()
            retryPreparedVideo = nil
            videoPreview = nil
            videoSendPhase = .idle
        }
    }

    private func retryVideoSend() {
        guard let prepared = retryPreparedVideo else { return }
        let caption = retryVideoCaption
        videoTask?.cancel()
        videoTask = Task {
            await uploadPreparedVideo(prepared, caption: caption)
        }
    }

    private func cancelVideoSend() {
        videoTask?.cancel()
        videoTask = nil
        if let trim = pendingTrim {
            try? FileManager.default.removeItem(at: trim.sourceURL)
        }
        pendingTrim = nil
        pendingVideo?.deleteTempFile()
        pendingVideo = nil
        retryPreparedVideo?.deleteTempFile()
        retryPreparedVideo = nil
        retryVideoCaption = ""
        pendingOutgoing = nil
        videoFailDetail = nil
        videoPreview = nil
        videoSendPhase = .idle
    }

    private static func videoPoster(from url: URL) async -> UIImage? {
        let asset = AVURLAsset(url: url)
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: 400, height: 400)
        let time = CMTime(seconds: 0.05, preferredTimescale: 600)
        guard let cg = try? await generator.image(at: time).image else { return nil }
        return UIImage(cgImage: cg)
    }

    private func scroll(_ proxy: ScrollViewProxy, animated: Bool) {
        guard let last = displayedMessages.last else { return }
        if animated {
            withAnimation(.spring(duration: 0.35)) { proxy.scrollTo(last.id, anchor: .bottom) }
        } else {
            proxy.scrollTo(last.id, anchor: .bottom)
        }
    }

    private func startsRun(at index: Int) -> Bool {
        guard index > 0 else { return true }
        return !displayedMessages[index].follows(displayedMessages[index - 1])
    }

    private func endsRun(at index: Int) -> Bool {
        guard index < displayedMessages.count - 1 else { return true }
        return !displayedMessages[index + 1].follows(displayedMessages[index])
    }

    private func dismissLongPress() {
        longPressTarget = nil
        longPressFrame = .zero
    }
}

private struct BubbleFrameKey: PreferenceKey {
    nonisolated(unsafe) static var defaultValue: [String: CGRect] = [:]

    static func reduce(value: inout [String: CGRect], nextValue: () -> [String: CGRect]) {
        value.merge(nextValue(), uniquingKeysWith: { _, new in new })
    }
}

private struct ReactionBarWidthKey: PreferenceKey {
    nonisolated(unsafe) static var defaultValue: CGFloat = 0

    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = max(value, nextValue())
    }
}

private struct ActionMenuHeightKey: PreferenceKey {
    nonisolated(unsafe) static var defaultValue: CGFloat = 0

    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = max(value, nextValue())
    }
}

private extension Message {
    func follows(_ previous: Message) -> Bool {
        guard authorId == previous.authorId else { return false }
        guard let mine = createdAt, let theirs = previous.createdAt else { return true }
        return mine.timeIntervalSince(theirs) < 300
    }
}

private enum ReportSheetTarget: Identifiable {
    case message(Message)
    case thread(ChatThread)

    var id: String {
        switch self {
        case let .message(message): "m-\(message.id)"
        case let .thread(thread): "t-\(thread.id)"
        }
    }
}

private struct BlockConfirm: Identifiable {
    let uid: String
    let name: String
    var id: String { uid }
}

private struct ReportReasonSheet: View {
    let onPick: (ReportReason) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Why are you reporting this?")
                .font(.cardTitle)
                .foregroundStyle(Theme.text)
                .padding(.horizontal, 20)
                .padding(.top, 8)

            ForEach(ReportReason.allCases, id: \.rawValue) { reason in
                Button {
                    onPick(reason)
                } label: {
                    Text(reason.label)
                        .font(.body)
                        .foregroundStyle(Theme.text)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 20)
                        .padding(.vertical, 12)
                }
                .buttonStyle(.plain)
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}

/// Facebook-style long-press: photo stays put; scrim + chrome animate around it.
private struct MessageLongPressOverlay: View {
    let message: Message
    let isMine: Bool
    let myUid: String
    let anchor: CGRect
    let onReact: (String) -> Void
    let onReply: () -> Void
    let onEdit: () -> Void
    let onDelete: () -> Void
    let onReport: () -> Void
    let onBlock: () -> Void
    let onDismiss: () -> Void

    @State private var scrim = false
    @State private var chromeIn = false
    @State private var emojiPop: [Bool]
    @State private var isExiting = false
    /// Measured width of the reaction pill — the clamp used to assume 5 icons (286pt)
    /// while we ship 6, which clipped the trailing edge on outgoing bubbles.
    @State private var reactionBarWidth: CGFloat = 330
    @State private var actionMenuHeight: CGFloat = 0

    init(
        message: Message,
        isMine: Bool,
        myUid: String,
        anchor: CGRect,
        onReact: @escaping (String) -> Void,
        onReply: @escaping () -> Void,
        onEdit: @escaping () -> Void,
        onDelete: @escaping () -> Void,
        onReport: @escaping () -> Void,
        onBlock: @escaping () -> Void,
        onDismiss: @escaping () -> Void
    ) {
        self.message = message
        self.isMine = isMine
        self.myUid = myUid
        self.anchor = anchor
        self.onReact = onReact
        self.onReply = onReply
        self.onEdit = onEdit
        self.onDelete = onDelete
        self.onReport = onReport
        self.onBlock = onBlock
        self.onDismiss = onDismiss
        _emojiPop = State(initialValue: Array(repeating: false, count: ReactionEmoji.allCases.count))
    }

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .topLeading) {
                Color.black.opacity(scrim ? 0.55 : 0)
                    .ignoresSafeArea()
                    .onTapGesture(perform: dismissAnimated)

                // Keep the lift sized to the real bubble — without a width cap,
                // GeometryReader lets long text expand to the overlay edge and clip.
                liftedMessage
                    .frame(width: max(anchor.width, 1), alignment: .leading)
                    .fixedSize(horizontal: false, vertical: true)
                    .shadow(color: .black.opacity(chromeIn ? 0.4 : 0), radius: chromeIn ? 18 : 0, y: chromeIn ? 10 : 0)
                    .offset(x: anchor.minX, y: anchor.minY)
                    .allowsHitTesting(false)

                reactionBar
                    .fixedSize()
                    .background {
                        GeometryReader { barGeo in
                            Color.clear.preference(
                                key: ReactionBarWidthKey.self,
                                value: barGeo.size.width
                            )
                        }
                    }
                    .onPreferenceChange(ReactionBarWidthKey.self) { reactionBarWidth = $0 }
                    .scaleEffect(chromeIn ? 1 : 0.82)
                    .opacity(chromeIn ? 1 : 0)
                    .offset(
                        x: clampedReactionBarX(in: geo.size.width),
                        y: chromeLayout(in: geo.size).barY
                    )

                actionMenu
                    .fixedSize()
                    .background {
                        GeometryReader { menuGeo in
                            Color.clear.preference(
                                key: ActionMenuHeightKey.self,
                                value: menuGeo.size.height
                            )
                        }
                    }
                    .onPreferenceChange(ActionMenuHeightKey.self) { actionMenuHeight = $0 }
                    .scaleEffect(
                        chromeIn ? 1 : 0.94,
                        anchor: chromeLayout(in: geo.size).flipped
                            ? (isMine ? .bottomTrailing : .bottomLeading)
                            : (isMine ? .topTrailing : .topLeading)
                    )
                    .opacity(chromeIn ? 1 : 0)
                    .offset(
                        x: isMine ? anchor.maxX - menuWidth : anchor.minX,
                        y: chromeLayout(in: geo.size).menuY
                    )
            }
        }
        .onAppear(perform: playEnter)
    }

    // Approximate menu width so we can place it without a second layout pass.
    private var menuWidth: CGFloat { isMine ? 160 : 220 }

    private var estimatedMenuHeight: CGFloat {
        let rows: CGFloat
        if isMine {
            rows = message.isEditable ? 3 : 2
        } else {
            rows = 3
        }
        return rows * 46 + max(0, rows - 1)
    }

    private struct ChromeLayout {
        var barY: CGFloat
        var menuY: CGFloat
        var flipped: Bool
    }

    /// Keep reaction bar + action menu from overlapping when the menu flips above the bubble.
    private func chromeLayout(in size: CGSize) -> ChromeLayout {
        let margin: CGFloat = 16
        let gap: CGFloat = 10
        let barH: CGFloat = 62
        let menuH = max(actionMenuHeight, estimatedMenuHeight)
        let barAboveBubble = anchor.minY - 58
        let menuBelowBubble = anchor.maxY + 10

        if menuBelowBubble + menuH <= size.height - margin {
            return ChromeLayout(barY: barAboveBubble, menuY: menuBelowBubble, flipped: false)
        }

        // Stack above the bubble: [reactions] then [menu], both fully on-screen.
        let stackH = barH + gap + menuH
        let stackTop = max(margin, min(anchor.minY - 10 - stackH, size.height - margin - stackH))
        return ChromeLayout(
            barY: stackTop,
            menuY: stackTop + barH + gap,
            flipped: true
        )
    }

    /// Keep the reaction pill fully on-screen. Outgoing bubbles sit near the
    /// trailing edge, so centering on midX would push half the bar off-screen.
    private func clampedReactionBarX(in width: CGFloat) -> CGFloat {
        let margin: CGFloat = 12
        let barW = max(reactionBarWidth, 1)
        let ideal = anchor.midX - barW / 2
        let maxX = max(margin, width - barW - margin)
        return min(max(ideal, margin), maxX)
    }

    private var reactionBar: some View {
        HStack(spacing: 10) {
            ForEach(Array(ReactionEmoji.allCases.enumerated()), id: \.element.rawValue) { index, reaction in
                Button {
                    onReact(reaction.rawValue)
                    dismissAnimated()
                } label: {
                    Text(reaction.rawValue)
                        .font(.system(size: 30))
                        .frame(width: 42, height: 42)
                        .background(
                            message.reacted(by: myUid, emoji: reaction.rawValue)
                                ? Theme.accent.opacity(0.25)
                                : Color.clear,
                            in: Circle()
                        )
                        .scaleEffect(emojiPop.indices.contains(index) && emojiPop[index] ? 1 : 0.4)
                        .opacity(emojiPop.indices.contains(index) && emojiPop[index] ? 1 : 0)
                }
                .buttonStyle(.pressable)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(.ultraThinMaterial, in: Capsule())
        .overlay {
            Capsule().strokeBorder(Theme.hairline, lineWidth: 1)
        }
        .shadow(color: .black.opacity(0.35), radius: 16, y: 8)
    }

    @ViewBuilder
    private var liftedMessage: some View {
        if message.isSticker {
            Text(message.text)
                .font(.system(size: 56))
                .padding(4)
        } else {
            let mediaOnly = (message.hasImage || message.hasVideo) && message.text.isEmpty && message.reply == nil
            VStack(alignment: .leading, spacing: 6) {
                if let reply = message.reply {
                    HStack(spacing: 8) {
                        RoundedRectangle(cornerRadius: 2)
                            .fill(isMine ? Color.white.opacity(0.7) : Theme.accent)
                            .frame(width: 3)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(reply.authorName)
                                .font(.meta)
                                .foregroundStyle(isMine ? Color.white.opacity(0.85) : Theme.accent)
                            Text(reply.text)
                                .font(.subheadline)
                                .foregroundStyle(isMine ? Color.white.opacity(0.75) : Theme.subtle)
                                .lineLimit(2)
                        }
                    }
                    .padding(8)
                    .background(
                        (isMine ? Color.white.opacity(0.12) : Theme.base.opacity(0.55)),
                        in: RoundedRectangle(cornerRadius: 10, style: .continuous)
                    )
                }

                if message.hasImage, let path = message.imagePath {
                    MessageImage(
                        path: path,
                        width: message.imageWidth,
                        height: message.imageHeight,
                        allowsFullscreen: false
                    )
                }

                if message.hasVideo, let path = message.videoPath {
                    VideoBubble(
                        path: path,
                        durationMs: message.videoDurationMs ?? 0,
                        width: message.videoWidth,
                        height: message.videoHeight,
                        isSending: message.isLocalPending
                    )
                }

                if message.hasVoice, let path = message.audioPath {
                    VoiceBubble(
                        path: path,
                        durationMs: message.audioDurationMs ?? 0,
                        isMine: isMine
                    )
                }

                if !message.text.isEmpty, !message.hasVoice {
                    Text(message.text)
                        .font(.body)
                        .foregroundStyle(isMine ? .white : Theme.text)
                        .lineLimit(8)
                }
            }
            .padding(.horizontal, mediaOnly ? 4 : 12)
            .padding(.vertical, mediaOnly ? 4 : 9)
            .background(
                isMine ? Theme.accent : Theme.raised,
                in: RoundedRectangle(cornerRadius: Theme.Radius.bubble, style: .continuous)
            )
        }
    }

    private var actionMenu: some View {
        VStack(spacing: 0) {
            menuRow(title: "Reply", systemImage: "arrowshape.turn.up.left") {
                onReply()
                dismissAnimated()
            }
            if isMine {
                if message.isEditable {
                    Divider().overlay(Theme.hairline)
                    menuRow(title: "Edit", systemImage: "pencil") {
                        onEdit()
                        dismissAnimated()
                    }
                }
                Divider().overlay(Theme.hairline)
                menuRow(title: "Delete", systemImage: "trash", tint: Theme.danger) {
                    onDelete()
                    dismissAnimated()
                }
            } else {
                Divider().overlay(Theme.hairline)
                menuRow(title: "Report", systemImage: "flag") {
                    onReport()
                    dismissAnimated()
                }
                Divider().overlay(Theme.hairline)
                menuRow(
                    title: "Block \(message.authorName)",
                    systemImage: "hand.raised",
                    tint: Theme.danger
                ) {
                    onBlock()
                    dismissAnimated()
                }
            }
        }
        .frame(width: menuWidth, alignment: .leading)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .strokeBorder(Theme.hairline, lineWidth: 1)
        }
        .shadow(color: .black.opacity(0.4), radius: 18, y: 10)
    }

    private func menuRow(
        title: String,
        systemImage: String,
        tint: Color = Theme.text,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: systemImage)
                    .font(.system(size: 16, weight: .semibold))
                    .frame(width: 22)
                Text(title)
                    .font(.body)
                Spacer(minLength: 0)
            }
            .foregroundStyle(tint)
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func playEnter() {
        withAnimation(.easeOut(duration: 0.22)) { scrim = true }
        withAnimation(.spring(response: 0.36, dampingFraction: 0.78)) {
            chromeIn = true
        }
        for index in ReactionEmoji.allCases.indices {
            withAnimation(.spring(response: 0.3, dampingFraction: 0.55).delay(0.04 + Double(index) * 0.028)) {
                if emojiPop.indices.contains(index) {
                    emojiPop[index] = true
                }
            }
        }
    }

    private func dismissAnimated() {
        guard !isExiting else { return }
        isExiting = true
        withAnimation(.easeOut(duration: 0.2)) {
            chromeIn = false
            scrim = false
            emojiPop = Array(repeating: false, count: emojiPop.count)
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.22) {
            onDismiss()
        }
    }
}

// MARK: - Rows

private struct MessageRow: View {
    let message: Message
    let isMine: Bool
    let myUid: String
    let startsRun: Bool
    let endsRun: Bool
    var isLifted: Bool = false
    let onLongPress: () -> Void
    let onToggleReaction: (String) -> Void
    var onOpenVideo: (String, Int) -> Void = { _, _ in }

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            if isMine {
                Spacer(minLength: 48)
            } else if startsRun {
                InitialAvatar(name: message.authorName, seed: message.authorId, size: 30)
            } else {
                Color.clear.frame(width: 30, height: 1)
            }

            VStack(alignment: isMine ? .trailing : .leading, spacing: 4) {
                if !isMine, startsRun {
                    Text(message.authorName)
                        .font(.meta)
                        .foregroundStyle(InitialAvatar.tint(for: message.authorId))
                        .padding(.leading, 2)
                }

                bubble
                    .background {
                        GeometryReader { geo in
                            Color.clear.preference(
                                key: BubbleFrameKey.self,
                                value: [message.id: geo.frame(in: .named("thread"))]
                            )
                        }
                    }
                    .onLongPressGesture(minimumDuration: 0.35) {
                        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                        onLongPress()
                    }
                    .opacity(isLifted ? 0 : 1)

                if !message.reactions.isEmpty {
                    ReactionStrip(
                        reactions: message.reactions,
                        myUid: myUid,
                        onToggle: onToggleReaction
                    )
                    .opacity(isLifted ? 0 : 1)
                }

                if endsRun {
                    HStack(spacing: 4) {
                        Text(message.isLocalPending ? "Sending…" : relativeTime(message.createdAt))
                        if message.isEdited, !message.isLocalPending {
                            Text("· Edited")
                        }
                    }
                    .font(.meta)
                    .foregroundStyle(Theme.faint)
                    .padding(.horizontal, 2)
                    .opacity(isLifted ? 0 : 1)
                }
            }

            if !isMine { Spacer(minLength: 48) }
        }
        .padding(.top, startsRun ? 12 : 2)
    }

    @ViewBuilder
    private var bubble: some View {
        if message.isSticker {
            Text(message.text)
                .font(.system(size: 56))
                .padding(4)
        } else {
            VStack(alignment: .leading, spacing: 6) {
                if let reply = message.reply {
                    HStack(spacing: 8) {
                        RoundedRectangle(cornerRadius: 2)
                            .fill(isMine ? Color.white.opacity(0.7) : Theme.accent)
                            .frame(width: 3)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(reply.authorName)
                                .font(.meta)
                                .foregroundStyle(isMine ? Color.white.opacity(0.85) : Theme.accent)
                            Text(reply.text)
                                .font(.subheadline)
                                .foregroundStyle(isMine ? Color.white.opacity(0.75) : Theme.subtle)
                                .lineLimit(2)
                        }
                    }
                    .padding(8)
                    .background(
                        (isMine ? Color.white.opacity(0.12) : Theme.base.opacity(0.55)),
                        in: RoundedRectangle(cornerRadius: 10, style: .continuous)
                    )
                }

                if message.hasImage, let path = message.imagePath {
                    MessageImage(path: path, width: message.imageWidth, height: message.imageHeight)
                }

                if message.hasVideo, let path = message.videoPath {
                    VideoBubble(
                        path: path,
                        durationMs: message.videoDurationMs ?? 0,
                        width: message.videoWidth,
                        height: message.videoHeight,
                        isSending: message.isLocalPending,
                        onOpenFullscreen: {
                            onOpenVideo(path, message.videoDurationMs ?? 0)
                        }
                    )
                }

                if message.hasVoice, let path = message.audioPath {
                    VoiceBubble(
                        path: path,
                        durationMs: message.audioDurationMs ?? 0,
                        isMine: isMine
                    )
                }

                if !message.text.isEmpty, !message.hasVoice {
                    Text(message.text)
                        .font(.body)
                        .foregroundStyle(isMine ? .white : Theme.text)
                }
            }
            .padding(.horizontal, (message.hasImage || message.hasVideo) && message.text.isEmpty && message.reply == nil ? 4 : 12)
            .padding(.vertical, (message.hasImage || message.hasVideo) && message.text.isEmpty && message.reply == nil ? 4 : 9)
            .background(
                isMine ? Theme.accent : Theme.raised,
                in: Theme.bubble(
                    radius: Theme.Radius.bubble,
                    tail: endsRun ? (isMine ? .bottomTrailing : .bottomLeading) : nil
                )
            )
        }
    }
}

private struct ReactionStrip: View {
    let reactions: [String: [String]]
    let myUid: String
    let onToggle: (String) -> Void

    var body: some View {
        HStack(spacing: 6) {
            ForEach(reactions.keys.sorted(), id: \.self) { emoji in
                let uids = reactions[emoji] ?? []
                Button {
                    onToggle(emoji)
                } label: {
                    HStack(spacing: 4) {
                        Text(emoji).font(.system(size: 13))
                        Text("\(uids.count)")
                            .font(.meta)
                            .foregroundStyle(uids.contains(myUid) ? Theme.accent : Theme.subtle)
                    }
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(
                        Theme.raised,
                        in: Capsule()
                    )
                    .overlay {
                        Capsule().strokeBorder(
                            uids.contains(myUid) ? Theme.accent.opacity(0.7) : Theme.hairline,
                            lineWidth: 1
                        )
                    }
                }
                .buttonStyle(.plain)
            }
        }
    }
}

private struct MessageImage: View {
    let path: String
    let width: Int?
    let height: Int?
    var allowsFullscreen: Bool = true

    @State private var showFullscreen = false
    @State private var remoteImage: UIImage?

    private var isRemote: Bool {
        path.hasPrefix("http://") || path.hasPrefix("https://")
    }

    private var localImage: UIImage? {
        guard !isRemote else { return nil }
        return UIImage(contentsOfFile: LocalMediaStore.url(forRelativePath: path).path)
    }

    private var displayImage: UIImage? { localImage ?? remoteImage }

    var body: some View {
        Group {
            if let displayImage {
                Image(uiImage: displayImage)
                    .resizable()
                    .scaledToFill()
            } else if isRemote {
                Color.black.opacity(0.2)
                    .overlay { ProgressView().tint(Theme.subtle) }
            } else {
                Color.black.opacity(0.2)
                    .overlay {
                        Image(systemName: "photo")
                            .foregroundStyle(Theme.faint)
                    }
            }
        }
        .frame(width: displayWidth, height: displayHeight)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .contentShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .onTapGesture {
            guard allowsFullscreen else { return }
            showFullscreen = true
        }
        .task(id: path) {
            guard isRemote, remoteImage == nil, let url = URL(string: path) else { return }
            if let (data, _) = try? await URLSession.shared.data(from: url) {
                remoteImage = UIImage(data: data)
            }
        }
        .fullScreenCover(isPresented: $showFullscreen) {
            FullscreenImageViewer(image: displayImage, onDismiss: { showFullscreen = false })
        }
    }

    private var displayWidth: CGFloat { 220 }
    private var displayHeight: CGFloat {
        guard let width, let height, width > 0 else { return 160 }
        return min(280, displayWidth * CGFloat(height) / CGFloat(width))
    }
}

private struct FullscreenImageViewer: View {
    let image: UIImage?
    let onDismiss: () -> Void

    @State private var scale: CGFloat = 1
    @State private var lastScale: CGFloat = 1
    @State private var dragOffset: CGFloat = 0

    private var dragProgress: CGFloat {
        min(1, dragOffset / Self.dragTravel)
    }

    private var isZoomed: Bool { scale > 1.05 }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .scaleEffect(scale * (1 - dragProgress * 0.14))
                    .offset(y: dragOffset)
                    .geometryGroup()
                    .padding(12)
                    .gesture(magnifyGesture)
                    .simultaneousGesture(swipeToDismiss)
                    .onTapGesture(count: 2) {
                        withAnimation(.easeOut(duration: 0.2)) {
                            if scale > 1.1 {
                                scale = 1
                                lastScale = 1
                            } else {
                                scale = 2.5
                                lastScale = 2.5
                            }
                        }
                    }
            }

            VStack {
                HStack {
                    Spacer()
                    Button(action: onDismiss) {
                        Image(systemName: "xmark.circle.fill")
                            .font(.system(size: 28))
                            .foregroundStyle(.white.opacity(0.85))
                            .padding(16)
                    }
                    .accessibilityLabel("Close")
                }
                Spacer()
            }
            .opacity(1 - Double(min(1, dragOffset / 70)))
        }
        .statusBarHidden(true)
    }

    private var magnifyGesture: some Gesture {
        MagnifyGesture()
            .onChanged { value in
                scale = max(1, min(4, lastScale * value.magnification))
            }
            .onEnded { _ in
                lastScale = scale
                if scale < 1.05 {
                    withAnimation(.easeOut(duration: 0.2)) {
                        scale = 1
                        lastScale = 1
                    }
                }
            }
    }

    private var swipeToDismiss: some Gesture {
        DragGesture(minimumDistance: 14)
            .onChanged { value in
                guard !isZoomed else { return }
                dragOffset = max(0, value.translation.height)
            }
            .onEnded { value in
                guard !isZoomed else {
                    dragOffset = 0
                    return
                }
                let flung = value.predictedEndTranslation.height > Self.dragTravel
                if dragOffset > Self.dismissThreshold || flung {
                    onDismiss()
                } else {
                    withAnimation(.interactiveSpring(response: 0.32, dampingFraction: 0.82)) {
                        dragOffset = 0
                    }
                }
            }
    }

    private static let dragTravel: CGFloat = 240
    private static let dismissThreshold: CGFloat = 110
}

/// Stickers / photo / video — always visible so text and media stay equal.
private struct ComposerMediaControls: View {
    let showStickers: Bool
    let isPreparingImage: Bool
    let isPreparingVideo: Bool
    let pendingImage: Bool
    let pendingVideo: Bool
    @Binding var pickerItem: PhotosPickerItem?
    @Binding var videoPickerItem: PhotosPickerItem?
    let onToggleStickers: () -> Void

    var body: some View {
        HStack(spacing: 2) {
            Button(action: onToggleStickers) {
                Image(systemName: showStickers ? "face.smiling.fill" : "face.smiling")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(Theme.subtle)
                    .frame(width: 36, height: 40)
            }
            .accessibilityLabel("Stickers")

            PhotosPicker(selection: $pickerItem, matching: .images, photoLibrary: .shared()) {
                Image(systemName: "photo")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(Theme.subtle)
                    .frame(width: 36, height: 40)
            }
            .accessibilityLabel("Add a photo")
            .disabled(isPreparingImage || isPreparingVideo || pendingVideo)

            PhotosPicker(selection: $videoPickerItem, matching: .videos, photoLibrary: .shared()) {
                Image(systemName: "video")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(isPreparingVideo ? Theme.faint : Theme.subtle)
                    .frame(width: 36, height: 40)
            }
            .accessibilityLabel("Add a video")
            .disabled(isPreparingImage || isPreparingVideo || pendingImage || pendingVideo)
        }
    }
}

private enum VideoSendPhase {
    case idle
    case compressing
    case uploading
    case failed

    struct StatusCopy {
        let title: String
        let subtitle: String?
    }

    func statusCopy(detail: String?) -> StatusCopy? {
        switch self {
        case .idle:
            return nil
        case .compressing:
            return StatusCopy(title: "Getting your video ready…", subtitle: "Almost there")
        case .uploading:
            return StatusCopy(title: "Sending…", subtitle: nil)
        case .failed:
            return StatusCopy(
                title: detail ?? "Couldn’t send that video",
                subtitle: "Try again, or pick a different one"
            )
        }
    }
}

private struct VideoSendStatusBanner: View {
    let status: VideoSendPhase.StatusCopy
    let phase: VideoSendPhase
    let isBusy: Bool
    let preview: UIImage?
    let onCancel: () -> Void
    let onRetry: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 12) {
                ZStack {
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .fill(Theme.raised)
                        .frame(width: 52, height: 52)
                    if let preview {
                        Image(uiImage: preview)
                            .resizable()
                            .interpolation(.high)
                            .scaledToFill()
                            .frame(width: 52, height: 52)
                            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                    } else {
                        Image(systemName: "video.fill")
                            .foregroundStyle(Theme.subtle)
                    }
                }
                VStack(alignment: .leading, spacing: 3) {
                    Text(status.title)
                        .font(.control)
                        .foregroundStyle(phase == .failed ? Theme.danger : Theme.text)
                    if let subtitle = status.subtitle {
                        Text(subtitle)
                            .font(.meta)
                            .foregroundStyle(Theme.subtle)
                    }
                }
                Spacer(minLength: 0)
                if isBusy {
                    Button("Cancel", action: onCancel)
                        .font(.meta)
                        .foregroundStyle(Theme.subtle)
                } else if phase == .failed {
                    Button("Try again", action: onRetry)
                        .font(.meta)
                        .foregroundStyle(Theme.accent)
                    Button("Dismiss", action: onCancel)
                        .font(.meta)
                        .foregroundStyle(Theme.subtle)
                }
            }
            if isBusy {
                BusyBar()
            }
        }
        .padding(12)
        .background(Theme.raised, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .strokeBorder(isBusy ? Theme.accent.opacity(0.45) : Theme.hairline, lineWidth: 1)
        }
        .padding(.horizontal, 12)
        .padding(.top, 8)
    }
}

/// Looping bar for work with no measurable progress. The linear `ProgressView`
/// style has no indeterminate mode on iOS, so it just sits there at zero.
private struct BusyBar: View {
    @State private var slid = false

    var body: some View {
        GeometryReader { geo in
            let track = geo.size.width
            let pill = max(48, track * 0.4)
            Capsule()
                .fill(Theme.accent)
                .frame(width: pill, height: 4)
                .offset(x: slid ? track - pill : 0)
                .animation(.easeInOut(duration: 0.85).repeatForever(autoreverses: true), value: slid)
        }
        .frame(height: 4)
        .background(Theme.accent.opacity(0.18), in: Capsule())
        .onAppear { slid = true }
    }
}

private struct PendingVideoTrim: Identifiable {
    let id = UUID()
    let sourceURL: URL
    let durationMs: Int
}

private struct FullscreenVideoRoute: Identifiable {
    let id = UUID()
    let path: String
    let durationMs: Int
}

private struct VideoBubble: View {
    let path: String
    let durationMs: Int
    let width: Int?
    let height: Int?
    var isSending: Bool = false
    var onOpenFullscreen: () -> Void = {}

    @State private var poster: UIImage?

    private var mediaURL: URL? {
        if path.hasPrefix("http://") || path.hasPrefix("https://") {
            return URL(string: path)
        }
        if path.hasPrefix("file://") {
            return URL(string: path)
        }
        if path.hasPrefix("/") {
            return URL(fileURLWithPath: path)
        }
        return LocalMediaStore.url(forRelativePath: path)
    }

    var body: some View {
        ZStack {
            Color.black.opacity(0.35)

            if let poster {
                Image(uiImage: poster)
                    .resizable()
                    .interpolation(.high)
                    .scaledToFill()
            }

            if isSending {
                Color.black.opacity(0.5)
                Text("Sending…")
                    .font(.meta)
                    .foregroundStyle(.white)
            } else {
                Image(systemName: "play.fill")
                    .font(.system(size: 20, weight: .bold))
                    .foregroundStyle(.white)
                    .frame(width: 54, height: 54)
                    .background(.black.opacity(0.45), in: Circle())
                    .overlay {
                        Circle().strokeBorder(.white.opacity(0.5), lineWidth: 1.5)
                    }
                    .shadow(color: .black.opacity(0.3), radius: 8, y: 2)

                Text(Self.format(durationMs))
                    .font(.meta)
                    .foregroundStyle(.white)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(.black.opacity(0.55), in: Capsule())
                    .padding(8)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
            }
        }
        .frame(width: displayWidth, height: displayHeight)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .contentShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .onTapGesture { if !isSending { onOpenFullscreen() } }
        .task(id: path) { await loadPoster() }
        .accessibilityLabel(isSending ? "Sending video" : "Video, \(Self.format(durationMs))")
        .accessibilityAddTraits(isSending ? [] : .isButton)
    }

    private var displayWidth: CGFloat { 220 }
    private var displayHeight: CGFloat {
        guard let width, let height, width > 0 else { return 160 }
        return min(280, displayWidth * CGFloat(height) / CGFloat(width))
    }

    private func loadPoster() async {
        guard poster == nil, let url = mediaURL else { return }
        let asset = AVURLAsset(url: url)
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        // Bubbles are 220pt wide, so a 3x screen needs ~660px on the short edge
        // after scaledToFill crops. Uploads are capped at 720p, so this keeps
        // the frame at its native size instead of downsampling it.
        generator.maximumSize = CGSize(width: 1280, height: 1280)
        let time = CMTime(seconds: 0.05, preferredTimescale: 600)
        guard let cg = try? await generator.image(at: time).image else { return }
        poster = UIImage(cgImage: cg)
    }

    private static func format(_ ms: Int) -> String {
        let total = max(1, ms / 1_000)
        return String(format: "%d:%02d", total / 60, total % 60)
    }
}

private struct VoiceBubble: View {
    let path: String
    let durationMs: Int
    let isMine: Bool

    @State private var player: AVPlayer?
    @State private var isPlaying = false

    var body: some View {
        HStack(spacing: 10) {
            Button(action: toggle) {
                Image(systemName: isPlaying ? "pause.fill" : "play.fill")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(isMine ? .white : Theme.accent)
                    .frame(width: 32, height: 32)
                    .background(
                        (isMine ? Color.white.opacity(0.2) : Theme.accent.opacity(0.15)),
                        in: Circle()
                    )
            }
            .buttonStyle(.plain)

            Capsule()
                .fill(isMine ? Color.white.opacity(0.35) : Theme.hairline)
                .frame(width: 110, height: 4)

            Text(Self.format(durationMs))
                .font(.meta)
                .foregroundStyle(isMine ? Color.white.opacity(0.85) : Theme.subtle)
                .monospacedDigit()
        }
        .onDisappear { stop() }
    }

    private func toggle() {
        if isPlaying {
            stop()
            return
        }
        let url: URL?
        if path.hasPrefix("http://") || path.hasPrefix("https://") {
            url = URL(string: path)
        } else {
            url = LocalMediaStore.url(forRelativePath: path)
        }
        guard let url else { return }
        let item = AVPlayerItem(url: url)
        let av = AVPlayer(playerItem: item)
        player = av
        isPlaying = true
        NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: item,
            queue: .main
        ) { _ in
            isPlaying = false
        }
        av.play()
    }

    private func stop() {
        player?.pause()
        player = nil
        isPlaying = false
    }

    private static func format(_ ms: Int) -> String {
        let total = max(1, ms / 1000)
        return String(format: "%d:%02d", total / 60, total % 60)
    }
}

// MARK: - Voice recorder

@MainActor
@Observable
final class VoiceRecorder {
    private(set) var isRecording = false
    private(set) var elapsedMs = 0

    private var recorder: AVAudioRecorder?
    private var fileURL: URL?
    private var timer: Timer?
    private var startedAt: Date?

    var elapsedLabel: String {
        let total = elapsedMs / 1000
        return String(format: "%d:%02d", total / 60, total % 60)
    }

    func start() async {
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker])
            try session.setActive(true)
            let granted = await withCheckedContinuation { (cont: CheckedContinuation<Bool, Never>) in
                session.requestRecordPermission { cont.resume(returning: $0) }
            }
            guard granted else { return }

            let url = FileManager.default.temporaryDirectory
                .appendingPathComponent("\(UUID().uuidString).m4a")
            let settings: [String: Any] = [
                AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
                AVSampleRateKey: 22_050,
                AVNumberOfChannelsKey: 1,
                AVEncoderAudioQualityKey: AVAudioQuality.medium.rawValue,
            ]
            let av = try AVAudioRecorder(url: url, settings: settings)
            av.record()
            recorder = av
            fileURL = url
            startedAt = Date()
            isRecording = true
            elapsedMs = 0
            timer = Timer.scheduledTimer(withTimeInterval: 0.2, repeats: true) { [weak self] _ in
                Task { @MainActor in
                    guard let self, let started = self.startedAt else { return }
                    self.elapsedMs = Int(Date().timeIntervalSince(started) * 1000)
                    if self.elapsedMs >= 60_000 {
                        _ = self.stop()
                    }
                }
            }
        } catch {
            cancel()
        }
    }

    func stop() -> PreparedAudio? {
        timer?.invalidate()
        timer = nil
        recorder?.stop()
        defer {
            recorder = nil
            isRecording = false
            fileURL = nil
            startedAt = nil
        }
        guard let fileURL,
              let data = try? Data(contentsOf: fileURL),
              data.count > 0
        else { return nil }
        let duration = max(500, elapsedMs)
        try? FileManager.default.removeItem(at: fileURL)
        return PreparedAudio(data: data, durationMs: duration, contentType: "audio/mp4")
    }

    func cancel() {
        timer?.invalidate()
        timer = nil
        recorder?.stop()
        if let fileURL {
            try? FileManager.default.removeItem(at: fileURL)
        }
        recorder = nil
        self.fileURL = nil
        isRecording = false
        elapsedMs = 0
        startedAt = nil
    }
}
