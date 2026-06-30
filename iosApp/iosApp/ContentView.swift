import AVFoundation
import AVKit
import CryptoKit
import Security
import SwiftUI
import UIKit
import Vision
import WebKit

private let appTitle = "Anime/Novel/Manga - All in One"
private let apiBaseURL = URL(string: "https://novelapp1.onrender.com/api")!
private let kokoroManifestURL = URL(string: "https://novelapp1.onrender.com/assets/kokoro/manifest.json")!

struct ContentView: View {
    @StateObject private var app = AppModel()
    @StateObject private var narration = NarrationController()

    var body: some View {
        ZStack {
            AppColors.background.ignoresSafeArea()
            if app.showSplash {
                SplashView {
                    app.showSplash = false
                    Task { await app.finishBoot() }
                }
            } else if app.isCheckingAccount {
                BrandedLoadingView(title: "Checking account", subtitle: "Connecting to Render")
            } else if app.account == nil && !app.isGuest {
                AuthView()
                    .environmentObject(app)
            } else {
                RootTabsView()
                    .environmentObject(app)
                    .environmentObject(narration)
            }
        }
        .preferredColorScheme(.dark)
        .task {
            await app.boot()
            await narration.prepareAfterLaunch()
        }
    }
}

// MARK: - App State

@MainActor
final class AppModel: ObservableObject {
    @Published var showSplash = true
    @Published var isCheckingAccount = true
    @Published var isGuest = false
    @Published var isAuthSubmitting = false
    @Published var authError: String?
    @Published var account: UserAccount?
    @Published var selectedKind: ContentKind = .novels
    @Published var query = ""
    @Published var itemsByKind: [ContentKind: [ContentItem]] = [:]
    @Published var loadingKinds: Set<ContentKind> = []
    @Published var contentError: String?
    @Published var favorites: [ContentItem] = []
    @Published var readHistory: [HistoryItem] = []
    @Published var watchHistory: [HistoryItem] = []
    @Published var backgroundNarrationEnabled = false

    private let api = APIClient()
    private let auth = AuthService()
    private var bootStarted = false
    private var pageByKind: [ContentKind: Int] = [:]

    func boot() async {
        guard !bootStarted else { return }
        bootStarted = true
        await verifyAccount()
    }

    func finishBoot() async {
        if !isCheckingAccount {
            await loadHomeIfNeeded(kind: selectedKind)
        }
    }

    func verifyAccount() async {
        isCheckingAccount = true
        defer { isCheckingAccount = false }
        do {
            guard auth.savedToken != nil else { return }
            let user = try await auth.me()
            account = user
            isGuest = false
            await hydrateUserState()
        } catch {
            auth.clearToken()
            account = nil
            authError = readable(error)
        }
    }

    func browseAsGuest() {
        authError = nil
        isGuest = true
    }

    func signIn(email: String, password: String) async {
        isAuthSubmitting = true
        authError = nil
        defer { isAuthSubmitting = false }
        do {
            account = try await auth.login(email: email, password: password)
            isGuest = false
            await hydrateUserState()
            await loadHomeIfNeeded(kind: selectedKind)
        } catch {
            authError = readable(error)
        }
    }

    func createAccount(username: String, email: String, password: String) async {
        isAuthSubmitting = true
        authError = nil
        defer { isAuthSubmitting = false }
        do {
            account = try await auth.register(username: username, email: email, password: password)
            isGuest = false
            await hydrateUserState()
            await loadHomeIfNeeded(kind: selectedKind)
        } catch {
            authError = readable(error)
        }
    }

    func signOut() async {
        try? await auth.logout()
        auth.clearToken()
        account = nil
        isGuest = false
        favorites = []
        readHistory = []
        watchHistory = []
    }

    func select(kind: ContentKind) {
        selectedKind = kind
        contentError = nil
        Task { await loadHomeIfNeeded(kind: kind) }
    }

    func loadHomeIfNeeded(kind: ContentKind) async {
        if itemsByKind[kind]?.isEmpty == false { return }
        await loadHome(kind: kind, reset: true)
    }

    func loadHome(kind: ContentKind, reset: Bool) async {
        if reset { pageByKind[kind] = 1 }
        let page = pageByKind[kind, default: 1]
        loadingKinds.insert(kind)
        contentError = nil
        defer { loadingKinds.remove(kind) }
        do {
            let payload: ContentItemsPayload = try await api.get("/content/home", query: [
                "type": kind.rawValue,
                "page": String(page)
            ])
            itemsByKind[kind] = payload.items
        } catch {
            contentError = readable(error)
        }
    }

    func searchActiveKind() async {
        let clean = query.trimmingCharacters(in: .whitespacesAndNewlines)
        if clean.isEmpty {
            await loadHome(kind: selectedKind, reset: true)
            return
        }
        loadingKinds.insert(selectedKind)
        contentError = nil
        defer { loadingKinds.remove(selectedKind) }
        do {
            let payload: ContentItemsPayload = try await api.get("/content/search", query: [
                "type": selectedKind.rawValue,
                "q": clean,
                "page": "1"
            ])
            itemsByKind[selectedKind] = payload.items
        } catch {
            contentError = readable(error)
        }
    }

    func refreshDifferentSet() async {
        pageByKind[selectedKind, default: 1] += 1
        if query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            await loadHome(kind: selectedKind, reset: false)
        } else {
            await searchActiveKind()
        }
    }

    func chapters(for item: ContentItem) async throws -> [ChapterItem] {
        let payload: ChaptersPayload = try await api.post("/content/chapters", body: [
            "kind": item.kind,
            "detailUrl": item.detailUrl,
            "title": item.title,
            "sourceName": item.sourceName
        ])
        return payload.chapters
    }

    func chapterText(chapter: ChapterItem, item: ContentItem) async throws -> String {
        let payload: ChapterTextPayload = try await api.post("/content/chapter-text", body: [
            "chapterUrl": chapter.url,
            "sourceName": item.sourceName,
            "title": item.title
        ])
        return payload.text
    }

    func mangaPages(chapter: ChapterItem, item: ContentItem) async throws -> [String] {
        let payload: MangaPagesPayload = try await api.post("/content/manga-pages", body: [
            "chapterUrl": chapter.url,
            "sourceName": item.sourceName,
            "title": item.title
        ])
        return payload.pages
    }

    func watchRoute(for item: ContentItem) async throws -> WatchRoute {
        try await api.post("/content/watch-route", body: [
            "kind": item.kind,
            "title": item.title,
            "detailUrl": item.detailUrl,
            "sourceName": item.sourceName
        ])
    }

    func toggleFavorite(_ item: ContentItem) {
        if let index = favorites.firstIndex(where: { $0.id == item.id }) {
            favorites.remove(at: index)
        } else {
            favorites.insert(item, at: 0)
        }
        Task { await syncUserState() }
    }

    func isFavorite(_ item: ContentItem) -> Bool {
        favorites.contains(where: { $0.id == item.id })
    }

    func recordRead(_ item: ContentItem, chapter: ChapterItem) {
        let entry = HistoryItem(id: "\(item.id):\(chapter.url)", title: item.title, subtitle: chapter.title, kind: item.kind, updatedAt: Date())
        readHistory.removeAll { $0.id == entry.id }
        readHistory.insert(entry, at: 0)
        readHistory = Array(readHistory.prefix(100))
        Task { await syncUserState() }
    }

    func recordWatch(_ item: ContentItem) {
        let entry = HistoryItem(id: item.id, title: item.title, subtitle: item.sourceName, kind: item.kind, updatedAt: Date())
        watchHistory.removeAll { $0.id == entry.id }
        watchHistory.insert(entry, at: 0)
        watchHistory = Array(watchHistory.prefix(100))
        Task { await syncUserState() }
    }

    func syncUserStateForView() async {
        await syncUserState()
    }

    private func hydrateUserState() async {
        guard auth.savedToken != nil else { return }
        do {
            let response: UserStateEnvelope = try await api.get("/user/state", token: auth.savedToken)
            favorites = response.state.favorites.compactMap(ContentItem.init(syncFavorite:))
            readHistory = response.state.readHistory.map(HistoryItem.init(syncRead:))
            watchHistory = response.state.watchHistory.map(HistoryItem.init(syncWatch:))
        } catch {
            contentError = readable(error)
        }
    }

    private func syncUserState() async {
        guard auth.savedToken != nil else { return }
        let state = UserSyncState(
            favorites: favorites.map(SyncFavorite.init(item:)),
            readHistory: readHistory.map(SyncReadHistory.init(item:)),
            watchHistory: watchHistory.map(SyncWatchHistory.init(item:)),
            updatedAt: Int64(Date().timeIntervalSince1970 * 1000)
        )
        try? await api.putRaw("/user/state", token: auth.savedToken, body: ["state": state])
    }
}

