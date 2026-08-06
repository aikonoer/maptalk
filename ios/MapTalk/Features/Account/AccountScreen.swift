import PhotosUI
import SwiftUI
import UIKit

/// Standard account settings — profile, sign-in, posting prefs, safety.
/// Spec: `docs/ACCOUNT.md`.
struct AccountScreen: View {
    private let author: Author
    @State private var model: AccountModel
    @State private var showEditName = false
    @State private var draftName = ""
    @State private var photoItem: PhotosPickerItem?
    @State private var showPhotoActions = false
    @State private var showPhotoPicker = false
    @Environment(\.dismiss) private var dismiss

    init(environment: AppEnvironment, author: Author) {
        self.author = author
        _model = State(
            initialValue: AccountModel(
                safety: environment.safetyRepository,
                auth: environment.authRepository,
                author: author
            )
        )
        _draftName = State(initialValue: author.displayName)
    }

    var body: some View {
        List {
            profileHeaderSection
            profileFieldsSection
            signInSection
            postingSection
            safetySection
            accountActionsSection
        }
        .scrollContentBackground(.hidden)
        .background(Theme.base)
        .navigationTitle("Account")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(Theme.surface, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Done") { dismiss() }
                    .foregroundStyle(Theme.accent)
            }
        }
        .sheet(isPresented: $showEditName) { editNameSheet }
        .confirmationDialog("Profile photo", isPresented: $showPhotoActions, titleVisibility: .visible) {
            Button("Choose photo") { showPhotoPicker = true }
            if model.photoURL != nil {
                Button("Remove photo", role: .destructive) {
                    Task { await model.removePhoto() }
                }
            }
            Button("Cancel", role: .cancel) {}
        }
        .photosPicker(isPresented: $showPhotoPicker, selection: $photoItem, matching: .images)
        .onChange(of: photoItem) { _, item in
            guard let item else { return }
            Task { await model.setPhoto(from: item) }
            photoItem = nil
        }
        .onAppear { model.start() }
        .onDisappear { model.stop() }
    }

    private var profileHeaderSection: some View {
        Section {
            VStack(spacing: 14) {
                Button {
                    showPhotoActions = true
                } label: {
                    ZStack(alignment: .bottomTrailing) {
                        InitialAvatar(
                            name: model.displayName,
                            seed: author.uid,
                            size: 88,
                            photoURL: model.photoURL
                        )
                        Image(systemName: "camera.fill")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundStyle(Theme.text)
                            .frame(width: 28, height: 28)
                            .background(Theme.accent, in: Circle())
                            .overlay { Circle().strokeBorder(Theme.surface, lineWidth: 2) }
                    }
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Change profile photo")

                VStack(spacing: 4) {
                    Text(model.displayName)
                        .font(.screenTitle)
                        .foregroundStyle(Theme.text)
                        .multilineTextAlignment(.center)
                    Text(model.providerLabel)
                        .font(.meta)
                        .foregroundStyle(Theme.faint)
                }

                if model.isSavingPhoto {
                    ProgressView()
                        .tint(Theme.subtle)
                }
                if let message = model.statusMessage {
                    Text(message)
                        .font(.subheadline)
                        .foregroundStyle(model.statusIsError ? Theme.danger : Theme.subtle)
                        .multilineTextAlignment(.center)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
            .listRowBackground(Theme.raised)
        }
    }

    private var profileFieldsSection: some View {
        Section {
            Button {
                draftName = model.displayName
                showEditName = true
            } label: {
                settingsRow(title: "Display name", value: model.displayName)
            }
            .listRowBackground(Theme.raised)

            Button {
                showPhotoActions = true
            } label: {
                settingsRow(
                    title: "Profile photo",
                    value: model.photoURL == nil ? "None" : "Set"
                )
            }
            .listRowBackground(Theme.raised)
        } header: {
            Text("Profile")
                .foregroundStyle(Theme.subtle)
        }
    }

    @ViewBuilder
    private var signInSection: some View {
        Section {
            if model.isAnonymous {
                Button {
                    Task { await model.linkWithApple() }
                } label: {
                    HStack(spacing: 10) {
                        Image(systemName: "apple.logo")
                            .font(.system(size: 17, weight: .semibold))
                        Text(model.isLinking ? "Connecting…" : "Continue with Apple")
                        Spacer(minLength: 0)
                    }
                    .font(.body)
                    .foregroundStyle(Theme.text)
                }
                .disabled(model.isLinking)
                .listRowBackground(Theme.raised)
            } else if !model.linkedProviders.isEmpty {
                ForEach(model.linkedProviders, id: \.self) { provider in
                    HStack {
                        Text(provider)
                            .foregroundStyle(Theme.text)
                        Spacer()
                        Text("Linked")
                            .font(.meta)
                            .foregroundStyle(Theme.faint)
                    }
                    .font(.body)
                    .listRowBackground(Theme.raised)
                }
            }
        } header: {
            Text("Sign-in")
                .foregroundStyle(Theme.subtle)
        } footer: {
            Text(
                model.isAnonymous
                    ? "You’re on an anonymous account. Save it with Apple so chats stick across reinstalls. Google sign-in is on Android."
                    : "Your account stays the same when you reinstall on this Apple ID."
            )
            .foregroundStyle(Theme.faint)
        }
    }

    private var postingSection: some View {
        Section {
            HStack {
                Text("Post anonymously by default")
                    .foregroundStyle(Theme.text)
                Spacer()
                Text("Soon")
                    .font(.meta)
                    .foregroundStyle(Theme.faint)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(Theme.base.opacity(0.8), in: Capsule())
            }
            .font(.body)
            .listRowBackground(Theme.raised)
        } header: {
            Text("Posting")
                .foregroundStyle(Theme.subtle)
        } footer: {
            Text("Later you’ll choose for each chat or reply whether to show your name or stay anonymous — while keeping one account.")
                .foregroundStyle(Theme.faint)
        }
    }

    private var safetySection: some View {
        Section {
            NavigationLink {
                BlockedPeopleScreen(model: model)
            } label: {
                Text("Blocked people")
                    .foregroundStyle(Theme.text)
            }
            .listRowBackground(Theme.raised)
        } header: {
            Text("Safety")
                .foregroundStyle(Theme.subtle)
        }
    }

    private var accountActionsSection: some View {
        Section {
            soonRow(title: "Sign out")
            soonRow(title: "Delete account", destructive: true)
        } header: {
            Text("Account")
                .foregroundStyle(Theme.subtle)
        } footer: {
            Text("Sign out and delete are coming next. See docs/ACCOUNT.md for the full field list.")
                .foregroundStyle(Theme.faint)
        }
    }

    private func settingsRow(title: String, value: String) -> some View {
        HStack {
            Text(title)
                .foregroundStyle(Theme.text)
            Spacer()
            Text(value)
                .foregroundStyle(Theme.faint)
                .lineLimit(1)
            Image(systemName: "chevron.right")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(Theme.faint)
        }
        .font(.body)
    }

    private func soonRow(title: String, destructive: Bool = false) -> some View {
        HStack {
            Text(title)
                .foregroundStyle(destructive ? Theme.danger : Theme.text)
            Spacer()
            Text("Soon")
                .font(.meta)
                .foregroundStyle(Theme.faint)
        }
        .font(.body)
        .listRowBackground(Theme.raised)
    }

    private var editNameSheet: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 16) {
                Text("What should we call you?")
                    .font(.cardTitle)
                    .foregroundStyle(Theme.text)

                TextField(
                    "",
                    text: $draftName,
                    prompt: Text("Display name").foregroundStyle(Theme.faint)
                )
                .font(.body)
                .foregroundStyle(Theme.text)
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
                .background(Theme.raised, in: RoundedRectangle(cornerRadius: Theme.Radius.field))
                .overlay {
                    RoundedRectangle(cornerRadius: Theme.Radius.field)
                        .strokeBorder(Theme.hairline, lineWidth: 1)
                }
                .onChange(of: draftName) { _, value in
                    if value.count > AuthRepository.maxDisplayNameLength {
                        draftName = String(value.prefix(AuthRepository.maxDisplayNameLength))
                    }
                }

                Text("\(draftName.trimmingCharacters(in: .whitespacesAndNewlines).count)/\(AuthRepository.maxDisplayNameLength)")
                    .font(.meta)
                    .foregroundStyle(Theme.faint)

                Spacer()
            }
            .padding(24)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .background(Theme.surface)
            .navigationTitle("Display name")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { showEditName = false }
                        .foregroundStyle(Theme.subtle)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        Task {
                            await model.saveDisplayName(draftName)
                            showEditName = false
                        }
                    }
                    .foregroundStyle(Theme.accent)
                    .disabled(
                        draftName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                            || model.isSavingName
                    )
                }
            }
        }
        .presentationDetents([.height(280)])
        .presentationDragIndicator(.visible)
        .presentationBackground(Theme.surface)
    }
}

