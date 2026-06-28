package com.alexleoreeves.novelapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.alexleoreeves.novelapp.data.AppTheme
import com.alexleoreeves.novelapp.platform.DeveloperContact
import com.alexleoreeves.novelapp.ui.theme.accentColor
import com.alexleoreeves.novelapp.ui.theme.backgroundColor
import com.alexleoreeves.novelapp.ui.theme.cardColor
import com.alexleoreeves.novelapp.ui.theme.subTextColor
import com.alexleoreeves.novelapp.ui.theme.surfaceColor
import com.alexleoreeves.novelapp.ui.theme.textColor

enum class AuthMode(val label: String) {
    SIGN_IN("Sign in"),
    CREATE_ACCOUNT("Create account")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(
    currentTheme: AppTheme,
    isSubmitting: Boolean = false,
    errorMessage: String?,
    onClearError: () -> Unit,
    onSignIn: (email: String, password: String) -> Unit,
    onCreateAccount: (username: String, email: String, password: String) -> Unit,
    onDismiss: (() -> Unit)? = null
) {
    var mode by remember { mutableStateOf(AuthMode.CREATE_ACCOUNT) }
    var username by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    fun validateAndSubmit() {
        localError = null
        onClearError()

        if (mode == AuthMode.CREATE_ACCOUNT && username.isBlank()) {
            localError = "Enter a username."
            return
        }
        if (!email.contains("@") || !email.contains(".")) {
            localError = "Enter a valid email address."
            return
        }
        if (password.length < 6) {
            localError = "Password must be at least 6 characters."
            return
        }

        if (mode == AuthMode.CREATE_ACCOUNT) {
            onCreateAccount(username.trim(), email.trim(), password)
        } else {
            onSignIn(email.trim(), password)
        }
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
                    "Welcome to NovelApp",
                    style = MaterialTheme.typography.headlineMedium,
                    color = currentTheme.textColor(),
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center
                )
                Text(
                    "Sign in or create an account to continue.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = currentTheme.subTextColor(),
                    textAlign = TextAlign.Center
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    AuthMode.values().forEach { item ->
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

                if (mode == AuthMode.CREATE_ACCOUNT) {
                    AuthTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = "Username",
                        currentTheme = currentTheme
                    )
                }

                AuthTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = "Email",
                    currentTheme = currentTheme,
                    keyboardType = KeyboardType.Email
                )

                AuthTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = "Password",
                    currentTheme = currentTheme,
                    keyboardType = KeyboardType.Password,
                    isPassword = true
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
                        Text(mode.label, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }

                if (onDismiss != null) {
                    androidx.compose.material3.TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Browse as Guest",
                            color = currentTheme.accentColor(),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))

                Text(
                    "Developed by ${DeveloperContact.NAME} - ${DeveloperContact.EMAIL}",
                    style = MaterialTheme.typography.labelSmall,
                    color = currentTheme.subTextColor(),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun AuthLoadingScreen(
    currentTheme: AppTheme,
    message: String
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(currentTheme.backgroundColor())
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            CircularProgressIndicator(color = currentTheme.accentColor())
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = currentTheme.subTextColor(),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun AuthTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    currentTheme: AppTheme,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
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
}
