package id.clipmi.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.clipmi.android.data.BumperEntity
import id.clipmi.android.ui.components.ClipMiCard
import id.clipmi.android.ui.components.ClipMiSheet
import id.clipmi.android.ui.components.ConfirmDialog
import id.clipmi.android.ui.components.EmptyState
import id.clipmi.android.ui.components.GhostButton
import id.clipmi.android.ui.components.IconCircle
import id.clipmi.android.ui.components.LocalNotifier
import id.clipmi.android.ui.components.ScreenGutter
import id.clipmi.android.ui.components.SegmentedTabs
import id.clipmi.android.ui.components.SubScreenHeader
import id.clipmi.android.ui.theme.ClipMi
import id.clipmi.android.ui.theme.ClipMiIcons
import id.clipmi.core.php.Php
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.suspendCancellableCoroutine

class BumperRejected(message: String) : Exception(message)

/**
 * Pustaka intro/outro di iPhone (padanan MediaLibrary.importBumper Android): video dari Photos disalin ke
 * Documents/bumpers (folder dan tabel yang sama dengan Android, jadi ikut cadangan dan pulihan), paling panjang
 * [BumperEntity.MAX_SECONDS] detik, dengan gambar kecil dari frame di sekitar detik pertama.
 */
class IosBumpers(private val repo: Repository, private val renderer: IosRenderer) {
    private val dao get() = repo.db.bumpers()

    /** Memilih video dari Photos; null = batal. */
    suspend fun pick(): String? = suspendCancellableCoroutine { cont ->
        createDirectory(repo.absolute("tmp"))
        renderer.engine.pickVideo(repo.absolute("tmp/bumper-pick.mov"), object : PickListener {
            override fun finished(path: String?) {
                if (cont.isActive) cont.resume(path)
            }
        })
    }

    suspend fun import(kind: String, picked: String): BumperEntity {
        val label = BumperEntity.KINDS.getValue(kind)
        try {
            val info = renderer.probe(picked)
            val duration = info?.duration
            if (info == null || duration == null || duration <= 0) throw BumperRejected("Video ini tidak bisa dibaca. Pakai MP4 (H.264) atau MOV.")
            if (duration > BumperEntity.MAX_SECONDS) {
                throw BumperRejected("$label maksimal ${BumperEntity.MAX_SECONDS} detik; video ini ${duration.roundToInt()} detik.")
            }
            createDirectory(repo.absolute("bumpers"))
            val relative = "bumpers/$kind-${random(10)}.mov"
            moveItem(picked, repo.absolute(relative))
            val count = dao.all().count { it.kind == kind }
            val id = dao.insert(BumperEntity(kind = kind, name = "$label ${count + 1}", path = relative, duration = Php.round(duration, 3)))
            val thumbnail = "bumpers/$kind-$id-thumb.jpg"
            val ok = renderer.videoFrame(repo.absolute(relative), minOf(1.0, duration / 2), repo.absolute(thumbnail), 320)
            val bumper = dao.find(id)!!.copy(thumbnailPath = thumbnail.takeIf { ok })
            dao.update(bumper)
            return bumper
        } finally {
            removeItem(picked)
        }
    }

    /** Menghapus dari pustaka beserta filenya; klip dan project yang memakainya kembali tanpa intro/outro. */
    suspend fun remove(bumper: BumperEntity) {
        removeItem(repo.absolute(bumper.path))
        bumper.thumbnailPath?.let { removeItem(repo.absolute(it)) }
        dao.delete(bumper)
    }

    private fun random(length: Int): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length).map { chars.random() }.joinToString("")
    }
}

/**
 * Pustaka intro (pembuka) dan outro (penutup), padanan BumpersScreen Android: dipilih per klip di editor (tab Potong)
 * atau sebagai bawaan project, lalu disambung saat render.
 */
