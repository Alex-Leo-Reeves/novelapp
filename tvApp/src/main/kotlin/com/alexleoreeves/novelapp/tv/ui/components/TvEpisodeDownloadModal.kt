package com.alexleoreeves.novelapp.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.alexleoreeves.novelapp.data.Chapter
import com.alexleoreeves.novelapp.data.UnifiedSearchResult
import com.alexleoreeves.novelapp.data.mediacache.DownloadPhase
import com.alexleoreeves.novelapp.data.mediacache.DownloadTask

/**
 * TV Episode Download Selection Modal.
 *
 * Remote-friendly episode picker enabling granular episode downloads for
 * multi-episode series and anime/donghua on Android TV.
 */
@Composable
fun TvEpisodeDownloadModal(
    item: UnifiedSearchResult,
    chapters: List<Chapter>,
    cacheTasks: Map<String, DownloadTask>,
    onDismiss: () -> Unit,
    onConfirmDownload: (List<Chapter>) -> Unit
) {
    var selectedChapters by remember { mutableStateOf<Set<String>>(emptySet()) }
    val seasonsList = remember(chapters) {
        chapters.map { it.seasonNumber.coerceAtLeast(1) }.distinct().sorted()
    }
    var selectedSeason by remember(seasonsList) {
        mutableStateOf(seasonsList.firstOrNull() ?: 1)
    }

    val visibleChapters = remember(chapters, selectedSeason, seasonsList) {
        if (seasonsList.size <= 1) chapters
        else chapters.filter { it.seasonNumber.coerceAtLeast(1) == selectedSeason }
    }

    val isAllSelected = remember(visibleChapters, selectedChapters) {
        visibleChapters.isNotEmpty() && visibleChapters.all { it.url in selectedChapters }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .width(680.dp)
                .heightIn(max = 560.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF10101A),
            border = BorderStroke(1.dp, Color.White.copy(0.12f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Select Episodes to Download",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            item.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Select All Toggle
                    var selectAllFocused by remember { mutableStateOf(false) }
                    Surface(
                        onClick = {
                            selectedChapters = if (isAllSelected) {
                                selectedChapters - visibleChapters.map { it.url }.toSet()
                            } else {
                                selectedChapters + visibleChapters.map { it.url }.toSet()
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        color = if (selectAllFocused) Color(0xFF00BFFF) else Color.White.copy(0.08f),
                        modifier = Modifier.onFocusChanged { selectAllFocused = it.isFocused }
                    ) {
                        Text(
                            if (isAllSelected) "Deselect All" else "Select All",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Season Selector
                if (seasonsList.size > 1) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                    ) {
                        items(seasonsList) { seasonNum ->
                            val isSelected = selectedSeason == seasonNum
                            var sFocused by remember { mutableStateOf(false) }
                            Surface(
                                onClick = { selectedSeason = seasonNum },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) Color(0xFF00BFFF) else if (sFocused) Color(0xFF00BFFF).copy(0.2f) else Color(0xFF181824),
                                border = if (sFocused) BorderStroke(1.5.dp, Color.White) else null,
                                modifier = Modifier.onFocusChanged { sFocused = it.isFocused }
                            ) {
                                Text(
                                    "Season $seasonNum",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }

                // Episode List
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    items(visibleChapters) { ch ->
                        val isSelected = ch.url in selectedChapters
                        val downloadStatus = cacheTasks.values.firstOrNull {
                            it.request.parentId == item.id && it.request.episodeNumber == ch.chapterNumber
                        }
                        val isDownloaded = downloadStatus?.isTerminal == true && downloadStatus.phase == DownloadPhase.COMPLETED
                        val isQueued = downloadStatus != null && !downloadStatus.isTerminal

                        var itemFocused by remember { mutableStateOf(false) }
                        Surface(
                            onClick = {
                                selectedChapters = if (isSelected) selectedChapters - ch.url else selectedChapters + ch.url
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = when {
                                itemFocused -> Color(0xFF00BFFF).copy(0.25f)
                                isSelected -> Color(0xFF00BFFF).copy(0.12f)
                                else -> Color(0xFF181824)
                            },
                            border = when {
                                itemFocused -> BorderStroke(2.dp, Color(0xFF00BFFF))
                                isSelected -> BorderStroke(1.dp, Color(0xFF00BFFF).copy(0.6f))
                                else -> BorderStroke(1.dp, Color.White.copy(0.04f))
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .onFocusChanged { itemFocused = it.isFocused }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = null,
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = Color(0xFF00BFFF),
                                        uncheckedColor = Color.White.copy(0.4f),
                                        checkmarkColor = Color.White
                                    )
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    ch.title.ifBlank { "Episode ${ch.chapterNumber}" },
                                    color = Color.White,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (isDownloaded) {
                                    Surface(
                                        color = Color(0xFF06D6A0).copy(0.2f),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            "Downloaded",
                                            color = Color(0xFF06D6A0),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                } else if (isQueued) {
                                    Surface(
                                        color = Color(0xFF00BFFF).copy(0.2f),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            "Queued",
                                            color = Color(0xFF00BFFF),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Action Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    var cancelFocused by remember { mutableStateOf(false) }
                    Surface(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(10.dp),
                        color = if (cancelFocused) Color.White.copy(0.18f) else Color.Transparent,
                        border = BorderStroke(1.dp, Color.White.copy(0.2f)),
                        modifier = Modifier
                            .height(44.dp)
                            .onFocusChanged { cancelFocused = it.isFocused }
                    ) {
                        Box(Modifier.fillMaxHeight().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                            Text("Cancel", color = Color.White, style = MaterialTheme.typography.labelLarge)
                        }
                    }

                    Spacer(Modifier.width(12.dp))

                    val selectedCount = selectedChapters.size
                    var confirmFocused by remember { mutableStateOf(false) }
                    Surface(
                        onClick = {
                            val picked = chapters.filter { it.url in selectedChapters }
                            if (picked.isNotEmpty()) {
                                onConfirmDownload(picked)
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        color = when {
                            selectedCount == 0 -> Color(0xFF00BFFF).copy(0.3f)
                            confirmFocused -> Color(0xFF00BFFF)
                            else -> Color(0xFF00BFFF).copy(0.85f)
                        },
                        border = if (confirmFocused) BorderStroke(2.dp, Color.White) else null,
                        modifier = Modifier
                            .height(44.dp)
                            .onFocusChanged { confirmFocused = it.isFocused }
                    ) {
                        Box(Modifier.fillMaxHeight().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Download, null, tint = Color.White, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (selectedCount > 0) "Download ($selectedCount)" else "Select Episodes",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
