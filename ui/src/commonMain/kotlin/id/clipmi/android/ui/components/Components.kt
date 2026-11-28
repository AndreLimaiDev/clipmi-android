package id.clipmi.android.ui.components

import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.clipmi.android.data.TranscriptStatus
import id.clipmi.android.data.VideoStatus
import id.clipmi.android.ui.theme.ClipMi
import id.clipmi.android.ui.theme.ClipMiIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.relocation.BringIntoViewRequester

// ---------------------------------------------------------------------------------------------------------------
// Tata letak dasar
// ---------------------------------------------------------------------------------------------------------------

/** Jarak tepi layar (mockup: 20). */
val ScreenGutter = 20.dp

/** Ruang di bawah daftar agar isi terakhir tidak tertutup navigasi melayang (layar lebar Android: navigasi di samping). */
val FloatingNavSpace: Dp
    @Composable get() = if (isWideScreen() && !BottomTabsAlways) 24.dp else 112.dp

/** iOS: bilah tab asli selalu di bawah, juga di iPad; Android: layar lebar memakai navigasi samping. */
expect val BottomTabsAlways: Boolean

/** Lebar ≥600 dp (tablet, HP miring): navigasi utama pindah ke samping, isi daftar dan form dibatasi lebarnya. */
@Composable
expect fun isWideScreen(): Boolean

/** Layar lebar dan mendatar: pratinjau yang biasanya tetap di atas pindah ke kiri, isian di kanan. */
@Composable
expect fun isSideBySide(): Boolean

/** Lebar maksimum isi daftar dan form di layar lebar, supaya baris tidak memanjang selebar tablet. */
val ReadableWidth = 760.dp

/**
 * Isi satu layar di layar lebar: dibatasi [maxWidth] di tengah (null: selebar layar) dan menghindari poni kamera atau
 * bilah navigasi di sisi kiri/kanan saat miring. Di HP tegak tidak mengubah apa pun.
 */
@Composable
fun ScreenFrame(maxWidth: Dp? = ReadableWidth, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(if (maxWidth != null) Modifier.widthIn(max = maxWidth).fillMaxSize() else Modifier.fillMaxSize()) { content() }
    }
}

/**
 * Layar dengan video yang selalu terlihat. [side] (layar lebar mendatar): [media] di kiri setinggi layar berlatar hitam,
 * bilah status hitam selebar layar, dan [content] di kanan. Tegak: hanya [content]; layar itu menaruh videonya di atas.
 */
@Composable
fun SideBySideMedia(side: Boolean, mediaWeight: Float = 0.45f, media: @Composable (Modifier) -> Unit, content: @Composable () -> Unit) {
    if (!side) {
        content()
        return
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().background(Color.Black).windowInsetsTopHeight(WindowInsets.statusBars))
        Row(Modifier.fillMaxWidth().weight(1f).consumeWindowInsets(WindowInsets.statusBars)) {
            media(Modifier.weight(mediaWeight).fillMaxHeight().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start)))
            Box(Modifier.weight(1f - mediaWeight).fillMaxHeight().background(ClipMi.colors.bg).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End))) { content() }
        }
    }
}

/** Judul layar utama: label kecil "ClipMI" + judul besar, aksi di kanan. */
@Composable
fun ScreenHeader(title: String, modifier: Modifier = Modifier, eyebrow: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            eyebrow?.let { Eyebrow(it) }
            Text(title, style = MaterialTheme.typography.headlineMedium, color = ClipMi.colors.text)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
    }
}

@Composable
fun Eyebrow(text: String, color: Color = ClipMi.colors.accent) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, letterSpacing = 1.sp), color = color)
}

/** Latar di belakang status bar agar isi yang digulir tidak menabrak jam dan ikon. */
@Composable
fun StatusBarScrim(modifier: Modifier = Modifier, color: Color = ClipMi.colors.bg) {
    Box(modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(color))
}

/** Ikon status bar terang selama layar ini tampil (di atas video hitam), dikembalikan saat keluar. */
@Composable
expect fun LightStatusIcons()

