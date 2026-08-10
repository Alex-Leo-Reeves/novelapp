package com.alexleoreeves.novelapp.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alexleoreeves.novelapp.data.AppTheme
import com.alexleoreeves.novelapp.data.UserProfile
import com.alexleoreeves.novelapp.ui.theme.GlassBackground
import com.alexleoreeves.novelapp.ui.theme.NeonBlue

val ProfileAvatarGradients = listOf(
    listOf(Color(0xFFE50914), Color(0xFFB81D24)), // Netflix Red
    listOf(Color(0xFF00BFFF), Color(0xFF0072FF)), // Electric Blue
    listOf(Color(0xFF00E676), Color(0xFF00897B)), // Emerald Green
    listOf(Color(0xFFFF9100), Color(0xFFFF3D00)), // Amber Orange
    listOf(Color(0xFFD500F9), Color(0xFF651FFF)), // Deep Purple
    listOf(Color(0xFFFF4081), Color(0xFFC2185B))  // Bright Pink
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSelectionScreen(
    currentTheme: AppTheme,
    profiles: List<UserProfile>,
    accountUsername: String? = null,
    onUpdateUsername: ((String) -> Unit)? = null,
    onSelectProfile: (UserProfile) -> Unit,
    onCreateProfile: (name: String, isKids: Boolean, colorIndex: Int) -> Unit,
    onEditProfile: ((profile: UserProfile, newName: String, isKids: Boolean, colorIndex: Int) -> Unit)? = null
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<UserProfile?>(null) }
    var isManageMode by remember { mutableStateOf(false) }

    var showUsernameDialog by remember { 
        mutableStateOf(accountUsername.isNullOrBlank() || accountUsername.equals("Reader", ignoreCase = true))
    }
    var customUsernameInput by remember { mutableStateOf("") }

    // Dialog state for New/Edit profile
    var profileNameInput by remember { mutableStateOf("") }
    var isKidsProfileInput by remember { mutableStateOf(false) }
    var selectedColorIndexInput by remember { mutableStateOf(0) }

    Box(modifier = Modifier.fillMaxSize()) {
        GlassBackground()

        if (showUsernameDialog && onUpdateUsername != null) {
            AlertDialog(
                onDismissRequest = { },
                title = { Text("Choose a Username", fontWeight = FontWeight.Bold, color = Color.White) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Please create a username for your account to continue.",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 13.sp
                        )
                        OutlinedTextField(
                            value = customUsernameInput,
                            onValueChange = { customUsernameInput = it },
                            label = { Text("Username") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NeonBlue,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                                focusedLabelColor = NeonBlue,
                                unfocusedLabelColor = Color.White.copy(alpha = 0.6f),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (customUsernameInput.trim().length >= 2) {
                                onUpdateUsername(customUsernameInput.trim())
                                showUsernameDialog = false
                            }
                        },
                        enabled = customUsernameInput.trim().length >= 2,
                        colors = ButtonDefaults.buttonColors(containerColor = NeonBlue)
                    ) {
                        Text("Save Username", color = Color.White)
                    }
                },
                containerColor = Color(0xFF13151F)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = if (isManageMode) "Manage Profiles" else "Who's Watching?",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = if (isManageMode) "Tap a profile to edit name or kids status" else "Select your profile to continue",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 14.sp
            )

            Spacer(Modifier.height(30.dp))

            // Profile Grid
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.widthIn(max = 360.dp)
            ) {
                items(profiles.size) { idx ->
                    val profile = profiles[idx]
                    ProfileAvatarCard(
                        profile = profile,
                        isManageMode = isManageMode,
                        onClick = {
                            if (isManageMode) {
                                editingProfile = profile
                                profileNameInput = profile.name
                                isKidsProfileInput = profile.isKids
                                selectedColorIndexInput = profile.avatarColorIndex
                            } else {
                                onSelectProfile(profile)
                            }
                        }
                    )
                }

                // Add Profile Card
                item {
                    AddProfileCard(onClick = {
                        editingProfile = null
                        profileNameInput = ""
                        isKidsProfileInput = false
                        selectedColorIndexInput = 0
                        showAddDialog = true
                    })
                }
            }

            Spacer(Modifier.height(32.dp))

            // Manage Profiles Toggle Button
            OutlinedButton(
                onClick = { isManageMode = !isManageMode },
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Icon(
                    if (isManageMode) Icons.Rounded.Check else Icons.Rounded.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (isManageMode) "Done" else "Manage Profiles",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }

        // Add or Edit Profile Dialog
        if (showAddDialog || editingProfile != null) {
            val isEditing = editingProfile != null
            AlertDialog(
                onDismissRequest = {
                    showAddDialog = false
                    editingProfile = null
                },
                title = {
                    Text(
                        if (isEditing) "Edit Profile" else "Create New Profile",
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        OutlinedTextField(
                            value = profileNameInput,
                            onValueChange = { profileNameInput = it },
                            label = { Text("Profile Name") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NeonBlue,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                                focusedLabelColor = NeonBlue,
                                unfocusedLabelColor = Color.White.copy(alpha = 0.6f),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        // Avatar Color Picker
                        Text("Avatar Color:", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            ProfileAvatarGradients.indices.forEach { colorIdx ->
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(Brush.linearGradient(ProfileAvatarGradients[colorIdx]))
                                        .border(
                                            width = if (selectedColorIndexInput == colorIdx) 2.5.dp else 0.dp,
                                            color = if (selectedColorIndexInput == colorIdx) Color.White else Color.Transparent,
                                            shape = CircleShape
                                        )
                                        .clickable { selectedColorIndexInput = colorIdx }
                                )
                            }
                        }

                        // Kids Profile Toggle
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Kids Profile", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text("Family-friendly movies & cartoons only", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
                            }
                            Switch(
                                checked = isKidsProfileInput,
                                onCheckedChange = { isKidsProfileInput = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = NeonBlue
                                )
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (profileNameInput.isNotBlank()) {
                                if (isEditing && editingProfile != null) {
                                    onEditProfile?.invoke(
                                        editingProfile!!,
                                        profileNameInput.trim(),
                                        isKidsProfileInput,
                                        selectedColorIndexInput
                                    )
                                } else {
                                    onCreateProfile(
                                        profileNameInput.trim(),
                                        isKidsProfileInput,
                                        selectedColorIndexInput
                                    )
                                }
                                showAddDialog = false
                                editingProfile = null
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonBlue)
                    ) {
                        Text(if (isEditing) "Save Changes" else "Create Profile", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showAddDialog = false
                        editingProfile = null
                    }) {
                        Text("Cancel", color = Color.White.copy(alpha = 0.6f))
                    }
                },
                containerColor = Color(0xFF13151F)
            )
        }
    }
}

@Composable
private fun ProfileAvatarCard(
    profile: UserProfile,
    isManageMode: Boolean = false,
    onClick: () -> Unit
) {
    val gradientColors = ProfileAvatarGradients.getOrElse(profile.avatarColorIndex) { ProfileAvatarGradients[0] }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(100.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Brush.linearGradient(gradientColors))
                .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
        ) {
            Text(
                text = profile.name.take(1).uppercase(),
                color = Color.White,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold
            )

            if (profile.isKids) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFFFF9100))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("KIDS", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
                }
            }

            if (isManageMode) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Edit,
                        contentDescription = "Edit Profile",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        Text(
            text = profile.name,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

@Composable
private fun AddProfileCard(
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(100.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White.copy(alpha = 0.08f))
                .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(20.dp))
        ) {
            Icon(
                Icons.Rounded.Add,
                contentDescription = "Add Profile",
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(48.dp)
            )
        }

        Spacer(Modifier.height(10.dp))

        Text(
            text = "Add Profile",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )
    }
}
