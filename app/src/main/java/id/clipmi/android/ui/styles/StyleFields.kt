package id.clipmi.android.ui.styles

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import id.clipmi.android.ui.components.ChipRow
import id.clipmi.android.ui.components.ClipMiSheet
import id.clipmi.android.ui.components.ConfirmDialog
import id.clipmi.android.ui.components.Field
import id.clipmi.android.ui.components.FileImage
import id.clipmi.android.ui.components.FilterPill
import id.clipmi.android.ui.components.PrimaryButton
import id.clipmi.android.ui.components.SecondaryButton
import id.clipmi.android.ui.theme.ClipMi
import id.clipmi.android.ui.theme.ClipMiIcons
import java.io.File
import kotlin.math.roundToInt

/** Warna #RRGGBB menjadi Color (putih bila tidak sah). */
fun hexColor(value: String?): Color =
    value?.trimStart('#')?.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Color(0xFF000000 or it) } ?: Color.White

private val HEX = Regex("^#[0-9A-Fa-f]{6}$")

/** Palet cepat; warna lain lewat kode hex. */
private val SWATCHES = listOf(
    "#FFFFFF", "#000000", "#FFD400", "#F97316", "#EF4444", "#22C55E", "#34D399", "#3B82F6", "#1E3A8A", "#A855F7", "#9CA3AF", "#111827",
)

/** Label kecil di atas isian gaya. */
@Composable
fun FieldLabel(text: String, value: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = ClipMi.colors.textBody, modifier = Modifier.weight(1f))
        value?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = ClipMi.colors.textMuted) }
    }
}

/** Penggeser bilangan bulat dengan nilai di kanan label. */
@Composable
fun IntSlider(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit, suffix: String = "", modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        FieldLabel(label, "$value$suffix")
        id.clipmi.android.ui.components.ClipMiSlider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt().coerceIn(range)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
}

/** Pilihan dari beberapa nilai sebagai pil yang bisa digeser (tanpa scrollbar). */
@Composable
fun <T> ChoiceRow(label: String?, options: Map<T, String>, selected: T, onSelect: (T) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        label?.let { FieldLabel(it) }
        // Dibungkus ke baris berikutnya: pilihan di dalam kartu tidak boleh terpotong di tepi.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, text) -> FilterPill(text, selected = value == selected, onClick = { onSelect(value) }) }
        }
    }
}

/** Warna: palet cepat dan kode hex. */
@Composable
fun ColorField(label: String, value: String, onChange: (String) -> Unit) {
    val c = ClipMi.colors
    var editing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel(label, value.uppercase())
        ChipRow(gutter = 0.dp) {
            SWATCHES.forEach { swatch ->
                val selected = swatch.equals(value, ignoreCase = true)
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(hexColor(swatch))
                        .border(if (selected) 3.dp else 1.dp, if (selected) c.accent else c.lineStrong, CircleShape)
                        .clickable(role = Role.RadioButton, onClickLabel = swatch) { onChange(swatch) },
                )
            }
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(c.panel)
                    .border(1.dp, c.lineStrong, CircleShape)
                    .clickable(role = Role.Button, onClickLabel = "Kode warna") { editing = true },
                contentAlignment = Alignment.Center,
            ) { Icon(ClipMiIcons.Edit, contentDescription = null, tint = c.text, modifier = Modifier.size(18.dp)) }
        }
    }
    if (editing) {
        var text by remember { mutableStateOf(value.uppercase()) }
        val valid = HEX.matches(text)
        ClipMiSheet(onDismiss = { editing = false }, title = label, subtitle = "Kode warna #RRGGBB") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(if (valid) hexColor(text) else c.panel).border(1.dp, c.lineStrong, RoundedCornerShape(14.dp)))
                Field(
                    value = text,
                    onValueChange = { text = it.uppercase().take(7) },
                    singleLine = true,
                    error = if (!valid && text.length >= 7) "Warna harus berformat #RRGGBB." else null,
                    modifier = Modifier.weight(1f),
                )
            }
            PrimaryButton("Pakai warna", onClick = { onChange(text); editing = false }, enabled = valid, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Gambar gaya (logo, latar, template): pratinjau kecil, ganti, dan hapus. */
@Composable
fun ImageField(label: String, file: File?, hint: String, onPick: () -> Unit, onRemove: (() -> Unit)?, busy: Boolean = false, error: String? = null) {
    val c = ClipMi.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel(label)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)).background(c.panel).border(1.dp, c.line, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (file?.isFile == true) FileImage(file, Modifier.size(64.dp), maxSize = 192, placeholder = ClipMiIcons.Image)
                else Icon(ClipMiIcons.Image, contentDescription = null, tint = c.textMuted, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(if (busy) "Memproses…" else if (file != null) "Ganti" else "Pilih gambar", onClick = onPick, enabled = !busy, height = 40.dp)
                    if (onRemove != null && file != null) SecondaryButton("Hapus", onClick = onRemove, height = 40.dp, contentColor = c.bad)
                }
                Text(error ?: hint, style = MaterialTheme.typography.bodySmall, color = if (error != null) c.bad else c.textMuted)
            }
        }
    }
}

