package com.alexleoreeves.novelapp.ui

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.alexleoreeves.novelapp.data.AppTheme
import com.alexleoreeves.novelapp.ui.theme.*

@Composable
fun OtpVerificationScreen(
    email: String,
    currentTheme: AppTheme,
    isSubmitting: Boolean = false,
    errorMessage: String?,
    onClearError: () -> Unit,
    onVerifyOtp: (code: String) -> Unit,
    onResendOtp: () -> Unit,
    onUseRecoverySecret: () -> Unit,
    onBack: () -> Unit
) {
    var otpCode by remember { mutableStateOf("") }

    // RGB animated light colors for border effect
    val infiniteTransition = rememberInfiniteTransition(label = "rgbBorder")
    val color1 by infiniteTransition.animateColor(
        initialValue = Color(0xFFFF0055),
        targetValue = Color(0xFF00FFCC),
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "color1"
    )
    val color2 by infiniteTransition.animateColor(
        initialValue = Color(0xFF00FFCC),
        targetValue = Color(0xFFFFCC00),
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "color2"
    )
    val color3 by infiniteTransition.animateColor(
        initialValue = Color(0xFF9900FF),
        targetValue = Color(0xFFFF0055),
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "color3"
    )

    val borderBrush = if (isSubmitting) {
        Brush.horizontalGradient(listOf(color1, color2, color3, color1))
    } else {
        Brush.horizontalGradient(listOf(currentTheme.accentColor(), currentTheme.accentColor()))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        currentTheme.backgroundColor(),
                        currentTheme.surfaceColor()
                    )
                )
            )
            .verticalScroll(rememberScrollState())
            .imePadding()
            .navigationBarsPadding()
            .padding(20.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = currentTheme.cardColor()),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp)
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(currentTheme.accentColor().copy(alpha = 0.14f), RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.MarkEmailRead,
                        contentDescription = null,
                        tint = currentTheme.accentColor(),
                        modifier = Modifier.size(36.dp)
                    )
                }

                Text(
                    "Enter Verification Code",
                    style = MaterialTheme.typography.headlineMedium,
                    color = currentTheme.textColor(),
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center
                )

                Text(
                    "We sent a 6-digit OTP code to\n$email",
                    style = MaterialTheme.typography.bodyMedium,
                    color = currentTheme.subTextColor(),
                    textAlign = TextAlign.Center
                )

                // OTP input with RGB animated border when submitting
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = if (isSubmitting) 3.dp else 1.dp,
                            brush = borderBrush,
                            shape = RoundedCornerShape(16.dp)
                        )
                        .background(currentTheme.surfaceColor(), RoundedCornerShape(16.dp))
                        .padding(8.dp)
                ) {
                    OutlinedTextField(
                        value = otpCode,
                        onValueChange = {
                            if (it.length <= 10) {
                                otpCode = it.filter { c -> c.isLetterOrDigit() }
                                onClearError()
                            }
                        },
                        label = { Text("Enter OTP Code") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword,
                            imeAction = ImeAction.Done
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            focusedTextColor = currentTheme.textColor(),
                            unfocusedTextColor = currentTheme.textColor(),
                            focusedLabelColor = currentTheme.accentColor(),
                            unfocusedLabelColor = currentTheme.subTextColor(),
                            cursorColor = currentTheme.accentColor()
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (isSubmitting) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            color = color1,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            "Verifying OTP code...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = color2,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (!errorMessage.isNullOrBlank()) {
                    Text(
                        errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFEF4444),
                        textAlign = TextAlign.Center
                    )
                }

                Button(
                    onClick = {
                        if (otpCode.isNotBlank()) {
                            onVerifyOtp(otpCode.trim())
                        }
                    },
                    enabled = !isSubmitting && otpCode.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = currentTheme.accentColor()),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Verify & Continue",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onResendOtp, enabled = !isSubmitting) {
                        Text("Resend Code", color = currentTheme.accentColor())
                    }
                    TextButton(onClick = onBack, enabled = !isSubmitting) {
                        Text("Back", color = currentTheme.subTextColor())
                    }
                }

                Divider(color = currentTheme.subTextColor().copy(alpha = 0.2f))

                TextButton(
                    onClick = onUseRecoverySecret,
                    enabled = !isSubmitting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Can't use OTP? Use recovery key",
                        color = currentTheme.accentColor(),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
