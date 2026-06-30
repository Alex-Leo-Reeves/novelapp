import SwiftUI
import WebKit
import ComposeApp

struct ContentView: View {
    @StateObject private var model = NovelAppViewModel()

    var body: some View {
        ZStack {
            AppBackground()

            if model.showSplash {
                SplashIntroView {
                    model.finishSplash()
                }
            } else if model.isCheckingAuth {
                CheckingAccountView()
            } else if model.account == nil && !model.isGuestSession {
                AuthGateView(model: model)
            } else {
                MainTabsView(model: model)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .ignoresSafeArea(.keyboard)
        .preferredColorScheme(.dark)
    }
}

private struct MainTabsView: View {
    @ObservedObject var model: NovelAppViewModel

    var body: some View {
        TabView {
            DiscoverView(model: model)
                .tabItem { Label("Discover", systemImage: "square.grid.2x2") }

            LibraryView(model: model)
                .tabItem { Label("Read", systemImage: "book") }

            DownloadsView()
                .tabItem { Label("Downloads", systemImage: "arrow.down.circle") }

            YouView(model: model)
                .tabItem { Label("You", systemImage: "person.crop.circle") }
        }
        .tint(Color.accentMint)
    }
}

private struct SplashIntroView: View {
    let onFinished: () -> Void
    @State private var markScale = 0.72
    @State private var markOpacity = 0.0
    @State private var titleOffset: CGFloat = 18
    @State private var glow = false

    var body: some View {
        ZStack {
            AppBackground()
            VStack(spacing: 18) {
                ZStack {
                    RoundedRectangle(cornerRadius: 24)
                        .fill(Color.accentMint)
                        .frame(width: 104, height: 104)
                        .shadow(color: Color.accentMint.opacity(glow ? 0.65 : 0.2), radius: glow ? 34 : 12)
                    Text("N")
                        .font(.system(size: 54, weight: .black))
                        .foregroundColor(.black)
                }
                .scaleEffect(markScale)
                .opacity(markOpacity)

                VStack(spacing: 7) {
                    Text("NovelApp")
                        .font(.system(size: 34, weight: .black))
                        .foregroundColor(.white)
                    Text("Developed by Mike")
                        .font(.callout.weight(.semibold))
                        .foregroundColor(Color.accentMint)
                    Text("masteralexleoreevesd1@gmail.com")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                .offset(y: titleOffset)
                .opacity(markOpacity)
            }
            .padding(24)
        }
        .onAppear {
            withAnimation(.spring(response: 0.72, dampingFraction: 0.72)) {
                markScale = 1
                markOpacity = 1
                titleOffset = 0
            }
            withAnimation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true)) {
                glow = true
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.65) {
                onFinished()
            }
        }
    }
}

private struct CheckingAccountView: View {
    var body: some View {
        ZStack {
            AppBackground()
            VStack(spacing: 14) {
                ProgressView()
                    .tint(Color.accentMint)
                Text("Checking your saved account...")
                    .font(.headline)
                    .foregroundColor(.white)
                Text("Your NovelApp account is tied to the Render backend.")
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
            }
            .padding(24)
        }
    }
}

private struct AuthGateView: View {
    @ObservedObject var model: NovelAppViewModel
    @State private var mode: AuthMode = .create
    @State private var username = ""
    @State private var email = ""
    @State private var password = ""
    @State private var localError: String?

    var body: some View {
        ZStack {
            AppBackground()
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Welcome to NovelApp")
                            .font(.system(size: 34, weight: .black))
                            .foregroundColor(.white)
                            .fixedSize(horizontal: false, vertical: true)
                        Text("Sign in or create an account to sync your account and future history through Render.")
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                    }

                    Picker("Mode", selection: $mode) {
                        Text("Create").tag(AuthMode.create)
                        Text("Sign in").tag(AuthMode.signIn)
                    }
                    .pickerStyle(.segmented)

                    VStack(spacing: 12) {
                        if mode == .create {
                            AuthField(title: "Username", text: $username, systemImage: "person")
                        }
                        AuthField(title: "Email", text: $email, systemImage: "envelope", keyboardType: .emailAddress)
                        AuthSecureField(title: "Password", text: $password)
                    }

                    if let message = localError ?? model.authError, !message.isEmpty {
                        Text(message)
                            .font(.subheadline.weight(.semibold))
                            .foregroundColor(Color(red: 1, green: 0.46, blue: 0.55))
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }

                    Button {
                        submit()
                    } label: {
                        HStack {
                            if model.isAuthSubmitting {
                                ProgressView()
                                    .tint(.black)
                            }
                            Text(mode == .create ? "Create account" : "Sign in")
                        }
                        .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(model.isAuthSubmitting)

                    Button {
                        model.browseAsGuest()
                    } label: {
                        Text("Browse as Guest")
                            .font(.headline)
                            .frame(maxWidth: .infinity, minHeight: 48)
                    }
                    .buttonStyle(GuestButtonStyle())

                    Text("Developed by Mike · \(model.developer.email)")
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .frame(maxWidth: .infinity, alignment: .center)
                        .padding(.top, 8)
                }
                .padding(20)
                .frame(maxWidth: 560)
                .frame(maxWidth: .infinity)
            }
        }
    }

    private func submit() {
        localError = nil
        let trimmedEmail = email.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedUsername = username.trimmingCharacters(in: .whitespacesAndNewlines)

        if mode == .create && trimmedUsername.isEmpty {
            localError = "Enter a username."
            return
        }
        if !trimmedEmail.contains("@") || !trimmedEmail.contains(".") {
            localError = "Enter a valid email address."
            return
        }
        if password.count < 6 {
            localError = "Password must be at least 6 characters."
            return
        }

        if mode == .create {
            model.createAccount(username: trimmedUsername, email: trimmedEmail, password: password)
        } else {
            model.signIn(email: trimmedEmail, password: password)
        }
    }
}