/** Judul halaman turunan: tombol kembali bulat + judul, aksi di kanan. */
@Composable
fun SubScreenHeader(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().statusBarsPadding().padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconCircle(ClipMiIcons.Back, "Kembali", onBack, background = Color.Transparent, border = false)
        Spacer(Modifier.width(4.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, color = ClipMi.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** Judul bagian kecil huruf besar di atas grup kartu (Pengaturan). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp),
        color = ClipMi.colors.textMuted,
        modifier = modifier.padding(start = 4.dp),
    )
}

// ---------------------------------------------------------------------------------------------------------------
// Kartu
// ---------------------------------------------------------------------------------------------------------------

/** Kartu standar: latar kartu, garis tipis, sudut 24. */
@Composable
fun ClipMiCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(14.dp),
    spacing: Dp = 10.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = ClipMi.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(c.card)
            .border(1.dp, c.line, MaterialTheme.shapes.large)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

/** Grup baris pengaturan dalam satu kartu; pisahkan baris dengan [GroupDivider]. */
@Composable
fun SettingsGroup(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        title?.let { SectionLabel(it) }
        ClipMiCard(padding = PaddingValues(0.dp), spacing = 0.dp, content = content)
    }
}

@Composable
fun GroupDivider() = Box(Modifier.fillMaxWidth().height(1.dp).background(ClipMi.colors.line))

/** Baris pengaturan: judul, keterangan, dan isi kanan (bawaan: panah bila bisa diketuk). */
@Composable
fun SettingsRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = if (onClick != null) ({ ChevronIcon() }) else null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = ClipMi.colors.text)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ClipMi.colors.textMuted) }
        }
        trailing?.invoke()
    }
}

/** Baris dengan sakelar; seluruh baris bisa diketuk. */
@Composable
fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch) { onCheckedChange(!checked) }
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = ClipMi.colors.text)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ClipMi.colors.textMuted) }
        }
        ClipMiSwitch(checked, onCheckedChange = null)
    }
}

@Composable
fun ClipMiSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?) {
    val c = ClipMi.colors
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedTrackColor = c.accent,
            checkedThumbColor = c.onAccent,
            checkedBorderColor = c.accent,
            uncheckedTrackColor = c.panel,
            uncheckedThumbColor = c.textMuted,
            uncheckedBorderColor = c.lineStrong,
        ),
    )
}