struct BlockedPeopleScreen: View {
    @Bindable var model: AccountModel

    var body: some View {
        List {
            Section {
                if model.isLoadingBlocked {
                    HStack {
                        Spacer()
                        ProgressView().tint(Theme.subtle)
                        Spacer()
                    }
                    .listRowBackground(Theme.raised)
                } else if model.blocked.isEmpty {
                    Text("Nobody blocked yet. Long-press a message to block someone.")
                        .font(.subheadline)
                        .foregroundStyle(Theme.subtle)
                        .listRowBackground(Theme.raised)
                } else {
                    ForEach(model.blocked) { person in
                        HStack {
                            InitialAvatar(name: person.displayName, seed: person.uid, size: 32)
                            Text(person.displayName)
                                .font(.body)
                                .foregroundStyle(Theme.text)
                            Spacer()
                            Button("Unblock") {
                                model.unblock(person)
                            }
                            .font(.meta)
                            .foregroundStyle(Theme.accent)
                        }
                        .listRowBackground(Theme.raised)
                    }
                }
            } footer: {
                Text("Blocked authors’ chats and messages stay hidden for you only.")
                    .foregroundStyle(Theme.faint)
            }
        }
        .scrollContentBackground(.hidden)
        .background(Theme.base)
        .navigationTitle("Blocked people")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(Theme.surface, for: .navigationBar)
    }
}

