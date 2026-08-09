package com.alexleoreeves.novelapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
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

@Composable
fun NewPasswordScreen(
    email: String,
    currentTheme: AppTheme,
    isSubmitting: Boolean = false,
    errorMessage: String?,
    onClearError: () -> Unit,
    onSetNewPassword: (newPassword: String) -> Unit,
    onCancel: () -> Unit
) {
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    fun validateAndSubmit() {
        localError = null
        onClearError()

        if (newPassword.length < 6) {
            localError = "Password must be at least 6 characters."
            return
        }
        if (newPassword != confirmPassword) {
            localError = "Passwords do not match."
            return
        }
        onSetNewPassword(newPassword)
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
                        Icons.Default.Key,
                        contentDescription = null,
                        tint = currentTheme.accentColor(),
                        modifier = Modifier.size(36.dp)
                    )
                }

                Text(
                    "Set New Password",
                    style = MaterialTheme.typography.headlineMedium,
                    color = currentTheme.textColor(),
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center
                )

                Text(
                    "Create a new password for\n$email",
                    style = MaterialTheme.typography.bodyMedium,
                    color = currentTheme.subTextColor(),
                    textAlign = TextAlign.Center
                )

                OutlinedTextField(
                    value = newPassword,
                    onValueChange = { newPassword = it; localError = null; onClearError() },
                    label = { Text("New Password") },
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
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it; localError = null; onClearError() },
                    label = { Text("Confirm Password") },
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
                            "Save New Password",
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                TextButton(onClick = onCancel, enabled = !isSubmitting) {
                    Text("Cancel", color = currentTheme.subTextColor())
                }
            }
        }
    }
}