/** Pilihan tunggal dalam satu kartu (pengganti deretan RadioButton); baris terpilih bertanda centang. */
@Composable
fun <T> OptionList(title: String?, options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    val c = ClipMi.colors
    SettingsGroup(title) {
        options.forEachIndexed { i, option ->
            if (i > 0) GroupDivider()
            val active = option == selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.RadioButton) { onSelect(option) }
                    .heightIn(min = 44.dp)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    label(option),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (active) FontWeight.Bold else FontWeight.Medium),
                    color = if (active) c.text else c.textBody,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier.size(20.dp).clip(CircleShape).background(if (active) c.accent else Color.Transparent)
                        .then(if (active) Modifier else Modifier.border(2.dp, c.lineStrong, CircleShape)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (active) Icon(ClipMiIcons.Check, contentDescription = null, tint = c.onAccent, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

@Composable
fun ChevronIcon() = Icon(ClipMiIcons.Chevron, contentDescription = null, tint = ClipMi.colors.textMuted, modifier = Modifier.size(20.dp))

/** Petak angka ringkas (Beranda: jumlah project, kandidat, ruang dipakai). */
@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier, unit: String? = null) {
    val c = ClipMi.colors
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(c.card).border(1.dp, c.line, RoundedCornerShape(18.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = MaterialTheme.typography.headlineSmall, color = c.text, maxLines = 1)
            unit?.let { Text(" $it", style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp), color = c.text, modifier = Modifier.padding(bottom = 3.dp)) }
        }
        Text(label, style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Kotak ikon berwarna lembut (judul kartu fitur, baris pilihan besar). */
@Composable
fun IconBadge(icon: ImageVector, tone: Tone = Tone.Good, size: Dp = 36.dp) {
    val (fg, bg) = tone.colors()
    Box(Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(bg), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(size / 2))
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Tombol
// ---------------------------------------------------------------------------------------------------------------

private val ButtonText = TextStyle(fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)

/** Tombol utama: emerald, pil, tinggi 48. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    height: Dp = 48.dp,
) {
    val c = ClipMi.colors
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = height),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = c.accent,
            contentColor = c.onAccent,
            disabledContainerColor = c.accent.copy(alpha = 0.3f),
            disabledContentColor = c.onAccent.copy(alpha = 0.6f),
        ),
        contentPadding = PaddingValues(horizontal = if (height < 48.dp) 14.dp else 20.dp),
    ) {
        icon?.let {
            Icon(it, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge.merge(ButtonText).let { if (height < 48.dp) it.copy(fontSize = 14.sp) else it }, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Tombol kedua: panel gelap dengan garis tepi. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    height: Dp = 48.dp,
    contentColor: Color = ClipMi.colors.text,
) {
    val c = ClipMi.colors
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = height),
        shape = CircleShape,
        border = BorderStroke(1.dp, c.lineStrong),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = c.panel,
            contentColor = contentColor,
            disabledContainerColor = c.panel.copy(alpha = 0.5f),
            disabledContentColor = c.textMuted.copy(alpha = 0.6f),
        ),
        contentPadding = PaddingValues(horizontal = if (height < 48.dp) 12.dp else 16.dp),
    ) {
        icon?.let {
            Icon(it, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Tombol teks: aksen (atau merah untuk aksi berisiko). */
@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false) {
    val c = ClipMi.colors
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 44.dp),
        shape = CircleShape,
        colors = ButtonDefaults.textButtonColors(contentColor = if (danger) c.bad else c.accent),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/** Tombol ikon bulat 44 (kembali, cari, putar). */
@Composable
fun IconCircle(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    background: Color = ClipMi.colors.card,
    tint: Color = ClipMi.colors.text,
    border: Boolean = true,
    enabled: Boolean = true,
) {
    val c = ClipMi.colors
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .then(if (border) Modifier.border(1.dp, c.line, CircleShape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = if (enabled) tint else tint.copy(alpha = 0.4f), modifier = Modifier.size(20.dp))
    }
}

/** Tombol "Project baru" melayang: pil emerald dengan ikon tambah. */
@Composable
fun FloatingPill(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    PrimaryButton(text, onClick, modifier, icon = icon, height = 56.dp)
}

// ---------------------------------------------------------------------------------------------------------------
// Chip dan tab
// ---------------------------------------------------------------------------------------------------------------

/** Label status kecil berwarna (mockup: 11/700, sudut 10). */
@Composable
fun StatusBadge(label: String, tone: Tone, modifier: Modifier = Modifier) {
    val (fg, bg) = tone.colors()
    Text(
        label,
        color = fg,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(10.dp)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Tombol aksi kecil (impor, salin, terapkan): kotak membulat berlatar, berbeda dari pil filter. */
@Composable
fun ActionChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 36.dp,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    primary: Boolean = false,
) {
    val c = ClipMi.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .height(height)
            .clip(shape)
            .background(if (primary && enabled) c.accent else c.panel)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label.ifEmpty { null }, onClick = onClick)
            .padding(horizontal = if (label.isEmpty()) 10.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        val color = when {
            !enabled -> c.textMuted.copy(alpha = 0.6f)
            primary -> c.onAccent
            else -> c.text
        }
        icon?.let { Icon(it, null, tint = color, modifier = Modifier.size(if (label.isEmpty()) 20.dp else 16.dp)) }
        if (label.isNotEmpty()) Text(label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp), color = color, maxLines = 1)
    }
}

/**
 * Pil untuk memilih atau memfilter (terpilih = gelap). Untuk menjalankan aksi pakai [ActionChip], supaya pengguna bisa
 * membedakan mana yang memfilter dan mana yang menjalankan sesuatu.
 */
@Composable
fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, height: Dp = 36.dp, enabled: Boolean = true) {
    val c = ClipMi.colors
    val shape = RoundedCornerShape(height / 2)
    Box(
        modifier
            .height(height)
            .clip(shape)
            .background(if (selected) c.text else Color.Transparent)
            .then(if (selected) Modifier else Modifier.border(1.dp, c.line, shape))
            .clickable(enabled = enabled, role = Role.Tab, onClick = onClick)
            .padding(horizontal = if (height < 36.dp) 12.dp else 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = if (height < 36.dp) 12.sp else 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold),
            color = when {
                selected -> c.bg
                enabled -> c.textBody
                else -> c.textMuted.copy(alpha = 0.6f)
            },
            maxLines = 1,
        )
    }
}

