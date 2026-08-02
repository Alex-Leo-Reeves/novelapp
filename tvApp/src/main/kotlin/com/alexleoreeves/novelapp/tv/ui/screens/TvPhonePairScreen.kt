package com.alexleoreeves.novelapp.tv.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import com.alexleoreeves.novelapp.data.ApiConfig
import com.alexleoreeves.novelapp.data.TvPairStart
import com.alexleoreeves.novelapp.data.TvPairPollState
import com.alexleoreeves.novelapp.data.startTvPair
import com.alexleoreeves.novelapp.data.pollTvPairStatus
import com.alexleoreeves.novelapp.tv.platform.SavedUserAccount
import com.alexleoreeves.novelapp.tv.platform.UserSessionStore
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import java.util.Hashtable

@Composable
fun TvPhonePairScreen(
    sessionStore: UserSessionStore,
    onApproved: (SavedUserAccount) -> Unit,
    onBack: () -> Unit
) {
    var pair by remember { mutableStateOf<TvPairStart?>(null) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isStarting by remember { mutableStateOf(true) }
    var isExpired by remember { mutableStateOf(false) }
    var pairAttempt by remember { mutableStateOf(0) }

    // Start a new pairing session on first composition and every retry.
    LaunchedEffect(pairAttempt) {
        isStarting = true
        isExpired = false
        errorMessage = null
        pair = null
        qrBitmap = null
        try {
            val result = startTvPair()
            pair = result
            qrBitmap = generateQrCode(
                result.qrContent.ifBlank { "${ApiConfig.SITE_BASE_URL}/tv-pair.html" },
                512
            )
        } catch (e: Exception) {
            errorMessage = e.message ?: "Could not start TV pairing."
        }
        isStarting = false
    }

    // Poll until the phone approves, the code expires, or this screen leaves.
    LaunchedEffect(pair?.pairId, pairAttempt) {
        val pairId = pair?.pairId ?: return@LaunchedEffect
        while (true) {
            delay(2_500)
            when (val state = pollTvPairStatus(pairId)) {
                is TvPairPollState.Approved -> {
                    sessionStore.saveAccount(state.account)
                    onApproved(state.account)
                    return@LaunchedEffect
                }
                is TvPairPollState.Expired -> {
                    isExpired = true
                    return@LaunchedEffect
                }
                is TvPairPollState.Pending -> Unit
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF06060A)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            var backFocused by remember { mutableStateOf(false) }
            Surface(
                onClick = onBack,
                shape = RoundedCornerShape(10.dp),
                color = if (backFocused) Color(0xFF1C1C2E) else Color.Transparent,
                border = if (backFocused) BorderStroke(2.dp, Color(0xFF00BFFF)) else null,
                modifier = Modifier
                    .align(Alignment.Start)
                    .onFocusChanged { backFocused = it.isFocused }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.ArrowBack, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Text("Back", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                }
            }

            when {
                isStarting -> {
                    CircularProgressIndicator(color = Color(0xFF00BFFF), modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("Starting TV pairing...", color = Color.White.copy(0.7f))
                }

                errorMessage != null -> {
                    Icon(Icons.Default.ErrorOutline, null, tint = Color(0xFFEF4444), modifier = Modifier.size(64.dp))
                    Text(errorMessage!!, color = Color(0xFFEF4444), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { pairAttempt += 1 }) { Text("Try Again") }
                }

                isExpired -> {
                    Icon(Icons.Default.TimerOff, null, tint = Color.White.copy(0.4f), modifier = Modifier.size(64.dp))
                    Text(
                        "This pairing code has expired.",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Black,
                        color = Color.White
                    )
                    Text("Start a new one to sign in with your phone.", color = Color.White.copy(0.6f))
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { pairAttempt += 1 }) { Text("Start Again") }
                }

                pair != null -> {
                    val activePair = pair!!

                    Icon(Icons.Default.AutoStories, null, tint = Color(0xFF00BFFF), modifier = Modifier.size(56.dp))
                    Text(
                        "Sign in with your phone",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Black,
                        color = Color.White
                    )
                    Text(
                        "Scan this QR code with the NovaRead app on your phone, or open the link on your phone's browser.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(0.6f),
                        textAlign = TextAlign.Center
                    )

                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        modifier = Modifier.size(280.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (qrBitmap != null) {
                                Image(
                                    bitmap = qrBitmap!!.asImageBitmap(),
                                    contentDescription = "TV pairing QR code",
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                CircularProgressIndicator(color = Color(0xFF00BFFF))
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    Surface(
                        color = Color(0xFF14141E),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFF00BFFF).copy(0.4f))
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "OR ENTER THIS CODE",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(0.5f)
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                activePair.code.ifBlank { "----" }.split("").joinToString(" ").trim(),
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFF00BFFF),
                                letterSpacing = 8.sp
                            )
                        }
                    }

                    Text(
                        "Waiting for phone approval...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(0.5f)
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFF06D6A0)
                        )
                        Text(
                            "Code expires in ${activePair.expiresInSeconds}s",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(0.5f)
                        )
                    }
                }
            }
        }
    }
}

private fun generateQrCode(content: String, size: Int): Bitmap {
    val hints = Hashtable<EncodeHintType, Any>().apply {
        put(EncodeHintType.MARGIN, 1)
        put(EncodeHintType.CHARACTER_SET, "UTF-8")
    }
    val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bitmap.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
    return bitmap
}
