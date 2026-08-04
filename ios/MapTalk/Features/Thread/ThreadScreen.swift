import PhotosUI
import SwiftUI
import UIKit

struct ThreadScreen: View {

    private let author: Author
    @State private var model: ThreadModel
    @State private var draft = ""
    @State private var pickerItem: PhotosPickerItem?
    @State private var pendingImage: UIImage?
    @State private var isPreparingImage = false
    @FocusState private var isComposing: Bool

    init(environment: AppEnvironment, author: Author, threadId: String) {
        self.author = author
        _model = State(
            initialValue: ThreadModel(repository: environment.threadRepository, threadId: threadId)
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
            }
            .onAppear { model.start() }
            .onDisappear { model.stop() }
            .onChange(of: pickerItem) { _, item in
                guard let item else { return }
                Task { await loadPickerItem(item) }
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
                Text("Go first \u{2014} everyone looking at this spot will see it.")
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
                                startsRun: startsRun(at: index),
                                endsRun: endsRun(at: index)
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

            HStack(alignment: .bottom, spacing: 10) {
                PhotosPicker(selection: $pickerItem, matching: .images, photoLibrary: .shared()) {
                    Image(systemName: "photo")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(Theme.subtle)
                        .frame(width: 40, height: 40)
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
                .animation(.easeOut(duration: 0.15), value: isComposing)
                .onChange(of: draft) { _, newValue in
                    if newValue.count > ThreadModel.maxMessageLength {
                        draft = String(newValue.prefix(ThreadModel.maxMessageLength))
                    }
                }

                Button(action: send) {
                    Group {
                        if isPreparingImage {
                            ProgressView().tint(.white)
                        } else {
                            Image(systemName: "arrow.up")
                                .font(.system(size: 16, weight: .bold))
                        }
                    }
                    .foregroundStyle(canSend ? .white : Theme.faint)
                    .frame(width: 40, height: 40)
                    .background(canSend ? Theme.accent : Theme.raised, in: Circle())
                }
                .buttonStyle(.pressable)
                .disabled(!canSend || isPreparingImage)
                .animation(.spring(duration: 0.25), value: canSend)
                .accessibilityLabel("Send")
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)

            if draft.count > ThreadModel.maxMessageLength - 60 {
                Text("\(draft.count)/\(ThreadModel.maxMessageLength)")
                    .font(.meta)
                    .foregroundStyle(Theme.faint)
                    .padding(.bottom, 8)
            }
        }
        .background(Theme.surface)
    }

    private func send() {
        let caption = draft
        let image = pendingImage.flatMap(ImageCompressor.prepare)
        model.send(caption, as: author, image: image)
        draft = ""
        pendingImage = nil
        pickerItem = nil
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

private struct MessageRow: View {

    let message: Message
    let isMine: Bool
    let startsRun: Bool
    let endsRun: Bool

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            if isMine {
                Spacer(minLength: 48)
            } else if startsRun {
                InitialAvatar(name: message.authorName, seed: message.authorId, size: 30)
            } else {
                Color.clear.frame(width: 30, height: 1)
            }

            VStack(alignment: isMine ? .trailing : .leading, spacing: 3) {
                if !isMine, startsRun {
                    Text(message.authorName)
                        .font(.meta)
                        .foregroundStyle(InitialAvatar.tint(for: message.authorId))
                        .padding(.leading, 2)
                }

                VStack(alignment: .leading, spacing: 6) {
                    if message.hasImage, let path = message.imagePath {
                        MessageImage(path: path, width: message.imageWidth, height: message.imageHeight)
                    }
                    if !message.text.isEmpty {
                        Text(message.text)
                            .font(.body)
                            .foregroundStyle(isMine ? .white : Theme.text)
                    }
                }
                .padding(.horizontal, message.hasImage && message.text.isEmpty ? 4 : 14)
                .padding(.vertical, message.hasImage && message.text.isEmpty ? 4 : 9)
                .background(
                    isMine ? Theme.accent : Theme.raised,
                    in: Theme.bubble(
                        radius: Theme.Radius.bubble,
                        tail: endsRun ? (isMine ? .bottomTrailing : .bottomLeading) : nil
                    )
                )

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
