package id.clipmi.android.ui.screens

import androidx.compose.ui.graphics.Color
import id.clipmi.android.ui.components.IconCircle
import id.clipmi.android.ui.components.PromptStyleGuideSheet
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import id.clipmi.android.AppContainer
import id.clipmi.android.data.ProjectEntity
import id.clipmi.android.ui.components.ChipRow
import id.clipmi.android.ui.components.ClipMiSheet
import id.clipmi.android.ui.components.ConfirmDialog
import id.clipmi.android.ui.components.Field
import id.clipmi.android.ui.components.FilterPill
import id.clipmi.android.ui.components.GhostButton
import id.clipmi.android.ui.components.LocalNotifier
import id.clipmi.android.ui.components.PrimaryButton
import id.clipmi.android.ui.components.SecondaryButton
import id.clipmi.android.ui.theme.ClipMi
import id.clipmi.android.ui.theme.ClipMiIcons
import id.clipmi.core.analysis.PromptOptions
import id.clipmi.core.analysis.PromptStyle
import id.clipmi.core.analysis.PromptStyles
import kotlinx.coroutines.launch

private val DURATIONS = listOf(null to "Ikut gaya", (15 to 30) to "15–30 dtk", (30 to 60) to "30–60 dtk", (60 to 90) to "60–90 dtk")
private val COUNTS = listOf(null to "Ikut gaya", (3 to 5) to "3–5", (5 to 10) to "5–10", (10 to 15) to "10–15", (15 to 25) to "15–25")

/** Pemilih kecil di kartu analisis: gaya yang dipakai project ini; ketuk untuk mengubah. */
@Composable
fun PromptStyleChip(container: AppContainer, project: ProjectEntity, modifier: Modifier = Modifier) {
    val c = ClipMi.colors
    var open by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf("") }
    LaunchedEffect(project.promptStyle, project.promptOptions) {
        val (style, options) = container.analysis.promptStyle(project)
        label = style.name + if (options != null && !options.isEmpty()) " · diatur" else ""
    }
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.bg).border(1.dp, c.line, RoundedCornerShape(12.dp))
            .clickable(onClickLabel = "Ubah gaya analisis") { open = true }.padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Gaya analisis", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
            Text(label, style = MaterialTheme.typography.titleSmall, color = c.text)
        }
        Icon(ClipMiIcons.Chevron, null, tint = c.textMuted, modifier = Modifier.size(18.dp))
    }
    if (open) PromptStyleSheet(container, project, onDismiss = { open = false })
}