// MARK: - Screens

private struct SplashView: View {
    let done: () -> Void
    @State private var scale = 0.72
    @State private var opacity = 0.0

    var body: some View {
        ZStack {
            AppColors.background.ignoresSafeArea()
            VStack(spacing: 18) {
                ZStack {
                    RoundedRectangle(cornerRadius: 24)
                        .fill(AppColors.accent)
                        .frame(width: 104, height: 104)
                        .shadow(color: AppColors.accent.opacity(0.45), radius: 28)
                    Text("N")
                        .font(.system(size: 54, weight: .black))
                        .foregroundColor(.black)
                }
                .scaleEffect(scale)
                .opacity(opacity)
                VStack(spacing: 8) {
                    Text(appTitle)
                        .font(.system(size: 28, weight: .black))
                        .multilineTextAlignment(.center)
                    Text("Developed by Mike")
                        .font(.headline)
                        .foregroundColor(AppColors.accent)
                    Text("masteralexleoreevesd1@gmail.com")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                .padding(.horizontal, 24)
                .opacity(opacity)
            }
        }
        .onAppear {
            withAnimation(.spring(response: 0.7, dampingFraction: 0.72)) {
                scale = 1
                opacity = 1
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.45, execute: done)
        }
    }
}

private struct AuthView: View {
    @EnvironmentObject private var app: AppModel
    @State private var mode: AuthMode = .create
    @State private var username = ""
    @State private var email = ""
    @State private var password = ""
    @State private var localError: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Text(appTitle)
                    .font(.system(size: 32, weight: .black))
                    .fixedSize(horizontal: false, vertical: true)
                Text("Create an account or sign in to sync favorites, read history, watch history, and future billing features across Android and iPhone.")
                    .foregroundColor(.secondary)
                Picker("Mode", selection: $mode) {
                    Text("Create").tag(AuthMode.create)
                    Text("Sign in").tag(AuthMode.signIn)
                }
                .pickerStyle(.segmented)
                if mode == .create {
                    FieldRow(title: "Username", text: $username, icon: "person")
                }
                FieldRow(title: "Email", text: $email, icon: "envelope", keyboard: .emailAddress)
                SecureFieldRow(title: "Password", text: $password)
                if let error = localError ?? app.authError {
                    Text(error)
                        .font(.subheadline.weight(.semibold))
                        .foregroundColor(.red)
                }
                Button {
                    submit()
                } label: {
                    HStack {
                        if app.isAuthSubmitting { ProgressView().tint(.black) }
                        Text(mode == .create ? "Create account" : "Sign in")
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(PrimaryButtonStyle())
                Button("Browse as Guest") {
                    app.browseAsGuest()
                    Task { await app.loadHomeIfNeeded(kind: app.selectedKind) }
                }
                .buttonStyle(SecondaryButtonStyle())
                Text("Developed by Mike - masteralexleoreevesd1@gmail.com")
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .frame(maxWidth: .infinity, alignment: .center)
            }
            .padding(20)
        }
        .background(AppColors.background)
    }

    private func submit() {
        localError = nil
        let cleanEmail = email.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanUsername = username.trimmingCharacters(in: .whitespacesAndNewlines)
        guard cleanEmail.contains("@"), cleanEmail.contains(".") else {
            localError = "Enter a valid email."
            return
        }
        guard password.count >= 6 else {
            localError = "Password must be at least 6 characters."
            return
        }
        Task {
            if mode == .create {
                guard cleanUsername.count >= 2 else {
                    localError = "Username must be at least 2 characters."
                    return
                }
                await app.createAccount(username: cleanUsername, email: cleanEmail, password: password)
            } else {
                await app.signIn(email: cleanEmail, password: password)
            }
        }
    }
}

private enum AuthMode {
    case create
    case signIn
}

private struct RootTabsView: View {
    var body: some View {
        TabView {
            DiscoverView()
                .tabItem { Label("Discover", systemImage: "square.grid.2x2") }
            FavoritesView()
                .tabItem { Label("Favorites", systemImage: "heart") }
            DownloadsView()
                .tabItem { Label("Downloads", systemImage: "arrow.down.circle") }
            YouView()
                .tabItem { Label("You", systemImage: "person.crop.circle") }
        }
        .tint(AppColors.accent)
    }
}

private struct DiscoverView: View {
    @EnvironmentObject private var app: AppModel
    private let columns = [GridItem(.adaptive(minimum: 142, maximum: 210), spacing: 14)]

    var body: some View {
        NavigationStack {
            ZStack {
                AppColors.background.ignoresSafeArea()
                ScrollView {
                    VStack(spacing: 14) {
                        DiscoverHeader()
                        if app.loadingKinds.contains(app.selectedKind) && currentItems.isEmpty {
                            LoadingGrid()
                        } else if currentItems.isEmpty {
                            EmptyState(message: app.contentError ?? "No results yet.")
                        } else {
                            LazyVGrid(columns: columns, spacing: 14) {
                                ForEach(currentItems) { item in
                                    NavigationLink(value: item) {
                                        ContentCard(item: item)
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                            .padding(.horizontal, 14)
                        }
                    }
                    .padding(.bottom, 26)
                }
                .refreshable { await app.refreshDifferentSet() }
            }
            .navigationTitle(appTitle)
            .navigationBarTitleDisplayMode(.inline)
            .navigationDestination(for: ContentItem.self) { item in
                DetailView(item: item)
            }
        }
    }

    private var currentItems: [ContentItem] {
        app.itemsByKind[app.selectedKind, default: []]
    }
}

private struct DiscoverHeader: View {
    @EnvironmentObject private var app: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(app.selectedKind.title)
                        .font(.title2.bold())
                    Text("Search and play without leaving the app")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                Spacer()
                if app.loadingKinds.contains(app.selectedKind) {
                    ProgressView().tint(AppColors.accent)
                }
            }
            HStack {
                Image(systemName: "magnifyingglass")
                    .foregroundColor(.secondary)
                TextField("Search \(app.selectedKind.title)", text: $app.query)
                    .submitLabel(.search)
                    .disableAutocorrection(true)
                    .onSubmit { Task { await app.searchActiveKind() } }
                if !app.query.isEmpty {
                    Button {
                        app.query = ""
                        Task { await app.loadHome(kind: app.selectedKind, reset: true) }
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                    }
                    .foregroundColor(.secondary)
                }
            }
            .padding(12)
            .background(AppColors.panel, in: RoundedRectangle(cornerRadius: 10))
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(ContentKind.allCases) { kind in
                        Button {
                            app.select(kind: kind)
                        } label: {
                            Label(kind.title, systemImage: kind.icon)
                                .font(.subheadline.weight(.semibold))
                                .padding(.horizontal, 12)
                                .padding(.vertical, 9)
                                .background(app.selectedKind == kind ? AppColors.accent : AppColors.panel, in: Capsule())
                                .foregroundColor(app.selectedKind == kind ? .black : .white)
                        }
                    }
                }
            }
        }
        .padding(14)
        .background(AppColors.background.opacity(0.96))
    }
}