/**
 * Melebarkan isi sejauh [gutter] ke kiri dan kanan, keluar dari padding daftar. Dipakai untuk baris yang digeser
 * ke samping supaya chip terpotong di tepi layar (tanda bisa digeser), bukan 16dp sebelum tepi.
 */
fun Modifier.bleed(gutter: Dp = ScreenGutter): Modifier = this.layout { measurable, constraints ->
    val extra = gutter.roundToPx()
    val placeable = measurable.measure(constraints.offset(horizontal = extra * 2))
    layout(placeable.width - extra * 2, placeable.height) { placeable.place(-extra, 0) }
}

/** Kotak kosong yang lembut dengan garis putus-putus: tempat yang belum diisi (misalnya hari tanpa jadwal). */
@Composable
fun DashedCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = ClipMi.colors
    val shape = MaterialTheme.shapes.large
    Column(
        modifier.fillMaxWidth().clip(shape).background(c.accentSoft.copy(alpha = 0.35f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .drawBehind {
                val stroke = 1.dp.toPx()
                val radius = 24.dp.toPx()
                drawRoundRect(
                    color = c.accent.copy(alpha = 0.4f),
                    topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
                    size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = stroke,
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
                    ),
                )
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** Baris chip yang digeser kiri–kanan tanpa scrollbar; isi mepet ke tepi layar. */
@Composable
fun ChipRow(modifier: Modifier = Modifier, gutter: Dp = ScreenGutter, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = gutter),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Tab bersegmen (Transkrip | Klip). */
@Composable
fun SegmentedTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, badges: List<Int?> = emptyList()) {
    val c = ClipMi.colors
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.card).border(1.dp, c.line, RoundedCornerShape(18.dp)).padding(4.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val active = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (active) c.line else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        label,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold),
                        color = if (active) c.text else c.textMuted,
                    )
                    // Jumlah sebagai badge kecil (misalnya jumlah klip atau segmen transkrip).
                    // Nol tidak ditampilkan.
                    badges.getOrNull(i)?.takeIf { it > 0 }?.let { count ->
                        Box(
                            Modifier.heightIn(min = 20.dp).clip(RoundedCornerShape(10.dp)).background(if (active) c.accent else c.line).padding(horizontal = 7.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                count.toString(),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = if (active) c.onAccent else c.textMuted,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Isian
// ---------------------------------------------------------------------------------------------------------------

/** Kolom isian dengan label di atas dan keterangan di bawah (mockup Project baru). */
@Composable
fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    supporting: String? = null,
    error: String? = null,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    leadingIcon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = ClipMi.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        label?.let { Text(it, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = c.textBody) }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = placeholder?.let { { Text(it, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
            leadingIcon = leadingIcon?.let { { Icon(it, contentDescription = null, tint = c.textMuted, modifier = Modifier.size(20.dp)) } },
            trailingIcon = trailing,
            isError = error != null,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.text),
            shape = MaterialTheme.shapes.medium,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = c.bg,
                unfocusedContainerColor = c.bg,
                disabledContainerColor = c.bg,
                errorContainerColor = c.bg,
                focusedBorderColor = c.accent,
                unfocusedBorderColor = c.line,
                errorBorderColor = c.bad,
                cursorColor = c.accent,
            ),
        )
        (error ?: supporting)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (error != null) c.bad else c.textMuted) }
    }
}

/**
 * Kolom teks sampul dengan bilah alat ikon di bawahnya: Sorot (membungkus pilihan atau kata di kursor dengan `*`),
 * Hapus sorotan, Baris baru, dan KAPITAL. Tanda `*kata*` tetap tampil di teks (sama dengan aturan judul sampul).
 */
