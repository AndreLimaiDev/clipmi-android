package id.clipmi.android.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.clipmi.android.AppContainer
import id.clipmi.android.data.BumperEntity
import id.clipmi.android.ui.components.ClipMiCard
import id.clipmi.android.ui.components.ConfirmDialog
import id.clipmi.android.ui.components.EmptyState
import id.clipmi.android.ui.components.FileImage
import id.clipmi.android.ui.components.FloatingPill
import id.clipmi.android.ui.components.IconCircle
import id.clipmi.android.ui.components.LocalNotifier
import id.clipmi.android.ui.components.ScreenGutter
import id.clipmi.android.ui.components.SegmentedTabs
import id.clipmi.android.ui.components.SubScreenHeader
import id.clipmi.android.ui.theme.ClipMi
import id.clipmi.android.ui.theme.ClipMiIcons
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Pustaka intro (pembuka) dan outro (penutup): video pendek dari galeri HP, dipilih per klip di editor (tab Sampul)
 * atau sebagai bawaan project, lalu disambung saat render.
 */
@Composable
fun BumpersScreen(container: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val notifier = LocalNotifier.current
    val bumpers by container.database.bumpers().observeAll().collectAsState(initial = null)
    var tab by remember { mutableIntStateOf(0) }
    val kind = if (tab == 0) "intro" else "outro"
    val label = BumperEntity.KINDS.getValue(kind)
    var importing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<BumperEntity?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            runCatching { container.media.importBumper(kind, uri) }
                .onSuccess { notifier.show("$label \"${it.name}\" ditambahkan.") }
                .onFailure { notifier.show(it.message ?: "Video tidak bisa dipakai.") }
            importing = false
        }
    }

    Box(Modifier.fillMaxSize().background(ClipMi.colors.bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            SubScreenHeader("Intro & outro", onBack)
            SegmentedTabs(listOf("Intro", "Outro"), tab, { tab = it }, Modifier.padding(horizontal = ScreenGutter, vertical = 4.dp))
            val list = bumpers.orEmpty().filter { it.kind == kind }
            if (bumpers != null && list.isEmpty()) {
                EmptyState(
                    "Belum ada ${label.lowercase()}",
                    "Video ${if (kind == "intro") "pembuka" else "penutup"} pendek (maksimal ${BumperEntity.MAX_SECONDS} detik, MP4/MOV) dari galeri HP. " +
                        "Pilih per klip di editor, tab Sampul.",
                    icon = ClipMiIcons.Film,
                )
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = ScreenGutter, end = ScreenGutter, top = 12.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(list, key = { it.id }) { bumper ->
                    ClipMiCard {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            FileImage(
                                container.workspace.file(bumper.thumbnailPath),
                                Modifier.width(96.dp).height(56.dp).clip(RoundedCornerShape(12.dp)),
                                maxSize = 320, placeholder = ClipMiIcons.Film,
                            )
                            Column(Modifier.weight(1f)) {
                                Text(bumper.name, style = MaterialTheme.typography.titleMedium, color = ClipMi.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(String.format(Locale.forLanguageTag("id"), "%.1f detik", bumper.duration), style = MaterialTheme.typography.bodySmall, color = ClipMi.colors.textMuted)
                            }
                            IconCircle(ClipMiIcons.Trash, "Hapus", { deleting = bumper }, size = 40.dp, background = ClipMi.colors.panel)
                        }
                    }
                }
            }
        }
        FloatingPill(
            if (importing) "Menyalin video…" else "Tambah ${label.lowercase()}", ClipMiIcons.Plus,
            onClick = { if (!importing) picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp),
        )
    }

    deleting?.let { bumper ->
        ConfirmDialog(
            title = "Hapus \"${bumper.name}\"?",
            body = "Klip dan project yang memakainya kembali tanpa ${BumperEntity.KINDS.getValue(bumper.kind).lowercase()}. Hasil render yang sudah jadi tidak berubah.",
            confirmLabel = "Hapus",
            danger = true,
            onConfirm = {
                deleting = null
                scope.launch { container.media.removeBumper(bumper); notifier.show("Dihapus dari pustaka.") }
            },
            onDismiss = { deleting = null },
        )
    }
}
