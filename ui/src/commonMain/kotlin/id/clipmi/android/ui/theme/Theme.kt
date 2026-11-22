package id.clipmi.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Token warna ClipMI (mockup "Sistem UI"): netral hitam kehijauan, satu aksen emerald,
 * warna status hanya untuk status. Mode terang dan gelap mengikuti sistem; editor selalu gelap.
 */
@Immutable
data class ClipMiColors(
    val isDark: Boolean,
    /** Latar layar. */
    val bg: Color,
    /** Kartu dan panel bawah. */
    val card: Color,
    /** Tombol kedua, kolom isian di atas kartu, nav melayang. */
    val panel: Color,
    /** Garis tepi kartu dan pemisah. */
    val line: Color,
    /** Garis tepi tombol kedua. */
    val lineStrong: Color,
    val text: Color,
    /** Teks isi di atas kartu (sedikit lebih redup dari teks utama). */
    val textBody: Color,
    /** Keterangan: waktu, ukuran, petunjuk. */
    val textMuted: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val busy: Color,
    val busySoft: Color,
    val warn: Color,
    val warnSoft: Color,
    val bad: Color,
    val badSoft: Color,
    /** Snackbar dan chip filter terpilih: kebalikan latar. */
    val inverse: Color,
    val onInverse: Color,
    val inverseAccent: Color,
    /** Sorotan hasil pencarian di transkrip. */
    val highlight: Color,
    val onHighlight: Color,
    val scrim: Color,
)

val DarkTokens = ClipMiColors(
    isDark = true,
    bg = Color(0xFF0B0F0E),
    card = Color(0xFF141918),
    panel = Color(0xFF1C2321),
    line = Color(0xFF26302D),
    lineStrong = Color(0xFF2E3936),
    text = Color(0xFFF2F5F4),
    textBody = Color(0xFFC9D3D0),
    textMuted = Color(0xFF9AA7A3),
    accent = Color(0xFF34D399),
    onAccent = Color(0xFF052E1F),
    accentSoft = Color(0x2434D399),
    busy = Color(0xFF7DD3FC),
    busySoft = Color(0x2438BDF8),
    warn = Color(0xFFFCD34D),
    warnSoft = Color(0x24FBBF24),
    bad = Color(0xFFFCA5A5),
    badSoft = Color(0x24F87171),
    inverse = Color(0xFFF2F5F4),
    onInverse = Color(0xFF0B0F0E),
    inverseAccent = Color(0xFF047857),
    highlight = Color(0x4DFBBF24),
    onHighlight = Color(0xFFFDE68A),
    scrim = Color(0x8C000000),
)

val LightTokens = ClipMiColors(
    isDark = false,
    bg = Color(0xFFF4F6F5),
    card = Color(0xFFFFFFFF),
    panel = Color(0xFFEDF1EF),
    line = Color(0xFFDDE3E1),
    lineStrong = Color(0xFFCBD3D0),
    text = Color(0xFF0E1513),
    textBody = Color(0xFF2E3936),
    textMuted = Color(0xFF4F5B57),
    accent = Color(0xFF047857),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0xFFD1FAE5),
    busy = Color(0xFF075985),
    busySoft = Color(0xFFE0F2FE),
    warn = Color(0xFF92400E),
    warnSoft = Color(0xFFFEF3C7),
    bad = Color(0xFFB91C1C),
    badSoft = Color(0xFFFEE2E2),
    inverse = Color(0xFF0E1513),
    onInverse = Color(0xFFFFFFFF),
    inverseAccent = Color(0xFF34D399),
    highlight = Color(0x66FBBF24),
    onHighlight = Color(0xFF78350F),
    scrim = Color(0x66000000),
)

private val LocalClipMiColors = staticCompositionLocalOf { DarkTokens }

/** Akses token: `ClipMi.colors.accent`. */
object ClipMi {
    val colors: ClipMiColors
        @Composable @ReadOnlyComposable get() = LocalClipMiColors.current
}

/**
 * Font antarmuka ClipMI: Plus Jakarta Sans (OFL 1.1, satu berkas variabel 200–800). Berkasnya milik tiap aplikasi
 * (Android: res/font, iOS: bundle), jadi dipasang lewat [install] sebelum layar pertama tampil.
 */
