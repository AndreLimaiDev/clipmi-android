package id.clipmi.ios

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import kotlinx.coroutines.launch
import id.clipmi.android.ui.components.LocalNotifier
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.clipmi.android.ui.components.ChipRow
import id.clipmi.android.ui.components.ClipMiSheet
import id.clipmi.android.ui.components.ClipMiSlider
import id.clipmi.android.ui.components.ClipMiSwitch
import id.clipmi.android.ui.components.ConfirmDialog
import id.clipmi.android.ui.components.Field
import id.clipmi.android.ui.components.FilterPill
import id.clipmi.android.ui.components.IconCircle
import id.clipmi.android.ui.components.PrimaryButton
import id.clipmi.android.ui.components.ScreenGutter
import id.clipmi.android.ui.components.SecondaryButton
import id.clipmi.android.ui.components.SegmentedTabs
import id.clipmi.android.ui.components.SubScreenHeader
import id.clipmi.android.ui.components.bleed
import id.clipmi.android.ui.theme.ClipMi
import id.clipmi.android.ui.theme.ClipMiIcons
import id.clipmi.core.render.Fonts
import kotlin.math.roundToInt

// Isian halaman gaya (padanan ui/styles/StyleFields.kt Android).

/** Warna #RRGGBB menjadi Color (putih bila tidak sah). */
fun hexColor(value: String?): Color =
    value?.trimStart('#')?.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Color(0xFF000000 or it) } ?: Color.White

private val HEX = Regex("^#[0-9A-Fa-f]{6}$")

/** Palet cepat; warna lain lewat kode hex. */
private val SWATCHES = listOf(
    "#FFFFFF", "#000000", "#FFD400", "#F97316", "#EF4444", "#22C55E", "#34D399", "#3B82F6", "#1E3A8A", "#A855F7", "#9CA3AF", "#111827",
)

@Composable
fun FieldLabel(text: String, value: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = ClipMi.colors.textBody, modifier = Modifier.weight(1f))
        value?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = ClipMi.colors.textMuted) }
    }
}

@Composable
fun IntSlider(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit, suffix: String = "", modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        FieldLabel(label, "$value$suffix")
        ClipMiSlider(
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
        ChipRow(Modifier.bleed(16.dp), gutter = 16.dp) {
            options.forEach { (value, text) -> FilterPill(text, selected = value == selected, height = 36.dp, onClick = { onSelect(value) }) }
        }
    }
}

