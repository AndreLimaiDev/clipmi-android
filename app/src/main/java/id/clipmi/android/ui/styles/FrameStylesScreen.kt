package id.clipmi.android.ui.styles

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.clipmi.android.AppContainer
import id.clipmi.android.data.FrameStyleEntity
import id.clipmi.android.domain.MediaLibrary
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
import id.clipmi.core.render.FrameStyle
import id.clipmi.core.render.TemplateArea
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/** Halaman Gaya Bingkai: rasio, tata letak video, latar, logo, watermark, dan bilah judul. */
@Composable
fun FrameStylesScreen(container: AppContainer, onBack: () -> Unit, onEdit: (id: Long, copyOf: Long) -> Unit) {
    val styles by container.database.styles().observeFrameStyles().collectAsState(initial = null)

    Box(Modifier.fillMaxSize().background(ClipMi.colors.bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            SubScreenHeader("Gaya bingkai", onBack)
            val list = styles
            if (list != null && list.isEmpty()) EmptyState("Belum ada gaya", "Buat gaya bingkai pertama.", icon = ClipMiIcons.Image)
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = ScreenGutter, end = ScreenGutter, top = 4.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        "Tata letak layar hasil. Gaya bawaan dipakai untuk klip yang belum memilih bingkai.",
                        style = MaterialTheme.typography.bodySmall, color = ClipMi.colors.textMuted,
                    )
                }
                items(list.orEmpty(), key = { it.id }) { style ->
                    val usage by produceState(0, style) { value = container.database.styles().frameUsage(style.id, style.isDefault) }
                    ClipMiCard(onClick = { onEdit(style.id, 0) }) {
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
        FloatingPill("Gaya baru", ClipMiIcons.Plus, onClick = { onEdit(0, 0) }, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp))
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

/**
 * Ubah atau buat gaya bingkai (`FrameStyleForm` desktop). Gambar (logo, latar, template) langsung disalin ke workspace
 * saat dipilih; gambar yang tidak jadi disimpan dihapus lagi saat halaman ditutup.
 */
@Composable
fun FrameStyleEditScreen(container: AppContainer, id: Long, copyOf: Long, onBack: () -> Unit, onDuplicate: (Long) -> Unit) {
    val scope = rememberCoroutineScope()
    val notifier = LocalNotifier.current
    val dao = container.database.styles()
    val media = container.media
    var draft by remember { mutableStateOf<FrameStyleEntity?>(null) }
    var original by remember { mutableStateOf<FrameStyleEntity?>(null) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var imageError by remember { mutableStateOf<Pair<String, String>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    // Gambar yang disalin selama halaman ini terbuka; yang tidak tersimpan dihapus saat keluar.
    val created = remember { mutableSetOf<String>() }
    var saved by remember { mutableStateOf<FrameStyleEntity?>(null) }
    val sample = rememberSampleFrame(container)
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

    val pick = rememberFrameImagePicker { field, uri ->
        scope.launch {
            busy = field
            imageError = null
            try {
                draft = draft?.let { importFrameImage(media, field, uri, it) { path -> created += path } }
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
        val result = style.copy(name = name, watermarkText = style.watermarkText?.trim()?.replace(Regex("\\s+"), " ")?.ifEmpty { null }, updatedAt = System.currentTimeMillis())
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
    val mode = style.mode

    Column(Modifier.fillMaxSize().background(ClipMi.colors.bg).statusBarsPadding()) {
        SubScreenHeader(if (id == 0L) "Gaya bingkai baru" else "Ubah gaya bingkai", leave) {
            PrimaryButton("Simpan", onClick = { save() }, enabled = (dirty || style.id == 0L) && busy == null, height = 40.dp)
        }
        // Pratinjau tetap di atas; hanya isian di bawahnya yang bergulir.
        StickyPreview(expandedWidth = if (core.canvasWidth() > core.canvasHeight()) 300.dp else 190.dp, compactWidth = if (core.canvasWidth() > core.canvasHeight()) 200.dp else 112.dp) { width ->
            FrameStylePreview(container, core, caption, sample, Modifier.width(width))
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ScreenGutter).navigationBarsPadding().padding(top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Field(style.name, { v -> update { it.copy(name = v) }; nameError = null }, label = "Nama gaya", singleLine = true, error = nameError)

            FrameStyleFields(container, style, ::update, busy, imageError, pick)

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

/** Sembilan titik jangkar (kiri atas … kanan bawah) untuk logo dan watermark. */
@Composable
private fun AnchorPicker(label: String, x: Int, y: Int, onSelect: (Int, Int) -> Unit) {
    val c = ClipMi.colors
    val current = FrameStyle.ANCHORS.values.firstOrNull { it.second == x && it.third == y }?.first ?: "Diatur sendiri"
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel(label, current)
        Column(
            Modifier.size(132.dp, 132.dp * 16 / 9 / 2).clip(RoundedCornerShape(12.dp)).background(c.panel).border(1.dp, c.line, RoundedCornerShape(12.dp)).padding(6.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            FrameStyle.ANCHORS.values.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    row.forEach { (name, ax, ay) ->
                        val active = ax == x && ay == y
                        Box(
                            Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).clickable(role = Role.RadioButton, onClickLabel = name) { onSelect(ax, ay) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.size(if (active) 14.dp else 8.dp).clip(RoundedCornerShape(50)).background(if (active) c.accent else c.textMuted))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Isian gaya bingkai (dipakai halaman Gaya bingkai dan sheet edit gaya di editor). [onPick] membuka pemilih gambar
 * untuk "logo", "background", atau "template"; [busy] dan [imageError] menandai gambar yang sedang/gagal diproses.
 */
@Composable
fun FrameStyleFields(
    container: AppContainer,
    style: FrameStyleEntity,
    update: ((FrameStyleEntity) -> FrameStyleEntity) -> Unit,
    busy: String?,
    imageError: Pair<String, String>?,
    onPick: (String) -> Unit,
) {
    val mode = style.mode
    FieldGroup("Tata letak") {
        ChoiceRow("Video", FrameStyle.MODES, mode) { v ->
            // "Video + area teks" paling sering dipakai dengan video di atas.
            update { it.copy(mode = v, videoPosition = if (v == "top" && it.videoPosition == "center") "top" else it.videoPosition) }
        }
        if (mode == "template") {
            ImageField(
                "Gambar template", container.workspace.file(style.templatePath),
                "PNG atau WebP selayar penuh dengan bagian video dikosongkan (transparan). Rasio mengikuti ukuran gambar.",
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
                    "Gambar latar", container.workspace.file(style.backgroundPath),
                    "Misalnya desain tim. Dipotong memenuhi layar. Maksimal 5 MB.",
                    onPick = { onPick("background") }, onRemove = { update { it.copy(backgroundPath = null) } }, busy = busy == "background",
                    error = imageError?.takeIf { it.first == "background" }?.second,
                )
            }
        }
    }

    FieldGroup("Logo") {
        ImageField(
            "Gambar logo", container.workspace.file(style.logoPath), "PNG berlatar transparan paling rapi. Maksimal 2 MB.",
            onPick = { onPick("logo") }, onRemove = { update { it.copy(logoPath = null) } }, busy = busy == "logo",
            error = imageError?.takeIf { it.first == "logo" }?.second,
        )
        if (style.logoPath != null) {
            AnchorPicker("Posisi logo", style.logoX, style.logoY) { x, y -> update { it.copy(logoX = x, logoY = y) } }
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

/** Pemilih gambar galeri untuk gambar gaya bingkai; [onPicked] menerima jenis gambar dan URI-nya. */
@Composable
fun rememberFrameImagePicker(onPicked: (String, android.net.Uri) -> Unit): (String) -> Unit {
    var field by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val target = field
        field = null
        if (uri != null && target != null) onPicked(target, uri)
    }
    return { target ->
        field = target
        launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
}

/**
 * Menyalin gambar pilihan ke workspace dan memasangnya di [draft] (template sekaligus menentukan rasio dan area video).
 * [created] menerima path salinan, supaya yang tidak jadi disimpan bisa dihapus.
 */
suspend fun importFrameImage(media: MediaLibrary, field: String, uri: android.net.Uri, draft: FrameStyleEntity, created: (String) -> Unit): FrameStyleEntity =
    when (field) {
        "logo" -> media.importImage(uri, MediaLibrary.ImageKind.Logo).also(created).let { draft.copy(logoPath = it) }
        "background" -> media.importImage(uri, MediaLibrary.ImageKind.Background).also(created).let { draft.copy(backgroundPath = it) }
        else -> media.importTemplate(uri).also { created(it.path) }.let { t ->
            draft.copy(templatePath = t.path, aspect = t.aspect, templateArea = Json.encodeToString(TemplateArea.serializer(), t.area))
        }
    }
