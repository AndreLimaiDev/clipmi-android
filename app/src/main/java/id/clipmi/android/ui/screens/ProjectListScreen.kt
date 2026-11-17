package id.clipmi.android.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.clipmi.android.AppContainer
import id.clipmi.android.data.ProjectEntity
import id.clipmi.android.data.VideoStatus
import id.clipmi.android.ui.components.EmptyState
import id.clipmi.android.ui.components.FileImage
import id.clipmi.android.ui.components.StatusChip
import id.clipmi.android.ui.components.formatBytes
import id.clipmi.android.ui.components.tone
import id.clipmi.core.transcript.Timecode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ProjectListScreen(container: AppContainer, onOpen: (Long) -> Unit, message: String?, onMessageShown: () -> Unit) {
    val projects by container.database.projects().observeAll().collectAsState(initial = null)
    var creating by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        when {
            projects == null -> Unit
            projects!!.isEmpty() -> EmptyState(
                "Belum ada project",
                "Ketuk \"Project baru\" untuk menempel link YouTube atau memilih video dari galeri. Dari aplikasi YouTube, " +
                    "bagikan video ke ClipMI.",
            )
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(projects!!, key = { it.id }) { ProjectCard(container, it, onClick = { onOpen(it.id) }) }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { creating = true },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("Project baru") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    if (creating) NewProjectDialog(container, onDismiss = { creating = false }, onCreated = { id -> creating = false; id?.let(onOpen) })
    if (message != null) {
        AlertDialog(onDismissRequest = onMessageShown, confirmButton = { TextButton(onClick = onMessageShown) { Text("OK") } }, text = { Text(message) })
    }
}

@Composable
private fun ProjectCard(container: AppContainer, project: ProjectEntity, onClick: () -> Unit) {
    val size by produceState(0L, project.updatedAt) { value = withContext(Dispatchers.IO) { container.workspace.projectSize(project.id) } }
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            FileImage(container.workspace.file(project.thumbnailPath), Modifier.width(120.dp).aspectRatio(16f / 9f))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(project.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        project.durationSeconds?.let { Timecode.format(it) },
                        if (project.isYouTube) "YouTube" else "Galeri",
                        size.takeIf { it > 0 }?.let(::formatBytes),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusChip("Video: ${project.video.label}", project.video.tone())
                    StatusChip("Transkrip: ${project.transcript.label}", project.transcript.tone())
                }
                if (project.video == VideoStatus.Downloading && project.videoProgress != null) {
                    LinearProgressIndicator(progress = { project.videoProgress / 100f }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** Project baru: tempel satu atau beberapa link YouTube, atau pilih video dari galeri/file manager. */
@Composable
private fun NewProjectDialog(container: AppContainer, onDismiss: () -> Unit, onCreated: (Long?) -> Unit) {
    val scope = rememberCoroutineScope()
    var links by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { onCreated(container.actions.createFromLocal(uri)) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Project baru") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = links,
                    onValueChange = { links = it; error = null },
                    label = { Text("Link YouTube") },
                    supportingText = { Text(error ?: "Satu atau beberapa link (maks. 30). Hanya konten yang sudah diizinkan pemiliknya.") },
                    isError = error != null,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = { pickVideo.launch(arrayOf("video/mp4", "video/quicktime", "video/x-matroska", "video/*")) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Pilih video dari galeri") }
                Spacer(Modifier.height(0.dp))
            }
        },
        confirmButton = {
            TextButton(
                enabled = links.isNotBlank() && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        val result = container.actions.createFromYouTube(links)
                        busy = false
                        when {
                            result.created.isEmpty() -> error = result.skipped.joinToString("; ").ifEmpty { "Tidak ada link video YouTube yang dikenali." }
                            result.created.size == 1 -> onCreated(result.created[0])
                            else -> onCreated(null)
                        }
                    }
                },
            ) { Text("Buat") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } },
    )
}
