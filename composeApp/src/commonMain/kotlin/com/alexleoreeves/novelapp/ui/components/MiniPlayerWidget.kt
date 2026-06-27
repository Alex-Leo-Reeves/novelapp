package com.alexleoreeves.novelapp.ui

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.alexleoreeves.novelapp.data.AppTheme
import com.alexleoreeves.novelapp.ui.theme.*

/**
 * Collapsible mini-player widget rendered floating above all screens.
 * Drag behavior is handled by the platform-specific layer.
 * This composable focuses on the expanded / collapsed states.
 */
@Composable
fun MiniPlayerWidget(
    novelTitle: String,
    chapterTitle: String,
    isPlaying: Boolean,
    currentTheme: AppTheme,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedContent(
        targetState = isExpanded,
        transitionSpec = {
            (fadeIn() + expandHorizontally()).togetherWith(fadeOut() + shrinkHorizontally())
        },
        modifier = modifier
    ) { expanded ->
        if (expanded) {
            // Full expanded bar
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = currentTheme.surfaceColor().copy(alpha = 0.97f),
                shadowElevation = 8.dp,
                modifier = Modifier.clickable { onToggleExpand() }
            ) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .widthIn(min = 280.dp, max = 340.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Music note icon
                    Icon(
                        Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = currentTheme.accentColor(),
                        modifier = Modifier.size(20.dp)
                    )
                    // Track info
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            novelTitle,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = currentTheme.textColor(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            chapterTitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = currentTheme.subTextColor(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    // Controls
                    IconButton(
                        onClick = onSkipBack,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Replay10, "Rewind",
                            tint = currentTheme.textColor(),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = { if (isPlaying) onPause() else onPlay() },
                        modifier = Modifier
                            .size(40.dp)
                            .background(currentTheme.accentColor(), CircleShape)
                    ) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            if (isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    IconButton(
                        onClick = onSkipForward,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Forward10, "Forward",
                            tint = currentTheme.textColor(),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        } else {
            // Minimized floating bubble
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(currentTheme.accentColor(), CircleShape)
                    .clickable { onToggleExpand() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.MusicNote,
                    contentDescription = "Expand player",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}
