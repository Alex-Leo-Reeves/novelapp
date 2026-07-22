package com.alexleoreeves.novelapp.tv.ui

import androidx.compose.animation.*
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.alexleoreeves.novelapp.tv.audio.TvTtsEngine
import com.alexleoreeves.novelapp.tv.data.*
import com.alexleoreeves.novelapp.tv.platform.SavedUserAccount
import com.alexleoreeves.novelapp.tv.platform.UserSessionStore
import com.alexleoreeves.novelapp.tv.ui.screens.*
import com.alexleoreeves.novelapp.tv.ui.theme.*
import kotlinx.coroutines.launch

enum class TvScreen {
    SPLASH, AUTH, HOME, DETAIL, PLAYER, READER, MANGA_VIEWER
}

data class NavigationState(
    val screen: TvScreen = TvScreen.SPLASH,
    val selectedSection: TvSection = TvSection.HOME,
    val selectedItem: UnifiedSearchResult? = null,
    val playUrl: String = "",
    val playTitle: String = "",
    val readerText: String = "",
    val readerTitle: String = "",
    val mangaPages: List<String> = emptyList(),
    val mangaTitle: String = "",
    val showSearch: Boolean = false,
    val account: SavedUserAccount? = null
)

@Composable
fun TvApp(
    sessionStore: UserSessionStore,
    ttsEngine: TvTtsEngine
) {
    var nav by remember { mutableStateOf(NavigationState()) }
    var isLoading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    // Load saved account
    LaunchedEffect(Unit) {
        val saved = sessionStore.loadAccount()
        if (saved != null && saved.authToken.isNotBlank()) {
            // Verify token is still valid
            val fresh = try { authMe(saved.authToken) } catch (_: Exception) { null }
            if (fresh != null) {
                nav = nav.copy(account = fresh)
                sessionStore.saveAccount(fresh)
            } else {
                sessionStore.clearAccount()
            }
        }
        isLoading = false
    }

    // Handle back navigation
    fun goBack() {
        nav = when (nav.screen) {
            TvScreen.DETAIL -> nav.copy(screen = TvScreen.HOME, selectedItem = null)
            TvScreen.PLAYER -> nav.copy(screen = TvScreen.DETAIL, playUrl = "", playTitle = "")
            TvScreen.READER -> nav.copy(screen = TvScreen.DETAIL, readerText = "", readerTitle = "")
            TvScreen.MANGA_VIEWER -> nav.copy(screen = TvScreen.DETAIL, mangaPages = emptyList(), mangaTitle = "")
            TvScreen.AUTH -> nav.copy(screen = TvScreen.HOME)
            TvScreen.SPLASH -> nav.copy(screen = TvScreen.HOME)
            else -> nav
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF06060A))) {
        when {
            isLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Purple500, modifier = Modifier.size(48.dp))
                }
            }

            nav.screen == TvScreen.SPLASH -> {
                TvSplashScreen(onFinished = {
                    val account = sessionStore.loadAccount()
                    nav = if (account != null) {
                        nav.copy(screen = TvScreen.HOME, account = account)
                    } else {
                        nav.copy(screen = TvScreen.HOME)
                    }
                })
            }

            nav.screen == TvScreen.AUTH -> {
                TvAuthScreen(
                    onSignIn = { email, password ->
                        scope.launch {
                            try {
                                val account = authLogin(email, password)
                                sessionStore.saveAccount(account)
                                nav = nav.copy(screen = TvScreen.HOME, account = account)
                            } catch (e: Exception) { /* error handled inside TvAuthScreen */ }
                        }
                    },
                    onCreateAccount = { username, email, password, recoverySecret ->
                        scope.launch {
                            try {
                                val account = authRegister(username, email, password, recoverySecret)
                                sessionStore.saveAccount(account)
                                nav = nav.copy(screen = TvScreen.HOME, account = account)
                            } catch (e: Exception) { /* error handled inside TvAuthScreen */ }
                        }
                    },
                    onDismiss = { nav = nav.copy(screen = TvScreen.HOME) }
                )
            }

            else -> {
                // Main layout with sidebar navigation
                Row(modifier = Modifier.fillMaxSize()) {
                    // Skip sidebar in non-home screens
                    if (nav.screen == TvScreen.HOME) {
                        // Sidebar
                        TvSidebar(
                            selectedSection = nav.selectedSection,
                            onSectionSelected = { section ->
                                nav = nav.copy(selectedSection = section, showSearch = false)
                            },
                            account = nav.account,
                            onSignInClick = { nav = nav.copy(screen = TvScreen.AUTH) },
                            onSignOut = {
                                scope.launch {
                                    nav.account?.let { authLogout(it.authToken) }
                                }
                                sessionStore.clearAccount()
                                nav = nav.copy(account = null, selectedSection = TvSection.HOME)
                            }
                        )
                    }

                    // Main content area
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        when (nav.screen) {
                            TvScreen.HOME -> {
                                TvHomeScreen(
                                    section = nav.selectedSection,
                                    account = nav.account,
                                    onMediaSelected = { item ->
                                        nav = nav.copy(screen = TvScreen.DETAIL, selectedItem = item)
                                    },
                                    onSearch = { query ->
                                        // Search is handled via the keyboard in TvHomeScreen
                                    }
                                )
                            }

                            TvScreen.DETAIL -> {
                                val item = nav.selectedItem
                                if (item != null) {
                                    TvDetailScreen(
                                        item = item,
                                        account = nav.account,
                                        onPlayEpisode = { url, title ->
                                            nav = nav.copy(screen = TvScreen.PLAYER, playUrl = url, playTitle = title)
                                        },
                                        onReadNovel = { text, title ->
                                            nav = nav.copy(screen = TvScreen.READER, readerText = text, readerTitle = title)
                                        },
                                        onReadManga = { pages, title ->
                                            nav = nav.copy(screen = TvScreen.MANGA_VIEWER, mangaPages = pages, mangaTitle = title)
                                        },
                                        onBack = { goBack() }
                                    )
                                }
                            }

                            TvScreen.PLAYER -> {
                                TvPlayerScreen(
                                    streamUrl = nav.playUrl,
                                    title = nav.playTitle,
                                    onBack = { goBack() }
                                )
                            }

                            TvScreen.READER -> {
                                TvNovelReaderScreen(
                                    text = nav.readerText,
                                    title = nav.readerTitle,
                                    ttsEngine = ttsEngine,
                                    onBack = { goBack() }
                                )
                            }

                            TvScreen.MANGA_VIEWER -> {
                                TvMangaViewerScreen(
                                    pages = nav.mangaPages,
                                    title = nav.mangaTitle,
                                    onBack = { goBack() }
                                )
                            }

                            else -> {}
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvSidebar(
    selectedSection: TvSection,
    onSectionSelected: (TvSection) -> Unit,
    account: SavedUserAccount?,
    onSignInClick: () -> Unit,
    onSignOut: () -> Unit
) {
    val sections = listOf(
        TvSection.HOME to Icons.Default.Home,
        TvSection.NOVELS to Icons.Default.AutoStories,
        TvSection.MANGA to Icons.Default.Collections,
        TvSection.COMICS to Icons.Default.ImportContacts,
        TvSection.ANIME to Icons.Default.PlayCircle,
        TvSection.DONGHUA to Icons.Default.VideoLibrary,
        TvSection.K_DRAMA to Icons.Default.LiveTv,
        TvSection.CARTOON to Icons.Default.Animation,
        TvSection.CLASSIC to Icons.Default.Theaters,
        TvSection.MOVIES to Icons.Default.Movie,
        TvSection.NOLLYWOOD to Icons.Default.Flag,
        TvSection.SPORTS to Icons.Default.SportsSoccer,
        TvSection.DOWNLOADS to Icons.Default.Download,
        TvSection.YOU to Icons.Default.AccountCircle
    )

    Column(
        modifier = Modifier
            .width(80.dp)
            .fillMaxHeight()
            .background(Color(0xFF0A0A12))
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // App logo
        Icon(Icons.Default.AutoStories, null, tint = Color(0xFF00BFFF), modifier = Modifier.size(36.dp).padding(bottom = 8.dp))

        // Sections
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            sections.forEach { (section, icon) ->
                val isSelected = selectedSection == section
                var isFocused by remember { mutableStateOf(false) }

                Surface(
                    onClick = { onSectionSelected(section) },
                    shape = RoundedCornerShape(10.dp),
                    color = when {
                        isSelected -> Color(0xFF00BFFF).copy(0.2f)
                        isFocused -> Color(0xFF1C1C2E)
                        else -> Color.Transparent
                    },
                    border = when {
                        isSelected -> BorderStroke(2.dp, Color(0xFF00BFFF))
                        isFocused -> BorderStroke(2.dp, Purple500)
                        else -> null
                    },
                    modifier = Modifier
                        .size(56.dp)
                        .onFocusChanged { isFocused = it.isFocused }
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            icon,
                            section.label,
                            tint = if (isSelected) Color(0xFF00BFFF) else Color.White.copy(0.5f),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }

        // Account section at bottom
        Spacer(Modifier.height(4.dp))

        if (account != null) {
            // User avatar
            var youFocused by remember { mutableStateOf(false) }
            Surface(
                onClick = { onSectionSelected(TvSection.YOU) },
                shape = CircleShape,
                color = if (selectedSection == TvSection.YOU) Purple500.copy(0.3f)
                    else if (youFocused) Color(0xFF1C1C2E)
                    else Color(0xFF14141E),
                border = if (selectedSection == TvSection.YOU) BorderStroke(2.dp, Purple500)
                    else if (youFocused) BorderStroke(2.dp, Purple500)
                    else null,
                modifier = Modifier
                    .size(44.dp)
                    .onFocusChanged { youFocused = it.isFocused }
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        account.username.take(1).uppercase(),
                        color = if (selectedSection == TvSection.YOU) Purple500 else Color.White.copy(0.7f),
                        fontWeight = FontWeight.Black,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            if (account.isPremium) {
                Icon(
                    Icons.Default.Verified,
                    null,
                    tint = Purple500,
                    modifier = Modifier.size(12.dp).offset(y = (-6).dp)
                )
            }
        } else {
            // Sign in button
            var signInFocused by remember { mutableStateOf(false) }
            Surface(
                onClick = onSignInClick,
                shape = RoundedCornerShape(8.dp),
                color = if (signInFocused) Purple500.copy(0.3f) else Color.Transparent,
                border = if (signInFocused) BorderStroke(1.dp, Purple500) else null,
                modifier = Modifier
                    .size(44.dp)
                    .onFocusChanged { signInFocused = it.isFocused }
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Login, null, tint = Purple500, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}