/**
 * Pilihan font sebagai kartu kecil dengan contoh hurufnya, digeser ke samping: font bawaan, font tambahan pengguna (tekan
 * lama untuk menghapus), lalu kartu "Tambah font" (TTF/OTF dari Files). Sama dengan Android.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FontChoiceRow(label: String, fonts: IosUserFonts, selected: String, onSelect: (String) -> Unit) {
    val c = ClipMi.colors
    val notifier = LocalNotifier.current
    val scope = rememberCoroutineScope()
    val families = remember { HashMap<String, FontFamily>() }
    var removing by remember { mutableStateOf<String?>(null) }
    val custom = fonts.entries
    val options = Fonts.AVAILABLE.map { (key, font) -> key to font.label } + custom.map { it.family to it.family }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel(label, options.firstOrNull { it.first == selected }?.second ?: selected)
        ChipRow(Modifier.bleed(16.dp), gutter = 16.dp) {
            options.forEach { (key, text) ->
                val on = key == selected
                val user = custom.any { it.family == key }
                val family = families.getOrPut(key) { captionFontFamily(fonts.dir, key) }
                Column(
                    Modifier.width(84.dp).clip(RoundedCornerShape(12.dp)).background(if (on) c.text else c.panel)
                        .combinedClickable(onClickLabel = text, onLongClick = if (user) ({ removing = key }) else null, onLongClickLabel = if (user) "Hapus font" else null) { onSelect(key) }
                        .padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Aa", style = TextStyle(fontFamily = family, fontSize = 22.sp, color = if (on) c.bg else c.text))
                    Text(text, style = MaterialTheme.typography.labelSmall, color = if (on) c.bg else c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
                }
            }
            Column(
                Modifier.width(84.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, c.lineStrong, RoundedCornerShape(12.dp))
                    .clickable(onClickLabel = "Tambah font") {
                        scope.launch {
                            runCatching { fonts.pickAndAdd() }
                                .onSuccess { entry ->
                                    if (entry != null) {
                                        families.remove(entry.family)
                                        onSelect(entry.family)
                                        notifier.show("Font \"${entry.family}\" ditambahkan.")
                                    }
                                }
                                .onFailure { notifier.show(it.message ?: "Font tidak bisa ditambahkan.") }
                        }
                    }
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(ClipMiIcons.Plus, null, tint = c.text, modifier = Modifier.size(26.dp))
                Text("Tambah font", style = MaterialTheme.typography.labelSmall, color = c.textMuted, maxLines = 1)
            }
        }
    }
    removing?.let { family ->
        ConfirmDialog(
            "Hapus font?",
            "Font \"$family\" dihapus dari iPhone ini. Gaya yang memakainya beralih ke font bawaan saat dirender.",
            "Hapus",
            danger = true,
            onConfirm = {
                removing = null
                fonts.remove(family)
                families.remove(family)
                if (selected == family) onSelect(Fonts.DEFAULT)
            },
            onDismiss = { removing = null },
        )
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

/** Gambar gaya (logo, latar, template): pratinjau kecil, ganti, dan hapus. [path] absolut. */
@Composable
fun ImageField(label: String, path: String?, hint: String, onPick: () -> Unit, onRemove: (() -> Unit)?, busy: Boolean = false, error: String? = null) {
    val c = ClipMi.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel(label)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, c.line, RoundedCornerShape(14.dp))) {
                FileThumbnail(path, Modifier.size(64.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(if (busy) "Memproses…" else if (path != null) "Ganti" else "Pilih gambar", onClick = onPick, enabled = !busy, height = 40.dp)
                    if (onRemove != null && path != null) SecondaryButton("Hapus", onClick = onRemove, height = 40.dp, contentColor = c.bad)
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
        ClipMiSwitch(checked, onCheckedChange = null)
    }
}

/** Kembali dari halaman ubah gaya: bila ada perubahan yang belum disimpan, tanya dulu sebelum dibuang. */
@Composable
fun rememberDiscardGuard(dirty: Boolean, onBack: () -> Unit): () -> Unit {
    var asking by remember { mutableStateOf(false) }
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

/** Ukuran pratinjau yang dipilih, sama untuk semua halaman edit gaya. */
object PreviewSize {
    var compact by mutableStateOf(true)
}

/** Pratinjau yang tetap di atas halaman edit gaya (isian di bawahnya saja yang bergulir), bisa diperkecil/diperbesar. */
@Composable
fun StickyPreview(expandedWidth: Dp, compactWidth: Dp, content: @Composable (Dp) -> Unit) {
    val c = ClipMi.colors
    val width by animateDpAsState(if (PreviewSize.compact) compactWidth else expandedWidth, label = "pratinjau")
    Box(Modifier.fillMaxWidth().background(c.bg).padding(top = 4.dp, bottom = 8.dp)) {
        Box(Modifier.align(Alignment.Center)) { content(width) }
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(c.panel)
                .border(1.dp, c.line, RoundedCornerShape(10.dp))
                .clickable(role = Role.Button, onClickLabel = if (PreviewSize.compact) "Perbesar pratinjau" else "Perkecil pratinjau") { PreviewSize.compact = !PreviewSize.compact }
                .padding(8.dp),
        ) {
            Icon(if (PreviewSize.compact) ClipMiIcons.Expand else ClipMiIcons.Collapse, contentDescription = null, tint = c.textBody, modifier = Modifier.size(18.dp))
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
}

/** Judul menu Gaya klip dengan tab Subtitle | Bingkai. */
@Composable
fun StylesHeader(selected: Int, onBack: () -> Unit, onAdd: () -> Unit, onSelect: (Int) -> Unit, actions: @Composable () -> Unit = {}) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SubScreenHeader("Gaya klip", onBack) {
            actions()
            IconCircle(ClipMiIcons.Plus, if (selected == 0) "Gaya subtitle baru" else "Gaya bingkai baru", onAdd)
        }
        SegmentedTabs(listOf("Subtitle", "Bingkai"), selected, onSelect = onSelect, modifier = Modifier.padding(start = ScreenGutter, end = ScreenGutter, bottom = 8.dp))
    }
}

/** Nama yang belum dipakai: [name], atau "[name] 2", "[name] 3", dan seterusnya (`freeName` desktop). */
suspend fun freeName(name: String, taken: suspend (String) -> Boolean): String {
    var candidate = name
    var i = 2
    while (taken(candidate)) {
        candidate = name.take(55) + " " + i
        i++
    }
    return candidate
}
