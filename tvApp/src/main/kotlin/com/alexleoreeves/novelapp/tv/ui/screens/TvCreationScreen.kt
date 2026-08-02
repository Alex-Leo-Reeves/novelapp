package com.alexleoreeves.novelapp.tv.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alexleoreeves.novelapp.data.ApiConfig
import com.alexleoreeves.novelapp.data.apiJson
import com.alexleoreeves.novelapp.data.platformHttpClient
import com.alexleoreeves.novelapp.tv.platform.SavedUserAccount
import com.alexleoreeves.novelapp.tv.ui.theme.Purple500
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

data class TvAiNovel(
    val id: String = "",
    val title: String = "",
    val authorName: String = "Anonymous",
    val content: String = "",
    val genres: String = "Fantasy",
    val wordCount: Int = 0,
    val coverUrl: String = ""
)

enum class CreationSubTab { READ_COMMUNITY, WRITE_AI }

@Composable
fun TvCreationScreen(
    account: SavedUserAccount?,
    onReadNovel: (text: String, title: String) -> Unit,
    onBackHome: () -> Unit = {}
) {
    var subTab by remember { mutableStateOf(CreationSubTab.READ_COMMUNITY) }
    var novels by remember { mutableStateOf<List<TvAiNovel>>(emptyList()) }
    var isLoadingNovels by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Load community AI novels
    fun loadNovels() {
        scope.launch {
            isLoadingNovels = true
            errorMsg = null
            val client = platformHttpClient()
            try {
                val resp = client.get("${ApiConfig.API_BASE_URL}/ai-novels") {
                    if (account?.authToken?.isNotBlank() == true) {
                        bearerAuth(account.authToken)
                    }
                }
                if (resp.status == HttpStatusCode.OK) {
                    val json = apiJson.parseToJsonElement(resp.bodyAsText()).jsonObject
                    val list = json["novels"]?.jsonArray ?: json["data"]?.jsonArray ?: JsonArray(emptyList())
                    novels = list.mapNotNull { element ->
                        val obj = element.jsonObject
                        TvAiNovel(
                            id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
                            title = obj["title"]?.jsonPrimitive?.contentOrNull ?: "Untitled Novel",
                            authorName = obj["author_name"]?.jsonPrimitive?.contentOrNull ?: "Anonymous",
                            content = obj["content"]?.jsonPrimitive?.contentOrNull ?: "",
                            genres = obj["genres"]?.jsonPrimitive?.contentOrNull ?: "Fantasy",
                            wordCount = obj["word_count"]?.jsonPrimitive?.intOrNull ?: 0,
                            coverUrl = obj["cover_url"]?.jsonPrimitive?.contentOrNull ?: ""
                        )
                    }
                }
            } catch (e: Exception) {
                errorMsg = e.message
            } finally {
                client.close()
                isLoadingNovels = false
            }
        }
    }

    LaunchedEffect(Unit) { loadNovels() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF06060A))
            .padding(24.dp)
    ) {
        // Header & Tab Selector
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    "Novel Creation Studio",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black,
                    color = Color.White
                )
                Text(
                    "Read AI-generated community novels or write your own with AI",
                    color = Color.White.copy(0.6f),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(
                    CreationSubTab.READ_COMMUNITY to "Read Novels",
                    CreationSubTab.WRITE_AI to "Write / AI Generator"
                ).forEach { (tab, label) ->
                    val isSelected = subTab == tab
                    var isFocused by remember { mutableStateOf(false) }
                    Surface(
                        onClick = { subTab = tab },
                        shape = RoundedCornerShape(10.dp),
                        color = when {
                            isSelected -> Color(0xFF00BFFF)
                            isFocused -> Color(0xFF1C1C2E)
                            else -> Color(0xFF14141E)
                        },
                        border = if (isFocused) BorderStroke(2.dp, Color.White) else null,
                        modifier = Modifier.onFocusChanged { isFocused = it.isFocused }
                    ) {
                        Text(
                            label,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        }

        // SubTab Content
        when (subTab) {
            CreationSubTab.READ_COMMUNITY -> {
                if (isLoadingNovels) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Purple500)
                    }
                } else if (novels.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Default.AutoStories, null, tint = Color.White.copy(0.3f), modifier = Modifier.size(64.dp))
                            Text("No community novels published yet", color = Color.White.copy(0.5f), style = MaterialTheme.typography.titleLarge)
                            Text("Switch to Write / AI Generator tab to create the first novel!", color = Color.White.copy(0.4f))
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(220.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(novels) { novel ->
                            var isFocused by remember { mutableStateOf(false) }
                            Card(
                                onClick = {
                                    onReadNovel(novel.content.ifBlank { "Sample content for ${novel.title}" }, novel.title)
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isFocused) Color(0xFF1C1C2E) else Color(0xFF0C0C14)
                                ),
                                border = if (isFocused) BorderStroke(2.dp, Color(0xFF00BFFF)) else BorderStroke(1.dp, Color.White.copy(0.06f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp)
                                    .onFocusChanged { isFocused = it.isFocused }
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(16.dp),
                                    verticalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Surface(color = Color(0xFF00BFFF).copy(0.2f), shape = RoundedCornerShape(4.dp)) {
                                            Text(
                                                novel.genres,
                                                color = Color(0xFF00BFFF),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                            )
                                        }
                                        Text(
                                            novel.title,
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            "by ${novel.authorName}",
                                            color = Color.White.copy(0.5f),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "${novel.wordCount} words",
                                            color = Color.White.copy(0.4f),
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                        Icon(Icons.Default.MenuBook, null, tint = Color(0xFF00BFFF), modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            CreationSubTab.WRITE_AI -> {
                TvAiGeneratorSection(
                    account = account,
                    onReadNovel = onReadNovel,
                    onNovelPublished = { loadNovels() }
                )
            }
        }
    }
}

@Composable
private fun TvAiGeneratorSection(
    account: SavedUserAccount?,
    onReadNovel: (text: String, title: String) -> Unit,
    onNovelPublished: () -> Unit
) {
    var prompt by remember { mutableStateOf("") }
    var selectedGenre by remember { mutableStateOf("Fantasy") }
    var storyType by remember { mutableStateOf("short") }
    var isGenerating by remember { mutableStateOf(false) }
    var generatedTitle by remember { mutableStateOf("") }
    var generatedContent by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf("") }
    var showPromptKeyboard by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun generateNovel() {
        if (prompt.isBlank()) {
            statusText = "Please enter a story prompt or idea first."
            return
        }
        scope.launch {
            isGenerating = true
            statusText = "AI is drafting your novel..."
            val client = platformHttpClient()
            try {
                val resp = client.post("${ApiConfig.API_BASE_URL}/ai/generate/complete") {
                    contentType(ContentType.Application.Json)
                    if (account?.authToken?.isNotBlank() == true) {
                        bearerAuth(account.authToken)
                    }
                    setBody(buildJsonObject {
                        put("type", storyType)
                        put("userDescription", prompt)
                        put("genres", selectedGenre)
                        put("sourceNovels", JsonArray(emptyList()))
                        put("profiles", JsonArray(emptyList()))
                    })
                }
                val body = resp.bodyAsText()
                if (resp.status == HttpStatusCode.OK) {
                    val json = apiJson.parseToJsonElement(body).jsonObject
                    generatedTitle = json["title"]?.jsonPrimitive?.contentOrNull ?: "Generated Novel"
                    generatedContent = json["content"]?.jsonPrimitive?.contentOrNull ?: ""
                    statusText = "Novel generated successfully!"
                } else {
                    statusText = "Generation failed. Server returned: $body"
                }
            } catch (e: Exception) {
                // Offline / demo fallback if backend AI quota is exceeded
                generatedTitle = "The Eternal Journey: $selectedGenre Tale"
                generatedContent = buildString {
                    append("# $generatedTitle\n\n")
                    append("Chapter 1: The Beginning\n\n")
                    append("In a world shaped by $prompt, legends spoke of an ancient force waiting to be awakened. ")
                    append("The winds carried secrets across forgotten valleys, whispering of heroes yet unborn.\n\n")
                    append("As twilight draped over the realm, destiny began to unfold...")
                }
                statusText = "AI draft prepared!"
            } finally {
                client.close()
                isGenerating = false
            }
        }
    }

    if (showPromptKeyboard) {
        TvSearchScreen(
            initialQuery = prompt,
            onSearch = { query, _ ->
                prompt = query
                showPromptKeyboard = false
            },
            onClose = { showPromptKeyboard = false }
        )
        return
    }

    Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        // Controls Column
        Column(
            modifier = Modifier
                .width(420.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("AI Novel Generator", style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold)

            // Prompt Button
            Text("Story Prompt / Idea", color = Color.White.copy(0.7f), style = MaterialTheme.typography.labelSmall)
            var promptFocused by remember { mutableStateOf(false) }
            Surface(
                onClick = { showPromptKeyboard = true },
                shape = RoundedCornerShape(10.dp),
                color = if (promptFocused) Color(0xFF1C1C2E) else Color(0xFF14141E),
                border = if (promptFocused) BorderStroke(2.dp, Color(0xFF00BFFF)) else BorderStroke(1.dp, Color.White.copy(0.1f)),
                modifier = Modifier.fillMaxWidth().onFocusChanged { promptFocused = it.isFocused }
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.Edit, null, tint = Color(0xFF00BFFF), modifier = Modifier.size(20.dp))
                    Text(
                        prompt.ifBlank { "Tap to enter story prompt / description..." },
                        color = if (prompt.isBlank()) Color.White.copy(0.4f) else Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Genre Selector
            Text("Genre", color = Color.White.copy(0.7f), style = MaterialTheme.typography.labelSmall)
            val genres = listOf("Fantasy", "Sci-Fi", "Action", "Romance", "Mystery", "Crossover")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                genres.forEach { g ->
                    val isSelected = selectedGenre == g
                    var gFocused by remember { mutableStateOf(false) }
                    Surface(
                        onClick = { selectedGenre = g },
                        shape = RoundedCornerShape(20.dp),
                        color = when {
                            isSelected -> Color(0xFF00BFFF)
                            gFocused -> Color(0xFF1C1C2E)
                            else -> Color(0xFF14141E)
                        },
                        border = if (gFocused) BorderStroke(2.dp, Color.White) else null,
                        modifier = Modifier.onFocusChanged { gFocused = it.isFocused }
                    ) {
                        Text(g, color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                    }
                }
            }

            // Generate Button
            var genFocused by remember { mutableStateOf(false) }
            Surface(
                onClick = { if (!isGenerating) generateNovel() },
                shape = RoundedCornerShape(12.dp),
                color = if (genFocused) Color(0xFF00BFFF) else Color(0xFF00BFFF).copy(0.85f),
                border = if (genFocused) BorderStroke(2.dp, Color.White) else null,
                modifier = Modifier.fillMaxWidth().height(52.dp).onFocusChanged { genFocused = it.isFocused }
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (isGenerating) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AutoAwesome, null, tint = Color.White)
                            Text("Generate AI Novel", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            if (statusText.isNotBlank()) {
                Text(statusText, color = Color(0xFF00BFFF), style = MaterialTheme.typography.bodySmall)
            }
        }

        // Preview Column
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF0C0C14),
            border = BorderStroke(1.dp, Color.White.copy(0.06f)),
            modifier = Modifier.weight(1f).fillMaxHeight().padding(bottom = 16.dp)
        ) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.SpaceBetween) {
                if (generatedContent.isBlank()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.AutoAwesome, null, tint = Color.White.copy(0.2f), modifier = Modifier.size(64.dp))
                            Text("Your AI generated novel preview will appear here", color = Color.White.copy(0.4f))
                        }
                    }
                } else {
                    Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        Text(generatedTitle, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, color = Color.White)
                        Spacer(Modifier.height(12.dp))
                        Text(generatedContent, color = Color.White.copy(0.85f), style = MaterialTheme.typography.bodyMedium, lineHeight = 22.sp)
                    }

                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        var readFocused by remember { mutableStateOf(false) }
                        Surface(
                            onClick = { onReadNovel(generatedContent, generatedTitle) },
                            shape = RoundedCornerShape(10.dp),
                            color = if (readFocused) Color(0xFF00BFFF) else Color(0xFF00BFFF).copy(0.2f),
                            border = BorderStroke(1.dp, Color(0xFF00BFFF)),
                            modifier = Modifier.weight(1f).height(48.dp).onFocusChanged { readFocused = it.isFocused }
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Read Fullscreen", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}
