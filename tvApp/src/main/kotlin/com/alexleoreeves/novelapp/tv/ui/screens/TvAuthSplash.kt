package com.alexleoreeves.novelapp.tv.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import com.alexleoreeves.novelapp.tv.platform.SavedUserAccount

@Composable
fun TvSplashScreen(onFinished: () -> Unit) {
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(2500); onFinished() }
    val pulse = rememberInfiniteTransition(label = "pulse")
    val glow by pulse.animateFloat(0.75f, 1.0f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "glow")

    Box(
        modifier = Modifier.fillMaxSize().background(
            Brush.radialGradient(listOf(Color(0xFF0F041C), Color(0xFF05050A), Color.Black))
        ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier.size(140.dp).graphicsLayer { scaleX = glow; scaleY = glow },
                contentAlignment = Alignment.Center
            ) {
                Box(modifier = Modifier.size(130.dp).background(
                    Brush.radialGradient(listOf(Color(0xFF7C3AED).copy(0.4f), Color.Transparent)), CircleShape
                ))
                Icon(Icons.Default.AutoStories, null, tint = Color(0xFF00BFFF), modifier = Modifier.size(72.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text("NovaRead TV", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Black, color = Color.White)
            Spacer(Modifier.height(8.dp))
            Text("Anime · Novels · Manga · Movies", style = MaterialTheme.typography.titleLarge, color = Color(0xFF00BFFF).copy(0.9f))
        }
    }
}

@Composable
fun TvAuthScreen(
    onSignIn: (String, String) -> Unit,
    onCreateAccount: (String, String, String, String) -> Unit,
    onDismiss: () -> Unit,
    isSubmitting: Boolean = false,
    externalError: String? = null
) {
    val isLogin = remember { mutableStateOf(true) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var recoverySecret by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf(externalError) }

    LaunchedEffect(externalError) { localError = externalError }

    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF06060A)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.widthIn(max = 500.dp).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(Icons.Default.AutoStories, null, tint = Color(0xFF00BFFF), modifier = Modifier.size(56.dp))
            Text(
                if (isLogin.value) "Sign In to NovaRead" else "Create Account",
                style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black, color = Color.White
            )

            if (localError != null) {
                Surface(color = Color(0xFF7F1D1D), shape = RoundedCornerShape(8.dp)) {
                    Text(localError!!, color = Color(0xFFFCA5A5), modifier = Modifier.padding(12.dp))
                }
            }

            if (!isLogin.value) {
                TvTextField(value = username, onValueChange = { username = it }, label = "Username")
            }

            TvTextField(value = email, onValueChange = { email = it }, label = "Email")
            TvTextField(value = password, onValueChange = { password = it }, label = "Password", isPassword = true)

            if (!isLogin.value) {
                TvTextField(value = recoverySecret, onValueChange = { recoverySecret = it }, label = "Recovery Secret (min 10 chars)")
            }

            Button(
                onClick = {
                    if (isLogin.value) onSignIn(email, password)
                    else onCreateAccount(username, email, password, recoverySecret)
                },
                enabled = !isSubmitting && email.isNotBlank() && password.length >= 6,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00BFFF)),
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                if (isSubmitting) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                else Text(if (isLogin.value) "Sign In" else "Create Account", fontWeight = FontWeight.Bold)
            }

            TextButton(onClick = { isLogin.value = !isLogin.value; localError = null }) {
                Text(
                    if (isLogin.value) "Don't have an account? Create one" else "Already have an account? Sign in",
                    color = Color(0xFF00BFFF)
                )
            }

            TextButton(onClick = onDismiss) {
                Text("Continue as Guest", color = Color.White.copy(0.6f))
            }
        }
    }
}

@Composable
private fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    isPassword: Boolean = false
) {
    var isFocused by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color(0xFF00BFFF),
            unfocusedBorderColor = Color.White.copy(0.2f),
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            cursorColor = Color(0xFF00BFFF),
            focusedLabelColor = Color(0xFF00BFFF),
            unfocusedLabelColor = Color.White.copy(0.5f)
        ),
        modifier = Modifier.fillMaxWidth().onFocusChanged { isFocused = it },
        shape = RoundedCornerShape(8.dp)
    )
}