private struct DetailView: View {
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var narration: NarrationController
    let item: ContentItem
    @State private var chapters: [ChapterItem] = []
    @State private var isLoading = false
    @State private var error: String?
    @State private var selectedNovelChapter: ReaderPayload?
    @State private var selectedMangaChapter: MangaPayload?
    @State private var selectedRoute: WatchRoute?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                HStack(alignment: .top, spacing: 14) {
                    CoverImage(url: item.coverUrl)
                        .frame(width: 116, height: 164)
                    VStack(alignment: .leading, spacing: 8) {
                        Text(item.title)
                            .font(.title2.bold())
                            .fixedSize(horizontal: false, vertical: true)
                        Text(item.subtitle.isEmpty ? item.sourceName : item.subtitle)
                            .foregroundColor(.secondary)
                        Text(item.sourceName)
                            .font(.caption.weight(.semibold))
                            .foregroundColor(AppColors.accent)
                        Button {
                            app.toggleFavorite(item)
                        } label: {
                            Label(app.isFavorite(item) ? "Saved" : "Save", systemImage: app.isFavorite(item) ? "heart.fill" : "heart")
                        }
                        .buttonStyle(CompactButtonStyle())
                    }
                }
                Text(item.synopsis.isEmpty ? "No synopsis was provided by this source." : item.synopsis)
                    .foregroundColor(.secondary)
                if item.isReadable {
                    chapterSection
                } else {
                    Button {
                        Task { await loadRoute() }
                    } label: {
                        Label("Watch now", systemImage: "play.circle.fill")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(PrimaryButtonStyle())
                }
                if let error {
                    Text(error)
                        .foregroundColor(.red)
                        .font(.subheadline.weight(.semibold))
                }
            }
            .padding(16)
        }
        .background(AppColors.background)
        .navigationTitle(item.kind.capitalized)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            if item.isReadable && chapters.isEmpty {
                await loadChapters()
            }
        }
        .fullScreenCover(item: $selectedNovelChapter) { payload in
            NovelReaderView(payload: payload)
                .environmentObject(app)
                .environmentObject(narration)
        }
        .fullScreenCover(item: $selectedMangaChapter) { payload in
            MangaReaderView(payload: payload)
                .environmentObject(app)
                .environmentObject(narration)
        }
        .fullScreenCover(item: $selectedRoute) { route in
            MediaRouteView(route: route)
        }
    }

    private var chapterSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("Chapters")
                    .font(.headline)
                Spacer()
                if isLoading { ProgressView().tint(AppColors.accent) }
            }
            if chapters.isEmpty && !isLoading {
                Text("No chapters loaded yet.")
                    .foregroundColor(.secondary)
            }
            ForEach(chapters) { chapter in
                Button {
                    Task { await open(chapter: chapter) }
                } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(chapter.title)
                                .font(.subheadline.weight(.semibold))
                                .foregroundColor(.white)
                            Text("Chapter \(chapter.chapterNumber)")
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                        Spacer()
                        Image(systemName: item.kind == "manga" ? "photo.on.rectangle" : "book")
                            .foregroundColor(AppColors.accent)
                    }
                    .padding(12)
                    .background(AppColors.panel, in: RoundedRectangle(cornerRadius: 10))
                }
                .buttonStyle(.plain)
            }
        }
    }

    private func loadChapters() async {
        isLoading = true
        error = nil
        defer { isLoading = false }
        do {
            chapters = try await app.chapters(for: item)
        } catch {
            self.error = readable(error)
        }
    }

    private func open(chapter: ChapterItem) async {
        error = nil
        do {
            if item.kind == "manga" {
                let pages = try await app.mangaPages(chapter: chapter, item: item)
                app.recordRead(item, chapter: chapter)
                selectedMangaChapter = MangaPayload(item: item, chapter: chapter, pages: pages)
            } else {
                let text = try await app.chapterText(chapter: chapter, item: item)
                app.recordRead(item, chapter: chapter)
                selectedNovelChapter = ReaderPayload(item: item, chapter: chapter, text: text)
            }
        } catch {
            self.error = readable(error)
        }
    }

    private func loadRoute() async {
        error = nil
        do {
            let route = try await app.watchRoute(for: item)
            app.recordWatch(item)
            selectedRoute = route
        } catch {
            self.error = readable(error)
        }
    }
}

private struct NovelReaderView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var narration: NarrationController
    let payload: ReaderPayload
    @State private var controlsVisible = true
    @State private var fontSize: Double = 20
    @State private var darkMode = true
    @State private var seekValue: Double = 0
    @State private var isSeeking = false

    var body: some View {
        ZStack(alignment: .bottom) {
            (darkMode ? Color.black : Color(.systemBackground)).ignoresSafeArea()
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(alignment: .leading, spacing: 18) {
                        Text(payload.chapter.title)
                            .font(.title2.bold())
                            .foregroundColor(darkMode ? .white : .primary)
                        ForEach(Array(paragraphs.enumerated()), id: \.offset) { index, paragraph in
                            HighlightedParagraphText(
                                text: paragraph.text,
                                baseOffset: paragraph.offset,
                                currentRange: narration.currentRange,
                                darkMode: darkMode
                            )
                            .font(.system(size: fontSize, weight: .regular, design: .serif))
                            .lineSpacing(7)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .id(index)
                        }
                    }
                    .padding(.horizontal, 18)
                    .padding(.top, 28)
                    .padding(.bottom, 170)
                }
                .onChange(of: narration.currentRange.location) { _ in
                    guard narration.isPlaying else { return }
                    withAnimation(.easeInOut(duration: 0.28)) {
                        proxy.scrollTo(activeParagraphIndex, anchor: .center)
                    }
                }
            }
            .onTapGesture {
                withAnimation(.easeInOut(duration: 0.2)) { controlsVisible.toggle() }
            }
            if controlsVisible {
                ReaderControls(
                    title: payload.item.title,
                    subtitle: payload.chapter.title,
                    progress: $seekValue,
                    isPlaying: narration.isPlaying,
                    status: narration.statusMessage,
                    backgroundEnabled: $app.backgroundNarrationEnabled,
                    fontSize: $fontSize,
                    darkMode: $darkMode,
                    close: {
                        if !app.backgroundNarrationEnabled { narration.stop() }
                        dismiss()
                    },
                    playPause: {
                        if narration.isPlaying {
                            narration.pause()
                        } else {
                            narration.play(text: payload.text, title: payload.item.title, background: app.backgroundNarrationEnabled)
                        }
                    },
                    stop: { narration.stop() },
                    seekChanged: { editing in
                        isSeeking = editing
                        if !editing {
                            narration.seek(text: payload.text, progress: seekValue, title: payload.item.title, background: app.backgroundNarrationEnabled)
                        }
                    }
                )
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .onReceive(narration.$progress) { value in
            if !isSeeking { seekValue = value }
        }
        .onDisappear {
            if !app.backgroundNarrationEnabled { narration.stop() }
        }
    }

    private var paragraphs: [(offset: Int, text: String)] {
        var result: [(Int, String)] = []
        var cursor = 0
        let parts = payload.text.components(separatedBy: "\n\n")
        for part in parts {
            let clean = part.trimmingCharacters(in: .whitespacesAndNewlines)
            if !clean.isEmpty {
                result.append((cursor, clean))
            }
            cursor += part.count + 2
        }
        return result.isEmpty ? [(0, payload.text)] : result
    }

    private var activeParagraphIndex: Int {
        let location = narration.currentRange.location
        let values = paragraphs
        return values.lastIndex { location >= $0.offset } ?? 0
    }
}