/** Lembar gaya analisis: pilih gaya (bawaan atau buatan sendiri), durasi, jumlah klip, dan fokus topik. */
@Composable
private fun PromptStyleSheet(container: AppContainer, project: ProjectEntity, onDismiss: () -> Unit) {
    val c = ClipMi.colors
    val scope = rememberCoroutineScope()
    val notifier = LocalNotifier.current
    var styles by remember { mutableStateOf<List<PromptStyle>>(PromptStyles.BUILT_IN) }
    var selected by remember { mutableStateOf(PromptStyles.KAJIAN.id) }
    var duration by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var count by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var focus by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<PromptStyle?>(null) }
    var creating by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    var guide by remember { mutableStateOf(false) }
    if (guide) PromptStyleGuideSheet(onDismiss = { guide = false })

    LaunchedEffect(Unit) {
        val (style, options) = container.analysis.promptStyle(project)
        selected = style.id
        duration = options?.minSeconds?.let { it to (options.maxSeconds ?: it) }
        count = options?.minClips?.let { it to (options.maxClips ?: it) }
        focus = options?.focus.orEmpty()
    }
    LaunchedEffect(reload) { styles = container.settings.promptStyles() }

    if (creating || editing != null) {
        CustomStyleSheet(
            editing,
            onDismiss = { creating = false; editing = null },
            onSave = { style ->
                scope.launch {
                    container.settings.saveCustomPromptStyle(style)
                    selected = style.id
                    reload++
                    creating = false
                    editing = null
                }
            },
            onDelete = { id ->
                scope.launch {
                    container.settings.deleteCustomPromptStyle(id)
                    if (selected == id) selected = PromptStyles.KAJIAN.id
                    reload++
                    editing = null
                }
            },
        )
        return
    }

    ClipMiSheet(onDismiss, title = "Gaya analisis", subtitle = "Kriteria AI memilih momen sesuai jenis video. Berlaku untuk analisis otomatis dan lewat chat AI di project ini.") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            styles.forEach { style ->
                val on = style.id == selected
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (on) c.accentSoft else c.bg)
                        .border(1.dp, if (on) c.accent else c.line, RoundedCornerShape(14.dp))
                        .clickable { selected = style.id }.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(style.name, style = MaterialTheme.typography.titleSmall, color = c.text)
                        val desc = style.description.ifBlank { if (style.custom) "Gaya buatan sendiri" else "" }
                        if (desc.isNotBlank()) Text(desc, style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                    }
                    if (style.custom) GhostButton("Ubah", onClick = { editing = style })
                    else if (on) Icon(ClipMiIcons.CircleCheck, null, tint = c.accent, modifier = Modifier.padding(end = 8.dp).size(20.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                GhostButton("+ Buat gaya sendiri", onClick = { creating = true })
                // Panduan membuat gaya sendiri.
                IconCircle(ClipMiIcons.Book, "Panduan gaya sendiri", { guide = true }, size = 36.dp, background = Color.Transparent, border = false, tint = c.textMuted)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Durasi klip", style = MaterialTheme.typography.labelMedium, color = c.textBody)
            ChipRow(gutter = 0.dp) { DURATIONS.forEach { (v, l) -> FilterPill(l, selected = duration == v, onClick = { duration = v }) } }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Jumlah klip", style = MaterialTheme.typography.labelMedium, color = c.textBody)
            ChipRow(gutter = 0.dp) { COUNTS.forEach { (v, l) -> FilterPill(l, selected = count == v, onClick = { count = v }) } }
        }
        Field(focus, { focus = it }, label = "Fokus topik (opsional)", placeholder = "misalnya: sabar, rezeki, keluarga", singleLine = true)

        PrimaryButton("Simpan", modifier = Modifier.fillMaxWidth(), onClick = {
            scope.launch {
                container.analysis.setPromptStyle(
                    project.id, selected,
                    PromptOptions(duration?.first, duration?.second, count?.first, count?.second, focus.trim().ifEmpty { null }),
                )
                notifier.show("Gaya analisis disimpan. Berlaku untuk analisis berikutnya.")
                onDismiss()
            }
        })
    }
}

/** Membuat atau mengubah gaya sendiri: nama, keterangan, dan kriteria (satu per baris). */
@Composable
private fun CustomStyleSheet(style: PromptStyle?, onDismiss: () -> Unit, onSave: (PromptStyle) -> Unit, onDelete: (String) -> Unit) {
    val c = ClipMi.colors
    var name by remember { mutableStateOf(style?.name.orEmpty()) }
    var description by remember { mutableStateOf(style?.description.orEmpty()) }
    // Kriteria pengguna saja (aturan dasar di akhir ditambahkan otomatis).
    val base = PromptStyles.custom("x", "x", emptyList()).criteria.size
    var criteria by remember { mutableStateOf(style?.criteria?.dropLast(base)?.joinToString("\n").orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    ClipMiSheet(
        onDismiss,
        title = if (style == null) "Gaya baru" else "Ubah gaya",
        subtitle = "Tulis kriteria satu per baris. Aturan dasar (durasi, berdiri sendiri, tidak memotong kalimat, jumlah klip, seri) ditambahkan otomatis.",
    ) {
        Field(name, { name = it; error = null }, label = "Nama gaya", placeholder = "misalnya: Khutbah Jumat", singleLine = true, error = error)
        Field(description, { description = it }, label = "Keterangan (opsional)", singleLine = true)
        Field(
            criteria, { criteria = it },
            label = "Kriteria",
            placeholder = "misalnya: Lewati bagian berbahasa Arab",
            minLines = 4, maxLines = 10,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (style != null) SecondaryButton("Hapus", onClick = { confirmDelete = true }, modifier = Modifier.weight(1f), contentColor = c.bad)
            PrimaryButton("Simpan gaya", modifier = Modifier.weight(1f), onClick = {
                val trimmed = name.trim()
                if (trimmed.isEmpty()) { error = "Nama gaya wajib diisi."; return@PrimaryButton }
                val custom = PromptStyles.custom(style?.id ?: "custom-${System.currentTimeMillis()}", trimmed, criteria.lines())
                onSave(custom.copy(description = description.trim()))
            })
        }
    }
    if (confirmDelete && style != null) {
        ConfirmDialog(
            "Hapus gaya?", "Gaya \"${style.name}\" dihapus. Project yang memakainya kembali ke gaya Kajian monolog.", "Hapus", danger = true,
            onConfirm = { confirmDelete = false; onDelete(style.id) },
            onDismiss = { confirmDelete = false },
        )
    }
}
