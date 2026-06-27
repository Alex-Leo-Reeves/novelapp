package com.alexleoreeves.novelapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alexleoreeves.novelapp.data.AppTheme
import com.alexleoreeves.novelapp.platform.AppReleaseConfig
import com.alexleoreeves.novelapp.platform.DeveloperContact
import com.alexleoreeves.novelapp.platform.ExternalLinkOpener
import com.alexleoreeves.novelapp.platform.SavedUserAccount
import com.alexleoreeves.novelapp.ui.theme.accentColor
import com.alexleoreeves.novelapp.ui.theme.backgroundColor
import com.alexleoreeves.novelapp.ui.theme.cardColor
import com.alexleoreeves.novelapp.ui.theme.subTextColor
import com.alexleoreeves.novelapp.ui.theme.surfaceColor
import com.alexleoreeves.novelapp.ui.theme.textColor
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class UpdateManifest(
    val versionCode: Int = AppReleaseConfig.CURRENT_VERSION_CODE,
    val versionName: String = AppReleaseConfig.CURRENT_VERSION_NAME,
    val apkUrl: String = AppReleaseConfig.DOWNLOAD_URL,
    val releaseNotes: List<String> = emptyList(),
    val forceUpdate: Boolean = false
)

private sealed class UpdateState {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data object Current : UpdateState()
    data class Available(val manifest: UpdateManifest) : UpdateState()
    data class Failed(val message: String) : UpdateState()
}

@Composable
fun YouScreen(
    account: SavedUserAccount,
    currentTheme: AppTheme,
    linkOpener: ExternalLinkOpener,
    onSignOut: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val client = remember {
        HttpClient {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    }
    var updateState by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }

    suspend fun checkForUpdates() {
        updateState = UpdateState.Checking
        updateState = try {
            val manifest = client.get(AppReleaseConfig.UPDATE_MANIFEST_URL).body<UpdateManifest>()
            if (manifest.versionCode > AppReleaseConfig.CURRENT_VERSION_CODE) {
                UpdateState.Available(manifest)
            } else {
                UpdateState.Current
            }
        } catch (e: Exception) {
            UpdateState.Failed(e.message ?: "Update check failed")
        }
    }

    LaunchedEffect(Unit) {
        checkForUpdates()
    }

    DisposableEffect(Unit) {
        onDispose { client.close() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(currentTheme.backgroundColor())
            .verticalScroll(rememberScrollState())
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(currentTheme.surfaceColor(), currentTheme.backgroundColor())
                    )
                )
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 18.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "You",
                    style = MaterialTheme.typography.headlineLarge,
                    color = currentTheme.textColor(),
                    fontWeight = FontWeight.Black
                )
                Text(
                    "Your account, contact links, and app updates.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = currentTheme.subTextColor()
                )
            }
        }

        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            ProfileCard(account = account, currentTheme = currentTheme)

            SectionTitle("Contact Mike", currentTheme)
            ContactCard(
                title = "Email",
                subtitle = DeveloperContact.EMAIL,
                enabled = true,
                currentTheme = currentTheme,
                onClick = { linkOpener.open("mailto:${DeveloperContact.EMAIL}") }
            )
            ContactCard(
                title = "Telegram channel",
                subtitle = DeveloperContact.TELEGRAM_CHANNEL_URL,
                enabled = DeveloperContact.TELEGRAM_CHANNEL_URL.isNotBlank(),
                currentTheme = currentTheme,
                onClick = { linkOpener.open(DeveloperContact.TELEGRAM_CHANNEL_URL) }
            )
            ContactCard(
                title = "WhatsApp channel",
                subtitle = DeveloperContact.WHATSAPP_CHANNEL_URL,
                enabled = DeveloperContact.WHATSAPP_CHANNEL_URL.isNotBlank(),
                currentTheme = currentTheme,
                onClick = { linkOpener.open(DeveloperContact.WHATSAPP_CHANNEL_URL) }
            )

            SectionTitle("App update", currentTheme)
            UpdateCard(
                state = updateState,
                currentTheme = currentTheme,
                onCheckAgain = {
                    scope.launch { checkForUpdates() }
                },
                onDownload = { url ->
                    linkOpener.open(url.ifBlank { AppReleaseConfig.DOWNLOAD_URL })
                }
            )

            OutlinedButton(
                onClick = onSignOut,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Sign out")
            }

            Spacer(Modifier.height(12.dp))

            Text(
                "Developed by ${DeveloperContact.NAME}",
                style = MaterialTheme.typography.titleMedium,
                color = currentTheme.accentColor(),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

@Composable
private fun ProfileCard(account: SavedUserAccount, currentTheme: AppTheme) {
    Card(
        colors = CardDefaults.cardColors(containerColor = currentTheme.cardColor()),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = currentTheme.accentColor().copy(alpha = 0.18f),
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        account.username.take(1).uppercase(),
                        color = currentTheme.accentColor(),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    account.username,
                    color = currentTheme.textColor(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    account.email,
                    color = currentTheme.subTextColor(),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, currentTheme: AppTheme) {
    Text(
        text,
        color = currentTheme.textColor(),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ContactCard(
    title: String,
    subtitle: String,
    enabled: Boolean,
    currentTheme: AppTheme,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        colors = CardDefaults.cardColors(containerColor = currentTheme.cardColor()),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Link,
                contentDescription = null,
                tint = if (enabled) currentTheme.accentColor() else currentTheme.subTextColor(),
                modifier = Modifier.size(22.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    color = currentTheme.textColor(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    subtitle,
                    color = currentTheme.subTextColor(),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun UpdateCard(
    state: UpdateState,
    currentTheme: AppTheme,
    onCheckAgain: () -> Unit,
    onDownload: (String) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = currentTheme.cardColor()),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (state) {
                    UpdateState.Checking -> CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = currentTheme.accentColor()
                    )
                    is UpdateState.Available -> Icon(Icons.Default.Download, null, tint = currentTheme.accentColor())
                    UpdateState.Current -> Icon(Icons.Default.Check, null, tint = Color(0xFF4CAF50))
                    else -> Icon(Icons.Default.Info, null, tint = currentTheme.subTextColor())
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        when (state) {
                            UpdateState.Idle -> "Ready to check"
                            UpdateState.Checking -> "Checking for updates"
                            UpdateState.Current -> "You are up to date"
                            is UpdateState.Available -> "Version ${state.manifest.versionName} is available"
                            is UpdateState.Failed -> "Could not check updates"
                        },
                        color = currentTheme.textColor(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Current version ${AppReleaseConfig.CURRENT_VERSION_NAME}",
                        color = currentTheme.subTextColor(),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            if (state is UpdateState.Available && state.manifest.releaseNotes.isNotEmpty()) {
                Text(
                    state.manifest.releaseNotes.joinToString(separator = "\n") { "- $it" },
                    color = currentTheme.subTextColor(),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onCheckAgain,
                    enabled = state !is UpdateState.Checking,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Check")
                }

                if (state is UpdateState.Available) {
                    Button(
                        onClick = { onDownload(state.manifest.apkUrl) },
                        colors = ButtonDefaults.buttonColors(containerColor = currentTheme.accentColor()),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Download", color = Color.White)
                    }
                }
            }
        }
    }
}
