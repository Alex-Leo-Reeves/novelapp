package com.alexleoreeves.novelapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LockReset
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.alexleoreeves.novelapp.data.AppTheme
import com.alexleoreeves.novelapp.ui.theme.*

enum class ForgotPasswordMode(val label: String) {
    EMAIL_OTP("Email OTP"),
    RECOVERY_SECRET("Recovery Secret")
}

@Composable
fun ForgotPasswordScreen(
    currentTheme: AppTheme,
    isSubmitting: Boolean = false,
    errorMessage: String?,
    onClearError: () -> Unit,
    onSendOtp: (email: String) -> Unit,
    onRecoverWithSecret: (recoverySecret: String, newPassword: String) -> Unit,
    onBackToSignIn: () -> Unit
) {
    var mode by remember { mutableStateOf(ForgotPasswordMode.EMAIL_OTP) }
    var email by remember { mutableStateOf("") }
    var recoverySecret by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    fun validateAndSubmit() {
        localError = null
        onClearError()

        if (mode == ForgotPasswordMode.EMAIL_OTP) {
            if (!email.contains("@") || !email.contains(".")) {
                localError = "Enter a valid email address."
                return
            }
            onSendOtp(email.trim())
        } else {
            if (recoverySecret.trim().length < 10) {
                localError = "Recovery secret must be at least 10 characters."
                return
            }
            if (newPassword.length < 6) {
                localError = "New password must be at least 6 characters."
                return
            }
            onRecoverWithSecret(recoverySecret.trim(), newPassword)
        }
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
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(currentTheme.accentColor().copy(alpha = 0.14f), RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.LockReset,
                        contentDescription = null,
                        tint = currentTheme.accentColor(),
                        modifier = Modifier.size(36.dp)
                    )
                }

                Text(
                    "Reset Password",
                    style = MaterialTheme.typography.headlineMedium,
                    color = currentTheme.textColor(),
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center
                )

                Text(
                    "Choose how you want to reset your account password.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = currentTheme.subTextColor(),
                    textAlign = TextAlign.Center
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ForgotPasswordMode.entries.forEach { item ->
                        FilterChip(
                            selected = mode == item,
                            onClick = {
                                mode = item
                                localError = null
                                onClearError()
                            },
                            label = { Text(item.label) },
                            modifier = Modifier.weight(1f),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = currentTheme.accentColor(),
                                selectedLabelColor = Color.White,
                                containerColor = currentTheme.surfaceColor(),
                                labelColor = currentTheme.subTextColor()
                            )
                        )
                    }
                }

                if (mode == ForgotPasswordMode.EMAIL_OTP) {
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it; localError = null; onClearError() },
                        label = { Text("Email Address") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
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
                    Text(
                        "We will send a 6-digit verification code to your email.",
                        style = MaterialTheme.typography.bodySmall,
                        color = currentTheme.subTextColor(),
                        textAlign = TextAlign.Center
                    )
                } else {
                    OutlinedTextField(
                        value = recoverySecret,
                        onValueChange = { recoverySecret = it; localError = null; onClearError() },
                        label = { Text("Recovery Secret Key") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Next
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
                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = { newPassword = it; localError = null; onClearError() },
                        label = { Text("New Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
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
                }

                val shownError = localError ?: errorMessage
                if (!shownError.isNullOrBlank()) {
                    Text(
                        shownError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFEF4444),
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
                        Text(
                            if (mode == ForgotPasswordMode.EMAIL_OTP) "Send OTP Code" else "Reset Password",
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                TextButton(onClick = onBackToSignIn, enabled = !isSubmitting) {
                    Text("Back to Sign In", color = currentTheme.accentColor())
                }
            }
        }
    }
}