private enum AuthMode {
    case create
    case signIn
}

private struct AuthField: View {
    let title: String
    @Binding var text: String
    let systemImage: String
    var keyboardType: UIKeyboardType = .default

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: systemImage)
                .foregroundColor(.secondary)
                .frame(width: 22)
            TextField(title, text: $text)
                .keyboardType(keyboardType)
                .textInputAutocapitalization(keyboardType == .emailAddress ? .never : .words)
                .disableAutocorrection(true)
        }
        .padding(.horizontal, 12)
        .frame(height: 50)
        .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))
    }
}

private struct AuthSecureField: View {
    let title: String
    @Binding var text: String

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "lock")
                .foregroundColor(.secondary)
                .frame(width: 22)
            SecureField(title, text: $text)
                .textInputAutocapitalization(.never)
                .disableAutocorrection(true)
        }
        .padding(.horizontal, 12)
        .frame(height: 50)
        .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))
    }
}

@MainActor
private final class NovelAppViewModel: ObservableObject {
    @Published var selectedTab: DiscoverTab = .novels
    @Published var query: String = ""
    @Published var items: [ContentItem] = []
    @Published var isLoading = false
    @Published var errorMessage: String?
    @Published var page = 1
    @Published var developer = DeveloperProfile.fallback
    @Published var version = AppVersion.fallback
    @Published var readText: String = ""
    @Published var isNarrating = false
    @Published var narrationMessage = "Kokoro narration is ready when you are."
    @Published var showSplash = true
    @Published var isCheckingAuth = true
    @Published var isAuthSubmitting = false
    @Published var isGuestSession = false
    @Published var authError: String?
    @Published var account: AccountPayload?

    private let bridge = NovelAppIosBridge()

    init() {
        loadDeveloper()
        loadVersion()
        loadHome(resetPage: true)
        verifySavedAccount()
    }

    deinit {
        bridge.close()
    }

    func select(_ tab: DiscoverTab) {
        selectedTab = tab
        query = ""
        loadHome(resetPage: true)
    }

    func loadHome(resetPage: Bool = false) {
        if resetPage { page = 1 }
        isLoading = true
        errorMessage = nil

        bridge.loadHomeJson(tab: selectedTab.bridgeKey, page: Int32(page)) { [weak self] payloadJson, error in
            DispatchQueue.main.async {
                self?.apply(payloadJson: payloadJson, error: error)
            }
        }
    }

    func search() {
        page = 1
        isLoading = true
        errorMessage = nil

        bridge.searchJson(tab: selectedTab.bridgeKey, query: query, page: Int32(page)) { [weak self] payloadJson, error in
            DispatchQueue.main.async {
                self?.apply(payloadJson: payloadJson, error: error)
            }
        }
    }

