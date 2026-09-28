package com.alexleoreeves.novelapp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
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

private data class VctkVoice(
    val id: Int,
    val label: String,
    val description: String,
    val isFemale: Boolean
)

private val VCTK_VOICES = listOf(
    // ── Female voices ──────────────────────────────────────────────────────
    VctkVoice(0,  "Clara",     "British · Clear & Composed",        true),
    VctkVoice(1,  "Ava",       "British · Warm & Gentle",           true),
    VctkVoice(2,  "Fiona",     "Scottish · Storyteller tone",       true),
    VctkVoice(3,  "Nadia",     "British · Crisp & Expressive",      true),
    VctkVoice(5,  "Isla",      "British · Silky & Calm",            true),
    VctkVoice(6,  "Zoe",       "Southern English · Bright & Lively",true),
    VctkVoice(7,  "Priya",     "British Indian · Distinctive",      true),
    VctkVoice(9,  "Rachel",    "Welsh · Melodic & Soothing",        true),
    VctkVoice(11, "Amelia",    "Northern · Earthy & Rich",          true),
    VctkVoice(13, "Harper",    "British · Academic & Refined",      true),
    // ── Male voices ────────────────────────────────────────────────────────
    VctkVoice(14, "Liam",      "British · Deep & Authoritative",    false),
    VctkVoice(15, "Callum",    "Scottish · Rugged & Confident",     false),
    VctkVoice(16, "Marcus",    "British · Smooth & Measured",       false),
    VctkVoice(17, "Ethan",     "British · Clear & Balanced",        false),
    VctkVoice(18, "Finn",      "Northern · Gruff & Warm",           false),
    VctkVoice(20, "Hugo",      "British · Resonant & Bold",         false),
    VctkVoice(22, "Connor",    "Irish-tinted · Storytelling flair", false),
    VctkVoice(24, "Adrian",    "British · Steady & Trustworthy",    false),
    VctkVoice(26, "Oscar",     "Southern English · Youthful",       false),
    VctkVoice(28, "Sebastian", "British · Narrator-grade baritone", false),
)

