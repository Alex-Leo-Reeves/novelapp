package com.alexleoreeves.novelapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.alexleoreeves.novelapp.ui.theme.accentColor
import com.alexleoreeves.novelapp.ui.theme.backgroundColor
import com.alexleoreeves.novelapp.ui.theme.cardColor
import com.alexleoreeves.novelapp.ui.theme.subTextColor
import com.alexleoreeves.novelapp.ui.theme.textColor
import com.alexleoreeves.novelapp.ui.theme.surfaceColor

@Composable
fun OtpScreen(
    email: String,
    isSubmitting: Boolean = false,
    errorMessage: String?,
    onClearError: () -> Unit,
    onVerify: (otp: String) -> Unit,
    onResend: () -> Unit,
    onBack: () -> Unit,
    currentTheme: AppTheme
) {
    var otp by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    fun validateAndSubmit() {
        localError = null
        onClearError()

        val clean = otp.trim()
        if (clean.length < 4) {
            localError = "Enter the OTP code sent to your email."
            return
        }
        onVerify(clean)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        currentTheme.surfaceColor(),
                        currentTheme.backgroundColor()
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
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = currentTheme.cardColor()),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp)
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.AutoStories,
                    contentDescription = null,
                    tint = currentTheme.accentColor()
                )
                Text(
                    "Verify your email",
                    style = MaterialTheme.typography.headlineMedium,
                    color = currentTheme.textColor(),
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center
                )
                Text(
                    "Enter the OTP code sent to",
                    style = MaterialTheme.typography.bodyMedium,
                    color = currentTheme.subTextColor(),
                    textAlign = TextAlign.Center
                )
                Text(
                    email,
                    style = MaterialTheme.typography.bodyLarge,
                    color = currentTheme.accentColor(),
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                OutlinedTextField(
                    value = otp,
                    onValueChange = { otp = it.take(8).filter { c -> c.isDigit() || c.isLetter() } },
                    label = { Text("OTP Code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = currentTheme.accentColor(),
                        unfocusedBorderColor = currentTheme.subTextColor().copy(alpha = 0.35f),
                        focusedTextColor = currentTheme.textColor(),
                        unfocusedTextColor = currentTheme.textColor(),
                        focusedLabelColor = currentTheme.accentColor(),
                        unfocusedLabelColor = currentTheme.subTextColor(),
                        cursorColor = currentTheme.accentColor()
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                val shownError = localError ?: errorMessage
                if (!shownError.isNullOrBlank()) {
                    Text(
                        shownError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFCF6679),
                        textAlign = TextAlign.Center
                    )
                }

                Button(
                    onClick = { validateAndSubmit() },
                    enabled = !isSubmitting,
                    colors = ButtonDefaults.buttonColors(containerColor = currentTheme.accentColor()),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        Text("Verify", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }

                TextButton(
                    onClick = {
                        localError = null
                        onClearError()
                        onResend()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Resend OTP", color = currentTheme.accentColor())
                }

                Spacer(Modifier.height(4.dp))

                TextButton(
                    onClick = {
                        localError = null
                        onClearError()
                        onBack()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Use a different email", color = currentTheme.subTextColor())
                }
            }
        }
    }
}