object ClipMiFonts {
    var sans: FontFamily = FontFamily.Default
        private set
    private var cached: Typography? = null

    fun install(family: FontFamily) {
        sans = family
        cached = null
    }

    internal fun typography(): Typography = cached ?: typographyFor(sans).also { cached = it }
}

/** Plus Jakarta Sans (lihat [ClipMiFonts]). */
val JakartaSans: FontFamily get() = ClipMiFonts.sans

private fun style(family: FontFamily, size: Int, weight: FontWeight, line: Int, spacing: Double = 0.0) = TextStyle(
    fontFamily = family,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = spacing.em,
)

/** Skala huruf mockup: judul layar 30, judul bagian 20, judul kartu 15, isi 14, keterangan 12. */
val ClipMiTypography: Typography get() = ClipMiFonts.typography()

private fun typographyFor(f: FontFamily) = Typography(
    displaySmall = style(f, 34, FontWeight.ExtraBold, 40, -0.02),
    headlineLarge = style(f, 30, FontWeight.ExtraBold, 36, -0.02),
    headlineMedium = style(f, 26, FontWeight.ExtraBold, 32, -0.01),
    headlineSmall = style(f, 22, FontWeight.ExtraBold, 28),
    titleLarge = style(f, 20, FontWeight.ExtraBold, 25),
    titleMedium = style(f, 15, FontWeight.Bold, 20),
    titleSmall = style(f, 14, FontWeight.Bold, 18),
    bodyLarge = style(f, 15, FontWeight.Medium, 22),
    bodyMedium = style(f, 14, FontWeight.Medium, 21),
    bodySmall = style(f, 12, FontWeight.Medium, 17),
    labelLarge = style(f, 14, FontWeight.Bold, 18),
    labelMedium = style(f, 12, FontWeight.SemiBold, 16),
    labelSmall = style(f, 11, FontWeight.Bold, 14),
)

/** Sudut: chip 10–14, kolom isian 18, kartu 24, panel bawah 28. */
val ClipMiShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private fun materialColors(t: ClipMiColors) = (if (t.isDark) darkColorScheme() else lightColorScheme()).copy(
    primary = t.accent,
    onPrimary = t.onAccent,
    primaryContainer = t.accentSoft,
    onPrimaryContainer = t.accent,
    inversePrimary = t.inverseAccent,
    secondary = t.textBody,
    onSecondary = t.bg,
    secondaryContainer = t.panel,
    onSecondaryContainer = t.text,
    tertiary = t.warn,
    onTertiary = t.bg,
    tertiaryContainer = t.warnSoft,
    onTertiaryContainer = t.warn,
    background = t.bg,
    onBackground = t.text,
    surface = t.bg,
    onSurface = t.text,
    surfaceVariant = t.panel,
    onSurfaceVariant = t.textMuted,
    surfaceTint = Color.Transparent,
    inverseSurface = t.inverse,
    inverseOnSurface = t.onInverse,
    error = t.bad,
    onError = t.bg,
    errorContainer = t.badSoft,
    onErrorContainer = t.bad,
    outline = t.lineStrong,
    outlineVariant = t.line,
    scrim = t.scrim,
    surfaceBright = t.panel,
    surfaceDim = t.bg,
    surfaceContainerLowest = t.bg,
    surfaceContainerLow = t.card,
    surfaceContainer = t.card,
    surfaceContainerHigh = t.card,
    surfaceContainerHighest = t.panel,
)

/** Terang/gelap mengikuti sistem. Tanpa warna dinamis: identitas ClipMI tetap sama di semua HP. */
@Composable
fun ClipMiTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val tokens = if (darkTheme) DarkTokens else LightTokens
    CompositionLocalProvider(LocalClipMiColors provides tokens) {
        MaterialTheme(colorScheme = materialColors(tokens), typography = ClipMiTypography, shapes = ClipMiShapes, content = content)
    }
}

/** Editor dan pemutar selalu gelap, apa pun tema sistem. */
@Composable
fun ClipMiDarkSurface(content: @Composable () -> Unit) = ClipMiTheme(darkTheme = true, content = content)
