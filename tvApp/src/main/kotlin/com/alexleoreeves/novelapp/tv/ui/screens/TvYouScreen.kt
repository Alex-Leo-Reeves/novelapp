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
import com.alexleoreeves.novelapp.tv.platform.SavedUserAccount
import com.alexleoreeves.novelapp.tv.payment.QrPaymentScreen
import com.alexleoreeves.novelapp.tv.ui.theme.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

@Composable
fun TvYouScreen(
    account: SavedUserAccount?,
    onSignOut: () -> Unit,
    onBack: () -> Unit = {}
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
    var selectedPlan by remember { mutableStateOf<Pair<String, Pair<String, Int>>?>(null) }
    var billingMessage by remember { mutableStateOf("") }

    if (showSubscribe && selectedPlan != null) {
        QrPaymentScreen(
            account = account,
            planId = selectedPlan!!.first,
            planLabel = selectedPlan!!.second.first,
            planAmount = selectedPlan!!.second.second,
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
                    border = if (backFocused) BorderStroke(2.dp, Purple500) else null,
                    modifier = Modifier.onFocusChanged { backFocused = it.isFocused }
                ) {
                    Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.ArrowBack, null, tint = Color.White, modifier = Modifier.size(20.dp))
                        Text("Back", color = Color.White)
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    // Avatar
                    Surface(shape = CircleShape, color = Purple500.copy(0.2f), modifier = Modifier.size(72.dp)) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(account.username.take(1).uppercase(), color = Purple500, fontWeight = FontWeight.Black, style = MaterialTheme.typography.headlineLarge)
                        }
                    }
                    Column {
                        Text(account.username, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black, color = Color.White)
                        Text(account.email, color = Color.White.copy(0.6f), style = MaterialTheme.typography.bodyLarge)
                        Surface(color = if (account.isPremium) Color(0xFF8B5CF6).copy(0.2f) else Color(0xFF14141E), shape = RoundedCornerShape(8.dp)) {
                            Text(
                                if (account.isPremium) "✦ PREMIUM" else "FREE",
                                color = if (account.isPremium) Color(0xFF8B5CF6) else Color.White.copy(0.5f),
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
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

            val plans = listOf(
                Triple("premium_3_devices", Pair("Premium 3 Devices", 1000), "Full movies, cartoons, K-drama, up to 3 devices"),
                Triple("premium_unlimited", Pair("Premium Unlimited", 4000), "Full access with unlimited signed-in devices")
            )

            plans.forEach { (planId, info, desc) ->
                val isActive = account.isPremium && account.plan == planId
                var planFocused by remember { mutableStateOf(false) }

                Surface(
                    onClick = {
                        if (!isActive) {
                            selectedPlan = planId to info
                            showSubscribe = true
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    color = if (isActive) Purple500.copy(0.15f) else if (planFocused) Color(0xFF1C1C2E) else Color(0xFF0C0C14),
                    border = if (isActive) BorderStroke(2.dp, Purple500)
                        else if (planFocused) BorderStroke(2.dp, Purple500)
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
                                Text(info.first, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                if (isActive) {
                                    Surface(color = Purple500, shape = RoundedCornerShape(4.dp)) {
                                        Text("ACTIVE", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                    }
                                }
                            }
                            Text(desc, color = Color.White.copy(0.5f), style = MaterialTheme.typography.bodySmall)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("₦${info.second}", color = if (isActive) Purple500 else Color.White, fontWeight = FontWeight.Black, style = MaterialTheme.typography.headlineMedium)
                            Text("/month", color = Color.White.copy(0.4f), style = MaterialTheme.typography.labelSmall)
                        }
                        if (!isActive && planFocused) {
                            Icon(Icons.Default.ArrowForward, null, tint = Purple500, modifier = Modifier.size(32.dp))
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
                StatCard("Premium", if (account.isPremium) "Active" else "Free", Color(0xFF8B5CF6))
                StatCard("Devices", "${account.maxDevices ?: 2}", Color(0xFF06D6A0))
                StatCard("Plan", account.plan.replace("premium_", "").replace("_", " ").ifBlank { "free" }, Color(0xFFFF2A85))
            }

            Spacer(Modifier.height(24.dp))

            // Sign out
            var signOutFocused by remember { mutableStateOf(false) }
            Surface(
                onClick = onSignOut,
                shape = RoundedCornerShape(10.dp),
                color = if (signOutFocused) Color(0xFFFF2A85).copy(0.2f) else Color(0xFF14141E),
                border = if (signOutFocused) BorderStroke(2.dp, Color(0xFFFF2A85)) else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { signOutFocused = it.isFocused }
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.Logout, null, tint = Color(0xFFFF2A85), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Sign Out", color = Color(0xFFFF2A85), fontWeight = FontWeight.Bold)
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