    func refreshDifferentSet() {
        page += 1
        if query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            loadHome()
        } else {
            search()
        }
    }

    func finishSplash() {
        withAnimation(.easeInOut(duration: 0.28)) {
            showSplash = false
        }
    }

    func browseAsGuest() {
        authError = nil
        isGuestSession = true
    }

    func signOut() {
        bridge.logout()
        account = nil
        isGuestSession = false
        authError = nil
    }

    func verifySavedAccount() {
        isCheckingAuth = true
        authError = nil
        bridge.verifySavedAccount { [weak self] payloadJson, error in
            DispatchQueue.main.async {
                self?.applyAuth(payloadJson: payloadJson, error: error, preserveGuest: false)
                self?.isCheckingAuth = false
            }
        }
    }

    func signIn(email: String, password: String) {
        isAuthSubmitting = true
        authError = nil
        bridge.loginJson(email: email, password: password) { [weak self] payloadJson, error in
            DispatchQueue.main.async {
                self?.applyAuth(payloadJson: payloadJson, error: error, preserveGuest: false)
                self?.isAuthSubmitting = false
            }
        }
    }

    func createAccount(username: String, email: String, password: String) {
        isAuthSubmitting = true
        authError = nil
        bridge.registerJson(username: username, email: email, password: password) { [weak self] payloadJson, error in
            DispatchQueue.main.async {
                self?.applyAuth(payloadJson: payloadJson, error: error, preserveGuest: false)
                self?.isAuthSubmitting = false
            }
        }
    }

    func fetchChapters(for item: ContentItem, completion: @escaping ([ChapterItem], String?) -> Void) {
        bridge.chaptersJson(kind: item.kind, detailUrl: item.detailUrl, sourceName: item.sourceName) { payloadJson, error in
            DispatchQueue.main.async {
                guard let data = payloadJson.data(using: .utf8),
                      let payload = try? JSONDecoder().decode(ChapterPayload.self, from: data)
                else {
                    completion([], error ?? "Unable to read chapter list.")
                    return
                }
                completion(payload.chapters, error)
            }
        }
    }

    func fetchChapterText(_ chapter: ChapterItem, sourceName: String, completion: @escaping (String, String?) -> Void) {
        bridge.chapterTextJson(chapterUrl: chapter.url, sourceName: sourceName) { payloadJson, error in
            DispatchQueue.main.async {
                guard let data = payloadJson.data(using: .utf8),
                      let payload = try? JSONDecoder().decode(TextPayload.self, from: data)
                else {
                    completion("", error ?? "Unable to load chapter text.")
                    return
                }
                completion(payload.text, error)
            }
        }
    }

    func fetchMangaPages(_ chapter: ChapterItem, sourceName: String, completion: @escaping ([String], String?) -> Void) {
        bridge.mangaPagesJson(chapterUrl: chapter.url, sourceName: sourceName) { payloadJson, error in
            DispatchQueue.main.async {
                guard let data = payloadJson.data(using: .utf8),
                      let payload = try? JSONDecoder().decode(MangaPagesPayload.self, from: data)
                else {
                    completion([], error ?? "Unable to load manga pages.")
                    return
                }
                completion(payload.pages, error)
            }
        }
    }

    func watchUrl(for item: ContentItem) -> URL? {
        guard let data = bridge.watchUrlJson(kind: item.kind, title: item.title, detailUrl: item.detailUrl).data(using: .utf8),
              let payload = try? JSONDecoder().decode(WatchPayload.self, from: data)
        else { return nil }
        return URL(string: payload.url)
    }

    func toggleNarration() {
        if isNarrating {
            stopNarration()
            return
        }
        playNarration(text: readText, cacheKey: "ios-manual-reader-\(readText.stableCacheSuffix)")
    }

    func playNarration(text: String, cacheKey: String) {
        let clean = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else {
            narrationMessage = "Open or paste readable text first."
            return
        }
        readText = clean
        isNarrating = true
        narrationMessage = "Preparing voice..."
        bridge.playNarration(text: clean, cacheKey: cacheKey)
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.75) { [weak self] in
            self?.refreshNarrationStatus()
        }
    }

    func stopNarration() {
        bridge.stopNarration()
        isNarrating = false
        narrationMessage = "Narration stopped."
    }

    func refreshNarrationStatus() {
        guard let data = bridge.narrationStatusJson().data(using: .utf8),
              let status = try? JSONDecoder().decode(NarrationStatus.self, from: data)
        else { return }
        isNarrating = status.isPlaying || status.isBuffering
        narrationMessage = status.message.isEmpty ? (isNarrating ? "Reading..." : "Narration ready.") : status.message
    }

    private func apply(payloadJson: String, error: String?) {
        isLoading = false
        errorMessage = error
        guard let data = payloadJson.data(using: .utf8),
              let payload = try? JSONDecoder().decode(ContentPayload.self, from: data)
        else {
            items = []
            errorMessage = error ?? "Unable to read Kotlin response."
            return
        }
        items = payload.items
    }

    private func applyAuth(payloadJson: String, error: String?, preserveGuest: Bool) {
        guard let data = payloadJson.data(using: .utf8),
              let payload = try? JSONDecoder().decode(AuthPayload.self, from: data)
        else {
            authError = error ?? "Unable to read auth response."
            account = nil
            return
        }

        account = payload.account
        if payload.account != nil {
            isGuestSession = false
            authError = nil
        } else if !preserveGuest {
            authError = error
        }
    }

    private func loadDeveloper() {
        guard let data = bridge.developerJson().data(using: .utf8),
              let profile = try? JSONDecoder().decode(DeveloperProfile.self, from: data)
        else { return }
        developer = profile
    }

    private func loadVersion() {
        guard let data = bridge.versionJson().data(using: .utf8),
              let release = try? JSONDecoder().decode(AppVersion.self, from: data)
        else { return }
        version = release
    }
}

private struct DiscoverView: View {
    @ObservedObject var model: NovelAppViewModel

    private let columns = [
        GridItem(.adaptive(minimum: 132, maximum: 190), spacing: 12)
    ]