private struct HighlightedParagraphText: View {
    let text: String
    let baseOffset: Int
    let currentRange: NSRange
    let darkMode: Bool

    var body: some View {
        composed
            .foregroundColor(darkMode ? .white : .primary)
    }

    private var composed: Text {
        let localStart = currentRange.location - baseOffset
        guard localStart >= 0, localStart < text.count else {
            return Text(text)
        }
        let length = min(currentRange.length, text.count - localStart)
        guard length > 0 else { return Text(text) }
        let nsRange = NSRange(location: localStart, length: length)
        guard let range = Range(nsRange, in: text) else { return Text(text) }
        return Text(String(text[..<range.lowerBound]))
            + Text(String(text[range])).foregroundColor(AppColors.accent).bold()
            + Text(String(text[range.upperBound...]))
    }
}

private struct ReaderControls: View {
    let title: String
    let subtitle: String
    @Binding var progress: Double
    let isPlaying: Bool
    let status: String
    @Binding var backgroundEnabled: Bool
    @Binding var fontSize: Double
    @Binding var darkMode: Bool
    let close: () -> Void
    let playPause: () -> Void
    let stop: () -> Void
    let seekChanged: (Bool) -> Void

    var body: some View {
        VStack(spacing: 12) {
            Capsule().fill(Color.white.opacity(0.22)).frame(width: 44, height: 4)
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.headline).lineLimit(1)
                    Text(subtitle).font(.caption).foregroundColor(.secondary).lineLimit(1)
                }
                Spacer()
                Button(action: close) { Image(systemName: "xmark.circle.fill").font(.title2) }
            }
            Slider(value: $progress, in: 0...1, onEditingChanged: seekChanged)
                .tint(AppColors.accent)
            HStack(spacing: 14) {
                Toggle("Background", isOn: $backgroundEnabled)
                    .toggleStyle(.switch)
                Toggle("Dark", isOn: $darkMode)
                    .toggleStyle(.switch)
            }
            HStack {
                Image(systemName: "textformat.size")
                Slider(value: $fontSize, in: 16...30)
                    .tint(AppColors.accent)
            }
            HStack(spacing: 20) {
                Button(action: stop) { Image(systemName: "stop.fill").font(.title2) }
                Button(action: playPause) {
                    Image(systemName: isPlaying ? "pause.fill" : "play.fill")
                        .font(.system(size: 28, weight: .bold))
                        .frame(width: 70, height: 54)
                        .background(AppColors.accent, in: Capsule())
                        .foregroundColor(.black)
                }
                Text(status)
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .lineLimit(2)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(16)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .padding(12)
    }
}

private struct MangaReaderView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var narration: NarrationController
    let payload: MangaPayload
    @State private var controlsVisible = true
    @State private var images: [String: UIImage] = [:]
    @State private var loadingText = "Preparing pages..."
    @State private var currentPage = 0
    @State private var ocrStatus = "OCR ready"
    @State private var skipEmptyPages = false
    @State private var darkMode = true

    var body: some View {
        ZStack(alignment: .bottom) {
            (darkMode ? Color.black : Color(.systemBackground)).ignoresSafeArea()
            ScrollView {
                LazyVStack(spacing: 0) {
                    if images.count < payload.pages.count {
                        VStack(spacing: 10) {
                            ProgressView(value: Double(images.count), total: Double(max(payload.pages.count, 1)))
                                .tint(AppColors.accent)
                            Text(loadingText)
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                        .padding(24)
                    }
                    ForEach(Array(payload.pages.enumerated()), id: \.offset) { index, url in
                        Group {
                            if let image = images[url] {
                                Image(uiImage: image)
                                    .resizable()
                                    .scaledToFit()
                                    .frame(maxWidth: .infinity)
                                    .background(Color.black)
                                    .onAppear { currentPage = index }
                            } else {
                                Rectangle()
                                    .fill(Color.black)
                                    .frame(height: 520)
                                    .overlay(ProgressView().tint(AppColors.accent))
                            }
                        }
                    }
                }
            }
            .onTapGesture {
                withAnimation(.easeInOut(duration: 0.2)) { controlsVisible.toggle() }
            }
            if controlsVisible {
                VStack(spacing: 10) {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(payload.item.title).font(.headline).lineLimit(1)
                            Text("Page \(min(currentPage + 1, payload.pages.count)) of \(payload.pages.count)")
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                        Spacer()
                        Button {
                            narration.stop()
                            dismiss()
                        } label: {
                            Image(systemName: "xmark.circle.fill").font(.title2)
                        }
                    }
                    HStack {
                        Toggle("Dark", isOn: $darkMode).toggleStyle(.switch)
                        Toggle("Skip empty", isOn: $skipEmptyPages).toggleStyle(.switch)
                    }
                    HStack(spacing: 12) {
                        Button {
                            Task { await readCurrentPage() }
                        } label: {
                            Label("OCR read", systemImage: "text.viewfinder")
                        }
                        .buttonStyle(CompactButtonStyle())
                        Button("Stop") { narration.stop() }
                            .buttonStyle(CompactButtonStyle())
                        Text(ocrStatus)
                            .font(.caption)
                            .foregroundColor(.secondary)
                            .lineLimit(2)
                        Spacer()
                    }
                }
                .padding(16)
                .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                .padding(12)
            }
        }
        .task { await preloadPages() }
        .onDisappear {
            if !app.backgroundNarrationEnabled { narration.stop() }
        }
    }

    private func preloadPages() async {
        for (index, urlString) in payload.pages.enumerated() where images[urlString] == nil {
            loadingText = "Loading manga page \(index + 1) of \(payload.pages.count)"
            guard let url = URL(string: urlString) else { continue }
            do {
                let (data, _) = try await URLSession.shared.data(from: url)
                if let image = UIImage(data: data) {
                    images[urlString] = image
                }
            } catch {
                loadingText = "Failed to load page \(index + 1)"
            }
        }
        loadingText = "Pages ready"
    }

    private func readCurrentPage() async {
        guard currentPage < payload.pages.count else { return }
        let url = payload.pages[currentPage]
        guard let image = images[url] else {
            ocrStatus = "This page is still loading."
            return
        }
        ocrStatus = "Scanning page \(currentPage + 1)..."
        do {
            let text = try await VisionOcrReader.recognize(image: image)
            if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                ocrStatus = "No text found on this page."
                return
            }
            ocrStatus = "Reading page \(currentPage + 1)"
            narration.play(text: text, title: payload.item.title, background: app.backgroundNarrationEnabled)
        } catch {
            ocrStatus = readable(error)
        }
    }
}

