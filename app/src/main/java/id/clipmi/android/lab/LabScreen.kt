package id.clipmi.android.lab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Layar Uji Fase 0: memicu uji coba teknis dan menampilkan log-nya. */
@Composable
fun LabScreen() {
    val context = LocalContext.current
    val log = remember { LabLog(context) }
    var url by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            text = log.tail(120)
            delay(1000)
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Uji Fase 0", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(url, { url = it }, label = { Text("Link YouTube (berizin)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = url.isNotBlank(), onClick = { LabTests.enqueue(context, "ytdlp", mapOf("url" to url.trim())) }) { Text("yt-dlp") }
            OutlinedButton(onClick = { LabTests.enqueue(context, "ytdlp_update") }) { Text("Perbarui yt-dlp") }
            OutlinedButton(onClick = { LabTests.enqueue(context, "ass") }) { Text("libass") }
            OutlinedButton(onClick = { LabTests.enqueue(context, "render_media3", mapOf("mode" to "blur")) }) { Text("Media3") }
            OutlinedButton(onClick = { LabTests.enqueue(context, "render_ffmpeg", mapOf("mode" to "blur")) }) { Text("FFmpeg") }
            OutlinedButton(onClick = { LabTests.enqueue(context, "device") }) { Text("Info HP") }
            OutlinedButton(onClick = { log.clear() }) { Text("Hapus log") }
        }
        Text(
            text,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        )
    }
}