@Composable
fun MarkupField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    supporting: String? = null,
    minLines: Int = 2,
    maxLines: Int = 6,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = ClipMi.colors
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    LaunchedEffect(value) { if (value != field.text) field = TextFieldValue(value, TextRange(value.length)) }
    fun apply(next: TextFieldValue) {
        field = next
        if (next.text != value) onValueChange(next.text)
    }
    /** Pilihan, atau kata di posisi kursor bila tidak ada yang dipilih. */
    fun range(): Pair<Int, Int> {
        val t = field.text
        val sel = field.selection
        if (!sel.collapsed) return sel.min to sel.max
        var start = sel.start.coerceIn(0, t.length)
        var end = start
        while (start > 0 && !t[start - 1].isWhitespace()) start--
        while (end < t.length && !t[end].isWhitespace()) end++
        return start to end
    }
    // Saat kolom aktif dan keyboard terbuka, gulir supaya kolom beserta bilah alat di bawahnya terlihat utuh.
    val requester = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(focused, keyboardOpen) {
        if (focused) {
            delay(250)
            requester.bringIntoView()
        }
    }
    Column(modifier.fillMaxWidth().bringIntoViewRequester(requester), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        label?.let { Text(it, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = c.textBody) }
        OutlinedTextField(
            value = field,
            onValueChange = { apply(it) },
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            trailingIcon = trailing,
            minLines = minLines,
            maxLines = maxLines,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.text),
            shape = MaterialTheme.shapes.medium,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = c.bg,
                unfocusedContainerColor = c.bg,
                focusedBorderColor = c.accent,
                unfocusedBorderColor = c.line,
                cursorColor = c.accent,
            ),
        )
        // Bilah alat di bawah kolom: menu pilihan teks bawaan sistem muncul di atas pilihan, jadi tidak menutupinya.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            MarkupTool("Sorot kata", ClipMiIcons.Highlighter, accent = true) {
                val (start, end) = range()
                if (end > start) {
                    val t = field.text
                    val core = t.substring(start, end).trim('*')
                    val text = t.substring(0, start) + "*" + core + "*" + t.substring(end)
                    apply(TextFieldValue(text, TextRange(start, start + core.length + 2)))
                }
            }
            MarkupTool("Hapus sorotan", ClipMiIcons.Eraser) {
                val sel = field.selection
                val (start, end) = if (sel.collapsed) 0 to field.text.length else sel.min to sel.max
                val t = field.text
                val inner = t.substring(start, end).replace("*", "")
                apply(TextFieldValue(t.substring(0, start) + inner + t.substring(end), TextRange(start, start + inner.length)))
            }
            MarkupTool("Baris baru", ClipMiIcons.NewLine) {
                val t = field.text
                val at = field.selection.max
                apply(TextFieldValue(t.substring(0, at) + "\n" + t.substring(at), TextRange(at + 1)))
            }
            MarkupTool("Huruf kapital", ClipMiIcons.TextCase) {
                val (start, end) = range()
                if (end > start) {
                    val t = field.text
                    val part = t.substring(start, end)
                    val next = if (part == part.uppercase()) part.lowercase() else part.uppercase()
                    apply(TextFieldValue(t.substring(0, start) + next + t.substring(end), TextRange(start, end)))
                }
            }
            supporting?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.textMuted, modifier = Modifier.weight(1f).padding(start = 4.dp), maxLines = 2) }
        }
    }
}

/** Tombol ikon bilah alat teks (kotak membulat 12dp); [label] dibacakan pembaca layar. */
@Composable
private fun MarkupTool(label: String, icon: ImageVector, accent: Boolean = false, onClick: () -> Unit) {
    val c = ClipMi.colors
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(if (accent) c.accent.copy(alpha = 0.16f) else c.panel)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = if (accent) c.accent else c.text, modifier = Modifier.size(20.dp))
    }
}

