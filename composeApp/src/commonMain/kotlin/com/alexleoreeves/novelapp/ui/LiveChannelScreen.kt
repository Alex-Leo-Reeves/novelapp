package com.alexleoreeves.novelapp.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.alexleoreeves.novelapp.data.LiveChannel
import com.alexleoreeves.novelapp.data.LiveChannelCategory
import com.alexleoreeves.novelapp.data.LiveChannelSource
import com.alexleoreeves.novelapp.ui.theme.AppTheme
import kotlin.math.ceil

private const val CHANNELS_PER_PAGE = 20

@Composable
fun LiveChannelScreen(
    currentTheme: AppTheme,
    onPlayChannel: (streamUrl: String, channelTitle: String) -> Unit
) {
    var selectedCategory by rememberSaveable { mutableStateOf(LiveChannelCategory.ALL) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var currentPage by rememberSaveable { mutableStateOf(1) }

    // Filter channels
    val filteredChannels = remember(selectedCategory, searchQuery) {
        val base = LiveChannelSource.getChannelsByCategory(selectedCategory)
        if (searchQuery.isBlank()) {
            base
        } else {
            val q = searchQuery.trim().lowercase()
            base.filter { it.name.lowercase().contains(q) || it.country.lowercase().contains(q) }
        }
    }

    val totalPages = remember(filteredChannels.size) {
        maxOf(1, ceil(filteredChannels.size.toDouble() / CHANNELS_PER_PAGE).toInt())
    }

    // Reset page on category or search change
    LaunchedEffect(selectedCategory, searchQuery) {
        currentPage = 1
    }

    val pagedChannels = remember(filteredChannels, currentPage) {
        val startIndex = (currentPage - 1) * CHANNELS_PER_PAGE
        filteredChannels.drop(startIndex).take(CHANNELS_PER_PAGE)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0B14))
            .statusBarsPadding()
    ) {
        // Header & Search
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Live TV Channels",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Black,
                            color = Color.White
                        )
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFFFF2A55).copy(alpha = 0.2f),
                            border = BorderStroke(1.dp, Color(0xFFFF2A55).copy(alpha = 0.6f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFFF2A55))
                                )
                                Text(
                                    "300+ LIVE",
                                    color = Color(0xFFFF2A55),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }
                        }
                    }
                    Text(
                        "Watch live streams worldwide • Sports, Movies, Cartoons, News & Indian TV",
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search 300+ live TV channels...", color = Color.White.copy(0.4f), fontSize = 14.sp) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = Color(0xFF00BFFF)) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, null, tint = Color.White.copy(0.6f))
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF00BFFF),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                    focusedContainerColor = Color(0xFF141424),
                    unfocusedContainerColor = Color(0xFF141424),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )
        }

        // Category Filter Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LiveChannelCategory.values().forEach { cat ->
                val isSelected = selectedCategory == cat
                Surface(
                    onClick = { selectedCategory = cat },
                    shape = RoundedCornerShape(20.dp),
                    color = if (isSelected) Color(0xFF00BFFF) else Color(0xFF1E1E34),
                    border = BorderStroke(1.dp, if (isSelected) Color(0xFF00BFFF) else Color.White.copy(0.1f))
                ) {
                    Text(
                        text = cat.label,
                        color = if (isSelected) Color.Black else Color.White,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
        }

        // Channels Grid (20 items per page)
        Box(modifier = Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 6.dp)) {
            if (pagedChannels.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.TvOff, null, tint = Color.White.copy(0.3f), modifier = Modifier.size(48.dp))
                        Text("No channels found for \"$searchQuery\"", color = Color.White.copy(0.6f))
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(160.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(pagedChannels, key = { it.id }) { channel ->
                        ChannelCard(
                            channel = channel,
                            onClick = { onPlayChannel(channel.streamUrl, channel.name) }
                        )
                    }
                }
            }
        }

        // Pagination Bar
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFF121222),
            border = BorderStroke(1.dp, Color.White.copy(0.08f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { if (currentPage > 1) currentPage-- },
                    enabled = currentPage > 1,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1E1E38),
                        disabledContainerColor = Color(0xFF161628)
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Default.ChevronLeft, null, tint = if (currentPage > 1) Color.White else Color.White.copy(0.2f))
                    Spacer(Modifier.width(4.dp))
                    Text("Previous", color = if (currentPage > 1) Color.White else Color.White.copy(0.2f), fontSize = 13.sp)
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Page $currentPage of $totalPages",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Text(
                        "${filteredChannels.size} channels total",
                        color = Color.White.copy(0.5f),
                        fontSize = 11.sp
                    )
                }

                Button(
                    onClick = { if (currentPage < totalPages) currentPage++ },
                    enabled = currentPage < totalPages,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00BFFF),
                        disabledContainerColor = Color(0xFF161628)
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text("Next 20", color = if (currentPage < totalPages) Color.Black else Color.White.copy(0.2f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.ChevronRight, null, tint = if (currentPage < totalPages) Color.Black else Color.White.copy(0.2f))
                }
            }
        }
    }
}

@Composable
private fun ChannelCard(
    channel: LiveChannel,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF18182C),
        border = BorderStroke(1.dp, Color.White.copy(0.08f)),
        modifier = Modifier.fillMaxWidth().height(150.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = channel.logoUrl,
                contentDescription = channel.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().alpha(0.35f)
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color(0xFF0C0C18).copy(alpha = 0.95f))
                        )
                    )
            )

            Column(
                modifier = Modifier.fillMaxSize().padding(10.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Row: LIVE badge + Quality
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFFFF2A55)
                    ) {
                        Text(
                            "LIVE",
                            color = Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color.Black.copy(0.6f)
                    ) {
                        Text(
                            channel.quality,
                            color = Color(0xFF00BFFF),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }

                // Bottom: Title & Category
                Column {
                    Text(
                        text = channel.name,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.Public, null, tint = Color.White.copy(0.5f), modifier = Modifier.size(10.dp))
                        Text(
                            "${channel.country} • ${channel.category.label}",
                            color = Color.White.copy(0.6f),
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