@Composable
fun BumpersScreen(repo: Repository, bumpers: IosBumpers, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val notifier = LocalNotifier.current
    val all by remember { repo.db.bumpers().observeAll() }.collectAsState(initial = null)
    var tab by remember { mutableStateOf(0) }
    val kind = if (tab == 0) "intro" else "outro"
    val label = BumperEntity.KINDS.getValue(kind)
    var importing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<BumperEntity?>(null) }
    var playing by remember { mutableStateOf<BumperEntity?>(null) }

    Column(Modifier.fillMaxSize().background(ClipMi.colors.bg)) {
        // Tambah di kanan atas (bukan tombol melayang yang menutupi daftar).
        SubScreenHeader("Intro & outro", onBack) {
            if (importing) CircularProgressIndicator(Modifier.size(24.dp), color = ClipMi.colors.accent, strokeWidth = 2.dp)
            else IconCircle(ClipMiIcons.Plus, "Tambah ${label.lowercase()}", {
                scope.launch {
                    val picked = bumpers.pick() ?: return@launch
                    importing = true
                    runCatching { bumpers.import(kind, picked) }
                        .onSuccess { notifier.show("$label \"${it.name}\" ditambahkan.") }
                        .onFailure { notifier.show(it.message ?: "Video tidak bisa dipakai.") }
                    importing = false
                }
            })
        }
        SegmentedTabs(listOf("Intro", "Outro"), tab, { tab = it }, Modifier.padding(horizontal = ScreenGutter, vertical = 4.dp))
        val list = all.orEmpty().filter { it.kind == kind }
        if (all != null && list.isEmpty()) {
            EmptyState(
                "Belum ada ${label.lowercase()}",
                "Video ${if (kind == "intro") "pembuka" else "penutup"} pendek dari Foto, maksimal ${BumperEntity.MAX_SECONDS} detik. Ketuk + untuk menambah.",
                icon = ClipMiIcons.Film,
            )
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = ScreenGutter, end = ScreenGutter, top = 12.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(list, key = { it.id }) { bumper ->
                // Ketuk kartu untuk memutar videonya.
                ClipMiCard(onClick = { playing = bumper }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        FileThumbnail(bumper.thumbnailPath?.let { repo.absolute(it) }, Modifier.width(96.dp).height(56.dp).clip(RoundedCornerShape(12.dp)))
                        Column(Modifier.weight(1f)) {
                            Text(bumper.name, style = MaterialTheme.typography.titleMedium, color = ClipMi.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${Php.numberFormat(bumper.duration, 1).replace('.', ',')} detik", style = MaterialTheme.typography.bodySmall, color = ClipMi.colors.textMuted)
                        }
                        IconCircle(ClipMiIcons.Trash, "Hapus", { deleting = bumper }, size = 40.dp, background = Color.Transparent, border = false, tint = ClipMi.colors.bad)
                    }
                }
            }
        }
    }

    playing?.let { bumper ->
        ClipMiSheet({ playing = null }, title = bumper.name) {
            Box(Modifier.height(420.dp).aspectRatio(9f / 16f).align(Alignment.CenterHorizontally).clip(RoundedCornerShape(16.dp)).background(Color.Black)) {
                SourceVideo(repo.absolute(bumper.path), Modifier.fillMaxSize())
            }
            GhostButton("Tutup", onClick = { playing = null }, modifier = Modifier.fillMaxWidth())
        }
    }
    deleting?.let { bumper ->
        ConfirmDialog(
            title = "Hapus \"${bumper.name}\"?",
            body = "Klip dan project yang memakainya kembali tanpa ${BumperEntity.KINDS.getValue(bumper.kind).lowercase()}. Hasil render yang sudah jadi tidak berubah.",
            confirmLabel = "Hapus",
            danger = true,
            onConfirm = {
                deleting = null
                scope.launch { bumpers.remove(bumper); notifier.show("Dihapus dari pustaka.") }
            },
            onDismiss = { deleting = null },
        )
    }
}
