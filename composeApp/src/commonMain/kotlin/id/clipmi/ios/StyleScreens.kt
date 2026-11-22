package id.clipmi.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.clipmi.android.data.CaptionStyleEntity
import id.clipmi.android.data.FrameStyleEntity
import id.clipmi.android.ui.components.ActionChip
import id.clipmi.android.ui.components.ClipMiCard
import id.clipmi.android.ui.components.ConfirmDialog
import id.clipmi.android.ui.components.EmptyState
import id.clipmi.android.ui.components.Field
import id.clipmi.android.ui.components.LocalNotifier
import id.clipmi.android.ui.components.ScreenGutter
import id.clipmi.android.ui.components.SecondaryButton
import id.clipmi.android.ui.components.StatusBadge
import id.clipmi.android.ui.components.SubScreenHeader
import id.clipmi.android.ui.components.Tone
import id.clipmi.android.ui.theme.ClipMi
import id.clipmi.android.ui.theme.ClipMiIcons
import id.clipmi.core.render.CaptionStyle
import id.clipmi.core.render.Fonts
import id.clipmi.core.render.FrameStyle
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// Gaya klip di iPhone (padanan ui/styles/CaptionStylesScreen.kt dan FrameStylesScreen.kt Android): tabel dan kolom
// yang sama, jadi gaya dari cadangan atau Paket Standar Tim langsung terpakai.

