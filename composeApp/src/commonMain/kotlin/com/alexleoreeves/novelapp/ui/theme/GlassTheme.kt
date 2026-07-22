package com.alexleoreeves.novelapp.ui.theme

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alexleoreeves.novelapp.BottomTab

// ─────────────────────────────────────────────────────────────────────────────
//  Glassmorphism Design Tokens & Shared Composables
// ─────────────────────────────────────────────────────────────────────────────

// Ultra-vibrant background gradient (Neon Magenta → Deep Violet)
val GlassBackgroundBrush = Brush.verticalGradient(
    colors = listOf(
        Color(0xFFFF007F), // Neon Magenta
        Color(0xFF7F00FF)  // Deep Violet
    )
)

// Dark translucent overlay
val GlassOverlayColor = Color(0xFF121212).copy(alpha = 0.45f)

// Card glass fill — vertical gradient: top bright, bottom dim
fun glassCardBrush(): Brush = Brush.verticalGradient(
    colors = listOf(
        Color.White.copy(alpha = 0.12f),
        Color.White.copy(alpha = 0.03f)
    )
)

// Card edge stroke — diagonal: top-left bright, bottom-right transparent
fun glassBorderBrush(width: Float, height: Float): Brush = Brush.linearGradient(
    start = Offset(0f, 0f),
    end = Offset(width, height),
    colors = listOf(
        Color.White.copy(alpha = 0.35f),
        Color.Transparent
    )
)

// Shimmer placeholder color
val GlassShimmerColor = Color.White.copy(alpha = 0.05f)

// Bottom bar background
val GlassBottomBarColor = Color.White.copy(alpha = 0.08f)

// Card corner radius
val GlassCardShape = RoundedCornerShape(28.dp)
val GlassImageShape = RoundedCornerShape(16.dp)
val GlassChipShape = RoundedCornerShape(20.dp)
val GlassPillShape = RoundedCornerShape(50)

// Neon glow active tab
val NeonMagenta = Color(0xFFFF007F)
val NeonViolet = Color(0xFFB366FF)

// ─────────────────────────────────────────────────────────────────────────────
//  Background Layer — Vibrant gradient + blur overlay
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun GlassBackground(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(GlassBackgroundBrush)
    ) {
        // Soft blur overlay simulating ambient light
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF1A0033).copy(alpha = 0.3f),
                            Color(0xFF0D001A).copy(alpha = 0.6f),
                            Color(0xFF1A0033).copy(alpha = 0.3f)
                        )
                    )
                )
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Glass Surface Panel — translucent dark overlay
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun GlassSurfacePanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .background(GlassOverlayColor)
            .padding(horizontal = 16.dp),
        content = content
    )
}