    var body: some View {
        NavigationView {
            ZStack {
                AppBackground()

                ScrollView {
                    LazyVStack(spacing: 18, pinnedViews: [.sectionHeaders]) {
                        Section {
                            if model.isLoading && model.items.isEmpty {
                                LoadingRows()
                            } else if model.items.isEmpty {
                                EmptyState(error: model.errorMessage)
                            } else {
                                LazyVGrid(columns: columns, spacing: 14) {
                                    ForEach(model.items) { item in
                                        NavigationLink {
                                            DetailView(model: model, item: item)
                                        } label: {
                                            ContentCard(item: item)
                                        }
                                        .buttonStyle(.plain)
                                    }
                                }
                                .padding(.horizontal, 14)
                            }
                        } header: {
                            DiscoverHeader(model: model)
                                .background(Color.deepInk.opacity(0.96))
                        }
                    }
                    .padding(.bottom, 28)
                }
                .refreshable {
                    model.refreshDifferentSet()
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .navigationTitle("NovelApp")
            .navigationBarTitleDisplayMode(.inline)
        }
        .navigationViewStyle(.stack)
    }
}

private struct DiscoverHeader: View {
    @ObservedObject var model: NovelAppViewModel

    var body: some View {
        VStack(spacing: 12) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("NovelApp")
                        .font(.title2.weight(.bold))
                    Text(model.selectedTab.title)
                        .font(.caption)
                        .foregroundColor(.secondary)
                }

                Spacer()

                if model.isLoading {
                    ProgressView()
                        .tint(Color.accentMint)
                }
            }

            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass")
                    .foregroundColor(.secondary)
                TextField("Search", text: $model.query)
                    .textInputAutocapitalization(.words)
                    .disableAutocorrection(true)
                    .submitLabel(.search)
                    .onSubmit { model.search() }
                if !model.query.isEmpty {
                    Button {
                        model.query = ""
                        model.loadHome(resetPage: true)
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                    }
                    .buttonStyle(.plain)
                    .foregroundColor(.secondary)
                }
            }
            .padding(.horizontal, 12)
            .frame(height: 44)
            .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(DiscoverTab.allCases) { tab in
                        Button {
                            model.select(tab)
                        } label: {
                            Label(tab.title, systemImage: tab.iconName)
                                .font(.callout.weight(.semibold))
                                .lineLimit(1)
                                .padding(.horizontal, 12)
                                .frame(height: 38)
                                .background(
                                    tab == model.selectedTab ? Color.accentMint : Color.white.opacity(0.07),
                                    in: RoundedRectangle(cornerRadius: 8)
                                )
                                .foregroundColor(tab == model.selectedTab ? .black : .white)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
        .padding(.horizontal, 14)
        .padding(.top, 8)
        .padding(.bottom, 12)
    }
}

private struct ContentCard: View {
    let item: ContentItem

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            PosterImage(urlString: item.coverUrl)
                .aspectRatio(0.72, contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: 8))

            Text(item.title)
                .font(.subheadline.weight(.semibold))
                .foregroundColor(.white)
                .lineLimit(2)
                .multilineTextAlignment(.leading)

            Text("\(item.kind.displayKind) · \(item.sourceName)")
                .font(.caption2.weight(.medium))
                .foregroundColor(.secondary)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(9)
        .background(Color.white.opacity(0.055), in: RoundedRectangle(cornerRadius: 8))
        .overlay(
            RoundedRectangle(cornerRadius: 8)
                .stroke(Color.white.opacity(0.08), lineWidth: 1)
        )
    }
}

private struct PosterImage: View {
    let urlString: String

    var body: some View {
        Group {
            if let url = URL(string: urlString), !urlString.isEmpty {
                AsyncImage(url: url) { phase in
                    switch phase {
                    case .success(let image):
                        image
                            .resizable()
                            .scaledToFill()
                    case .failure:
                        PosterPlaceholder()
                    default:
                        ZStack {
                            PosterPlaceholder()
                            ProgressView()
                                .tint(Color.accentMint)
                        }
                    }
                }
            } else {
                PosterPlaceholder()
            }
        }
        .clipped()
    }
}

