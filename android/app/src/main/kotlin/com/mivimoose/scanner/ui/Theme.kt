package com.mivimoose.scanner.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Holo Vault: near-black, glass panels, a holographic gradient for what matters. */
object Vault {
    val bg = Color(0xFF07080C)
    val surface = Color(0xFF10131B)
    val surface2 = Color(0xFF171B26)
    val surface3 = Color(0xFF202534)
    val line = Color(0x1AFFFFFF)
    val text = Color(0xFFECEFF7)
    val text2 = Color(0xFFB7BDCC)
    val muted = Color(0xFF7D8496)
    val mint = Color(0xFF6EF3D6)
    val periwinkle = Color(0xFF8AA2FF)
    val orchid = Color(0xFFE48BFF)
    val peach = Color(0xFFFFCF8A)
    val gold = Color(0xFFF7C948)
    val holo = listOf(mint, periwinkle, orchid, peach)
    val holoBrush get() = Brush.linearGradient(holo)

    /** Status (fixed, always with an icon or label): up, down, warning. */
    val good = Color(0xFF0CA30C)
    val goodText = Color(0xFF3DD96B)
    val bad = Color(0xFFD03B3B)
    val badText = Color(0xFFFF7A7A)
    val warn = Color(0xFFFAB219)

    /** Chart series (validated against `surface`): blue, orange, aqua. */
    val series1 = Color(0xFF3987E5)
    val series2 = Color(0xFFD95926)
    val series3 = Color(0xFF199E70)
    val grid = Color(0xFF242836)
    val axis = Color(0xFF383C4A)
    val axisText = Color(0xFF898781)

    /** Value tiers: bulk, hit, big hit, jackpot. */
    fun tierColor(tier: Int) = when (tier) { 3 -> gold; 2 -> orchid; 1 -> mint; else -> text2 }
    fun tierName(tier: Int) = when (tier) { 3 -> "JACKPOT"; 2 -> "BIG HIT"; 1 -> "HIT"; else -> "" }
}

private val scheme = darkColorScheme(
    primary = Vault.mint, onPrimary = Color(0xFF04120E), secondary = Vault.periwinkle, onSecondary = Color(0xFF0A0F24),
    tertiary = Vault.orchid, background = Vault.bg, onBackground = Vault.text, surface = Vault.surface, onSurface = Vault.text,
    surfaceVariant = Vault.surface2, onSurfaceVariant = Vault.text2, surfaceContainer = Vault.surface,
    surfaceContainerHigh = Vault.surface2, surfaceContainerHighest = Vault.surface3, surfaceContainerLow = Vault.surface,
    surfaceContainerLowest = Vault.bg, outline = Color(0x33FFFFFF), outlineVariant = Vault.line, error = Vault.badText,
)

private val type = Typography().let { t ->
    t.copy(
        displayLarge = t.displayLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
        displayMedium = t.displayMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.Bold),
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.Bold),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = t.labelSmall.copy(letterSpacing = 0.6.sp),
    )
}

val Eyebrow = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, color = Vault.muted)

@Composable
fun VaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
}
