package com.alexleoreeves.novelapp.tv.payment

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.alexleoreeves.novelapp.tv.data.*
import com.alexleoreeves.novelapp.tv.platform.SavedUserAccount
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import java.util.Hashtable

@Composable
fun QrPaymentScreen(
    account: SavedUserAccount,
    planId: String,
    planLabel: String,
    planAmount: Int,
    onComplete: () -> Unit,
    onBack: () -> Unit
) {
    var checkout by remember { mutableStateOf<BillingCheckout?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var pollCount by remember { mutableStateOf(0) }

    LaunchedEffect(planId) {
        isLoading = true
        try {
            checkout = createCheckout(account.authToken, planId)
        } catch (e: Exception) {
            errorMessage = e.message ?: "Failed to create checkout"
        }
        isLoading = false
    }

    // Poll for payment verification
    LaunchedEffect(checkout?.txRef, pollCount) {
        val txRef = checkout?.txRef ?: return@LaunchedEffect
        if (pollCount > 60) return@LaunchedEffect // 5 minutes max
        delay(5_000)
        try {
            val status = billingStatus(account.authToken)
            val premium = status?.get("premium")?.jsonPrimitive?.booleanOrNull ?: false
            if (premium) {
                onComplete()
                return@LaunchedEffect
            }
        } catch (_: Exception) { }
        pollCount += 1
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF06060A))
            .padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (isLoading) {
            CircularProgressIndicator(color = Color(0xFF00BFFF), modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(16.dp))
            Text("Creating payment link...", color = Color.White.copy(0.7f))
        } else if (errorMessage != null) {
            Icon(Icons.Default.ErrorOutline, null, tint = Color(0xFFEF4444), modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(16.dp))
            Text(errorMessage!!, color = Color(0xFFEF4444), textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onBack) { Text("Try Again") }
        } else {
            val checkoutLink = checkout?.link ?: ""
            val qrBitmap = remember(checkoutLink) {
                if (checkoutLink.isNotBlank()) generateQrCode(checkoutLink, 512) else null
            }

            // App icon header
            Icon(
                Icons.Default.AutoStories, null,
                tint = Color(0xFF00BFFF),
                modifier = Modifier.size(48.dp)
            )

            Spacer(Modifier.height(16.dp))

            Text(
                "Subscribe to NovaRead",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Black,
                color = Color.White
            )

            Spacer(Modifier.height(8.dp))

            Surface(
                color = Color(0xFF1A1A2E),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        planLabel,
                        style = MaterialTheme.typography.titleLarge,
                        color = Color(0xFF00BFFF),
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "\u20A6$planAmount/month",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // QR Code
            if (qrBitmap != null) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    modifier = Modifier.size(280.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "Payment QR Code",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            Text(
                "Scan with your phone camera",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Spacer(Modifier.height(6.dp))

            Text(
                "You will be taken to Flutterwave to complete payment on your device.\nSign in with your NovaRead account to process the payment.\n\nEven if the TV turns off, your payment will still go through.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(0.7f),
                textAlign = TextAlign.Center,
                lineHeight = MaterialTheme.typography.bodyMedium.lineHeight
            )

            Spacer(Modifier.height(24.dp))

            // Progress indicator
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
                    "Waiting for payment verification...",
                    color = Color.White.copy(0.6f),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(Modifier.height(16.dp))

            OutlinedButton(
                onClick = onBack,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Back", color = Color.White)
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
