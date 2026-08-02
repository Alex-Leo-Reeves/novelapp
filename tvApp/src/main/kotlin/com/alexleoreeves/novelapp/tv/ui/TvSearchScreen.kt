package com.alexleoreeves.novelapp.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.alexleoreeves.novelapp.data.UnifiedSearchResult
import com.alexleoreeves.novelapp.tv.ui.components.TvSearchKeyboard
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

@Composable
fun TvSearchScreen(
    onMediaSelected: (UnifiedSearchResult) -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    val queryFlow = remember { MutableStateFlow("") }
    var results by remember { mutableStateOf<List<UnifiedSearchResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        queryFlow
            .debounce(800L)
            .collectLatest { q ->
                if (q.isNotBlank()) {
                    isSearching = true
                    val res = com.alexleoreeves.novelapp.data.searchContent("anime", q)
                    results = res
                    isSearching = false
                } else {
                    results = emptyList()
                    isSearching = false
                }
            }
    }

    Row(modifier = Modifier.fillMaxSize().background(Color.Black).padding(24.dp)) {
        // Keyboard Side
        Column(
            modifier = Modifier.width(340.dp).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (query.isEmpty()) "Search..." else query,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 24.dp),
                color = Color.White
            )

            TvKeyboard(
                onKeyPress = { key ->
                    query += key
                    queryFlow.value = query
                },
                onBackspace = {
                    if (query.isNotEmpty()) {
                        query = query.dropLast(1)
                        queryFlow.value = query
                    }
                },
                onClear = {
                    query = ""
                    queryFlow.value = query
                },
                onSpace = {
                    query += " "
                    queryFlow.value = query
                }
            )
        }

        Spacer(Modifier.width(24.dp))

        // Results Side
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            if (isSearching) {
                // TV Loading
                Text("Searching...", color = Color.White, modifier = Modifier.align(Alignment.Center))
            } else if (results.isNotEmpty()) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(160.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(results) { item ->
                        Card(
                            onClick = { onMediaSelected(item) },
                            modifier = Modifier.aspectRatio(0.72f).fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFF14141E)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                AsyncImage(
                                    model = item.coverUrl,
                                    contentDescription = item.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
            } else if (query.isNotBlank()) {
                Text("No results found.", color = Color.White, modifier = Modifier.align(Alignment.Center))
            } else {
                Text("Start typing to search...", color = Color.Gray, modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
fun TvKeyboard(
    onKeyPress: (String) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onSpace: () -> Unit
) {
    val keys = listOf(
        "A", "B", "C", "D", "E", "F",
        "G", "H", "I", "J", "K", "L",
        "M", "N", "O", "P", "Q", "R",
        "S", "T", "U", "V", "W", "X",
        "Y", "Z", "1", "2", "3", "4",
        "5", "6", "7", "8", "9", "0"
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(6),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().height(300.dp)
        ) {
            items(keys) { key ->
                Button(
                    onClick = { onKeyPress(key) },
                    modifier = Modifier.aspectRatio(1f),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(key)
                }
            }
        }
        
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(onClick = onSpace, modifier = Modifier.weight(1f)) {
                Text("Space")
            }
            Button(onClick = onBackspace, modifier = Modifier.weight(1f)) {
                Text("Del")
            }
            Button(onClick = onClear, modifier = Modifier.weight(1f)) {
                Text("Clear")
            }
        }
    }
}
