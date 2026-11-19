package id.clipmi.android.ui.styles

import id.clipmi.android.ui.components.ActionChip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.clipmi.android.AppContainer
import id.clipmi.android.data.CaptionStyleEntity
import id.clipmi.android.domain.toCore
import id.clipmi.android.ui.components.ClipMiCard
import id.clipmi.android.ui.components.ConfirmDialog
import id.clipmi.android.ui.components.EmptyState
import id.clipmi.android.ui.components.Field
import id.clipmi.android.ui.components.FloatingPill
import id.clipmi.android.ui.components.LocalNotifier
import id.clipmi.android.ui.components.PrimaryButton
import id.clipmi.android.ui.components.ScreenGutter
import id.clipmi.android.ui.components.SecondaryButton
import id.clipmi.android.ui.components.StatusBadge
import id.clipmi.android.ui.components.SubScreenHeader
import id.clipmi.android.ui.components.Tone
import id.clipmi.android.ui.theme.ClipMi
import id.clipmi.android.ui.theme.ClipMiIcons
import id.clipmi.core.render.CaptionStyle
import id.clipmi.core.render.Fonts
import kotlinx.coroutines.launch
import java.io.File

/** Halaman Gaya Subtitle: daftar preset; gaya bawaan dipakai klip yang belum memilih gaya. */
@Composable
fun CaptionStylesScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onEdit: (id: Long, copyOf: Long) -> Unit,
    header: @Composable () -> Unit = { SubScreenHeader("Gaya subtitle", onBack) },
) {
    val styles by container.database.styles().observeCaptionStyles().collectAsState(initial = null)
    val fonts = remember { container.renderer.fontsDir() }
    val families = remember { HashMap<String, FontFamily>() }

    Box(Modifier.fillMaxSize().background(ClipMi.colors.bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            header()
            val list = styles
            if (list != null && list.isEmpty()) {
                EmptyState("Belum ada gaya", "Buat gaya subtitle pertama.", icon = ClipMiIcons.Edit)
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = ScreenGutter, end = ScreenGutter, top = 4.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(list.orEmpty(), key = { it.id }) { style ->
                    val family = families.getOrPut(style.fontFamily) { fontFamily(fonts, style.fontFamily) }
                    val usage by produceState(0, style) { value = container.database.styles().captionUsage(style.id, style.isDefault) }
                    ClipMiCard(onClick = { onEdit(style.id, 0) }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            CaptionSample(style, family, Modifier.width(96.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(style.name, style = MaterialTheme.typography.titleMedium, color = ClipMi.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${Fonts.AVAILABLE[style.fontFamily]?.label ?: style.fontFamily} · ${style.fontSize} · ${CaptionStyle.EFFECTS[style.effect] ?: style.effect}",
                                    style = MaterialTheme.typography.bodySmall, color = ClipMi.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Text(if (usage == 0) "Belum dipakai klip" else "Dipakai $usage klip", style = MaterialTheme.typography.bodySmall, color = ClipMi.colors.textMuted)
                            }
                            if (style.isDefault) StatusBadge("Bawaan", Tone.Good)
                        }
                    }
                }
            }
        }
    }
}

fun fontFamily(dir: File, family: String): FontFamily =
    runCatching { FontFamily(Font(id.clipmi.android.domain.UserFonts.file(dir, family))) }.getOrDefault(FontFamily.Default)

/** "Sabar" dengan huruf, warna, dan sorotan gaya (gambar kecil di daftar). */
@Composable
fun CaptionSample(style: CaptionStyleEntity, family: FontFamily, modifier: Modifier = Modifier) {
    val sample = if (style.uppercase) "SABAR" else "Sabar"
    Box(
        modifier.height(56.dp).clip(RoundedCornerShape(14.dp)).background(androidx.compose.ui.graphics.Color(0xFF1E2E29)),
        contentAlignment = Alignment.Center,
    ) {
        val color = hexColor(if (style.highlightActiveWord && style.highlightStyle == "color") style.highlightColor else style.primaryColor)
        Text(
            sample,
            style = TextStyle(fontFamily = family, fontSize = 20.sp, color = color, shadow = Shadow(hexColor(style.outlineColor), blurRadius = 5f)),
            modifier = if (style.highlightActiveWord && style.highlightStyle == "box") {
                Modifier.clip(RoundedCornerShape(4.dp)).background(hexColor(style.highlightColor)).padding(horizontal = 6.dp)
            } else Modifier,
        )
    }
}

/**
 * Ubah atau buat gaya subtitle (`CaptionStyleForm` desktop). [id] 0 = gaya baru; [copyOf] = salin isian gaya lain.
 */
@Composable
fun CaptionStyleEditScreen(container: AppContainer, id: Long, copyOf: Long, onBack: () -> Unit, onDuplicate: (Long) -> Unit) {
    val scope = rememberCoroutineScope()
    val notifier = LocalNotifier.current
    val dao = container.database.styles()
    var draft by remember { mutableStateOf<CaptionStyleEntity?>(null) }
    var original by remember { mutableStateOf<CaptionStyleEntity?>(null) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val sample = rememberSampleFrame(container)

    LaunchedEffect(id, copyOf) {
        val loaded = when {
            id != 0L -> dao.captionStyle(id)
            copyOf != 0L -> dao.captionStyle(copyOf)?.let { it.copy(id = 0, name = freeName(it.name + " salinan") { n -> dao.captionNameTaken(n, 0) > 0 }, isDefault = false) }
            else -> CaptionStyleEntity(name = freeName("Gaya baru") { n -> dao.captionNameTaken(n, 0) > 0 })
        }
        draft = loaded
        original = loaded
    }
    val style = draft ?: return
    val dirty = style != original
    val leave = rememberDiscardGuard(dirty, onBack)

    fun update(change: (CaptionStyleEntity) -> CaptionStyleEntity) {
        draft = change(style)
    }

    fun save() = scope.launch {
        val name = style.name.trim().replace(Regex("\\s+"), " ")
        nameError = when {
            name.isEmpty() -> "Nama gaya wajib diisi."
            name.length > 60 -> "Nama gaya maksimal 60 karakter."
            dao.captionNameTaken(name, style.id) > 0 -> "Nama gaya ini sudah dipakai."
            else -> null
        }
        if (nameError != null) return@launch
        val saved = style.copy(name = name, updatedAt = System.currentTimeMillis())
        if (saved.id == 0L) {
            val newId = dao.insert(saved)
            // Gaya pertama otomatis menjadi bawaan.
            if (dao.defaultCaptionStyle()?.isDefault != true) dao.makeCaptionDefault(newId)
        } else {
            dao.update(saved)
        }
        notifier.show("Gaya \"$name\" disimpan.")
        onBack()
    }

    Column(Modifier.fillMaxSize().background(ClipMi.colors.bg).statusBarsPadding()) {
        SubScreenHeader(if (id == 0L) "Gaya subtitle baru" else "Ubah gaya subtitle", leave) {
            ActionChip("Simpan", { save() }, height = 40.dp, enabled = dirty || style.id == 0L, primary = true)
        }
        // Pratinjau tetap di atas (miring: di kiri); hanya isian yang bergulir.
        StyleEditBody(expandedWidth = 190.dp, compactWidth = 112.dp, preview = { width ->
            CaptionStylePreview(container, style.toCore(), sample, Modifier.width(width))
        }) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ScreenGutter).navigationBarsPadding().padding(top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Field(style.name, { v -> update { it.copy(name = v) }; nameError = null }, label = "Nama gaya", singleLine = true, error = nameError)

            CaptionStyleFields(style, ::update)

            if (style.id != 0L) {
                FieldGroup("Gaya ini") {
                    if (!style.isDefault) {
                        SecondaryButton("Jadikan bawaan", onClick = {
                            scope.launch { dao.makeCaptionDefault(style.id); notifier.show("\"${style.name}\" jadi gaya bawaan.") ; original = original?.copy(isDefault = true); draft = style.copy(isDefault = true) }
                        }, modifier = Modifier.fillMaxWidth())
                    }
                    SecondaryButton("Duplikat", onClick = { onDuplicate(style.id) }, icon = ClipMiIcons.Copy, modifier = Modifier.fillMaxWidth())
                    if (!style.isDefault) {
                        SecondaryButton("Hapus gaya", onClick = { confirmDelete = true }, icon = ClipMiIcons.Trash, modifier = Modifier.fillMaxWidth(), contentColor = ClipMi.colors.bad)
                    } else {
                        Text("Gaya bawaan tidak bisa dihapus. Jadikan gaya lain bawaan dulu.", style = MaterialTheme.typography.bodySmall, color = ClipMi.colors.textMuted)
                    }
                }
            }
            Spacer(Modifier.size(8.dp))
        }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Hapus gaya \"${style.name}\"?",
            body = "Klip yang memakai gaya ini kembali ke gaya bawaan.",
            confirmLabel = "Hapus",
            danger = true,
            onConfirm = {
                confirmDelete = false
                scope.launch { dao.captionStyle(style.id)?.let { dao.delete(it) }; notifier.show("Gaya dihapus."); onBack() }
            },
            onDismiss = { confirmDelete = false },
        )
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

/** Isian gaya subtitle (dipakai halaman Gaya subtitle dan sheet edit gaya di editor). */
@Composable
fun CaptionStyleFields(style: CaptionStyleEntity, update: ((CaptionStyleEntity) -> CaptionStyleEntity) -> Unit) {
    val fontsDir = id.clipmi.android.nativebridge.AssRenderer.installFonts(androidx.compose.ui.platform.LocalContext.current)
    FieldGroup("Huruf") {
        FontChoiceRow("Font", fontsDir, style.fontFamily) { v -> update { it.copy(fontFamily = v) } }
        IntSlider("Ukuran", style.fontSize, 40..140, { v -> update { it.copy(fontSize = v) } })
        ToggleField("Huruf kapital semua", null, style.uppercase) { v -> update { it.copy(uppercase = v) } }
        ColorField("Warna teks", style.primaryColor) { v -> update { it.copy(primaryColor = v) } }
        ChoiceRow("Efek", CaptionStyle.EFFECTS, style.effect) { v -> update { it.copy(effect = v) } }
        ColorField("Warna garis tepi", style.outlineColor) { v -> update { it.copy(outlineColor = v) } }
    }

    FieldGroup("Tampilan") {
        ChoiceRow("Posisi", CaptionStyle.POSITIONS, style.position) { v -> update { it.copy(position = v) } }
        IntSlider("Kata per tampilan", style.wordsPerLine, 1..8, { v -> update { it.copy(wordsPerLine = v) } })
        ChoiceRow("Animasi", CaptionStyle.ANIMATIONS, style.animation) { v -> update { it.copy(animation = v) } }
        ToggleField("Kotak latar per baris", null, style.lineBox) { v -> update { it.copy(lineBox = v) } }
        if (style.lineBox) {
            ColorField("Warna kotak", style.lineBoxColor) { v -> update { it.copy(lineBoxColor = v) } }
            IntSlider("Kepekatan kotak", style.lineBoxOpacity, 10..100, { v -> update { it.copy(lineBoxOpacity = v) } }, suffix = "%")
        }
    }

    FieldGroup("Sorotan") {
        ToggleField("Sorot kata yang diucapkan", "Karaoke", style.highlightActiveWord) { v -> update { it.copy(highlightActiveWord = v) } }
        if (style.highlightActiveWord) {
            ChoiceRow("Cara sorot", CaptionStyle.HIGHLIGHT_STYLES, style.highlightStyle) { v -> update { it.copy(highlightStyle = v) } }
            ColorField("Warna sorotan", style.highlightColor) { v -> update { it.copy(highlightColor = v) } }
        }
        ToggleField("Redupkan kata berikutnya", null, style.upcomingColor != null) { v -> update { it.copy(upcomingColor = if (v) "#9CA3AF" else null) } }
        style.upcomingColor?.let { color -> ColorField("Warna kata berikutnya", color) { v -> update { it.copy(upcomingColor = v) } } }
        ToggleField("Warnai kata kunci", "Dari AI atau ditandai di editor", style.keywordHighlight) { v -> update { it.copy(keywordHighlight = v) } }
        if (style.keywordHighlight) ColorField("Warna kata kunci", style.keywordColor) { v -> update { it.copy(keywordColor = v) } }
    }
}