/** Kolom cari ringkas di atas daftar; [trailingText] misalnya "14 cocok". */
@Composable
fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, trailingText: String? = null, onClear: (() -> Unit)? = null) {
    val c = ClipMi.colors
    Row(
        modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(18.dp)).background(c.card).border(1.dp, c.line, RoundedCornerShape(18.dp)).padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(ClipMiIcons.Search, contentDescription = null, tint = c.textMuted, modifier = Modifier.size(20.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = c.textMuted, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        trailingText?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.textMuted) }
        if (value.isNotEmpty() && onClear != null) {
            IconCircle(ClipMiIcons.Close, "Hapus pencarian", onClear, size = 36.dp, background = Color.Transparent, border = false, tint = c.textMuted)
        } else {
            Spacer(Modifier.width(8.dp))
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Progres, status, dan kosong
// ---------------------------------------------------------------------------------------------------------------

/** Batang progres tebal 6 dengan ujung bulat; [fraction] null = tak tentu. */
@Composable
fun ProgressBar(fraction: Float?, modifier: Modifier = Modifier, color: Color = ClipMi.colors.accent) {
    val c = ClipMi.colors
    if (fraction == null) {
        LinearProgressIndicator(modifier.fillMaxWidth().height(6.dp).clip(CircleShape), color = color, trackColor = c.line)
    } else {
        Box(modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(c.line)) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).clip(CircleShape).background(color))
        }
    }
}

enum class Tone { Good, Busy, Warn, Bad, Idle }

/** Warna teks dan latar lembut untuk satu nada status. */
@Composable
fun Tone.colors(): Pair<Color, Color> {
    val c = ClipMi.colors
    return when (this) {
        Tone.Good -> c.accent to c.accentSoft
        Tone.Busy -> c.busy to c.busySoft
        Tone.Warn -> c.warn to c.warnSoft
        Tone.Bad -> c.bad to c.badSoft
        Tone.Idle -> c.textMuted to c.panel
    }
}

fun VideoStatus.tone() = when (this) {
    VideoStatus.Ready -> Tone.Good
    VideoStatus.Downloading, VideoStatus.Processing -> Tone.Busy
    VideoStatus.Failed -> Tone.Bad
    VideoStatus.Deleted -> Tone.Warn
    VideoStatus.Pending -> Tone.Idle
}

fun TranscriptStatus.tone() = when (this) {
    TranscriptStatus.Ready -> Tone.Good
    TranscriptStatus.Fetching -> Tone.Busy
    TranscriptStatus.NeedsTranscript -> Tone.Warn
    TranscriptStatus.Failed -> Tone.Bad
    TranscriptStatus.Pending -> Tone.Idle
}

/** Keadaan kosong: ikon dalam lingkaran, judul, penjelasan, dan aksi opsional. */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = ClipMiIcons.Film,
    action: (@Composable () -> Unit)? = null,
) {
    val c = ClipMi.colors
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = c.accent, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = c.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = c.textMuted, textAlign = TextAlign.Center)
        action?.let {
            Spacer(Modifier.height(24.dp))
            it()
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Panel bawah, dialog, dan snackbar
// ---------------------------------------------------------------------------------------------------------------

/**
 * Panel bawah untuk form dan pilihan. Android: panel Material (sudut 28, pegangan kecil di atas). iOS: lembar asli
 * (UISheetPresentationController) bila sudah didaftarkan, selain itu panel Material.
 */
@Composable
expect fun ClipMiSheet(onDismiss: () -> Unit, title: String? = null, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit)

/** Dialog hanya untuk konfirmasi berisiko (hapus, timpa). iOS: UIAlertController asli bila sudah didaftarkan. */
@Composable
expect fun ConfirmDialog(title: String, body: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit, danger: Boolean = false)

/** Isi panel bawah: judul, subjudul, lalu isian yang bisa digulir. Dipakai panel Material dan lembar asli iOS. */
@Composable
fun SheetBody(title: String?, subtitle: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = ClipMi.colors
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = ScreenGutter, end = ScreenGutter, top = 8.dp, bottom = 24.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (title != null) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = c.text)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp), color = c.textMuted) }
            }
        }
        content()
    }
}

/** Panel bawah Material (Android, dan cadangan iOS). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaterialSheet(onDismiss: () -> Unit, title: String? = null, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = ClipMi.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.card,
        contentColor = c.text,
        scrimColor = c.scrim,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(Modifier.padding(top = 12.dp, bottom = 6.dp).size(width = 40.dp, height = 4.dp).clip(CircleShape).background(c.lineStrong))
        },
    ) {
        SheetBody(title, subtitle, content = content)
    }
}

/** Dialog konfirmasi Material (Android, dan cadangan iOS). */
@Composable
fun MaterialConfirmDialog(title: String, body: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit, danger: Boolean = false) {
    val c = ClipMi.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.card,
        titleContentColor = c.text,
        textContentColor = c.textBody,
        shape = RoundedCornerShape(28.dp),
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(body, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { GhostButton(confirmLabel, onClick = onConfirm, danger = danger) },
        dismissButton = { GhostButton("Batal", onClick = onDismiss) },
    )
}

