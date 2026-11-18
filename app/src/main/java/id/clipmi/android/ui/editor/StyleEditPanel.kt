package id.clipmi.android.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.clipmi.android.data.CaptionStyleEntity
import id.clipmi.android.data.FrameStyleEntity
import id.clipmi.android.ui.components.ClipMiSheet
import id.clipmi.android.ui.components.ConfirmDialog
import id.clipmi.android.ui.components.Field
import id.clipmi.android.ui.components.GhostButton
import id.clipmi.android.ui.components.LocalNotifier
import id.clipmi.android.ui.components.PrimaryButton
import id.clipmi.android.ui.components.SecondaryButton
import id.clipmi.android.ui.styles.CaptionStyleFields
import id.clipmi.android.ui.styles.FrameStyleFields
import id.clipmi.android.ui.styles.freeName
import id.clipmi.android.ui.styles.importFrameImage
import id.clipmi.android.ui.styles.rememberFrameImagePicker
import id.clipmi.android.ui.theme.ClipMi
import kotlinx.coroutines.launch

/**
 * Sheet "Edit gaya" di editor (`EditsClipStyles` desktop): gaya subtitle ([kind] "caption") atau gaya bingkai ("frame")
 * yang dipakai klip diedit di panel bawah, dengan pratinjau editor di atas langsung memakai isian yang belum disimpan.
 * Disimpan ke gaya itu (semua klip yang memakainya ikut berubah) atau sebagai gaya baru khusus klip ini.
 */