private struct MediaRouteView: View {
    @Environment(\.dismiss) private var dismiss
    let route: WatchRoute
    @State private var isLoading = true

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Color.black.ignoresSafeArea()
            if route.route == "direct", let url = URL(string: route.url) {
                VideoPlayer(player: AVPlayer(url: url))
                    .ignoresSafeArea()
            } else if route.route == "embed", let url = URL(string: route.url) {
                WebShell(url: url, isLoading: $isLoading)
                    .ignoresSafeArea()
                if isLoading {
                    BrandedLoadingView(title: "Loading \(route.provider)", subtitle: route.title)
                }
            } else {
                EmptyState(message: route.message ?? "Provider unavailable.")
                    .padding()
            }
            Button {
                dismiss()
            } label: {
                Image(systemName: "xmark.circle.fill")
                    .font(.title)
                    .padding(14)
            }
            .foregroundColor(.white)
        }
    }
}

private struct FavoritesView: View {
    @EnvironmentObject private var app: AppModel

    var body: some View {
        NavigationStack {
            List {
                if app.favorites.isEmpty {
                    Text("No favorites yet.")
                        .foregroundColor(.secondary)
                }
                ForEach(app.favorites) { item in
                    NavigationLink(value: item) {
                        Text(item.title)
                    }
                }
                .onDelete { offsets in
                    app.favorites.remove(atOffsets: offsets)
                    Task { await app.syncUserStateForView() }
                }
            }
            .scrollContentBackground(.hidden)
            .background(AppColors.background)
            .navigationTitle("Favorites")
            .navigationDestination(for: ContentItem.self) { item in
                DetailView(item: item)
            }
        }
    }
}

private struct DownloadsView: View {
    var body: some View {
        NavigationStack {
            VStack(spacing: 12) {
                Image(systemName: "arrow.down.circle")
                    .font(.system(size: 42))
                    .foregroundColor(AppColors.accent)
                Text("Downloads")
                    .font(.title2.bold())
                Text("Novel audio and manga chapter caches are prepared inside each reader. Persistent downloaded chapters stay on device; temporary online chapter audio is cleared after app restart.")
                    .multilineTextAlignment(.center)
                    .foregroundColor(.secondary)
                    .padding(.horizontal)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(AppColors.background)
            .navigationTitle("Downloads")
        }
    }
}

private struct YouView: View {
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var narration: NarrationController

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    profileCard
                    settingsCard
                    historyCard(title: "Read history", items: app.readHistory)
                    historyCard(title: "Watch history", items: app.watchHistory)
                    linksCard
                    Text("Developed by Mike")
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .frame(maxWidth: .infinity, alignment: .center)
                }
                .padding(16)
            }
            .background(AppColors.background)
            .navigationTitle("You")
        }
    }

    private var profileCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Account").font(.headline)
            if let account = app.account {
                Text(account.username).font(.title3.bold())
                Text(account.email).foregroundColor(.secondary)
                Text("Plan: \(account.plan) - Billing: \(account.billingStatus)")
                    .font(.caption)
                    .foregroundColor(.secondary)
                Button("Sign out") { Task { await app.signOut() } }
                    .buttonStyle(SecondaryButtonStyle())
            } else {
                Text(app.isGuest ? "Browsing as guest" : "Not signed in")
                    .foregroundColor(.secondary)
            }
        }
        .card()
    }

    private var settingsCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Reader settings").font(.headline)
            Toggle("Background narration", isOn: $app.backgroundNarrationEnabled)
            HStack {
                Text("Voice")
                Spacer()
                Text(narration.statusMessage)
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            Button("Retry Kokoro setup") {
                Task { await narration.prepareAfterLaunch(force: true) }
            }
            .buttonStyle(CompactButtonStyle())
        }
        .card()
    }

    private func historyCard(title: String, items: [HistoryItem]) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title).font(.headline)
            if items.isEmpty {
                Text("Nothing here yet.").foregroundColor(.secondary)
            } else {
                ForEach(items.prefix(8)) { item in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(item.title).font(.subheadline.weight(.semibold))
                        Text(item.subtitle).font(.caption).foregroundColor(.secondary)
                    }
                }
            }
        }
        .card()
    }

    private var linksCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Reach Mike").font(.headline)
            Link("Email", destination: URL(string: "mailto:masteralexleoreevesd1@gmail.com")!)
            Link("Telegram", destination: URL(string: "https://t.me/developeralexd1")!)
            Link("WhatsApp Channel", destination: URL(string: "https://whatsapp.com/channel/0029Vb8fgDa2P59cCnEkWW3I")!)
        }
        .card()
    }
}

// MARK: - Narration

@MainActor
final class NarrationController: NSObject, ObservableObject, AVSpeechSynthesizerDelegate {
    @Published var isPlaying = false
    @Published var progress: Double = 0
    @Published var currentRange = NSRange(location: 0, length: 0)
    @Published var statusMessage = "Voice ready"

    private let synthesizer = AVSpeechSynthesizer()
    private let kokoro = KokoroInstaller()
    private var currentText = ""
    private var startOffset = 0
    private var currentTitle = ""
    private var backgroundMode = false

    override init() {
        super.init()
        synthesizer.delegate = self
    }

    func prepareAfterLaunch(force: Bool = false) async {
        if !force, kokoro.isInstalled { return }
        statusMessage = "Kokoro setup checking"
        do {
            try await kokoro.ensureInstalled(force: force) { [weak self] message in
                Task { @MainActor in self?.statusMessage = message }
            }
            statusMessage = "Kokoro installed; Apple TTS fallback active until native ONNX speech session is enabled"
        } catch {
            statusMessage = "Kokoro unavailable; Apple TTS fallback ready"
        }
    }

    func play(text: String, title: String, background: Bool) {
        let clean = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else {
            statusMessage = "No readable text."
            return
        }
        currentText = clean
        startOffset = 0
        currentTitle = title
        backgroundMode = background
        Task { await prepareAfterLaunch() }
        startSpeech(from: clean)
    }

    func pause() {
        if synthesizer.isSpeaking {
            synthesizer.pauseSpeaking(at: .word)
            isPlaying = false
            statusMessage = "Paused"
        }
    }

    func stop() {
        synthesizer.stopSpeaking(at: .immediate)
        isPlaying = false
        progress = 0
        currentRange = NSRange(location: 0, length: 0)
        statusMessage = "Stopped"
        if !backgroundMode {
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        }
    }

    func seek(text: String, progress: Double, title: String, background: Bool) {
        let clean = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return }
        let target = max(0, min(clean.count - 1, Int(Double(clean.count) * progress)))
        let index = clean.index(clean.startIndex, offsetBy: target)
        let safeIndex = clean[index...].firstIndex(where: { $0.isWhitespace || $0.isNewline }) ?? index
        let suffix = String(clean[safeIndex...]).trimmingCharacters(in: .whitespacesAndNewlines)
        currentText = clean
        startOffset = clean.distance(from: clean.startIndex, to: safeIndex)
        currentTitle = title
        backgroundMode = background
        startSpeech(from: suffix.isEmpty ? clean : suffix)
    }

    private func startSpeech(from speechText: String) {
        synthesizer.stopSpeaking(at: .immediate)
        configureAudioSession()
        let utterance = AVSpeechUtterance(string: speechText)
        utterance.voice = AVSpeechSynthesisVoice(language: "en-US")
        utterance.rate = AVSpeechUtteranceDefaultSpeechRate * 0.9
        utterance.pitchMultiplier = 1.0
        utterance.volume = 1.0
        isPlaying = true
        statusMessage = kokoro.isInstalled ? "Reading with stable English voice" : "Apple TTS reading while Kokoro installs"
        synthesizer.speak(utterance)
    }

    private func configureAudioSession() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .spokenAudio, options: [.duckOthers])
        try? session.setActive(true)
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, willSpeakRangeOfSpeechString characterRange: NSRange, utterance: AVSpeechUtterance) {
        Task { @MainActor in
            let location = self.startOffset + characterRange.location
            self.currentRange = NSRange(location: location, length: characterRange.length)
            if !self.currentText.isEmpty {
                self.progress = min(1, max(0, Double(location) / Double(self.currentText.count)))
            }
        }
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        Task { @MainActor in
            self.isPlaying = false
            self.progress = 1
            self.statusMessage = "Finished"
        }
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        Task { @MainActor in self.isPlaying = false }
    }
}

