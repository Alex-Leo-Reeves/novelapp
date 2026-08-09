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
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(2800); onFinished() }
    val pulse = rememberInfiniteTransition(label = "pulse")
    val glow by pulse.animateFloat(0.72f, 1.0f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "glow")
    val ringAlpha by pulse.animateFloat(0.25f, 0.7f, infiniteRepeatable(tween(1500), RepeatMode.Reverse), label = "ringAlpha")

    Box(
        modifier = Modifier.fillMaxSize().background(
            Brush.radialGradient(listOf(Color(0xFF170B2E), Color(0xFF05050A), Color.Black))
        ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier.size(150.dp),
                contentAlignment = Alignment.Center
            ) {
                // Outer animated ring
                Box(
                    modifier = Modifier.size(146.dp).graphicsLayer { scaleX = glow; scaleY = glow }
                        .border(2.dp, Color(0xFF00BFFF).copy(alpha = ringAlpha), CircleShape)
                )
                Box(
                    modifier = Modifier.size(130.dp).graphicsLayer { scaleX = glow; scaleY = glow },
                    contentAlignment = Alignment.Center
                ) {
                    Box(modifier = Modifier.size(126.dp).background(
                        Brush.radialGradient(listOf(Color(0xFF00BFFF).copy(0.35f), Color.Transparent)), CircleShape
                    ))
                    Icon(
                        Icons.Default.AutoStories, null,
                        tint = Color(0xFF00BFFF),
                        modifier = Modifier.size(70.dp)
                    )
                }
            }
            Spacer(Modifier.height(26.dp))
            Text(
                "NovaRead TV",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Black,
                color = Color.White,
                letterSpacing = 1.sp
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Anime · Novels · Manga · Movies",
                style = MaterialTheme.typography.titleLarge,
                color = Color(0xFF00BFFF).copy(0.9f)
            )
            Spacer(Modifier.height(40.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Developed by Mike",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF5C05D)
                )
                Text(
                    "masteralexleoreevesd1@gmail.com",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(0.55f)
                )
            }
        }
    }
}

@Composable
fun TvAuthScreen(
    onSignIn: (String, String) -> Unit,
    onCreateAccount: (String, String, String, String) -> Unit,
    onPhonePair: () -> Unit = {},
    isSubmitting: Boolean = false,
    externalError: String? = null
) {
    val isLogin = remember { mutableStateOf(true) }
    var useClassicSignup by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var recoverySecret by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf(externalError) }

    LaunchedEffect(externalError) { localError = externalError }

    Box(
        modifier = Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF0C0A18), Color(0xFF06060A), Color(0xFF050308)))
        ),
        contentAlignment = Alignment.Center
    ) {
        // Soft accent glow behind the card
        Box(
            modifier = Modifier.align(Alignment.Center).size(720.dp).background(
                Brush.radialGradient(
                    listOf(Color(0xFF00BFFF).copy(0.10f), Color.Transparent),
                    center = androidx.compose.ui.geometry.Offset(0.5f, 0.4f)
                ),
                CircleShape
            )
        )

        Column(
            modifier = Modifier.widthIn(max = 520.dp).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Logo + brand
            Box(
                modifier = Modifier.size(72.dp).background(Color(0xFF00BFFF).copy(0.12f), RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.AutoStories, null, tint = Color(0xFF00BFFF), modifier = Modifier.size(40.dp))
            }
            Text(
                if (isLogin.value) "Sign in to NovaRead" else "Create your account",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Black,
                color = Color.White
            )
            Text(
                if (isLogin.value) "Welcome back — pick up where you left off." else "Join NovaRead across all your devices.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(0.55f),
                textAlign = TextAlign.Center
            )

            if (localError != null) {
                Surface(
                    color = Color(0xFF3B0D0D),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Color(0xFFF87171).copy(0.35f))
                ) {
                    Text(
                        localError!!,
                        color = Color(0xFFFCA5A5),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }

            if (!isLogin.value && useClassicSignup) {
                TvTextField(value = username, onValueChange = { username = it }, label = "Username")
            }

            TvTextField(value = email, onValueChange = { email = it }, label = "Email")
            TvTextField(value = password, onValueChange = { password = it }, label = "Password", isPassword = true)

            if (!isLogin.value && useClassicSignup) {
                TvTextField(value = recoverySecret, onValueChange = { recoverySecret = it }, label = "Recovery Secret (min 10 chars)")
            }

            if (!isLogin.value) {
                TextButton(onClick = { useClassicSignup = !useClassicSignup; localError = null }) {
                    Text(
                        if (useClassicSignup) "Use Email OTP signup instead" else "Can't use OTP? Use recovery key",
                        color = Color(0xFF00BFFF),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Button(
                onClick = {
                    if (isLogin.value) {
                        onSignIn(email, password)
                    } else {
                        val finalUsername = if (useClassicSignup) username else email.substringBefore("@")
                        onCreateAccount(finalUsername, email, password, recoverySecret)
                    }
                },
                enabled = !isSubmitting && email.isNotBlank() && password.length >= 6,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00BFFF)),
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(12.dp)
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

            HorizontalDivider(color = Color.White.copy(0.12f))

            Button(
                onClick = onPhonePair,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF14141E)),
                border = BorderStroke(1.dp, Color(0xFF06D6A0).copy(0.7f)),
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.QrCodeScanner, null, tint = Color(0xFF06D6A0), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Log in with your phone", color = Color(0xFF06D6A0), fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(4.dp))

            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "Developed by Mike",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF5C05D)
                )
                Text(
                    "masteralexleoreevesd1@gmail.com",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(0.4f)
                )
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
            focusedContainerColor = Color(0xFF0E0E18),
            unfocusedContainerColor = Color(0xFF0C0C14),
            unfocusedBorderColor = Color.White.copy(0.18f),
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            cursorColor = Color(0xFF00BFFF),
            focusedLabelColor = Color(0xFF00BFFF),
            unfocusedLabelColor = Color.White.copy(0.5f)
        ),
        modifier = Modifier.fillMaxWidth().onFocusChanged { isFocused = it.isFocused },
        shape = RoundedCornerShape(10.dp)
    )
}
