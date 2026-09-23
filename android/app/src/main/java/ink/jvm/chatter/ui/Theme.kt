package ink.jvm.chatter.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Colors that Material's scheme has no slot for: chat canvas, bubbles, accents. */
data class ChatPalette(
    val canvas: Brush,
    val bubbleMine: Brush,
    val bubblePeer: Color,
    val onBubbleMine: Color,
    val onBubblePeer: Color,
    val quoteBarMine: Color,
    val quoteBarPeer: Color,
    val dayChip: Color,
    val onDayChip: Color,
    val accent: Brush,
    val online: Color,
    val callBackdrop: Brush,
    val bubbleShadow: Boolean,
    /** Bubble corner radius (round / square styles). */
    val bubbleRadius: androidx.compose.ui.unit.Dp = 18.dp,
    /** Wallpaper picked from the gallery; drawn under the canvas gradient when set. */
    val wallpaper: String? = null,
)

/** One accent set = primary hues for light and dark plus the bubble gradient. */
private class Accent(
    val primary: Color, val deep: Color, val light: Color, val container: Color, val onContainer: Color,
    val darkPrimary: Color, val darkOnPrimary: Color, val darkContainer: Color,
    val canvasLight: List<Color>, val canvasDark: List<Color>,
    val bubbleLight: List<Color>, val bubbleDark: List<Color>,
)

private val Plum = Color(0xFF2B1A22)
private val Mauve = Color(0xFF8E5C70)

private val ACCENTS = mapOf(
    // Rose / sakura: warm pinks, blush surfaces, plum text (the original look).
    "rose" to Accent(
        Color(0xFFEE5C8E), Color(0xFFC8336F), Color(0xFFFF8FB4), Color(0xFFFFE4EC), Color(0xFF5A1028),
        Color(0xFFFF9FBD), Color(0xFF4A1024), Color(0xFF7A2A4C),
        listOf(Color(0xFFFFF4F7), Color(0xFFFFE9F0)), listOf(Color(0xFF1A1119), Color(0xFF150D12)),
        listOf(Color(0xFFFF7FA9), Color(0xFFEE5C8E)), listOf(Color(0xFFC24A7E), Color(0xFF8F2F5F)),
    ),
    "sky" to Accent(
        Color(0xFF3E8BE6), Color(0xFF2360B8), Color(0xFF7AB6FF), Color(0xFFDCEBFF), Color(0xFF0E2E5C),
        Color(0xFF8FC1FF), Color(0xFF0A2A52), Color(0xFF244B80),
        listOf(Color(0xFFF2F7FF), Color(0xFFE4EEFF)), listOf(Color(0xFF0F151F), Color(0xFF0B1017)),
        listOf(Color(0xFF63A6FF), Color(0xFF3E8BE6)), listOf(Color(0xFF3F79C4), Color(0xFF2A5490)),
    ),
    "mint" to Accent(
        Color(0xFF2FA57A), Color(0xFF1F7A59), Color(0xFF6ED4AC), Color(0xFFD8F5E8), Color(0xFF0E4A2C),
        Color(0xFF7ED4A5), Color(0xFF00391F), Color(0xFF1E4A34),
        listOf(Color(0xFFF1FBF6), Color(0xFFE3F5EC)), listOf(Color(0xFF0F1A15), Color(0xFF0A120E)),
        listOf(Color(0xFF52C596), Color(0xFF2FA57A)), listOf(Color(0xFF2E8C68), Color(0xFF1F6A4E)),
    ),
    "lavender" to Accent(
        Color(0xFF8B5CF6), Color(0xFF6D3FD1), Color(0xFFB79CFF), Color(0xFFEBE3FF), Color(0xFF32175E),
        Color(0xFFC4ACFF), Color(0xFF2A1055), Color(0xFF4E3080),
        listOf(Color(0xFFF7F3FF), Color(0xFFEEE7FF)), listOf(Color(0xFF15111F), Color(0xFF0F0B17)),
        listOf(Color(0xFFA47DFF), Color(0xFF8B5CF6)), listOf(Color(0xFF7A55D0), Color(0xFF553A98)),
    ),
)