final class KokoroInstaller {
    private let fileManager = FileManager.default

    var isInstalled: Bool {
        fileManager.fileExists(atPath: modelFile.path)
    }

    func ensureInstalled(force: Bool, progress: @escaping (String) -> Void) async throws {
        if isInstalled && !force {
            progress("Kokoro installed")
            return
        }
        progress("Downloading Kokoro manifest")
        let (manifestData, _) = try await URLSession.shared.data(from: kokoroManifestURL)
        let manifest = try JSONDecoder().decode(KokoroManifest.self, from: manifestData)
        try fileManager.createDirectory(at: kokoroDirectory, withIntermediateDirectories: true, attributes: nil)
        progress("Downloading Kokoro model")
        let (tempURL, _) = try await URLSession.shared.download(from: URL(string: manifest.model.url)!)
        let data = try Data(contentsOf: tempURL)
        let hash = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        guard data.count == manifest.model.sizeBytes, hash.lowercased() == manifest.model.sha256.lowercased() else {
            throw AppError.message("Kokoro checksum failed.")
        }
        if fileManager.fileExists(atPath: modelFile.path) {
            try fileManager.removeItem(at: modelFile)
        }
        try data.write(to: modelFile, options: .atomic)
        progress("Kokoro installed")
    }

    private var kokoroDirectory: URL {
        fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("kokoro", isDirectory: true)
    }

    private var modelFile: URL {
        kokoroDirectory.appendingPathComponent("model_quantized.onnx")
    }
}

// MARK: - API and Auth

final class APIClient {
    private let decoder = JSONDecoder()
    private let encoder = JSONEncoder()

    func get<T: Decodable>(_ path: String, query: [String: String] = [:], token: String? = nil) async throws -> T {
        var components = URLComponents(url: apiBaseURL.appendingPathComponent(path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))), resolvingAgainstBaseURL: false)!
        components.queryItems = query.map { URLQueryItem(name: $0.key, value: $0.value) }
        var request = URLRequest(url: components.url!)
        request.timeoutInterval = 25
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let token { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        return try await perform(request)
    }

    func post<T: Decodable>(_ path: String, body: [String: String]) async throws -> T {
        var request = URLRequest(url: apiBaseURL.appendingPathComponent(path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))))
        request.httpMethod = "POST"
        request.timeoutInterval = 25
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        return try await perform(request)
    }

    func putRaw<T: Encodable>(_ path: String, token: String?, body: T) async throws {
        var request = URLRequest(url: apiBaseURL.appendingPathComponent(path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))))
        request.httpMethod = "PUT"
        request.timeoutInterval = 25
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let token { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        request.httpBody = try encoder.encode(AnyEncodable(body))
        let _: EmptyResponse = try await perform(request, allowRaw: true)
    }

    private func perform<T: Decodable>(_ request: URLRequest, allowRaw: Bool = false) async throws -> T {
        let (data, response) = try await URLSession.shared.data(for: request)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(code) else {
            if let apiError = try? decoder.decode(AuthErrorResponse.self, from: data) {
                throw AppError.message(apiError.error)
            }
            throw AppError.message("Server returned HTTP \(code).")
        }
        if let envelope = try? decoder.decode(APIEnvelope<T>.self, from: data), let value = envelope.data {
            return value
        }
        if allowRaw, T.self == EmptyResponse.self {
            return EmptyResponse() as! T
        }
        return try decoder.decode(T.self, from: data)
    }
}

final class AuthService {
    private let api = APIClient()
    private let decoder = JSONDecoder()

    var savedToken: String? { KeychainStore.read(service: "novelapp.auth", account: "token") }

    func register(username: String, email: String, password: String) async throws -> UserAccount {
        let response: AuthResponse = try await rawAuth(path: "/auth/register", body: ["username": username, "email": email, "password": password])
        if let token = response.token { KeychainStore.save(token, service: "novelapp.auth", account: "token") }
        return response.user
    }

    func login(email: String, password: String) async throws -> UserAccount {
        let response: AuthResponse = try await rawAuth(path: "/auth/login", body: ["email": email, "password": password])
        if let token = response.token { KeychainStore.save(token, service: "novelapp.auth", account: "token") }
        return response.user
    }

    func me() async throws -> UserAccount {
        guard let token = savedToken else { throw AppError.message("No saved session.") }
        let response: AuthResponse = try await rawAuth(path: "/auth/me", token: token)
        return response.user
    }

    func logout() async throws {
        guard let token = savedToken else { return }
        var request = URLRequest(url: apiBaseURL.appendingPathComponent("auth/logout"))
        request.httpMethod = "POST"
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        _ = try? await URLSession.shared.data(for: request)
    }

    func clearToken() {
        KeychainStore.delete(service: "novelapp.auth", account: "token")
    }

    private func rawAuth(path: String, token: String? = nil, body: [String: String]? = nil) async throws -> AuthResponse {
        var request = URLRequest(url: apiBaseURL.appendingPathComponent(path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))))
        request.timeoutInterval = 25
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let token { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        if let body {
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
        }
        let (data, response) = try await URLSession.shared.data(for: request)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(code) else {
            if let apiError = try? decoder.decode(AuthErrorResponse.self, from: data) {
                throw AppError.message(apiError.error)
            }
            throw AppError.message("Authentication failed.")
        }
        return try decoder.decode(AuthResponse.self, from: data)
    }
}

enum KeychainStore {
    static func save(_ value: String, service: String, account: String) {
        delete(service: service, account: account)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecValueData as String: Data(value.utf8),
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock
        ]
        SecItemAdd(query as CFDictionary, nil)
    }

    static func read(service: String, account: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data
        else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func delete(service: String, account: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        SecItemDelete(query as CFDictionary)
    }
}

// MARK: - UI Helpers

private struct ContentCard: View {
    let item: ContentItem

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            CoverImage(url: item.coverUrl)
                .aspectRatio(0.72, contentMode: .fit)
            Text(item.title)
                .font(.subheadline.weight(.semibold))
                .lineLimit(2)
            Text(item.subtitle.isEmpty ? item.sourceName : item.subtitle)
                .font(.caption)
                .foregroundColor(.secondary)
                .lineLimit(1)
        }
        .padding(8)
        .background(AppColors.panel, in: RoundedRectangle(cornerRadius: 10))
    }
}

private struct CoverImage: View {
    let url: String

    var body: some View {
        AsyncImage(url: URL(string: url)) { phase in
            switch phase {
            case .success(let image):
                image.resizable().scaledToFill()
            case .failure:
                placeholder
            case .empty:
                ZStack {
                    placeholder
                    ProgressView().tint(AppColors.accent)
                }
            @unknown default:
                placeholder
            }
        }
        .clipped()
        .background(AppColors.panel)
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    private var placeholder: some View {
        ZStack {
            AppColors.panel
            Image(systemName: "book.closed")
                .font(.title)
                .foregroundColor(AppColors.accent)
        }
    }
}

private struct BrandedLoadingView: View {
    let title: String
    let subtitle: String

