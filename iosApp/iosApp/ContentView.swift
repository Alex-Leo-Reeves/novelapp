import SwiftUI
import ComposeApp

struct ContentView: View {
    @StateObject private var model = NovelAppViewModel()

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
        .tint(.accentMint)
        .preferredColorScheme(.dark)
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

    private let bridge = NovelAppIosBridge()

    init() {
        loadDeveloper()
        loadVersion()
        loadHome(resetPage: true)
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

    func toggleNarration() {
        if isNarrating {
            bridge.stopNarration()
            isNarrating = false
            narrationMessage = "Narration stopped."
            return
        }
        let text = readText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else {
            narrationMessage = "Paste text first."
            return
        }
        isNarrating = true
        narrationMessage = "Preparing chapter audio..."
        bridge.playNarration(text: text, cacheKey: "ios-swift-reader-\(text.hashValue)")
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.75) { [weak self] in
            self?.refreshNarrationStatus()
        }
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
        GridItem(.adaptive(minimum: 150, maximum: 210), spacing: 14)
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
                                LazyVGrid(columns: columns, spacing: 16) {
                                    ForEach(model.items) { item in
                                        NavigationLink {
                                            DetailView(item: item)
                                        } label: {
                                            ContentCard(item: item)
                                        }
                                        .buttonStyle(.plain)
                                    }
                                }
                                .padding(.horizontal, 18)
                            }
                        } header: {
                            DiscoverHeader(model: model)
                                .background(.ultraThinMaterial)
                        }
                    }
                    .padding(.bottom, 24)
                }
                .refreshable {
                    model.refreshDifferentSet()
                }
            }
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
                        .foregroundStyle(.secondary)
                }

                Spacer()

                if model.isLoading {
                    ProgressView()
                        .tint(.accentMint)
                }
            }

            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass")
                    .foregroundStyle(.secondary)
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
                    .foregroundStyle(.secondary)
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
                                .labelStyle(.titleAndIcon)
                                .font(.callout.weight(.semibold))
                                .lineLimit(1)
                                .padding(.horizontal, 12)
                                .frame(height: 38)
                                .background(
                                    tab == model.selectedTab ? Color.accentMint : Color.white.opacity(0.07),
                                    in: RoundedRectangle(cornerRadius: 8)
                                )
                                .foregroundStyle(tab == model.selectedTab ? Color.black : Color.white)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 18)
            }
        }
        .padding(.horizontal, 18)
        .padding(.top, 8)
        .padding(.bottom, 12)
    }
}

private struct ContentCard: View {
    let item: ContentItem

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            PosterImage(urlString: item.coverUrl)
                .aspectRatio(0.72, contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: 8))

            Text(item.title)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.white)
                .lineLimit(2)
                .multilineTextAlignment(.leading)

            HStack(spacing: 6) {
                Text(item.kind.capitalized)
                Text("•")
                Text(item.sourceName)
            }
            .font(.caption2.weight(.medium))
            .foregroundStyle(.secondary)
            .lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(10)
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
                                .tint(.accentMint)
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
                .foregroundStyle(.white.opacity(0.78))
        }
    }
}

private struct DetailView: View {
    let item: ContentItem

    var body: some View {
        ZStack {
            AppBackground()
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    HStack(alignment: .top, spacing: 16) {
                        PosterImage(urlString: item.coverUrl)
                            .frame(width: 130, height: 184)
                            .clipShape(RoundedRectangle(cornerRadius: 8))

                        VStack(alignment: .leading, spacing: 8) {
                            Text(item.title)
                                .font(.title3.weight(.bold))
                            Text(item.subtitle.isEmpty ? item.sourceName : item.subtitle)
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                            Label(item.kind.capitalized, systemImage: "sparkle.magnifyingglass")
                                .font(.caption.weight(.semibold))
                                .padding(.horizontal, 10)
                                .frame(height: 30)
                                .background(Color.accentMint.opacity(0.18), in: RoundedRectangle(cornerRadius: 8))
                        }
                    }

                    if !item.synopsis.isEmpty {
                        Text(item.synopsis)
                            .font(.body)
                            .foregroundStyle(.white.opacity(0.86))
                            .lineSpacing(4)
                    }

                    HStack(spacing: 10) {
                        Button {
                        } label: {
                            Label(item.kind == "anime" || item.kind == "movie" || item.kind == "cartoon" || item.kind == "kdrama" ? "Watch" : "Open", systemImage: "play.fill")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(PrimaryButtonStyle())

                        Button {
                        } label: {
                            Image(systemName: "heart")
                                .frame(width: 48, height: 48)
                        }
                        .buttonStyle(SecondaryIconButtonStyle())
                    }
                }
                .padding(18)
            }
        }
        .navigationTitle(item.title)
        .navigationBarTitleDisplayMode(.inline)
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
                                .foregroundStyle(.secondary)

                            TextEditor(text: $model.readText)
                                .frame(minHeight: 180)
                                .padding(8)
                                .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))
                                .scrollContentBackgroundCompat()

                            Button {
                                model.toggleNarration()
                            } label: {
                                Label(model.isNarrating ? "Stop Reading" : "Play Narration", systemImage: model.isNarrating ? "stop.fill" : "play.fill")
                                    .frame(maxWidth: .infinity)
                            }
                            .buttonStyle(PrimaryButtonStyle())
                            .disabled(model.readText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !model.isNarrating)
                        }
                        .padding(14)
                        .background(Color.white.opacity(0.055), in: RoundedRectangle(cornerRadius: 8))

                        VStack(alignment: .leading, spacing: 10) {
                            Text("Recent")
                                .font(.headline)
                            ForEach(model.items.prefix(10)) { item in
                                NavigationLink {
                                    DetailView(item: item)
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
                                                .foregroundStyle(.secondary)
                                        }
                                    }
                                }
                                .buttonStyle(.plain)
                                Divider().background(Color.white.opacity(0.08))
                            }
                        }
                    }
                    .padding(18)
                }
            }
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
                        .foregroundStyle(.accentMint)
                    Text("Downloads")
                        .font(.title3.weight(.bold))
                    Text("Saved chapters and videos will appear here.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                .padding(24)
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
                                .foregroundStyle(.secondary)
                        }
                        .padding(.vertical, 6)
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
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle("You")
        }
        .navigationViewStyle(.stack)
    }
}

private struct LoadingRows: View {
    var body: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 150, maximum: 210), spacing: 14)], spacing: 16) {
            ForEach(0..<8, id: \.self) { _ in
                RoundedRectangle(cornerRadius: 8)
                    .fill(Color.white.opacity(0.07))
                    .frame(height: 285)
                    .overlay(ProgressView().tint(.accentMint))
            }
        }
        .padding(.horizontal, 18)
    }
}

private struct EmptyState: View {
    let error: String?

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 38))
                .foregroundStyle(.accentMint)
            Text(error ?? "No results found")
                .font(.headline)
                .multilineTextAlignment(.center)
                .foregroundStyle(.white.opacity(0.9))
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
            .foregroundStyle(.black)
            .frame(height: 48)
            .background(Color.accentMint.opacity(configuration.isPressed ? 0.75 : 1), in: RoundedRectangle(cornerRadius: 8))
    }
}

private struct SecondaryIconButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .foregroundStyle(.white)
            .background(Color.white.opacity(configuration.isPressed ? 0.12 : 0.08), in: RoundedRectangle(cornerRadius: 8))
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

private extension Color {
    static let deepInk = Color(red: 0.025, green: 0.031, blue: 0.045)
    static let accentMint = Color(red: 0.49, green: 0.95, blue: 0.73)
}