/** Wallpaper gradients selectable in settings; "plain" is a flat surface colour. */
val CHAT_BACKGROUNDS: List<Pair<String, String>> = listOf(
    "rose" to "玫瑰", "sky" to "天空", "mint" to "薄荷", "lavender" to "薰衣草", "dusk" to "黄昏", "plain" to "素色",
)

private fun canvasFor(bg: String, accent: Accent, dark: Boolean): Brush = when (bg) {
    "dusk" -> Brush.verticalGradient(if (dark) listOf(Color(0xFF1E1420), Color(0xFF2A1A12)) else listOf(Color(0xFFFFF0E6), Color(0xFFFFE0EC)))
    "plain" -> Brush.verticalGradient(if (dark) listOf(Color(0xFF14110F), Color(0xFF14110F)) else listOf(Color(0xFFF7F5F2), Color(0xFFF7F5F2)))
    "rose", "sky", "mint", "lavender" -> {
        val a = ACCENTS[bg] ?: accent
        Brush.verticalGradient(if (dark) a.canvasDark else a.canvasLight)
    }
    else -> Brush.verticalGradient(if (dark) accent.canvasDark else accent.canvasLight)
}

private fun lightScheme(a: Accent): ColorScheme = lightColorScheme(
    primary = a.primary,
    onPrimary = Color.White,
    primaryContainer = a.container,
    onPrimaryContainer = a.onContainer,
    secondary = Color(0xFFB56B84),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE8EF),
    onSecondaryContainer = Color(0xFF4A2030),
    tertiary = Color(0xFF3BA776),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDDF5E8),
    onTertiaryContainer = Color(0xFF0E4A2C),
    background = Color(0xFFFFF5F8),
    onBackground = Plum,
    surface = Color(0xFFFFFBFC),
    onSurface = Plum,
    surfaceVariant = Color(0xFFFCE9EF),
    onSurfaceVariant = Mauve,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFF7F9),
    surfaceContainer = Color(0xFFFFF1F5),
    surfaceContainerHigh = Color(0xFFFFEAF0),
    surfaceContainerHighest = Color(0xFFFFE2EA),
    outline = Color(0xFFE8B8C8),
    outlineVariant = Color(0xFFF7DCE5),
    error = Color(0xFFD9414F),
    onError = Color.White,
    errorContainer = Color(0xFFFFE1E4),
    onErrorContainer = Color(0xFF7A1A24),
)

private fun darkScheme(a: Accent): ColorScheme = darkColorScheme(
    primary = a.darkPrimary,
    onPrimary = a.darkOnPrimary,
    primaryContainer = a.darkContainer,
    onPrimaryContainer = Color(0xFFFFDCE6),
    secondary = Color(0xFFE8B0C2),
    onSecondary = Color(0xFF3A1828),
    secondaryContainer = Color(0xFF52303F),
    onSecondaryContainer = Color(0xFFFFE4EC),
    tertiary = Color(0xFF7ED4A5),
    onTertiary = Color(0xFF00391F),
    tertiaryContainer = Color(0xFF1E4A34),
    onTertiaryContainer = Color(0xFFC6F2DA),
    background = Color(0xFF17101A),
    onBackground = Color(0xFFFFE8EE),
    surface = Color(0xFF1E1419),
    onSurface = Color(0xFFFFE8EE),
    surfaceVariant = Color(0xFF3A2430),
    onSurfaceVariant = Color(0xFFDDB2C1),
    surfaceContainerLowest = Color(0xFF130C10),
    surfaceContainerLow = Color(0xFF211619),
    surfaceContainer = Color(0xFF271A20),
    surfaceContainerHigh = Color(0xFF2F2028),
    surfaceContainerHighest = Color(0xFF392731),
    outline = Color(0xFF7A5060),
    outlineVariant = Color(0xFF3F2A34),
    error = Color(0xFFFF8A94),
    onError = Color(0xFF4A0A12),
    errorContainer = Color(0xFF6B1F2A),
    onErrorContainer = Color(0xFFFFDADC),
)