@Composable
fun YouScreen(
    account: SavedUserAccount,
    currentTheme: AppTheme,
    downloadRepo: LocalDownloadRepository,
    linkOpener: ExternalLinkOpener,
    updateTarget: AppUpdateTarget = AppUpdateTarget.ANDROID,
    onPlayEpisode: (localPath: String, title: String) -> Unit,
    onReadMangaChapter: (localPath: String, title: String) -> Unit,
    onReadNovelChapter: (localPath: String, title: String, sourceName: String) -> Unit,
    onResumeRead: (ReadHistoryItem) -> Unit,
    onResumeWatch: (WatchHistoryItem) -> Unit,
    /**
     * Start a checkout. [currency] is null when the user hasn't picked one, in
     * which case the server infers it from Accept-Language.
     */
    onSubscribePlan: (planId: String, currency: String?) -> Unit,
    onSignOut: () -> Unit,
    ttsController: SherpaNarrationController,
    favorites: List<FavoriteNovel> = emptyList(),
    onToggleFavorite: ((FavoriteNovel) -> Unit)? = null,
    onSwitchProfile: () -> Unit = {},
    // ── App Settings (persisted through AppSettingsStore) ────────────────
    showServerSelectors: Boolean = false,
    onShowServerSelectorsChange: (Boolean) -> Unit = {},
    activeLanguage: AppLanguage = AppLanguage.SYSTEM,
    deviceLanguageName: String = "English",
    onLanguageChange: (AppLanguage) -> Unit = {}
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
    // null = let the server infer the currency from Accept-Language, so
    // Nigerian users keep seeing naira without touching anything.
    var selectedCurrency by remember(account.authToken) { mutableStateOf<String?>(null) }

    suspend fun checkForUpdates() {
        updateState = UpdateState.Checking
        updateState = try {
            val manifest = fetchAppUpdateManifest(client, updateTarget) ?: error("Update manifest unavailable")
            if (manifest.isAvailableFor(updateTarget)) UpdateState.Available(manifest) else UpdateState.Current
        } catch (e: Exception) {
            UpdateState.Failed(e.message ?: "Update check failed")
        }
    }

    // iOS ships outside the App Store — the in-app updater (and its automatic
    // check) only exists for the Android/TV/desktop self-install channels.
    LaunchedEffect(Unit) { if (updateTarget != AppUpdateTarget.IOS) checkForUpdates() }

    LaunchedEffect(account.authToken, selectedCurrency) {
        runCatching { authApi.billingStatus(account.authToken, selectedCurrency) }
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
            isPremium = account.isPremium == true || billingStatus?.premium == true,
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
                            color = NeonBlue.copy(alpha = 0.18f),
                            modifier = Modifier.size(56.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    account.username.take(1).uppercase(),
                                    color = NeonBlue,
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
                        IconButton(onClick = onSwitchProfile) {
                            Icon(Icons.Default.People, "Switch Profile", tint = NeonBlue, modifier = Modifier.size(24.dp))
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
                    onSubscribePlan = onSubscribePlan,
                    selectedCurrency = selectedCurrency,
                    onCurrencyChange = { selectedCurrency = it }
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
                        Icon(Icons.Default.History, null, tint = NeonBlue, modifier = Modifier.size(22.dp))
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
                        Icon(Icons.Default.Download, null, tint = NeonBlue, modifier = Modifier.size(22.dp))
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

                // App Settings — persisted preferences (language follows the
                // device unless the user overrides it).
                GlassSectionLabel("App Settings")
                AppSettingsCard(
                    showServerSelectors = showServerSelectors,
                    onShowServerSelectorsChange = onShowServerSelectorsChange,
                    activeLanguage = activeLanguage,
                    deviceLanguageName = deviceLanguageName,
                    onLanguageChange = onLanguageChange
                )

                // Contact
                GlassSectionLabel("Contact Mike")
                ContactCard("Email", DeveloperContact.EMAIL, true) { linkOpener.open("mailto:${DeveloperContact.EMAIL}") }
                ContactCard("Telegram channel", DeveloperContact.TELEGRAM_CHANNEL_URL, DeveloperContact.TELEGRAM_CHANNEL_URL.isNotBlank()) { linkOpener.open(DeveloperContact.TELEGRAM_CHANNEL_URL) }
                ContactCard("WhatsApp channel", DeveloperContact.WHATSAPP_CHANNEL_URL, DeveloperContact.WHATSAPP_CHANNEL_URL.isNotBlank()) { linkOpener.open(DeveloperContact.WHATSAPP_CHANNEL_URL) }

                // Voice Settings
                GlassSectionLabel("Voice Settings")
                VoiceSettingsCard(ttsController)

                // App update — hidden on iOS: the IPA has no self-update channel.
                if (updateTarget != AppUpdateTarget.IOS) {
                    GlassSectionLabel("App update")
                    UpdateCard(
                        state = updateState,
                        updateTarget = updateTarget,
                        onCheckAgain = { scope.launch { checkForUpdates() } },
                        onDownload = { url -> linkOpener.open(url.ifBlank { AppReleaseConfig.DOWNLOAD_URL }) }
                    )
                }

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
                    color = NeonBlue.copy(alpha = 0.7f),
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
    onSubscribePlan: (planId: String, currency: String?) -> Unit,
    selectedCurrency: String?,
    onCurrencyChange: (String?) -> Unit
) {
    val plan = billingStatus?.currentPlan ?: account.plan
    val isPremium = billingStatus?.premium ?: account.isPremium
    val maxDevices = billingStatus?.maxDevices ?: account.maxDevices
    val plans = billingStatus?.plans.orEmpty().ifEmpty {
        listOf(
            BillingPlan(id = "premium_3_devices", label = "Premium 3 devices", amount = 1000, maxDevices = 3, priceLabel = "\u20A61,000", description = "Full movies, cartoons, K-drama, and up to 3 signed-in devices."),
            BillingPlan(id = "premium_unlimited", label = "Premium unlimited", amount = 4000, maxDevices = null, priceLabel = "\u20A64,000", description = "Full access and unlimited signed-in devices.")
        )
    }
    val currencies = billingStatus?.supportedCurrencies.orEmpty()
    val activeCurrency = selectedCurrency ?: billingStatus?.selectedCurrency ?: "NGN"

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
                Surface(color = NeonBlue.copy(alpha = 0.14f), shape = RoundedCornerShape(999.dp)) {
                    Text(
                        maxDevices?.let { "$it devices" } ?: "Unlimited",
                        color = NeonBlue,
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

            // Currency switcher — only when the storefront offers a choice.
            if (currencies.size > 1) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Paying from",
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 11.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        currencies.forEach { option ->
                            val isActiveCurrency = option.code == activeCurrency
                            if (isActiveCurrency) {
                                Button(
                                    onClick = { onCurrencyChange(option.code) },
                                    colors = ButtonDefaults.buttonColors(containerColor = NeonBlue),
                                    shape = RoundedCornerShape(999.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
                                ) {
                                    Text("${option.symbol} ${option.code}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { onCurrencyChange(option.code) },
                                    shape = RoundedCornerShape(999.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
                                ) {
                                    Text("${option.symbol} ${option.code}", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    if (activeCurrency != (billingStatus?.selectedCurrency ?: "NGN")) {
                        Text(
                            "Charged in $activeCurrency. Your bank may add a conversion fee.",
                            color = Color.White.copy(alpha = 0.35f),
                            fontSize = 10.sp
                        )
                    }
                }
            }

            if (message.isNotBlank()) {
                Text(message, color = NeonBlue, fontSize = 11.sp)
            }

            plans.forEach { paidPlan ->
                val active = isPremium && plan == paidPlan.id
                val label = "${paidPlan.label} · ${paidPlan.displayPrice()}/month"
                if (active) {
                    OutlinedButton(
                        onClick = {}, enabled = false,
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)
                    ) { Text("$label active") }
                } else {
                    Button(
                        onClick = { onSubscribePlan(paidPlan.id, selectedCurrency) },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonBlue),
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
            Icon(Icons.Default.Link, null, tint = if (enabled) NeonBlue else Color.White.copy(alpha = 0.3f), modifier = Modifier.size(20.dp))
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
    updateTarget: AppUpdateTarget,
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
                    UpdateState.Checking -> CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = NeonBlue)
                    is UpdateState.Available -> Icon(Icons.Default.Download, null, tint = NeonBlue)
                    UpdateState.Current -> Icon(Icons.Default.Check, null, tint = Color(0xFF4CAF50))
                    else -> Icon(Icons.Default.Info, null, tint = Color.White.copy(alpha = 0.4f))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        when (state) {
                            UpdateState.Idle -> "Ready to check"
                            UpdateState.Checking -> "Checking for updates"
                            UpdateState.Current -> "You are up to date"
                            is UpdateState.Available -> "Version ${state.manifest.versionNameFor(updateTarget)} is available"
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

            if (state is UpdateState.Available) {
                val targetNotes = state.manifest.releaseNotesFor(updateTarget)
                if (targetNotes.isNotEmpty()) {
                    Text(targetNotes.joinToString(separator = "\n") { "- $it" }, color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onCheckAgain, enabled = state !is UpdateState.Checking,
                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)
                ) { Text("Check") }
                if (state is UpdateState.Available) {
                    Button(
                        onClick = { onDownload(state.manifest.downloadUrlFor(updateTarget)) },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonBlue),
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)
                    ) { Text("Download", color = Color.White, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Voice Settings — now with popup dialog for voice selection + test hearing
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun VoiceSettingsCard(ttsController: SherpaNarrationController) {
    val narrationSettings by ttsController.settings.collectAsState()

    GlassCard(contentPadding = PaddingValues(0.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Narrator voice
            VoiceSelectorRow(
                label = "Narrator Voice",
                selectedVoiceId = narrationSettings.narratorVoiceId,
                onVoiceSelected = { newId -> ttsController.updateSettings { it.copy(narratorVoiceId = newId) } },
                onTestPlay = { ttsController.testVoice(narrationSettings.narratorVoiceId) },
                ttsController = ttsController
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
            // Character voice
            VoiceSelectorRow(
                label = "Dialogue Voice",
                selectedVoiceId = narrationSettings.characterVoiceId,
                onVoiceSelected = { newId -> ttsController.updateSettings { it.copy(characterVoiceId = newId) } },
                onTestPlay = { ttsController.testVoice(narrationSettings.characterVoiceId) },
                ttsController = ttsController
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
            // Volume slider
            VolumeSliderRow(
                label = "Narration Volume",
                currentVolume = narrationSettings.narratorVolume,
                onVolumeChange = { newVol -> ttsController.updateSettings { it.copy(narratorVolume = newVol) } }
            )
        }
    }
}

@Composable
private fun VoiceSelectorRow(
    label: String,
    selectedVoiceId: Int,
    onVoiceSelected: (Int) -> Unit,
    onTestPlay: () -> Unit,
    ttsController: SherpaNarrationController
) {
    var showDialog by remember { mutableStateOf(false) }
    val isTesting by ttsController.isTestingVoice.collectAsState()
    var previewingVoiceId by remember { mutableStateOf<Int?>( null) }

    // When the test finishes, clear the previewing id
    LaunchedEffect(isTesting) {
        if (!isTesting) previewingVoiceId = null
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showDialog = true },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            val voiceName = VCTK_VOICES.find { it.id == selectedVoiceId }?.label ?: "Voice $selectedVoiceId"
            val voiceDesc = VCTK_VOICES.find { it.id == selectedVoiceId }?.description ?: ""
            Text(voiceName, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            if (voiceDesc.isNotBlank()) {
                Text(voiceDesc, color = Color.White.copy(alpha = 0.4f), fontSize = 10.sp)
            }
        }
        // Preview play/loading button (top-level row; only active for selected voice)
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(36.dp)) {
            if (isTesting && previewingVoiceId == selectedVoiceId) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = NeonBlue,
                    strokeWidth = 2.dp
                )
            } else {
                IconButton(
                    onClick = {
                        previewingVoiceId = selectedVoiceId
                        onTestPlay()
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, "Preview", tint = NeonBlue, modifier = Modifier.size(20.dp))
                }
            }
        }
        Icon(Icons.Default.ChevronRight, null, tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.size(18.dp))
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = {
                Text("Select $label", color = Color.White, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // Section headers
                    Text(
                        "Female Voices",
                        color = NeonBlue.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                    )
                    VCTK_VOICES.filter { it.isFemale }.forEach { voice ->
                        VoiceListItem(
                            voice = voice,
                            isSelected = voice.id == selectedVoiceId,
                            isTesting = isTesting,
                            previewingVoiceId = previewingVoiceId,
                            onSelect = { onVoiceSelected(voice.id); showDialog = false },
                            onPreview = {
                                previewingVoiceId = voice.id
                                ttsController.testVoice(voice.id)
                            }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Male Voices",
                        color = NeonBlue.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                    )
                    VCTK_VOICES.filter { !it.isFemale }.forEach { voice ->
                        VoiceListItem(
                            voice = voice,
                            isSelected = voice.id == selectedVoiceId,
                            isTesting = isTesting,
                            previewingVoiceId = previewingVoiceId,
                            onSelect = { onVoiceSelected(voice.id); showDialog = false },
                            onPreview = {
                                previewingVoiceId = voice.id
                                ttsController.testVoice(voice.id)
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Done", color = NeonBlue)
                }
            },
            containerColor = Color(0xFF12121E),
            titleContentColor = Color.White,
            iconContentColor = Color.White
        )
    }
}

@Composable
private fun VoiceListItem(
    voice: VctkVoice,
    isSelected: Boolean,
    isTesting: Boolean,
    previewingVoiceId: Int?,
    onSelect: () -> Unit,
    onPreview: () -> Unit
) {
    val isThisLoading = isTesting && previewingVoiceId == voice.id
    Surface(
        onClick = onSelect,
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) NeonBlue.copy(alpha = 0.13f) else Color.Transparent,
        border = if (isSelected) BorderStroke(1.dp, NeonBlue.copy(alpha = 0.5f)) else null,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Gender icon
            Icon(
                if (voice.isFemale) Icons.Default.Face else Icons.Default.Person,
                null,
                tint = if (isSelected) NeonBlue else Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(22.dp)
            )
            // Name + description
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    voice.label,
                    color = if (isSelected) NeonBlue else Color.White,
                    fontSize = 14.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
                Text(
                    voice.description,
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 10.sp
                )
            }
            // Preview button / loading spinner
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(32.dp)) {
                if (isThisLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = NeonBlue,
                        strokeWidth = 2.dp
                    )
                } else {
                    IconButton(
                        onClick = onPreview,
                        modifier = Modifier.size(32.dp),
                        enabled = !isTesting   // disable other buttons while one is loading
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            "Preview voice",
                            tint = if (isTesting) Color.White.copy(alpha = 0.25f) else NeonBlue,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            // Checkmark for selected
            if (isSelected) {
                Icon(Icons.Default.Check, null, tint = NeonBlue, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun VolumeSliderRow(
    label: String,
    currentVolume: Float,
    onVolumeChange: (Float) -> Unit
) {
    Column {
        Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Filled.VolumeDown,
                null,
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(18.dp)
            )
            Slider(
                value = currentVolume,
                onValueChange = onVolumeChange,
                valueRange = 0f..2f,
                steps = 19, // 20 steps of 0.1 each
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = NeonBlue,
                    activeTrackColor = NeonBlue,
                    inactiveTrackColor = Color.White.copy(alpha = 0.15f)
                )
            )
            Icon(
                Icons.Filled.VolumeUp,
                null,
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(18.dp)
            )
            Text(
                "${(currentVolume * 100).toInt()}%",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
                modifier = Modifier.width(40.dp)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  App Settings — server-selector visibility + language (both persisted)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun AppSettingsCard(
    showServerSelectors: Boolean,
    onShowServerSelectorsChange: (Boolean) -> Unit,
    activeLanguage: AppLanguage,
    deviceLanguageName: String,
    onLanguageChange: (AppLanguage) -> Unit
) {
    var showLanguagePicker by remember { mutableStateOf(false) }

    if (showLanguagePicker) {
        LanguagePickerDialog(
            activeLanguage = activeLanguage,
            deviceLanguageName = deviceLanguageName,
            onDismiss = { showLanguagePicker = false },
            onSelect = { picked ->
                onLanguageChange(picked)
                showLanguagePicker = false
            }
        )
    }

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // ── Server selectors ────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Dns, null, tint = NeonBlue, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Show server selectors",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Pick the streaming provider yourself on movie, anime and donghua pages. Off = the app chooses the best server automatically.",
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 11.sp
                    )
                }
                Spacer(Modifier.width(10.dp))
                Switch(
                    checked = showServerSelectors,
                    onCheckedChange = onShowServerSelectorsChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = NeonBlue
                    )
                )
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

            // ── Language ────────────────────────────────────────────────────
            val languageLabel = if (activeLanguage == AppLanguage.SYSTEM) {
                "Device language · $deviceLanguageName"
            } else {
                activeLanguage.nativeName
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showLanguagePicker = true }
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Language, null, tint = NeonBlue, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Language",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(languageLabel, color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp)
                }
                Icon(Icons.Default.ChevronRight, null, tint = Color.White.copy(alpha = 0.3f))
            }
        }
    }
}

@Composable
private fun LanguagePickerDialog(
    activeLanguage: AppLanguage,
    deviceLanguageName: String,
    onDismiss: () -> Unit,
    onSelect: (AppLanguage) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("App language", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                AppLanguage.entries.forEach { language ->
                    val isActive = language == activeLanguage
                    val label = if (language == AppLanguage.SYSTEM) {
                        "Device language · $deviceLanguageName"
                    } else {
                        "${language.nativeName} · ${language.englishName}"
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(language) }
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            label,
                            color = if (isActive) NeonBlue else Color.White,
                            fontSize = 14.sp,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.weight(1f)
                        )
                        if (isActive) {
                            Icon(
                                Icons.Default.Check,
                                null,
                                tint = NeonBlue,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done", color = NeonBlue)
            }
        }
    )
}

