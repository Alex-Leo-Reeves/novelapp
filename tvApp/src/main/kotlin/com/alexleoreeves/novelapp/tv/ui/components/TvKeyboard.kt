package com.alexleoreeves.novelapp.tv.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.alexleoreeves.novelapp.tv.ui.theme.Purple500
import com.alexleoreeves.novelapp.tv.ui.theme.TvSubtext

private val KeyBlue = Color(0xFF00BFFF)
private val KeyGray = Color(0xFF2A2A3A)
private val KeyDark = Color(0xFF1A1A2A)
private val KeyText = Color(0xFFFFFFFF)

private val Row1 = listOf("Q","W","E","R","T","Y","U","I","O","P")
private val Row2 = listOf("A","S","D","F","G","H","J","K","L")
private val Row3 = listOf("Z","X","C","V","B","N","M")
private val RowNumbers = listOf("1","2","3","4","5","6","7","8","9","0")
private val RowSpecial = listOf("-","_","@",".",",","?","!","'", "\u0022","/","\\","#","$","%","&","*","(",")")

enum class KeyboardMode { LETTERS, NUMBERS, SPECIAL }

@Composable
fun TvSearchKeyboard(
    value: String,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
    onBack: () -> Unit,
    suggestions: List<String> = emptyList(),
    onSuggestionClick: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf(value) }
    var mode by remember { mutableStateOf(KeyboardMode.LETTERS) }
    val focusManager = LocalFocusManager.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF0C0C14))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Search display bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1A1A2E))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Search, null,
                tint = KeyBlue,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text.ifEmpty { "Search..." },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Normal,
                color = if (text.isEmpty()) TvSubtext else Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (text.isNotEmpty()) {
                IconButton(
                    onClick = { text = ""; onValueChange("") },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(Icons.Default.Close, null, tint = TvSubtext, modifier = Modifier.size(18.dp))
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Suggestions row
        if (suggestions.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                suggestions.forEach { sug ->
                    var isFocused by remember { mutableStateOf(false) }
                    Surface(
                        onClick = {
                            text = sug
                            onValueChange(sug)
                            onSearch()
                        },
                        shape = RoundedCornerShape(20.dp),
                        color = if (isFocused) KeyBlue.copy(0.3f) else Color(0xFF1A1A2E),
                        border = if (isFocused) BorderStroke(2.dp, KeyBlue) else null,
                        modifier = Modifier.onFocusChanged { isFocused = it.isFocused }
                    ) {
                        Text(
                            sug,
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // Keyboard grid
        val keys = when (mode) {
            KeyboardMode.LETTERS -> Row1 + Row2 + Row3
            KeyboardMode.NUMBERS -> RowNumbers
            KeyboardMode.SPECIAL -> RowSpecial
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(78.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            userScrollEnabled = false,
            modifier = Modifier.heightIn(max = 200.dp)
        ) {
            items(keys.size) { idx ->
                TvKeyButton(
                    key = keys[idx],
                    onClick = {
                        text += keys[idx]
                        onValueChange(text)
                    },
                    isSpecial = keys[idx].startsWith("&") || keys[idx] == "#"
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Bottom action row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Mode toggle
            ActionButton(
                label = when (mode) {
                    KeyboardMode.LETTERS -> "ABC"
                    KeyboardMode.NUMBERS -> "123"
                    KeyboardMode.SPECIAL -> "#+="
                },
                onClick = {
                    mode = when (mode) {
                        KeyboardMode.LETTERS -> KeyboardMode.NUMBERS
                        KeyboardMode.NUMBERS -> KeyboardMode.SPECIAL
                        KeyboardMode.SPECIAL -> KeyboardMode.LETTERS
                    }
                }
            )

            // Space
            ActionButton(
                label = "Space",
                onClick = { text += " "; onValueChange(text) },
                modifier = Modifier.weight(1f)
            )

            // Backspace
            ActionButton(
                label = "\u232B",
                onClick = {
                    if (text.isNotEmpty()) {
                        text = text.dropLast(1)
                        onValueChange(text)
                    }
                }
            )

            // Clear
            ActionButton(
                label = "Clear",
                onClick = { text = ""; onValueChange("") }
            )

            // Search / Enter
            var searchFocused by remember { mutableStateOf(false) }
            Surface(
                onClick = {
                    onValueChange(text)
                    onSearch()
                },
                shape = RoundedCornerShape(8.dp),
                color = if (searchFocused) KeyBlue.copy(0.8f) else Color(0xFF00BFFF),
                border = if (searchFocused) BorderStroke(2.dp, KeyBlue) else null,
                modifier = Modifier
                    .widthIn(min = 80.dp)
                    .onFocusChanged { searchFocused = it.isFocused }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.Search, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Text(
                        "Search",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (isFocused) KeyBlue.copy(0.4f) else KeyDark,
        border = if (isFocused) BorderStroke(2.dp, KeyBlue) else null,
        modifier = modifier.onFocusChanged { isFocused = it.isFocused }
    ) {
        Text(
            label,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
    }
}

@Composable
private fun TvKeyButton(
    key: String,
    onClick: () -> Unit,
    isSpecial: Boolean = false
) {
    var isFocused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        color = when {
            isFocused -> KeyBlue.copy(0.6f)
            isSpecial -> KeyGray.copy(0.6f)
            else -> KeyGray
        },
        border = if (isFocused) BorderStroke(2.dp, KeyBlue) else null,
        modifier = Modifier
            .aspectRatio(1.4f)
            .onFocusChanged { isFocused = it.isFocused }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                key,
                color = KeyText,
                fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
        }
    }
}