/** Menu Gaya klip: tab Subtitle | Bingkai. [onEdit] (subtitle?, id, copyOf). */
@Composable
fun StylesScreen(
    repo: Repository, renderer: IosRenderer, tab: Int, onTab: (Int) -> Unit, onBack: () -> Unit,
    onEdit: (caption: Boolean, id: Long, copyOf: Long) -> Unit, actions: @Composable () -> Unit = {},
) {
    val dao = repo.db.styles()
    val captions by remember { dao.observeCaptionStyles() }.collectAsState(initial = null)
    val frames by remember { dao.observeFrameStyles() }.collectAsState(initial = null)
    val families = remember { HashMap<String, FontFamily>() }

    Column(Modifier.fillMaxSize().background(ClipMi.colors.bg)) {
        StylesHeader(tab, onBack, onAdd = { onEdit(tab == 0, 0, 0) }, onSelect = onTab, actions = actions)
        if (tab == 0) {
            val list = captions
            if (list != null && list.isEmpty()) EmptyState("Belum ada gaya", "Buat gaya subtitle pertama.", icon = ClipMiIcons.Edit)
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = ScreenGutter, end = ScreenGutter, top = 4.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(list.orEmpty(), key = { it.id }) { style ->
                    val family = families.getOrPut(style.fontFamily) { captionFontFamily(renderer.fontsDir, style.fontFamily) }
                    val usage by produceState(0, style) { value = dao.captionUsage(style.id, style.isDefault) }
                    ClipMiCard(onClick = { onEdit(true, style.id, 0) }) {
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
        } else {
            val list = frames
            if (list != null && list.isEmpty()) EmptyState("Belum ada gaya", "Buat gaya bingkai pertama.", icon = ClipMiIcons.Image)
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = ScreenGutter, end = ScreenGutter, top = 4.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(list.orEmpty(), key = { it.id }) { style ->
                    val usage by produceState(0, style) { value = dao.frameUsage(style.id, style.isDefault) }
                    ClipMiCard(onClick = { onEdit(false, style.id, 0) }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            FrameGlyph(style)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(style.name, style = MaterialTheme.typography.titleMedium, color = ClipMi.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(style.toCore().summary(), style = MaterialTheme.typography.bodySmall, color = ClipMi.colors.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
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

/** "Sabar" dengan huruf, warna, dan sorotan gaya (gambar kecil di daftar). */
@Composable
private fun CaptionSample(style: CaptionStyleEntity, family: FontFamily, modifier: Modifier = Modifier) {
    val sample = if (style.uppercase) "SABAR" else "Sabar"
    Box(modifier.height(56.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF1E2E29)), contentAlignment = Alignment.Center) {
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

/** Gambar kecil tata letak: rasio, latar, dan letak video. */
@Composable
private fun FrameGlyph(frame: FrameStyleEntity) {
    val aspect = FrameStyle.ASPECTS[frame.aspect] ?: FrameStyle.ASPECTS.getValue("9:16")
    val height = 64f
    val width = height * aspect.width / aspect.height
    val video = Color(0xFF3B5A50)
    val bg = when (frame.background) {
        "color" -> hexColor(frame.backgroundColor)
        "gradient" -> hexColor(frame.backgroundColor2)
        else -> Color(0xFF1E2E29)
    }
    Box(Modifier.width(72.dp).height(68.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(width.dp, height.dp).clip(RoundedCornerShape(6.dp)).background(if (frame.mode == "crop") video else bg)) {
            when (frame.mode) {
                "fit" -> Box(Modifier.align(Alignment.Center).fillMaxWidth().height((height * 0.32f).dp).background(video))
                "top" -> Box(
                    Modifier.align(when (frame.videoPosition) { "bottom" -> Alignment.BottomCenter; "top" -> Alignment.TopCenter; else -> Alignment.Center })
                        .fillMaxWidth().height((height * frame.videoHeight / 100f).dp).background(video),
                )
                "template" -> Box(Modifier.align(Alignment.Center).fillMaxWidth(0.8f).fillMaxHeight(0.5f).background(video))
            }
            if (frame.titleBar) Box(Modifier.fillMaxWidth().height(8.dp).background(hexColor(frame.titleBarColor)))
        }
    }
}

/** Ubah atau buat gaya subtitle (`CaptionStyleForm` desktop). [id] 0 = gaya baru; [copyOf] = salin isian gaya lain. */
@Composable
fun CaptionStyleEditScreen(repo: Repository, renderer: IosRenderer, id: Long, copyOf: Long, onBack: () -> Unit, onDuplicate: (Long) -> Unit) {
    val scope = rememberCoroutineScope()
    val notifier = LocalNotifier.current
    val dao = repo.db.styles()
    var draft by remember { mutableStateOf<CaptionStyleEntity?>(null) }
    var original by remember { mutableStateOf<CaptionStyleEntity?>(null) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val sample = rememberSampleFrame(repo)

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
        val saved = style.copy(name = name, updatedAt = nowMillis())
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

    Column(Modifier.fillMaxSize().background(ClipMi.colors.bg)) {
        SubScreenHeader(if (id == 0L) "Gaya subtitle baru" else "Ubah gaya subtitle", leave) {
            ActionChip("Simpan", { save() }, height = 40.dp, enabled = dirty || style.id == 0L, primary = true)
        }
        // Pratinjau tetap di atas; hanya isian yang bergulir.
        StickyPreview(expandedWidth = 190.dp, compactWidth = 112.dp) { width ->
            CaptionStylePreview(renderer, style.toCore(), sample, Modifier.width(width))
        }
        Column(
            Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = ScreenGutter).navigationBarsPadding().padding(top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Field(style.name, { v -> update { it.copy(name = v) }; nameError = null }, label = "Nama gaya", singleLine = true, error = nameError)
            CaptionStyleFields(renderer, style, ::update)
            if (style.id != 0L) {
                FieldGroup("Gaya ini") {
                    if (!style.isDefault) {
                        SecondaryButton("Jadikan bawaan", onClick = {
                            scope.launch {
                                dao.makeCaptionDefault(style.id)
                                notifier.show("\"${style.name}\" jadi gaya bawaan.")
                                original = original?.copy(isDefault = true)
                                draft = style.copy(isDefault = true)
                            }
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

/** Isian gaya subtitle (sama dengan Android). */
@Composable
fun CaptionStyleFields(renderer: IosRenderer, style: CaptionStyleEntity, update: ((CaptionStyleEntity) -> CaptionStyleEntity) -> Unit) {
    FieldGroup("Huruf") {
        FontChoiceRow("Font", renderer.userFonts, style.fontFamily) { v -> update { it.copy(fontFamily = v) } }
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

/**
 * Ubah atau buat gaya bingkai (`FrameStyleForm` desktop). Gambar (logo, latar, template) langsung disalin ke Documents
 * saat dipilih; gambar yang tidak jadi disimpan dihapus lagi saat halaman ditutup.
 */
@Composable
fun FrameStyleEditScreen(repo: Repository, renderer: IosRenderer, media: StyleMedia, id: Long, copyOf: Long, onBack: () -> Unit, onDuplicate: (Long) -> Unit) {
    val scope = rememberCoroutineScope()
    val notifier = LocalNotifier.current
    val dao = repo.db.styles()
    var draft by remember { mutableStateOf<FrameStyleEntity?>(null) }
    var original by remember { mutableStateOf<FrameStyleEntity?>(null) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var imageError by remember { mutableStateOf<Pair<String, String>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    // Gambar yang disalin selama halaman ini terbuka; yang tidak tersimpan dihapus saat keluar.
    val created = remember { mutableSetOf<String>() }
    var saved by remember { mutableStateOf<FrameStyleEntity?>(null) }
    val sample = rememberSampleFrame(repo)
    val caption by produceState(CaptionStyle.DEFAULT) { value = dao.defaultCaptionStyle()?.toCore() ?: CaptionStyle.DEFAULT }

    LaunchedEffect(id, copyOf) {
        val loaded = when {
            id != 0L -> dao.frameStyle(id)
            copyOf != 0L -> dao.frameStyle(copyOf)?.let { from ->
                // Gaya baru dari gaya lain membawa salinan gambarnya sendiri.
                val copies = listOf(from.logoPath, from.backgroundPath, from.templatePath).map { path -> media.copyImage(path)?.also { created += it } }
                from.copy(
                    id = 0, isDefault = false, name = freeName(from.name + " salinan") { n -> dao.frameNameTaken(n, 0) > 0 },
                    logoPath = copies[0], backgroundPath = copies[1], templatePath = copies[2],
                )
            }
            else -> FrameStyleEntity(name = freeName("Bingkai baru") { n -> dao.frameNameTaken(n, 0) > 0 }, mode = "crop")
        }
        draft = loaded
        original = loaded
    }
    DisposableEffect(Unit) {
        onDispose {
            val kept = saved
            val keep = setOfNotNull(kept?.logoPath, kept?.backgroundPath, kept?.templatePath)
            created.filter { it !in keep }.forEach(media::delete)
        }
    }

    val style = draft ?: return
    val dirty = style != original
    val leave = rememberDiscardGuard(dirty, onBack)

    fun update(change: (FrameStyleEntity) -> FrameStyleEntity) {
        draft = change(style)
    }

    val pick: (String) -> Unit = { field ->
        scope.launch {
            busy = field
            imageError = null
            try {
                val current = draft ?: return@launch
                media.importInto(field, current) { path -> created += path }?.let { draft = it }
            } catch (e: Exception) {
                imageError = field to (e.message ?: "Gambar tidak bisa dipakai.")
            } finally {
                busy = null
            }
        }
    }

    fun save() = scope.launch {
        val name = style.name.trim().replace(Regex("\\s+"), " ")
        nameError = when {
            name.isEmpty() -> "Nama gaya wajib diisi."
            name.length > 60 -> "Nama gaya maksimal 60 karakter."
            dao.frameNameTaken(name, style.id) > 0 -> "Nama gaya ini sudah dipakai."
            else -> null
        }
        if (nameError != null) return@launch
        if (style.mode == "template" && (style.templatePath == null || style.templateArea == null)) {
            imageError = "template" to "Pilih gambar template, atau pilih tata letak lain."
            return@launch
        }
        if (style.mode != "template" && style.mode != "crop" && style.background == "image" && style.backgroundPath == null) {
            imageError = "background" to "Pilih gambar latar, atau pilih jenis latar lain."
            return@launch
        }
        val result = style.copy(name = name, watermarkText = style.watermarkText?.trim()?.replace(Regex("\\s+"), " ")?.ifEmpty { null }, updatedAt = nowMillis())
        if (result.id == 0L) {
            val newId = dao.insert(result)
            if (dao.defaultFrameStyle()?.isDefault != true) dao.makeFrameDefault(newId)
        } else {
            dao.update(result)
        }
        // Gambar lama yang diganti atau dihapus ikut dihapus.
        original?.let { old ->
            listOf(old.logoPath to result.logoPath, old.backgroundPath to result.backgroundPath, old.templatePath to result.templatePath)
                .filter { (before, after) -> before != null && before != after && old.id != 0L }
                .forEach { (before, _) -> media.delete(before) }
        }
        saved = result
        notifier.show("Gaya \"$name\" disimpan.")
        onBack()
    }

    val core = remember(style) { style.toCore() }
    val wide = core.canvasWidth() > core.canvasHeight()

    Column(Modifier.fillMaxSize().background(ClipMi.colors.bg)) {
        SubScreenHeader(if (id == 0L) "Gaya bingkai baru" else "Ubah gaya bingkai", leave) {
            ActionChip("Simpan", { save() }, height = 40.dp, enabled = (dirty || style.id == 0L) && busy == null, primary = true)
        }
        StickyPreview(expandedWidth = if (wide) 300.dp else 190.dp, compactWidth = if (wide) 200.dp else 112.dp) { width ->
            FrameStylePreview(media, core, caption, sample, Modifier.width(width))
        }
        Column(
            Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = ScreenGutter).navigationBarsPadding().padding(top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Field(style.name, { v -> update { it.copy(name = v) }; nameError = null }, label = "Nama gaya", singleLine = true, error = nameError)
            FrameStyleFields(repo, media, style, ::update, busy, imageError, pick)
            if (style.id != 0L) {
                FieldGroup("Gaya ini") {
                    if (!style.isDefault) {
                        SecondaryButton("Jadikan bawaan", onClick = {
                            scope.launch {
                                dao.makeFrameDefault(style.id)
                                notifier.show("\"${style.name}\" jadi bingkai bawaan.")
                                original = original?.copy(isDefault = true)
                                draft = style.copy(isDefault = true)
                            }
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
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Hapus gaya \"${style.name}\"?",
            body = "Klip yang memakai gaya ini kembali ke bingkai bawaan. Gambar gaya ini ikut dihapus.",
            confirmLabel = "Hapus",
            danger = true,
            onConfirm = {
                confirmDelete = false
                scope.launch {
                    dao.frameStyle(style.id)?.let { stored ->
                        dao.delete(stored)
                        listOf(stored.logoPath, stored.backgroundPath, stored.templatePath).forEach(media::delete)
                    }
                    notifier.show("Gaya dihapus.")
                    onBack()
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** Rasio hasil: kotak kecil sesuai rasio, label, dan contoh pemakaian. */
@Composable
private fun AspectPicker(selected: String, onSelect: (String) -> Unit) {
    val c = ClipMi.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel("Ukuran video")
        FrameStyle.ASPECTS.entries.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (value, info) ->
                    val active = value == selected
                    Row(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .border(if (active) 2.dp else 1.dp, if (active) c.accent else c.line, RoundedCornerShape(14.dp))
                            .clickable(role = Role.RadioButton) { onSelect(value) }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val (w, h) = if (info.width >= info.height) 22f to 22f * info.height / info.width else 22f * info.width / info.height to 22f
                        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(w.dp, h.dp).border(2.dp, if (active) c.accent else c.textMuted, RoundedCornerShape(3.dp)))
                        }
                        Column {
                            Text(info.label, style = MaterialTheme.typography.titleSmall, color = c.text)
                            Text(info.hint, style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (row.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

/** Posisi logo atau watermark: layar mini 9:16 dengan sembilan titik jangkar dan titik yang bisa diseret (0–100). */
@Composable
private fun AnchorPicker(label: String, x: Int, y: Int, hint: String? = null, onSelect: (Int, Int) -> Unit) {
    val c = ClipMi.colors
    val current = FrameStyle.ANCHORS.values.firstOrNull { it.second == x && it.third == y }?.first ?: "Diatur sendiri · $x, $y"
    val dot = 18.dp
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel(label, current)
        BoxWithConstraints(
            Modifier.size(108.dp, 192.dp).clip(RoundedCornerShape(12.dp)).background(c.panel).border(1.dp, c.line, RoundedCornerShape(12.dp))
                .padding(8.dp)
                .pointerInput(Unit) {
                    fun at(offset: Offset) = Pair(
                        (offset.x / size.width * 100).roundToInt().coerceIn(0, 100),
                        (offset.y / size.height * 100).roundToInt().coerceIn(0, 100),
                    )
                    detectDragGestures(onDragStart = { val (px, py) = at(it); onSelect(px, py) }) { change, _ ->
                        val (px, py) = at(change.position)
                        onSelect(px, py)
                    }
                },
        ) {
            val w = maxWidth - dot
            val h = maxHeight - dot
            FrameStyle.ANCHORS.values.forEach { (name, ax, ay) ->
                Box(
                    Modifier.offset(w * ax / 100, h * ay / 100).size(dot).clip(RoundedCornerShape(6.dp))
                        .clickable(role = Role.RadioButton, onClickLabel = name) { onSelect(ax, ay) },
                    contentAlignment = Alignment.Center,
                ) { Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(c.textMuted)) }
            }
            Box(Modifier.offset(w * x / 100, h * y / 100).size(dot).clip(RoundedCornerShape(6.dp)).background(c.accent))
        }
        hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.textMuted) }
    }
}

/** Isian gaya bingkai (sama dengan Android). [onPick] membuka pemilih gambar untuk "logo", "background", atau "template". */
@Composable
fun FrameStyleFields(
    repo: Repository,
    media: StyleMedia,
    style: FrameStyleEntity,
    update: ((FrameStyleEntity) -> FrameStyleEntity) -> Unit,
    busy: String?,
    imageError: Pair<String, String>?,
    onPick: (String) -> Unit,
) {
    val mode = style.mode
    fun file(path: String?) = path?.takeIf { media.exists(it) }?.let { repo.absolute(it) }
    FieldGroup("Tata letak") {
        ChoiceRow("Video", FrameStyle.MODES, mode) { v ->
            // "Video + area teks" paling sering dipakai dengan video di atas.
            update { it.copy(mode = v, videoPosition = if (v == "top" && it.videoPosition == "center") "top" else it.videoPosition) }
        }
        if (mode == "template") {
            ImageField(
                "Gambar template", file(style.templatePath),
                "PNG selayar penuh dengan bagian video dikosongkan (transparan). Rasio mengikuti ukuran gambar.",
                onPick = { onPick("template") }, onRemove = null, busy = busy == "template",
                error = imageError?.takeIf { it.first == "template" }?.second,
            )
            val info = FrameStyle.ASPECTS[style.aspect]
            Text("Ukuran ${style.aspect}${info?.let { " (${it.width}×${it.height} piksel)" }.orEmpty()}", style = MaterialTheme.typography.bodySmall, color = ClipMi.colors.textMuted)
        } else {
            AspectPicker(style.aspect) { v -> update { it.copy(aspect = v) } }
        }
        if (mode == "fit" || mode == "top") {
            ChoiceRow("Letak video", FrameStyle.VIDEO_POSITIONS, style.videoPosition) { v -> update { it.copy(videoPosition = v) } }
        }
        if (mode == "top") IntSlider("Tinggi area video", style.videoHeight, 30..85, { v -> update { it.copy(videoHeight = v) } }, suffix = "%")
        IntSlider("Zoom", style.zoom, 100..200, { v -> update { it.copy(zoom = (v / 5) * 5) } }, suffix = "%")
    }

    if (mode != "crop" && mode != "template") {
        FieldGroup("Bingkai video") {
            IntSlider("Sudut membulat", style.cornerRadius, 0..80, { v -> update { it.copy(cornerRadius = (v / 2) * 2) } })
            IntSlider("Garis tepi", style.borderWidth, 0..24, { v -> update { it.copy(borderWidth = v) } })
            if (style.borderWidth > 0) ColorField("Warna garis tepi", style.borderColor) { v -> update { it.copy(borderColor = v) } }
        }
        FieldGroup("Latar") {
            ChoiceRow(null, FrameStyle.BACKGROUNDS, style.background) { v -> update { it.copy(background = v) } }
            when (style.background) {
                "blur" -> IntSlider("Kekuatan blur", style.blurStrength, 1..30, { v -> update { it.copy(blurStrength = v) } })
                "color" -> ColorField("Warna latar", style.backgroundColor) { v -> update { it.copy(backgroundColor = v) } }
                "gradient" -> {
                    ColorField("Warna atas", style.backgroundColor) { v -> update { it.copy(backgroundColor = v) } }
                    ColorField("Warna bawah", style.backgroundColor2) { v -> update { it.copy(backgroundColor2 = v) } }
                }
                "image" -> ImageField(
                    "Gambar latar", file(style.backgroundPath),
                    "Misalnya desain tim. Dipotong memenuhi layar.",
                    onPick = { onPick("background") }, onRemove = { update { it.copy(backgroundPath = null) } }, busy = busy == "background",
                    error = imageError?.takeIf { it.first == "background" }?.second,
                )
            }
        }
    }

    FieldGroup("Logo") {
        ImageField(
            "Gambar logo", file(style.logoPath), "PNG berlatar transparan paling rapi.",
            onPick = { onPick("logo") }, onRemove = { update { it.copy(logoPath = null) } }, busy = busy == "logo",
            error = imageError?.takeIf { it.first == "logo" }?.second,
        )
        if (style.logoPath != null) {
            AnchorPicker("Posisi logo", style.logoX, style.logoY, hint = "Seret titik untuk posisi bebas.") { x, y -> update { it.copy(logoX = x, logoY = y) } }
            IntSlider("Ukuran logo", style.logoSize, 5..40, { v -> update { it.copy(logoSize = v) } }, suffix = "%")
            IntSlider("Kepekatan logo", style.logoOpacity, 10..100, { v -> update { it.copy(logoOpacity = v) } }, suffix = "%")
        }
    }

    FieldGroup("Watermark teks") {
        Field(style.watermarkText.orEmpty(), { v -> update { it.copy(watermarkText = v.take(80)) } }, placeholder = "Misalnya @kajian.kita", singleLine = true)
        if (!style.watermarkText.isNullOrBlank()) {
            ChoiceRow("Font", Fonts.AVAILABLE.mapValues { it.value.label }, style.watermarkFont) { v -> update { it.copy(watermarkFont = v) } }
            IntSlider("Ukuran", style.watermarkSize, 16..120, { v -> update { it.copy(watermarkSize = v) } })
            ColorField("Warna", style.watermarkColor) { v -> update { it.copy(watermarkColor = v) } }
            IntSlider("Kepekatan", style.watermarkOpacity, 10..100, { v -> update { it.copy(watermarkOpacity = v) } }, suffix = "%")
            AnchorPicker("Posisi watermark", style.watermarkX, style.watermarkY) { x, y -> update { it.copy(watermarkX = x, watermarkY = y) } }
        }
    }

    FieldGroup("Judul dan subtitle") {
        ToggleField("Bilah judul", "Judul klip di bagian atas layar", style.titleBar) { v -> update { it.copy(titleBar = v) } }
        if (style.titleBar) {
            if (mode != "template") ColorField("Warna bilah", style.titleBarColor) { v -> update { it.copy(titleBarColor = v) } }
            ColorField("Warna judul", style.titleTextColor) { v -> update { it.copy(titleTextColor = v) } }
        }
        ToggleField("Posisi subtitle otomatis", "Mengikuti tata letak", style.subtitleY == null) { v -> update { it.copy(subtitleY = if (v) null else 75) } }
        style.subtitleY?.let { y -> IntSlider("Posisi subtitle (dari atas)", y, 5..95, { v -> update { it.copy(subtitleY = v) } }, suffix = "%") }
    }
}