/** Pesan singkat di bawah layar (pengganti dialog "OK"). Dipasang sekali di akar navigasi. */
@Stable
class Notifier(internal val host: SnackbarHostState, private val scope: CoroutineScope) {
    /** Jarak tambahan dari bawah (di atas navigasi melayang atau bilah aksi): yang terbesar dari semua layar aktif. */
    internal val insets = mutableStateMapOf<Any, Dp>()
    val bottomInset: Dp get() = insets.values.maxOrNull() ?: 0.dp

    fun show(message: String, action: String? = null, onAction: (() -> Unit)? = null) {
        scope.launch {
            host.currentSnackbarData?.dismiss()
            val long = message.length > 70 || action != null
            val result = host.showSnackbar(
                message,
                actionLabel = action ?: if (long) "OK" else null,
                duration = if (long) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) onAction?.invoke()
        }
    }
}

val LocalNotifier = staticCompositionLocalOf<Notifier> { error("Notifier belum dipasang") }

/** Atur jarak snackbar selama layar ini tampil (mis. di atas bilah "Buat klip"). */
@Composable
fun SnackbarInset(inset: Dp) {
    val notifier = LocalNotifier.current
    val key = remember { Any() }
    DisposableEffect(inset) {
        notifier.insets[key] = inset
        onDispose { notifier.insets.remove(key) }
    }
}

@Composable
fun ClipMiSnackbarHost(notifier: Notifier, modifier: Modifier = Modifier) {
    val c = ClipMi.colors
    SnackbarHost(notifier.host, modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp + notifier.bottomInset)) { data ->
        Snackbar(
            snackbarData = data,
            shape = RoundedCornerShape(18.dp),
            containerColor = c.inverse,
            contentColor = c.onInverse,
            actionColor = c.inverseAccent,
        )
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Gambar dan format
// ---------------------------------------------------------------------------------------------------------------

/**
 * Slider aplikasi: jalur tipis (4 dp) dan bulatan putih, tanpa garis tegak dan titik ujung. Dipakai di semua layar
 * (editor, gaya subtitle, gaya bingkai) supaya seragam.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ClipMiSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val colors = clipMiSliderColors()
    androidx.compose.material3.Slider(
        value = value.coerceIn(valueRange),
        onValueChange = onValueChange,
        valueRange = valueRange,
        onValueChangeFinished = onValueChangeFinished,
        modifier = modifier,
        colors = colors,
        thumb = { ClipMiSliderThumb() },
        track = { androidx.compose.material3.SliderDefaults.Track(sliderState = it, modifier = Modifier.height(4.dp), colors = colors, drawStopIndicator = null, thumbTrackGapSize = 0.dp) },
    )
}

/** Slider rentang dengan tampilan yang sama dengan [ClipMiSlider]. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ClipMiRangeSlider(
    value: ClosedFloatingPointRange<Float>,
    onValueChange: (ClosedFloatingPointRange<Float>) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val colors = clipMiSliderColors()
    androidx.compose.material3.RangeSlider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        onValueChangeFinished = onValueChangeFinished,
        modifier = modifier,
        colors = colors,
        startThumb = { ClipMiSliderThumb() },
        endThumb = { ClipMiSliderThumb() },
        track = { androidx.compose.material3.SliderDefaults.Track(rangeSliderState = it, modifier = Modifier.height(4.dp), colors = colors, drawStopIndicator = null, thumbTrackGapSize = 0.dp) },
    )
}

@Composable
private fun clipMiSliderColors() = ClipMi.colors.let { c ->
    androidx.compose.material3.SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = c.accent, inactiveTrackColor = c.line)
}

@Composable
private fun ClipMiSliderThumb() {
    Box(Modifier.size(20.dp).shadow(2.dp, CircleShape).clip(CircleShape).background(Color.White).border(1.dp, Color.Black.copy(alpha = 0.08f), CircleShape))
}
