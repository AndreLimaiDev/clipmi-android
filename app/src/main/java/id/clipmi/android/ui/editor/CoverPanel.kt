package id.clipmi.android.ui.editor

import id.clipmi.android.ui.components.ClipMiRangeSlider
import id.clipmi.android.ui.components.ClipMiSlider
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import id.clipmi.android.ui.components.bleed
import id.clipmi.android.ui.components.ActionChip
import id.clipmi.android.ui.components.Field
import id.clipmi.core.analysis.ClipText
import id.clipmi.core.render.CoverTitleLayout
import id.clipmi.core.render.CoverTitle
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import id.clipmi.android.data.BumperEntity
import id.clipmi.android.domain.MediaLibrary
import id.clipmi.android.ui.components.ChipRow
import id.clipmi.android.ui.components.ClipMiSwitch
import id.clipmi.android.ui.components.FilterPill
import id.clipmi.android.ui.components.GhostButton
import id.clipmi.android.ui.components.LocalNotifier
import id.clipmi.android.ui.components.SecondaryButton
import id.clipmi.android.ui.theme.ClipMi
import id.clipmi.android.ui.theme.ClipMiIcons
import id.clipmi.core.transcript.Timecode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tab Sampul: sampul dari frame video atau gambar galeri (judul bisa ditulis di atasnya, bisa ditempel di awal video),
 * serta intro/outro dari pustaka untuk klip ini.
 */
@Composable
fun CoverPanel(state: EditorState, position: Double) {
    val c = ClipMi.colors
    val scope = rememberCoroutineScope()
    val notifier = LocalNotifier.current
    val s = state.snapshot
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var rendering by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }

    // Pratinjau sampul: dibuat FFmpeg seperti saat render, sesaat setelah pengaturannya berhenti berubah.
    val coverKey = listOf(s.coverTime, s.coverPath, s.coverTitle, s.coverLayout, s.keywords, s.captionStyleId, s.frameStyleId, s.cropPosition, s.cropZoom, s.cropY, s.clip.startTime)
    LaunchedEffect(coverKey, state.captionStyles, state.frameStyles) {
        if (!s.hasCover) {
            preview = null
            return@LaunchedEffect
        }
        delay(400)
        rendering = true
        preview = runCatching {
            val file = state.container.renderer.ensureCover(state.entitySnapshot(), state.project)
            withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap() }
        }.onFailure { notifier.show("Sampul gagal dibuat: ${it.message?.take(100)}") }.getOrNull()
        rendering = false
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            runCatching { state.container.media.importImage(uri, MediaLibrary.ImageKind.Cover, "projects/${state.project.id}/covers") }
                .onSuccess { path -> state.apply { it.copy(coverPath = path, coverTime = null) } }
                .onFailure { notifier.show(it.message ?: "Gambar tidak bisa dipakai.") }
            importing = false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PanelTitle(
            "Sampul",
            when {
                s.coverPath != null -> "Gambar dari galeri"
                s.coverTime != null -> "Frame di ${Timecode.format(s.coverTime - s.clip.startTime)}"
                else -> "Belum ada"
            },
        )
        var viewing by remember { mutableStateOf(false) }
        var saving by remember { mutableStateOf(false) }
        // Folder tujuan unduhan terakhir; dikosongkan begitu sampulnya berubah.
        var savedTo by remember(preview) { mutableStateOf<String?>(null) }
        val download: () -> Unit = {
            saving = true
            scope.launch {
                runCatching { state.container.renderer.downloadCover(state.entitySnapshot(), state.project) }
                    .onSuccess { savedTo = it; if (!viewing) notifier.show("Sampul disimpan di Galeri › $it") }
                    .onFailure { notifier.show(it.message ?: "Sampul tidak bisa disimpan.") }
                saving = false
            }
        }
        if (viewing) preview?.let { CoverViewer(it, saving, savedTo, onDownload = download, onClose = { viewing = false }) }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.width(96.dp).aspectRatio(9f / 16f).clip(RoundedCornerShape(14.dp)).background(EDITOR_CARD)
                    // Ketuk untuk melihat besar, dengan garis potongan tiap platform.
                    .clickable(enabled = preview != null, onClickLabel = "Lihat sampul") { viewing = true },
                contentAlignment = Alignment.Center,
            ) {
                preview?.let { Image(it, contentDescription = "Sampul", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    ?: Icon(ClipMiIcons.Image, contentDescription = null, tint = c.textMuted, modifier = Modifier.size(24.dp))
                if (rendering || importing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = c.accent)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionChip("Frame saat ini", height = 40.dp, icon = ClipMiIcons.Film, modifier = Modifier.fillMaxWidth(), onClick = {
                    val time = Math.round(position.coerceIn(s.clip.startTime, s.clip.endTime) * 1000) / 1000.0
                    state.apply { it.copy(coverTime = time, coverPath = null) }
                })
                ActionChip("Dari galeri", height = 40.dp, icon = ClipMiIcons.Image, modifier = Modifier.fillMaxWidth(), enabled = !importing, onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                })
                if (s.hasCover) {
                    ActionChip(if (saving) "Menyimpan…" else "Unduh sampul", height = 40.dp, icon = ClipMiIcons.Download, modifier = Modifier.fillMaxWidth(), enabled = !saving && !rendering, onClick = download)
                    GhostButton("Tanpa sampul", danger = true, onClick = { state.apply { it.copy(coverTime = null, coverPath = null) } })
                }
            }
        }
        if (s.hasCover) {
            Toggle("Judul di sampul", null, s.coverTitle) { on -> state.apply { it.copy(coverTitle = on) } }
            if (s.coverTitle) CoverTitleSection(state)
            Toggle("Tempel di awal video", "Untuk platform yang memakai frame pertama.", s.coverInVideo) { on ->
                state.apply { it.copy(coverInVideo = on) }
            }
        }

        // Intro & outro dipilih di panel Potong (kartu di awal dan akhir teks klip).
    }
}