@Composable
fun StyleEditPanel(state: EditorState, kind: String, onClose: () -> Unit) {
    val c = ClipMi.colors
    val scope = rememberCoroutineScope()
    val notifier = LocalNotifier.current
    val container = state.container
    val dao = container.database.styles()
    val media = container.media
    val caption = kind == "caption"
    val originalCaption = remember { state.captionEntity() }
    val originalFrame = remember { state.frameEntity() }
    // Gambar bingkai yang disalin selama sheet ini terbuka; yang tidak jadi disimpan dihapus lagi.
    val created = remember { mutableSetOf<String>() }
    var kept by remember { mutableStateOf<Set<String>>(emptySet()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var imageError by remember { mutableStateOf<Pair<String, String>?>(null) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var choosing by remember { mutableStateOf(false) }
    var usage by remember { mutableStateOf(0) }
    var discard by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (caption) state.editCaption(originalCaption) else state.editFrame(originalFrame)
    }
    DisposableEffect(Unit) {
        onDispose {
            state.editCaption(null)
            state.editFrame(null)
            created.filter { it !in kept }.forEach(media::delete)
        }
    }

    val captionDraft = state.captionDraft
    val frameDraft = state.frameDraft
    val name = if (caption) captionDraft?.name else frameDraft?.name
    val dirty = if (caption) captionDraft != originalCaption else frameDraft != originalFrame
    BackHandler { if (dirty) discard = true else onClose() }

    val pick = rememberFrameImagePicker { field, uri ->
        scope.launch {
            busy = field
            imageError = null
            try {
                state.frameDraft?.let { draft -> state.editFrame(importFrameImage(media, field, uri, draft) { created += it }) }
            } catch (e: Exception) {
                imageError = field to (e.message ?: "Gambar tidak bisa dipakai.")
            } finally {
                busy = null
            }
        }
    }

    /** Nama gaya dirapikan dan diperiksa; null bila tidak sah (pesan di [nameError]). */
    suspend fun validName(raw: String, exceptId: Long): String? {
        val clean = raw.trim().replace(Regex("\\s+"), " ")
        nameError = when {
            clean.isEmpty() -> "Nama gaya wajib diisi."
            clean.length > 60 -> "Nama gaya maksimal 60 karakter."
            (if (caption) dao.captionNameTaken(clean, exceptId) else dao.frameNameTaken(clean, exceptId)) > 0 -> "Nama gaya ini sudah dipakai."
            else -> null
        }
        return clean.takeIf { nameError == null }
    }

    fun saveExisting() = scope.launch {
        val now = System.currentTimeMillis()
        if (caption) {
            val draft = captionDraft ?: return@launch
            val clean = validName(draft.name, draft.id) ?: return@launch
            dao.update(draft.copy(name = clean, updatedAt = now))
        } else {
            val draft = frameDraft ?: return@launch
            val clean = validName(draft.name, draft.id) ?: return@launch
            val result = draft.copy(name = clean, watermarkText = draft.watermarkText?.trim()?.ifEmpty { null }, updatedAt = now)
            dao.update(result)
            // Gambar lama yang diganti atau dihapus ikut dihapus.
            originalFrame?.let { old ->
                listOf(old.logoPath to result.logoPath, old.backgroundPath to result.backgroundPath, old.templatePath to result.templatePath)
                    .filter { (before, after) -> before != null && before != after }
                    .forEach { (before, _) -> media.delete(before) }
            }
            kept = setOfNotNull(result.logoPath, result.backgroundPath, result.templatePath)
        }
        notifier.show("Gaya \"$name\" diperbarui untuk semua klip yang memakainya.")
        onClose()
    }

    fun saveAsNew() = scope.launch {
        val now = System.currentTimeMillis()
        if (caption) {
            val draft = captionDraft ?: return@launch
            // Nama yang belum diganti diberi nomor ("Kajian Putih 2"), sama dengan desktop.
            val base = if (draft.name.trim() == originalCaption?.name) draft.name.trim() else validName(draft.name, 0) ?: return@launch
            val newName = freeName(base) { n -> dao.captionNameTaken(n, 0) > 0 }
            val id = dao.insert(draft.copy(id = 0, name = newName, isDefault = false, createdAt = now, updatedAt = now))
            state.apply { it.copy(captionStyleId = id) }
            notifier.show("Gaya baru \"$newName\" dipakai klip ini.")
        } else {
            val draft = frameDraft ?: return@launch
            val base = if (draft.name.trim() == originalFrame?.name) draft.name.trim() else validName(draft.name, 0) ?: return@launch
            val newName = freeName(base) { n -> dao.frameNameTaken(n, 0) > 0 }
            // Gambar milik gaya lama disalin, supaya menghapus salah satu gaya tidak menghapus gambar yang lain.
            fun own(path: String?) = if (path != null && path !in created) media.copyImage(path) else path
            val result = draft.copy(
                id = 0, name = newName, isDefault = false, createdAt = now, updatedAt = now,
                watermarkText = draft.watermarkText?.trim()?.ifEmpty { null },
                logoPath = own(draft.logoPath), backgroundPath = own(draft.backgroundPath), templatePath = own(draft.templatePath),
            )
            val id = dao.insert(result)
            kept = setOfNotNull(result.logoPath, result.backgroundPath, result.templatePath)
            state.apply { it.copy(frameStyleId = id) }
            notifier.show("Bingkai baru \"$newName\" dipakai klip ini.")
        }
        onClose()
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            GhostButton("Batal", { if (dirty) discard = true else onClose() })
            Text(
                (if (caption) "Gaya subtitle" else "Gaya bingkai") + (name?.let { ": $it" } ?: ""),
                style = MaterialTheme.typography.titleSmall,
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
            PrimaryButton("Simpan", enabled = dirty && busy == null, height = 40.dp, onClick = {
                scope.launch {
                    usage = if (caption) originalCaption?.let { dao.captionUsage(it.id, it.isDefault) } ?: 0
                    else originalFrame?.let { dao.frameUsage(it.id, it.isDefault) } ?: 0
                    choosing = true
                }
            })
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (caption) {
                val draft = captionDraft ?: return@Column
                Field(draft.name, { v -> state.editCaption(draft.copy(name = v)); nameError = null }, label = "Nama gaya", singleLine = true, error = nameError)
                CaptionStyleFields(draft) { change -> state.captionDraft?.let { state.editCaption(change(it)) } }
            } else {
                val draft = frameDraft ?: return@Column
                Field(draft.name, { v -> state.editFrame(draft.copy(name = v)); nameError = null }, label = "Nama gaya", singleLine = true, error = nameError)
                FrameStyleFields(container, draft, { change -> state.frameDraft?.let { state.editFrame(change(it)) } }, busy, imageError, pick)
            }
        }
    }

    if (choosing) {
        ClipMiSheet({ choosing = false }, title = "Simpan perubahan gaya", subtitle = name) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PrimaryButton("Simpan ke gaya ini", { choosing = false; saveExisting() }, modifier = Modifier.fillMaxWidth())
                Text(
                    if (usage > 1) "Berlaku juga untuk ${usage - 1} klip lain yang memakai gaya ini; hasil render lamanya tidak ikut berubah." else "Gaya ini hanya dipakai klip ini.",
                    style = MaterialTheme.typography.bodySmall, color = c.textMuted,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SecondaryButton("Simpan sebagai gaya baru", { choosing = false; saveAsNew() }, modifier = Modifier.fillMaxWidth())
                Text("Gaya lama tidak berubah; gaya baru langsung dipakai klip ini saja.", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
            }
        }
    }
    if (discard) {
        ConfirmDialog(
            title = "Buang perubahan?", body = "Perubahan pada gaya ini belum disimpan.", confirmLabel = "Buang", danger = true,
            onConfirm = { discard = false; onClose() }, onDismiss = { discard = false },
        )
    }
}

