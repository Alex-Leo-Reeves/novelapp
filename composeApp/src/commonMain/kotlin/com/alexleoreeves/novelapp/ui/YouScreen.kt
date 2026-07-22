package com.alexleoreeves.novelapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.foundation.clickable
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.alexleoreeves.novelapp.audio.SherpaNarrationController
import com.alexleoreeves.novelapp.data.*
import com.alexleoreeves.novelapp.platform.*
import com.alexleoreeves.novelapp.ui.theme.*
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
    ttsController: SherpaNarrationController,
    favorites: List<FavoriteNovel> = emptyList(),
    onToggleFavorite: ((FavoriteNovel) -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val client = remember {
        platformHttpClient {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
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
            if (manifest.isAvailable) UpdateState.Available(manifest) else UpdateState.Current
        } catch (e: Exception) {
            UpdateState.Failed(e.message ?: "Update check failed")
        }
    }

    LaunchedEffect(Unit) { checkForUpdates() }

    LaunchedEffect(account.authToken) {
        runCatching { authApi.billingStatus(account.authToken) }
            .onSuccess { billingStatus = it; billingMessage = "" }
            .onFailure { billingMessage = it.message ?: "Subscription details unavailable." }
    }

    DisposableEffect(Unit) { onDispose { client.close() } }

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

    Box(modifier = Modifier.fillMaxSize()) {
        GlassBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(GlassOverlayColor)
                .verticalScroll(rememberScrollState())
        ) {
            // ── Header ───────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 18.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "You",
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        "Your account, downloads, and settings.",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 14.sp
                    )
                }
            }

            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Profile
                GlassCard(contentPadding = PaddingValues(0.dp)) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = NeonMagenta.copy(alpha = 0.18f),
                            modifier = Modifier.size(56.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    account.username.take(1).uppercase(),
                                    color = NeonMagenta,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                account.username,
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                account.email,
                                color = Color.White.copy(alpha = 0.5f),
                                fontSize = 13.sp
                            )
                        }
                    }
                }

                // Favorites
                if (favorites.isNotEmpty() && onToggleFavorite != null) {
                    GlassSectionLabel("Favorites (${favorites.size})")
                    favorites.forEach { fav ->
                        GlassCard(contentPadding = PaddingValues(0.dp)) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(fav.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(fav.sourceName, color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp)
                                }
                                IconButton(onClick = { onToggleFavorite(fav) }) {
                                    Icon(Icons.Default.Delete, "Remove favorite", tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }

                // Subscription
                GlassSectionLabel("Subscription")
                SubscriptionCard(
                    account = account,
                    billingStatus = billingStatus,
                    message = billingMessage,
                    onSubscribePlan = onSubscribePlan
                )

                // History
                GlassSectionLabel("History")
                GlassCard(
                    onClick = { showHistory = true },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Default.History, null, tint = NeonMagenta, modifier = Modifier.size(22.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("History", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "${downloadRepo.getWatchHistory().size} watched · ${downloadRepo.getReadHistory().size} read",
                                color = Color.White.copy(alpha = 0.45f),
                                fontSize = 11.sp
                            )
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = Color.White.copy(alpha = 0.3f))
                    }
                }

                // Downloads
                GlassSectionLabel("Downloads")
                val animeCount = downloadRepo.getAnimeItems().size
                val mangaCount = downloadRepo.getMangaItems().size
                val novelCount = downloadRepo.getNovelItems().size
                GlassCard(
                    onClick = { showDownloads = true },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Default.Download, null, tint = NeonMagenta, modifier = Modifier.size(22.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Offline library", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "$animeCount anime · $mangaCount manga · $novelCount novels",
                                color = Color.White.copy(alpha = 0.45f),
                                fontSize = 11.sp
                            )
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = Color.White.copy(alpha = 0.3f))
                    }
                }

                // Contact
                GlassSectionLabel("Contact Mike")
                ContactCard("Email", DeveloperContact.EMAIL, true) { linkOpener.open("mailto:${DeveloperContact.EMAIL}") }
                ContactCard("Telegram channel", DeveloperContact.TELEGRAM_CHANNEL_URL, DeveloperContact.TELEGRAM_CHANNEL_URL.isNotBlank()) { linkOpener.open(DeveloperContact.TELEGRAM_CHANNEL_URL) }
                ContactCard("WhatsApp channel", DeveloperContact.WHATSAPP_CHANNEL_URL, DeveloperContact.WHATSAPP_CHANNEL_URL.isNotBlank()) { linkOpener.open(DeveloperContact.WHATSAPP_CHANNEL_URL) }

                // Voice Settings
                GlassSectionLabel("Voice Settings")
                VoiceSettingsCard(ttsController)

                // App update
                GlassSectionLabel("App update")
                UpdateCard(
                    state = updateState,
                    onCheckAgain = { scope.launch { checkForUpdates() } },
                    onDownload = { url -> linkOpener.open(url.ifBlank { AppReleaseConfig.DOWNLOAD_URL }) }
                )

                // Sign out
                OutlinedButton(
                    onClick = onSignOut,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White.copy(alpha = 0.7f))
                ) {
                    Text("Sign out")
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    "Developed by ${DeveloperContact.NAME}",
                    color = NeonMagenta.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                Spacer(Modifier.height(100.dp))
            }
        }
    }
}