private struct PosterPlaceholder: View {
    var body: some View {
        ZStack {
            LinearGradient(
                colors: [Color.deepInk, Color.accentMint.opacity(0.35)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            Image(systemName: "book.closed")
                .font(.title)
                .foregroundColor(.white.opacity(0.78))
        }
    }
}

private struct DetailView: View {
    @ObservedObject var model: NovelAppViewModel
    let item: ContentItem

    @State private var chapters: [ChapterItem] = []
    @State private var isLoadingChapters = false
    @State private var isOpeningChapter = false
    @State private var actionMessage: String?
    @State private var readerDocument: ReaderDocument?
    @State private var mangaDocument: MangaDocument?
    @State private var webDocument: WebDocument?

    var body: some View {
        ZStack {
            AppBackground()
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    header
                    synopsis
                    primaryActions
                    chapterSection
                }
                .padding(16)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .navigationTitle(item.title)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            if item.isReadable && chapters.isEmpty && !isLoadingChapters {
                loadChapters()
            }
        }
        .fullScreenCover(item: $readerDocument) { document in
            TextReaderView(model: model, document: document)
        }
        .fullScreenCover(item: $mangaDocument) { document in
            MangaReaderView(document: document)
        }
        .fullScreenCover(item: $webDocument) { document in
            WebPlayerView(document: document)
        }
    }

    private var header: some View {
        HStack(alignment: .top, spacing: 14) {
            PosterImage(urlString: item.coverUrl)
                .frame(width: 120, height: 170)
                .clipShape(RoundedRectangle(cornerRadius: 8))

            VStack(alignment: .leading, spacing: 8) {
                Text(item.title)
                    .font(.title3.weight(.bold))
                    .fixedSize(horizontal: false, vertical: true)
                Text(item.subtitle.isEmpty ? item.sourceName : item.subtitle)
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                Label(item.kind.displayKind, systemImage: item.kind.iconName)
                    .font(.caption.weight(.semibold))
                    .padding(.horizontal, 10)
                    .frame(height: 30)
                    .background(Color.accentMint.opacity(0.18), in: RoundedRectangle(cornerRadius: 8))
            }
        }
    }

    @ViewBuilder
    private var synopsis: some View {
        if !item.synopsis.isEmpty {
            Text(item.synopsis)
                .font(.body)
                .foregroundColor(.white.opacity(0.86))
                .lineSpacing(4)
        }
    }

    private var primaryActions: some View {
        VStack(spacing: 10) {
            HStack(spacing: 10) {
                Button {
                    if item.isReadable {
                        loadChapters()
                    } else if let url = model.watchUrl(for: item) {
                        webDocument = WebDocument(title: item.title, url: url)
                    }
                } label: {
                    Label(item.isReadable ? "Load chapters" : "Watch in app", systemImage: item.isReadable ? "list.bullet.rectangle" : "play.rectangle.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(PrimaryButtonStyle())

                Button {
                    if !item.synopsis.isEmpty {
                        model.playNarration(text: item.synopsis, cacheKey: "ios-synopsis-\(item.id.stableCacheSuffix)")
                    }
                } label: {
                    Image(systemName: "speaker.wave.2.fill")
                        .frame(width: 48, height: 48)
                }
                .buttonStyle(SecondaryIconButtonStyle())
                .disabled(item.synopsis.isEmpty)
            }

            if let actionMessage {
                Text(actionMessage)
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }

    @ViewBuilder
    private var chapterSection: some View {
        if item.isReadable {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Text(item.kind == "manga" ? "Manga chapters" : "Novel chapters")
                        .font(.headline)
                    Spacer()
                    if isLoadingChapters || isOpeningChapter {
                        ProgressView()
                            .tint(Color.accentMint)
                    }
                }

                if chapters.isEmpty && !isLoadingChapters {
                    Text(actionMessage ?? "No chapters loaded yet. Tap Load chapters.")
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(14)
                        .background(Color.white.opacity(0.055), in: RoundedRectangle(cornerRadius: 8))
                } else {
                    LazyVStack(spacing: 8) {
                        ForEach(chapters) { chapter in
                            Button {
                                open(chapter)
                            } label: {
                                ChapterRow(chapter: chapter, isManga: item.kind == "manga")
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
        }
    }

    private func loadChapters() {
        guard item.isReadable else { return }
        isLoadingChapters = true
        actionMessage = item.kind == "manga" ? "Loading manga chapters..." : "Loading novel chapters..."
        model.fetchChapters(for: item) { loaded, error in
            chapters = loaded
            isLoadingChapters = false
            actionMessage = error ?? (loaded.isEmpty ? "No chapters were found from \(item.sourceName)." : "\(loaded.count) chapters loaded.")
        }
    }

    private func open(_ chapter: ChapterItem) {
        isOpeningChapter = true
        actionMessage = item.kind == "manga" ? "Loading manga pages..." : "Loading chapter text..."

        if item.kind == "manga" {
            model.fetchMangaPages(chapter, sourceName: item.sourceName) { pages, error in
                isOpeningChapter = false
                if pages.isEmpty {
                    actionMessage = error ?? "No manga pages were found."
                } else {
                    actionMessage = "\(pages.count) pages loaded."
                    mangaDocument = MangaDocument(title: item.title, chapterTitle: chapter.title, pages: pages)
                }
            }
        } else {
            model.fetchChapterText(chapter, sourceName: item.sourceName) { text, error in
                isOpeningChapter = false
                let clean = text.trimmingCharacters(in: .whitespacesAndNewlines)
                if clean.isEmpty || clean.localizedCaseInsensitiveContains("failed to load chapter") {
                    actionMessage = error ?? "This source did not return readable chapter text."
                } else {
                    actionMessage = "Chapter ready."
                    readerDocument = ReaderDocument(title: item.title, chapterTitle: chapter.title, text: clean)
                }
            }
        }
    }
}

private struct ChapterRow: View {
    let chapter: ChapterItem
    let isManga: Bool

    var body: some View {
        HStack(spacing: 12) {
            Text(chapter.chapterNumber > 0 ? "\(chapter.chapterNumber)" : "#")
                .font(.caption.weight(.bold))
                .foregroundColor(.black)
                .frame(width: 34, height: 34)
                .background(Color.accentMint, in: RoundedRectangle(cornerRadius: 8))

            VStack(alignment: .leading, spacing: 3) {
                Text(chapter.title.isEmpty ? "Chapter \(chapter.chapterNumber)" : chapter.title)
                    .font(.body.weight(.semibold))
                    .lineLimit(2)
                Text(isManga ? "Open fullscreen pages" : "Read chapter and play narration")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }

            Spacer()
            Image(systemName: "chevron.right")
                .foregroundColor(.secondary)
        }
        .padding(12)
        .background(Color.white.opacity(0.055), in: RoundedRectangle(cornerRadius: 8))
    }
}

private struct TextReaderView: View {
    @ObservedObject var model: NovelAppViewModel
    let document: ReaderDocument
    @Environment(\.dismiss) private var dismiss
    @State private var fontSize: Double = 18

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VStack(spacing: 0) {
                readerToolbar

                ScrollView {
                    Text(document.text)
                        .font(.system(size: fontSize))
                        .foregroundColor(.white.opacity(0.92))
                        .lineSpacing(7)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(18)
                }

                VStack(spacing: 10) {
                    Slider(value: $fontSize, in: 15...26, step: 1)
                        .tint(Color.accentMint)
                    Button {
                        if model.isNarrating {
                            model.stopNarration()
                        } else {
                            model.playNarration(
                                text: document.text,
                                cacheKey: "ios-reader-\(document.title.stableCacheSuffix)-\(document.chapterTitle.stableCacheSuffix)"
                            )
                        }
                    } label: {
                        Label(model.isNarrating ? "Stop narration" : "Play chapter", systemImage: model.isNarrating ? "stop.fill" : "play.fill")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    Text(model.narrationMessage)
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(14)
                .background(Color.deepInk.opacity(0.96))
            }
        }
        .onDisappear {
            model.stopNarration()
        }
    }

    private var readerToolbar: some View {
        HStack(spacing: 12) {
            Button {
                dismiss()
            } label: {
                Image(systemName: "xmark")
                    .frame(width: 38, height: 38)
            }
            .buttonStyle(SecondaryIconButtonStyle())

            VStack(alignment: .leading, spacing: 2) {
                Text(document.title)
                    .font(.headline)
                    .lineLimit(1)
                Text(document.chapterTitle)
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .lineLimit(1)
            }
            Spacer()
        }
        .padding(14)
        .background(Color.deepInk.opacity(0.96))
    }
}

private struct MangaReaderView: View {
    let document: MangaDocument
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VStack(spacing: 0) {
                HStack(spacing: 12) {
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "xmark")
                            .frame(width: 38, height: 38)
                    }
                    .buttonStyle(SecondaryIconButtonStyle())

                    VStack(alignment: .leading, spacing: 2) {
                        Text(document.title)
                            .font(.headline)
                            .lineLimit(1)
                        Text("\(document.chapterTitle) · \(document.pages.count) pages")
                            .font(.caption)
                            .foregroundColor(.secondary)
                            .lineLimit(1)
                    }
                    Spacer()
                }
                .padding(14)
                .background(Color.black)

                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(Array(document.pages.enumerated()), id: \.offset) { index, pageUrl in
                            VStack(spacing: 0) {
                                AsyncImage(url: URL(string: pageUrl)) { phase in
                                    switch phase {
                                    case .success(let image):
                                        image
                                            .resizable()
                                            .scaledToFit()
                                    case .failure:
                                        VStack(spacing: 8) {
                                            Image(systemName: "exclamationmark.triangle")
                                            Text("Page \(index + 1) failed to load")
                                                .font(.caption)
                                        }
                                        .foregroundColor(.secondary)
                                        .frame(maxWidth: .infinity, minHeight: 220)
                                    default:
                                        ProgressView()
                                            .tint(Color.accentMint)
                                            .frame(maxWidth: .infinity, minHeight: 220)
                                    }
                                }
                            }
                            .frame(maxWidth: .infinity)
                            .background(Color.black)
                        }
                    }
                }
                .background(Color.black)
            }
        }
    }
}

private struct WebPlayerView: View {
    let document: WebDocument
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VStack(spacing: 0) {
                HStack {
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "xmark")
                            .frame(width: 38, height: 38)
                    }
                    .buttonStyle(SecondaryIconButtonStyle())

                    Text(document.title)
                        .font(.headline)
                        .lineLimit(1)
                    Spacer()
                }
                .padding(14)
                .background(Color.deepInk)

                WebView(url: document.url)
                    .background(Color.black)
            }
        }
    }
}

