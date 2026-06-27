package com.alexleoreeves.novelapp.ui

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
import androidx.compose.ui.unit.*
import com.alexleoreeves.novelapp.BuildKonfig
import com.alexleoreeves.novelapp.audio.GeminiTtsController
import com.alexleoreeves.novelapp.data.AppTheme
import com.alexleoreeves.novelapp.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UniversalReadScreen(currentTheme: AppTheme) {
    val scope = rememberCoroutineScope()
    val ttsController = remember { GeminiTtsController(BuildKonfig.GEMINI_API_KEY) }
    val isPlaying = ttsController.isPlaying.collectAsState()

    var pastedText by remember { mutableStateOf("") }
    var urlInput by remember { mutableStateOf("") }
    var activeTab by remember { mutableStateOf(0) }
    var isReading by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(currentTheme.backgroundColor())
    ) {
        // Header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(currentTheme.surfaceColor(), currentTheme.backgroundColor())
                    )
                )
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Column {
                Text(
                    "Universal Read",
                    style = MaterialTheme.typography.headlineLarge,
                    color = currentTheme.textColor(),
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Listen to anything — text, URLs, or documents",
                    style = MaterialTheme.typography.bodyMedium,
                    color = currentTheme.subTextColor()
                )
            }
        }

        // Tab selector
        TabRow(
            selectedTabIndex = activeTab,
            containerColor = currentTheme.surfaceColor(),
            contentColor = currentTheme.accentColor(),
            indicator = { tabPositions ->
                SecondaryIndicator(
                    Modifier.tabIndicatorOffset(tabPositions[activeTab]),
                    color = currentTheme.accentColor()
                )
            }
        ) {
            Tab(selected = activeTab == 0, onClick = { activeTab = 0 }) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.ContentPaste, null,
                        tint = if (activeTab == 0) currentTheme.accentColor() else currentTheme.subTextColor(),
                        modifier = Modifier.size(16.dp))
                    Text("Paste Text",
                        color = if (activeTab == 0) currentTheme.accentColor() else currentTheme.subTextColor())
                }
            }
            Tab(selected = activeTab == 1, onClick = { activeTab = 1 }) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Link, null,
                        tint = if (activeTab == 1) currentTheme.accentColor() else currentTheme.subTextColor(),
                        modifier = Modifier.size(16.dp))
                    Text("From URL",
                        color = if (activeTab == 1) currentTheme.accentColor() else currentTheme.subTextColor())
                }
            }
            Tab(selected = activeTab == 2, onClick = { activeTab = 2 }) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.UploadFile, null,
                        tint = if (activeTab == 2) currentTheme.accentColor() else currentTheme.subTextColor(),
                        modifier = Modifier.size(16.dp))
                    Text("Import File",
                        color = if (activeTab == 2) currentTheme.accentColor() else currentTheme.subTextColor())
                }
            }
        }

        var showFilePicker by remember { mutableStateOf(false) }
        var importedFileName by remember { mutableStateOf("") }
        var importedFileContent by remember { mutableStateOf("") }

        com.alexleoreeves.novelapp.ui.components.FilePicker(
            show = showFilePicker,
            onFileSelected = { name, content ->
                showFilePicker = false
                importedFileName = name
                importedFileContent = content
            },
            onDismiss = { showFilePicker = false }
        )

        // Content
        when (activeTab) {
            0 -> {
                // Paste text area
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = pastedText,
                        onValueChange = { pastedText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        placeholder = {
                            Text(
                                "Paste your text, article, or story here…",
                                color = currentTheme.subTextColor()
                            )
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = currentTheme.accentColor(),
                            unfocusedBorderColor = currentTheme.subTextColor().copy(0.5f),
                            focusedTextColor = currentTheme.textColor(),
                            unfocusedTextColor = currentTheme.textColor(),
                            cursorColor = currentTheme.accentColor(),
                            unfocusedContainerColor = currentTheme.cardColor().copy(0.5f),
                            focusedContainerColor = currentTheme.cardColor().copy(0.5f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )

                    if (pastedText.isNotEmpty()) {
                        Text(
                            "${pastedText.split("\\s+".toRegex()).size} words",
                            style = MaterialTheme.typography.labelSmall,
                            color = currentTheme.subTextColor()
                        )
                    }

                    Button(
                        onClick = {
                            if (pastedText.isNotEmpty()) {
                                scope.launch {
                                    isReading = true
                                    ttsController.readText(pastedText)
                                    isReading = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = currentTheme.accentColor()
                        ),
                        shape = RoundedCornerShape(12.dp),
                        enabled = pastedText.isNotEmpty() && !isPlaying.value
                    ) {
                        Icon(
                            if (isPlaying.value) Icons.Default.StopCircle else Icons.Default.PlayCircle,
                            null,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (isPlaying.value) "Stop Reading" else "Start Reading",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            1 -> {
                // URL input
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Article / Blog URL", color = currentTheme.subTextColor()) },
                        placeholder = { Text("https://...", color = currentTheme.subTextColor()) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = currentTheme.accentColor(),
                            unfocusedBorderColor = currentTheme.subTextColor().copy(0.5f),
                            focusedTextColor = currentTheme.textColor(),
                            unfocusedTextColor = currentTheme.textColor(),
                            cursorColor = currentTheme.accentColor()
                        ),
                        shape = RoundedCornerShape(12.dp),
                        leadingIcon = {
                            Icon(Icons.Default.Link, null,
                                tint = currentTheme.accentColor())
                        }
                    )

                    Button(
                        onClick = {
                            scope.launch {
                                isReading = true
                                ttsController.readText("Reading content from: $urlInput")
                                isReading = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = currentTheme.accentColor()
                        ),
                        shape = RoundedCornerShape(12.dp),
                        enabled = urlInput.isNotEmpty() && urlInput.startsWith("http")
                    ) {
                        Icon(Icons.Default.Download, null, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Fetch & Read",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Info card
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = currentTheme.cardColor()
                    ) {
                        Row(modifier = Modifier.padding(14.dp)) {
                            Icon(
                                Icons.Default.Info,
                                null,
                                tint = currentTheme.accentColor(),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "Paste the URL of any article, blog post, or webpage. The app will extract the text and read it to you using AI narration.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = currentTheme.subTextColor()
                            )
                        }
                    }
                }
            }
            2 -> {
                // File Import area
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(24.dp))
                    IconButton(
                        onClick = { showFilePicker = true },
                        modifier = Modifier
                            .size(100.dp)
                            .background(currentTheme.cardColor(), RoundedCornerShape(16.dp))
                            .border(2.dp, currentTheme.accentColor().copy(0.4f), RoundedCornerShape(16.dp))
                    ) {
                        Icon(
                            Icons.Default.UploadFile,
                            null,
                            tint = currentTheme.accentColor(),
                            modifier = Modifier.size(48.dp)
                        )
                    }
                    Text(
                        text = if (importedFileName.isNotEmpty()) "Selected: $importedFileName" else "Tap to choose a document",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = currentTheme.textColor()
                    )
                    Text(
                        text = "Supports TXT, PDF, EPUB, and documents",
                        style = MaterialTheme.typography.bodyMedium,
                        color = currentTheme.subTextColor()
                    )

                    Spacer(Modifier.weight(1f))

                    Button(
                        onClick = {
                            if (importedFileContent.isNotEmpty()) {
                                scope.launch {
                                    isReading = true
                                    ttsController.readText(importedFileContent)
                                    isReading = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = currentTheme.accentColor()
                        ),
                        shape = RoundedCornerShape(12.dp),
                        enabled = importedFileContent.isNotEmpty() && !isPlaying.value
                    ) {
                        Icon(
                            if (isPlaying.value) Icons.Default.StopCircle else Icons.Default.PlayCircle,
                            null,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (isPlaying.value) "Stop Reading" else "Start Reading",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
}