@MainActor
@Observable
final class AccountModel {
    private(set) var blocked: [BlockedPerson] = []
    private(set) var isLoadingBlocked = true
    private(set) var displayName: String
    private(set) var photoURL: String?
    private(set) var providerLabel: String
    private(set) var linkedProviders: [String] = []
    private(set) var isAnonymous: Bool
    private(set) var isLinking = false
    private(set) var isSavingName = false
    private(set) var isSavingPhoto = false
    private(set) var statusMessage: String?
    private(set) var statusIsError = false

    private let safety: SafetyRepository
    private let auth: AuthRepository
    private let author: Author
    private let apple = AppleSignInCoordinator()
    private var blockTask: Task<Void, Never>?
    private var profileTask: Task<Void, Never>?

    init(safety: SafetyRepository, auth: AuthRepository, author: Author) {
        self.safety = safety
        self.auth = auth
        self.author = author
        displayName = author.displayName
        providerLabel = auth.providerLabel
        isAnonymous = auth.isAnonymous
        linkedProviders = auth.linkedProviderNames
    }

    func start() {
        refreshIdentity()
        if blockTask == nil {
            blockTask = Task {
                for await people in safety.blockedPeople() {
                    blocked = people
                    isLoadingBlocked = false
                }
            }
        }
        if profileTask == nil, let uid = auth.currentUid {
            profileTask = Task {
                for await profile in auth.profile(uid: uid) {
                    if let name = profile.displayName, !name.isEmpty {
                        displayName = name
                    }
                    photoURL = profile.photoURL
                }
            }
        }
    }

    func stop() {
        blockTask?.cancel()
        blockTask = nil
        profileTask?.cancel()
        profileTask = nil
    }

    func unblock(_ person: BlockedPerson) {
        safety.unblock(uid: person.uid, as: author)
    }

    func saveDisplayName(_ name: String) async {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        isSavingName = true
        statusMessage = nil
        do {
            try await auth.saveDisplayName(trimmed)
            displayName = trimmed
        } catch {
            statusIsError = true
            statusMessage = error.localizedDescription
        }
        isSavingName = false
    }

    func setPhoto(from item: PhotosPickerItem) async {
        isSavingPhoto = true
        statusMessage = nil
        do {
            guard let data = try await item.loadTransferable(type: Data.self),
                  let image = UIImage(data: data)
            else {
                throw AuthRepository.LinkError.failed("Couldn’t read that photo.")
            }
            photoURL = try await auth.saveAvatar(image)
            statusIsError = false
            statusMessage = "Photo updated."
        } catch {
            statusIsError = true
            statusMessage = error.localizedDescription
        }
        isSavingPhoto = false
    }

    func removePhoto() async {
        isSavingPhoto = true
        statusMessage = nil
        do {
            try await auth.removeAvatar()
            photoURL = nil
            statusIsError = false
            statusMessage = "Photo removed."
        } catch {
            statusIsError = true
            statusMessage = error.localizedDescription
        }
        isSavingPhoto = false
    }

    func linkWithApple() async {
        guard !isLinking else { return }
        isLinking = true
        statusMessage = nil
        do {
            let result = try await apple.signIn()
            try await auth.linkWithApple(idToken: result.idToken, rawNonce: result.rawNonce)
            refreshIdentity()
            statusIsError = false
            statusMessage = "Account saved with Apple."
        } catch let error as AuthRepository.LinkError where error == .cancelled {
            statusMessage = nil
        } catch {
            statusIsError = true
            statusMessage = error.localizedDescription
        }
        isLinking = false
    }

    private func refreshIdentity() {
        providerLabel = auth.providerLabel
        isAnonymous = auth.isAnonymous
        linkedProviders = auth.linkedProviderNames
    }
}

extension AuthRepository.LinkError: Equatable {
    static func == (lhs: Self, rhs: Self) -> Bool {
        switch (lhs, rhs) {
        case (.notSignedIn, .notSignedIn),
             (.alreadyLinked, .alreadyLinked),
             (.credentialInUse, .credentialInUse),
             (.cancelled, .cancelled):
            true
        case let (.failed(a), .failed(b)):
            a == b
        default:
            false
        }
    }
}
