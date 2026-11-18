package id.clipmi.android.ui.components

import android.graphics.BitmapFactory
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

// ---------------------------------------------------------------------------------------------------------------
// Tata letak dasar
// ---------------------------------------------------------------------------------------------------------------

/** Jarak tepi layar (mockup: 20). */
val ScreenGutter = 20.dp

/** Ruang di bawah daftar agar isi terakhir tidak tertutup navigasi melayang. */
val FloatingNavSpace = 112.dp

/** Judul layar utama: label kecil "ClipMI" + judul besar, aksi di kanan. */
@Composable
fun ScreenHeader(title: String, modifier: Modifier = Modifier, eyebrow: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            eyebrow?.let { Eyebrow(it) }
            Text(title, style = MaterialTheme.typography.headlineLarge, color = ClipMi.colors.text)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
    }
}

@Composable
fun Eyebrow(text: String, color: Color = ClipMi.colors.accent) {
    Text(text.uppercase(Locale.ROOT), style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, letterSpacing = 1.sp), color = color)
}

/** Latar di belakang status bar agar isi yang digulir tidak menabrak jam dan ikon. */
@Composable
fun StatusBarScrim(modifier: Modifier = Modifier, color: Color = ClipMi.colors.bg) {
    Box(modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(color))
}

/** Ikon status bar terang selama layar ini tampil (di atas video hitam), dikembalikan saat keluar. */
@Composable
fun LightStatusIcons() {
    val activity = LocalActivity.current ?: return
    val view = LocalView.current
    DisposableEffect(activity) {
        val controller = WindowCompat.getInsetsController(activity.window, view)
        val before = controller.isAppearanceLightStatusBars
        controller.isAppearanceLightStatusBars = false
        onDispose { controller.isAppearanceLightStatusBars = before }
    }
}

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
        text.uppercase(Locale.ROOT),
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
                    .heightIn(min = 52.dp)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    label(option),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (active) FontWeight.Bold else FontWeight.Medium),
                    color = if (active) c.text else c.textBody,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier.size(22.dp).clip(CircleShape).background(if (active) c.accent else Color.Transparent)
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

/** Chip filter/aksi: terpilih = warna kebalikan latar, lainnya bergaris tepi. */
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
fun SegmentedTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
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
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold),
                    color = if (active) c.text else c.textMuted,
                )
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

/** Panel bawah untuk form dan pilihan (sudut 28, pegangan kecil di atas). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipMiSheet(onDismiss: () -> Unit, title: String? = null, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
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
        Column(
            Modifier
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
}

/** Dialog hanya untuk konfirmasi berisiko (hapus, timpa). */
@Composable
fun ConfirmDialog(title: String, body: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit, danger: Boolean = false) {
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

/** Gambar dari file (thumbnail), didekode di latar dan diperkecil agar hemat memori. */
@Composable
fun FileImage(file: File?, modifier: Modifier = Modifier, maxSize: Int = 480, placeholder: ImageVector? = ClipMiIcons.Play) {
    var image by remember(file?.path, file?.lastModified()) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file?.path, file?.lastModified()) {
        image = withContext(Dispatchers.IO) {
            if (file == null || !file.isFile) return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxSize) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }
    }
    val c = ClipMi.colors
    Box(modifier.background(c.panel), contentAlignment = Alignment.Center) {
        val bitmap = image
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else if (placeholder != null) {
            Icon(placeholder, contentDescription = null, tint = c.textMuted, modifier = Modifier.size(24.dp))
        }
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(Locale.forLanguageTag("id"), bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(Locale.ROOT, bytes / (1L shl 20).toDouble())
    else -> "%.0f KB".format(Locale.ROOT, bytes / 1024.0)
}
