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
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PlayCircle
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
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.alexleoreeves.novelapp.audio.SherpaNarrationController
import com.alexleoreeves.novelapp.data.AppTheme
import com.alexleoreeves.novelapp.data.AppUpdateManifest
import com.alexleoreeves.novelapp.data.AuthApi
import com.alexleoreeves.novelapp.data.BillingPlan
import com.alexleoreeves.novelapp.data.BillingStatus
import com.alexleoreeves.novelapp.data.LocalDownloadRepository
import com.alexleoreeves.novelapp.data.ReadHistoryItem
import com.alexleoreeves.novelapp.data.WatchHistoryItem
import com.alexleoreeves.novelapp.data.fetchAppUpdateManifest
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
import com.alexleoreeves.novelapp.platform.platformHttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

private sealed class UpdateState {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data object Current : UpdateState()
    data class Available(val manifest: AppUpdateManifest) : UpdateState()
    data class Failed(val message: String) : UpdateState()
}

@Composable
fun YouScreen(
    account: SavedUserAccount,
    currentTheme: AppTheme,
    downloadRepo: LocalDownloadRepository,
    linkOpener: ExternalLinkOpener,
    onPlayEpisode: (localPath: String, title: String) -> Unit,
    onReadMangaChapter: (localPath: String, title: String) -> Unit,
    onReadNovelChapter: (localPath: String, title: String, sourceName: String) -> Unit,
    onResumeRead: (ReadHistoryItem) -> Unit,
    onResumeWatch: (WatchHistoryItem) -> Unit,
    onSubscribePlan: (String) -> Unit,
    onSignOut: () -> Unit,
    ttsController: SherpaNarrationController
) {
    val scope = rememberCoroutineScope()
    val client = remember {
        platformHttpClient {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    }
    var updateState by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    var showDownloads by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    val authApi = remember { AuthApi() }
    var billingStatus by remember(account.authToken) { mutableStateOf<BillingStatus?>(null) }
    var billingMessage by remember(account.authToken) { mutableStateOf("") }

    suspend fun checkForUpdates() {
        updateState = UpdateState.Checking
        updateState = try {
            val manifest = fetchAppUpdateManifest(client) ?: error("Update manifest unavailable")
            if (manifest.isAvailable) {
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

    LaunchedEffect(account.authToken) {
        runCatching { authApi.billingStatus(account.authToken) }
            .onSuccess {
                billingStatus = it
                billingMessage = ""
            }
            .onFailure { billingMessage = it.message ?: "Subscription details unavailable." }
    }

    DisposableEffect(Unit) {
        onDispose { client.close() }
    }

    if (showHistory) {
        HistoryScreen(
            currentTheme = currentTheme,
            downloadRepo = downloadRepo,
            onResumeRead = onResumeRead,
            onResumeWatch = onResumeWatch,
            onBack = { showHistory = false }
        )
        return
    }

    if (showDownloads) {
        DownloadsScreen(
            currentTheme = currentTheme,
            downloadRepo = downloadRepo,
            onPlayEpisode = onPlayEpisode,
            onReadMangaChapter = onReadMangaChapter,
            onReadNovelChapter = onReadNovelChapter,
            onRootBack = { showDownloads = false }
        )
        return
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

            SectionTitle("Subscription", currentTheme)
            SubscriptionCard(
                account = account,
                billingStatus = billingStatus,
                message = billingMessage,
                currentTheme = currentTheme,
                onSubscribePlan = onSubscribePlan
            )

            SectionTitle("History", currentTheme)
            HistoryEntryCard(
                watchCount = downloadRepo.getWatchHistory().size,
                readCount = downloadRepo.getReadHistory().size,
                currentTheme = currentTheme,
                onOpen = { showHistory = true }
            )

            SectionTitle("Downloads", currentTheme)
            DownloadsEntryCard(
                downloadRepo = downloadRepo,
                currentTheme = currentTheme,
                onOpen = { showDownloads = true }
            )

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

            SectionTitle("Voice Settings", currentTheme)
            VoiceSettingsCard(ttsController, currentTheme)

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
@OptIn(ExperimentalMaterial3Api::class)
private fun HistoryEntryCard(
    watchCount: Int,
    readCount: Int,
    currentTheme: AppTheme,
    onOpen: () -> Unit
) {
    Card(
        onClick = onOpen,
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
                Icons.Default.History,
                contentDescription = null,
                tint = currentTheme.accentColor(),
                modifier = Modifier.size(24.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "History",
                    color = currentTheme.textColor(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "$watchCount watched · $readCount read",
                    color = currentTheme.subTextColor(),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Icon(Icons.Default.ChevronRight, null, tint = currentTheme.subTextColor())
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun DownloadsEntryCard(
    downloadRepo: LocalDownloadRepository,
    currentTheme: AppTheme,
    onOpen: () -> Unit
) {
    val animeCount = downloadRepo.getAnimeItems().size
    val mangaCount = downloadRepo.getMangaItems().size
    val novelCount = downloadRepo.getNovelItems().size

    Card(
        onClick = onOpen,
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
                Icons.Default.Download,
                contentDescription = null,
                tint = currentTheme.accentColor(),
                modifier = Modifier.size(24.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Offline library",
                    color = currentTheme.textColor(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "$animeCount anime · $mangaCount manga · $novelCount novels",
                    color = currentTheme.subTextColor(),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Icon(Icons.Default.ChevronRight, null, tint = currentTheme.subTextColor())
        }
    }
}

@Composable
private fun <T> HistoryList(
    items: List<T>,
    emptyText: String,
    currentTheme: AppTheme,
    row: @Composable (T) -> Unit
) {
    if (items.isEmpty()) {
        Card(
            colors = CardDefaults.cardColors(containerColor = currentTheme.cardColor().copy(alpha = 0.55f)),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.History, null, tint = currentTheme.subTextColor())
                Text(emptyText, color = currentTheme.subTextColor(), style = MaterialTheme.typography.bodyMedium)
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEach { row(it) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadHistoryCard(
    item: ReadHistoryItem,
    currentTheme: AppTheme,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = currentTheme.cardColor()),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (item.isManga) Icons.Default.MenuBook else Icons.Default.AutoStories,
                contentDescription = null,
                tint = currentTheme.accentColor()
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.title,
                    color = currentTheme.textColor(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    item.chapterTitle,
                    color = currentTheme.subTextColor(),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(Icons.Default.ChevronRight, null, tint = currentTheme.subTextColor())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WatchHistoryCard(
    item: WatchHistoryItem,
    currentTheme: AppTheme,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = currentTheme.cardColor()),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.PlayCircle, contentDescription = null, tint = Color(0xFFFF5722))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.title,
                    color = currentTheme.textColor(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    item.episodeTitle,
                    color = currentTheme.subTextColor(),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(Icons.Default.ChevronRight, null, tint = currentTheme.subTextColor())
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
private fun SubscriptionCard(
    account: SavedUserAccount,
    billingStatus: BillingStatus?,
    message: String,
    currentTheme: AppTheme,
    onSubscribePlan: (String) -> Unit
) {
    val plan = billingStatus?.currentPlan ?: account.plan
    val isPremium = billingStatus?.premium ?: account.isPremium
    val maxDevices = billingStatus?.maxDevices ?: account.maxDevices
    val plans = billingStatus?.plans.orEmpty().ifEmpty {
        listOf(
            BillingPlan(
                id = "premium_3_devices",
                label = "Premium 3 devices",
                amount = 1000,
                maxDevices = 3,
                description = "Full movies, cartoons, K-drama, and up to 3 signed-in devices."
            ),
            BillingPlan(
                id = "premium_unlimited",
                label = "Premium unlimited",
                amount = 4000,
                maxDevices = null,
                description = "Full access and unlimited signed-in devices."
            )
        )
    }

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
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (isPremium) "Premium active" else "Free account",
                        color = currentTheme.textColor(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Plan: ${plan.readablePlanName()}",
                        color = currentTheme.subTextColor(),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Surface(
                    color = currentTheme.accentColor().copy(alpha = 0.14f),
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Text(
                        maxDevices?.let { "$it devices" } ?: "Unlimited",
                        color = currentTheme.accentColor(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            Text(
                "Free accounts can stay signed in on 2 devices. Paid plans unlock full movies, cartoons, and K-drama.",
                color = currentTheme.subTextColor(),
                style = MaterialTheme.typography.bodySmall
            )

            if (message.isNotBlank()) {
                Text(
                    message,
                    color = currentTheme.accentColor(),
                    style = MaterialTheme.typography.labelSmall
                )
            }

            plans.forEach { paidPlan ->
                val active = isPremium && plan == paidPlan.id
                val label = "${paidPlan.label} · ₦${paidPlan.amount}/month"
                if (active) {
                    OutlinedButton(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("$label active")
                    }
                } else {
                    Button(
                        onClick = { onSubscribePlan(paidPlan.id) },
                        colors = ButtonDefaults.buttonColors(containerColor = currentTheme.accentColor()),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(label, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private fun String.readablePlanName(): String =
    when (this) {
        "premium", "premium_3_devices" -> "Premium 3 devices"
        "premium_unlimited" -> "Premium unlimited"
        else -> "Free"
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
                        Text("Download", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private val VCTK_VOICES = listOf(
    Pair(0, "Female 1 (Clear)"),
    Pair(5, "Female 2"),
    Pair(6, "Female 3"),
    Pair(17, "Male 1 (Clear)"),
    Pair(18, "Male 2"),
    Pair(14, "Male 3")
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun VoiceSettingsCard(
    ttsController: SherpaNarrationController,
    currentTheme: AppTheme
) {
    val narrationSettings by ttsController.settings.collectAsState()
    
    Card(
        colors = CardDefaults.cardColors(containerColor = currentTheme.cardColor()),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            VoiceSelectorRow(
                label = "Narrator Voice",
                selectedVoiceId = narrationSettings.narratorVoiceId,
                currentTheme = currentTheme,
                onVoiceSelected = { newId ->
                    ttsController.updateSettings { it.copy(narratorVoiceId = newId) }
                },
                onTestPlay = { ttsController.testVoice(narrationSettings.narratorVoiceId) }
            )
            
            androidx.compose.material3.HorizontalDivider(color = currentTheme.subTextColor().copy(alpha = 0.2f))
            
            VoiceSelectorRow(
                label = "Character Voice",
                selectedVoiceId = narrationSettings.characterVoiceId,
                currentTheme = currentTheme,
                onVoiceSelected = { newId ->
                    ttsController.updateSettings { it.copy(characterVoiceId = newId) }
                },
                onTestPlay = { ttsController.testVoice(narrationSettings.characterVoiceId) }
            )
        }
    }
}

@Composable
private fun VoiceSelectorRow(
    label: String,
    selectedVoiceId: Int,
    currentTheme: AppTheme,
    onVoiceSelected: (Int) -> Unit,
    onTestPlay: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedVoiceName = VCTK_VOICES.find { it.first == selectedVoiceId }?.second ?: "Voice $selectedVoiceId"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = currentTheme.textColor(),
            style = MaterialTheme.typography.bodyMedium
        )
        
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                Text(
                    text = selectedVoiceName,
                    color = currentTheme.accentColor(),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .clickable { expanded = true }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
                androidx.compose.material3.DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.background(currentTheme.surfaceColor())
                ) {
                    VCTK_VOICES.forEach { (id, name) ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(name, color = currentTheme.textColor()) },
                            onClick = {
                                onVoiceSelected(id)
                                expanded = false
                            }
                        )
                    }
                }
            }
            
            androidx.compose.material3.IconButton(onClick = onTestPlay, modifier = Modifier.size(24.dp)) {
                Icon(
                    Icons.Default.PlayCircle,
                    contentDescription = "Test Play",
                    tint = currentTheme.accentColor(),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