@Composable
private fun SubscriptionCard(
    account: SavedUserAccount,
    billingStatus: BillingStatus?,
    message: String,
    onSubscribePlan: (String) -> Unit
) {
    val plan = billingStatus?.currentPlan ?: account.plan
    val isPremium = billingStatus?.premium ?: account.isPremium
    val maxDevices = billingStatus?.maxDevices ?: account.maxDevices
    val plans = billingStatus?.plans.orEmpty().ifEmpty {
        listOf(
            BillingPlan(id = "premium_3_devices", label = "Premium 3 devices", amount = 1000, maxDevices = 3, description = "Full movies, cartoons, K-drama, and up to 3 signed-in devices."),
            BillingPlan(id = "premium_unlimited", label = "Premium unlimited", amount = 4000, maxDevices = null, description = "Full access and unlimited signed-in devices.")
        )
    }

    GlassCard(contentPadding = PaddingValues(0.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (isPremium) "Premium active" else "Free account",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Plan: ${plan.readablePlanName()}",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 11.sp
                    )
                }
                Surface(color = NeonMagenta.copy(alpha = 0.14f), shape = RoundedCornerShape(999.dp)) {
                    Text(
                        maxDevices?.let { "$it devices" } ?: "Unlimited",
                        color = NeonMagenta,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            Text(
                "Free accounts can stay signed in on 2 devices. Paid plans unlock full movies, cartoons, and K-drama.",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 13.sp
            )

            if (message.isNotBlank()) {
                Text(message, color = NeonMagenta, fontSize = 11.sp)
            }

            plans.forEach { paidPlan ->
                val active = isPremium && plan == paidPlan.id
                val label = "${paidPlan.label} · ₦${paidPlan.amount}/month"
                if (active) {
                    OutlinedButton(
                        onClick = {}, enabled = false,
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)
                    ) { Text("$label active") }
                } else {
                    Button(
                        onClick = { onSubscribePlan(paidPlan.id) },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonMagenta),
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)
                    ) { Text(label, color = Color.White, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

private fun String.readablePlanName(): String = when (this) {
    "premium", "premium_3_devices" -> "Premium 3 devices"
    "premium_unlimited" -> "Premium unlimited"
    else -> "Free"
}

@Composable
private fun ContactCard(title: String, subtitle: String, enabled: Boolean, onClick: () -> Unit) {
    GlassCard(
        onClick = if (enabled) onClick else null,
        contentPadding = PaddingValues(0.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Default.Link, null, tint = if (enabled) NeonMagenta else Color.White.copy(alpha = 0.3f), modifier = Modifier.size(20.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun UpdateCard(
    state: UpdateState,
    onCheckAgain: () -> Unit,
    onDownload: (String) -> Unit
) {
    GlassCard(contentPadding = PaddingValues(0.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (state) {
                    UpdateState.Checking -> CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = NeonMagenta)
                    is UpdateState.Available -> Icon(Icons.Default.Download, null, tint = NeonMagenta)
                    UpdateState.Current -> Icon(Icons.Default.Check, null, tint = Color(0xFF4CAF50))
                    else -> Icon(Icons.Default.Info, null, tint = Color.White.copy(alpha = 0.4f))
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
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Current version ${AppReleaseConfig.CURRENT_VERSION_NAME}",
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 11.sp
                    )
                }
            }

            if (state is UpdateState.Available && state.manifest.releaseNotes.isNotEmpty()) {
                Text(state.manifest.releaseNotes.joinToString(separator = "\n") { "- $it" }, color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onCheckAgain, enabled = state !is UpdateState.Checking,
                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)
                ) { Text("Check") }
                if (state is UpdateState.Available) {
                    Button(
                        onClick = { onDownload(state.manifest.apkUrl) },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonMagenta),
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)
                    ) { Text("Download", color = Color.White, fontWeight = FontWeight.Bold) }
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
private fun VoiceSettingsCard(ttsController: SherpaNarrationController) {
    val narrationSettings by ttsController.settings.collectAsState()

    GlassCard(contentPadding = PaddingValues(0.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            VoiceSelectorRow(
                label = "Narrator Voice",
                selectedVoiceId = narrationSettings.narratorVoiceId,
                onVoiceSelected = { newId -> ttsController.updateSettings { it.copy(narratorVoiceId = newId) } },
                onTestPlay = { ttsController.testVoice(narrationSettings.narratorVoiceId) }
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
            VoiceSelectorRow(
                label = "Overlay Voice",
                selectedVoiceId = narrationSettings.characterVoiceId,
                onVoiceSelected = { newId -> ttsController.updateSettings { it.copy(characterVoiceId = newId) } },
                onTestPlay = { ttsController.testVoice(narrationSettings.characterVoiceId) }
            )
        }
    }
}

@Composable
private fun VoiceSelectorRow(
    label: String,
    selectedVoiceId: Int,
    onVoiceSelected: (Int) -> Unit,
    onTestPlay: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            val voiceName = VCTK_VOICES.find { it.first == selectedVoiceId }?.second ?: "Voice $selectedVoiceId"
            Text(voiceName, color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp)
        }
        IconButton(onClick = onTestPlay, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.PlayArrow, "Test", tint = NeonMagenta, modifier = Modifier.size(20.dp))
        }
    }
}
