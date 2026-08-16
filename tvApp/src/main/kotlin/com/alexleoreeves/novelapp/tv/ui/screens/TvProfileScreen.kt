package com.alexleoreeves.novelapp.tv.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.alexleoreeves.novelapp.tv.platform.SavedUserAccount
import com.alexleoreeves.novelapp.tv.platform.TvProfileStore
import com.alexleoreeves.novelapp.tv.ui.TvProfile
import com.alexleoreeves.novelapp.tv.ui.theme.*

/**
 * D-pad friendly profile manager.
 * Supports choosing profiles, adding new profiles (Profile Creator),
 * and editing/deleting existing profiles (Profile Editor).
 */
@Composable
fun TvProfileScreen(
    account: SavedUserAccount?,
    onSelectProfile: (TvProfile) -> Unit
) {
    val context = LocalContext.current
    val baseName = account?.username?.takeIf { it.isNotBlank() } ?: "Main"
    
    var profiles by remember { mutableStateOf(TvProfileStore.getProfiles(context, baseName)) }
    var isEditMode by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<TvProfile?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF06060A)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(32.dp),
            modifier = Modifier.padding(24.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.AutoStories,
                    null,
                    tint = Color(0xFF00BFFF),
                    modifier = Modifier.size(52.dp)
                )
                Text(
                    text = if (isEditMode) "Manage Profiles" else "Who's watching?",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Black,
                    color = Color.White
                )
                Text(
                    text = if (isEditMode) "Select a profile to edit, change avatar, or delete" else "Choose a profile to start watching",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(0.6f)
                )
            }

            // Profiles Row / Grid
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically,
                contentPadding = PaddingValues(horizontal = 32.dp)
            ) {
                items(profiles, key = { it.id }) { profile ->
                    ProfileCard(
                        profile = profile,
                        isEditMode = isEditMode,
                        onClick = {
                            if (isEditMode) {
                                editingProfile = profile
                            } else {
                                TvProfileStore.setActiveProfileId(context, profile.id)
                                onSelectProfile(profile)
                            }
                        }
                    )
                }

                // Add Profile Tile (when not editing or always visible)
                if (profiles.size < 6) {
                    item {
                        AddProfileTile(
                            onClick = { showCreateDialog = true }
                        )
                    }
                }
            }

            // Manage Profiles Toggle Button
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { isEditMode = !isEditMode },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (isEditMode) Color(0xFF00BFFF) else Color.White.copy(0.7f)
                    ),
                    border = BorderStroke(
                        1.dp,
                        if (isEditMode) Color(0xFF00BFFF) else Color.White.copy(0.2f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        if (isEditMode) Icons.Default.Check else Icons.Default.Edit,
                        null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (isEditMode) "Done Editing" else "Manage Profiles",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Profile Creator Dialog
        if (showCreateDialog) {
            ProfileEditorDialog(
                title = "Create Profile",
                initialName = "",
                initialIsKids = false,
                initialColorIndex = profiles.size % TvProfileStore.AVATAR_COLORS.size,
                allowDelete = false,
                onDismiss = { showCreateDialog = false },
                onSave = { name, isKids, colorIdx ->
                    val created = TvProfileStore.addProfile(context, name, isKids, colorIdx)
                    profiles = TvProfileStore.getProfiles(context, baseName)
                    showCreateDialog = false
                },
                onDelete = {}
            )
        }

        // Profile Editor Dialog
        editingProfile?.let { target ->
            ProfileEditorDialog(
                title = "Edit Profile",
                initialName = target.name,
                initialIsKids = target.isKids,
                initialColorIndex = target.avatarColorIndex,
                allowDelete = profiles.size > 1,
                onDismiss = { editingProfile = null },
                onSave = { name, isKids, colorIdx ->
                    profiles = TvProfileStore.updateProfile(context, target.id, name, isKids, colorIdx)
                    editingProfile = null
                },
                onDelete = {
                    profiles = TvProfileStore.deleteProfile(context, target.id)
                    editingProfile = null
                }
            )
        }
    }
}

