package com.alexleoreeves.novelapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.alexleoreeves.novelapp.data.AppTheme
import com.alexleoreeves.novelapp.ui.theme.*

@Composable
fun DownloadAudioDialog(
    contentType: String,
    currentTheme: AppTheme,
    onResult: (includeAudio: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val isNovel = contentType == "NOVEL"
    var includeAudio by remember { mutableStateOf(isNovel) }
    val title = when (contentType) {
        "NOVEL" -> "Download Chapter"
        "MANGA" -> "Download Chapter"
        "COMIC" -> "Download Issue"
        else -> "Download"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Download,
                contentDescription = null,
                tint = currentTheme.accentColor(),
                modifier = Modifier.size(28.dp)
            )
        },
        title = {
            Text(
                title,
                fontWeight = FontWeight.Bold,
                color = currentTheme.textColor()
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Choose what to save for offline reading:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = currentTheme.subTextColor()
                )

                // Row 1: Text — always checked and disabled
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(
                        checked = true,
                        onCheckedChange = null,
                        enabled = false,
                        colors = CheckboxDefaults.colors(
                            checkedColor = currentTheme.accentColor(),
                            uncheckedColor = currentTheme.subTextColor()
                        )
                    )
                    Column {
                        Text(
                            when {
                                isNovel -> "Download Text"
                                contentType == "MANGA" -> "Download Manga Pages"
                                contentType == "COMIC" -> "Download Comic Pages"
                                else -> "Download Content"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = currentTheme.textColor()
                        )
                        Text(
                            "Always included",
                            style = MaterialTheme.typography.labelSmall,
                            color = currentTheme.subTextColor()
                        )
                    }
                }

                // Row 2: Audio — optional, default checked for novels
                if (isNovel) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Checkbox(
                            checked = includeAudio,
                            onCheckedChange = { includeAudio = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = currentTheme.accentColor(),
                                uncheckedColor = currentTheme.subTextColor()
                            )
                        )
                        Column {
                            Text(
                                "Generate Narration Audio",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = currentTheme.textColor()
                            )
                            Text(
                                "Include narrated audio for offline listening",
                                style = MaterialTheme.typography.labelSmall,
                                color = currentTheme.subTextColor()
                            )
                        }
                    }

                    if (includeAudio) {
                        Surface(
                            color = currentTheme.accentColor().copy(0.1f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.Info,
                                    null,
                                    tint = currentTheme.accentColor(),
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    "Audio generation adds ~30 seconds per chapter on first download.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = currentTheme.subTextColor()
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onResult(includeAudio) },
                colors = ButtonDefaults.buttonColors(containerColor = currentTheme.accentColor()),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Download, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Download", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Cancel")
            }
        },
        containerColor = currentTheme.surfaceColor(),
        titleContentColor = currentTheme.textColor(),
        textContentColor = currentTheme.subTextColor()
    )
}
