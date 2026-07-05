package com.alexleoreeves.novelapp.ui

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.alexleoreeves.novelapp.data.*
import com.alexleoreeves.novelapp.audio.KokoroNarrationController
import com.alexleoreeves.novelapp.platform.currentTimeMillis
import com.alexleoreeves.novelapp.platform.SavedUserAccount
import com.alexleoreeves.novelapp.ui.theme.*
import io.ktor.client.HttpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiNovelScreen(
    currentTheme: AppTheme,
    account: SavedUserAccount?,
    downloadRepo: LocalDownloadRepository,
    requireAuth: (() -> Unit) -> Unit,
    ttsController: KokoroNarrationController,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val api = remember { AiNovelApi() }
    val httpClient = remember { HttpClient() }
    val tmdbScraper = remember { TMDBMovieScraper(httpClient) }

    var selectedTab by remember { mutableStateOf(0) } // 0 = Community, 1 = Create
    val token = account?.authToken.orEmpty()

    // Community tab state
    var communityNovels by remember { mutableStateOf<List<AiNovel>>(emptyList()) }
    var isLoadingCommunity by remember { mutableStateOf(false) }
    var communityPage by remember { mutableStateOf(1) }
    var hasMoreCommunity by remember { mutableStateOf(true) }

    // Quota state
    var quota by remember { mutableStateOf<AiQuota?>(null) }
    var isLoadingQuota by remember { mutableStateOf(false) }

    // Selected reading novel state
    var activeNovelReading by remember { mutableStateOf<AiNovel?>(null) }

    // Create state
    var isLongNovel by remember { mutableStateOf(false) }
    var selectedSources by remember { mutableStateOf<List<UnifiedSearchResult>>(emptyList()) }
    var userDescription by remember { mutableStateOf("") }
    
    // Scraper/picker state
    var showSourcePicker by remember { mutableStateOf(false) }
    var searchPickerQuery by remember { mutableStateOf("") }
    var searchPickerResults by remember { mutableStateOf<List<UnifiedSearchResult>>(emptyList()) }
    var isSearchingPicker by remember { mutableStateOf(false) }
    var pickerTab by remember { mutableStateOf(0) } // 0 = Downloaded Novels, 1 = Search TMDB

    // Generation state
    var isGenerating by remember { mutableStateOf(false) }
    var generationStatusText by remember { mutableStateOf("") }
    var generationProgress by remember { mutableStateOf(0f) }
    var generationCountdownSeconds by remember { mutableStateOf(0) }
    var generatedResult by remember { mutableStateOf<AiNovelCompleteResponse?>(null) }
    var generationError by remember { mutableStateOf("") }

    // Publish/Save state
    var isPublishing by remember { mutableStateOf(false) }
    var isSavingLocally by remember { mutableStateOf(false) }
    var feedbackMessage by remember { mutableStateOf("") }

    // Load quota and initial community novels
    LaunchedEffect(account) {
        if (account != null) {
            isLoadingQuota = true
            runCatching { api.fetchQuota(token) }
                .onSuccess { quota = it }
                .onFailure { it.printStackTrace() }
            isLoadingQuota = false
        }
    }

    LaunchedEffect(selectedTab, account) {
        if (selectedTab == 0 && account != null) {
            isLoadingCommunity = true
            runCatching { api.fetchCommunityNovels(1, token) }
                .onSuccess {
                    communityNovels = it
                    communityPage = 1
                    hasMoreCommunity = it.isNotEmpty()
                }
                .onFailure { it.printStackTrace() }
            isLoadingCommunity = false
        }
    }

    // Main layout
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(currentTheme.backgroundColor())
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = currentTheme.textColor()
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "AI Crossover Creator",
                        style = MaterialTheme.typography.titleLarge,
                        color = currentTheme.textColor(),
                        fontWeight = FontWeight.Bold
                    )
                }

                // Quota badge
                quota?.let { q ->
                    Surface(
                        color = currentTheme.accentColor().copy(alpha = 0.15f),
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        val remainingText = if (q.limitShort == -1) {
                            "Unlimited Creations"
                        } else {
                            "Short: ${q.limitShort} · Long: ${q.limitLong} left"
                        }
                        Text(
                            remainingText,
                            color = currentTheme.accentColor(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            // Tab selectors
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .background(currentTheme.cardColor(), RoundedCornerShape(12.dp))
                    .padding(4.dp)
            ) {
                listOf("Community", "Create Mashup").forEachIndexed { index, title ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (selectedTab == index) currentTheme.accentColor()
                                else Color.Transparent
                            )
                            .clickable {
                                if (index == 1) {
                                    requireAuth { selectedTab = index }
                                } else {
                                    selectedTab = index
                                }
                            }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            title,
                            color = if (selectedTab == index) Color.White else currentTheme.textColor(),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // Tabs Content
            Box(modifier = Modifier.weight(1f)) {
                if (selectedTab == 0) {
                    // Community Tab
                    if (isLoadingCommunity && communityNovels.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = currentTheme.accentColor())
                        }
                    } else if (communityNovels.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "No community AI novels yet. Be the first to publish one!",
                                color = currentTheme.subTextColor(),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(24.dp)
                            )
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            items(communityNovels) { novel ->
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = currentTheme.cardColor()),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { activeNovelReading = novel }
                                ) {
                                    Column {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(180.dp)
                                                .background(Color.DarkGray)
                                        ) {
                                            if (novel.cover_url.isNotBlank()) {
                                                AsyncImage(
                                                    model = novel.cover_url,
                                                    contentDescription = novel.title,
                                                    modifier = Modifier.fillMaxSize(),
                                                    contentScale = ContentScale.Crop
                                                )
                                            } else {
                                                Icon(
                                                    Icons.Default.AutoStories,
                                                    contentDescription = null,
                                                    modifier = Modifier.align(Alignment.Center).size(48.dp),
                                                    tint = Color.Gray
                                                )
                                            }
                                        }

                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Text(
                                                novel.title,
                                                color = currentTheme.textColor(),
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                "By ${novel.author_name}",
                                                color = currentTheme.subTextColor(),
                                                style = MaterialTheme.typography.labelSmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                "${novel.word_count} words · ${novel.type.uppercase()}",
                                                color = currentTheme.accentColor(),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Create Mashup Tab
                    if (isGenerating) {
                        // Generation Loading Screen
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(
                                progress = { generationProgress },
                                color = currentTheme.accentColor(),
                                strokeWidth = 6.dp,
                                modifier = Modifier.size(80.dp)
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                "Generating Mashup Novel...",
                                style = MaterialTheme.typography.titleLarge,
                                color = currentTheme.textColor(),
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                generationStatusText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = currentTheme.subTextColor(),
                                textAlign = TextAlign.Center
                            )
                            if (generationCountdownSeconds > 0) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    "Estimated time remaining: ${generationCountdownSeconds / 60}m ${generationCountdownSeconds % 60}s",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = currentTheme.accentColor(),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    } else if (generatedResult != null) {
                        // Success Result view
                        val result = generatedResult!!
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            // Cover Preview
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(280.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(currentTheme.cardColor())
                            ) {
                                AsyncImage(
                                    model = result.coverUrl,
                                    contentDescription = "Cover Art",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                result.title,
                                style = MaterialTheme.typography.headlineMedium,
                                color = currentTheme.textColor(),
                                fontWeight = FontWeight.Black
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Generated with Gemini 2.5 Flash",
                                style = MaterialTheme.typography.labelSmall,
                                color = currentTheme.subTextColor()
                            )

                            // Actions
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            isSavingLocally = true
                                            try {
                                                val novelId = "ai_" + currentTimeMillis()
                                                downloadRepo.addItem(
                                                    DownloadedItem(
                                                        id = novelId,
                                                        title = result.title,
                                                        coverUrl = result.coverUrl,
                                                        type = "NOVEL",
                                                        sourceName = "AI Generated"
                                                    )
                                                )
                                                val savedPath = saveDownloadedText(novelId, 1, result.content)
                                                downloadRepo.addChapter(
                                                    DownloadedChapter(
                                                        parentId = novelId,
                                                        chapterNumber = 1,
                                                        chapterTitle = "Crossover Fusion",
                                                        localFilePath = savedPath
                                                    )
                                                )
                                                feedbackMessage = "Saved offline successfully!"
                                            } catch (e: Exception) {
                                                feedbackMessage = "Save failed: ${e.message}"
                                            }
                                            isSavingLocally = false
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = currentTheme.accentColor())
                                ) {
                                    if (isSavingLocally) {
                                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp))
                                    } else {
                                        Icon(Icons.Outlined.Download, contentDescription = null)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Save Offline")
                                    }
                                }

                                Button(
                                    onClick = {
                                        scope.launch {
                                            isPublishing = true
                                            try {
                                                api.publishNovel(
                                                    title = result.title,
                                                    coverPrompt = result.coverPrompt,
                                                    coverUrl = result.coverUrl,
                                                    content = result.content,
                                                    type = if (isLongNovel) "long" else "short",
                                                    sourceNovels = selectedSources,
                                                    genres = "Crossover",
                                                    token = token
                                                )
                                                feedbackMessage = "Published to community!"
                                            } catch (e: Exception) {
                                                feedbackMessage = "Publish failed: ${e.message}"
                                            }
                                            isPublishing = false
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                                ) {
                                    if (isPublishing) {
                                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp))
                                    } else {
                                        Icon(Icons.Outlined.CloudUpload, contentDescription = null)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Publish")
                                    }
                                }
                            }

                            // Feedback Banner
                            if (feedbackMessage.isNotBlank()) {
                                Text(
                                    feedbackMessage,
                                    color = currentTheme.accentColor(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(bottom = 12.dp)
                                )
                            }

                            // Speech trigger
                            Button(
                                onClick = {
                                    ttsController.playText(result.content, result.title)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = currentTheme.cardColor())
                            ) {
                                Icon(Icons.Default.VolumeUp, contentDescription = null, tint = currentTheme.textColor())
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Listen with Kokoro Speech", color = currentTheme.textColor())
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                            Divider(color = currentTheme.textColor().copy(alpha = 0.1f))
                            Spacer(modifier = Modifier.height(16.dp))

                            // Text Content
                            Text(
                                result.content,
                                style = MaterialTheme.typography.bodyLarge,
                                color = currentTheme.textColor(),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(32.dp))
                            Button(
                                onClick = {
                                    generatedResult = null
                                    feedbackMessage = ""
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = currentTheme.cardColor())
                            ) {
                                Text("Create Another Mashup", color = currentTheme.textColor())
                            }
                        }
                    } else {
                        // Configuration Form
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // Novel Type Selector Card
                            Card(
                                colors = CardDefaults.cardColors(containerColor = currentTheme.cardColor()),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text(
                                        "1. Choose Generation Type",
                                        color = currentTheme.textColor(),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Row(modifier = Modifier.fillMaxWidth()) {
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(
                                                    if (!isLongNovel) currentTheme.accentColor().copy(alpha = 0.2f)
                                                    else Color.Transparent
                                                )
                                                .border(
                                                    1.dp,
                                                    if (!isLongNovel) currentTheme.accentColor()
                                                    else currentTheme.textColor().copy(alpha = 0.1f),
                                                    RoundedCornerShape(8.dp)
                                                )
                                                .clickable { isLongNovel = false }
                                                .padding(12.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text("Short Novel", color = currentTheme.textColor(), fontWeight = FontWeight.Bold)
                                                Text("Quick summary reference", color = currentTheme.subTextColor(), style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(
                                                    if (isLongNovel) currentTheme.accentColor().copy(alpha = 0.2f)
                                                    else Color.Transparent
                                                )
                                                .border(
                                                    1.dp,
                                                    if (isLongNovel) currentTheme.accentColor()
                                                    else currentTheme.textColor().copy(alpha = 0.1f),
                                                    RoundedCornerShape(8.dp)
                                                )
                                                .clickable { isLongNovel = true }
                                                .padding(12.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text("Long Novel", color = currentTheme.textColor(), fontWeight = FontWeight.Bold)
                                                Text("Reads actual books/episodes", color = currentTheme.subTextColor(), style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                }
                            }

                            // Sources Selector
                            Card(
                                colors = CardDefaults.cardColors(containerColor = currentTheme.cardColor()),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(
                                        "2. Select Fused Universes (Max 3)",
                                        color = currentTheme.textColor(),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )

                                    if (selectedSources.isEmpty()) {
                                        Text(
                                            "No source universes added yet.",
                                            color = currentTheme.subTextColor(),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    } else {
                                        selectedSources.forEach { src ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(
                                                        currentTheme.backgroundColor().copy(alpha = 0.5f),
                                                        RoundedCornerShape(8.dp)
                                                    )
                                                    .padding(8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                AsyncImage(
                                                    model = src.coverUrl,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)),
                                                    contentScale = ContentScale.Crop
                                                )
                                                Spacer(modifier = Modifier.width(10.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        src.title,
                                                        color = currentTheme.textColor(),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        fontWeight = FontWeight.Bold,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        src.sourceName,
                                                        color = currentTheme.subTextColor(),
                                                        style = MaterialTheme.typography.labelSmall
                                                    )
                                                }
                                                IconButton(onClick = { selectedSources = selectedSources - src }) {
                                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red)
                                                }
                                            }
                                        }
                                    }

                                    if (selectedSources.size < 3) {
                                        Button(
                                            onClick = {
                                                showSourcePicker = true
                                                searchPickerResults = emptyList()
                                                searchPickerQuery = ""
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = ButtonDefaults.buttonColors(containerColor = currentTheme.accentColor())
                                        ) {
                                            Icon(Icons.Default.Add, contentDescription = null)
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Add Universe / Novel Source")
                                        }
                                    }
                                }
                            }

                            // Custom Instructions/Description
                            Card(
                                colors = CardDefaults.cardColors(containerColor = currentTheme.cardColor()),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text(
                                        "3. Add Your Plot Direction / Description",
                                        color = currentTheme.textColor(),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    TextField(
                                        value = userDescription,
                                        onValueChange = { userDescription = it },
                                        placeholder = {
                                            Text("Include specific characters, setting descriptions, or plot twists you want the AI to fuse together...")
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(120.dp),
                                        colors = TextFieldDefaults.colors(),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                }
                            }

                            // Error block
                            if (generationError.isNotBlank()) {
                                Text(
                                    generationError,
                                    color = Color.Red,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            // Generate Button
                            Button(
                                onClick = {
                                    if (selectedSources.size < 2) {
                                        generationError = "Please select at least 2 novels or shows to generate a mashup."
                                        return@Button
                                    }
                                    generationError = ""
                                    isGenerating = true
                                    generationProgress = 0.05f
                                    generationStatusText = "Initiating server connection & checking quota..."
                                    
                                    scope.launch {
                                        runCatching {
                                            val type = if (isLongNovel) "long" else "short"
                                            api.generateStart(type, token)
                                        }.onSuccess {
                                            // Quota deducted successfully. Start actual generation pipeline.
                                            scope.launch {
                                                try {
                                                    val type = if (isLongNovel) "long" else "short"
                                                    if (!isLongNovel) {
                                                        // Short novel pipeline
                                                        generationProgress = 0.5f
                                                        generationStatusText = "Generating crossover story using summaries..."
                                                        val comp = api.generateComplete(
                                                            type = "short",
                                                            sourceNovels = selectedSources,
                                                            userDescription = userDescription,
                                                            profiles = emptyList(),
                                                            token = token
                                                        )
                                                        generatedResult = comp
                                                        // Refresh quota
                                                        quota = api.fetchQuota(token)
                                                    } else {
                                                        // Long novel pipeline - Chunk-based Processing
                                                        val allChunks = mutableListOf<Pair<String, String>>()
                                                        generationStatusText = "Gathering chapters and summaries..."
                                                        
                                                        selectedSources.forEach { src ->
                                                            if (src.detailPageUrl.startsWith("tmdb://")) {
                                                                // TMDB video: fetch episodes and use episode overviews
                                                                val parts = src.detailPageUrl.removePrefix("tmdb://").split("/")
                                                                val mediaType = parts.getOrNull(0) ?: "tv"
                                                                val tmdbId = parts.getOrNull(1) ?: ""
                                                                
                                                                if (mediaType == "tv") {
                                                                    val eps = tmdbScraper.fetchTVSeasonsAndEpisodes(tmdbId)
                                                                    eps.take(5).forEach { ep ->
                                                                        if (ep.title.isNotBlank()) {
                                                                            allChunks.add(
                                                                                Pair(
                                                                                    "Episode ${ep.episodeNumber}: ${ep.title}. Plot: ${src.synopsis}",
                                                                                    "${src.title} (TV Show)"
                                                                                )
                                                                            )
                                                                        }
                                                                    }
                                                                } else {
                                                                    allChunks.add(Pair(src.synopsis, "${src.title} (Movie)"))
                                                                }
                                                            } else {
                                                                // Local Downloaded Novel: read chapter files
                                                                val chaps = downloadRepo.getChaptersFor(src.id)
                                                                chaps.take(3).forEach { ch ->
                                                                    val txt = loadDownloadedText(ch.localFilePath)
                                                                    if (txt.isNotBlank()) {
                                                                        allChunks.add(
                                                                            Pair(
                                                                                "Chapter ${ch.chapterNumber}: ${ch.chapterTitle}. Content:\n$txt",
                                                                                src.title
                                                                            )
                                                                        )
                                                                    }
                                                                }
                                                            }
                                                        }

                                                        if (allChunks.isEmpty()) {
                                                            allChunks.add(Pair("No chapters available.", "Lore Reference"))
                                                        }

                                                        val totalSteps = allChunks.size
                                                        val summaries = mutableListOf<String>()

                                                        for (i in allChunks.indices) {
                                                            val currentChunk = allChunks[i]
                                                            generationProgress = 0.1f + ((i.toFloat() / totalSteps) * 0.6f)
                                                            generationStatusText = "Reading chunk ${i + 1} of $totalSteps from ${currentChunk.second}..."
                                                            generationCountdownSeconds = (totalSteps - i) * 20

                                                            val chunkRes = api.generateChunk(
                                                                chunkText = currentChunk.first.take(5000), // Enforce size limit
                                                                sourceName = currentChunk.second,
                                                                token = token
                                                            )
                                                            summaries.add(chunkRes.summary)

                                                            if (i < totalSteps - 1) {
                                                                // 20-second explicit cooldown delay to prevent Gemini TPM limits
                                                                for (cd in 20 downTo 1) {
                                                                    generationCountdownSeconds = (totalSteps - i - 1) * 20 + cd
                                                                    generationStatusText = "Cooldown before next chunk... (${cd}s remaining)"
                                                                    delay(1000)
                                                                }
                                                            }
                                                        }

                                                        generationProgress = 0.85f
                                                        generationCountdownSeconds = 0
                                                        generationStatusText = "Synthesizing full crossovers and outlining final novel..."
                                                        
                                                        val comp = api.generateComplete(
                                                            type = "long",
                                                            sourceNovels = selectedSources,
                                                            userDescription = userDescription,
                                                            profiles = summaries,
                                                            token = token
                                                        )
                                                        generatedResult = comp
                                                        quota = api.fetchQuota(token)
                                                    }
                                                } catch (e: Exception) {
                                                    generationError = e.message ?: "Generation pipeline failed."
                                                    e.printStackTrace()
                                                } finally {
                                                    isGenerating = false
                                                }
                                            }
                                        }.onFailure {
                                            generationError = it.message ?: "Failed to verify quota/start generation."
                                            isGenerating = false
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = currentTheme.accentColor())
                            ) {
                                Icon(Icons.Default.Bolt, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("FUSE & GENERATE NOVEL")
                            }
                        }
                    }
                }
            }
        }

        // Search Source Picker Overlay Dialog
        if (showSourcePicker) {
            AlertDialog(
                onDismissRequest = { showSourcePicker = false },
                title = { Text("Select Fused Source", color = currentTheme.textColor()) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        // Dialog picker tabs
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(currentTheme.cardColor(), RoundedCornerShape(8.dp))
                                .padding(2.dp)
                        ) {
                            listOf("Downloaded Novels", "Search Video").forEachIndexed { idx, label ->
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(
                                            if (pickerTab == idx) currentTheme.accentColor()
                                            else Color.Transparent
                                        )
                                        .clickable { pickerTab = idx }
                                        .padding(8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        label,
                                        color = if (pickerTab == idx) Color.White else currentTheme.textColor(),
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        }

                        if (pickerTab == 0) {
                            // Downloaded Novels list
                            val downloadedNovels = remember { downloadRepo.getNovelItems() }
                            if (downloadedNovels.isEmpty()) {
                                Text(
                                    "No downloaded offline novels found. Please download some novels first to mash them up.",
                                    color = currentTheme.subTextColor(),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(vertical = 12.dp)
                                )
                            } else {
                                LazyColumn(modifier = Modifier.height(260.dp)) {
                                    items(downloadedNovels) { item ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    val unified = UnifiedSearchResult(
                                                        id = item.id,
                                                        title = item.title,
                                                        coverUrl = item.coverUrl,
                                                        detailPageUrl = item.id,
                                                        sourceName = item.sourceName
                                                    )
                                                    if (selectedSources.none { it.id == unified.id }) {
                                                        selectedSources = selectedSources + unified
                                                    }
                                                    showSourcePicker = false
                                                }
                                                .padding(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            AsyncImage(
                                                model = item.coverUrl,
                                                contentDescription = null,
                                                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(4.dp)),
                                                contentScale = ContentScale.Crop
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column {
                                                Text(item.title, color = currentTheme.textColor(), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                                Text(item.sourceName, color = currentTheme.subTextColor(), style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            // Search TMDB Movies/shows
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TextField(
                                        value = searchPickerQuery,
                                        onValueChange = { searchPickerQuery = it },
                                        placeholder = { Text("Search show/movie name...") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true,
                                        colors = TextFieldDefaults.colors()
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    IconButton(
                                        onClick = {
                                            if (searchPickerQuery.isBlank()) return@IconButton
                                            isSearchingPicker = true
                                            scope.launch {
                                                runCatching {
                                                    // Search across multiple categories: Movies, Cartoons, K-Drama
                                                    (tmdbScraper.search(searchPickerQuery)).map { res ->
                                                        UnifiedSearchResult(
                                                            id = "tmdb_${if (res.type == "MOVIE") "movie" else "tv"}_${res.id}",
                                                            title = res.title,
                                                            coverUrl = res.coverUrl,
                                                            detailPageUrl = "tmdb://${if (res.type == "MOVIE") "movie" else "tv"}/${res.id}",
                                                            sourceName = "TMDB Media",
                                                            synopsis = res.description
                                                        )
                                                    }
                                                }.onSuccess {
                                                    searchPickerResults = it
                                                }.onFailure {
                                                    it.printStackTrace()
                                                }
                                                isSearchingPicker = false
                                            }
                                        }
                                    ) {
                                        if (isSearchingPicker) {
                                            CircularProgressIndicator(color = currentTheme.accentColor(), modifier = Modifier.size(24.dp))
                                        } else {
                                            Icon(Icons.Default.Search, contentDescription = "Search", tint = currentTheme.accentColor())
                                        }
                                    }
                                }

                                LazyColumn(modifier = Modifier.height(200.dp)) {
                                    items(searchPickerResults) { item ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    if (selectedSources.none { it.id == item.id }) {
                                                        selectedSources = selectedSources + item
                                                    }
                                                    showSourcePicker = false
                                                }
                                                .padding(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            AsyncImage(
                                                model = item.coverUrl,
                                                contentDescription = null,
                                                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(4.dp)),
                                                contentScale = ContentScale.Crop
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(item.title, color = currentTheme.textColor(), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text(item.sourceName, color = currentTheme.subTextColor(), style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSourcePicker = false }) {
                        Text("Close", color = currentTheme.accentColor())
                    }
                },
                containerColor = currentTheme.surfaceColor()
            )
        }

        // Full Screen Reader overlay view
        activeNovelReading?.let { novel ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(currentTheme.backgroundColor())
                    .statusBarsPadding()
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Header controls
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(onClick = { activeNovelReading = null }) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = currentTheme.textColor())
                        }
                        Text(
                            novel.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = currentTheme.textColor(),
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                        )
                        IconButton(onClick = {
                            ttsController.playText(novel.content, novel.title)
                        }) {
                            Icon(Icons.Default.VolumeUp, contentDescription = "Narrate", tint = currentTheme.accentColor())
                        }
                    }

                    // Content scroll area
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 8.dp)
                    ) {
                        // Header metadata
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (novel.cover_url.isNotBlank()) {
                                AsyncImage(
                                    model = novel.cover_url,
                                    contentDescription = null,
                                    modifier = Modifier.size(80.dp).clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(
                                    novel.title,
                                    style = MaterialTheme.typography.titleLarge,
                                    color = currentTheme.textColor(),
                                    fontWeight = FontWeight.Black
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "Created by: ${novel.author_name}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = currentTheme.subTextColor()
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "${novel.word_count} words",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = currentTheme.accentColor(),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Divider(color = currentTheme.textColor().copy(alpha = 0.1f))
                        Spacer(modifier = Modifier.height(16.dp))

                        // Novel text body
                        Text(
                            novel.content,
                            style = MaterialTheme.typography.bodyLarge,
                            color = currentTheme.textColor(),
                            lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.35f
                        )
                        Spacer(modifier = Modifier.height(64.dp))
                    }
                }
            }
        }
    }
}
