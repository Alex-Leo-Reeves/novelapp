package com.alexleoreeves.novelapp.tv.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.alexleoreeves.novelapp.data.AppLanguage
import com.alexleoreeves.novelapp.data.BillingCurrency
import com.alexleoreeves.novelapp.data.BillingPlan
import com.alexleoreeves.novelapp.data.TvBillingSnapshot
import com.alexleoreeves.novelapp.data.billingSnapshot
import com.alexleoreeves.novelapp.tv.platform.SavedUserAccount
import com.alexleoreeves.novelapp.tv.payment.QrPaymentScreen
import com.alexleoreeves.novelapp.tv.ui.theme.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

@Composable
fun TvYouScreen(
    account: SavedUserAccount?,
    onSignOut: () -> Unit,
    onBack: () -> Unit = {},
    selectedProfile: com.alexleoreeves.novelapp.tv.ui.TvProfile? = null,
    onSwitchProfile: () -> Unit = {},
    // ── App Settings (persisted through TvAppSettingsStore) ──────────────
    showServerSelectors: Boolean = false,
    onShowServerSelectorsChange: (Boolean) -> Unit = {},
    activeLanguage: AppLanguage = AppLanguage.SYSTEM,
    deviceLanguageName: String = "English",
    onLanguageChange: (AppLanguage) -> Unit = {}
) {
    if (account == null) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color(0xFF06060A)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.AccountCircle, null, tint = Color.White.copy(0.3f), modifier = Modifier.size(80.dp))
                Text("Sign in to access your profile", color = Color.White.copy(0.6f), style = MaterialTheme.typography.titleLarge)
                Text("Your subscriptions, history and downloads", color = Color.White.copy(0.4f))
            }
        }
        return
    }

    var showSubscribe by remember { mutableStateOf(false) }
    var selectedPlan by remember { mutableStateOf<BillingPlan?>(null) }
    var billingMessage by remember { mutableStateOf("") }
    // Server-priced plans. A null currency lets the server infer it from the
    // request, so Nigerian viewers keep seeing naira by default.
    var billingState by remember { mutableStateOf<TvBillingSnapshot?>(null) }
    var selectedCurrency by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(account.authToken, selectedCurrency) {
        billingState = billingSnapshot(account.authToken, selectedCurrency)
    }

    if (showSubscribe && selectedPlan != null) {
        val plan = selectedPlan!!
        QrPaymentScreen(
            account = account,
            planId = plan.id,
            planLabel = plan.label,
            planPriceLabel = plan.displayPrice(),
            currency = plan.selectedCurrency,
            onComplete = { showSubscribe = false },
            onBack = { showSubscribe = false }
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF06060A))
            .verticalScroll(rememberScrollState())
    ) {
        // Header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF0F041C), Color(0xFF06060A))
                    )
                )
                .padding(horizontal = 24.dp, vertical = 20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                var backFocused by remember { mutableStateOf(false) }
                Surface(
                    onClick = onBack,
                    shape = RoundedCornerShape(10.dp),
                    color = if (backFocused) Color(0xFF1C1C2E) else Color.Transparent,
                    border = if (backFocused) BorderStroke(2.dp, NeonBlue) else null,
                    modifier = Modifier.onFocusChanged { backFocused = it.isFocused }
                ) {
                    Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.ArrowBack, null, tint = Color.White, modifier = Modifier.size(20.dp))
                        Text("Back", color = Color.White)
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    // Avatar
                    Surface(shape = CircleShape, color = NeonBlue.copy(0.2f), modifier = Modifier.size(72.dp)) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(account.username.take(1).uppercase(), color = NeonBlue, fontWeight = FontWeight.Black, style = MaterialTheme.typography.headlineLarge)
                        }
                    }
                Column {
                    Text(account.username, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black, color = Color.White)
                    Text(
                        "Profile: ${selectedProfile?.name ?: "Main"}",
                        color = Color.White.copy(0.6f),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(account.email, color = Color.White.copy(0.6f), style = MaterialTheme.typography.bodyLarge)
                    Surface(color = if (account.isPremium) Color(0xFF00BFFF).copy(0.2f) else Color(0xFF14141E), shape = RoundedCornerShape(8.dp)) {
                        Text(
                            if (account.isPremium) "✦ PREMIUM" else "FREE",
                            color = if (account.isPremium) Color(0xFF00BFFF) else Color.White.copy(0.5f),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }
                }

                // Switch profile
                var switchFocused by remember { mutableStateOf(false) }
                Surface(
                    onClick = { onSwitchProfile() },
                    shape = RoundedCornerShape(10.dp),
                    color = if (switchFocused) Color(0xFF00BFFF).copy(0.25f) else Color(0xFF14141E),
                    border = if (switchFocused) BorderStroke(2.dp, Color(0xFF00BFFF)) else BorderStroke(1.dp, Color.White.copy(0.06f)),
                    modifier = Modifier.onFocusChanged { switchFocused = it.isFocused }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.SwapHoriz, null, tint = Color(0xFF00BFFF), modifier = Modifier.size(18.dp))
                        Text("Switch Profile", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                }
            }
        }

        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Subscription cards
            SectionTitle("Subscription")

            val snapshot = billingState
            val plans = snapshot?.plans.orEmpty().ifEmpty { tvOfflineFallbackPlans() }
            val currencies = snapshot?.supportedCurrencies.orEmpty()

            // Currency switcher — only shown when the storefront offers a choice.
            if (currencies.size > 1) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Paying from:",
                        color = Color.White.copy(0.5f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    currencies.forEach { option ->
                        TvCurrencyChip(
                            option = option,
                            selected = (selectedCurrency ?: snapshot?.selectedCurrency) == option.code,
                            onClick = { selectedCurrency = option.code }
                        )
                    }
                }
            }

            plans.forEach { paidPlan ->
                val isActive = account.isPremium && account.plan == paidPlan.id
                var planFocused by remember { mutableStateOf(false) }

                Surface(
                    onClick = {
                        if (!isActive) {
                            selectedPlan = paidPlan
                            showSubscribe = true
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    color = if (isActive) NeonBlue.copy(0.15f) else if (planFocused) Color(0xFF1C1C2E) else Color(0xFF0C0C14),
                    border = if (isActive) BorderStroke(2.dp, NeonBlue)
                        else if (planFocused) BorderStroke(2.dp, NeonBlue)
                        else BorderStroke(1.dp, Color.White.copy(0.05f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { planFocused = it.isFocused }
                ) {
                    Row(
                        modifier = Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(paidPlan.label, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                if (isActive) {
                                Surface(color = NeonBlue, shape = RoundedCornerShape(4.dp)) {
                                    Text("ACTIVE", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                    }
                                }
                            }
                            Text(paidPlan.description, color = Color.White.copy(0.5f), style = MaterialTheme.typography.bodySmall)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(paidPlan.displayPrice(), color = if (isActive) NeonBlue else Color.White, fontWeight = FontWeight.Black, style = MaterialTheme.typography.headlineMedium)
                            Text("/month", color = Color.White.copy(0.4f), style = MaterialTheme.typography.labelSmall)
                        }
                        if (!isActive && planFocused) {
                            Icon(Icons.Default.ArrowForward, null, tint = NeonBlue, modifier = Modifier.size(32.dp))
                        }
                    }
                }
            }

            // Current plan info
            if (account.paidUntil != null) {
                Surface(color = Color(0xFF14141E), shape = RoundedCornerShape(10.dp)) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.Info, null, tint = Color(0xFF06D6A0), modifier = Modifier.size(20.dp))
                        Text("Paid until: ${account.paidUntil}", color = Color.White.copy(0.7f))
                    }
                }
            }

            // Quick stats
            SectionTitle("Quick Stats")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatCard("Premium", if (account.isPremium) "Active" else "Free", Color(0xFF00BFFF))
                StatCard("Devices", "${account.maxDevices ?: 2}", Color(0xFF06D6A0))
                StatCard("Plan", account.plan.replace("premium_", "").replace("_", " ").ifBlank { "free" }, Color(0xFF00BFFF))
            }

            Spacer(Modifier.height(24.dp))

            // ── App Settings ──────────────────────────────────────────────
            SectionTitle("App Settings")
            Spacer(Modifier.height(12.dp))

            // Server selector visibility — press OK to flip.
            var serverToggleFocused by remember { mutableStateOf(false) }
            Surface(
                onClick = { onShowServerSelectorsChange(!showServerSelectors) },
                shape = RoundedCornerShape(10.dp),
                color = if (serverToggleFocused) Color(0xFF00BFFF).copy(0.2f) else Color(0xFF14141E),
                border = if (serverToggleFocused) BorderStroke(2.dp, Color(0xFF00BFFF))
                    else BorderStroke(1.dp, Color.White.copy(0.05f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { serverToggleFocused = it.isFocused }
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Dns, null, tint = Color(0xFF00BFFF), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Show server selectors", color = Color.White, fontWeight = FontWeight.Bold)
                        Text(
                            "Pick the streaming provider yourself on movie, anime and donghua pages.",
                            color = Color.White.copy(0.5f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        if (showServerSelectors) "ON" else "OFF",
                        color = if (showServerSelectors) Color(0xFF06D6A0) else Color.White.copy(0.5f),
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Language — press OK to cycle through the supported languages
            // (remote-friendly: no text entry, no nested dialog).
            var languageFocused by remember { mutableStateOf(false) }
            Surface(
                onClick = {
                    onLanguageChange(
                        AppLanguage.entries[(AppLanguage.entries.indexOf(activeLanguage) + 1) % AppLanguage.entries.size]
                    )
                },
                shape = RoundedCornerShape(10.dp),
                color = if (languageFocused) Color(0xFF00BFFF).copy(0.2f) else Color(0xFF14141E),
                border = if (languageFocused) BorderStroke(2.dp, Color(0xFF00BFFF))
                    else BorderStroke(1.dp, Color.White.copy(0.05f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { languageFocused = it.isFocused }
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Language, null, tint = Color(0xFF00BFFF), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Language", color = Color.White, fontWeight = FontWeight.Bold)
                        Text(
                            if (activeLanguage == AppLanguage.SYSTEM) "Device language · $deviceLanguageName"
                            else "${activeLanguage.nativeName} · ${activeLanguage.englishName}",
                            color = Color.White.copy(0.5f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        "CHANGE",
                        color = Color(0xFF00BFFF),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // Switch / Manage Profiles
            var switchProfileFocused by remember { mutableStateOf(false) }
            Surface(
                onClick = onSwitchProfile,
                shape = RoundedCornerShape(10.dp),
                color = if (switchProfileFocused) Color(0xFF00BFFF).copy(0.2f) else Color(0xFF14141E),
                border = if (switchProfileFocused) BorderStroke(2.dp, Color(0xFF00BFFF)) else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { switchProfileFocused = it.isFocused }
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.People, null, tint = Color(0xFF00BFFF), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Switch / Manage Profiles", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(12.dp))

            // Sign out
            var signOutFocused by remember { mutableStateOf(false) }
            Surface(
                onClick = onSignOut,
                shape = RoundedCornerShape(10.dp),
                color = if (signOutFocused) Color(0xFFFF5252).copy(0.2f) else Color(0xFF14141E),
                border = if (signOutFocused) BorderStroke(2.dp, Color(0xFFFF5252)) else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { signOutFocused = it.isFocused }
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.Logout, null, tint = Color(0xFFFF5252), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Sign Out", color = Color(0xFFFF5252), fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
}

@Composable
private fun StatCard(label: String, value: String, accent: Color) {
    Surface(
        color = Color(0xFF0C0C14),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color.White.copy(0.05f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp).width(140.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(value, color = accent, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)
            Text(label, color = Color.White.copy(0.5f), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Naira-priced plans used only when /billing/status can't be reached (offline
 * or cold start). The server response always wins once it arrives.
 */
private fun tvOfflineFallbackPlans(): List<BillingPlan> = listOf(
    BillingPlan(
        id = "premium_3_devices",
        label = "Premium 3 Devices",
        amount = 1000,
        maxDevices = 3,
        priceLabel = "\u20A61,000",
        description = "Full movies, cartoons, K-drama, up to 3 devices"
    ),
    BillingPlan(
        id = "premium_unlimited",
        label = "Premium Unlimited",
        amount = 4000,
        maxDevices = null,
        priceLabel = "\u20A64,000",
        description = "Full access with unlimited signed-in devices"
    )
)

/** Focusable currency pill for the TV paywall. */
@Composable
private fun TvCurrencyChip(
    option: BillingCurrency,
    selected: Boolean,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = if (selected) NeonBlue.copy(0.25f) else Color(0xFF14141E),
        border = when {
            focused -> BorderStroke(2.dp, NeonBlue)
            selected -> BorderStroke(2.dp, NeonBlue.copy(0.6f))
            else -> BorderStroke(1.dp, Color.White.copy(0.08f))
        },
        modifier = Modifier.onFocusChanged { focused = it.isFocused }
    ) {
        Text(
            option.code,
            color = if (selected) Color.White else Color.White.copy(0.7f),
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}
