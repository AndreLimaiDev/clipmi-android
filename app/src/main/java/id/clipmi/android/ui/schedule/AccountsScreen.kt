package id.clipmi.android.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import id.clipmi.android.AppContainer
import id.clipmi.android.data.SocialAccountEntity
import id.clipmi.android.domain.SlotCodec
import id.clipmi.android.ui.components.ClipMiCard
import id.clipmi.android.ui.components.ClipMiSheet
import id.clipmi.android.ui.components.ConfirmDialog
import id.clipmi.android.ui.components.EmptyState
import id.clipmi.android.ui.components.Field
import id.clipmi.android.ui.components.FilterPill
import id.clipmi.android.ui.components.GhostButton
import id.clipmi.android.ui.components.IconCircle
import id.clipmi.android.ui.components.LocalNotifier
import id.clipmi.android.ui.components.PrimaryButton
import id.clipmi.android.ui.components.ScreenGutter
import id.clipmi.android.ui.components.SecondaryButton
import id.clipmi.android.ui.components.SubScreenHeader
import id.clipmi.android.ui.styles.FieldLabel
import id.clipmi.android.ui.styles.hexColor
import id.clipmi.android.ui.theme.ClipMi
import id.clipmi.android.ui.theme.ClipMiIcons
import id.clipmi.core.schedule.Platforms
import id.clipmi.core.schedule.Slot
import id.clipmi.core.schedule.Slots
import kotlinx.coroutines.launch

