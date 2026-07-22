package com.alexleoreeves.novelapp.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import coil3.compose.AsyncImage
import com.alexleoreeves.novelapp.audio.SherpaNarrationController
import com.alexleoreeves.novelapp.audio.VoiceSetupPhase
import com.alexleoreeves.novelapp.data.*
import com.alexleoreeves.novelapp.platform.SavedUserAccount
import com.alexleoreeves.novelapp.platform.currentTimeMillis
import com.alexleoreeves.novelapp.ui.theme.*
import io.ktor.client.HttpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private fun maxSourcesForPlan(plan: String): Int = when (plan.trim().lowercase()) {
    "ai_novel_4" -> 4
    "ai_novel_5", "ai_creator_20", "ai_creator_unlimited" -> 5
    else -> 3
}

private fun availableSourceUpgrades(currentPlan: String): List<Pair<String, String>> {
    val plan = currentPlan.trim().lowercase()
    return when {
        plan in listOf("ai_novel_4", "ai_novel_5", "ai_creator_20", "ai_creator_unlimited") -> emptyList()
        else -> listOf(
            "ai_novel_4" to "Upgrade to 4 sources — ₦1,000",
            "ai_novel_5" to "Upgrade to 5 sources — ₦3,000"
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UniversalReadScreen(
    currentTheme: AppTheme,
    ttsController: SherpaNarrationController,
    requireAuth: (() -> Unit) -> Unit,
    account: SavedUserAccount? = null,
    downloadRepo: LocalDownloadRepository? = null,
    favorites: List<FavoriteNovel>? = null,
    onSubscribePlan: ((String) -> Unit)? = null
) {
    val isPlaying = ttsController.isPlaying.collectAsState()
    val isBuffering = ttsController.isBuffering.collectAsState()
    val voiceSetupStatus = ttsController.voiceSetupStatus.collectAsState()
    val ttsError = ttsController.lastError.collectAsState()

    var pastedText by remember { mutableStateOf("") }
    var urlInput by remember { mutableStateOf("") }
    var activeTab by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {
        GlassBackground()

        Column(modifier = Modifier.fillMaxSize().background(GlassOverlayColor)) {
            // Header
            Box(modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .statusBarsPadding()
            ) {
                Column {
                    Text("Universal Read", style = MaterialTheme.typography.headlineLarge,
                        color = Color.White, fontWeight = FontWeight.Bold)
                    Text("Listen to anything — text, URLs, docs, or AI novels",
                        style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.5f))
                }
            }

            TabRow(selectedTabIndex = activeTab, containerColor = currentTheme.surfaceColor(), contentColor = currentTheme.accentColor()) {
                Tab(selected = activeTab == 0, onClick = { activeTab = 0 }) {
                    Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ContentPaste, null, tint = if (activeTab == 0) currentTheme.accentColor() else currentTheme.subTextColor(), modifier = Modifier.size(14.dp))
                        Text("Paste Text", color = if (activeTab == 0) currentTheme.accentColor() else currentTheme.subTextColor())
                    }
                }
                Tab(selected = activeTab == 1, onClick = { activeTab = 1 }) {
                    Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Link, null, tint = if (activeTab == 1) currentTheme.accentColor() else currentTheme.subTextColor(), modifier = Modifier.size(14.dp))
                        Text("From URL", color = if (activeTab == 1) currentTheme.accentColor() else currentTheme.subTextColor())
                    }
                }
                Tab(selected = activeTab == 2, onClick = { activeTab = 2 }) {
                    Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoStories, null, tint = if (activeTab == 2) currentTheme.accentColor() else currentTheme.subTextColor(), modifier = Modifier.size(14.dp))
                        Text("AI Novel", color = if (activeTab == 2) currentTheme.accentColor() else currentTheme.subTextColor())
                    }
                }
                Tab(selected = activeTab == 3, onClick = { requireAuth { activeTab = 3 } }) {
                    Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.EditNote, null, tint = if (activeTab == 3) currentTheme.accentColor() else currentTheme.subTextColor(), modifier = Modifier.size(14.dp))
                        Text("Write", color = if (activeTab == 3) currentTheme.accentColor() else currentTheme.subTextColor())
                    }
                }
            }

            val setupStatus = voiceSetupStatus.value
            if (isBuffering.value || setupStatus.shouldShow) {
                Column(modifier = Modifier.fillMaxWidth().background(currentTheme.surfaceColor()).padding(horizontal = 16.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val percent = setupStatus.progressFraction
                    val statusMessage = if (isBuffering.value && !setupStatus.shouldShow) "Preparing narration audio."
                    else setupStatus.userMessage.ifBlank { "Preparing voice." }
                    Text(text = buildString { append(statusMessage); if (percent != null) { append(" "); append((percent * 100f).roundToInt()); append("%") } },
                        style = MaterialTheme.typography.bodySmall, color = currentTheme.subTextColor())
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(0.72f).height(3.dp).padding(top = 6.dp),
                        color = currentTheme.accentColor(), trackColor = currentTheme.cardColor().copy(alpha = 0.5f))
                    if (setupStatus.phase == VoiceSetupPhase.Error || setupStatus.phase == VoiceSetupPhase.Fallback) {
                        TextButton(onClick = {
                            val text = when { activeTab == 0 && pastedText.isNotBlank() -> pastedText
                                activeTab == 1 && urlInput.isNotBlank() -> "Reading content from: $urlInput"
                                else -> "" }
                            if (text.isNotBlank()) scope.launch { ttsController.playText(GroqTextCleaner.cleanForKokoro(text)) }
                        }) { Text("Retry voice setup", color = currentTheme.accentColor()) }
                    }
                }
            }

            // Show last synthesis error (hidden when buffering clears it)
            ttsError.value?.takeIf { it.isNotBlank() }?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            when (activeTab) {
                0 -> PasteTextTab(pastedText, isPlaying.value, isBuffering.value, currentTheme, requireAuth, ttsController) { pastedText = it }
                1 -> UrlInputTab(urlInput, isPlaying.value, isBuffering.value, currentTheme, requireAuth, ttsController) { urlInput = it }
                2 -> AiNovelCreatorTab(currentTheme = currentTheme, account = account, downloadRepo = downloadRepo, favorites = favorites, requireAuth = requireAuth, ttsController = ttsController, onSubscribePlan = onSubscribePlan)
                3 -> WriteNovelTab(currentTheme = currentTheme, account = account, ttsController = ttsController, requireAuth = requireAuth)
            }
        }
    }
}

