package id.clipmi.android.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import id.clipmi.android.ui.components.ActionChip
import id.clipmi.android.ui.components.ChipRow
import id.clipmi.android.ui.components.FilterPill
import id.clipmi.android.ui.components.IconCircle
import id.clipmi.android.ui.theme.ClipMi
import id.clipmi.android.ui.theme.ClipMiDarkSurface
import id.clipmi.android.ui.theme.ClipMiIcons

/**
 * Bagian sampul yang terlihat di tempat sampul tampil selain layar penuh. Perkiraan dari panduan ukuran 2025–2026
 * (bukan ukuran resmi platform): grid profil TikTok memotong tengah 3:4, feed Instagram 4:5, dan rak Shorts YouTube
 * menampilkan sampul utuh tetapi judul video menutupi bagian bawahnya.
 */
private enum class CoverGuide(val label: String, val note: String) {
    TikTok("TikTok", "Grid profil TikTok: bagian tengah 3:4"),
    Feed("Feed", "Feed Instagram: bagian tengah 4:5"),
    Shorts("Shorts", "Rak Shorts YouTube: bagian bawah tertutup judul"),
}

/** Sampul layar penuh dengan garis potongan tiap platform dan tombol unduh. */
@Composable
fun CoverViewer(image: ImageBitmap, saving: Boolean, savedTo: String?, onDownload: () -> Unit, onClose: () -> Unit) {
    // Tepi ke tepi: menutupi seluruh layar editor (Android 10 tetap menyisakan jarak bila decorFitsSystemWindows aktif).
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ClipMiDarkSurface {
            var guide by rememberSaveable { mutableStateOf<CoverGuide?>(CoverGuide.TikTok) }
            Column(Modifier.fillMaxSize().background(EDITOR_BG).safeDrawingPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconCircle(ClipMiIcons.Close, "Tutup", onClose, background = Color.Transparent, border = false)
                    Text("Sampul", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 4.dp))
                    ActionChip(if (saving) "Menyimpan…" else if (savedTo != null) "Tersimpan" else "Unduh", onDownload, height = 40.dp, icon = if (savedTo != null && !saving) ClipMiIcons.CircleCheck else ClipMiIcons.Download, primary = true, enabled = !saving)
                }
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    val ratio = image.width.toFloat() / image.height
                    val w = minOf(maxWidth.value, maxHeight.value * ratio)
                    Box(Modifier.size(w.dp, (w / ratio).dp).clip(RoundedCornerShape(12.dp))) {
                        Image(image, contentDescription = "Sampul", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                        guide?.let { GuideOverlay(it, Modifier.fillMaxSize()) }
                    }
                }
                Text(guide?.note ?: "Tanpa garis", color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp))
                savedTo?.let { Text("Tersimpan di Galeri › $it", color = ClipMi.colors.accent, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) }
                ChipRow(gutter = 4.dp) {
                    CoverGuide.entries.forEach { g -> FilterPill(g.label, selected = guide == g, height = 36.dp, onClick = { guide = g }) }
                    FilterPill("Tanpa garis", selected = guide == null, height = 36.dp, onClick = { guide = null })
                }
            }
        }
    }
}

/** Bagian yang terpotong/tertutup digelapkan; batas bagian yang terlihat digaris putus-putus. */
@Composable
private fun GuideOverlay(guide: CoverGuide, modifier: Modifier) {
    Canvas(modifier) {
        val shade = Color.Black.copy(alpha = 0.55f)
        val visible: Rect = when (guide) {
            CoverGuide.TikTok -> centered(size, 3f / 4f)
            CoverGuide.Feed -> centered(size, 4f / 5f)
            // Judul dan nama kanal di rak Shorts menutupi kira-kira seperlima bagian bawah.
            CoverGuide.Shorts -> Rect(0f, 0f, size.width, size.height * 0.78f)
        }
        clipRect(visible.left, visible.top, visible.right, visible.bottom, clipOp = ClipOp.Difference) { drawRect(shade) }
        val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
        val inset = 2.dp.toPx()
        drawRect(
            Color.White,
            topLeft = Offset(visible.left + inset / 2, visible.top + inset / 2),
            size = Size(visible.width - inset, visible.height - inset),
            style = Stroke(width = inset, pathEffect = dash),
        )
    }
}

/** Kotak terbesar berasio [ratio] (lebar/tinggi) di tengah area [size]. */
private fun centered(size: Size, ratio: Float): Rect {
    val w = minOf(size.width, size.height * ratio)
    val h = w / ratio
    val left = (size.width - w) / 2
    val top = (size.height - h) / 2
    return Rect(left, top, left + w, top + h)
}
