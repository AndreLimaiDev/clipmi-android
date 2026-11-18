package id.clipmi.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.unit.dp
import id.clipmi.android.AppContainer
import id.clipmi.android.data.CorrectionEntryEntity
import id.clipmi.android.ui.components.ClipMiSheet
import id.clipmi.android.ui.components.ClipMiSwitch
import id.clipmi.android.ui.components.ConfirmDialog
import id.clipmi.android.ui.components.EmptyState
import id.clipmi.android.ui.components.Field
import id.clipmi.android.ui.components.FloatingPill
import id.clipmi.android.ui.components.GhostButton
import id.clipmi.android.ui.components.GroupDivider
import id.clipmi.android.ui.components.LocalNotifier
import id.clipmi.android.ui.components.PrimaryButton
import id.clipmi.android.ui.components.ScreenGutter
import id.clipmi.android.ui.components.SecondaryButton
import id.clipmi.android.ui.components.SnackbarInset
import id.clipmi.android.ui.components.SubScreenHeader
import id.clipmi.android.ui.theme.ClipMi
import id.clipmi.android.ui.theme.ClipMiIcons
import kotlinx.coroutines.launch

/** Kamus koreksi: pasangan kata salah → benar yang dipakai ulang lintas project (sama dengan desktop). */
@Composable
fun CorrectionsScreen(container: AppContainer, onBack: () -> Unit) {
    val entries by container.database.corrections().observeAll().collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val notifier = LocalNotifier.current
    var editing by remember { mutableStateOf<CorrectionEntryEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    val c = ClipMi.colors
    SnackbarInset(72.dp)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            SubScreenHeader("Kamus koreksi", onBack) {
                if (!entries.isNullOrEmpty()) {
                    GhostButton("Terapkan ulang", onClick = {
                        scope.launch {
                            val (projects, total) = container.transcripts.applyCorrectionsToAll()
                            notifier.show(if (total > 0) "$total kata dikoreksi di $projects project." else "Semua transkrip sudah sesuai kamus.")
                        }
                    })
                }
            }
            val list = entries ?: return@Column
            if (list.isEmpty()) {
                EmptyState(
                    "Kamus masih kosong",
                    "Tambahkan kata yang sering salah di subtitle otomatis, misalnya istilah agama dan nama tokoh. Kamus diterapkan otomatis pada transkrip baru.",
                    icon = ClipMiIcons.Book,
                )
                return@Column
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = ScreenGutter, end = ScreenGutter, top = 8.dp, bottom = 120.dp)) {
                item {
                    Text(
                        "Cocok per kata utuh, tanpa membedakan huruf besar/kecil. Ketuk untuk mengubah.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textMuted,
                        modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
                    )
                }
                itemsIndexed(list, key = { _, it -> it.id }) { i, entry ->
                    val top = if (i == 0) 24.dp else 0.dp
                    val bottom = if (i == list.lastIndex) 24.dp else 0.dp
                    val shape = RoundedCornerShape(top, top, bottom, bottom)
                    Column(Modifier.clip(shape).background(c.card)) {
                        Row(
                            Modifier.fillMaxWidth().clickable { editing = entry }.heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(entry.wrongText, style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                                Text("→ ${entry.correctText}", style = MaterialTheme.typography.titleMedium, color = if (entry.isActive) c.text else c.textMuted)
                            }
                            ClipMiSwitch(entry.isActive, onCheckedChange = { active ->
                                scope.launch { container.database.corrections().update(entry.copy(isActive = active, updatedAt = System.currentTimeMillis())) }
                            })
                        }
                        if (i < list.lastIndex) GroupDivider()
                    }
                }
            }
        }
        FloatingPill(
            "Tambah", ClipMiIcons.Plus, onClick = { adding = true },
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = ScreenGutter, bottom = 24.dp),
        )
    }

    if (adding || editing != null) {
        CorrectionSheet(container, editing, onDone = { adding = false; editing = null })
    }
}

@Composable
private fun CorrectionSheet(container: AppContainer, entry: CorrectionEntryEntity?, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var wrong by remember { mutableStateOf(entry?.wrongText.orEmpty()) }
    var correct by remember { mutableStateOf(entry?.correctText.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    ClipMiSheet(
        onDone,
        title = if (entry == null) "Tambah koreksi" else "Ubah koreksi",
        subtitle = "Cocok per kata utuh, tidak membedakan huruf besar/kecil; tanda baca di kata asli dipertahankan.",
    ) {
        Field(wrong, { wrong = it; error = null }, label = "Teks salah", placeholder = "misal: alloh", singleLine = true)
        Field(correct, { correct = it; error = null }, label = "Teks benar", placeholder = "misal: Allah", singleLine = true, error = error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (entry != null) {
                SecondaryButton("Hapus", onClick = { confirmDelete = true }, contentColor = ClipMi.colors.bad, modifier = Modifier.weight(1f))
            }
            PrimaryButton("Simpan", modifier = Modifier.weight(1f), onClick = {
                scope.launch {
                    error = container.actions.saveCorrection(entry?.id, wrong, correct, entry?.isActive ?: true)
                    if (error == null) onDone()
                }
            })
        }
    }

    if (confirmDelete && entry != null) {
        ConfirmDialog(
            "Hapus koreksi?", "\"${entry.wrongText} → ${entry.correctText}\" dihapus dari kamus. Transkrip yang sudah dikoreksi tidak berubah.", "Hapus", danger = true,
            onConfirm = { confirmDelete = false; scope.launch { container.database.corrections().delete(entry.id); onDone() } },
            onDismiss = { confirmDelete = false },
        )
    }
}