private fun paletteFor(accentKey: String, bg: String, bubbleStyle: String, dark: Boolean): ChatPalette {
    val a = ACCENTS[accentKey] ?: ACCENTS.getValue("rose")
    val wallpaper = bg.takeIf { it.startsWith("file:") }?.removePrefix("file:")
    val radius = if (bubbleStyle == "square") 8.dp else 18.dp
    return if (!dark) ChatPalette(
        canvas = canvasFor(bg, a, false),
        bubbleMine = Brush.linearGradient(a.bubbleLight),
        bubblePeer = Color.White,
        onBubbleMine = Color.White,
        onBubblePeer = Plum,
        quoteBarMine = Color(0xDDFFFFFF),
        quoteBarPeer = a.primary,
        dayChip = Color(0xDDFFFFFF),
        onDayChip = Mauve,
        accent = Brush.linearGradient(listOf(a.light, a.deep)),
        online = Color(0xFF3BA776),
        callBackdrop = Brush.verticalGradient(listOf(Color(0xFF3A1530), Color(0xFF1B0B16))),
        bubbleShadow = true,
        bubbleRadius = radius,
        wallpaper = wallpaper,
    ) else ChatPalette(
        canvas = canvasFor(bg, a, true),
        bubbleMine = Brush.linearGradient(a.bubbleDark),
        bubblePeer = Color(0xFF2B1E26),
        onBubbleMine = Color(0xFFFFEFF4),
        onBubblePeer = Color(0xFFFFE8EE),
        quoteBarMine = Color(0xCCFFFFFF),
        quoteBarPeer = a.darkPrimary,
        dayChip = Color(0xCC2B1E26),
        onDayChip = Color(0xFFDDB2C1),
        accent = Brush.linearGradient(listOf(a.darkPrimary, a.bubbleDark.first())),
        online = Color(0xFF7ED4A5),
        callBackdrop = Brush.verticalGradient(listOf(Color(0xFF2A1022), Color(0xFF0E070C))),
        bubbleShadow = false,
        bubbleRadius = radius,
        wallpaper = wallpaper,
    )
}

val LocalChatPalette = staticCompositionLocalOf { paletteFor("rose", "rose", "round", false) }

private val ChatterShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** [accent], [background], [bubbleStyle] come from Prefs (settings → 外观). */
@Composable
fun ChatterTheme(
    fontScale: Float = 1f,
    accent: String = "rose",
    background: String = "rose",
    bubbleStyle: String = "round",
    content: @Composable () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val base = androidx.compose.ui.platform.LocalDensity.current
    val a = ACCENTS[accent] ?: ACCENTS.getValue("rose")
    CompositionLocalProvider(
        LocalChatPalette provides paletteFor(accent, background, bubbleStyle, dark),
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(base.density, base.fontScale * fontScale),
    ) {
        MaterialTheme(
            colorScheme = if (dark) darkScheme(a) else lightScheme(a),
            shapes = ChatterShapes,
            content = content,
        )
    }
}

/** Accent choices shown in settings. */
val ACCENT_CHOICES: List<Triple<String, String, Color>> = listOf(
    Triple("rose", "玫瑰", Color(0xFFEE5C8E)), Triple("sky", "天空", Color(0xFF3E8BE6)),
    Triple("mint", "薄荷", Color(0xFF2FA57A)), Triple("lavender", "薰衣草", Color(0xFF8B5CF6)),
)

/** First letter of a name for avatar circles. */
fun initialOf(name: String): String = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