    var body: some View {
        VStack(spacing: 12) {
            ProgressView().tint(AppColors.accent)
            Text(title).font(.headline)
            Text(subtitle).font(.caption).foregroundColor(.secondary)
        }
        .padding(22)
        .background(AppColors.panel, in: RoundedRectangle(cornerRadius: 14))
    }
}

private struct LoadingGrid: View {
    private let columns = [GridItem(.adaptive(minimum: 142, maximum: 210), spacing: 14)]

    var body: some View {
        LazyVGrid(columns: columns, spacing: 14) {
            ForEach(0..<8, id: \.self) { _ in
                VStack(spacing: 8) {
                    RoundedRectangle(cornerRadius: 8).fill(AppColors.panel).aspectRatio(0.72, contentMode: .fit)
                    RoundedRectangle(cornerRadius: 4).fill(AppColors.panel).frame(height: 12)
                    RoundedRectangle(cornerRadius: 4).fill(AppColors.panel).frame(height: 10).padding(.trailing, 36)
                }
                .padding(8)
            }
        }
        .padding(.horizontal, 14)
    }
}

private struct EmptyState: View {
    let message: String

    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: "exclamationmark.circle")
                .font(.system(size: 36))
                .foregroundColor(AppColors.accent)
            Text(message)
                .multilineTextAlignment(.center)
                .foregroundColor(.secondary)
        }
        .frame(maxWidth: .infinity, minHeight: 220)
    }
}

private struct FieldRow: View {
    let title: String
    @Binding var text: String
    let icon: String
    var keyboard: UIKeyboardType = .default

    var body: some View {
        HStack {
            Image(systemName: icon).foregroundColor(.secondary).frame(width: 22)
            TextField(title, text: $text)
                .keyboardType(keyboard)
                .textInputAutocapitalization(keyboard == .emailAddress ? .never : .words)
                .disableAutocorrection(true)
        }
        .fieldStyle()
    }
}

private struct SecureFieldRow: View {
    let title: String
    @Binding var text: String

    var body: some View {
        HStack {
            Image(systemName: "lock").foregroundColor(.secondary).frame(width: 22)
            SecureField(title, text: $text)
                .textInputAutocapitalization(.never)
                .disableAutocorrection(true)
        }
        .fieldStyle()
    }
}

private struct WebShell: UIViewRepresentable {
    let url: URL
    @Binding var isLoading: Bool

    func makeCoordinator() -> Coordinator { Coordinator(isLoading: $isLoading) }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.allowsInlineMediaPlayback = true
        configuration.mediaTypesRequiringUserActionForPlayback = []
        configuration.websiteDataStore = .default()
        let view = WKWebView(frame: .zero, configuration: configuration)
        view.navigationDelegate = context.coordinator
        view.scrollView.backgroundColor = .black
        view.isOpaque = false
        view.load(URLRequest(url: url))
        return view
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}

    final class Coordinator: NSObject, WKNavigationDelegate {
        @Binding var isLoading: Bool

        init(isLoading: Binding<Bool>) {
            _isLoading = isLoading
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            isLoading = false
        }

        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            isLoading = false
        }
    }
}

enum VisionOcrReader {
    static func recognize(image: UIImage) async throws -> String {
        guard let cgImage = image.cgImage else { throw AppError.message("OCR could not read this image.") }
        return try await withCheckedThrowingContinuation { continuation in
            let request = VNRecognizeTextRequest { request, error in
                if let error {
                    continuation.resume(throwing: error)
                    return
                }
                let text = (request.results as? [VNRecognizedTextObservation] ?? [])
                    .sorted { $0.boundingBox.minY > $1.boundingBox.minY }
                    .compactMap { $0.topCandidates(1).first?.string }
                    .filter { $0.trimmingCharacters(in: .whitespacesAndNewlines).count > 1 }
                    .joined(separator: " ")
                continuation.resume(returning: text)
            }
            request.recognitionLevel = .accurate
            request.usesLanguageCorrection = true
            request.recognitionLanguages = ["en-US", "ja-JP", "ko-KR"]
            let handler = VNImageRequestHandler(cgImage: cgImage)
            DispatchQueue.global(qos: .userInitiated).async {
                do { try handler.perform([request]) }
                catch { continuation.resume(throwing: error) }
            }
        }
    }
}

// MARK: - Models

enum ContentKind: String, CaseIterable, Identifiable {
    case novels
    case manga
    case anime
    case kdrama
    case cartoon
    case movies

    var id: String { rawValue }
    var title: String {
        switch self {
        case .novels: return "Novels"
        case .manga: return "Manga"
        case .anime: return "Anime"
        case .kdrama: return "K-Drama"
        case .cartoon: return "Cartoon"
        case .movies: return "Movies"
        }
    }
    var icon: String {
        switch self {
        case .novels: return "book"
        case .manga: return "photo.on.rectangle"
        case .anime: return "play.tv"
        case .kdrama: return "tv"
        case .cartoon: return "sparkles.tv"
        case .movies: return "film"
        }
    }
}

struct ContentItem: Identifiable, Codable, Hashable {
    let id: String
    let title: String
    let subtitle: String
    let coverUrl: String
    let detailUrl: String
    let sourceName: String
    let kind: String
    let synopsis: String

    var isReadable: Bool { kind == "novel" || kind == "manga" }

    init(id: String, title: String, subtitle: String, coverUrl: String, detailUrl: String, sourceName: String, kind: String, synopsis: String) {
        self.id = id
        self.title = title
        self.subtitle = subtitle
        self.coverUrl = coverUrl
        self.detailUrl = detailUrl
        self.sourceName = sourceName
        self.kind = kind
        self.synopsis = synopsis
    }

    init?(syncFavorite: SyncFavorite) {
        guard !syncFavorite.id.isEmpty else { return nil }
        self.init(
            id: syncFavorite.id,
            title: syncFavorite.title,
            subtitle: syncFavorite.author ?? syncFavorite.sourceName,
            coverUrl: syncFavorite.coverUrl,
            detailUrl: syncFavorite.detailPageUrl,
            sourceName: syncFavorite.sourceName,
            kind: "novel",
            synopsis: ""
        )
    }
}

struct ChapterItem: Identifiable, Codable, Hashable {
    var id: String { url }
    let title: String
    let url: String
    let chapterNumber: Int
}

struct ReaderPayload: Identifiable {
    var id: String { chapter.url }
    let item: ContentItem
    let chapter: ChapterItem
    let text: String
}

struct MangaPayload: Identifiable {
    var id: String { chapter.url }
    let item: ContentItem
    let chapter: ChapterItem
    let pages: [String]
}

struct WatchRoute: Identifiable, Codable {
    var id: String { "\(route):\(url):\(title)" }
    let route: String
    let url: String
    let provider: String
    let title: String
    let message: String?
}

struct UserAccount: Codable {
    let id: String
    let username: String
    let email: String
    let plan: String
    let billingStatus: String
    let createdAt: String
}

struct HistoryItem: Identifiable, Codable, Hashable {
    let id: String
    let title: String
    let subtitle: String
    let kind: String
    let updatedAt: Date

    init(id: String, title: String, subtitle: String, kind: String, updatedAt: Date) {
        self.id = id
        self.title = title
        self.subtitle = subtitle
        self.kind = kind
        self.updatedAt = updatedAt
    }