private struct WebView: UIViewRepresentable {
    let url: URL

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.allowsInlineMediaPlayback = true
        configuration.mediaTypesRequiringUserActionForPlayback = []
        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.isOpaque = false
        webView.backgroundColor = .black
        webView.scrollView.backgroundColor = .black
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        webView.load(URLRequest(url: url))
    }
}

private struct LibraryView: View {
    @ObservedObject var model: NovelAppViewModel

    var body: some View {
        NavigationView {
            ZStack {
                AppBackground()
                ScrollView {
                    VStack(alignment: .leading, spacing: 18) {
                        VStack(alignment: .leading, spacing: 10) {
                            Text("Kokoro Reader")
                                .font(.title3.weight(.bold))
                            Text(model.narrationMessage)
                                .font(.caption)
                                .foregroundColor(.secondary)

                            TextEditor(text: $model.readText)
                                .frame(minHeight: 210)
                                .padding(8)
                                .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))
                                .scrollContentBackgroundCompat()

                            Button {
                                model.toggleNarration()
                            } label: {
                                Label(model.isNarrating ? "Stop Reading" : "Play Text", systemImage: model.isNarrating ? "stop.fill" : "play.fill")
                                    .frame(maxWidth: .infinity)
                            }
                            .buttonStyle(PrimaryButtonStyle())
                            .disabled(model.readText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !model.isNarrating)
                        }
                        .padding(14)
                        .background(Color.white.opacity(0.055), in: RoundedRectangle(cornerRadius: 8))

                        VStack(alignment: .leading, spacing: 10) {
                            Text("Recent from \(model.selectedTab.title)")
                                .font(.headline)
                            ForEach(model.items.prefix(10)) { item in
                                NavigationLink {
                                    DetailView(model: model, item: item)
                                } label: {
                                    HStack(spacing: 12) {
                                        PosterImage(urlString: item.coverUrl)
                                            .frame(width: 44, height: 62)
                                            .clipShape(RoundedRectangle(cornerRadius: 6))
                                        VStack(alignment: .leading, spacing: 4) {
                                            Text(item.title)
                                                .font(.body.weight(.semibold))
                                                .lineLimit(1)
                                            Text(item.sourceName)
                                                .font(.caption)
                                                .foregroundColor(.secondary)
                                        }
                                        Spacer()
                                        Image(systemName: "chevron.right")
                                            .foregroundColor(.secondary)
                                    }
                                }
                                .buttonStyle(.plain)
                                Divider().background(Color.white.opacity(0.08))
                            }
                        }
                    }
                    .padding(16)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .navigationTitle("Read")
        }
        .navigationViewStyle(.stack)
    }
}