/** Kelompok isian dalam kartu dengan judul kecil. */
@Composable
fun FieldGroup(title: String, content: @Composable () -> Unit) {
    val c = ClipMi.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = c.textMuted, modifier = Modifier.padding(start = 4.dp))
        Column(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(c.card).border(1.dp, c.line, MaterialTheme.shapes.large).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) { content() }
    }
}

/** Sakelar ringkas di dalam [FieldGroup] (tanpa jarak tepi tambahan). */
@Composable
fun ToggleField(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = ClipMi.colors
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Switch) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.text)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.textMuted) }
        }
        id.clipmi.android.ui.components.ClipMiSwitch(checked, onCheckedChange = null)
    }
}

/**
 * Kembali dari halaman ubah gaya: bila ada perubahan yang belum disimpan, tanya dulu sebelum dibuang
 * (tombol kembali di header maupun tombol/gestur kembali HP).
 */
@Composable
fun rememberDiscardGuard(dirty: Boolean, onBack: () -> Unit): () -> Unit {
    var asking by remember { mutableStateOf(false) }
    BackHandler(enabled = dirty) { asking = true }
    if (asking) {
        ConfirmDialog(
            title = "Buang perubahan?",
            body = "Perubahan pada gaya ini belum disimpan.",
            confirmLabel = "Buang",
            danger = true,
            onConfirm = { asking = false; onBack() },
            onDismiss = { asking = false },
        )
    }
    return { if (dirty) asking = true else onBack() }
}

/**
 * Pratinjau yang tetap di atas halaman edit gaya (isian di bawahnya saja yang bergulir), dengan tombol perkecil/perbesar
 * supaya isian tidak terlalu sempit di layar pendek. Ukurannya diingat selama aplikasi terbuka.
 */
@Composable
fun StickyPreview(expandedWidth: Dp, compactWidth: Dp, content: @Composable (Dp) -> Unit) {
    val c = ClipMi.colors
    val width by androidx.compose.animation.core.animateDpAsState(if (PreviewSize.compact) compactWidth else expandedWidth, label = "pratinjau")
    Box(Modifier.fillMaxWidth().background(c.bg).padding(top = 4.dp, bottom = 8.dp)) {
        Box(Modifier.align(Alignment.Center)) { content(width) }
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp)
                .clip(CircleShape)
                .background(c.panel)
                .border(1.dp, c.line, CircleShape)
                .clickable(role = Role.Button, onClickLabel = if (PreviewSize.compact) "Perbesar pratinjau" else "Perkecil pratinjau") { PreviewSize.compact = !PreviewSize.compact }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(if (PreviewSize.compact) "Perbesar" else "Perkecil", style = MaterialTheme.typography.labelMedium, color = c.textBody)
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
}

/** Ukuran pratinjau yang dipilih, sama untuk semua halaman edit gaya. */
object PreviewSize {
    var compact by mutableStateOf(false)
}
