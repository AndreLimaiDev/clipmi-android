package id.clipmi.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.clipmi.android.AppContainer
import id.clipmi.android.data.CorrectionEntryEntity
import id.clipmi.android.ui.components.EmptyState
import kotlinx.coroutines.launch

/** Kamus koreksi: pasangan kata salah → benar yang dipakai ulang lintas project (sama dengan desktop). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CorrectionsScreen(container: AppContainer, onBack: () -> Unit) {
    val entries by container.database.corrections().observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<CorrectionEntryEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Kamus koreksi") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali") } },
            actions = {
                TextButton(onClick = {
                    scope.launch {
                        val (projects, total) = container.transcripts.applyCorrectionsToAll()
                        notice = if (total > 0) "$total kata dikoreksi di $projects project." else "Semua transkrip sudah sesuai kamus."
                    }
                }) { Text("Terapkan ulang") }
            },
        )
        Box(Modifier.fillMaxSize()) {
            if (entries.isEmpty()) {
                EmptyState("Kamus masih kosong", "Tambahkan kata yang sering salah di subtitle otomatis, misalnya istilah agama dan nama tokoh. Kamus diterapkan otomatis pada transkrip baru.")
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(entries, key = { it.id }) { entry ->
                        Row(
                            Modifier.fillMaxWidth().clickable { editing = entry }.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(entry.wrongText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("→ ${entry.correctText}", style = MaterialTheme.typography.bodyLarge)
                            }
                            Switch(checked = entry.isActive, onCheckedChange = { active ->
                                scope.launch { container.database.corrections().update(entry.copy(isActive = active, updatedAt = System.currentTimeMillis())) }
                            })
                        }
                        HorizontalDivider()
                    }
                }
            }
            FloatingActionButton(onClick = { adding = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
                Icon(Icons.Filled.Add, contentDescription = "Tambah koreksi")
            }
        }
    }

    if (adding || editing != null) {
        CorrectionDialog(
            container,
            editing,
            onDone = { adding = false; editing = null },
        )
    }
    notice?.let {
        AlertDialog(onDismissRequest = { notice = null }, confirmButton = { TextButton(onClick = { notice = null }) { Text("OK") } }, text = { Text(it) })
    }
}

@Composable
private fun CorrectionDialog(container: AppContainer, entry: CorrectionEntryEntity?, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var wrong by remember { mutableStateOf(entry?.wrongText.orEmpty()) }
    var correct by remember { mutableStateOf(entry?.correctText.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (entry == null) "Tambah koreksi" else "Ubah koreksi") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(wrong, { wrong = it; error = null }, label = { Text("Teks salah") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(correct, { correct = it; error = null }, label = { Text("Teks benar") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(
                    error ?: "Cocok per kata utuh, tidak membedakan huruf besar/kecil; tanda baca di kata asli dipertahankan.",
                    color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    error = container.actions.saveCorrection(entry?.id, wrong, correct, entry?.isActive ?: true)
                    if (error == null) onDone()
                }
            }) { Text("Simpan") }
        },
        dismissButton = {
            Row {
                if (entry != null) {
                    TextButton(onClick = { scope.launch { container.database.corrections().delete(entry.id); onDone() } }) {
                        Text("Hapus", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDone) { Text("Batal") }
            }
        },
    )
}
