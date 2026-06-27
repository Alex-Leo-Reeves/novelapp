package com.alexleoreeves.novelapp.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*
import kotlinx.coroutines.delay
import java.util.Calendar
import kotlin.math.*

@Composable
fun AmbientPlayerScreen(
    novelTitle: String,
    chapterTitle: String,
    isPlaying: Boolean,
    sleepTimerMinutes: Int,
    onSleepTimerSelected: (Int) -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onWakeUp: () -> Unit
) {
    // Live clock state
    var hour by remember { mutableStateOf(0) }
    var minute by remember { mutableStateOf(0) }
    var second by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            val cal = Calendar.getInstance()
            hour = cal.get(Calendar.HOUR)
            minute = cal.get(Calendar.MINUTE)
            second = cal.get(Calendar.SECOND)
            delay(1000)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onWakeUp
            )
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // ── Analog Clock ─────────────────────────────────────────────────
            Canvas(
                modifier = Modifier
                    .size(180.dp)
                    .alpha(0.55f)
            ) {
                drawAnalogClock(hour, minute, second)
            }

            Spacer(Modifier.height(32.dp))

            // ── Novel / Chapter Info ─────────────────────────────────────────
            Text(
                novelTitle,
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White.copy(alpha = 0.9f),
                fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                chapterTitle,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.5f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            Spacer(Modifier.height(24.dp))

            // ── Sleep Timer Options ──────────────────────────────────────────
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.alpha(0.7f)
            ) {
                Icon(
                    Icons.Default.AccessTime,
                    null,
                    tint = Color.White.copy(0.6f),
                    modifier = Modifier.size(14.dp)
                )
                listOf(0, 15, 30, 45).forEach { mins ->
                    val isSelected = sleepTimerMinutes == mins
                    Text(
                        text = if (mins == 0) "Off" else "${mins}m",
                        color = if (isSelected) Color(0xFFAB6BEB) else Color.White.copy(0.6f),
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { onSleepTimerSelected(mins) }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            if (sleepTimerMinutes > 0) {
                Text(
                    "Timer ending in ~$sleepTimerMinutes mins",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFAB6BEB).copy(0.8f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Spacer(Modifier.height(32.dp))

            // ── Media Controls ───────────────────────────────────────────────
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Skip Back 10s
                IconButton(onClick = onSkipBack) {
                    Icon(
                        Icons.Default.Replay10,
                        contentDescription = "Rewind 10s",
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(32.dp)
                    )
                }
                // Play/Pause
                IconButton(
                    onClick = { if (isPlaying) onPause() else onPlay() },
                    modifier = Modifier
                        .size(64.dp)
                        .background(Color.White.copy(alpha = 0.15f), CircleShape)
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }
                // Skip Forward 10s
                IconButton(onClick = onSkipForward) {
                    Icon(
                        Icons.Default.Forward10,
                        contentDescription = "Forward 10s",
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "Tap anywhere to return",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.25f)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Analog clock drawing
// ─────────────────────────────────────────────────────────────────────────────
private fun DrawScope.drawAnalogClock(hour: Int, minute: Int, second: Int) {
    val cx = size.width / 2
    val cy = size.height / 2
    val radius = size.minDimension / 2 - 4.dp.toPx()
    val white = Color.White

    // Outer circle
    drawCircle(color = white.copy(alpha = 0.6f), radius = radius, style = Stroke(width = 1.5.dp.toPx()))

    // Hour markers
    for (i in 0 until 12) {
        val angle = Math.toRadians((i * 30 - 90).toDouble())
        val outer = radius - 4.dp.toPx()
        val inner = radius - 10.dp.toPx()
        drawLine(
            color = white.copy(alpha = 0.5f),
            start = Offset((cx + cos(angle) * inner).toFloat(), (cy + sin(angle) * inner).toFloat()),
            end = Offset((cx + cos(angle) * outer).toFloat(), (cy + sin(angle) * outer).toFloat()),
            strokeWidth = 1.dp.toPx()
        )
    }

    // Hour hand
    val hourAngle = Math.toRadians(((hour % 12 + minute / 60.0) * 30 - 90))
    val hourLen = radius * 0.5f
    drawLine(
        color = white, strokeWidth = 3.dp.toPx(),
        start = Offset(cx, cy),
        end = Offset((cx + cos(hourAngle) * hourLen).toFloat(), (cy + sin(hourAngle) * hourLen).toFloat()),
        cap = StrokeCap.Round
    )

    // Minute hand
    val minuteAngle = Math.toRadians((minute * 6 - 90).toDouble())
    val minuteLen = radius * 0.72f
    drawLine(
        color = white, strokeWidth = 2.dp.toPx(),
        start = Offset(cx, cy),
        end = Offset((cx + cos(minuteAngle) * minuteLen).toFloat(), (cy + sin(minuteAngle) * minuteLen).toFloat()),
        cap = StrokeCap.Round
    )

    // Second hand
    val secondAngle = Math.toRadians((second * 6 - 90).toDouble())
    val secondLen = radius * 0.82f
    drawLine(
        color = Color(0xFFAB6BEB), strokeWidth = 1.dp.toPx(),
        start = Offset(cx, cy),
        end = Offset((cx + cos(secondAngle) * secondLen).toFloat(), (cy + sin(secondAngle) * secondLen).toFloat()),
        cap = StrokeCap.Round
    )

    // Center dot
    drawCircle(color = white, radius = 4.dp.toPx())
}