/** Akun sosmed: daftar akun dengan slot rutinnya, tambah, ubah, dan hapus. */
@Composable
fun AccountsScreen(container: AppContainer, onBack: () -> Unit) {
    val accounts by container.database.schedules().observeAccounts().collectAsState(initial = null)
    var editing by remember { mutableStateOf<SocialAccountEntity?>(null) }

    Column(Modifier.fillMaxSize().background(ClipMi.colors.bg)) {
        SubScreenHeader("Akun sosmed", onBack) {
            IconCircle(ClipMiIcons.Plus, "Tambah akun", { editing = SocialAccountEntity(name = "", platform = "instagram", color = Platforms.nextColor(accounts.orEmpty().map { it.color })) })
        }
        val list = accounts ?: return@Column
        if (list.isEmpty()) {
            EmptyState(
                "Belum ada akun",
                "Tambahkan akun TikTok, Instagram, YouTube, atau Facebook yang Anda kelola. Hanya label untuk jadwal: ClipMI tidak login ke akun mana pun.",
                icon = ClipMiIcons.Users,
                action = { PrimaryButton("Tambah akun", { editing = SocialAccountEntity(name = "", platform = "instagram", color = Platforms.COLORS[0]) }, icon = ClipMiIcons.Plus) },
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = ScreenGutter, end = ScreenGutter, top = 8.dp, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(list, key = { it.id }) { account ->
                    val slots = SlotCodec.decode(account.slots)
                    ClipMiCard(onClick = { editing = account }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            AccountDot(account.color, 14.dp)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(account.name, style = MaterialTheme.typography.titleMedium, color = ClipMi.colors.text)
                                Text(listOfNotNull(Platforms.label(account.platform), account.handle).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = ClipMi.colors.textMuted)
                                Text(
                                    if (slots.isEmpty()) "Tanpa slot rutin" else Slots.summary(slots),
                                    style = MaterialTheme.typography.bodySmall, color = if (slots.isEmpty()) ClipMi.colors.textMuted else ClipMi.colors.textBody,
                                )
                            }
                            Icon(ClipMiIcons.Chevron, contentDescription = null, tint = ClipMi.colors.textMuted, modifier = Modifier.size(20.dp))
                        }
                    }
                }
                item(key = "hint") {
                    Text(
                        "Slot rutin dipakai Isi otomatis di tab Antrean. Akun yang slotnya di jam yang sama mendapat satu jadwal bersama.",
                        style = MaterialTheme.typography.bodySmall, color = ClipMi.colors.textMuted, modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }

    editing?.let { account -> AccountSheet(container, account, onDone = { editing = null }) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountSheet(container: AppContainer, account: SocialAccountEntity, onDone: () -> Unit) {
    val c = ClipMi.colors
    val scope = rememberCoroutineScope()
    val notifier = LocalNotifier.current
    var name by remember { mutableStateOf(account.name) }
    var platform by remember { mutableStateOf(account.platform) }
    var handle by remember { mutableStateOf(account.handle.orEmpty()) }
    var color by remember { mutableStateOf(account.color) }
    var slots by remember { mutableStateOf(SlotCodec.decode(account.slots)) }
    var timeFor by remember { mutableStateOf<Int?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val nameError = when {
        name.isBlank() -> "Nama akun wajib diisi."
        name.trim().length > 60 -> "Nama akun maksimal 60 karakter."
        else -> null
    }
    val slotError = slots.indexOfFirst { it.days.isEmpty() }.takeIf { it >= 0 }

    ClipMiSheet(onDone, title = if (account.id == 0L) "Akun baru" else "Ubah akun") {
        Field(name, { name = it.take(60) }, label = "Nama", placeholder = "Kajian Sunnah", singleLine = true, error = nameError.takeIf { name.isNotEmpty() || account.id != 0L })
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FieldLabel("Platform")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Platforms.LABELS.forEach { (value, label) -> FilterPill(label, platform == value, { platform = value }) }
            }
        }
        Field(handle, { handle = it.take(60) }, label = "Handle (opsional)", placeholder = "@kajian.sunnah", singleLine = true)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FieldLabel("Warna di kalender")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Platforms.COLORS.forEach { swatch ->
                    val selected = swatch.equals(color, ignoreCase = true)
                    Box(
                        Modifier.size(32.dp).clip(CircleShape).background(hexColor(swatch))
                            .border(if (selected) 3.dp else 0.dp, if (selected) c.text else c.line, CircleShape)
                            .clickable(role = Role.RadioButton, onClickLabel = swatch) { color = swatch },
                    )
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FieldLabel("Slot rutin", if (slots.isEmpty()) "untuk Isi otomatis" else Slots.summary(slots))
            slots.forEachIndexed { index, slot ->
                Column(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(c.panel).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton(slot.time.replace(':', '.'), { timeFor = index }, icon = ClipMiIcons.Clock, height = 40.dp)
                        Box(Modifier.weight(1f))
                        IconCircle(ClipMiIcons.Trash, "Hapus slot", { slots = slots.filterIndexed { i, _ -> i != index } }, size = 36.dp, background = c.card, tint = c.bad)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Platforms.DAYS.forEach { (day, label) ->
                            val on = day in slot.days
                            Box(
                                Modifier.weight(1f).size(36.dp).clip(CircleShape).background(if (on) c.accent else c.card)
                                    .border(1.dp, if (on) c.accent else c.line, CircleShape)
                                    .clickable(role = Role.Checkbox, onClickLabel = label) { slots = slots.mapIndexed { i, s -> if (i == index) Slots.toggleDay(s, day) else s } },
                                contentAlignment = Alignment.Center,
                            ) { Text(label, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = if (on) c.onAccent else c.textMuted) }
                        }
                    }
                    if (slotError == index) Text("Pilih minimal satu hari untuk slot ini.", style = MaterialTheme.typography.bodySmall, color = c.bad)
                }
            }
            if (slots.size < 12) SecondaryButton("Tambah slot", { slots = slots + Slots.next(slots) }, icon = ClipMiIcons.Plus, height = 40.dp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (account.id != 0L) GhostButton("Hapus akun", { confirmDelete = true }, danger = true)
            Box(Modifier.weight(1f))
            PrimaryButton("Simpan", enabled = nameError == null && slotError == null, onClick = {
                scope.launch {
                    container.scheduler.saveAccount(account.id, name, platform, handle, color, slots)
                    notifier.show(if (account.id == 0L) "Akun ditambahkan." else "Akun diperbarui.")
                    onDone()
                }
            })
        }
    }

    timeFor?.let { index ->
        val (hour, minute) = Slots.parseTime(slots[index].time) ?: (Slots.DEFAULT_HOUR to 0)
        TimePickerDialog(hour, minute, onDismiss = { timeFor = null }) { h, m ->
            slots = slots.mapIndexed { i, s -> if (i == index) Slot("%02d:%02d".format(h, m), s.days) else s }
            timeFor = null
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            "Hapus akun ini?", "\"${account.name}\" dilepas dari semua jadwal. Jadwal yang jadi tanpa akun dihapus dan klipnya kembali ke antrean.", "Hapus", danger = true,
            onConfirm = {
                confirmDelete = false
                scope.launch {
                    container.scheduler.deleteAccount(account.id)
                    notifier.show("Akun dihapus.")
                    onDone()
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}
