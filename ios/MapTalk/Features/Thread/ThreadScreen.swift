import AVFoundation
import PhotosUI
import SwiftUI
import UIKit

struct ThreadScreen: View {

    private let author: Author
    private let threadId: String
    @State private var model: ThreadModel
    @State private var draft = ""
    @State private var pickerItem: PhotosPickerItem?
    @State private var pendingImage: UIImage?
    @State private var isPreparingImage = false
    @State private var showStickers = false
    @State private var longPressTarget: Message?
    @State private var reportTarget: ReportSheetTarget?
    @State private var blockConfirm: BlockConfirm?
    @State private var reportThanks = false
    @State private var recorder = VoiceRecorder()
    @FocusState private var isComposing: Bool
    @Environment(\.dismiss) private var dismiss

    init(environment: AppEnvironment, author: Author, threadId: String) {
        self.author = author
        self.threadId = threadId
        _model = State(
            initialValue: ThreadModel(
                repository: environment.threadRepository,
                safety: environment.safetyRepository,
                threadId: threadId
            )
        )
    }

    private var trimmedDraft: String {
        draft.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var canSend: Bool {
        pendingImage != nil || !trimmedDraft.isEmpty
    }

    var body: some View {
        messages
            .background(Theme.base)
            .safeAreaInset(edge: .bottom) { composer }
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(Theme.surface, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .principal) { header }
                ToolbarItem(placement: .topBarTrailing) {
                    if let thread = model.thread, thread.authorId != author.uid {
                        Menu {
                            Button("Report chat", systemImage: "flag") {
                                reportTarget = .thread(thread)
                            }
                            Button("Block \(thread.authorName)", systemImage: "hand.raised", role: .destructive) {
                                blockConfirm = BlockConfirm(
                                    uid: thread.authorId,
                                    name: thread.authorName
                                )
                            }
                        } label: {
                            Image(systemName: "ellipsis")
                                .foregroundStyle(Theme.subtle)
                        }
                        .accessibilityLabel("Chat options")
                    }
                }
            }
            .onAppear { model.start() }
            .onDisappear {
                model.stop()
                recorder.cancel()
            }
            .onChange(of: model.shouldDismiss) { _, dismissNow in
                if dismissNow { dismiss() }
            }
            .onChange(of: pickerItem) { _, item in
                guard let item else { return }
                Task { await loadPickerItem(item) }
            }
            .overlay {
                if let message = longPressTarget {
                    MessageLongPressOverlay(
                        message: message,
                        isMine: message.authorId == author.uid,
                        myUid: author.uid,
                        onReact: { emoji in
                            model.toggleReaction(emoji, on: message, as: author)
                            longPressTarget = nil
                        },
                        onReply: {
                            model.setReply(to: message)
                            longPressTarget = nil
                        },
                        onReport: {
                            reportTarget = .message(message)
                            longPressTarget = nil
                        },
                        onBlock: {
                            blockConfirm = BlockConfirm(
                                uid: message.authorId,
                                name: message.authorName
                            )
                            longPressTarget = nil
                        },
                        onDismiss: { longPressTarget = nil }
                    )
                    .transition(.opacity)
                    .zIndex(20)
                }
            }
            .animation(.easeOut(duration: 0.18), value: longPressTarget?.id)
            .sheet(item: $reportTarget) { target in
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
            .alert("Thanks — we’ll take a look", isPresented: $reportThanks) {
                Button("OK", role: .cancel) {}
            } message: {
                Text("Your report was sent. You can also block the person if you don’t want to see them.")
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
    }

    private var header: some View {
        VStack(spacing: 2) {
            Text(model.thread?.title ?? "Chat")
                .font(.cardTitle)
                .foregroundStyle(Theme.text)
                .lineLimit(1)

            if let thread = model.thread {
                HStack(spacing: 5) {
                    Text(thread.kind.glyph).font(.system(size: 9))
                    Text(thread.kind.label)
                        .foregroundStyle(thread.kind.tint)
                    Text("\u{00b7} \(thread.authorName) \u{00b7} \(relativeTime(thread.createdAt))")
                        .foregroundStyle(Theme.faint)
                }
                .font(.meta)
                .lineLimit(1)
            }
        }
    }

    @ViewBuilder
    private var messages: some View {
        if model.isLoading {
            ProgressView()
                .tint(Theme.subtle)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if model.messages.isEmpty {
            VStack(spacing: 10) {
                Image(systemName: "bubble.left.and.bubble.right")
                    .font(.system(size: 30, weight: .light))
                    .foregroundStyle(Theme.faint)
                Text("Nobody has said anything yet")
                    .font(.cardTitle)
                    .foregroundStyle(Theme.subtle)
                Text("Go first \u{2014} photos, stickers, voice, the lot.")
                    .font(.subheadline)
                    .foregroundStyle(Theme.faint)
                    .multilineTextAlignment(.center)
            }
            .padding(40)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(Array(model.messages.enumerated()), id: \.element.id) { index, message in
                            MessageRow(
                                message: message,
                                isMine: message.authorId == author.uid,
                                myUid: author.uid,
                                startsRun: startsRun(at: index),
                                endsRun: endsRun(at: index),
                                onLongPress: { longPressTarget = message },
                                onToggleReaction: { emoji in
                                    model.toggleReaction(emoji, on: message, as: author)
                                }
                            )
                            .id(message.id)
                        }
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 16)
                }
                .scrollDismissesKeyboard(.interactively)
                .onChange(of: model.messages.count) { _, _ in scroll(proxy, animated: true) }
                .onAppear { scroll(proxy, animated: false) }
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
                    Button {
                        showStickers.toggle()
                    } label: {
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
                    .disabled(isPreparingImage)

                    TextField(
                        "",
                        text: $draft,
                        prompt: Text(pendingImage == nil ? "Say something" : "Add a caption")
                            .foregroundStyle(Theme.faint),
                        axis: .vertical
                    )
                    .font(.body)
                    .foregroundStyle(Theme.text)
                    .focused($isComposing)
                    .lineLimit(1...5)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .background(Theme.raised, in: Capsule())
                    .overlay {
                        Capsule().strokeBorder(isComposing ? Theme.accent : Theme.hairline, lineWidth: 1)
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
                    }
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 10)
            }
        }
        .background(Theme.surface)
    }