private struct DownloadsView: View {
    var body: some View {
        NavigationView {
            ZStack {
                AppBackground()
                VStack(spacing: 14) {
                    Image(systemName: "arrow.down.circle")
                        .font(.system(size: 42))
                        .foregroundColor(Color.accentMint)
                    Text("Downloads")
                        .font(.title3.weight(.bold))
                    Text("Saved chapters and videos will appear here.")
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                        .multilineTextAlignment(.center)
                }
                .padding(24)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            .navigationTitle("Downloads")
        }
        .navigationViewStyle(.stack)
    }
}

private struct YouView: View {
    @ObservedObject var model: NovelAppViewModel

    var body: some View {
        NavigationView {
            ZStack {
                AppBackground()
                List {
                    Section {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("Developed by \(model.developer.name)")
                                .font(.headline)
                            Text("Version \(model.version.versionName)")
                                .font(.subheadline)
                                .foregroundColor(.secondary)
                        }
                        .padding(.vertical, 6)
                    }

                    Section("Account") {
                        if let account = model.account {
                            VStack(alignment: .leading, spacing: 5) {
                                Text(account.username)
                                    .font(.headline)
                                Text(account.email)
                                    .font(.subheadline)
                                    .foregroundColor(.secondary)
                                Text("Plan: \(account.plan.capitalized) · Billing: \(account.billingStatus)")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                            .padding(.vertical, 4)

                            Button(role: .destructive) {
                                model.signOut()
                            } label: {
                                Label("Sign out", systemImage: "rectangle.portrait.and.arrow.right")
                            }
                        } else if model.isGuestSession {
                            VStack(alignment: .leading, spacing: 5) {
                                Text("Browsing as Guest")
                                    .font(.headline)
                                Text("Sign in to tie account data to Render.")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                            Button {
                                model.isGuestSession = false
                            } label: {
                                Label("Sign in or create account", systemImage: "person.crop.circle.badge.plus")
                            }
                        }
                    }

                    Section("Reach me") {
                        if let email = URL(string: "mailto:\(model.developer.email)") {
                            Link(destination: email) {
                                Label(model.developer.email, systemImage: "envelope")
                            }
                        }
                        if let telegram = URL(string: model.developer.telegramUrl) {
                            Link(destination: telegram) {
                                Label("Telegram channel", systemImage: "paperplane")
                            }
                        }
                        if let whatsapp = URL(string: model.developer.whatsappUrl) {
                            Link(destination: whatsapp) {
                                Label("WhatsApp channel", systemImage: "message")
                            }
                        }
                    }

                    Section("Service") {
                        Text(model.version.apiBaseUrl)
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }
                .scrollContentBackgroundCompat()
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .navigationTitle("You")
        }
        .navigationViewStyle(.stack)
    }
}

private struct LoadingRows: View {
    var body: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 132, maximum: 190), spacing: 12)], spacing: 14) {
            ForEach(0..<8, id: \.self) { _ in
                RoundedRectangle(cornerRadius: 8)
                    .fill(Color.white.opacity(0.07))
                    .frame(height: 255)
                    .overlay(ProgressView().tint(Color.accentMint))
            }
        }
        .padding(.horizontal, 14)
    }
}

private struct EmptyState: View {
    let error: String?

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 38))
                .foregroundColor(Color.accentMint)
            Text(error ?? "No results found")
                .font(.headline)
                .multilineTextAlignment(.center)
                .foregroundColor(.white.opacity(0.9))
        }
        .frame(maxWidth: .infinity, minHeight: 260)
        .padding(24)
    }
}

private struct AppBackground: View {
    var body: some View {
        LinearGradient(
            colors: [Color.deepInk, Color.black, Color(red: 0.03, green: 0.08, blue: 0.07)],
            startPoint: .top,
            endPoint: .bottom
        )
        .ignoresSafeArea()
    }
}

