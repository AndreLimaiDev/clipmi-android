package id.clipmi.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.clipmi.core.transcript.Timecode
import id.clipmi.core.youtube.YouTubeUrl
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Warna utama ClipMI (sama dengan token Android: emerald). */
private val Accent = Color(0xFF34D399)
private val AccentDark = Color(0xFF047857)

@Composable
fun App(engine: MediaEngine, documents: String) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) {
        darkColorScheme(primary = Accent, onPrimary = Color(0xFF04342C), background = Color(0xFF0B0F0E), surface = Color(0xFF111614))
    } else {
        lightColorScheme(primary = AccentDark, onPrimary = Color.White, background = Color(0xFFF4F6F5), surface = Color.White)
    }
    MaterialTheme(colorScheme = scheme) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
            ProjectScreen(engine, remember(documents) { ProjectStore(documents) })
        }
    }
}

@Composable
private fun ProjectScreen(engine: MediaEngine, store: ProjectStore) {
    var projects by remember { mutableStateOf(store.load().sortedByDescending { it.createdAt }) }
    var link by remember { mutableStateOf("") }
    var linkError by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Pair<Double, String>?>(null) }
    val clipboard = LocalClipboardManager.current

    fun update(project: IosProject) {
        projects = (listOf(project) + projects.filterNot { it.id == project.id }).sortedByDescending { it.createdAt }
        store.save(projects)
    }

    fun start() {
        val url = link.trim()
        val videoId = YouTubeUrl.videoId(url)
        if (videoId == null) {
            linkError = "Bukan link video YouTube."
            return
        }
        linkError = null
        val project = IosProject(id = nowMillis(), title = "Video YouTube $videoId", sourceUrl = url, videoId = videoId, createdAt = nowMillis())
        update(project)
        link = ""
        val dir = store.directory(project.id)
        createDirectory(dir)
        progress = 0.0 to "Menyiapkan…"
        engine.downloadYouTube(url, dir, object : DownloadListener {
            override fun progress(fraction: Double, status: String) {
                progress = fraction to status
            }

            override fun finished(resultJson: String) {
                progress = null
                val result = runCatching { Json.parseToJsonElement(resultJson).jsonObject }.getOrNull()
                val ok = result?.get("ok")?.jsonPrimitive?.booleanOrNull == true
                update(
                    if (ok) {
                        project.copy(
                            title = result!!["title"]?.jsonPrimitive?.contentOrNull ?: project.title,
                            durationSeconds = result["duration"]?.jsonPrimitive?.doubleOrNull,
                            videoMb = result["mb"]?.jsonPrimitive?.doubleOrNull,
                            status = IosProject.STATUS_READY,
                        )
                    } else {
                        project.copy(
                            status = IosProject.STATUS_FAILED,
                            error = result?.get("error")?.jsonPrimitive?.contentOrNull ?: "Unduhan gagal.",
                        )
                    },
                )
            }
        })
    }

    val c = MaterialTheme.colorScheme
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("ClipMI", color = c.primary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("Project", fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, color = c.onBackground)
            }
        }
        item {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Link YouTube", fontWeight = FontWeight.Bold, color = c.onSurface)
                OutlinedTextField(
                    link, { link = it; linkError = null },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://youtu.be/…") },
                    singleLine = true,
                    isError = linkError != null,
                    supportingText = linkError?.let { { Text(it) } },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { clipboard.getText()?.text?.let { link = it; linkError = null } }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                        Text("Tempel")
                    }
                    Button(
                        onClick = { start() },
                        enabled = progress == null && link.isNotBlank(),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = c.primary),
                    ) { Text("Buat project") }
                }
                progress?.let { (fraction, status) ->
                    LinearProgressIndicator(progress = { fraction.toFloat() }, modifier = Modifier.fillMaxWidth().height(6.dp), color = c.primary)
                    Text(status, fontSize = 13.sp, color = c.onSurface.copy(alpha = 0.7f))
                    Text("Biarkan aplikasi tetap terbuka sampai selesai.", fontSize = 12.sp, color = c.onSurface.copy(alpha = 0.55f))
                }
            }
        }
        if (projects.isEmpty()) {
            item {
                Text("Belum ada project. Tempel link video kajian di atas.", color = c.onBackground.copy(alpha = 0.6f), modifier = Modifier.padding(top = 24.dp))
            }
        }
        items(projects, key = { it.id }) { project -> ProjectCard(project) }
    }
}

@Composable
private fun ProjectCard(project: IosProject) {
    val c = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(project.title, fontWeight = FontWeight.Bold, color = c.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val meta = listOfNotNull(
            "YouTube",
            project.durationSeconds?.let { Timecode.format(it) },
            project.videoMb?.let { "${(it * 10).toLong() / 10.0} MB" },
        ).joinToString(" · ")
        Text(meta, fontSize = 13.sp, color = c.onSurface.copy(alpha = 0.6f))
        val (label, color) = when (project.status) {
            IosProject.STATUS_READY -> "Video siap" to c.primary
            IosProject.STATUS_FAILED -> (project.error ?: "Gagal") to Color(0xFFE5484D)
            else -> "Mengunduh…" to c.onSurface.copy(alpha = 0.6f)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 13.sp, color = color, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}
