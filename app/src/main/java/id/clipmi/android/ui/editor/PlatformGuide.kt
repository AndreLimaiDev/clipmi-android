package id.clipmi.android.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import id.clipmi.android.ui.theme.ClipMiIcons

/** Tampilan aplikasi tujuan yang bisa ditumpangkan di atas pratinjau editor. */
enum class PlatformGuide(val label: String, val icon: ImageVector) {
    TikTok("TikTok", ClipMiIcons.TikTok),
    Reels("Reels", ClipMiIcons.Instagram),
    Shorts("Shorts", ClipMiIcons.YouTube),
}

/** Pilihan pratinjau editor yang bertahan selama aplikasi terbuka (termasuk saat layar diputar). */
object EditorPreviewPrefs {
    /** Pratinjau diperkecil supaya panel tab lebih lega (HP tegak). */
    var compact by mutableStateOf(false)
    var guide by mutableStateOf<PlatformGuide?>(null)
}

private data class SideItem(val icon: ImageVector?, val label: String?, val y: Float, val avatar: Boolean = false, val disc: Boolean = false)

/**
 * Perkiraan letak tombol, nama akun, dan keterangan tiap aplikasi di layar 9:16 (dari tangkapan layar 1080×1920),
 * ditumpangkan di atas pratinjau supaya terlihat bagian klip yang tertutup. Hanya panduan: tidak ikut dirender.
 */
@Composable
fun PlatformGuideLayer(guide: PlatformGuide, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        // Satu piksel layar 1080 lebar, dalam dp pratinjau.
        val px = maxWidth / 1080f
        fun text(size: Float, weight: FontWeight = FontWeight.SemiBold, alpha: Float = 1f) = TextStyle(
            color = Color.White.copy(alpha = alpha),
            fontSize = with(density) { (px * size).toSp() },
            fontWeight = weight,
            lineHeight = with(density) { (px * size * 1.25f).toSp() },
            shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 1f), 4f),
        )
        val h = maxHeight

        // Bayangan atas dan bawah seperti di aplikasinya, supaya teks putih terbaca.
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.12f).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Transparent))))
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.32f)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)))),
        )

        // Bilah atas.
        when (guide) {
            PlatformGuide.TikTok -> Row(
                Modifier.fillMaxWidth().offset(y = h * 0.035f),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Mengikuti", style = text(44f, alpha = 0.75f))
                Text("  |  ", style = text(36f, alpha = 0.5f))
                Text("Untuk Anda", style = text(46f, FontWeight.Bold))
            }
            PlatformGuide.Reels -> Row(Modifier.fillMaxWidth().offset(y = h * 0.03f).padding(horizontal = px * 40f), verticalAlignment = Alignment.CenterVertically) {
                Text("Reels", style = text(60f, FontWeight.Bold), modifier = Modifier.weight(1f))
                Icon(ClipMiIcons.Image, null, tint = Color.White, modifier = Modifier.size(px * 70f))
            }
            PlatformGuide.Shorts -> Row(
                Modifier.fillMaxWidth().offset(y = h * 0.03f).padding(horizontal = px * 40f),
                horizontalArrangement = Arrangement.spacedBy(px * 48f, Alignment.End),
            ) {
                Icon(ClipMiIcons.Search, null, tint = Color.White, modifier = Modifier.size(px * 66f))
                Icon(ClipMiIcons.More, null, tint = Color.White, modifier = Modifier.size(px * 66f))
            }
        }

        // Kolom tombol kanan.
        val side = when (guide) {
            PlatformGuide.TikTok -> listOf(
                SideItem(null, null, 0.47f, avatar = true),
                SideItem(ClipMiIcons.Heart, "233,6 rb", 0.565f),
                SideItem(ClipMiIcons.Comment, "1.150", 0.655f),
                SideItem(ClipMiIcons.Share, "Bagikan", 0.745f),
                SideItem(null, null, 0.875f, disc = true),
            )
            PlatformGuide.Reels -> listOf(
                SideItem(ClipMiIcons.Heart, "12,4 rb", 0.6f),
                SideItem(ClipMiIcons.Comment, "318", 0.685f),
                SideItem(ClipMiIcons.Send, "1.024", 0.77f),
                SideItem(ClipMiIcons.More, null, 0.845f),
                SideItem(null, null, 0.915f, disc = true),
            )
            PlatformGuide.Shorts -> listOf(
                SideItem(ClipMiIcons.Heart, "8,2 rb", 0.5f),
                SideItem(ClipMiIcons.Comment, "214", 0.6f),
                SideItem(ClipMiIcons.Share, "Bagikan", 0.7f),
                SideItem(ClipMiIcons.Refresh, "Remix", 0.8f),
                SideItem(null, null, 0.9f, disc = true),
            )
        }
        val column = px * 150f
        side.forEach { item ->
            Column(
                Modifier.align(Alignment.TopEnd).offset(x = -px * 20f, y = h * item.y).width(column),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when {
                    item.avatar -> Box(Modifier.size(px * 104f).border(px * 4f, Color.White, CircleShape).padding(px * 4f).background(Color(0xFF9AA7A3), CircleShape))
                    item.disc -> Box(Modifier.size(px * 88f).background(Color(0xFF26302D), RoundedCornerShape(if (guide == PlatformGuide.TikTok) 50 else 22)).border(px * 4f, Color.White.copy(alpha = 0.7f), RoundedCornerShape(if (guide == PlatformGuide.TikTok) 50 else 22)))
                    else -> Icon(item.icon!!, null, tint = Color.White, modifier = Modifier.size(px * 86f))
                }
                item.label?.let { Text(it, style = text(30f), maxLines = 1) }
            }
        }

        // Nama akun dan keterangan di kiri bawah (tidak sampai ke kolom tombol).
        val textWidth = maxWidth - column - px * 70f
        Column(
            Modifier.align(Alignment.TopStart).offset(x = px * 40f, y = h * when (guide) {
                PlatformGuide.TikTok -> 0.825f
                PlatformGuide.Reels -> 0.85f
                PlatformGuide.Shorts -> 0.84f
            }).width(textWidth),
            verticalArrangement = Arrangement.spacedBy(px * 14f),
        ) {
            when (guide) {
                PlatformGuide.TikTok -> {
                    Text("@akunmu · 1-28", style = text(42f, FontWeight.Bold))
                    Text("Keterangan klip kamu #fyp #kajian", style = text(38f, FontWeight.Normal), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("♪ Suara asli - akunmu", style = text(36f, FontWeight.Normal))
                }
                PlatformGuide.Reels -> {
                    AccountRow(px, "akunmu", "Ikuti", text(40f, FontWeight.Bold))
                    Text("Keterangan klip kamu…", style = text(38f, FontWeight.Normal), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("♪ akunmu · Audio asli", style = text(34f, FontWeight.Normal))
                }
                PlatformGuide.Shorts -> {
                    Text("Judul klip kamu #shorts", style = text(42f, FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    AccountRow(px, "@akunmu", "Subscribe", text(38f, FontWeight.Bold))
                }
            }
        }
    }
}

@Composable
private fun AccountRow(px: Dp, name: String, action: String, style: TextStyle) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(px * 18f)) {
        Box(Modifier.size(px * 64f).background(Color(0xFF9AA7A3), CircleShape))
        Text(name, style = style, maxLines = 1)
        Box(Modifier.border(px * 3f, Color.White.copy(alpha = 0.8f), RoundedCornerShape(px * 14f)).padding(horizontal = px * 18f, vertical = px * 6f)) {
            Text(action, style = style.copy(fontSize = style.fontSize * 0.85f))
        }
    }
}
