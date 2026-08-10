package com.alexleoreeves.novelapp.tv.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alexleoreeves.novelapp.data.TvSearchHistoryManager
import com.alexleoreeves.novelapp.data.TvNovelSearchRepository
import com.alexleoreeves.novelapp.tv.ui.components.TvSearchKeyboard
import com.alexleoreeves.novelapp.tv.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun TvSearchScreen(
    initialQuery: String = "",
    selectedCategory: String = "all",
    onCategoryChange: (String) -> Unit = {},
    onSearch: (query: String, category: String) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val historyManager = remember(context) { TvSearchHistoryManager(context) }
    var history by remember { mutableStateOf(historyManager.getHistory()) }
    var query by remember { mutableStateOf(initialQuery) }
    var category by remember { mutableStateOf(selectedCategory) }
    var liveSuggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    val novelRepo = remember { TvNovelSearchRepository() }

    LaunchedEffect(query, category) {
        val trimmed = query.trim()
        liveSuggestions = emptyList()
        if (trimmed.length >= 2) {
            delay(350L)
            liveSuggestions = searchTvContentForCategory(category, trimmed, novelRepo)
                .map { it.title }
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase() }
                .take(10)
        }
    }

    fun executeSearch(searchQuery: String) {
        val trimmed = searchQuery.trim()
        if (trimmed.isNotBlank()) {
            historyManager.addSearchQuery(trimmed)
            history = historyManager.getHistory()
            onSearch(trimmed, category)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TvBlack)
    ) {
        // Back button
        var backFocused by remember { mutableStateOf(false) }
        Surface(
            onClick = onClose,
            shape = RoundedCornerShape(8.dp),
            color = if (backFocused) Color(0xFF1C1C2E) else Color.Transparent,
            border = if (backFocused) BorderStroke(2.dp, Purple500) else null,
            modifier = Modifier
                .padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
                .onFocusChanged { backFocused = it.isFocused }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    Icons.Default.ArrowBack, null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    "Back",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Custom search keyboard
        TvSearchKeyboard(
            value = query,
            onValueChange = { query = it },
            onSearch = { executeSearch(query) },
            onBack = onClose,
            suggestions = liveSuggestions.ifEmpty { defaultTvSearchSuggestions(category, query) },
            onSuggestionClick = { sug ->
                query = sug
                executeSearch(sug)
            },
            searchHistory = history,
            onClearHistory = {
                historyManager.clearHistory()
                history = emptyList()
            },
            selectedCategory = category,
            onCategorySelected = { cat ->
                category = cat
                onCategoryChange(cat)
            }
        )
    }
}

private fun defaultTvSearchSuggestions(category: String, query: String): List<String> {
    val base = when (category.lowercase()) {
        "anime" -> listOf("Dragon Ball Super: Broly", "Dragon Ball Z", "One Piece", "Solo Leveling", "Blue Lock", "Attack on Titan", "Demon Slayer")
        "movie" -> listOf("Spider-Man: Homecoming", "Inception", "The Matrix", "Black Panther", "Avatar", "Toy Story")
        "donghua" -> listOf("Renegade Immortal", "Swallowed Star", "Soul Land", "Perfect World", "Battle Through The Heavens")
        "kdrama" -> listOf("Queen of Tears", "True Beauty", "Crash Landing on You", "Squid Game")
        "cartoon" -> listOf("Avatar: The Last Airbender", "Teen Titans", "Adventure Time", "SpongeBob SquarePants")
        "novel" -> listOf("Lord of the Mysteries", "My Vampire System", "Renegade Immortal", "A Will Eternal", "Mother of Learning")
        else -> listOf("Dragon Ball Super: Broly", "Spider-Man: Homecoming", "Dragon Ball Z", "Lord of the Mysteries", "Solo Leveling", "One Piece")
    }
    val trimmed = query.trim()
    return if (trimmed.length < 2) {
        base
    } else {
        base.filter { it.contains(trimmed, ignoreCase = true) }.ifEmpty { base }
    }
}
