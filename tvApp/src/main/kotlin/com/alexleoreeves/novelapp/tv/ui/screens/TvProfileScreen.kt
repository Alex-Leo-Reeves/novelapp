package com.alexleoreeves.novelapp.tv.ui.screens

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.alexleoreeves.novelapp.tv.platform.SavedUserAccount
import com.alexleoreeves.novelapp.tv.ui.TvProfile
import com.alexleoreeves.novelapp.tv.ui.theme.*

/**
 * D-pad friendly profile picker. Every signed-in user must pick a profile
 * before the app opens (Main from the account username + a Kids profile).
 */
@Composable
fun TvProfileScreen(
    account: SavedUserAccount?,
    onSelectProfile: (TvProfile) -> Unit
) {
    val baseName = account?.username?.takeIf { it.isNotBlank() } ?: "Main"
    val profiles = listOf(
        TvProfile(id = "main", name = baseName, isKids = false, avatarColorIndex = 1),
        TvProfile(id = "kids", name = "Kids", isKids = true, avatarColorIndex = 3)
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF06060A)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(40.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.AutoStories, null, tint = Color(0xFF00BFFF), modifier = Modifier.size(56.dp))
                Text(
                    "Who's watching?",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Black,
                    color = Color.White
                )
                Text(
                    "Choose a profile to start watching",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(0.6f)
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                profiles.forEach { profile ->
                    ProfileTile(profile = profile, onClick = { onSelectProfile(profile) })
                }
            }
        }
    }
}

@Composable
private fun ProfileTile(
    profile: TvProfile,
    onClick: () -> Unit
) {
    val avatarColor = if (profile.isKids) Color(0xFF06D6A0) else Color(0xFF00BFFF)
    var isFocused by remember { mutableStateOf(profile.id == "main") }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .width(190.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (isFocused) Color(0xFF17172A) else Color.Transparent,
                RoundedCornerShape(16.dp)
            )
            .border(
                width = if (isFocused) 3.dp else 1.dp,
                color = if (isFocused) avatarColor else Color.White.copy(0.08f),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(vertical = 28.dp, horizontal = 16.dp)
    ) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = avatarColor.copy(0.25f),
            border = BorderStroke(
                if (isFocused) 3.dp else 1.dp,
                if (isFocused) avatarColor else Color.White.copy(0.15f)
            ),
            modifier = Modifier
                .size(120.dp)
                .onFocusChanged { isFocused = it.isFocused }
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (profile.isKids) {
                    Icon(Icons.Default.ChildCare, null, tint = avatarColor, modifier = Modifier.size(56.dp))
                } else {
                    Text(
                        profile.name.take(1).uppercase(),
                        color = avatarColor,
                        fontWeight = FontWeight.Black,
                        style = MaterialTheme.typography.displayLarge
                    )
                }
            }
        }

        Text(
            profile.name,
            color = if (isFocused) Color.White else Color.White.copy(0.55f),
            fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal,
            style = MaterialTheme.typography.titleLarge
        )

        if (profile.isKids) {
            Surface(color = Color(0xFF06D6A0).copy(0.2f), shape = RoundedCornerShape(6.dp)) {
                Text(
                    "KIDS MODE",
                    color = Color(0xFF06D6A0),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        } else {
            Spacer(Modifier.height(20.dp))
        }
    }
}
