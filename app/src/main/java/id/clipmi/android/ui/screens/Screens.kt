package id.clipmi.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import id.clipmi.android.AppContainer
import id.clipmi.android.domain.TranscriptionProvider
import id.clipmi.android.ui.components.EmptyState
import kotlinx.coroutines.launch

@Composable
fun ScheduleScreen() = EmptyState("Jadwal posting", "Kalender, antrean, dan pengingat posting (Fase 4).")

@Composable
fun StatsScreen() = EmptyState("Statistik", "Tayangan, suka, dan analisis performa postingan (Fase 4–5).")

@Composable
fun SettingsScreen(container: AppContainer, onOpenCorrections: () -> Unit, onOpenLab: () -> Unit) {
    val scope = rememberCoroutineScope()
    var provider by remember { mutableStateOf(TranscriptionProvider.Groq) }
    var baseUrl by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(false) }
    var auto by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<String?>(null) }
    var ytdlp by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val (p, url, m) = container.settings.rawTranscription()
        provider = p ?: TranscriptionProvider.Groq
        baseUrl = url.orEmpty()
        model = m.orEmpty()
        hasKey = container.settings.hasKey(provider)
        auto = container.settings.autoTranscribe()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Pengaturan", style = MaterialTheme.typography.titleLarge)

        Text("Transkripsi API", style = MaterialTheme.typography.titleMedium)
        Text(
            "Untuk video tanpa subtitle Indonesia. Audio dikirim per 20 menit ke penyedia (Groq Whisper paling murah, ada paket gratis).",
            style = MaterialTheme.typography.bodySmall,
        )
        TranscriptionProvider.entries.forEach { option ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = provider == option, onClick = {
                    provider = option
                    hasKey = container.settings.hasKey(option)
                    saved = null
                }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = provider == option, onClick = null)
                Text(option.label, Modifier.padding(start = 8.dp))
            }
        }
        OutlinedTextField(
            value = baseUrl, onValueChange = { baseUrl = it; saved = null },
            label = { Text("Alamat API") }, placeholder = { Text(provider.baseUrl ?: "https://…/v1") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = model, onValueChange = { model = it; saved = null },
            label = { Text("Model") }, placeholder = { Text(provider.defaultModel ?: "nama model Whisper") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = apiKey, onValueChange = { apiKey = it; saved = null },
            label = { Text("API key") },
            placeholder = { Text(if (hasKey) "Tersimpan (isi untuk mengganti)" else provider.keyPlaceholder) },
            supportingText = { Text("Disimpan terenkripsi di Android Keystore; tidak ikut cadangan.") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Transkripsi otomatis untuk video tanpa subtitle", Modifier.weight(1f))
            Switch(checked = auto, onCheckedChange = { auto = it; saved = null })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                scope.launch {
                    container.settings.saveTranscription(provider, baseUrl, model, apiKey)
                    container.settings.setAutoTranscribe(auto)
                    apiKey = ""
                    hasKey = container.settings.hasKey(provider)
                    saved = if (container.settings.transcription() != null) "Tersimpan. Transkripsi API siap dipakai." else "Tersimpan, tetapi belum lengkap (alamat, model, atau API key)."
                }
            }) { Text("Simpan") }
            if (hasKey) TextButton(onClick = { container.settings.removeKey(provider); hasKey = false }) { Text("Hapus API key") }
        }
        saved?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        HorizontalDivider()
        Text("Transkrip", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = onOpenCorrections, modifier = Modifier.fillMaxWidth()) { Text("Kamus koreksi") }

        HorizontalDivider()
        Text("Pengunduh YouTube (yt-dlp)", style = MaterialTheme.typography.titleMedium)
        Text(ytdlp ?: "YouTube sering berubah; perbarui yt-dlp bila unduhan gagal.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = {
            ytdlp = "Memperbarui…"
            scope.launch {
                ytdlp = runCatching {
                    val status = container.ytDlp.update()
                    "yt-dlp ${container.ytDlp.version()} (${if (status == "ALREADY_UP_TO_DATE") "sudah terbaru" else "diperbarui"})"
                }.getOrElse { "Gagal memperbarui: ${it.message}" }
            }
        }) { Text("Perbarui yt-dlp") }

        HorizontalDivider()
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onOpenLab) { Text("Uji Fase 0 (teknis)") }
    }
}
