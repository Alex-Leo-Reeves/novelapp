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
import com.alexleoreeves.novelapp.tv.ui.components.TvSearchKeyboard
import com.alexleoreeves.novelapp.tv.ui.theme.*

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
            suggestions = listOf(
                "Lord of the Mysteries",
                "Blue Lock",
                "Solo Leveling",
                "One Piece",
                "Attack on Titan",
                "Demon Slayer",
                "Jujutsu Kaisen"
            ),
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