// ─────────────────────────────────────────────────────────────────────────────
//  Glass Content Card — elegant glass card with edge stroke
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable RowScope.() -> Unit
) {
    val clickModifier = if (onClick != null) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier
    }

    Surface(
        modifier = modifier
            .then(clickModifier)
            .clip(GlassCardShape),
        shape = GlassCardShape,
        color = Color.Transparent,
    ) {
        Box(
            modifier = Modifier
                .background(glassCardBrush())
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.35f),
                            Color.Transparent,
                            Color.White.copy(alpha = 0.05f)
                        )
                    ),
                    shape = GlassCardShape
                )
                .padding(contentPadding)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Glass Image Card — Coil 3 AsyncImage with shimmer placeholder
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun GlassImagePlaceholder(
    modifier: Modifier = Modifier,
    aspectRatio: Float = 3f / 4f
) {
    Box(
        modifier = modifier
            .aspectRatio(aspectRatio)
            .clip(GlassImageShape)
            .background(GlassShimmerColor)
    ) {
        // Animated shimmer sweep
        val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
        val shimmerOffset by infiniteTransition.animateFloat(
            initialValue = -1f,
            targetValue = 2f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "shimmerOffset"
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.0f),
                            Color.White.copy(alpha = 0.12f),
                            Color.White.copy(alpha = 0.0f)
                        ),
                        startX = shimmerOffset * 500f,
                        endX = (shimmerOffset + 1f) * 500f
                    )
                )
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Glass Capsule Tab — pill-shaped tab chip
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun GlassCapsuleTab(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = if (isSelected) {
        Color.White.copy(alpha = 0.18f)
    } else {
        Color.White.copy(alpha = 0.06f)
    }
    val textColor = if (isSelected) Color.White else Color.White.copy(alpha = 0.55f)

    Box(
        modifier = modifier
            .clip(GlassChipShape)
            .background(bgColor)
            .border(
                width = if (isSelected) 1.5.dp else 0.5.dp,
                color = if (isSelected) NeonMagenta.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.12f),
                shape = GlassChipShape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Glass Section Header Label
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun GlassSectionLabel(
    title: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Neon accent bar
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(20.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(NeonMagenta, NeonViolet)
                    )
                )
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = title,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Glass Genre / Category Chip
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun GlassGenreChip(
    text: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .border(
                width = 0.5.dp,
                color = Color.White.copy(alpha = 0.15f),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Glass Star Rating Row
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun GlassStarRating(
    rating: Float,
    maxStars: Int = 5,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until maxStars) {
            val filled = i < rating.toInt()
            Text(
                text = if (filled) "★" else "☆",
                color = if (filled) Color(0xFFFFD700) else Color.White.copy(alpha = 0.3f),
                fontSize = 14.sp
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = String.format("%.1f", rating),
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 12.sp
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Glass Bottom Navigation Bar
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun GlassBottomBar(
    currentTab: BottomTab,
    onTabSelected: (BottomTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = GlassBottomBarColor,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlassBottomNavItem(
                tab = BottomTab.DISCOVER,
                label = "Discover",
                icon = Icons.Filled.PlayCircle,
                isSelected = currentTab == BottomTab.DISCOVER,
                onClick = { onTabSelected(BottomTab.DISCOVER) }
            )
            GlassBottomNavItem(
                tab = BottomTab.NMC,
                label = "NMC",
                icon = Icons.Filled.Book,
                isSelected = currentTab == BottomTab.NMC,
                onClick = { onTabSelected(BottomTab.NMC) }
            )
            GlassBottomNavItem(
                tab = BottomTab.SPORTS,
                label = "Sports",
                icon = Icons.Filled.EmojiEvents,
                isSelected = currentTab == BottomTab.SPORTS,
                onClick = { onTabSelected(BottomTab.SPORTS) }
            )
            GlassBottomNavItem(
                tab = BottomTab.READ,
                label = "Read",
                icon = Icons.Filled.MenuBook,
                isSelected = currentTab == BottomTab.READ,
                onClick = { onTabSelected(BottomTab.READ) }
            )
            GlassBottomNavItem(
                tab = BottomTab.YOU,
                label = "You",
                icon = Icons.Filled.Person,
                isSelected = currentTab == BottomTab.YOU,
                onClick = { onTabSelected(BottomTab.YOU) }
            )
        }
    }
}

@Composable
private fun GlassBottomNavItem(
    tab: BottomTab,
    label: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val iconColor = if (isSelected) Color.White else Color.White.copy(alpha = 0.45f)
    val textColor = if (isSelected) Color.White else Color.White.copy(alpha = 0.45f)

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .then(
                if (isSelected) {
                    Modifier.background(NeonMagenta.copy(alpha = 0.12f))
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = iconColor,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            color = textColor,
            fontSize = 10.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
        if (isSelected) {
            Spacer(modifier = Modifier.height(2.dp))
            Box(
                modifier = Modifier
                    .width(16.dp)
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(NeonMagenta, NeonViolet)
                        )
                    )
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Glass Search Bar
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun GlassSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String = "Search...",
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .border(
                width = 0.5.dp,
                color = Color.White.copy(alpha = 0.15f),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = "Search",
                tint = Color.White.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            // Placeholder TextField — we use a styled BasicTextField
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = placeholder,
                        color = Color.White.copy(alpha = 0.35f),
                        fontSize = 14.sp
                    )
                }
                // Note: Full TextField implementation requires actual input handling.
                // The composable is designed as a wrapper — the caller handles the actual
                // text field inside or uses this as a decorative container.
                Text(
                    text = query,
                    color = Color.White,
                    fontSize = 14.sp
                )
            }
            if (query.isNotEmpty()) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Clear",
                    tint = Color.White.copy(alpha = 0.4f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
