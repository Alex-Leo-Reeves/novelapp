package com.alexleoreeves.novelapp.tv.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import coil3.compose.AsyncImage
import com.alexleoreeves.novelapp.data.LiveChannel
import com.alexleoreeves.novelapp.data.LiveChannelCategory
import com.alexleoreeves.novelapp.data.LiveChannelSource

private val TvLivePurple   = Color(0xFF7C4DFF)
private val TvLivePink     = Color(0xFFE040FB)
private val TvLiveRed      = Color(0xFFFF5252)
private val TvLiveDarkBg   = Color(0xFF0A0A12)
private val TvLiveCardBg   = Color(0xFF16162A)

@Composable
fun TvLiveChannelScreen(
    onPlay: (url: String, title: String) -> Unit = { _, _ -> },
    onBack: () -> Unit = {}
) {
    var selectedCategory by remember { mutableStateOf(LiveChannelCategory.ALL) }
    var currentPage      by remember { mutableIntStateOf(0) }
    var searchQuery      by remember { mutableStateOf("") }

    val CHANNELS_PER_PAGE = 20

    val allFiltered by remember(selectedCategory, searchQuery) {
        derivedStateOf {
            LiveChannelSource.getChannelsByCategory(selectedCategory)
                .filter { ch ->
                    searchQuery.isBlank() ||
                    ch.name.contains(searchQuery, ignoreCase = true) ||
                    ch.country.contains(searchQuery, ignoreCase = true)
                }
        }
    }

    val totalPages by remember(allFiltered) {
        derivedStateOf { maxOf(1, (allFiltered.size + CHANNELS_PER_PAGE - 1) / CHANNELS_PER_PAGE) }
    }

    val pageChannels by remember(allFiltered, currentPage) {
        derivedStateOf {
            val from = currentPage * CHANNELS_PER_PAGE
            val to   = minOf(from + CHANNELS_PER_PAGE, allFiltered.size)
            if (from >= allFiltered.size) emptyList() else allFiltered.subList(from, to)
        }
    }

    val categories = LiveChannelCategory.entries

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TvLiveDarkBg)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Header ─────────────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(listOf(TvLivePurple.copy(0.8f), TvLivePink.copy(0.6f)))
                    )
                    .padding(horizontal = 32.dp, vertical = 16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(Icons.Default.LiveTv, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp))
                    Column {
                        Text("Live TV", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                        Text("${allFiltered.size} channels • Page ${currentPage + 1} / $totalPages",
                            color = Color.White.copy(0.7f), fontSize = 14.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    // Back button
                    TvLiveFocusableButton(
                        label = "← Back",
                        icon = Icons.Default.ArrowBack,
                        onClick = onBack
                    )
                }
            }

            // ── Category chips ─────────────────────────────────────────────────
            LazyRow(
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(categories) { cat ->
                    val active = cat == selectedCategory
                    TvLiveCategoryChip(
                        label = cat.label,
                        active = active,
                        onClick = {
                            selectedCategory = cat
                            currentPage = 0
                        }
                    )
                }
            }

            // ── Channel grid ───────────────────────────────────────────────────
            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(pageChannels, key = { it.id }) { channel ->
                    TvLiveChannelCard(
                        channel = channel,
                        onClick = { onPlay(channel.streamUrl, channel.name) }
                    )
                }
            }

            // ── Pagination controls ────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TvLiveCardBg)
                    .padding(horizontal = 32.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TvLiveFocusableButton(
                    label = "◀ Prev",
                    icon = Icons.Default.ChevronLeft,
                    enabled = currentPage > 0,
                    onClick = { if (currentPage > 0) currentPage-- }
                )

                Spacer(Modifier.width(24.dp))

                Text(
                    "${currentPage + 1} / $totalPages",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )

                Spacer(Modifier.width(24.dp))

                TvLiveFocusableButton(
                    label = "Next ▶",
                    icon = Icons.Default.ChevronRight,
                    enabled = currentPage < totalPages - 1,
                    onClick = { if (currentPage < totalPages - 1) currentPage++ }
                )
            }
        }
    }
}

@Composable
private fun TvLiveChannelCard(channel: LiveChannel, onClick: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    var isFocused by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(if (isFocused) 1.08f else 1f, label = "scale")
    val elevation by animateDpAsState(if (isFocused) 8.dp else 2.dp, label = "elev")
    val borderColor = if (isFocused) TvLivePurple else Color.White.copy(0.1f)
    val borderWidth = if (isFocused) 2.dp else 1.dp

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.6f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .focusRequester(focusRequester)
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(onClick = onClick),
        color = TvLiveCardBg,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(borderWidth, borderColor),
        shadowElevation = elevation
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Logo or icon
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(TvLivePurple.copy(0.2f)),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = channel.logoUrl,
                    contentDescription = channel.name,
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(Modifier.height(6.dp))

            Text(
                text = channel.name,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = channel.country,
                color = Color.White.copy(0.5f),
                fontSize = 10.sp,
                maxLines = 1
            )

            if (isFocused) {
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(TvLiveRed)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("LIVE", color = TvLiveRed, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    }
}

@Composable
private fun TvLiveCategoryChip(label: String, active: Boolean, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    val bg = when {
        active  -> Brush.horizontalGradient(listOf(TvLivePurple, TvLivePink))
        isFocused -> Brush.horizontalGradient(listOf(TvLivePurple.copy(0.4f), TvLivePink.copy(0.3f)))
        else      -> Brush.horizontalGradient(listOf(TvLiveCardBg, TvLiveCardBg))
    }

    Box(
        modifier = Modifier
            .onFocusChanged { isFocused = it.isFocused }
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .border(1.dp, if (active || isFocused) TvLivePurple else Color.White.copy(0.2f), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            color = if (active || isFocused) Color.White else Color.White.copy(0.6f),
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun TvLiveFocusableButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val bg = if (isFocused && enabled)
        Brush.horizontalGradient(listOf(TvLivePurple, TvLivePink))
    else
        Brush.horizontalGradient(listOf(TvLiveCardBg, TvLiveCardBg))

    Box(
        modifier = Modifier
            .onFocusChanged { isFocused = it.isFocused }
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, if (isFocused) TvLivePurple else Color.White.copy(0.2f), RoundedCornerShape(8.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(icon, contentDescription = null, tint = if (enabled) Color.White else Color.White.copy(0.3f), modifier = Modifier.size(16.dp))
            Text(label, color = if (enabled) Color.White else Color.White.copy(0.3f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}