@Composable
private fun ProfileCard(
    profile: TvProfile,
    isEditMode: Boolean,
    onClick: () -> Unit
) {
    val avatarColor = TvProfileStore.getAvatarColor(profile.avatarColorIndex, profile.isKids)
    var isFocused by remember { mutableStateOf(false) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .width(180.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (isFocused) Color(0xFF17172A) else Color(0xFF0F0F1A),
                RoundedCornerShape(16.dp)
            )
            .border(
                width = if (isFocused) 3.dp else 1.dp,
                color = if (isFocused) (if (isEditMode) Color(0xFFFFD166) else avatarColor) else Color.White.copy(0.08f),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(vertical = 24.dp, horizontal = 12.dp)
    ) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = avatarColor.copy(0.2f),
            border = BorderStroke(
                if (isFocused) 3.dp else 2.dp,
                if (isFocused) (if (isEditMode) Color(0xFFFFD166) else avatarColor) else avatarColor.copy(0.5f)
            ),
            modifier = Modifier
                .size(110.dp)
                .onFocusChanged { isFocused = it.isFocused }
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (profile.isKids) {
                    Icon(
                        Icons.Default.ChildCare,
                        null,
                        tint = avatarColor,
                        modifier = Modifier.size(54.dp)
                    )
                } else {
                    Text(
                        profile.name.take(1).uppercase(),
                        color = avatarColor,
                        fontWeight = FontWeight.Black,
                        style = MaterialTheme.typography.displayMedium
                    )
                }

                if (isEditMode) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(0.45f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            null,
                            tint = Color(0xFFFFD166),
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
        }

        Text(
            profile.name,
            color = if (isFocused) Color.White else Color.White.copy(0.7f),
            fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1
        )

        if (profile.isKids) {
            Surface(color = Color(0xFF06D6A0).copy(0.2f), shape = RoundedCornerShape(6.dp)) {
                Text(
                    "KIDS MODE",
                    color = Color(0xFF06D6A0),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        } else {
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun AddProfileTile(
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .width(180.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (isFocused) Color(0xFF17172A) else Color(0xFF0F0F1A),
                RoundedCornerShape(16.dp)
            )
            .border(
                width = if (isFocused) 3.dp else 1.dp,
                color = if (isFocused) Color(0xFF00BFFF) else Color.White.copy(0.08f),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(vertical = 24.dp, horizontal = 12.dp)
    ) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = Color.White.copy(0.05f),
            border = BorderStroke(
                if (isFocused) 3.dp else 1.dp,
                if (isFocused) Color(0xFF00BFFF) else Color.White.copy(0.15f)
            ),
            modifier = Modifier
                .size(110.dp)
                .onFocusChanged { isFocused = it.isFocused }
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Add,
                    null,
                    tint = if (isFocused) Color(0xFF00BFFF) else Color.White.copy(0.6f),
                    modifier = Modifier.size(48.dp)
                )
            }
        }

        Text(
            "Add Profile",
            color = if (isFocused) Color.White else Color.White.copy(0.6f),
            fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal,
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun ProfileEditorDialog(
    title: String,
    initialName: String,
    initialIsKids: Boolean,
    initialColorIndex: Int,
    allowDelete: Boolean,
    onDismiss: () -> Unit,
    onSave: (name: String, isKids: Boolean, colorIndex: Int) -> Unit,
    onDelete: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var isKids by remember { mutableStateOf(initialIsKids) }
    var selectedColorIdx by remember { mutableIntStateOf(initialColorIndex) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                title,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.width(360.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Profile Name") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF00BFFF),
                        unfocusedBorderColor = Color.White.copy(0.3f),
                        focusedLabelColor = Color(0xFF00BFFF),
                        unfocusedLabelColor = Color.White.copy(0.6f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                // Kids Mode Toggle
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { isKids = !isKids }
                        .padding(vertical = 8.dp)
                ) {
                    Column {
                        Text("Kids Profile", fontWeight = FontWeight.Bold, color = Color.White)
                        Text(
                            "Shows family-friendly cartoons and anime only",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(0.6f)
                        )
                    }
                    Switch(
                        checked = isKids,
                        onCheckedChange = { isKids = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF06D6A0),
                            checkedTrackColor = Color(0xFF06D6A0).copy(0.4f)
                        )
                    )
                }

                // Avatar Color Palette
                if (!isKids) {
                    Text("Avatar Color", fontWeight = FontWeight.SemiBold, color = Color.White)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TvProfileStore.AVATAR_COLORS.forEachIndexed { idx, color ->
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(
                                        width = if (selectedColorIdx == idx) 3.dp else 1.dp,
                                        color = if (selectedColorIdx == idx) Color.White else Color.Transparent,
                                        shape = CircleShape
                                    )
                                    .clickable { selectedColorIdx = idx },
                                contentAlignment = Alignment.Center
                            ) {
                                if (selectedColorIdx == idx) {
                                    Icon(
                                        Icons.Default.Check,
                                        null,
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(name, isKids, selectedColorIdx)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00BFFF))
            ) {
                Text("Save", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (allowDelete) {
                    TextButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFF5252))
                    ) {
                        Text("Delete")
                    }
                }
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White.copy(0.7f))
                ) {
                    Text("Cancel")
                }
            }
        },
        containerColor = Color(0xFF141422)
    )
}