@Composable
private fun Toggle(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = ClipMi.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.text)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.textMuted) }
        }
        ClipMiSwitch(checked, onChange)
    }
}

/**
 * Model judul sampul (khusus Android): Klasik (sama dengan desktop) atau model tipografi, teks sampul dengan `*kata*`
 * sebagai sorotan, warna sorotan, posisi, dan ukuran.
 */
@Composable
private fun CoverTitleSection(state: EditorState) {
    val c = ClipMi.colors
    val layout = CoverTitle.parse(state.snapshot.coverLayout)
    fun update(change: (CoverTitleLayout) -> CoverTitleLayout) = state.apply { it.copy(coverLayout = CoverTitle.encode(change(CoverTitle.parse(it.coverLayout)))) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ChipRow(gutter = 16.dp, modifier = Modifier.bleed()) {
            CoverTitle.MODELS.forEach { (key, label) -> FilterPill(label, selected = layout.model == key, height = 34.dp, onClick = { update { it.copy(model = key) } }) }
        }
        if (layout.classic) return@Column

        // Teks sampul: tersimpan sendiri setelah berhenti mengetik (centang hijau sebagai tanda).
        var text by remember(state.entity.id) { mutableStateOf(layout.text ?: ClipText.displayTitle(state.entity.title, state.entity.partNumber)) }
        var saved by remember { mutableStateOf(false) }
        LaunchedEffect(text) {
            val current = CoverTitle.parse(state.snapshot.coverLayout).text ?: ClipText.displayTitle(state.entity.title, state.entity.partNumber)
            if (text == current) return@LaunchedEffect
            delay(800)
            update { it.copy(text = text.takeIf { t -> t.isNotBlank() }) }
            saved = true
            delay(2000)
            saved = false
        }
        Field(
            text, { text = it }, label = "Teks sampul", minLines = 2, maxLines = 4,
            supporting = "Enter = baris baru · *kata* = sorotan",
            trailing = if (saved) ({ Icon(ClipMiIcons.Check, contentDescription = "Tersimpan", tint = c.accent, modifier = Modifier.size(20.dp)) }) else null,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Sorotan", style = MaterialTheme.typography.labelMedium, color = c.textBody, modifier = Modifier.width(64.dp))
            val accent = layout.accent ?: CoverTitle.ACCENTS[layout.model]
            CoverTitle.PALETTE.forEach { hex ->
                val color = Color(android.graphics.Color.parseColor(hex))
                Box(
                    Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(color)
                        .border(2.dp, if (hex.equals(accent, ignoreCase = true)) c.accent else Color.Transparent, RoundedCornerShape(8.dp))
                        .clickable(onClickLabel = "Warna $hex") { update { it.copy(accent = hex) } },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Posisi", style = MaterialTheme.typography.labelMedium, color = c.textBody, modifier = Modifier.width(64.dp))
            listOf("top" to "Atas", "center" to "Tengah", "bottom" to "Bawah").forEach { (key, label) ->
                FilterPill(label, selected = layout.position == key, height = 32.dp, onClick = { update { it.copy(position = if (it.position == key) null else key) } })
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row {
                Text("Ukuran", style = MaterialTheme.typography.labelMedium, color = c.textBody, modifier = Modifier.weight(1f))
                Text("${layout.size}%", style = MaterialTheme.typography.labelMedium, color = c.text)
            }
            CoverSizeSlider(layout.size, onChange = { v -> state.drag { it.copy(coverLayout = CoverTitle.encode(CoverTitle.parse(it.coverLayout).copy(size = v))) } }, onDone = { state.endDrag() })
        }
    }
}

@Composable
private fun CoverSizeSlider(size: Int, onChange: (Int) -> Unit, onDone: () -> Unit) {
    ClipMiSlider(
        value = size.toFloat(),
        onValueChange = { onChange(Math.round(it)) },
        valueRange = CoverTitle.SIZE_MIN.toFloat()..CoverTitle.SIZE_MAX.toFloat(),
        onValueChangeFinished = onDone,
    )
}
