package id.clipmi.android.ui.editor

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
    val coverKey = listOf(s.coverTime, s.coverPath, s.coverTitle, s.captionStyleId, s.frameStyleId, s.cropPosition, s.cropZoom, s.cropY, s.clip.startTime)
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
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.width(96.dp).aspectRatio(9f / 16f).clip(RoundedCornerShape(14.dp)).background(EDITOR_CARD),
                contentAlignment = Alignment.Center,
            ) {
                preview?.let { Image(it, contentDescription = "Sampul", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    ?: Icon(ClipMiIcons.Image, contentDescription = null, tint = c.textMuted, modifier = Modifier.size(24.dp))
                if (rendering || importing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = c.accent)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Frame saat ini", icon = ClipMiIcons.Film, height = 40.dp, modifier = Modifier.fillMaxWidth(), onClick = {
                    val time = Math.round(position.coerceIn(s.clip.startTime, s.clip.endTime) * 1000) / 1000.0
                    state.apply { it.copy(coverTime = time, coverPath = null) }
                })
                SecondaryButton("Dari galeri", icon = ClipMiIcons.Image, height = 40.dp, modifier = Modifier.fillMaxWidth(), enabled = !importing, onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                })
                if (s.hasCover) GhostButton("Tanpa sampul", danger = true, onClick = { state.apply { it.copy(coverTime = null, coverPath = null) } })
            }
        }
        Text("Putar atau geser video ke momen yang pas, lalu ketuk Frame saat ini.", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        if (s.hasCover) {
            Toggle("Judul di sampul", "Judul klip ditulis besar di atas sampul.", s.coverTitle) { on -> state.apply { it.copy(coverTitle = on) } }
            Toggle("Tempel di awal video", "0,1 detik pertama, supaya jadi cover di platform yang memakai frame pertama.", s.coverInVideo) { on ->
                state.apply { it.copy(coverInVideo = on) }
            }
        }

        PanelTitle("Intro & outro")
        BumperChoice("Intro", state.bumpers.filter { it.kind == "intro" }, s.introId) { id -> state.apply { it.copy(introId = id) } }
        if (s.introId != null && state.spec().leadKind() == "intro" && state.hookLayout().opener) {
            Text("Dengan intro, kartu pembuka hook tidak dipakai; hook tampil di atas intro.", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        }
        BumperChoice("Outro", state.bumpers.filter { it.kind == "outro" }, s.outroId) { id -> state.apply { it.copy(outroId = id) } }
        if (state.bumpers.isEmpty()) {
            Text("Belum ada video di pustaka. Tambahkan di Pengaturan › Intro & outro.", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        } else {
            val isProjectDefault = state.project.introId == s.introId && state.project.outroId == s.outroId
            GhostButton(
                if (isProjectDefault) "Sudah jadi bawaan project" else "Jadikan bawaan project ini",
                enabled = !isProjectDefault,
                onClick = {
                    scope.launch {
                        state.container.database.projects().setBumpers(state.project.id, s.introId, s.outroId)
                        state.project = state.project.copy(introId = s.introId, outroId = s.outroId)
                        notifier.show("Klip baru di project ini memakai intro/outro yang sama.")
                    }
                },
            )
        }
    }
}

@Composable
private fun BumperChoice(label: String, options: List<BumperEntity>, selected: Long?, onSelect: (Long?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = ClipMi.colors.textBody)
        ChipRow(gutter = 0.dp) {
            FilterPill("Tanpa ${label.lowercase()}", selected = selected == null, height = 34.dp, onClick = { onSelect(null) })
            options.forEach { b ->
                FilterPill("${b.name} · ${Math.round(b.duration)} dtk", selected = selected == b.id, height = 34.dp, onClick = { onSelect(b.id) })
            }
        }
    }
}

@Composable
private fun Toggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = ClipMi.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.text)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        }
        ClipMiSwitch(checked, onChange)
    }
}