    private func replyPreview(_ message: Message) -> String {
        if message.isSticker { return message.text }
        if message.hasVoice { return "Voice note" }
        if message.hasImage { return message.text.isEmpty ? "Photo" : message.text }
        return message.text
    }

    private func send() {
        let caption = draft
        let image = pendingImage.flatMap(ImageCompressor.prepare)
        model.send(caption, as: author, image: image)
        draft = ""
        pendingImage = nil
        pickerItem = nil
        showStickers = false
    }

    private func loadPickerItem(_ item: PhotosPickerItem) async {
        isPreparingImage = true
        defer { isPreparingImage = false }
        guard let data = try? await item.loadTransferable(type: Data.self),
              let image = UIImage(data: data)
        else { return }
        pendingImage = image
    }

    private func scroll(_ proxy: ScrollViewProxy, animated: Bool) {
        guard let last = model.messages.last else { return }
        if animated {
            withAnimation(.spring(duration: 0.35)) { proxy.scrollTo(last.id, anchor: .bottom) }
        } else {
            proxy.scrollTo(last.id, anchor: .bottom)
        }
    }

    private func startsRun(at index: Int) -> Bool {
        guard index > 0 else { return true }
        return !model.messages[index].follows(model.messages[index - 1])
    }

    private func endsRun(at index: Int) -> Bool {
        guard index < model.messages.count - 1 else { return true }
        return !model.messages[index + 1].follows(model.messages[index])
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

/// Facebook-style long-press: dimmed scrim, reaction pill, action list.
private struct MessageLongPressOverlay: View {
    let message: Message
    let isMine: Bool
    let myUid: String
    let onReact: (String) -> Void
    let onReply: () -> Void
    let onReport: () -> Void
    let onBlock: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        ZStack {
            Color.black.opacity(0.55)
                .ignoresSafeArea()
                .onTapGesture(perform: onDismiss)

            VStack(spacing: 14) {
                Spacer(minLength: 40)

                reactionBar
                    .scaleEffect(1)
                    .transition(.scale.combined(with: .opacity))

                previewBubble
                    .frame(maxWidth: 280)
                    .frame(maxWidth: .infinity, alignment: isMine ? .trailing : .leading)
                    .padding(.horizontal, 28)

                actionMenu
                    .frame(maxWidth: 260)
                    .frame(maxWidth: .infinity, alignment: isMine ? .trailing : .leading)
                    .padding(.horizontal, 28)

                Spacer(minLength: 80)
            }
        }
    }

    private var reactionBar: some View {
        HStack(spacing: 10) {
            ForEach(ReactionEmoji.allCases, id: \.rawValue) { reaction in
                Button {
                    onReact(reaction.rawValue)
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
    private var previewBubble: some View {
        if message.isSticker {
            Text(message.text)
                .font(.system(size: 64))
                .padding(8)
        } else {
            VStack(alignment: .leading, spacing: 6) {
                if message.hasImage {
                    Label("Photo", systemImage: "photo")
                        .font(.subheadline)
                        .foregroundStyle(isMine ? Color.white.opacity(0.85) : Theme.subtle)
                }
                if message.hasVoice {
                    Label("Voice note", systemImage: "waveform")
                        .font(.subheadline)
                        .foregroundStyle(isMine ? Color.white.opacity(0.85) : Theme.subtle)
                }
                if !message.text.isEmpty, !message.hasVoice {
                    Text(message.text)
                        .font(.body)
                        .foregroundStyle(isMine ? .white : Theme.text)
                        .lineLimit(4)
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .background(
                isMine ? Theme.accent : Theme.raised,
                in: RoundedRectangle(cornerRadius: Theme.Radius.bubble, style: .continuous)
            )
            .shadow(color: .black.opacity(0.3), radius: 12, y: 6)
        }
    }

    private var actionMenu: some View {
        VStack(spacing: 0) {
            menuRow(title: "Reply", systemImage: "arrowshape.turn.up.left", action: onReply)
            if !isMine {
                Divider().overlay(Theme.hairline)
                menuRow(title: "Report", systemImage: "flag", action: onReport)
                Divider().overlay(Theme.hairline)
                menuRow(
                    title: "Block \(message.authorName)",
                    systemImage: "hand.raised",
                    tint: Theme.danger,
                    action: onBlock
                )
            }
        }
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
}

// MARK: - Rows

private struct MessageRow: View {
    let message: Message
    let isMine: Bool
    let myUid: String
    let startsRun: Bool
    let endsRun: Bool
    let onLongPress: () -> Void
    let onToggleReaction: (String) -> Void

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
                    .onLongPressGesture(minimumDuration: 0.35) {
                        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                        onLongPress()
                    }

                if !message.reactions.isEmpty {
                    ReactionStrip(
                        reactions: message.reactions,
                        myUid: myUid,
                        onToggle: onToggleReaction
                    )
                }

                if endsRun {
                    Text(relativeTime(message.createdAt))
                        .font(.meta)
                        .foregroundStyle(Theme.faint)
                        .padding(.horizontal, 2)
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
            .padding(.horizontal, message.hasImage && message.text.isEmpty && message.reply == nil ? 4 : 12)
            .padding(.vertical, message.hasImage && message.text.isEmpty && message.reply == nil ? 4 : 9)
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
        .onTapGesture { showFullscreen = true }
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

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .scaleEffect(scale)
                    .gesture(
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
                    )
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
                    .padding(12)
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
        }
        .statusBarHidden(true)
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
