package com.alexleoreeves.novelapp.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alexleoreeves.novelapp.data.mediacache.MediaServerCandidate
import com.alexleoreeves.novelapp.data.mediacache.MediaServerProbe
import com.alexleoreeves.novelapp.data.mediacache.MediaServerProbeResult
import com.alexleoreeves.novelapp.data.mediacache.MediaServerProbeStatus
import com.alexleoreeves.novelapp.data.mediacache.MediaTransportPort
import com.alexleoreeves.novelapp.tv.ui.theme.NeonBlue

/** Selected server handed back to the caller when the user picks a healthy one. */
data class TvServerSelection(
    val url: String,
    val serverId: String,
    val serverName: String
)

/**
 * Full-screen server-health modal shown before a download (or stream retry).
 *
 * Every candidate URL is pre-flighted concurrently through [MediaServerProbe];
 * healthy servers sort to the top (fastest latency first), dead/unsupported
 * servers sink to the bottom with a status chip. Selecting a server invokes
 * [onSelect]; "Pick best" instantly chooses the first healthy result (null when
 * every server is dead so the caller can surface a retry prompt).
 *
 * Focus/d-pad friendly: each row is a focus-tintable Surface, matching the rest
 * of the TV UI. The health state lives in `remember` so re-opening re-probes.
 */
@Composable
fun TvServerCheckModal(
    title: String,
    candidates: List<MediaServerCandidate>,
    transport: MediaTransportPort,
    onSelect: (TvServerSelection) -> Unit,
    onCancel: () -> Unit
) {
    var results by remember {
        mutableStateOf<List<MediaServerProbeResult>?>(null)
    }
    val probe = remember { MediaServerProbe(transport) }

    LaunchedEffect(candidates) {
        results = null
        if (candidates.isNotEmpty()) {
            results = probe.probeServers(candidates)
        }
    }

    fun selectBest() {
        val best = results?.firstOrNull { it.isHealthy } ?: return
        onSelect(
            TvServerSelection(
                url = best.url,
                serverId = best.serverId,
                serverName = best.serverName
            )
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF06060A).copy(alpha = 0.96f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = Color(0xFF0C0C12),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .widthIn(max = 920.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        Icons.Default.Speed,
                        null,
                        tint = NeonBlue,
                        modifier = Modifier.size(26.dp)
                    )
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                        color = Color.White
                    )
                }

                Text(
                    "Checking server health — healthy & fastest first",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.55f)
                )

                val current = results
                when {
                    candidates.isEmpty() -> {
                        EmptyRow("No servers available to check")
                    }
                    current == null -> {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                color = NeonBlue,
                                modifier = Modifier.size(34.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "Probing servers...",
                                color = Color.White.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                    else -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(340.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            current.forEach { result ->
                                ServerRow(
                                    result = result,
                                    onClick = {
                                        onSelect(
                                            TvServerSelection(
                                                url = result.url,
                                                serverId = result.serverId,
                                                serverName = result.serverName
                                            )
                                        )
                                    }
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        onClick = { selectBest() },
                        enabled = results?.any { it.isHealthy } == true,
                        shape = RoundedCornerShape(10.dp),
                        color = if (results?.any { it.isHealthy } == true) NeonBlue else NeonBlue.copy(alpha = 0.25f)
                    ) {
                        Text(
                            "Pick best server",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                        )
                    }
                    Surface(
                        onClick = onCancel,
                        shape = RoundedCornerShape(10.dp),
                        color = Color.White.copy(alpha = 0.08f)
                    ) {
                        Text(
                            "Cancel",
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        }
    }
}

/** One candidate row with a status chip + latency, focus-tintable. */
@Composable
private fun ServerRow(
    result: MediaServerProbeResult,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (focused) NeonBlue.copy(alpha = 0.14f) else Color.Transparent)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .onFocusChanged { focused = it.isFocused },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatusIcon(result.status)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                result.serverName.ifBlank { result.serverId },
                color = Color.White,
                fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                statusSubtitle(result),
                color = Color.White.copy(alpha = 0.45f),
                style = MaterialTheme.typography.labelMedium
            )
        }
        if (result.isHealthy) {
            Text(
                "${result.probeLatencyMs} ms",
                color = NeonBlue,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelLarge
            )
        }
        Icon(
            Icons.Default.KeyboardArrowRight,
            null,
            tint = if (focused) NeonBlue else Color.White.copy(alpha = 0.25f),
            modifier = Modifier.size(22.dp)
        )
    }
    Surface(
        onClick = onClick,
        enabled = result.isHealthy,
        shape = RoundedCornerShape(10.dp),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth().height(0.dp)
    ) {}
}

/** Standard alert + close row for empty/dead states. */
@Composable
private fun EmptyRow(message: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Error,
            null,
            tint = Color.White.copy(alpha = 0.4f),
            modifier = Modifier.size(26.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(message, color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun StatusIcon(status: MediaServerProbeStatus) {
    when (status) {
        MediaServerProbeStatus.HEALTHY -> Icon(
            Icons.Default.CheckCircle,
            null,
            tint = Color(0xFF06D6A0),
            modifier = Modifier.size(22.dp)
        )
        MediaServerProbeStatus.UNREACHABLE,
        MediaServerProbeStatus.NO_RANGE,
        MediaServerProbeStatus.EMPTY -> Icon(
            Icons.Default.Error,
            null,
            tint = Color(0xFFFF6B6B),
            modifier = Modifier.size(22.dp)
        )
        MediaServerProbeStatus.UNKNOWN -> Icon(
            Icons.Default.Speed,
            null,
            tint = Color.White.copy(alpha = 0.3f),
            modifier = Modifier.size(22.dp)
        )
    }
}

private fun statusSubtitle(result: MediaServerProbeResult): String = when (result.status) {
    MediaServerProbeStatus.HEALTHY ->
        "${formatBytes(result.totalBytes)} • supports downloads"
    MediaServerProbeStatus.UNREACHABLE -> "Server unreachable / timed out"
    MediaServerProbeStatus.NO_RANGE -> "Reachable but no range support"
    MediaServerProbeStatus.EMPTY -> "Reachable but empty or unknown size"
    MediaServerProbeStatus.UNKNOWN -> "Not probed yet"
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "unknown size"
    val gib = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    if (gib >= 1.0) return "%.1f GB".format(gib)
    val mib = bytes.toDouble() / (1024.0 * 1024.0)
    return "%.0f MB".format(mib)
}