@Composable
private fun PasteTextTab(text: String, isPlaying: Boolean, isBuffering: Boolean, currentTheme: AppTheme, requireAuth: (() -> Unit) -> Unit, ttsController: SherpaNarrationController, onTextChanged: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(value = text, onValueChange = onTextChanged, modifier = Modifier.fillMaxWidth().weight(1f),
            placeholder = { Text("Paste text, article, or story here…", color = currentTheme.subTextColor()) },
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = currentTheme.accentColor(), unfocusedBorderColor = currentTheme.subTextColor().copy(0.5f), focusedTextColor = currentTheme.textColor(), unfocusedTextColor = currentTheme.textColor(), cursorColor = currentTheme.accentColor(), unfocusedContainerColor = currentTheme.cardColor().copy(0.5f), focusedContainerColor = currentTheme.cardColor().copy(0.5f)),
            shape = RoundedCornerShape(12.dp))
        if (text.isNotEmpty()) Text("${text.split("\\s+".toRegex()).size} words", style = MaterialTheme.typography.labelSmall, color = currentTheme.subTextColor())
        Button(onClick = { requireAuth { if (isPlaying) ttsController.stop() else if (text.isNotEmpty()) scope.launch { ttsController.playText(GroqTextCleaner.cleanForKokoro(text)) } } },
            modifier = Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = currentTheme.accentColor()), shape = RoundedCornerShape(12.dp), enabled = text.isNotEmpty() || isPlaying) {
            Icon(if (isBuffering) Icons.Default.GraphicEq else if (isPlaying) Icons.Default.StopCircle else Icons.Default.PlayCircle, null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (isBuffering) "Preparing Voice" else if (isPlaying) "Stop Reading" else "Start Reading", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun UrlInputTab(url: String, isPlaying: Boolean, isBuffering: Boolean, currentTheme: AppTheme, requireAuth: (() -> Unit) -> Unit, ttsController: SherpaNarrationController, onUrlChanged: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(value = url, onValueChange = onUrlChanged, modifier = Modifier.fillMaxWidth(), label = { Text("Article / Blog URL", color = currentTheme.subTextColor()) }, placeholder = { Text("https://...", color = currentTheme.subTextColor()) }, singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = currentTheme.accentColor(), unfocusedBorderColor = currentTheme.subTextColor().copy(0.5f), focusedTextColor = currentTheme.textColor(), unfocusedTextColor = currentTheme.textColor(), cursorColor = currentTheme.accentColor()),
            shape = RoundedCornerShape(12.dp), leadingIcon = { Icon(Icons.Default.Link, null, tint = currentTheme.accentColor()) })
        Button(onClick = { requireAuth { if (isPlaying) ttsController.stop() else scope.launch { ttsController.playText(GroqTextCleaner.cleanForKokoro("Reading content from: $url")) } } },
            modifier = Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = currentTheme.accentColor()), shape = RoundedCornerShape(12.dp), enabled = isPlaying || (url.isNotEmpty() && url.startsWith("http"))) {
            Icon(if (isPlaying) Icons.Default.StopCircle else Icons.Default.Download, null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp)); Text(if (isPlaying) "Stop Reading" else "Fetch & Read", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
        }
        Surface(shape = RoundedCornerShape(12.dp), color = currentTheme.cardColor()) {
            Row(modifier = Modifier.padding(14.dp)) {
                Icon(Icons.Default.Info, null, tint = currentTheme.accentColor(), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp)); Text("Paste any article/blog URL. Text will be extracted and read using AI narration.", style = MaterialTheme.typography.bodyMedium, color = currentTheme.subTextColor())
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
//  AI Novel Creator Tab — with favorites picker, payment dropdown, source limits, working local save.
// ─────────────────────────────────────────────────────────────────────────
@Composable
private fun AiNovelCreatorTab(
    currentTheme: AppTheme, account: SavedUserAccount?, downloadRepo: LocalDownloadRepository?,
    favorites: List<FavoriteNovel>?, requireAuth: (() -> Unit) -> Unit,
    ttsController: SherpaNarrationController, onSubscribePlan: ((String) -> Unit)? = null
) {
    val token = account?.authToken.orEmpty()
    val api = remember { AiNovelApi() }
    val sourceLimit = maxSourcesForPlan(account?.plan ?: "free")
    val upgradeOptions = availableSourceUpgrades(account?.plan ?: "free")

    var innerTab by remember { mutableStateOf(0) }
    var communityNovels by remember { mutableStateOf<List<AiNovel>>(emptyList()) }
    var isLoadingCommunity by remember { mutableStateOf(false) }
    var quota by remember { mutableStateOf<AiQuota?>(null) }
    var selectedSources by remember { mutableStateOf<List<UnifiedSearchResult>>(emptyList()) }
    var userDescription by remember { mutableStateOf("") }
    var isLongNovel by remember { mutableStateOf(false) }
    var showSourcePicker by remember { mutableStateOf(false) }
    var searchPickerQuery by remember { mutableStateOf("") }
    var searchPickerResults by remember { mutableStateOf<List<UnifiedSearchResult>>(emptyList()) }
    var isSearchingPicker by remember { mutableStateOf(false) }
    var pickerTab by remember { mutableStateOf(0) }
    var showPaymentDropdown by remember { mutableStateOf(false) }
    var isGenerating by remember { mutableStateOf(false) }
    var generationProgress by remember { mutableStateOf(0f) }
    var generationStatusText by remember { mutableStateOf("") }
    var generationCountdownSeconds by remember { mutableStateOf(0) }
    var generatedResult by remember { mutableStateOf<AiNovelCompleteResponse?>(null) }
    var generationError by remember { mutableStateOf("") }
    var isPublishing by remember { mutableStateOf(false) }
    var isSavingLocally by remember { mutableStateOf(false) }
    var feedbackMessage by remember { mutableStateOf("") }
    var activeReadingNovel by remember { mutableStateOf<AiNovel?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    val scope = rememberCoroutineScope()
    val httpClient = remember { HttpClient() }
    val tmdbScraper = remember { TMDBMovieScraper(httpClient) }

    val novelDownloadedItems = remember { downloadRepo?.getNovelItems() ?: emptyList() }
    val allFavoriteItems: List<UnifiedSearchResult> = remember(favorites, novelDownloadedItems) {
        val fromFavs = (favorites ?: emptyList()).map { fav -> UnifiedSearchResult(id = fav.id, title = fav.title, coverUrl = fav.coverUrl, detailPageUrl = fav.detailPageUrl, sourceName = "Favorite: ${fav.sourceName}", synopsis = fav.genre) }
        val fromDownloaded = novelDownloadedItems.map { item -> UnifiedSearchResult(id = item.id, title = item.title, coverUrl = item.coverUrl, detailPageUrl = item.id, sourceName = "Saved: ${item.sourceName}") }
        (fromFavs + fromDownloaded).distinctBy { it.id }
    }

    LaunchedEffect(account) { if (token.isNotBlank()) runCatching { api.fetchQuota(token) }.onSuccess { quota = it } }
    LaunchedEffect(innerTab, account) { if (innerTab == 0 && token.isNotBlank()) { isLoadingCommunity = true; runCatching { api.fetchCommunityNovels(1, token) }.onSuccess { communityNovels = it }; isLoadingCommunity = false } }

    // Full-screen community novel reader
    if (activeReadingNovel != null) {
        val novel = activeReadingNovel!!
        Box(modifier = Modifier.fillMaxSize().background(currentTheme.backgroundColor())) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp).statusBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { activeReadingNovel = null }) { Icon(Icons.Default.ArrowBack, null, tint = currentTheme.textColor()) }
                    Spacer(Modifier.width(8.dp))
                    Text(novel.title, style = MaterialTheme.typography.titleMedium, color = currentTheme.textColor(), fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { ttsController.playText(novel.content, novel.title) }) { Icon(Icons.Default.VolumeUp, null, tint = currentTheme.accentColor()) }
                }
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
                    if (novel.cover_url.isNotBlank()) { Box(modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(12.dp)).background(currentTheme.cardColor())) { AsyncImage(model = novel.cover_url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }; Spacer(Modifier.height(12.dp)) }
                    Text("By ${novel.author_name}", style = MaterialTheme.typography.labelSmall, color = currentTheme.subTextColor())
                    Divider(color = currentTheme.textColor().copy(alpha = 0.1f), modifier = Modifier.padding(vertical = 12.dp))
                    Text(novel.content, style = MaterialTheme.typography.bodyLarge, color = currentTheme.textColor())
                    Spacer(Modifier.height(48.dp))
                }
            }
        }
        return
    }

    // Main layout — wrapped in Box for SnackbarHost overlay
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).background(currentTheme.cardColor(), RoundedCornerShape(10.dp)).padding(4.dp)) {
                listOf("Community", "Create Mashup").forEachIndexed { idx, title ->
                    Box(modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(if (innerTab == idx) currentTheme.accentColor() else Color.Transparent)
                        .clickable { if (idx == 1) requireAuth { innerTab = idx } else innerTab = idx }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Text(title, color = if (innerTab == idx) Color.White else currentTheme.textColor(), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Max source novels: $sourceLimit", style = MaterialTheme.typography.labelSmall, color = currentTheme.subTextColor())
                if (upgradeOptions.isNotEmpty()) TextButton(onClick = { showPaymentDropdown = true }) { Icon(Icons.Default.Upgrade, null, modifier = Modifier.size(14.dp), tint = currentTheme.accentColor()); Spacer(Modifier.width(4.dp)); Text("Unlock more", color = currentTheme.accentColor(), style = MaterialTheme.typography.labelSmall) }
            }

            quota?.let { q -> Text(if (q.limitShort == -1) "Unlimited creations" else "Short: ${q.limitShort} · Long: ${q.limitLong} left this week", color = currentTheme.accentColor(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)) }

            if (innerTab == 0) {
                // ── Community Tab ──
                if (isLoadingCommunity && communityNovels.isEmpty()) { Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = currentTheme.accentColor()) } }
                else if (communityNovels.isEmpty()) { Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No community AI novels yet. Be the first to publish!", color = currentTheme.subTextColor(), textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp)) } }
                else { LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(communityNovels) { novel -> Card(colors = CardDefaults.cardColors(containerColor = currentTheme.cardColor()), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().clickable { activeReadingNovel = novel }) {
                        Column {
                            Box(modifier = Modifier.fillMaxWidth().height(160.dp).background(Color.DarkGray)) { if (novel.cover_url.isNotBlank()) AsyncImage(model = novel.cover_url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else Icon(Icons.Default.AutoStories, null, modifier = Modifier.align(Alignment.Center).size(40.dp), tint = Color.Gray) }
                            Column(modifier = Modifier.padding(8.dp)) { Text(novel.title, color = currentTheme.textColor(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("By ${novel.author_name}", color = currentTheme.subTextColor(), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${novel.word_count} words · ${novel.type.uppercase()}", color = currentTheme.accentColor(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
                        }
                    } } } }
            } else if (isGenerating) {
                // ── Generation Loading ──
                Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(progress = { generationProgress }, color = currentTheme.accentColor(), strokeWidth = 6.dp, modifier = Modifier.size(80.dp))
                    Spacer(Modifier.height(24.dp)); Text("Generating Mashup Novel...", style = MaterialTheme.typography.titleLarge, color = currentTheme.textColor(), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp)); Text(generationStatusText, style = MaterialTheme.typography.bodyMedium, color = currentTheme.subTextColor(), textAlign = TextAlign.Center)
                    if (generationCountdownSeconds > 0) { Spacer(Modifier.height(16.dp)); Text("Estimated time remaining: ${generationCountdownSeconds / 60}m ${generationCountdownSeconds % 60}s", style = MaterialTheme.typography.bodyMedium, color = currentTheme.accentColor(), fontWeight = FontWeight.Bold) }
                }
            } else if (generatedResult != null) {
                // ── Generated Result ──
                val result = generatedResult!!
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(16.dp)).background(currentTheme.cardColor())) { AsyncImage(model = result.coverUrl, contentDescription = "Cover", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    Text(result.title, style = MaterialTheme.typography.headlineMedium, color = currentTheme.textColor(), fontWeight = FontWeight.Black)
                    Text("Generated with Gemini 2.5 Flash", style = MaterialTheme.typography.labelSmall, color = currentTheme.subTextColor())
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { scope.launch { try { val novelId = "ai_${currentTimeMillis()}"; downloadRepo?.addItem(DownloadedItem(id = novelId, title = result.title, coverUrl = result.coverUrl, type = "NOVEL", sourceName = "AI Generated")); val savedPath = saveDownloadedText(novelId, 1, result.content); downloadRepo?.addChapter(DownloadedChapter(parentId = novelId, chapterNumber = 1, chapterTitle = "Crossover Fusion", localFilePath = savedPath)); feedbackMessage = if (savedPath.isNotBlank()) "Saved offline! Check your Downloads/NovelApp folder." else "Saved offline!" } catch (e: Exception) { feedbackMessage = "Save failed: ${e.message}" } } },
                            modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = currentTheme.accentColor())) { Icon(Icons.Outlined.Download, null); Spacer(Modifier.width(4.dp)); Text("Save Offline") }
                        Button(onClick = { scope.launch { try { api.publishNovel(result.title, result.coverPrompt, result.coverUrl, result.content, if (isLongNovel) "long" else "short", selectedSources, "Crossover", token); feedbackMessage = "Published to community!" } catch (e: Exception) { feedbackMessage = "Publish failed: ${e.message}" } } },
                            modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))) { Icon(Icons.Outlined.CloudUpload, null); Spacer(Modifier.width(4.dp)); Text("Publish") }
                    }
                    if (feedbackMessage.isNotBlank()) Text(feedbackMessage, color = currentTheme.accentColor(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Button(onClick = { ttsController.playText(result.content, result.title) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = currentTheme.cardColor())) { Icon(Icons.Default.VolumeUp, null, tint = currentTheme.textColor()); Spacer(Modifier.width(6.dp)); Text("Listen with Kokoro", color = currentTheme.textColor()) }
                    Divider(color = currentTheme.textColor().copy(alpha = 0.1f))
                    Text(result.content, style = MaterialTheme.typography.bodyLarge, color = currentTheme.textColor())
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { generatedResult = null; feedbackMessage = "" }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = currentTheme.cardColor())) { Text("Create Another", color = currentTheme.textColor()) }
                }
            } else {
                // ── Config Form ──
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Source limit card
                    if (upgradeOptions.isNotEmpty()) {
                        Card(colors = CardDefaults.cardColors(containerColor = currentTheme.cardColor()), shape = RoundedCornerShape(12.dp)) {
                            Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(modifier = Modifier.weight(1f)) { Text("Your Limit: $sourceLimit novels", color = currentTheme.textColor(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold); Text("Free users can fuse up to 3 sources.", color = currentTheme.subTextColor(), style = MaterialTheme.typography.labelSmall) }
                                Button(onClick = { showPaymentDropdown = true }, colors = ButtonDefaults.buttonColors(containerColor = currentTheme.accentColor()), shape = RoundedCornerShape(8.dp)) { Icon(Icons.Default.Upgrade, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Upgrade", style = MaterialTheme.typography.labelSmall) }
                            }
                        }
                    }
                    // Type selector
                    Card(colors = CardDefaults.cardColors(containerColor = currentTheme.cardColor()), shape = RoundedCornerShape(12.dp)) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text("1. Choose Generation Type", color = currentTheme.textColor(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold); Spacer(Modifier.height(12.dp))
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Box(modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (!isLongNovel) currentTheme.accentColor().copy(alpha = 0.2f) else Color.Transparent).border(1.dp, if (!isLongNovel) currentTheme.accentColor() else currentTheme.textColor().copy(alpha = 0.1f), RoundedCornerShape(8.dp)).clickable { isLongNovel = false }.padding(12.dp), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("Short Novel", color = currentTheme.textColor(), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium); Text("Quick summary", color = currentTheme.subTextColor(), style = MaterialTheme.typography.labelSmall) }
                                }
                                Spacer(Modifier.width(12.dp))
                                Box(modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (isLongNovel) currentTheme.accentColor().copy(alpha = 0.2f) else Color.Transparent).border(1.dp, if (isLongNovel) currentTheme.accentColor() else currentTheme.textColor().copy(alpha = 0.1f), RoundedCornerShape(8.dp)).clickable { isLongNovel = true }.padding(12.dp), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("Long Novel", color = currentTheme.textColor(), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium); Text("Reads chapters", color = currentTheme.subTextColor(), style = MaterialTheme.typography.labelSmall) }
                                }
                            }
                        }
                    }
                    // Sources
                    Card(colors = CardDefaults.cardColors(containerColor = currentTheme.cardColor()), shape = RoundedCornerShape(12.dp)) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("2. Select Fused Universes (Max $sourceLimit)", color = currentTheme.textColor(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            if (selectedSources.isEmpty()) Text("No source universes added yet.", color = currentTheme.subTextColor(), style = MaterialTheme.typography.bodySmall)
                            selectedSources.forEach { src ->
                                Row(modifier = Modifier.fillMaxWidth().background(currentTheme.backgroundColor().copy(alpha = 0.5f), RoundedCornerShape(8.dp)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    AsyncImage(model = src.coverUrl, contentDescription = null, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)), contentScale = ContentScale.Crop)
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) { Text(src.title, color = currentTheme.textColor(), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); /* source name hidden */ }
                                    IconButton(onClick = { selectedSources = selectedSources - src }) { Icon(Icons.Default.Delete, null, tint = Color.Red) }
                                }
                            }
                            if (selectedSources.size < sourceLimit) { Button(onClick = { showSourcePicker = true }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = currentTheme.accentColor())) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Add Universe / Novel Source") } }
                            else Text("Source limit reached. Upgrade to add more.", color = currentTheme.accentColor(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                    // Description
                    Card(colors = CardDefaults.cardColors(containerColor = currentTheme.cardColor()), shape = RoundedCornerShape(12.dp)) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text("3. Add Your Plot Direction / Description", color = currentTheme.textColor(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold); Spacer(Modifier.height(10.dp))
                            TextField(value = userDescription, onValueChange = { userDescription = it }, placeholder = { Text("Include specific characters, setting descriptions, or plot twists...") }, modifier = Modifier.fillMaxWidth().height(120.dp), colors = TextFieldDefaults.colors(), shape = RoundedCornerShape(8.dp))
                        }
                    }
                    if (generationError.isNotBlank()) Text(generationError, color = Color.Red, style = MaterialTheme.typography.bodySmall)
                    // Generate Button
                    Button(onClick = {
                        if (selectedSources.size < 2) { generationError = "Please select at least 2 source novels/shows."; return@Button }
                        if (selectedSources.size > sourceLimit) { generationError = "You can only use $sourceLimit source novels."; return@Button }
                        generationError = ""; isGenerating = true; generationProgress = 0.05f; generationStatusText = "Initiating server connection & checking quota..."
                        scope.launch {
                            runCatching { api.generateStart(if (isLongNovel) "long" else "short", token) }
                                .onSuccess {
                                    scope.launch {
                                        try {
                                            if (!isLongNovel) {
                                                generationProgress = 0.5f; generationStatusText = "Generating crossover story..."
                                                val comp = api.generateComplete("short", selectedSources, userDescription, emptyList(), token)
                                                generatedResult = comp; quota = api.fetchQuota(token)
                                            } else {
                                                val allChunks = mutableListOf<Pair<String, String>>()
                                                generationStatusText = "Gathering chapters and summaries..."
                                                selectedSources.forEach { src ->
                                                    if (src.detailPageUrl.startsWith("tmdb://")) {
                                                        val parts = src.detailPageUrl.removePrefix("tmdb://").split("/")
                                                        val mediaType = parts.getOrNull(0) ?: "tv"; val tmdbId = parts.getOrNull(1) ?: ""
                                                        if (mediaType == "tv") { val eps = tmdbScraper.fetchTVSeasonsAndEpisodes(tmdbId); eps.take(5).forEach { ep -> if (ep.title.isNotBlank()) allChunks.add(Pair("Episode ${ep.episodeNumber}: ${ep.title}. Plot: ${src.synopsis}", src.title)) } }
                                                        else allChunks.add(Pair(src.synopsis, src.title))
                                                    } else { downloadRepo?.getChaptersFor(src.id)?.take(3)?.forEach { ch -> val txt = loadDownloadedText(ch.localFilePath); if (txt.isNotBlank()) allChunks.add(Pair("Chapter ${ch.chapterNumber}: ${ch.chapterTitle}. Content:\n$txt", src.title)) } }
                                                }
                                                if (allChunks.isEmpty()) allChunks.add(Pair("No chapters available.", "Lore Reference"))
                                                val summaries = mutableListOf<String>()
                                                val totalSteps = allChunks.size
                                                for (i in allChunks.indices) { val currentChunk = allChunks[i]; generationProgress = 0.1f + ((i.toFloat() / totalSteps) * 0.6f); generationStatusText = "Reading chunk ${i + 1} of $totalSteps from ${currentChunk.second}..."; generationCountdownSeconds = (totalSteps - i) * 20; val chunkRes = api.generateChunk(currentChunk.first.take(5000), currentChunk.second, token); summaries.add(chunkRes.summary); if (i < totalSteps - 1) { for (cd in 20 downTo 1) { generationCountdownSeconds = (totalSteps - i - 1) * 20 + cd; generationStatusText = "Cooldown... (${cd}s)"; delay(1000) } } }
                                                generationProgress = 0.85f; generationCountdownSeconds = 0; generationStatusText = "Synthesizing final novel..."
                                                val comp = api.generateComplete("long", selectedSources, userDescription, summaries, token)
                                                generatedResult = comp; quota = api.fetchQuota(token)
                                            }
                                        } catch (e: Exception) { generationError = e.message ?: "Generation pipeline failed."; e.printStackTrace() } finally { isGenerating = false }
                                    }
                                }.onFailure { generationError = it.message ?: "Failed to verify quota."; isGenerating = false }
                        }
                    }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), colors = ButtonDefaults.buttonColors(containerColor = currentTheme.accentColor())) {
                        Icon(Icons.Default.Bolt, null); Spacer(Modifier.width(6.dp)); Text("FUSE & GENERATE NOVEL")
                    }
                }
            }
        }

        // SnackbarHost overlay for generation-complete notification
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .align(Alignment.BottomCenter),
            snackbar = { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = currentTheme.accentColor(),
                    contentColor = Color.White,
                    shape = RoundedCornerShape(14.dp)
                )
            }
        )
    }

    // Show snackbar when generation finishes successfully
    LaunchedEffect(generatedResult) {
        if (generatedResult != null) {
            snackbarHostState.showSnackbar(
                message = "\u2728 \"${generatedResult!!.title}\" is ready! Scroll down to read it.",
                duration = SnackbarDuration.Short
            )
        }
    }

    // ── Source Picker Dialog ──
    if (showSourcePicker) {
        AlertDialog(onDismissRequest = { showSourcePicker = false }, title = { Text("Select Fused Source", color = currentTheme.textColor()) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(modifier = Modifier.fillMaxWidth().background(currentTheme.cardColor(), RoundedCornerShape(8.dp)).padding(2.dp)) {
                        listOf("My Favorites", "Search Video/Books").forEachIndexed { idx, label ->
                            Box(modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(if (pickerTab == idx) currentTheme.accentColor() else Color.Transparent).clickable { pickerTab = idx }.padding(8.dp), contentAlignment = Alignment.Center) {
                                Text(label, color = if (pickerTab == idx) Color.White else currentTheme.textColor(), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    if (pickerTab == 0) {
                        if (allFavoriteItems.isEmpty()) Text("No saved or downloaded novels found. Add favorites or download novels first.", color = currentTheme.subTextColor(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp))
                        else { LazyColumn(modifier = Modifier.height(260.dp)) { items(allFavoriteItems) { item -> Row(modifier = Modifier.fillMaxWidth().clickable { if (selectedSources.none { it.id == item.id } && selectedSources.size < sourceLimit) selectedSources = selectedSources + item; showSourcePicker = false }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) { AsyncImage(model = item.coverUrl, contentDescription = null, modifier = Modifier.size(36.dp).clip(RoundedCornerShape(4.dp)), contentScale = ContentScale.Crop); Spacer(Modifier.width(10.dp)); Column { Text(item.title, color = currentTheme.textColor(), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold); /* source name hidden */ } } } } }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                TextField(value = searchPickerQuery, onValueChange = { searchPickerQuery = it }, placeholder = { Text("Search show/movie/novel name...") }, modifier = Modifier.weight(1f), singleLine = true, colors = TextFieldDefaults.colors())
                                Spacer(Modifier.width(6.dp))
                                IconButton(onClick = { if (searchPickerQuery.isBlank()) return@IconButton; isSearchingPicker = true; scope.launch { runCatching { tmdbScraper.search(searchPickerQuery).map { res -> UnifiedSearchResult(id = "tmdb_${if (res.type == "MOVIE") "movie" else "tv"}_${res.id}", title = res.title, coverUrl = res.coverUrl, detailPageUrl = "tmdb://${if (res.type == "MOVIE") "movie" else "tv"}/${res.id}", sourceName = "TMDB Media", synopsis = res.description) } }.onSuccess { searchPickerResults = it }; isSearchingPicker = false } }) {
                                    if (isSearchingPicker) CircularProgressIndicator(color = currentTheme.accentColor(), modifier = Modifier.size(24.dp)) else Icon(Icons.Default.Search, null, tint = currentTheme.accentColor())
                                }
                            }
                            LazyColumn(modifier = Modifier.height(200.dp)) { items(searchPickerResults) { item -> Row(modifier = Modifier.fillMaxWidth().clickable { if (selectedSources.none { it.id == item.id } && selectedSources.size < sourceLimit) selectedSources = selectedSources + item; showSourcePicker = false }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) { AsyncImage(model = item.coverUrl, contentDescription = null, modifier = Modifier.size(36.dp).clip(RoundedCornerShape(4.dp)), contentScale = ContentScale.Crop); Spacer(Modifier.width(10.dp)); Column(modifier = Modifier.weight(1f)) { Text(item.title, color = currentTheme.textColor(), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); /* source name hidden */ } } } }
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = { showSourcePicker = false }) { Text("Close", color = currentTheme.accentColor()) } }, containerColor = currentTheme.surfaceColor())
    }

    // ── Payment Upgrade Dropdown ──
    if (showPaymentDropdown) {
        AlertDialog(onDismissRequest = { showPaymentDropdown = false }, title = { Text("Upgrade Source Limit", color = currentTheme.textColor(), fontWeight = FontWeight.Bold) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Your current plan ($sourceLimit sources) limits how many novels/shows you can fuse. Upgrade for more:", color = currentTheme.subTextColor(), style = MaterialTheme.typography.bodyMedium)
                    Card(onClick = { onSubscribePlan?.invoke("ai_novel_4"); showPaymentDropdown = false }, colors = CardDefaults.cardColors(containerColor = currentTheme.cardColor()), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Column(modifier = Modifier.weight(1f)) { Text("AI Fusion 4", color = currentTheme.textColor(), fontWeight = FontWeight.Bold); Text("Fuse up to 4 sources", color = currentTheme.subTextColor(), style = MaterialTheme.typography.labelSmall) }; Text("₦1,000", color = currentTheme.accentColor(), fontWeight = FontWeight.Bold) }
                    }
                    Card(onClick = { onSubscribePlan?.invoke("ai_novel_5"); showPaymentDropdown = false }, colors = CardDefaults.cardColors(containerColor = currentTheme.cardColor()), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Column(modifier = Modifier.weight(1f)) { Text("AI Fusion 5", color = currentTheme.textColor(), fontWeight = FontWeight.Bold); Text("Fuse up to 5 sources", color = currentTheme.subTextColor(), style = MaterialTheme.typography.labelSmall) }; Text("₦3,000", color = currentTheme.accentColor(), fontWeight = FontWeight.Bold) }
                    }
                    Text("Payments via Flutterwave. One-time per month.", color = currentTheme.subTextColor(), style = MaterialTheme.typography.labelSmall)
                }
            }, confirmButton = { TextButton(onClick = { showPaymentDropdown = false }) { Text("Close", color = currentTheme.accentColor()) } }, containerColor = currentTheme.surfaceColor())
    }
}