private struct PrimaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .foregroundColor(.black)
            .frame(height: 48)
            .background(Color.accentMint.opacity(configuration.isPressed ? 0.75 : 1), in: RoundedRectangle(cornerRadius: 8))
    }
}

private struct SecondaryIconButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .foregroundColor(.white)
            .background(Color.white.opacity(configuration.isPressed ? 0.12 : 0.08), in: RoundedRectangle(cornerRadius: 8))
    }
}

private struct GuestButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundColor(.white)
            .background(Color.white.opacity(configuration.isPressed ? 0.14 : 0.08), in: RoundedRectangle(cornerRadius: 8))
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(Color.white.opacity(0.14), lineWidth: 1)
            )
    }
}

private enum DiscoverTab: String, CaseIterable, Identifiable {
    case novels
    case manga
    case anime
    case kDrama
    case cartoon
    case movies

    var id: String { rawValue }

    var title: String {
        switch self {
        case .novels: return "Novels"
        case .manga: return "Manga"
        case .anime: return "Anime"
        case .kDrama: return "K-Drama"
        case .cartoon: return "Cartoon"
        case .movies: return "Movies"
        }
    }

    var bridgeKey: String {
        switch self {
        case .kDrama: return "kdrama"
        default: return rawValue
        }
    }

    var iconName: String {
        switch self {
        case .novels: return "text.book.closed"
        case .manga: return "rectangle.stack"
        case .anime: return "play.rectangle"
        case .kDrama: return "tv"
        case .cartoon: return "sparkles.tv"
        case .movies: return "film"
        }
    }
}

private struct ContentPayload: Codable {
    let items: [ContentItem]
}

private struct ContentItem: Codable, Identifiable {
    let id: String
    let title: String
    let subtitle: String
    let coverUrl: String
    let detailUrl: String
    let sourceName: String
    let kind: String
    let synopsis: String

    var isReadable: Bool {
        kind == "novel" || kind == "manga"
    }
}

private struct ChapterPayload: Codable {
    let chapters: [ChapterItem]
}

private struct ChapterItem: Codable, Identifiable {
    let title: String
    let url: String
    let chapterNumber: Int

    var id: String { "\(chapterNumber)-\(url)" }
}

private struct TextPayload: Codable {
    let text: String
}

private struct MangaPagesPayload: Codable {
    let pages: [String]
}

private struct WatchPayload: Codable {
    let url: String
}

private struct ReaderDocument: Identifiable {
    let id = UUID()
    let title: String
    let chapterTitle: String
    let text: String
}

private struct MangaDocument: Identifiable {
    let id = UUID()
    let title: String
    let chapterTitle: String
    let pages: [String]
}

private struct WebDocument: Identifiable {
    let id = UUID()
    let title: String
    let url: URL
}

private struct DeveloperProfile: Codable {
    let name: String
    let email: String
    let telegramUrl: String
    let whatsappUrl: String

    static let fallback = DeveloperProfile(
        name: "Mike",
        email: "masteralexleoreevesd1@gmail.com",
        telegramUrl: "https://t.me/developeralexd1",
        whatsappUrl: "https://whatsapp.com/channel/0029Vb8fgDa2P59cCnEkWW3I"
    )
}

private struct AppVersion: Codable {
    let versionCode: Int
    let versionName: String
    let apiBaseUrl: String
    let updateManifestUrl: String
    let downloadUrl: String

    static let fallback = AppVersion(
        versionCode: 9,
        versionName: "1.8",
        apiBaseUrl: "https://novelapp1.onrender.com/api",
        updateManifestUrl: "https://novelapp1.onrender.com/app-version.json",
        downloadUrl: "https://novelapp1.onrender.com/downloads/novelapp-android.apk"
    )
}

private struct NarrationStatus: Codable {
    let isPlaying: Bool
    let isBuffering: Bool
    let message: String
    let progress: Float?
}

private struct AuthPayload: Codable {
    let account: AccountPayload?
}

private struct AccountPayload: Codable, Identifiable {
    let id: String
    let username: String
    let email: String
    let plan: String
    let billingStatus: String
    let createdAt: String
}

private extension View {
    @ViewBuilder
    func scrollContentBackgroundCompat() -> some View {
        if #available(iOS 16.0, *) {
            self.scrollContentBackground(.hidden)
        } else {
            self
        }
    }
}

private extension String {
    var stableCacheSuffix: String {
        let scalars = unicodeScalars.map { UInt64($0.value) }
        let value = scalars.reduce(UInt64(1469598103934665603)) { hash, scalar in
            (hash ^ scalar) &* 1099511628211
        }
        return String(value, radix: 16)
    }

    var displayKind: String {
        switch lowercased() {
        case "kdrama": return "K-Drama"
        case "movie": return "Movie"
        default: return capitalized
        }
    }

    var iconName: String {
        switch lowercased() {
        case "manga": return "rectangle.stack"
        case "anime": return "play.rectangle"
        case "movie": return "film"
        case "cartoon": return "sparkles.tv"
        case "kdrama": return "tv"
        default: return "text.book.closed"
        }
    }
}

private extension Color {
    static let deepInk = Color(red: 0.025, green: 0.031, blue: 0.045)
    static let accentMint = Color(red: 0.49, green: 0.95, blue: 0.73)
}