    init(syncRead: SyncReadHistory) {
        self.init(id: syncRead.parentId, title: syncRead.title, subtitle: syncRead.chapterTitle, kind: syncRead.isManga ? "manga" : "novel", updatedAt: syncRead.updatedAt.dateFromMillis)
    }

    init(syncWatch: SyncWatchHistory) {
        self.init(id: syncWatch.parentId, title: syncWatch.title, subtitle: syncWatch.episodeTitle, kind: "video", updatedAt: syncWatch.updatedAt.dateFromMillis)
    }
}

struct ContentItemsPayload: Codable { let items: [ContentItem] }
struct ChaptersPayload: Codable { let chapters: [ChapterItem] }
struct ChapterTextPayload: Codable { let text: String }
struct MangaPagesPayload: Codable { let pages: [String] }
struct AuthResponse: Codable { let token: String?; let user: UserAccount }
struct AuthErrorResponse: Codable { let error: String }
struct APIEnvelope<T: Decodable>: Decodable { let ok: Bool; let data: T?; let error: String? }
struct EmptyResponse: Decodable {}

struct KokoroManifest: Codable {
    let version: String
    let minimumAppVersionCode: Int
    let model: KokoroModel
}

struct KokoroModel: Codable {
    let fileName: String
    let url: String
    let sizeBytes: Int
    let sha256: String
}

struct UserStateEnvelope: Codable {
    let user: UserAccount?
    let state: UserSyncState
}

struct UserSyncState: Codable {
    let favorites: [SyncFavorite]
    let readHistory: [SyncReadHistory]
    let watchHistory: [SyncWatchHistory]
    let updatedAt: Int64

    enum CodingKeys: String, CodingKey {
        case favorites, readHistory, watchHistory, updatedAt
    }

    init(favorites: [SyncFavorite], readHistory: [SyncReadHistory], watchHistory: [SyncWatchHistory], updatedAt: Int64) {
        self.favorites = favorites
        self.readHistory = readHistory
        self.watchHistory = watchHistory
        self.updatedAt = updatedAt
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        favorites = (try? container.decode([SyncFavorite].self, forKey: .favorites)) ?? []
        readHistory = (try? container.decode([SyncReadHistory].self, forKey: .readHistory)) ?? []
        watchHistory = (try? container.decode([SyncWatchHistory].self, forKey: .watchHistory)) ?? []
        if let value = try? container.decode(Int64.self, forKey: .updatedAt) {
            updatedAt = value
        } else if let value = try? container.decode(String.self, forKey: .updatedAt) {
            updatedAt = Int64((ISO8601DateFormatter().date(from: value) ?? Date()).timeIntervalSince1970 * 1000)
        } else {
            updatedAt = Int64(Date().timeIntervalSince1970 * 1000)
        }
    }
}

struct SyncFavorite: Codable {
    let id: String
    let title: String
    let coverUrl: String
    let sourceName: String
    let detailPageUrl: String
    let author: String?
    let genre: String?
    let addedAt: Int64

    enum CodingKeys: String, CodingKey {
        case id, title, coverUrl, sourceName, detailPageUrl, detailUrl, author, genre, addedAt
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        title = try container.decode(String.self, forKey: .title)
        coverUrl = (try? container.decode(String.self, forKey: .coverUrl)) ?? ""
        sourceName = (try? container.decode(String.self, forKey: .sourceName)) ?? ""
        detailPageUrl = (try? container.decode(String.self, forKey: .detailPageUrl))
            ?? (try? container.decode(String.self, forKey: .detailUrl))
            ?? ""
        author = try? container.decode(String.self, forKey: .author)
        genre = try? container.decode(String.self, forKey: .genre)
        addedAt = (try? container.decode(Int64.self, forKey: .addedAt)) ?? 0
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(title, forKey: .title)
        try container.encode(coverUrl, forKey: .coverUrl)
        try container.encode(sourceName, forKey: .sourceName)
        try container.encode(detailPageUrl, forKey: .detailPageUrl)
        try container.encode(author ?? "", forKey: .author)
        try container.encode(genre ?? "", forKey: .genre)
        try container.encode(addedAt, forKey: .addedAt)
    }

    init(item: ContentItem) {
        id = item.id
        title = item.title
        coverUrl = item.coverUrl
        sourceName = item.sourceName
        detailPageUrl = item.detailUrl
        author = item.subtitle
        genre = item.kind
        addedAt = Int64(Date().timeIntervalSince1970 * 1000)
    }
}

struct SyncReadHistory: Codable {
    let parentId: String
    let title: String
    let coverUrl: String
    let sourceName: String
    let chapterTitle: String
    let chapterUrl: String
    let isManga: Bool
    let positionIndex: Int
    let updatedAt: Int64

    init(item: HistoryItem) {
        parentId = item.id
        title = item.title
        coverUrl = ""
        sourceName = item.kind
        chapterTitle = item.subtitle
        chapterUrl = item.id
        isManga = item.kind == "manga"
        positionIndex = 0
        updatedAt = Int64(item.updatedAt.timeIntervalSince1970 * 1000)
    }
}

struct SyncWatchHistory: Codable {
    let parentId: String
    let title: String
    let coverUrl: String
    let episodeTitle: String
    let streamUrl: String
    let episodeNumber: Int
    let positionMs: Int64
    let updatedAt: Int64

    init(item: HistoryItem) {
        parentId = item.id
        title = item.title
        coverUrl = ""
        episodeTitle = item.subtitle
        streamUrl = ""
        episodeNumber = 0
        positionMs = 0
        updatedAt = Int64(item.updatedAt.timeIntervalSince1970 * 1000)
    }
}

struct AnyEncodable: Encodable {
    private let encodeClosure: (Encoder) throws -> Void

    init<T: Encodable>(_ value: T) {
        encodeClosure = value.encode(to:)
    }

    func encode(to encoder: Encoder) throws {
        try encodeClosure(encoder)
    }
}

enum AppError: Error {
    case message(String)
}

func readable(_ error: Error) -> String {
    if case let AppError.message(message) = error { return message }
    return error.localizedDescription
}

extension Int64 {
    var dateFromMillis: Date {
        Date(timeIntervalSince1970: Double(self) / 1000)
    }
}

// MARK: - Styling

enum AppColors {
    static let background = Color(red: 0.03, green: 0.04, blue: 0.07)
    static let panel = Color(red: 0.09, green: 0.11, blue: 0.16)
    static let accent = Color(red: 0.26, green: 0.84, blue: 0.71)
}

struct PrimaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .foregroundColor(.black)
            .padding(.vertical, 14)
            .padding(.horizontal, 16)
            .background(AppColors.accent.opacity(configuration.isPressed ? 0.75 : 1), in: RoundedRectangle(cornerRadius: 10))
    }
}

struct SecondaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .foregroundColor(.white)
            .padding(.vertical, 13)
            .padding(.horizontal, 16)
            .frame(maxWidth: .infinity)
            .background(AppColors.panel.opacity(configuration.isPressed ? 0.7 : 1), in: RoundedRectangle(cornerRadius: 10))
    }
}

struct CompactButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.subheadline.weight(.semibold))
            .foregroundColor(.white)
            .padding(.horizontal, 12)
            .padding(.vertical, 9)
            .background(AppColors.panel.opacity(configuration.isPressed ? 0.7 : 1), in: Capsule())
    }
}

private extension View {
    func fieldStyle() -> some View {
        padding(.horizontal, 12)
            .frame(height: 52)
            .background(AppColors.panel, in: RoundedRectangle(cornerRadius: 10))
    }

    func card() -> some View {
        padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(AppColors.panel, in: RoundedRectangle(cornerRadius: 12))
    }
}