// ─────────────────────────────────────────────────────────────────────────
//  Write Novel Tab — user can create their own novels with chapters
// ─────────────────────────────────────────────────────────────────────────
@Composable
private fun WriteNovelTab(
    currentTheme: AppTheme,
    account: SavedUserAccount?,
    ttsController: SherpaNarrationController,
    requireAuth: (() -> Unit) -> Unit
) {
    val token = account?.authToken.orEmpty()
    val api = remember { UserNovelApi() }
    val scope = rememberCoroutineScope()

    // State
    var myNovels by remember { mutableStateOf<List<UserNovel>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showCreateForm by remember { mutableStateOf(false) }
    var editingNovel by remember { mutableStateOf<UserNovel?>(null) }

    // Create form fields
    var newTitle by remember { mutableStateOf("") }
    var newCoverUrl by remember { mutableStateOf("") }
    var newDescription by remember { mutableStateOf("") }

    // Chapter editor fields
    var chapterTitle by remember { mutableStateOf("") }
    var chapterContent by remember { mutableStateOf("") }
    var editingChapterNumber by remember { mutableStateOf(0) }
    var statusMsg by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf("") }

    // Load my novels
    LaunchedEffect(token) {
        if (token.isNotBlank()) {
            isLoading = true
            runCatching { api.getMyNovels(token) }.onSuccess { myNovels = it }
            isLoading = false
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(currentTheme.backgroundColor())) {
        if (editingNovel != null) {
            // ── Chapter Editor View ──
            val novel = editingNovel!!
            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { editingNovel = null }) { Icon(Icons.Default.ArrowBack, null, tint = currentTheme.textColor()) }
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(novel.title, style = MaterialTheme.typography.titleMedium, color = currentTheme.textColor(), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${novel.chapters.size} chapter(s) · ${novel.status}", style = MaterialTheme.typography.labelSmall, color = currentTheme.subTextColor())
                    }
                    if (novel.status != "published") {
                        Button(onClick = {
                            scope.launch {
                                runCatching { api.publishNovel(novel.id, token) }
                                    .onSuccess {
                                        statusMsg = "Published! Now visible to everyone."
                                        editingNovel = novel.copy(status = "published")
                                    }
                                    .onFailure { errorMsg = it.message ?: "Publish failed" }
                            }
                        }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)), shape = RoundedCornerShape(8.dp)) {
                            Text("Publish", color = Color.White, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Divider(color = currentTheme.textColor().copy(alpha = 0.1f))

                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Chapter list
                    if (novel.chapters.isNotEmpty()) {
                        Text("Published Chapters", style = MaterialTheme.typography.titleSmall, color = currentTheme.textColor(), fontWeight = FontWeight.Bold)
                        novel.chapters.sortedBy { it.chapter_number }.forEach { ch ->
                            Surface(shape = RoundedCornerShape(8.dp), color = currentTheme.cardColor(), modifier = Modifier.fillMaxWidth()) {
                                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(ch.title.ifBlank { "Chapter ${ch.chapter_number}" }, color = currentTheme.textColor(), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                        Text("${ch.content.split("\\s+".toRegex()).size} words", color = currentTheme.subTextColor(), style = MaterialTheme.typography.labelSmall)
                                    }
                                    IconButton(onClick = { ttsController.playText(ch.content, "${novel.title} - ${ch.title}") }) {
                                        Icon(Icons.Default.VolumeUp, null, tint = currentTheme.accentColor(), modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }

                    Divider(color = currentTheme.textColor().copy(alpha = 0.1f))

                    // Add chapter form
                    Text("Write New Chapter", style = MaterialTheme.typography.titleSmall, color = currentTheme.textColor(), fontWeight = FontWeight.Bold)

                    OutlinedTextField(value = chapterTitle, onValueChange = { chapterTitle = it },
                        label = { Text("Chapter Title (optional)", color = currentTheme.subTextColor()) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = currentTheme.accentColor(), unfocusedBorderColor = currentTheme.subTextColor().copy(0.5f), focusedTextColor = currentTheme.textColor(), unfocusedTextColor = currentTheme.textColor(), cursorColor = currentTheme.accentColor()),
                        shape = RoundedCornerShape(8.dp))

                    OutlinedTextField(value = chapterContent, onValueChange = { chapterContent = it },
                        label = { Text("Chapter content...", color = currentTheme.subTextColor()) },
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = currentTheme.accentColor(), unfocusedBorderColor = currentTheme.subTextColor().copy(0.5f), focusedTextColor = currentTheme.textColor(), unfocusedTextColor = currentTheme.textColor(), cursorColor = currentTheme.accentColor(), unfocusedContainerColor = currentTheme.cardColor().copy(0.5f), focusedContainerColor = currentTheme.cardColor().copy(0.5f)),
                        shape = RoundedCornerShape(8.dp))

                    if (statusMsg.isNotBlank()) Text(statusMsg, color = Color(0xFF4CAF50), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    if (errorMsg.isNotBlank()) Text(errorMsg, color = Color.Red, style = MaterialTheme.typography.bodySmall)

                    Button(onClick = {
                        if (chapterContent.isBlank()) { errorMsg = "Content is required."; return@Button }
                        val nextNum = (novel.chapters.maxOfOrNull { it.chapter_number } ?: 0) + 1
                        scope.launch {
                            runCatching { api.addChapter(novel.id, nextNum, chapterTitle.ifBlank { "Chapter $nextNum" }, chapterContent, token) }
                                .onSuccess {
                                    statusMsg = "Chapter $nextNum saved!"
                                    chapterTitle = ""; chapterContent = ""; errorMsg = ""
                                    // Reload novel
                                    runCatching { api.getNovelById(novel.id, token) }.onSuccess { editingNovel = it }
                                }
                                .onFailure { errorMsg = it.message ?: "Failed to save chapter" }
                        }
                    }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = currentTheme.accentColor()), shape = RoundedCornerShape(8.dp)) {
                        Text("Save Chapter", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else if (showCreateForm) {
            // ── Create Novel Form ──
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showCreateForm = false }) { Icon(Icons.Default.ArrowBack, null, tint = currentTheme.textColor()) }
                    Spacer(Modifier.width(8.dp))
                    Text("Create New Novel", style = MaterialTheme.typography.titleLarge, color = currentTheme.textColor(), fontWeight = FontWeight.Bold)
                }

                OutlinedTextField(value = newTitle, onValueChange = { newTitle = it },
                    label = { Text("Novel Title", color = currentTheme.subTextColor()) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = currentTheme.accentColor(), unfocusedBorderColor = currentTheme.subTextColor().copy(0.5f), focusedTextColor = currentTheme.textColor(), unfocusedTextColor = currentTheme.textColor(), cursorColor = currentTheme.accentColor()),
                    shape = RoundedCornerShape(8.dp))

                OutlinedTextField(value = newCoverUrl, onValueChange = { newCoverUrl = it },
                    label = { Text("Cover Image URL (optional)", color = currentTheme.subTextColor()) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = currentTheme.accentColor(), unfocusedBorderColor = currentTheme.subTextColor().copy(0.5f), focusedTextColor = currentTheme.textColor(), unfocusedTextColor = currentTheme.textColor(), cursorColor = currentTheme.accentColor()),
                    shape = RoundedCornerShape(8.dp))

                OutlinedTextField(value = newDescription, onValueChange = { newDescription = it },
                    label = { Text("Description (optional)", color = currentTheme.subTextColor()) },
                    modifier = Modifier.fillMaxWidth().height(100.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = currentTheme.accentColor(), unfocusedBorderColor = currentTheme.subTextColor().copy(0.5f), focusedTextColor = currentTheme.textColor(), unfocusedTextColor = currentTheme.textColor(), cursorColor = currentTheme.accentColor(), unfocusedContainerColor = currentTheme.cardColor().copy(0.5f), focusedContainerColor = currentTheme.cardColor().copy(0.5f)),
                    shape = RoundedCornerShape(8.dp))

                if (errorMsg.isNotBlank()) Text(errorMsg, color = Color.Red, style = MaterialTheme.typography.bodySmall)

                Button(onClick = {
                    if (newTitle.length < 2) { errorMsg = "Title must be at least 2 characters."; return@Button }
                    scope.launch {
                        runCatching { api.createNovel(newTitle, newCoverUrl, newDescription, token) }
                            .onSuccess { novel ->
                                statusMsg = "\"${novel.title}\" created!"
                                newTitle = ""; newCoverUrl = ""; newDescription = ""; errorMsg = ""
                                showCreateForm = false
                                runCatching { api.getMyNovels(token) }.onSuccess { myNovels = it }
                            }
                            .onFailure { errorMsg = it.message ?: "Create failed" }
                    }
                }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = currentTheme.accentColor()), shape = RoundedCornerShape(8.dp)) {
                    Icon(Icons.Default.Create, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Create Novel", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        } else {
            // ── My Novels List ──
            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("My Novels", style = MaterialTheme.typography.titleMedium, color = currentTheme.textColor(), fontWeight = FontWeight.Bold)
                        Text("Create and publish your own stories", color = currentTheme.subTextColor(), style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = { showCreateForm = true }, colors = ButtonDefaults.buttonColors(containerColor = currentTheme.accentColor()), shape = RoundedCornerShape(8.dp)) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("New", color = Color.White)
                    }
                }

                if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = currentTheme.accentColor()) }
                } else if (myNovels.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Outlined.EditNote, null, tint = currentTheme.subTextColor(), modifier = Modifier.size(64.dp))
                            Spacer(Modifier.height(12.dp))
                            Text("No novels yet", color = currentTheme.subTextColor(), style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text("Tap \"New\" to start writing!", color = currentTheme.subTextColor().copy(0.7f), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(myNovels) { novel ->
                            Card(colors = CardDefaults.cardColors(containerColor = currentTheme.cardColor()), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().clickable { editingNovel = novel }) {
                                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(modifier = Modifier.size(60.dp).clip(RoundedCornerShape(8.dp)).background(currentTheme.backgroundColor())) {
                                        if (novel.cover_url.isNotBlank()) AsyncImage(model = novel.cover_url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                        else Icon(Icons.Default.AutoStories, null, modifier = Modifier.align(Alignment.Center).size(28.dp), tint = currentTheme.subTextColor())
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(novel.title, color = currentTheme.textColor(), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("By ${novel.author_name}", color = currentTheme.subTextColor(), style = MaterialTheme.typography.labelSmall)
                                            Surface(shape = RoundedCornerShape(4.dp), color = if (novel.status == "published") Color(0xFF4CAF50).copy(0.2f) else currentTheme.accentColor().copy(0.2f)) {
                                                Text(novel.status.take(1).uppercase() + novel.status.drop(1), color = if (novel.status == "published") Color(0xFF4CAF50) else currentTheme.accentColor(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                            }
                                        }
                                    }
                                    Icon(Icons.Default.ChevronRight, null, tint = currentTheme.subTextColor())
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
