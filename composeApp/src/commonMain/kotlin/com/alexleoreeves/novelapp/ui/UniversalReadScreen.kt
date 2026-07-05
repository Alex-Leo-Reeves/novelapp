package com.alexleoreeves.novelapp.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import coil3.compose.AsyncImage
import com.alexleoreeves.novelapp.audio.KokoroNarrationController
import com.alexleoreeves.novelapp.audio.KokoroVoiceSetupPhase
import com.alexleoreeves.novelapp.data.*
import com.alexleoreeves.novelapp.ui.theme.*
import io.ktor.client.HttpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UniversalReadScreen(
    currentTheme: AppTheme,
    ttsController: KokoroNarrationController,
    requireAuth: (() -> Unit) -> Unit,
    account: com.alexleoreeves.novelapp.platform.SavedUserAccount? = null,
    downloadRepo: LocalDownloadRepository? = null
) {
    val isPlaying = ttsController.isPlaying.collectAsState()
    val isBuffering = ttsController.isBuffering.collectAsState()
    val voiceSetupStatus = ttsController.voiceSetupStatus.collectAsState()

    var pastedText by remember { mutableStateOf("") }
    var urlInput by remember { mutableStateOf("") }
    var activeTab by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(currentTheme.backgroundColor())
    ) {
        // Header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(currentTheme.surfaceColor(), currentTheme.backgroundColor())
                    )
                )
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Column {
                Text(
                    "Universal Read",
                    style = MaterialTheme.typography.headlineLarge,
                    color = currentTheme.textColor(),
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Listen to anything — text, URLs, docs, or AI novels",
                    style = MaterialTheme.typography.bodyMedium,
                    color = currentTheme.subTextColor()
                )
            }
        }

        // Tab selector
        TabRow(
            selectedTabIndex = activeTab,
            containerColor = currentTheme.surfaceColor(),
            contentColor = currentTheme.accentColor()
        ) {
            Tab(selected = activeTab == 0, onClick = { activeTab = 0 }) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.ContentPaste, null,
                        tint = if (activeTab == 0) currentTheme.accentColor() else currentTheme.subTextColor(),
                        modifier = Modifier.size(14.dp))
                    Text("Paste Text",
                        color = if (activeTab == 0) currentTheme.accentColor() else currentTheme.subTextColor())
                }
            }
            Tab(selected = activeTab == 1, onClick = { activeTab = 1 }) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Link, null,
                        tint = if (activeTab == 1) currentTheme.accentColor() else currentTheme.subTextColor(),
                        modifier = Modifier.size(14.dp))
                    Text("From URL",
                        color = if (activeTab == 1) currentTheme.accentColor() else currentTheme.subTextColor())
                }
            }
            Tab(selected = activeTab == 2, onClick = { activeTab = 2 }) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.AutoStories, null,
                        tint = if (activeTab == 2) currentTheme.accentColor() else currentTheme.subTextColor(),
                        modifier = Modifier.size(14.dp))
                    Text("AI Novel",
                        color = if (activeTab == 2) currentTheme.accentColor() else currentTheme.subTextColor())
                }
            }
        }

        var showFilePicker by remember { mutableStateOf(false) }
        var importedFileName by remember { mutableStateOf("") }
        var importedFileContent by remember { mutableStateOf("") }

        com.alexleoreeves.novelapp.ui.components.FilePicker(
            show = showFilePicker,
            onFileSelected = { name, content ->
                showFilePicker = false
                importedFileName = name
                importedFileContent = content
            },
            onDismiss = { showFilePicker = false }
        )

        val setupStatus = voiceSetupStatus.value
        if (isBuffering.value || setupStatus.shouldShow) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(currentTheme.surfaceColor())
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val percent = setupStatus.progressFraction
                val statusMessage = if (isBuffering.value && !setupStatus.shouldShow) {
                    "Preparing narration audio."
                } else {
                    setupStatus.userMessage.ifBlank { "Preparing voice." }
                }
                Text(
                    text = buildString {
                        append(statusMessage)
                        if (percent != null) {
                            append(" ")
                            append((percent * 100f).roundToInt())
                            append("%")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = currentTheme.subTextColor()
                )
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth(0.72f)
                        .height(3.dp)
                        .padding(top = 6.dp),
                    color = currentTheme.accentColor(),
                    trackColor = currentTheme.cardColor().copy(alpha = 0.5f)
                )
                if (setupStatus.phase == KokoroVoiceSetupPhase.Error || setupStatus.phase == KokoroVoiceSetupPhase.Fallback) {
                    TextButton(
                        onClick = {
                            val text = when {
                                activeTab == 0 && pastedText.isNotBlank() -> pastedText
                                activeTab == 1 && urlInput.isNotBlank() -> "Reading content from: $urlInput"
                                else -> ""
                            }
                            if (text.isNotBlank()) {
                                scope.launch { ttsController.playText(GroqTextCleaner.cleanForKokoro(text)) }
                            }
                        }
                    ) { Text("Retry voice setup", color = currentTheme.accentColor()) }
                }
            }
        }

        // ── Tab Content ───────────────────────────────────────────────────
        when (activeTab) {
            0 -> PasteTextTab(pastedText, isPlaying.value, isBuffering.value, currentTheme, requireAuth, ttsController) { pastedText = it }
            1 -> UrlInputTab(urlInput, isPlaying.value, isBuffering.value, currentTheme, requireAuth, ttsController) { urlInput = it }
            2 -> AiNovelCreatorTab(
                currentTheme = currentTheme,
                account = account,
                downloadRepo = downloadRepo,
                requireAuth = requireAuth,
                ttsController = ttsController
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
//  Paste Text Tab
// ─────────────────────────────────────────────────────────────────────────
@Composable
private fun PasteTextTab(
    text: String,
    isPlaying: Boolean,
    isBuffering: Boolean,
    currentTheme: AppTheme,
    requireAuth: (() -> Unit) -> Unit,
    ttsController: KokoroNarrationController,
    onTextChanged: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChanged,
            modifier = Modifier.fillMaxWidth().weight(1f),
            placeholder = { Text("Paste text, article, or story here…", color = currentTheme.subTextColor()) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = currentTheme.accentColor(),
                unfocusedBorderColor = currentTheme.subTextColor().copy(0.5f),
                focusedTextColor = currentTheme.textColor(),
                unfocusedTextColor = currentTheme.textColor(),
                cursorColor = currentTheme.accentColor(),
                unfocusedContainerColor = currentTheme.cardColor().copy(0.5f),
                focusedContainerColor = currentTheme.cardColor().copy(0.5f)
            ),
            shape = RoundedCornerShape(12.dp)
        )
        if (text.isNotEmpty()) {
            Text("${text.split("\\s+".toRegex()).size} words", style = MaterialTheme.typography.labelSmall, color = currentTheme.subTextColor())
        }
        Button(
            onClick = {
                requireAuth {
                    if (isPlaying) ttsController.stop()
                    else if (text.isNotEmpty()) scope.launch { ttsController.playText(GroqTextCleaner.cleanForKokoro(text)) }
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = currentTheme.accentColor()),
            shape = RoundedCornerShape(12.dp),
            enabled = text.isNotEmpty() || isPlaying
        ) {
            Icon(if (isBuffering) Icons.Default.GraphicEq else if (isPlaying) Icons.Default.StopCircle else Icons.Default.PlayCircle, null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (isBuffering) "Preparing Voice" else if (isPlaying) "Stop Reading" else "Start Reading", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
//  URL Input Tab
// ─────────────────────────────────────────────────────────────────────────
@Composable
private fun UrlInputTab(
    url: String,
    isPlaying: Boolean,
    isBuffering: Boolean,
    currentTheme: AppTheme,
    requireAuth: (() -> Unit) -> Unit,
    ttsController: KokoroNarrationController,
    onUrlChanged: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedTextField(
            value = url,
            onValueChange = onUrlChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Article / Blog URL", color = currentTheme.subTextColor()) },
            placeholder = { Text("https://...", color = currentTheme.subTextColor()) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = currentTheme.accentColor(),
                unfocusedBorderColor = currentTheme.subTextColor().copy(0.5f),
                focusedTextColor = currentTheme.textColor(),
                unfocusedTextColor = currentTheme.textColor(),
                cursorColor = currentTheme.accentColor()
            ),
            shape = RoundedCornerShape(12.dp),
            leadingIcon = { Icon(Icons.Default.Link, null, tint = currentTheme.accentColor()) }
        )
        Button(
            onClick = {
                requireAuth {
                    if (isPlaying) ttsController.stop()
                    else scope.launch { ttsController.playText(GroqTextCleaner.cleanForKokoro("Reading content from: $url")) }
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = currentTheme.accentColor()),
            shape = RoundedCornerShape(12.dp),
            enabled = isPlaying || (url.isNotEmpty() && url.startsWith("http"))
        ) {
            Icon(if (isPlaying) Icons.Default.StopCircle else Icons.Default.Download, null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (isPlaying) "Stop Reading" else "Fetch & Read", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
        }
        Surface(shape = RoundedCornerShape(12.dp), color = currentTheme.cardColor()) {
            Row(modifier = Modifier.padding(14.dp)) {
                Icon(Icons.Default.Info, null, tint = currentTheme.accentColor(), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("Paste any article/blog URL. Text will be extracted and read using AI narration.", style = MaterialTheme.typography.bodyMedium, color = currentTheme.subTextColor())
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
//  AI Novel Creator Tab
// ─────────────────────────────────────────────────────────────────────────
@Composable
private fun AiNovelCreatorTab(
    currentTheme: AppTheme,
    account: com.alexleoreeves.novelapp.platform.SavedUserAccount?,
    downloadRepo: LocalDownloadRepository?,
    requireAuth: (() -> Unit) -> Unit,
    ttsController: KokoroNarrationController
) {
    val token = account?.authToken.orEmpty()
    val api = remember { AiNovelApi() }

    var innerTab by remember { mutableStateOf(0) } // 0=Community, 1=Create
    var communityNovels by remember { mutableStateOf<List<AiNovel>>(emptyList()) }
    var isLoadingCommunity by remember { mutableStateOf(false) }
    var quota by remember { mutableStateOf<AiQuota?>(null) }
    var selectedSources by remember { mutableStateOf<List<UnifiedSearchResult>>(emptyList()) }
    var userDescription by remember { mutableStateOf("") }
    var isLongNovel by remember { mutableStateOf(false) }
    var isGenerating by remember { mutableStateOf(false) }
    var generationProgress by remember { mutableStateOf(0f) }
    var generationStatusText by remember { mutableStateOf("") }
    var generatedResult by remember { mutableStateOf<AiNovelCompleteResponse?>(null) }
    var generationError by remember { mutableStateOf("") }
    var showSourcePicker by remember { mutableStateOf(false) }
    var searchPickerQuery by remember { mutableStateOf("") }
    var searchPickerResults by remember { mutableStateOf<List<UnifiedSearchResult>>(emptyList()) }
    var isSearchingPicker by remember { mutableStateOf(false) }
    var feedbackMessage by remember { mutableStateOf("") }
    var activeReadingNovel by remember { mutableStateOf<AiNovel?>(null) }

    val scope = rememberCoroutineScope()
    val httpClient = remember { HttpClient() }
    val tmdbScraper = remember { TMDBMovieScraper(httpClient) }

    LaunchedEffect(account) {
        if (token.isNotBlank()) {
            runCatching { api.fetchQuota(token) }.onSuccess { quota = it }
        }
    }
    LaunchedEffect(innerTab, account) {
        if (innerTab == 0 && token.isNotBlank()) {
            isLoadingCommunity = true
            runCatching { api.fetchCommunityNovels(1, token) }.onSuccess { communityNovels = it }
            isLoadingCommunity = false
        }
    }

    if (activeReadingNovel != null) {
        val novel = activeReadingNovel!!
        Box(modifier = Modifier.fillMaxSize().background(currentTheme.backgroundColor())) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp).statusBarsPadding(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { activeReadingNovel = null }) {
                        Icon(Icons.Default.ArrowBack, null, tint = currentTheme.textColor())
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(novel.title, style = MaterialTheme.typography.titleMedium, color = currentTheme.textColor(), fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { ttsController.playText(novel.content, novel.title) }) {
                        Icon(Icons.Default.VolumeUp, null, tint = currentTheme.accentColor())
                    }
                }
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)
                ) {
                    if (novel.cover_url.isNotBlank()) {
                        Box(modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(12.dp)).background(currentTheme.cardColor())) {
                            AsyncImage(model = novel.cover_url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    Text("By ${novel.author_name}", style = MaterialTheme.typography.labelSmall, color = currentTheme.subTextColor())
                    Divider(color = currentTheme.textColor().copy(alpha = 0.1f), modifier = Modifier.padding(vertical = 12.dp))
                    Text(novel.content, style = MaterialTheme.typography.bodyLarge, color = currentTheme.textColor())
                    Spacer(Modifier.height(48.dp))
                }
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Tab headers
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                .background(currentTheme.cardColor(), RoundedCornerShape(10.dp)).padding(4.dp)
        ) {
            listOf("Community", "Create Mashup").forEachIndexed { idx, title ->
                Box(
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp))
                        .background(if (innerTab == idx) currentTheme.accentColor() else Color.Transparent)
                        .clickable {
                            if (idx == 1) requireAuth { innerTab = idx }
                            else innerTab = idx
                        }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(title, color = if (innerTab == idx) Color.White else currentTheme.textColor(), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        // Quota bar
        quota?.let { q ->
            val remainingText = if (q.limitShort == -1) "Unlimited creations" else "Short: ${q.limitShort} · Long: ${q.limitLong} left"
            Text(remainingText, color = currentTheme.accentColor(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
        }

        if (innerTab == 0) {
            // ── Community Tab ─────────────────────────────────────────────
            if (isLoadingCommunity && communityNovels.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = currentTheme.accentColor())
                }
            } else if (communityNovels.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No community AI novels yet. Be the first to publish!", color = currentTheme.subTextColor(), textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
                }
            } else {
                LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(communityNovels) { novel ->
                        Card(colors = CardDefaults.cardColors(containerColor = currentTheme.cardColor()), shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().clickable { activeReadingNovel = novel }) {
                            Column {
                                Box(modifier = Modifier.fillMaxWidth().height(160.dp).background(Color.DarkGray)) {
                                    if (novel.cover_url.isNotBlank()) {
                                        AsyncImage(model = novel.cover_url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                    } else {
                                        Icon(Icons.Default.AutoStories, null, modifier = Modifier.align(Alignment.Center).size(40.dp), tint = Color.Gray)
                                    }
                                }
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text(novel.title, color = currentTheme.textColor(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("By ${novel.author_name}", color = currentTheme.subTextColor(), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${novel.word_count} words · ${novel.type.uppercase()}", color = currentTheme.accentColor(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // ── Create Mashup Tab ─────────────────────────────────────────
            if (isGenerating) {
                Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(progress = { generationProgress }, color = currentTheme.accentColor(), strokeWidth = 6.dp, modifier = Modifier.size(80.dp))
                    Spacer(Modifier.height(24.dp))
                    Text("Generating Mashup Novel...", style = MaterialTheme.typography.titleLarge, color = currentTheme.textColor(), fontWeight = FontWeight.Bold)
                    Text(generationStatusText, style = MaterialTheme.typography.bodyMedium, color = currentTheme.subTextColor(), textAlign = TextAlign.Center)
                }
            } else if (generatedResult != null) {
                val result = generatedResult!!
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Cover
                    Box(modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(16.dp)).background(currentTheme.cardColor())) {
                        AsyncImage(model = result.coverUrl, contentDescription = "Cover", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    Text(result.title, style = MaterialTheme.typography.headlineMedium, color = currentTheme.textColor(), fontWeight = FontWeight.Black)
                    Text("Generated with Gemini 2.5 Flash", style = MaterialTheme.typography.labelSmall, color = currentTheme.subTextColor())

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            scope.launch {
                                try {
                                    downloadRepo?.addItem(DownloadedItem(id = "ai_" + com.alexleoreeves.novelapp.platform.currentTimeMillis(), title = result.title, coverUrl = result.coverUrl, type = "NOVEL", sourceName = "AI"))
                                    downloadRepo?.addChapter(DownloadedChapter(parentId = result.title, chapterNumber = 1, chapterTitle = "Crossover Fusion", localFilePath = ""))
                                    feedbackMessage = "Saved offline!"
                                } catch (e: Exception) { feedbackMessage = "Save failed: ${e.message}" }
                            }
                        }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = currentTheme.accentColor())) {
                            Icon(Icons.Default.Download, null)
                            Spacer(Modifier.width(4.dp))
                            Text("Save Offline")
                        }
                        Button(onClick = {
                            scope.launch {
                                try {
                                    api.publishNovel(result.title, result.coverPrompt, result.coverUrl, result.content, if (isLongNovel) "long" else "short", selectedSources, "Crossover", token)
                                    feedbackMessage = "Published to community!"
                                } catch (e: Exception) { feedbackMessage = "Publish failed: ${e.message}" }
                            }
                        }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))) {
                            Icon(Icons.Default.CloudUpload, null)
                            Spacer(Modifier.width(4.dp))
                            Text("Publish")
                        }
                    }
                    if (feedbackMessage.isNotBlank()) {
                        Text(feedbackMessage, color = currentTheme.accentColor(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    }
                    Button(onClick = { ttsController.playText(result.content, result.title) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = currentTheme.cardColor())) {
                        Icon(Icons.Default.VolumeUp, null, tint = currentTheme.textColor())
                        Spacer(Modifier.width(6.dp))
                        Text("Listen with Kokoro", color = currentTheme.textColor())
                    }
                    Divider(color = currentTheme.textColor().copy(alpha = 0.1f))
                    Text(result.content, style = MaterialTheme.typography.bodyLarge, color = currentTheme.textColor())
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { generatedResult = null; feedbackMessage = "" }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = currentTheme.cardColor())) {
                        Text("Create Another", color = currentTheme.textColor())
                    }
                }
            } else {
                // Config form
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Type selector
                    Card(colors = CardDefaults.cardColors(containerColor = currentTheme.cardColor()), shape = RoundedCornerShape(12.dp)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("1. Choose Type", color = currentTheme.textColor(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(10.dp))
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Box(modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                                    .background(if (!isLongNovel) currentTheme.accentColor().copy(alpha = 0.2f) else Color.Transparent)
                                    .border(1.dp, if (!isLongNovel) currentTheme.accentColor() else currentTheme.textColor().copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                                    .clickable { isLongNovel = false }.padding(10.dp), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("Short Novel", color = currentTheme.textColor(), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                                        Text("Summary-based", color = currentTheme.subTextColor(), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                Spacer(Modifier.width(10.dp))
                                Box(modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                                    .background(if (isLongNovel) currentTheme.accentColor().copy(alpha = 0.2f) else Color.Transparent)
                                    .border(1.dp, if (isLongNovel) currentTheme.accentColor() else currentTheme.textColor().copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                                    .clickable { isLongNovel = true }.padding(10.dp), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("Long Novel", color = currentTheme.textColor(), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                                        Text("Chunk-based", color = currentTheme.subTextColor(), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                    // Sources
                    Card(colors = CardDefaults.cardColors(containerColor = currentTheme.cardColor()), shape = RoundedCornerShape(12.dp)) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("2. Select Sources (max 2)", color = currentTheme.textColor(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            if (selectedSources.isEmpty()) {
                                Text("No sources added.", color = currentTheme.subTextColor(), style = MaterialTheme.typography.bodySmall)
                            }
                            selectedSources.forEach { src ->
                                Row(modifier = Modifier.fillMaxWidth().background(currentTheme.backgroundColor().copy(alpha = 0.5f), RoundedCornerShape(6.dp)).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    AsyncImage(model = src.coverUrl, contentDescription = null, modifier = Modifier.size(32.dp).clip(RoundedCornerShape(4.dp)), contentScale = ContentScale.Crop)
                                    Spacer(Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(src.title, color = currentTheme.textColor(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(src.sourceName, color = currentTheme.subTextColor(), style = MaterialTheme.typography.labelSmall)
                                    }
                                    IconButton(onClick = { selectedSources = selectedSources - src }, modifier = Modifier.size(24.dp)) {
                                        Icon(Icons.Default.Delete, null, tint = Color.Red, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                            if (selectedSources.size < 2) {
                                Button(onClick = { showSourcePicker = true }, colors = ButtonDefaults.buttonColors(containerColor = currentTheme.accentColor())) {
                                    Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Add Source", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                    // Description
                    Card(colors = CardDefaults.cardColors(containerColor = currentTheme.cardColor()), shape = RoundedCornerShape(12.dp)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("3. Plot Direction", color = currentTheme.textColor(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            TextField(value = userDescription, onValueChange = { userDescription = it },
                                placeholder = { Text("Character/setting notes...") },
                                modifier = Modifier.fillMaxWidth().height(100.dp),
                                colors = TextFieldDefaults.colors(),
                                shape = RoundedCornerShape(8.dp))
                        }
                    }
                    if (generationError.isNotBlank()) {
                        Text(generationError, color = Color.Red, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = {
                        if (selectedSources.size < 1) { generationError = "Select at least 1 source."; return@Button }
                        generationError = ""
                        isGenerating = true
                        generationProgress = 0.05f
                        generationStatusText = "Initiating..."
                        scope.launch {
                            runCatching { api.generateStart(if (isLongNovel) "long" else "short", token) }
                                .onSuccess {
                                    try {
                                        val type = if (isLongNovel) "long" else "short"
                                        if (!isLongNovel) {
                                            generationProgress = 0.5f
                                            generationStatusText = "Generating crossover story..."
                                            val comp = api.generateComplete("short", selectedSources, userDescription, emptyList(), token)
                                            generatedResult = comp
                                            quota = api.fetchQuota(token)
                                        } else {
                                            generationStatusText = "Gathering chapters..."
                                            val allChunks = mutableListOf<Pair<String, String>>()
                                            selectedSources.forEach { src ->
                                                if (src.detailPageUrl.startsWith("tmdb://")) {
                                                    val parts = src.detailPageUrl.removePrefix("tmdb://").split("/")
                                                    val mediaType = parts.getOrNull(0) ?: "tv"
                                                    val tmdbId = parts.getOrNull(1) ?: ""
                                                    if (mediaType == "tv") {
                                                        val eps = tmdbScraper.fetchTVSeasonsAndEpisodes(tmdbId)
                                                        eps.take(3).forEach { ep -> allChunks.add(Pair("Episode ${ep.episodeNumber}: ${ep.title}. Plot: ${src.synopsis}", src.title)) }
                                                    } else { allChunks.add(Pair(src.synopsis, src.title)) }
                                                } else {
                                                    downloadRepo?.getChaptersFor(src.id)?.take(2)?.forEach { ch ->
                                                        allChunks.add(Pair("Chapter ${ch.chapterNumber}: ${ch.chapterTitle}", src.title))
                                                    }
                                                }
                                            }
                                            if (allChunks.isEmpty()) allChunks.add(Pair("No chapters available.", "Lore Reference"))
                                            val summaries = mutableListOf<String>()
                                            for (i in allChunks.indices) {
                                                generationProgress = 0.1f + ((i.toFloat() / allChunks.size) * 0.6f)
                                                generationStatusText = "Processing ${i + 1} of ${allChunks.size}..."
                                                val chunkRes = api.generateChunk(allChunks[i].first.take(5000), allChunks[i].second, token)
                                                summaries.add(chunkRes.summary)
                                                if (i < allChunks.size - 1) { delay(3000) }
                                            }
                                            generationProgress = 0.85f
                                            generationStatusText = "Assembling final novel..."
                                            val comp = api.generateComplete("long", selectedSources, userDescription, summaries, token)
                                            generatedResult = comp
                                            quota = api.fetchQuota(token)
                                        }
                                    } catch (e: Exception) { generationError = e.message ?: "Pipeline failed." }
                                }
                                .onFailure { generationError = it.message ?: "Failed to start." }
                            isGenerating = false
                        }
                    }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = currentTheme.accentColor())) {
                        Icon(Icons.Default.Bolt, null)
                        Spacer(Modifier.width(6.dp))
                        Text("GENERATE NOVEL", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // ── Source Picker Dialog ──────────────────────────────────────────────
    if (showSourcePicker) {
        AlertDialog(
            onDismissRequest = { showSourcePicker = false },
            title = { Text("Select Source", color = currentTheme.textColor()) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(
                        value = searchPickerQuery,
                        onValueChange = { searchPickerQuery = it },
                        placeholder = { Text("Search...") },
                        singleLine = true,
                        colors = TextFieldDefaults.colors()
                    )
                    LazyColumn(modifier = Modifier.height(200.dp)) {
                        items(searchPickerResults) { item ->
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable {
                                        if (selectedSources.none { it.id == item.id }) selectedSources = selectedSources + item
                                        showSourcePicker = false
                                    }
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AsyncImage(model = item.coverUrl, contentDescription = null, modifier = Modifier.size(36.dp).clip(RoundedCornerShape(4.dp)), contentScale = ContentScale.Crop)
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(item.title, color = currentTheme.textColor(), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(item.sourceName, color = currentTheme.subTextColor(), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = {
                            isSearchingPicker = true
                            scope.launch {
                                runCatching { tmdbScraper.search(searchPickerQuery) }
                                    .onSuccess { searchPickerResults = it.map { UnifiedSearchResult(id = "tmdb_${if (it.type == "MOVIE") "movie" else "tv"}_${it.id}", title = it.title, coverUrl = it.coverUrl, detailPageUrl = "tmdb://${if (it.type == "MOVIE") "movie" else "tv"}/${it.id}", sourceName = "TMDB", synopsis = it.description) } }
                                isSearchingPicker = false
                            }
                        }, colors = ButtonDefaults.buttonColors(containerColor = currentTheme.accentColor())) {
                            if (isSearchingPicker) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp))
                            else Text("Search TMDB")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSourcePicker = false }) { Text("Close", color = currentTheme.accentColor()) } },
            containerColor = currentTheme.surfaceColor()
        )
    }
}
